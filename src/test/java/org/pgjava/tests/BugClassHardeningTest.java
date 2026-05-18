package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.QueryResult;
import org.pgjava.engine.Session;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests targeting the five identified bug classes:
 * 1. Scope not propagated through recursion
 * 2. Type representation mismatch across boundaries
 * 3. Composite/structural types in generic ops
 * 4. Silent stub implementations (covered in JdbcHardeningTest)
 * 5. Wrong test expectations (meta — not testable directly)
 */
class BugClassHardeningTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("bugclass_" + System.nanoTime());
        sess = db.openSession();
    }

    @AfterEach
    void tearDown() { sess.close(); }

    private QueryResult q(String sql) throws SQLException { return sess.execute(sql); }
    private void exec(String sql)     throws SQLException { sess.execute(sql); }

    // ── Bug class 2: temporal coercion in UPDATE ─────────────────────────────

    @Test void updateTemporalCoercion() throws SQLException {
        exec("CREATE TABLE ts_test (id int, ts timestamp)");
        exec("INSERT INTO ts_test VALUES (1, '2024-01-15 10:00:00')");
        // now() returns OffsetDateTime; column is TIMESTAMP (LocalDateTime)
        exec("UPDATE ts_test SET ts = now() WHERE id = 1");
        QueryResult r = q("SELECT ts FROM ts_test WHERE id = 1");
        assertNotNull(r.rows().get(0)[0]);
        // Verify it stored as LocalDateTime, not OffsetDateTime
        Object val = r.rows().get(0)[0];
        assertFalse(val instanceof java.time.OffsetDateTime,
                "UPDATE should coerce OffsetDateTime to LocalDateTime for TIMESTAMP column");
    }

    @Test void updateStringCoercion() throws SQLException {
        exec("CREATE TABLE int_test (id int, val int)");
        exec("INSERT INTO int_test VALUES (1, 10)");
        // String '20' should be coerced to int for an int column
        exec("UPDATE int_test SET val = '20' WHERE id = 1");
        QueryResult r = q("SELECT val FROM int_test WHERE id = 1");
        assertEquals(20, ((Number) r.rows().get(0)[0]).intValue());
    }

    // ── Bug class 3: composite types in generic ops ──────────────────────────

    @Test void rowNotEquals() throws SQLException {
        exec("CREATE TABLE r1 (a int, b int)");
        exec("INSERT INTO r1 VALUES (1, 2), (1, 2), (3, 4)");
        // (a, b) <> (1, 2) should exclude the first two rows
        QueryResult r = q("SELECT * FROM r1 WHERE (a, b) <> (1, 2)");
        assertEquals(1, r.rows().size());
        assertEquals(3, ((Number) r.rows().get(0)[0]).intValue());
    }

    @Test void nullifWithEqualValues() throws SQLException {
        // NULLIF should return NULL when args are equal
        QueryResult r = q("SELECT NULLIF(42, 42)");
        assertNull(r.rows().get(0)[0]);
    }

    @Test void nullifWithDifferentValues() throws SQLException {
        QueryResult r = q("SELECT NULLIF(42, 99)");
        assertEquals(42, ((Number) r.rows().get(0)[0]).intValue());
    }

    @Test void nullifWithNumericPromotion() throws SQLException {
        // int vs bigint — should still compare as equal
        QueryResult r = q("SELECT NULLIF(42, 42::bigint)");
        assertNull(r.rows().get(0)[0]);
    }

    @Test void multiColumnInWithNotEquals() throws SQLException {
        exec("CREATE TABLE mc (a int, b text)");
        exec("INSERT INTO mc VALUES (1, 'x'), (2, 'y'), (3, 'z')");
        // Multi-column NOT IN
        QueryResult r = q("SELECT a FROM mc WHERE (a, b) NOT IN ((1, 'x'), (2, 'y')) ORDER BY a");
        assertEquals(1, r.rows().size());
        assertEquals(3, ((Number) r.rows().get(0)[0]).intValue());
    }

    @Test void isDistinctFromWithNulls() throws SQLException {
        QueryResult r1 = q("SELECT 1 IS DISTINCT FROM 1");
        assertEquals(false, r1.rows().get(0)[0]);

        QueryResult r2 = q("SELECT 1 IS DISTINCT FROM 2");
        assertEquals(true, r2.rows().get(0)[0]);

        QueryResult r3 = q("SELECT 1 IS DISTINCT FROM NULL");
        assertEquals(true, r3.rows().get(0)[0]);

        QueryResult r4 = q("SELECT NULL IS DISTINCT FROM NULL");
        assertEquals(false, r4.rows().get(0)[0]);
    }

    @Test void isNotDistinctFromWithNulls() throws SQLException {
        QueryResult r1 = q("SELECT 1 IS NOT DISTINCT FROM 1");
        assertEquals(true, r1.rows().get(0)[0]);

        QueryResult r2 = q("SELECT NULL IS NOT DISTINCT FROM NULL");
        assertEquals(true, r2.rows().get(0)[0]);

        QueryResult r3 = q("SELECT 1 IS NOT DISTINCT FROM NULL");
        assertEquals(false, r3.rows().get(0)[0]);
    }

    // ── Bug class 2: JDBC temporal coercion ──────────────────────────────────

    @Test void jdbcUpdateTemporalViaStatement() throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:pgjava:mem:jdbc_tc_" + System.nanoTime())) {
            Statement st = conn.createStatement();
            st.execute("CREATE TABLE jts (id int, ts timestamp)");
            st.execute("INSERT INTO jts VALUES (1, '2024-01-01 00:00:00')");
            st.execute("UPDATE jts SET ts = now() WHERE id = 1");
            ResultSet rs = st.executeQuery("SELECT ts FROM jts WHERE id = 1");
            assertTrue(rs.next());
            // Should be retrievable as Timestamp without error
            Timestamp ts = rs.getTimestamp(1);
            assertNotNull(ts);
            st.close();
        }
    }

    // ── Scope propagation: CTE visibility ────────────────────────────────────

    @Test void cteInSubquery() throws SQLException {
        // Non-recursive CTE referenced inside a subquery
        exec("CREATE TABLE scope_t (id int, val int)");
        exec("INSERT INTO scope_t VALUES (1, 10), (2, 20), (3, 30)");
        QueryResult r = q("""
            WITH cte AS (SELECT 2 AS target_id)
            SELECT val FROM scope_t
            WHERE id = (SELECT target_id FROM cte)
            """);
        assertEquals(1, r.rows().size());
        assertEquals(20, ((Number) r.rows().get(0)[0]).intValue());
    }

    @Test void cteInUnionAllBranches() throws SQLException {
        // Both UNION ALL branches should see CTEs (was broken before)
        QueryResult r = q("""
            WITH vals AS (SELECT 1 AS n UNION ALL SELECT 2)
            SELECT n FROM vals WHERE n = 1
            UNION ALL
            SELECT n FROM vals WHERE n = 2
            ORDER BY n
            """);
        assertEquals(2, r.rows().size());
    }

    // ── Bug class 3: row comparison operators ────────────────────────────────

    @Test void rowLessThan() throws SQLException {
        exec("CREATE TABLE rl (a int, b int)");
        exec("INSERT INTO rl VALUES (1, 2), (1, 3), (2, 1)");
        QueryResult r = q("SELECT * FROM rl WHERE (a, b) < (1, 3) ORDER BY a, b");
        assertEquals(1, r.rows().size());
        assertEquals(1, ((Number) r.rows().get(0)[0]).intValue());
        assertEquals(2, ((Number) r.rows().get(0)[1]).intValue());
    }

    @Test void rowGreaterThanOrEqual() throws SQLException {
        exec("CREATE TABLE rge (a int, b int)");
        exec("INSERT INTO rge VALUES (1, 2), (1, 3), (2, 1)");
        QueryResult r = q("SELECT * FROM rge WHERE (a, b) >= (1, 3) ORDER BY a, b");
        assertEquals(2, r.rows().size()); // (1,3) and (2,1)
    }

    @Test void rowBetween() throws SQLException {
        exec("CREATE TABLE rb (a int, b int)");
        exec("INSERT INTO rb VALUES (1, 1), (1, 5), (2, 3), (3, 1)");
        QueryResult r = q("SELECT * FROM rb WHERE (a, b) BETWEEN (1, 2) AND (2, 5) ORDER BY a, b");
        assertEquals(2, r.rows().size()); // (1,5) and (2,3)
    }

    // ── Bug class 2: ON CONFLICT DO UPDATE coercion ──────────────────────────

    @Test void onConflictDoUpdateTemporalCoercion() throws SQLException {
        exec("CREATE TABLE upsert_ts (id int PRIMARY KEY, ts timestamp)");
        exec("INSERT INTO upsert_ts VALUES (1, '2024-01-01 00:00:00')");
        // now() returns OffsetDateTime; column is TIMESTAMP
        exec("INSERT INTO upsert_ts VALUES (1, '2024-06-01 00:00:00') ON CONFLICT (id) DO UPDATE SET ts = now()");
        QueryResult r = q("SELECT ts FROM upsert_ts WHERE id = 1");
        Object val = r.rows().get(0)[0];
        assertFalse(val instanceof java.time.OffsetDateTime,
                "ON CONFLICT DO UPDATE should coerce OffsetDateTime to LocalDateTime");
    }

    // ── Bug class 1: CTE in DML ──────────────────────────────────────────────

    @Test void cteInUpdateFrom() throws SQLException {
        exec("CREATE TABLE cu (id int, val int)");
        exec("INSERT INTO cu VALUES (1, 10), (2, 20)");
        exec("""
            WITH new_vals AS (SELECT 1 AS id, 99 AS val)
            UPDATE cu SET val = new_vals.val FROM new_vals WHERE cu.id = new_vals.id
            """);
        QueryResult r = q("SELECT val FROM cu WHERE id = 1");
        assertEquals(99, ((Number) r.rows().get(0)[0]).intValue());
    }

    @Test void cteInDeleteUsing() throws SQLException {
        exec("CREATE TABLE cd (id int, val int)");
        exec("INSERT INTO cd VALUES (1, 10), (2, 20), (3, 30)");
        exec("""
            WITH to_delete AS (SELECT 2 AS id)
            DELETE FROM cd USING to_delete WHERE cd.id = to_delete.id
            """);
        QueryResult r = q("SELECT COUNT(*) FROM cd");
        assertEquals(2L, ((Number) r.rows().get(0)[0]).longValue());
    }

    @Test void cteInInsertSelect() throws SQLException {
        exec("CREATE TABLE ci_src (id int)");
        exec("CREATE TABLE ci_dst (id int)");
        exec("INSERT INTO ci_src VALUES (1), (2), (3)");
        exec("""
            WITH subset AS (SELECT id FROM ci_src WHERE id <= 2)
            INSERT INTO ci_dst SELECT * FROM subset
            """);
        QueryResult r = q("SELECT COUNT(*) FROM ci_dst");
        assertEquals(2L, ((Number) r.rows().get(0)[0]).longValue());
    }

    // ── NOT LIKE / NOT ILIKE ────────────────────────────────────────────────

    @Test void notLikeBasic() throws SQLException {
        QueryResult r = q("SELECT 'hello' NOT LIKE 'h%' AS result");
        assertEquals(1, r.rows().size());
        assertEquals(false, r.rows().get(0)[0]);
    }

    @Test void notIlikeBasic() throws SQLException {
        QueryResult r = q("SELECT 'Hello' NOT ILIKE 'hello' AS result");
        assertEquals(1, r.rows().size());
        assertEquals(false, r.rows().get(0)[0]);
    }

    // ── Bug class 6: cross-type temporal comparison ─────────────────────────

    @Test void timestampLessThanNow() throws SQLException {
        // now() returns OffsetDateTime, column stores LocalDateTime
        // comparing them must not throw ClassCastException
        exec("CREATE TABLE tc (id int, ts timestamp)");
        exec("INSERT INTO tc VALUES (1, '2024-01-01 00:00:00')");
        QueryResult r = q("SELECT * FROM tc WHERE ts < now()");
        assertEquals(1, r.rows().size());
    }

    @Test void timestampGreaterThanLiteral() throws SQLException {
        exec("CREATE TABLE tc2 (id int, ts timestamptz)");
        exec("INSERT INTO tc2 VALUES (1, '2024-06-01 12:00:00+00')");
        exec("INSERT INTO tc2 VALUES (2, '2020-01-01 00:00:00+00')");
        QueryResult r = q("SELECT id FROM tc2 WHERE ts > '2023-01-01 00:00:00' ORDER BY id");
        assertEquals(1, r.rows().size());
        assertEquals(1, ((Number) r.rows().get(0)[0]).intValue());
    }

    @Test void orderByTimestampMixed() throws SQLException {
        // Sort operator must handle mixed temporal types too
        exec("CREATE TABLE tc3 (id int, ts timestamp)");
        exec("INSERT INTO tc3 VALUES (1, '2024-03-01 00:00:00')");
        exec("INSERT INTO tc3 VALUES (2, '2024-01-01 00:00:00')");
        QueryResult r = q("SELECT id FROM tc3 ORDER BY ts");
        assertEquals(2, r.rows().size());
        assertEquals(2, ((Number) r.rows().get(0)[0]).intValue());
        assertEquals(1, ((Number) r.rows().get(1)[0]).intValue());
    }

    // ── Interval arithmetic ─────────────────────────────────────────────────

    @Test void timestampPlusInterval() throws SQLException {
        exec("CREATE TABLE ivl (id int, ts timestamp)");
        exec("INSERT INTO ivl VALUES (1, '2024-01-15 10:00:00')");
        QueryResult r = q("SELECT ts + interval '1 day' FROM ivl WHERE id = 1");
        assertEquals(1, r.rows().size());
        Object val = r.rows().get(0)[0];
        assertNotNull(val);
        assertTrue(val.toString().contains("2024-01-16"), "Expected next day, got: " + val);
    }

    @Test void timestampMinusInterval() throws SQLException {
        exec("CREATE TABLE ivl2 (id int, ts timestamp)");
        exec("INSERT INTO ivl2 VALUES (1, '2024-03-01 12:00:00')");
        QueryResult r = q("SELECT ts - interval '2 hours' FROM ivl2 WHERE id = 1");
        assertEquals(1, r.rows().size());
        assertTrue(r.rows().get(0)[0].toString().contains("10:00"), "Expected 10:00, got: " + r.rows().get(0)[0]);
    }

    @Test void datePlusInterval() throws SQLException {
        QueryResult r = q("SELECT DATE '2024-01-01' + interval '1 month'");
        assertEquals(1, r.rows().size());
        assertTrue(r.rows().get(0)[0].toString().contains("2024-02"), "Expected Feb, got: " + r.rows().get(0)[0]);
    }

    @Test void intervalPlusInterval() throws SQLException {
        QueryResult r = q("SELECT interval '1 hour' + interval '30 minutes'");
        assertEquals(1, r.rows().size());
        String val = r.rows().get(0)[0].toString();
        assertTrue(val.contains("01:30") || val.contains("1:30"), "Expected 1:30, got: " + val);
    }

    @Test void timestampMinusTimestamp() throws SQLException {
        exec("CREATE TABLE ivl3 (a timestamp, b timestamp)");
        exec("INSERT INTO ivl3 VALUES ('2024-01-15 10:00:00', '2024-01-14 08:00:00')");
        QueryResult r = q("SELECT a - b FROM ivl3");
        assertEquals(1, r.rows().size());
        // Should be an interval of ~26 hours
        assertNotNull(r.rows().get(0)[0]);
    }
}
