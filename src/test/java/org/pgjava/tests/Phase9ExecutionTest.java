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
 * Phase 9: Query execution engine — SELECT, INSERT, UPDATE, DELETE.
 *
 * Tests run directly against pgjava internals (no DualExecutor / golden PG).
 * Each test creates an isolated database instance.
 */
class Phase9ExecutionTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("phase9_exec_" + System.nanoTime());
        sess = db.openSession();
    }

    @AfterEach
    void tearDown() {
        sess.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private QueryResult q(String sql) throws SQLException {
        return sess.execute(sql);
    }

    /** Returns the first column of the first row as a String. */
    private String scalar(String sql) throws SQLException {
        QueryResult r = q(sql);
        assertFalse(r.rows().isEmpty(), "expected at least one row from: " + sql);
        Object v = r.rows().get(0)[0];
        return v == null ? null : v.toString();
    }

    private Object raw(String sql) throws SQLException {
        QueryResult r = q(sql);
        assertFalse(r.rows().isEmpty(), "expected at least one row from: " + sql);
        return r.rows().get(0)[0];
    }

    private void exec(String sql) throws SQLException {
        sess.execute(sql);
    }

    // =========================================================================
    // SELECT — literal / arithmetic
    // =========================================================================

    @Test void selectLiteral() throws SQLException {
        assertEquals("42", scalar("SELECT 42"));
    }

    @Test void selectArithmetic() throws SQLException {
        assertEquals("7", scalar("SELECT 3 + 4"));
    }

    @Test void selectString() throws SQLException {
        assertEquals("hello", scalar("SELECT 'hello'"));
    }

    @Test void selectMultipleColumns() throws SQLException {
        QueryResult r = q("SELECT 1, 2, 3");
        assertEquals(1, r.rows().size());
        assertEquals(3, r.columns().size());
        assertArrayEquals(new Object[]{1, 2, 3}, r.rows().get(0));
    }

    @Test void selectNoFrom() throws SQLException {
        assertEquals("6", scalar("SELECT 2 * 3"));
    }

    // =========================================================================
    // INSERT + SELECT FROM table
    // =========================================================================

    @Test void insertAndSelect() throws SQLException {
        exec("CREATE TABLE t (id int, name text)");
        exec("INSERT INTO t VALUES (1, 'alice')");
        exec("INSERT INTO t VALUES (2, 'bob')");
        QueryResult r = q("SELECT * FROM t ORDER BY id");
        assertEquals(2, r.rows().size());
        assertEquals("1", r.rows().get(0)[0].toString());
        assertEquals("alice", r.rows().get(0)[1].toString());
        assertEquals("2", r.rows().get(1)[0].toString());
        assertEquals("bob", r.rows().get(1)[1].toString());
    }

    @Test void insertReturnsRowCount() throws SQLException {
        exec("CREATE TABLE t (id int)");
        QueryResult r = sess.execute("INSERT INTO t VALUES (1)");
        assertEquals(1, r.updateCount());
        assertFalse(r.isQuery());
    }

    @Test void insertNamedColumns() throws SQLException {
        exec("CREATE TABLE t (id int, name text, active boolean)");
        exec("INSERT INTO t (name, id) VALUES ('carol', 3)");
        QueryResult r = q("SELECT id, name, active FROM t");
        assertEquals(1, r.rows().size());
        assertEquals("3", r.rows().get(0)[0].toString());
        assertEquals("carol", r.rows().get(0)[1].toString());
        assertNull(r.rows().get(0)[2]);
    }

    // =========================================================================
    // WHERE filter
    // =========================================================================

    @Test void selectWithWhere() throws SQLException {
        exec("CREATE TABLE t (id int, val int)");
        exec("INSERT INTO t VALUES (1, 10), (2, 20), (3, 30)");
        QueryResult r = q("SELECT val FROM t WHERE id = 2");
        assertEquals(1, r.rows().size());
        assertEquals("20", r.rows().get(0)[0].toString());
    }

    @Test void selectWhereNoMatch() throws SQLException {
        exec("CREATE TABLE t (id int)");
        exec("INSERT INTO t VALUES (1), (2)");
        QueryResult r = q("SELECT * FROM t WHERE id = 99");
        assertEquals(0, r.rows().size());
    }

    // =========================================================================
    // ORDER BY and LIMIT
    // =========================================================================

    @Test void orderBy() throws SQLException {
        exec("CREATE TABLE t (n int)");
        exec("INSERT INTO t VALUES (3), (1), (2)");
        QueryResult r = q("SELECT n FROM t ORDER BY n");
        assertEquals(List.of("1", "2", "3"),
                r.rows().stream().map(row -> row[0].toString()).toList());
    }

    @Test void orderByDesc() throws SQLException {
        exec("CREATE TABLE t (n int)");
        exec("INSERT INTO t VALUES (1), (2), (3)");
        QueryResult r = q("SELECT n FROM t ORDER BY n DESC");
        assertEquals(List.of("3", "2", "1"),
                r.rows().stream().map(row -> row[0].toString()).toList());
    }

    @Test void limit() throws SQLException {
        exec("CREATE TABLE t (n int)");
        exec("INSERT INTO t VALUES (1), (2), (3), (4), (5)");
        QueryResult r = q("SELECT n FROM t ORDER BY n LIMIT 3");
        assertEquals(3, r.rows().size());
        assertEquals("1", r.rows().get(0)[0].toString());
    }

    @Test void limitOffset() throws SQLException {
        exec("CREATE TABLE t (n int)");
        exec("INSERT INTO t VALUES (1), (2), (3), (4), (5)");
        QueryResult r = q("SELECT n FROM t ORDER BY n LIMIT 2 OFFSET 2");
        assertEquals(2, r.rows().size());
        assertEquals("3", r.rows().get(0)[0].toString());
        assertEquals("4", r.rows().get(1)[0].toString());
    }

    // =========================================================================
    // Aggregates
    // =========================================================================

    @Test void countStar() throws SQLException {
        exec("CREATE TABLE t (id int)");
        exec("INSERT INTO t VALUES (1), (2), (3)");
        assertEquals("3", scalar("SELECT COUNT(*) FROM t"));
    }

    @Test void countStarEmpty() throws SQLException {
        exec("CREATE TABLE t (id int)");
        assertEquals("0", scalar("SELECT COUNT(*) FROM t"));
    }

    @Test void sum() throws SQLException {
        exec("CREATE TABLE t (n int)");
        exec("INSERT INTO t VALUES (10), (20), (30)");
        assertEquals("60", scalar("SELECT SUM(n) FROM t"));
    }

    @Test void minMax() throws SQLException {
        exec("CREATE TABLE t (n int)");
        exec("INSERT INTO t VALUES (5), (1), (9), (3)");
        assertEquals("1", scalar("SELECT MIN(n) FROM t"));
        assertEquals("9", scalar("SELECT MAX(n) FROM t"));
    }

    @Test void groupBy() throws SQLException {
        exec("CREATE TABLE t (dept text, salary int)");
        exec("INSERT INTO t VALUES ('eng', 100), ('eng', 200), ('hr', 50)");
        QueryResult r = q("SELECT dept, SUM(salary) FROM t GROUP BY dept ORDER BY dept");
        assertEquals(2, r.rows().size());
        assertEquals("eng", r.rows().get(0)[0].toString());
        assertEquals("300", r.rows().get(0)[1].toString());
        assertEquals("hr",  r.rows().get(1)[0].toString());
        assertEquals("50",  r.rows().get(1)[1].toString());
    }

    // =========================================================================
    // UPDATE
    // =========================================================================

    @Test void updateAll() throws SQLException {
        exec("CREATE TABLE t (id int, val int)");
        exec("INSERT INTO t VALUES (1, 10), (2, 20)");
        QueryResult r = sess.execute("UPDATE t SET val = 99");
        assertEquals(2, r.updateCount());
        QueryResult sel = q("SELECT val FROM t ORDER BY id");
        assertEquals("99", sel.rows().get(0)[0].toString());
        assertEquals("99", sel.rows().get(1)[0].toString());
    }

    @Test void updateWithWhere() throws SQLException {
        exec("CREATE TABLE t (id int, val int)");
        exec("INSERT INTO t VALUES (1, 10), (2, 20), (3, 30)");
        QueryResult r = sess.execute("UPDATE t SET val = 999 WHERE id = 2");
        assertEquals(1, r.updateCount());
        assertEquals("999",  scalar("SELECT val FROM t WHERE id = 2"));
        assertEquals("10",   scalar("SELECT val FROM t WHERE id = 1"));
    }

    @Test void updateNoMatch() throws SQLException {
        exec("CREATE TABLE t (id int, val int)");
        exec("INSERT INTO t VALUES (1, 10)");
        QueryResult r = sess.execute("UPDATE t SET val = 0 WHERE id = 99");
        assertEquals(0, r.updateCount());
    }

    // =========================================================================
    // DELETE
    // =========================================================================

    @Test void deleteAll() throws SQLException {
        exec("CREATE TABLE t (id int)");
        exec("INSERT INTO t VALUES (1), (2), (3)");
        QueryResult r = sess.execute("DELETE FROM t");
        assertEquals(3, r.updateCount());
        assertEquals("0", scalar("SELECT COUNT(*) FROM t"));
    }

    @Test void deleteWithWhere() throws SQLException {
        exec("CREATE TABLE t (id int)");
        exec("INSERT INTO t VALUES (1), (2), (3)");
        QueryResult r = sess.execute("DELETE FROM t WHERE id = 2");
        assertEquals(1, r.updateCount());
        assertEquals("2", scalar("SELECT COUNT(*) FROM t"));
    }

    @Test void deleteNoMatch() throws SQLException {
        exec("CREATE TABLE t (id int)");
        exec("INSERT INTO t VALUES (1)");
        QueryResult r = sess.execute("DELETE FROM t WHERE id = 99");
        assertEquals(0, r.updateCount());
    }

    // =========================================================================
    // VALUES
    // =========================================================================

    @Test void valuesClause() throws SQLException {
        QueryResult r = q("VALUES (1, 'a'), (2, 'b'), (3, 'c')");
        assertEquals(3, r.rows().size());
        assertEquals("1", r.rows().get(0)[0].toString());
        assertEquals("b", r.rows().get(1)[1].toString());
    }

    // =========================================================================
    // DISTINCT
    // =========================================================================

    @Test void distinct() throws SQLException {
        exec("CREATE TABLE t (n int)");
        exec("INSERT INTO t VALUES (1), (2), (1), (3), (2)");
        QueryResult r = q("SELECT DISTINCT n FROM t ORDER BY n");
        assertEquals(3, r.rows().size());
        assertEquals("1", r.rows().get(0)[0].toString());
        assertEquals("2", r.rows().get(1)[0].toString());
        assertEquals("3", r.rows().get(2)[0].toString());
    }

    // =========================================================================
    // JOIN
    // =========================================================================

    @Test void innerJoin() throws SQLException {
        exec("CREATE TABLE a (id int, name text)");
        exec("CREATE TABLE b (aid int, score int)");
        exec("INSERT INTO a VALUES (1, 'alice'), (2, 'bob')");
        exec("INSERT INTO b VALUES (1, 90), (1, 80), (2, 70)");
        QueryResult r = q("""
                SELECT a.name, b.score FROM a
                JOIN b ON a.id = b.aid
                ORDER BY b.score
                """);
        assertEquals(3, r.rows().size());
        assertEquals("bob",   r.rows().get(0)[0].toString());
        assertEquals("70",    r.rows().get(0)[1].toString());
        assertEquals("alice", r.rows().get(1)[0].toString());
        assertEquals("80",    r.rows().get(1)[1].toString());
    }

    @Test void leftJoin() throws SQLException {
        exec("CREATE TABLE a (id int, name text)");
        exec("CREATE TABLE b (aid int, val int)");
        exec("INSERT INTO a VALUES (1, 'alice'), (2, 'bob')");
        exec("INSERT INTO b VALUES (1, 100)");
        QueryResult r = q("""
                SELECT a.name, b.val FROM a
                LEFT JOIN b ON a.id = b.aid
                ORDER BY a.id
                """);
        assertEquals(2, r.rows().size());
        assertEquals("alice", r.rows().get(0)[0].toString());
        assertEquals("100",   r.rows().get(0)[1].toString());
        assertEquals("bob",   r.rows().get(1)[0].toString());
        assertNull(r.rows().get(1)[1]);
    }

    // =========================================================================
    // UNION / INTERSECT / EXCEPT
    // =========================================================================

    @Test void union() throws SQLException {
        QueryResult r = q("SELECT 1 UNION SELECT 2 UNION SELECT 1");
        assertEquals(2, r.rows().size()); // deduped
    }

    @Test void unionAll() throws SQLException {
        QueryResult r = q("SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 1");
        assertEquals(3, r.rows().size());
    }

    // =========================================================================
    // Subquery in FROM
    // =========================================================================

    @Test void subqueryInFrom() throws SQLException {
        exec("CREATE TABLE t (n int)");
        exec("INSERT INTO t VALUES (1), (2), (3)");
        QueryResult r = q("""
                SELECT sub.n FROM (SELECT n FROM t WHERE n > 1) AS sub ORDER BY sub.n
                """);
        assertEquals(2, r.rows().size());
        assertEquals("2", r.rows().get(0)[0].toString());
        assertEquals("3", r.rows().get(1)[0].toString());
    }

    // =========================================================================
    // NOT NULL constraint
    // =========================================================================

    @Test void notNullViolation() throws SQLException {
        exec("CREATE TABLE t (id int NOT NULL)");
        SQLException ex = assertThrows(SQLException.class,
                () -> exec("INSERT INTO t VALUES (NULL)"));
        assertEquals("23502", ex.getSQLState());
    }

    // =========================================================================
    // UNIQUE constraint
    // =========================================================================

    @Test void uniqueViolation() throws SQLException {
        exec("CREATE TABLE t (id int UNIQUE)");
        exec("INSERT INTO t VALUES (1)");
        SQLException ex = assertThrows(SQLException.class,
                () -> exec("INSERT INTO t VALUES (1)"));
        assertEquals("23505", ex.getSQLState());
    }

    // =========================================================================
    // INSERT from SELECT
    // =========================================================================

    @Test void insertFromSelect() throws SQLException {
        exec("CREATE TABLE src (n int)");
        exec("CREATE TABLE dst (n int)");
        exec("INSERT INTO src VALUES (10), (20), (30)");
        exec("INSERT INTO dst SELECT n FROM src WHERE n > 10");
        assertEquals("2", scalar("SELECT COUNT(*) FROM dst"));
    }

    // =========================================================================
    // Unknown table error
    // =========================================================================

    @Test void unknownTableThrows() throws SQLException {
        SQLException ex = assertThrows(SQLException.class,
                () -> q("SELECT * FROM nonexistent"));
        assertEquals("42P01", ex.getSQLState());
    }

    // =========================================================================
    // HAVING
    // =========================================================================

    @Test void having() throws SQLException {
        exec("CREATE TABLE t (dept text, salary int)");
        exec("INSERT INTO t VALUES ('eng', 100), ('eng', 200), ('hr', 50)");
        QueryResult r = q("""
                SELECT dept, SUM(salary) FROM t
                GROUP BY dept
                HAVING SUM(salary) > 100
                """);
        assertEquals(1, r.rows().size());
        assertEquals("eng", r.rows().get(0)[0].toString());
    }

    // =========================================================================
    // Subqueries — scalar, EXISTS, IN, ANY/ALL (Phase 9b Priority 1)
    // =========================================================================

    @Test void scalarSubqueryNonCorrelated() throws SQLException {
        exec("CREATE TABLE t (n int)");
        exec("INSERT INTO t VALUES (5), (10), (15)");
        // Scalar subquery in SELECT
        assertEquals("15", scalar("SELECT (SELECT MAX(n) FROM t)"));
    }

    @Test void scalarSubqueryInWhere() throws SQLException {
        exec("CREATE TABLE t (n int)");
        exec("INSERT INTO t VALUES (5), (10), (15)");
        QueryResult r = q("SELECT n FROM t WHERE n > (SELECT AVG(n) FROM t) ORDER BY n");
        assertEquals(1, r.rows().size());
        assertEquals("15", r.rows().get(0)[0].toString());
    }

    @Test void existsSubquery() throws SQLException {
        exec("CREATE TABLE orders (id int, cid int)");
        exec("CREATE TABLE customers (id int, name text)");
        exec("INSERT INTO customers VALUES (1, 'alice'), (2, 'bob')");
        exec("INSERT INTO orders VALUES (1, 1)");
        // alice has orders, bob does not
        QueryResult r = q("""
                SELECT name FROM customers
                WHERE EXISTS (SELECT 1 FROM orders WHERE orders.cid = customers.id)
                ORDER BY name
                """);
        assertEquals(1, r.rows().size());
        assertEquals("alice", r.rows().get(0)[0].toString());
    }

    @Test void notExistsSubquery() throws SQLException {
        exec("CREATE TABLE orders (id int, cid int)");
        exec("CREATE TABLE customers (id int, name text)");
        exec("INSERT INTO customers VALUES (1, 'alice'), (2, 'bob')");
        exec("INSERT INTO orders VALUES (1, 1)");
        QueryResult r = q("""
                SELECT name FROM customers
                WHERE NOT EXISTS (SELECT 1 FROM orders WHERE orders.cid = customers.id)
                ORDER BY name
                """);
        assertEquals(1, r.rows().size());
        assertEquals("bob", r.rows().get(0)[0].toString());
    }

    @Test void inSubquery() throws SQLException {
        exec("CREATE TABLE all_ids (id int)");
        exec("CREATE TABLE allowed (id int)");
        exec("INSERT INTO all_ids VALUES (1), (2), (3)");
        exec("INSERT INTO allowed VALUES (1), (3)");
        QueryResult r = q("SELECT id FROM all_ids WHERE id IN (SELECT id FROM allowed) ORDER BY id");
        assertEquals(2, r.rows().size());
        assertEquals("1", r.rows().get(0)[0].toString());
        assertEquals("3", r.rows().get(1)[0].toString());
    }

    @Test void notInSubquery() throws SQLException {
        exec("CREATE TABLE all_ids (id int)");
        exec("CREATE TABLE allowed (id int)");
        exec("INSERT INTO all_ids VALUES (1), (2), (3)");
        exec("INSERT INTO allowed VALUES (1), (3)");
        QueryResult r = q("SELECT id FROM all_ids WHERE id NOT IN (SELECT id FROM allowed) ORDER BY id");
        assertEquals(1, r.rows().size());
        assertEquals("2", r.rows().get(0)[0].toString());
    }

    @Test void anySubquery() throws SQLException {
        exec("CREATE TABLE t (n int)");
        exec("INSERT INTO t VALUES (10), (20), (30)");
        // 15 = ANY(10, 20, 30) → false; 20 = ANY(10, 20, 30) → true
        assertEquals("true",  scalar("SELECT 20 = ANY(SELECT n FROM t)"));
        assertEquals("false", scalar("SELECT 15 = ANY(SELECT n FROM t)"));
    }

    @Test void allSubquery() throws SQLException {
        exec("CREATE TABLE t (n int)");
        exec("INSERT INTO t VALUES (10), (20), (30)");
        // 5 < ALL(10,20,30) → true; 15 < ALL(10,20,30) → false
        assertEquals("true",  scalar("SELECT 5 < ALL(SELECT n FROM t)"));
        assertEquals("false", scalar("SELECT 15 < ALL(SELECT n FROM t)"));
    }

    @Test void correlatedScalarSubquery() throws SQLException {
        exec("CREATE TABLE dept (id int, name text)");
        exec("CREATE TABLE emp (dept_id int, salary int)");
        exec("INSERT INTO dept VALUES (1, 'eng'), (2, 'hr')");
        exec("INSERT INTO emp VALUES (1, 100), (1, 200), (2, 50)");
        // For each dept, show its max salary via correlated subquery
        QueryResult r = q("""
                SELECT d.name, (SELECT MAX(e.salary) FROM emp e WHERE e.dept_id = d.id) AS max_sal
                FROM dept d
                ORDER BY d.id
                """);
        assertEquals(2, r.rows().size());
        assertEquals("eng", r.rows().get(0)[0].toString());
        assertEquals("200", r.rows().get(0)[1].toString());
        assertEquals("hr",  r.rows().get(1)[0].toString());
        assertEquals("50",  r.rows().get(1)[1].toString());
    }

    @Test void correlatedExistsFilter() throws SQLException {
        exec("CREATE TABLE parent (id int)");
        exec("CREATE TABLE child (pid int)");
        exec("INSERT INTO parent VALUES (1), (2), (3)");
        exec("INSERT INTO child VALUES (1), (1), (3)");
        // Parents that have at least one child
        QueryResult r = q("""
                SELECT id FROM parent
                WHERE EXISTS (SELECT 1 FROM child WHERE child.pid = parent.id)
                ORDER BY id
                """);
        assertEquals(2, r.rows().size());
        assertEquals("1", r.rows().get(0)[0].toString());
        assertEquals("3", r.rows().get(1)[0].toString());
    }

    @Test void orderByAlias() throws SQLException {
        exec("CREATE TABLE t (a int, b int)");
        exec("INSERT INTO t VALUES (1,2),(3,1),(2,3)");
        QueryResult r = q("SELECT a + b AS total FROM t ORDER BY total");
        assertEquals(3, r.rows().size());
        assertEquals("3", r.rows().get(0)[0].toString());
        assertEquals("4", r.rows().get(1)[0].toString());
        assertEquals("5", r.rows().get(2)[0].toString());
    }

    @Test void createTableAsSelect() throws SQLException {
        exec("CREATE TABLE src (id int, name text)");
        exec("INSERT INTO src VALUES (1,'alice'),(2,'bob'),(3,'carol')");
        exec("CREATE TABLE dst AS SELECT id, name FROM src WHERE id > 1");
        QueryResult r = q("SELECT id, name FROM dst ORDER BY id");
        assertEquals(2, r.rows().size());
        assertEquals("2", r.rows().get(0)[0].toString());
        assertEquals("bob", r.rows().get(0)[1].toString());
        assertEquals("3", r.rows().get(1)[0].toString());
    }

    @Test void orderByPosition() throws SQLException {
        exec("CREATE TABLE t (name text, age int)");
        exec("INSERT INTO t VALUES ('alice',30),('bob',25),('carol',35)");
        QueryResult r = q("SELECT name, age FROM t ORDER BY 2 DESC");
        assertEquals(3, r.rows().size());
        assertEquals("carol", r.rows().get(0)[0].toString());
        assertEquals("alice", r.rows().get(1)[0].toString());
        assertEquals("bob",   r.rows().get(2)[0].toString());
    }
}
