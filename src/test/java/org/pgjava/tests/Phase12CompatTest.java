package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.QueryResult;
import org.pgjava.engine.Session;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 12 — PostgreSQL Compatibility Polish.
 *
 * <p>Tests that commonly-used SQL constructs which have no semantic meaning in
 * an embedded single-user database (DDL permissions, maintenance commands, etc.)
 * are accepted without error rather than throwing UnsupportedOperationException.
 */
class Phase12CompatTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("phase12_" + System.nanoTime());
        sess = db.openSession();
    }

    @AfterEach
    void tearDown() {
        sess.close();
    }

    private QueryResult q(String sql) throws SQLException { return sess.execute(sql); }
    private void exec(String sql) throws SQLException     { sess.execute(sql); }

    // =========================================================================
    // No-op DDL: role / user management
    // =========================================================================

    @Test void createRoleAccepted() throws SQLException {
        exec("CREATE ROLE myrole");
    }

    @Test void createRoleWithOptions() throws SQLException {
        exec("CREATE ROLE app_user WITH LOGIN PASSWORD 'secret'");
    }

    @Test void dropRoleAccepted() throws SQLException {
        exec("DROP ROLE IF EXISTS myrole");
    }

    @Test void alterRoleAccepted() throws SQLException {
        exec("ALTER ROLE postgres SET search_path TO public");
    }

    // =========================================================================
    // No-op DDL: maintenance / locking
    // =========================================================================

    @Test void lockTableAccepted() throws SQLException {
        exec("CREATE TABLE locked_tbl (x int)");
        exec("LOCK TABLE locked_tbl IN EXCLUSIVE MODE");
    }

    @Test void commentOnTableAccepted() throws SQLException {
        exec("CREATE TABLE commented (x int)");
        exec("COMMENT ON TABLE commented IS 'A test table'");
    }

    @Test void commentOnColumnAccepted() throws SQLException {
        exec("CREATE TABLE commented2 (x int)");
        exec("COMMENT ON COLUMN commented2.x IS 'The x column'");
    }

    @Test void reindexAccepted() throws SQLException {
        exec("CREATE TABLE reindexed (id int PRIMARY KEY)");
        exec("REINDEX TABLE reindexed");
    }

    @Test void clusterAccepted() throws SQLException {
        exec("CREATE TABLE clustered (id int)");
        exec("CLUSTER clustered");
    }

    @Test void checkpointAccepted() throws SQLException {
        exec("CHECKPOINT");
    }

    // =========================================================================
    // No-op DDL: extensions
    // =========================================================================

    @Test void createExtensionAccepted() throws SQLException {
        exec("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"");
    }

    @Test void createExtensionPgcrypto() throws SQLException {
        exec("CREATE EXTENSION pgcrypto");
    }

    @Test void dropExtensionAccepted() throws SQLException {
        exec("DROP EXTENSION IF EXISTS uuid_ossp");
    }

    // =========================================================================
    // No-op: DO block (anonymous function / plpgsql stub)
    // =========================================================================

    @Test void doBlockAccepted() throws SQLException {
        exec("DO $$ BEGIN NULL; END $$");
    }

    @Test void doBlockWithLanguage() throws SQLException {
        exec("DO $body$ BEGIN NULL; END $body$ LANGUAGE plpgsql");
    }

    // =========================================================================
    // pg_typeof function
    // =========================================================================

    @Test void pgTypeofInteger() throws SQLException {
        QueryResult r = q("SELECT pg_typeof(42)");
        assertTrue(r.isQuery());
        assertEquals(1, r.rows().size());
        assertEquals("integer", r.rows().get(0)[0].toString());
    }

    @Test void pgTypeofText() throws SQLException {
        QueryResult r = q("SELECT pg_typeof('hello'::text)");
        assertTrue(r.isQuery());
        assertEquals("text", r.rows().get(0)[0].toString());
    }

    @Test void pgTypeofDouble() throws SQLException {
        QueryResult r = q("SELECT pg_typeof(3.14::double precision)");
        assertTrue(r.isQuery());
        assertEquals("double precision", r.rows().get(0)[0].toString());
    }

    @Test void pgTypeofBoolean() throws SQLException {
        QueryResult r = q("SELECT pg_typeof(true)");
        assertTrue(r.isQuery());
        assertEquals("boolean", r.rows().get(0)[0].toString());
    }

    @Test void pgTypeofNull() throws SQLException {
        QueryResult r = q("SELECT pg_typeof(NULL)");
        assertTrue(r.isQuery());
        assertEquals("unknown", r.rows().get(0)[0].toString());
    }

    // =========================================================================
    // SET search_path / SET LOCAL
    // =========================================================================

    @Test void setSearchPath() throws SQLException {
        exec("SET search_path TO public");
        exec("CREATE TABLE sp_test (x int)");
        exec("INSERT INTO sp_test VALUES (1)");
        QueryResult r = q("SELECT x FROM sp_test");
        assertEquals(1, r.rows().size());
    }

    @Test void setLocalAccepted() throws SQLException {
        exec("CREATE TABLE sl_test (x int)");
        exec("BEGIN");
        exec("SET LOCAL search_path TO public");
        exec("INSERT INTO sl_test VALUES (7)");
        exec("COMMIT");
        QueryResult r = q("SELECT x FROM sl_test");
        assertEquals(1, r.rows().size());
    }

    // =========================================================================
    // Robustness: unknown UnsupportedStmt types don't break good statements
    // =========================================================================

    @Test void noOpDoesNotBreakSubsequentStatements() throws SQLException {
        exec("CREATE ROLE nobody");
        exec("CREATE TABLE after_noop (id int)");
        exec("INSERT INTO after_noop VALUES (99)");
        QueryResult r = q("SELECT id FROM after_noop");
        assertEquals(1, r.rows().size());
        assertEquals("99", r.rows().get(0)[0].toString());
    }
}
