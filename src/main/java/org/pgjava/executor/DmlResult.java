package org.pgjava.executor;

import org.pgjava.engine.ColumnMeta;

import java.util.List;

/**
 * Result of executing a DML statement (INSERT / UPDATE / DELETE).
 *
 * <p>When the statement has no RETURNING clause, {@code returningCols} is empty
 * and {@code hasReturning()} returns false.  In that case only {@code rowCount}
 * is meaningful.
 */
public record DmlResult(
        int              rowCount,
        List<ColumnMeta> returningCols,
        List<Object[]>   returningRows,
        boolean          returningClause
) {
    /** DML result without RETURNING. */
    public static DmlResult ofCount(int count) {
        return new DmlResult(count, List.of(), List.of(), false);
    }

    /** DML result with RETURNING rows. */
    public static DmlResult ofReturning(int count, List<ColumnMeta> cols, List<Object[]> rows) {
        return new DmlResult(count, cols, rows, true);
    }

    /** Whether the original statement had a RETURNING clause (even if zero rows matched). */
    public boolean hasReturning() { return returningClause; }
}
