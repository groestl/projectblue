package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;

/**
 * Golden tests for advanced aggregate features — validated against real PostgreSQL.
 *
 * Covers: FILTER clause, GROUPING SETS, CUBE, ROLLUP, DISTINCT aggregates,
 * ordered-set aggregates (string_agg/array_agg with ORDER BY), multiple
 * aggregates in one SELECT, HAVING with complex conditions, nested aggregates
 * via subquery, ORDER BY expression not in SELECT, GROUP BY expression.
 */
@ExtendWith(GoldenExtension.class)
class GoldenAggregateTest {

    // ── FILTER clause ─────────────────────────────────────────────────────────
    // pgjava carries aggFilter in the FunctionCall AST but AggAccumulator does
    // not yet check it — the filter predicate is silently ignored.
    // assumeSupported() keeps postgres as the correctness anchor.

    @Test void filterOnSum(DualExecutor db) throws Exception {
        db.assumeSupported(); // FILTER clause not yet evaluated in pgjava AggAccumulator
        db.execute("CREATE TABLE t (dept text, val int)");
        db.execute("INSERT INTO t VALUES ('eng',5),('eng',15),('eng',25),('mkt',8),('mkt',12)");
        db.assertQuery("""
            SELECT dept,
                   SUM(val) AS total,
                   SUM(val) FILTER (WHERE val > 10) AS big_sum
            FROM t GROUP BY dept ORDER BY dept
            """);
    }

    @Test void filterOnCount(DualExecutor db) throws Exception {
        db.assumeSupported(); // FILTER clause not yet evaluated in pgjava AggAccumulator
        db.execute("CREATE TABLE t (name text, score int, pass boolean)");
        db.execute("INSERT INTO t VALUES ('a',80,true),('b',45,false),('c',90,true),('d',30,false)");
        db.assertQuery("""
            SELECT COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE pass) AS passed,
                   COUNT(*) FILTER (WHERE NOT pass) AS failed
            FROM t
            """);
    }

    @Test void filterWithGroupBy(DualExecutor db) throws Exception {
        db.assumeSupported(); // FILTER clause not yet evaluated in pgjava AggAccumulator
        db.execute("CREATE TABLE sales (region text, amount int, is_online boolean)");
        db.execute("""
            INSERT INTO sales VALUES
            ('north',100,true),('north',200,false),('north',150,true),
            ('south',80,false),('south',120,true)
            """);
        db.assertQuery("""
            SELECT region,
                   SUM(amount) AS total,
                   SUM(amount) FILTER (WHERE is_online) AS online_total,
                   COUNT(*) FILTER (WHERE NOT is_online) AS offline_count
            FROM sales GROUP BY region ORDER BY region
            """);
    }

    // ── GROUPING SETS ─────────────────────────────────────────────────────────
    // pgjava does not yet implement GROUPING SETS / CUBE / ROLLUP — the AST
    // builder silently discards these clauses.  assumeSupported() keeps the
    // postgres side running as a correctness anchor while skipping pgjava.

    @Test void groupingSetsBasic(DualExecutor db) throws Exception {
        db.assumeSupported(); // GROUPING SETS not yet implemented in pgjava
        db.execute("CREATE TABLE t (a text, b text, val int)");
        db.execute("""
            INSERT INTO t VALUES
            ('x','p',1),('x','q',2),('y','p',3),('y','q',4)
            """);
        db.assertQuery("""
            SELECT a, b, SUM(val) AS total
            FROM t
            GROUP BY GROUPING SETS ((a), (b), ())
            ORDER BY a NULLS LAST, b NULLS LAST
            """);
    }

    @Test void groupingSetsWithNull(DualExecutor db) throws Exception {
        db.assumeSupported(); // GROUPING SETS not yet implemented in pgjava
        db.execute("CREATE TABLE t (dept text, region text, sales int)");
        db.execute("""
            INSERT INTO t VALUES
            ('eng','west',100),('eng','east',200),
            ('mkt','west',150),('mkt','east',50)
            """);
        db.assertQuery("""
            SELECT dept, region, SUM(sales) AS total
            FROM t
            GROUP BY GROUPING SETS ((dept, region), (dept), ())
            ORDER BY dept NULLS LAST, region NULLS LAST
            """);
    }

    // ── CUBE ─────────────────────────────────────────────────────────────────

    @Test void cubeBasic(DualExecutor db) throws Exception {
        db.assumeSupported(); // CUBE not yet implemented in pgjava
        db.execute("CREATE TABLE t (a text, b text, val int)");
        db.execute("""
            INSERT INTO t VALUES
            ('x','p',1),('x','q',2),('y','p',3),('y','q',4)
            """);
        db.assertQuery("""
            SELECT a, b, SUM(val) AS total
            FROM t
            GROUP BY CUBE (a, b)
            ORDER BY a NULLS LAST, b NULLS LAST
            """);
    }

    @Test void cubeThreeColumns(DualExecutor db) throws Exception {
        db.assumeSupported(); // CUBE not yet implemented in pgjava
        db.execute("CREATE TABLE t (a text, b text, c text, val int)");
        db.execute("""
            INSERT INTO t VALUES
            ('x','p','i',1),('x','p','j',2),('x','q','i',3),('y','p','i',4)
            """);
        db.assertQuery("""
            SELECT a, b, c, COUNT(*) AS cnt, SUM(val) AS total
            FROM t
            GROUP BY CUBE (a, b, c)
            ORDER BY a NULLS LAST, b NULLS LAST, c NULLS LAST
            """);
    }

    // ── ROLLUP ────────────────────────────────────────────────────────────────

    @Test void rollupBasic(DualExecutor db) throws Exception {
        db.assumeSupported(); // ROLLUP not yet implemented in pgjava
        db.execute("CREATE TABLE t (year int, quarter text, sales int)");
        db.execute("""
            INSERT INTO t VALUES
            (2023,'Q1',100),(2023,'Q2',200),(2023,'Q3',150),(2023,'Q4',250),
            (2024,'Q1',120),(2024,'Q2',180)
            """);
        db.assertQuery("""
            SELECT year, quarter, SUM(sales) AS total
            FROM t
            GROUP BY ROLLUP (year, quarter)
            ORDER BY year NULLS LAST, quarter NULLS LAST
            """);
    }

    @Test void rollupThreeLevels(DualExecutor db) throws Exception {
        db.assumeSupported(); // ROLLUP not yet implemented in pgjava
        db.execute("CREATE TABLE t (country text, state text, city text, sales int)");
        db.execute("""
            INSERT INTO t VALUES
            ('US','CA','LA',100),('US','CA','SF',200),
            ('US','NY','NYC',300),('EU','DE','BER',150)
            """);
        db.assertQuery("""
            SELECT country, state, city, SUM(sales) AS total
            FROM t
            GROUP BY ROLLUP (country, state, city)
            ORDER BY country NULLS LAST, state NULLS LAST, city NULLS LAST
            """);
    }

    // ── DISTINCT aggregates ───────────────────────────────────────────────────

    @Test void countDistinctColumn(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text, category text)");
        db.execute("""
            INSERT INTO t VALUES
            ('eng','backend'),('eng','frontend'),('eng','backend'),
            ('mkt','digital'),('mkt','print'),('mkt','digital')
            """);
        db.assertQuery("""
            SELECT dept,
                   COUNT(*) AS total,
                   COUNT(DISTINCT category) AS unique_categories
            FROM t GROUP BY dept ORDER BY dept
            """);
    }

    @Test void sumDistinct(DualExecutor db) throws Exception {
        db.assumeSupported(); // SUM(DISTINCT ...) not yet deduplicated in pgjava AggAccumulator
        db.execute("CREATE TABLE t (tag text, val int)");
        db.execute("INSERT INTO t VALUES ('a',10),('a',10),('b',20),('b',30)");
        db.assertQuery("""
            SELECT tag,
                   SUM(val) AS sum_all,
                   SUM(DISTINCT val) AS sum_distinct
            FROM t GROUP BY tag ORDER BY tag
            """);
    }

    // ── string_agg with ORDER BY ──────────────────────────────────────────────

    @Test void stringAggOrderBy(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text, name text, seniority int)");
        db.execute("""
            INSERT INTO t VALUES
            ('eng','charlie',3),('eng','alice',1),('eng','bob',2),
            ('mkt','eve',2),('mkt','dave',1)
            """);
        db.assertQuery("""
            SELECT dept,
                   string_agg(name, ',' ORDER BY seniority) AS members_by_seniority
            FROM t GROUP BY dept ORDER BY dept
            """);
    }

    @Test void stringAggDistinct(DualExecutor db) throws Exception {
        db.assumeSupported(); // string_agg(DISTINCT ...) not yet deduplicated in pgjava
        db.execute("CREATE TABLE t (group_name text, tag text)");
        db.execute("""
            INSERT INTO t VALUES
            ('g1','alpha'),('g1','beta'),('g1','alpha'),
            ('g2','gamma'),('g2','beta')
            """);
        db.assertQuery("""
            SELECT group_name,
                   string_agg(DISTINCT tag, ',' ORDER BY tag) AS unique_tags
            FROM t GROUP BY group_name ORDER BY group_name
            """);
    }

    // ── array_agg with ORDER BY ───────────────────────────────────────────────

    @Test void arrayAggOrderBy(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (category text, item text, priority int)");
        db.execute("""
            INSERT INTO t VALUES
            ('food','banana',2),('food','apple',1),('food','cherry',3),
            ('tech','phone',1),('tech','laptop',2)
            """);
        db.assertQuery("""
            SELECT category,
                   array_agg(item ORDER BY priority) AS ordered_items
            FROM t GROUP BY category ORDER BY category
            """);
    }

    // ── Multiple aggregate functions in one SELECT ────────────────────────────

    @Test void multipleAggregatesInSelect(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text, salary int)");
        db.execute("""
            INSERT INTO t VALUES
            ('eng',80000),('eng',120000),('eng',100000),
            ('mkt',70000),('mkt',90000)
            """);
        db.assertQuery("""
            SELECT dept,
                   COUNT(*) AS headcount,
                   MIN(salary) AS min_sal,
                   MAX(salary) AS max_sal,
                   SUM(salary) AS total_sal,
                   AVG(salary)::int AS avg_sal
            FROM t GROUP BY dept ORDER BY dept
            """);
    }

    @Test void mixedAggregatesNoGroup(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (n int)");
        db.execute("INSERT INTO t VALUES (1),(2),(3),(4),(5),(NULL)");
        db.assertQuery("""
            SELECT COUNT(*) AS cnt_all,
                   COUNT(n) AS cnt_notnull,
                   SUM(n) AS total,
                   MIN(n) AS minimum,
                   MAX(n) AS maximum
            FROM t
            """);
    }

    // ── HAVING with complex conditions ────────────────────────────────────────

    @Test void havingComplexCondition(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text, category text, val int)");
        db.execute("""
            INSERT INTO t VALUES
            ('eng','a',10),('eng','a',20),('eng','b',5),
            ('mkt','a',30),('mkt','b',15),('mkt','b',25),
            ('hr','a',5)
            """);
        db.assertQuery("""
            SELECT dept,
                   COUNT(*) AS cnt,
                   SUM(val) AS total
            FROM t
            GROUP BY dept
            HAVING COUNT(*) >= 3 AND SUM(val) > 30
            ORDER BY dept
            """);
    }

    @Test void havingWithDistinctCount(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (region text, product text, sales int)");
        db.execute("""
            INSERT INTO t VALUES
            ('west','a',10),('west','a',20),('west','b',30),
            ('east','a',5),('east','b',15),('east','c',25),
            ('north','a',100)
            """);
        db.assertQuery("""
            SELECT region, COUNT(DISTINCT product) AS product_count, SUM(sales) AS total
            FROM t
            GROUP BY region
            HAVING COUNT(DISTINCT product) >= 2
            ORDER BY region
            """);
    }

    // ── Nested aggregates via subquery ────────────────────────────────────────

    @Test void nestedAggregateViaSubquery(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text, salary int)");
        db.execute("""
            INSERT INTO t VALUES
            ('eng',100),('eng',200),('mkt',150),('mkt',50),('hr',80)
            """);
        // Average of department sums (aggregate of aggregates via subquery)
        db.assertQuery("""
            SELECT AVG(dept_total)::int AS avg_dept_total
            FROM (
                SELECT dept, SUM(salary) AS dept_total
                FROM t GROUP BY dept
            ) AS dept_sums
            """);
    }

    @Test void maxOfGroupCounts(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (category text, item text)");
        db.execute("""
            INSERT INTO t VALUES
            ('a','x'),('a','y'),('a','z'),
            ('b','x'),('b','y'),
            ('c','x')
            """);
        db.assertQuery("""
            SELECT MAX(cnt) AS max_category_size
            FROM (
                SELECT category, COUNT(*) AS cnt FROM t GROUP BY category
            ) AS category_counts
            """);
    }

    // ── ORDER BY expression not in SELECT ─────────────────────────────────────

    @Test void orderByExpressionNotInSelect(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (name text, first_name text, last_name text)");
        db.execute("""
            INSERT INTO t VALUES ('carol','Carol','Smith'),('alice','Alice','Jones'),('bob','Bob','Adams')
            """);
        // ORDER BY concatenation expression not present in SELECT list
        db.assertQuery("""
            SELECT name FROM t ORDER BY last_name || ',' || first_name
            """);
    }

    @Test void groupByWithOrderByDifferentExpr(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text, salary int)");
        db.execute("""
            INSERT INTO t VALUES ('eng',100),('eng',200),('mkt',150),('hr',50)
            """);
        db.assertQuery("""
            SELECT dept, COUNT(*) AS cnt
            FROM t
            GROUP BY dept
            ORDER BY SUM(salary) DESC
            """);
    }

    // ── GROUP BY expression (not just column name) ─────────────────────────────

    @Test void groupByExpression(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (ts int, val int)");
        db.execute("""
            INSERT INTO t VALUES (1,10),(2,20),(3,30),(4,40),(5,50),(6,60)
            """);
        // Group by even/odd
        db.assertQuery("""
            SELECT ts % 2 AS parity, COUNT(*) AS cnt, SUM(val) AS total
            FROM t
            GROUP BY ts % 2
            ORDER BY parity
            """);
    }

    @Test void groupByLengthExpression(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (word text, freq int)");
        db.execute("""
            INSERT INTO t VALUES ('hi',5),('hey',3),('hello',2),('yo',8),('yes',1),('howdy',4)
            """);
        db.assertQuery("""
            SELECT LENGTH(word) AS word_len, COUNT(*) AS cnt, SUM(freq) AS total_freq
            FROM t
            GROUP BY LENGTH(word)
            ORDER BY word_len
            """);
    }

    @Test void groupByCaseExpression(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (score int, student text)");
        db.execute("""
            INSERT INTO t VALUES (95,'alice'),(82,'bob'),(67,'carol'),(45,'dave'),(78,'eve')
            """);
        db.assertQuery("""
            SELECT
                CASE WHEN score >= 90 THEN 'A'
                     WHEN score >= 80 THEN 'B'
                     WHEN score >= 70 THEN 'C'
                     ELSE 'F' END AS grade,
                COUNT(*) AS students
            FROM t
            GROUP BY CASE WHEN score >= 90 THEN 'A'
                          WHEN score >= 80 THEN 'B'
                          WHEN score >= 70 THEN 'C'
                          ELSE 'F' END
            ORDER BY grade
            """);
    }
}
