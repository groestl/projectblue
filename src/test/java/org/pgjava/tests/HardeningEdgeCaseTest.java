package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.QueryResult;
import org.pgjava.engine.Session;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL edge-case hardening: COALESCE, NULLIF, GREATEST, LEAST, CASE with NULLs,
 * multi-column IN, self-joins, GROUP BY expressions, HAVING with aggregates,
 * nested mixed JOINs, CAST edge cases.
 */
class HardeningEdgeCaseTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("edge_" + System.nanoTime());
        sess = db.openSession();
    }

    @AfterEach
    void tearDown() { sess.close(); }

    private QueryResult q(String sql) throws SQLException { return sess.execute(sql); }
    private void exec(String sql)     throws SQLException { sess.execute(sql); }

    // ── COALESCE ──────────────────────────────────────────────────────────────

    @Test void coalesceBasic() throws SQLException {
        QueryResult r = q("SELECT COALESCE(NULL, NULL, 3, 4)");
        assertEquals(3, ((Number) r.rows().get(0)[0]).intValue());
    }

    @Test void coalesceAllNull() throws SQLException {
        QueryResult r = q("SELECT COALESCE(NULL, NULL)");
        assertNull(r.rows().get(0)[0]);
    }

    @Test void coalesceFirstNonNull() throws SQLException {
        QueryResult r = q("SELECT COALESCE('hello', 'world')");
        assertEquals("hello", r.rows().get(0)[0]);
    }

    @Test void coalesceInWhere() throws SQLException {
        exec("CREATE TABLE t (id int, name text)");
        exec("INSERT INTO t VALUES (1, NULL), (2, 'bob')");
        QueryResult r = q("SELECT id, COALESCE(name, 'unknown') AS n FROM t ORDER BY id");
        assertEquals(2, r.rows().size());
        assertEquals("unknown", r.rows().get(0)[1]);
        assertEquals("bob", r.rows().get(1)[1]);
    }

    // ── NULLIF ────────────────────────────────────────────────────────────────

    @Test void nullifEqual() throws SQLException {
        QueryResult r = q("SELECT NULLIF(5, 5)");
        assertNull(r.rows().get(0)[0]);
    }

    @Test void nullifDifferent() throws SQLException {
        QueryResult r = q("SELECT NULLIF(5, 3)");
        assertEquals(5, ((Number) r.rows().get(0)[0]).intValue());
    }

    @Test void nullifWithNull() throws SQLException {
        QueryResult r = q("SELECT NULLIF(NULL, 5)");
        assertNull(r.rows().get(0)[0]);
    }

    // ── GREATEST / LEAST ──────────────────────────────────────────────────────

    @Test void greatestIntegers() throws SQLException {
        QueryResult r = q("SELECT GREATEST(3, 1, 4, 1, 5, 9)");
        assertEquals(9, ((Number) r.rows().get(0)[0]).intValue());
    }

    @Test void leastIntegers() throws SQLException {
        QueryResult r = q("SELECT LEAST(3, 1, 4, 1, 5, 9)");
        assertEquals(1, ((Number) r.rows().get(0)[0]).intValue());
    }

    @Test void greatestStrings() throws SQLException {
        QueryResult r = q("SELECT GREATEST('apple', 'banana', 'cherry')");
        assertEquals("cherry", r.rows().get(0)[0]);
    }

    @Test void leastStrings() throws SQLException {
        QueryResult r = q("SELECT LEAST('apple', 'banana', 'cherry')");
        assertEquals("apple", r.rows().get(0)[0]);
    }

    @Test void greatestWithNull() throws SQLException {
        // PostgreSQL: GREATEST ignores NULLs
        QueryResult r = q("SELECT GREATEST(NULL, 3, NULL, 7, NULL)");
        assertEquals(7, ((Number) r.rows().get(0)[0]).intValue());
    }

    @Test void leastWithNull() throws SQLException {
        QueryResult r = q("SELECT LEAST(NULL, 3, NULL, 7, NULL)");
        assertEquals(3, ((Number) r.rows().get(0)[0]).intValue());
    }

    // ── CASE with NULLs ───────────────────────────────────────────────────────

    @Test void caseWhenNull() throws SQLException {
        QueryResult r = q("SELECT CASE WHEN NULL THEN 'yes' ELSE 'no' END");
        assertEquals("no", r.rows().get(0)[0]);
    }

    @Test void caseNullResult() throws SQLException {
        QueryResult r = q("SELECT CASE WHEN true THEN NULL ELSE 'x' END");
        assertNull(r.rows().get(0)[0]);
    }

    @Test void caseSimpleWithNull() throws SQLException {
        exec("CREATE TABLE t (val int)");
        exec("INSERT INTO t VALUES (1),(NULL),(3)");
        QueryResult r = q("""
            SELECT val, CASE val
                WHEN 1 THEN 'one'
                WHEN 3 THEN 'three'
                ELSE 'other'
            END AS label
            FROM t ORDER BY val NULLS FIRST
            """);
        assertEquals(3, r.rows().size());
        assertEquals("other", r.rows().get(0)[1]); // NULL val → ELSE
        assertEquals("one",   r.rows().get(1)[1]);
        assertEquals("three", r.rows().get(2)[1]);
    }

    // ── Self-joins ────────────────────────────────────────────────────────────

    @Test void selfJoin() throws SQLException {
        exec("CREATE TABLE emp (id int, name text, mgr_id int)");
        exec("INSERT INTO emp VALUES (1,'alice',NULL),(2,'bob',1),(3,'carol',1),(4,'dave',2)");
        QueryResult r = q("""
            SELECT e.name AS employee, m.name AS manager
            FROM emp e LEFT JOIN emp m ON e.mgr_id = m.id
            ORDER BY e.name
            """);
        assertEquals(4, r.rows().size());
        assertEquals("alice", r.rows().get(0)[0]); assertNull(r.rows().get(0)[1]); // no manager
        assertEquals("bob",   r.rows().get(1)[0]); assertEquals("alice", r.rows().get(1)[1]);
        assertEquals("carol", r.rows().get(2)[0]); assertEquals("alice", r.rows().get(2)[1]);
        assertEquals("dave",  r.rows().get(3)[0]); assertEquals("bob",   r.rows().get(3)[1]);
    }

    // ── GROUP BY with expressions ─────────────────────────────────────────────

    @Test void groupByExpression() throws SQLException {
        exec("CREATE TABLE sales (amount int, category text)");
        exec("INSERT INTO sales VALUES (10,'a'),(25,'b'),(30,'a'),(5,'b'),(50,'c')");
        QueryResult r = q("""
            SELECT CASE WHEN amount > 20 THEN 'high' ELSE 'low' END AS tier,
                   COUNT(*) AS cnt
            FROM sales
            GROUP BY CASE WHEN amount > 20 THEN 'high' ELSE 'low' END
            ORDER BY tier
            """);
        assertEquals(2, r.rows().size());
        assertEquals("high", r.rows().get(0)[0]);
        assertEquals(3L, ((Number) r.rows().get(0)[1]).longValue()); // 25,30,50 > 20
        assertEquals("low",  r.rows().get(1)[0]);
        assertEquals(2L, ((Number) r.rows().get(1)[1]).longValue()); // 10,5 <= 20
    }

    // ── HAVING with aggregates ────────────────────────────────────────────────

    @Test void havingWithAggregate() throws SQLException {
        exec("CREATE TABLE t (cat text, val int)");
        exec("INSERT INTO t VALUES ('a',1),('a',2),('b',3),('c',10),('c',20)");
        QueryResult r = q("""
            SELECT cat, SUM(val) AS total
            FROM t GROUP BY cat HAVING SUM(val) > 5
            ORDER BY cat
            """);
        assertEquals(1, r.rows().size());
        assertEquals("c", r.rows().get(0)[0]);
        assertEquals(30L, ((Number) r.rows().get(0)[1]).longValue());
    }

    @Test void havingWithCount() throws SQLException {
        exec("CREATE TABLE t (cat text, val int)");
        exec("INSERT INTO t VALUES ('a',1),('a',2),('b',3)");
        QueryResult r = q("SELECT cat, COUNT(*) AS cnt FROM t GROUP BY cat HAVING COUNT(*) > 1");
        assertEquals(1, r.rows().size());
        assertEquals("a", r.rows().get(0)[0]);
    }

    // ── Nested mixed JOINs ────────────────────────────────────────────────────

    @Test void mixedJoinTypes() throws SQLException {
        exec("CREATE TABLE a (id int, val text)");
        exec("CREATE TABLE b (id int, a_id int, val text)");
        exec("CREATE TABLE c (id int, b_id int, val text)");
        exec("INSERT INTO a VALUES (1,'x'),(2,'y')");
        exec("INSERT INTO b VALUES (10,1,'p'),(20,1,'q')");
        exec("INSERT INTO c VALUES (100,10,'r')");
        QueryResult r = q("""
            SELECT a.val, b.val, c.val
            FROM a
            INNER JOIN b ON b.a_id = a.id
            LEFT JOIN c ON c.b_id = b.id
            ORDER BY a.val, b.val
            """);
        assertEquals(2, r.rows().size());
        assertEquals("x", r.rows().get(0)[0]); assertEquals("p", r.rows().get(0)[1]); assertEquals("r", r.rows().get(0)[2]);
        assertEquals("x", r.rows().get(1)[0]); assertEquals("q", r.rows().get(1)[1]); assertNull(r.rows().get(1)[2]);
    }

    // ── CAST edge cases ───────────────────────────────────────────────────────

    @Test void castIntToText() throws SQLException {
        QueryResult r = q("SELECT CAST(42 AS text)");
        assertEquals("42", r.rows().get(0)[0]);
    }

    @Test void castTextToInt() throws SQLException {
        QueryResult r = q("SELECT CAST('123' AS int)");
        assertEquals(123, ((Number) r.rows().get(0)[0]).intValue());
    }

    @Test void castDoubleToInt() throws SQLException {
        QueryResult r = q("SELECT CAST(3.7 AS int)");
        assertEquals(4, ((Number) r.rows().get(0)[0]).intValue());
    }

    @Test void castIntToBool() throws SQLException {
        QueryResult r = q("SELECT CAST(1 AS boolean), CAST(0 AS boolean)");
        assertEquals(true, r.rows().get(0)[0]);
        assertEquals(false, r.rows().get(0)[1]);
    }

    @Test void castShorthandSyntax() throws SQLException {
        QueryResult r = q("SELECT '42'::int");
        assertEquals(42, ((Number) r.rows().get(0)[0]).intValue());
    }

    // ── Multi-column comparisons ──────────────────────────────────────────────

    @Test void multiColumnIn() throws SQLException {
        exec("CREATE TABLE t (a int, b int, val text)");
        exec("INSERT INTO t VALUES (1,10,'x'),(1,20,'y'),(2,10,'z')");
        QueryResult r = q("SELECT val FROM t WHERE (a, b) IN ((1, 10), (2, 10)) ORDER BY val");
        assertEquals(2, r.rows().size());
        assertEquals("x", r.rows().get(0)[0]);
        assertEquals("z", r.rows().get(1)[0]);
    }

    // ── String functions ──────────────────────────────────────────────────────

    @Test void concatOperator() throws SQLException {
        QueryResult r = q("SELECT 'hello' || ' ' || 'world'");
        assertEquals("hello world", r.rows().get(0)[0]);
    }

    @Test void upperLower() throws SQLException {
        QueryResult r = q("SELECT UPPER('hello'), LOWER('WORLD')");
        assertEquals("HELLO", r.rows().get(0)[0]);
        assertEquals("world", r.rows().get(0)[1]);
    }

    @Test void lengthAndTrim() throws SQLException {
        QueryResult r = q("SELECT LENGTH('hello'), TRIM('  hi  ')");
        assertEquals(5, ((Number) r.rows().get(0)[0]).intValue());
        assertEquals("hi", r.rows().get(0)[1]);
    }

    @Test void substringFunction() throws SQLException {
        QueryResult r = q("SELECT SUBSTRING('hello world' FROM 7 FOR 5)");
        assertEquals("world", r.rows().get(0)[0]);
    }

    // ── Aggregate edge cases ──────────────────────────────────────────────────

    @Test void countDistinct() throws SQLException {
        exec("CREATE TABLE t (val int)");
        exec("INSERT INTO t VALUES (1),(1),(2),(3),(3),(3)");
        QueryResult r = q("SELECT COUNT(DISTINCT val) FROM t");
        assertEquals(3L, ((Number) r.rows().get(0)[0]).longValue());
    }

    @Test void aggregateOnEmpty() throws SQLException {
        exec("CREATE TABLE t (val int)");
        QueryResult r = q("SELECT COUNT(*), SUM(val), AVG(val), MIN(val), MAX(val) FROM t");
        assertEquals(1, r.rows().size());
        assertEquals(0L, ((Number) r.rows().get(0)[0]).longValue()); // COUNT(*) = 0
        assertNull(r.rows().get(0)[1]); // SUM = NULL
        assertNull(r.rows().get(0)[2]); // AVG = NULL
        assertNull(r.rows().get(0)[3]); // MIN = NULL
        assertNull(r.rows().get(0)[4]); // MAX = NULL
    }

    @Test void sumWithNulls() throws SQLException {
        exec("CREATE TABLE t (val int)");
        exec("INSERT INTO t VALUES (1),(NULL),(3)");
        QueryResult r = q("SELECT SUM(val) FROM t");
        assertEquals(4L, ((Number) r.rows().get(0)[0]).longValue());
    }

    // ── Boolean expressions ───────────────────────────────────────────────────

    @Test void booleanAndOr() throws SQLException {
        QueryResult r = q("SELECT true AND false, true OR false, NOT true");
        assertEquals(false, r.rows().get(0)[0]);
        assertEquals(true, r.rows().get(0)[1]);
        assertEquals(false, r.rows().get(0)[2]);
    }

    @Test void isNullIsNotNull() throws SQLException {
        exec("CREATE TABLE t (id int, val text)");
        exec("INSERT INTO t VALUES (1, 'a'), (2, NULL), (3, 'c')");
        QueryResult r = q("SELECT id FROM t WHERE val IS NULL");
        assertEquals(1, r.rows().size());
        assertEquals(2, ((Number) r.rows().get(0)[0]).intValue());

        r = q("SELECT id FROM t WHERE val IS NOT NULL ORDER BY id");
        assertEquals(2, r.rows().size());
    }

    // ── BETWEEN ───────────────────────────────────────────────────────────────

    @Test void between() throws SQLException {
        exec("CREATE TABLE t (val int)");
        exec("INSERT INTO t VALUES (1),(5),(10),(15),(20)");
        QueryResult r = q("SELECT val FROM t WHERE val BETWEEN 5 AND 15 ORDER BY val");
        assertEquals(3, r.rows().size());
        assertEquals(5,  ((Number) r.rows().get(0)[0]).intValue());
        assertEquals(10, ((Number) r.rows().get(1)[0]).intValue());
        assertEquals(15, ((Number) r.rows().get(2)[0]).intValue());
    }

    // ── LIKE / ILIKE ──────────────────────────────────────────────────────────

    @Test void likePattern() throws SQLException {
        exec("CREATE TABLE t (name text)");
        exec("INSERT INTO t VALUES ('alice'),('bob'),('ALICE'),('carol')");
        QueryResult r = q("SELECT name FROM t WHERE name LIKE 'a%' ORDER BY name");
        assertEquals(1, r.rows().size());
        assertEquals("alice", r.rows().get(0)[0]);
    }

    @Test void ilikePattern() throws SQLException {
        exec("CREATE TABLE t (name text)");
        exec("INSERT INTO t VALUES ('alice'),('bob'),('ALICE'),('carol')");
        QueryResult r = q("SELECT name FROM t WHERE name ILIKE 'a%' ORDER BY name");
        assertEquals(2, r.rows().size());
    }

    // ── IN list (non-subquery) ────────────────────────────────────────────────

    @Test void inList() throws SQLException {
        exec("CREATE TABLE t (val int)");
        exec("INSERT INTO t VALUES (1),(2),(3),(4),(5)");
        QueryResult r = q("SELECT val FROM t WHERE val IN (2, 4) ORDER BY val");
        assertEquals(2, r.rows().size());
        assertEquals(2, ((Number) r.rows().get(0)[0]).intValue());
        assertEquals(4, ((Number) r.rows().get(1)[0]).intValue());
    }

    @Test void notInList() throws SQLException {
        exec("CREATE TABLE t (val int)");
        exec("INSERT INTO t VALUES (1),(2),(3),(4),(5)");
        QueryResult r = q("SELECT val FROM t WHERE val NOT IN (2, 4) ORDER BY val");
        assertEquals(3, r.rows().size());
    }

    // ── Aliased subquery with join ────────────────────────────────────────────

    @Test void subqueryJoin() throws SQLException {
        exec("CREATE TABLE t (id int, val int)");
        exec("INSERT INTO t VALUES (1,10),(2,20),(3,30)");
        QueryResult r = q("""
            SELECT t.id, s.total
            FROM t
            INNER JOIN (SELECT SUM(val) AS total FROM t) AS s ON true
            ORDER BY t.id
            """);
        assertEquals(3, r.rows().size());
        for (Object[] row : r.rows()) {
            assertEquals(60L, ((Number) row[1]).longValue());
        }
    }
}
