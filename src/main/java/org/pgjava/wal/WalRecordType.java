package org.pgjava.wal;

/**
 * 1-byte record type tags for WAL records.
 * Values match the specification in {@code IMPLEMENTATION_STATUS.md}.
 */
public final class WalRecordType {
    private WalRecordType() {}

    public static final byte BEGIN               = 1;
    public static final byte COMMIT              = 2;
    public static final byte ROLLBACK            = 3;
    public static final byte INSERT              = 4;
    public static final byte UPDATE              = 5;
    public static final byte DELETE              = 6;
    public static final byte DDL                 = 7;
    public static final byte CHECKPOINT          = 8;
    public static final byte SAVEPOINT           = 9;
    public static final byte ROLLBACK_TO_SAVEPOINT = 10;
    public static final byte RELEASE_SAVEPOINT     = 11;
}
