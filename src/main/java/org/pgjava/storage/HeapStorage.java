package org.pgjava.storage;

import org.pgjava.catalog.IndexDef;
import org.pgjava.catalog.TableDef;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-database storage registry: owns all {@link HeapTable}s and
 * {@link BTreeIndex}es for the lifetime of the database.
 *
 * <p>Tables and indexes are registered when DDL executes (CREATE TABLE /
 * CREATE INDEX) and de-registered on DROP.  The DDL executor calls these
 * methods; the DML executor calls the heap and index directly.
 */
public final class HeapStorage {

    /** HeapTable keyed by table OID. */
    private final Map<Long, HeapTable>  tables  = new ConcurrentHashMap<>();

    /** BTreeIndex keyed by index OID. */
    private final Map<Long, BTreeIndex> indexes = new ConcurrentHashMap<>();

    /** Indexes grouped by table OID for fast lookup during DML. */
    private final Map<Long, List<BTreeIndex>> indexesByTable = new ConcurrentHashMap<>();

    /** Database-wide row lock table for SELECT … FOR UPDATE / FOR SHARE. */
    private final RowLockTable rowLocks = new RowLockTable();

    /** Database-wide table-level lock manager (8 PostgreSQL lock modes). */
    private final TableLockManager tableLocks = new TableLockManager();

    {
        // Wire up cross-reference for deadlock detection across lock types
        tableLocks.setRowLocks(rowLocks);
    }

    public RowLockTable rowLocks() { return rowLocks; }
    public TableLockManager tableLocks() { return tableLocks; }

    // -------------------------------------------------------------------------
    // Table lifecycle

    /**
     * Register a new heap table.  Called by DDL when CREATE TABLE completes.
     */
    public HeapTable createTable(TableDef def) {
        HeapTable ht = new HeapTable(def);
        tables.put(def.oid(), ht);
        indexesByTable.put(def.oid(), new ArrayList<>());
        return ht;
    }

    /**
     * Unregister and return the heap table.  Called by DDL on DROP TABLE.
     * All associated indexes are also removed.
     */
    public HeapTable dropTable(long tableOid) {
        HeapTable ht = tables.remove(tableOid);
        List<BTreeIndex> idxList = indexesByTable.remove(tableOid);
        if (idxList != null) {
            for (BTreeIndex idx : idxList) indexes.remove(idx.def().oid());
        }
        return ht;
    }

    /** Lookup a heap table by OID.  Returns null if not registered. */
    public HeapTable table(long tableOid) {
        return tables.get(tableOid);
    }

    /** All heap tables. */
    public Collection<HeapTable> allTables() {
        return Collections.unmodifiableCollection(tables.values());
    }

    // -------------------------------------------------------------------------
    // Index lifecycle

    /**
     * Register a new B-tree index.  Called by DDL when CREATE INDEX completes.
     */
    public BTreeIndex createIndex(IndexDef def) {
        return createIndex(def, null);
    }

    public BTreeIndex createIndex(IndexDef def, org.pgjava.types.PgCollation collation) {
        BTreeIndex idx = new BTreeIndex(def, collation);
        indexes.put(def.oid(), idx);
        indexesByTable.computeIfAbsent(def.tableOid(), k -> new ArrayList<>()).add(idx);
        return idx;
    }

    /**
     * Unregister and return the index.  Called by DDL on DROP INDEX.
     */
    public BTreeIndex dropIndex(long indexOid) {
        BTreeIndex idx = indexes.remove(indexOid);
        if (idx != null) {
            List<BTreeIndex> list = indexesByTable.get(idx.def().tableOid());
            if (list != null) list.remove(idx);
        }
        return idx;
    }

    /** Lookup an index by OID.  Returns null if not registered. */
    public BTreeIndex index(long indexOid) {
        return indexes.get(indexOid);
    }

    /**
     * Returns all indexes defined on the given table, in definition order.
     * Returns an empty list if no indexes or table not registered.
     */
    public List<BTreeIndex> indexesForTable(long tableOid) {
        List<BTreeIndex> list = indexesByTable.get(tableOid);
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    /** All indexes. */
    public Collection<BTreeIndex> allIndexes() {
        return Collections.unmodifiableCollection(indexes.values());
    }

    // -------------------------------------------------------------------------
    // Deep copy

    /**
     * Create an independent deep copy of this storage.  All heap tables and
     * indexes are cloned; lock managers are created fresh (no active sessions).
     *
     * @param tableDefsByOid a map from table OID → cloned TableDef, used to wire
     *                       the deep-copied HeapTable to the correct (cloned) TableDef
     */
    public HeapStorage deepCopy(Map<Long, org.pgjava.catalog.TableDef> tableDefsByOid) {
        HeapStorage copy = new HeapStorage();
        // Deep copy tables
        for (var entry : tables.entrySet()) {
            long oid = entry.getKey();
            HeapTable ht = entry.getValue();
            org.pgjava.catalog.TableDef clonedDef = tableDefsByOid.get(oid);
            if (clonedDef == null) clonedDef = ht.def(); // fallback for system tables
            copy.tables.put(oid, ht.deepCopy(clonedDef));
        }
        // Deep copy indexes
        for (var entry : indexes.entrySet()) {
            copy.indexes.put(entry.getKey(), entry.getValue().deepCopy());
        }
        // Rebuild indexesByTable
        for (var entry : copy.indexes.entrySet()) {
            BTreeIndex idx = entry.getValue();
            copy.indexesByTable.computeIfAbsent(idx.def().tableOid(), k -> new ArrayList<>()).add(idx);
        }
        return copy;
    }
}
