package org.pgjava.executor;

import org.pgjava.catalog.*;
import org.pgjava.engine.PgErrorException;
import org.pgjava.sql.ast.*;
import org.pgjava.storage.*;
import org.pgjava.storage.LockMode;
import org.pgjava.types.ArrayType;
import org.pgjava.types.CoercionEngine;
import org.pgjava.types.PgOid;
import org.pgjava.types.TypeInput;
import org.pgjava.wal.Transaction;
import org.pgjava.wal.TransactionManager;
import org.pgjava.storage.TxSnapshot;

import java.time.OffsetDateTime;
import java.time.LocalDateTime;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * INSERT operator: reads rows from the source operator and inserts them into
 * the target table, enforcing constraints.
 *
 * <p>Columns are mapped from the source by the column list provided at plan time.
 * If the column list is empty, all columns in definition order are assumed.
 *
 * <p>Returns rows only when RETURNING is present (for RETURNING support,
 * set a Project wrapping this operator's output in the plan).
 */
public final class InsertOp extends Operator {

    private final Operator           source;
    private final TableDef           def;
    private final HeapTable          heap;
    private final HeapStorage        storage;
    private final List<BTreeIndex>   indexes;
    private final TransactionManager txMgr;
    private final Transaction        tx;
    private final int[]              colMapping; // source col[i] → target attnum-1
    private final Evaluator          eval;
    private final List<TargetEntry>  returning;
    private final OnConflictClause   onConflict;
    private TxSnapshot               txSnapshot;   // set by Planner; used for ON CONFLICT lookup
    private ConstraintChecker.TableResolver tableResolver; // for FK checks
    private org.pgjava.engine.Database triggerDb;
    private java.util.List<String>     triggerSearchPath;

    private int             rowCount;
    private boolean         done;
    private List<Object[]>  returningRows;

    public InsertOp(Operator source, TableDef def, HeapStorage storage,
                    TransactionManager txMgr, Transaction tx, int[] colMapping,
                    Evaluator eval) {
        this(source, def, storage, txMgr, tx, colMapping, eval, List.of(), null);
    }

    public InsertOp(Operator source, TableDef def, HeapStorage storage,
                    TransactionManager txMgr, Transaction tx, int[] colMapping,
                    Evaluator eval, List<TargetEntry> returning) {
        this(source, def, storage, txMgr, tx, colMapping, eval, returning, null);
    }

    public InsertOp(Operator source, TableDef def, HeapStorage storage,
                    TransactionManager txMgr, Transaction tx, int[] colMapping,
                    Evaluator eval, List<TargetEntry> returning, OnConflictClause onConflict) {
        super(source.schema());
        this.source        = source;
        this.def           = def;
        this.storage       = storage;
        this.heap          = storage.table(def.oid());
        this.indexes       = storage.indexesForTable(def.oid());
        this.txMgr         = txMgr;
        this.tx            = tx;
        this.colMapping    = colMapping;
        this.eval          = eval;
        this.returning     = returning;
        this.onConflict    = onConflict;
        this.rowCount      = 0;
        this.done          = false;
        this.returningRows = returning.isEmpty() ? null : new ArrayList<>();
    }

    public void setSnapshot(TxSnapshot snap) { this.txSnapshot = snap; }
    public void setTableResolver(ConstraintChecker.TableResolver r) { this.tableResolver = r; }
    public void setTriggerContext(org.pgjava.engine.Database db, java.util.List<String> sp) {
        this.triggerDb = db; this.triggerSearchPath = sp;
    }
    public List<Object[]> returningRows()    { return returningRows; }

    @Override
    public void open() throws SQLException {
        source.open();
    }

    /** Drives all inserts and returns the affected row count (in a synthetic row). */
    public int execute() throws SQLException {
        // Acquire ROW_EXCLUSIVE table-level lock (held until COMMIT/ROLLBACK).
        try {
            storage.tableLocks().acquire(def.oid(), tx.txid(), LockMode.ROW_EXCLUSIVE, 0);
        } catch (SQLException e) { throw e; }

        // Narrow constraint lock for the "constraint-check → heap-insert" window.
        heap.constraintLock().lock();
        try {
            return executeUnderLock();
        } finally {
            heap.constraintLock().unlock();
        }
    }

    private int executeUnderLock() throws SQLException {
        // BEFORE STATEMENT trigger
        if (triggerDb != null) {
            TriggerExecutor.fireStatement(def, org.pgjava.catalog.TriggerDef.INSERT,
                    org.pgjava.catalog.TriggerDef.BEFORE, triggerDb, triggerSearchPath);
        }

        Row srcRow;
        while ((srcRow = source.next()) != null) {
            Object[] srcVals = srcRow.values();
            // Map source columns to target layout
            int colCount = def.columnCount();
            Object[] targetVals = new Object[colCount];

            if (colMapping.length == 0) {
                // No explicit column list: positional, pad missing with null
                int len = Math.min(srcVals.length, colCount);
                System.arraycopy(srcVals, 0, targetVals, 0, len);
            } else {
                for (int i = 0; i < colMapping.length && i < srcVals.length; i++) {
                    int targetIdx = colMapping[i];
                    if (targetIdx >= 0 && targetIdx < colCount) {
                        targetVals[targetIdx] = srcVals[i];
                    }
                }
            }

            // Reject explicit non-DEFAULT values for GENERATED ALWAYS columns
            for (int i = 0; i < colCount; i++) {
                ColumnDef col = def.columns().get(i);
                if (col.generated() == GeneratedKind.ALWAYS && col.defaultExpr() != null
                        && targetVals[i] != null) {
                    throw PgErrorException.error("428C9",
                            "cannot insert a non-DEFAULT value into column \"" + col.name() + "\"")
                            .detail("Column \"" + col.name() + "\" is a generated column.")
                            .build();
                }
            }

            // Coerce values to target column type where necessary
            for (int i = 0; i < colCount; i++) {
                Object v = targetVals[i];
                if (v == null) continue;
                ColumnDef col = def.columns().get(i);
                int colOid = col.type().oid();

                if (v instanceof String s) {
                    if (col.type() instanceof ArrayType at) {
                        // Array literal: "{elem1,elem2}" → List<Object>
                        targetVals[i] = TypeInput.parseArrayLiteral(s, at.elementType().oid());
                    } else if (colOid != PgOid.TEXT && colOid != PgOid.VARCHAR
                            && colOid != PgOid.BPCHAR && colOid != PgOid.NAME
                            && colOid != PgOid.UNKNOWN) {
                        // Only coerce if the target type is not a plain string type
                        targetVals[i] = TypeInput.parse(s, colOid);
                    }
                } else if (v instanceof OffsetDateTime odt && colOid == PgOid.TIMESTAMP) {
                    // timestamptz → timestamp without time zone: strip offset
                    targetVals[i] = odt.toLocalDateTime();
                } else if (v instanceof LocalDateTime ldt && colOid == PgOid.TIMESTAMPTZ) {
                    // timestamp → timestamptz: assume UTC
                    targetVals[i] = ldt.atOffset(java.time.ZoneOffset.UTC);
                }
            }

            // Apply DEFAULT expressions for any null value where DEFAULT is defined
            for (int i = 0; i < colCount; i++) {
                ColumnDef col = def.columns().get(i);
                if (col.generated() == GeneratedKind.ALWAYS) continue; // computed below
                if (targetVals[i] == null && col.defaultExpr() != null) {
                    targetVals[i] = eval.eval(col.defaultExpr(), EvalContext.empty());
                }
            }

            // Evaluate GENERATED ALWAYS AS (expr) STORED columns using the row values
            for (int i = 0; i < colCount; i++) {
                ColumnDef col = def.columns().get(i);
                if (col.generated() == GeneratedKind.ALWAYS && col.defaultExpr() != null) {
                    EvalContext rowCtx = ReturningEval.buildContext(def, targetVals);
                    targetVals[i] = eval.eval(col.defaultExpr(), rowCtx);
                }
            }

            // BEFORE ROW trigger
            if (triggerDb != null) {
                targetVals = TriggerExecutor.fireBeforeRow(def, org.pgjava.catalog.TriggerDef.INSERT,
                        targetVals, null, triggerDb, triggerSearchPath);
                if (targetVals == null) continue; // trigger suppressed this row
            }

            // ON CONFLICT — check for a conflicting row before normal constraint enforcement
            if (onConflict != null) {
                List<String> inferCols;
                String constraintName = null;

                if (onConflict.infer() != null) {
                    String cn = onConflict.infer().constraintName();
                    if (cn != null && !cn.isEmpty()) {
                        // Resolve constraint name → column list (constraint name ≠ index name)
                        List<String> cols = resolveConstraintColumns(cn);
                        inferCols = cols != null ? cols : List.of();
                        // Don't pass constraintName — we matched via columns instead
                    } else if (onConflict.infer().indexElems() != null
                            && !onConflict.infer().indexElems().isEmpty()) {
                        inferCols = new ArrayList<>();
                        for (var ie : onConflict.infer().indexElems()) {
                            if (ie.colname() != null) {
                                inferCols.add(ie.colname().toLowerCase());
                            }
                            // Expression-based index elements are ignored for conflict
                            // inference — only simple column references are supported
                        }
                    } else {
                        inferCols = List.of();
                    }
                } else {
                    inferCols = List.of();
                }

                ConstraintChecker.ConflictInfo conflict =
                        ConstraintChecker.findConflict(def, targetVals, indexes,
                                inferCols, constraintName);
                if (conflict != null) {
                    if (onConflict.action() == OnConflictAction.NOTHING) {
                        continue; // DO NOTHING — skip this row
                    }
                    // DO UPDATE
                    TxSnapshot snap = txSnapshot != null
                            ? txSnapshot : new TxSnapshot(tx.txid(), java.util.Set.of());
                    Row existingRow = heap.lookupByRowId(conflict.rowId(), snap);
                    if (existingRow == null) {
                        // Row was tombstoned concurrently — treat as no conflict
                    } else {
                        handleDoUpdate(existingRow, targetVals);
                        continue;
                    }
                }
            }

            // Normal constraint checks + insert
            ConstraintChecker.checkNotNull(def, targetVals);
            ConstraintChecker.checkUnique(def, targetVals, indexes, null);
            ConstraintChecker.checkCheck(def, targetVals, eval);
            ConstraintChecker.checkDomainAndEnum(def, targetVals, eval);
            if (tableResolver != null) {
                ConstraintChecker.checkForeignKey(def, targetVals, storage, tableResolver);
            }

            // Insert via TransactionManager (WAL before heap)
            try {
                txMgr.insert(tx, def.oid(), targetVals,
                        TransactionManager.columnNameExtractor(heap));
            } catch (IOException e) {
                throw PgErrorException.error("XX000", "insert WAL write failed: " + e.getMessage()).cause(e).build();
            }
            rowCount++;

            // RETURNING: evaluate expressions against the inserted row
            if (returningRows != null) {
                EvalContext ctx = ReturningEval.buildContext(def, targetVals);
                returningRows.add(ReturningEval.evalRow(returning, def, ctx, eval));
            }

            // AFTER ROW trigger
            if (triggerDb != null) {
                TriggerExecutor.fireAfterRow(def, org.pgjava.catalog.TriggerDef.INSERT,
                        targetVals, null, triggerDb, triggerSearchPath);
            }
        }
        source.close();

        // AFTER STATEMENT trigger
        if (triggerDb != null) {
            TriggerExecutor.fireStatement(def, org.pgjava.catalog.TriggerDef.INSERT,
                    org.pgjava.catalog.TriggerDef.AFTER, triggerDb, triggerSearchPath);
        }

        return rowCount;
    }

    /**
     * Resolve a constraint name to its column list by looking at the table's constraints.
     * Returns null if the constraint is not found.
     */
    private List<String> resolveConstraintColumns(String constraintName) {
        for (org.pgjava.catalog.Constraint c : def.constraints()) {
            if (!constraintName.equalsIgnoreCase(c.name())) continue;
            if (c instanceof org.pgjava.catalog.Constraint.PrimaryKey pk) return pk.columns();
            if (c instanceof org.pgjava.catalog.Constraint.Unique uq) return uq.columns();
        }
        return null;
    }

    /**
     * Execute the DO UPDATE branch of ON CONFLICT.
     *
     * @param existingRow the current heap row that conflicts
     * @param excludedVals the new values that would have been inserted (EXCLUDED pseudo-table)
     */
    private void handleDoUpdate(Row existingRow, Object[] excludedVals) throws SQLException {
        // Build evaluation context: existing row as unqualified + "excluded" alias for new values
        List<String> colNames = def.columns().stream()
                .map(c -> c.name().toLowerCase()).toList();
        EvalContext.ColumnMap cm = EvalContext.ColumnMap.of(colNames);
        Row excludedRow = new Row(new RowId(0, 0), excludedVals);
        EvalContext ctx = EvalContext.of(existingRow, cm)
                .child("excluded", excludedRow, cm);

        Object[] newVals = existingRow.values().clone();
        for (AssignTarget at : onConflict.targetList()) {
            if (at.names().isEmpty()) continue;
            String colName = at.names().get(0);
            ColumnDef col = def.column(colName);
            if (col == null) {
                throw PgErrorException.error("42703",
                        "column \"" + colName + "\" of relation \"" + def.name() + "\" does not exist").build();
            }
            if (at.val() != null) {
                Object v = CollatedValue.unwrap(eval.eval(at.val(), ctx));
                if (v != null) {
                    int colOid = col.type().oid();
                    if (v instanceof String s
                            && colOid != PgOid.TEXT && colOid != PgOid.VARCHAR
                            && colOid != PgOid.BPCHAR && colOid != PgOid.NAME
                            && colOid != PgOid.UNKNOWN) {
                        v = TypeInput.parse(s, colOid);
                    } else if (v instanceof OffsetDateTime odt && colOid == PgOid.TIMESTAMP) {
                        v = odt.toLocalDateTime();
                    } else if (v instanceof LocalDateTime ldt && colOid == PgOid.TIMESTAMPTZ) {
                        v = ldt.atOffset(java.time.ZoneOffset.UTC);
                    }
                }
                newVals[col.attnum() - 1] = v;
            }
        }

        // Apply WHERE clause if present (e.g. ON CONFLICT DO UPDATE SET ... WHERE ...)
        if (onConflict.whereClause() != null) {
            Object pass = eval.eval(onConflict.whereClause(), ctx);
            if (!Boolean.TRUE.equals(pass)) return; // condition not met — skip
        }

        // BEFORE UPDATE ROW trigger (ON CONFLICT switches to UPDATE semantics)
        Object[] oldVals = existingRow.values();
        if (triggerDb != null) {
            newVals = TriggerExecutor.fireBeforeRow(def, org.pgjava.catalog.TriggerDef.UPDATE,
                    newVals, oldVals, triggerDb, triggerSearchPath);
            if (newVals == null) return; // trigger suppressed
        }

        ConstraintChecker.checkNotNull(def, newVals);
        ConstraintChecker.checkUnique(def, newVals, indexes, existingRow.rowId());
        ConstraintChecker.checkCheck(def, newVals, eval);
        ConstraintChecker.checkDomainAndEnum(def, newVals, eval);
        if (tableResolver != null) {
            ConstraintChecker.checkForeignKey(def, newVals, storage, tableResolver);
        }

        try {
            txMgr.update(tx, def.oid(), existingRow.rowId(), oldVals, newVals,
                    TransactionManager.columnNameExtractor(heap));
        } catch (IOException e) {
            throw PgErrorException.error("XX000", "upsert WAL write failed: " + e.getMessage()).cause(e).build();
        }
        rowCount++;

        if (returningRows != null) {
            EvalContext retCtx = ReturningEval.buildContext(def, newVals);
            returningRows.add(ReturningEval.evalRow(returning, def, retCtx, eval));
        }

        // AFTER UPDATE ROW trigger
        if (triggerDb != null) {
            TriggerExecutor.fireAfterRow(def, org.pgjava.catalog.TriggerDef.UPDATE,
                    newVals, oldVals, triggerDb, triggerSearchPath);
        }
    }

    @Override
    public Row next() throws SQLException {
        if (!done) { execute(); done = true; }
        return null; // INSERT doesn't return rows (unless RETURNING — handled separately)
    }

    @Override
    public void close() {
        source.close();
    }
}
