package org.pgjava.engine;

import org.pgjava.catalog.CatalogManager;
import org.pgjava.storage.HeapStorage;
import org.pgjava.wal.TransactionManager;
import org.pgjava.wal.WalWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a single pgjava database instance.
 *
 * <p>Owns the {@link CatalogManager} (schemas, tables, sequences, etc.),
 * {@link HeapStorage} (heap tables + indexes), {@link WalWriter}, and
 * {@link TransactionManager} (transaction lifecycle + WAL-based undo).
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>In-memory</b>: {@link #create(String)} — data lives only in RAM.
 *   <li><b>Persistent</b>: {@link #openOrCreatePersistent(Path)} — catalog + heap
 *       are serialized to disk on every commit; WAL is written to
 *       {@code <dataDir>/wal/}.
 * </ul>
 */
public final class Database {

    // ── File-backed database registry (keyed by canonical path string) ────────
    private static final ConcurrentHashMap<String, Database> FILE_REGISTRY =
            new ConcurrentHashMap<>();

    // ── Instance fields ───────────────────────────────────────────────────────

    private final String             name;
    private final CatalogManager     catalog;
    private final HeapStorage        storage;
    private final WalWriter          wal;
    private final TransactionManager txManager;
    private final NotificationBus    notifyBus = new NotificationBus();
    private final org.pgjava.storage.AdvisoryLockManager advisoryLocks =
            new org.pgjava.storage.AdvisoryLockManager();
    private final org.pgjava.types.PgCollation   collation;
    private final org.pgjava.types.PgTypeRegistry typeRegistry;

    /** Non-null only for persistent databases. */
    private final Path dataDir;

    // =========================================================================
    // Factories
    // =========================================================================

    /** Creates a new in-memory database. Public factory for use outside the engine package. */
    public static Database create(String name) { return new Database(name); }

    /**
     * Remove a persistent database from the JVM-level file registry, simulating
     * a cold start.  Any existing sessions against the database become invalid.
     * Used only in tests.
     */
    public static void evictPersistent(Path dataDir) {
        FILE_REGISTRY.remove(dataDir.toAbsolutePath().normalize().toString());
    }

    /**
     * Open (or create) a file-backed persistent database at {@code dataDir}.
     *
     * <p>If the directory already contains a {@code catalog.json} snapshot, the
     * catalog and all heap data are recovered from disk.  Otherwise a fresh
     * database is initialised and the data directory is created.
     *
     * <p>Within one JVM, the same {@code dataDir} always returns the same
     * {@code Database} instance.
     *
     * @throws IOException if the data directory cannot be created or the WAL
     *                     writer cannot open its segment file
     */
    public static Database openOrCreatePersistent(Path dataDir) throws IOException {
        String key = dataDir.toAbsolutePath().normalize().toString();
        // computeIfAbsent cannot throw checked exceptions; wrap and unwrap.
        IOException[] caught = {null};
        Database db = FILE_REGISTRY.computeIfAbsent(key, k -> {
            try {
                return new Database(dataDir.getFileName().toString(), dataDir);
            } catch (IOException e) {
                caught[0] = e;
                return null;
            }
        });
        if (caught[0] != null) throw caught[0];
        return db;
    }

    // =========================================================================
    // Constructors
    // =========================================================================

    /** In-memory constructor. */
    Database(String name) {
        this.name         = name;
        this.dataDir      = null;
        this.catalog      = new CatalogManager(name);
        this.storage      = new HeapStorage();
        this.wal          = new WalWriter();                  // in-memory mode
        this.txManager    = new TransactionManager(storage, wal);
        this.collation    = org.pgjava.types.PgCollation.DEFAULT;
        this.typeRegistry = org.pgjava.types.PgTypeRegistry.newDatabase();
        registerPgLocks();
    }

    /** Persistent constructor — recovers from disk if catalog.json exists. */
    private Database(String name, Path dataDir) throws IOException {
        this.name         = name;
        this.dataDir      = dataDir;
        this.catalog      = new CatalogManager(name);
        this.storage      = new HeapStorage();
        this.wal          = new WalWriter(dataDir.resolve("wal"));
        this.txManager    = new TransactionManager(storage, wal);
        this.collation    = org.pgjava.types.PgCollation.DEFAULT;
        this.typeRegistry = org.pgjava.types.PgTypeRegistry.newDatabase();
        registerPgLocks();

        if (CatalogSerializer.exists(dataDir)) {
            CatalogSerializer.load(catalog, this, dataDir);
            HeapSerializer.loadAll(storage, catalog, dataDir);
        }
    }

    /** Register the pg_locks virtual table with a supplier that reads live lock state. */
    private void registerPgLocks() {
        catalog.registerVirtualTable(new org.pgjava.catalog.syscat.PgLocks(() -> {
            var rows = new java.util.ArrayList<Object[]>();

            // Table-level locks
            for (var li : storage.tableLocks().getLocksSnapshot()) {
                rows.add(new Object[]{
                        "relation",   // locktype
                        0L,           // database OID (always 0 for embedded)
                        li.tableOid(),// relation
                        null,         // page
                        null,         // tuple
                        null,         // virtualxid
                        li.txId(),    // transactionid
                        null,         // classid
                        null,         // objid
                        null,         // objsubid
                        null,         // virtualtransaction
                        null,         // pid
                        li.mode().pgName(), // mode
                        li.granted(), // granted
                        false,        // fastpath
                        null          // waitstart
                });
            }

            // Advisory locks
            for (var ai : advisoryLocks.snapshot()) {
                int classid = (int) (ai.key() >>> 32);
                int objid   = (int) (ai.key() & 0xFFFFFFFFL);
                rows.add(new Object[]{
                        "advisory",   // locktype
                        0L,           // database
                        null,         // relation
                        null,         // page
                        null,         // tuple
                        null,         // virtualxid
                        null,         // transactionid
                        (long) classid, // classid
                        (long) objid,   // objid
                        (short) 1,    // objsubid
                        null,         // virtualtransaction
                        null,         // pid
                        ai.exclusive() ? "ExclusiveLock" : "ShareLock", // mode
                        true,         // granted
                        false,        // fastpath
                        null          // waitstart
                });
            }

            return rows;
        }));
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public String             name()         { return name; }
    public CatalogManager     catalog()      { return catalog; }
    public HeapStorage        storage()      { return storage; }
    public WalWriter          wal()          { return wal; }
    public TransactionManager txManager()    { return txManager; }
    public NotificationBus    notifyBus()    { return notifyBus; }
    public org.pgjava.storage.AdvisoryLockManager advisoryLocks() { return advisoryLocks; }
    public org.pgjava.types.PgCollation   collation()    { return collation; }
    public org.pgjava.types.PgTypeRegistry typeRegistry() { return typeRegistry; }

    public boolean isPersistent() { return dataDir != null; }

    // =========================================================================
    // Persistence
    // =========================================================================

    /**
     * Flush the catalog snapshot and all heap data to disk.
     * No-op in in-memory mode.
     *
     * <p>Called by {@link Session} after every successful statement that leaves
     * no open transaction (i.e. DDL and auto-commit DML), and after every
     * explicit COMMIT.
     */
    public synchronized void persist() throws IOException {
        if (dataDir == null) return;
        CatalogSerializer.save(catalog, dataDir);
        HeapSerializer.saveAll(storage, dataDir);
    }

    // =========================================================================
    // Session
    // =========================================================================

    /** Open a new session (connection) against this database. */
    public Session openSession() {
        return new Session(this);
    }

    // =========================================================================
    // Cloning
    // =========================================================================

    /**
     * Create an independent, in-memory deep copy of this database.
     *
     * <p>The clone gets a full copy of the catalog and heap storage, but fresh
     * {@link TransactionManager}, {@link WalWriter}, {@link NotificationBus},
     * and {@link org.pgjava.storage.AdvisoryLockManager}.  Mutations in the
     * clone do not affect this database and vice versa.
     *
     * <p>The clone is always in-memory, even if this database is persistent.
     *
     * @param newName the name for the cloned database
     * @return a new, fully independent Database
     */
    public Database clone(String newName) {
        CatalogManager.DeepCopyResult copyResult = catalog.deepCopy(newName);
        HeapStorage clonedStorage = storage.deepCopy(copyResult.tableDefsByOid());
        return new Database(newName, copyResult.catalog(), clonedStorage);
    }

    /** Cloning constructor: accepts pre-built catalog and storage, creates fresh tx state. */
    private Database(String name, CatalogManager catalog, HeapStorage storage) {
        this.name         = name;
        this.dataDir      = null;
        this.catalog      = catalog;
        this.storage      = storage;
        this.wal          = new WalWriter();
        this.txManager    = new TransactionManager(storage, wal);
        this.collation    = org.pgjava.types.PgCollation.DEFAULT;
        this.typeRegistry = buildTypeRegistryFromCatalog(catalog);
        registerPgLocks();
    }

    /**
     * Build a per-database type registry from the catalog's schema types.
     * Used when cloning a database — the cloned catalog already has all
     * user-defined types in its schemas; we just need to re-register them.
     */
    private static org.pgjava.types.PgTypeRegistry buildTypeRegistryFromCatalog(CatalogManager catalog) {
        org.pgjava.types.PgTypeRegistry reg = org.pgjava.types.PgTypeRegistry.newDatabase();
        for (org.pgjava.catalog.Schema schema : catalog.allSchemas().values()) {
            for (org.pgjava.types.PgType t : schema.types().values()) {
                reg.register(t);
            }
        }
        return reg;
    }
}
