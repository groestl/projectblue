package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;

/**
 * Golden tests: core SQL features validated against real PostgreSQL.
 *
 * Every {@code assertQuery} runs on both pgjava and embedded-postgres and
 * fails if results diverge. Covers: CRUD, joins, aggregates, subqueries,
 * CTEs, window functions, DISTINCT ON, LATERAL, NULL semantics, type ops.
 */
@ExtendWith(GoldenExtension.class)
class GoldenCrudTest {

    // ── Basic SELECT ──────────────────────────────────────────────────────────

    @Test void selectConstant(DualExecutor db) throws Exception {
        db.assertQuery("SELECT 1");
    }

    @Test void selectArithmetic(DualExecutor db) throws Exception {
        db.assertQuery("SELECT 2 + 3 * 4 - 1 AS result");
    }

    @Test void selectStringLiteral(DualExecutor db) throws Exception {
        db.assertQuery("SELECT 'hello' || ' ' || 'world' AS greeting");
    }

    @Test void selectNullHandling(DualExecutor db) throws Exception {
        db.assertQuery("SELECT NULL IS NULL AS a, NULL IS NOT NULL AS b, COALESCE(NULL, 42) AS c");
    }

    // ── INSERT + SELECT ───────────────────────────────────────────────────────

    @Test void basicInsertSelect(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, name text)");
        db.execute("INSERT INTO t VALUES (1,'alice'),(2,'bob'),(3,'carol')");
        db.assertQuery("SELECT id, name FROM t ORDER BY id");
    }

    @Test void insertSelectStar(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE nums (n int)");
        db.execute("INSERT INTO nums VALUES (10),(20),(30)");
        db.assertQuery("SELECT * FROM nums ORDER BY n");
    }

    // ── WHERE clause ─────────────────────────────────────────────────────────

    @Test void whereEquals(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, val text)");
        db.execute("INSERT INTO t VALUES (1,'a'),(2,'b'),(3,'a')");
        db.assertQuery("SELECT id FROM t WHERE val = 'a' ORDER BY id");
    }

    @Test void whereBetween(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (n int)");
        db.execute("INSERT INTO t VALUES (1),(2),(3),(4),(5)");
        db.assertQuery("SELECT n FROM t WHERE n BETWEEN 2 AND 4 ORDER BY n");
    }

    @Test void whereIn(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (n int)");
        db.execute("INSERT INTO t VALUES (1),(2),(3),(4),(5)");
        db.assertQuery("SELECT n FROM t WHERE n IN (1,3,5) ORDER BY n");
    }

    @Test void whereLike(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (s text)");
        db.execute("INSERT INTO t VALUES ('foo'),('foobar'),('bar'),('baz')");
        db.assertQuery("SELECT s FROM t WHERE s LIKE 'foo%' ORDER BY s");
    }

    @Test void whereIsNull(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (n int)");
        db.execute("INSERT INTO t VALUES (1),(NULL),(3),(NULL)");
        db.assertQuery("SELECT COUNT(*) AS nulls FROM t WHERE n IS NULL");
        db.assertQuery("SELECT COUNT(*) AS notnulls FROM t WHERE n IS NOT NULL");
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────

    @Test void update(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, val int)");
        db.execute("INSERT INTO t VALUES (1,10),(2,20),(3,30)");
        db.execute("UPDATE t SET val = val * 2 WHERE id > 1");
        db.assertQuery("SELECT id, val FROM t ORDER BY id");
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    @Test void delete(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, val text)");
        db.execute("INSERT INTO t VALUES (1,'a'),(2,'b'),(3,'c')");
        db.execute("DELETE FROM t WHERE id = 2");
        db.assertQuery("SELECT id FROM t ORDER BY id");
    }

    // ── Aggregates ────────────────────────────────────────────────────────────

    @Test void countSumAvgMinMax(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (n int)");
        db.execute("INSERT INTO t VALUES (1),(2),(3),(4),(5)");
        db.assertQuery("SELECT COUNT(*), SUM(n), MIN(n), MAX(n) FROM t");
    }

    @Test void groupBy(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text, salary int)");
        db.execute("INSERT INTO t VALUES ('eng',100),('eng',200),('mkt',150),('mkt',50)");
        db.assertQuery("SELECT dept, SUM(salary) AS total FROM t GROUP BY dept ORDER BY dept");
    }

    @Test void groupByHaving(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text, salary int)");
        db.execute("INSERT INTO t VALUES ('eng',100),('eng',200),('mkt',50)");
        db.assertQuery("""
            SELECT dept, SUM(salary) AS total
            FROM t GROUP BY dept HAVING SUM(salary) > 100
            ORDER BY dept
            """);
    }

    @Test void countDistinct(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text)");
        db.execute("INSERT INTO t VALUES ('a'),('b'),('a'),('c'),('b')");
        db.assertQuery("SELECT COUNT(DISTINCT dept) FROM t");
    }

    // ── JOINs ─────────────────────────────────────────────────────────────────

    @Test void innerJoin(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE dept (id int, name text)");
        db.execute("CREATE TABLE emp (id int, dept_id int, name text)");
        db.execute("INSERT INTO dept VALUES (1,'eng'),(2,'mkt')");
        db.execute("INSERT INTO emp VALUES (1,1,'alice'),(2,1,'bob'),(3,2,'carol')");
        db.assertQuery("""
            SELECT e.name, d.name AS dept
            FROM emp e JOIN dept d ON e.dept_id = d.id
            ORDER BY e.name
            """);
    }

    @Test void leftJoin(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE dept (id int, name text)");
        db.execute("CREATE TABLE emp (id int, dept_id int, name text)");
        db.execute("INSERT INTO dept VALUES (1,'eng'),(2,'mkt'),(3,'hr')");
        db.execute("INSERT INTO emp VALUES (1,1,'alice'),(2,1,'bob')");
        db.assertQuery("""
            SELECT d.name, COUNT(e.id) AS headcount
            FROM dept d LEFT JOIN emp e ON e.dept_id = d.id
            GROUP BY d.name ORDER BY d.name
            """);
    }

    @Test void selfJoin(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE emp (id int, name text, manager_id int)");
        db.execute("INSERT INTO emp VALUES (1,'ceo',NULL),(2,'vp',1),(3,'mgr',1),(4,'dev',2)");
        db.assertQuery("""
            SELECT e.name, m.name AS manager
            FROM emp e LEFT JOIN emp m ON e.manager_id = m.id
            ORDER BY e.id
            """);
    }

    // ── Subqueries ────────────────────────────────────────────────────────────

    @Test void subqueryInWhere(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE dept (id int, name text)");
        db.execute("CREATE TABLE emp (id int, dept_id int, name text, salary int)");
        db.execute("INSERT INTO dept VALUES (1,'eng'),(2,'mkt')");
        db.execute("INSERT INTO emp VALUES (1,1,'alice',100),(2,1,'bob',200),(3,2,'carol',150)");
        db.assertQuery("""
            SELECT name FROM emp
            WHERE dept_id IN (SELECT id FROM dept WHERE name = 'eng')
            ORDER BY name
            """);
    }

    @Test void existsSubquery(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id int)");
        db.execute("CREATE TABLE child (parent_id int, val int)");
        db.execute("INSERT INTO parent VALUES (1),(2),(3)");
        db.execute("INSERT INTO child VALUES (1,10),(1,20),(3,30)");
        db.assertQuery("""
            SELECT id FROM parent
            WHERE EXISTS (SELECT 1 FROM child WHERE child.parent_id = parent.id)
            ORDER BY id
            """);
    }

    @Test void scalarSubquery(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, val int)");
        db.execute("INSERT INTO t VALUES (1,10),(2,20),(3,30)");
        db.assertQuery("""
            SELECT id, val,
                   (SELECT MAX(val) FROM t) AS max_val
            FROM t ORDER BY id
            """);
    }

    @Test void correlatedSubquery(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text, salary int)");
        db.execute("INSERT INTO t VALUES ('eng',100),('eng',200),('mkt',150),('mkt',50)");
        db.assertQuery("""
            SELECT dept, salary
            FROM t outer_t
            WHERE salary = (SELECT MAX(salary) FROM t WHERE dept = outer_t.dept)
            ORDER BY dept
            """);
    }

    // ── CTEs ─────────────────────────────────────────────────────────────────

    @Test void basicCte(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, val int)");
        db.execute("INSERT INTO t VALUES (1,10),(2,20),(3,30)");
        db.assertQuery("""
            WITH big AS (SELECT * FROM t WHERE val >= 20)
            SELECT id, val FROM big ORDER BY id
            """);
    }

    @Test void chainedCtes(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (n int)");
        db.execute("INSERT INTO t VALUES (1),(2),(3),(4),(5)");
        db.assertQuery("""
            WITH evens AS (SELECT n FROM t WHERE n % 2 = 0),
                 doubled AS (SELECT n * 2 AS n FROM evens)
            SELECT n FROM doubled ORDER BY n
            """);
    }

    @Test void recursiveCteCounting(DualExecutor db) throws Exception {
        db.assertQuery("""
            WITH RECURSIVE cnt(n) AS (
                SELECT 1
                UNION ALL
                SELECT n + 1 FROM cnt WHERE n < 5
            )
            SELECT n FROM cnt ORDER BY n
            """);
    }

    // ── ORDER BY / LIMIT / OFFSET ─────────────────────────────────────────────

    @Test void orderByAlias(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (a int, b int)");
        db.execute("INSERT INTO t VALUES (1,3),(2,1),(3,2)");
        db.assertQuery("SELECT a, a + b AS total FROM t ORDER BY total");
    }

    @Test void orderByPosition(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (a text, b int)");
        db.execute("INSERT INTO t VALUES ('c',3),('a',1),('b',2)");
        db.assertQuery("SELECT a, b FROM t ORDER BY 2 DESC");
    }

    @Test void limitOffset(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (n int)");
        db.execute("INSERT INTO t VALUES (1),(2),(3),(4),(5)");
        db.assertQuery("SELECT n FROM t ORDER BY n LIMIT 3 OFFSET 1");
    }

    // ── DISTINCT ──────────────────────────────────────────────────────────────

    @Test void distinct(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text, n int)");
        db.execute("INSERT INTO t VALUES ('a',1),('b',2),('a',3),('b',4)");
        db.assertQuery("SELECT DISTINCT dept FROM t ORDER BY dept");
    }

    @Test void distinctOn(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text, salary int, name text)");
        db.execute("INSERT INTO t VALUES ('eng',200,'bob'),('eng',100,'alice'),('mkt',150,'carol')");
        db.assertQuery("""
            SELECT DISTINCT ON (dept) dept, salary, name
            FROM t ORDER BY dept, salary DESC
            """);
    }

    // ── Window functions ──────────────────────────────────────────────────────

    @Test void rowNumber(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text, salary int)");
        db.execute("INSERT INTO t VALUES ('eng',100),('eng',200),('mkt',150)");
        db.assertQuery("""
            SELECT dept, salary, ROW_NUMBER() OVER (PARTITION BY dept ORDER BY salary) AS rn
            FROM t ORDER BY dept, salary
            """);
    }

    @Test void rankWindow(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (val int)");
        db.execute("INSERT INTO t VALUES (10),(10),(20),(30)");
        db.assertQuery("SELECT val, RANK() OVER (ORDER BY val) AS rk FROM t ORDER BY val");
    }

    @Test void lagLead(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (ts int, val int)");
        db.execute("INSERT INTO t VALUES (1,10),(2,20),(3,30)");
        db.assertQuery("""
            SELECT ts, val,
                   LAG(val, 1, 0) OVER (ORDER BY ts) AS prev,
                   LEAD(val, 1, 0) OVER (ORDER BY ts) AS nxt
            FROM t ORDER BY ts
            """);
    }

    @Test void sumOverPartition(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text, salary int)");
        db.execute("INSERT INTO t VALUES ('eng',100),('eng',200),('mkt',150)");
        db.assertQuery("""
            SELECT dept, salary, SUM(salary) OVER (PARTITION BY dept) AS dept_total
            FROM t ORDER BY dept, salary
            """);
    }

    // ── LATERAL ───────────────────────────────────────────────────────────────

    @Test void lateral(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE dept (id int, name text)");
        db.execute("CREATE TABLE emp (dept_id int, name text, salary int)");
        db.execute("INSERT INTO dept VALUES (1,'eng'),(2,'mkt')");
        db.execute("INSERT INTO emp VALUES (1,'alice',100),(1,'bob',200),(2,'carol',150)");
        db.assertQuery("""
            SELECT d.name, top.name AS top_emp
            FROM dept d,
            LATERAL (
                SELECT name FROM emp WHERE dept_id = d.id ORDER BY salary DESC LIMIT 1
            ) AS top
            ORDER BY d.name
            """);
    }

    // ── NULL semantics ────────────────────────────────────────────────────────

    @Test void nullArithmetic(DualExecutor db) throws Exception {
        db.assertQuery("SELECT 1 + NULL AS a, NULL * 0 AS b, NULL = NULL AS c");
    }

    @Test void coalesceNullif(DualExecutor db) throws Exception {
        db.assertQuery("""
            SELECT
                COALESCE(NULL, NULL, 3)  AS c1,
                COALESCE(1, 2)           AS c2,
                NULLIF(1, 1)             AS n1,
                NULLIF(1, 2)             AS n2
            """);
    }

    @Test void nullGrouping(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text, val int)");
        db.execute("INSERT INTO t VALUES ('a',1),(NULL,2),('a',3),(NULL,4)");
        db.assertQuery("SELECT dept, SUM(val) FROM t GROUP BY dept ORDER BY dept NULLS LAST");
    }

    // ── String functions ──────────────────────────────────────────────────────

    @Test void stringFunctions(DualExecutor db) throws Exception {
        db.assertQuery("""
            SELECT
                upper('hello')           AS u,
                lower('WORLD')           AS l,
                length('foo')            AS len,
                trim('  bar  ')          AS tr,
                substring('hello', 2, 3) AS sub,
                'foo' || 'bar'           AS cat,
                replace('abcabc', 'a', 'x') AS rep
            """);
    }

    @Test void likeIlike(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (s text)");
        db.execute("INSERT INTO t VALUES ('hello'),('HELLO'),('Hello'),('world')");
        // Count matches — avoids collation-dependent ordering of equal-case-fold strings
        db.assertQuery("SELECT COUNT(*) FROM t WHERE s ILIKE 'hello'");
        db.assertQuery("SELECT COUNT(*) FROM t WHERE s LIKE 'hello'");
        db.assertQuery("SELECT s FROM t WHERE s LIKE 'hel%' ORDER BY s");
    }

    // ── Numeric / type operations ─────────────────────────────────────────────

    @Test void numericFunctions(DualExecutor db) throws Exception {
        db.assertQuery("""
            SELECT
                abs(-5)           AS a,
                ceil(1.3)         AS c,
                floor(1.7)        AS f,
                round(1.5)        AS r,
                mod(10, 3)        AS m
            """);
    }

    @Test void casting(DualExecutor db) throws Exception {
        db.assertQuery("""
            SELECT
                42::text             AS i2t,
                '42'::int            AS t2i,
                '3.14'::float8       AS t2f,
                true::int            AS b2i,
                1::boolean           AS i2b
            """);
    }

    @Test void caseExpression(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (n int)");
        db.execute("INSERT INTO t VALUES (-1),(0),(1),(2)");
        db.assertQuery("""
            SELECT n,
                   CASE WHEN n < 0 THEN 'negative'
                        WHEN n = 0 THEN 'zero'
                        ELSE 'positive' END AS sign
            FROM t ORDER BY n
            """);
    }

    // ── Set operations ────────────────────────────────────────────────────────

    @Test void unionAll(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE a (n int)");
        db.execute("CREATE TABLE b (n int)");
        db.execute("INSERT INTO a VALUES (1),(2),(3)");
        db.execute("INSERT INTO b VALUES (2),(3),(4)");
        db.assertQuery("SELECT n FROM a UNION ALL SELECT n FROM b ORDER BY n");
    }

    @Test void unionDistinct(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE a (n int)");
        db.execute("CREATE TABLE b (n int)");
        db.execute("INSERT INTO a VALUES (1),(2),(3)");
        db.execute("INSERT INTO b VALUES (2),(3),(4)");
        db.assertQuery("SELECT n FROM a UNION SELECT n FROM b ORDER BY n");
    }

    @Test void intersectExcept(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE a (n int)");
        db.execute("CREATE TABLE b (n int)");
        db.execute("INSERT INTO a VALUES (1),(2),(3)");
        db.execute("INSERT INTO b VALUES (2),(3),(4)");
        db.assertQuery("SELECT n FROM a INTERSECT SELECT n FROM b ORDER BY n");
        db.assertQuery("SELECT n FROM a EXCEPT SELECT n FROM b ORDER BY n");
    }

    // ── ON CONFLICT ───────────────────────────────────────────────────────────

    @Test void onConflictDoNothing(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int PRIMARY KEY, val text)");
        db.execute("INSERT INTO t VALUES (1,'a')");
        db.execute("INSERT INTO t VALUES (1,'b') ON CONFLICT DO NOTHING");
        db.assertQuery("SELECT id, val FROM t");
    }

    @Test void onConflictDoUpdate(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int PRIMARY KEY, cnt int)");
        db.execute("INSERT INTO t VALUES (1,5)");
        db.execute("INSERT INTO t VALUES (1,3) ON CONFLICT (id) DO UPDATE SET cnt = t.cnt + EXCLUDED.cnt");
        db.assertQuery("SELECT id, cnt FROM t");
    }

    // ── Larger dataset ────────────────────────────────────────────────────────

    @Test void largerDataset(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, dept text, salary int)");
        StringBuilder ins = new StringBuilder("INSERT INTO t VALUES ");
        String[] depts = {"eng", "mkt", "hr", "fin"};
        for (int i = 1; i <= 100; i++) {
            if (i > 1) ins.append(',');
            ins.append('(').append(i).append(",'").append(depts[i % 4]).append("',")
               .append(50000 + (i * 317) % 50000).append(')');
        }
        db.execute(ins.toString());
        db.assertQuery("""
            SELECT dept, COUNT(*) AS cnt, AVG(salary)::int AS avg_sal
            FROM t GROUP BY dept ORDER BY dept
            """);
        db.assertQuery("SELECT COUNT(*) FROM t WHERE salary > 75000");
        db.assertQuery("SELECT id, salary FROM t ORDER BY salary DESC LIMIT 5");
    }

    // ── Collation ─────────────────────────────────────────────────────────────

    @Test void collationCOrderBy(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t_coll (name text)");
        db.execute("INSERT INTO t_coll VALUES ('banana'),('Apple'),('cherry'),('avocado')");
        db.assertQuery("SELECT name FROM t_coll ORDER BY name COLLATE \"C\"");
    }

    @Test void collationCComparison(DualExecutor db) throws Exception {
        db.assertQuery("SELECT 'a' > 'B' COLLATE \"C\"");
        db.assertQuery("SELECT 'Z' COLLATE \"C\" < 'a' COLLATE \"C\"");
    }

    @Test void columnCollateC(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t_colc (name text COLLATE \"C\")");
        db.execute("INSERT INTO t_colc VALUES ('banana'),('Apple'),('cherry'),('avocado')");
        db.assertQuery("SELECT name FROM t_colc ORDER BY name");
    }

    // ── JSON/JSONB operators ──────────────────────────────────────────────────

    @Test void jsonExtractOperators(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '{\"a\":1,\"b\":\"hello\"}'::jsonb -> 'a'");
        db.assertQuery("SELECT '{\"a\":1,\"b\":\"hello\"}'::jsonb ->> 'b'");
        db.assertQuery("SELECT '[10,20,30]'::jsonb -> 1");
        db.assertQuery("SELECT '[10,20,30]'::jsonb ->> 0");
    }

    @Test void jsonPathOperators(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '{\"a\":{\"b\":42}}'::jsonb #> ARRAY['a','b']");
        db.assertQuery("SELECT '{\"a\":{\"b\":42}}'::jsonb #>> ARRAY['a','b']");
    }

    @Test void jsonContainment(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '{\"a\":1,\"b\":2}'::jsonb @> '{\"a\":1}'::jsonb");
        db.assertQuery("SELECT '{\"a\":1}'::jsonb <@ '{\"a\":1,\"b\":2}'::jsonb");
        db.assertQuery("SELECT '[1,2,3]'::jsonb @> '[1,3]'::jsonb");
    }

    @Test void jsonKeyExists(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '{\"a\":1,\"b\":2}'::jsonb ? 'a'");
        db.assertQuery("SELECT '{\"a\":1}'::jsonb ? 'missing'");
    }

    @Test void jsonFunctions(DualExecutor db) throws Exception {
        db.assertQuery("SELECT jsonb_typeof('{}'::jsonb)");
        db.assertQuery("SELECT jsonb_typeof('[]'::jsonb)");
        db.assertQuery("SELECT jsonb_typeof('\"hello\"'::jsonb)");
        db.assertQuery("SELECT jsonb_typeof('42'::jsonb)");
        db.assertQuery("SELECT jsonb_array_length('[1,2,3]'::jsonb)");
    }

    @Test void jsonbColumnQuery(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE jdocs (id serial PRIMARY KEY, data jsonb)");
        db.execute("""
                INSERT INTO jdocs (data) VALUES
                ('{"name":"Alice","age":30}'),
                ('{"name":"Bob","age":25}')
                """);
        db.assertQuery("SELECT data ->> 'name' AS name FROM jdocs ORDER BY data ->> 'name'");
        db.assertQuery("SELECT data ->> 'name' FROM jdocs WHERE data @> '{\"age\":25}'::jsonb");
    }

    // ── CREATE FUNCTION (SQL language) ──────────────────────────────────────

    @Test void createFunctionConstant(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION answer() RETURNS integer AS $$
                    SELECT 42
                $$ LANGUAGE sql
                """);
        db.assertQuery("SELECT answer()");
    }

    @Test void createFunctionWithParams(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION add_ints(a integer, b integer) RETURNS integer AS $$
                    SELECT $1 + $2
                $$ LANGUAGE sql
                """);
        db.assertQuery("SELECT add_ints(3, 4)");
    }

    @Test void createFunctionQueryingTable(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE emp (id serial PRIMARY KEY, name text, salary integer)");
        db.execute("INSERT INTO emp (name, salary) VALUES ('Alice', 100000), ('Bob', 80000)");
        db.execute("""
                CREATE FUNCTION max_sal() RETURNS integer AS $$
                    SELECT MAX(salary) FROM emp
                $$ LANGUAGE sql
                """);
        db.assertQuery("SELECT max_sal()");
    }

    @Test void createOrReplaceFunction(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION val() RETURNS integer AS $$
                    SELECT 1
                $$ LANGUAGE sql
                """);
        db.execute("""
                CREATE OR REPLACE FUNCTION val() RETURNS integer AS $$
                    SELECT 2
                $$ LANGUAGE sql
                """);
        db.assertQuery("SELECT val()");
    }

    @Test void dropFunctionWithArgTypes(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION dftest(a integer, b integer) RETURNS integer AS $$
                    SELECT $1 + $2
                $$ LANGUAGE sql
                """);
        db.execute("""
                CREATE FUNCTION dftest(a text) RETURNS text AS $$
                    SELECT $1
                $$ LANGUAGE sql
                """);
        db.execute("DROP FUNCTION dftest(integer, integer)");
        db.assertQuery("SELECT dftest('still here')");
    }

    @Test void dropFunctionBasic(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION todrop() RETURNS integer AS $$
                    SELECT 42
                $$ LANGUAGE sql
                """);
        db.assertQuery("SELECT todrop()");
        db.execute("DROP FUNCTION todrop()");
        db.assertError("SELECT todrop()", "42883");
    }

    // ── GENERATED ALWAYS AS (expr) STORED ───────────────────────────────────

    @Test void generatedColumnInsert(DualExecutor db) throws Exception {
        db.execute("""
                CREATE TABLE gc (
                    a integer,
                    b integer,
                    c integer GENERATED ALWAYS AS (a + b) STORED
                )
                """);
        db.execute("INSERT INTO gc (a, b) VALUES (3, 7)");
        db.assertQuery("SELECT c FROM gc");
    }

    @Test void generatedColumnUpdate(DualExecutor db) throws Exception {
        db.execute("""
                CREATE TABLE gc2 (
                    x integer,
                    doubled integer GENERATED ALWAYS AS (x * 2) STORED
                )
                """);
        db.execute("INSERT INTO gc2 (x) VALUES (5)");
        db.assertQuery("SELECT doubled FROM gc2");
        db.execute("UPDATE gc2 SET x = 10");
        db.assertQuery("SELECT doubled FROM gc2");
    }

    @Test void generatedColumnReturning(DualExecutor db) throws Exception {
        db.execute("""
                CREATE TABLE gc3 (
                    n integer,
                    sq integer GENERATED ALWAYS AS (n * n) STORED
                )
                """);
        db.assertQuery("INSERT INTO gc3 (n) VALUES (6) RETURNING sq");
    }
}
