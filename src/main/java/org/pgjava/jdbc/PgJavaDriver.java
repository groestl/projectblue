package org.pgjava.jdbc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;

/**
 * Embedded JDBC driver for pgjava.
 *
 * <p>URL formats:
 * <ul>
 *   <li>{@code jdbc:pgjava:mem:<dbname>}         — pure in-memory
 *   <li>{@code jdbc:pgjava:mem:<dbname>?wal=true} — in-memory + WAL
 *   <li>{@code jdbc:pgjava:file:<path>}           — WAL-backed persistent
 * </ul>
 *
 * <p>Registered automatically via {@code META-INF/services/java.sql.Driver}.
 * Phase 2 implements the full connection lifecycle. Until then, {@link #connect}
 * throws {@link UnsupportedOperationException}.
 */
public final class PgJavaDriver implements Driver {
    private static final Logger log = LoggerFactory.getLogger(PgJavaDriver.class);

    public static final String URL_PREFIX = "jdbc:pgjava:";
    public static final int MAJOR_VERSION = 15;
    public static final int MINOR_VERSION = 0;

    static {
        try {
            DriverManager.registerDriver(new PgJavaDriver());
            log.debug("PgJavaDriver registered");
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        JdbcUrl parsed = JdbcUrl.parse(url);

        org.pgjava.engine.Database db;

        if (parsed.isClusterScoped()) {
            // jdbc:pgjava://<cluster>/<db>[?schema=...]
            PgJavaCluster cluster = ClusterRegistry.require(parsed.clusterName());
            db = cluster.database(parsed.dbName());
        } else if (parsed.mode() == JdbcUrl.Mode.FILE) {
            // jdbc:pgjava:file:<path> — WAL-backed persistent database
            try {
                db = org.pgjava.engine.Database.openOrCreatePersistent(Paths.get(parsed.dbName()));
            } catch (IOException e) {
                throw new SQLException(
                        "Cannot open persistent database at \"" + parsed.dbName() + "\": "
                                + e.getMessage(), "XX000", e);
            }
        } else {
            // Legacy: jdbc:pgjava:mem:<db> — routes to the JVM-global DatabaseRegistry
            db = org.pgjava.engine.DatabaseRegistry.getOrCreate(parsed.dbName());
        }

        var session = db.openSession();

        // Apply schema override if present (from SchemaHandle.jdbcUrl()).
        // schema has already been validated as a safe identifier in JdbcUrl.parse(),
        // but we set it directly on the session anyway — no SQL string building.
        if (parsed.schema() != null) {
            session.setVariable("search_path", parsed.schema() + ", public");
        }

        String user = info != null ? info.getProperty("user", "pgjava") : "pgjava";
        return new PgJavaConnection(session, url, user);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return MAJOR_VERSION;
    }

    @Override
    public int getMinorVersion() {
        return MINOR_VERSION;
    }

    @Override
    public boolean jdbcCompliant() {
        return false; // not yet
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("pgjava uses SLF4J, not java.util.logging");
    }
}
