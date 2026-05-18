package org.pgjava.storage;

import java.util.Set;

/**
 * Immutable point-in-time snapshot for READ COMMITTED isolation.
 *
 * <p>A row is visible to a statement if:
 * <ol>
 *   <li>Its insert ({@code xmin}) is visible — committed by another tx, or is our
 *       own uncommitted insert.</li>
 *   <li>Its deletion ({@code xmax}) is NOT visible — i.e., the row has not been
 *       deleted by a committed transaction or by the current transaction.</li>
 * </ol>
 *
 * <p>Sentinel values:
 * <ul>
 *   <li>{@code xmin = 0} — pre-existing row inserted before transaction tracking;
 *       always treated as committed (always visible).</li>
 *   <li>{@code xmax = null} — not in tombstones; row is live (not deleted).</li>
 *   <li>{@code xmax = 0} — permanently deleted (undoInsert or pre-tracking delete);
 *       always invisible.</li>
 *   <li>{@code xmax = N} — deleted by txid N; visible as deleted only after N commits.</li>
 * </ul>
 *
 * <p>The snapshot is taken at statement start.  In READ COMMITTED, each new
 * statement gets a fresh snapshot, so it sees rows committed by other transactions
 * up to that point.  Within a transaction, a statement also sees its own
 * uncommitted inserts ({@code xmin == currentTxid}).
 */
public record TxSnapshot(long currentTxid, Set<Long> committed) {

    /**
     * Returns {@code true} if a row with the given {@code xmin} (insert txid)
     * and {@code xmax} (delete txid, nullable) is visible under this snapshot.
     *
     * @param xmax the value from the tombstones map, or {@code null} if the row
     *             is not in the tombstones map (i.e., not deleted)
     */
    public boolean isVisible(long xmin, Long xmax) {
        return insertVisible(xmin) && !deleteVisible(xmax);
    }

    // --- insert visibility -----------------------------------------------

    /**
     * Is the row's insertion visible?
     * Yes if: pre-existing (xmin=0), committed by another tx, or our own insert.
     */
    private boolean insertVisible(long xmin) {
        return xmin == 0L
            || committed.contains(xmin)
            || xmin == currentTxid;
    }

    // --- delete visibility -----------------------------------------------

    /**
     * Is the row's deletion visible?
     * {@code null} means not in tombstones → not deleted → returns false.
     * {@code 0L} means permanently deleted (undoInsert) → always visible as deleted.
     * {@code N} means deleted by txid N → visible as deleted if N is committed or current tx.
     */
    private boolean deleteVisible(Long xmax) {
        if (xmax == null) return false;   // not deleted
        if (xmax == 0L)   return true;    // permanently deleted
        return committed.contains(xmax)
            || (currentTxid != 0L && xmax == currentTxid);
    }
}
