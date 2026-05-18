package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.jdbc.PgJavaDriver;

import java.sql.*;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2: Embedded JDBC driver unit tests.
 * These tests exercise the driver layer directly — no DualExecutor, no real PostgreSQL.
 */
class Phase2JdbcTest {

    private static final String URL = "jdbc:pgjava:mem:phase2test";

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, new Properties());
    }

    // -------------------------------------------------------------------------
    // Driver registration

    @Test
    void driverManagerAcceptsUrl() throws SQLException {
        Driver d = DriverManager.getDriver(URL);
        assertNotNull(d);
        assertInstanceOf(PgJavaDriver.class, d);
    }

    @Test
    void connectionNotNull() throws SQLException {
        try (Connection conn = connect()) {
            assertNotNull(conn);
            assertFalse(conn.isClosed());
        }
    }

    // -------------------------------------------------------------------------
    // URL parsing

    @Test
    void sameDbNameReturnsSameDatabase() throws SQLException {
        try (Connection a = connect(); Connection b = connect()) {
            // Both connections are against the same in-memory DB
            assertEquals(a.getCatalog(), b.getCatalog());
        }
    }

    // -------------------------------------------------------------------------
    // SET / SHOW

    @Test
    void setAndShow() throws SQLException {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO myschema");
            ResultSet rs = stmt.executeQuery("SHOW search_path");
            assertTrue(rs.next());
            assertEquals("myschema", rs.getString(1));
            rs.close();
        }
    }

    @Test
    void setSearchPathQuotedIdentifier() throws SQLException {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO \"test_schema\"");
            ResultSet rs = stmt.executeQuery("SHOW search_path");
            assertTrue(rs.next());
            assertEquals("test_schema", rs.getString(1));
            rs.close();
        }
    }

    // -------------------------------------------------------------------------
    // Schema create/drop (no-ops)

    @Test
    void createSchemaNoop() throws SQLException {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            // Should not throw
            stmt.execute("CREATE SCHEMA IF NOT EXISTS test_abc");
        }
    }

    @Test
    void dropSchemaNoop() throws SQLException {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS test_abc");
        }
    }

    // -------------------------------------------------------------------------
    // Transaction control (no-ops)

    @Test
    void beginCommitNoop() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.commit();
        }
    }

    @Test
    void rollbackNoop() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.rollback();
        }
    }

    @Test
    void savepointNoop() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            Savepoint sp = conn.setSavepoint("sp1");
            assertNotNull(sp);
            conn.rollback(sp);
            conn.releaseSavepoint(sp);
            conn.commit();
        }
    }

    // -------------------------------------------------------------------------
    // PreparedStatement

    @Test
    void preparedStatementCreates() throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SET search_path TO ?")) {
            assertNotNull(ps);
            ps.setString(1, "public");
            ps.execute();
        }
    }

    @Test
    void parameterMetaData() throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT ?, ?")) {
            ParameterMetaData pmd = ps.getParameterMetaData();
            assertEquals(2, pmd.getParameterCount());
        }
    }

    // -------------------------------------------------------------------------
    // DatabaseMetaData

    @Test
    void databaseMetaDataNotNull() throws SQLException {
        try (Connection conn = connect()) {
            DatabaseMetaData meta = conn.getMetaData();
            assertNotNull(meta);
        }
    }

    @Test
    void databaseProductName() throws SQLException {
        try (Connection conn = connect()) {
            assertEquals("PostgreSQL", conn.getMetaData().getDatabaseProductName());
        }
    }

    @Test
    void getTablesReturnsEmptyResultSet() throws SQLException {
        try (Connection conn = connect()) {
            ResultSet rs = conn.getMetaData().getTables(null, null, "%", null);
            assertNotNull(rs);
            // Phase 2: no tables yet, but columns must be present
            ResultSetMetaData rsMeta = rs.getMetaData();
            assertEquals(10, rsMeta.getColumnCount());
            assertEquals("TABLE_CAT",   rsMeta.getColumnLabel(1));
            assertEquals("TABLE_SCHEM", rsMeta.getColumnLabel(2));
            assertEquals("TABLE_NAME",  rsMeta.getColumnLabel(3));
            assertEquals("TABLE_TYPE",  rsMeta.getColumnLabel(4));
            assertFalse(rs.next());
            rs.close();
        }
    }

    @Test
    void getColumnsReturnsEmptyResultSet() throws SQLException {
        try (Connection conn = connect()) {
            ResultSet rs = conn.getMetaData().getColumns(null, null, "%", "%");
            assertNotNull(rs);
            ResultSetMetaData rsMeta = rs.getMetaData();
            // JDBC spec: getColumns returns 24 columns
            assertEquals(24, rsMeta.getColumnCount());
            assertEquals("TABLE_CAT",   rsMeta.getColumnLabel(1));
            assertEquals("COLUMN_NAME", rsMeta.getColumnLabel(4));
            assertFalse(rs.next());
            rs.close();
        }
    }

    @Test
    void getPrimaryKeysReturnsEmptyResultSet() throws SQLException {
        try (Connection conn = connect()) {
            ResultSet rs = conn.getMetaData().getPrimaryKeys(null, null, "t");
            assertNotNull(rs);
            ResultSetMetaData rsMeta = rs.getMetaData();
            assertEquals(6, rsMeta.getColumnCount());
            assertFalse(rs.next());
            rs.close();
        }
    }

    @Test
    void getImportedKeysReturnsEmptyResultSet() throws SQLException {
        try (Connection conn = connect()) {
            ResultSet rs = conn.getMetaData().getImportedKeys(null, null, "t");
            assertNotNull(rs);
            assertFalse(rs.next());
            rs.close();
        }
    }

    // -------------------------------------------------------------------------
    // isValid (HikariCP heartbeat)

    @Test
    void isValidReturnsTrueWhenOpen() throws SQLException {
        try (Connection conn = connect()) {
            assertTrue(conn.isValid(1));
        }
    }

    @Test
    void isValidReturnsFalseWhenClosed() throws SQLException {
        Connection conn = connect();
        conn.close();
        assertFalse(conn.isValid(1));
    }

    // -------------------------------------------------------------------------
    // ResultSetMetaData on SHOW result

    @Test
    void showResultHasMetaData() throws SQLException {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SHOW search_path");
            ResultSetMetaData meta = rs.getMetaData();
            assertEquals(1, meta.getColumnCount());
            assertNotNull(meta.getColumnName(1));
            rs.close();
        }
    }

    // -------------------------------------------------------------------------
    // Phase 9: SELECT now executes successfully

    @Test
    void selectExecutes() throws SQLException {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void createTableExecutes() throws SQLException {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            // Phase 6: DDL is now implemented — CREATE TABLE should succeed
            assertDoesNotThrow(() -> stmt.execute("CREATE TABLE foo_phase2 (id INT)"));
        }
    }

    // -------------------------------------------------------------------------
    // DatabaseRegistry isolation

    @Test
    void differentDbNamesAreDifferentDatabases() throws SQLException {
        DatabaseRegistry.drop("db_a_phase2");
        DatabaseRegistry.drop("db_b_phase2");
        try (Connection a = DriverManager.getConnection("jdbc:pgjava:mem:db_a_phase2");
             Connection b = DriverManager.getConnection("jdbc:pgjava:mem:db_b_phase2")) {
            assertNotEquals(a.getCatalog(), b.getCatalog());
        } finally {
            DatabaseRegistry.drop("db_a_phase2");
            DatabaseRegistry.drop("db_b_phase2");
        }
    }
}
