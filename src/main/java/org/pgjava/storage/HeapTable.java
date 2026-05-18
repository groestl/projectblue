package org.pgjava.storage;

import org.pgjava.catalog.TableDef;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Heap storage for a single table: a list of {@link Row}s with MVCC visibility
 * and a table-level write lock for serialising concurrent DML.
 *
 * <h3>Concurrency model</h3>
 * <ul>
 *   <li><b>Reads</b> are lock-free: {@link CopyOnWriteArrayList} provides a
 *       point-in-time snapshot iterator that is safe under concurrent writes.
 *       Visibility filtering via a {@link TxSnapshot} implements READ COMMITTED
 *       isolation — a statement never sees uncommitted rows from other sessions.</li>
 *   <li><b>DML writers</b> acquire a table-level lock (e.g. ROW_EXCLUSIVE) via
 *       {@link TableLockManager} for the transaction duration, and hold the narrow
 *       {@link #constraintLock()} only during the "constraint-check → heap-mutate"
 *       window within each statement.</li>
 * </ul>
 *
 * <h3>MVCC fields</h3>
 * Each {@link Row} carries an {@code xmin} — the txid that inserted it (0 = always
 * visible).  The tombstone map stores {@code xmax} per row — the txid that deleted
 * it (0 = permanently deleted, i.e. committed before tracking began).
 */
public final class HeapTable {

    private final TableDef def;
    private final long     tableOid;

    /** All rows ever inserted (including tombstoned). MVCC xmin embedded in Row. */
    private final List<Row> rows = new CopyOnWriteArrayList<>();

    /**
     * Tombstone map: RowId → xmax.
     * <ul>
     *   <li>xmax = 0 → permanently deleted (pre-tracking or immediate delete)</li>
     *   <li>xmax = N → deleted by txid N (visible as deleted only after N commits)</li>
     * </ul>
     * Absent entry → row is not deleted.
     */
    private final ConcurrentHashMap<RowId, Long> tombstones = new ConcurrentHashMap<>();

    /**
     * Narrow constraint-check lock.  DML operators acquire this only for the
     * "constraint-check → heap-mutate" window within a single statement, preventing
     * unique-key races when multiple transactions hold ROW_EXCLUSIVE simultaneously.
     *
     * <p>This is NOT the table-level lock (which is managed by {@link TableLockManager}).
     * The table-level lock (e.g. ROW_EXCLUSIVE) is held for the entire transaction;
     * this lock is held only for the narrow mutation window within each statement.
     */
    private final ReentrantLock constraintLock = new ReentrantLock();

    public HeapTable(TableDef def) {
        this.def      = def;
        this.tableOid = def.oid();
    }

    public TableDef def()      { return def; }
    public long     tableOid() { return tableOid; }

    /** Returns the narrow constraint-check lock for DML mutation windows. */
    public ReentrantLock constraintLock() { return constraintLock; }

    // -------------------------------------------------------------------------
    // Mutations — called by TransactionManager (which holds the write lock via DML op)

    /**
     * Insert a pre-existing row ({@code xmin = 0}) — for test code and WAL load paths
     * that don't track a txid.  All production DML should use {@link #insert(Object[], long)}.
     */
    public Row insert(Object[] values) {
        return insert(values, 0L);
    }

    /**
     * Insert a row with the given xmin.
     *
     * @param values column values
     * @param xmin   the txid inserting this row (0 = pre-existing committed)
     * @return the inserted Row
     */
    public Row insert(Object[] values, long xmin) {
        int pos  = rows.size();
        RowId id = new RowId(tableOid, pos);
        Row   r  = new Row(id, values, xmin);
        rows.add(r);
        return r;
    }

    /**
     * Insert a row that already carries a RowId and xmin (WAL undo of DELETE).
     * The row is appended at the tail; position-within-list may differ from the
     * original, but RowId identity is preserved.
     */
    public void insertWithId(Row row) {
        rows.add(row);
    }

    /**
     * Tombstone a row (logical delete).
     *
     * @param rowId the row to tombstone
     * @param xmax  the txid performing the delete (0 = immediate / committed delete)
     */
    public void delete(RowId rowId, long xmax) {
        if (rowId.tableOid() != tableOid)
            throw new IllegalArgumentException(
                    "RowId " + rowId + " does not belong to table OID " + tableOid);
        tombstones.put(rowId, xmax);
    }

    /**
     * Tombstone a row with {@code xmax = 0} — permanently deleted, always invisible.
     * Convenience for callers that don't track a txid (e.g. legacy code paths,
     * undoInsert, truncate).
     */
    public void delete(RowId rowId) {
        delete(rowId, 0L);
    }

    /**
     * Update a row with {@code xmin = 0} — for test code paths that don't track a txid.
     */
    public Row update(RowId oldRowId, Object[] newValues) {
        return update(oldRowId, newValues, 0L);
    }

    /**
     * Update a row: tombstone the old RowId, insert new values under the given xmin.
     *
     * @return the new Row
     */
    public Row update(RowId oldRowId, Object[] newValues, long xmin) {
        delete(oldRowId, xmin);   // xmax == xmin == the updating txid
        return insert(newValues, xmin);
    }

    /**
     * Undo a tombstone (used by ROLLBACK when undoing a DELETE).
     * Removes the tombstone entry entirely so the row becomes visible again.
     */
    public void undoTombstone(RowId rowId) {
        tombstones.remove(rowId);
    }

    /**
     * Tombstone a row that was inserted by a transaction being rolled back.
     * Uses {@code xmax = 0} so the row is permanently invisible.
     */
    public void undoInsert(RowId rowId) {
        tombstones.put(rowId, 0L);
    }

    // -------------------------------------------------------------------------
    // Scans

    /**
     * Full scan with READ COMMITTED visibility filtering.
     *
     * <p>Only rows satisfying {@link TxSnapshot#isVisible(long, Long)} are returned.
     * This is the normal path for all query operators (SeqScan, etc.).
     */
    public Iterator<Row> fullScan(TxSnapshot snap) {
        return rows.stream()
                .filter(r -> snap.isVisible(r.xmin(), tombstones.get(r.rowId())))
                .iterator();
    }

    /**
     * Physical scan — returns all non-tombstoned rows regardless of xmin visibility.
     *
     * <p>Used by {@link org.pgjava.engine.HeapSerializer} (which runs at commit time
     * when all rows in the heap are effectively committed) and by WAL undo helpers.
     * Do NOT use in query operators — use {@link #fullScan(TxSnapshot)} instead.
     */
    public Iterator<Row> fullScan() {
        return rows.stream()
                .filter(r -> !tombstones.containsKey(r.rowId()))
                .iterator();
    }

    /**
     * Lookup a row by RowId with visibility filtering.
     *
     * @return the row if it exists and is visible under {@code snap}, else {@code null}
     */
    public Row lookupByRowId(RowId rowId, TxSnapshot snap) {
        Long xmax = tombstones.get(rowId);
        for (Row r : rows) {
            if (r.rowId().equals(rowId)) {
                if (snap.isVisible(r.xmin(), xmax)) return r;
                return null;
            }
        }
        return null;
    }

    /**
     * Lookup a live (non-tombstoned) row by RowId without visibility filtering.
     *
     * @return the row if it exists and is not tombstoned, else {@code null}
     */
    public Row lookupByRowId(RowId rowId) {
        if (tombstones.containsKey(rowId)) return null;
        for (Row r : rows) {
            if (r.rowId().equals(rowId)) return r;
        }
        return null;
    }

    /**
     * Lookup a row by RowId ignoring tombstones — for WAL undo operations that
     * need to find a tombstoned row to restore it.
     *
     * @return the row if it exists in the list (including tombstoned rows), else {@code null}
     */
    Row lookupByRowIdPhysical(RowId rowId) {
        for (Row r : rows) {
            if (r.rowId().equals(rowId)) return r;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Utility

    /** Total number of rows (including tombstoned). */
    public int totalRows() { return rows.size(); }

    /** Number of live rows (not tombstoned, no visibility filter). */
    public long liveRowCount() {
        return rows.stream().filter(r -> !tombstones.containsKey(r.rowId())).count();
    }

    /**
     * Widen all existing rows to include a newly-added column at {@code newColIndex}
     * (0-based).  Rows whose {@code values[]} array is already long enough are left
     * untouched; shorter rows are extended and the new slot is filled with
     * {@code defaultVal} (the column DEFAULT expression value, or null).
     *
     * <p>Called by DDL {@code ADD COLUMN} to match PostgreSQL's behaviour of
     * immediately filling existing rows with the column's DEFAULT value.
     * Must be called while holding the table write lock (DDL is single-threaded
     * in the embedded driver).
     */
    public void widenAllRows(int newColIndex, Object defaultVal) {
        // Rebuild under the write lock.  Concurrent readers on the COWL see either
        // the old snapshot or the new one — both are valid since DDL runs between
        // statements in the embedded driver.
        List<Row> newList = new java.util.ArrayList<>(rows.size());
        for (Row r : rows) {
            if (r.values().length <= newColIndex) {
                Object[] wider = new Object[newColIndex + 1];
                System.arraycopy(r.values(), 0, wider, 0, r.values().length);
                wider[newColIndex] = defaultVal;
                newList.add(new Row(r.rowId(), wider, r.xmin()));
            } else {
                newList.add(r);
            }
        }
        // CopyOnWriteArrayList iterators are snapshot-based, so concurrent readers
        // always see a consistent view. The clear+addAll window is safe because:
        // 1) Iterators created before clear() continue on the old backing array
        // 2) RowId assignment (rows.size()) is under constraintLock, same as this method
        rows.clear();
        rows.addAll(newList);
    }

    /**
     * Truncate: clear all rows and tombstones.
     * Called by DDL TRUNCATE (outside any per-row transaction tracking).
     */
    public void truncate() {
        rows.clear();
        tombstones.clear();
    }

    // -------------------------------------------------------------------------
    // Package-private helpers for index maintenance (RowLockTable)

    /** Returns true if the row is tombstoned (by any txid). */
    public boolean isTombstoned(RowId rowId) {
        return tombstones.containsKey(rowId);
    }

    // -------------------------------------------------------------------------
    // Deep copy

    /**
     * Create an independent deep copy of this heap table.  All rows are copied
     * (via {@link Row#copyValues()}), tombstones are duplicated, and a fresh
     * constraint lock is created.
     */
    public HeapTable deepCopy(TableDef clonedDef) {
        HeapTable copy = new HeapTable(clonedDef);
        for (Row r : rows) {
            copy.rows.add(new Row(r.rowId(), r.copyValues(), r.xmin()));
        }
        copy.tombstones.putAll(tombstones);
        return copy;
    }
}
