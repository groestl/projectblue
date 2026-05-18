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
 * CREATE FUNCTION for SQL-language functions.
 */
class CreateFunctionTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("func_test_" + System.nanoTime());
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

    // =========================================================================
    // Basic constant function
    // =========================================================================

    @Test void constantFunction() throws SQLException {
        exec("""
            CREATE FUNCTION answer() RETURNS integer AS $$
                SELECT 42
            $$ LANGUAGE sql
            """);
        assertEquals(42, scalar("SELECT answer()"));
    }

    // =========================================================================
    // Function with parameters
    // =========================================================================

    @Test void functionWithParams() throws SQLException {
        exec("""
            CREATE FUNCTION add_ints(a integer, b integer) RETURNS integer AS $$
                SELECT $1 + $2
            $$ LANGUAGE sql
            """);
        assertEquals(7, scalar("SELECT add_ints(3, 4)"));
    }

    @Test void functionWithTextParams() throws SQLException {
        exec("""
            CREATE FUNCTION greet(name text) RETURNS text AS $$
                SELECT 'Hello, ' || $1 || '!'
            $$ LANGUAGE sql
            """);
        assertEquals("Hello, World!", scalar("SELECT greet('World')"));
    }

    // =========================================================================
    // Function querying a table
    // =========================================================================

    @Test void functionQueryingTable() throws SQLException {
        exec("CREATE TABLE employees (id serial PRIMARY KEY, name text, salary integer)");
        exec("INSERT INTO employees (name, salary) VALUES ('Alice', 100000), ('Bob', 80000)");
        exec("""
            CREATE FUNCTION max_salary() RETURNS integer AS $$
                SELECT MAX(salary) FROM employees
            $$ LANGUAGE sql
            """);
        assertEquals(100000, scalar("SELECT max_salary()"));
    }

    @Test void functionWithParamQueryingTable() throws SQLException {
        exec("CREATE TABLE items (id serial PRIMARY KEY, name text, price numeric)");
        exec("INSERT INTO items (name, price) VALUES ('A', 10.5), ('B', 20.0), ('C', 5.0)");
        exec("""
            CREATE FUNCTION items_above(threshold numeric) RETURNS bigint AS $$
                SELECT COUNT(*) FROM items WHERE price > $1
            $$ LANGUAGE sql
            """);
        assertEquals(2L, scalar("SELECT items_above(7.0)"));
    }

    // =========================================================================
    // OR REPLACE
    // =========================================================================

    @Test void orReplace() throws SQLException {
        exec("""
            CREATE FUNCTION val() RETURNS integer AS $$
                SELECT 1
            $$ LANGUAGE sql
            """);
        assertEquals(1, scalar("SELECT val()"));

        exec("""
            CREATE OR REPLACE FUNCTION val() RETURNS integer AS $$
                SELECT 2
            $$ LANGUAGE sql
            """);
        assertEquals(2, scalar("SELECT val()"));
    }

    // =========================================================================
    // Used in expressions
    // =========================================================================

    @Test void functionInWhereClause() throws SQLException {
        exec("CREATE TABLE nums (n integer)");
        exec("INSERT INTO nums VALUES (1), (2), (3), (4), (5)");
        exec("""
            CREATE FUNCTION double_it(x integer) RETURNS integer AS $$
                SELECT $1 * 2
            $$ LANGUAGE sql
            """);
        QueryResult r = sess.execute("SELECT n FROM nums WHERE double_it(n) > 6 ORDER BY n");
        assertEquals(List.of(4, 5),
                r.rows().stream().map(row -> (Integer) row[0]).toList());
    }

    @Test void functionInSelectList() throws SQLException {
        exec("""
            CREATE FUNCTION square(x integer) RETURNS integer AS $$
                SELECT $1 * $1
            $$ LANGUAGE sql
            """);
        assertEquals(25, scalar("SELECT square(5)"));
    }

    @Test void nestedFunctionCalls() throws SQLException {
        exec("""
            CREATE FUNCTION inc(x integer) RETURNS integer AS $$
                SELECT $1 + 1
            $$ LANGUAGE sql
            """);
        exec("""
            CREATE FUNCTION add3(x integer) RETURNS integer AS $$
                SELECT inc(inc(inc($1)))
            $$ LANGUAGE sql
            """);
        assertEquals(13, scalar("SELECT add3(10)"));
    }

    // =========================================================================
    // Boolean return
    // =========================================================================

    @Test void booleanFunction() throws SQLException {
        exec("""
            CREATE FUNCTION is_positive(x integer) RETURNS boolean AS $$
                SELECT $1 > 0
            $$ LANGUAGE sql
            """);
        assertEquals(true, scalar("SELECT is_positive(5)"));
        assertEquals(false, scalar("SELECT is_positive(-1)"));
    }

    // =========================================================================
    // Function with no rows returns null
    // =========================================================================

    @Test void emptyResultReturnsNull() throws SQLException {
        exec("CREATE TABLE empty_t (id integer)");
        exec("""
            CREATE FUNCTION find_id() RETURNS integer AS $$
                SELECT id FROM empty_t LIMIT 1
            $$ LANGUAGE sql
            """);
        assertNull(scalar("SELECT find_id()"));
    }

    // =========================================================================
    // DROP FUNCTION
    // =========================================================================

    @Test void dropFunction() throws SQLException {
        exec("""
            CREATE FUNCTION to_drop() RETURNS integer AS $$
                SELECT 99
            $$ LANGUAGE sql
            """);
        assertEquals(99, scalar("SELECT to_drop()"));
        exec("DROP FUNCTION to_drop()");
        assertThrows(SQLException.class, () -> scalar("SELECT to_drop()"));
    }

    @Test void dropFunctionWithoutArgs() throws SQLException {
        exec("""
            CREATE FUNCTION sole() RETURNS integer AS $$
                SELECT 1
            $$ LANGUAGE sql
            """);
        exec("DROP FUNCTION sole");
        assertThrows(SQLException.class, () -> scalar("SELECT sole()"));
    }

    @Test void dropFunctionIfExistsNoError() throws SQLException {
        // Should not throw when function doesn't exist
        exec("DROP FUNCTION IF EXISTS nonexistent()");
        exec("DROP FUNCTION IF EXISTS nonexistent");
    }

    @Test void dropFunctionNotFoundErrors() throws SQLException {
        assertThrows(SQLException.class, () -> exec("DROP FUNCTION nonexistent()"));
    }

    @Test void dropFunctionWithArgTypes() throws SQLException {
        exec("""
            CREATE FUNCTION typed(a integer, b integer) RETURNS integer AS $$
                SELECT $1 + $2
            $$ LANGUAGE sql
            """);
        exec("""
            CREATE FUNCTION typed(a text) RETURNS text AS $$
                SELECT $1
            $$ LANGUAGE sql
            """);
        // Drop only the (integer, integer) overload
        exec("DROP FUNCTION typed(integer, integer)");
        // text overload still works
        assertEquals("hello", scalar("SELECT typed('hello')"));
        // integer overload is gone
        assertThrows(SQLException.class, () -> scalar("SELECT typed(1, 2)"));
    }

    @Test void dropFunctionAmbiguousErrors() throws SQLException {
        exec("""
            CREATE FUNCTION ambig(a integer) RETURNS integer AS $$
                SELECT $1
            $$ LANGUAGE sql
            """);
        exec("""
            CREATE FUNCTION ambig(a text) RETURNS text AS $$
                SELECT $1
            $$ LANGUAGE sql
            """);
        // DROP without arg types should error because there are multiple overloads
        assertThrows(SQLException.class, () -> exec("DROP FUNCTION ambig"));
    }
}
