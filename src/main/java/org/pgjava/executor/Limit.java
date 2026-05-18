package org.pgjava.executor;

import org.pgjava.storage.Row;

import java.sql.SQLException;

/**
 * Applies LIMIT and OFFSET to the input.
 */
public final class Limit extends Operator {

    private final Operator source;
    private final long     limitCount;  // -1 = no limit
    private final long     offset;

    private long emitted;
    private long skipped;

    public Limit(Operator source, long limitCount, long offset) {
        super(source.schema());
        this.source     = source;
        this.limitCount = limitCount;
        this.offset     = offset;
    }

    @Override
    public void open() throws SQLException {
        source.open();
        emitted = 0;
        skipped = 0;
    }

    @Override
    public Row next() throws SQLException {
        if (limitCount >= 0 && emitted >= limitCount) return null;
        Row row;
        while ((row = source.next()) != null) {
            if (skipped < offset) { skipped++; continue; }
            emitted++;
            return row;
        }
        return null;
    }

    @Override
    public void close() {
        source.close();
    }

    @Override protected java.util.List<Operator> planChildren() { return java.util.List.of(source); }
}
