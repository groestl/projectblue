package org.pgjava.sql.ast.plpgsql;

import java.util.List;

/** Sealed base for all PL/pgSQL statement nodes. */
public sealed interface PlPgSqlStmt extends PlPgSqlNode {

    // =====================================================================
    // Assignment
    // =====================================================================

    /** {@code target := expr} */
    record AssignStmt(String target, String expr) implements PlPgSqlStmt {}

    // =====================================================================
    // Return
    // =====================================================================

    /** {@code RETURN [expr]} */
    record ReturnStmt(String expr) implements PlPgSqlStmt {}

    /** {@code RETURN NEXT expr} */
    record ReturnNextStmt(String expr) implements PlPgSqlStmt {}

    /** {@code RETURN QUERY sql} or {@code RETURN QUERY EXECUTE dynSql [USING ...]} */
    record ReturnQueryStmt(String sql, boolean isDynamic,
                           List<String> usingExprs) implements PlPgSqlStmt {}

    // =====================================================================
    // Control flow
    // =====================================================================

    /** {@code IF cond THEN stmts [ELSIF ...] [ELSE stmts] END IF} */
    record IfStmt(String condition, List<PlPgSqlStmt> thenBody,
                  List<ElsifClause> elsifs,
                  List<PlPgSqlStmt> elseBody) implements PlPgSqlStmt {}

    /** {@code CASE [operand] WHEN ... THEN ... [ELSE ...] END CASE} */
    record CaseStmt(String operand, List<CaseWhenClause> whens,
                    List<PlPgSqlStmt> elseBody) implements PlPgSqlStmt {}

    /** {@code [<<label>>] LOOP stmts END LOOP} */
    record LoopStmt(String label, List<PlPgSqlStmt> body) implements PlPgSqlStmt {}

    /** {@code [<<label>>] WHILE cond LOOP stmts END LOOP} */
    record WhileLoopStmt(String label, String condition,
                         List<PlPgSqlStmt> body) implements PlPgSqlStmt {}

    /** {@code [<<label>>] FOR var IN [REVERSE] lower..upper [BY step] LOOP stmts END LOOP} */
    record ForIntLoopStmt(String label, String varName,
                          String lower, String upper, String step,
                          boolean reverse,
                          List<PlPgSqlStmt> body) implements PlPgSqlStmt {}

    /** {@code [<<label>>] FOR record IN query LOOP stmts END LOOP} */
    record ForQueryLoopStmt(String label, List<String> targetVars,
                            String sql,
                            List<PlPgSqlStmt> body) implements PlPgSqlStmt {}

    /** {@code [<<label>>] FOR record IN cursor_name [(args)] LOOP stmts END LOOP} */
    record ForCursorLoopStmt(String label, String recordVar,
                             String cursorName, List<String> cursorArgs,
                             List<PlPgSqlStmt> body) implements PlPgSqlStmt {}

    /** {@code [<<label>>] FOREACH var [SLICE n] IN ARRAY expr LOOP stmts END LOOP} */
    record ForeachArrayLoopStmt(String label, String varName,
                                Integer slice, String arrayExpr,
                                List<PlPgSqlStmt> body) implements PlPgSqlStmt {}

    /** {@code EXIT [label] [WHEN cond]} */
    record ExitStmt(String label, String whenCondition) implements PlPgSqlStmt {}

    /** {@code CONTINUE [label] [WHEN cond]} */
    record ContinueStmt(String label, String whenCondition) implements PlPgSqlStmt {}

    // =====================================================================
    // RAISE
    // =====================================================================

    /** {@code RAISE [level] [format, args] [USING ...]} */
    record RaiseStmt(RaiseLevel level, String format,
                     String conditionName, String sqlstate,
                     List<String> formatArgs,
                     List<RaiseOption> options) implements PlPgSqlStmt {}

    // =====================================================================
    // SQL integration
    // =====================================================================

    /** {@code PERFORM query} — execute SELECT, discard result, set FOUND */
    record PerformStmt(String sql) implements PlPgSqlStmt {}

    /** {@code EXECUTE dynSql [INTO [STRICT] vars] [USING params]} */
    record ExecuteStmt(String dynamicSql, List<String> intoVars,
                       boolean strict,
                       List<String> usingExprs) implements PlPgSqlStmt {}

    /** SQL statement (SELECT INTO, DML, DML RETURNING INTO) — raw SQL text */
    record SqlStmt(String sql, List<String> intoVars, boolean strict) implements PlPgSqlStmt {
        /** Backwards-compatible: SQL without INTO extraction. */
        SqlStmt(String sql) { this(sql, List.of(), false); }
    }

    /** {@code GET [STACKED] DIAGNOSTICS var = item [, ...]} */
    record GetDiagnosticsStmt(boolean stacked,
                              List<DiagnosticsItem> items) implements PlPgSqlStmt {}

    record DiagnosticsItem(String variable, String tag) implements PlPgSqlNode {}

    // =====================================================================
    // ASSERT, NULL, Block
    // =====================================================================

    /** {@code ASSERT condition [, message]} */
    record AssertStmt(String condition, String message) implements PlPgSqlStmt {}

    /** {@code NULL} — no-op statement */
    record NullStmt() implements PlPgSqlStmt {}

    /** Nested block: {@code [<<label>>] [DECLARE ...] BEGIN ... END} */
    record BlockStmt(PlPgSqlBody body) implements PlPgSqlStmt {}

    // =====================================================================
    // Cursors
    // =====================================================================

    /** {@code OPEN cursor_name [(args)]} — bound cursor */
    record OpenCursorStmt(String name, List<String> args) implements PlPgSqlStmt {}

    /** {@code OPEN cursor_name FOR query} — unbound cursor for query */
    record OpenCursorForQueryStmt(String name, String sql,
                                  Boolean scroll) implements PlPgSqlStmt {}

    /** {@code OPEN cursor_name FOR EXECUTE dynSql [USING ...]} */
    record OpenCursorForExecuteStmt(String name, String dynamicSql,
                                    List<String> usingExprs) implements PlPgSqlStmt {}

    /** {@code FETCH [direction] [FROM|IN] cursor INTO target} */
    record FetchStmt(FetchDirection direction, String directionExpr,
                     String cursorName,
                     List<String> targetVars) implements PlPgSqlStmt {}

    /** {@code MOVE [direction] [FROM|IN] cursor} */
    record MoveCursorStmt(FetchDirection direction, String directionExpr,
                          String cursorName) implements PlPgSqlStmt {}

    /** {@code CLOSE cursor} */
    record CloseCursorStmt(String cursorName) implements PlPgSqlStmt {}
}
