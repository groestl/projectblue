package org.pgjava.executor;

import org.pgjava.storage.Row;
import org.pgjava.storage.RowId;

import java.util.List;

/**
 * Operator that yields rows from a pre-materialized VALUES list.
 * Used for {@code INSERT … VALUES} and bare {@code VALUES (…)} queries.
 */
public final class ValuesScan extends Operator {

    private static final RowId VALUES_ROWID = new RowId(-2, -2);

    private final List<Object[]> rows;
    private int pos;

    public ValuesScan(List<String> colNames, List<Object[]> rows) {
        super(OutputSchema.ofNames(colNames));
        this.rows = rows;
    }

    @Override
    public void open() {
        pos = 0;
    }

    @Override
    public Row next() {
        if (pos >= rows.size()) return null;
        return new Row(VALUES_ROWID, rows.get(pos++));
    }

    @Override
    public void close() {
        pos = 0;
    }
}
