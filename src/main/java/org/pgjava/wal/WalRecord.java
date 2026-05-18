package org.pgjava.wal;

import org.pgjava.storage.RowId;

import java.util.List;

/**
 * In-memory representation of a WAL record.
 *
 * <p>Sealed hierarchy — one subtype per {@link WalRecordType}.
 * The executor creates these; {@link WalWriter} serialises them to the log.
 * {@link TransactionManager} keeps a per-transaction list for undo on ROLLBACK.
 */
public sealed interface WalRecord
        permits WalRecord.Begin, WalRecord.Commit, WalRecord.Rollback,
                WalRecord.Insert, WalRecord.Update, WalRecord.Delete,
                WalRecord.Ddl, WalRecord.Savepoint, WalRecord.RollbackToSavepoint,
                WalRecord.ReleaseSavepoint, WalRecord.Checkpoint {

    long lsn();
    long txid();
    byte type();

    // -------------------------------------------------------------------------

    record Begin(long lsn, long txid)
            implements WalRecord {
        @Override public byte type() { return WalRecordType.BEGIN; }
    }

    record Commit(long lsn, long txid)
            implements WalRecord {
        @Override public byte type() { return WalRecordType.COMMIT; }
    }

    record Rollback(long lsn, long txid)
            implements WalRecord {
        @Override public byte type() { return WalRecordType.ROLLBACK; }
    }

    /**
     * INSERT: records the new row (for undo: delete this row).
     */
    record Insert(long lsn, long txid, long tableOid, RowId rowId, Object[] values)
            implements WalRecord {
        @Override public byte type() { return WalRecordType.INSERT; }
    }

    /**
     * UPDATE: records both old and new rows (for undo: tombstone new, un-tombstone old).
     */
    record Update(long lsn, long txid,
                  long tableOid,
                  RowId oldRowId, Object[] oldValues,
                  RowId newRowId, Object[] newValues)
            implements WalRecord {
        @Override public byte type() { return WalRecordType.UPDATE; }
    }

    /**
     * DELETE: records the deleted row (for undo: un-tombstone).
     */
    record Delete(long lsn, long txid, long tableOid, RowId rowId, Object[] deletedValues)
            implements WalRecord {
        @Override public byte type() { return WalRecordType.DELETE; }
    }

    /**
     * DDL statement (CREATE TABLE, DROP TABLE, etc.).
     * DDL undo in a ROLLBACK is a known limitation in Phase 7 — a warning is logged.
     */
    record Ddl(long lsn, long txid, String sql)
            implements WalRecord {
        @Override public byte type() { return WalRecordType.DDL; }
    }

    record Savepoint(long lsn, long txid, String name)
            implements WalRecord {
        @Override public byte type() { return WalRecordType.SAVEPOINT; }
    }

    record RollbackToSavepoint(long lsn, long txid, String name)
            implements WalRecord {
        @Override public byte type() { return WalRecordType.ROLLBACK_TO_SAVEPOINT; }
    }

    record ReleaseSavepoint(long lsn, long txid, String name)
            implements WalRecord {
        @Override public byte type() { return WalRecordType.RELEASE_SAVEPOINT; }
    }

    record Checkpoint(long lsn, long txid)
            implements WalRecord {
        @Override public byte type() { return WalRecordType.CHECKPOINT; }
    }
}
