package org.pgjava.executor;

import org.pgjava.catalog.ColumnDef;
import org.pgjava.catalog.GeneratedKind;
import org.pgjava.catalog.TableDef;
import org.pgjava.engine.PgErrorException;
import org.pgjava.sql.ast.*;
import org.pgjava.storage.*;
import org.pgjava.storage.LockMode;
import org.pgjava.types.PgOid;
import org.pgjava.types.TypeInput;
import org.pgjava.wal.Transaction;
import org.pgjava.wal.TransactionManager;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * UPDATE operator: scans the source (already filtered by WHERE), computes new
 * column values from assignment expressions, and applies them via the
 * TransactionManager (WAL-before-heap).
 *
 * <p>Materializes the source first to avoid cursor-stability issues (updating a row
 * while iterating the same heap would produce duplicate updates).
 */
public final class UpdateOp extends Operator {

    private final Operator           source;
    private final OutputSchema       srcSchema;
    private final TableDef           def;
    private final HeapTable          heap;
    private final HeapStorage        storage;
    private final List<BTreeIndex>   indexes;
    private final TransactionManager txMgr;
    private final Transaction        tx;
    private final List<String>       colNames;  // columns to update (in order)
    private final List<Expr>         colExprs;  // corresponding new-value expressions
    private final Evaluator          eval;
    private final List<TargetEntry>  returning;

    private ConstraintChecker.TableResolver tableResolver;
    private ConstraintChecker.AllTablesSupplier allTablesSupplier;
    private org.pgjava.engine.Database triggerDb;
    private java.util.List<String>     triggerSearchPath;
    private int          rowCount;
    private boolean      done;
    private List<Object[]> returningRows;

    public UpdateOp(Operator source, TableDef def, HeapStorage storage,
                    TransactionManager txMgr, Transaction tx,
                    List<String> colNames, List<Expr> colExprs, Evaluator eval) {
        this(source, def, storage, txMgr, tx, colNames, colExprs, eval, List.of());
    }

    public UpdateOp(Operator source, TableDef def, HeapStorage storage,
                    TransactionManager txMgr, Transaction tx,
                    List<String> colNames, List<Expr> colExprs, Evaluator eval,
                    List<TargetEntry> returning) {
        super(source.schema());
        this.source    = source;
        this.srcSchema = source.schema();
        this.def       = def;
        this.storage   = storage;
        this.heap      = storage.table(def.oid());
        this.indexes   = storage.indexesForTable(def.oid());
        this.txMgr     = txMgr;
        this.tx        = tx;
        this.colNames      = colNames;
        this.colExprs      = colExprs;
        this.eval          = eval;
        this.returning     = returning;
        this.rowCount      = 0;
        this.done          = false;
        this.returningRows = returning.isEmpty() ? null : new ArrayList<>();
    }

    public void setTableResolver(ConstraintChecker.TableResolver r) { this.tableResolver = r; }
    public void setAllTablesSupplier(ConstraintChecker.AllTablesSupplier s) { this.allTablesSupplier = s; }
    public void setTriggerContext(org.pgjava.engine.Database db, java.util.List<String> sp) {
        this.triggerDb = db; this.triggerSearchPath = sp;
    }
    public List<Object[]> returningRows() { return returningRows; }

    @Override
    public void open() throws SQLException {
        source.open();
    }

    /** Drives all updates and returns the affected row count. */
    public int execute() throws SQLException {
        // Acquire ROW_EXCLUSIVE table-level lock (held until COMMIT/ROLLBACK).
        storage.tableLocks().acquire(def.oid(), tx.txid(), LockMode.ROW_EXCLUSIVE, 0);

        // Materialize source rows first (without the constraint lock) to avoid holding
        // the lock during the scan phase.
        List<Row> toUpdate = new ArrayList<>();
        Row r;
        while ((r = source.next()) != null) toUpdate.add(r);
        source.close();

        // Narrow constraint lock for the mutation phase.
        heap.constraintLock().lock();
        try {
            return updateUnderLock(toUpdate);
        } finally {
            heap.constraintLock().unlock();
        }
    }

    private int updateUnderLock(List<Row> toUpdate) throws SQLException {
        // BEFORE STATEMENT trigger
        if (triggerDb != null) {
            TriggerExecutor.fireStatement(def, org.pgjava.catalog.TriggerDef.UPDATE,
                    org.pgjava.catalog.TriggerDef.BEFORE, triggerDb, triggerSearchPath);
        }

        for (Row srcRow : toUpdate) {
            EvalContext ctx = srcSchema.buildContext(srcRow);
            Object[] oldVals = srcRow.values();
            // Widen newVals to current column count in case ADD COLUMN extended the schema
            // after this row was inserted (new columns filled with their DEFAULT or null).
            int colCount = def.columnCount();
            Object[] newVals = new Object[colCount];
            System.arraycopy(oldVals, 0, newVals, 0, Math.min(oldVals.length, colCount));
            for (int i = oldVals.length; i < colCount; i++) {
                ColumnDef newCol = def.columns().get(i);
                if (newCol.defaultExpr() != null) {
                    try { newVals[i] = eval.eval(newCol.defaultExpr(), EvalContext.empty()); }
                    catch (SQLException e) { throw e; }
                    catch (Exception e) { /* complex default (e.g. nextval) — fall back to null */ }
                }
            }

            for (int i = 0; i < colNames.size(); i++) {
                ColumnDef col = def.column(colNames.get(i));
                if (col != null) {
                    // Reject SET on generated columns (PG allows only DEFAULT)
                    if (col.generated() == GeneratedKind.ALWAYS && col.defaultExpr() != null) {
                        throw PgErrorException.error("428C9",
                                "column \"" + col.name() + "\" can only be updated to DEFAULT")
                                .detail("Column \"" + col.name() + "\" is a generated column.")
                                .build();
                    }
                    Object v = CollatedValue.unwrap(eval.eval(colExprs.get(i), ctx));
                    if (v != null) {
                        int colOid = col.type().oid();
                        if (v instanceof String s
                                && colOid != PgOid.TEXT && colOid != PgOid.VARCHAR
                                && colOid != PgOid.BPCHAR && colOid != PgOid.NAME
                                && colOid != PgOid.UNKNOWN) {
                            try { v = TypeInput.parse(s, colOid); }
                            catch (SQLException e) { throw e; }
                            catch (Exception e) { /* keep string if parse fails */ }
                        } else if (v instanceof OffsetDateTime odt && colOid == PgOid.TIMESTAMP) {
                            v = odt.toLocalDateTime();
                        } else if (v instanceof LocalDateTime ldt && colOid == PgOid.TIMESTAMPTZ) {
                            v = ldt.atOffset(java.time.ZoneOffset.UTC);
                        }
                    }
                    newVals[col.attnum() - 1] = v;
                }
            }

            // Recompute GENERATED ALWAYS AS (expr) STORED columns using updated row
            for (int i = 0; i < colCount; i++) {
                ColumnDef gcol = def.columns().get(i);
                if (gcol.generated() == GeneratedKind.ALWAYS && gcol.defaultExpr() != null) {
                    EvalContext rowCtx = ReturningEval.buildContext(def, newVals);
                    newVals[i] = eval.eval(gcol.defaultExpr(), rowCtx);
                }
            }

            // BEFORE ROW trigger
            if (triggerDb != null) {
                newVals = TriggerExecutor.fireBeforeRow(def, org.pgjava.catalog.TriggerDef.UPDATE,
                        newVals, oldVals, triggerDb, triggerSearchPath);
                if (newVals == null) continue; // trigger suppressed this row
            }

            ConstraintChecker.checkNotNull(def, newVals);
            ConstraintChecker.checkUnique(def, newVals, indexes, srcRow.rowId());
            ConstraintChecker.checkCheck(def, newVals, eval);
            ConstraintChecker.checkDomainAndEnum(def, newVals, eval);
            if (tableResolver != null) {
                ConstraintChecker.checkForeignKey(def, newVals, storage, tableResolver);
            }
            // Parent-side FK enforcement (CASCADE, SET NULL, RESTRICT, etc.)
            if (allTablesSupplier != null) {
                ConstraintChecker.checkForeignKeyParent(
                        def, oldVals, storage, allTablesSupplier, txMgr, tx,
                        false, newVals);
            }

            try {
                txMgr.update(tx, def.oid(), srcRow.rowId(), oldVals, newVals,
                        TransactionManager.columnNameExtractor(heap));
            } catch (IOException e) {
                throw PgErrorException.error("XX000", "update WAL write failed: " + e.getMessage()).cause(e).build();
            }
            rowCount++;

            // RETURNING: evaluate against new values
            if (returningRows != null) {
                EvalContext retCtx = ReturningEval.buildContext(def, newVals);
                returningRows.add(ReturningEval.evalRow(returning, def, retCtx, eval));
            }

            // AFTER ROW trigger
            if (triggerDb != null) {
                TriggerExecutor.fireAfterRow(def, org.pgjava.catalog.TriggerDef.UPDATE,
                        newVals, oldVals, triggerDb, triggerSearchPath);
            }
        }

        // AFTER STATEMENT trigger
        if (triggerDb != null) {
            TriggerExecutor.fireStatement(def, org.pgjava.catalog.TriggerDef.UPDATE,
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
