package org.pgjava.tests;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;

import java.util.List;

/**
 * Differential expression testing: each entry is a {@code SELECT <expr> AS result} that
 * runs against both pgjava and real PostgreSQL. Divergence is a bug.
 *
 * <p>All expressions are aliased as {@code result} to avoid column-name divergence
 * (PostgreSQL derives names like {@code case}, {@code greatest}, {@code text};
 * pgjava always returns {@code ?column?} for unnamed expressions).
 *
 * <p>Scope: value correctness of the expression evaluator. Not tested here:
 * - NUMERIC vs double precision (PG treats decimal literals as NUMERIC; we use double)
 * - Integer overflow/promotion (PG raises error; we silently overflow)
 * - Non-deterministic functions (now, random, gen_random_uuid)
 */
@ExtendWith(GoldenExtension.class)
class GoldenExpressionFuzzTest {

    static List<String> expressions() {
        return List.of(
            // ── Integer arithmetic ────────────────────────────────────────────
            "SELECT 1 + 1 AS result",
            "SELECT 2 * 3 AS result",
            "SELECT 10 - 4 AS result",
            "SELECT 7 / 2 AS result",          // integer division → 3
            "SELECT 7 % 3 AS result",
            "SELECT -5 + 3 AS result",
            "SELECT 2 + 3 * 4 AS result",      // precedence: 14 not 20
            "SELECT (2 + 3) * 4 AS result",
            "SELECT 10 / 2 / 2 AS result",     // left-associative → 2
            "SELECT -(5) AS result",
            "SELECT -(-7) AS result",
            "SELECT 0 * 99999 AS result",
            "SELECT 1 + 2 + 3 + 4 + 5 AS result",
            "SELECT 2147483647 AS result",      // INT_MAX (no arithmetic, just the literal)
            "SELECT -2147483648 AS result",     // INT_MIN

            // ── Float arithmetic ──────────────────────────────────────────────
            "SELECT 1.5 + 2.5 AS result",
            "SELECT 3.0 / 2.0 AS result",       // 1.5 (float, not integer division)
            "SELECT 1e10 * 1e10 AS result",

            // ── Special float values ──────────────────────────────────────────
            "SELECT 'inf'::float8 AS result",
            "SELECT '-inf'::float8 AS result",
            "SELECT '+inf'::float8 AS result",
            "SELECT 'infinity'::float8 AS result",
            "SELECT '-infinity'::float8 AS result",
            "SELECT 'nan'::float8 IS NULL AS result",  // NaN is not null

            // ── Comparison operators ──────────────────────────────────────────
            "SELECT 1 < 2 AS result",
            "SELECT 2 < 1 AS result",
            "SELECT 1 <= 1 AS result",
            "SELECT 1 > 2 AS result",
            "SELECT 2 > 1 AS result",
            "SELECT 1 >= 1 AS result",
            "SELECT 1 = 1 AS result",
            "SELECT 1 = 2 AS result",
            "SELECT 1 <> 2 AS result",
            "SELECT 1 != 2 AS result",
            "SELECT 'a' < 'b' AS result",
            "SELECT 'b' < 'a' AS result",
            "SELECT 'abc' = 'abc' AS result",
            "SELECT 'abc' = 'ABC' AS result",

            // ── Boolean logic ─────────────────────────────────────────────────
            "SELECT true AND true AS result",
            "SELECT true AND false AS result",
            "SELECT false AND false AS result",
            "SELECT true OR false AS result",
            "SELECT false OR false AS result",
            "SELECT NOT true AS result",
            "SELECT NOT false AS result",
            "SELECT true AND NOT false AS result",
            "SELECT (1 < 2) AND (2 < 3) AS result",
            "SELECT (1 < 2) OR (3 < 2) AS result",

            // ── NULL propagation ──────────────────────────────────────────────
            "SELECT NULL IS NULL AS result",
            "SELECT NULL IS NOT NULL AS result",
            "SELECT 1 + NULL AS result",
            // NULL + NULL omitted: PG rejects "operator not unique: unknown + unknown"
            "SELECT NULL = NULL AS result",
            "SELECT NULL <> NULL AS result",
            "SELECT NULL < 1 AS result",
            "SELECT true AND NULL AS result",
            "SELECT false AND NULL AS result",
            "SELECT true OR NULL AS result",
            "SELECT false OR NULL AS result",
            "SELECT NOT NULL AS result",
            "SELECT NULL::int AS result",
            "SELECT NULL::text AS result",
            "SELECT NULL::bool AS result",

            // ── COALESCE / NULLIF / GREATEST / LEAST ─────────────────────────
            "SELECT COALESCE(NULL, 1) AS result",
            "SELECT COALESCE(NULL, NULL, 3) AS result",
            "SELECT COALESCE(1, 2) AS result",
            "SELECT NULLIF(1, 1) AS result",
            "SELECT NULLIF(1, 2) AS result",
            "SELECT NULLIF(NULL, NULL) AS result",
            "SELECT GREATEST(1, 3, 2) AS result",
            "SELECT LEAST(3, 1, 2) AS result",
            "SELECT GREATEST(NULL, 1) AS result",
            "SELECT LEAST(NULL, 1) AS result",
            "SELECT GREATEST('b', 'a', 'c') AS result",
            "SELECT LEAST('b', 'a', 'c') AS result",

            // ── CASE expressions ─────────────────────────────────────────────
            "SELECT CASE WHEN 1 > 0 THEN 'yes' ELSE 'no' END AS result",
            "SELECT CASE WHEN 1 < 0 THEN 'yes' ELSE 'no' END AS result",
            "SELECT CASE WHEN NULL THEN 'yes' ELSE 'no' END AS result",
            "SELECT CASE 1 WHEN 1 THEN 'one' WHEN 2 THEN 'two' ELSE 'other' END AS result",
            "SELECT CASE 3 WHEN 1 THEN 'one' WHEN 2 THEN 'two' ELSE 'other' END AS result",
            "SELECT CASE WHEN 1=1 THEN 1 WHEN 2=2 THEN 2 END AS result",
            "SELECT CASE WHEN false THEN 1 END AS result",
            "SELECT CASE WHEN 3 BETWEEN 1 AND 5 THEN 'yes' ELSE 'no' END AS result",

            // ── BETWEEN / IN ──────────────────────────────────────────────────
            "SELECT 2 BETWEEN 1 AND 3 AS result",
            "SELECT 0 BETWEEN 1 AND 3 AS result",
            "SELECT 3 BETWEEN 1 AND 3 AS result",
            "SELECT 4 BETWEEN 1 AND 3 AS result",
            "SELECT 2 NOT BETWEEN 1 AND 3 AS result",
            "SELECT 1 IN (1, 2, 3) AS result",
            "SELECT 4 IN (1, 2, 3) AS result",
            "SELECT 1 NOT IN (1, 2, 3) AS result",
            "SELECT NULL IN (1, 2, 3) AS result",
            "SELECT 1 IN (1, NULL, 3) AS result",

            // ── Type casts ────────────────────────────────────────────────────
            "SELECT 42::text AS result",
            "SELECT '42'::int AS result",
            "SELECT '42'::bigint AS result",
            "SELECT '3.14'::float8 AS result",
            "SELECT true::int AS result",
            "SELECT false::int AS result",
            "SELECT 1::boolean AS result",
            "SELECT 0::boolean AS result",
            "SELECT CAST(42 AS text) AS result",
            "SELECT CAST('99' AS int) AS result",

            // ── String functions ──────────────────────────────────────────────
            "SELECT length('') AS result",
            "SELECT length('hello') AS result",
            "SELECT upper('hello') AS result",
            "SELECT lower('HELLO') AS result",
            "SELECT upper(lower('Hello')) AS result",
            "SELECT 'foo' || 'bar' AS result",
            "SELECT '' || 'x' || '' AS result",
            "SELECT trim('  hello  ') AS result",
            "SELECT ltrim('  hello  ') AS result",
            "SELECT rtrim('  hello  ') AS result",
            "SELECT substring('hello world', 1, 5) AS result",
            "SELECT substring('hello world', 7) AS result",
            "SELECT position('world' IN 'hello world') AS result",
            "SELECT position('xyz' IN 'hello world') AS result",
            "SELECT replace('hello', 'l', 'r') AS result",
            "SELECT repeat('ab', 3) AS result",
            "SELECT reverse('hello') AS result",
            "SELECT left('hello', 3) AS result",
            "SELECT right('hello', 3) AS result",
            "SELECT lpad('hi', 5) AS result",
            "SELECT lpad('hi', 5, '*') AS result",
            "SELECT rpad('hi', 5, '-') AS result",
            "SELECT split_part('a,b,c', ',', 2) AS result",
            "SELECT starts_with('hello', 'hel') AS result",
            "SELECT starts_with('hello', 'xyz') AS result",
            "SELECT 'abc' LIKE 'a%' AS result",
            "SELECT 'abc' LIKE '%b%' AS result",
            "SELECT 'abc' LIKE 'xyz' AS result",
            "SELECT 'ABC' ILIKE 'abc' AS result",
            "SELECT 'ABC' ILIKE 'xyz' AS result",

            // ── Math functions ────────────────────────────────────────────────
            "SELECT abs(-5) AS result",
            "SELECT abs(5) AS result",
            "SELECT abs(0) AS result",
            "SELECT ceil(1.1) AS result",
            "SELECT ceil(-1.1) AS result",
            "SELECT floor(1.9) AS result",
            "SELECT floor(-1.9) AS result",
            "SELECT round(1.5) AS result",
            "SELECT round(2.5) AS result",
            "SELECT round(-1.5) AS result",
            "SELECT trunc(3.9) AS result",
            "SELECT trunc(-3.9) AS result",
            "SELECT mod(10, 3) AS result",
            "SELECT mod(-10, 3) AS result",
            "SELECT sqrt(4.0) AS result",
            "SELECT power(2, 10) AS result",
            "SELECT power(3, 3) AS result",
            "SELECT sign(-5) AS result",
            "SELECT sign(0) AS result",
            "SELECT sign(5) AS result",

            // ── Date/time (deterministic, no now()) ───────────────────────────
            "SELECT DATE '2024-01-15' AS result",
            "SELECT DATE '2024-01-15' + 7 AS result",
            "SELECT DATE '2024-01-15' - DATE '2024-01-01' AS result",
            "SELECT EXTRACT(year  FROM DATE '2024-06-15') AS result",
            "SELECT EXTRACT(month FROM DATE '2024-06-15') AS result",
            "SELECT EXTRACT(day   FROM DATE '2024-06-15') AS result",

            // ── Nested / complex ──────────────────────────────────────────────
            "SELECT COALESCE(NULLIF(1, 1), 42) AS result",
            "SELECT upper(substring('hello world', 1, 5)) AS result",
            "SELECT length(trim('  padded  ')) AS result",
            "SELECT abs(round(-3.7)) AS result",
            "SELECT (1 + 2) * (3 + 4) - (5 * 6) + 7 AS result",
            "SELECT CASE WHEN 1 IN (1,2,3) THEN 'yes' ELSE 'no' END AS result",
            "SELECT GREATEST(abs(-5), ceil(3.2::float8), floor(4.9::float8)) AS result"
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expressions")
    void expression(String sql, DualExecutor db) throws Exception {
        db.assertQuery(sql);
    }
}
