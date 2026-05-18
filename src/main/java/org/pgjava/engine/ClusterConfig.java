package org.pgjava.engine;

import java.nio.file.Path;

/**
 * Immutable configuration for a {@link org.pgjava.jdbc.PgJavaCluster}.
 *
 * <p>All fields are optional — a cluster with only a name is a valid in-memory,
 * no-server cluster suitable for embedded use.
 *
 * <p>Build via {@link #builder(String)}.
 */
public final class ClusterConfig {

    private final String  name;
    private final int     port;           // 0 = no wire protocol server
    private final Path    dataDirectory;  // null = in-memory (no WAL on disk)
    private final boolean dropOnStop;     // remove from ClusterRegistry on stop()

    private ClusterConfig(Builder b) {
        this.name          = b.name;
        this.port          = b.port;
        this.dataDirectory = b.dataDirectory;
        this.dropOnStop    = b.dropOnStop;
    }

    public String  name()          { return name; }
    public int     port()          { return port; }
    public Path    dataDirectory() { return dataDirectory; }
    public boolean dropOnStop()    { return dropOnStop; }

    public boolean hasServer()      { return port > 0; }
    public boolean isPersistent()   { return dataDirectory != null; }

    public static Builder builder(String name) { return new Builder(name); }

    // -------------------------------------------------------------------------

    public static final class Builder {
        private final String name;
        private int     port          = 0;
        private Path    dataDirectory = null;
        private boolean dropOnStop    = false;

        private Builder(String name) {
            if (name == null || name.isBlank())
                throw new IllegalArgumentException("cluster name must not be blank");
            this.name = name;
        }

        /** Expose a wire protocol server on this port. 0 = no server (default). */
        public Builder port(int port) {
            if (port < 0 || port > 65535)
                throw new IllegalArgumentException("invalid port: " + port);
            this.port = port;
            return this;
        }

        /** Enable WAL persistence under this directory. null = in-memory (default). */
        public Builder dataDirectory(Path path) {
            this.dataDirectory = path;
            return this;
        }

        /**
         * Remove this cluster from {@link org.pgjava.jdbc.ClusterRegistry} when
         * {@code stop()} is called. Useful for ephemeral test clusters.
         */
        public Builder dropOnStop(boolean dropOnStop) {
            this.dropOnStop = dropOnStop;
            return this;
        }

        public ClusterConfig build() { return new ClusterConfig(this); }
    }
}
