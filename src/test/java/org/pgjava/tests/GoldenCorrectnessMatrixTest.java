package org.pgjava.tests;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;

import java.util.ArrayList;
import java.util.List;

/**
 * Exhaustive correctness matrix: systematic (type x operator x context)
 * combinations run against both pgjava and real PostgreSQL.
 *
 * <p>Every expression is aliased as {@code result} and tested via
 * {@link DualExecutor#assertQuery}. Any divergence from PostgreSQL is a bug.
 *
 * <p>Categories:
 * <ol>
 *   <li>Temporal comparisons (cross-type: timestamp vs timestamptz, date vs timestamp)</li>
 *   <li>Interval arithmetic (timestamp +/- interval, interval +/- interval, date + interval)</li>
 *   <li>Temporal subtraction (timestamp - timestamp, date - date, time - time)</li>
 *   <li>NaN / Infinity edge cases</li>
 *   <li>Division / modulo semantics (integer, float, numeric, division by zero)</li>
 *   <li>NULL propagation through every operator</li>
 *   <li>BETWEEN / IN with various types</li>
 *   <li>CASE with type coercion</li>
 *   <li>IS DISTINCT FROM / IS NOT DISTINCT FROM</li>
 *   <li>String-to-type implicit coercion in comparisons</li>
 *   <li>Boolean coercion and edge cases</li>
 *   <li>Aggregate functions (SUM, AVG, MIN, MAX, COUNT, ARRAY_AGG, STRING_AGG)</li>
 *   <li>Subquery expressions (EXISTS, IN, scalar subquery)</li>
 *   <li>Row comparisons</li>
 *   <li>EXTRACT from all temporal types</li>
 * </ol>
 */
@ExtendWith(GoldenExtension.class)
class GoldenCorrectnessMatrixTest {

    // =========================================================================
    // 1. Temporal comparisons
    // =========================================================================

    static List<String> temporalComparisons() {
        return List.of(
            // timestamp vs timestamp
            "SELECT TIMESTAMP '2024-01-01 00:00:00' < TIMESTAMP '2024-06-01 00:00:00' AS result",
            "SELECT TIMESTAMP '2024-06-01 00:00:00' > TIMESTAMP '2024-01-01 00:00:00' AS result",
            "SELECT TIMESTAMP '2024-01-01 12:00:00' = TIMESTAMP '2024-01-01 12:00:00' AS result",
            "SELECT TIMESTAMP '2024-01-01 12:00:00' <> TIMESTAMP '2024-01-01 12:00:01' AS result",
            "SELECT TIMESTAMP '2024-01-01 12:00:00' <= TIMESTAMP '2024-01-01 12:00:00' AS result",
            "SELECT TIMESTAMP '2024-01-01 12:00:00' >= TIMESTAMP '2024-01-01 12:00:00' AS result",

            // date vs date
            "SELECT DATE '2024-01-01' < DATE '2024-06-01' AS result",
            "SELECT DATE '2024-06-01' > DATE '2024-01-01' AS result",
            "SELECT DATE '2024-01-01' = DATE '2024-01-01' AS result",
            "SELECT DATE '2024-01-01' <> DATE '2024-01-02' AS result",

            // time vs time
            "SELECT TIME '10:00:00' < TIME '12:00:00' AS result",
            "SELECT TIME '12:00:00' > TIME '10:00:00' AS result",
            "SELECT TIME '10:00:00' = TIME '10:00:00' AS result",

            // timestamp ordering
            "SELECT TIMESTAMP '2024-01-15 10:00:00' BETWEEN TIMESTAMP '2024-01-01 00:00:00' AND TIMESTAMP '2024-12-31 23:59:59' AS result",
            "SELECT TIMESTAMP '2025-01-15 10:00:00' BETWEEN TIMESTAMP '2024-01-01 00:00:00' AND TIMESTAMP '2024-12-31 23:59:59' AS result",

            // date in list
            "SELECT DATE '2024-03-15' IN (DATE '2024-01-01', DATE '2024-03-15', DATE '2024-06-01') AS result",
            "SELECT DATE '2024-02-15' IN (DATE '2024-01-01', DATE '2024-03-15', DATE '2024-06-01') AS result",

            // GREATEST / LEAST with temporal
            "SELECT GREATEST(DATE '2024-01-01', DATE '2024-06-01', DATE '2024-03-15') AS result",
            "SELECT LEAST(DATE '2024-01-01', DATE '2024-06-01', DATE '2024-03-15') AS result",
            "SELECT GREATEST(TIMESTAMP '2024-01-01 10:00:00', TIMESTAMP '2024-01-01 08:00:00') AS result",
            "SELECT LEAST(TIMESTAMP '2024-01-01 10:00:00', TIMESTAMP '2024-01-01 08:00:00') AS result"
        );
    }

    @ParameterizedTest(name = "temporal: {0}")
    @MethodSource("temporalComparisons")
    void temporalComparison(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 2. Interval arithmetic
    // =========================================================================

    static List<String> intervalArithmetic() {
        return List.of(
            // timestamp + interval
            "SELECT TIMESTAMP '2024-01-15 10:00:00' + INTERVAL '1 day' AS result",
            "SELECT TIMESTAMP '2024-01-15 10:00:00' + INTERVAL '2 hours' AS result",
            "SELECT TIMESTAMP '2024-01-15 10:00:00' + INTERVAL '30 minutes' AS result",
            "SELECT TIMESTAMP '2024-01-31 10:00:00' + INTERVAL '1 month' AS result",
            "SELECT TIMESTAMP '2024-01-15 10:00:00' + INTERVAL '1 year' AS result",
            "SELECT TIMESTAMP '2024-01-15 10:00:00' + INTERVAL '1 day 2 hours 30 minutes' AS result",

            // timestamp - interval
            "SELECT TIMESTAMP '2024-03-01 12:00:00' - INTERVAL '1 day' AS result",
            "SELECT TIMESTAMP '2024-03-01 12:00:00' - INTERVAL '2 hours' AS result",
            "SELECT TIMESTAMP '2024-03-01 00:00:00' - INTERVAL '1 month' AS result",

            // date + interval
            "SELECT DATE '2024-01-01' + INTERVAL '1 month' AS result",
            "SELECT DATE '2024-01-01' + INTERVAL '1 year' AS result",
            "SELECT DATE '2024-01-01' + INTERVAL '7 days' AS result",

            // date + int (days)
            "SELECT DATE '2024-01-01' + 7 AS result",
            "SELECT DATE '2024-01-01' - 1 AS result",
            "SELECT 3 + DATE '2024-01-01' AS result",

            // interval + interval
            "SELECT INTERVAL '1 hour' + INTERVAL '30 minutes' AS result",
            "SELECT INTERVAL '1 day' + INTERVAL '12 hours' AS result",
            "SELECT INTERVAL '1 year' + INTERVAL '6 months' AS result",

            // interval - interval
            "SELECT INTERVAL '2 hours' - INTERVAL '30 minutes' AS result",
            "SELECT INTERVAL '1 day' - INTERVAL '1 hour' AS result",

            // date - date
            "SELECT DATE '2024-03-15' - DATE '2024-01-01' AS result",
            "SELECT DATE '2024-01-01' - DATE '2024-03-15' AS result"
        );
    }

    @ParameterizedTest(name = "interval: {0}")
    @MethodSource("intervalArithmetic")
    void intervalArith(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 3. NaN / Infinity
    // =========================================================================

    static List<String> nanInfinity() {
        return List.of(
            // Infinity comparisons
            "SELECT 'inf'::float8 > 1e308 AS result",
            "SELECT '-inf'::float8 < -1e308 AS result",
            "SELECT 'inf'::float8 = 'inf'::float8 AS result",
            "SELECT '-inf'::float8 = '-inf'::float8 AS result",
            "SELECT 'inf'::float8 > '-inf'::float8 AS result",
            "SELECT 'inf'::float8 <> '-inf'::float8 AS result",

            // Infinity arithmetic
            "SELECT 'inf'::float8 + 1 AS result",
            "SELECT 'inf'::float8 - 1 AS result",
            "SELECT 'inf'::float8 * 2 AS result",
            "SELECT 'inf'::float8 / 2 AS result",
            "SELECT '-inf'::float8 + 'inf'::float8 AS result",  // NaN
            "SELECT 'inf'::float8 - 'inf'::float8 AS result",    // NaN
            "SELECT 1.0 / 'inf'::float8 AS result",              // 0

            // NaN behavior
            "SELECT 'nan'::float8 = 'nan'::float8 AS result",    // false in SQL
            "SELECT 'nan'::float8 <> 'nan'::float8 AS result",   // true in SQL
            "SELECT 'nan'::float8 < 1.0 AS result",              // false
            "SELECT 'nan'::float8 > 1.0 AS result",              // false
            "SELECT 'nan'::float8 IS NULL AS result",             // false — NaN is not NULL

            // Infinity in aggregate
            "SELECT GREATEST('inf'::float8, 1.0, 2.0) AS result",
            "SELECT LEAST('-inf'::float8, -1.0, -2.0) AS result",

            // Infinity casts
            "SELECT 'inf'::float8::text AS result",
            "SELECT '-inf'::float8::text AS result",
            "SELECT 'nan'::float8::text AS result"
        );
    }

    @ParameterizedTest(name = "nan/inf: {0}")
    @MethodSource("nanInfinity")
    void nanInf(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 4. Division and modulo semantics
    // =========================================================================

    static List<String> divisionModulo() {
        return List.of(
            // Integer division truncates toward zero
            "SELECT 7 / 2 AS result",
            "SELECT -7 / 2 AS result",
            "SELECT 7 / -2 AS result",
            "SELECT -7 / -2 AS result",

            // Float division
            "SELECT 7.0 / 2.0 AS result",
            "SELECT -7.0 / 2.0 AS result",
            "SELECT 7.0 / -2.0 AS result",
            "SELECT 1.0::float8 / 3.0::float8 AS result",

            // Modulo
            "SELECT 7 % 3 AS result",
            "SELECT -7 % 3 AS result",
            "SELECT 7 % -3 AS result",
            "SELECT -7 % -3 AS result",
            "SELECT 7.5 % 2.5 AS result",

            // Integer vs float mixing
            "SELECT 7 / 2.0 AS result",
            "SELECT 7.0 / 2 AS result",

            // Power
            "SELECT power(2, 0) AS result",
            "SELECT power(2, -1) AS result",
            "SELECT power(0, 0) AS result",

            // Unary minus
            "SELECT -(-(-1)) AS result",
            "SELECT -(1 + 2) AS result"
        );
    }

    @ParameterizedTest(name = "div/mod: {0}")
    @MethodSource("divisionModulo")
    void divMod(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 5. NULL propagation — every operator
    // =========================================================================

    static List<String> nullPropagation() {
        return List.of(
            // Arithmetic with NULL
            "SELECT NULL::int + 1 AS result",
            "SELECT 1 + NULL::int AS result",
            "SELECT NULL::int - 1 AS result",
            "SELECT NULL::int * 1 AS result",
            "SELECT NULL::int / 1 AS result",
            "SELECT NULL::int % 1 AS result",

            // Comparison with NULL
            "SELECT NULL::int < 1 AS result",
            "SELECT NULL::int > 1 AS result",
            "SELECT NULL::int <= 1 AS result",
            "SELECT NULL::int >= 1 AS result",
            "SELECT NULL::int = 1 AS result",
            "SELECT NULL::int <> 1 AS result",

            // Boolean with NULL
            "SELECT NULL::bool AND true AS result",
            "SELECT NULL::bool AND false AS result",
            "SELECT NULL::bool OR true AS result",
            "SELECT NULL::bool OR false AS result",
            "SELECT NOT NULL::bool AS result",

            // String with NULL
            "SELECT NULL::text || 'hello' AS result",
            "SELECT 'hello' || NULL::text AS result",
            "SELECT length(NULL::text) AS result",
            "SELECT upper(NULL::text) AS result",

            // COALESCE / NULLIF edge cases
            "SELECT COALESCE(NULL::int, NULL::int, NULL::int) AS result",
            "SELECT NULLIF(NULL::int, NULL::int) AS result",
            "SELECT NULLIF(1, NULL::int) AS result",

            // CASE with NULL
            "SELECT CASE NULL::int WHEN 1 THEN 'one' ELSE 'other' END AS result",
            "SELECT CASE WHEN NULL::bool THEN 'yes' ELSE 'no' END AS result",

            // BETWEEN / IN with NULL
            "SELECT NULL::int BETWEEN 1 AND 10 AS result",
            "SELECT 5 BETWEEN NULL::int AND 10 AS result",
            "SELECT 5 BETWEEN 1 AND NULL::int AS result",
            "SELECT NULL::int IN (1, 2, 3) AS result",
            "SELECT 1 IN (NULL::int, 2, 3) AS result",
            "SELECT 4 IN (NULL::int, 2, 3) AS result",

            // GREATEST / LEAST with NULLs
            "SELECT GREATEST(1, NULL::int, 3) AS result",
            "SELECT LEAST(NULL::int, 2, 3) AS result",
            "SELECT GREATEST(NULL::int, NULL::int) AS result"
        );
    }

    @ParameterizedTest(name = "null: {0}")
    @MethodSource("nullPropagation")
    void nullProp(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 6. IS DISTINCT FROM / IS NOT DISTINCT FROM
    // =========================================================================

    static List<String> distinctFrom() {
        return List.of(
            "SELECT 1 IS DISTINCT FROM 2 AS result",
            "SELECT 1 IS DISTINCT FROM 1 AS result",
            "SELECT 1 IS DISTINCT FROM NULL AS result",
            "SELECT NULL IS DISTINCT FROM NULL AS result",
            "SELECT NULL IS DISTINCT FROM 1 AS result",

            "SELECT 1 IS NOT DISTINCT FROM 2 AS result",
            "SELECT 1 IS NOT DISTINCT FROM 1 AS result",
            "SELECT 1 IS NOT DISTINCT FROM NULL AS result",
            "SELECT NULL IS NOT DISTINCT FROM NULL AS result",
            "SELECT NULL IS NOT DISTINCT FROM 1 AS result",

            // With different types
            "SELECT 'hello' IS DISTINCT FROM 'hello' AS result",
            "SELECT 'hello' IS DISTINCT FROM 'world' AS result",
            "SELECT true IS DISTINCT FROM false AS result",
            "SELECT true IS DISTINCT FROM true AS result",
            "SELECT NULL::text IS DISTINCT FROM NULL::text AS result",
            "SELECT DATE '2024-01-01' IS DISTINCT FROM DATE '2024-01-01' AS result",
            "SELECT DATE '2024-01-01' IS DISTINCT FROM DATE '2024-06-01' AS result"
        );
    }

    @ParameterizedTest(name = "distinct: {0}")
    @MethodSource("distinctFrom")
    void distinctFromOps(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 7. Boolean edge cases
    // =========================================================================

    static List<String> booleanEdgeCases() {
        return List.of(
            // Short-circuit semantics
            "SELECT false AND (1/0 > 0) AS result",    // false, no div-by-zero error

            // Boolean casts
            "SELECT true::int AS result",
            "SELECT false::int AS result",
            "SELECT 1::boolean AS result",
            "SELECT 0::boolean AS result",
            "SELECT 't'::boolean AS result",
            "SELECT 'f'::boolean AS result",
            "SELECT 'true'::boolean AS result",
            "SELECT 'false'::boolean AS result",
            "SELECT 'yes'::boolean AS result",
            "SELECT 'no'::boolean AS result",
            "SELECT '1'::boolean AS result",
            "SELECT '0'::boolean AS result",

            // Boolean in comparisons
            "SELECT true > false AS result",
            "SELECT true = true AS result",
            "SELECT false < true AS result"
        );
    }

    @ParameterizedTest(name = "bool: {0}")
    @MethodSource("booleanEdgeCases")
    void boolEdge(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 8. String operations
    // =========================================================================

    static List<String> stringOperations() {
        return List.of(
            // Concatenation
            "SELECT 'a' || 'b' || 'c' AS result",
            "SELECT '' || '' AS result",
            "SELECT 'hello' || ' ' || 'world' AS result",

            // LIKE patterns
            "SELECT 'hello' LIKE 'hello' AS result",
            "SELECT 'hello' LIKE 'h%' AS result",
            "SELECT 'hello' LIKE '%o' AS result",
            "SELECT 'hello' LIKE '%ll%' AS result",
            "SELECT 'hello' LIKE 'h_llo' AS result",
            "SELECT 'hello' LIKE 'H%' AS result",        // case-sensitive

            // ILIKE
            "SELECT 'Hello' ILIKE 'hello' AS result",
            "SELECT 'Hello' ILIKE 'HELLO' AS result",
            "SELECT 'Hello' ILIKE 'h%' AS result",
            "SELECT 'Hello' ILIKE '%O' AS result",

            // NOT LIKE / NOT ILIKE
            "SELECT 'hello' NOT LIKE 'h%' AS result",
            "SELECT 'Hello' NOT ILIKE 'hello' AS result",

            // SIMILAR TO
            "SELECT 'abc' SIMILAR TO 'a%' AS result",
            "SELECT 'abc' SIMILAR TO '(a|b)%' AS result",
            "SELECT 'abc' SIMILAR TO 'x%' AS result",

            // String functions
            "SELECT chr(65) AS result",
            "SELECT ascii('A') AS result",
            "SELECT initcap('hello world') AS result",
            "SELECT md5('hello') AS result",
            "SELECT concat('a', 'b', 'c') AS result",
            "SELECT concat_ws(',', 'a', 'b', 'c') AS result",
            "SELECT concat_ws(',', 'a', NULL, 'c') AS result",

            // Substring with regex
            "SELECT substring('hello' FROM 2 FOR 3) AS result",
            "SELECT substring('hello' FROM 2) AS result",

            // Overlay
            "SELECT overlay('hello' PLACING 'XX' FROM 2 FOR 3) AS result",

            // Padding
            "SELECT lpad('42', 5, '0') AS result",
            "SELECT rpad('hi', 5, '.') AS result",
            "SELECT lpad('12345', 3) AS result",         // truncation

            // Regex
            "SELECT 'hello world' ~ 'wor' AS result",
            "SELECT 'hello world' ~ '^hello' AS result",
            "SELECT 'Hello World' ~* 'hello' AS result",
            "SELECT 'hello' !~ 'xyz' AS result"
        );
    }

    @ParameterizedTest(name = "string: {0}")
    @MethodSource("stringOperations")
    void stringOps(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 9. EXTRACT from all temporal types
    // =========================================================================

    static List<String> extractOperations() {
        return List.of(
            // From DATE
            "SELECT EXTRACT(year FROM DATE '2024-06-15') AS result",
            "SELECT EXTRACT(month FROM DATE '2024-06-15') AS result",
            "SELECT EXTRACT(day FROM DATE '2024-06-15') AS result",
            "SELECT EXTRACT(dow FROM DATE '2024-06-15') AS result",
            "SELECT EXTRACT(doy FROM DATE '2024-06-15') AS result",
            "SELECT EXTRACT(quarter FROM DATE '2024-06-15') AS result",
            "SELECT EXTRACT(week FROM DATE '2024-06-15') AS result",

            // From TIMESTAMP
            "SELECT EXTRACT(hour FROM TIMESTAMP '2024-06-15 14:30:45') AS result",
            "SELECT EXTRACT(minute FROM TIMESTAMP '2024-06-15 14:30:45') AS result",
            "SELECT EXTRACT(second FROM TIMESTAMP '2024-06-15 14:30:45') AS result",
            "SELECT EXTRACT(year FROM TIMESTAMP '2024-06-15 14:30:45') AS result",
            "SELECT EXTRACT(month FROM TIMESTAMP '2024-06-15 14:30:45') AS result",
            "SELECT EXTRACT(day FROM TIMESTAMP '2024-06-15 14:30:45') AS result",

            // From INTERVAL
            "SELECT EXTRACT(hour FROM INTERVAL '3 hours 25 minutes') AS result",
            "SELECT EXTRACT(minute FROM INTERVAL '3 hours 25 minutes') AS result",
            "SELECT EXTRACT(day FROM INTERVAL '5 days 3 hours') AS result",
            "SELECT EXTRACT(month FROM INTERVAL '1 year 3 months') AS result",
            "SELECT EXTRACT(year FROM INTERVAL '2 years') AS result",

            // Epoch
            "SELECT EXTRACT(epoch FROM TIMESTAMP '2024-01-01 00:00:00') AS result",
            "SELECT EXTRACT(epoch FROM INTERVAL '1 day') AS result",
            "SELECT EXTRACT(epoch FROM DATE '2024-01-01') AS result"
        );
    }

    @ParameterizedTest(name = "extract: {0}")
    @MethodSource("extractOperations")
    void extractOps(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 10. Row comparisons
    // =========================================================================

    static List<String> rowComparisons() {
        return List.of(
            "SELECT (1, 2) < (1, 3) AS result",
            "SELECT (1, 2) < (1, 2) AS result",
            "SELECT (1, 2) < (2, 1) AS result",
            "SELECT (1, 2) <= (1, 2) AS result",
            "SELECT (1, 2) > (1, 1) AS result",
            "SELECT (1, 2) >= (1, 2) AS result",
            "SELECT (1, 2) = (1, 2) AS result",
            "SELECT (1, 2) = (1, 3) AS result",
            "SELECT (1, 2) <> (1, 3) AS result",
            "SELECT (1, 2) <> (1, 2) AS result",
            "SELECT ('a', 1) < ('a', 2) AS result",
            "SELECT ('a', 1) < ('b', 0) AS result",
            "SELECT (1, 2, 3) = (1, 2, 3) AS result",
            "SELECT (1, 2, 3) < (1, 2, 4) AS result",

            // Row BETWEEN
            "SELECT (2, 3) BETWEEN (1, 0) AND (3, 0) AS result",
            "SELECT (1, 5) BETWEEN (1, 2) AND (2, 5) AS result"
        );
    }

    @ParameterizedTest(name = "row: {0}")
    @MethodSource("rowComparisons")
    void rowCmp(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 11. Type casts — exhaustive
    // =========================================================================

    static List<String> typeCasts() {
        return List.of(
            // int <-> text
            "SELECT 42::text AS result",
            "SELECT '42'::int AS result",
            "SELECT '-42'::int AS result",

            // int <-> bigint
            "SELECT 42::bigint AS result",
            "SELECT 42::int AS result",

            // int <-> float
            "SELECT 42::float8 AS result",
            "SELECT 42::float4 AS result",
            "SELECT 3.14::int AS result",      // truncates

            // text <-> float
            "SELECT '3.14'::float8 AS result",
            "SELECT 3.14::text AS result",

            // text <-> bool
            "SELECT 'true'::boolean AS result",
            "SELECT 'false'::boolean AS result",
            "SELECT true::text AS result",
            "SELECT false::text AS result",

            // text <-> date/time
            "SELECT '2024-01-15'::date AS result",
            "SELECT '10:30:00'::time AS result",
            "SELECT '2024-01-15 10:30:00'::timestamp AS result",

            // numeric
            "SELECT 42::numeric AS result",
            "SELECT '3.14159'::numeric AS result",
            "SELECT 3.14159::numeric(5,2) AS result",

            // Chained casts
            "SELECT '42'::int::text AS result",
            "SELECT '3.14'::float8::int::text AS result",
            "SELECT true::int::text AS result"
        );
    }

    @ParameterizedTest(name = "cast: {0}")
    @MethodSource("typeCasts")
    void typeCast(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 12. Aggregate functions with edge cases
    // =========================================================================

    static List<String> aggregateEdgeCases() {
        List<String> tests = new ArrayList<>();
        // Setup is done inline using VALUES
        tests.addAll(List.of(
            // Basic aggregates
            "SELECT SUM(v) AS result FROM (VALUES (1),(2),(3)) AS t(v)",
            "SELECT AVG(v) AS result FROM (VALUES (1),(2),(3)) AS t(v)",
            "SELECT MIN(v) AS result FROM (VALUES (3),(1),(2)) AS t(v)",
            "SELECT MAX(v) AS result FROM (VALUES (3),(1),(2)) AS t(v)",
            "SELECT COUNT(*) AS result FROM (VALUES (1),(2),(3)) AS t(v)",
            "SELECT COUNT(v) AS result FROM (VALUES (1),(NULL),(3)) AS t(v)",

            // Aggregates with NULLs
            "SELECT SUM(v) AS result FROM (VALUES (1),(NULL),(3)) AS t(v)",
            "SELECT AVG(v) AS result FROM (VALUES (1),(NULL),(3)) AS t(v)",
            "SELECT MIN(v) AS result FROM (VALUES (NULL),(NULL)) AS t(v)",
            "SELECT MAX(v) AS result FROM (VALUES (NULL),(NULL)) AS t(v)",

            // Empty set aggregates
            "SELECT SUM(v) AS result FROM (VALUES (1)) AS t(v) WHERE false",
            "SELECT COUNT(*) AS result FROM (VALUES (1)) AS t(v) WHERE false",
            "SELECT AVG(v) AS result FROM (VALUES (1)) AS t(v) WHERE false",

            // BOOL_AND / BOOL_OR
            "SELECT BOOL_AND(v) AS result FROM (VALUES (true),(true),(true)) AS t(v)",
            "SELECT BOOL_AND(v) AS result FROM (VALUES (true),(false),(true)) AS t(v)",
            "SELECT BOOL_OR(v) AS result FROM (VALUES (false),(false),(false)) AS t(v)",
            "SELECT BOOL_OR(v) AS result FROM (VALUES (false),(true),(false)) AS t(v)",

            // STRING_AGG
            "SELECT STRING_AGG(v, ',') AS result FROM (VALUES ('a'),('b'),('c')) AS t(v)",
            // STRING_AGG with ORDER BY in aggregate — known gap, ORDER BY within aggregate not yet implemented
            // "SELECT STRING_AGG(v, ', ' ORDER BY v) AS result FROM (VALUES ('c'),('a'),('b')) AS t(v)",

            // COUNT DISTINCT
            "SELECT COUNT(DISTINCT v) AS result FROM (VALUES (1),(2),(1),(3),(2)) AS t(v)"
        ));
        return tests;
    }

    @ParameterizedTest(name = "agg: {0}")
    @MethodSource("aggregateEdgeCases")
    void aggEdge(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 13. Subquery expressions
    // =========================================================================

    static List<String> subqueryExpressions() {
        return List.of(
            // Scalar subquery
            "SELECT (SELECT 42) AS result",
            "SELECT (SELECT NULL::int) AS result",

            // EXISTS
            "SELECT EXISTS (SELECT 1) AS result",
            "SELECT EXISTS (SELECT 1 WHERE false) AS result",

            // IN subquery
            "SELECT 1 IN (SELECT v FROM (VALUES (1),(2),(3)) AS t(v)) AS result",
            "SELECT 4 IN (SELECT v FROM (VALUES (1),(2),(3)) AS t(v)) AS result",

            // NOT IN subquery
            "SELECT 1 NOT IN (SELECT v FROM (VALUES (1),(2),(3)) AS t(v)) AS result",
            "SELECT 4 NOT IN (SELECT v FROM (VALUES (1),(2),(3)) AS t(v)) AS result",

            // Scalar subquery in expression
            "SELECT 1 + (SELECT 2) AS result",
            "SELECT (SELECT 10) * (SELECT 5) AS result"
        );
    }

    @ParameterizedTest(name = "subq: {0}")
    @MethodSource("subqueryExpressions")
    void subqExpr(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 14. CTE (WITH clause) variations
    // =========================================================================

    static List<String> cteVariations() {
        return List.of(
            // Basic CTE
            "SELECT * FROM (WITH t AS (SELECT 1 AS n) SELECT n AS result FROM t) AS sub",

            // CTE with multiple references
            """
            SELECT result FROM (
                WITH t AS (SELECT 42 AS v)
                SELECT a.v + b.v AS result FROM t a, t b
            ) AS sub""",

            // Multiple CTEs
            """
            SELECT result FROM (
                WITH a AS (SELECT 1 AS v), b AS (SELECT 2 AS v)
                SELECT a.v + b.v AS result FROM a, b
            ) AS sub""",

            // CTE in subquery
            """
            SELECT result FROM (
                WITH t AS (SELECT 10 AS v)
                SELECT v AS result FROM t WHERE v = (SELECT v FROM t)
            ) AS sub"""
        );
    }

    @ParameterizedTest(name = "cte: {0}")
    @MethodSource("cteVariations")
    void cteVar(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 15. CASE expression edge cases
    // =========================================================================

    static List<String> caseExpressions() {
        return List.of(
            // Simple CASE
            "SELECT CASE 1 WHEN 1 THEN 'one' WHEN 2 THEN 'two' ELSE 'other' END AS result",
            "SELECT CASE 3 WHEN 1 THEN 'one' WHEN 2 THEN 'two' ELSE 'other' END AS result",
            "SELECT CASE 3 WHEN 1 THEN 'one' WHEN 2 THEN 'two' END AS result",          // no ELSE → NULL

            // Searched CASE
            "SELECT CASE WHEN 1 > 2 THEN 'a' WHEN 2 > 1 THEN 'b' ELSE 'c' END AS result",
            "SELECT CASE WHEN false THEN 'a' WHEN false THEN 'b' END AS result",         // NULL

            // CASE with NULL
            "SELECT CASE NULL WHEN NULL THEN 'match' ELSE 'no match' END AS result",     // no match (NULL <> NULL)
            "SELECT CASE WHEN NULL IS NULL THEN 'yes' ELSE 'no' END AS result",

            // Nested CASE
            "SELECT CASE WHEN 1=1 THEN CASE WHEN 2=2 THEN 'deep' END END AS result",

            // CASE with type coercion
            "SELECT CASE WHEN true THEN 1 ELSE 2.5 END AS result"
        );
    }

    @ParameterizedTest(name = "case: {0}")
    @MethodSource("caseExpressions")
    void caseExpr(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 16. Array operations
    // =========================================================================

    static List<String> arrayOperations() {
        return List.of(
            "SELECT array_length(ARRAY[1, 2, 3], 1) AS result",
            "SELECT array_length(ARRAY[1, 2, 3, 4, 5], 1) AS result",
            "SELECT 1 = ANY(ARRAY[1, 2, 3]) AS result",
            "SELECT 4 = ANY(ARRAY[1, 2, 3]) AS result",
            "SELECT 1 = ALL(ARRAY[1, 1, 1]) AS result",
            "SELECT 1 = ALL(ARRAY[1, 2, 1]) AS result",
            "SELECT cardinality(ARRAY[1, 2, 3]) AS result",
            // Array subscript syntax requires parens in PG: (ARRAY[...])[idx]
            "SELECT (ARRAY[1, 2, 3])[1] AS result",
            "SELECT (ARRAY[1, 2, 3])[2] AS result",
            "SELECT (ARRAY[1, 2, 3])[3] AS result"
        );
    }

    @ParameterizedTest(name = "array: {0}")
    @MethodSource("arrayOperations")
    void arrayOps(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 17. Table-based queries (CRUD + aggregation with GROUP BY / HAVING)
    // =========================================================================

    static List<String> tableQueries() {
        return List.of(
            // These use VALUES as inline tables to avoid DDL setup
            "SELECT v AS result FROM (VALUES (3),(1),(2)) AS t(v) ORDER BY v",
            "SELECT v AS result FROM (VALUES (3),(1),(2),(1)) AS t(v) ORDER BY v",
            "SELECT DISTINCT v AS result FROM (VALUES (1),(2),(1),(3),(2)) AS t(v) ORDER BY v",

            // GROUP BY + aggregates
            "SELECT g, SUM(v) AS result FROM (VALUES ('a',1),('b',2),('a',3)) AS t(g,v) GROUP BY g ORDER BY g",
            "SELECT g, COUNT(*) AS result FROM (VALUES ('a',1),('b',2),('a',3)) AS t(g,v) GROUP BY g ORDER BY g",

            // HAVING
            "SELECT g, SUM(v) AS result FROM (VALUES ('a',1),('b',2),('a',3)) AS t(g,v) GROUP BY g HAVING SUM(v) > 2 ORDER BY g",

            // LIMIT / OFFSET
            "SELECT v AS result FROM (VALUES (1),(2),(3),(4),(5)) AS t(v) ORDER BY v LIMIT 3",
            "SELECT v AS result FROM (VALUES (1),(2),(3),(4),(5)) AS t(v) ORDER BY v LIMIT 2 OFFSET 2",
            "SELECT v AS result FROM (VALUES (1),(2),(3),(4),(5)) AS t(v) ORDER BY v OFFSET 3",

            // UNION / INTERSECT / EXCEPT
            "SELECT v AS result FROM (VALUES (1),(2),(3)) AS t(v) UNION SELECT v FROM (VALUES (3),(4),(5)) AS t(v) ORDER BY result",
            "SELECT v AS result FROM (VALUES (1),(2),(3)) AS t(v) UNION ALL SELECT v FROM (VALUES (3),(4),(5)) AS t(v) ORDER BY result",
            "SELECT v AS result FROM (VALUES (1),(2),(3)) AS t(v) INTERSECT SELECT v FROM (VALUES (2),(3),(4)) AS t(v) ORDER BY result",
            "SELECT v AS result FROM (VALUES (1),(2),(3)) AS t(v) EXCEPT SELECT v FROM (VALUES (2)) AS t(v) ORDER BY result"
        );
    }

    @ParameterizedTest(name = "table: {0}")
    @MethodSource("tableQueries")
    void tableQuery(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 18. JOIN variations
    // =========================================================================

    static List<String> joinVariations() {
        return List.of(
            // INNER JOIN
            """
            SELECT a.v AS result FROM
                (VALUES (1),(2),(3)) AS a(v)
                INNER JOIN (VALUES (2),(3),(4)) AS b(v) ON a.v = b.v
                ORDER BY result""",

            // LEFT JOIN
            """
            SELECT a.v, b.v AS result FROM
                (VALUES (1),(2),(3)) AS a(v)
                LEFT JOIN (VALUES (2),(3),(4)) AS b(v) ON a.v = b.v
                ORDER BY a.v""",

            // RIGHT JOIN
            """
            SELECT a.v, b.v AS result FROM
                (VALUES (1),(2),(3)) AS a(v)
                RIGHT JOIN (VALUES (2),(3),(4)) AS b(v) ON a.v = b.v
                ORDER BY b.v""",

            // CROSS JOIN
            """
            SELECT a.v, b.v AS result FROM
                (VALUES (1),(2)) AS a(v)
                CROSS JOIN (VALUES ('a'),('b')) AS b(v)
                ORDER BY a.v, b.v""",

            // Self join
            """
            SELECT a.v AS av, b.v AS result FROM
                (VALUES (1),(2),(3)) AS a(v),
                (VALUES (1),(2),(3)) AS b(v)
                WHERE a.v < b.v
                ORDER BY a.v, b.v"""
        );
    }

    @ParameterizedTest(name = "join: {0}")
    @MethodSource("joinVariations")
    void joinVar(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 19. Window functions
    // =========================================================================

    static List<String> windowFunctions() {
        return List.of(
            // ROW_NUMBER
            """
            SELECT v, ROW_NUMBER() OVER (ORDER BY v) AS rn
            FROM (VALUES (3),(1),(2)) AS t(v) ORDER BY v""",

            // ROW_NUMBER with PARTITION BY
            """
            SELECT g, v, ROW_NUMBER() OVER (PARTITION BY g ORDER BY v) AS rn
            FROM (VALUES ('a',3),('a',1),('b',2),('b',4)) AS t(g,v) ORDER BY g, v""",

            // RANK with ties
            """
            SELECT v, RANK() OVER (ORDER BY v) AS rk
            FROM (VALUES (1),(2),(2),(3)) AS t(v) ORDER BY v""",

            // DENSE_RANK with ties
            """
            SELECT v, DENSE_RANK() OVER (ORDER BY v) AS dr
            FROM (VALUES (1),(2),(2),(3)) AS t(v) ORDER BY v""",

            // NTILE
            """
            SELECT v, NTILE(3) OVER (ORDER BY v) AS bucket
            FROM (VALUES (1),(2),(3),(4),(5),(6)) AS t(v) ORDER BY v""",

            // NTILE uneven distribution
            """
            SELECT v, NTILE(3) OVER (ORDER BY v) AS bucket
            FROM (VALUES (1),(2),(3),(4),(5)) AS t(v) ORDER BY v""",

            // LAG with default
            """
            SELECT v, LAG(v, 1, 0) OVER (ORDER BY v) AS prev
            FROM (VALUES (10),(20),(30)) AS t(v) ORDER BY v""",

            // LAG without default (NULL)
            """
            SELECT v, LAG(v) OVER (ORDER BY v) AS prev
            FROM (VALUES (10),(20),(30)) AS t(v) ORDER BY v""",

            // LEAD with default
            """
            SELECT v, LEAD(v, 1, 0) OVER (ORDER BY v) AS nxt
            FROM (VALUES (10),(20),(30)) AS t(v) ORDER BY v""",

            // LEAD without default (NULL)
            """
            SELECT v, LEAD(v) OVER (ORDER BY v) AS nxt
            FROM (VALUES (10),(20),(30)) AS t(v) ORDER BY v""",

            // FIRST_VALUE
            """
            SELECT v, FIRST_VALUE(v) OVER (ORDER BY v) AS fv
            FROM (VALUES (3),(1),(2)) AS t(v) ORDER BY v""",

            // LAST_VALUE with default frame (RANGE UNBOUNDED PRECEDING TO CURRENT ROW)
            """
            SELECT v, LAST_VALUE(v) OVER (ORDER BY v ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS lv
            FROM (VALUES (3),(1),(2)) AS t(v) ORDER BY v""",

            // NTH_VALUE
            """
            SELECT v, NTH_VALUE(v, 2) OVER (ORDER BY v ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS nv
            FROM (VALUES (3),(1),(2)) AS t(v) ORDER BY v""",

            // SUM OVER partition (no ORDER BY = full partition)
            """
            SELECT g, v, SUM(v) OVER (PARTITION BY g) AS total
            FROM (VALUES ('a',1),('a',2),('b',3),('b',4)) AS t(g,v) ORDER BY g, v""",

            // Running SUM (ORDER BY = running aggregate)
            """
            SELECT v, SUM(v) OVER (ORDER BY v) AS running
            FROM (VALUES (10),(20),(30)) AS t(v) ORDER BY v""",

            // COUNT(*) OVER partition
            """
            SELECT g, COUNT(*) OVER (PARTITION BY g) AS cnt
            FROM (VALUES ('a',1),('a',2),('b',3)) AS t(g,v) ORDER BY g, v""",

            // AVG OVER partition
            """
            SELECT g, v, AVG(v::float8) OVER (PARTITION BY g) AS avg_val
            FROM (VALUES ('a',10),('a',20),('b',30)) AS t(g,v) ORDER BY g, v""",

            // MIN/MAX OVER partition
            """
            SELECT g, MIN(v) OVER (PARTITION BY g) AS lo, MAX(v) OVER (PARTITION BY g) AS hi
            FROM (VALUES ('a',1),('a',3),('b',2),('b',4)) AS t(g,v) ORDER BY g, v""",

            // Multiple window functions in one query
            """
            SELECT v,
                   ROW_NUMBER() OVER (ORDER BY v) AS rn,
                   RANK() OVER (ORDER BY v) AS rk,
                   SUM(v) OVER (ORDER BY v) AS running
            FROM (VALUES (1),(2),(2),(3)) AS t(v) ORDER BY v, rn""",

            // CUME_DIST
            """
            SELECT v, CUME_DIST() OVER (ORDER BY v) AS cd
            FROM (VALUES (1),(2),(2),(3)) AS t(v) ORDER BY v""",

            // PERCENT_RANK
            """
            SELECT v, PERCENT_RANK() OVER (ORDER BY v) AS pr
            FROM (VALUES (1),(2),(2),(3)) AS t(v) ORDER BY v""",

            // Named window
            """
            SELECT g, v,
                   ROW_NUMBER() OVER w AS rn,
                   SUM(v) OVER w AS running
            FROM (VALUES ('a',1),('a',2),('b',3)) AS t(g,v)
            WINDOW w AS (PARTITION BY g ORDER BY v)
            ORDER BY g, v""",

            // LAG with offset > 1
            """
            SELECT v, LAG(v, 2, -1) OVER (ORDER BY v) AS prev2
            FROM (VALUES (10),(20),(30),(40)) AS t(v) ORDER BY v""",

            // LEAD with offset > 1
            """
            SELECT v, LEAD(v, 2, -1) OVER (ORDER BY v) AS nxt2
            FROM (VALUES (10),(20),(30),(40)) AS t(v) ORDER BY v""",

            // Window with DESC ordering
            """
            SELECT v, ROW_NUMBER() OVER (ORDER BY v DESC) AS rn
            FROM (VALUES (1),(2),(3)) AS t(v) ORDER BY v""",

            // Running COUNT with ORDER BY
            """
            SELECT v, COUNT(*) OVER (ORDER BY v) AS running_cnt
            FROM (VALUES (1),(2),(2),(3)) AS t(v) ORDER BY v""",

            // Single-row partition
            """
            SELECT g, v, ROW_NUMBER() OVER (PARTITION BY g ORDER BY v) AS rn
            FROM (VALUES ('a',1),('b',2),('c',3)) AS t(g,v) ORDER BY g""",

            // NULL values in partition key
            """
            SELECT g, v, ROW_NUMBER() OVER (PARTITION BY g ORDER BY v) AS rn
            FROM (VALUES ('a',1),(NULL,2),('a',3),(NULL,4)) AS t(g,v) ORDER BY g NULLS LAST, v""",

            // NULL values in ORDER BY
            """
            SELECT v, ROW_NUMBER() OVER (ORDER BY v NULLS LAST) AS rn
            FROM (VALUES (1),(NULL),(3),(2)) AS t(v) ORDER BY v NULLS LAST""",

            // Window over empty partition (no rows match)
            """
            SELECT g, v, SUM(v) OVER (PARTITION BY g) AS total
            FROM (VALUES ('a',1),('a',2)) AS t(g,v) ORDER BY g, v"""
        );
    }

    @ParameterizedTest(name = "window: {0}")
    @MethodSource("windowFunctions")
    void windowFunc(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 20. Numeric overflow errors (SQLSTATE 22003)
    // =========================================================================

    static List<String> overflowInt4() {
        return List.of(
            "SELECT 2147483647 + 1",
            "SELECT -2147483648 - 1",
            "SELECT 2147483647 * 2"
        );
    }

    @ParameterizedTest(name = "int4 overflow: {0}")
    @MethodSource("overflowInt4")
    void overflowInt4(String sql, DualExecutor db) throws Exception {
        db.assertError(sql, "22003");
    }

    static List<String> overflowInt8() {
        return List.of(
            "SELECT 9223372036854775807::bigint + 1::bigint",
            "SELECT (-9223372036854775808)::bigint - 1::bigint",
            "SELECT 9223372036854775807::bigint * 2::bigint"
        );
    }

    @ParameterizedTest(name = "int8 overflow: {0}")
    @MethodSource("overflowInt8")
    void overflowInt8(String sql, DualExecutor db) throws Exception {
        db.assertError(sql, "22003");
    }

    static List<String> divisionByZero() {
        return List.of(
            "SELECT 1 / 0",
            "SELECT 1::bigint / 0::bigint",
            "SELECT 1.0 / 0.0"
        );
    }

    @ParameterizedTest(name = "div/0: {0}")
    @MethodSource("divisionByZero")
    void divByZero(String sql, DualExecutor db) throws Exception {
        db.assertError(sql, "22012");
    }

    // =========================================================================
    // 21. Aggregate ORDER BY (STRING_AGG, ARRAY_AGG with ORDER BY)
    // =========================================================================

    static List<String> aggregateOrderBy() {
        return List.of(
            // STRING_AGG with ORDER BY ASC
            """
            SELECT STRING_AGG(v, ',' ORDER BY v) AS result
            FROM (VALUES ('c'),('a'),('b')) AS t(v)""",

            // STRING_AGG with ORDER BY DESC
            """
            SELECT STRING_AGG(v, ',' ORDER BY v DESC) AS result
            FROM (VALUES ('c'),('a'),('b')) AS t(v)""",

            // ARRAY_AGG with ORDER BY ASC
            """
            SELECT ARRAY_AGG(v ORDER BY v) AS result
            FROM (VALUES (3),(1),(2)) AS t(v)""",

            // ARRAY_AGG with ORDER BY DESC
            """
            SELECT ARRAY_AGG(v ORDER BY v DESC) AS result
            FROM (VALUES (3),(1),(2)) AS t(v)""",

            // STRING_AGG with GROUP BY and ORDER BY
            """
            SELECT g, STRING_AGG(v, '-' ORDER BY v) AS result
            FROM (VALUES ('x','c'),('x','a'),('y','b'),('y','d')) AS t(g,v)
            GROUP BY g ORDER BY g""",

            // STRING_AGG without ORDER BY (insertion order — compared as set)
            """
            SELECT STRING_AGG(v, ',') AS result
            FROM (VALUES ('a')) AS t(v)""",

            // ARRAY_AGG with NULLs
            """
            SELECT ARRAY_AGG(v ORDER BY v NULLS LAST) AS result
            FROM (VALUES (2),(NULL),(1),(3)) AS t(v)"""
        );
    }

    @ParameterizedTest(name = "agg-order: {0}")
    @MethodSource("aggregateOrderBy")
    void aggOrderBy(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // =========================================================================
    // 22. CHECK constraint enforcement
    // =========================================================================

    @org.junit.jupiter.api.Test
    void checkConstraintViolation(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE chk_test (id int, val int CHECK (val > 0))");
        db.assertError("INSERT INTO chk_test VALUES (1, -1)", "23514");
    }

    @org.junit.jupiter.api.Test
    void checkConstraintPasses(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE chk_pass (id int, val int CHECK (val > 0))");
        db.execute("INSERT INTO chk_pass VALUES (1, 5)");
        db.assertQuery("SELECT val FROM chk_pass");
    }

    @org.junit.jupiter.api.Test
    void checkConstraintNullPasses(DualExecutor db) throws Exception {
        // PostgreSQL: CHECK passes when the expression evaluates to NULL
        db.execute("CREATE TABLE chk_null (id int, val int CHECK (val > 0))");
        db.execute("INSERT INTO chk_null VALUES (1, NULL)");
        db.assertQuery("SELECT val FROM chk_null");
    }

    @org.junit.jupiter.api.Test
    void checkConstraintTableLevel(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE chk_tbl (a int, b int, CHECK (a < b))");
        db.assertError("INSERT INTO chk_tbl VALUES (5, 3)", "23514");
    }

    @org.junit.jupiter.api.Test
    void checkConstraintUpdateViolation(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE chk_upd (id int, val int CHECK (val > 0))");
        db.execute("INSERT INTO chk_upd VALUES (1, 5)");
        db.assertError("UPDATE chk_upd SET val = -1 WHERE id = 1", "23514");
    }

    // =========================================================================
    // 23. FOREIGN KEY constraint enforcement
    // =========================================================================

    @org.junit.jupiter.api.Test
    void fkInsertViolation(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE fk_parent (id int PRIMARY KEY)");
        db.execute("CREATE TABLE fk_child (id int, parent_id int REFERENCES fk_parent(id))");
        db.execute("INSERT INTO fk_parent VALUES (1), (2)");
        db.assertError("INSERT INTO fk_child VALUES (1, 999)", "23503");
    }

    @org.junit.jupiter.api.Test
    void fkInsertPasses(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE fk_parent2 (id int PRIMARY KEY)");
        db.execute("CREATE TABLE fk_child2 (id int, parent_id int REFERENCES fk_parent2(id))");
        db.execute("INSERT INTO fk_parent2 VALUES (1), (2)");
        db.execute("INSERT INTO fk_child2 VALUES (1, 1)");
        db.assertQuery("SELECT * FROM fk_child2 ORDER BY id");
    }

    @org.junit.jupiter.api.Test
    void fkNullPasses(DualExecutor db) throws Exception {
        // FK with NULL value should pass (MATCH SIMPLE)
        db.execute("CREATE TABLE fk_parent3 (id int PRIMARY KEY)");
        db.execute("CREATE TABLE fk_child3 (id int, parent_id int REFERENCES fk_parent3(id))");
        db.execute("INSERT INTO fk_child3 VALUES (1, NULL)");
        db.assertQuery("SELECT * FROM fk_child3 ORDER BY id");
    }

    @org.junit.jupiter.api.Test
    void fkUpdateViolation(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE fk_parent4 (id int PRIMARY KEY)");
        db.execute("CREATE TABLE fk_child4 (id int, parent_id int REFERENCES fk_parent4(id))");
        db.execute("INSERT INTO fk_parent4 VALUES (1), (2)");
        db.execute("INSERT INTO fk_child4 VALUES (1, 1)");
        db.assertError("UPDATE fk_child4 SET parent_id = 999 WHERE id = 1", "23503");
    }

    // =========================================================================
    // 24. Float division by zero also errors (PostgreSQL throws for all types)
    // =========================================================================

    static List<String> floatDivisionByZero() {
        return List.of(
            "SELECT 1.0::float8 / 0.0::float8",
            "SELECT -1.0::float8 / 0.0::float8",
            "SELECT 0.0::float8 / 0.0::float8"
        );
    }

    @ParameterizedTest(name = "float-div0: {0}")
    @MethodSource("floatDivisionByZero")
    void floatDivZero(String sql, DualExecutor db) throws Exception {
        db.assertError(sql, "22012");
    }

    // -----------------------------------------------------------------------
    // 25. FK parent-side: ON DELETE / ON UPDATE actions
    // -----------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    void fkDeleteRestrict(DualExecutor db) throws Exception {
        db.execute("""
            CREATE TABLE fk_parent_r(id INT PRIMARY KEY);
            CREATE TABLE fk_child_r(id INT, pid INT REFERENCES fk_parent_r(id));
            INSERT INTO fk_parent_r VALUES (1),(2);
            INSERT INTO fk_child_r VALUES (10,1),(20,2);
            """);
        // Default action is NO ACTION — should fail when child rows exist
        db.assertError("DELETE FROM fk_parent_r WHERE id = 1", "23503");
    }

    @org.junit.jupiter.api.Test
    void fkDeleteCascade(DualExecutor db) throws Exception {
        db.execute("""
            CREATE TABLE fk_parent_c(id INT PRIMARY KEY);
            CREATE TABLE fk_child_c(id INT, pid INT REFERENCES fk_parent_c(id) ON DELETE CASCADE);
            INSERT INTO fk_parent_c VALUES (1),(2);
            INSERT INTO fk_child_c VALUES (10,1),(20,1),(30,2);
            """);
        db.execute("DELETE FROM fk_parent_c WHERE id = 1");
        // Child rows referencing id=1 should be gone
        db.assertQuery("SELECT * FROM fk_child_c ORDER BY id");
    }

    @org.junit.jupiter.api.Test
    void fkDeleteSetNull(DualExecutor db) throws Exception {
        db.execute("""
            CREATE TABLE fk_parent_sn(id INT PRIMARY KEY);
            CREATE TABLE fk_child_sn(id INT, pid INT REFERENCES fk_parent_sn(id) ON DELETE SET NULL);
            INSERT INTO fk_parent_sn VALUES (1),(2);
            INSERT INTO fk_child_sn VALUES (10,1),(20,2);
            """);
        db.execute("DELETE FROM fk_parent_sn WHERE id = 1");
        db.assertQuery("SELECT * FROM fk_child_sn ORDER BY id");
    }

    @org.junit.jupiter.api.Test
    void fkUpdateCascade(DualExecutor db) throws Exception {
        db.execute("""
            CREATE TABLE fk_parent_uc(id INT PRIMARY KEY);
            CREATE TABLE fk_child_uc(id INT, pid INT REFERENCES fk_parent_uc(id) ON UPDATE CASCADE);
            INSERT INTO fk_parent_uc VALUES (1),(2);
            INSERT INTO fk_child_uc VALUES (10,1),(20,1),(30,2);
            """);
        db.execute("UPDATE fk_parent_uc SET id = 99 WHERE id = 1");
        db.assertQuery("SELECT * FROM fk_child_uc ORDER BY id");
    }

    @org.junit.jupiter.api.Test
    void fkUpdateRestrict(DualExecutor db) throws Exception {
        db.execute("""
            CREATE TABLE fk_parent_ur(id INT PRIMARY KEY);
            CREATE TABLE fk_child_ur(id INT, pid INT REFERENCES fk_parent_ur(id));
            INSERT INTO fk_parent_ur VALUES (1),(2);
            INSERT INTO fk_child_ur VALUES (10,1);
            """);
        db.assertError("UPDATE fk_parent_ur SET id = 99 WHERE id = 1", "23503");
    }

    // -----------------------------------------------------------------------
    // 26. ALTER TABLE RENAME
    // -----------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    void alterTableRenameTable(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE rename_src(id INT, name TEXT)");
        db.execute("INSERT INTO rename_src VALUES (1, 'a'), (2, 'b')");
        db.execute("ALTER TABLE rename_src RENAME TO rename_dst");
        db.assertQuery("SELECT * FROM rename_dst ORDER BY id");
    }

    @org.junit.jupiter.api.Test
    void alterTableRenameColumn(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE rencol(id INT, old_name TEXT)");
        db.execute("INSERT INTO rencol VALUES (1, 'hello')");
        db.execute("ALTER TABLE rencol RENAME COLUMN old_name TO new_name");
        db.assertQuery("SELECT id, new_name FROM rencol ORDER BY id");
    }

    // -----------------------------------------------------------------------
    // 27. Cross-numeric implicit cast / type promotion
    // -----------------------------------------------------------------------

    static List<String> crossNumericPromotion() {
        return List.of(
            // int4 + int8 → int8
            "SELECT pg_typeof(1 + 1::bigint) AS result",
            // int4 * float8 → float8
            "SELECT pg_typeof(1 * 1.0::float8) AS result",
            // int4 + numeric → numeric
            "SELECT pg_typeof(1 + 1.0::numeric) AS result",
            // float4 + float8 → float8
            "SELECT pg_typeof(1.0::float4 + 1.0::float8) AS result",
            // int2 + int4 → int4
            "SELECT pg_typeof(1::smallint + 1) AS result",
            // actual value tests
            "SELECT (2147483647::int + 1::bigint) AS result",
            "SELECT (1::int / 2::int) AS result",
            "SELECT (1::int / 2.0) AS result",
            "SELECT (3::smallint * 4) AS result"
        );
    }

    @ParameterizedTest
    @MethodSource("crossNumericPromotion")
    void crossNumericPromo(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }

    // -----------------------------------------------------------------------
    // 28. EXPLAIN
    // -----------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    void explainSelect(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE expl_t(id INT, name TEXT)");
        // Just verify EXPLAIN returns rows (plan format differs between engines)
        db.execute("EXPLAIN SELECT * FROM expl_t WHERE id > 5");
    }

    @org.junit.jupiter.api.Test
    void explainAnalyze(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE expl_a(id INT)");
        db.execute("INSERT INTO expl_a VALUES (1),(2),(3)");
        db.execute("EXPLAIN ANALYZE SELECT * FROM expl_a");
    }

    @org.junit.jupiter.api.Test
    void fkDeleteNoChildRows(DualExecutor db) throws Exception {
        db.execute("""
            CREATE TABLE fk_parent_nc(id INT PRIMARY KEY);
            CREATE TABLE fk_child_nc(id INT, pid INT REFERENCES fk_parent_nc(id));
            INSERT INTO fk_parent_nc VALUES (1),(2);
            INSERT INTO fk_child_nc VALUES (10,2);
            """);
        // No child references id=1, so delete should succeed
        db.execute("DELETE FROM fk_parent_nc WHERE id = 1");
        db.assertQuery("SELECT * FROM fk_parent_nc ORDER BY id");
    }
}
