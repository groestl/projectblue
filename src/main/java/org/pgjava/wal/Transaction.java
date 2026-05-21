package org.pgjava.wal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a single database transaction.
 *
 * <p>Keeps an ordered list of {@link WalRecord}s written during the transaction
 * so that {@link TransactionManager#rollback(Transaction)} can replay them in
 * reverse for undo.
 */
public final class Transaction {

    public enum State { ACTIVE, COMMITTED, ABORTED }

    private final long   txid;
    private volatile State state = State.ACTIVE;

    /** Ordered WAL records written in this transaction (in forward order). */
    private final List<WalRecord> records = Collections.synchronizedList(new ArrayList<>());

    /**
     * Catalog undo actions: in-memory schema changes that must be reverted if this
     * transaction rolls back. Stored in forward order; applied in reverse on rollback.
     * Each entry records: (recordsIndexAtRegistration, undoAction).
     */
    private final List<CatalogUndo> catalogUndos = new ArrayList<>();

    private record CatalogUndo(int recordsIndex, Runnable action) {}

    /** Stack of savepoint names → index in records list at savepoint time. */
    private final List<SavepointMark> savepoints = new ArrayList<>();

    public Transaction(long txid) {
        this.txid = txid;
    }

    public long  txid()  { return txid; }
    public State state() { return state; }

    public boolean isActive()    { return state == State.ACTIVE; }
    public boolean isCommitted() { return state == State.COMMITTED; }
    public boolean isAborted()   { return state == State.ABORTED; }

    void setState(State s) { this.state = s; }

    /** Called by TransactionManager after writing a WAL record for this transaction. */
    void addRecord(WalRecord rec) { records.add(rec); }

    /**
     * Register a catalog undo action. Called by DdlExecutor after each catalog mutation.
     * On rollback, all catalog undos registered after the current WAL position are applied
     * in reverse order.
     */
    public void addCatalogUndo(Runnable undo) {
        synchronized (records) {
            catalogUndos.add(new CatalogUndo(records.size(), undo));
        }
    }

    /**
     * Returns catalog undos that were registered after {@code fromRecordIndex}
     * (for partial savepoint rollback), in reverse registration order.
     */
    List<Runnable> catalogUndosAfter(int fromRecordIndex) {
        List<Runnable> result = new ArrayList<>();
        for (int i = catalogUndos.size() - 1; i >= 0; i--) {
            if (catalogUndos.get(i).recordsIndex() >= fromRecordIndex) {
                result.add(catalogUndos.get(i).action());
            }
        }
        return result;
    }

    /** Remove catalog undos registered after {@code fromRecordIndex} (used after rollback). */
    void removeCatalogUndosAfter(int fromRecordIndex) {
        catalogUndos.removeIf(u -> u.recordsIndex() >= fromRecordIndex);
    }

    /** Returns an unmodifiable snapshot of all records in forward order. */
    public List<WalRecord> records() {
        synchronized (records) {
            return Collections.unmodifiableList(new ArrayList<>(records));
        }
    }

    /** Records the current position as a named savepoint. */
    void markSavepoint(String name) {
        // Remove any existing savepoint with the same name (SAVEPOINT is idempotent per name)
        savepoints.removeIf(sp -> sp.name().equals(name));
        savepoints.add(new SavepointMark(name, records.size()));
    }

    /**
     * Returns the index (into the records list) at which the savepoint was created,
     * or -1 if no such savepoint exists.
     */
    int savepointIndex(String name) {
        for (int i = savepoints.size() - 1; i >= 0; i--) {
            if (savepoints.get(i).name().equals(name)) return savepoints.get(i).index();
        }
        return -1;
    }

    /**
     * Remove all savepoints created strictly after {@code index}
     * (used after rollback to savepoint — the target savepoint itself is preserved).
     */
    void trimSavepointsAfter(int index) {
        savepoints.removeIf(sp -> sp.index() > index);
    }

    /**
     * Remove the named savepoint and all savepoints created at or after its index.
     * Used by RELEASE SAVEPOINT which destroys the savepoint.
     */
    void removeSavepointAndAfter(String name) {
        int idx = savepointIndex(name);
        if (idx >= 0) {
            savepoints.removeIf(sp -> sp.index() >= idx);
        }
    }

    /**
     * Update the index of the named savepoint to a new WAL position.
     * Used after ROLLBACK TO SAVEPOINT so the savepoint tracks the new position.
     */
    void updateSavepointIndex(String name, int newIndex) {
        savepoints.removeIf(sp -> sp.name().equals(name));
        savepoints.add(new SavepointMark(name, newIndex));
    }

    /** Remove and return all records added after {@code index} (for partial rollback). */
    List<WalRecord> removeRecordsAfter(int index) {
        synchronized (records) {
            List<WalRecord> tail = new ArrayList<>(records.subList(index, records.size()));
            records.subList(index, records.size()).clear();
            return tail;
        }
    }

    // -------------------------------------------------------------------------

    private record SavepointMark(String name, int index) {}
}
