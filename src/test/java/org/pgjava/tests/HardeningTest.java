package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.QueryResult;
import org.pgjava.engine.Session;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hardening tests: edge cases across window functions, recursive CTEs,
 * correlated subqueries, ON CONFLICT, RETURNING, LATERAL, UPDATE...FROM,
 * DELETE...USING, DISTINCT ON, CREATE TABLE AS, and type coercions.
 */
class HardeningTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("harden_" + System.nanoTime());
        sess = db.openSession();
    }

    @AfterEach
    void tearDown() { sess.close(); }

    private QueryResult q(String sql) throws SQLException { return sess.execute(sql); }
    private void exec(String sql)     throws SQLException { sess.execute(sql); }

    // ── Window: percent_rank and cume_dist ────────────────────────────────────

    @Test void percentRank() throws SQLException {
        exec("CREATE TABLE t (val int)");
        exec("INSERT INTO t VALUES (10),(20),(20),(30)");
        QueryResult r = q("SELECT val, PERCENT_RANK() OVER (ORDER BY val) AS pr FROM t ORDER BY val");
        assertEquals(4, r.rows().size());
        // percent_rank = (rank - 1) / (N - 1)
        // val 10 → rank 1, pr = 0/3 = 0.0
        // val 20 → rank 2, pr = 1/3 ≈ 0.333
        // val 20 → rank 2, pr = 1/3 ≈ 0.333
        // val 30 → rank 4, pr = 3/3 = 1.0
        assertEquals(0.0, ((Number) r.rows().get(0)[1]).doubleValue(), 1e-9);
        double pr20 = ((Number) r.rows().get(1)[1]).doubleValue();
        assertTrue(Math.abs(pr20 - 1.0/3) < 1e-9, "percent_rank for 20 should be ~0.333");
        assertEquals(1.0, ((Number) r.rows().get(3)[1]).doubleValue(), 1e-9);
    }

    @Test void cumeDist() throws SQLException {
        exec("CREATE TABLE t (val int)");
        exec("INSERT INTO t VALUES (10),(20),(20),(30)");
        QueryResult r = q("SELECT val, CUME_DIST() OVER (ORDER BY val) AS cd FROM t ORDER BY val");
        assertEquals(4, r.rows().size());
        // cume_dist = (number of rows with value <= current) / N
        // 10: 1/4 = 0.25, 20: 3/4 = 0.75, 20: 3/4, 30: 4/4 = 1.0
        assertEquals(0.25, ((Number) r.rows().get(0)[1]).doubleValue(), 1e-9);
        assertEquals(0.75, ((Number) r.rows().get(1)[1]).doubleValue(), 1e-9);
        assertEquals(0.75, ((Number) r.rows().get(2)[1]).doubleValue(), 1e-9);
        assertEquals(1.0,  ((Number) r.rows().get(3)[1]).doubleValue(), 1e-9);
    }

    // ── Window: nth_value ─────────────────────────────────────────────────────

    @Test void nthValue() throws SQLException {
        exec("CREATE TABLE t (val int)");
        exec("INSERT INTO t VALUES (10),(20),(30),(40)");
        QueryResult r = q("""
            SELECT val, NTH_VALUE(val, 2) OVER (ORDER BY val
                ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS nv
            FROM t ORDER BY val
            """);
        assertEquals(4, r.rows().size());
        for (Object[] row : r.rows()) {
            assertEquals(20, ((Number) row[1]).intValue(), "nth_value(val,2) should be 20 for all rows");
        }
    }

    // ── Window: last_value with proper frame ──────────────────────────────────

    @Test void lastValueFullFrame() throws SQLException {
        exec("CREATE TABLE t (val int)");
        exec("INSERT INTO t VALUES (10),(20),(30)");
        QueryResult r = q("""
            SELECT val, LAST_VALUE(val) OVER (ORDER BY val
                ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS lv
            FROM t ORDER BY val
            """);
        assertEquals(3, r.rows().size());
        for (Object[] row : r.rows()) {
            assertEquals(30, ((Number) row[1]).intValue(), "last_value with full frame should be 30");
        }
    }

    // ── Window: multiple window functions in one query ────────────────────────

    @Test void multipleWindowFunctions() throws SQLException {
        exec("CREATE TABLE t (dept text, salary int)");
        exec("INSERT INTO t VALUES ('a',100),('a',200),('a',150),('b',300)");
        QueryResult r = q("""
            SELECT dept, salary,
                ROW_NUMBER() OVER (PARTITION BY dept ORDER BY salary) AS rn,
                RANK()       OVER (PARTITION BY dept ORDER BY salary) AS rk,
                SUM(salary)  OVER (PARTITION BY dept) AS total,
                LAG(salary)  OVER (PARTITION BY dept ORDER BY salary) AS prev
            FROM t ORDER BY dept, salary
            """);
        assertEquals(4, r.rows().size());
        // dept a, salary 100: rn=1, rk=1, total=450, prev=null
        Object[] r0 = r.rows().get(0);
        assertEquals(1, ((Number) r0[2]).intValue());   // rn
        assertEquals(1, ((Number) r0[3]).intValue());   // rk
        assertEquals(450, ((Number) r0[4]).intValue()); // total
        assertNull(r0[5]);                               // prev (no lag)

        // dept a, salary 150: rn=2, prev=100
        Object[] r1 = r.rows().get(1);
        assertEquals(2, ((Number) r1[2]).intValue());
        assertEquals(100, ((Number) r1[5]).intValue());
    }

    // ── Correlated subquery: scalar in SELECT ─────────────────────────────────

    @Test void correlatedScalarSubquery() throws SQLException {
        exec("CREATE TABLE parent (id int PRIMARY KEY, name text)");
        exec("CREATE TABLE child (id int, pid int, val int)");
        exec("INSERT INTO parent VALUES (1,'a'),(2,'b')");
        exec("INSERT INTO child VALUES (10,1,5),(11,1,3),(12,2,7)");
        QueryResult r = q("""
            SELECT p.name, (SELECT COUNT(*) FROM child c WHERE c.pid = p.id) AS cnt
            FROM parent p ORDER BY p.name
            """);
        assertEquals(2, r.rows().size());
        assertEquals("a", r.rows().get(0)[0]);
        assertEquals(2L, ((Number) r.rows().get(0)[1]).longValue());
        assertEquals("b", r.rows().get(1)[0]);
        assertEquals(1L, ((Number) r.rows().get(1)[1]).longValue());
    }

    // ── Correlated: EXISTS / NOT EXISTS ───────────────────────────────────────

    @Test void existsSubquery() throws SQLException {
        exec("CREATE TABLE orders (id int, customer_id int)");
        exec("CREATE TABLE customers (id int, name text)");
        exec("INSERT INTO customers VALUES (1,'alice'),(2,'bob'),(3,'carol')");
        exec("INSERT INTO orders VALUES (100,1),(101,1),(102,3)");
        QueryResult r = q("""
            SELECT name FROM customers c
            WHERE EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.id)
            ORDER BY name
            """);
        assertEquals(2, r.rows().size());
        assertEquals("alice", r.rows().get(0)[0]);
        assertEquals("carol", r.rows().get(1)[0]);
    }

    @Test void notExistsSubquery() throws SQLException {
        exec("CREATE TABLE orders (id int, customer_id int)");
        exec("CREATE TABLE customers (id int, name text)");
        exec("INSERT INTO customers VALUES (1,'alice'),(2,'bob'),(3,'carol')");
        exec("INSERT INTO orders VALUES (100,1),(101,3)");
        QueryResult r = q("""
            SELECT name FROM customers c
            WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.id)
            """);
        assertEquals(1, r.rows().size());
        assertEquals("bob", r.rows().get(0)[0]);
    }

    // ── IN (subquery) with NULLs ──────────────────────────────────────────────

    @Test void inSubqueryBasic() throws SQLException {
        exec("CREATE TABLE t (id int)");
        exec("CREATE TABLE s (ref_id int)");
        exec("INSERT INTO t VALUES (1),(2),(3)");
        exec("INSERT INTO s VALUES (1),(3)");
        QueryResult r = q("SELECT id FROM t WHERE id IN (SELECT ref_id FROM s) ORDER BY id");
        assertEquals(2, r.rows().size());
        assertEquals(1, ((Number) r.rows().get(0)[0]).intValue());
        assertEquals(3, ((Number) r.rows().get(1)[0]).intValue());
    }

    @Test void notInSubqueryBasic() throws SQLException {
        exec("CREATE TABLE t (id int)");
        exec("CREATE TABLE s (ref_id int)");
        exec("INSERT INTO t VALUES (1),(2),(3)");
        exec("INSERT INTO s VALUES (1),(3)");
        QueryResult r = q("SELECT id FROM t WHERE id NOT IN (SELECT ref_id FROM s) ORDER BY id");
        assertEquals(1, r.rows().size());
        assertEquals(2, ((Number) r.rows().get(0)[0]).intValue());
    }

    // ── ANY / ALL ─────────────────────────────────────────────────────────────

    @Test void anySubquery() throws SQLException {
        exec("CREATE TABLE t (val int)");
        exec("INSERT INTO t VALUES (10),(20),(30)");
        QueryResult r = q("SELECT val FROM t WHERE val > ANY (SELECT 15) ORDER BY val");
        assertEquals(2, r.rows().size());
        assertEquals(20, ((Number) r.rows().get(0)[0]).intValue());
        assertEquals(30, ((Number) r.rows().get(1)[0]).intValue());
    }

    @Test void allSubquery() throws SQLException {
        exec("CREATE TABLE t (val int)");
        exec("CREATE TABLE s (threshold int)");
        exec("INSERT INTO t VALUES (10),(20),(30)");
        exec("INSERT INTO s VALUES (5),(15)");
        QueryResult r = q("SELECT val FROM t WHERE val > ALL (SELECT threshold FROM s) ORDER BY val");
        assertEquals(2, r.rows().size());
        assertEquals(20, ((Number) r.rows().get(0)[0]).intValue());
        assertEquals(30, ((Number) r.rows().get(1)[0]).intValue());
    }

    // ── Scalar subquery: >1 row error ─────────────────────────────────────────

    @Test void scalarSubqueryTooManyRows() throws SQLException {
        exec("CREATE TABLE t (val int)");
        exec("INSERT INTO t VALUES (1),(2)");
        SQLException ex = assertThrows(SQLException.class, () ->
            q("SELECT (SELECT val FROM t)")
        );
        assertEquals("21000", ex.getSQLState());
    }

    // ── ON CONFLICT: DO NOTHING with multiple rows ────────────────────────────

    @Test void onConflictDoNothingMultiple() throws SQLException {
        exec("CREATE TABLE t (id int PRIMARY KEY, name text)");
        exec("INSERT INTO t VALUES (1,'orig')");
        exec("INSERT INTO t VALUES (1,'dup1'),(2,'new'),(1,'dup2') ON CONFLICT DO NOTHING");
        QueryResult r = q("SELECT id, name FROM t ORDER BY id");
        assertEquals(2, r.rows().size());
        assertEquals("orig", r.rows().get(0)[1]); // unchanged
        assertEquals("new",  r.rows().get(1)[1]); // inserted
    }

    // ── ON CONFLICT DO UPDATE with WHERE ──────────────────────────────────────

    @Test void onConflictDoUpdateWithWhere() throws SQLException {
        exec("CREATE TABLE t (id int PRIMARY KEY, val int)");
        exec("INSERT INTO t VALUES (1, 10)");
        // Only update if excluded value is greater
        exec("""
            INSERT INTO t VALUES (1, 5)
            ON CONFLICT (id) DO UPDATE SET val = EXCLUDED.val
            WHERE EXCLUDED.val > t.val
            """);
        QueryResult r = q("SELECT val FROM t WHERE id = 1");
        assertEquals(10, ((Number) r.rows().get(0)[0]).intValue(), "WHERE should block the update");

        exec("""
            INSERT INTO t VALUES (1, 20)
            ON CONFLICT (id) DO UPDATE SET val = EXCLUDED.val
            WHERE EXCLUDED.val > t.val
            """);
        r = q("SELECT val FROM t WHERE id = 1");
        assertEquals(20, ((Number) r.rows().get(0)[0]).intValue(), "WHERE should allow the update");
    }

    // ── ON CONFLICT DO UPDATE with RETURNING ──────────────────────────────────

    @Test void onConflictDoUpdateReturning() throws SQLException {
        exec("CREATE TABLE t (id int PRIMARY KEY, val text)");
        exec("INSERT INTO t VALUES (1,'old')");
        QueryResult r = q("""
            INSERT INTO t VALUES (1,'new')
            ON CONFLICT (id) DO UPDATE SET val = EXCLUDED.val
            RETURNING id, val
            """);
        assertEquals(1, r.rows().size());
        assertEquals(1, ((Number) r.rows().get(0)[0]).intValue());
        assertEquals("new", r.rows().get(0)[1]);
    }

    // ── RETURNING: DELETE ─────────────────────────────────────────────────────

    @Test void deleteReturning() throws SQLException {
        exec("CREATE TABLE t (id int, name text)");
        exec("INSERT INTO t VALUES (1,'a'),(2,'b'),(3,'c')");
        QueryResult r = q("DELETE FROM t WHERE id > 1 RETURNING id, name");
        assertEquals(2, r.rows().size());
    }

    // ── RETURNING: UPDATE with expression ─────────────────────────────────────

    @Test void updateReturningExpression() throws SQLException {
        exec("CREATE TABLE t (id int, val int)");
        exec("INSERT INTO t VALUES (1,10),(2,20)");
        QueryResult r = q("UPDATE t SET val = val * 2 RETURNING id, val");
        assertEquals(2, r.rows().size());
        // Check that RETURNING shows the NEW values
        for (Object[] row : r.rows()) {
            int id  = ((Number) row[0]).intValue();
            int val = ((Number) row[1]).intValue();
            if (id == 1) assertEquals(20, val);
            if (id == 2) assertEquals(40, val);
        }
    }

    // ── UPDATE ... FROM ───────────────────────────────────────────────────────

    @Test void updateFrom() throws SQLException {
        exec("CREATE TABLE t (id int, val text)");
        exec("CREATE TABLE src (id int, new_val text)");
        exec("INSERT INTO t VALUES (1,'old1'),(2,'old2')");
        exec("INSERT INTO src VALUES (1,'new1')");
        exec("UPDATE t SET val = src.new_val FROM src WHERE t.id = src.id");
        QueryResult r = q("SELECT id, val FROM t ORDER BY id");
        assertEquals(2, r.rows().size());
        assertEquals("new1", r.rows().get(0)[1]);
        assertEquals("old2", r.rows().get(1)[1]);
    }

    // ── DELETE ... USING ──────────────────────────────────────────────────────

    @Test void deleteUsing() throws SQLException {
        exec("CREATE TABLE t (id int, val text)");
        exec("CREATE TABLE blacklist (id int)");
        exec("INSERT INTO t VALUES (1,'a'),(2,'b'),(3,'c')");
        exec("INSERT INTO blacklist VALUES (1),(3)");
        exec("DELETE FROM t USING blacklist bl WHERE t.id = bl.id");
        QueryResult r = q("SELECT id FROM t");
        assertEquals(1, r.rows().size());
        assertEquals(2, ((Number) r.rows().get(0)[0]).intValue());
    }

    // ── CREATE TABLE AS ───────────────────────────────────────────────────────

    @Test void createTableAs() throws SQLException {
        exec("CREATE TABLE src (id int, val text)");
        exec("INSERT INTO src VALUES (1,'a'),(2,'b')");
        exec("CREATE TABLE dst AS SELECT id, val FROM src WHERE id = 1");
        QueryResult r = q("SELECT * FROM dst");
        assertEquals(1, r.rows().size());
        assertEquals(1, ((Number) r.rows().get(0)[0]).intValue());
    }

    // ── INSERT ... DEFAULT VALUES ─────────────────────────────────────────────

    @Test void insertDefaultValues() throws SQLException {
        exec("CREATE TABLE t (id serial PRIMARY KEY, name text DEFAULT 'unnamed')");
        exec("INSERT INTO t DEFAULT VALUES");
        QueryResult r = q("SELECT id, name FROM t");
        assertEquals(1, r.rows().size());
        assertNotNull(r.rows().get(0)[0]);
        assertEquals("unnamed", r.rows().get(0)[1]);
    }

    // ── Recursive CTE: cycle guard (limit) ────────────────────────────────────

    @Test void recursiveCteWithLimit() throws SQLException {
        // Generate numbers 1..100 via recursive CTE
        QueryResult r = q("""
            WITH RECURSIVE nums(n) AS (
                SELECT 1
                UNION ALL
                SELECT n + 1 FROM nums WHERE n < 100
            )
            SELECT COUNT(*) FROM nums
            """);
        assertEquals(100L, ((Number) r.rows().get(0)[0]).longValue());
    }

    // ── LATERAL with empty right side ─────────────────────────────────────────

    @Test void lateralNoMatch() throws SQLException {
        exec("CREATE TABLE parent (id int)");
        exec("CREATE TABLE child (pid int, val int)");
        exec("INSERT INTO parent VALUES (1),(2)");
        exec("INSERT INTO child VALUES (1, 10)");
        // parent id=2 has no children; LATERAL inner join should exclude it
        QueryResult r = q("""
            SELECT p.id, c.val
            FROM parent p,
            LATERAL (SELECT val FROM child WHERE pid = p.id) AS c
            ORDER BY p.id
            """);
        assertEquals(1, r.rows().size());
        assertEquals(1, ((Number) r.rows().get(0)[0]).intValue());
    }

    // ── ORDER BY alias ────────────────────────────────────────────────────────

    @Test void orderByAlias() throws SQLException {
        exec("CREATE TABLE t (a int, b int)");
        exec("INSERT INTO t VALUES (1,10),(2,5),(3,8)");
        QueryResult r = q("SELECT a, b, a + b AS total FROM t ORDER BY total DESC");
        assertEquals(3, r.rows().size());
        // total: 11(1+10), 11(3+8), 7(2+5) — 11 ties, so check first is 11
        int first = ((Number) r.rows().get(0)[2]).intValue();
        assertEquals(11, first);
        int last = ((Number) r.rows().get(2)[2]).intValue();
        assertEquals(7, last);
    }

    // ── ORDER BY positional ───────────────────────────────────────────────────

    @Test void orderByPosition() throws SQLException {
        exec("CREATE TABLE t (name text, age int)");
        exec("INSERT INTO t VALUES ('alice',30),('bob',25),('carol',35)");
        QueryResult r = q("SELECT name, age FROM t ORDER BY 2 DESC");
        assertEquals(3, r.rows().size());
        assertEquals("carol", r.rows().get(0)[0]); // age 35
        assertEquals("alice", r.rows().get(1)[0]); // age 30
        assertEquals("bob",   r.rows().get(2)[0]); // age 25
    }

    // ── DISTINCT ON with no matching rows ─────────────────────────────────────

    @Test void distinctOnEmpty() throws SQLException {
        exec("CREATE TABLE t (dept text, val int)");
        QueryResult r = q("SELECT DISTINCT ON (dept) dept, val FROM t ORDER BY dept, val");
        assertEquals(0, r.rows().size());
    }

    // ── Multiple CTEs (non-recursive) ─────────────────────────────────────────

    @Test void multipleCtes() throws SQLException {
        exec("CREATE TABLE t (id int, val int)");
        exec("INSERT INTO t VALUES (1,10),(2,20),(3,30)");
        QueryResult r = q("""
            WITH
                low AS (SELECT id, val FROM t WHERE val < 25),
                high AS (SELECT id, val FROM t WHERE val >= 25)
            SELECT 'low' AS src, COUNT(*) AS cnt FROM low
            UNION ALL
            SELECT 'high', COUNT(*) FROM high
            ORDER BY src
            """);
        assertEquals(2, r.rows().size());
        assertEquals("high", r.rows().get(0)[0]);
        assertEquals(1L, ((Number) r.rows().get(0)[1]).longValue());
        assertEquals("low", r.rows().get(1)[0]);
        assertEquals(2L, ((Number) r.rows().get(1)[1]).longValue());
    }

    // ── Nested subquery in FROM ───────────────────────────────────────────────

    @Test void nestedSubqueryInFrom() throws SQLException {
        exec("CREATE TABLE t (val int)");
        exec("INSERT INTO t VALUES (1),(2),(3),(4),(5)");
        QueryResult r = q("""
            SELECT avg_val FROM (
                SELECT AVG(val) AS avg_val FROM t
            ) sub
            """);
        assertEquals(1, r.rows().size());
        assertEquals(3.0, ((Number) r.rows().get(0)[0]).doubleValue(), 1e-9);
    }

    // ── CASE WHEN in various positions ────────────────────────────────────────

    @Test void caseWhenInOrderBy() throws SQLException {
        exec("CREATE TABLE t (name text, priority text)");
        exec("INSERT INTO t VALUES ('a','low'),('b','high'),('c','medium')");
        QueryResult r = q("""
            SELECT name, priority FROM t
            ORDER BY CASE priority
                WHEN 'high' THEN 1
                WHEN 'medium' THEN 2
                WHEN 'low' THEN 3
            END
            """);
        assertEquals(3, r.rows().size());
        assertEquals("b", r.rows().get(0)[0]); // high → 1
        assertEquals("c", r.rows().get(1)[0]); // medium → 2
        assertEquals("a", r.rows().get(2)[0]); // low → 3
    }
}
