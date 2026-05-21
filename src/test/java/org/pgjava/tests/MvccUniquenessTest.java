package org.pgjava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pgjava.catalog.*;
import org.pgjava.storage.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;
import org.pgjava.wal.*;
import org.pgjava.engine.Database;

import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that unique-index enforcement is MVCC-aware.
 *
 * <p>The bug: {@code BTreeIndex.insert()} checked {@code !ids.isEmpty()} without
 * filtering by snapshot visibility, causing spurious 23505 errors when the only
 * conflicting index entry came from an uncommitted or already-tombstoned row.
 *
 * <p>The fix: {@link ConstraintChecker#checkUnique(TableDef, Object[], List, RowId,
 * HeapTable, TxSnapshot)} skips RowIds whose heap row is not visible under the
 * current snapshot. {@link BTreeIndex#insert} no longer enforces uniqueness itself.
 */
class MvccUniquenessTest {

    private static final PgTypeRegistry REG  = PgTypeRegistry.INSTANCE;
    private static final org.pgjava.types.PgType INT4 = REG.byOid(PgOid.INT4);
    private static final org.pgjava.types.PgType TEXT = REG.byOid(PgOid.TEXT);

    private TableDef tableDef;
    private HeapTable heapTable;
    private HeapStorage storage;
    private WalWriter wal;
    private TransactionManager txMgr;
    private BTreeIndex uniqueIdx;

    @BeforeEach
    void setUp() {
        tableDef = new TableDef(200L, "t", "public", false);
        tableDef.addColumn(ColumnDef.notNull("id",   1, INT4));
        tableDef.addColumn(ColumnDef.of     ("name", 2, TEXT));

        storage  = new HeapStorage();
        heapTable = storage.createTable(tableDef);
        wal      = new WalWriter();
        txMgr    = new TransactionManager(storage, wal);

        // CREATE UNIQUE INDEX ON t(id)
        IndexDef idxDef = new IndexDef(201L, "t_id_key", "public", "t", 200L,
                List.of(IndexColumn.asc("id")), true, false, "btree");
        tableDef.addIndex(idxDef);
        uniqueIdx = storage.createIndex(idxDef);
    }

    // -------------------------------------------------------------------------
    // Unit-level: raw HeapTable + BTreeIndex

    @Test
    void checkUnique_rejectsCommittedDuplicateKey() throws SQLException {
        // Pre-existing committed row with id=1 (xmin=0 → always visible)
        Row row = heapTable.insert(new Object[]{1, "alice"}, 0L);
        uniqueIdx.insert(new Object[]{1}, row.rowId());

        // Snapshot from tx 99 with no committed peers — but xmin=0 rows are always visible
        TxSnapshot snap = new TxSnapshot(99L, Set.of());

        SQLException ex = assertThrows(SQLException.class, () ->
                ConstraintChecker.checkUnique(tableDef, new Object[]{1, "bob"},
                        List.of(uniqueIdx), null, heapTable, snap));
        assertEquals("23505", ex.getSQLState());
    }

    @Test
    void checkUnique_allowsKeyWhenOnlyConflictIsUncommitted() throws SQLException {
        // Row inserted by tx 42 (not committed in snapshot for tx 99)
        Row row = heapTable.insert(new Object[]{1, "alice"}, 42L);
        uniqueIdx.insert(new Object[]{1}, row.rowId());

        // Snapshot from tx 99 — tx 42 not in committed set → alice's row not visible
        TxSnapshot snap = new TxSnapshot(99L, Set.of());

        // Should NOT throw: the only id=1 entry is from an uncommitted transaction
        assertDoesNotThrow(() ->
                ConstraintChecker.checkUnique(tableDef, new Object[]{1, "bob"},
                        List.of(uniqueIdx), null, heapTable, snap));
    }

    @Test
    void checkUnique_allowsKeyAfterConflictingRowTombstoned() throws SQLException {
        // Insert pre-existing committed row, then tombstone it (delete committed)
        Row row = heapTable.insert(new Object[]{1, "alice"}, 0L);
        uniqueIdx.insert(new Object[]{1}, row.rowId());

        // Tombstone by tx 10 (committed in snapshot)
        heapTable.delete(row.rowId(), 10L);

        // Snapshot from tx 99 that sees tx 10 as committed → alice's row is deleted
        TxSnapshot snap = new TxSnapshot(99L, Set.of(10L));

        // Should NOT throw: the conflicting row was deleted (tombstoned and committed)
        assertDoesNotThrow(() ->
                ConstraintChecker.checkUnique(tableDef, new Object[]{1, "bob"},
                        List.of(uniqueIdx), null, heapTable, snap));
    }

    @Test
    void checkUnique_allowsKeyAfterConflictingRowTombstonedByCurrentTx() throws SQLException {
        // Row inserted by some older committed tx
        Row row = heapTable.insert(new Object[]{1, "alice"}, 0L);
        uniqueIdx.insert(new Object[]{1}, row.rowId());

        // Current tx (99) deletes it
        heapTable.delete(row.rowId(), 99L);

        // Snapshot from tx 99 itself
        TxSnapshot snap = new TxSnapshot(99L, Set.of());

        // Should NOT throw: the current tx already deleted the row
        assertDoesNotThrow(() ->
                ConstraintChecker.checkUnique(tableDef, new Object[]{1, "bob"},
                        List.of(uniqueIdx), null, heapTable, snap));
    }

    @Test
    void checkUnique_nullRowsNeverConflict() throws SQLException {
        // NULL key values must not trigger uniqueness (NULLs are distinct in PG)
        Row row = heapTable.insert(new Object[]{null, "alice"}, 0L);
        uniqueIdx.insert(new Object[]{null}, row.rowId());

        TxSnapshot snap = new TxSnapshot(99L, Set.of());

        assertDoesNotThrow(() ->
                ConstraintChecker.checkUnique(tableDef, new Object[]{null, "bob"},
                        List.of(uniqueIdx), null, heapTable, snap));
    }

    // -------------------------------------------------------------------------
    // SQL-level: verify the fix works end-to-end via Session

    @Test
    void sqlLevel_insertDeleteReinsertSameKey() throws SQLException {
        Database db = Database.create("mvcc_test");
        try (var s = db.openSession()) {
            s.execute("CREATE TABLE items (id INT PRIMARY KEY, val TEXT)");
            s.execute("INSERT INTO items VALUES (1, 'first')");
            s.execute("DELETE FROM items WHERE id = 1");
            // Re-insert same PK — must not throw 23505
            assertDoesNotThrow(() -> s.execute("INSERT INTO items VALUES (1, 'second')"));
            var res = s.execute("SELECT val FROM items WHERE id = 1");
            assertEquals("second", res.rows().get(0)[0]);
        }
    }

    @Test
    void sqlLevel_duplicateKeyStillRejected() throws SQLException {
        Database db = Database.create("mvcc_test2");
        try (var s = db.openSession()) {
            s.execute("CREATE TABLE items (id INT PRIMARY KEY, val TEXT)");
            s.execute("INSERT INTO items VALUES (1, 'first')");
            // Second insert with same PK — must throw 23505
            SQLException ex = assertThrows(SQLException.class,
                    () -> s.execute("INSERT INTO items VALUES (1, 'duplicate')"));
            assertEquals("23505", ex.getSQLState());
        }
    }

    @Test
    void sqlLevel_insertWithinTransactionRollbackAllowsReuse() throws SQLException {
        // Insert K=1 in a transaction, rollback, then insert K=1 in a new tx
        Database db = Database.create("mvcc_test3");
        try (var s = db.openSession()) {
            s.execute("CREATE TABLE items (id INT PRIMARY KEY, val TEXT)");

            s.execute("BEGIN");
            s.execute("INSERT INTO items VALUES (1, 'temp')");
            s.execute("ROLLBACK");

            // After rollback, the PK slot must be free again
            assertDoesNotThrow(() -> {
                s.execute("INSERT INTO items VALUES (1, 'permanent')");
            });
            var res = s.execute("SELECT val FROM items WHERE id = 1");
            assertEquals("permanent", res.rows().get(0)[0]);
        }
    }
}
