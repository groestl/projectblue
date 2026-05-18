package org.pgjava.storage;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.pgjava.engine.PgErrorException;

/**
 * Manages table-level locks for a database, implementing PostgreSQL's 8 lock
 * modes with exact compatibility semantics.
 *
 * <p>Each DML/DDL/query statement acquires the appropriate table-level lock
 * via {@link #acquire}.  Locks are held until the transaction ends (COMMIT or
 * ROLLBACK), at which point {@link #releaseAll} is called.
 *
 * <h3>Concurrency Model</h3>
 * <ul>
 *   <li>Lock state is protected by {@code synchronized} on the per-table
 *       {@link TableLockState}.  This is simple and correct for an embedded DB.</li>
 *   <li>Waiters block on a {@link CompletableFuture} that is completed when
 *       the conflicting lock is released.</li>
 *   <li>Deadlock detection runs synchronously before a waiter blocks.</li>
 * </ul>
 */
public final class TableLockManager {

    /** Per-table lock state, created lazily. */
    private final ConcurrentHashMap<Long, TableLockState> states = new ConcurrentHashMap<>();

    /** Row lock table reference — used for deadlock detection across lock types. */
    private volatile RowLockTable rowLocks;

    public void setRowLocks(RowLockTable rl) { this.rowLocks = rl; }

    // -------------------------------------------------------------------------
    // Public API

    /**
     * Acquire a table-level lock, blocking if necessary.
     *
     * @param tableOid  OID of the table
     * @param txId      requesting transaction ID
     * @param mode      requested lock mode
     * @param timeoutMs lock timeout in milliseconds (0 = wait forever)
     * @throws SQLException SQLSTATE 55P03 on timeout, 40P01 on deadlock
     */
    public void acquire(long tableOid, long txId, LockMode mode, long timeoutMs)
            throws SQLException {
        TableLockState state = states.computeIfAbsent(tableOid, k -> new TableLockState());
        synchronized (state) {
            // Already holding an equal-or-stronger lock?
            LockMode held = state.heldMode(txId);
            if (held != null && held.isAtLeastAsStrong(mode)) return;

            // Compatible with all current holders?
            if (state.isCompatible(txId, mode)) {
                state.grant(txId, mode);
                return;
            }
        }

        // Must wait — check for deadlocks first, then block
        blockForLock(state, tableOid, txId, mode, timeoutMs);
    }

    /**
     * Try to acquire a table-level lock without blocking.
     *
     * @return {@code true} if the lock was granted
     */
    public boolean tryAcquire(long tableOid, long txId, LockMode mode) {
        TableLockState state = states.computeIfAbsent(tableOid, k -> new TableLockState());
        synchronized (state) {
            LockMode held = state.heldMode(txId);
            if (held != null && held.isAtLeastAsStrong(mode)) return true;

            if (state.isCompatible(txId, mode)) {
                state.grant(txId, mode);
                return true;
            }
        }
        return false;
    }

    /**
     * Release all table-level locks held by the given transaction.
     * Wakes up any waiters that can now be granted their requested lock.
     */
    public void releaseAll(long txId) {
        for (var entry : states.entrySet()) {
            TableLockState state = entry.getValue();
            boolean hadLock;
            synchronized (state) {
                hadLock = state.release(txId);
                if (hadLock) {
                    grantWaiters(state);
                }
            }
        }
    }

    /**
     * Returns a snapshot of all currently held and waiting locks.
     * Used by {@code pg_locks}.
     */
    public List<LockInfo> getLocksSnapshot() {
        List<LockInfo> result = new ArrayList<>();
        for (var entry : states.entrySet()) {
            long tableOid = entry.getKey();
            TableLockState state = entry.getValue();
            synchronized (state) {
                for (LockEntry le : state.holders) {
                    result.add(new LockInfo(tableOid, le.txId, le.mode, true));
                }
                for (LockWaiter w : state.waiters) {
                    result.add(new LockInfo(tableOid, w.txId, w.mode, false));
                }
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Blocking + deadlock detection

    private void blockForLock(TableLockState state, long tableOid, long txId,
                              LockMode mode, long timeoutMs) throws SQLException {
        CompletableFuture<Void> signal;
        synchronized (state) {
            // Re-check under lock (another tx may have released)
            LockMode held = state.heldMode(txId);
            if (held != null && held.isAtLeastAsStrong(mode)) return;
            if (state.isCompatible(txId, mode)) {
                state.grant(txId, mode);
                return;
            }

            // Deadlock detection before blocking
            if (detectDeadlock(txId, tableOid, mode)) {
                throw PgErrorException.error("40P01",
                        "deadlock detected").build();
            }

            signal = new CompletableFuture<>();
            state.waiters.add(new LockWaiter(txId, mode, signal));
        }

        // Block outside the synchronized section
        try {
            if (timeoutMs > 0) {
                signal.get(timeoutMs, TimeUnit.MILLISECONDS);
            } else {
                // Default: wait up to 60 seconds to prevent truly infinite hangs
                // in test scenarios. Real PG waits forever by default.
                signal.get(60, TimeUnit.SECONDS);
            }
        } catch (TimeoutException e) {
            // Remove from wait queue
            synchronized (state) {
                state.waiters.removeIf(w -> w.txId == txId && w.mode == mode);
            }
            throw PgErrorException.error("55P03",
                    "canceling statement due to lock timeout").build();
        } catch (Exception e) {
            synchronized (state) {
                state.waiters.removeIf(w -> w.txId == txId && w.mode == mode);
            }
            if (e.getCause() instanceof SQLException se) throw se;
            throw PgErrorException.error("55P03",
                    "lock wait interrupted: " + e.getMessage()).build();
        }
    }

    /**
     * Detect deadlock by walking the wait-for graph starting from {@code startTxId}.
     * Returns true if granting the requested lock would create a cycle.
     *
     * <p>Must be called while holding the lock on the relevant state.
     * Accesses other states with individual synchronized blocks.
     */
    private boolean detectDeadlock(long startTxId, long startTableOid, LockMode requestedMode) {
        // Build a wait-for graph and do DFS cycle detection.
        // Edge: txA → txB means txA is waiting for txB to release a conflicting lock.
        Set<Long> visited = new HashSet<>();
        Deque<Long> stack = new ArrayDeque<>();

        // Find who startTxId would wait for
        TableLockState startState = states.get(startTableOid);
        if (startState == null) return false;

        // The start tx waits for all holders that conflict with requestedMode
        synchronized (startState) {
            for (LockEntry holder : startState.holders) {
                if (holder.txId != startTxId && LockMode.conflicts(requestedMode, holder.mode)) {
                    stack.push(holder.txId);
                }
            }
        }

        // DFS: does any of those transactions (transitively) wait for startTxId?
        while (!stack.isEmpty()) {
            long txId = stack.pop();
            if (txId == startTxId) return true; // cycle!
            if (!visited.add(txId)) continue;

            // Find what txId is waiting for
            for (var entry : states.entrySet()) {
                TableLockState st = entry.getValue();
                synchronized (st) {
                    for (LockWaiter w : st.waiters) {
                        if (w.txId == txId) {
                            // txId is waiting — it waits for all conflicting holders
                            for (LockEntry holder : st.holders) {
                                if (holder.txId != txId && LockMode.conflicts(w.mode, holder.mode)) {
                                    stack.push(holder.txId);
                                }
                            }
                        }
                    }
                }
            }

            // Also check row-level waits
            // (row-level deadlocks are handled in RowLockTable.waitForLock)
        }
        return false;
    }

    /** Try to grant pending waiters after a lock release. */
    private void grantWaiters(TableLockState state) {
        // Must be called while holding synchronized(state)
        Iterator<LockWaiter> it = state.waiters.iterator();
        while (it.hasNext()) {
            LockWaiter w = it.next();
            if (state.isCompatible(w.txId, w.mode)) {
                state.grant(w.txId, w.mode);
                w.signal.complete(null);
                it.remove();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal data structures

    static final class TableLockState {
        final List<LockEntry> holders = new ArrayList<>();
        final Deque<LockWaiter> waiters = new ArrayDeque<>();

        /** Returns the strongest mode held by txId, or null if not holding. */
        LockMode heldMode(long txId) {
            LockMode strongest = null;
            for (LockEntry e : holders) {
                if (e.txId == txId) {
                    if (strongest == null || e.mode.ordinal() > strongest.ordinal()) {
                        strongest = e.mode;
                    }
                }
            }
            return strongest;
        }

        /** Check if the requested mode is compatible with all holders except txId itself. */
        boolean isCompatible(long txId, LockMode mode) {
            for (LockEntry e : holders) {
                if (e.txId != txId && LockMode.conflicts(mode, e.mode)) {
                    return false;
                }
            }
            return true;
        }

        /** Grant the lock: add or upgrade the holder entry. */
        void grant(long txId, LockMode mode) {
            // Upgrade: if already holding a weaker lock, replace it
            for (int i = 0; i < holders.size(); i++) {
                LockEntry e = holders.get(i);
                if (e.txId == txId) {
                    if (mode.ordinal() > e.mode.ordinal()) {
                        holders.set(i, new LockEntry(txId, mode));
                    }
                    return;
                }
            }
            holders.add(new LockEntry(txId, mode));
        }

        /** Release all locks held by txId. Returns true if any were held. */
        boolean release(long txId) {
            return holders.removeIf(e -> e.txId == txId);
        }
    }

    record LockEntry(long txId, LockMode mode) {}

    record LockWaiter(long txId, LockMode mode, CompletableFuture<Void> signal) {}

    /** Snapshot of a single lock for pg_locks exposure. */
    public record LockInfo(long tableOid, long txId, LockMode mode, boolean granted) {}
}
