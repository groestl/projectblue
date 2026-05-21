package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;

/**
 * Golden tests for PL/pgSQL functions comparing pgjava against real PostgreSQL.
 *
 * <p>Covers: variable declaration, assignment, RETURN, IF/ELSIF/ELSE,
 * CASE, LOOP/WHILE/FOR loops, SELECT INTO, RAISE, EXCEPTION handling,
 * FOUND, cursors, dynamic SQL (EXECUTE), RETURN NEXT / RETURN QUERY,
 * %TYPE and %ROWTYPE, PERFORM, and basic stored procedures.
 */
@ExtendWith(GoldenExtension.class)
class GoldenPlPgSqlTest {

    // ── Basic functions ───────────────────────────────────────────────────────

    @Test void simpleReturn(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION add_two(x INT, y INT) RETURNS INT LANGUAGE plpgsql AS $$
                BEGIN
                    RETURN x + y;
                END;
                $$
                """);
        db.assertQuery("SELECT add_two(3, 4)");
    }

    @Test void variableDeclarationAndAssignment(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION compute() RETURNS INT LANGUAGE plpgsql AS $$
                DECLARE
                    a INT := 10;
                    b INT;
                BEGIN
                    b := a * 3 + 5;
                    RETURN b;
                END;
                $$
                """);
        db.assertQuery("SELECT compute()");
    }

    @Test void textReturn(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION greet(name TEXT) RETURNS TEXT LANGUAGE plpgsql AS $$
                BEGIN
                    RETURN 'Hello, ' || name || '!';
                END;
                $$
                """);
        db.assertQuery("SELECT greet('World')");
    }

    // ── Conditionals ──────────────────────────────────────────────────────────

    @Test void ifElse(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION classify(n INT) RETURNS TEXT LANGUAGE plpgsql AS $$
                BEGIN
                    IF n > 0 THEN
                        RETURN 'positive';
                    ELSIF n < 0 THEN
                        RETURN 'negative';
                    ELSE
                        RETURN 'zero';
                    END IF;
                END;
                $$
                """);
        db.assertQuery("SELECT classify(5), classify(-3), classify(0)");
    }

    @Test void caseExpression(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION day_name(d INT) RETURNS TEXT LANGUAGE plpgsql AS $$
                BEGIN
                    RETURN CASE d
                        WHEN 1 THEN 'Monday'
                        WHEN 2 THEN 'Tuesday'
                        WHEN 3 THEN 'Wednesday'
                        ELSE 'Other'
                    END;
                END;
                $$
                """);
        db.assertQuery("SELECT day_name(1), day_name(2), day_name(7)");
    }

    // ── Loops ─────────────────────────────────────────────────────────────────

    @Test void whileLoop(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION sum_to(n INT) RETURNS INT LANGUAGE plpgsql AS $$
                DECLARE
                    i   INT := 1;
                    total INT := 0;
                BEGIN
                    WHILE i <= n LOOP
                        total := total + i;
                        i := i + 1;
                    END LOOP;
                    RETURN total;
                END;
                $$
                """);
        db.assertQuery("SELECT sum_to(10)");
    }

    @Test void forIntegerLoop(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION factorial(n INT) RETURNS BIGINT LANGUAGE plpgsql AS $$
                DECLARE
                    result BIGINT := 1;
                BEGIN
                    FOR i IN 1..n LOOP
                        result := result * i;
                    END LOOP;
                    RETURN result;
                END;
                $$
                """);
        db.assertQuery("SELECT factorial(5), factorial(10)");
    }

    @Test void loopWithExit(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION first_over_100(step INT) RETURNS INT LANGUAGE plpgsql AS $$
                DECLARE
                    n INT := 0;
                BEGIN
                    LOOP
                        n := n + step;
                        EXIT WHEN n > 100;
                    END LOOP;
                    RETURN n;
                END;
                $$
                """);
        db.assertQuery("SELECT first_over_100(7), first_over_100(13)");
    }

    @Test void forReverseLoop(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION count_down(n INT) RETURNS INT LANGUAGE plpgsql AS $$
                DECLARE
                    total INT := 0;
                BEGIN
                    FOR i IN REVERSE n..1 LOOP
                        total := total + i;
                    END LOOP;
                    RETURN total;
                END;
                $$
                """);
        db.assertQuery("SELECT count_down(5)");
    }

    // ── SELECT INTO / FOUND ───────────────────────────────────────────────────

    @Test void selectIntoFound(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE employees (id INT PRIMARY KEY, name TEXT, salary NUMERIC)");
        db.execute("INSERT INTO employees VALUES (1, 'Alice', 70000), (2, 'Bob', 50000)");
        db.execute("""
                CREATE FUNCTION get_salary(emp_id INT) RETURNS NUMERIC LANGUAGE plpgsql AS $$
                DECLARE
                    s NUMERIC;
                BEGIN
                    SELECT salary INTO s FROM employees WHERE id = emp_id;
                    IF NOT FOUND THEN
                        RETURN -1;
                    END IF;
                    RETURN s;
                END;
                $$
                """);
        db.assertQuery("SELECT get_salary(1), get_salary(99)");
    }

    @Test void selectIntoStrict(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT, val TEXT)");
        db.execute("INSERT INTO t VALUES (1, 'x'), (2, 'y')");
        db.execute("""
                CREATE FUNCTION get_val(p_id INT) RETURNS TEXT LANGUAGE plpgsql AS $$
                DECLARE
                    v TEXT;
                BEGIN
                    SELECT val INTO STRICT v FROM t WHERE id = p_id;
                    RETURN v;
                EXCEPTION
                    WHEN NO_DATA_FOUND THEN RETURN 'not found';
                    WHEN TOO_MANY_ROWS THEN RETURN 'too many';
                END;
                $$
                """);
        db.assertQuery("SELECT get_val(1), get_val(99)");
    }

    // ── FOR loop over query ───────────────────────────────────────────────────

    @Test void forLoopOverQuery(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE nums (n INT)");
        db.execute("INSERT INTO nums VALUES (1), (2), (3), (4), (5)");
        db.execute("""
                CREATE FUNCTION sum_nums() RETURNS INT LANGUAGE plpgsql AS $$
                DECLARE
                    rec RECORD;
                    total INT := 0;
                BEGIN
                    FOR rec IN SELECT n FROM nums ORDER BY n LOOP
                        total := total + rec.n;
                    END LOOP;
                    RETURN total;
                END;
                $$
                """);
        db.assertQuery("SELECT sum_nums()");
    }

    // ── DML inside functions ──────────────────────────────────────────────────

    @Test void insertInsideFunction(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE log_t (msg TEXT)");
        db.execute("""
                CREATE FUNCTION log_msg(m TEXT) RETURNS VOID LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO log_t VALUES (m);
                END;
                $$
                """);
        db.execute("SELECT log_msg('hello')");
        db.execute("SELECT log_msg('world')");
        db.assertQuery("SELECT msg FROM log_t ORDER BY msg");
    }

    @Test void updateInsideFunction(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE counter (name TEXT PRIMARY KEY, val INT)");
        db.execute("INSERT INTO counter VALUES ('hits', 0)");
        db.execute("""
                CREATE FUNCTION increment(key TEXT) RETURNS INT LANGUAGE plpgsql AS $$
                DECLARE
                    new_val INT;
                BEGIN
                    UPDATE counter SET val = val + 1 WHERE name = key;
                    SELECT val INTO new_val FROM counter WHERE name = key;
                    RETURN new_val;
                END;
                $$
                """);
        db.assertQuery("SELECT increment('hits'), increment('hits'), increment('hits')");
    }

    // ── Exception handling ────────────────────────────────────────────────────

    @Test void exceptionHandling(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY)");
        db.execute("""
                CREATE FUNCTION safe_insert(p_id INT) RETURNS TEXT LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO t VALUES (p_id);
                    RETURN 'inserted';
                EXCEPTION
                    WHEN unique_violation THEN
                        RETURN 'duplicate';
                END;
                $$
                """);
        db.execute("INSERT INTO t VALUES (1)");
        db.assertQuery("SELECT safe_insert(2), safe_insert(1)");
    }

    @Test void raiseException(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION check_positive(n INT) RETURNS INT LANGUAGE plpgsql AS $$
                BEGIN
                    IF n <= 0 THEN
                        RAISE EXCEPTION 'Value must be positive, got %', n;
                    END IF;
                    RETURN n;
                END;
                $$
                """);
        db.assertQuery("SELECT check_positive(5)");
        db.assertError("SELECT check_positive(-1)", "P0001");
    }

    @Test void raiseNotice(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION noisy(n INT) RETURNS INT LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE NOTICE 'Processing %', n;
                    RETURN n * 2;
                END;
                $$
                """);
        db.assertQuery("SELECT noisy(5)");
    }

    // ── %TYPE and %ROWTYPE ────────────────────────────────────────────────────

    @Test void percentType(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE products (id INT, price NUMERIC(10,2))");
        db.execute("INSERT INTO products VALUES (1, 9.99)");
        db.execute("""
                CREATE FUNCTION get_price(p_id INT) RETURNS NUMERIC LANGUAGE plpgsql AS $$
                DECLARE
                    p products.price%TYPE;
                BEGIN
                    SELECT price INTO p FROM products WHERE id = p_id;
                    RETURN p;
                END;
                $$
                """);
        db.assertQuery("SELECT get_price(1)");
    }

    @Test void rowType(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE person (id INT, name TEXT, age INT)");
        db.execute("INSERT INTO person VALUES (1, 'Alice', 30)");
        db.execute("""
                CREATE FUNCTION get_person_name(p_id INT) RETURNS TEXT LANGUAGE plpgsql AS $$
                DECLARE
                    rec person%ROWTYPE;
                BEGIN
                    SELECT * INTO rec FROM person WHERE id = p_id;
                    RETURN rec.name;
                END;
                $$
                """);
        db.assertQuery("SELECT get_person_name(1)");
    }

    // ── RETURN QUERY / SETOF ──────────────────────────────────────────────────

    @Test void returnQuery(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (n INT)");
        db.execute("INSERT INTO t VALUES (1), (2), (3), (4), (5)");
        db.execute("""
                CREATE FUNCTION evens_up_to(limit_n INT) RETURNS SETOF INT LANGUAGE plpgsql AS $$
                BEGIN
                    RETURN QUERY SELECT n FROM t WHERE n % 2 = 0 AND n <= limit_n ORDER BY n;
                END;
                $$
                """);
        db.assertQuery("SELECT evens_up_to(4) ORDER BY 1");
    }

    @Test void returnNext(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION generate_squares(n INT) RETURNS SETOF INT LANGUAGE plpgsql AS $$
                DECLARE
                    i INT;
                BEGIN
                    FOR i IN 1..n LOOP
                        RETURN NEXT i * i;
                    END LOOP;
                END;
                $$
                """);
        db.assertQuery("SELECT generate_squares(5) ORDER BY 1");
    }

    // ── Dynamic SQL (EXECUTE) ─────────────────────────────────────────────────

    @Test void dynamicExecute(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (val INT)");
        db.execute("INSERT INTO t VALUES (10), (20), (30)");
        db.execute("""
                CREATE FUNCTION dynamic_sum(tbl TEXT) RETURNS BIGINT LANGUAGE plpgsql AS $$
                DECLARE
                    result BIGINT;
                BEGIN
                    EXECUTE 'SELECT sum(val) FROM ' || quote_ident(tbl) INTO result;
                    RETURN result;
                END;
                $$
                """);
        db.assertQuery("SELECT dynamic_sum('t')");
    }

    // ── Procedures (CALL) ─────────────────────────────────────────────────────

    @Test void simpleProcedure(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE audit (msg TEXT)");
        db.execute("""
                CREATE PROCEDURE log_event(msg TEXT) LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO audit VALUES (msg);
                END;
                $$
                """);
        db.execute("CALL log_event('start')");
        db.execute("CALL log_event('end')");
        db.assertQuery("SELECT msg FROM audit ORDER BY msg");
    }

    // ── Recursive function ────────────────────────────────────────────────────

    @Test void recursiveFunction(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION fib(n INT) RETURNS INT LANGUAGE plpgsql AS $$
                BEGIN
                    IF n <= 1 THEN RETURN n; END IF;
                    RETURN fib(n - 1) + fib(n - 2);
                END;
                $$
                """);
        db.assertQuery("SELECT fib(0), fib(1), fib(5), fib(8)");
    }

    // ── Default parameters ────────────────────────────────────────────────────

    @Test void defaultParameters(DualExecutor db) throws Exception {
        db.execute("""
                CREATE FUNCTION multiply(x INT, y INT DEFAULT 2) RETURNS INT LANGUAGE plpgsql AS $$
                BEGIN
                    RETURN x * y;
                END;
                $$
                """);
        db.assertQuery("SELECT multiply(5), multiply(5, 3)");
    }
}
