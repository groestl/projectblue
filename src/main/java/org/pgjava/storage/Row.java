package org.pgjava.storage;

import java.util.Arrays;

/**
 * An immutable heap row: a {@link RowId} plus an array of column values.
 *
 * <p>Values are in column-definition order (0-based).  A null element means
 * SQL NULL.  The array must not be mutated after construction; callers that
 * need to modify values must create a new Row.
 *
 * <p>The RowId is required so that DML operators (Update, Delete) and index
 * maintenance can unambiguously identify the source row across operators.
 */
public final class Row {

    private final RowId     rowId;
    private final Object[]  values;
    /** txid that inserted this row; 0 = pre-existing / always visible. */
    private final long      xmin;

    /** Constructor for pre-existing / always-visible rows ({@code xmin = 0}). */
    public Row(RowId rowId, Object[] values) {
        this(rowId, values, 0L);
    }

    public Row(RowId rowId, Object[] values, long xmin) {
        this.rowId  = rowId;
        this.values = values;
        this.xmin   = xmin;
    }

    public RowId    rowId()           { return rowId; }
    public Object[] values()          { return values; }
    public long     xmin()            { return xmin; }
    public int      columnCount()     { return values.length; }

    /**
     * Returns the value at {@code zeroIndex}, or {@code null} if the index is
     * beyond the physical column count.  This handles rows that were inserted
     * before an {@code ADD COLUMN} extended the table schema — columns added
     * after insertion are implicitly NULL.
     */
    public Object get(int zeroIndex) {
        if (zeroIndex < 0 || zeroIndex >= values.length) return null;
        return values[zeroIndex];
    }

    /** Returns a copy of the values array (safe to hand to callers). */
    public Object[] copyValues() {
        return Arrays.copyOf(values, values.length);
    }

    @Override
    public String toString() {
        return rowId + Arrays.toString(values);
    }
}
