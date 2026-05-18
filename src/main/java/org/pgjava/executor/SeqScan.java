package org.pgjava.executor;

import org.pgjava.catalog.TableDef;
import org.pgjava.storage.HeapTable;
import org.pgjava.storage.Row;
import org.pgjava.storage.TxSnapshot;

import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;

/**
 * Sequential scan of a heap table with READ COMMITTED visibility filtering.
 *
 * <p>The scan sees only rows that are visible under the provided {@link TxSnapshot}:
 * rows committed before the snapshot was taken, plus the current session's own
 * uncommitted inserts.  Rows inserted by other uncommitted transactions are
 * invisible (no dirty reads).
 */
public final class SeqScan extends Operator {

    private final HeapTable  heap;
    private final TxSnapshot snapshot;
    private Iterator<Row>    iter;

    public SeqScan(String alias, TableDef def, HeapTable heap, TxSnapshot snapshot) {
        super(OutputSchema.ofTable(alias, def));
        this.heap     = heap;
        this.snapshot = snapshot;
    }

    @Override
    public void open() {
        iter = heap.fullScan(snapshot);
    }

    @Override
    public Row next() throws SQLException {
        if (iter == null) return null;
        return iter.hasNext() ? iter.next() : null;
    }

    @Override
    public void close() {
        iter = null;
    }

    @Override protected String planNodeName() { return "Seq Scan"; }
    @Override protected String planDetail()   { return "on " + schema().alias(0); }
}
