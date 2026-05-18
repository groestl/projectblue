package org.pgjava.executor;

import org.pgjava.engine.PgErrorException;
import org.pgjava.sql.ast.LockWaitPolicy;
import org.pgjava.storage.RowId;
import org.pgjava.storage.RowLockTable;
import org.pgjava.storage.Row;

import java.sql.SQLException;

/**
 * Implements {@code SELECT … FOR UPDATE / FOR SHARE / FOR NO KEY UPDATE / FOR KEY SHARE}.
 *
 * <p>Wraps an existing operator tree.  As each candidate row flows up from the
 * source, this operator tries to acquire a row-level lock via {@link RowLockTable}:
 *
 * <ul>
 *   <li>{@link LockWaitPolicy#BLOCK} — wait semantics.  In the single-user
 *       embedded engine there is no true wait mechanism; if the row is already
 *       locked by another transaction this behaves like NOWAIT and throws
 *       {@code 55P03}.  (Deadlock avoidance is out of scope for an embedded DB.)
 *   <li>{@link LockWaitPolicy#SKIP} ({@code SKIP LOCKED}) — silently skip rows
 *       that cannot be immediately locked; return only lockable rows.
 *   <li>{@link LockWaitPolicy#ERROR} ({@code NOWAIT}) — fail immediately with
 *       SQLSTATE {@code 55P03} if any candidate row is already locked.
 * </ul>
 *
 * <p>Locks are held until the enclosing transaction COMMITs or ROLLBACKs, at
 * which point {@link RowLockTable#releaseAll} is called by
 * {@link org.pgjava.wal.TransactionManager}.
 */
public final class ForUpdateOperator extends Operator {

    private final Operator      source;
    private final RowLockTable  lockTable;
    private final LockWaitPolicy waitPolicy;
    private final long          txId;
    private final long          lockTimeoutMs;

    /**
     * @param source     inner operator (full WHERE-filtered, projected result)
     * @param lockTable  the database-wide row lock table
     * @param waitPolicy BLOCK / SKIP / ERROR
     * @param txId       transaction ID that will own the acquired locks
     */
    public ForUpdateOperator(Operator source, RowLockTable lockTable,
                             LockWaitPolicy waitPolicy, long txId) {
        this(source, lockTable, waitPolicy, txId, 0);
    }

    public ForUpdateOperator(Operator source, RowLockTable lockTable,
                             LockWaitPolicy waitPolicy, long txId, long lockTimeoutMs) {
        super(source.schema());
        this.source        = source;
        this.lockTable     = lockTable;
        this.waitPolicy    = waitPolicy;
        this.txId          = txId;
        this.lockTimeoutMs = lockTimeoutMs;
    }

    @Override
    public void open() throws SQLException {
        source.open();
    }

    @Override
    public Row next() throws SQLException {
        while (true) {
            Row row = source.next();
            if (row == null) return null;

            RowId rowId = row.rowId();
            if (rowId == null) {
                // Synthetic rows (e.g. from VALUES, aggregates) have no RowId — pass through.
                return row;
            }

            boolean granted = lockTable.tryLock(rowId, txId);
            if (granted) return row;

            // Row locked by another transaction — apply wait policy
            switch (waitPolicy) {
                case SKIP -> { /* skip this row and try the next */ }
                case ERROR -> throw PgErrorException.error("55P03",
                        "could not obtain lock on row in relation").build();
                case BLOCK -> {
                    // Real blocking: wait for the lock to be released
                    try {
                        boolean acquired = lockTable.waitForLock(rowId, txId, lockTimeoutMs);
                        if (acquired) return row;
                        throw PgErrorException.error("55P03",
                                "canceling statement due to lock timeout").build();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw PgErrorException.error("57014",
                                "canceling statement due to user request").build();
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        source.close();
    }
}
