package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.pgjava.catalog.AggregateDef;
import org.pgjava.engine.Database;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that user-defined aggregates registered in the {@link org.pgjava.catalog.FunctionRegistry}
 * are recognized by the Planner as aggregate functions and dispatched correctly by
 * {@link org.pgjava.executor.AggAccumulator}.
 *
 * <p>The bug: {@code Planner.isAggFunc()} checked only against a hardcoded set
 * {@code AGG_NAMES}. Any aggregate not in that set was treated as a scalar function,
 * causing it to be evaluated per-row rather than accumulated.
 *
 * <p>The fix: {@code isAggFunc()} now also consults
 * {@code catalog.functions().findAggregate(name)}, and {@code AggAccumulator}
 * dispatches unknown names to the matching {@link AggregateDef.Accumulator}.
 */
class UserDefinedAggregateTest {

    /**
     * Register a simple "product" aggregate — multiplies all non-null values.
     * Returns 1 for an empty group (identity element for multiplication).
     */
    private static AggregateDef makeProductAggregate() {
        var int4Type  = PgTypeRegistry.INSTANCE.byOid(PgOid.INT4);
        var int8Type  = PgTypeRegistry.INSTANCE.byOid(PgOid.INT8);
        return new AggregateDef(
                70000L, "product", "public",
                List.of(int4Type), int8Type,
                () -> new AggregateDef.Accumulator() {
                    private long product = 1;
                    private boolean hasValue = false;

                    @Override
                    public void accumulate(Object value) {
                        if (value != null) {
                            product *= ((Number) value).longValue();
                            hasValue = true;
                        }
                    }

                    @Override
                    public Object result() {
                        return hasValue ? product : null;
                    }
                });
    }

    @Test
    void uda_singleGroup() throws SQLException {
        Database db = Database.create("uda_single");
        db.catalog().functions().registerAggregate(makeProductAggregate());

        try (var s = db.openSession()) {
            s.execute("CREATE TABLE nums (n INT)");
            s.execute("INSERT INTO nums VALUES (2), (3), (4)");

            var res = s.execute("SELECT product(n) FROM nums");
            long result = ((Number) res.rows().get(0)[0]).longValue();
            assertEquals(24L, result, "2 * 3 * 4 = 24");
        }
    }

    @Test
    void uda_groupBy() throws SQLException {
        Database db = Database.create("uda_group");
        db.catalog().functions().registerAggregate(makeProductAggregate());

        try (var s = db.openSession()) {
            s.execute("CREATE TABLE items (cat TEXT, val INT)");
            s.execute("INSERT INTO items VALUES ('a', 2), ('a', 3), ('b', 5), ('b', 4)");

            var res = s.execute("SELECT cat, product(val) FROM items GROUP BY cat ORDER BY cat");
            assertEquals(2, res.rows().size());

            // cat='a': 2 * 3 = 6
            assertEquals("a", res.rows().get(0)[0].toString());
            assertEquals(6L, ((Number) res.rows().get(0)[1]).longValue());

            // cat='b': 5 * 4 = 20
            assertEquals("b", res.rows().get(1)[0].toString());
            assertEquals(20L, ((Number) res.rows().get(1)[1]).longValue());
        }
    }

    @Test
    void uda_nullsIgnored() throws SQLException {
        Database db = Database.create("uda_nulls");
        db.catalog().functions().registerAggregate(makeProductAggregate());

        try (var s = db.openSession()) {
            s.execute("CREATE TABLE vals (n INT)");
            s.execute("INSERT INTO vals VALUES (3), (NULL), (7)");

            var res = s.execute("SELECT product(n) FROM vals");
            long result = ((Number) res.rows().get(0)[0]).longValue();
            assertEquals(21L, result, "NULL ignored: 3 * 7 = 21");
        }
    }

    @Test
    void uda_emptyGroup_returnsNull() throws SQLException {
        Database db = Database.create("uda_empty");
        db.catalog().functions().registerAggregate(makeProductAggregate());

        try (var s = db.openSession()) {
            s.execute("CREATE TABLE empty_t (n INT)");
            // No rows inserted
            var res = s.execute("SELECT product(n) FROM empty_t");
            assertNull(res.rows().get(0)[0], "Empty group: product returns null");
        }
    }

    @Test
    void builtinAggregatesStillWork() throws SQLException {
        // Regression: registering a UDA must not break existing built-in aggregates
        Database db = Database.create("uda_regression");
        db.catalog().functions().registerAggregate(makeProductAggregate());

        try (var s = db.openSession()) {
            s.execute("CREATE TABLE t (x INT)");
            s.execute("INSERT INTO t VALUES (1), (2), (3), (4)");

            var r = s.execute("SELECT count(*), sum(x), min(x), max(x) FROM t");
            Object[] row = r.rows().get(0);
            assertEquals(4L,  ((Number) row[0]).longValue());
            assertEquals(10L, ((Number) row[1]).longValue()); // or BigDecimal
            assertEquals(1,   ((Number) row[2]).intValue());
            assertEquals(4,   ((Number) row[3]).intValue());
        }
    }
}
