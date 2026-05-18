package org.pgjava.jdbc;

import org.pgjava.engine.ClusterConfig;
import org.pgjava.engine.Database;
import org.pgjava.server.PgServer;

import java.sql.SQLException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A named, lifecycle-managed pgjava instance.
 *
 * <p>A cluster owns one or more {@link Database}s and optionally exposes a wire
 * protocol server (when {@link ClusterConfig#port()} is non-zero — not yet
 * implemented; see Phase D).
 *
 * <p>Create via {@link #create(ClusterConfig)} and call {@link #start()} before
 * making any connections. Call {@link #stop()} when done — typically as a Spring
 * {@code destroyMethod} or in a JVM shutdown hook.
 *
 * <p>Multiple clusters may coexist in the same JVM. Each has its own isolated
 * database namespace. JDBC URLs of the form {@code jdbc:pgjava://<name>/<db>}
 * route to the cluster registered under {@code <name>}.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * PgJavaCluster cluster = PgJavaCluster.create(
 *     ClusterConfig.builder("test")
 *         .dropOnStop(true)
 *         .build()
 * ).start();
 *
 * SchemaHandle schema = cluster.allocateSchema("mydb");
 * DataSource   ds     = schema.dataSource();
 * // ... run tests ...
 * schema.drop();
 * cluster.stop();
 * }</pre>
 */
public final class PgJavaCluster {

    private final ClusterConfig config;
    private final ConcurrentHashMap<String, Database> databases = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile PgServer server = null;

    private PgJavaCluster(ClusterConfig config) {
        this.config = config;
    }

    /** Create a new (not yet started) cluster with the given configuration. */
    public static PgJavaCluster create(ClusterConfig config) {
        return new PgJavaCluster(config);
    }

    // -------------------------------------------------------------------------
    // Lifecycle

    /**
     * Start the cluster. Synchronous — returns only when the cluster is fully ready.
     *
     * <p>If the config specifies a wire protocol server, the port will be bound
     * before this method returns (not yet implemented — Phase D).
     *
     * @return {@code this} for chaining
     * @throws IllegalStateException if already started or previously stopped
     */
    public PgJavaCluster start() {
        if (stopped.get())
            throw new IllegalStateException(
                    "cluster \"" + config.name() + "\" has been stopped and cannot be restarted");
        if (!running.compareAndSet(false, true))
            return this; // idempotent if called twice

        ClusterRegistry.register(this);

        if (config.hasServer()) {
            server = new PgServer(config.port(), this);
            server.start();
        }
        return this;
    }

    /**
     * Stop the cluster. Drains in-flight connections, flushes WAL (when persistent),
     * optionally deregisters from {@link ClusterRegistry}.
     *
     * <p>Safe to call multiple times — subsequent calls are no-ops.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        stopped.set(true);

        if (server != null) {
            server.stop();
            server = null;
        }

        // Phase C: flush WAL for all databases here

        if (config.dropOnStop()) {
            ClusterRegistry.deregister(config.name());
        }
    }

    // -------------------------------------------------------------------------
    // Database access

    /**
     * Return the database with the given name, creating it if it does not yet exist.
     *
     * <p>Uses a plain in-memory {@link Database} for now. Phase C will honour
     * {@link ClusterConfig#dataDirectory()} for WAL-backed persistence.
     */
    public Database database(String name) {
        checkRunning();
        return databases.computeIfAbsent(name.toLowerCase(), n -> {
            Database db = Database.create(n);
            db.catalog().setAllDbNames(() -> databases.keySet());
            return db;
        });
    }

    /**
     * Drop the named database. Any existing sessions become invalid.
     *
     * @throws IllegalArgumentException if the database does not exist
     */
    public void dropDatabase(String name) {
        checkRunning();
        if (databases.remove(name.toLowerCase()) == null)
            throw new IllegalArgumentException(
                    "database \"" + name + "\" does not exist in cluster \"" + config.name() + "\"");
    }

    // -------------------------------------------------------------------------
    // Database cloning

    /**
     * Clone an existing database under a new name.  The clone is a fully
     * independent, in-memory deep copy — mutations in either database do not
     * affect the other.
     *
     * <p>Typical usage for test isolation:
     * <pre>{@code
     * cluster.database("template");
     * // ... run migrations against "template" ...
     * Database test1 = cluster.cloneDatabase("template", "test1");
     * Database test2 = cluster.cloneDatabase("template", "test2");
     * }</pre>
     *
     * @param sourceName the name of the database to clone
     * @param targetName the name for the new database
     * @return the cloned database
     * @throws IllegalArgumentException if source does not exist or target already exists
     * @throws IllegalStateException if the cluster is not running
     */
    public Database cloneDatabase(String sourceName, String targetName) {
        checkRunning();
        String srcKey = sourceName.toLowerCase();
        String tgtKey = targetName.toLowerCase();

        Database source = databases.get(srcKey);
        if (source == null)
            throw new IllegalArgumentException(
                    "source database \"" + sourceName + "\" does not exist in cluster \"" + config.name() + "\"");

        // Atomically check target doesn't exist and insert the clone
        Database[] result = {null};
        databases.compute(tgtKey, (key, existing) -> {
            if (existing != null)
                throw new IllegalArgumentException(
                        "target database \"" + targetName + "\" already exists in cluster \"" + config.name() + "\"");
            Database clone = source.clone(tgtKey);
            clone.catalog().setAllDbNames(() -> databases.keySet());
            result[0] = clone;
            return clone;
        });
        return result[0];
    }

    // -------------------------------------------------------------------------
    // Schema isolation

    /**
     * Allocate an isolated schema within the named database.
     *
     * <p>The schema name is generated as {@code test_<8-hex-chars>}. The returned
     * {@link SchemaHandle} provides a schema-scoped {@link javax.sql.DataSource}
     * and a {@link SchemaHandle#drop()} method for cleanup.
     *
     * <p>Intended for massively parallel integration tests — each test class
     * allocates its own schema, runs in isolation, then drops it in {@code @AfterAll}.
     *
     * @param dbName the database to create the schema in
     */
    public SchemaHandle allocateSchema(String dbName) {
        checkRunning();
        Database db         = database(dbName);
        String   schemaName = generateSchemaName();
        try {
            db.catalog().createSchema(schemaName, false);
        } catch (SQLException e) {
            // Extremely unlikely UUID collision — regenerate once
            schemaName = generateSchemaName();
            try {
                db.catalog().createSchema(schemaName, false);
            } catch (SQLException ex) {
                throw new RuntimeException("failed to allocate schema", ex);
            }
        }
        return new SchemaHandle(this, db, dbName.toLowerCase(), schemaName);
    }

    // -------------------------------------------------------------------------
    // Introspection

    public String        name()      { return config.name(); }
    public ClusterConfig config()    { return config; }
    public boolean       isRunning() { return running.get(); }

    // -------------------------------------------------------------------------

    private static String generateSchemaName() {
        byte[] bytes = new byte[4];
        new java.util.Random().nextBytes(bytes);
        return "test_" + HexFormat.of().formatHex(bytes);
    }

    private void checkRunning() {
        if (!running.get())
            throw new IllegalStateException(
                    "cluster \"" + config.name() + "\" is not running — call start() first");
    }
}
