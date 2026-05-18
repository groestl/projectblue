package org.pgjava.jdbc;

import org.pgjava.engine.Database;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * Non-pooling {@link DataSource} backed by a pgjava {@link Database}.
 *
 * <p>Each {@link #getConnection()} call opens a new {@link org.pgjava.engine.Session}.
 * This is cheap (object allocation only — no network) and correct. Wrap in HikariCP
 * or another pool if connection reuse is needed.
 *
 * <p>If a {@code searchPath} is provided, every connection will have
 * {@code SET search_path = <schema>[, public]} applied before being returned.
 * This is the mechanism used by {@link SchemaHandle} for schema-isolated connections.
 */
public final class PgJavaDataSource implements DataSource {

    private final Database db;
    private final String   url;
    private final String   searchPath;  // null = no override

    private PrintWriter logWriter = null;

    /** Public factory: plain datasource backed by the given in-memory database. */
    public static PgJavaDataSource forDatabase(Database db) {
        return new PgJavaDataSource(db, "jdbc:pgjava:mem:" + db.name());
    }

    /** Plain datasource — no search_path override. */
    PgJavaDataSource(Database db, String url) {
        this(db, url, null);
    }

    /** Schema-isolated datasource — injects SET search_path on every connection. */
    PgJavaDataSource(Database db, String url, String searchPath) {
        this.db         = db;
        this.url        = url;
        this.searchPath = searchPath;
    }

    @Override
    public Connection getConnection() throws SQLException {
        var session = db.openSession();
        if (searchPath != null) {
            // Set directly on the session — no SQL string building, no injection risk.
            // searchPath is always set by SchemaHandle using a generated safe identifier.
            session.setVariable("search_path", searchPath);
        }
        return new PgJavaConnection(session, url);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    // -------------------------------------------------------------------------
    // DataSource boilerplate

    @Override public PrintWriter getLogWriter()                         { return logWriter; }
    @Override public void        setLogWriter(PrintWriter out)          { this.logWriter = out; }
    @Override public void        setLoginTimeout(int seconds)           {}
    @Override public int         getLoginTimeout()                      { return 0; }
    @Override public Logger      getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("pgjava uses SLF4J");
    }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface);
    }
    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}
