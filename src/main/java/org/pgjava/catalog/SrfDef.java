package org.pgjava.catalog;

import org.pgjava.types.PgType;

import java.sql.SQLException;
import java.util.List;

/**
 * Metadata + implementation for a set-returning function (SRF).
 *
 * <p>Unlike scalar {@link FunctionDef}, an SRF produces zero or more rows when
 * called in a {@code FROM} clause.  Each invocation returns an
 * {@code Iterable<Object[]>}; each element is one output row whose width
 * matches {@link #outColumnNames()}.
 *
 * <p>Examples: {@code generate_series(1,5)}, {@code pg_listening_channels()},
 * {@code unnest(array)}.
 */
public record SrfDef(
        long         oid,
        String       name,
        String       schemaName,
        List<PgType> argTypes,
        List<String> outColumnNames,   // names of the output columns
        boolean      variadic,
        SrfImpl      impl
) {
    /** Callable implementation: given evaluated argument values, produces rows. */
    @FunctionalInterface
    public interface SrfImpl {
        Iterable<Object[]> invoke(Object[] args) throws SQLException;
    }

    /** Returns true if this SRF matches the given (name, argCount) call site. */
    public boolean matches(String name, int argCount) {
        return this.name().equalsIgnoreCase(name)
                && (variadic || argTypes.size() == argCount);
    }
}
