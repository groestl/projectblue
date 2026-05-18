package org.pgjava.engine;

import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-global registry of in-memory databases.
 * {@code jdbc:pgjava:mem:foo} always returns the same {@link Database} instance
 * within the same JVM (and class-loader).
 */
public final class DatabaseRegistry {

    private static final ConcurrentHashMap<String, Database> REGISTRY = new ConcurrentHashMap<>();

    private DatabaseRegistry() {}

    /**
     * Return the {@link Database} for the given name, creating it if it does not yet exist.
     */
    public static Database getOrCreate(String dbName) {
        return REGISTRY.computeIfAbsent(dbName, name -> {
            Database db = Database.create(name);
            db.catalog().setAllDbNames(() -> REGISTRY.keySet());
            return db;
        });
    }

    /**
     * Drop the named database — only for testing / cleanup.
     * Any existing sessions against the database become invalid.
     */
    public static void drop(String dbName) {
        REGISTRY.remove(dbName);
    }
}
