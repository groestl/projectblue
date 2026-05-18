package org.pgjava.wal;

import org.pgjava.catalog.ColumnDef;
import org.pgjava.storage.RowId;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/**
 * Writes {@link WalRecord}s to either an in-memory buffer (for
 * {@code jdbc:pgjava:mem:*}) or a file on disk (for
 * {@code jdbc:pgjava:file:*}).
 *
 * <p><b>In-memory mode</b>: records accumulate in an {@link ArrayList}.
 * No fsync.  Discarded on JVM exit.
 *
 * <p><b>Persistent mode</b>: records are appended to a log file and fsynced
 * on COMMIT.  A new segment is started when the current file exceeds 64 MB.
 *
 * <p>Record format (binary, big-endian):
 * <pre>
 * [LSN: 8][txid: 8][record_type: 1][payload_length: 4][payload: N][checksum: 4 CRC32]
 * </pre>
 *
 * <p>Payload encoding depends on record type (see {@link WalValueEncoder}).
 */
public final class WalWriter implements Closeable {

    private static final long MAX_SEGMENT_BYTES = 64L * 1024 * 1024; // 64 MB

    private final boolean persistent;
    private final Path    walDir;

    /** LSN counter — strictly monotonically increasing. */
    private final AtomicLong lsnGen = new AtomicLong(1L);

    // ---- In-memory mode ----
    private final List<WalRecord> buffer = Collections.synchronizedList(new ArrayList<>());

    // ---- Persistent mode ----
    private DataOutputStream fileOut;
    private FileOutputStream fileRaw;  // kept for getFD().sync()
    private long             segmentBytes;
    private int              segmentNum;

    // -------------------------------------------------------------------------
    // Constructors

    /** In-memory mode: WAL records buffered in RAM, never flushed. */
    public WalWriter() {
        this.persistent = false;
        this.walDir     = null;
    }

    /** Persistent mode: WAL records written to {@code walDir/wal-NNNNN.log}. */
    public WalWriter(Path walDir) throws IOException {
        this.persistent = true;
        this.walDir     = walDir;
        Files.createDirectories(walDir);
        openNewSegment();
    }

    // -------------------------------------------------------------------------
    // Write

    /**
     * Write a WAL record and assign it a fresh LSN.  Thread-safe.
     *
     * @return the LSN assigned to this record
     */
    public synchronized long write(WalRecord record) throws IOException {
        if (persistent) {
            writeToFile(record);
        } else {
            buffer.add(record);
        }
        return record.lsn();
    }

    /** Flush (fsync) the current segment.  No-op in in-memory mode. */
    public synchronized void flush() throws IOException {
        if (persistent && fileOut != null) {
            fileOut.flush();
            if (fileRaw != null) fileRaw.getFD().sync();
        }
    }

    // -------------------------------------------------------------------------
    // LSN allocation

    /** Allocate the next LSN.  Must be called before constructing a WalRecord. */
    public long nextLsn() { return lsnGen.getAndIncrement(); }

    // -------------------------------------------------------------------------
    // In-memory buffer access (for TransactionManager undo logic)

    /**
     * Returns an unmodifiable snapshot of the in-memory buffer.
     * Only meaningful in in-memory mode; in persistent mode this is empty.
     */
    public List<WalRecord> buffer() {
        return Collections.unmodifiableList(new ArrayList<>(buffer));
    }

    // -------------------------------------------------------------------------
    // Persistent write

    private void writeToFile(WalRecord record) throws IOException {
        byte[] payload = encodePayload(record);

        // Header
        fileOut.writeLong(record.lsn());
        fileOut.writeLong(record.txid());
        fileOut.writeByte(record.type());
        fileOut.writeInt(payload.length);
        fileOut.write(payload);

        // CRC32 over lsn(8) + txid(8) + type(1) + len(4) + payload
        CRC32 crc = new CRC32();
        crc.update(longBytes(record.lsn()));
        crc.update(longBytes(record.txid()));
        crc.update(record.type());
        crc.update(intBytes(payload.length));
        crc.update(payload);
        fileOut.writeInt((int) crc.getValue());

        segmentBytes += 8 + 8 + 1 + 4 + payload.length + 4;
        if (segmentBytes >= MAX_SEGMENT_BYTES) {
            fileOut.flush();
            fileOut.close();
            openNewSegment();
        }
    }

    private void openNewSegment() throws IOException {
        Path seg = walDir.resolve(String.format("wal-%05d.log", segmentNum++));
        fileRaw  = new FileOutputStream(seg.toFile(), /* append */ true);
        fileOut  = new DataOutputStream(new BufferedOutputStream(fileRaw));
        segmentBytes = 0;
    }

    // -------------------------------------------------------------------------
    // Payload encoding

    private static byte[] encodePayload(WalRecord rec) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(buf);

        switch (rec) {
            case WalRecord.Begin    ignored -> { /* no payload */ }
            case WalRecord.Commit   ignored -> { /* no payload */ }
            case WalRecord.Rollback ignored -> { /* no payload */ }
            case WalRecord.Checkpoint ignored -> { /* no payload */ }

            case WalRecord.Insert ins -> {
                dos.writeLong(ins.tableOid());
                dos.writeLong(ins.rowId().tableOid());
                dos.writeInt(ins.rowId().position());
                dos.writeInt(ins.values().length);
                // For now: encode all values as TEXT (OID=25) — the full type-tagged
                // encoding requires schema lookup at write time. The executor will
                // pass correct OIDs in Phase 8 when it knows the column types.
                for (Object v : ins.values()) {
                    WalValueEncoder.encode(dos, org.pgjava.types.PgOid.TEXT,
                            v == null ? null : v.toString());
                }
            }
            case WalRecord.Update upd -> {
                dos.writeLong(upd.tableOid());
                dos.writeLong(upd.oldRowId().tableOid());
                dos.writeInt(upd.oldRowId().position());
                dos.writeLong(upd.newRowId().tableOid());
                dos.writeInt(upd.newRowId().position());
                dos.writeInt(upd.oldValues().length);
                for (Object v : upd.oldValues()) {
                    WalValueEncoder.encode(dos, org.pgjava.types.PgOid.TEXT,
                            v == null ? null : v.toString());
                }
                dos.writeInt(upd.newValues().length);
                for (Object v : upd.newValues()) {
                    WalValueEncoder.encode(dos, org.pgjava.types.PgOid.TEXT,
                            v == null ? null : v.toString());
                }
            }
            case WalRecord.Delete del -> {
                dos.writeLong(del.tableOid());
                dos.writeLong(del.rowId().tableOid());
                dos.writeInt(del.rowId().position());
                dos.writeInt(del.deletedValues().length);
                for (Object v : del.deletedValues()) {
                    WalValueEncoder.encode(dos, org.pgjava.types.PgOid.TEXT,
                            v == null ? null : v.toString());
                }
            }
            case WalRecord.Ddl ddl -> {
                byte[] sql = ddl.sql().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                dos.writeInt(sql.length);
                dos.write(sql);
            }
            case WalRecord.Savepoint sp -> {
                byte[] name = sp.name().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                dos.writeInt(name.length);
                dos.write(name);
            }
            case WalRecord.RollbackToSavepoint rts -> {
                byte[] name = rts.name().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                dos.writeInt(name.length);
                dos.write(name);
            }
            case WalRecord.ReleaseSavepoint rs -> {
                byte[] name = rs.name().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                dos.writeInt(name.length);
                dos.write(name);
            }
        }

        dos.flush();
        return buf.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Byte helpers

    private static byte[] longBytes(long v) {
        return new byte[]{
                (byte)(v >> 56), (byte)(v >> 48), (byte)(v >> 40), (byte)(v >> 32),
                (byte)(v >> 24), (byte)(v >> 16), (byte)(v >>  8), (byte) v
        };
    }

    private static byte[] intBytes(int v) {
        return new byte[]{(byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte) v};
    }

    // -------------------------------------------------------------------------

    @Override
    public synchronized void close() throws IOException {
        if (persistent && fileOut != null) {
            fileOut.flush();
            fileOut.close();
            fileOut = null;
        }
    }
}
