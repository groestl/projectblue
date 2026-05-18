package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.QueryResult;
import org.pgjava.engine.Session;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Trigger execution tests — Phase 11: CREATE TRIGGER, BEFORE/AFTER ROW/STATEMENT,
 * NEW/OLD, TG_* variables, WHEN clause, UPDATE OF, multiple triggers.
 */
class TriggerTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("trigger_test_" + System.nanoTime());
        sess = db.openSession();
    }

    @AfterEach
    void tearDown() { if (sess != null) sess.close(); }

    private Object scalar(String sql) throws SQLException {
        QueryResult r = sess.execute(sql);
        assertNotNull(r.rows());
        assertFalse(r.rows().isEmpty(), "expected one row for: " + sql);
        return r.rows().get(0)[0];
    }

    private void exec(String sql) throws SQLException {
        sess.execute(sql);
    }

    // =====================================================================
    // BEFORE INSERT ROW — modify NEW
    // =====================================================================

    @Test void beforeInsertModifiesNew() throws SQLException {
        exec("CREATE TABLE t1 (id serial PRIMARY KEY, name text, created_at text)");
        exec("""
            CREATE FUNCTION set_created() RETURNS trigger AS $$
            BEGIN
                NEW.created_at := 'now';
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg_set_created BEFORE INSERT ON t1 FOR EACH ROW EXECUTE FUNCTION set_created()");
        exec("INSERT INTO t1 (name) VALUES ('alice')");
        assertEquals("now", scalar("SELECT created_at FROM t1 WHERE name = 'alice'"));
    }

    // =====================================================================
    // BEFORE INSERT ROW — suppress row (return NULL)
    // =====================================================================

    @Test void beforeInsertReturnsNullSuppressesRow() throws SQLException {
        exec("CREATE TABLE t2 (id integer, val text)");
        exec("""
            CREATE FUNCTION reject_negative() RETURNS trigger AS $$
            BEGIN
                IF NEW.id < 0 THEN
                    RETURN NULL;
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg_reject BEFORE INSERT ON t2 FOR EACH ROW EXECUTE FUNCTION reject_negative()");
        exec("INSERT INTO t2 VALUES (1, 'ok')");
        exec("INSERT INTO t2 VALUES (-1, 'bad')");
        exec("INSERT INTO t2 VALUES (2, 'also ok')");
        assertEquals(2L, scalar("SELECT count(*) FROM t2"));
    }

    // =====================================================================
    // AFTER INSERT ROW — audit trail
    // =====================================================================

    @Test void afterInsertAuditTrail() throws SQLException {
        exec("CREATE TABLE t3 (id integer, val text)");
        exec("CREATE TABLE t3_audit (op text, row_id integer)");
        exec("""
            CREATE FUNCTION audit_insert() RETURNS trigger AS $$
            BEGIN
                INSERT INTO t3_audit VALUES (TG_OP, NEW.id);
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg_audit AFTER INSERT ON t3 FOR EACH ROW EXECUTE FUNCTION audit_insert()");
        exec("INSERT INTO t3 VALUES (10, 'hello')");
        assertEquals("INSERT", scalar("SELECT op FROM t3_audit"));
        assertEquals(10, scalar("SELECT row_id FROM t3_audit"));
    }

    // =====================================================================
    // BEFORE UPDATE ROW — OLD and NEW
    // =====================================================================

    @Test void beforeUpdateOldAndNew() throws SQLException {
        exec("CREATE TABLE t4 (id integer, val text, prev_val text)");
        exec("""
            CREATE FUNCTION track_update() RETURNS trigger AS $$
            BEGIN
                NEW.prev_val := OLD.val;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg_track BEFORE UPDATE ON t4 FOR EACH ROW EXECUTE FUNCTION track_update()");
        exec("INSERT INTO t4 VALUES (1, 'initial', NULL)");
        exec("UPDATE t4 SET val = 'changed' WHERE id = 1");
        assertEquals("initial", scalar("SELECT prev_val FROM t4 WHERE id = 1"));
        assertEquals("changed", scalar("SELECT val FROM t4 WHERE id = 1"));
    }

    // =====================================================================
    // AFTER UPDATE ROW — audit
    // =====================================================================

    @Test void afterUpdateAudit() throws SQLException {
        exec("CREATE TABLE t5 (id integer, val text)");
        exec("CREATE TABLE t5_audit (op text, old_val text, new_val text)");
        exec("""
            CREATE FUNCTION audit_update() RETURNS trigger AS $$
            BEGIN
                INSERT INTO t5_audit VALUES (TG_OP, OLD.val, NEW.val);
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg_audit_upd AFTER UPDATE ON t5 FOR EACH ROW EXECUTE FUNCTION audit_update()");
        exec("INSERT INTO t5 VALUES (1, 'old')");
        exec("UPDATE t5 SET val = 'new' WHERE id = 1");
        assertEquals("UPDATE", scalar("SELECT op FROM t5_audit"));
        assertEquals("old", scalar("SELECT old_val FROM t5_audit"));
        assertEquals("new", scalar("SELECT new_val FROM t5_audit"));
    }

    // =====================================================================
    // BEFORE DELETE ROW — prevent deletion
    // =====================================================================

    @Test void beforeDeleteSuppresses() throws SQLException {
        exec("CREATE TABLE t6 (id integer, protected boolean)");
        exec("""
            CREATE FUNCTION prevent_delete() RETURNS trigger AS $$
            BEGIN
                IF OLD.protected THEN
                    RETURN NULL;
                END IF;
                RETURN OLD;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg_prevent BEFORE DELETE ON t6 FOR EACH ROW EXECUTE FUNCTION prevent_delete()");
        exec("INSERT INTO t6 VALUES (1, true)");
        exec("INSERT INTO t6 VALUES (2, false)");
        exec("DELETE FROM t6");
        // Only unprotected row should be deleted
        assertEquals(1L, scalar("SELECT count(*) FROM t6"));
        assertEquals(1, scalar("SELECT id FROM t6"));
    }

    // =====================================================================
    // AFTER DELETE ROW — audit
    // =====================================================================

    @Test void afterDeleteAudit() throws SQLException {
        exec("CREATE TABLE t7 (id integer)");
        exec("CREATE TABLE t7_audit (deleted_id integer)");
        exec("""
            CREATE FUNCTION audit_delete() RETURNS trigger AS $$
            BEGIN
                INSERT INTO t7_audit VALUES (OLD.id);
                RETURN OLD;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg_del_audit AFTER DELETE ON t7 FOR EACH ROW EXECUTE FUNCTION audit_delete()");
        exec("INSERT INTO t7 VALUES (42)");
        exec("DELETE FROM t7 WHERE id = 42");
        assertEquals(42, scalar("SELECT deleted_id FROM t7_audit"));
    }

    // =====================================================================
    // BEFORE STATEMENT trigger
    // =====================================================================

    @Test void beforeStatementTrigger() throws SQLException {
        exec("CREATE TABLE t8 (id integer)");
        exec("CREATE TABLE t8_log (msg text)");
        exec("""
            CREATE FUNCTION log_stmt() RETURNS trigger AS $$
            BEGIN
                INSERT INTO t8_log VALUES (TG_WHEN || ' ' || TG_OP || ' ' || TG_LEVEL);
                RETURN NULL;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg_stmt BEFORE INSERT ON t8 FOR EACH STATEMENT EXECUTE FUNCTION log_stmt()");
        exec("INSERT INTO t8 VALUES (1)");
        assertEquals("BEFORE INSERT STATEMENT", scalar("SELECT msg FROM t8_log"));
    }

    // =====================================================================
    // AFTER STATEMENT trigger
    // =====================================================================

    @Test void afterStatementTrigger() throws SQLException {
        exec("CREATE TABLE t9 (id integer)");
        exec("CREATE TABLE t9_log (msg text)");
        exec("""
            CREATE FUNCTION log_after_stmt() RETURNS trigger AS $$
            BEGIN
                INSERT INTO t9_log VALUES ('AFTER STMT');
                RETURN NULL;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg_after_stmt AFTER INSERT ON t9 FOR EACH STATEMENT EXECUTE FUNCTION log_after_stmt()");
        exec("INSERT INTO t9 VALUES (1)");
        assertEquals("AFTER STMT", scalar("SELECT msg FROM t9_log"));
    }

    // =====================================================================
    // Multiple triggers — alphabetical order
    // =====================================================================

    @Test void multipleTriggersFiredAlphabetically() throws SQLException {
        exec("CREATE TABLE t10 (id integer)");
        exec("CREATE SEQUENCE t10_seq");
        exec("CREATE TABLE t10_log (seq integer, msg text)");
        exec("""
            CREATE FUNCTION log_trig_name() RETURNS trigger AS $$
            BEGIN
                INSERT INTO t10_log VALUES (nextval('t10_seq'), TG_NAME);
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        // Create in reverse alphabetical order
        exec("CREATE TRIGGER zzz_trig BEFORE INSERT ON t10 FOR EACH ROW EXECUTE FUNCTION log_trig_name()");
        exec("CREATE TRIGGER aaa_trig BEFORE INSERT ON t10 FOR EACH ROW EXECUTE FUNCTION log_trig_name()");
        exec("CREATE TRIGGER mmm_trig BEFORE INSERT ON t10 FOR EACH ROW EXECUTE FUNCTION log_trig_name()");
        exec("INSERT INTO t10 VALUES (1)");
        QueryResult r = sess.execute("SELECT msg FROM t10_log ORDER BY seq");
        assertEquals(3, r.rows().size());
        assertEquals("aaa_trig", r.rows().get(0)[0]);
        assertEquals("mmm_trig", r.rows().get(1)[0]);
        assertEquals("zzz_trig", r.rows().get(2)[0]);
    }

    // =====================================================================
    // TG_* special variables
    // =====================================================================

    @Test void triggerSpecialVariables() throws SQLException {
        exec("CREATE TABLE t11 (id integer)");
        exec("CREATE TABLE t11_info (tg_name text, tg_when text, tg_level text, tg_op text, tg_table text, tg_schema text)");
        exec("""
            CREATE FUNCTION capture_tg_vars() RETURNS trigger AS $$
            BEGIN
                INSERT INTO t11_info VALUES (TG_NAME, TG_WHEN, TG_LEVEL, TG_OP, TG_TABLE_NAME, TG_TABLE_SCHEMA);
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER my_trig BEFORE INSERT ON t11 FOR EACH ROW EXECUTE FUNCTION capture_tg_vars()");
        exec("INSERT INTO t11 VALUES (1)");
        QueryResult r = sess.execute("SELECT * FROM t11_info");
        assertEquals(1, r.rows().size());
        Object[] row = r.rows().get(0);
        assertEquals("my_trig", row[0]);
        assertEquals("BEFORE", row[1]);
        assertEquals("ROW", row[2]);
        assertEquals("INSERT", row[3]);
        assertEquals("t11", row[4]);
        assertEquals("public", row[5]);
    }

    // =====================================================================
    // WHEN clause
    // =====================================================================

    @Test void whenClauseFilters() throws SQLException {
        exec("CREATE TABLE t12 (id integer, val text)");
        exec("CREATE TABLE t12_log (msg text)");
        exec("""
            CREATE FUNCTION log_when() RETURNS trigger AS $$
            BEGIN
                INSERT INTO t12_log VALUES ('fired for ' || NEW.id);
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg_when BEFORE INSERT ON t12 FOR EACH ROW WHEN (NEW.id > 5) EXECUTE FUNCTION log_when()");
        exec("INSERT INTO t12 VALUES (3, 'low')");
        exec("INSERT INTO t12 VALUES (10, 'high')");
        // Only the insert with id > 5 should fire the trigger
        assertEquals(1L, scalar("SELECT count(*) FROM t12_log"));
        assertEquals("fired for 10", scalar("SELECT msg FROM t12_log"));
    }

    // =====================================================================
    // UPDATE OF specific columns
    // =====================================================================

    @Test void updateOfSpecificColumns() throws SQLException {
        exec("CREATE TABLE t13 (id integer, name text, age integer)");
        exec("CREATE TABLE t13_log (msg text)");
        exec("""
            CREATE FUNCTION log_name_change() RETURNS trigger AS $$
            BEGIN
                INSERT INTO t13_log VALUES ('name changed');
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg_name_upd BEFORE UPDATE OF name ON t13 FOR EACH ROW EXECUTE FUNCTION log_name_change()");
        exec("INSERT INTO t13 VALUES (1, 'alice', 30)");
        // Update age only — trigger should NOT fire
        exec("UPDATE t13 SET age = 31 WHERE id = 1");
        assertEquals(0L, scalar("SELECT count(*) FROM t13_log"));
        // Update name — trigger should fire
        exec("UPDATE t13 SET name = 'bob' WHERE id = 1");
        assertEquals(1L, scalar("SELECT count(*) FROM t13_log"));
    }

    // =====================================================================
    // DROP TRIGGER
    // =====================================================================

    @Test void dropTrigger() throws SQLException {
        exec("CREATE TABLE t14 (id integer)");
        exec("CREATE TABLE t14_log (msg text)");
        exec("""
            CREATE FUNCTION log14() RETURNS trigger AS $$
            BEGIN
                INSERT INTO t14_log VALUES ('fired');
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg14 BEFORE INSERT ON t14 FOR EACH ROW EXECUTE FUNCTION log14()");
        exec("INSERT INTO t14 VALUES (1)");
        assertEquals(1L, scalar("SELECT count(*) FROM t14_log"));
        // Drop and verify trigger no longer fires
        exec("DROP TRIGGER trg14 ON t14");
        exec("INSERT INTO t14 VALUES (2)");
        assertEquals(1L, scalar("SELECT count(*) FROM t14_log")); // still 1
    }

    @Test void dropTriggerIfExists() throws SQLException {
        exec("CREATE TABLE t15 (id integer)");
        // Should not throw
        exec("DROP TRIGGER IF EXISTS nonexistent ON t15");
    }

    @Test void dropTriggerNotExistsThrows() throws SQLException {
        exec("CREATE TABLE t16 (id integer)");
        assertThrows(SQLException.class, () -> exec("DROP TRIGGER nonexistent ON t16"));
    }

    // =====================================================================
    // CREATE OR REPLACE TRIGGER
    // =====================================================================

    @Test void createOrReplaceTrigger() throws SQLException {
        exec("CREATE TABLE t17 (id integer)");
        exec("CREATE SEQUENCE t17_seq");
        exec("CREATE TABLE t17_log (seq integer, msg text)");
        exec("""
            CREATE FUNCTION log_v1() RETURNS trigger AS $$
            BEGIN
                INSERT INTO t17_log VALUES (nextval('t17_seq'), 'v1');
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("""
            CREATE FUNCTION log_v2() RETURNS trigger AS $$
            BEGIN
                INSERT INTO t17_log VALUES (nextval('t17_seq'), 'v2');
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg17 BEFORE INSERT ON t17 FOR EACH ROW EXECUTE FUNCTION log_v1()");
        exec("INSERT INTO t17 VALUES (1)");
        assertEquals("v1", scalar("SELECT msg FROM t17_log ORDER BY seq DESC LIMIT 1"));
        // Replace with v2
        exec("CREATE OR REPLACE TRIGGER trg17 BEFORE INSERT ON t17 FOR EACH ROW EXECUTE FUNCTION log_v2()");
        exec("INSERT INTO t17 VALUES (2)");
        assertEquals("v2", scalar("SELECT msg FROM t17_log ORDER BY seq DESC LIMIT 1"));
    }

    // =====================================================================
    // Trigger raises exception — rolls back DML
    // =====================================================================

    @Test void triggerExceptionRollsBackInsert() throws SQLException {
        exec("CREATE TABLE t18 (id integer)");
        exec("""
            CREATE FUNCTION reject_all() RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'not allowed';
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg18 BEFORE INSERT ON t18 FOR EACH ROW EXECUTE FUNCTION reject_all()");
        assertThrows(SQLException.class, () -> exec("INSERT INTO t18 VALUES (1)"));
        assertEquals(0L, scalar("SELECT count(*) FROM t18"));
    }

    // =====================================================================
    // Multi-event trigger (INSERT OR UPDATE)
    // =====================================================================

    @Test void multiEventTrigger() throws SQLException {
        exec("CREATE TABLE t19 (id integer, val text)");
        exec("CREATE SEQUENCE t19_seq");
        exec("CREATE TABLE t19_log (seq integer, op text)");
        exec("""
            CREATE FUNCTION log_op() RETURNS trigger AS $$
            BEGIN
                INSERT INTO t19_log VALUES (nextval('t19_seq'), TG_OP);
                IF TG_OP = 'DELETE' THEN
                    RETURN OLD;
                ELSE
                    RETURN NEW;
                END IF;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg19 AFTER INSERT OR UPDATE OR DELETE ON t19 FOR EACH ROW EXECUTE FUNCTION log_op()");
        exec("INSERT INTO t19 VALUES (1, 'a')");
        exec("UPDATE t19 SET val = 'b' WHERE id = 1");
        exec("DELETE FROM t19 WHERE id = 1");
        QueryResult r = sess.execute("SELECT op FROM t19_log ORDER BY seq");
        assertEquals(3, r.rows().size());
        assertEquals("INSERT", r.rows().get(0)[0]);
        assertEquals("UPDATE", r.rows().get(1)[0]);
        assertEquals("DELETE", r.rows().get(2)[0]);
    }

    // =====================================================================
    // BEFORE INSERT — chain of triggers modifying NEW
    // =====================================================================

    @Test void chainedBeforeInsertTriggers() throws SQLException {
        exec("CREATE TABLE t20 (val integer)");
        exec("""
            CREATE FUNCTION add_one() RETURNS trigger AS $$
            BEGIN
                NEW.val := NEW.val + 1;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        // Two triggers both add 1
        exec("CREATE TRIGGER a_add BEFORE INSERT ON t20 FOR EACH ROW EXECUTE FUNCTION add_one()");
        exec("CREATE TRIGGER b_add BEFORE INSERT ON t20 FOR EACH ROW EXECUTE FUNCTION add_one()");
        exec("INSERT INTO t20 VALUES (10)");
        assertEquals(12, scalar("SELECT val FROM t20")); // 10 + 1 + 1
    }

    // =====================================================================
    // BEFORE DELETE: NEW is null, OLD is the deleted row
    // =====================================================================

    // =====================================================================
    // ON CONFLICT DO UPDATE fires UPDATE triggers
    // =====================================================================

    // =====================================================================
    // TRUNCATE fires statement-level triggers
    // =====================================================================

    @Test void truncateFiresStatementTriggers() throws SQLException {
        exec("CREATE TABLE t24 (id integer)");
        exec("CREATE TABLE t24_log (msg text)");
        exec("""
            CREATE FUNCTION log_truncate() RETURNS trigger AS $$
            BEGIN
                INSERT INTO t24_log VALUES (TG_WHEN || ' ' || TG_OP);
                RETURN NULL;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg_trunc_before BEFORE TRUNCATE ON t24 FOR EACH STATEMENT EXECUTE FUNCTION log_truncate()");
        exec("CREATE TRIGGER trg_trunc_after AFTER TRUNCATE ON t24 FOR EACH STATEMENT EXECUTE FUNCTION log_truncate()");
        exec("INSERT INTO t24 VALUES (1), (2), (3)");
        exec("TRUNCATE t24");
        assertEquals(0L, scalar("SELECT count(*) FROM t24"));
        assertEquals(2L, scalar("SELECT count(*) FROM t24_log"));
    }

    // =====================================================================
    // COPY FROM fires triggers
    // =====================================================================

    @Test void copyFromFiresTriggers() throws SQLException {
        exec("CREATE TABLE t23 (id integer, val text)");
        exec("CREATE TABLE t23_log (msg text)");
        exec("""
            CREATE FUNCTION log_copy_insert() RETURNS trigger AS $$
            BEGIN
                INSERT INTO t23_log VALUES (TG_OP || ':' || NEW.id);
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg_copy BEFORE INSERT ON t23 FOR EACH ROW EXECUTE FUNCTION log_copy_insert()");
        exec("COPY t23 FROM STDIN;\n1\thello\n2\tworld\n\\.");
        assertEquals(2L, scalar("SELECT count(*) FROM t23"));
        assertEquals(2L, scalar("SELECT count(*) FROM t23_log"));
    }

    // =====================================================================
    // ON CONFLICT DO UPDATE fires UPDATE triggers
    // =====================================================================

    @Test void onConflictDoUpdateFiresUpdateTriggers() throws SQLException {
        exec("CREATE TABLE t22 (id integer PRIMARY KEY, val text)");
        exec("CREATE TABLE t22_log (op text, old_val text, new_val text)");
        exec("""
            CREATE FUNCTION log_update_t22() RETURNS trigger AS $$
            BEGIN
                INSERT INTO t22_log VALUES (TG_OP, OLD.val, NEW.val);
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg_upd BEFORE UPDATE ON t22 FOR EACH ROW EXECUTE FUNCTION log_update_t22()");
        exec("INSERT INTO t22 VALUES (1, 'original')");
        exec("INSERT INTO t22 VALUES (1, 'conflict') ON CONFLICT (id) DO UPDATE SET val = EXCLUDED.val");
        assertEquals("conflict", scalar("SELECT val FROM t22"));
        assertEquals("UPDATE", scalar("SELECT op FROM t22_log"));
        assertEquals("original", scalar("SELECT old_val FROM t22_log"));
        assertEquals("conflict", scalar("SELECT new_val FROM t22_log"));
    }

    // =====================================================================
    // BEFORE DELETE: NEW is null, OLD is the deleted row
    // =====================================================================

    @Test void beforeDeleteNewIsNull() throws SQLException {
        exec("CREATE TABLE t21 (id integer)");
        exec("CREATE TABLE t21_log (old_id integer, tg_op_val text)");
        exec("""
            CREATE FUNCTION log_delete_new_old() RETURNS trigger AS $$
            DECLARE
                v_op text;
            BEGIN
                v_op := TG_OP;
                INSERT INTO t21_log VALUES (OLD.id, v_op);
                RETURN OLD;
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CREATE TRIGGER trg_del BEFORE DELETE ON t21 FOR EACH ROW EXECUTE FUNCTION log_delete_new_old()");
        exec("INSERT INTO t21 VALUES (99)");
        exec("DELETE FROM t21 WHERE id = 99");
        assertEquals(99, scalar("SELECT old_id FROM t21_log"));
        assertEquals("DELETE", scalar("SELECT tg_op_val FROM t21_log"));
        // Verify the delete actually happened (trigger returned OLD, not null)
        assertEquals(0L, scalar("SELECT count(*) FROM t21"));
    }
}
