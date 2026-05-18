package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.QueryResult;
import org.pgjava.engine.Session;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 9b Priority 4: ON CONFLICT / Upsert.
 *
 * Tests cover:
 *   - ON CONFLICT DO NOTHING  (with and without explicit infer target)
 *   - ON CONFLICT (cols) DO UPDATE SET ...
 *   - DO UPDATE with EXCLUDED pseudo-table
 *   - DO UPDATE WHERE condition
 *   - ON CONFLICT ON CONSTRAINT name
 *   - RETURNING with ON CONFLICT
 *   - Multi-row insert with mixed conflict / no-conflict rows
 */
class Phase9bOnConflictTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("phase9b_oc_" + System.nanoTime());
        sess = db.openSession();
    }

    @AfterEach
    void tearDown() {
        sess.close();
    }

    private QueryResult q(String sql) throws SQLException { return sess.execute(sql); }
    private void exec(String sql)       throws SQLException { sess.execute(sql); }

    // =========================================================================
    // DO NOTHING
    // =========================================================================

    @Test void doNothingSkipsConflict() throws SQLException {
        exec("CREATE TABLE t (id int PRIMARY KEY, val text)");
        exec("INSERT INTO t VALUES (1, 'a')");
        exec("INSERT INTO t VALUES (1, 'b') ON CONFLICT DO NOTHING");
        QueryResult r = q("SELECT val FROM t WHERE id = 1");
        assertEquals(1, r.rows().size());
        assertEquals("a", r.rows().get(0)[0].toString()); // original value preserved
    }

    @Test void doNothingWithInferTarget() throws SQLException {
        exec("CREATE TABLE t (id int, val text, UNIQUE (id))");
        exec("INSERT INTO t VALUES (1, 'first')");
        exec("INSERT INTO t VALUES (1, 'second') ON CONFLICT (id) DO NOTHING");
        QueryResult r = q("SELECT COUNT(*) FROM t");
        assertEquals("1", r.rows().get(0)[0].toString());
    }

    @Test void doNothingNoConflict() throws SQLException {
        exec("CREATE TABLE t (id int PRIMARY KEY, val text)");
        exec("INSERT INTO t VALUES (1, 'a')");
        exec("INSERT INTO t VALUES (2, 'b') ON CONFLICT DO NOTHING");
        QueryResult r = q("SELECT COUNT(*) FROM t");
        assertEquals("2", r.rows().get(0)[0].toString()); // row was inserted
    }

    // =========================================================================
    // DO UPDATE (basic upsert)
    // =========================================================================

    @Test void doUpdateSetsNewValue() throws SQLException {
        exec("CREATE TABLE t (id int PRIMARY KEY, val text)");
        exec("INSERT INTO t VALUES (1, 'original')");
        exec("INSERT INTO t VALUES (1, 'updated') ON CONFLICT (id) DO UPDATE SET val = EXCLUDED.val");
        QueryResult r = q("SELECT val FROM t WHERE id = 1");
        assertEquals("updated", r.rows().get(0)[0].toString());
    }

    @Test void doUpdateMultipleColumns() throws SQLException {
        exec("CREATE TABLE t (id int PRIMARY KEY, a int, b int)");
        exec("INSERT INTO t VALUES (1, 10, 20)");
        exec("INSERT INTO t VALUES (1, 99, 88) ON CONFLICT (id) DO UPDATE SET a = EXCLUDED.a, b = EXCLUDED.b");
        QueryResult r = q("SELECT a, b FROM t WHERE id = 1");
        assertEquals("99", r.rows().get(0)[0].toString());
        assertEquals("88", r.rows().get(0)[1].toString());
    }

    @Test void doUpdateExpressionWithExisting() throws SQLException {
        exec("CREATE TABLE t (id int PRIMARY KEY, cnt int)");
        exec("INSERT INTO t VALUES (1, 5)");
        // Increment existing value
        exec("INSERT INTO t VALUES (1, 3) ON CONFLICT (id) DO UPDATE SET cnt = t.cnt + EXCLUDED.cnt");
        QueryResult r = q("SELECT cnt FROM t WHERE id = 1");
        assertEquals("8", r.rows().get(0)[0].toString()); // 5 + 3
    }

    @Test void doUpdateInsertWhenNoConflict() throws SQLException {
        exec("CREATE TABLE t (id int PRIMARY KEY, val text)");
        exec("INSERT INTO t VALUES (1, 'a') ON CONFLICT (id) DO UPDATE SET val = EXCLUDED.val");
        QueryResult r = q("SELECT val FROM t");
        assertEquals(1, r.rows().size());
        assertEquals("a", r.rows().get(0)[0].toString());
    }

    // =========================================================================
    // DO UPDATE WHERE condition
    // =========================================================================

    @Test void doUpdateWhereConditionMet() throws SQLException {
        exec("CREATE TABLE t (id int PRIMARY KEY, score int)");
        exec("INSERT INTO t VALUES (1, 50)");
        // Only update if new score is higher
        exec("INSERT INTO t VALUES (1, 80) ON CONFLICT (id) DO UPDATE SET score = EXCLUDED.score WHERE EXCLUDED.score > t.score");
        QueryResult r = q("SELECT score FROM t WHERE id = 1");
        assertEquals("80", r.rows().get(0)[0].toString());
    }

    @Test void doUpdateWhereConditionNotMet() throws SQLException {
        exec("CREATE TABLE t (id int PRIMARY KEY, score int)");
        exec("INSERT INTO t VALUES (1, 50)");
        // New score is lower — WHERE not satisfied, row unchanged
        exec("INSERT INTO t VALUES (1, 30) ON CONFLICT (id) DO UPDATE SET score = EXCLUDED.score WHERE EXCLUDED.score > t.score");
        QueryResult r = q("SELECT score FROM t WHERE id = 1");
        assertEquals("50", r.rows().get(0)[0].toString()); // unchanged
    }

    // =========================================================================
    // ON CONFLICT ON CONSTRAINT name
    // =========================================================================

    @Test void onConflictOnConstraintName() throws SQLException {
        exec("CREATE TABLE t (id int CONSTRAINT pk_t PRIMARY KEY, val text)");
        exec("INSERT INTO t VALUES (1, 'a')");
        exec("INSERT INTO t VALUES (1, 'b') ON CONFLICT ON CONSTRAINT pk_t DO UPDATE SET val = EXCLUDED.val");
        QueryResult r = q("SELECT val FROM t WHERE id = 1");
        assertEquals("b", r.rows().get(0)[0].toString());
    }

    // =========================================================================
    // RETURNING with ON CONFLICT
    // =========================================================================

    @Test void doNothingReturningEmpty() throws SQLException {
        exec("CREATE TABLE t (id int PRIMARY KEY, val text)");
        exec("INSERT INTO t VALUES (1, 'a')");
        QueryResult r = q("INSERT INTO t VALUES (1, 'b') ON CONFLICT DO NOTHING RETURNING id");
        // DO NOTHING with conflict → no rows returned
        assertEquals(0, r.rows().size());
    }

    @Test void doUpdateReturning() throws SQLException {
        exec("CREATE TABLE t (id int PRIMARY KEY, val text)");
        exec("INSERT INTO t VALUES (1, 'old')");
        QueryResult r = q("INSERT INTO t VALUES (1, 'new') ON CONFLICT (id) DO UPDATE SET val = EXCLUDED.val RETURNING id, val");
        assertTrue(r.isQuery());
        assertEquals(1, r.rows().size());
        assertEquals("1",   r.rows().get(0)[0].toString());
        assertEquals("new", r.rows().get(0)[1].toString());
    }

    @Test void doUpdateReturningStar() throws SQLException {
        exec("CREATE TABLE t (id int PRIMARY KEY, x int)");
        exec("INSERT INTO t VALUES (1, 10)");
        QueryResult r = q("INSERT INTO t VALUES (1, 20) ON CONFLICT (id) DO UPDATE SET x = EXCLUDED.x RETURNING *");
        assertTrue(r.isQuery());
        assertEquals(1, r.rows().size());
        assertEquals("1",  r.rows().get(0)[0].toString());
        assertEquals("20", r.rows().get(0)[1].toString());
    }

    // =========================================================================
    // Multi-row insert with mixed conflicts
    // =========================================================================

    @Test void multiRowMixedConflicts() throws SQLException {
        exec("CREATE TABLE t (id int PRIMARY KEY, val text)");
        exec("INSERT INTO t VALUES (1, 'a'), (3, 'c')");
        // Row 1 conflicts, row 2 is new, row 3 conflicts
        exec("INSERT INTO t VALUES (1, 'x'), (2, 'b'), (3, 'y') ON CONFLICT DO NOTHING");
        QueryResult r = q("SELECT id FROM t ORDER BY id");
        assertEquals(3, r.rows().size()); // 1, 2, 3 — new row inserted, conflicts skipped
        assertEquals("1", r.rows().get(0)[0].toString());
        assertEquals("2", r.rows().get(1)[0].toString());
        assertEquals("3", r.rows().get(2)[0].toString());
        // Originals preserved
        assertEquals("a", q("SELECT val FROM t WHERE id = 1").rows().get(0)[0].toString());
        assertEquals("c", q("SELECT val FROM t WHERE id = 3").rows().get(0)[0].toString());
    }

    @Test void multiRowDoUpdate() throws SQLException {
        exec("CREATE TABLE t (id int PRIMARY KEY, cnt int)");
        exec("INSERT INTO t VALUES (1, 0), (2, 0)");
        exec("INSERT INTO t VALUES (1, 5), (2, 10) ON CONFLICT (id) DO UPDATE SET cnt = EXCLUDED.cnt");
        QueryResult r = q("SELECT cnt FROM t ORDER BY id");
        assertEquals(2, r.rows().size());
        assertEquals("5",  r.rows().get(0)[0].toString());
        assertEquals("10", r.rows().get(1)[0].toString());
    }
}
