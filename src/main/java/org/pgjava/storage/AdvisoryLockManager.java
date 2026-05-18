package org.pgjava.storage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages PostgreSQL-style advisory locks for a database.
 *
 * <p>Advisory locks come in two scopes:
 * <ul>
 *   <li><b>Session-level</b> — persist until explicitly unlocked or session close.</li>
 *   <li><b>Transaction-level</b> — auto-released on COMMIT or ROLLBACK.</li>
 * </ul>
 *
 * <p>And two modes:
 * <ul>
 *   <li><b>Exclusive</b> — conflicts with other exclusive and shared locks on the same key.</li>
 *   <li><b>Shared</b> — conflicts only with exclusive locks on the same key.</li>
 * </ul>
 *
 * <p>Thread-safe via synchronization on the internal map entries.
 */
public final class AdvisoryLockManager {

    /**
     * Per-key lock state.  The key is a single {@code long} (for two-key overloads,
     * the caller composes them: {@code ((long)k1 << 32) | (k2 & 0xFFFFFFFFL)}).
     */
    private final ConcurrentHashMap<Long, List<AdvisoryEntry>> locks = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Lock acquisition

    /**
     * Acquire an exclusive advisory lock (blocking).
     * For the embedded DB, "blocking" means we spin-wait up to 60s.
     *
     * @param key        advisory lock key
     * @param holderId   txId for transaction-level, sessionId for session-level
     * @param sessionLevel true for session-level, false for transaction-level
     * @return true if acquired
     */
    public boolean lock(long key, long holderId, boolean sessionLevel) {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (true) {
            if (tryLockExclusive(key, holderId, sessionLevel)) return true;
            if (System.currentTimeMillis() >= deadline) return false;
            try { Thread.sleep(10); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Acquire a shared advisory lock (blocking).
     */
    public boolean lockShared(long key, long holderId, boolean sessionLevel) {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (true) {
            if (tryLockShared(key, holderId, sessionLevel)) return true;
            if (System.currentTimeMillis() >= deadline) return false;
            try { Thread.sleep(10); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Try to acquire an exclusive advisory lock without blocking.
     *
     * @return true if the lock was granted
     */
    public boolean tryLockExclusive(long key, long holderId, boolean sessionLevel) {
        List<AdvisoryEntry> entries = locks.computeIfAbsent(key, k -> new ArrayList<>());
        synchronized (entries) {
            // Check for conflicts: exclusive conflicts with ALL other holders
            for (AdvisoryEntry e : entries) {
                if (e.holderId != holderId) return false;
            }
            entries.add(new AdvisoryEntry(holderId, true, sessionLevel));
            return true;
        }
    }

    /**
     * Try to acquire a shared advisory lock without blocking.
     *
     * @return true if the lock was granted
     */
    public boolean tryLockShared(long key, long holderId, boolean sessionLevel) {
        List<AdvisoryEntry> entries = locks.computeIfAbsent(key, k -> new ArrayList<>());
        synchronized (entries) {
            // Shared conflicts only with exclusive locks from other holders
            for (AdvisoryEntry e : entries) {
                if (e.exclusive && e.holderId != holderId) return false;
            }
            entries.add(new AdvisoryEntry(holderId, false, sessionLevel));
            return true;
        }
    }

    // -------------------------------------------------------------------------
    // Lock release

    /**
     * Unlock an exclusive session-level advisory lock.
     *
     * @return true if a matching lock was found and released
     */
    public boolean unlock(long key, long holderId) {
        List<AdvisoryEntry> entries = locks.get(key);
        if (entries == null) return false;
        synchronized (entries) {
            Iterator<AdvisoryEntry> it = entries.iterator();
            while (it.hasNext()) {
                AdvisoryEntry e = it.next();
                if (e.holderId == holderId && e.exclusive && e.sessionLevel) {
                    it.remove();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Unlock a shared session-level advisory lock.
     */
    public boolean unlockShared(long key, long holderId) {
        List<AdvisoryEntry> entries = locks.get(key);
        if (entries == null) return false;
        synchronized (entries) {
            Iterator<AdvisoryEntry> it = entries.iterator();
            while (it.hasNext()) {
                AdvisoryEntry e = it.next();
                if (e.holderId == holderId && !e.exclusive && e.sessionLevel) {
                    it.remove();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Release all transaction-level advisory locks held by the given holder.
     * Called on COMMIT and ROLLBACK.
     */
    public void releaseAllXact(long holderId) {
        for (List<AdvisoryEntry> entries : locks.values()) {
            synchronized (entries) {
                entries.removeIf(e -> e.holderId == holderId && !e.sessionLevel);
            }
        }
    }

    /**
     * Release all session-level advisory locks held by the given holder.
     * Called on session close.
     */
    public void releaseAllSession(long holderId) {
        for (List<AdvisoryEntry> entries : locks.values()) {
            synchronized (entries) {
                entries.removeIf(e -> e.holderId == holderId && e.sessionLevel);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Snapshot for pg_locks

    /** Returns a snapshot of all currently held advisory locks. */
    public List<AdvisoryLockInfo> snapshot() {
        List<AdvisoryLockInfo> result = new ArrayList<>();
        for (var entry : locks.entrySet()) {
            long key = entry.getKey();
            List<AdvisoryEntry> entries = entry.getValue();
            synchronized (entries) {
                for (AdvisoryEntry e : entries) {
                    result.add(new AdvisoryLockInfo(key, e.holderId, e.exclusive, e.sessionLevel));
                }
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Internal types

    private record AdvisoryEntry(long holderId, boolean exclusive, boolean sessionLevel) {}

    public record AdvisoryLockInfo(long key, long holderId, boolean exclusive, boolean sessionLevel) {}
}
