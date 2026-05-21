package org.pgjava.storage;

import org.pgjava.catalog.IndexDef;
import org.pgjava.engine.PgErrorException;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * In-memory B-tree index backed by a {@link ConcurrentSkipListMap}.
 *
 * <p>The map key is an {@link IndexKey}; the value is a list of {@link RowId}s
 * that share that key (for non-unique indexes this can be &gt;1; for unique indexes
 * it is always 0 or 1).
 *
 * <p>Thread-safety: the map itself is thread-safe; individual list mutations are
 * synchronised per-key.
 */
public final class BTreeIndex {

    private final IndexDef def;
    private final org.pgjava.types.PgCollation collation;
    private final ConcurrentSkipListMap<IndexKey, List<RowId>> tree;

    public BTreeIndex(IndexDef def) {
        this(def, org.pgjava.types.PgCollation.DEFAULT);
    }

    public BTreeIndex(IndexDef def, org.pgjava.types.PgCollation collation) {
        this.def = def;
        this.collation = collation != null ? collation : org.pgjava.types.PgCollation.DEFAULT;
        this.tree = new ConcurrentSkipListMap<>(IndexKey.comparator(this.collation));
    }

    public IndexDef def() { return def; }
    public String   name() { return def.name(); }
    public boolean  isUnique() { return def.unique(); }

    // -------------------------------------------------------------------------
    // Mutations

    /**
     * Insert a key → RowId mapping.
     *
     * <p>Uniqueness is enforced by {@link org.pgjava.storage.ConstraintChecker#checkUnique}
     * before this method is called (under the table's constraintLock).  This method appends
     * unconditionally so that WAL undo/rollback paths can re-insert entries without spurious
     * 23505 errors from uncommitted or tombstoned rows left in the index by other transactions.
     *
     * @throws SQLException declared for API compatibility; never thrown by this method
     */
    public void insert(Object[] keyValues, RowId rowId) throws SQLException {
        IndexKey key = new IndexKey(keyValues);
        List<RowId> ids = tree.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (ids) {
            ids.add(rowId);
        }
    }

    /**
     * Remove a key → RowId mapping.  No-op if the mapping does not exist.
     */
    public void delete(Object[] keyValues, RowId rowId) {
        IndexKey key = new IndexKey(keyValues);
        List<RowId> ids = tree.get(key);
        if (ids != null) {
            synchronized (ids) {
                ids.remove(rowId);
                if (ids.isEmpty()) tree.remove(key, ids);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lookups

    /**
     * Exact-match lookup.  Returns an unmodifiable snapshot of the RowId list,
     * or an empty list if the key is not present.
     */
    public List<RowId> lookupExact(Object[] keyValues) {
        IndexKey key = new IndexKey(keyValues);
        List<RowId> ids = tree.get(key);
        if (ids == null) return List.of();
        synchronized (ids) {
            return List.copyOf(ids);
        }
    }

    /**
     * Range lookup.  Returns an ordered iterator of RowIds for all keys in the
     * specified range.  Passing {@code null} for a bound means open (unbounded).
     */
    public Iterator<RowId> lookupRange(
            Object[] low,  boolean lowInclusive,
            Object[] high, boolean highInclusive) {

        NavigableMap<IndexKey, List<RowId>> sub;

        if (low == null && high == null) {
            sub = tree;
        } else if (low == null) {
            IndexKey hiKey = new IndexKey(high);
            sub = tree.headMap(hiKey, highInclusive);
        } else if (high == null) {
            IndexKey loKey = new IndexKey(low);
            sub = tree.tailMap(loKey, lowInclusive);
        } else {
            IndexKey loKey = new IndexKey(low);
            IndexKey hiKey = new IndexKey(high);
            sub = tree.subMap(loKey, lowInclusive, hiKey, highInclusive);
        }

        // Flatten all RowId lists in key order
        List<RowId> result = new ArrayList<>();
        for (List<RowId> ids : sub.values()) {
            synchronized (ids) {
                result.addAll(ids);
            }
        }
        return result.iterator();
    }

    /**
     * Returns true if the index contains the given key (any RowId).
     */
    public boolean containsKey(Object[] keyValues) {
        List<RowId> ids = tree.get(new IndexKey(keyValues));
        if (ids == null) return false;
        synchronized (ids) { return !ids.isEmpty(); }
    }

    /** Full scan of all entries in key order — used by index-scan operators. */
    public Iterator<Map.Entry<IndexKey, List<RowId>>> fullScan() {
        return tree.entrySet().iterator();
    }

    /** Number of distinct keys in the index. */
    public int size() { return tree.size(); }

    /** Clear all entries (used by TRUNCATE). */
    public void clear() { tree.clear(); }

    // -------------------------------------------------------------------------
    // Deep copy

    /**
     * Create an independent deep copy of this index.  The new index uses the same
     * {@link IndexDef} and collation (preserving the comparator), and all entries
     * are copied with fresh RowId lists.
     */
    public BTreeIndex deepCopy() {
        BTreeIndex copy = new BTreeIndex(this.def, this.collation);
        for (var entry : tree.entrySet()) {
            List<RowId> ids;
            synchronized (entry.getValue()) {
                ids = new ArrayList<>(entry.getValue());
            }
            copy.tree.put(entry.getKey(), Collections.synchronizedList(ids));
        }
        return copy;
    }
}
