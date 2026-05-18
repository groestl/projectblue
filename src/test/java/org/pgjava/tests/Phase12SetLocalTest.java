package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.QueryResult;
import org.pgjava.engine.Session;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 12: SET LOCAL, RESET, and related GUC behaviour.
 *
 * <p>SET LOCAL sets a variable for the duration of the current transaction only;
 * the pre-transaction value is restored on COMMIT and ROLLBACK alike.
 * RESET restores a variable to its default (i.e. removes any session override).
 */
class Phase12SetLocalTest {

    private Database db;

    @BeforeEach
    void setUp() {
        db = DatabaseRegistry.getOrCreate("phase12_set_" + System.nanoTime());
    }

    private Session session() { return db.openSession(); }

    // =========================================================================
    // RESET
    // =========================================================================

    @Test void resetRemovesSessionVar() throws SQLException {
        try (Session s = session()) {
            s.execute("SET search_path = myschema");
            assertEquals("myschema", s.getVariable("search_path"));

            s.execute("RESET search_path");
            // After RESET, variable should be gone (returns null / default)
            assertNull(s.getVariable("search_path"),
                    "RESET should remove the session variable");
        }
    }

    @Test void resetUnknownParamIsNoOp() throws SQLException {
        try (Session s = session()) {
            // RESET on a var that was never set — must not throw
            assertDoesNotThrow(() -> s.execute("RESET work_mem"));
        }
    }

    // =========================================================================
    // SET LOCAL — basic
    // =========================================================================

    @Test void setLocalRestoredOnCommit() throws SQLException {
        try (Session s = session()) {
            s.execute("SET search_path = 'session_value'");

            s.execute("BEGIN");
            s.execute("SET LOCAL search_path = 'local_value'");
            assertEquals("local_value", s.getVariable("search_path"),
                    "SET LOCAL must take effect immediately");

            s.execute("COMMIT");
            assertEquals("session_value", s.getVariable("search_path"),
                    "SET LOCAL value must be restored to pre-tx value on COMMIT");
        }
    }

    @Test void setLocalRestoredOnRollback() throws SQLException {
        try (Session s = session()) {
            s.execute("SET search_path = 'session_value'");

            s.execute("BEGIN");
            s.execute("SET LOCAL search_path = 'local_value'");
            assertEquals("local_value", s.getVariable("search_path"));

            s.execute("ROLLBACK");
            assertEquals("session_value", s.getVariable("search_path"),
                    "SET LOCAL value must be restored to pre-tx value on ROLLBACK");
        }
    }

    @Test void setLocalRestoredWhenVarDidNotExistBefore() throws SQLException {
        try (Session s = session()) {
            // work_mem not set at session level
            assertNull(s.getVariable("work_mem"));

            s.execute("BEGIN");
            s.execute("SET LOCAL work_mem = '64MB'");
            assertEquals("64MB", s.getVariable("work_mem"));

            s.execute("COMMIT");
            assertNull(s.getVariable("work_mem"),
                    "SET LOCAL on previously-absent var must remove it again on tx end");
        }
    }

    @Test void setLocalMultipleVarsRestoredIndependently() throws SQLException {
        try (Session s = session()) {
            s.execute("SET search_path = 'sp'");
            s.execute("SET timezone = 'Europe/Berlin'");

            s.execute("BEGIN");
            s.execute("SET LOCAL search_path = 'lsp'");
            s.execute("SET LOCAL timezone = 'America/New_York'");

            s.execute("COMMIT");
            assertEquals("sp", s.getVariable("search_path"));
            assertEquals("Europe/Berlin", s.getVariable("timezone"));
        }
    }

    /** SET SESSION (explicit scope) must persist past transaction end. */
    @Test void setSessionPersistsAcrossTx() throws SQLException {
        try (Session s = session()) {
            s.execute("BEGIN");
            s.execute("SET SESSION search_path = 'persistent'");
            s.execute("COMMIT");

            assertEquals("persistent", s.getVariable("search_path"),
                    "SET SESSION must survive COMMIT unchanged");
        }
    }

    /** SET LOCAL outside a transaction acts like SET SESSION (no transaction to scope to). */
    @Test void setLocalOutsideTransactionActsLikeSession() throws SQLException {
        try (Session s = session()) {
            // No BEGIN — SET LOCAL outside tx is treated as session-level
            s.execute("SET LOCAL search_path = 'persists'");
            assertEquals("persists", s.getVariable("search_path"));
        }
    }

    /** Multiple SET LOCAL calls to the same var — only the first pre-tx value is saved. */
    @Test void setLocalTwiceSameVar() throws SQLException {
        try (Session s = session()) {
            s.execute("SET search_path = 'original'");

            s.execute("BEGIN");
            s.execute("SET LOCAL search_path = 'first'");
            s.execute("SET LOCAL search_path = 'second'");
            assertEquals("second", s.getVariable("search_path"));

            s.execute("ROLLBACK");
            assertEquals("original", s.getVariable("search_path"),
                    "Restore must recover the true pre-tx value, not the intermediate one");
        }
    }

    // =========================================================================
    // SHOW verifies the value is reflected
    // =========================================================================

    @Test void showReflectsSetLocal() throws SQLException {
        try (Session s = session()) {
            s.execute("SET search_path = 'base'");
            s.execute("BEGIN");
            s.execute("SET LOCAL search_path = 'tmp'");

            QueryResult r = s.execute("SHOW search_path");
            assertEquals(1, r.rows().size());
            assertEquals("tmp", r.rows().get(0)[0].toString());

            s.execute("COMMIT");
            QueryResult r2 = s.execute("SHOW search_path");
            assertEquals("base", r2.rows().get(0)[0].toString());
        }
    }
}
