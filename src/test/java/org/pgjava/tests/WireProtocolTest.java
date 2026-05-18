package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.ClusterConfig;
import org.pgjava.jdbc.PgJavaCluster;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the PostgreSQL wire protocol server (Phase 1 / Phase D).
 *
 * <p>Connects via the pgjdbc driver (org.postgresql.Driver) over TCP to a
 * PgJavaCluster that has a wire protocol server enabled. This is the same
 * path that psql, DBeaver, and any unmodified PostgreSQL client would take.
 */
class WireProtocolTest {

    private static PgJavaCluster cluster;
    private static int port;

    @BeforeAll
    static void startServer() {
        // Pick a random high port to avoid conflicts
        port = 15432 + (int)(System.nanoTime() % 1000);
        cluster = PgJavaCluster.create(
                ClusterConfig.builder("wire_" + port)
                        .port(port)
                        .dropOnStop(true)
                        .build()
        ).start();
    }

    @AfterAll
    static void stopServer() {
        if (cluster != null) cluster.stop();
    }

    private Connection connect() throws SQLException {
        // Use pgjdbc (org.postgresql.Driver) to connect via wire protocol
        String url = "jdbc:postgresql://localhost:" + port + "/testdb";
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user",     "postgres");
        props.setProperty("password", "");
        // Disable SSL so we don't need a certificate
        props.setProperty("sslmode",  "disable");
        // Don't use prepared statement caching (simpler path through extended query)
        props.setProperty("preferQueryMode", "simple");
        return DriverManager.getConnection(url, props);
    }

    @Test
    void connect_and_ping() throws Exception {
        try (Connection conn = connect()) {
            assertFalse(conn.isClosed());
        }
    }

    @Test
    void select_constant() throws Exception {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT 42");
            assertTrue(rs.next());
            assertEquals(42, rs.getInt(1));
            assertFalse(rs.next());
        }
    }

    @Test
    void select_string_constant() throws Exception {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT 'hello'");
            assertTrue(rs.next());
            assertEquals("hello", rs.getString(1));
        }
    }

    @Test
    void create_insert_select() throws Exception {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE wire_t1 (id int, val text)");
            st.execute("INSERT INTO wire_t1 VALUES (1, 'one')");
            st.execute("INSERT INTO wire_t1 VALUES (2, 'two')");
            ResultSet rs = st.executeQuery("SELECT id, val FROM wire_t1 ORDER BY id");
            assertTrue(rs.next()); assertEquals(1, rs.getInt(1)); assertEquals("one", rs.getString(2));
            assertTrue(rs.next()); assertEquals(2, rs.getInt(1)); assertEquals("two", rs.getString(2));
            assertFalse(rs.next());
        }
    }

    @Test
    void update_and_delete() throws Exception {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE wire_t2 (id int, v int)");
            st.execute("INSERT INTO wire_t2 VALUES (1, 10), (2, 20)");
            int updated = st.executeUpdate("UPDATE wire_t2 SET v = 99 WHERE id = 1");
            assertEquals(1, updated);
            int deleted = st.executeUpdate("DELETE FROM wire_t2 WHERE id = 2");
            assertEquals(1, deleted);
            ResultSet rs = st.executeQuery("SELECT id, v FROM wire_t2");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(99, rs.getInt(2));
            assertFalse(rs.next());
        }
    }

    @Test
    void set_and_show() throws Exception {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            st.execute("SET search_path = myschema, public");
            ResultSet rs = st.executeQuery("SHOW search_path");
            assertTrue(rs.next());
            assertNotNull(rs.getString(1));
        }
    }

    @Test
    void ddl_create_drop() throws Exception {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE wire_drop_me (x int)");
            st.execute("DROP TABLE wire_drop_me");
            // Should be gone
            assertThrows(SQLException.class,
                    () -> st.executeQuery("SELECT * FROM wire_drop_me"));
        }
    }

    @Test
    void multiple_connections_isolated() throws Exception {
        try (Connection c1 = connect();
             Connection c2 = connect();
             Statement  s1 = c1.createStatement();
             Statement  s2 = c2.createStatement()) {
            s1.execute("CREATE TABLE wire_shared (n int)");
            s1.execute("INSERT INTO wire_shared VALUES (1)");
            // Both connections see same data (shared table)
            ResultSet rs1 = s1.executeQuery("SELECT COUNT(*) FROM wire_shared");
            rs1.next();
            assertEquals(1, rs1.getInt(1));
            ResultSet rs2 = s2.executeQuery("SELECT COUNT(*) FROM wire_shared");
            rs2.next();
            assertEquals(1, rs2.getInt(1));
        }
    }

    @Test
    void extended_query_prepared_statement() throws Exception {
        // Re-enable extended query protocol for this test
        String url = "jdbc:postgresql://localhost:" + port + "/testdb";
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user",     "postgres");
        props.setProperty("password", "");
        props.setProperty("sslmode",  "disable");
        // Use extended query protocol (the default)
        try (Connection conn = DriverManager.getConnection(url, props);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS wire_prep (id int, name text)");
            st.execute("INSERT INTO wire_prep VALUES (10, 'extended')");
        }
        try (Connection conn = DriverManager.getConnection(url, props);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, name FROM wire_prep WHERE id = ?")) {
            ps.setInt(1, 10);
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            assertEquals(10,         rs.getInt(1));
            assertEquals("extended", rs.getString(2));
        }
    }
}
