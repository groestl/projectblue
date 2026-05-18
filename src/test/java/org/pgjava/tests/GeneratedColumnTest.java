package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.QueryResult;
import org.pgjava.engine.Session;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GENERATED ALWAYS AS (expr) STORED — computed generated columns.
 */
class GeneratedColumnTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("gen_test_" + System.nanoTime());
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

    private Object[] row(String sql) throws SQLException {
        QueryResult r = sess.execute(sql);
        assertNotNull(r.rows());
        assertFalse(r.rows().isEmpty(), "expected one row for: " + sql);
        return r.rows().get(0);
    }

    private void exec(String sql) throws SQLException {
        sess.execute(sql);
    }

    // =========================================================================
    // Basic computed column
    // =========================================================================

    @Test void computedColumnOnInsert() throws SQLException {
        exec("""
            CREATE TABLE orders (
                qty integer,
                price numeric,
                total numeric GENERATED ALWAYS AS (qty * price) STORED
            )
            """);
        exec("INSERT INTO orders (qty, price) VALUES (3, 10.50)");
        Object total = scalar("SELECT total FROM orders");
        assertEquals(31.5, ((Number) total).doubleValue(), 0.001);
    }

    @Test void computedColumnOnUpdate() throws SQLException {
        exec("""
            CREATE TABLE items (
                a integer,
                b integer,
                sum integer GENERATED ALWAYS AS (a + b) STORED
            )
            """);
        exec("INSERT INTO items (a, b) VALUES (1, 2)");
        assertEquals(3, scalar("SELECT sum FROM items"));

        exec("UPDATE items SET a = 10");
        assertEquals(12, scalar("SELECT sum FROM items"));

        exec("UPDATE items SET b = 20");
        assertEquals(30, scalar("SELECT sum FROM items"));
    }

    @Test void computedColumnWithTextConcat() throws SQLException {
        exec("""
            CREATE TABLE people (
                first_name text,
                last_name text,
                full_name text GENERATED ALWAYS AS (first_name || ' ' || last_name) STORED
            )
            """);
        exec("INSERT INTO people (first_name, last_name) VALUES ('Alice', 'Smith')");
        assertEquals("Alice Smith", scalar("SELECT full_name FROM people"));

        exec("UPDATE people SET last_name = 'Jones'");
        assertEquals("Alice Jones", scalar("SELECT full_name FROM people"));
    }

    @Test void computedColumnReturning() throws SQLException {
        exec("""
            CREATE TABLE calc (
                x integer,
                doubled integer GENERATED ALWAYS AS (x * 2) STORED
            )
            """);
        QueryResult r = sess.execute(
                "INSERT INTO calc (x) VALUES (5) RETURNING doubled");
        assertNotNull(r.rows());
        assertEquals(1, r.rows().size());
        assertEquals(10, r.rows().get(0)[0]);
    }

    @Test void computedColumnMultipleRows() throws SQLException {
        exec("""
            CREATE TABLE multi (
                n integer,
                sq integer GENERATED ALWAYS AS (n * n) STORED
            )
            """);
        exec("INSERT INTO multi (n) VALUES (1), (2), (3), (4), (5)");
        QueryResult r = sess.execute("SELECT n, sq FROM multi ORDER BY n");
        assertEquals(5, r.rows().size());
        assertEquals(1, r.rows().get(0)[1]);
        assertEquals(4, r.rows().get(1)[1]);
        assertEquals(9, r.rows().get(2)[1]);
        assertEquals(16, r.rows().get(3)[1]);
        assertEquals(25, r.rows().get(4)[1]);
    }

    @Test void computedColumnWithFunction() throws SQLException {
        exec("""
            CREATE TABLE lengths (
                val text,
                len integer GENERATED ALWAYS AS (length(val)) STORED
            )
            """);
        exec("INSERT INTO lengths (val) VALUES ('hello')");
        assertEquals(5, scalar("SELECT len FROM lengths"));

        exec("UPDATE lengths SET val = 'hi'");
        assertEquals(2, scalar("SELECT len FROM lengths"));
    }

    @Test void generatedIdentityStillWorks() throws SQLException {
        // Ensure GENERATED ALWAYS AS IDENTITY (sequence) is not broken
        exec("""
            CREATE TABLE ids (
                id integer GENERATED ALWAYS AS IDENTITY,
                name text
            )
            """);
        exec("INSERT INTO ids (name) VALUES ('a'), ('b'), ('c')");
        QueryResult r = sess.execute("SELECT id FROM ids ORDER BY id");
        assertEquals(3, r.rows().size());
        assertEquals(1L, ((Number) r.rows().get(0)[0]).longValue());
        assertEquals(2L, ((Number) r.rows().get(1)[0]).longValue());
        assertEquals(3L, ((Number) r.rows().get(2)[0]).longValue());
    }
}
