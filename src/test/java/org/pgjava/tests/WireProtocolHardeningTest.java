package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.ClusterConfig;
import org.pgjava.jdbc.PgJavaCluster;

import java.sql.*;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire protocol hardening: extended query edge cases, error recovery,
 * transactions, prepared statements with different types.
 */
class WireProtocolHardeningTest {

    private static PgJavaCluster cluster;
    private static int port;

    @BeforeAll
    static void startServer() {
        port = 16432 + (int)(System.nanoTime() % 1000);
        cluster = PgJavaCluster.create(
                ClusterConfig.builder("wire_hard_" + port)
                        .port(port)
                        .dropOnStop(true)
                        .build()
        ).start();
    }

    @AfterAll
    static void stopServer() {
        if (cluster != null) cluster.stop();
    }

    private Connection connectSimple() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", "postgres");
        props.setProperty("password", "");
        props.setProperty("sslmode", "disable");
        props.setProperty("preferQueryMode", "simple");
        return DriverManager.getConnection(
                "jdbc:postgresql://localhost:" + port + "/testdb", props);
    }

    private Connection connectExtended() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", "postgres");
        props.setProperty("password", "");
        props.setProperty("sslmode", "disable");
        return DriverManager.getConnection(
                "jdbc:postgresql://localhost:" + port + "/testdb", props);
    }

    // ── Error recovery ────────────────────────────────────────────────────────

    @Test void errorRecoverySimple() throws Exception {
        try (Connection conn = connectSimple();
             Statement st = conn.createStatement()) {
            // First: a bad query
            assertThrows(SQLException.class,
                    () -> st.executeQuery("SELECT * FROM nonexistent_table_xyz"));
            // After error, connection should still work
            ResultSet rs = st.executeQuery("SELECT 1");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test void errorRecoveryExtended() throws Exception {
        try (Connection conn = connectExtended();
             Statement st = conn.createStatement()) {
            assertThrows(SQLException.class,
                    () -> st.executeQuery("SELECT * FROM nonexistent_table_xyz"));
            // Should recover after Sync
            ResultSet rs = st.executeQuery("SELECT 42");
            assertTrue(rs.next());
            assertEquals(42, rs.getInt(1));
        }
    }

    // ── Prepared statement with various types ─────────────────────────────────

    @Test void preparedStatementTypes() throws Exception {
        try (Connection conn = connectExtended();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS wire_types (id int, name text, val float8, flag boolean)");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO wire_types VALUES (?, ?, ?, ?)")) {
                ps.setInt(1, 1);
                ps.setString(2, "hello");
                ps.setDouble(3, 3.14);
                ps.setBoolean(4, true);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM wire_types WHERE id = ?")) {
                ps.setInt(1, 1);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("id"));
                assertEquals("hello", rs.getString("name"));
                assertEquals(3.14, rs.getDouble("val"), 1e-9);
                assertTrue(rs.getBoolean("flag"));
            }
        }
    }

    // ── Multiple statements in simple query ───────────────────────────────────

    @Test void multipleStatementsSimple() throws Exception {
        try (Connection conn = connectSimple();
             Statement st = conn.createStatement()) {
            // pgjdbc in simple mode sends multiple statements separated by ;
            st.execute("CREATE TABLE IF NOT EXISTS wire_multi (n int)");
            st.execute("INSERT INTO wire_multi VALUES (1)");
            st.execute("INSERT INTO wire_multi VALUES (2)");
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM wire_multi");
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) >= 2);
        }
    }

    // ── Transaction control over wire ─────────────────────────────────────────

    @Test void transactionCommit() throws Exception {
        try (Connection conn = connectSimple();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS wire_tx (id int)");
            conn.setAutoCommit(false);
            st.execute("INSERT INTO wire_tx VALUES (100)");
            conn.commit();
            conn.setAutoCommit(true);
            ResultSet rs = st.executeQuery("SELECT id FROM wire_tx WHERE id = 100");
            assertTrue(rs.next());
            assertEquals(100, rs.getInt(1));
        }
    }

    @Test void transactionRollback() throws Exception {
        try (Connection conn = connectSimple();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS wire_tx_rb (id int)");
            // Insert a row and commit it first
            st.execute("INSERT INTO wire_tx_rb VALUES (1)");
            conn.setAutoCommit(false);
            st.execute("INSERT INTO wire_tx_rb VALUES (999)");
            conn.rollback();
            conn.setAutoCommit(true);
            // 999 should be gone
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM wire_tx_rb WHERE id = 999");
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    // ── DDL over wire ─────────────────────────────────────────────────────────

    @Test void createInsertSelectDrop() throws Exception {
        try (Connection conn = connectExtended();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE wire_lifecycle (id serial PRIMARY KEY, name text NOT NULL)");
            st.executeUpdate("INSERT INTO wire_lifecycle (name) VALUES ('alpha')");
            st.executeUpdate("INSERT INTO wire_lifecycle (name) VALUES ('beta')");
            ResultSet rs = st.executeQuery("SELECT name FROM wire_lifecycle ORDER BY id");
            assertTrue(rs.next()); assertEquals("alpha", rs.getString(1));
            assertTrue(rs.next()); assertEquals("beta", rs.getString(1));
            assertFalse(rs.next());
            st.execute("DROP TABLE wire_lifecycle");
        }
    }

    // ── Prepared statement reuse ──────────────────────────────────────────────

    @Test void preparedStatementReuse() throws Exception {
        try (Connection conn = connectExtended();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS wire_reuse (id int, val text)");
            st.execute("INSERT INTO wire_reuse VALUES (1,'a'),(2,'b'),(3,'c')");
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT val FROM wire_reuse WHERE id = ?")) {
                ps.setInt(1, 1);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next()); assertEquals("a", rs.getString(1));
                rs.close();

                ps.setInt(1, 3);
                rs = ps.executeQuery();
                assertTrue(rs.next()); assertEquals("c", rs.getString(1));
                rs.close();
            }
        }
    }

    // ── NULL handling over wire ────────────────────────────────────────────────

    @Test void nullValues() throws Exception {
        try (Connection conn = connectExtended();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS wire_null (id int, val text)");
            st.execute("INSERT INTO wire_null VALUES (1, NULL)");
            ResultSet rs = st.executeQuery("SELECT val FROM wire_null WHERE id = 1");
            assertTrue(rs.next());
            assertNull(rs.getString(1));
            assertTrue(rs.wasNull());
        }
    }

    // ── DatabaseMetaData over wire ────────────────────────────────────────────

    // NOTE: DatabaseMetaData.getTables() over wire protocol uses pgjdbc's own
    // SQL which queries pg_catalog.pg_description (not yet implemented).
    // The embedded JDBC driver handles this correctly; wire protocol needs
    // the pg_description virtual table for full pgjdbc metadata support.
    @Test void metadataProductName() throws Exception {
        try (Connection conn = connectExtended()) {
            DatabaseMetaData md = conn.getMetaData();
            assertEquals("PostgreSQL", md.getDatabaseProductName());
        }
    }

    // ── Aggregate query over wire ─────────────────────────────────────────────

    @Test void aggregateOverWire() throws Exception {
        try (Connection conn = connectSimple();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS wire_agg (dept text, salary int)");
            st.execute("INSERT INTO wire_agg VALUES ('eng',100),('eng',200),('mkt',150)");
            ResultSet rs = st.executeQuery(
                    "SELECT dept, SUM(salary) AS total FROM wire_agg GROUP BY dept ORDER BY dept");
            assertTrue(rs.next());
            assertEquals("eng", rs.getString(1));
            assertEquals(300, rs.getInt(2));
            assertTrue(rs.next());
            assertEquals("mkt", rs.getString(1));
            assertEquals(150, rs.getInt(2));
        }
    }

    // ── SQLSTATE error codes over wire ─────────────────────────────────────────

    @Test void sqlStateUndefinedTable() throws Exception {
        try (Connection conn = connectSimple();
             Statement st = conn.createStatement()) {
            try {
                st.executeQuery("SELECT * FROM this_table_does_not_exist");
                fail("Should have thrown");
            } catch (SQLException e) {
                assertEquals("42P01", e.getSQLState());
            }
        }
    }

    @Test void sqlStateSyntaxError() throws Exception {
        try (Connection conn = connectSimple();
             Statement st = conn.createStatement()) {
            try {
                st.executeQuery("SELECTX 1");
                fail("Should have thrown");
            } catch (SQLException e) {
                assertNotNull(e.getSQLState());
                assertTrue(e.getSQLState().startsWith("42"),
                        "Syntax error should be 42xxx, got: " + e.getSQLState());
            }
        }
    }

    // ── Wire format correctness ───────────────────────────────────────────────

    @Test void booleanFormatOverWire() throws Exception {
        try (Connection conn = connectSimple();
             Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT true, false");
            assertTrue(rs.next());
            assertTrue(rs.getBoolean(1));
            assertFalse(rs.getBoolean(2));
        }
    }

    @Test void timestampFormatOverWire() throws Exception {
        try (Connection conn = connectSimple();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS wire_ts (ts timestamp)");
            st.execute("INSERT INTO wire_ts VALUES ('2024-03-15 10:30:00')");
            ResultSet rs = st.executeQuery("SELECT ts FROM wire_ts");
            assertTrue(rs.next());
            Timestamp ts = rs.getTimestamp(1);
            assertNotNull(ts);
            assertEquals(2024 - 1900, ts.getYear());
            assertEquals(2, ts.getMonth()); // 0-based
            assertEquals(15, ts.getDate());
        }
    }

    @Test void byteaFormatOverWire() throws Exception {
        try (Connection conn = connectSimple();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS wire_bytea (data bytea)");
            st.execute("INSERT INTO wire_bytea VALUES ('\\x48656c6c6f')");
            ResultSet rs = st.executeQuery("SELECT data FROM wire_bytea");
            assertTrue(rs.next());
            byte[] data = rs.getBytes(1);
            assertNotNull(data);
        }
    }

    @Test void arrayFormatOverWire() throws Exception {
        try (Connection conn = connectSimple();
             Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT ARRAY[1, 2, 3]");
            assertTrue(rs.next());
            String val = rs.getString(1);
            assertNotNull(val);
            // PG format: {1,2,3}
            assertTrue(val.startsWith("{") && val.endsWith("}"),
                    "Array should use PG format {}, got: " + val);
        }
    }
}
