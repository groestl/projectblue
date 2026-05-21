package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.pgjava.engine.Database;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that FK constraint enforcement is MVCC-aware.
 *
 * <p>The bug: {@code ConstraintChecker.checkForeignKey()} called
 * {@code idx.lookupExact(fkVals)} and accepted any non-empty hit list —
 * including RowIds from deleted (tombstoned) or uncommitted parent rows.
 * This let child rows reference a parent that no longer existed under the
 * current snapshot.
 *
 * <p>The fix: the MVCC-aware overload filters index hits through
 * {@link org.pgjava.storage.HeapTable#lookupByRowId(org.pgjava.storage.RowId,
 * org.pgjava.storage.TxSnapshot)} and uses {@link org.pgjava.storage.HeapTable#fullScan(TxSnapshot)}
 * for full-scan fallback, so only committed, non-deleted rows satisfy the constraint.
 */
class FkMvccTest {

    // ── Basic FK enforcement still works ─────────────────────────────────────

    @Test
    void fkReferenceToExistingParentAllowed() throws SQLException {
        Database db = Database.create("fk_mvcc_basic");
        try (var s = db.openSession()) {
            s.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
            s.execute("CREATE TABLE child  (id INT, pid INT REFERENCES parent(id))");
            s.execute("INSERT INTO parent VALUES (1)");
            assertDoesNotThrow(() -> s.execute("INSERT INTO child VALUES (1, 1)"));
        }
    }

    @Test
    void fkReferenceToMissingParentRejected() throws SQLException {
        Database db = Database.create("fk_mvcc_missing");
        try (var s = db.openSession()) {
            s.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
            s.execute("CREATE TABLE child  (id INT, pid INT REFERENCES parent(id))");
            SQLException ex = assertThrows(SQLException.class,
                    () -> s.execute("INSERT INTO child VALUES (1, 99)"));
            assertEquals("23503", ex.getSQLState());
        }
    }

    // ── MVCC: deleted parent must not satisfy FK ──────────────────────────────

    @Test
    void fkRejectedAfterParentDeleted() throws SQLException {
        Database db = Database.create("fk_mvcc_delete");
        try (var s = db.openSession()) {
            s.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
            s.execute("CREATE TABLE child  (id INT, pid INT REFERENCES parent(id))");

            s.execute("INSERT INTO parent VALUES (1)");
            // Delete the parent — its index entry still exists but is tombstoned
            s.execute("DELETE FROM parent WHERE id = 1");

            // Child insert must fail: parent row is deleted
            SQLException ex = assertThrows(SQLException.class,
                    () -> s.execute("INSERT INTO child VALUES (1, 1)"),
                    "FK must fail: parent row was deleted");
            assertEquals("23503", ex.getSQLState(),
                    "Expected SQLSTATE 23503, got: " + ex.getSQLState() + " — " + ex.getMessage());
        }
    }

    // ── Insert-then-delete-in-same-session round-trip ─────────────────────────

    @Test
    void fkAllowedAfterParentReinserted() throws SQLException {
        Database db = Database.create("fk_mvcc_reinsert");
        try (var s = db.openSession()) {
            s.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
            s.execute("CREATE TABLE child  (id INT, pid INT REFERENCES parent(id))");

            s.execute("INSERT INTO parent VALUES (1)");
            s.execute("DELETE FROM parent WHERE id = 1");
            s.execute("INSERT INTO parent VALUES (1)"); // re-insert

            // Now FK should succeed again
            assertDoesNotThrow(() -> s.execute("INSERT INTO child VALUES (1, 1)"));
        }
    }

    // ── NULL FK columns satisfy constraint (MATCH SIMPLE) ────────────────────

    @Test
    void fkNullColumnSatisfiesConstraint() throws SQLException {
        Database db = Database.create("fk_mvcc_null");
        try (var s = db.openSession()) {
            s.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
            s.execute("CREATE TABLE child  (id INT, pid INT REFERENCES parent(id))");
            // NULL pid — MATCH SIMPLE: constraint satisfied regardless
            assertDoesNotThrow(() -> s.execute("INSERT INTO child VALUES (1, NULL)"));
        }
    }

    // ── UPDATE: FK checked on new values ─────────────────────────────────────

    @Test
    void fkUpdateToMissingParentRejected() throws SQLException {
        Database db = Database.create("fk_mvcc_update");
        try (var s = db.openSession()) {
            s.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
            s.execute("CREATE TABLE child  (id INT PRIMARY KEY, pid INT REFERENCES parent(id))");
            s.execute("INSERT INTO parent VALUES (1), (2)");
            s.execute("INSERT INTO child  VALUES (1, 1)");

            // Update child's FK to a non-existent parent
            SQLException ex = assertThrows(SQLException.class,
                    () -> s.execute("UPDATE child SET pid = 99 WHERE id = 1"));
            assertEquals("23503", ex.getSQLState());
        }
    }

    @Test
    void fkUpdateToValidParentAllowed() throws SQLException {
        Database db = Database.create("fk_mvcc_update_ok");
        try (var s = db.openSession()) {
            s.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
            s.execute("CREATE TABLE child  (id INT PRIMARY KEY, pid INT REFERENCES parent(id))");
            s.execute("INSERT INTO parent VALUES (1), (2)");
            s.execute("INSERT INTO child  VALUES (1, 1)");
            assertDoesNotThrow(() -> s.execute("UPDATE child SET pid = 2 WHERE id = 1"));
        }
    }
}
