package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.QueryResult;
import org.pgjava.engine.Session;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import org.pgjava.engine.PgErrorException;

/**
 * PL/pgSQL function execution tests (Phases 2-5: interpreter core, variables, control flow,
 * RAISE, exception handling).
 */
class PlPgSqlExecutionTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("plpgsql_test_" + System.nanoTime());
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
    // Basic return
    // =====================================================================

    @Test void constantReturn() throws SQLException {
        exec("""
            CREATE FUNCTION plconst() RETURNS integer AS $$
            BEGIN
                RETURN 42;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(42, scalar("SELECT plconst()"));
    }

    @Test void returnExpression() throws SQLException {
        exec("""
            CREATE FUNCTION pldouble(x integer) RETURNS integer AS $$
            BEGIN
                RETURN x * 2;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(10, scalar("SELECT pldouble(5)"));
    }

    // =====================================================================
    // Variables and assignment
    // =====================================================================

    @Test void declareAndAssign() throws SQLException {
        exec("""
            CREATE FUNCTION plvars() RETURNS integer AS $$
            DECLARE
                a integer := 10;
                b integer;
            BEGIN
                b := a + 5;
                RETURN b;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(15, scalar("SELECT plvars()"));
    }

    @Test void variableDefault() throws SQLException {
        exec("""
            CREATE FUNCTION pldefault() RETURNS text AS $$
            DECLARE
                msg text DEFAULT 'hello';
            BEGIN
                RETURN msg;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals("hello", scalar("SELECT pldefault()"));
    }

    @Test void parameterByName() throws SQLException {
        exec("""
            CREATE FUNCTION pladd(a integer, b integer) RETURNS integer AS $$
            BEGIN
                RETURN a + b;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(7, scalar("SELECT pladd(3, 4)"));
    }

    @Test void paramAndVariable() throws SQLException {
        exec("""
            CREATE FUNCTION plmix(n integer) RETURNS integer AS $$
            DECLARE
                result integer := 0;
            BEGIN
                result := n * n;
                RETURN result;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(25, scalar("SELECT plmix(5)"));
    }

    // =====================================================================
    // IF / ELSIF / ELSE
    // =====================================================================

    @Test void ifThen() throws SQLException {
        exec("""
            CREATE FUNCTION plsign(x integer) RETURNS text AS $$
            BEGIN
                IF x > 0 THEN
                    RETURN 'positive';
                END IF;
                RETURN 'non-positive';
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals("positive", scalar("SELECT plsign(5)"));
        assertEquals("non-positive", scalar("SELECT plsign(-1)"));
    }

    @Test void ifElsifElse() throws SQLException {
        exec("""
            CREATE FUNCTION plsign2(x integer) RETURNS text AS $$
            BEGIN
                IF x > 0 THEN
                    RETURN 'positive';
                ELSIF x = 0 THEN
                    RETURN 'zero';
                ELSE
                    RETURN 'negative';
                END IF;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals("positive", scalar("SELECT plsign2(5)"));
        assertEquals("zero", scalar("SELECT plsign2(0)"));
        assertEquals("negative", scalar("SELECT plsign2(-3)"));
    }

    // =====================================================================
    // Loops
    // =====================================================================

    @Test void simpleWhileLoop() throws SQLException {
        exec("""
            CREATE FUNCTION plcount() RETURNS integer AS $$
            DECLARE
                i integer := 0;
                total integer := 0;
            BEGIN
                WHILE i < 5 LOOP
                    total := total + i;
                    i := i + 1;
                END LOOP;
                RETURN total;
            END;
            $$ LANGUAGE plpgsql
            """);
        // 0+1+2+3+4 = 10
        assertEquals(10, scalar("SELECT plcount()"));
    }

    @Test void forIntLoop() throws SQLException {
        exec("""
            CREATE FUNCTION plsum(n integer) RETURNS integer AS $$
            DECLARE
                total integer := 0;
            BEGIN
                FOR i IN 1..n LOOP
                    total := total + i;
                END LOOP;
                RETURN total;
            END;
            $$ LANGUAGE plpgsql
            """);
        // 1+2+3+4+5 = 15
        assertEquals(15L, ((Number) scalar("SELECT plsum(5)")).longValue());
    }

    @Test void forIntLoopReverse() throws SQLException {
        exec("""
            CREATE FUNCTION plrev() RETURNS integer AS $$
            DECLARE
                total integer := 0;
                factor integer := 100;
            BEGIN
                FOR i IN REVERSE 3..1 LOOP
                    total := total + i * factor;
                    factor := factor / 10;
                END LOOP;
                RETURN total;
            END;
            $$ LANGUAGE plpgsql
            """);
        // 3*100 + 2*10 + 1*1 = 321
        assertEquals(321L, ((Number) scalar("SELECT plrev()")).longValue());
    }

    @Test void exitLoop() throws SQLException {
        exec("""
            CREATE FUNCTION plexit() RETURNS integer AS $$
            DECLARE
                i integer := 0;
            BEGIN
                LOOP
                    i := i + 1;
                    EXIT WHEN i >= 5;
                END LOOP;
                RETURN i;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(5, scalar("SELECT plexit()"));
    }

    // =====================================================================
    // CASE
    // =====================================================================

    @Test void searchedCase() throws SQLException {
        exec("""
            CREATE FUNCTION plgrade(score integer) RETURNS text AS $$
            BEGIN
                CASE
                    WHEN score >= 90 THEN RETURN 'A';
                    WHEN score >= 80 THEN RETURN 'B';
                    WHEN score >= 70 THEN RETURN 'C';
                    ELSE RETURN 'F';
                END CASE;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals("A", scalar("SELECT plgrade(95)"));
        assertEquals("B", scalar("SELECT plgrade(85)"));
        assertEquals("F", scalar("SELECT plgrade(50)"));
    }

    // =====================================================================
    // Nested blocks
    // =====================================================================

    @Test void nestedBlock() throws SQLException {
        exec("""
            CREATE FUNCTION plnest() RETURNS integer AS $$
            DECLARE
                x integer := 10;
            BEGIN
                DECLARE
                    y integer := 20;
                BEGIN
                    x := x + y;
                END;
                RETURN x;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(30, scalar("SELECT plnest()"));
    }

    // =====================================================================
    // Fibonacci — complex control flow
    // =====================================================================

    @Test void fibonacci() throws SQLException {
        exec("""
            CREATE FUNCTION fib(n integer) RETURNS integer AS $$
            DECLARE
                a integer := 0;
                b integer := 1;
                temp integer;
            BEGIN
                IF n <= 0 THEN RETURN 0; END IF;
                IF n = 1 THEN RETURN 1; END IF;
                FOR i IN 2..n LOOP
                    temp := a + b;
                    a := b;
                    b := temp;
                END LOOP;
                RETURN b;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(0, scalar("SELECT fib(0)"));
        assertEquals(1, scalar("SELECT fib(1)"));
        assertEquals(1, scalar("SELECT fib(2)"));
        assertEquals(55, scalar("SELECT fib(10)"));
    }

    // =====================================================================
    // Factorial — recursive PL/pgSQL
    // =====================================================================

    @Test void factorial() throws SQLException {
        exec("""
            CREATE FUNCTION fact(n integer) RETURNS integer AS $$
            BEGIN
                IF n <= 1 THEN RETURN 1; END IF;
                RETURN n * fact(n - 1);
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(1, scalar("SELECT fact(0)"));
        assertEquals(1, scalar("SELECT fact(1)"));
        assertEquals(120, scalar("SELECT fact(5)"));
    }

    // =====================================================================
    // PERFORM (SQL integration)
    // =====================================================================

    @Test void performSetsFound() throws SQLException {
        exec("CREATE TABLE pltest (id integer)");
        exec("INSERT INTO pltest VALUES (1)");
        exec("""
            CREATE FUNCTION plperf() RETURNS boolean AS $$
            BEGIN
                PERFORM id FROM pltest WHERE id = 1;
                RETURN FOUND;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(true, scalar("SELECT plperf()"));
    }

    // =====================================================================
    // NULL statement
    // =====================================================================

    @Test void nullStatement() throws SQLException {
        exec("""
            CREATE FUNCTION plnull() RETURNS integer AS $$
            BEGIN
                NULL;
                RETURN 1;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(1, scalar("SELECT plnull()"));
    }

    // =====================================================================
    // FOR query loop
    // =====================================================================

    @Test void forQueryLoop() throws SQLException {
        exec("CREATE TABLE nums (val integer)");
        exec("INSERT INTO nums VALUES (10), (20), (30)");
        exec("""
            CREATE FUNCTION plqsum() RETURNS integer AS $$
            DECLARE
                total integer := 0;
                r integer;
            BEGIN
                FOR r IN SELECT val FROM nums LOOP
                    total := total + r;
                END LOOP;
                RETURN total;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(60, scalar("SELECT plqsum()"));
    }

    // =====================================================================
    // RAISE EXCEPTION
    // =====================================================================

    @Test void raiseExceptionSimple() throws SQLException {
        exec("""
            CREATE FUNCTION plraise() RETURNS integer AS $$
            BEGIN
                RAISE EXCEPTION 'something went wrong';
                RETURN 1;
            END;
            $$ LANGUAGE plpgsql
            """);
        var ex = assertThrows(SQLException.class, () -> scalar("SELECT plraise()"));
        assertEquals("P0001", ex.getSQLState());
        assertTrue(ex.getMessage().contains("something went wrong"));
    }

    @Test void raiseExceptionFormatArgs() throws SQLException {
        exec("""
            CREATE FUNCTION plraise_fmt(n integer) RETURNS integer AS $$
            BEGIN
                RAISE EXCEPTION 'bad value: %, limit is %', n, 100;
                RETURN 1;
            END;
            $$ LANGUAGE plpgsql
            """);
        var ex = assertThrows(SQLException.class, () -> scalar("SELECT plraise_fmt(200)"));
        assertEquals("P0001", ex.getSQLState());
        assertTrue(ex.getMessage().contains("bad value: 200, limit is 100"));
    }

    @Test void raiseExceptionWithErrcode() throws SQLException {
        exec("""
            CREATE FUNCTION plraise_code() RETURNS integer AS $$
            BEGIN
                RAISE EXCEPTION 'oops' USING ERRCODE = '22012';
                RETURN 1;
            END;
            $$ LANGUAGE plpgsql
            """);
        var ex = assertThrows(SQLException.class, () -> scalar("SELECT plraise_code()"));
        assertEquals("22012", ex.getSQLState());
    }

    @Test void raiseExceptionWithDetailHint() throws SQLException {
        exec("""
            CREATE FUNCTION plraise_dh() RETURNS integer AS $$
            BEGIN
                RAISE EXCEPTION 'main error'
                    USING DETAIL = 'some detail',
                          HINT = 'try something else';
                RETURN 1;
            END;
            $$ LANGUAGE plpgsql
            """);
        var ex = assertThrows(PgErrorException.class, () -> scalar("SELECT plraise_dh()"));
        assertEquals("P0001", ex.getSQLState());
        assertEquals("some detail", ex.detail());
        assertEquals("try something else", ex.hint());
    }

    @Test void raiseExceptionConditionName() throws SQLException {
        exec("""
            CREATE FUNCTION plraise_cond() RETURNS integer AS $$
            BEGIN
                RAISE division_by_zero;
                RETURN 1;
            END;
            $$ LANGUAGE plpgsql
            """);
        var ex = assertThrows(SQLException.class, () -> scalar("SELECT plraise_cond()"));
        assertEquals("22012", ex.getSQLState());
    }

    @Test void raiseExceptionSqlstate() throws SQLException {
        exec("""
            CREATE FUNCTION plraise_state() RETURNS integer AS $$
            BEGIN
                RAISE SQLSTATE '45000';
                RETURN 1;
            END;
            $$ LANGUAGE plpgsql
            """);
        var ex = assertThrows(SQLException.class, () -> scalar("SELECT plraise_state()"));
        assertEquals("45000", ex.getSQLState());
    }

    @Test void raiseNoticeDoesNotThrow() throws SQLException {
        exec("""
            CREATE FUNCTION plnotice() RETURNS integer AS $$
            BEGIN
                RAISE NOTICE 'hello %', 'world';
                RETURN 42;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(42, scalar("SELECT plnotice()"));
    }

    @Test void raiseWarningDoesNotThrow() throws SQLException {
        exec("""
            CREATE FUNCTION plwarn() RETURNS integer AS $$
            BEGIN
                RAISE WARNING 'be careful';
                RETURN 99;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(99, scalar("SELECT plwarn()"));
    }

    // =====================================================================
    // ASSERT
    // =====================================================================

    @Test void assertPassing() throws SQLException {
        exec("""
            CREATE FUNCTION plassert_ok() RETURNS integer AS $$
            BEGIN
                ASSERT 1 = 1, 'math is broken';
                RETURN 42;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(42, scalar("SELECT plassert_ok()"));
    }

    @Test void assertFailing() throws SQLException {
        exec("""
            CREATE FUNCTION plassert_fail() RETURNS integer AS $$
            BEGIN
                ASSERT 1 = 2, 'math is broken';
                RETURN 42;
            END;
            $$ LANGUAGE plpgsql
            """);
        var ex = assertThrows(SQLException.class, () -> scalar("SELECT plassert_fail()"));
        assertEquals("P0004", ex.getSQLState());
        assertTrue(ex.getMessage().contains("math is broken"));
    }

    @Test void assertFailingNoMessage() throws SQLException {
        exec("""
            CREATE FUNCTION plassert_fail2() RETURNS integer AS $$
            BEGIN
                ASSERT false;
                RETURN 42;
            END;
            $$ LANGUAGE plpgsql
            """);
        var ex = assertThrows(SQLException.class, () -> scalar("SELECT plassert_fail2()"));
        assertEquals("P0004", ex.getSQLState());
        assertTrue(ex.getMessage().contains("assertion failed"));
    }

    // =====================================================================
    // EXCEPTION WHEN handlers
    // =====================================================================

    @Test void exceptionWhenCatch() throws SQLException {
        exec("""
            CREATE FUNCTION plcatch() RETURNS text AS $$
            BEGIN
                BEGIN
                    RAISE EXCEPTION 'boom';
                EXCEPTION
                    WHEN OTHERS THEN
                        RETURN 'caught';
                END;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals("caught", scalar("SELECT plcatch()"));
    }

    @Test void exceptionWhenSpecificCondition() throws SQLException {
        exec("""
            CREATE FUNCTION plcatch_div() RETURNS text AS $$
            DECLARE
                x integer;
            BEGIN
                BEGIN
                    x := 1 / 0;
                EXCEPTION
                    WHEN division_by_zero THEN
                        RETURN 'division by zero caught';
                END;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals("division by zero caught", scalar("SELECT plcatch_div()"));
    }

    @Test void exceptionAccessSqlstate() throws SQLException {
        exec("""
            CREATE FUNCTION plcatch_state() RETURNS text AS $$
            BEGIN
                BEGIN
                    RAISE EXCEPTION 'boom' USING ERRCODE = '23505';
                EXCEPTION
                    WHEN OTHERS THEN
                        RETURN SQLERRM;
                END;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals("boom", scalar("SELECT plcatch_state()"));
    }

    @Test void exceptionAccessSqlstateCode() throws SQLException {
        exec("""
            CREATE FUNCTION plcatch_code() RETURNS text AS $$
            BEGIN
                BEGIN
                    RAISE EXCEPTION 'boom' USING ERRCODE = '23505';
                EXCEPTION
                    WHEN OTHERS THEN
                        RETURN SQLSTATE;
                END;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals("23505", scalar("SELECT plcatch_code()"));
    }

    @Test void exceptionUnmatchedPropagates() throws SQLException {
        exec("""
            CREATE FUNCTION pluncaught() RETURNS text AS $$
            BEGIN
                BEGIN
                    RAISE EXCEPTION 'boom' USING ERRCODE = '23505';
                EXCEPTION
                    WHEN division_by_zero THEN
                        RETURN 'wrong handler';
                END;
            END;
            $$ LANGUAGE plpgsql
            """);
        var ex = assertThrows(SQLException.class, () -> scalar("SELECT pluncaught()"));
        assertEquals("23505", ex.getSQLState());
    }

    @Test void exceptionWithClassCondition() throws SQLException {
        exec("""
            CREATE FUNCTION plcatch_class() RETURNS text AS $$
            BEGIN
                BEGIN
                    RAISE EXCEPTION 'boom' USING ERRCODE = '23505';
                EXCEPTION
                    WHEN integrity_constraint_violation THEN
                        RETURN 'class match';
                END;
            END;
            $$ LANGUAGE plpgsql
            """);
        // 23505 (unique_violation) is a subclass of 23xxx (integrity_constraint_violation)
        assertEquals("class match", scalar("SELECT plcatch_class()"));
    }

    @Test void exceptionReturnDoesNotDoubleHandle() throws SQLException {
        exec("""
            CREATE FUNCTION plcatch_return() RETURNS integer AS $$
            BEGIN
                BEGIN
                    RETURN 42;
                EXCEPTION
                    WHEN OTHERS THEN
                        RETURN -1;
                END;
            END;
            $$ LANGUAGE plpgsql
            """);
        // RETURN signal should not be caught by exception handler
        assertEquals(42, scalar("SELECT plcatch_return()"));
    }

    @Test void exceptionMultipleConditions() throws SQLException {
        exec("""
            CREATE FUNCTION plcatch_multi() RETURNS text AS $$
            BEGIN
                BEGIN
                    RAISE EXCEPTION 'boom' USING ERRCODE = '22012';
                EXCEPTION
                    WHEN unique_violation OR division_by_zero THEN
                        RETURN 'matched OR';
                END;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals("matched OR", scalar("SELECT plcatch_multi()"));
    }

    @Test void exceptionHandlerContinuesExecution() throws SQLException {
        exec("""
            CREATE FUNCTION plcatch_continue() RETURNS integer AS $$
            DECLARE
                result integer := 0;
            BEGIN
                BEGIN
                    RAISE EXCEPTION 'boom';
                EXCEPTION
                    WHEN OTHERS THEN
                        result := 10;
                END;
                result := result + 5;
                RETURN result;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(15, scalar("SELECT plcatch_continue()"));
    }

    // =====================================================================
    // EXECUTE (dynamic SQL)
    // =====================================================================

    @Test void executeDynamicSelect() throws SQLException {
        exec("""
            CREATE FUNCTION pldyn_sel() RETURNS integer AS $$
            DECLARE
                result integer;
            BEGIN
                EXECUTE 'SELECT 42' INTO result;
                RETURN result;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(42, scalar("SELECT pldyn_sel()"));
    }

    @Test void executeDynamicDml() throws SQLException {
        exec("CREATE TABLE dyn_test (id integer, val text)");
        exec("""
            CREATE FUNCTION pldyn_dml() RETURNS integer AS $$
            DECLARE
                cnt integer;
            BEGIN
                EXECUTE 'INSERT INTO dyn_test VALUES (1, ''hello'')';
                EXECUTE 'INSERT INTO dyn_test VALUES (2, ''world'')';
                EXECUTE 'SELECT count(*) FROM dyn_test' INTO cnt;
                RETURN cnt;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(2L, ((Number) scalar("SELECT pldyn_dml()")).longValue());
    }

    @Test void executeDynamicWithConcat() throws SQLException {
        exec("CREATE TABLE dyn2 (id integer)");
        exec("""
            CREATE FUNCTION pldyn_concat(tbl text) RETURNS integer AS $$
            DECLARE
                cnt integer;
            BEGIN
                EXECUTE 'SELECT count(*) FROM ' || tbl INTO cnt;
                RETURN cnt;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(0L, ((Number) scalar("SELECT pldyn_concat('dyn2')")).longValue());
    }

    @Test void executeIntoMultipleVars() throws SQLException {
        exec("""
            CREATE FUNCTION pldyn_multi() RETURNS text AS $$
            DECLARE
                a integer;
                b text;
            BEGIN
                EXECUTE 'SELECT 42, ''hello''' INTO a, b;
                RETURN b;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals("hello", scalar("SELECT pldyn_multi()"));
    }

    @Test void executeStrict() throws SQLException {
        exec("CREATE TABLE dyn_strict (id integer)");
        exec("INSERT INTO dyn_strict VALUES (1), (2)");
        exec("""
            CREATE FUNCTION pldyn_strict() RETURNS integer AS $$
            DECLARE
                result integer;
            BEGIN
                EXECUTE 'SELECT id FROM dyn_strict' INTO STRICT result;
                RETURN result;
            END;
            $$ LANGUAGE plpgsql
            """);
        var ex = assertThrows(SQLException.class, () -> scalar("SELECT pldyn_strict()"));
        assertEquals("P0003", ex.getSQLState());
    }

    // =====================================================================
    // Cursors
    // =====================================================================

    @Test void cursorDeclareOpenFetchClose() throws SQLException {
        exec("CREATE TABLE cur_test (id integer, name text)");
        exec("INSERT INTO cur_test VALUES (1, 'alpha'), (2, 'beta'), (3, 'gamma')");
        exec("""
            CREATE FUNCTION plcur() RETURNS text AS $$
            DECLARE
                c CURSOR FOR SELECT name FROM cur_test ORDER BY id;
                result text := '';
                v text;
            BEGIN
                OPEN c;
                FETCH c INTO v;
                result := result || v;
                FETCH c INTO v;
                result := result || ',' || v;
                CLOSE c;
                RETURN result;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals("alpha,beta", scalar("SELECT plcur()"));
    }

    @Test void cursorFetchFound() throws SQLException {
        exec("CREATE TABLE cur_f (id integer)");
        exec("INSERT INTO cur_f VALUES (1)");
        exec("""
            CREATE FUNCTION plcur_found() RETURNS boolean AS $$
            DECLARE
                c CURSOR FOR SELECT id FROM cur_f;
                v integer;
            BEGIN
                OPEN c;
                FETCH c INTO v;
                IF NOT FOUND THEN
                    CLOSE c;
                    RETURN false;
                END IF;
                FETCH c INTO v;
                CLOSE c;
                RETURN NOT FOUND;
            END;
            $$ LANGUAGE plpgsql
            """);
        // First fetch finds a row (FOUND=true), second fetch finds nothing (FOUND=false)
        // Returns NOT FOUND = NOT false = true (after second fetch makes FOUND=false)
        assertEquals(true, scalar("SELECT plcur_found()"));
    }

    @Test void cursorForLoop() throws SQLException {
        exec("CREATE TABLE cur_for (val integer)");
        exec("INSERT INTO cur_for VALUES (10), (20), (30)");
        exec("""
            CREATE FUNCTION plcur_for() RETURNS integer AS $$
            DECLARE
                c CURSOR FOR SELECT val FROM cur_for;
                total integer := 0;
                r integer;
            BEGIN
                FOR r IN c LOOP
                    total := total + r;
                END LOOP;
                RETURN total;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(60, scalar("SELECT plcur_for()"));
    }

    @Test void cursorOpenForQuery() throws SQLException {
        exec("CREATE TABLE cur_oq (id integer)");
        exec("INSERT INTO cur_oq VALUES (5), (10)");
        exec("""
            CREATE FUNCTION plcur_oq() RETURNS integer AS $$
            DECLARE
                c refcursor;
                v integer;
                total integer := 0;
            BEGIN
                OPEN c FOR SELECT id FROM cur_oq;
                LOOP
                    FETCH c INTO v;
                    EXIT WHEN NOT FOUND;
                    total := total + v;
                END LOOP;
                CLOSE c;
                RETURN total;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(15, scalar("SELECT plcur_oq()"));
    }

    // =====================================================================
    // Set-returning functions (RETURN NEXT / RETURN QUERY)
    // =====================================================================

    @Test void returnNext() throws SQLException {
        exec("""
            CREATE FUNCTION plsrf_next(n integer) RETURNS SETOF integer AS $$
            BEGIN
                FOR i IN 1..n LOOP
                    RETURN NEXT i;
                END LOOP;
            END;
            $$ LANGUAGE plpgsql
            """);
        QueryResult r = sess.execute("SELECT * FROM plsrf_next(3)");
        assertEquals(3, r.rows().size());
        assertEquals(1L, ((Number) r.rows().get(0)[0]).longValue());
        assertEquals(2L, ((Number) r.rows().get(1)[0]).longValue());
        assertEquals(3L, ((Number) r.rows().get(2)[0]).longValue());
    }

    @Test void returnQuery() throws SQLException {
        exec("CREATE TABLE srf_data (val integer)");
        exec("INSERT INTO srf_data VALUES (10), (20), (30)");
        exec("""
            CREATE FUNCTION plsrf_query() RETURNS SETOF integer AS $$
            BEGIN
                RETURN QUERY SELECT val FROM srf_data;
            END;
            $$ LANGUAGE plpgsql
            """);
        QueryResult r = sess.execute("SELECT * FROM plsrf_query()");
        assertEquals(3, r.rows().size());
    }

    @Test void returnNextAndQuery() throws SQLException {
        exec("CREATE TABLE srf_mix (val integer)");
        exec("INSERT INTO srf_mix VALUES (100)");
        exec("""
            CREATE FUNCTION plsrf_mix() RETURNS SETOF integer AS $$
            BEGIN
                RETURN NEXT 1;
                RETURN NEXT 2;
                RETURN QUERY SELECT val FROM srf_mix;
            END;
            $$ LANGUAGE plpgsql
            """);
        QueryResult r = sess.execute("SELECT * FROM plsrf_mix()");
        assertEquals(3, r.rows().size());
        assertEquals(1, r.rows().get(0)[0]);
        assertEquals(2, r.rows().get(1)[0]);
        assertEquals(100, r.rows().get(2)[0]);
    }

    // =====================================================================
    // Advanced declarations
    // =====================================================================

    @Test void constantVariable() throws SQLException {
        exec("""
            CREATE FUNCTION plconst_var() RETURNS integer AS $$
            DECLARE
                c CONSTANT integer := 42;
            BEGIN
                RETURN c;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(42, scalar("SELECT plconst_var()"));
    }

    @Test void constantAssignFails() throws SQLException {
        exec("""
            CREATE FUNCTION plconst_fail() RETURNS integer AS $$
            DECLARE
                c CONSTANT integer := 42;
            BEGIN
                c := 10;
                RETURN c;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertThrows(SQLException.class, () -> scalar("SELECT plconst_fail()"));
    }

    @Test void notNullVariable() throws SQLException {
        exec("""
            CREATE FUNCTION plnn() RETURNS integer AS $$
            DECLARE
                x integer NOT NULL := 5;
            BEGIN
                RETURN x;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(5, scalar("SELECT plnn()"));
    }

    @Test void aliasForParam() throws SQLException {
        exec("""
            CREATE FUNCTION plalias(val integer) RETURNS integer AS $$
            DECLARE
                v ALIAS FOR val;
            BEGIN
                RETURN v * 2;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(10, scalar("SELECT plalias(5)"));
    }

    // =====================================================================
    // DO blocks
    // =====================================================================

    @Test void doBlockSimple() throws SQLException {
        exec("CREATE TABLE do_test (val integer)");
        exec("""
            DO $$
            BEGIN
                INSERT INTO do_test VALUES (42);
            END;
            $$
            """);
        assertEquals(42, scalar("SELECT val FROM do_test"));
    }

    @Test void doBlockWithVariables() throws SQLException {
        exec("CREATE TABLE do_vars (val integer)");
        exec("""
            DO $$
            DECLARE
                x integer := 10;
                y integer := 20;
            BEGIN
                INSERT INTO do_vars VALUES (x + y);
            END;
            $$
            """);
        assertEquals(30, scalar("SELECT val FROM do_vars"));
    }

    @Test void doBlockWithRaise() throws SQLException {
        assertThrows(SQLException.class, () -> exec("""
            DO $$
            BEGIN
                RAISE EXCEPTION 'boom from DO';
            END;
            $$
            """));
    }

    @Test void raiseFormatPercentEscape() throws SQLException {
        exec("""
            CREATE FUNCTION plpct() RETURNS text AS $$
            BEGIN
                BEGIN
                    RAISE EXCEPTION '100%% complete: %', 'done';
                EXCEPTION
                    WHEN OTHERS THEN
                        RETURN SQLERRM;
                END;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals("100% complete: done", scalar("SELECT plpct()"));
    }

    // =====================================================================
    // FOREACH ARRAY loop
    // =====================================================================

    @Test void foreachArrayLoop() throws SQLException {
        exec("""
            CREATE FUNCTION sum_array(arr integer[]) RETURNS integer AS $$
            DECLARE
                total integer := 0;
                elem integer;
            BEGIN
                FOREACH elem IN ARRAY arr LOOP
                    total := total + elem;
                END LOOP;
                RETURN total;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(15, scalar("SELECT sum_array(ARRAY[1,2,3,4,5])"));
    }

    @Test void foreachArrayLoopWithExit() throws SQLException {
        exec("""
            CREATE FUNCTION first_negative(arr integer[]) RETURNS integer AS $$
            DECLARE
                elem integer;
            BEGIN
                FOREACH elem IN ARRAY arr LOOP
                    IF elem < 0 THEN RETURN elem; END IF;
                END LOOP;
                RETURN 0;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(-3, scalar("SELECT first_negative(ARRAY[5, 2, -3, 7])"));
        assertEquals(0, scalar("SELECT first_negative(ARRAY[1, 2, 3])"));
    }

    @Test void foreachNullArrayReturnsDefault() throws SQLException {
        exec("""
            CREATE FUNCTION foreach_null() RETURNS integer AS $$
            DECLARE
                elem integer;
                cnt integer := 0;
            BEGIN
                FOREACH elem IN ARRAY NULL::integer[] LOOP
                    cnt := cnt + 1;
                END LOOP;
                RETURN cnt;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(0, scalar("SELECT foreach_null()"));
    }

    // =====================================================================
    // Array subscript assignment
    // =====================================================================

    @Test void arraySubscriptAssignment() throws SQLException {
        exec("""
            CREATE FUNCTION arr_sub_test() RETURNS integer AS $$
            DECLARE
                arr integer[] := ARRAY[10, 20, 30];
            BEGIN
                arr[2] := 99;
                RETURN arr[2];
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(99, scalar("SELECT arr_sub_test()"));
    }

    @Test void arraySubscriptAssignExtend() throws SQLException {
        exec("""
            CREATE FUNCTION arr_extend_test() RETURNS integer AS $$
            DECLARE
                arr integer[];
            BEGIN
                arr[1] := 42;
                RETURN arr[1];
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(42, scalar("SELECT arr_extend_test()"));
    }

    // =====================================================================
    // Default parameter values
    // =====================================================================

    @Test void defaultParameterValue() throws SQLException {
        exec("""
            CREATE FUNCTION def_test(a integer, b integer DEFAULT 100) RETURNS integer AS $$
            BEGIN
                RETURN a + b;
            END;
            $$ LANGUAGE plpgsql
            """);
        // Call with both args
        assertEquals(30, scalar("SELECT def_test(10, 20)"));
        // Call with default
        assertEquals(110, scalar("SELECT def_test(10)"));
    }

    // =====================================================================
    // Named parameter calls
    // =====================================================================

    @Test void namedParameterCall() throws SQLException {
        exec("""
            CREATE FUNCTION named_test(x integer, y integer) RETURNS integer AS $$
            BEGIN
                RETURN x - y;
            END;
            $$ LANGUAGE plpgsql
            """);
        // Positional: 10 - 3 = 7
        assertEquals(7, scalar("SELECT named_test(10, 3)"));
        // Named (reversed): 3 - 10 = -7
        assertEquals(-7, scalar("SELECT named_test(y => 10, x => 3)"));
    }

    // =====================================================================
    // OUT parameters
    // =====================================================================

    @Test void outParameter() throws SQLException {
        exec("""
            CREATE FUNCTION out_test(a integer, OUT result integer) AS $$
            BEGIN
                result := a * 2;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(20, scalar("SELECT out_test(10)"));
    }

    // =====================================================================
    // pg_proc.prosrc exposes function body
    // =====================================================================

    @Test void pgProcProsrcExposed() throws SQLException {
        exec("""
            CREATE FUNCTION prosrc_test() RETURNS integer AS $$
            BEGIN RETURN 42; END;
            $$ LANGUAGE plpgsql
            """);
        Object src = scalar(
                "SELECT prosrc FROM pg_proc WHERE proname = 'prosrc_test'");
        assertNotNull(src);
        assertTrue(src.toString().contains("RETURN 42"));
    }

    // =====================================================================
    // information_schema.routines
    // =====================================================================

    @Test void informationSchemaRoutines() throws SQLException {
        exec("""
            CREATE FUNCTION is_routines_test() RETURNS integer AS $$
            BEGIN RETURN 1; END;
            $$ LANGUAGE plpgsql
            """);
        Object name = scalar(
                "SELECT routine_name FROM information_schema.routines WHERE routine_name = 'is_routines_test'");
        assertEquals("is_routines_test", name);
    }

    // =====================================================================
    // %TYPE declarations
    // =====================================================================

    @Test void percentType() throws SQLException {
        exec("CREATE TABLE pct_test (id integer, name text)");
        exec("""
            CREATE FUNCTION pct_type_test() RETURNS text AS $$
            DECLARE
                v pct_test.name%TYPE;
            BEGIN
                v := 'hello';
                RETURN v;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals("hello", scalar("SELECT pct_type_test()"));
    }

    // =====================================================================
    // %ROWTYPE declarations
    // =====================================================================

    @Test void percentRowtype() throws SQLException {
        exec("CREATE TABLE rt_test (id integer, val text)");
        exec("INSERT INTO rt_test VALUES (1, 'abc')");
        exec("""
            CREATE FUNCTION rowtype_test() RETURNS text AS $$
            DECLARE
                r rt_test%ROWTYPE;
            BEGIN
                SELECT id, val INTO r FROM rt_test WHERE id = 1;
                RETURN r.val;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals("abc", scalar("SELECT rowtype_test()"));
    }

    // =====================================================================
    // RECORD type
    // =====================================================================

    @Test void recordType() throws SQLException {
        exec("CREATE TABLE rec_test (id integer, name text)");
        exec("INSERT INTO rec_test VALUES (1, 'Alice'), (2, 'Bob')");
        exec("""
            CREATE FUNCTION record_test() RETURNS text AS $$
            DECLARE
                r RECORD;
            BEGIN
                SELECT id, name INTO r FROM rec_test WHERE id = 2;
                RETURN r.name;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals("Bob", scalar("SELECT record_test()"));
    }

    @Test void recordInForLoop() throws SQLException {
        exec("CREATE TABLE rfl_test (id integer, val integer)");
        exec("INSERT INTO rfl_test VALUES (1, 10), (2, 20), (3, 30)");
        exec("""
            CREATE FUNCTION record_for_test() RETURNS integer AS $$
            DECLARE
                r RECORD;
                total integer := 0;
            BEGIN
                FOR r IN SELECT id, val FROM rfl_test ORDER BY id LOOP
                    total := total + r.val;
                END LOOP;
                RETURN total;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(60, scalar("SELECT record_for_test()"));
    }

    // =====================================================================
    // SELECT INTO with extracted INTO clause
    // =====================================================================

    @Test void selectIntoVariable() throws SQLException {
        exec("CREATE TABLE sit_test (id integer, val text)");
        exec("INSERT INTO sit_test VALUES (1, 'hello')");
        exec("""
            CREATE FUNCTION select_into_test() RETURNS text AS $$
            DECLARE
                v text;
            BEGIN
                SELECT val INTO v FROM sit_test WHERE id = 1;
                RETURN v;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals("hello", scalar("SELECT select_into_test()"));
    }

    @Test void selectIntoStrict() throws SQLException {
        exec("CREATE TABLE sis_test (id integer)");
        exec("""
            CREATE FUNCTION strict_test() RETURNS integer AS $$
            DECLARE
                v integer;
            BEGIN
                SELECT id INTO STRICT v FROM sis_test WHERE id = 999;
                RETURN v;
            EXCEPTION WHEN no_data_found THEN
                RETURN -1;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(-1, scalar("SELECT strict_test()"));
    }

    // =====================================================================
    // VARIADIC array collection
    // =====================================================================

    @Test void variadicFunction() throws SQLException {
        exec("""
            CREATE FUNCTION variadic_sum(VARIADIC nums integer[]) RETURNS integer AS $$
            DECLARE
                total integer := 0;
                n integer;
            BEGIN
                FOREACH n IN ARRAY nums LOOP
                    total := total + n;
                END LOOP;
                RETURN total;
            END;
            $$ LANGUAGE plpgsql
            """);
        assertEquals(6, scalar("SELECT variadic_sum(1, 2, 3)"));
        assertEquals(10, scalar("SELECT variadic_sum(10)"));
    }
}
