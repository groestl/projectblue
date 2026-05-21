package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;

/**
 * Golden tests for PostgreSQL sequences comparing pgjava against real PostgreSQL.
 *
 * <p>Covers: CREATE SEQUENCE options, nextval/currval/setval, SERIAL/BIGSERIAL columns,
 * sequence reset, lastval(), sequence in DEFAULT, overflow errors, and currval-before-nextval.
 *
 * <p>The per-session currval isolation (ThreadLocal fix) is not directly testable via
 * DualExecutor since it requires concurrent sessions; it is covered by SequenceSessionIsolationTest.
 */
@ExtendWith(GoldenExtension.class)
class GoldenSequenceTest {

    // ── Basic CREATE SEQUENCE + nextval/currval ───────────────────────────────

    @Test void nextvalAdvances(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE s START 1 INCREMENT 1");
        db.assertQuery("SELECT nextval('s'), nextval('s'), nextval('s')");
    }

    @Test void nextvalWithIncrement(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE s START 10 INCREMENT 5");
        db.assertQuery("SELECT nextval('s'), nextval('s'), nextval('s')");
    }

    @Test void nextvalDescending(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE s START 100 INCREMENT -3 MINVALUE 1 MAXVALUE 100");
        db.assertQuery("SELECT nextval('s'), nextval('s'), nextval('s')");
    }

    @Test void currvalAfterNextval(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE s START 1");
        db.execute("SELECT nextval('s')");
        db.assertQuery("SELECT currval('s')");
    }

    @Test void currvalMatchesLastNextval(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE s START 5 INCREMENT 2");
        db.execute("SELECT nextval('s')");
        db.execute("SELECT nextval('s')");
        db.execute("SELECT nextval('s')");
        // currval must equal the last nextval (9)
        db.assertQuery("SELECT currval('s')");
    }

    @Test void setvalThenCurrval(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE s START 1");
        db.execute("SELECT setval('s', 42)");
        db.assertQuery("SELECT currval('s')");
    }

    @Test void setvalThenNextval(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE s START 1");
        db.execute("SELECT setval('s', 100)");
        // setval(seq, n) — next nextval returns n+1
        db.assertQuery("SELECT nextval('s')");
    }

    @Test void setvalWithIsCalledFalse(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE s START 1");
        // setval(seq, n, false) — next nextval returns n (is_called=false)
        db.execute("SELECT setval('s', 50, false)");
        db.assertQuery("SELECT nextval('s')");
    }

    @Test void lastvalAfterNextval(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE s1 START 10");
        db.execute("CREATE SEQUENCE s2 START 20");
        db.execute("SELECT nextval('s1')");
        db.execute("SELECT nextval('s2')");
        // lastval() returns value from most recently advanced sequence in this session
        db.assertQuery("SELECT lastval()");
    }

    // ── SERIAL / BIGSERIAL ────────────────────────────────────────────────────

    @Test void serialColumn(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id SERIAL, name TEXT)");
        db.execute("INSERT INTO t (name) VALUES ('alice'), ('bob'), ('carol')");
        db.assertQuery("SELECT id, name FROM t ORDER BY id");
    }

    @Test void bigserialColumn(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id BIGSERIAL, val INT)");
        db.execute("INSERT INTO t (val) VALUES (10), (20), (30)");
        db.assertQuery("SELECT id, val FROM t ORDER BY id");
    }

    @Test void serialCurrval(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id SERIAL, name TEXT)");
        db.execute("INSERT INTO t (name) VALUES ('x')");
        // pg_get_serial_sequence returns the backing sequence name
        db.assertQuery("SELECT currval(pg_get_serial_sequence('t', 'id'))");
    }

    @Test void serialMultiInsertPreservesOrder(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id SERIAL PRIMARY KEY, val TEXT)");
        db.execute("INSERT INTO t (val) VALUES ('a')");
        db.execute("INSERT INTO t (val) VALUES ('b')");
        db.execute("INSERT INTO t (val) VALUES ('c')");
        db.assertQuery("SELECT id, val FROM t ORDER BY id");
    }

    // ── Sequence in DEFAULT ───────────────────────────────────────────────────

    @Test void sequenceAsDefault(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE seq START 100 INCREMENT 10");
        db.execute("CREATE TABLE t (id INT DEFAULT nextval('seq'), name TEXT)");
        db.execute("INSERT INTO t (name) VALUES ('a'), ('b'), ('c')");
        db.assertQuery("SELECT id, name FROM t ORDER BY id");
    }

    // ── RESTART / RESET ───────────────────────────────────────────────────────

    @Test void alterSequenceRestart(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE s START 1");
        db.execute("SELECT nextval('s'), nextval('s'), nextval('s')");
        db.execute("ALTER SEQUENCE s RESTART WITH 1");
        db.assertQuery("SELECT nextval('s')");
    }

    // ── Sequence metadata ─────────────────────────────────────────────────────

    @Test void sequenceSelectStar(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE s START 5 INCREMENT 2 MINVALUE 1 MAXVALUE 1000");
        // Query sequence metadata via pg_sequences view
        db.assertCatalogQuery("""
                SELECT sequence_name, start_value, increment, minimum_value, maximum_value, cycle_option
                FROM information_schema.sequences
                WHERE sequence_name = 's'
                """);
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test void currvalBeforeNextvalErrors(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE s START 1");
        db.assertError("SELECT currval('s')", "55000");
    }

    @Test void nextvalNonExistentSequenceErrors(DualExecutor db) throws Exception {
        db.assertError("SELECT nextval('no_such_seq')", "42P01");
    }

    @Test void sequenceOverflowErrors(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE s START 1 MAXVALUE 3 NO CYCLE");
        db.execute("SELECT nextval('s'), nextval('s'), nextval('s')");
        db.assertError("SELECT nextval('s')", "2200H");
    }

    @Test void createDuplicateSequenceErrors(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE s START 1");
        db.assertError("CREATE SEQUENCE s START 1", "42P07");
    }

    // ── Cycle ─────────────────────────────────────────────────────────────────

    @Test void cycleWrapsAround(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE s START 1 MAXVALUE 3 CYCLE MINVALUE 1");
        db.assertQuery("SELECT nextval('s'), nextval('s'), nextval('s'), nextval('s'), nextval('s')");
    }
}
