package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.QueryResult;
import org.pgjava.engine.Session;
import org.pgjava.types.PgRange;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 12: Range types — int4range, int8range, numrange, tsrange, tstzrange, daterange.
 * Covers literals, operators (@>, <@, &&, =), functions (lower/upper/isempty etc.),
 * and constructor functions (int4range(lo, hi, '[)')).
 */
class Phase12RangeTypesTest {

    private Database db;

    @BeforeEach void setUp() {
        db = DatabaseRegistry.getOrCreate("phase12_range_" + System.nanoTime());
    }

    private Session s() { return db.openSession(); }

    // =========================================================================
    // Literal parsing via cast
    // =========================================================================

    @Test void int4rangeLiteral() throws SQLException {
        try (Session s = s()) {
            QueryResult r = s.execute("SELECT '[1,5)'::int4range");
            Object val = r.rows().get(0)[0];
            assertInstanceOf(PgRange.class, val);
            PgRange range = (PgRange) val;
            assertEquals(1, ((Number) range.lower()).intValue());
            assertEquals(5, ((Number) range.upper()).intValue());
            assertTrue(range.lowerInc());
            assertFalse(range.upperInc());
        }
    }

    @Test void int8rangeLiteral() throws SQLException {
        try (Session s = s()) {
            QueryResult r = s.execute("SELECT '[100,200]'::int8range");
            PgRange range = (PgRange) r.rows().get(0)[0];
            assertEquals(100L, ((Number) range.lower()).longValue());
            assertEquals(201L, ((Number) range.upper()).longValue());
            assertTrue(range.lowerInc());
            assertFalse(range.upperInc());
        }
    }

    @Test void numrangeLiteral() throws SQLException {
        try (Session s = s()) {
            QueryResult r = s.execute("SELECT '[1.5,3.5)'::numrange");
            PgRange range = (PgRange) r.rows().get(0)[0];
            assertEquals(0, new java.math.BigDecimal("1.5").compareTo(
                    (java.math.BigDecimal) range.lower()));
        }
    }

    @Test void emptyRangeLiteral() throws SQLException {
        try (Session s = s()) {
            QueryResult r = s.execute("SELECT 'empty'::int4range");
            PgRange range = (PgRange) r.rows().get(0)[0];
            assertTrue(range.isEmpty());
        }
    }

    @Test void unboundedRange() throws SQLException {
        try (Session s = s()) {
            QueryResult r = s.execute("SELECT '(,)'::int4range");
            PgRange range = (PgRange) r.rows().get(0)[0];
            assertTrue(range.lowerInf());
            assertTrue(range.upperInf());
        }
    }

    // =========================================================================
    // Storage in tables
    // =========================================================================

    @Test void storeAndRetrieveRange() throws SQLException {
        try (Session s = s()) {
            s.execute("CREATE TABLE periods (id int, dur int4range)");
            s.execute("INSERT INTO periods VALUES (1, '[1,10)')");
            QueryResult r = s.execute("SELECT dur FROM periods WHERE id = 1");
            PgRange range = (PgRange) r.rows().get(0)[0];
            assertEquals(1,  ((Number) range.lower()).intValue());
            assertEquals(10, ((Number) range.upper()).intValue());
        }
    }

    // =========================================================================
    // Operators
    // =========================================================================

    @Test void containsElement() throws SQLException {
        try (Session s = s()) {
            // range @> element
            QueryResult r = s.execute("SELECT '[1,10)'::int4range @> 5");
            assertEquals(Boolean.TRUE, r.rows().get(0)[0]);

            r = s.execute("SELECT '[1,10)'::int4range @> 10");
            assertEquals(Boolean.FALSE, r.rows().get(0)[0]);
        }
    }

    @Test void containedByRange() throws SQLException {
        try (Session s = s()) {
            // element <@ range
            QueryResult r = s.execute("SELECT 5 <@ '[1,10)'::int4range");
            assertEquals(Boolean.TRUE, r.rows().get(0)[0]);

            r = s.execute("SELECT 10 <@ '[1,10)'::int4range");
            assertEquals(Boolean.FALSE, r.rows().get(0)[0]);
        }
    }

    @Test void rangeContainsRange() throws SQLException {
        try (Session s = s()) {
            // range @> range
            QueryResult r = s.execute("SELECT '[1,10)'::int4range @> '[2,5)'::int4range");
            assertEquals(Boolean.TRUE, r.rows().get(0)[0]);

            r = s.execute("SELECT '[1,5)'::int4range @> '[1,10)'::int4range");
            assertEquals(Boolean.FALSE, r.rows().get(0)[0]);
        }
    }

    @Test void overlaps() throws SQLException {
        try (Session s = s()) {
            QueryResult r = s.execute("SELECT '[1,5)'::int4range && '[3,8)'::int4range");
            assertEquals(Boolean.TRUE, r.rows().get(0)[0]);

            r = s.execute("SELECT '[1,3)'::int4range && '[3,8)'::int4range");
            // [1,3) and [3,8) are adjacent but not overlapping
            assertEquals(Boolean.FALSE, r.rows().get(0)[0]);
        }
    }

    @Test void rangeEquality() throws SQLException {
        try (Session s = s()) {
            QueryResult r = s.execute("SELECT '[1,5)'::int4range = '[1,5)'::int4range");
            assertEquals(Boolean.TRUE, r.rows().get(0)[0]);

            r = s.execute("SELECT '[1,5)'::int4range = '[1,5]'::int4range");
            assertEquals(Boolean.FALSE, r.rows().get(0)[0]);
        }
    }

    // =========================================================================
    // Functions
    // =========================================================================

    @Test void lower() throws SQLException {
        try (Session s = s()) {
            QueryResult r = s.execute("SELECT lower('[3,8)'::int4range)");
            assertEquals(3, ((Number) r.rows().get(0)[0]).intValue());
        }
    }

    @Test void upper() throws SQLException {
        try (Session s = s()) {
            QueryResult r = s.execute("SELECT upper('[3,8)'::int4range)");
            assertEquals(8, ((Number) r.rows().get(0)[0]).intValue());
        }
    }

    @Test void lowerInfiniteReturnsNull() throws SQLException {
        try (Session s = s()) {
            QueryResult r = s.execute("SELECT lower('(,5)'::int4range)");
            assertNull(r.rows().get(0)[0]);
        }
    }

    @Test void isempty() throws SQLException {
        try (Session s = s()) {
            QueryResult r = s.execute("SELECT isempty('empty'::int4range)");
            assertEquals(Boolean.TRUE, r.rows().get(0)[0]);

            r = s.execute("SELECT isempty('[1,5)'::int4range)");
            assertEquals(Boolean.FALSE, r.rows().get(0)[0]);
        }
    }

    @Test void lowerUpperInc() throws SQLException {
        try (Session s = s()) {
            QueryResult r = s.execute("SELECT lower_inc('[1,5)'::int4range)");
            assertEquals(Boolean.TRUE, r.rows().get(0)[0]);

            r = s.execute("SELECT upper_inc('[1,5)'::int4range)");
            assertEquals(Boolean.FALSE, r.rows().get(0)[0]);
        }
    }

    @Test void lowerUpperInf() throws SQLException {
        try (Session s = s()) {
            QueryResult r = s.execute("SELECT lower_inf('(,5)'::int4range)");
            assertEquals(Boolean.TRUE, r.rows().get(0)[0]);

            r = s.execute("SELECT upper_inf('[1,)'::int4range)");
            assertEquals(Boolean.TRUE, r.rows().get(0)[0]);
        }
    }

    @Test void rangeMerge() throws SQLException {
        try (Session s = s()) {
            QueryResult r = s.execute("SELECT range_merge('[1,5)'::int4range, '[3,8)'::int4range)");
            PgRange merged = (PgRange) r.rows().get(0)[0];
            assertEquals(1, ((Number) merged.lower()).intValue());
            assertEquals(8, ((Number) merged.upper()).intValue());
        }
    }

    // =========================================================================
    // Constructor functions
    // =========================================================================

    @Test void int4rangeConstructor() throws SQLException {
        try (Session s = s()) {
            QueryResult r = s.execute("SELECT int4range(1, 10)");
            PgRange range = (PgRange) r.rows().get(0)[0];
            assertEquals(1,  ((Number) range.lower()).intValue());
            assertEquals(10, ((Number) range.upper()).intValue());
            assertTrue(range.lowerInc());
            assertFalse(range.upperInc());
        }
    }

    @Test void int4rangeConstructorWithStyle() throws SQLException {
        try (Session s = s()) {
            QueryResult r = s.execute("SELECT int4range(1, 10, '[]')");
            PgRange range = (PgRange) r.rows().get(0)[0];
            // PostgreSQL canonicalizes [1,10] → [1,11): lower inclusive, upper exclusive
            assertTrue(range.lowerInc());
            assertFalse(range.upperInc());
        }
    }

    // =========================================================================
    // WHERE filter using range operators
    // =========================================================================

    @Test void whereContains() throws SQLException {
        try (Session s = s()) {
            s.execute("CREATE TABLE slots (id int, period int4range)");
            s.execute("INSERT INTO slots VALUES (1,'[1,10)'),(2,'[5,15)'),(3,'[20,30)')");
            QueryResult r = s.execute(
                    "SELECT id FROM slots WHERE period @> 6 ORDER BY id");
            List<Object[]> rows = r.rows();
            assertEquals(2, rows.size());
            assertEquals(1, ((Number) rows.get(0)[0]).intValue());
            assertEquals(2, ((Number) rows.get(1)[0]).intValue());
        }
    }
}
