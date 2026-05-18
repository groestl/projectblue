package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.QueryResult;
import org.pgjava.engine.Session;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 9b Priority 5: Window functions — ROW_NUMBER, RANK, DENSE_RANK,
 * LAG/LEAD, aggregate windows (SUM/COUNT/MIN/MAX OVER), named windows.
 */
class Phase9bWindowTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("phase9b_win_" + System.nanoTime());
        sess = db.openSession();
    }

    @AfterEach
    void tearDown() { sess.close(); }

    private QueryResult q(String sql) throws SQLException { return sess.execute(sql); }
    private void exec(String sql)     throws SQLException { sess.execute(sql); }

    // ── Ranking ───────────────────────────────────────────────────────────────

    @Test void rowNumber() throws SQLException {
        exec("CREATE TABLE t (id int, dept text, salary int)");
        exec("INSERT INTO t VALUES (1,'eng',100),(2,'eng',200),(3,'mkt',150)");
        QueryResult r = q("""
            SELECT id, ROW_NUMBER() OVER (ORDER BY salary) AS rn
            FROM t ORDER BY id
            """);
        // row 1 salary=100 → rn=1, row 2 salary=200 → rn=3, row 3 salary=150 → rn=2
        assertEquals(3, r.rows().size());
        assertEquals("1", r.rows().get(0)[1].toString()); // id=1 → rn=1
        assertEquals("3", r.rows().get(1)[1].toString()); // id=2 → rn=3
        assertEquals("2", r.rows().get(2)[1].toString()); // id=3 → rn=2
    }

    @Test void rowNumberPartition() throws SQLException {
        exec("CREATE TABLE t (dept text, salary int)");
        exec("INSERT INTO t VALUES ('eng',100),('eng',200),('mkt',150),('mkt',50)");
        QueryResult r = q("""
            SELECT dept, salary, ROW_NUMBER() OVER (PARTITION BY dept ORDER BY salary) AS rn
            FROM t ORDER BY dept, salary
            """);
        assertEquals(4, r.rows().size());
        // eng: salary 100→rn1, 200→rn2
        assertEquals("1", r.rows().get(0)[2].toString());
        assertEquals("2", r.rows().get(1)[2].toString());
        // mkt: salary 50→rn1, 150→rn2
        assertEquals("1", r.rows().get(2)[2].toString());
        assertEquals("2", r.rows().get(3)[2].toString());
    }

    @Test void rank() throws SQLException {
        exec("CREATE TABLE t (val int)");
        exec("INSERT INTO t VALUES (10),(10),(20),(30)");
        QueryResult r = q("SELECT val, RANK() OVER (ORDER BY val) AS rk FROM t ORDER BY val");
        assertEquals(4, r.rows().size());
        assertEquals("1", r.rows().get(0)[1].toString()); // 10 → rank 1
        assertEquals("1", r.rows().get(1)[1].toString()); // 10 → rank 1 (tied)
        assertEquals("3", r.rows().get(2)[1].toString()); // 20 → rank 3 (gap after tie)
        assertEquals("4", r.rows().get(3)[1].toString()); // 30 → rank 4
    }

    @Test void denseRank() throws SQLException {
        exec("CREATE TABLE t (val int)");
        exec("INSERT INTO t VALUES (10),(10),(20),(30)");
        QueryResult r = q("SELECT val, DENSE_RANK() OVER (ORDER BY val) AS dr FROM t ORDER BY val");
        assertEquals(4, r.rows().size());
        assertEquals("1", r.rows().get(0)[1].toString());
        assertEquals("1", r.rows().get(1)[1].toString());
        assertEquals("2", r.rows().get(2)[1].toString()); // no gap
        assertEquals("3", r.rows().get(3)[1].toString());
    }

    @Test void ntile() throws SQLException {
        exec("CREATE TABLE t (val int)");
        exec("INSERT INTO t VALUES (1),(2),(3),(4),(5),(6)");
        QueryResult r = q("SELECT val, NTILE(3) OVER (ORDER BY val) AS bucket FROM t ORDER BY val");
        assertEquals(6, r.rows().size());
        // 6 rows into 3 buckets: [1,2]=1, [3,4]=2, [5,6]=3
        assertEquals("1", r.rows().get(0)[1].toString());
        assertEquals("1", r.rows().get(1)[1].toString());
        assertEquals("2", r.rows().get(2)[1].toString());
        assertEquals("2", r.rows().get(3)[1].toString());
        assertEquals("3", r.rows().get(4)[1].toString());
        assertEquals("3", r.rows().get(5)[1].toString());
    }

    // ── Offset functions ──────────────────────────────────────────────────────

    @Test void lag() throws SQLException {
        exec("CREATE TABLE t (ts int, val int)");
        exec("INSERT INTO t VALUES (1,10),(2,20),(3,30)");
        QueryResult r = q("SELECT ts, LAG(val, 1, 0) OVER (ORDER BY ts) AS prev FROM t ORDER BY ts");
        assertEquals(3, r.rows().size());
        assertEquals("0",  r.rows().get(0)[1].toString()); // no previous → default 0
        assertEquals("10", r.rows().get(1)[1].toString());
        assertEquals("20", r.rows().get(2)[1].toString());
    }

    @Test void lead() throws SQLException {
        exec("CREATE TABLE t (ts int, val int)");
        exec("INSERT INTO t VALUES (1,10),(2,20),(3,30)");
        QueryResult r = q("SELECT ts, LEAD(val, 1, 0) OVER (ORDER BY ts) AS nxt FROM t ORDER BY ts");
        assertEquals(3, r.rows().size());
        assertEquals("20", r.rows().get(0)[1].toString());
        assertEquals("30", r.rows().get(1)[1].toString());
        assertEquals("0",  r.rows().get(2)[1].toString()); // no next → default 0
    }

    @Test void firstValue() throws SQLException {
        exec("CREATE TABLE t (dept text, salary int)");
        exec("INSERT INTO t VALUES ('eng',100),('eng',200),('eng',150)");
        QueryResult r = q("""
            SELECT salary, FIRST_VALUE(salary) OVER (ORDER BY salary) AS fv
            FROM t ORDER BY salary
            """);
        assertEquals(3, r.rows().size());
        // first_value over whole partition (no PARTITION BY) = min salary = 100
        assertEquals("100", r.rows().get(0)[1].toString());
        assertEquals("100", r.rows().get(1)[1].toString());
        assertEquals("100", r.rows().get(2)[1].toString());
    }

    // ── Aggregate windows ─────────────────────────────────────────────────────

    @Test void sumOver() throws SQLException {
        exec("CREATE TABLE t (dept text, salary int)");
        exec("INSERT INTO t VALUES ('eng',100),('eng',200),('mkt',150)");
        QueryResult r = q("""
            SELECT dept, salary, SUM(salary) OVER (PARTITION BY dept) AS dept_total
            FROM t ORDER BY dept, salary
            """);
        assertEquals(3, r.rows().size());
        // eng total = 300
        assertEquals("300", r.rows().get(0)[2].toString());
        assertEquals("300", r.rows().get(1)[2].toString());
        // mkt total = 150
        assertEquals("150", r.rows().get(2)[2].toString());
    }

    @Test void countOver() throws SQLException {
        exec("CREATE TABLE t (dept text, val int)");
        exec("INSERT INTO t VALUES ('a',1),('a',2),('b',3)");
        QueryResult r = q("SELECT dept, COUNT(*) OVER (PARTITION BY dept) AS cnt FROM t ORDER BY dept, val");
        assertEquals(3, r.rows().size());
        assertEquals("2", r.rows().get(0)[1].toString()); // dept a → 2 rows
        assertEquals("2", r.rows().get(1)[1].toString());
        assertEquals("1", r.rows().get(2)[1].toString()); // dept b → 1 row
    }

    @Test void runningSumWithOrderBy() throws SQLException {
        exec("CREATE TABLE t (ts int, val int)");
        exec("INSERT INTO t VALUES (1,10),(2,20),(3,30)");
        QueryResult r = q("""
            SELECT ts, SUM(val) OVER (ORDER BY ts) AS running
            FROM t ORDER BY ts
            """);
        assertEquals(3, r.rows().size());
        assertEquals("10", r.rows().get(0)[1].toString());
        assertEquals("30", r.rows().get(1)[1].toString());
        assertEquals("60", r.rows().get(2)[1].toString());
    }

    @Test void minMaxOver() throws SQLException {
        exec("CREATE TABLE t (dept text, salary int)");
        exec("INSERT INTO t VALUES ('eng',100),('eng',200),('eng',150)");
        QueryResult r = q("""
            SELECT salary,
                   MIN(salary) OVER (PARTITION BY dept) AS lo,
                   MAX(salary) OVER (PARTITION BY dept) AS hi
            FROM t ORDER BY salary
            """);
        assertEquals(3, r.rows().size());
        for (Object[] row : r.rows()) {
            assertEquals("100", row[1].toString());
            assertEquals("200", row[2].toString());
        }
    }

    // ── Named window ──────────────────────────────────────────────────────────

    @Test void namedWindow() throws SQLException {
        exec("CREATE TABLE t (dept text, salary int)");
        exec("INSERT INTO t VALUES ('eng',100),('eng',200),('mkt',150)");
        QueryResult r = q("""
            SELECT dept, salary,
                   ROW_NUMBER() OVER w AS rn,
                   SUM(salary) OVER w AS total
            FROM t
            WINDOW w AS (PARTITION BY dept ORDER BY salary)
            ORDER BY dept, salary
            """);
        assertEquals(3, r.rows().size());
        // eng: rn 1,2; mkt: rn 1
        assertEquals("1", r.rows().get(0)[2].toString());
        assertEquals("2", r.rows().get(1)[2].toString());
        assertEquals("1", r.rows().get(2)[2].toString());
    }
}
