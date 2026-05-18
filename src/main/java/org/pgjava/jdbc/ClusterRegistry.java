package org.pgjava.jdbc;

import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-global registry of named {@link PgJavaCluster} instances.
 *
 * <p>Clusters register themselves on {@link PgJavaCluster#start()} and
 * optionally deregister on {@link PgJavaCluster#stop()} (when
 * {@link org.pgjava.engine.ClusterConfig#dropOnStop()} is true).
 *
 * <p>{@link PgJavaDriver} resolves {@code jdbc:pgjava://<cluster>/<db>} URLs
 * by looking up the cluster name here.
 */
public final class ClusterRegistry {

    private static final ConcurrentHashMap<String, PgJavaCluster> CLUSTERS =
            new ConcurrentHashMap<>();

    private ClusterRegistry() {}

    /**
     * Register a cluster. Called by {@link PgJavaCluster#start()}.
     *
     * @throws IllegalStateException if a different cluster is already registered under the same name
     */
    static void register(PgJavaCluster cluster) {
        PgJavaCluster existing = CLUSTERS.putIfAbsent(cluster.name(), cluster);
        if (existing != null && existing != cluster)
            throw new IllegalStateException(
                    "a different cluster is already registered under name \""
                    + cluster.name() + "\"");
    }

    /** Deregister a cluster by name. Called by {@link PgJavaCluster#stop()} when dropOnStop=true. */
    static void deregister(String name) {
        CLUSTERS.remove(name);
    }

    /**
     * Look up a running cluster by name.
     *
     * @return the cluster, or {@code null} if not registered
     */
    public static PgJavaCluster get(String name) {
        return CLUSTERS.get(name);
    }

    /**
     * Look up a cluster, throwing if not found.
     *
     * @throws java.sql.SQLException SQLSTATE 3D000 if the cluster is not registered
     */
    public static PgJavaCluster require(String name) throws java.sql.SQLException {
        PgJavaCluster c = CLUSTERS.get(name);
        if (c == null)
            throw new java.sql.SQLException(
                    "no pgjava cluster registered under name \"" + name
                    + "\" — call PgJavaCluster.create(...).start() before connecting",
                    "3D000");
        return c;
    }
}
