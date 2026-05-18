package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 11 persistence tests: verifies that a file-backed pgjava database
 * survives "restart" (a new {@code Database} instance loaded from the same
 * data directory) and still contains committed data.
 *
 * <p>Each test simulates two JVM sessions by:
 * <ol>
 *   <li>Writing data through {@code jdbc:pgjava:file:<path>}.</li>
 *   <li>Evicting the Database from the file registry (simulating a new JVM).</li>
 *   <li>Opening a fresh connection to the same path.</li>
 *   <li>Asserting the data is present.</li>
 * </ol>
 */
class WalPersistenceTest {

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Evict a persistent database from the JVM-level file registry so the next
     * open simulates a cold start.
     */
    private static void evict(Path dataDir) {
        org.pgjava.engine.Database.evictPersistent(dataDir);
    }

    private static String url(Path dir) {
        return "jdbc:pgjava:file:" + dir.toAbsolutePath();
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    void basicInsertSurvivesRestart(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("db1");

        // Session 1 — create table, insert rows, commit
        try (Connection conn = DriverManager.getConnection(url(dir));
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE pets (name text, age int)");
            st.execute("INSERT INTO pets VALUES ('dog', 3)");
            st.execute("INSERT INTO pets VALUES ('cat', 5)");
        }

        evict(dir);

        // Session 2 — fresh load from disk
        try (Connection conn = DriverManager.getConnection(url(dir));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) AS n FROM pets")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt("n"), "Both rows should survive restart");
        }
    }

    @Test
    void rowValuesRoundTrip(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("db2");

        try (Connection conn = DriverManager.getConnection(url(dir));
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE nums (id int, val double precision, label text)");
            st.execute("INSERT INTO nums VALUES (1, 3.14, 'pi')");
            st.execute("INSERT INTO nums VALUES (2, 2.718, 'e')");
        }

        evict(dir);

        try (Connection conn = DriverManager.getConnection(url(dir));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, val, label FROM nums ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("id"));
            assertEquals(3.14, rs.getDouble("val"), 1e-9);
            assertEquals("pi", rs.getString("label"));

            assertTrue(rs.next());
            assertEquals(2, rs.getInt("id"));
            assertEquals(2.718, rs.getDouble("val"), 1e-9);
            assertEquals("e", rs.getString("label"));

            assertFalse(rs.next());
        }
    }

    @Test
    void primaryKeyIndexRebuiltOnRestart(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("db3");

        try (Connection conn = DriverManager.getConnection(url(dir));
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE items (id int PRIMARY KEY, name text)");
            st.execute("INSERT INTO items VALUES (10, 'apple')");
            st.execute("INSERT INTO items VALUES (20, 'banana')");
        }

        evict(dir);

        // After restart, PK constraint must still be enforced
        try (Connection conn = DriverManager.getConnection(url(dir));
             Statement st = conn.createStatement()) {
            // Lookup by PK still works
            try (ResultSet rs = st.executeQuery(
                    "SELECT name FROM items WHERE id = 10")) {
                assertTrue(rs.next());
                assertEquals("apple", rs.getString("name"));
            }

            // Duplicate PK must be rejected
            assertThrows(SQLException.class,
                    () -> st.execute("INSERT INTO items VALUES (10, 'duplicate')"),
                    "Duplicate PK should be rejected after restart");
        }
    }

    @Test
    void uncommittedDataNotPersistedOnRestart(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("db4");

        try (Connection conn = DriverManager.getConnection(url(dir));
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE tx_test (x int)");
            // Auto-commit INSERT — persisted
            st.execute("INSERT INTO tx_test VALUES (1)");
            // Explicit tx rollback — must NOT be persisted
            st.execute("BEGIN");
            st.execute("INSERT INTO tx_test VALUES (2)");
            st.execute("ROLLBACK");
        }

        evict(dir);

        try (Connection conn = DriverManager.getConnection(url(dir));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT x FROM tx_test ORDER BY x")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("x"));
            assertFalse(rs.next(), "Rolled-back row 2 must not survive restart");
        }
    }

    @Test
    void multipleTablesAndSchemasSurviveRestart(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("db5");

        try (Connection conn = DriverManager.getConnection(url(dir));
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE a (x int)");
            st.execute("CREATE TABLE b (y text)");
            st.execute("INSERT INTO a VALUES (42)");
            st.execute("INSERT INTO b VALUES ('hello')");
        }

        evict(dir);

        try (Connection conn = DriverManager.getConnection(url(dir));
             Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT x FROM a")) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt("x"));
            }
            try (ResultSet rs = st.executeQuery("SELECT y FROM b")) {
                assertTrue(rs.next());
                assertEquals("hello", rs.getString("y"));
            }
        }
    }

    @Test
    void sequenceCurrentValueSurvivesRestart(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("db6");

        try (Connection conn = DriverManager.getConnection(url(dir));
             Statement st = conn.createStatement()) {
            st.execute("CREATE SEQUENCE myseq START 1");
            // Advance the sequence several times
            st.execute("SELECT nextval('myseq')"); // 1
            st.execute("SELECT nextval('myseq')"); // 2
            st.execute("SELECT nextval('myseq')"); // 3
        }

        evict(dir);

        // After restart, nextval should continue from 4
        try (Connection conn = DriverManager.getConnection(url(dir));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT nextval('myseq') AS v")) {
            assertTrue(rs.next());
            assertEquals(4, rs.getLong("v"),
                    "Sequence should continue from saved position after restart");
        }
    }

    @Test
    void nullValuesRoundTrip(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("db7");

        try (Connection conn = DriverManager.getConnection(url(dir));
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE nullable_test (id int, val text)");
            st.execute("INSERT INTO nullable_test VALUES (1, NULL)");
            st.execute("INSERT INTO nullable_test VALUES (2, 'present')");
        }

        evict(dir);

        try (Connection conn = DriverManager.getConnection(url(dir));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, val FROM nullable_test ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("id"));
            assertNull(rs.getString("val"));
            assertTrue(rs.wasNull());

            assertTrue(rs.next());
            assertEquals(2, rs.getInt("id"));
            assertEquals("present", rs.getString("val"));
        }
    }

    @Test
    void updateAndDeleteSurviveRestart(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("db8");

        try (Connection conn = DriverManager.getConnection(url(dir));
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE mutable (id int, v text)");
            st.execute("INSERT INTO mutable VALUES (1, 'original')");
            st.execute("INSERT INTO mutable VALUES (2, 'to-delete')");
            st.execute("UPDATE mutable SET v = 'updated' WHERE id = 1");
            st.execute("DELETE FROM mutable WHERE id = 2");
        }

        evict(dir);

        try (Connection conn = DriverManager.getConnection(url(dir));
             Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT id, v FROM mutable ORDER BY id")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("id"));
                assertEquals("updated", rs.getString("v"));
                assertFalse(rs.next(), "Deleted row must not survive restart");
            }
        }
    }
}
