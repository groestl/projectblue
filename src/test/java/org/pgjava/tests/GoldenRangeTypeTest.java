package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;

/**
 * Golden tests for PostgreSQL range types comparing pgjava against real PostgreSQL.
 *
 * <p>Covers: int4range, int8range, numrange, daterange, tsrange —
 * construction, containment (@>), overlap (&&), adjacent (-|-), range functions,
 * EMPTY range, infinite bounds, discrete canonicalization, and table column usage.
 */
@ExtendWith(GoldenExtension.class)
class GoldenRangeTypeTest {

    // ── Literal construction ──────────────────────────────────────────────────

    @Test void int4rangeLiterals(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT int4range(1, 5),
                       int4range(1, 5, '[]'),
                       int4range(1, 5, '()'),
                       int4range(1, 5, '[)'),
                       int4range(1, 5, '(]')
                """);
    }

    @Test void int4rangeEmpty(DualExecutor db) throws Exception {
        db.assertQuery("SELECT 'empty'::int4range");
    }

    @Test void int4rangeInfiniteBounds(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT int4range(NULL, 10),
                       int4range(1, NULL),
                       int4range(NULL, NULL)
                """);
    }

    @Test void int4rangeCanonicalizes(DualExecutor db) throws Exception {
        // Discrete ranges are canonicalized to [lower, upper) form
        db.assertQuery("""
                SELECT int4range(1, 5, '()'),
                       int4range(1, 5, '(]'),
                       int4range(1, 5, '[]')
                """);
    }

    @Test void numrangeLiterals(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT numrange(1.5, 9.9),
                       numrange(1.5, 9.9, '[]'),
                       numrange(NULL, 10.0),
                       numrange(0.0, NULL)
                """);
    }

    @Test void daterangeLiterals(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT daterange('2024-01-01', '2024-12-31'),
                       daterange('2024-01-01', '2024-12-31', '[]'),
                       daterange('2024-01-01', NULL)
                """);
    }

    // ── Containment ───────────────────────────────────────────────────────────

    @Test void int4rangeContainsElement(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT int4range(1, 10) @> 5,
                       int4range(1, 10) @> 1,
                       int4range(1, 10) @> 10,
                       int4range(1, 10) @> 0,
                       int4range(1, 10, '[]') @> 10
                """);
    }

    @Test void int4rangeContainsRange(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT int4range(1, 20) @> int4range(5, 10),
                       int4range(1, 20) @> int4range(1, 20),
                       int4range(5, 10) @> int4range(1, 20),
                       int4range(1, 10) @> 'empty'::int4range
                """);
    }

    @Test void int4rangeContainedBy(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT int4range(5, 10) <@ int4range(1, 20),
                       int4range(1, 20) <@ int4range(5, 10),
                       5 <@ int4range(1, 10)
                """);
    }

    // ── Overlap ───────────────────────────────────────────────────────────────

    @Test void int4rangeOverlap(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT int4range(1, 10) && int4range(5, 15),
                       int4range(1, 10) && int4range(10, 20),
                       int4range(1, 10, '[)') && int4range(10, 20, '[)'),
                       int4range(1, 5)  && int4range(10, 20)
                """);
    }

    // ── Adjacency ─────────────────────────────────────────────────────────────

    @Test void int4rangeAdjacent(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT int4range(1, 5) -|- int4range(5, 10),
                       int4range(1, 5) -|- int4range(6, 10),
                       int4range(1, 5) -|- int4range(7, 10)
                """);
    }

    // ── Union / Intersection / Difference ────────────────────────────────────

    @Test void int4rangeUnion(DualExecutor db) throws Exception {
        db.assertQuery("SELECT int4range(1, 5) + int4range(3, 10)");
    }

    @Test void int4rangeIntersection(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT int4range(1, 10) * int4range(5, 15),
                       int4range(1, 5)  * int4range(5, 10),
                       int4range(1, 5)  * int4range(10, 20)
                """);
    }

    @Test void int4rangeDifference(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT int4range(1, 10) - int4range(5, 15),
                       int4range(1, 10) - int4range(1, 5)
                """);
    }

    // ── Comparison ───────────────────────────────────────────────────────────

    @Test void int4rangeComparison(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT int4range(1, 5) = int4range(1, 5),
                       int4range(1, 5) = int4range(1, 6),
                       int4range(1, 5) < int4range(2, 5),
                       int4range(1, 5) > int4range(1, 4)
                """);
    }

    // ── Functions ─────────────────────────────────────────────────────────────

    @Test void lowerUpper(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT lower(int4range(3, 10)),
                       upper(int4range(3, 10)),
                       lower(int4range(NULL, 10)),
                       upper(int4range(3, NULL))
                """);
    }

    @Test void lowerUpperInc(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT lower_inc(int4range(1, 5)),
                       upper_inc(int4range(1, 5)),
                       lower_inc(int4range(1, 5, '[]')),
                       upper_inc(int4range(1, 5, '[]'))
                """);
    }

    @Test void lowerUpperInf(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT lower_inf(int4range(NULL, 10)),
                       lower_inf(int4range(1, 10)),
                       upper_inf(int4range(1, NULL)),
                       upper_inf(int4range(1, 10))
                """);
    }

    @Test void isempty(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT isempty('empty'::int4range),
                       isempty(int4range(1, 5)),
                       isempty(int4range(3, 3))
                """);
    }

    @Test void rangeMerge(DualExecutor db) throws Exception {
        db.assertQuery("SELECT range_merge(int4range(1, 5), int4range(8, 12))");
    }

    // ── Table column usage ────────────────────────────────────────────────────

    @Test void rangeInTableColumn(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE reservations (id INT PRIMARY KEY, during daterange)");
        db.execute("""
                INSERT INTO reservations VALUES
                  (1, '[2024-01-01, 2024-01-07)'),
                  (2, '[2024-01-05, 2024-01-12)'),
                  (3, '[2024-02-01, 2024-02-14)')
                """);
        db.assertQuery("SELECT id FROM reservations ORDER BY id");
    }

    @Test void rangeOverlapQueryPattern(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE bookings (id INT PRIMARY KEY, period int4range)");
        db.execute("INSERT INTO bookings VALUES (1, '[1, 10)'), (2, '[5, 15)'), (3, '[20, 30)')");
        // Find all bookings overlapping with [8, 12)
        db.assertQuery("SELECT id FROM bookings WHERE period && int4range(8, 12) ORDER BY id");
    }

    @Test void rangeContainmentQueryPattern(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE events (id INT PRIMARY KEY, duration numrange)");
        db.execute("INSERT INTO events VALUES (1, '[0, 5]'), (2, '[3, 8]'), (3, '[10, 20]')");
        // Find events that contain the value 4
        db.assertQuery("SELECT id FROM events WHERE duration @> 4.0 ORDER BY id");
    }

    // ── Numeric and timestamp ranges ──────────────────────────────────────────

    @Test void numrangeOps(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT numrange(1.5, 9.9) @> 5.0,
                       numrange(1.5, 9.9) && numrange(5.0, 15.0),
                       lower(numrange(1.5, 9.9)),
                       upper(numrange(1.5, 9.9))
                """);
    }

    @Test void tsrangeOps(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT tsrange('2024-01-01', '2024-06-01') &&
                       tsrange('2024-03-01', '2024-09-01')
                """);
    }

    // ── int8range ─────────────────────────────────────────────────────────────

    @Test void int8rangeLiterals(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT int8range(1, 1000000000000),
                       lower(int8range(5, 100)),
                       upper(int8range(5, 100))
                """);
    }
}
