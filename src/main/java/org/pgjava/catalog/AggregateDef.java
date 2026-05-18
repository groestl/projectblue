package org.pgjava.catalog;

import org.pgjava.types.PgType;

import java.sql.SQLException;
import java.util.List;

/**
 * Metadata + implementation for an aggregate function (COUNT, SUM, AVG, etc.).
 */
public record AggregateDef(
        long         oid,
        String       name,
        String       schemaName,
        List<PgType> argTypes,
        PgType       returnType,
        AccumulatorFactory factory
) {
    /** Creates a fresh accumulator for one aggregation group. */
    @FunctionalInterface
    public interface AccumulatorFactory {
        Accumulator create();
    }

    /** Stateful accumulator for one aggregation group. */
    public interface Accumulator {
        void accumulate(Object value) throws SQLException;
        Object result() throws SQLException;
    }
}
