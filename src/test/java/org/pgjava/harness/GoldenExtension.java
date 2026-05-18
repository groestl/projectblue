package org.pgjava.harness;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * JUnit 5 extension that provides a {@link DualExecutor} to test methods.
 *
 * <p><b>PostgreSQL backend selection:</b>
 * <ol>
 *   <li>Tries embedded-postgres (auto-managed, no setup needed — Linux/macOS/Windows).
 *   <li>Falls back to an external PostgreSQL instance when embedded-postgres binaries are
 *       unavailable (e.g. Android/Termux). Configure via system property:
 *       {@code -Dpgjava.test.pgUrl=jdbc:postgresql://localhost:5432/postgres}
 *       (defaults to that value). The database must already exist and be accessible without
 *       a password (trust auth or {@code ~/.pgpass}).
 * </ol>
 *
 * <p><b>Lifecycle per test class:</b>
 * <ul>
 *   <li>{@code @BeforeAll}: acquire PostgreSQL data source (embedded or external URL string).
 *       Stored in the root store as a {@link PgDataSource} {@link CloseableResource}, so JUnit
 *       automatically shuts down embedded-postgres when the test suite finishes.
 *   <li>{@code @BeforeEach}: create isolated schema with random name; set search_path.
 *   <li>Test method: {@link DualExecutor} injected as parameter.
 *   <li>{@code @AfterEach}: {@code DROP SCHEMA … CASCADE}, close connections.
 * </ul>
 *
 * <p><b>Registration:</b> Must be registered at the <em>class</em> level via
 * {@code @ExtendWith(GoldenExtension.class)}, not on individual methods, because
 * {@link BeforeAllCallback} is a container-lifecycle callback that only fires when the extension
 * is attached to the test class container.
 */
public class GoldenExtension
        implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final Logger log = LoggerFactory.getLogger(GoldenExtension.class);

    /** System property to override the external PostgreSQL JDBC URL. */
    public static final String PG_URL_PROPERTY = "pgjava.test.pgUrl";
    public static final String PG_URL_DEFAULT  = "jdbc:postgresql://localhost:5432/postgres";

    private static final String DATASOURCE_KEY = "pgDataSource";
    private static final String PG_CONN_KEY    = "pgConnection";
    private static final String PJ_CONN_KEY    = "pgjavaConnection";
    private static final String SCHEMA_KEY     = "testSchema";

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(GoldenExtension.class);

    // -------------------------------------------------------------------------
    // CloseableResource wrapper — JUnit closes this automatically at suite end

    /**
     * Wraps either an {@link EmbeddedPostgres} or an external URL string.
     * Stored in the root store so JUnit calls {@link #close()} after all tests finish,
     * which stops the embedded-postgres process (or is a no-op for external PG).
     */
    private record PgDataSource(Object source) implements CloseableResource {

        /** Open a connection. {@code source} is either a {@link DataSource} or a URL string. */
        Connection openConnection() throws SQLException {
            if (source instanceof DataSource ds) return ds.getConnection();
            return DriverManager.getConnection((String) source);
        }

        @Override
        public void close() throws Throwable {
            if (source instanceof EmbeddedPostgres pg) pg.close();
            // External URL — nothing to close
        }
    }

    // -------------------------------------------------------------------------
    // BeforeAll — acquire PostgreSQL data source once per suite (thread-safe)

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        // getOrComputeIfAbsent is atomic — safe even under parallel test class execution
        context.getRoot().getStore(NS).getOrComputeIfAbsent(
                DATASOURCE_KEY, _k -> buildDataSource(), PgDataSource.class);
    }

    private static PgDataSource buildDataSource() {
        // 1. Try embedded-postgres
        try {
            EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
            log.info("Using embedded-postgres for golden standard");
            return new PgDataSource(pg.getPostgresDatabase());
        } catch (Exception e) {
            log.info("embedded-postgres unavailable ({}), trying external PostgreSQL", e.getMessage());
        }

        // 2. Fall back to external PostgreSQL
        String url = System.getProperty(PG_URL_PROPERTY, PG_URL_DEFAULT);
        try {
            // Verify the connection works before committing to it
            DriverManager.getConnection(url).close();
            log.info("Using external PostgreSQL at {}", url);
            return new PgDataSource(url);
        } catch (SQLException e) {
            throw new IllegalStateException(
                "Cannot connect to PostgreSQL for golden standard tests.\n" +
                "  Tried embedded-postgres (unavailable on this platform).\n" +
                "  Tried external PostgreSQL at: " + url + "\n" +
                "  Start PostgreSQL and ensure it accepts connections, or set -D" +
                PG_URL_PROPERTY + "=<url>\n" +
                "  Error: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // BeforeEach — create isolated schema, open connections

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        var store = context.getStore(NS);
        String schema = "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        store.put(SCHEMA_KEY, schema);

        PgDataSource pds = context.getRoot().getStore(NS)
                .get(DATASOURCE_KEY, PgDataSource.class);

        Connection pgConn = pds.openConnection();
        setSearchPath(pgConn, schema, true);
        store.put(PG_CONN_KEY, pgConn);

        Connection pgjavaConn = tryConnectPgjava(schema);
        store.put(PJ_CONN_KEY, pgjavaConn);
    }

    // -------------------------------------------------------------------------
    // AfterEach — drop schema, close connections

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        var store = context.getStore(NS);
        String schema = (String) store.get(SCHEMA_KEY);

        Connection pgConn = (Connection) store.get(PG_CONN_KEY);
        if (pgConn != null && !pgConn.isClosed()) {
            try { dropSchema(pgConn, schema); } finally { pgConn.close(); }
        }

        Connection pgjavaConn = (Connection) store.get(PJ_CONN_KEY);
        if (pgjavaConn != null && !pgjavaConn.isClosed()) {
            try { dropSchema(pgjavaConn, schema); } catch (Exception ignored) {}
            finally { try { pgjavaConn.close(); } catch (Exception ignored) {} }
        }
    }

    // -------------------------------------------------------------------------
    // ParameterResolver — inject DualExecutor

    @Override
    public boolean supportsParameter(ParameterContext paramCtx, ExtensionContext extCtx) {
        return paramCtx.getParameter().getType().equals(DualExecutor.class);
    }

    @Override
    public Object resolveParameter(ParameterContext paramCtx, ExtensionContext extCtx) {
        var store = extCtx.getStore(NS);
        return new DualExecutor(
                (Connection) store.get(PG_CONN_KEY),
                (Connection) store.get(PJ_CONN_KEY));
    }

    // -------------------------------------------------------------------------

    private static void setSearchPath(Connection conn, String schema, boolean create)
            throws SQLException {
        try (Statement st = conn.createStatement()) {
            if (create) st.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
            st.execute("SET search_path TO \"" + schema + "\"");
        }
    }

    private static void dropSchema(Connection conn, String schema) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        }
    }

    private static Connection tryConnectPgjava(String schema) {
        try {
            Connection conn = DriverManager.getConnection("jdbc:pgjava:mem:testdb");
            setSearchPath(conn, schema, true);
            return conn;
        } catch (UnsupportedOperationException e) {
            log.debug("pgjava not yet implemented — postgres-only mode");
            return null;
        } catch (Exception e) {
            log.debug("pgjava unavailable ({}) — postgres-only mode", e.getMessage());
            return null;
        }
    }
}
