package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;

/**
 * Golden tests for PostgreSQL triggers comparing pgjava against real PostgreSQL.
 *
 * <p>Covers: BEFORE/AFTER ROW triggers for INSERT/UPDATE/DELETE,
 * statement-level triggers, trigger suppression (RETURN NULL), NEW/OLD,
 * conditional triggers (WHEN clause), trigger firing order, and DROP TRIGGER.
 */
@ExtendWith(GoldenExtension.class)
class GoldenTriggerTest {

    // ── AFTER INSERT trigger ──────────────────────────────────────────────────

    @Test void afterInsertTrigger(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, val TEXT)");
        db.execute("CREATE TABLE t_log (op TEXT, id INT, val TEXT)");
        db.execute("""
                CREATE FUNCTION log_insert() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO t_log VALUES ('INSERT', NEW.id, NEW.val);
                    RETURN NEW;
                END;
                $$
                """);
        db.execute("CREATE TRIGGER t_after_insert AFTER INSERT ON t FOR EACH ROW EXECUTE FUNCTION log_insert()");
        db.execute("INSERT INTO t VALUES (1, 'alice'), (2, 'bob')");
        db.assertQuery("SELECT op, id, val FROM t_log ORDER BY id");
    }

    // ── BEFORE INSERT trigger (modify NEW) ────────────────────────────────────

    @Test void beforeInsertModifiesNew(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, name TEXT)");
        db.execute("""
                CREATE FUNCTION upper_name() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    NEW.name := upper(NEW.name);
                    RETURN NEW;
                END;
                $$
                """);
        db.execute("CREATE TRIGGER t_before_insert BEFORE INSERT ON t FOR EACH ROW EXECUTE FUNCTION upper_name()");
        db.execute("INSERT INTO t VALUES (1, 'alice'), (2, 'bob')");
        db.assertQuery("SELECT id, name FROM t ORDER BY id");
    }

    // ── BEFORE INSERT suppress (RETURN NULL) ─────────────────────────────────

    @Test void beforeInsertSuppressRow(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, val TEXT)");
        db.execute("""
                CREATE FUNCTION suppress_even() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.id % 2 = 0 THEN
                        RETURN NULL;  -- suppress even-ID rows
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        db.execute("CREATE TRIGGER t_suppress BEFORE INSERT ON t FOR EACH ROW EXECUTE FUNCTION suppress_even()");
        db.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b'), (3, 'c'), (4, 'd')");
        db.assertQuery("SELECT id, val FROM t ORDER BY id");
    }

    // ── AFTER UPDATE trigger ──────────────────────────────────────────────────

    @Test void afterUpdateTrigger(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, val INT)");
        db.execute("CREATE TABLE t_log (id INT, old_val INT, new_val INT)");
        db.execute("""
                CREATE FUNCTION log_update() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO t_log VALUES (NEW.id, OLD.val, NEW.val);
                    RETURN NEW;
                END;
                $$
                """);
        db.execute("CREATE TRIGGER t_after_upd AFTER UPDATE ON t FOR EACH ROW EXECUTE FUNCTION log_update()");
        db.execute("INSERT INTO t VALUES (1, 10), (2, 20)");
        db.execute("UPDATE t SET val = val + 5");
        db.assertQuery("SELECT id, old_val, new_val FROM t_log ORDER BY id");
    }

    // ── BEFORE UPDATE trigger (conditional) ──────────────────────────────────

    @Test void beforeUpdateConditional(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, val INT, updated_at TIMESTAMPTZ)");
        db.execute("""
                CREATE FUNCTION stamp_update() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    NEW.updated_at := '2024-01-01 12:00:00+00'::timestamptz;
                    RETURN NEW;
                END;
                $$
                """);
        db.execute("CREATE TRIGGER t_stamp BEFORE UPDATE ON t FOR EACH ROW EXECUTE FUNCTION stamp_update()");
        db.execute("INSERT INTO t VALUES (1, 10, NULL), (2, 20, NULL)");
        db.execute("UPDATE t SET val = 99 WHERE id = 1");
        // Only row 1 should have updated_at set
        db.assertQuery("SELECT id, val, updated_at IS NOT NULL AS has_ts FROM t ORDER BY id");
    }

    // ── AFTER DELETE trigger ──────────────────────────────────────────────────

    @Test void afterDeleteTrigger(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, name TEXT)");
        db.execute("CREATE TABLE deleted_log (id INT, name TEXT)");
        db.execute("""
                CREATE FUNCTION log_delete() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO deleted_log VALUES (OLD.id, OLD.name);
                    RETURN OLD;
                END;
                $$
                """);
        db.execute("CREATE TRIGGER t_after_del AFTER DELETE ON t FOR EACH ROW EXECUTE FUNCTION log_delete()");
        db.execute("INSERT INTO t VALUES (1, 'x'), (2, 'y'), (3, 'z')");
        db.execute("DELETE FROM t WHERE id IN (1, 3)");
        db.assertQuery("SELECT id, name FROM deleted_log ORDER BY id");
    }

    // ── WHEN clause ───────────────────────────────────────────────────────────

    @Test void triggerWithWhenClause(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, score INT)");
        db.execute("CREATE TABLE high_scores (id INT, score INT)");
        db.execute("""
                CREATE FUNCTION record_high() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO high_scores VALUES (NEW.id, NEW.score);
                    RETURN NEW;
                END;
                $$
                """);
        db.execute("CREATE TRIGGER t_high AFTER INSERT ON t FOR EACH ROW WHEN (NEW.score > 90) EXECUTE FUNCTION record_high()");
        db.execute("INSERT INTO t VALUES (1, 85), (2, 95), (3, 70), (4, 92)");
        db.assertQuery("SELECT id, score FROM high_scores ORDER BY id");
    }

    // ── Statement-level trigger ───────────────────────────────────────────────

    @Test void statementLevelTrigger(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT)");
        db.execute("CREATE TABLE stmt_log (event TEXT)");
        db.execute("""
                CREATE FUNCTION log_statement() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO stmt_log VALUES (TG_OP);
                    RETURN NULL;
                END;
                $$
                """);
        db.execute("CREATE TRIGGER t_stmt AFTER INSERT ON t FOR EACH STATEMENT EXECUTE FUNCTION log_statement()");
        db.execute("INSERT INTO t VALUES (1), (2), (3)");  // one statement = one trigger fire
        db.execute("INSERT INTO t VALUES (4)");              // another statement = another fire
        db.assertQuery("SELECT count(*) FROM stmt_log");
    }

    // ── Multiple triggers — firing order ──────────────────────────────────────

    @Test void multipleTriggersFiringOrder(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, val INT)");
        db.execute("CREATE TABLE log_order (step INT, msg TEXT)");
        db.execute("CREATE SEQUENCE step_seq START 1");
        db.execute("""
                CREATE FUNCTION log_first() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO log_order VALUES (nextval('step_seq'), 'first');
                    RETURN NEW;
                END;
                $$
                """);
        db.execute("""
                CREATE FUNCTION log_second() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO log_order VALUES (nextval('step_seq'), 'second');
                    RETURN NEW;
                END;
                $$
                """);
        // Alphabetical name order: aaa fires before bbb
        db.execute("CREATE TRIGGER aaa_trigger AFTER INSERT ON t FOR EACH ROW EXECUTE FUNCTION log_first()");
        db.execute("CREATE TRIGGER bbb_trigger AFTER INSERT ON t FOR EACH ROW EXECUTE FUNCTION log_second()");
        db.execute("INSERT INTO t VALUES (1, 10)");
        db.assertQuery("SELECT msg FROM log_order ORDER BY step");
    }

    // ── Trigger on view (INSTEAD OF) ─────────────────────────────────────────

    @Test void insteadOfInsertTrigger(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE base_t (id INT PRIMARY KEY, name TEXT, active BOOLEAN)");
        db.execute("CREATE VIEW active_v AS SELECT id, name FROM base_t WHERE active");
        db.execute("""
                CREATE FUNCTION insert_into_base() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO base_t VALUES (NEW.id, NEW.name, true);
                    RETURN NEW;
                END;
                $$
                """);
        db.execute("CREATE TRIGGER v_insert INSTEAD OF INSERT ON active_v FOR EACH ROW EXECUTE FUNCTION insert_into_base()");
        db.execute("INSERT INTO active_v VALUES (1, 'alice'), (2, 'bob')");
        db.assertQuery("SELECT id, name, active FROM base_t ORDER BY id");
    }

    // ── DROP TRIGGER ──────────────────────────────────────────────────────────

    @Test void dropTrigger(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT)");
        db.execute("CREATE TABLE log_t (id INT)");
        db.execute("""
                CREATE FUNCTION log_ins() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO log_t VALUES (NEW.id);
                    RETURN NEW;
                END;
                $$
                """);
        db.execute("CREATE TRIGGER t_ins AFTER INSERT ON t FOR EACH ROW EXECUTE FUNCTION log_ins()");
        db.execute("INSERT INTO t VALUES (1)");
        db.execute("DROP TRIGGER t_ins ON t");
        db.execute("INSERT INTO t VALUES (2)");
        // Only row 1 should be in log (trigger was dropped before row 2 insert)
        db.assertQuery("SELECT id FROM log_t ORDER BY id");
    }

    // ── Trigger raises exception ──────────────────────────────────────────────

    @Test void triggerRaisesException(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, val INT)");
        db.execute("""
                CREATE FUNCTION reject_negative() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.val < 0 THEN
                        RAISE EXCEPTION 'val must be non-negative';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        db.execute("CREATE TRIGGER t_check BEFORE INSERT ON t FOR EACH ROW EXECUTE FUNCTION reject_negative()");
        db.execute("INSERT INTO t VALUES (1, 10)");
        db.assertError("INSERT INTO t VALUES (2, -5)", "P0001");
        db.assertQuery("SELECT id FROM t ORDER BY id");
    }
}
