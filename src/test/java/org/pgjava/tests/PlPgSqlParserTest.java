package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.pgjava.sql.ast.plpgsql.*;
import org.pgjava.sql.parser.PlPgSqlBodyParser;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 tests: verify PL/pgSQL body parsing produces correct AST nodes.
 * These tests validate parsing only — no execution.
 */
class PlPgSqlParserTest {

    private PlPgSqlBody parse(String body) {
        try {
            return PlPgSqlBodyParser.parse(body);
        } catch (Exception e) {
            throw new AssertionError("Parse failed: " + e.getMessage(), e);
        }
    }

    // =====================================================================
    // Minimal body
    // =====================================================================

    @Test void emptyBody() {
        var ast = parse("BEGIN END;");
        assertNull(ast.label());
        assertTrue(ast.decls().isEmpty());
        assertTrue(ast.stmts().isEmpty());
        assertTrue(ast.handlers().isEmpty());
    }

    @Test void bodyWithLabel() {
        var ast = parse("<<myblock>> BEGIN END myblock;");
        assertEquals("myblock", ast.label());
    }

    // =====================================================================
    // Declarations
    // =====================================================================

    @Test void simpleVarDecl() {
        var ast = parse("""
            DECLARE
                x integer;
            BEGIN
            END;
            """);
        assertEquals(1, ast.decls().size());
        var decl = (PlPgSqlDecl.VarDecl) ast.decls().get(0);
        assertEquals("x", decl.name());
        assertEquals("integer", decl.typeName().toLowerCase());
        assertFalse(decl.constant());
        assertFalse(decl.notNull());
        assertNull(decl.defaultExpr());
        assertNull(decl.copyType());
    }

    @Test void varDeclWithDefault() {
        var ast = parse("""
            DECLARE
                x integer := 42;
            BEGIN
            END;
            """);
        var decl = (PlPgSqlDecl.VarDecl) ast.decls().get(0);
        assertEquals("x", decl.name());
        assertEquals("42", decl.defaultExpr().trim());
    }

    @Test void varDeclConstantNotNull() {
        var ast = parse("""
            DECLARE
                pi CONSTANT numeric NOT NULL := 3.14159;
            BEGIN
            END;
            """);
        var decl = (PlPgSqlDecl.VarDecl) ast.decls().get(0);
        assertEquals("pi", decl.name());
        assertTrue(decl.constant());
        assertTrue(decl.notNull());
        assertEquals("3.14159", decl.defaultExpr().trim());
    }

    @Test void varDeclWithDefaultKeyword() {
        var ast = parse("""
            DECLARE
                x integer DEFAULT 10;
            BEGIN
            END;
            """);
        var decl = (PlPgSqlDecl.VarDecl) ast.decls().get(0);
        assertEquals("10", decl.defaultExpr().trim());
    }

    @Test void recordDecl() {
        var ast = parse("""
            DECLARE
                r RECORD;
            BEGIN
            END;
            """);
        assertInstanceOf(PlPgSqlDecl.RecordDecl.class, ast.decls().get(0));
        assertEquals("r", ((PlPgSqlDecl.RecordDecl) ast.decls().get(0)).name());
    }

    @Test void aliasDecl() {
        var ast = parse("""
            DECLARE
                myarg ALIAS FOR $1;
            BEGIN
            END;
            """);
        var decl = (PlPgSqlDecl.AliasDecl) ast.decls().get(0);
        assertEquals("myarg", decl.name());
        assertEquals("$1", decl.target());
    }

    @Test void aliasDeclForName() {
        var ast = parse("""
            DECLARE
                myarg ALIAS FOR other_name;
            BEGIN
            END;
            """);
        var decl = (PlPgSqlDecl.AliasDecl) ast.decls().get(0);
        assertEquals("other_name", decl.target());
    }

    @Test void cursorDecl() {
        var ast = parse("""
            DECLARE
                c CURSOR FOR SELECT id FROM t;
            BEGIN
            END;
            """);
        var decl = (PlPgSqlDecl.CursorDecl) ast.decls().get(0);
        assertEquals("c", decl.name());
        assertTrue(decl.params().isEmpty());
        assertNull(decl.scroll());
        assertTrue(decl.querySql().toUpperCase().contains("SELECT"));
    }

    @Test void cursorDeclWithParams() {
        var ast = parse("""
            DECLARE
                c CURSOR (key integer, val text) FOR SELECT * FROM t WHERE id = key;
            BEGIN
            END;
            """);
        var decl = (PlPgSqlDecl.CursorDecl) ast.decls().get(0);
        assertEquals(2, decl.params().size());
        assertEquals("key", decl.params().get(0).name());
        assertEquals("val", decl.params().get(1).name());
    }

    @Test void scrollCursorDecl() {
        var ast = parse("""
            DECLARE
                c NO SCROLL CURSOR FOR SELECT 1;
            BEGIN
            END;
            """);
        var decl = (PlPgSqlDecl.CursorDecl) ast.decls().get(0);
        assertEquals(Boolean.FALSE, decl.scroll());
    }

    @Test void copyTypeDecl() {
        var ast = parse("""
            DECLARE
                x t.col%TYPE;
            BEGIN
            END;
            """);
        var decl = (PlPgSqlDecl.VarDecl) ast.decls().get(0);
        assertEquals("%TYPE", decl.copyType());
    }

    @Test void rowTypeDecl() {
        var ast = parse("""
            DECLARE
                r mytable%ROWTYPE;
            BEGIN
            END;
            """);
        var decl = (PlPgSqlDecl.VarDecl) ast.decls().get(0);
        assertEquals("%ROWTYPE", decl.copyType());
    }

    @Test void multipleDecls() {
        var ast = parse("""
            DECLARE
                a integer;
                b text := 'hello';
                r RECORD;
            BEGIN
            END;
            """);
        assertEquals(3, ast.decls().size());
        assertInstanceOf(PlPgSqlDecl.VarDecl.class, ast.decls().get(0));
        assertInstanceOf(PlPgSqlDecl.VarDecl.class, ast.decls().get(1));
        assertInstanceOf(PlPgSqlDecl.RecordDecl.class, ast.decls().get(2));
    }

    // =====================================================================
    // Assignment
    // =====================================================================

    @Test void assignStmt() {
        var ast = parse("BEGIN x := 42; END;");
        assertEquals(1, ast.stmts().size());
        var s = (PlPgSqlStmt.AssignStmt) ast.stmts().get(0);
        assertEquals("x", s.target());
        assertEquals("42", s.expr().trim());
    }

    @Test void assignExpr() {
        var ast = parse("BEGIN x := a + b * 2; END;");
        var s = (PlPgSqlStmt.AssignStmt) ast.stmts().get(0);
        assertTrue(s.expr().contains("a + b * 2"));
    }

    // =====================================================================
    // RETURN
    // =====================================================================

    @Test void returnNoExpr() {
        var ast = parse("BEGIN RETURN; END;");
        var s = (PlPgSqlStmt.ReturnStmt) ast.stmts().get(0);
        assertNull(s.expr());
    }

    @Test void returnExpr() {
        var ast = parse("BEGIN RETURN x + 1; END;");
        var s = (PlPgSqlStmt.ReturnStmt) ast.stmts().get(0);
        assertTrue(s.expr().contains("x + 1"));
    }

    @Test void returnNext() {
        var ast = parse("BEGIN RETURN NEXT row_val; END;");
        var s = (PlPgSqlStmt.ReturnNextStmt) ast.stmts().get(0);
        assertTrue(s.expr().contains("row_val"));
    }

    @Test void returnQuery() {
        var ast = parse("BEGIN RETURN QUERY SELECT * FROM t; END;");
        var s = (PlPgSqlStmt.ReturnQueryStmt) ast.stmts().get(0);
        assertFalse(s.isDynamic());
        assertTrue(s.sql().toUpperCase().contains("SELECT"));
    }

    @Test void returnQueryExecute() {
        var ast = parse("BEGIN RETURN QUERY EXECUTE 'SELECT 1' USING x; END;");
        var s = (PlPgSqlStmt.ReturnQueryStmt) ast.stmts().get(0);
        assertTrue(s.isDynamic());
        assertEquals(1, s.usingExprs().size());
    }

    // =====================================================================
    // IF / ELSIF / ELSE
    // =====================================================================

    @Test void simpleIf() {
        var ast = parse("BEGIN IF x > 0 THEN y := 1; END IF; END;");
        var s = (PlPgSqlStmt.IfStmt) ast.stmts().get(0);
        assertTrue(s.condition().contains("x > 0"));
        assertEquals(1, s.thenBody().size());
        assertTrue(s.elsifs().isEmpty());
        assertTrue(s.elseBody().isEmpty());
    }

    @Test void ifElse() {
        var ast = parse("BEGIN IF x > 0 THEN y := 1; ELSE y := 0; END IF; END;");
        var s = (PlPgSqlStmt.IfStmt) ast.stmts().get(0);
        assertEquals(1, s.thenBody().size());
        assertEquals(1, s.elseBody().size());
    }

    @Test void ifElsif() {
        var ast = parse("""
            BEGIN
                IF x > 0 THEN y := 1;
                ELSIF x = 0 THEN y := 0;
                ELSIF x < 0 THEN y := -1;
                ELSE y := NULL;
                END IF;
            END;
            """);
        var s = (PlPgSqlStmt.IfStmt) ast.stmts().get(0);
        assertEquals(2, s.elsifs().size());
        assertEquals(1, s.elseBody().size());
    }

    // =====================================================================
    // CASE
    // =====================================================================

    @Test void simpleCaseStmt() {
        var ast = parse("""
            BEGIN
                CASE x
                    WHEN 1 THEN y := 'one';
                    WHEN 2 THEN y := 'two';
                    ELSE y := 'other';
                END CASE;
            END;
            """);
        var s = (PlPgSqlStmt.CaseStmt) ast.stmts().get(0);
        assertNotNull(s.operand());
        assertEquals(2, s.whens().size());
        assertEquals(1, s.elseBody().size());
    }

    @Test void searchedCaseStmt() {
        var ast = parse("""
            BEGIN
                CASE
                    WHEN x > 0 THEN y := 'pos';
                    WHEN x < 0 THEN y := 'neg';
                END CASE;
            END;
            """);
        var s = (PlPgSqlStmt.CaseStmt) ast.stmts().get(0);
        assertNull(s.operand());
        assertEquals(2, s.whens().size());
    }

    // =====================================================================
    // Loops
    // =====================================================================

    @Test void simpleLoop() {
        var ast = parse("BEGIN LOOP EXIT; END LOOP; END;");
        var s = (PlPgSqlStmt.LoopStmt) ast.stmts().get(0);
        assertNull(s.label());
        assertEquals(1, s.body().size());
    }

    @Test void whileLoop() {
        var ast = parse("BEGIN WHILE x > 0 LOOP x := x - 1; END LOOP; END;");
        var s = (PlPgSqlStmt.WhileLoopStmt) ast.stmts().get(0);
        assertTrue(s.condition().contains("x > 0"));
        assertEquals(1, s.body().size());
    }

    @Test void forIntLoop() {
        var ast = parse("BEGIN FOR i IN 1..10 LOOP NULL; END LOOP; END;");
        var s = (PlPgSqlStmt.ForIntLoopStmt) ast.stmts().get(0);
        assertEquals("i", s.varName());
        assertEquals("1", s.lower().trim());
        assertEquals("10", s.upper().trim());
        assertFalse(s.reverse());
        assertNull(s.step());
    }

    @Test void forIntLoopReverse() {
        var ast = parse("BEGIN FOR i IN REVERSE 10..1 BY 2 LOOP NULL; END LOOP; END;");
        var s = (PlPgSqlStmt.ForIntLoopStmt) ast.stmts().get(0);
        assertTrue(s.reverse());
        assertEquals("2", s.step().trim());
    }

    @Test void forQueryLoop() {
        var ast = parse("BEGIN FOR rec IN SELECT * FROM t LOOP NULL; END LOOP; END;");
        var s = (PlPgSqlStmt.ForQueryLoopStmt) ast.stmts().get(0);
        assertEquals(1, s.targetVars().size());
        assertEquals("rec", s.targetVars().get(0));
        assertTrue(s.sql().toUpperCase().contains("SELECT"));
    }

    @Test void foreachArrayLoop() {
        var ast = parse("BEGIN FOREACH x IN ARRAY arr LOOP NULL; END LOOP; END;");
        var s = (PlPgSqlStmt.ForeachArrayLoopStmt) ast.stmts().get(0);
        assertEquals("x", s.varName());
        assertNull(s.slice());
    }

    @Test void foreachSlice() {
        var ast = parse("BEGIN FOREACH x SLICE 1 IN ARRAY arr LOOP NULL; END LOOP; END;");
        var s = (PlPgSqlStmt.ForeachArrayLoopStmt) ast.stmts().get(0);
        assertEquals(Integer.valueOf(1), s.slice());
    }

    @Test void exitStmt() {
        var ast = parse("BEGIN EXIT; END;");
        var s = (PlPgSqlStmt.ExitStmt) ast.stmts().get(0);
        assertNull(s.label());
        assertNull(s.whenCondition());
    }

    @Test void exitWithLabel() {
        var ast = parse("BEGIN EXIT myloop; END;");
        var s = (PlPgSqlStmt.ExitStmt) ast.stmts().get(0);
        assertEquals("myloop", s.label());
    }

    @Test void exitWhen() {
        var ast = parse("BEGIN EXIT WHEN x > 10; END;");
        var s = (PlPgSqlStmt.ExitStmt) ast.stmts().get(0);
        assertTrue(s.whenCondition().contains("x > 10"));
    }

    @Test void continueStmt() {
        var ast = parse("BEGIN CONTINUE; END;");
        assertInstanceOf(PlPgSqlStmt.ContinueStmt.class, ast.stmts().get(0));
    }

    @Test void continueWhen() {
        var ast = parse("BEGIN CONTINUE WHEN x = 5; END;");
        var s = (PlPgSqlStmt.ContinueStmt) ast.stmts().get(0);
        assertTrue(s.whenCondition().contains("x = 5"));
    }

    // =====================================================================
    // RAISE
    // =====================================================================

    @Test void raiseSimple() {
        var ast = parse("BEGIN RAISE NOTICE 'hello %', x; END;");
        var s = (PlPgSqlStmt.RaiseStmt) ast.stmts().get(0);
        assertEquals(RaiseLevel.NOTICE, s.level());
        assertEquals("hello %", s.format());
        assertEquals(1, s.formatArgs().size());
    }

    @Test void raiseException() {
        var ast = parse("BEGIN RAISE EXCEPTION 'error: %', msg; END;");
        var s = (PlPgSqlStmt.RaiseStmt) ast.stmts().get(0);
        assertEquals(RaiseLevel.EXCEPTION, s.level());
    }

    @Test void raiseUsing() {
        var ast = parse("BEGIN RAISE EXCEPTION 'oops' USING ERRCODE = '22000'; END;");
        var s = (PlPgSqlStmt.RaiseStmt) ast.stmts().get(0);
        assertEquals(1, s.options().size());
        assertEquals("ERRCODE", s.options().get(0).name());
    }

    @Test void raiseNoLevel() {
        var ast = parse("BEGIN RAISE 'something went wrong'; END;");
        var s = (PlPgSqlStmt.RaiseStmt) ast.stmts().get(0);
        assertEquals(RaiseLevel.EXCEPTION, s.level());
    }

    @Test void raiseSqlstate() {
        var ast = parse("BEGIN RAISE SQLSTATE = '22012'; END;");
        var s = (PlPgSqlStmt.RaiseStmt) ast.stmts().get(0);
        assertEquals("22012", s.sqlstate());
    }

    @Test void raiseConditionName() {
        var ast = parse("BEGIN RAISE division_by_zero; END;");
        var s = (PlPgSqlStmt.RaiseStmt) ast.stmts().get(0);
        assertEquals("division_by_zero", s.conditionName());
    }

    // =====================================================================
    // PERFORM, EXECUTE, SQL
    // =====================================================================

    @Test void performStmt() {
        var ast = parse("BEGIN PERFORM pg_sleep(1); END;");
        var s = (PlPgSqlStmt.PerformStmt) ast.stmts().get(0);
        assertTrue(s.sql().contains("pg_sleep"));
    }

    @Test void executeStmt() {
        var ast = parse("BEGIN EXECUTE 'SELECT 1'; END;");
        var s = (PlPgSqlStmt.ExecuteStmt) ast.stmts().get(0);
        assertTrue(s.dynamicSql().contains("SELECT 1"));
        assertTrue(s.intoVars().isEmpty());
        assertFalse(s.strict());
    }

    @Test void executeIntoStrict() {
        var ast = parse("BEGIN EXECUTE 'SELECT 1' INTO STRICT x; END;");
        var s = (PlPgSqlStmt.ExecuteStmt) ast.stmts().get(0);
        assertEquals(1, s.intoVars().size());
        assertTrue(s.strict());
    }

    @Test void executeUsing() {
        var ast = parse("BEGIN EXECUTE 'DELETE FROM t WHERE id = $1' USING myid; END;");
        var s = (PlPgSqlStmt.ExecuteStmt) ast.stmts().get(0);
        assertEquals(1, s.usingExprs().size());
    }

    @Test void sqlSelectStmt() {
        var ast = parse("BEGIN SELECT count(*) INTO cnt FROM t; END;");
        var s = (PlPgSqlStmt.SqlStmt) ast.stmts().get(0);
        assertTrue(s.sql().toUpperCase().contains("SELECT"));
    }

    @Test void sqlInsertStmt() {
        var ast = parse("BEGIN INSERT INTO t (a) VALUES (1); END;");
        var s = (PlPgSqlStmt.SqlStmt) ast.stmts().get(0);
        assertTrue(s.sql().toUpperCase().contains("INSERT"));
    }

    // =====================================================================
    // GET DIAGNOSTICS
    // =====================================================================

    @Test void getDiagnostics() {
        var ast = parse("BEGIN GET DIAGNOSTICS cnt = ROW_COUNT; END;");
        var s = (PlPgSqlStmt.GetDiagnosticsStmt) ast.stmts().get(0);
        assertFalse(s.stacked());
        assertEquals(1, s.items().size());
        assertEquals("cnt", s.items().get(0).variable());
    }

    @Test void getDiagnosticsStacked() {
        var ast = parse("BEGIN GET STACKED DIAGNOSTICS v = PG_CONTEXT; END;");
        var s = (PlPgSqlStmt.GetDiagnosticsStmt) ast.stmts().get(0);
        assertTrue(s.stacked());
    }

    // =====================================================================
    // ASSERT, NULL
    // =====================================================================

    @Test void assertStmt() {
        var ast = parse("BEGIN ASSERT x > 0, 'must be positive'; END;");
        var s = (PlPgSqlStmt.AssertStmt) ast.stmts().get(0);
        assertTrue(s.condition().contains("x > 0"));
        assertTrue(s.message().contains("must be positive"));
    }

    @Test void assertNoMessage() {
        var ast = parse("BEGIN ASSERT x > 0; END;");
        var s = (PlPgSqlStmt.AssertStmt) ast.stmts().get(0);
        assertNull(s.message());
    }

    @Test void nullStmt() {
        var ast = parse("BEGIN NULL; END;");
        assertInstanceOf(PlPgSqlStmt.NullStmt.class, ast.stmts().get(0));
    }

    // =====================================================================
    // Nested blocks
    // =====================================================================

    @Test void nestedBlock() {
        var ast = parse("""
            BEGIN
                DECLARE
                    y integer := 0;
                BEGIN
                    y := 1;
                END;
            END;
            """);
        var s = (PlPgSqlStmt.BlockStmt) ast.stmts().get(0);
        assertEquals(1, s.body().decls().size());
        assertEquals(1, s.body().stmts().size());
    }

    // =====================================================================
    // Cursor operations
    // =====================================================================

    @Test void openCursorBound() {
        var ast = parse("BEGIN OPEN c; END;");
        var s = (PlPgSqlStmt.OpenCursorStmt) ast.stmts().get(0);
        assertEquals("c", s.name());
        assertTrue(s.args().isEmpty());
    }

    @Test void openCursorBoundWithArgs() {
        var ast = parse("BEGIN OPEN c(42); END;");
        var s = (PlPgSqlStmt.OpenCursorStmt) ast.stmts().get(0);
        assertEquals(1, s.args().size());
    }

    @Test void openCursorForQuery() {
        var ast = parse("BEGIN OPEN c FOR SELECT * FROM t; END;");
        var s = (PlPgSqlStmt.OpenCursorForQueryStmt) ast.stmts().get(0);
        assertTrue(s.sql().toUpperCase().contains("SELECT"));
    }

    @Test void openCursorForExecute() {
        var ast = parse("BEGIN OPEN c FOR EXECUTE 'SELECT 1'; END;");
        var s = (PlPgSqlStmt.OpenCursorForExecuteStmt) ast.stmts().get(0);
        assertTrue(s.dynamicSql().contains("SELECT 1"));
    }

    @Test void fetchStmt() {
        var ast = parse("BEGIN FETCH c INTO x; END;");
        var s = (PlPgSqlStmt.FetchStmt) ast.stmts().get(0);
        assertEquals(FetchDirection.NEXT, s.direction());
        assertEquals("c", s.cursorName());
        assertEquals(1, s.targetVars().size());
    }

    @Test void fetchNext() {
        var ast = parse("BEGIN FETCH NEXT FROM c INTO x; END;");
        var s = (PlPgSqlStmt.FetchStmt) ast.stmts().get(0);
        assertEquals(FetchDirection.NEXT, s.direction());
    }

    @Test void fetchPrior() {
        var ast = parse("BEGIN FETCH PRIOR FROM c INTO x; END;");
        var s = (PlPgSqlStmt.FetchStmt) ast.stmts().get(0);
        assertEquals(FetchDirection.PRIOR, s.direction());
    }

    @Test void moveStmt() {
        var ast = parse("BEGIN MOVE NEXT IN c; END;");
        var s = (PlPgSqlStmt.MoveCursorStmt) ast.stmts().get(0);
        assertEquals(FetchDirection.NEXT, s.direction());
    }

    @Test void closeStmt() {
        var ast = parse("BEGIN CLOSE c; END;");
        var s = (PlPgSqlStmt.CloseCursorStmt) ast.stmts().get(0);
        assertEquals("c", s.cursorName());
    }

    // =====================================================================
    // EXCEPTION handlers
    // =====================================================================

    @Test void exceptionOthers() {
        var ast = parse("""
            BEGIN
                NULL;
            EXCEPTION
                WHEN OTHERS THEN
                    RAISE NOTICE 'caught';
            END;
            """);
        assertEquals(1, ast.handlers().size());
        assertEquals("others", ast.handlers().get(0).conditions().get(0));
        assertEquals(1, ast.handlers().get(0).stmts().size());
    }

    @Test void exceptionMultipleHandlers() {
        var ast = parse("""
            BEGIN
                NULL;
            EXCEPTION
                WHEN division_by_zero THEN
                    NULL;
                WHEN unique_violation OR not_null_violation THEN
                    RAISE;
            END;
            """);
        assertEquals(2, ast.handlers().size());
        assertEquals(1, ast.handlers().get(0).conditions().size());
        assertEquals(2, ast.handlers().get(1).conditions().size());
    }

    @Test void exceptionSqlstate() {
        var ast = parse("""
            BEGIN
                NULL;
            EXCEPTION
                WHEN SQLSTATE '22012' THEN
                    NULL;
            END;
            """);
        assertEquals("SQLSTATE 22012", ast.handlers().get(0).conditions().get(0));
    }

    // =====================================================================
    // Labeled loops
    // =====================================================================

    @Test void labeledLoop() {
        var ast = parse("""
            BEGIN
                <<outer>>
                LOOP
                    EXIT outer;
                END LOOP outer;
            END;
            """);
        var s = (PlPgSqlStmt.LoopStmt) ast.stmts().get(0);
        assertEquals("outer", s.label());
    }

    // =====================================================================
    // Complex / compound bodies
    // =====================================================================

    @Test void complexFunction() {
        var ast = parse("""
            DECLARE
                total integer := 0;
                i integer;
            BEGIN
                FOR i IN 1..10 LOOP
                    total := total + i;
                END LOOP;
                RETURN total;
            END;
            """);
        assertEquals(2, ast.decls().size());
        assertEquals(2, ast.stmts().size());
        assertInstanceOf(PlPgSqlStmt.ForIntLoopStmt.class, ast.stmts().get(0));
        assertInstanceOf(PlPgSqlStmt.ReturnStmt.class, ast.stmts().get(1));
    }

    @Test void fibonacciFunction() {
        var ast = parse("""
            DECLARE
                a integer := 0;
                b integer := 1;
                temp integer;
                i integer;
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
            """);
        assertEquals(4, ast.decls().size());
        assertEquals(4, ast.stmts().size()); // 2 IFs + 1 FOR + 1 RETURN
    }

    @Test void exceptionWithSavepoint() {
        var ast = parse("""
            DECLARE
                result text;
            BEGIN
                BEGIN
                    INSERT INTO t (val) VALUES ('test');
                    result := 'ok';
                EXCEPTION WHEN unique_violation THEN
                    result := 'duplicate';
                END;
                RETURN result;
            END;
            """);
        assertEquals(1, ast.decls().size());
        assertEquals(2, ast.stmts().size());
        var block = (PlPgSqlStmt.BlockStmt) ast.stmts().get(0);
        assertEquals(1, block.body().handlers().size());
    }
}
