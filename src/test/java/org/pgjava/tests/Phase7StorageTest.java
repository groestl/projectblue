package org.pgjava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pgjava.catalog.*;
import org.pgjava.storage.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;
import org.pgjava.wal.*;

import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7: storage engine (RowId, Row, HeapTable, HeapStorage, BTreeIndex,
 * IndexKey, ConstraintChecker) and WAL + TransactionManager (ROLLBACK undo).
 *
 * No real PostgreSQL needed — all tests are unit tests against pgjava internals.
 */
class Phase7StorageTest {

    // Shared type shortcuts
    private static final PgTypeRegistry REG  = PgTypeRegistry.INSTANCE;
    private static final org.pgjava.types.PgType INT4 = REG.byOid(PgOid.INT4);
    private static final org.pgjava.types.PgType TEXT = REG.byOid(PgOid.TEXT);
    private static final org.pgjava.types.PgType BOOL = REG.byOid(PgOid.BOOL);

    // Shared catalog objects
    private TableDef tableDef;
    private HeapTable heapTable;
    private HeapStorage storage;
    private WalWriter wal;
    private TransactionManager txMgr;

    @BeforeEach
    void setUp() {
        // CREATE TABLE employees (id integer NOT NULL, name text, active boolean)
        tableDef = new TableDef(100L, "employees", "public", false);
        tableDef.addColumn(ColumnDef.notNull("id",     1, INT4));
        tableDef.addColumn(ColumnDef.of     ("name",   2, TEXT));
        tableDef.addColumn(ColumnDef.of     ("active", 3, BOOL));

        storage = new HeapStorage();
        heapTable = storage.createTable(tableDef);

        wal   = new WalWriter();   // in-memory mode
        txMgr = new TransactionManager(storage, wal);
    }

    // =========================================================================
    // RowId

    @Test
    void rowIdEquality() {
        RowId a = new RowId(100L, 0);
        RowId b = new RowId(100L, 0);
        RowId c = new RowId(100L, 1);
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    void rowIdToString() {
        assertEquals("(100,5)", new RowId(100L, 5).toString());
    }

    // =========================================================================
    // HeapTable — basic insert / scan / delete / update

    @Test
    void insertAndScanReturnsRow() {
        Object[] vals = {1, "Alice", true};
        Row inserted = heapTable.insert(vals);

        assertNotNull(inserted.rowId());
        assertEquals(100L, inserted.rowId().tableOid());
        assertEquals(0,    inserted.rowId().position());
        assertEquals(1,    inserted.get(0));
        assertEquals("Alice", inserted.get(1));

        Iterator<Row> scan = heapTable.fullScan();
        assertTrue(scan.hasNext());
        Row scanned = scan.next();
        assertEquals(inserted.rowId(), scanned.rowId());
        assertFalse(scan.hasNext());
    }

    @Test
    void deleteRemovesRowFromScan() {
        Row r = heapTable.insert(new Object[]{1, "Alice", true});
        heapTable.delete(r.rowId());

        Iterator<Row> scan = heapTable.fullScan();
        assertFalse(scan.hasNext(), "deleted row should not appear in scan");
        assertEquals(1, heapTable.totalRows(), "totalRows includes tombstoned");
        assertEquals(0, heapTable.liveRowCount());
    }

    @Test
    void updateTombstonesOldInsertNew() {
        Row original = heapTable.insert(new Object[]{1, "Alice", true});
        Row updated  = heapTable.update(original.rowId(), new Object[]{1, "AliceX", false});

        assertNotEquals(original.rowId(), updated.rowId());
        assertEquals("AliceX", updated.get(1));

        List<Row> rows = scan(heapTable);
        assertEquals(1, rows.size());
        assertEquals("AliceX", rows.get(0).get(1));
    }

    @Test
    void lookupByRowId() {
        Row r = heapTable.insert(new Object[]{42, "Bob", null});
        Row found = heapTable.lookupByRowId(r.rowId());
        assertNotNull(found);
        assertEquals(42, found.get(0));
    }

    @Test
    void lookupByRowIdReturnsNullAfterDelete() {
        Row r = heapTable.insert(new Object[]{1, "A", true});
        heapTable.delete(r.rowId());
        assertNull(heapTable.lookupByRowId(r.rowId()));
    }

    @Test
    void truncateClearsAllRows() {
        heapTable.insert(new Object[]{1, "A", true});
        heapTable.insert(new Object[]{2, "B", false});
        heapTable.truncate();
        assertEquals(0, heapTable.totalRows());
        assertEquals(0, heapTable.liveRowCount());
    }

    @Test
    void multipleRowsScannedInOrder() {
        for (int i = 0; i < 5; i++) {
            heapTable.insert(new Object[]{i, "row" + i, true});
        }
        List<Row> rows = scan(heapTable);
        assertEquals(5, rows.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(i, rows.get(i).get(0));
        }
    }

    // =========================================================================
    // IndexKey ordering

    @Test
    void indexKeyNullSortsLast() {
        IndexKey a = new IndexKey(new Object[]{1});
        IndexKey b = new IndexKey(new Object[]{null});
        assertTrue(a.compareTo(b) < 0, "null should sort after non-null (NULLS LAST)");
    }

    @Test
    void indexKeyNumericPromotion() {
        IndexKey i = new IndexKey(new Object[]{1});
        IndexKey l = new IndexKey(new Object[]{1L});
        assertEquals(0, i.compareTo(l), "int and long 1 should be equal in index order");
    }

    @Test
    void indexKeyStringOrder() {
        IndexKey a = new IndexKey(new Object[]{"apple"});
        IndexKey b = new IndexKey(new Object[]{"banana"});
        assertTrue(a.compareTo(b) < 0);
    }

    @Test
    void indexKeyComposite() {
        IndexKey a = new IndexKey(new Object[]{1, "a"});
        IndexKey b = new IndexKey(new Object[]{1, "b"});
        IndexKey c = new IndexKey(new Object[]{2, "a"});
        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(c) < 0);
    }

    // =========================================================================
    // BTreeIndex

    private BTreeIndex makeUniqueIndex(String col) {
        IndexColumn ic = IndexColumn.asc(col);
        IndexDef def = new IndexDef(200L, "idx_employees_id", "public", "employees",
                tableDef.oid(), List.of(ic), true, true, "btree");
        return storage.createIndex(def);
    }

    private BTreeIndex makeNonUniqueIndex(String col) {
        IndexColumn ic = IndexColumn.asc(col);
        IndexDef def = new IndexDef(201L, "idx_employees_active", "public", "employees",
                tableDef.oid(), List.of(ic), false, false, "btree");
        return storage.createIndex(def);
    }

    @Test
    void indexInsertAndLookupExact() throws SQLException {
        BTreeIndex idx = makeUniqueIndex("id");
        RowId rid = new RowId(100L, 0);
        idx.insert(new Object[]{42}, rid);

        List<RowId> found = idx.lookupExact(new Object[]{42});
        assertEquals(1, found.size());
        assertEquals(rid, found.get(0));
    }

    @Test
    void indexLookupMissReturnsEmpty() throws SQLException {
        BTreeIndex idx = makeUniqueIndex("id");
        assertTrue(idx.lookupExact(new Object[]{999}).isEmpty());
    }

    @Test
    void uniqueIndexRejectsDuplicate() throws SQLException {
        // Uniqueness is enforced by ConstraintChecker, not BTreeIndex.insert().
        // BTreeIndex.insert() appends unconditionally to support WAL undo paths.
        BTreeIndex idx = makeUniqueIndex("id");
        RowId r1 = new RowId(100L, 0);

        // Insert the first row (pre-existing, xmin=0 → always visible)
        heapTable.insert(new Object[]{1, "alice", true}, 0L);
        idx.insert(new Object[]{1}, r1);

        // checkUnique with a snapshot that sees the committed row must throw 23505
        TxSnapshot snap = new TxSnapshot(99L, Set.of());
        Object[] dupeVals = new Object[]{1, "bob", false};
        SQLException ex = assertThrows(SQLException.class,
                () -> ConstraintChecker.checkUnique(tableDef, dupeVals,
                        List.of(idx), null, heapTable, snap));
        assertEquals("23505", ex.getSQLState());
    }

    @Test
    void nonUniqueIndexAllowsDuplicate() throws SQLException {
        BTreeIndex idx = makeNonUniqueIndex("active");
        RowId r1 = new RowId(100L, 0);
        RowId r2 = new RowId(100L, 1);
        idx.insert(new Object[]{true}, r1);
        idx.insert(new Object[]{true}, r2); // must not throw

        List<RowId> found = idx.lookupExact(new Object[]{true});
        assertEquals(2, found.size());
    }

    @Test
    void indexDeleteRemovesEntry() throws SQLException {
        BTreeIndex idx = makeUniqueIndex("id");
        RowId rid = new RowId(100L, 0);
        idx.insert(new Object[]{5}, rid);
        idx.delete(new Object[]{5}, rid);

        assertTrue(idx.lookupExact(new Object[]{5}).isEmpty());
    }

    @Test
    void indexRangeLookup() throws SQLException {
        BTreeIndex idx = makeNonUniqueIndex("id");
        for (int i = 1; i <= 10; i++) {
            idx.insert(new Object[]{i}, new RowId(100L, i - 1));
        }

        // Range [3, 6]
        List<RowId> range = new ArrayList<>();
        idx.lookupRange(new Object[]{3}, true, new Object[]{6}, true)
                .forEachRemaining(range::add);
        assertEquals(4, range.size()); // 3, 4, 5, 6
    }

    @Test
    void indexContainsKey() throws SQLException {
        BTreeIndex idx = makeUniqueIndex("id");
        assertFalse(idx.containsKey(new Object[]{7}));
        idx.insert(new Object[]{7}, new RowId(100L, 0));
        assertTrue(idx.containsKey(new Object[]{7}));
    }

    // =========================================================================
    // ConstraintChecker

    @Test
    void notNullOkWhenNotNullColHasValue() throws SQLException {
        ConstraintChecker.checkNotNull(tableDef, new Object[]{1, "A", true});
    }

    @Test
    void notNullFailsWhenNotNullColIsNull() {
        SQLException ex = assertThrows(SQLException.class,
                () -> ConstraintChecker.checkNotNull(tableDef, new Object[]{null, "A", true}));
        assertEquals("23502", ex.getSQLState());
        assertTrue(ex.getMessage().contains("id"));
    }

    @Test
    void notNullOkForNullableColumn() throws SQLException {
        // name and active are nullable
        ConstraintChecker.checkNotNull(tableDef, new Object[]{1, null, null});
    }

    @Test
    void uniqueCheckPassesWhenNoDuplicate() throws SQLException {
        BTreeIndex idx = makeUniqueIndex("id");
        ConstraintChecker.checkUnique(tableDef, new Object[]{1, "A", true},
                List.of(idx), null);
    }

    @Test
    void uniqueCheckFailsOnDuplicate() throws SQLException {
        BTreeIndex idx = makeUniqueIndex("id");
        RowId existing = new RowId(100L, 0);
        idx.insert(new Object[]{5}, existing);

        SQLException ex = assertThrows(SQLException.class,
                () -> ConstraintChecker.checkUnique(tableDef, new Object[]{5, "B", false},
                        List.of(idx), null));
        assertEquals("23505", ex.getSQLState());
    }

    @Test
    void uniqueCheckAllowsNullKey() throws SQLException {
        // NULL values are always allowed in unique indexes
        BTreeIndex idx = makeUniqueIndex("id");
        RowId r = new RowId(100L, 0);
        idx.insert(new Object[]{null}, r); // nullable column null in index
        // Should not throw even on second null
        ConstraintChecker.checkUnique(tableDef, new Object[]{null, "A", true},
                List.of(idx), null);
    }

    @Test
    void uniqueCheckExcludesOwnRow() throws SQLException {
        BTreeIndex idx = makeUniqueIndex("id");
        RowId self = new RowId(100L, 0);
        idx.insert(new Object[]{7}, self);

        // Update scenario: same key, but we're the same row — should not fail
        ConstraintChecker.checkUnique(tableDef, new Object[]{7, "Updated", false},
                List.of(idx), self);
    }

    // =========================================================================
    // HeapStorage

    @Test
    void heapStorageDropTableRemovesIndexes() throws SQLException {
        BTreeIndex idx = makeUniqueIndex("id");
        assertEquals(1, storage.indexesForTable(tableDef.oid()).size());

        storage.dropTable(tableDef.oid());
        assertEquals(0, storage.indexesForTable(tableDef.oid()).size());
    }

    @Test
    void heapStorageIndexesForTable() throws SQLException {
        BTreeIndex idx1 = makeUniqueIndex("id");
        // Create second index via storage directly (non-unique on name)
        IndexDef def2 = new IndexDef(202L, "idx_name", "public", "employees",
                tableDef.oid(), List.of(IndexColumn.asc("name")), false, false, "btree");
        BTreeIndex idx2 = storage.createIndex(def2);

        List<BTreeIndex> idxList = storage.indexesForTable(tableDef.oid());
        assertEquals(2, idxList.size());
    }

    // =========================================================================
    // TransactionManager — insert, rollback, commit

    @Test
    void transactionInsertAndCommit() throws Exception {
        Transaction tx = txMgr.begin();
        assertTrue(tx.isActive());

        var extractor = TransactionManager.columnNameExtractor(heapTable);
        Row inserted = txMgr.insert(tx, tableDef.oid(),
                new Object[]{1, "Alice", true}, extractor);
        txMgr.commit(tx);

        assertTrue(tx.isCommitted());
        List<Row> rows = scan(heapTable);
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).get(0));
    }

    @Test
    void transactionInsertRollback() throws Exception {
        Transaction tx = txMgr.begin();
        var extractor = TransactionManager.columnNameExtractor(heapTable);
        txMgr.insert(tx, tableDef.oid(), new Object[]{1, "Alice", true}, extractor);

        // Undo the insert
        txMgr.rollback(tx);

        assertTrue(tx.isAborted());
        // Row should be tombstoned (not visible)
        List<Row> rows = scan(heapTable);
        assertEquals(0, rows.size(), "rolled-back insert should not be visible");
    }

    @Test
    void transactionDeleteRollback() throws Exception {
        // Pre-insert a row outside of a transaction (direct heap insert)
        Row r = heapTable.insert(new Object[]{99, "Bob", false});

        // Add the row to the index so undo can restore it
        BTreeIndex idx = makeUniqueIndex("id");
        idx.insert(new Object[]{99}, r.rowId());

        Transaction tx = txMgr.begin();
        var extractor = TransactionManager.columnNameExtractor(heapTable);
        txMgr.delete(tx, tableDef.oid(), r.rowId(), new Object[]{99, "Bob", false}, extractor);

        // Row is tombstoned before rollback
        assertEquals(0, scan(heapTable).size());

        txMgr.rollback(tx);

        // After rollback, the tombstone is removed — row is visible again
        assertEquals(1, scan(heapTable).size());
    }

    @Test
    void transactionUpdateRollback() throws Exception {
        Row original = heapTable.insert(new Object[]{1, "Alice", true});
        BTreeIndex idx = makeUniqueIndex("id");
        idx.insert(new Object[]{1}, original.rowId());

        Transaction tx = txMgr.begin();
        var extractor = TransactionManager.columnNameExtractor(heapTable);
        Row updated = txMgr.update(tx, tableDef.oid(),
                original.rowId(), new Object[]{1, "Alice", true},
                new Object[]{1, "AliceUpdated", false},
                extractor);

        // Before rollback: updated value visible
        assertEquals("AliceUpdated", scan(heapTable).get(0).get(1));

        txMgr.rollback(tx);

        // After rollback: original value restored
        List<Row> rows = scan(heapTable);
        assertEquals(1, rows.size());
        assertEquals("Alice", rows.get(0).get(1));
    }

    @Test
    void savepointPartialRollback() throws Exception {
        var extractor = TransactionManager.columnNameExtractor(heapTable);

        Transaction tx = txMgr.begin();
        txMgr.insert(tx, tableDef.oid(), new Object[]{1, "A", true}, extractor);

        txMgr.savepoint(tx, "sp1");

        txMgr.insert(tx, tableDef.oid(), new Object[]{2, "B", false}, extractor);

        // Before ROLLBACK TO sp1: 2 rows visible
        assertEquals(2, scan(heapTable).size());

        txMgr.rollbackToSavepoint(tx, "sp1");

        // After ROLLBACK TO sp1: row 2 is gone
        assertEquals(1, scan(heapTable).size());
        assertEquals(1, scan(heapTable).get(0).get(0));

        txMgr.commit(tx);

        // After commit: only row 1 persists
        assertEquals(1, scan(heapTable).size());
    }

    @Test
    void rollbackToSavepointUnknownNameThrows() throws Exception {
        Transaction tx = txMgr.begin();
        SQLException ex = assertThrows(SQLException.class,
                () -> txMgr.rollbackToSavepoint(tx, "nonexistent"));
        assertEquals("3B001", ex.getSQLState());
        txMgr.rollback(tx);
    }

    @Test
    void walRecordsWrittenForInsert() throws Exception {
        Transaction tx = txMgr.begin();
        var extractor = TransactionManager.columnNameExtractor(heapTable);
        txMgr.insert(tx, tableDef.oid(), new Object[]{1, "A", true}, extractor);
        txMgr.commit(tx);

        List<WalRecord> records = wal.buffer();
        // Should have: BEGIN, INSERT, COMMIT
        assertEquals(3, records.size());
        assertInstanceOf(WalRecord.Begin.class,  records.get(0));
        assertInstanceOf(WalRecord.Insert.class, records.get(1));
        assertInstanceOf(WalRecord.Commit.class, records.get(2));
    }

    @Test
    void walLsnIsMonotonicallyIncreasing() throws Exception {
        Transaction tx1 = txMgr.begin();
        Transaction tx2 = txMgr.begin();
        txMgr.commit(tx1);
        txMgr.commit(tx2);

        List<WalRecord> recs = wal.buffer();
        for (int i = 1; i < recs.size(); i++) {
            assertTrue(recs.get(i).lsn() > recs.get(i-1).lsn(),
                    "LSNs must be strictly increasing");
        }
    }

    // =========================================================================
    // WalValueEncoder roundtrip

    @Test
    void walValueEncoderRoundtripInt4() throws Exception {
        roundtrip(PgOid.INT4, 42);
    }

    @Test
    void walValueEncoderRoundtripText() throws Exception {
        roundtrip(PgOid.TEXT, "hello world");
    }

    @Test
    void walValueEncoderRoundtripBool() throws Exception {
        roundtrip(PgOid.BOOL, Boolean.TRUE);
        roundtrip(PgOid.BOOL, Boolean.FALSE);
    }

    @Test
    void walValueEncoderRoundtripNull() throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(buf);
        WalValueEncoder.encode(dos, PgOid.TEXT, null);
        dos.flush();

        java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(buf.toByteArray()));
        Object decoded = WalValueEncoder.decode(dis);
        assertNull(decoded);
    }

    @Test
    void walValueEncoderRoundtripDate() throws Exception {
        roundtrip(PgOid.DATE, java.time.LocalDate.of(2024, 6, 15));
    }

    @Test
    void walValueEncoderRoundtripUuid() throws Exception {
        roundtrip(PgOid.UUID, java.util.UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
    }

    private void roundtrip(int oid, Object value) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(buf);
        WalValueEncoder.encode(dos, oid, value);
        dos.flush();

        java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(buf.toByteArray()));
        Object decoded = WalValueEncoder.decode(dis);
        assertEquals(value, decoded, "roundtrip failed for OID " + oid);
    }

    // =========================================================================
    // Helpers

    private List<Row> scan(HeapTable ht) {
        List<Row> rows = new ArrayList<>();
        ht.fullScan().forEachRemaining(rows::add);
        return rows;
    }
}
