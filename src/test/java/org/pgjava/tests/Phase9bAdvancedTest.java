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
 * Phase 9b: DISTINCT ON, recursive CTEs, and LATERAL subqueries.
 */
class Phase9bAdvancedTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("phase9b_adv_" + System.nanoTime());
        sess = db.openSession();
    }

    @AfterEach
    void tearDown() { sess.close(); }

    private QueryResult q(String sql) throws SQLException { return sess.execute(sql); }
    private void exec(String sql)     throws SQLException { sess.execute(sql); }

    // ── DISTINCT ON ───────────────────────────────────────────────────────────

    @Test void distinctOnBasic() throws SQLException {
        exec("CREATE TABLE emp (dept text, name text, salary int)");
        exec("INSERT INTO emp VALUES ('eng','alice',100),('eng','bob',200),('mkt','carol',150)");
        QueryResult r = q("""
            SELECT DISTINCT ON (dept) dept, name, salary
            FROM emp ORDER BY dept, salary DESC
            """);
        // First row per dept by salary DESC: eng→bob(200), mkt→carol(150)
        assertEquals(2, r.rows().size());
        List<Object[]> rows = r.rows();
        // dept=eng → bob (highest salary)
        boolean engFound = rows.stream().anyMatch(row ->
                "eng".equals(row[0]) && "bob".equals(row[1]));
        assertTrue(engFound, "DISTINCT ON should keep first row per dept (bob, 200)");
        boolean mktFound = rows.stream().anyMatch(row ->
                "mkt".equals(row[0]) && "carol".equals(row[1]));
        assertTrue(mktFound, "DISTINCT ON should keep carol for mkt");
    }

    @Test void distinctOnSingleKey() throws SQLException {
        exec("CREATE TABLE nums (n int)");
        exec("INSERT INTO nums VALUES (1),(1),(2),(3),(3)");
        QueryResult r = q("SELECT DISTINCT ON (n) n FROM nums ORDER BY n");
        assertEquals(3, r.rows().size());
        assertEquals(1, ((Number) r.rows().get(0)[0]).intValue());
        assertEquals(2, ((Number) r.rows().get(1)[0]).intValue());
        assertEquals(3, ((Number) r.rows().get(2)[0]).intValue());
    }

    // ── Recursive CTEs ────────────────────────────────────────────────────────

    @Test void recursiveCteCountdown() throws SQLException {
        QueryResult r = q("""
            WITH RECURSIVE cnt(n) AS (
                SELECT 5
                UNION ALL
                SELECT n - 1 FROM cnt WHERE n > 1
            )
            SELECT n FROM cnt ORDER BY n DESC
            """);
        assertEquals(5, r.rows().size());
        for (int i = 0; i < 5; i++) {
            assertEquals(5 - i, ((Number) r.rows().get(i)[0]).intValue());
        }
    }

    @Test void recursiveCteFibonacci() throws SQLException {
        QueryResult r = q("""
            WITH RECURSIVE fib(a, b) AS (
                SELECT 0, 1
                UNION ALL
                SELECT b, a + b FROM fib WHERE b <= 55
            )
            SELECT a FROM fib ORDER BY a
            """);
        // Fibonacci numbers ≤ 55: 0,1,1,2,3,5,8,13,21,34,55
        List<Integer> expected = List.of(0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55);
        assertEquals(expected.size(), r.rows().size());
    }

    @Test void recursiveCteTreeTraversal() throws SQLException {
        exec("CREATE TABLE tree (id int, parent_id int, name text)");
        exec("INSERT INTO tree VALUES (1,NULL,'root'),(2,1,'a'),(3,1,'b'),(4,2,'c')");
        QueryResult r = q("""
            WITH RECURSIVE hier AS (
                SELECT id, name, 0 AS depth FROM tree WHERE parent_id IS NULL
                UNION ALL
                SELECT t.id, t.name, h.depth + 1
                FROM tree t JOIN hier h ON t.parent_id = h.id
            )
            SELECT id, depth FROM hier ORDER BY id
            """);
        assertEquals(4, r.rows().size());
        // root at depth 0, a/b at depth 1, c at depth 2
        assertEquals(0, ((Number) r.rows().get(0)[1]).intValue()); // id=1 depth=0
        assertEquals(1, ((Number) r.rows().get(1)[1]).intValue()); // id=2 depth=1
        assertEquals(1, ((Number) r.rows().get(2)[1]).intValue()); // id=3 depth=1
        assertEquals(2, ((Number) r.rows().get(3)[1]).intValue()); // id=4 depth=2
    }

    // ── LATERAL ───────────────────────────────────────────────────────────────

    @Test void lateralBasic() throws SQLException {
        exec("CREATE TABLE dept (id int, name text)");
        exec("CREATE TABLE emp2 (dept_id int, name text, salary int)");
        exec("INSERT INTO dept VALUES (1,'eng'),(2,'mkt')");
        exec("INSERT INTO emp2 VALUES (1,'alice',100),(1,'bob',200),(2,'carol',150)");
        // For each dept, get the top earner
        QueryResult r = q("""
            SELECT d.name, top.name AS top_emp
            FROM dept d,
            LATERAL (
                SELECT name FROM emp2 WHERE dept_id = d.id ORDER BY salary DESC LIMIT 1
            ) AS top
            ORDER BY d.name
            """);
        assertEquals(2, r.rows().size());
        assertEquals("eng",   r.rows().get(0)[0]);
        assertEquals("bob",   r.rows().get(0)[1]);
        assertEquals("mkt",   r.rows().get(1)[0]);
        assertEquals("carol", r.rows().get(1)[1]);
    }

    @Test void lateralCorrelatedFilter() throws SQLException {
        exec("CREATE TABLE series (n int)");
        exec("INSERT INTO series VALUES (1),(2),(3)");
        QueryResult r = q("""
            SELECT s.n, sub.doubled
            FROM series s,
            LATERAL (SELECT s.n * 2 AS doubled) AS sub
            ORDER BY s.n
            """);
        assertEquals(3, r.rows().size());
        assertEquals(2, ((Number) r.rows().get(0)[1]).intValue());
        assertEquals(4, ((Number) r.rows().get(1)[1]).intValue());
        assertEquals(6, ((Number) r.rows().get(2)[1]).intValue());
    }
}
