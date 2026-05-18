package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.QueryResult;
import org.pgjava.engine.Session;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 9b Priority 2: RETURNING clause — INSERT / UPDATE / DELETE.
 */
class Phase9bReturningTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("phase9b_ret_" + System.nanoTime());
        sess = db.openSession();
    }

    @AfterEach
    void tearDown() {
        sess.close();
    }

    private QueryResult q(String sql) throws SQLException { return sess.execute(sql); }
    private void exec(String sql) throws SQLException     { sess.execute(sql); }

    // =========================================================================
    // INSERT RETURNING
    // =========================================================================

    @Test void insertReturningId() throws SQLException {
        exec("CREATE TABLE t (id SERIAL, name text)");
        // SERIAL auto-increment is handled separately; test with explicit value
        exec("CREATE TABLE t2 (id int, name text)");
        QueryResult r = q("INSERT INTO t2 VALUES (1, 'alice') RETURNING id");
        assertTrue(r.isQuery(), "RETURNING should produce a result set");
        assertEquals(1, r.rows().size());
        assertEquals("1", r.rows().get(0)[0].toString());
    }

    @Test void insertReturningStar() throws SQLException {
        exec("CREATE TABLE emp (id int, name text, salary int)");
        QueryResult r = q("INSERT INTO emp VALUES (1, 'alice', 100) RETURNING *");
        assertTrue(r.isQuery());
        assertEquals(1, r.rows().size());
        Object[] row = r.rows().get(0);
        assertEquals(3, row.length);
        assertEquals("1",     row[0].toString());
        assertEquals("alice", row[1].toString());
        assertEquals("100",   row[2].toString());
    }

    @Test void insertReturningExpression() throws SQLException {
        exec("CREATE TABLE t (id int, name text)");
        QueryResult r = q("INSERT INTO t VALUES (42, 'bob') RETURNING id, name");
        assertTrue(r.isQuery());
        assertEquals(1, r.rows().size());
        assertEquals("42",  r.rows().get(0)[0].toString());
        assertEquals("bob", r.rows().get(0)[1].toString());
    }

    @Test void insertReturningMultipleRows() throws SQLException {
        exec("CREATE TABLE t (n int)");
        // Multi-row insert with RETURNING
        QueryResult r = q("INSERT INTO t VALUES (1), (2), (3) RETURNING n");
        assertTrue(r.isQuery());
        assertEquals(3, r.rows().size());
    }

    // =========================================================================
    // UPDATE RETURNING
    // =========================================================================

    @Test void updateReturning() throws SQLException {
        exec("CREATE TABLE t (id int, val int)");
        exec("INSERT INTO t VALUES (1, 10), (2, 20)");
        QueryResult r = q("UPDATE t SET val = val + 5 WHERE id = 1 RETURNING id, val");
        assertTrue(r.isQuery());
        assertEquals(1, r.rows().size());
        assertEquals("1",  r.rows().get(0)[0].toString());
        assertEquals("15", r.rows().get(0)[1].toString()); // 10 + 5
    }

    @Test void updateReturningStar() throws SQLException {
        exec("CREATE TABLE t (id int, name text)");
        exec("INSERT INTO t VALUES (1, 'old')");
        QueryResult r = q("UPDATE t SET name = 'new' WHERE id = 1 RETURNING *");
        assertTrue(r.isQuery());
        assertEquals(1, r.rows().size());
        assertEquals("1",   r.rows().get(0)[0].toString());
        assertEquals("new", r.rows().get(0)[1].toString());
    }

    // =========================================================================
    // DELETE RETURNING
    // =========================================================================

    @Test void deleteReturning() throws SQLException {
        exec("CREATE TABLE t (id int, name text)");
        exec("INSERT INTO t VALUES (1, 'alice'), (2, 'bob')");
        QueryResult r = q("DELETE FROM t WHERE id = 1 RETURNING id, name");
        assertTrue(r.isQuery());
        assertEquals(1, r.rows().size());
        assertEquals("1",     r.rows().get(0)[0].toString());
        assertEquals("alice", r.rows().get(0)[1].toString());
        // Verify deletion happened
        QueryResult remaining = q("SELECT COUNT(*) FROM t");
        assertEquals("1", remaining.rows().get(0)[0].toString());
    }

    @Test void deleteReturningStar() throws SQLException {
        exec("CREATE TABLE t (id int, val int)");
        exec("INSERT INTO t VALUES (7, 99)");
        QueryResult r = q("DELETE FROM t RETURNING *");
        assertTrue(r.isQuery());
        assertEquals(1, r.rows().size());
        assertEquals("7",  r.rows().get(0)[0].toString());
        assertEquals("99", r.rows().get(0)[1].toString());
    }

    // =========================================================================
    // RETURNING via JDBC Statement API
    // =========================================================================

    @Test void jdbcGetGeneratedKeys() throws Exception {
        String dbName = "phase9b_gk_" + System.nanoTime();
        // Ensure database is registered before connecting via JDBC
        DatabaseRegistry.getOrCreate(dbName);
        try (Connection conn = java.sql.DriverManager.getConnection(
                "jdbc:pgjava:mem:" + dbName);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE t (id int, name text)");
            stmt.executeUpdate("INSERT INTO t VALUES (5, 'carol') RETURNING id");
            ResultSet rs = stmt.getGeneratedKeys();
            assertTrue(rs.next(), "getGeneratedKeys should return a row");
            assertEquals(5, rs.getInt(1));
            assertFalse(rs.next());
        }
    }
}
