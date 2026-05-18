package org.pgjava.storage;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Database-wide row-level lock table for {@code SELECT … FOR UPDATE / FOR SHARE}.
 *
 * <p>Each entry maps a {@link RowId} to the {@code txid} that currently holds
 * a lock on that row.  Only one transaction can hold a lock on a given row at a time.
 *
 * <p>Thread-safe via {@link ConcurrentHashMap}.  For the embedded single-user use
 * case the primary value is enabling {@code SKIP LOCKED} so that multiple
 * connections can each claim distinct rows from a work-queue table.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link #tryLock} — called by {@code ForUpdateOperator} for each row that
 *       passes the WHERE filter.  Returns {@code true} if the lock was granted.</li>
 *   <li>{@link #releaseAll} — called by {@link org.pgjava.wal.TransactionManager}
 *       on every COMMIT and ROLLBACK so locks are never left dangling.</li>
 * </ol>
 */
public final class RowLockTable {

    /** RowId → owning txid.  Only rows currently locked appear here. */
    private final ConcurrentHashMap<RowId, Long> locks = new ConcurrentHashMap<>();

    /**
     * Attempt to acquire a row lock for the given transaction.
     *
     * @param rowId  the row to lock
     * @param txId   the requesting transaction
     * @return {@code true} if the lock was granted (either because the row was
     *         unlocked, or because this transaction already owns the lock);
     *         {@code false} if a different transaction holds the lock
     */
    public boolean tryLock(RowId rowId, long txId) {
        Long existing = locks.putIfAbsent(rowId, txId);
        if (existing == null) return true;          // no prior lock — granted
        return existing == txId;                    // same tx re-locks — granted
    }

    /**
     * Returns {@code true} if {@code rowId} is currently locked by a transaction
     * other than {@code txId}.
     */
    public boolean isLockedByOther(RowId rowId, long txId) {
        Long owner = locks.get(rowId);
        return owner != null && owner != txId;
    }

    /**
     * Release all row locks held by the given transaction.
     * Called by {@link org.pgjava.wal.TransactionManager} on COMMIT and ROLLBACK.
     */
    public void releaseAll(long txId) {
        locks.entrySet().removeIf(e -> e.getValue() == txId);
    }

    /**
     * Wait for a row lock, polling with a short interval.
     *
     * @param rowId     the row to lock
     * @param txId      the requesting transaction
     * @param timeoutMs lock timeout in milliseconds (0 = default 60s for embedded DB)
     * @return {@code true} if the lock was granted
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public boolean waitForLock(RowId rowId, long txId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + (timeoutMs > 0 ? timeoutMs : 60_000L);
        while (true) {
            if (tryLock(rowId, txId)) return true;
            if (System.currentTimeMillis() >= deadline) return false;
            Thread.sleep(10); // 10ms poll interval — acceptable for embedded test DB
        }
    }

    /**
     * Returns the txid holding the lock on the given row, or {@code null} if unlocked.
     * Used for deadlock detection.
     */
    public Long lockOwner(RowId rowId) {
        return locks.get(rowId);
    }

    /** Returns a snapshot of all currently held locks. Used by pg_locks. */
    public Map<RowId, Long> snapshot() {
        return Map.copyOf(locks);
    }

    /** Returns the number of currently locked rows — useful for diagnostics. */
    public int size() { return locks.size(); }
}
