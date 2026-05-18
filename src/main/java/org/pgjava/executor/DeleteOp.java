package org.pgjava.executor;

import org.pgjava.catalog.TableDef;
import org.pgjava.engine.PgErrorException;
import org.pgjava.sql.ast.TargetEntry;
import org.pgjava.storage.*;
import org.pgjava.storage.LockMode;
import org.pgjava.wal.Transaction;
import org.pgjava.wal.TransactionManager;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * DELETE operator: scans the source (already filtered by WHERE) and tombstones
 * each row via the TransactionManager (WAL-before-heap).
 *
 * <p>Materializes the source first to avoid cursor-stability issues.
 */
public final class DeleteOp extends Operator {

    private final Operator           source;
    private final TableDef           def;
    private final HeapTable          heap;
    private final HeapStorage        storage;
    private final TransactionManager txMgr;
    private final Transaction        tx;
    private final Evaluator          eval;
    private final List<TargetEntry>  returning;

    private ConstraintChecker.AllTablesSupplier allTablesSupplier;
    private org.pgjava.engine.Database triggerDb;
    private java.util.List<String>     triggerSearchPath;
    private int          rowCount;
    private boolean      done;
    private List<Object[]> returningRows;

    public DeleteOp(Operator source, TableDef def, HeapStorage storage,
                    TransactionManager txMgr, Transaction tx) {
        this(source, def, storage, txMgr, tx, null, List.of());
    }

    public DeleteOp(Operator source, TableDef def, HeapStorage storage,
                    TransactionManager txMgr, Transaction tx,
                    Evaluator eval, List<TargetEntry> returning) {
        super(source.schema());
        this.source        = source;
        this.def           = def;
        this.storage       = storage;
        this.heap          = storage.table(def.oid());
        this.txMgr         = txMgr;
        this.tx            = tx;
        this.eval          = eval;
        this.returning     = returning;
        this.rowCount      = 0;
        this.done          = false;
        this.returningRows = returning.isEmpty() ? null : new ArrayList<>();
    }

    public void setAllTablesSupplier(ConstraintChecker.AllTablesSupplier s) { this.allTablesSupplier = s; }
    public void setTriggerContext(org.pgjava.engine.Database db, java.util.List<String> sp) {
        this.triggerDb = db; this.triggerSearchPath = sp;
    }
    public List<Object[]> returningRows() { return returningRows; }

    @Override
    public void open() throws SQLException {
        source.open();
    }

    /** Drives all deletes and returns the affected row count. */
    public int execute() throws SQLException {
        // Acquire ROW_EXCLUSIVE table-level lock (held until COMMIT/ROLLBACK).
        storage.tableLocks().acquire(def.oid(), tx.txid(), LockMode.ROW_EXCLUSIVE, 0);

        // Materialize first (without constraint lock) to avoid holding the lock during the scan.
        List<Row> toDelete = new ArrayList<>();
        Row r;
        while ((r = source.next()) != null) toDelete.add(r);
        source.close();

        // Narrow constraint lock for the mutation phase.
        heap.constraintLock().lock();
        try {
            return deleteUnderLock(toDelete);
        } finally {
            heap.constraintLock().unlock();
        }
    }

    private int deleteUnderLock(List<Row> toDelete) throws SQLException {
        // BEFORE STATEMENT trigger
        if (triggerDb != null) {
            TriggerExecutor.fireStatement(def, org.pgjava.catalog.TriggerDef.DELETE,
                    org.pgjava.catalog.TriggerDef.BEFORE, triggerDb, triggerSearchPath);
        }

        for (Row srcRow : toDelete) {
            // RETURNING: capture old values before tombstone
            Object[] oldVals = srcRow.values();

            // BEFORE ROW trigger
            if (triggerDb != null) {
                Object[] result = TriggerExecutor.fireBeforeRow(def,
                        org.pgjava.catalog.TriggerDef.DELETE,
                        null, oldVals, triggerDb, triggerSearchPath);
                if (result == null) continue; // trigger suppressed this row
            }

            // Parent-side FK enforcement (CASCADE, SET NULL, RESTRICT, etc.)
            if (allTablesSupplier != null) {
                ConstraintChecker.checkForeignKeyParent(
                        def, oldVals, storage, allTablesSupplier, txMgr, tx,
                        true, null);
            }

            try {
                txMgr.delete(tx, def.oid(), srcRow.rowId(), oldVals,
                        TransactionManager.columnNameExtractor(heap));
            } catch (IOException e) {
                throw PgErrorException.error("XX000", "delete WAL write failed: " + e.getMessage()).cause(e).build();
            }
            rowCount++;

            // RETURNING: evaluate against the deleted row's old values
            if (returningRows != null && eval != null) {
                EvalContext ctx = ReturningEval.buildContext(def, oldVals);
                returningRows.add(ReturningEval.evalRow(returning, def, ctx, eval));
            }

            // AFTER ROW trigger
            if (triggerDb != null) {
                TriggerExecutor.fireAfterRow(def, org.pgjava.catalog.TriggerDef.DELETE,
                        null, oldVals, triggerDb, triggerSearchPath);
            }
        }

        // AFTER STATEMENT trigger
        if (triggerDb != null) {
            TriggerExecutor.fireStatement(def, org.pgjava.catalog.TriggerDef.DELETE,
                    org.pgjava.catalog.TriggerDef.AFTER, triggerDb, triggerSearchPath);
        }

        return rowCount;
    }

    @Override
    public Row next() throws SQLException {
        if (!done) { execute(); done = true; }
        return null;
    }

    @Override
    public void close() {
        source.close();
    }
}

