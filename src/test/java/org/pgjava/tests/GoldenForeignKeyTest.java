package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;

/**
 * Golden tests for foreign key constraint behaviour comparing pgjava against real PostgreSQL.
 *
 * <p>Covers: basic FK enforcement, NULL columns (MATCH SIMPLE), cascading actions
 * (ON DELETE CASCADE/SET NULL/SET DEFAULT/RESTRICT, ON UPDATE CASCADE), multi-column FKs,
 * MVCC visibility (child insert after parent delete), and self-referential FKs.
 */
@ExtendWith(GoldenExtension.class)
class GoldenForeignKeyTest {

    // ── Basic insert enforcement ──────────────────────────────────────────────

    @Test void fkInsertValidParent(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, pid INT REFERENCES parent(id))");
        db.execute("INSERT INTO parent VALUES (1), (2), (3)");
        db.execute("INSERT INTO child  VALUES (1, 1), (2, 2), (3, 3)");
        db.assertQuery("SELECT c.id, c.pid FROM child c JOIN parent p ON c.pid = p.id ORDER BY c.id");
    }

    @Test void fkInsertMissingParentRejected(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, pid INT REFERENCES parent(id))");
        db.assertError("INSERT INTO child VALUES (1, 99)", "23503");
    }

    @Test void fkNullColumnSatisfies(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, pid INT REFERENCES parent(id))");
        // NULL FK column — MATCH SIMPLE: constraint satisfied
        db.execute("INSERT INTO child VALUES (1, NULL)");
        db.assertQuery("SELECT id, pid FROM child ORDER BY id");
    }

    // ── Update enforcement ────────────────────────────────────────────────────

    @Test void fkUpdateChildToMissingParentRejected(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, pid INT REFERENCES parent(id))");
        db.execute("INSERT INTO parent VALUES (1), (2)");
        db.execute("INSERT INTO child  VALUES (10, 1)");
        db.assertError("UPDATE child SET pid = 99 WHERE id = 10", "23503");
    }

    @Test void fkUpdateChildToValidParentAllowed(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, pid INT REFERENCES parent(id))");
        db.execute("INSERT INTO parent VALUES (1), (2)");
        db.execute("INSERT INTO child  VALUES (10, 1)");
        db.execute("UPDATE child SET pid = 2 WHERE id = 10");
        db.assertQuery("SELECT id, pid FROM child ORDER BY id");
    }

    // ── Parent delete enforcement ─────────────────────────────────────────────

    @Test void deleteParentWithChildRejected(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, pid INT REFERENCES parent(id))");
        db.execute("INSERT INTO parent VALUES (1)");
        db.execute("INSERT INTO child  VALUES (1, 1)");
        db.assertError("DELETE FROM parent WHERE id = 1", "23503");
    }

    @Test void deleteParentWithNoChildAllowed(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, pid INT REFERENCES parent(id))");
        db.execute("INSERT INTO parent VALUES (1), (2)");
        db.execute("INSERT INTO child  VALUES (1, 1)");
        db.execute("DELETE FROM parent WHERE id = 2");
        db.assertQuery("SELECT id FROM parent ORDER BY id");
    }

    // ── ON DELETE CASCADE ─────────────────────────────────────────────────────

    @Test void onDeleteCascade(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, pid INT REFERENCES parent(id) ON DELETE CASCADE)");
        db.execute("INSERT INTO parent VALUES (1), (2)");
        db.execute("INSERT INTO child  VALUES (10, 1), (11, 1), (12, 2)");
        db.execute("DELETE FROM parent WHERE id = 1");
        db.assertQuery("SELECT id, pid FROM child ORDER BY id");
    }

    @Test void onDeleteCascadeChained(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE a (id INT PRIMARY KEY)");
        db.execute("CREATE TABLE b (id INT PRIMARY KEY, aid INT REFERENCES a(id) ON DELETE CASCADE)");
        db.execute("CREATE TABLE c (id INT PRIMARY KEY, bid INT REFERENCES b(id) ON DELETE CASCADE)");
        db.execute("INSERT INTO a VALUES (1)");
        db.execute("INSERT INTO b VALUES (10, 1)");
        db.execute("INSERT INTO c VALUES (100, 10)");
        db.execute("DELETE FROM a WHERE id = 1");
        db.assertQuery("SELECT count(*) FROM b");
        db.assertQuery("SELECT count(*) FROM c");
    }

    // ── ON DELETE SET NULL ────────────────────────────────────────────────────

    @Test void onDeleteSetNull(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, pid INT REFERENCES parent(id) ON DELETE SET NULL)");
        db.execute("INSERT INTO parent VALUES (1), (2)");
        db.execute("INSERT INTO child  VALUES (10, 1), (11, 2)");
        db.execute("DELETE FROM parent WHERE id = 1");
        db.assertQuery("SELECT id, pid FROM child ORDER BY id");
    }

    // ── ON DELETE SET DEFAULT ─────────────────────────────────────────────────

    @Test void onDeleteSetDefault(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, pid INT DEFAULT 2 REFERENCES parent(id) ON DELETE SET DEFAULT)");
        db.execute("INSERT INTO parent VALUES (1), (2)");
        db.execute("INSERT INTO child  VALUES (10, 1)");
        db.execute("DELETE FROM parent WHERE id = 1");
        db.assertQuery("SELECT id, pid FROM child ORDER BY id");
    }

    // ── ON UPDATE CASCADE ─────────────────────────────────────────────────────

    @Test void onUpdateCascade(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, pid INT REFERENCES parent(id) ON UPDATE CASCADE)");
        db.execute("INSERT INTO parent VALUES (1), (2)");
        db.execute("INSERT INTO child  VALUES (10, 1), (11, 1)");
        db.execute("UPDATE parent SET id = 99 WHERE id = 1");
        db.assertQuery("SELECT id, pid FROM child ORDER BY id");
    }

    // ── Multi-column FK ───────────────────────────────────────────────────────

    @Test void multiColumnFk(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (a INT, b INT, PRIMARY KEY (a, b))");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, a INT, b INT, FOREIGN KEY (a, b) REFERENCES parent(a, b))");
        db.execute("INSERT INTO parent VALUES (1, 10), (2, 20)");
        db.execute("INSERT INTO child  VALUES (1, 1, 10)");
        db.assertQuery("SELECT id, a, b FROM child ORDER BY id");
    }

    @Test void multiColumnFkViolation(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (a INT, b INT, PRIMARY KEY (a, b))");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, a INT, b INT, FOREIGN KEY (a, b) REFERENCES parent(a, b))");
        db.execute("INSERT INTO parent VALUES (1, 10)");
        // (1, 99) is not in parent
        db.assertError("INSERT INTO child VALUES (1, 1, 99)", "23503");
    }

    @Test void multiColumnFkNullBypassesCheck(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (a INT, b INT, PRIMARY KEY (a, b))");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, a INT, b INT, FOREIGN KEY (a, b) REFERENCES parent(a, b))");
        // NULL in one FK column — MATCH SIMPLE: constraint satisfied
        db.execute("INSERT INTO child VALUES (1, 1, NULL)");
        db.assertQuery("SELECT id, a, b FROM child ORDER BY id");
    }

    // ── MVCC: FK after parent delete ──────────────────────────────────────────

    @Test void fkRejectedAfterParentDeleted(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, pid INT REFERENCES parent(id))");
        db.execute("INSERT INTO parent VALUES (1)");
        db.execute("DELETE FROM parent WHERE id = 1");
        // Parent row is deleted — FK must fail
        db.assertError("INSERT INTO child VALUES (1, 1)", "23503");
    }

    @Test void fkAllowedAfterParentReinserted(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, pid INT REFERENCES parent(id))");
        db.execute("INSERT INTO parent VALUES (1)");
        db.execute("DELETE FROM parent WHERE id = 1");
        db.execute("INSERT INTO parent VALUES (1)");
        // Parent re-inserted — FK must succeed
        db.execute("INSERT INTO child VALUES (1, 1)");
        db.assertQuery("SELECT id, pid FROM child ORDER BY id");
    }

    // ── Self-referential FK ───────────────────────────────────────────────────

    @Test void selfReferentialFk(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE node (id INT PRIMARY KEY, parent_id INT REFERENCES node(id))");
        db.execute("INSERT INTO node VALUES (1, NULL)");  // root
        db.execute("INSERT INTO node VALUES (2, 1)");
        db.execute("INSERT INTO node VALUES (3, 1)");
        db.execute("INSERT INTO node VALUES (4, 2)");
        db.assertQuery("SELECT id, parent_id FROM node ORDER BY id");
    }

    @Test void selfReferentialFkViolation(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE node (id INT PRIMARY KEY, parent_id INT REFERENCES node(id))");
        db.assertError("INSERT INTO node VALUES (1, 99)", "23503");
    }

    // ── Named constraints ─────────────────────────────────────────────────────

    @Test void namedFkConstraintInErrorMessage(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, pid INT, CONSTRAINT fk_child_parent FOREIGN KEY (pid) REFERENCES parent(id))");
        db.assertError("INSERT INTO child VALUES (1, 42)", "23503");
    }

    // ── Deferred behavior (NOT DEFERRABLE is default) ─────────────────────────

    @Test void fkCheckedImmediatelyByDefault(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
        db.execute("CREATE TABLE child  (id INT PRIMARY KEY, pid INT REFERENCES parent(id))");
        db.execute("INSERT INTO parent VALUES (1)");
        // Within a transaction, FK is checked immediately on each statement
        db.execute("BEGIN");
        db.assertError("INSERT INTO child VALUES (1, 99)", "23503");
        db.execute("ROLLBACK");
    }
}
