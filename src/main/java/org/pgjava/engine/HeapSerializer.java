package org.pgjava.engine;

import org.pgjava.catalog.CatalogManager;
import org.pgjava.catalog.ColumnDef;
import org.pgjava.catalog.IndexDef;
import org.pgjava.catalog.Schema;
import org.pgjava.catalog.TableDef;
import org.pgjava.storage.BTreeIndex;
import org.pgjava.storage.HeapStorage;
import org.pgjava.storage.HeapTable;
import org.pgjava.storage.Row;
import org.pgjava.types.PgOid;
import org.pgjava.wal.WalValueEncoder;

import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.Iterator;

/**
 * Serializes and deserializes heap table data (rows) to/from binary files under
 * {@code <dataDir>/data/<tableOid>.dat}.
 *
 * <p>Format per file:
 * <pre>
 *   [magic: 4]     0x50474854 "PGHT"
 *   [version: 2]   1
 *   [numRows: 8]   number of live rows
 *   for each row:
 *     [numCols: 4]
 *     for each col:
 *       per {@link WalValueEncoder} format: typeOid(4) + isNull(1) + len(4) + bytes
 * </pre>
 *
 * <p>Column type OIDs are taken from the {@link TableDef} (resolved at save time)
 * so values round-trip as their correct Java types, not as strings.
 */
final class HeapSerializer {

    private static final int   MAGIC   = 0x50474854; // "PGHT"
    private static final short VERSION = 1;

    private HeapSerializer() {}

    // =========================================================================
    // Save
    // =========================================================================

    static void saveAll(HeapStorage storage, Path dataDir) throws IOException {
        Path dataPath = dataDir.resolve("data");
        Files.createDirectories(dataPath);

        for (HeapTable ht : storage.allTables()) {
            saveTable(ht, dataPath);
        }
    }

    private static void saveTable(HeapTable ht, Path dataPath) throws IOException {
        TableDef def  = ht.def();
        Path     file = dataPath.resolve(def.oid() + ".dat");

        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(
                        Files.newOutputStream(file,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING)))) {

            dos.writeInt(MAGIC);
            dos.writeShort(VERSION);
            dos.writeLong(ht.liveRowCount());

            Iterator<Row> iter = ht.fullScan();
            while (iter.hasNext()) {
                Row      row    = iter.next();
                Object[] values = row.values();
                dos.writeInt(values.length);
                for (int i = 0; i < values.length; i++) {
                    int typeOid = (i < def.columnCount())
                            ? def.columns().get(i).type().oid()
                            : PgOid.TEXT;
                    WalValueEncoder.encode(dos, typeOid, values[i]);
                }
            }
        }
    }

    // =========================================================================
    // Load
    // =========================================================================

    static void loadAll(HeapStorage storage, CatalogManager catalog, Path dataDir) throws IOException {
        Path dataPath = dataDir.resolve("data");
        if (!Files.exists(dataPath)) return;

        for (Schema schema : catalog.allSchemas().values()) {
            for (TableDef t : schema.tables().values()) {
                HeapTable ht = storage.createTable(t);
                loadTable(ht, dataPath);

                // Rebuild indexes from the loaded rows
                for (IndexDef idxDef : t.indexes()) {
                    BTreeIndex idx = storage.createIndex(idxDef);
                    rebuildIndex(idx, ht, t);
                }
            }
        }
    }

    private static void loadTable(HeapTable ht, Path dataPath) throws IOException {
        Path file = dataPath.resolve(ht.def().oid() + ".dat");
        if (!Files.exists(file)) return;

        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {

            int magic = dis.readInt();
            if (magic != MAGIC)
                throw new IOException("Invalid table data file (bad magic): " + file);
            dis.readShort(); // version — reserved for future migrations

            long numRows = dis.readLong();
            for (long i = 0; i < numRows; i++) {
                int      numCols = dis.readInt();
                Object[] values  = new Object[numCols];
                for (int j = 0; j < numCols; j++) {
                    values[j] = WalValueEncoder.decode(dis);
                }
                ht.insert(values, 0L); // xmin=0: pre-existing, always visible
            }
        }
    }

    // =========================================================================
    // Index rebuild
    // =========================================================================

    private static void rebuildIndex(BTreeIndex idx, HeapTable ht, TableDef t) {
        Iterator<Row> iter = ht.fullScan();
        while (iter.hasNext()) {
            Row      row  = iter.next();
            Object[] keys = extractKeys(idx.def(), t, row.values());
            if (keys != null) {
                try {
                    idx.insert(keys, row.rowId());
                } catch (SQLException e) {
                    // Duplicate key during index rebuild — skip silently.
                    // This should not happen for valid persisted data.
                }
            }
        }
    }

    private static Object[] extractKeys(IndexDef def, TableDef t, Object[] values) {
        var indexCols = def.columns();
        Object[] keys = new Object[indexCols.size()];
        for (int i = 0; i < indexCols.size(); i++) {
            ColumnDef col = t.column(indexCols.get(i).column());
            if (col == null) return null;
            int pos = col.attnum() - 1; // 1-based → 0-based
            keys[i] = (pos >= 0 && pos < values.length) ? values[pos] : null;
        }
        return keys;
    }
}
