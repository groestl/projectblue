package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;
import org.pgjava.sql.parser.ParserProvider;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 milestone: the test harness works and validates against embedded-postgres.
 * The pgjava side is not yet implemented — all pgjava assertions are gracefully skipped.
 *
 * <p>These tests validate the harness itself, not pgjava.
 */
@ExtendWith(GoldenExtension.class)
class Phase0SmokeTest {

    /** Embedded-postgres starts and SELECT 1 returns 1. Harness is functional. */
    @Test
    void embeddedPostgresSelectOne(DualExecutor db) throws Exception {
        db.assertQuery("SELECT 1");
    }

    /** Arithmetic query works correctly on postgres side. */
    @Test
    void embeddedPostgresArithmetic(DualExecutor db) throws Exception {
        db.assertQuery("SELECT 1 + 1 AS result");
    }

    /** String literal and aliasing works. */
    @Test
    void embeddedPostgresStringLiteral(DualExecutor db) throws Exception {
        db.assertQuery("SELECT 'hello' AS greeting");
    }

    /** Parser provider reports correct state (native not available on Termux/Android). */
    @Test
    void parserProviderDetectsNativeUnavailability() {
        boolean nativeAvailable = ParserProvider.isNativeAvailable();
        // Value depends on platform — just verify it doesn't throw
        System.out.println("Native parser available: " + nativeAvailable);
    }

    /** DualExecutor gracefully handles pgjava being unavailable. */
    @Test
    void dualExecutorHandlesMissingPgjava(DualExecutor db) throws Exception {
        // Even with pgjava unavailable, this should not throw
        db.execute("CREATE TABLE smoketest (id INT, name TEXT)");
        db.execute("INSERT INTO smoketest VALUES (1, 'alpha'), (2, 'beta')");
        db.assertQuery("SELECT id, name FROM smoketest ORDER BY id");
    }

    /** assumeSupported() skips pgjava side — postgres side still runs. */
    @Test
    void assumeSupportedDoesNotBlockPostgres(DualExecutor db) throws Exception {
        db.assumeSupported(); // marks pgjava side as not yet implemented

        // Postgres side still runs and validates
        db.execute("CREATE TABLE things (val INT)");
        db.execute("INSERT INTO things VALUES (42)");
        db.assertQuery("SELECT val FROM things");
    }

    /** Postgres connection is accessible directly for assertions the harness doesn't cover. */
    @Test
    void directPostgresConnection(DualExecutor db) throws Exception {
        Connection pg = db.pgConnection();
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery("SELECT version()")) {
            assertTrue(rs.next());
            String version = rs.getString(1);
            assertTrue(version.contains("PostgreSQL"),
                    "Expected PostgreSQL version string, got: " + version);
        }
    }

    /** Schema isolation: table created in one test is not visible in another. */
    @Test
    void schemaIsolation_a(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE isolation_marker (x INT)");
        db.execute("INSERT INTO isolation_marker VALUES (1)");
        db.assertQuery("SELECT x FROM isolation_marker");
    }

    @Test
    void schemaIsolation_b(DualExecutor db) throws Exception {
        // If isolation_marker leaked from test_a, this would fail with duplicate table or
        // unexpected rows. The harness creates a fresh schema per test.
        db.execute("CREATE TABLE isolation_marker (x INT)");
        db.execute("INSERT INTO isolation_marker VALUES (99)");
        db.assertQuery("SELECT x FROM isolation_marker");
    }
}
