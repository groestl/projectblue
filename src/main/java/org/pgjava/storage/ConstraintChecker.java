package org.pgjava.storage;

import org.pgjava.catalog.*;
import org.pgjava.engine.PgErrorException;
import org.pgjava.executor.EvalContext;
import org.pgjava.executor.Evaluator;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Enforces table constraints before heap mutations.
 *
 * <p>Phase 7 implements:
 * <ul>
 *   <li>NOT NULL — checked inline before calling the storage layer</li>
 *   <li>UNIQUE / PRIMARY KEY — uses the index for O(log n) duplicate detection</li>
 * </ul>
 *
 * <p>CHECK and FOREIGN KEY require the expression evaluator (Phase 8) and are
 * deferred.  Calling {@code checkForeignKey} or {@code checkCheck} in Phase 7
 * is a no-op (returns silently) so DDL can register FK/CHECK constraints without
 * breaking anything.
 *
 * <p>All methods are stateless; callers pass the table definition, the storage,
 * and the values being inserted/updated.
 */
public final class ConstraintChecker {

    private ConstraintChecker() {}

    // -------------------------------------------------------------------------
    // NOT NULL

    /**
     * Verify that no NOT NULL column has a null value.
     *
     * @throws SQLException SQLSTATE 23502 on violation
     */
    public static void checkNotNull(TableDef table, Object[] values) throws SQLException {
        List<ColumnDef> cols = table.columns();
        for (int i = 0; i < cols.size() && i < values.length; i++) {
            ColumnDef col = cols.get(i);
            if (!col.nullable() && values[i] == null) {
                throw PgErrorException.error("23502",
                        "null value in column \"" + col.name()
                                + "\" of relation \"" + table.name()
                                + "\" violates not-null constraint")
                        .detail("Failing row contains (" + rowToString(values) + ").")
                        .build();
            }
        }
    }

    // -------------------------------------------------------------------------
    // UNIQUE / PRIMARY KEY

    /**
     * Verify UNIQUE and PRIMARY KEY constraints using the table's indexes.
     *
     * <p>Only unique indexes are checked.  The key values are extracted from
     * {@code values} using the index column definitions (by column name →
     * attnum lookup on the table).
     *
     * @param excludeRowId  if non-null, the RowId being updated (its existing
     *                      index entry is allowed to exist — we only care about
     *                      other rows).  Pass null for INSERT.
     * @throws SQLException SQLSTATE 23505 on duplicate
     */
    public static void checkUnique(TableDef table, Object[] values,
                                   List<BTreeIndex> indexes,
                                   RowId excludeRowId) throws SQLException {
        for (BTreeIndex idx : indexes) {
            if (!idx.isUnique()) continue;
            Object[] keyVals = extractKeyValues(idx, table, values);
            if (keyVals == null) continue;

            // If any key column is NULL, PostgreSQL does NOT enforce uniqueness
            // (NULLs are considered distinct from each other)
            boolean anyNull = false;
            for (Object kv : keyVals) { if (kv == null) { anyNull = true; break; } }
            if (anyNull) continue;

            List<RowId> existing = idx.lookupExact(keyVals);
            for (RowId rid : existing) {
                // Ignore the row being updated
                if (rid.equals(excludeRowId)) continue;
                throw PgErrorException.error("23505",
                        "duplicate key value violates unique constraint \""
                                + idx.name() + "\"")
                        .detail("Key (" + keyColNames(idx) + ")=(" + keyValStr(keyVals)
                                + ") already exists.")
                        .build();
            }
        }
    }

    // -------------------------------------------------------------------------
    // CHECK (deferred to Phase 8)

    /**
     * Verify CHECK constraints by evaluating their expressions against the row values.
     *
     * @throws SQLException SQLSTATE 23514 on violation
     */
    public static void checkCheck(TableDef table, Object[] values, Evaluator eval)
            throws SQLException {
        List<String> colNames = table.columns().stream()
                .map(c -> c.name().toLowerCase()).toList();
        EvalContext.ColumnMap cm = EvalContext.ColumnMap.of(colNames);
        Row row = new Row(new RowId(0, 0), values);
        EvalContext ctx = EvalContext.of(row, cm);

        for (Constraint c : table.constraints()) {
            if (c instanceof Constraint.Check chk && chk.expr() != null) {
                Object result = eval.eval(chk.expr(), ctx);
                // PostgreSQL: CHECK passes if result is TRUE or NULL (not FALSE)
                if (Boolean.FALSE.equals(result)) {
                    String name = chk.name() != null ? chk.name()
                            : table.name() + (chk.column() != null ? "_" + chk.column() : "") + "_check";
                    throw PgErrorException.error("23514",
                            "new row for relation \"" + table.name()
                                    + "\" violates check constraint \"" + name + "\"")
                            .detail("Failing row contains (" + rowToString(values) + ").")
                            .build();
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // FOREIGN KEY (deferred to Phase 8)

    /**
     * Verify FOREIGN KEY constraints on INSERT or UPDATE.
     * For each FK, checks that the referenced values exist in the parent table.
     *
     * @param resolveTable  function to resolve a table name to its definition
     * @throws SQLException SQLSTATE 23503 on violation
     */
    public static void checkForeignKey(TableDef table, Object[] values,
                                       HeapStorage storage,
                                       TableResolver resolveTable) throws SQLException {
        for (Constraint c : table.constraints()) {
            if (!(c instanceof Constraint.ForeignKey fk)) continue;
            // Resolve referenced table
            String refName = fk.refTable();
            if (fk.refSchema() != null && !fk.refSchema().isEmpty()) {
                refName = fk.refSchema() + "." + refName;
            }
            TableDef refDef;
            try {
                refDef = resolveTable.resolve(refName);
            } catch (SQLException e) {
                // Referenced table doesn't exist — skip (DDL ordering issue)
                continue;
            }

            // Extract FK column values from the child row
            Object[] fkVals = new Object[fk.columns().size()];
            boolean anyNull = false;
            for (int i = 0; i < fk.columns().size(); i++) {
                ColumnDef col = table.column(fk.columns().get(i));
                if (col == null) continue;
                fkVals[i] = values[col.attnum() - 1];
                if (fkVals[i] == null) { anyNull = true; break; }
            }
            // PostgreSQL: if any FK column is NULL, the constraint is satisfied (MATCH SIMPLE)
            if (anyNull) continue;

            // Look for a matching row in the parent table using its unique indexes
            List<BTreeIndex> refIndexes = storage.indexesForTable(refDef.oid());
            List<String> refCols = fk.refColumns().stream()
                    .map(String::toLowerCase).toList();

            boolean found = false;
            for (BTreeIndex idx : refIndexes) {
                if (!idx.isUnique()) continue;
                List<String> idxCols = idx.def().columns().stream()
                        .map(ie -> ie.column().toLowerCase()).toList();
                if (!idxCols.equals(refCols)) continue;

                // Match: look up by FK values in the parent's index
                List<RowId> hits = idx.lookupExact(fkVals);
                if (!hits.isEmpty()) { found = true; break; }
            }

            if (!found) {
                // Fall back to full scan if no matching unique index exists
                HeapTable refHeap = storage.table(refDef.oid());
                if (refHeap != null) {
                    java.util.Iterator<Row> iter = refHeap.fullScan();
                    while (iter.hasNext()) {
                        Row row = iter.next();
                        boolean match = true;
                        for (int i = 0; i < refCols.size(); i++) {
                            ColumnDef refCol = refDef.column(refCols.get(i));
                            if (refCol == null) { match = false; break; }
                            Object refVal = row.values()[refCol.attnum() - 1];
                            if (!java.util.Objects.equals(fkVals[i], refVal)) {
                                match = false; break;
                            }
                        }
                        if (match) { found = true; break; }
                    }
                }
            }

            if (!found) {
                String name = fk.name() != null ? fk.name()
                        : table.name() + "_" + String.join("_", fk.columns()) + "_fkey";
                throw PgErrorException.error("23503",
                        "insert or update on table \"" + table.name()
                                + "\" violates foreign key constraint \"" + name + "\"")
                        .detail("Key (" + String.join(", ", fk.columns()) + ")=("
                                + keyValStr(fkVals) + ") is not present in table \""
                                + fk.refTable() + "\".")
                        .build();
            }
        }
    }

    /** Functional interface for resolving table names to TableDefs. */
    @FunctionalInterface
    public interface TableResolver {
        TableDef resolve(String name) throws SQLException;
    }

    /** Functional interface for iterating all tables in scope. */
    @FunctionalInterface
    public interface AllTablesSupplier {
        java.util.Collection<TableDef> allTables();
    }

    /**
     * Enforce FOREIGN KEY constraints on the parent side when deleting or updating
     * a parent row. Finds all child tables that reference this parent table and
     * checks/cascades as needed.
     *
     * @param parentTable    the parent table being deleted from / updated
     * @param deletedValues  the old row values being removed
     * @param storage        heap storage for cross-table access
     * @param allTables      supplier of all tables to scan for FK references
     * @param txMgr          transaction manager for cascading DML
     * @param tx             current transaction
     * @param isDelete       true for DELETE, false for UPDATE
     * @param newValues      for UPDATE: the new row values (null for DELETE)
     * @throws SQLException SQLSTATE 23503 on violation
     */
    public static void checkForeignKeyParent(
            TableDef parentTable, Object[] deletedValues,
            HeapStorage storage, AllTablesSupplier allTables,
            org.pgjava.wal.TransactionManager txMgr, org.pgjava.wal.Transaction tx,
            boolean isDelete, Object[] newValues) throws SQLException {

        String parentName = parentTable.name().toLowerCase();

        for (TableDef childTable : allTables.allTables()) {
            for (Constraint c : childTable.constraints()) {
                if (!(c instanceof Constraint.ForeignKey fk)) continue;
                if (!fk.refTable().equalsIgnoreCase(parentName)) continue;

                // Extract the referenced (parent PK) values from the deleted row
                Object[] parentKeyVals = new Object[fk.refColumns().size()];
                boolean anyNull = false;
                for (int i = 0; i < fk.refColumns().size(); i++) {
                    ColumnDef pcol = parentTable.column(fk.refColumns().get(i));
                    if (pcol == null) continue;
                    parentKeyVals[i] = deletedValues[pcol.attnum() - 1];
                    if (parentKeyVals[i] == null) { anyNull = true; break; }
                }
                if (anyNull) continue;

                // For UPDATE: if the referenced columns didn't change, skip
                if (!isDelete && newValues != null) {
                    boolean changed = false;
                    for (int i = 0; i < fk.refColumns().size(); i++) {
                        ColumnDef pcol = parentTable.column(fk.refColumns().get(i));
                        if (pcol == null) continue;
                        Object newVal = newValues[pcol.attnum() - 1];
                        if (!java.util.Objects.equals(parentKeyVals[i], newVal)) {
                            changed = true; break;
                        }
                    }
                    if (!changed) continue;
                }

                // Find child rows that reference this parent key
                HeapTable childHeap = storage.table(childTable.oid());
                if (childHeap == null) continue;
                List<BTreeIndex> childIndexes = storage.indexesForTable(childTable.oid());

                // Try index lookup on child FK columns
                List<Row> referencingRows = findReferencingRows(
                        childTable, fk, parentKeyVals, childHeap, childIndexes);

                if (referencingRows.isEmpty()) continue;

                // Apply the ON DELETE / ON UPDATE action
                org.pgjava.sql.ast.FkAction action = isDelete ? fk.onDelete() : fk.onUpdate();
                if (action == null) action = org.pgjava.sql.ast.FkAction.NO_ACTION;

                switch (action) {
                    case NO_ACTION, RESTRICT -> {
                        String name = fk.name() != null ? fk.name()
                                : childTable.name() + "_" + String.join("_", fk.columns()) + "_fkey";
                        throw PgErrorException.error("23503",
                                "update or delete on table \"" + parentTable.name()
                                        + "\" violates foreign key constraint \"" + name
                                        + "\" on table \"" + childTable.name() + "\"")
                                .detail("Key (" + String.join(", ", fk.refColumns()) + ")=("
                                        + keyValStr(parentKeyVals)
                                        + ") is still referenced from table \""
                                        + childTable.name() + "\".")
                                .build();
                    }
                    case CASCADE -> {
                        if (isDelete) {
                            // Delete all referencing child rows
                            childHeap.constraintLock().lock();
                            try {
                                for (Row childRow : referencingRows) {
                                    try {
                                        txMgr.delete(tx, childTable.oid(), childRow.rowId(),
                                                childRow.values(),
                                                org.pgjava.wal.TransactionManager.columnNameExtractor(childHeap));
                                    } catch (java.io.IOException e) {
                                        throw PgErrorException.error("XX000", "cascade delete failed: " + e.getMessage()).cause(e).build();
                                    }
                                }
                            } finally {
                                childHeap.constraintLock().unlock();
                            }
                        } else {
                            // Update FK columns in child rows to match new parent key
                            childHeap.constraintLock().lock();
                            try {
                                for (Row childRow : referencingRows) {
                                    Object[] newChildVals = childRow.values().clone();
                                    for (int i = 0; i < fk.columns().size(); i++) {
                                        ColumnDef ccol = childTable.column(fk.columns().get(i));
                                        ColumnDef pcol = parentTable.column(fk.refColumns().get(i));
                                        if (ccol != null && pcol != null) {
                                            newChildVals[ccol.attnum() - 1] = newValues[pcol.attnum() - 1];
                                        }
                                    }
                                    try {
                                        txMgr.update(tx, childTable.oid(), childRow.rowId(),
                                                childRow.values(), newChildVals,
                                                org.pgjava.wal.TransactionManager.columnNameExtractor(childHeap));
                                    } catch (java.io.IOException e) {
                                        throw PgErrorException.error("XX000", "cascade update failed: " + e.getMessage()).cause(e).build();
                                    }
                                }
                            } finally {
                                childHeap.constraintLock().unlock();
                            }
                        }
                    }
                    case SET_NULL -> {
                        childHeap.constraintLock().lock();
                        try {
                            for (Row childRow : referencingRows) {
                                Object[] newChildVals = childRow.values().clone();
                                for (String col : fk.columns()) {
                                    ColumnDef ccol = childTable.column(col);
                                    if (ccol != null) newChildVals[ccol.attnum() - 1] = null;
                                }
                                try {
                                    txMgr.update(tx, childTable.oid(), childRow.rowId(),
                                            childRow.values(), newChildVals,
                                            org.pgjava.wal.TransactionManager.columnNameExtractor(childHeap));
                                } catch (java.io.IOException e) {
                                    throw PgErrorException.error("XX000", "set null failed: " + e.getMessage()).cause(e).build();
                                }
                            }
                        } finally {
                            childHeap.constraintLock().unlock();
                        }
                    }
                    case SET_DEFAULT -> {
                        // SET DEFAULT: set FK columns to their default values
                        childHeap.constraintLock().lock();
                        try {
                            for (Row childRow : referencingRows) {
                                Object[] newChildVals = childRow.values().clone();
                                for (String col : fk.columns()) {
                                    ColumnDef ccol = childTable.column(col);
                                    if (ccol != null) newChildVals[ccol.attnum() - 1] = null; // default = null if no explicit default
                                }
                                try {
                                    txMgr.update(tx, childTable.oid(), childRow.rowId(),
                                            childRow.values(), newChildVals,
                                            org.pgjava.wal.TransactionManager.columnNameExtractor(childHeap));
                                } catch (java.io.IOException e) {
                                    throw PgErrorException.error("XX000", "set default failed: " + e.getMessage()).cause(e).build();
                                }
                            }
                        } finally {
                            childHeap.constraintLock().unlock();
                        }
                    }
                }
            }
        }
    }

    /** Find all rows in child table whose FK columns match the given parent key values. */
    private static List<Row> findReferencingRows(
            TableDef childTable, Constraint.ForeignKey fk, Object[] parentKeyVals,
            HeapTable childHeap, List<BTreeIndex> childIndexes) {
        // Try to find via index on FK columns
        List<String> fkCols = fk.columns().stream().map(String::toLowerCase).toList();
        for (BTreeIndex idx : childIndexes) {
            List<String> idxCols = idx.def().columns().stream()
                    .map(ie -> ie.column().toLowerCase()).toList();
            if (idxCols.equals(fkCols)) {
                List<RowId> hits = idx.lookupExact(parentKeyVals);
                List<Row> result = new ArrayList<>();
                for (RowId rid : hits) {
                    Row r = childHeap.lookupByRowId(rid);
                    if (r != null) result.add(r);
                }
                return result;
            }
        }

        // Fall back to heap scan
        List<Row> result = new ArrayList<>();
        java.util.Iterator<Row> iter = childHeap.fullScan();
        while (iter.hasNext()) {
            Row row = iter.next();
            boolean match = true;
            for (int i = 0; i < fkCols.size(); i++) {
                ColumnDef ccol = childTable.column(fkCols.get(i));
                if (ccol == null) { match = false; break; }
                Object childVal = row.values()[ccol.attnum() - 1];
                if (!java.util.Objects.equals(parentKeyVals[i], childVal)) {
                    match = false; break;
                }
            }
            if (match) result.add(row);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // ON CONFLICT — conflict detection

    /**
     * Identifies whether a unique conflict exists for the given values, returning
     * information about the conflicting row if found.
     *
     * <p>Used by INSERT ... ON CONFLICT to locate the existing row for DO UPDATE
     * (or simply detect a conflict for DO NOTHING).
     *
     * @param inferCols     if non-empty, only check the unique index whose columns
     *                      match this list (in order). Empty = check any unique index.
     * @param constraintName if non-null, only check the index with this name.
     * @return a {@link ConflictInfo} if a conflict was found, or {@code null}
     */
    public static ConflictInfo findConflict(TableDef table, Object[] values,
                                            List<BTreeIndex> indexes,
                                            List<String> inferCols,
                                            String constraintName) {
        for (BTreeIndex idx : indexes) {
            if (!idx.isUnique()) continue;
            // Filter by constraint name
            if (constraintName != null
                    && !constraintName.equalsIgnoreCase(idx.name())) continue;
            // Filter by inferred column list
            if (!inferCols.isEmpty()) {
                List<String> idxCols = idx.def().columns().stream()
                        .map(ie -> ie.column().toLowerCase()).toList();
                List<String> lowerInfer = inferCols.stream()
                        .map(String::toLowerCase).toList();
                if (!idxCols.equals(lowerInfer)) continue;
            }

            Object[] keyVals = extractKeyValues(idx, table, values);
            if (keyVals == null) continue;
            boolean anyNull = false;
            for (Object kv : keyVals) { if (kv == null) { anyNull = true; break; } }
            if (anyNull) continue;

            List<RowId> existing = idx.lookupExact(keyVals);
            if (!existing.isEmpty()) {
                return new ConflictInfo(existing.get(0), idx);
            }
        }
        return null;
    }

    /** Carries the conflicting row's RowId and the index that detected the conflict. */
    public record ConflictInfo(RowId rowId, BTreeIndex index) {}

    // -------------------------------------------------------------------------
    // Helper

    /** Format row values for DETAIL: "Failing row contains (...)" — matches PG's output. */
    private static String rowToString(Object[] values) {
        var sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            if (values[i] == null) sb.append("null");
            else sb.append(values[i]);
        }
        return sb.toString();
    }

    /** Format key column names for DETAIL messages. */
    private static String keyColNames(BTreeIndex idx) {
        return String.join(", ", idx.def().columns().stream()
                .map(ie -> ie.column()).toList());
    }

    /** Format key values for DETAIL messages. */
    private static String keyValStr(Object[] vals) {
        var sb = new StringBuilder();
        for (int i = 0; i < vals.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(vals[i] == null ? "null" : vals[i]);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // DOMAIN / ENUM type validation

    /**
     * Validate domain constraints (CHECK, NOT NULL) and enum membership
     * for all columns with domain or enum types.
     *
     * @throws SQLException SQLSTATE 23514 on domain check violation,
     *                      23502 on domain NOT NULL violation,
     *                      22P02 on invalid enum value
     */
    public static void checkDomainAndEnum(TableDef table, Object[] values, Evaluator eval)
            throws SQLException {
        List<ColumnDef> cols = table.columns();
        for (int i = 0; i < cols.size() && i < values.length; i++) {
            Object val = values[i];
            org.pgjava.types.PgType colType = cols.get(i).type();

            if (colType instanceof org.pgjava.types.EnumType enumType) {
                if (val == null) continue;
                String strVal = (val instanceof org.pgjava.types.EnumValue ev) ? ev.label() : val.toString();
                int ordinal = enumType.labels().indexOf(strVal);
                if (ordinal < 0) {
                    throw PgErrorException.error("22P02",
                            "invalid input value for enum " + enumType.name()
                                    + ": \"" + strVal + "\"").build();
                }
                // Store as EnumValue for correct ordinal-based comparison
                values[i] = new org.pgjava.types.EnumValue(strVal, ordinal, enumType);
            } else if (colType instanceof org.pgjava.types.DomainType domainType) {
                // NOT NULL check (walk entire domain chain)
                org.pgjava.types.PgType dt = domainType;
                while (dt instanceof org.pgjava.types.DomainType d) {
                    if (val == null && d.notNull()) {
                        throw PgErrorException.error("23502",
                                "domain " + d.name()
                                        + " does not allow null values").build();
                    }
                    // CHECK constraints
                    if (val != null && !d.checkConstraints().isEmpty()) {
                        EvalContext.ColumnMap cm = EvalContext.ColumnMap.of(List.of("value"));
                        Row row = new Row(new RowId(0, 0), new Object[]{val});
                        EvalContext ctx = EvalContext.of(row, cm);
                        for (org.pgjava.sql.ast.Expr checkExpr : d.checkConstraints()) {
                            Object result = eval.eval(checkExpr, ctx);
                            if (Boolean.FALSE.equals(result)) {
                                throw PgErrorException.error("23514",
                                        "value for domain " + d.name()
                                                + " violates check constraint \""
                                                + d.name() + "_check\"").build();
                            }
                        }
                    }
                    dt = d.baseType();
                }
            }
        }
    }

    private static Object[] extractKeyValues(BTreeIndex idx, TableDef table, Object[] values) {
        var indexCols = idx.def().columns();
        Object[] keys = new Object[indexCols.size()];
        for (int i = 0; i < indexCols.size(); i++) {
            ColumnDef colDef = table.column(indexCols.get(i).column());
            if (colDef == null) return null;
            int pos = colDef.attnum() - 1;
            if (pos < 0 || pos >= values.length) return null;
            keys[i] = values[pos];
        }
        return keys;
    }
}
