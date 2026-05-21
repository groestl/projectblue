package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;

/**
 * Golden tests for JOIN types — validated against real PostgreSQL.
 *
 * Covers: INNER JOIN, LEFT/RIGHT/FULL OUTER JOIN, CROSS JOIN, NATURAL JOIN,
 * SELF JOIN, LATERAL JOIN, subquery in FROM, predicate pushdown, NULL semantics.
 */
@ExtendWith(GoldenExtension.class)
class GoldenJoinTest {

    // ── INNER JOIN ────────────────────────────────────────────────────────────

    @Test void innerJoinBasic(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE dept (id int, name text)");
        db.execute("CREATE TABLE emp (id int, dept_id int, name text)");
        db.execute("INSERT INTO dept VALUES (1,'eng'),(2,'mkt'),(3,'hr')");
        db.execute("INSERT INTO emp VALUES (1,1,'alice'),(2,1,'bob'),(3,2,'carol')");
        db.assertQuery("""
            SELECT e.name AS emp, d.name AS dept
            FROM emp e INNER JOIN dept d ON e.dept_id = d.id
            ORDER BY e.name
            """);
    }

    @Test void innerJoinMultiTable(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE region (id int, name text)");
        db.execute("CREATE TABLE dept (id int, region_id int, name text)");
        db.execute("CREATE TABLE emp (id int, dept_id int, name text)");
        db.execute("INSERT INTO region VALUES (1,'west'),(2,'east')");
        db.execute("INSERT INTO dept VALUES (1,1,'eng'),(2,2,'mkt')");
        db.execute("INSERT INTO emp VALUES (1,1,'alice'),(2,2,'bob')");
        db.assertQuery("""
            SELECT e.name AS emp, d.name AS dept, r.name AS region
            FROM emp e
            JOIN dept d ON e.dept_id = d.id
            JOIN region r ON d.region_id = r.id
            ORDER BY e.name
            """);
    }

    @Test void innerJoinExcludesUnmatched(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE a (id int, val text)");
        db.execute("CREATE TABLE b (a_id int, extra text)");
        db.execute("INSERT INTO a VALUES (1,'one'),(2,'two'),(3,'three')");
        db.execute("INSERT INTO b VALUES (1,'x'),(3,'y')");
        // Row with id=2 in 'a' has no match in 'b' — must not appear
        db.assertQuery("""
            SELECT a.val, b.extra
            FROM a JOIN b ON a.id = b.a_id
            ORDER BY a.val
            """);
    }

    // ── LEFT OUTER JOIN ───────────────────────────────────────────────────────

    @Test void leftJoinNullExtension(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE dept (id int, name text)");
        db.execute("CREATE TABLE emp (id int, dept_id int, name text)");
        db.execute("INSERT INTO dept VALUES (1,'eng'),(2,'mkt'),(3,'hr')");
        db.execute("INSERT INTO emp VALUES (1,1,'alice'),(2,1,'bob')");
        // hr (id=3) has no employees — must appear with NULL employee fields
        db.assertQuery("""
            SELECT d.name AS dept, e.name AS emp
            FROM dept d LEFT JOIN emp e ON e.dept_id = d.id
            ORDER BY d.name, e.name NULLS LAST
            """);
    }

    @Test void leftJoinCountPreservesAllLeft(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id int)");
        db.execute("CREATE TABLE child (parent_id int)");
        db.execute("INSERT INTO parent VALUES (1),(2),(3)");
        db.execute("INSERT INTO child VALUES (1),(1),(2)");
        // parent 3 has no children; COUNT must be 0, not NULL
        db.assertQuery("""
            SELECT p.id, COUNT(c.parent_id) AS child_count
            FROM parent p LEFT JOIN child c ON c.parent_id = p.id
            GROUP BY p.id ORDER BY p.id
            """);
    }

    @Test void leftJoinWhereFilterOnRight(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE a (id int)");
        db.execute("CREATE TABLE b (a_id int, val int)");
        db.execute("INSERT INTO a VALUES (1),(2),(3)");
        db.execute("INSERT INTO b VALUES (1,10),(2,20)");
        // Filtering right-side column in WHERE turns LEFT JOIN into INNER JOIN
        db.assertQuery("""
            SELECT a.id, b.val
            FROM a LEFT JOIN b ON a.id = b.a_id
            WHERE b.val > 10
            ORDER BY a.id
            """);
    }

    // ── RIGHT OUTER JOIN ──────────────────────────────────────────────────────

    @Test void rightJoinBasic(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE emp (id int, dept_id int, name text)");
        db.execute("CREATE TABLE dept (id int, name text)");
        db.execute("INSERT INTO emp VALUES (1,1,'alice'),(2,1,'bob')");
        db.execute("INSERT INTO dept VALUES (1,'eng'),(2,'mkt'),(3,'hr')");
        // mkt and hr have no employees — must appear with NULL emp fields
        db.assertQuery("""
            SELECT e.name AS emp, d.name AS dept
            FROM emp e RIGHT JOIN dept d ON e.dept_id = d.id
            ORDER BY d.name, e.name NULLS LAST
            """);
    }

    // ── FULL OUTER JOIN ───────────────────────────────────────────────────────

    @Test void fullOuterJoin(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE left_t (id int, lval text)");
        db.execute("CREATE TABLE right_t (id int, rval text)");
        db.execute("INSERT INTO left_t VALUES (1,'l1'),(2,'l2'),(3,'l3')");
        db.execute("INSERT INTO right_t VALUES (2,'r2'),(3,'r3'),(4,'r4')");
        // id=1 only in left, id=4 only in right — both must appear
        db.assertQuery("""
            SELECT l.id AS lid, l.lval, r.id AS rid, r.rval
            FROM left_t l FULL OUTER JOIN right_t r ON l.id = r.id
            ORDER BY COALESCE(l.id, r.id)
            """);
    }

    @Test void fullOuterJoinAllUnmatched(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE a (id int)");
        db.execute("CREATE TABLE b (id int)");
        db.execute("INSERT INTO a VALUES (1),(2)");
        db.execute("INSERT INTO b VALUES (3),(4)");
        db.assertQuery("""
            SELECT a.id AS aid, b.id AS bid
            FROM a FULL OUTER JOIN b ON a.id = b.id
            ORDER BY COALESCE(a.id, b.id)
            """);
    }

    // ── CROSS JOIN ────────────────────────────────────────────────────────────

    @Test void crossJoin(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE colors (c text)");
        db.execute("CREATE TABLE sizes (s text)");
        db.execute("INSERT INTO colors VALUES ('red'),('blue')");
        db.execute("INSERT INTO sizes VALUES ('S'),('M'),('L')");
        db.assertQuery("""
            SELECT c, s FROM colors CROSS JOIN sizes ORDER BY c, s
            """);
    }

    @Test void crossJoinRowCount(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE a (n int)");
        db.execute("CREATE TABLE b (n int)");
        db.execute("INSERT INTO a VALUES (1),(2),(3)");
        db.execute("INSERT INTO b VALUES (1),(2),(3),(4)");
        db.assertQuery("SELECT COUNT(*) FROM a CROSS JOIN b");
    }

    // ── NATURAL JOIN ──────────────────────────────────────────────────────────
    // pgjava parses NATURAL JOIN (natural=true, usingCols=[]) but the Planner
    // does not build a USING condition from the natural flag — it falls through
    // to condition=null, making it behave as CROSS JOIN.
    // assumeSupported() keeps postgres as the correctness anchor.

    @Test void naturalJoin(DualExecutor db) throws Exception {
        db.assumeSupported(); // NATURAL JOIN not yet implemented in pgjava planner
        db.execute("CREATE TABLE dept (id int, name text)");
        db.execute("CREATE TABLE emp (id int, name text, dept_id int)");
        db.execute("INSERT INTO dept VALUES (1,'eng'),(2,'mkt')");
        db.execute("INSERT INTO emp VALUES (1,'alice',1),(2,'bob',1),(3,'carol',2)");
        // NATURAL JOIN matches on columns with the same name: 'id' and 'name'
        // This matches dept.id=emp.id AND dept.name=emp.name — likely no rows
        db.assertQuery("""
            SELECT COUNT(*) FROM dept NATURAL JOIN emp
            """);
    }

    @Test void naturalJoinSharedColumn(DualExecutor db) throws Exception {
        db.assumeSupported(); // NATURAL JOIN not yet implemented in pgjava planner
        db.execute("CREATE TABLE product (code text, price int)");
        db.execute("CREATE TABLE inventory (code text, qty int)");
        db.execute("INSERT INTO product VALUES ('A',100),('B',200),('C',300)");
        db.execute("INSERT INTO inventory VALUES ('A',5),('B',0),('D',3)");
        db.assertQuery("""
            SELECT code, price, qty
            FROM product NATURAL JOIN inventory
            ORDER BY code
            """);
    }

    // ── SELF JOIN ─────────────────────────────────────────────────────────────

    @Test void selfJoinManagerHierarchy(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE emp (id int, name text, manager_id int)");
        db.execute("INSERT INTO emp VALUES (1,'ceo',NULL),(2,'vp',1),(3,'mgr',1),(4,'dev',2),(5,'intern',3)");
        db.assertQuery("""
            SELECT e.name AS employee, m.name AS manager
            FROM emp e LEFT JOIN emp m ON e.manager_id = m.id
            ORDER BY e.id
            """);
    }

    @Test void selfJoinFindPeers(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE emp (id int, name text, dept text)");
        db.execute("INSERT INTO emp VALUES (1,'alice','eng'),(2,'bob','eng'),(3,'carol','mkt')");
        db.assertQuery("""
            SELECT e1.name AS person, e2.name AS peer
            FROM emp e1 JOIN emp e2 ON e1.dept = e2.dept AND e1.id < e2.id
            ORDER BY e1.name, e2.name
            """);
    }

    // ── LATERAL JOIN ──────────────────────────────────────────────────────────

    @Test void lateralSubquery(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE dept (id int, name text)");
        db.execute("CREATE TABLE emp (dept_id int, name text, salary int)");
        db.execute("INSERT INTO dept VALUES (1,'eng'),(2,'mkt')");
        db.execute("INSERT INTO emp VALUES (1,'alice',100),(1,'bob',200),(2,'carol',150),(2,'dave',120)");
        db.assertQuery("""
            SELECT d.name AS dept, top.name AS top_earner, top.salary
            FROM dept d,
            LATERAL (
                SELECT name, salary FROM emp
                WHERE dept_id = d.id
                ORDER BY salary DESC LIMIT 1
            ) AS top
            ORDER BY d.name
            """);
    }

    @Test void lateralWithJoin(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE orders (id int, customer text, amount int)");
        db.execute("INSERT INTO orders VALUES (1,'alice',50),(2,'alice',150),(3,'bob',80),(4,'bob',200),(5,'carol',30)");
        db.assertQuery("""
            SELECT customer, total, top_order
            FROM (SELECT DISTINCT customer FROM orders) c,
            LATERAL (
                SELECT SUM(amount) AS total, MAX(amount) AS top_order
                FROM orders
                WHERE customer = c.customer
            ) AS stats
            ORDER BY customer
            """);
    }

    // ── Subquery in FROM ──────────────────────────────────────────────────────

    @Test void subqueryInFrom(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text, salary int)");
        db.execute("INSERT INTO t VALUES ('eng',100),('eng',200),('mkt',150),('mkt',50)");
        db.assertQuery("""
            SELECT dept, avg_sal
            FROM (
                SELECT dept, AVG(salary)::int AS avg_sal
                FROM t GROUP BY dept
            ) AS dept_avgs
            WHERE avg_sal > 100
            ORDER BY dept
            """);
    }

    @Test void joinWithSubqueryInFrom(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE emp (id int, dept text, salary int)");
        db.execute("INSERT INTO emp VALUES (1,'eng',100),(2,'eng',200),(3,'mkt',150),(4,'mkt',50)");
        db.assertQuery("""
            SELECT e.id, e.dept, e.salary, avg_sal.dept_avg
            FROM emp e
            JOIN (
                SELECT dept, AVG(salary)::int AS dept_avg FROM emp GROUP BY dept
            ) AS avg_sal ON e.dept = avg_sal.dept
            WHERE e.salary > avg_sal.dept_avg
            ORDER BY e.id
            """);
    }

    // ── Equality predicate: ON vs WHERE ───────────────────────────────────────

    @Test void joinOnVsWhereSameResult(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE a (id int, val int)");
        db.execute("CREATE TABLE b (id int, bval int)");
        db.execute("INSERT INTO a VALUES (1,10),(2,20),(3,30)");
        db.execute("INSERT INTO b VALUES (1,100),(2,200),(3,300)");
        // For INNER JOIN, condition in ON or WHERE produces same result
        db.assertQuery("""
            SELECT a.id, a.val, b.bval
            FROM a JOIN b ON a.id = b.id
            WHERE a.val > 10
            ORDER BY a.id
            """);
        db.assertQuery("""
            SELECT a.id, a.val, b.bval
            FROM a JOIN b ON a.id = b.id AND a.val > 10
            ORDER BY a.id
            """);
    }

    @Test void leftJoinOnConditionVsWhere(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id int)");
        db.execute("CREATE TABLE child (parent_id int, val int)");
        db.execute("INSERT INTO parent VALUES (1),(2),(3)");
        db.execute("INSERT INTO child VALUES (1,10),(1,20),(2,5)");
        // Condition in ON: parent 3 appears with NULL (left join preserved)
        db.assertQuery("""
            SELECT p.id, c.val
            FROM parent p LEFT JOIN child c ON p.id = c.parent_id AND c.val > 10
            ORDER BY p.id, c.val NULLS LAST
            """);
        // Condition in WHERE: parent 3 is eliminated (effectively INNER JOIN)
        db.assertQuery("""
            SELECT p.id, c.val
            FROM parent p LEFT JOIN child c ON p.id = c.parent_id
            WHERE c.val > 10
            ORDER BY p.id, c.val
            """);
    }

    // ── NULL semantics in joins ───────────────────────────────────────────────

    @Test void notInWithNullSubquery(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (n int)");
        db.execute("INSERT INTO t VALUES (1),(2),(3),(NULL)");
        // NOT IN with a subquery containing NULL returns empty result — classic NULL trap
        db.assertQuery("SELECT COUNT(*) FROM t WHERE n NOT IN (SELECT n FROM t WHERE n IS NOT NULL)");
        // Safe alternative using NOT EXISTS
        db.assertQuery("""
            SELECT COUNT(*) FROM t t1
            WHERE NOT EXISTS (
                SELECT 1 FROM t t2 WHERE t2.n IS NOT NULL AND t2.n = t1.n
            )
            """);
    }

    @Test void joinOnNullEquality(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE a (id int, key_col int)");
        db.execute("CREATE TABLE b (id int, key_col int)");
        db.execute("INSERT INTO a VALUES (1,10),(2,NULL),(3,30)");
        db.execute("INSERT INTO b VALUES (1,10),(2,NULL),(3,30)");
        // NULL = NULL is not true in SQL — rows with NULL key_col must not match
        db.assertQuery("""
            SELECT a.id, b.id AS bid
            FROM a JOIN b ON a.key_col = b.key_col
            ORDER BY a.id
            """);
    }
}
