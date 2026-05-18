package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden tests for transaction semantics — each test runs against both pgjava
 * and embedded-postgres and results must match.
 *
 * <p>Multi-connection isolation tests (one connection cannot see another's
 * uncommitted data) are in the pgjava-only block at the bottom since
 * DualExecutor models a single connection pair.
 */
@ExtendWith(GoldenExtension.class)
class GoldenTransactionTest {

    // =========================================================================
    // ROLLBACK undoes DML
    // =========================================================================

    @Test
    void rollbackUndoesInsert(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE tx_rollback_insert (x int)");
        db.execute("BEGIN");
        db.execute("INSERT INTO tx_rollback_insert VALUES (1)");
        db.execute("ROLLBACK");
        db.assertQuery("SELECT COUNT(*) AS result FROM tx_rollback_insert");
    }

    @Test
    void rollbackUndoesUpdate(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE tx_rollback_upd (x int)");
        db.execute("INSERT INTO tx_rollback_upd VALUES (10)");
        db.execute("BEGIN");
        db.execute("UPDATE tx_rollback_upd SET x = 99");
        db.execute("ROLLBACK");
        db.assertQuery("SELECT x AS result FROM tx_rollback_upd");
    }

    @Test
    void rollbackUndoesDelete(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE tx_rollback_del (x int)");
        db.execute("INSERT INTO tx_rollback_del VALUES (1)");
        db.execute("INSERT INTO tx_rollback_del VALUES (2)");
        db.execute("BEGIN");
        db.execute("DELETE FROM tx_rollback_del WHERE x = 1");
        db.execute("ROLLBACK");
        db.assertQuery("SELECT COUNT(*) AS result FROM tx_rollback_del");
    }

    // =========================================================================
    // COMMIT persists data
    // =========================================================================

    @Test
    void commitPersistsInsert(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE tx_commit_insert (x int)");
        db.execute("BEGIN");
        db.execute("INSERT INTO tx_commit_insert VALUES (42)");
        db.execute("COMMIT");
        db.assertQuery("SELECT x AS result FROM tx_commit_insert");
    }

    @Test
    void commitPersistsMultipleInserts(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE tx_multi_insert (x int)");
        db.execute("BEGIN");
        db.execute("INSERT INTO tx_multi_insert VALUES (1)");
        db.execute("INSERT INTO tx_multi_insert VALUES (2)");
        db.execute("INSERT INTO tx_multi_insert VALUES (3)");
        db.execute("COMMIT");
        db.assertQuery("SELECT COUNT(*) AS result FROM tx_multi_insert");
    }

    // =========================================================================
    // Auto-commit — each statement is its own transaction
    // =========================================================================

    @Test
    void autoCommitEachStatementVisible(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE tx_auto (x int)");
        db.execute("INSERT INTO tx_auto VALUES (1)");  // auto-committed
        db.execute("INSERT INTO tx_auto VALUES (2)");  // auto-committed
        db.assertQuery("SELECT COUNT(*) AS result FROM tx_auto");
    }

    // =========================================================================
    // Savepoints
    // =========================================================================

    @Test
    void savepointRollbackPartial(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE tx_sp (x int)");
        db.execute("BEGIN");
        db.execute("INSERT INTO tx_sp VALUES (1)");
        db.execute("SAVEPOINT before_two");
        db.execute("INSERT INTO tx_sp VALUES (2)");
        db.execute("ROLLBACK TO SAVEPOINT before_two");
        db.execute("COMMIT");
        db.assertQuery("SELECT x AS result FROM tx_sp ORDER BY x");
    }

    @Test
    void multipleSavepoints(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE tx_sp2 (x int)");
        db.execute("BEGIN");
        db.execute("INSERT INTO tx_sp2 VALUES (1)");
        db.execute("SAVEPOINT sp1");
        db.execute("INSERT INTO tx_sp2 VALUES (2)");
        db.execute("SAVEPOINT sp2");
        db.execute("INSERT INTO tx_sp2 VALUES (3)");
        db.execute("ROLLBACK TO SAVEPOINT sp1");  // removes 2 and 3
        db.execute("COMMIT");
        db.assertQuery("SELECT COUNT(*) AS result FROM tx_sp2");
    }

    @Test
    void savepointRollbackThenContinue(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE tx_sp3 (x int)");
        db.execute("BEGIN");
        db.execute("INSERT INTO tx_sp3 VALUES (1)");
        db.execute("SAVEPOINT sp1");
        db.execute("INSERT INTO tx_sp3 VALUES (2)");
        db.execute("ROLLBACK TO SAVEPOINT sp1");
        db.execute("INSERT INTO tx_sp3 VALUES (3)");  // after rollback to sp1
        db.execute("COMMIT");
        db.assertQuery("SELECT x AS result FROM tx_sp3 ORDER BY x");
    }

    @Test
    void releaseSavepoint(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE tx_rel (x int)");
        db.execute("BEGIN");
        db.execute("INSERT INTO tx_rel VALUES (1)");
        db.execute("SAVEPOINT sp1");
        db.execute("INSERT INTO tx_rel VALUES (2)");
        db.execute("RELEASE SAVEPOINT sp1");  // keeps row 2
        db.execute("COMMIT");
        db.assertQuery("SELECT COUNT(*) AS result FROM tx_rel");
    }

    // =========================================================================
    // Mixed DDL + DML in transaction
    // =========================================================================

    @Test
    void rollbackAfterMixedDml(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE tx_mixed (id int, val text)");
        db.execute("INSERT INTO tx_mixed VALUES (1, 'one')");
        db.execute("BEGIN");
        db.execute("INSERT INTO tx_mixed VALUES (2, 'two')");
        db.execute("UPDATE tx_mixed SET val = 'ONE' WHERE id = 1");
        db.execute("ROLLBACK");
        // Only the pre-BEGIN row should exist, with original value
        db.assertQuery("SELECT id, val AS result FROM tx_mixed ORDER BY id");
    }

    // =========================================================================
    // BEGIN + COMMIT no-ops when no DML
    // =========================================================================

    @Test
    void emptyTransaction(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE tx_empty (x int)");
        db.execute("BEGIN");
        db.execute("COMMIT");
        db.assertQuery("SELECT COUNT(*) AS result FROM tx_empty");
    }

    @Test
    void emptyRollback(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE tx_eroll (x int)");
        db.execute("INSERT INTO tx_eroll VALUES (1)");
        db.execute("BEGIN");
        db.execute("ROLLBACK");
        db.assertQuery("SELECT COUNT(*) AS result FROM tx_eroll");
    }

    // =========================================================================
    // FAILED state — pgjava-only (error semantics; no DualExecutor golden comparison)
    // These tests verify pgjava produces the correct SQLSTATE.
    // =========================================================================

    @Test
    void failedStateBlocksSubsequentDml() throws Exception {
        String url = "jdbc:pgjava:mem:tx_failed_" + System.nanoTime();
        try (Connection conn = DriverManager.getConnection(url);
             Statement  st   = conn.createStatement()) {
            st.execute("CREATE TABLE tx_f (x int)");
            st.execute("BEGIN");
            st.execute("INSERT INTO tx_f VALUES (1)");
            // Force a statement error — division by zero
            try { st.execute("SELECT 1/0"); } catch (SQLException ignored) {}
            // Now any DML must be rejected with 25P02
            try {
                st.execute("INSERT INTO tx_f VALUES (2)");
                fail("Expected 25P02 but no exception thrown");
            } catch (SQLException e) {
                assertEquals("25P02", e.getSQLState(),
                        "Expected SQLSTATE 25P02, got: " + e.getSQLState());
            }
            // ROLLBACK clears the failed state
            st.execute("ROLLBACK");
            // After ROLLBACK, the transaction is gone — row 1 was rolled back
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tx_f");
            rs.next();
            assertEquals(0, rs.getInt(1), "ROLLBACK should have undone the insert of row 1");
        }
    }

    @Test
    void failedStateCllearedByRollback() throws Exception {
        String url = "jdbc:pgjava:mem:tx_clear_" + System.nanoTime();
        try (Connection conn = DriverManager.getConnection(url);
             Statement  st   = conn.createStatement()) {
            st.execute("CREATE TABLE tx_c (x int)");
            // First transaction — fails
            st.execute("BEGIN");
            try { st.execute("SELECT 1/0"); } catch (SQLException ignored) {}
            st.execute("ROLLBACK");
            // Second transaction — must work normally
            st.execute("BEGIN");
            st.execute("INSERT INTO tx_c VALUES (42)");
            st.execute("COMMIT");
            ResultSet rs = st.executeQuery("SELECT x FROM tx_c");
            assertTrue(rs.next());
            assertEquals(42, rs.getInt(1));
        }
    }

    @Test
    void rollbackToSavepointClearsFailedState() throws Exception {
        String url = "jdbc:pgjava:mem:tx_sp_fail_" + System.nanoTime();
        try (Connection conn = DriverManager.getConnection(url);
             Statement  st   = conn.createStatement()) {
            st.execute("CREATE TABLE tx_sf (x int)");
            st.execute("BEGIN");
            st.execute("INSERT INTO tx_sf VALUES (1)");
            st.execute("SAVEPOINT sp1");
            // Cause a failure after the savepoint
            try { st.execute("SELECT 1/0"); } catch (SQLException ignored) {}
            // ROLLBACK TO SAVEPOINT should clear the failed state
            st.execute("ROLLBACK TO SAVEPOINT sp1");
            // Now we should be able to continue
            st.execute("INSERT INTO tx_sf VALUES (2)");
            st.execute("COMMIT");
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tx_sf");
            rs.next();
            assertEquals(2, rs.getInt(1), "Both rows should be present after rollback-to-savepoint");
        }
    }
}
