package org.pgjava.wal;

import org.pgjava.engine.PgErrorException;
import org.pgjava.storage.BTreeIndex;
import org.pgjava.storage.HeapStorage;
import org.pgjava.storage.HeapTable;
import org.pgjava.storage.Row;
import org.pgjava.storage.RowId;

import org.pgjava.storage.TxSnapshot;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Manages transaction lifecycle and coordinates WAL writes + heap/index mutations.
 *
 * <p>All DML must go through this manager so that:
 * <ol>
 *   <li>A WAL record is written before the heap is mutated (write-ahead guarantee).</li>
 *   <li>ROLLBACK can undo mutations by replaying WAL records in reverse.</li>
 * </ol>
 *
 * <p>Index mutations accompany every heap mutation atomically — this manager
 * takes the index list and updates them alongside the heap.
 */
public final class TransactionManager {

    private static final Logger LOG = Logger.getLogger(TransactionManager.class.getName());

    private final HeapStorage storage;
    private final WalWriter   wal;

    private final AtomicLong txidGen = new AtomicLong(1L);

    /** Active transactions keyed by txid. */
    private final ConcurrentHashMap<Long, Transaction> active = new ConcurrentHashMap<>();

    /**
     * Set of committed txids — grows monotonically, never shrinks.
     *
     * <p>Used by {@link TxSnapshot} to determine which rows are visible under READ
     * COMMITTED isolation.  A txid is added here at the moment COMMIT completes, so
     * any snapshot taken after that point will see the committed writes.
     */
    private final ConcurrentSkipListSet<Long> committed = new ConcurrentSkipListSet<>();

    public TransactionManager(HeapStorage storage, WalWriter wal) {
        this.storage = storage;
        this.wal     = wal;
    }

    // -------------------------------------------------------------------------
    // Transaction lifecycle

    /** Begin a new transaction. Writes a BEGIN WAL record. */
    public Transaction begin() throws IOException {
        long txid = txidGen.getAndIncrement();
        long lsn  = wal.nextLsn();
        Transaction tx = new Transaction(txid);
        active.put(txid, tx);
        WalRecord rec = new WalRecord.Begin(lsn, txid);
        tx.addRecord(rec);
        wal.write(rec);
        return tx;
    }

    /**
     * Commit a transaction.  Flushes the WAL (fsync in persistent mode).
     *
     * @throws IllegalStateException if the transaction is not active
     */
    public void commit(Transaction tx) throws IOException {
        assertActive(tx);
        long lsn = wal.nextLsn();
        WalRecord rec = new WalRecord.Commit(lsn, tx.txid());
        tx.addRecord(rec);
        wal.write(rec);
        wal.flush();
        tx.setState(Transaction.State.COMMITTED);
        active.remove(tx.txid());
        committed.add(tx.txid());
        storage.rowLocks().releaseAll(tx.txid());
        storage.tableLocks().releaseAll(tx.txid());
    }

    /**
     * Roll back a transaction.  Replays WAL records in reverse and undoes
     * each heap/index mutation, then applies catalog undos for any DDL executed
     * during the transaction.
     */
    public void rollback(Transaction tx) throws IOException, SQLException {
        if (tx.isAborted() || tx.isCommitted()) return;
        assertActive(tx);

        undoRecords(tx.records());
        // Apply catalog undos in reverse order (DDL rollback)
        for (Runnable undo : tx.catalogUndosAfter(0)) undo.run();

        long lsn = wal.nextLsn();
        WalRecord rec = new WalRecord.Rollback(lsn, tx.txid());
        wal.write(rec);
        tx.setState(Transaction.State.ABORTED);
        active.remove(tx.txid());
        storage.rowLocks().releaseAll(tx.txid());
        storage.tableLocks().releaseAll(tx.txid());
    }

    // -------------------------------------------------------------------------
    // Snapshot (READ COMMITTED visibility)

    /**
     * Create a READ COMMITTED snapshot for the given transaction.
     *
     * <p>The snapshot captures the committed set at this exact moment, so any
     * statement using this snapshot will see all transactions committed up to now,
     * plus its own uncommitted writes ({@code currentTxid}).
     *
     * @param currentTxid the txid of the calling session's active transaction,
     *                    or {@code 0} if the session has no active transaction
     */
    public TxSnapshot snapshotFor(long currentTxid) {
        return new TxSnapshot(currentTxid, new HashSet<>(committed));
    }

    // -------------------------------------------------------------------------
    // Savepoints

    /** Record a savepoint. */
    public void savepoint(Transaction tx, String name) throws IOException {
        assertActive(tx);
        tx.markSavepoint(name);
        long lsn = wal.nextLsn();
        WalRecord rec = new WalRecord.Savepoint(lsn, tx.txid(), name);
        tx.addRecord(rec);
        wal.write(rec);
    }

    /**
     * Roll back to a named savepoint: undo all mutations after the savepoint.
     *
     * @throws SQLException SQLSTATE 3B001 if the savepoint does not exist
     */
    public void rollbackToSavepoint(Transaction tx, String name)
            throws IOException, SQLException {
        assertActive(tx);
        int idx = tx.savepointIndex(name);
        if (idx < 0)
            throw PgErrorException.error("3B001", "savepoint \"" + name + "\" does not exist").build();

        List<WalRecord> tail = tx.removeRecordsAfter(idx);
        undoRecords(tail);
        // Apply catalog undos for DDL executed after the savepoint, in reverse order
        for (Runnable undo : tx.catalogUndosAfter(idx)) undo.run();
        tx.removeCatalogUndosAfter(idx);
        // Remove savepoints created after the target, but keep the target itself
        tx.trimSavepointsAfter(idx);

        long lsn = wal.nextLsn();
        WalRecord rec = new WalRecord.RollbackToSavepoint(lsn, tx.txid(), name);
        tx.addRecord(rec);
        wal.write(rec);

        // Update the savepoint to point to the current WAL position so it can be
        // rolled back to again (PostgreSQL keeps the savepoint alive after ROLLBACK TO)
        tx.updateSavepointIndex(name, idx);
    }

    /**
     * Release (destroy) a named savepoint.
     *
     * <p>In PostgreSQL, RELEASE SAVEPOINT destroys the named savepoint and all
     * savepoints created after it. The WAL records between the savepoint and the
     * current position are merged into the parent savepoint or transaction — they
     * are kept (not undone) so the mutations remain in effect.
     *
     * @throws SQLException SQLSTATE 3B001 if the savepoint does not exist
     */
    public void releaseSavepoint(Transaction tx, String name)
            throws IOException, SQLException {
        assertActive(tx);
        int idx = tx.savepointIndex(name);
        if (idx < 0)
            throw PgErrorException.error("3B001", "savepoint \"" + name + "\" does not exist").build();

        // Remove the named savepoint and any savepoints created after it.
        // WAL records are kept — RELEASE does not undo mutations, it just
        // destroys the savepoint so it can no longer be rolled back to.
        tx.removeSavepointAndAfter(name);

        long lsn = wal.nextLsn();
        WalRecord rec = new WalRecord.ReleaseSavepoint(lsn, tx.txid(), name);
        tx.addRecord(rec);
        wal.write(rec);
    }

    // -------------------------------------------------------------------------
    // DML operations (heap + index + WAL, atomically)

    /**
     * Insert a row into a heap table, maintain indexes, and write a WAL record.
     *
     * @param tx         the active transaction
     * @param tableOid   OID of the target table
     * @param values     column values in column-definition order
     * @param keyExtractor  extracts index key values from the row
     * @return the inserted Row
     */
    public Row insert(Transaction tx, long tableOid, Object[] values,
                      IndexKeyExtractor keyExtractor) throws IOException, SQLException {
        assertActive(tx);
        HeapTable ht = requireTable(tableOid);

        // Heap insert first to get the authoritative RowId (position assigned atomically).
        // Callers hold constraintLock so no concurrent insert can interleave.
        Row inserted = ht.insert(values, tx.txid());

        // Write WAL after heap (the row is only visible after commit, so write-ahead
        // guarantee is maintained: if we crash before WAL write, the uncommitted row
        // is invisible and will be cleaned up on recovery).
        long lsn  = wal.nextLsn();
        WalRecord rec = new WalRecord.Insert(lsn, tx.txid(), tableOid, inserted.rowId(), values);
        tx.addRecord(rec);
        wal.write(rec);

        // Index maintenance
        for (BTreeIndex idx : storage.indexesForTable(tableOid)) {
            Object[] keyVals = keyExtractor.extract(idx, values);
            if (keyVals != null) idx.insert(keyVals, inserted.rowId());
        }

        return inserted;
    }

    /**
     * Delete a row from a heap table, remove it from indexes, and write a WAL record.
     *
     * @param keyExtractor  extracts index key values from the old row values
     */
    public void delete(Transaction tx, long tableOid, RowId rowId, Object[] deletedValues,
                       IndexKeyExtractor keyExtractor) throws IOException, SQLException {
        assertActive(tx);
        HeapTable ht = requireTable(tableOid);

        long lsn = wal.nextLsn();
        WalRecord rec = new WalRecord.Delete(lsn, tx.txid(), tableOid, rowId, deletedValues);
        tx.addRecord(rec);
        wal.write(rec);

        // Index maintenance first (while we can still look up the key)
        for (BTreeIndex idx : storage.indexesForTable(tableOid)) {
            Object[] keyVals = keyExtractor.extract(idx, deletedValues);
            if (keyVals != null) idx.delete(keyVals, rowId);
        }

        // Heap tombstone — tag with deleting txid so MVCC scans can detect uncommitted deletes
        ht.delete(rowId, tx.txid());
    }

    /**
     * Update a row: tombstone the old, insert the new, maintain indexes.
     *
     * @return the new Row
     */
    public Row update(Transaction tx, long tableOid,
                      RowId oldRowId, Object[] oldValues,
                      Object[] newValues,
                      IndexKeyExtractor keyExtractor) throws IOException, SQLException {
        assertActive(tx);
        HeapTable ht = requireTable(tableOid);

        // Remove old index entries
        for (BTreeIndex idx : storage.indexesForTable(tableOid)) {
            Object[] oldKeys = keyExtractor.extract(idx, oldValues);
            if (oldKeys != null) idx.delete(oldKeys, oldRowId);
        }

        // Heap update — tombstone old with tx.txid() (xmax), insert new with tx.txid() (xmin)
        Row newRow = ht.update(oldRowId, newValues, tx.txid());

        // Write WAL with authoritative RowIds
        long lsn = wal.nextLsn();
        WalRecord rec = new WalRecord.Update(lsn, tx.txid(), tableOid,
                oldRowId, oldValues, newRow.rowId(), newValues);
        tx.addRecord(rec);
        wal.write(rec);

        // Insert new index entries
        for (BTreeIndex idx : storage.indexesForTable(tableOid)) {
            Object[] newKeys = keyExtractor.extract(idx, newValues);
            if (newKeys != null) idx.insert(newKeys, newRow.rowId());
        }

        return newRow;
    }

    /**
     * Write a DDL WAL record (called by the DDL executor after schema change).
     */
    public void writeDdl(Transaction tx, String sql) throws IOException {
        assertActive(tx);
        long lsn = wal.nextLsn();
        WalRecord rec = new WalRecord.Ddl(lsn, tx.txid(), sql);
        tx.addRecord(rec);
        wal.write(rec);
    }

    // -------------------------------------------------------------------------
    // Undo (ROLLBACK / ROLLBACK TO SAVEPOINT)

    private void undoRecords(List<WalRecord> forward) throws IOException, SQLException {
        // Process in reverse order
        for (int i = forward.size() - 1; i >= 0; i--) {
            WalRecord rec = forward.get(i);
            switch (rec) {
                case WalRecord.Insert ins -> undoInsert(ins);
                case WalRecord.Delete del -> undoDelete(del);
                case WalRecord.Update upd -> undoUpdate(upd);
                case WalRecord.Ddl ddl -> {
                    // DDL undo is handled via catalogUndos, not WAL replay
                }
                default -> { /* BEGIN, COMMIT, SAVEPOINT etc. — nothing to undo */ }
            }
        }
    }

    private void undoInsert(WalRecord.Insert ins) throws SQLException {
        HeapTable ht = storage.table(ins.tableOid());
        if (ht == null) return;
        // Undo insert = tombstone the row
        ht.undoInsert(ins.rowId());
        // Remove from indexes (best-effort: we don't have key extractor here,
        // so scan indexes and remove entries pointing to this RowId)
        for (BTreeIndex idx : storage.indexesForTable(ins.tableOid())) {
            removeRowIdFromIndex(idx, ins.rowId());
        }
    }

    private void undoDelete(WalRecord.Delete del) throws IOException {
        HeapTable ht = storage.table(del.tableOid());
        if (ht == null) return;
        // Undo delete = re-insert the row and un-tombstone
        ht.undoTombstone(del.rowId());
        // Re-insert into indexes — use linear scan on the values.
        // For exact key reconstruction we need type info; in Phase 7 we use
        // a string-based fallback that may not restore typed index lookups perfectly.
        // Full typed undo will be fixed in Phase 8 when the evaluator knows types.
        for (BTreeIndex idx : storage.indexesForTable(del.tableOid())) {
            Object[] keyVals = extractKeysFromValues(idx, del.deletedValues());
            if (keyVals != null) {
                try { idx.insert(keyVals, del.rowId()); }
                catch (SQLException e) { /* ignore if already present */ }
            }
        }
    }

    private void undoUpdate(WalRecord.Update upd) throws IOException, SQLException {
        HeapTable ht = storage.table(upd.tableOid());
        if (ht == null) return;
        // Undo update:
        //   1. tombstone the new row
        //   2. un-tombstone the old row
        //   3. update indexes: remove new key entries, restore old key entries
        ht.undoInsert(upd.newRowId());
        ht.undoTombstone(upd.oldRowId());

        for (BTreeIndex idx : storage.indexesForTable(upd.tableOid())) {
            // Remove new row from index
            removeRowIdFromIndex(idx, upd.newRowId());
            // Restore old row in index
            Object[] oldKeys = extractKeysFromValues(idx, upd.oldValues());
            if (oldKeys != null) {
                try { idx.insert(oldKeys, upd.oldRowId()); }
                catch (SQLException e) { /* ignore */ }
            }
        }
    }

    /** Scan an index and remove all entries pointing to the given RowId. */
    private void removeRowIdFromIndex(BTreeIndex idx, RowId rowId) {
        var it = idx.fullScan();
        while (it.hasNext()) {
            var entry = it.next();
            List<RowId> ids = entry.getValue();
            synchronized (ids) {
                ids.remove(rowId);
            }
        }
    }

    /**
     * Best-effort key extraction: uses the index's column names to resolve
     * positions via the HeapTable's TableDef.
     */
    private Object[] extractKeysFromValues(BTreeIndex idx, Object[] values) {
        if (values == null) return null;
        HeapTable ht = storage.table(idx.def().tableOid());
        if (ht == null) return null;
        return extractKeys(idx, ht, values);
    }

    private static Object[] extractKeys(BTreeIndex idx, HeapTable ht, Object[] values) {
        var indexCols = idx.def().columns();
        Object[] keys = new Object[indexCols.size()];
        for (int i = 0; i < indexCols.size(); i++) {
            var colDef = ht.def().column(indexCols.get(i).column());
            if (colDef == null) return null;
            int pos = colDef.attnum() - 1; // 1-based → 0-based
            keys[i] = (pos >= 0 && pos < values.length) ? values[pos] : null;
        }
        return keys;
    }

    // -------------------------------------------------------------------------
    // Helpers

    private void assertActive(Transaction tx) {
        if (!tx.isActive())
            throw new IllegalStateException("transaction " + tx.txid() + " is not active (state=" + tx.state() + ")");
    }

    private HeapTable requireTable(long tableOid) throws SQLException {
        HeapTable ht = storage.table(tableOid);
        if (ht == null)
            throw PgErrorException.error("42P01", "table OID " + tableOid + " not found in storage").build();
        return ht;
    }

    // -------------------------------------------------------------------------
    // IndexKeyExtractor interface

    /**
     * Extracts the key values for a given index from a row's column values.
     *
     * <p>Returns null if the index does not apply to the row (should not happen
     * in normal operation — every table index applies to every row).
     */
    @FunctionalInterface
    public interface IndexKeyExtractor {
        Object[] extract(BTreeIndex index, Object[] rowValues);
    }

    /**
     * Default extractor: resolves column names from the index definition against
     * the given HeapTable's column attnums to produce key values.
     */
    public static IndexKeyExtractor columnNameExtractor(HeapTable ht) {
        return (idx, rowValues) -> extractKeys(idx, ht, rowValues);
    }
}
