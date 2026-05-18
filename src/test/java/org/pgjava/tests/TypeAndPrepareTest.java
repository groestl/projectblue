package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.QueryResult;
import org.pgjava.engine.Session;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CREATE TYPE AS ENUM, CREATE DOMAIN, CALL, PREPARE/EXECUTE/DEALLOCATE.
 */
class TypeAndPrepareTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("type_prepare_test_" + System.nanoTime());
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

    private QueryResult exec(String sql) throws SQLException {
        return sess.execute(sql);
    }

    // ─── CREATE TYPE AS ENUM ─────────────────────────────────────────────────

    @Test void createEnumBasic() throws SQLException {
        exec("CREATE TYPE mood AS ENUM ('happy', 'sad', 'angry')");
        exec("CREATE TABLE people (name text, feeling mood)");
        exec("INSERT INTO people VALUES ('Alice', 'happy')");
        exec("INSERT INTO people VALUES ('Bob', 'sad')");
        assertEquals("happy", scalar("SELECT feeling FROM people WHERE name = 'Alice'"));
        assertEquals("sad", scalar("SELECT feeling FROM people WHERE name = 'Bob'"));
    }

    @Test void enumRejectsInvalidValue() throws SQLException {
        exec("CREATE TYPE color AS ENUM ('red', 'green', 'blue')");
        exec("CREATE TABLE items (name text, c color)");
        assertThrows(SQLException.class, () ->
                exec("INSERT INTO items VALUES ('x', 'purple')"));
    }

    @Test void enumNullAllowed() throws SQLException {
        exec("CREATE TYPE status AS ENUM ('active', 'inactive')");
        exec("CREATE TABLE accounts (id int, s status)");
        exec("INSERT INTO accounts VALUES (1, NULL)");
        assertNull(scalar("SELECT s FROM accounts WHERE id = 1"));
    }

    @Test void enumFilterInWhere() throws SQLException {
        exec("CREATE TYPE prio AS ENUM ('low', 'medium', 'high')");
        exec("CREATE TABLE tasks (id int, p prio)");
        exec("INSERT INTO tasks VALUES (1, 'low')");
        exec("INSERT INTO tasks VALUES (2, 'high')");
        exec("INSERT INTO tasks VALUES (3, 'medium')");
        assertEquals(2L, ((Number) scalar("SELECT id FROM tasks WHERE p = 'high'")).longValue());
    }

    // ─── CREATE DOMAIN ───────────────────────────────────────────────────────

    @Test void createDomainWithCheck() throws SQLException {
        exec("CREATE DOMAIN posint AS integer CHECK (VALUE > 0)");
        exec("CREATE TABLE scores (id int, score posint)");
        exec("INSERT INTO scores VALUES (1, 42)");
        assertEquals(42L, ((Number) scalar("SELECT score FROM scores WHERE id = 1")).longValue());
    }

    @Test void domainCheckRejectsInvalid() throws SQLException {
        exec("CREATE DOMAIN posint2 AS integer CHECK (VALUE > 0)");
        exec("CREATE TABLE nums (v posint2)");
        assertThrows(SQLException.class, () ->
                exec("INSERT INTO nums VALUES (-5)"));
    }

    @Test void domainCheckRejectsZero() throws SQLException {
        exec("CREATE DOMAIN posint3 AS integer CHECK (VALUE > 0)");
        exec("CREATE TABLE nums3 (v posint3)");
        assertThrows(SQLException.class, () ->
                exec("INSERT INTO nums3 VALUES (0)"));
    }

    @Test void domainNotNull() throws SQLException {
        exec("CREATE DOMAIN nn_text AS text NOT NULL");
        exec("CREATE TABLE labels (v nn_text)");
        exec("INSERT INTO labels VALUES ('hello')");
        assertThrows(SQLException.class, () ->
                exec("INSERT INTO labels VALUES (NULL)"));
    }

    @Test void domainAllowsNullWithoutConstraint() throws SQLException {
        exec("CREATE DOMAIN myint AS integer CHECK (VALUE > 0)");
        exec("CREATE TABLE t_dom (v myint)");
        // NULL should pass — CHECK passes for NULL per PostgreSQL semantics
        exec("INSERT INTO t_dom VALUES (NULL)");
        assertNull(scalar("SELECT v FROM t_dom"));
    }

    // ─── CALL ────────────────────────────────────────────────────────────────

    @Test void callProcedure() throws SQLException {
        exec("CREATE TABLE audit_log (msg text)");
        exec("""
            CREATE PROCEDURE log_msg(m text) AS $$
            BEGIN
                INSERT INTO audit_log VALUES (m);
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CALL log_msg('hello world')");
        assertEquals("hello world", scalar("SELECT msg FROM audit_log"));
    }

    @Test void callProcedureMultipleArgs() throws SQLException {
        exec("CREATE TABLE kv (k text, v int)");
        exec("""
            CREATE PROCEDURE insert_kv(key text, val int) AS $$
            BEGIN
                INSERT INTO kv VALUES (key, val);
            END;
            $$ LANGUAGE plpgsql
            """);
        exec("CALL insert_kv('answer', 42)");
        assertEquals(42L, ((Number) scalar("SELECT v FROM kv WHERE k = 'answer'")).longValue());
    }

    // ─── PREPARE / EXECUTE / DEALLOCATE ──────────────────────────────────────

    @Test void prepareAndExecuteSelect() throws SQLException {
        exec("CREATE TABLE t1 (id int, name text)");
        exec("INSERT INTO t1 VALUES (1, 'one'), (2, 'two'), (3, 'three')");
        exec("PREPARE find_name AS SELECT name FROM t1 WHERE id = $1");
        assertEquals("two", scalar("EXECUTE find_name(2)"));
        assertEquals("three", scalar("EXECUTE find_name(3)"));
    }

    @Test void prepareAndExecuteInsert() throws SQLException {
        exec("CREATE TABLE t2 (id int, val text)");
        exec("PREPARE ins AS INSERT INTO t2 VALUES ($1, $2)");
        exec("EXECUTE ins(1, 'alpha')");
        exec("EXECUTE ins(2, 'beta')");
        assertEquals("alpha", scalar("SELECT val FROM t2 WHERE id = 1"));
        assertEquals("beta", scalar("SELECT val FROM t2 WHERE id = 2"));
    }

    @Test void deallocate() throws SQLException {
        exec("CREATE TABLE t3 (id int)");
        exec("INSERT INTO t3 VALUES (1)");
        exec("PREPARE q AS SELECT id FROM t3 WHERE id = $1");
        assertEquals(1L, ((Number) scalar("EXECUTE q(1)")).longValue());
        exec("DEALLOCATE q");
        assertThrows(SQLException.class, () -> exec("EXECUTE q(1)"));
    }

    @Test void deallocateAll() throws SQLException {
        exec("CREATE TABLE t4 (id int)");
        exec("INSERT INTO t4 VALUES (1)");
        exec("PREPARE a AS SELECT id FROM t4");
        exec("PREPARE b AS SELECT id FROM t4");
        exec("DEALLOCATE ALL");
        assertThrows(SQLException.class, () -> exec("EXECUTE a"));
        assertThrows(SQLException.class, () -> exec("EXECUTE b"));
    }

    @Test void executeNonExistentPlan() {
        assertThrows(SQLException.class, () -> exec("EXECUTE no_such_plan(1)"));
    }

    // ─── Domain/Enum validation on UPDATE ────────────────────────────────────

    @Test void enumRejectsInvalidOnUpdate() throws SQLException {
        exec("CREATE TYPE dir AS ENUM ('north', 'south', 'east', 'west')");
        exec("CREATE TABLE compass (id int, d dir)");
        exec("INSERT INTO compass VALUES (1, 'north')");
        assertThrows(SQLException.class, () ->
                exec("UPDATE compass SET d = 'northwest' WHERE id = 1"));
        // Original value unchanged
        assertEquals("north", scalar("SELECT d FROM compass WHERE id = 1"));
    }

    @Test void domainCheckRejectsOnUpdate() throws SQLException {
        exec("CREATE DOMAIN pct AS integer CHECK (VALUE >= 0 AND VALUE <= 100)");
        exec("CREATE TABLE progress (id int, pct pct)");
        exec("INSERT INTO progress VALUES (1, 50)");
        assertThrows(SQLException.class, () ->
                exec("UPDATE progress SET pct = 200 WHERE id = 1"));
        assertEquals(50L, ((Number) scalar("SELECT pct FROM progress WHERE id = 1")).longValue());
    }
}
