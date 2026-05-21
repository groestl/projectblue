package org.pgjava.executor.plpgsql;

import org.pgjava.engine.Database;
import org.pgjava.engine.PgErrorException;
import org.pgjava.engine.Session;
import org.pgjava.executor.Evaluator;
import org.pgjava.executor.Planner;
import org.pgjava.executor.Operator;
import org.pgjava.sql.ast.*;
import org.pgjava.sql.ast.plpgsql.*;
import org.pgjava.sql.parser.ParserProvider;
import org.pgjava.storage.Row;
import org.pgjava.types.PgType;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tree-walking interpreter for PL/pgSQL function bodies.
 *
 * <p>Walks the AST produced by {@link org.pgjava.sql.parser.PlPgSqlBodyParser},
 * executing statements against the database. SQL expressions embedded in
 * PL/pgSQL are parsed via {@link ParserProvider} and evaluated using the
 * existing {@link Evaluator} infrastructure.
 */
public final class PlPgSqlInterpreter {

    private final Database      database;
    private final List<String>  searchPath;
    private final Object[]      params;       // $1...$N function arguments
    private final List<String>  paramNames;   // named parameter aliases
    private final PgType        returnType;

    /** Counter for generating unique savepoint names for EXCEPTION blocks. */
    private static final AtomicLong SAVEPOINT_SEQ = new AtomicLong();

    /**
     * The current exception being handled (set inside EXCEPTION handlers).
     * Used by {@code RAISE} with no arguments to re-raise the current exception.
     */
    private SQLException currentException;

    /** Trigger context — set when executing a trigger function. */
    private TriggerContext triggerCtx;

    /** OUT parameter names — set for functions with OUT/INOUT params. */
    private List<String> outParamNames;

    /** Notices produced by RAISE at non-EXCEPTION levels. */
    private final List<String> notices = new ArrayList<>();

    public PlPgSqlInterpreter(Database database, List<String> searchPath,
                              Object[] params, List<String> paramNames,
                              PgType returnType) {
        this.database   = database;
        this.searchPath = searchPath;
        this.params     = params;
        this.paramNames = paramNames;
        this.returnType = returnType;
    }

    /**
     * Execute a PL/pgSQL function body and return the result.
     */
    public Object execute(PlPgSqlBody body) throws SQLException {
        PlPgSqlScope scope = new PlPgSqlScope(null);

        // Bind named parameters as variables in the outermost scope
        if (paramNames != null) {
            for (int i = 0; i < paramNames.size(); i++) {
                String pname = paramNames.get(i);
                if (pname != null && !pname.isEmpty()) {
                    Object val = (i < params.length) ? params[i] : null;
                    scope.declare(new PlPgSqlVariable(pname, null, false, false, val));
                }
            }
        }

        // Declare OUT parameters as variables (initialized to null)
        if (outParamNames != null) {
            for (String outName : outParamNames) {
                if (outName != null && !outName.isEmpty() && scope.resolve(outName) == null) {
                    scope.declare(new PlPgSqlVariable(outName, null, false, false, null));
                }
            }
        }

        try {
            executeBlock(body, scope);
            // Function completed without explicit RETURN — collect OUT params
            return collectOutParams(scope);
        } catch (ControlFlowSignal.ReturnSignal ret) {
            // If bare RETURN with no expression and OUT params exist, collect them
            if (ret.value() == null && outParamNames != null && !outParamNames.isEmpty()) {
                return collectOutParams(scope);
            }
            return coerceReturn(ret.value());
        }
    }

    private Object collectOutParams(PlPgSqlScope scope) {
        if (outParamNames == null || outParamNames.isEmpty()) return null;
        if (outParamNames.size() == 1) {
            // Single OUT param: return its value directly
            PlPgSqlVariable v = scope.resolve(outParamNames.get(0));
            return v != null ? v.value() : null;
        }
        // Multiple OUT params: return as Object[] (record)
        Object[] result = new Object[outParamNames.size()];
        for (int i = 0; i < outParamNames.size(); i++) {
            PlPgSqlVariable v = scope.resolve(outParamNames.get(i));
            result[i] = v != null ? v.value() : null;
        }
        return result;
    }

    public void setTriggerContext(TriggerContext ctx) {
        this.triggerCtx = ctx;
    }

    public void setOutParamNames(List<String> names) {
        this.outParamNames = names;
    }

    /**
     * Execute a PL/pgSQL trigger function.
     *
     * <p>For BEFORE ROW triggers, the return value is the (possibly modified) NEW row,
     * or null to suppress the operation. For AFTER/STATEMENT triggers, the return value
     * is ignored.
     */
    public Object[] executeTrigger(PlPgSqlBody body) throws SQLException {
        PlPgSqlScope scope = new PlPgSqlScope(null);
        injectTriggerVariables(scope);

        try {
            executeBlock(body, scope);
            // No explicit RETURN — for BEFORE ROW, return NEW unchanged
            if (triggerCtx != null && triggerCtx.newRow() != null) {
                return triggerCtx.newRow();
            }
            return triggerCtx != null ? triggerCtx.oldRow() : null;
        } catch (ControlFlowSignal.ReturnSignal ret) {
            if (ret.value() == null) return null; // RETURN NULL suppresses the row
            // RETURN NEW or RETURN OLD — the return value should be the current NEW/OLD row
            if (ret.value() instanceof Object[] arr) return arr;
            // If they returned a single composite variable name, it was resolved already
            if (triggerCtx != null && triggerCtx.newRow() != null) return triggerCtx.newRow();
            return triggerCtx != null ? triggerCtx.oldRow() : null;
        }
    }

    private void injectTriggerVariables(PlPgSqlScope scope) throws SQLException {
        if (triggerCtx == null) return;

        // TG_* special variables
        scope.declare(new PlPgSqlVariable("tg_name", null, false, false, triggerCtx.tgName()));
        scope.declare(new PlPgSqlVariable("tg_when", null, false, false, triggerCtx.tgWhen()));
        scope.declare(new PlPgSqlVariable("tg_level", null, false, false, triggerCtx.tgLevel()));
        scope.declare(new PlPgSqlVariable("tg_op", null, false, false, triggerCtx.tgOp()));
        scope.declare(new PlPgSqlVariable("tg_table_name", null, false, false, triggerCtx.tgTableName()));
        scope.declare(new PlPgSqlVariable("tg_table_schema", null, false, false, triggerCtx.tgTableSchema()));
        scope.declare(new PlPgSqlVariable("tg_relid", null, false, false,
                triggerCtx.tableDef() != null ? triggerCtx.tableDef().oid() : 0L));
        scope.declare(new PlPgSqlVariable("tg_table_oid", null, false, false,
                triggerCtx.tableDef() != null ? triggerCtx.tableDef().oid() : 0L));
        scope.declare(new PlPgSqlVariable("tg_nargs", null, false, false,
                triggerCtx.tgArgv() != null ? triggerCtx.tgArgv().length : 0));
        // TG_ARGV is stored as a text[] — for now store the String[] directly
        scope.declare(new PlPgSqlVariable("tg_argv", null, false, false, triggerCtx.tgArgv()));

        // NEW and OLD are "special" composite variables — field access handled in createEvaluator
        if (triggerCtx.newRow() != null) {
            scope.declare(new PlPgSqlVariable("new", null, false, false, triggerCtx.newRow()));
        }
        if (triggerCtx.oldRow() != null) {
            scope.declare(new PlPgSqlVariable("old", null, true, false, triggerCtx.oldRow()));
        }
    }

    /** Accumulated rows for set-returning functions (RETURN NEXT / RETURN QUERY). */
    private List<Object[]> srfRows;

    /** Returns notices collected during execution (RAISE NOTICE/INFO/WARNING/etc.). */
    public List<String> notices() { return notices; }

    /**
     * Execute a PL/pgSQL set-returning function body, accumulating rows
     * via RETURN NEXT and RETURN QUERY.
     */
    public Iterable<Object[]> executeSrf(PlPgSqlBody body) throws SQLException {
        srfRows = new ArrayList<>();
        PlPgSqlScope scope = new PlPgSqlScope(null);

        if (paramNames != null) {
            for (int i = 0; i < paramNames.size(); i++) {
                String pname = paramNames.get(i);
                if (pname != null && !pname.isEmpty()) {
                    Object val = (i < params.length) ? params[i] : null;
                    scope.declare(new PlPgSqlVariable(pname, null, false, false, val));
                }
            }
        }

        try {
            executeBlock(body, scope);
        } catch (ControlFlowSignal.ReturnSignal ignored) {
            // RETURN with no value ends the SRF
        }
        return srfRows;
    }

    // =====================================================================
    // Block execution
    // =====================================================================

    private void executeBlock(PlPgSqlBody body, PlPgSqlScope parentScope) throws SQLException {
        PlPgSqlScope scope = new PlPgSqlScope(parentScope);

        // Process declarations
        for (PlPgSqlDecl decl : body.decls()) {
            executeDecl(decl, scope);
        }

        if (body.handlers().isEmpty()) {
            // No exception handlers — execute directly
            executeStmtList(body.stmts(), scope);
        } else {
            // Exception handlers present — use savepoint for rollback
            executeWithExceptionHandlers(body, scope);
        }
    }

    /**
     * Execute a block body with EXCEPTION WHEN handlers.
     *
     * <p>PostgreSQL creates a subtransaction (savepoint) before the block body.
     * If an exception occurs: rollback to savepoint, match handlers, execute handler.
     * On success: release savepoint.
     */
    private void executeWithExceptionHandlers(PlPgSqlBody body, PlPgSqlScope scope)
            throws SQLException {
        Session session = Session.current();
        String spName = "__plpgsql_exc_" + SAVEPOINT_SEQ.incrementAndGet();

        // Create savepoint before executing the body
        if (session != null) {
            session.plSavepoint(spName);
        }

        try {
            executeStmtList(body.stmts(), scope);
            // Success — release savepoint
            if (session != null) {
                session.plReleaseSavepoint(spName);
            }
        } catch (ControlFlowSignal sig) {
            // Control flow signals (RETURN, EXIT, CONTINUE) are not caught by EXCEPTION handlers
            if (session != null) {
                session.plReleaseSavepoint(spName);
            }
            throw sig;
        } catch (SQLException ex) {
            // Rollback to savepoint
            if (session != null) {
                session.plRollbackToSavepoint(spName);
            }

            // Extract SQLSTATE from the exception
            String sqlstate = ex.getSQLState();
            if (sqlstate == null) sqlstate = "XX000";

            // Try to match against handlers
            for (ExceptionHandler handler : body.handlers()) {
                if (handlerMatches(handler, sqlstate)) {
                    // Set SQLSTATE/SQLERRM variables in handler scope
                    PlPgSqlScope handlerScope = new PlPgSqlScope(scope);
                    handlerScope.declare(new PlPgSqlVariable(
                            "sqlstate", "text", false, false, sqlstate));
                    handlerScope.declare(new PlPgSqlVariable(
                            "sqlerrm", "text", false, false,
                            ex.getMessage() != null ? ex.getMessage() : ""));

                    // Track current exception for re-raise
                    SQLException prevException = this.currentException;
                    this.currentException = ex;
                    try {
                        executeStmtList(handler.stmts(), handlerScope);
                    } finally {
                        this.currentException = prevException;
                    }
                    return;
                }
            }

            // No handler matched — re-throw
            throw ex;
        }
    }

    private boolean handlerMatches(ExceptionHandler handler, String sqlstate) {
        for (String condition : handler.conditions()) {
            if (PlPgSqlExceptionConditions.matches(condition, sqlstate)) {
                return true;
            }
        }
        return false;
    }

    private void executeStmtList(List<PlPgSqlStmt> stmts, PlPgSqlScope scope) throws SQLException {
        for (PlPgSqlStmt stmt : stmts) {
            executeStmt(stmt, scope);
        }
    }

    // =====================================================================
    // Declarations
    // =====================================================================

    private void executeDecl(PlPgSqlDecl decl, PlPgSqlScope scope) throws SQLException {
        switch (decl) {
            case PlPgSqlDecl.VarDecl vd -> {
                Object defaultVal = null;
                if (vd.defaultExpr() != null) {
                    defaultVal = evalSqlExpr(vd.defaultExpr(), scope);
                }

                String resolvedType = vd.typeName();
                if ("%TYPE".equals(vd.copyType())) {
                    // table.column%TYPE — resolve column type from catalog
                    resolvedType = resolvePercentType(vd.typeName());
                    scope.declare(new PlPgSqlVariable(
                            vd.name(), resolvedType, vd.constant(), vd.notNull(), defaultVal));
                } else if ("%ROWTYPE".equals(vd.copyType())) {
                    // table%ROWTYPE — create composite variable with column names
                    var tableDef = database.catalog().resolveTable(vd.typeName(), searchPath);
                    var colNames = tableDef.columns().stream()
                            .map(c -> c.name().toLowerCase()).toList();
                    Object[] row = new Object[colNames.size()];
                    PlPgSqlVariable v = new PlPgSqlVariable(
                            vd.name(), "record", vd.constant(), vd.notNull(),
                            defaultVal != null ? defaultVal : row);
                    v.setColumnNames(colNames);
                    scope.declare(v);
                } else {
                    scope.declare(new PlPgSqlVariable(
                            vd.name(), resolvedType, vd.constant(), vd.notNull(), defaultVal));
                }
            }
            case PlPgSqlDecl.RecordDecl rd -> {
                scope.declare(new PlPgSqlVariable(rd.name(), "record", false, false, null));
            }
            case PlPgSqlDecl.AliasDecl ad -> {
                // Resolve alias target — alias is a live reference to the same variable
                PlPgSqlVariable target = null;
                if (ad.target().startsWith("$")) {
                    int idx = Integer.parseInt(ad.target().substring(1)) - 1;
                    if (paramNames != null && idx < paramNames.size()) {
                        target = scope.resolve(paramNames.get(idx));
                    }
                    if (target == null && idx >= 0 && idx < params.length) {
                        // No named param variable exists — create one to alias
                        target = new PlPgSqlVariable(ad.target(), null, false, false, params[idx]);
                    }
                } else {
                    target = scope.resolve(ad.target());
                }
                if (target != null) {
                    scope.declare(new PlPgSqlVariable(ad.name(), target));
                }
            }
            case PlPgSqlDecl.CursorDecl cd -> {
                // Register cursor with its bound query and parameter names for later OPEN
                List<String> cursorParams = cd.params() != null
                        ? cd.params().stream().map(p -> p.name().toLowerCase()).toList()
                        : List.of();
                PlPgSqlCursor cursor = new PlPgSqlCursor(cd.name(), cd.querySql(), cursorParams);
                scope.declareCursor(cursor);
                scope.declare(new PlPgSqlVariable(cd.name(), "refcursor", false, false, null));
            }
        }
    }

    // =====================================================================
    // Statement dispatch
    // =====================================================================

    private void executeStmt(PlPgSqlStmt stmt, PlPgSqlScope scope) throws SQLException {
        switch (stmt) {
            case PlPgSqlStmt.AssignStmt s -> executeAssign(s, scope);
            case PlPgSqlStmt.ReturnStmt s -> executeReturn(s, scope);
            case PlPgSqlStmt.NullStmt ignored -> { /* no-op */ }
            case PlPgSqlStmt.BlockStmt s -> executeBlock(s.body(), scope);
            case PlPgSqlStmt.PerformStmt s -> executePerform(s, scope);
            case PlPgSqlStmt.SqlStmt s -> executeSql(s, scope);

            // Control flow
            case PlPgSqlStmt.IfStmt s -> executeIf(s, scope);
            case PlPgSqlStmt.LoopStmt s -> executeLoop(s, scope);
            case PlPgSqlStmt.WhileLoopStmt s -> executeWhileLoop(s, scope);
            case PlPgSqlStmt.ForIntLoopStmt s -> executeForIntLoop(s, scope);
            case PlPgSqlStmt.ExitStmt s -> executeExit(s, scope);
            case PlPgSqlStmt.ContinueStmt s -> executeContinue(s, scope);
            case PlPgSqlStmt.CaseStmt s -> executeCase(s, scope);
            case PlPgSqlStmt.ForQueryLoopStmt s -> executeForQueryLoop(s, scope);

            // Phase 5: RAISE + ASSERT
            case PlPgSqlStmt.RaiseStmt s -> executeRaise(s, scope);
            case PlPgSqlStmt.AssertStmt s -> executeAssert(s, scope);

            // Phase 6: Dynamic SQL
            case PlPgSqlStmt.ExecuteStmt s -> executeExecute(s, scope);

            // Phase 8: Set-returning functions
            case PlPgSqlStmt.ReturnNextStmt s -> executeReturnNext(s, scope);
            case PlPgSqlStmt.ReturnQueryStmt s -> executeReturnQuery(s, scope);
            case PlPgSqlStmt.GetDiagnosticsStmt s -> executeGetDiagnostics(s, scope);
            // Phase 7: Cursors
            case PlPgSqlStmt.OpenCursorStmt s -> executeOpenCursor(s, scope);
            case PlPgSqlStmt.OpenCursorForQueryStmt s -> executeOpenCursorForQuery(s, scope);
            case PlPgSqlStmt.OpenCursorForExecuteStmt s -> executeOpenCursorForExecute(s, scope);
            case PlPgSqlStmt.FetchStmt s -> executeFetch(s, scope);
            case PlPgSqlStmt.MoveCursorStmt s -> executeMove(s, scope);
            case PlPgSqlStmt.CloseCursorStmt s -> executeCloseCursor(s, scope);
            case PlPgSqlStmt.ForCursorLoopStmt s -> executeForCursorLoop(s, scope);

            case PlPgSqlStmt.ForeachArrayLoopStmt s -> executeForeachArrayLoop(s, scope);
        }
    }

    // =====================================================================
    // Assignment
    // =====================================================================

    private void executeAssign(PlPgSqlStmt.AssignStmt s, PlPgSqlScope scope) throws SQLException {
        Object val = evalSqlExpr(s.expr(), scope);
        String target = s.target().toLowerCase();

        // Handle FOUND special variable
        if ("found".equals(target)) {
            scope.setFound(val instanceof Boolean b ? b : val != null);
            return;
        }

        // Parse target into varName, optional subscript, optional field
        // Patterns: "var", "var[idx]", "var.field", "var[idx].field"
        String varName = target;
        String subscriptExpr = null;
        String fieldName = null;

        int bracket = target.indexOf('[');
        int dot = target.indexOf('.');
        // If bracket appears before dot (or no dot), parse subscript first
        if (bracket > 0 && (dot < 0 || bracket < dot)) {
            varName = target.substring(0, bracket);
            int rbracket = target.indexOf(']', bracket);
            if (rbracket > bracket) {
                subscriptExpr = target.substring(bracket + 1, rbracket);
                // Check for dot after bracket
                if (rbracket + 1 < target.length() && target.charAt(rbracket + 1) == '.') {
                    fieldName = target.substring(rbracket + 2);
                }
            }
        } else if (dot > 0) {
            varName = target.substring(0, dot);
            fieldName = target.substring(dot + 1);
        }

        // Handle record/composite field assignment (rec.field := val)
        if (fieldName != null && subscriptExpr == null) {
            // OLD is read-only
            if ("old".equals(varName)) {
                throw PgErrorException.error("42601",
                        "record \"old\" is not assignable").build();
            }
            PlPgSqlVariable recVar = scope.resolve(varName);
            if (recVar != null && recVar.value() instanceof Object[] row) {
                // Trigger context: resolve column from tableDef
                if (triggerCtx != null && triggerCtx.tableDef() != null
                        && ("new".equals(varName) || "old".equals(varName))) {
                    var col = triggerCtx.tableDef().column(fieldName);
                    if (col != null) {
                        row[col.attnum() - 1] = val;
                        return;
                    }
                }
                // Generic record: look up field index from associated column metadata
                if (recVar.columnNames() != null) {
                    for (int i = 0; i < recVar.columnNames().size(); i++) {
                        if (recVar.columnNames().get(i).equalsIgnoreCase(fieldName)) {
                            row[i] = val;
                            return;
                        }
                    }
                }
                throw PgErrorException.error("42703",
                        "record \"" + varName + "\" has no field \"" + fieldName + "\"").build();
            }
            // Variable doesn't exist or isn't a record — fall through to error below
        }

        // Handle array subscript assignment (arr[idx] := val)
        if (subscriptExpr != null) {
            PlPgSqlVariable arrVar = scope.resolve(varName);
            if (arrVar == null) {
                throw PgErrorException.error("42704",
                        "variable \"" + varName + "\" does not exist").build();
            }
            // Evaluate subscript index
            Object idxObj = evalSqlExpr(subscriptExpr, scope);
            int idx = ((Number) idxObj).intValue();
            Object current = arrVar.value();
            if (current instanceof java.util.List<?>) {
                @SuppressWarnings("unchecked")
                java.util.List<Object> list = (java.util.List<Object>) current;
                // PG arrays are 1-based; extend if needed
                int zeroIdx = idx - 1;
                while (list.size() <= zeroIdx) list.add(null);
                list.set(zeroIdx, val);
            } else if (current instanceof Object[] arr) {
                // Convert to mutable list for easier manipulation
                java.util.List<Object> list = new java.util.ArrayList<>(java.util.Arrays.asList(arr));
                int zeroIdx = idx - 1;
                while (list.size() <= zeroIdx) list.add(null);
                list.set(zeroIdx, val);
                arrVar.setValue(list);
            } else if (current == null) {
                // Initialize a new array
                java.util.List<Object> list = new java.util.ArrayList<>();
                int zeroIdx = idx - 1;
                while (list.size() <= zeroIdx) list.add(null);
                list.set(zeroIdx, val);
                arrVar.setValue(list);
            } else {
                throw PgErrorException.error("42804",
                        "cannot subscript type " + current.getClass().getSimpleName()).build();
            }
            return;
        }

        // Simple variable assignment
        PlPgSqlVariable var = scope.resolve(varName);
        if (var == null) {
            throw PgErrorException.error("42704",
                    "variable \"" + s.target() + "\" does not exist").build();
        }
        var.setValue(coerceToType(val, var.typeName()));
    }

    // =====================================================================
    // RETURN
    // =====================================================================

    private void executeReturn(PlPgSqlStmt.ReturnStmt s, PlPgSqlScope scope) throws SQLException {
        Object val = null;
        if (s.expr() != null) {
            val = evalSqlExpr(s.expr(), scope);
        }
        throw new ControlFlowSignal.ReturnSignal(val);
    }

    // =====================================================================
    // RETURN NEXT / RETURN QUERY
    // =====================================================================

    private void executeReturnNext(PlPgSqlStmt.ReturnNextStmt s, PlPgSqlScope scope)
            throws SQLException {
        if (srfRows == null) {
            throw PgErrorException.error("0A000",
                    "cannot use RETURN NEXT in a non-set-returning function").build();
        }
        Object val = evalSqlExpr(s.expr(), scope);
        if (val instanceof Object[] row) {
            srfRows.add(row); // composite row — use directly
        } else {
            srfRows.add(new Object[]{ val }); // scalar — wrap
        }
    }

    private void executeReturnQuery(PlPgSqlStmt.ReturnQueryStmt s, PlPgSqlScope scope)
            throws SQLException {
        if (srfRows == null) {
            throw PgErrorException.error("0A000",
                    "cannot use RETURN QUERY in a non-set-returning function").build();
        }

        String sql;
        Object[] usingParams = null;
        if (s.isDynamic()) {
            // RETURN QUERY EXECUTE dynSql [USING param1, ...]
            Object sqlObj = evalSqlExpr(s.sql(), scope);
            if (sqlObj == null) {
                throw PgErrorException.error("22004",
                        "query string argument of EXECUTE is null").build();
            }
            sql = sqlObj.toString();
            if (s.usingExprs() != null && !s.usingExprs().isEmpty()) {
                usingParams = new Object[s.usingExprs().size()];
                for (int i = 0; i < s.usingExprs().size(); i++) {
                    usingParams[i] = evalSqlExpr(s.usingExprs().get(i), scope);
                }
            }
        } else {
            sql = s.sql();
        }

        var stmts = ParserProvider.parse(sql);
        if (stmts.isEmpty() || !(stmts.get(0) instanceof SelectStmt sel)) {
            throw PgErrorException.error("42601",
                    "RETURN QUERY requires a SELECT statement").build();
        }

        Evaluator eval = createEvaluator(scope);
        if (usingParams != null) eval.setFunctionParams(usingParams);
        Planner planner = new Planner(database.catalog(), database.storage(),
                database.txManager(), eval, searchPath);
        planner.setSnapshot(database.txManager().snapshotFor(0L));
        Operator root = planner.planSelect(sel);
        root.open();
        try {
            Row row;
            while ((row = root.next()) != null) {
                srfRows.add(row.values());
            }
        } finally {
            root.close();
        }
    }

    // =====================================================================
    // RAISE
    // =====================================================================

    private void executeRaise(PlPgSqlStmt.RaiseStmt s, PlPgSqlScope scope) throws SQLException {
        RaiseLevel level = s.level();
        if (level == null) level = RaiseLevel.EXCEPTION;

        // RAISE with no arguments inside an EXCEPTION handler → re-raise
        if (s.format() == null && s.conditionName() == null && s.sqlstate() == null
                && (s.formatArgs() == null || s.formatArgs().isEmpty())
                && (s.options() == null || s.options().isEmpty())) {
            if (currentException != null) {
                throw currentException;
            }
            // RAISE with no args outside handler — raise with default message
            throw PgErrorException.error("P0001", "").build();
        }

        // Determine SQLSTATE
        String sqlstate = "P0001"; // default for RAISE EXCEPTION
        String detail = null;
        String hint = null;

        // Process USING options (may override message, sqlstate, detail, hint)
        String messageOverride = null;
        if (s.options() != null) {
            for (RaiseOption opt : s.options()) {
                Object val = evalSqlExpr(opt.expr(), scope);
                String sval = val != null ? val.toString() : "";
                switch (opt.name().toUpperCase()) {
                    case "ERRCODE" -> sqlstate = sval;
                    case "MESSAGE" -> messageOverride = sval;
                    case "DETAIL"  -> detail = sval;
                    case "HINT"    -> hint = sval;
                    // COLUMN, CONSTRAINT, DATATYPE, TABLE, SCHEMA — stored but not used
                    default -> { }
                }
            }
        }

        // Handle RAISE with condition name
        if (s.conditionName() != null) {
            sqlstate = PlPgSqlExceptionConditions.toSqlState(s.conditionName());
            if (sqlstate == null) sqlstate = "P0001";
        }

        // Handle RAISE with explicit SQLSTATE
        if (s.sqlstate() != null) {
            sqlstate = s.sqlstate();
        }

        // Build the message from format string + args
        String message;
        if (messageOverride != null) {
            message = messageOverride;
        } else if (s.format() != null) {
            message = formatRaiseMessage(s.format(), s.formatArgs(), scope);
        } else {
            message = "";
        }

        if (level == RaiseLevel.EXCEPTION) {
            var builder = PgErrorException.error(sqlstate, message);
            if (detail != null) builder.detail(detail);
            if (hint != null) builder.hint(hint);
            throw builder.build();
        }

        // Non-exception levels: collect as notice
        notices.add(level.name() + ":  " + message);
    }

    /**
     * Format a RAISE message string, replacing % placeholders with evaluated arguments.
     * {@code %%} produces a literal {@code %}.
     */
    private String formatRaiseMessage(String format, List<String> args, PlPgSqlScope scope)
            throws SQLException {
        if (args == null || args.isEmpty()) return format;

        StringBuilder sb = new StringBuilder();
        int argIdx = 0;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '%') {
                if (i + 1 < format.length() && format.charAt(i + 1) == '%') {
                    sb.append('%');
                    i++; // skip second %
                } else if (argIdx < args.size()) {
                    Object val = evalSqlExpr(args.get(argIdx), scope);
                    sb.append(val != null ? val.toString() : "<NULL>");
                    argIdx++;
                } else {
                    // More %'s than arguments — PostgreSQL raises an error
                    throw PgErrorException.error("42601",
                            "too few parameters specified for RAISE").build();
                }
            } else {
                sb.append(c);
            }
        }
        if (argIdx < args.size()) {
            throw PgErrorException.error("42601",
                    "too many parameters specified for RAISE").build();
        }
        return sb.toString();
    }

    // =====================================================================
    // ASSERT
    // =====================================================================

    private void executeAssert(PlPgSqlStmt.AssertStmt s, PlPgSqlScope scope) throws SQLException {
        if (!evalBoolExpr(s.condition(), scope)) {
            String message = "assertion failed";
            if (s.message() != null) {
                Object msg = evalSqlExpr(s.message(), scope);
                if (msg != null) message = msg.toString();
            }
            throw PgErrorException.error("P0004", message).build();
        }
    }

    // =====================================================================
    // GET DIAGNOSTICS
    // =====================================================================

    private void executeGetDiagnostics(PlPgSqlStmt.GetDiagnosticsStmt s, PlPgSqlScope scope)
            throws SQLException {
        for (PlPgSqlStmt.DiagnosticsItem item : s.items()) {
            Object val = switch (item.tag().toUpperCase()) {
                case "ROW_COUNT" -> scope.rowCount();
                case "RESULT_OID" -> 0L;
                case "PG_CONTEXT" -> "";
                case "MESSAGE_TEXT" -> {
                    // In GET STACKED DIAGNOSTICS, return current exception message
                    yield currentException != null ? currentException.getMessage() : "";
                }
                case "RETURNED_SQLSTATE" -> {
                    yield currentException != null ? currentException.getSQLState() : "00000";
                }
                default -> null;
            };
            setVariable(item.variable(), val, scope);
        }
    }

    // =====================================================================
    // EXECUTE (dynamic SQL)
    // =====================================================================

    private void executeExecute(PlPgSqlStmt.ExecuteStmt s, PlPgSqlScope scope) throws SQLException {
        // Evaluate the dynamic SQL expression
        Object sqlObj = evalSqlExpr(s.dynamicSql(), scope);
        if (sqlObj == null) {
            throw PgErrorException.error("22004",
                    "query string argument of EXECUTE is null").build();
        }
        String dynSql = sqlObj.toString();

        // Evaluate USING parameters
        Object[] usingParams = null;
        if (s.usingExprs() != null && !s.usingExprs().isEmpty()) {
            usingParams = new Object[s.usingExprs().size()];
            for (int i = 0; i < s.usingExprs().size(); i++) {
                usingParams[i] = evalSqlExpr(s.usingExprs().get(i), scope);
            }
        }

        // Parse and execute
        var stmts = ParserProvider.parse(dynSql);
        if (stmts.isEmpty()) return;
        Stmt bodyStmt = stmts.get(0);

        if (bodyStmt instanceof SelectStmt sel) {
            Evaluator eval = createEvaluator(scope);
            if (usingParams != null) eval.setFunctionParams(usingParams);
            Planner planner = new Planner(database.catalog(), database.storage(),
                    database.txManager(), eval, searchPath);
            planner.setSnapshot(database.txManager().snapshotFor(0L));
            Operator root = planner.planSelect(sel);
            root.open();
            try {
                Row row = root.next();
                scope.setFound(row != null);

                // STRICT mode: validate row count before binding
                if (s.strict() && !s.intoVars().isEmpty()) {
                    if (row == null) {
                        throw PgErrorException.error("P0002", "query returned no rows").build();
                    }
                    Row extra = root.next();
                    if (extra != null) {
                        throw PgErrorException.error("P0003",
                                "query returned more than one row").build();
                    }
                }

                // INTO clause: bind variables
                if (!s.intoVars().isEmpty() && row != null) {
                    Object[] vals = row.values();
                    if (s.intoVars().size() == 1 && vals.length > 1) {
                        // Single target with multiple columns — assign as composite record
                        PlPgSqlVariable recVar = scope.resolve(s.intoVars().get(0).toLowerCase());
                        if (recVar != null) {
                            recVar.setValue(vals.clone());
                            recVar.setColumnNames(root.schema().names());
                        }
                    } else {
                        for (int i = 0; i < s.intoVars().size() && i < vals.length; i++) {
                            setVariable(s.intoVars().get(i), vals[i], scope);
                        }
                    }
                }
            } finally {
                root.close();
            }
        } else {
            // DML or DDL: execute via Session
            Session session = Session.current();
            org.pgjava.engine.QueryResult qr;
            if (session != null) {
                qr = session.execute(dynSql);
            } else {
                qr = database.openSession().execute(dynSql);
            }
            // PG: EXECUTE sets FOUND and ROW_COUNT for DML
            if (qr != null && qr.updateCount() >= 0) {
                scope.setFound(qr.updateCount() > 0);
                scope.setRowCount(qr.updateCount());
            }
        }
    }

    private void setVariable(String name, Object value, PlPgSqlScope scope) throws SQLException {
        String lower = name.toLowerCase();
        if ("found".equals(lower)) {
            scope.setFound(value instanceof Boolean b ? b : value != null);
            return;
        }
        PlPgSqlVariable var = scope.resolve(lower);
        if (var == null) {
            throw PgErrorException.error("42704",
                    "variable \"" + name + "\" does not exist").build();
        }
        var.setValue(value);
    }

    // =====================================================================
    // Control flow
    // =====================================================================

    private void executeIf(PlPgSqlStmt.IfStmt s, PlPgSqlScope scope) throws SQLException {
        if (evalBoolExpr(s.condition(), scope)) {
            executeStmtList(s.thenBody(), scope);
            return;
        }
        for (ElsifClause elsif : s.elsifs()) {
            if (evalBoolExpr(elsif.condition(), scope)) {
                executeStmtList(elsif.stmts(), scope);
                return;
            }
        }
        if (!s.elseBody().isEmpty()) {
            executeStmtList(s.elseBody(), scope);
        }
    }

    private void executeCase(PlPgSqlStmt.CaseStmt s, PlPgSqlScope scope) throws SQLException {
        if (s.operand() != null) {
            // Simple CASE: CASE operand WHEN val THEN ...
            Object operand = evalSqlExpr(s.operand(), scope);
            for (CaseWhenClause when : s.whens()) {
                for (String expr : when.exprs()) {
                    Object val = evalSqlExpr(expr, scope);
                    if (java.util.Objects.equals(operand, val)) {
                        executeStmtList(when.stmts(), scope);
                        return;
                    }
                }
            }
        } else {
            // Searched CASE: CASE WHEN cond THEN ...
            for (CaseWhenClause when : s.whens()) {
                for (String expr : when.exprs()) {
                    if (evalBoolExpr(expr, scope)) {
                        executeStmtList(when.stmts(), scope);
                        return;
                    }
                }
            }
        }
        if (!s.elseBody().isEmpty()) {
            executeStmtList(s.elseBody(), scope);
        } else if (s.operand() != null) {
            throw PgErrorException.error("20000", "case not found").build();
        }
    }

    private void executeLoop(PlPgSqlStmt.LoopStmt s, PlPgSqlScope scope) throws SQLException {
        while (true) {
            try {
                executeStmtList(s.body(), scope);
            } catch (ControlFlowSignal.ExitSignal exit) {
                if (exit.label() == null || exit.label().equals(s.label())) break;
                throw exit; // propagate to outer loop
            } catch (ControlFlowSignal.ContinueSignal cont) {
                if (cont.label() == null || cont.label().equals(s.label())) continue;
                throw cont;
            }
        }
    }

    private void executeWhileLoop(PlPgSqlStmt.WhileLoopStmt s, PlPgSqlScope scope) throws SQLException {
        while (evalBoolExpr(s.condition(), scope)) {
            try {
                executeStmtList(s.body(), scope);
            } catch (ControlFlowSignal.ExitSignal exit) {
                if (exit.label() == null || exit.label().equals(s.label())) break;
                throw exit;
            } catch (ControlFlowSignal.ContinueSignal cont) {
                if (cont.label() == null || cont.label().equals(s.label())) continue;
                throw cont;
            }
        }
    }

    private void executeForIntLoop(PlPgSqlStmt.ForIntLoopStmt s, PlPgSqlScope scope) throws SQLException {
        long lower = ((Number) evalSqlExpr(s.lower(), scope)).longValue();
        long upper = ((Number) evalSqlExpr(s.upper(), scope)).longValue();
        long step = s.step() != null ? ((Number) evalSqlExpr(s.step(), scope)).longValue() : 1;
        if (step <= 0) step = 1;

        // Declare or resolve loop variable
        PlPgSqlVariable loopVar = scope.resolve(s.varName());
        if (loopVar == null) {
            loopVar = new PlPgSqlVariable(s.varName(), "integer", false, false, null);
            scope.declare(loopVar);
        }

        scope.setFound(false);
        if (s.reverse()) {
            // REVERSE: first expr is high bound, second is low bound
            for (long i = lower; i >= upper; i -= step) {
                scope.setFound(true);
                loopVar.setValue(i);
                try {
                    executeStmtList(s.body(), scope);
                } catch (ControlFlowSignal.ExitSignal exit) {
                    if (exit.label() == null || exit.label().equals(s.label())) break;
                    throw exit;
                } catch (ControlFlowSignal.ContinueSignal cont) {
                    if (cont.label() == null || cont.label().equals(s.label())) continue;
                    throw cont;
                }
            }
        } else {
            for (long i = lower; i <= upper; i += step) {
                scope.setFound(true);
                loopVar.setValue(i);
                try {
                    executeStmtList(s.body(), scope);
                } catch (ControlFlowSignal.ExitSignal exit) {
                    if (exit.label() == null || exit.label().equals(s.label())) break;
                    throw exit;
                } catch (ControlFlowSignal.ContinueSignal cont) {
                    if (cont.label() == null || cont.label().equals(s.label())) continue;
                    throw cont;
                }
            }
        }
    }

    private void executeForQueryLoop(PlPgSqlStmt.ForQueryLoopStmt s, PlPgSqlScope scope) throws SQLException {
        var stmts = ParserProvider.parse(s.sql());
        if (stmts.isEmpty() || !(stmts.get(0) instanceof SelectStmt sel)) {
            throw PgErrorException.error("42601",
                    "FOR query loop requires a SELECT statement").build();
        }

        Evaluator eval = createEvaluator(scope);
        Planner planner = new Planner(database.catalog(), database.storage(),
                database.txManager(), eval, searchPath);
        planner.setSnapshot(database.txManager().snapshotFor(0L));
        Operator root = planner.planSelect(sel);

        // Declare or resolve target variables
        List<String> targets = s.targetVars();
        PlPgSqlVariable[] targetVars = new PlPgSqlVariable[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            PlPgSqlVariable v = scope.resolve(targets.get(i));
            if (v == null) {
                v = new PlPgSqlVariable(targets.get(i), null, false, false, null);
                scope.declare(v);
            }
            targetVars[i] = v;
        }

        scope.setFound(false);
        root.open();
        try {
            Row row;
            while ((row = root.next()) != null) {
                scope.setFound(true);
                Object[] vals = row.values();
                if (targets.size() == 1) {
                    PlPgSqlVariable tv = targetVars[0];
                    boolean isRecord = tv.typeName() == null
                            || "record".equalsIgnoreCase(tv.typeName());
                    if (isRecord || vals.length > 1) {
                        // RECORD target or multi-column: assign as composite
                        tv.setValue(vals.clone());
                        tv.setColumnNames(root.schema().names());
                    } else {
                        tv.setValue(vals[0]);
                    }
                } else {
                    for (int i = 0; i < targetVars.length && i < vals.length; i++) {
                        targetVars[i].setValue(vals[i]);
                    }
                }
                try {
                    executeStmtList(s.body(), scope);
                } catch (ControlFlowSignal.ExitSignal exit) {
                    if (exit.label() == null || exit.label().equals(s.label())) break;
                    throw exit;
                } catch (ControlFlowSignal.ContinueSignal cont) {
                    if (cont.label() == null || cont.label().equals(s.label())) continue;
                    throw cont;
                }
            }
        } finally {
            root.close();
        }
    }

    private void executeForeachArrayLoop(PlPgSqlStmt.ForeachArrayLoopStmt s, PlPgSqlScope scope)
            throws SQLException {
        Object arrVal = evalSqlExpr(s.arrayExpr(), scope);
        if (arrVal == null) {
            scope.setFound(false);
            return; // NULL array — no iterations
        }

        // Coerce to a list
        java.util.List<?> elements;
        if (arrVal instanceof java.util.List<?> list) {
            elements = list;
        } else if (arrVal instanceof Object[] oa) {
            elements = java.util.Arrays.asList(oa);
        } else {
            throw PgErrorException.error("42804",
                    "FOREACH expression must be an array, got " + arrVal.getClass().getSimpleName()).build();
        }

        // Declare or resolve loop variable
        PlPgSqlVariable loopVar = scope.resolve(s.varName());
        if (loopVar == null) {
            loopVar = new PlPgSqlVariable(s.varName(), null, false, false, null);
            scope.declare(loopVar);
        }

        scope.setFound(false);
        for (Object elem : elements) {
            scope.setFound(true);
            loopVar.setValue(elem);
            try {
                executeStmtList(s.body(), scope);
            } catch (ControlFlowSignal.ExitSignal exit) {
                if (exit.label() == null || exit.label().equals(s.label())) break;
                throw exit;
            } catch (ControlFlowSignal.ContinueSignal cont) {
                if (cont.label() == null || cont.label().equals(s.label())) continue;
                throw cont;
            }
        }
    }

    private void executeExit(PlPgSqlStmt.ExitStmt s, PlPgSqlScope scope) throws SQLException {
        if (s.whenCondition() != null && !evalBoolExpr(s.whenCondition(), scope)) {
            return; // condition not met, don't exit
        }
        throw new ControlFlowSignal.ExitSignal(s.label());
    }

    private void executeContinue(PlPgSqlStmt.ContinueStmt s, PlPgSqlScope scope) throws SQLException {
        if (s.whenCondition() != null && !evalBoolExpr(s.whenCondition(), scope)) {
            return;
        }
        throw new ControlFlowSignal.ContinueSignal(s.label());
    }

    // =====================================================================
    // SQL integration
    // =====================================================================

    private void executePerform(PlPgSqlStmt.PerformStmt s, PlPgSqlScope scope) throws SQLException {
        // PERFORM is SELECT without returning — set FOUND based on whether rows exist
        String sql = "SELECT " + s.sql();
        var stmts = ParserProvider.parse(sql);
        if (stmts.isEmpty()) return;
        Stmt bodyStmt = stmts.get(0);
        if (!(bodyStmt instanceof SelectStmt sel)) return;

        Evaluator eval = createEvaluator(scope);
        Planner planner = new Planner(database.catalog(), database.storage(),
                database.txManager(), eval, searchPath);
        planner.setSnapshot(database.txManager().snapshotFor(0L));
        Operator root = planner.planSelect(sel);
        root.open();
        try {
            scope.setFound(root.next() != null);
        } finally {
            root.close();
        }
    }

    private void executeSql(PlPgSqlStmt.SqlStmt s, PlPgSqlScope scope) throws SQLException {
        // Execute SQL statement and handle INTO clause if present in the SQL text
        String sql = s.sql();
        var stmts = ParserProvider.parse(sql);
        if (stmts.isEmpty()) return;
        Stmt bodyStmt = stmts.get(0);

        if (bodyStmt instanceof SelectStmt sel) {
            Evaluator eval = createEvaluator(scope);
            Planner planner = new Planner(database.catalog(), database.storage(),
                    database.txManager(), eval, searchPath);
            planner.setSnapshot(database.txManager().snapshotFor(0L));
            Operator root = planner.planSelect(sel);
            root.open();
            try {
                Row row = root.next();
                scope.setFound(row != null);

                // STRICT mode: validate row count before binding
                if (s.strict() && !s.intoVars().isEmpty()) {
                    if (row == null) {
                        throw PgErrorException.error("P0002", "query returned no rows").build();
                    }
                    Row extra = root.next();
                    if (extra != null) {
                        throw PgErrorException.error("P0003",
                                "query returned more than one row").build();
                    }
                }

                // INTO clause: bind variables
                if (!s.intoVars().isEmpty() && row != null) {
                    Object[] vals = row.values();
                    if (s.intoVars().size() == 1 && vals.length > 1) {
                        // Single target with multiple columns — assign as composite record
                        PlPgSqlVariable recVar = scope.resolve(s.intoVars().get(0).toLowerCase());
                        if (recVar != null) {
                            recVar.setValue(vals.clone());
                            recVar.setColumnNames(root.schema().names());
                        }
                    } else {
                        for (int i = 0; i < s.intoVars().size() && i < vals.length; i++) {
                            setVariable(s.intoVars().get(i), vals[i], scope);
                        }
                    }
                }
            } finally {
                root.close();
            }
        } else {
            // DML: execute with variable-resolver Evaluator via Session
            Session session = Session.current();
            org.pgjava.engine.QueryResult qr;
            if (session != null) {
                Evaluator eval = createEvaluator(scope);
                qr = session.plExecuteDml(bodyStmt, eval);
            } else {
                qr = database.openSession().execute(sql);
            }
            // PG: inline DML sets FOUND and ROW_COUNT
            if (qr != null && qr.updateCount() >= 0) {
                scope.setFound(qr.updateCount() > 0);
                scope.setRowCount(qr.updateCount());
            }
        }
    }

    // =====================================================================
    // Cursors
    // =====================================================================

    private PlPgSqlCursor requireCursor(String name, PlPgSqlScope scope) throws SQLException {
        PlPgSqlCursor cursor = scope.resolveCursor(name.toLowerCase());
        if (cursor == null) {
            throw PgErrorException.error("34000",
                    "cursor \"" + name + "\" does not exist").build();
        }
        return cursor;
    }

    private record MaterializedResult(List<Object[]> rows, List<String> columnNames) {}

    private List<Object[]> materializeQuery(String sql, PlPgSqlScope scope) throws SQLException {
        return materializeQueryWithNames(sql, scope).rows();
    }

    private MaterializedResult materializeQueryWithNames(String sql, PlPgSqlScope scope) throws SQLException {
        var stmts = ParserProvider.parse(sql);
        if (stmts.isEmpty() || !(stmts.get(0) instanceof SelectStmt sel)) {
            throw PgErrorException.error("42601",
                    "cursor query must be a SELECT").build();
        }
        Evaluator eval = createEvaluator(scope);
        Planner planner = new Planner(database.catalog(), database.storage(),
                database.txManager(), eval, searchPath);
        planner.setSnapshot(database.txManager().snapshotFor(0L));
        Operator root = planner.planSelect(sel);
        // Capture column names from output schema
        List<String> colNames = new ArrayList<>();
        for (int i = 0; i < root.schema().width(); i++) {
            colNames.add(root.schema().name(i));
        }
        root.open();
        try {
            List<Object[]> rows = new ArrayList<>();
            Row row;
            while ((row = root.next()) != null) {
                rows.add(row.values());
            }
            return new MaterializedResult(rows, colNames);
        } finally {
            root.close();
        }
    }

    private void executeOpenCursor(PlPgSqlStmt.OpenCursorStmt s, PlPgSqlScope scope) throws SQLException {
        PlPgSqlCursor cursor = requireCursor(s.name(), scope);
        if (cursor.isOpen()) {
            throw PgErrorException.error("24000",
                    "cursor \"" + s.name() + "\" already in use").build();
        }
        // Bound cursor — query comes from DECLARE, args are substituted
        String sql = cursor.querySql();
        if (sql == null) {
            throw PgErrorException.error("24000",
                    "cursor \"" + s.name() + "\" is not bound to a query").build();
        }
        // Cursor parameter substitution: bind args to declared param names as scope variables
        if (s.args() != null && !s.args().isEmpty() && !cursor.paramNames().isEmpty()) {
            PlPgSqlScope cursorScope = new PlPgSqlScope(scope);
            for (int i = 0; i < cursor.paramNames().size() && i < s.args().size(); i++) {
                Object argVal = evalSqlExpr(s.args().get(i), scope);
                cursorScope.declare(new PlPgSqlVariable(
                        cursor.paramNames().get(i), null, false, false, argVal));
            }
            var result = materializeQueryWithNames(sql, cursorScope);
            cursor.open(result.rows(), result.columnNames());
            return;
        }
        var result = materializeQueryWithNames(sql, scope);
        cursor.open(result.rows(), result.columnNames());
    }

    private void executeOpenCursorForQuery(PlPgSqlStmt.OpenCursorForQueryStmt s, PlPgSqlScope scope)
            throws SQLException {
        PlPgSqlCursor cursor = scope.resolveCursor(s.name().toLowerCase());
        if (cursor == null) {
            // Unbound cursor — create it on the fly
            cursor = new PlPgSqlCursor(s.name(), s.sql());
            scope.declareCursor(cursor);
        }
        if (cursor.isOpen()) {
            throw PgErrorException.error("24000",
                    "cursor \"" + s.name() + "\" already in use").build();
        }
        var result = materializeQueryWithNames(s.sql(), scope);
        cursor.open(result.rows(), result.columnNames());
    }

    private void executeOpenCursorForExecute(PlPgSqlStmt.OpenCursorForExecuteStmt s, PlPgSqlScope scope)
            throws SQLException {
        PlPgSqlCursor cursor = scope.resolveCursor(s.name().toLowerCase());
        if (cursor == null) {
            cursor = new PlPgSqlCursor(s.name(), null);
            scope.declareCursor(cursor);
        }
        if (cursor.isOpen()) {
            throw PgErrorException.error("24000",
                    "cursor \"" + s.name() + "\" already in use").build();
        }
        Object sqlObj = evalSqlExpr(s.dynamicSql(), scope);
        if (sqlObj == null) {
            throw PgErrorException.error("22004",
                    "query string argument of EXECUTE is null").build();
        }
        var result = materializeQueryWithNames(sqlObj.toString(), scope);
        cursor.open(result.rows(), result.columnNames());
    }

    private void executeFetch(PlPgSqlStmt.FetchStmt s, PlPgSqlScope scope) throws SQLException {
        PlPgSqlCursor cursor = requireCursor(s.cursorName(), scope);
        if (!cursor.isOpen()) {
            throw PgErrorException.error("24000",
                    "cursor \"" + s.cursorName() + "\" is not open").build();
        }

        Object[] row = fetchRow(cursor, s.direction(), s.directionExpr(), scope);
        scope.setFound(row != null);

        if (row != null && s.targetVars() != null) {
            if (s.targetVars().size() == 1 && row.length > 1) {
                // Single target with multiple columns — assign as composite record
                PlPgSqlVariable recVar = scope.resolve(s.targetVars().get(0).toLowerCase());
                if (recVar != null) {
                    recVar.setValue(row.clone());
                    if (!cursor.columnNames().isEmpty()) {
                        recVar.setColumnNames(cursor.columnNames());
                    }
                }
            } else {
                for (int i = 0; i < s.targetVars().size() && i < row.length; i++) {
                    setVariable(s.targetVars().get(i), row[i], scope);
                }
            }
        }
    }

    private void executeMove(PlPgSqlStmt.MoveCursorStmt s, PlPgSqlScope scope) throws SQLException {
        PlPgSqlCursor cursor = requireCursor(s.cursorName(), scope);
        if (!cursor.isOpen()) {
            throw PgErrorException.error("24000",
                    "cursor \"" + s.cursorName() + "\" is not open").build();
        }
        // MOVE just repositions without returning data
        Object[] row = fetchRow(cursor, s.direction(), s.directionExpr(), scope);
        scope.setFound(row != null);
    }

    private void executeCloseCursor(PlPgSqlStmt.CloseCursorStmt s, PlPgSqlScope scope) throws SQLException {
        PlPgSqlCursor cursor = requireCursor(s.cursorName(), scope);
        if (!cursor.isOpen()) {
            throw PgErrorException.error("24000",
                    "cursor \"" + s.cursorName() + "\" is not open").build();
        }
        cursor.close();
    }

    private void executeForCursorLoop(PlPgSqlStmt.ForCursorLoopStmt s, PlPgSqlScope scope)
            throws SQLException {
        PlPgSqlCursor cursor = requireCursor(s.cursorName(), scope);
        if (!cursor.isOpen()) {
            // Auto-open bound cursor
            if (cursor.querySql() != null) {
                cursor.open(materializeQuery(cursor.querySql(), scope));
            } else {
                throw PgErrorException.error("24000",
                        "cursor \"" + s.cursorName() + "\" is not open").build();
            }
        }

        PlPgSqlVariable recVar = scope.resolve(s.recordVar());
        if (recVar == null) {
            recVar = new PlPgSqlVariable(s.recordVar(), null, false, false, null);
            scope.declare(recVar);
        }

        scope.setFound(false);
        Object[] row;
        while ((row = cursor.fetchNext()) != null) {
            scope.setFound(true);
            recVar.setValue(row.length == 1 ? row[0] : row);
            try {
                executeStmtList(s.body(), scope);
            } catch (ControlFlowSignal.ExitSignal exit) {
                if (exit.label() == null || exit.label().equals(s.label())) break;
                throw exit;
            } catch (ControlFlowSignal.ContinueSignal cont) {
                if (cont.label() == null || cont.label().equals(s.label())) continue;
                throw cont;
            }
        }
        cursor.close();
    }

    private Object[] fetchRow(PlPgSqlCursor cursor, FetchDirection dir, String dirExpr,
                              PlPgSqlScope scope) throws SQLException {
        if (dir == null) dir = FetchDirection.NEXT;
        return switch (dir) {
            case NEXT -> cursor.fetchNext();
            case PRIOR -> cursor.fetchPrior();
            case FIRST -> cursor.fetchFirst();
            case LAST -> cursor.fetchLast();
            case ABSOLUTE -> {
                int n = ((Number) evalSqlExpr(dirExpr, scope)).intValue();
                yield cursor.fetchAbsolute(n);
            }
            case RELATIVE -> {
                int n = ((Number) evalSqlExpr(dirExpr, scope)).intValue();
                yield cursor.fetchRelative(n);
            }
            case FORWARD -> cursor.fetchNext();
            case FORWARD_ALL -> {
                var all = cursor.fetchForwardAll();
                yield all.isEmpty() ? null : all.getLast();
            }
            case BACKWARD -> cursor.fetchPrior();
            case BACKWARD_ALL -> {
                // Move to before first
                Object[] last = null;
                Object[] r;
                while ((r = cursor.fetchPrior()) != null) last = r;
                yield last;
            }
        };
    }

    // =====================================================================
    // Expression evaluation
    // =====================================================================

    /**
     * Evaluate a SQL expression string, with PL/pgSQL variables injected as parameters.
     */
    private Object evalSqlExpr(String exprText, PlPgSqlScope scope) throws SQLException {
        // Wrap expression as: SELECT (expr)
        String sql = "SELECT (" + exprText + ")";
        var stmts = ParserProvider.parse(sql);
        if (stmts.isEmpty()) return null;
        Stmt bodyStmt = stmts.get(0);
        if (!(bodyStmt instanceof SelectStmt sel)) return null;

        Evaluator eval = createEvaluator(scope);
        Planner planner = new Planner(database.catalog(), database.storage(),
                database.txManager(), eval, searchPath);
        planner.setSnapshot(database.txManager().snapshotFor(0L));
        Operator root = planner.planSelect(sel);
        root.open();
        try {
            Row row = root.next();
            return row == null ? null : row.values()[0];
        } finally {
            root.close();
        }
    }

    /**
     * Evaluate a SQL expression expected to return a boolean.
     */
    private boolean evalBoolExpr(String exprText, PlPgSqlScope scope) throws SQLException {
        Object val = evalSqlExpr(exprText, scope);
        if (val instanceof Boolean b) return b;
        if (val == null) return false;
        // Coerce string "true"/"false"
        if (val instanceof String s) return "true".equalsIgnoreCase(s) || "t".equalsIgnoreCase(s);
        return false;
    }

    /**
     * Create an Evaluator with PL/pgSQL variable values injected as function params.
     * Variable references in SQL expressions are handled by wrapping them as $N params.
     */
    private Evaluator createEvaluator(PlPgSqlScope scope) {
        Evaluator eval = new Evaluator(database.catalog().functions(), database.collation());
        eval.setFunctionParams(params);
        // Register a variable resolver so the Evaluator can access PL/pgSQL variables
        eval.setVariableResolver(new Evaluator.VariableResolver() {
            @Override
            public Object resolve(String name) throws SQLException {
                if ("found".equalsIgnoreCase(name)) return scope.isFound();
                if ("sqlstate".equalsIgnoreCase(name)) {
                    PlPgSqlVariable v = scope.resolve("sqlstate");
                    return v != null ? v.value() : null;
                }
                if ("sqlerrm".equalsIgnoreCase(name)) {
                    PlPgSqlVariable v = scope.resolve("sqlerrm");
                    return v != null ? v.value() : null;
                }
                PlPgSqlVariable var = scope.resolve(name);
                if (var == null)
                    throw new SQLException("column \"" + name + "\" not found");
                return var.value();
            }

            @Override
            public Object resolveField(String qualifier, String field) throws SQLException {
                // Trigger context: NEW/OLD use TableDef column lookup
                if (triggerCtx != null) {
                    Object[] row = null;
                    if ("new".equalsIgnoreCase(qualifier)) {
                        PlPgSqlVariable v = scope.resolve("new");
                        row = v != null ? (Object[]) v.value() : triggerCtx.newRow();
                    } else if ("old".equalsIgnoreCase(qualifier)) {
                        PlPgSqlVariable v = scope.resolve("old");
                        row = v != null ? (Object[]) v.value() : triggerCtx.oldRow();
                    }
                    if (row != null) {
                        // Try tableDef column lookup first
                        if (triggerCtx.tableDef() != null) {
                            var col = triggerCtx.tableDef().column(field);
                            if (col != null) {
                                int idx = col.attnum() - 1;
                                return idx < row.length ? row[idx] : null;
                            }
                        }
                        // Fall back to columnNames array (for INSTEAD OF triggers on views)
                        if (triggerCtx.columnNames() != null) {
                            for (int i = 0; i < triggerCtx.columnNames().length; i++) {
                                if (field.equalsIgnoreCase(triggerCtx.columnNames()[i])) {
                                    return i < row.length ? row[i] : null;
                                }
                            }
                        }
                    }
                }
                // Generic record/composite variable: use columnNames for field lookup
                PlPgSqlVariable recVar = scope.resolve(qualifier);
                if (recVar != null && recVar.value() instanceof Object[] row
                        && recVar.columnNames() != null) {
                    for (int i = 0; i < recVar.columnNames().size(); i++) {
                        if (recVar.columnNames().get(i).equalsIgnoreCase(field)) {
                            return i < row.length ? row[i] : null;
                        }
                    }
                }
                return UNRESOLVED;
            }
        });
        return eval;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private Object coerceReturn(Object value) {
        // Basic type coercion for return values
        if (value == null || returnType == null) return value;
        return value;
    }

    /**
     * Coerce a value to match a PL/pgSQL variable's declared type.
     * Handles the common cases: string→numeric, numeric→text, etc.
     */
    private String resolvePercentType(String ref) throws SQLException {
        // ref is "table.column" — split and resolve from catalog
        int dot = ref.lastIndexOf('.');
        if (dot <= 0) {
            throw PgErrorException.error("42601",
                    "invalid %TYPE reference: \"" + ref + "\"").build();
        }
        String tablePart = ref.substring(0, dot);
        String colName = ref.substring(dot + 1);
        var tableDef = database.catalog().resolveTable(tablePart, searchPath);
        var col = tableDef.column(colName);
        if (col == null) {
            throw PgErrorException.error("42703",
                    "column \"" + colName + "\" of relation \"" + tablePart + "\" does not exist").build();
        }
        return col.type().name();
    }

    private static Object coerceToType(Object val, String typeName) {
        if (val == null || typeName == null) return val;
        String tn = typeName.toLowerCase();
        try {
            return switch (tn) {
                case "integer", "int", "int4" -> {
                    if (val instanceof Number n) yield n.intValue();
                    if (val instanceof String s) yield Integer.parseInt(s.strip());
                    yield val;
                }
                case "bigint", "int8" -> {
                    if (val instanceof Number n) yield n.longValue();
                    if (val instanceof String s) yield Long.parseLong(s.strip());
                    yield val;
                }
                case "smallint", "int2" -> {
                    if (val instanceof Number n) yield (short) n.intValue();
                    if (val instanceof String s) yield Short.parseShort(s.strip());
                    yield val;
                }
                case "numeric", "decimal" -> {
                    if (val instanceof java.math.BigDecimal) yield val;
                    if (val instanceof Number n) yield java.math.BigDecimal.valueOf(n.doubleValue());
                    if (val instanceof String s) yield new java.math.BigDecimal(s.strip());
                    yield val;
                }
                case "real", "float4" -> {
                    if (val instanceof Number n) yield n.floatValue();
                    if (val instanceof String s) yield Float.parseFloat(s.strip());
                    yield val;
                }
                case "double precision", "float8" -> {
                    if (val instanceof Number n) yield n.doubleValue();
                    if (val instanceof String s) yield Double.parseDouble(s.strip());
                    yield val;
                }
                case "text", "varchar", "character varying", "name" -> {
                    yield val.toString();
                }
                case "boolean", "bool" -> {
                    if (val instanceof Boolean) yield val;
                    if (val instanceof String s) yield switch (s.strip().toLowerCase()) {
                        case "t", "true", "yes", "on", "1" -> true;
                        case "f", "false", "no", "off", "0" -> false;
                        default -> val;
                    };
                    if (val instanceof Number n) yield n.intValue() != 0;
                    yield val;
                }
                default -> val;
            };
        } catch (NumberFormatException e) {
            return val; // if coercion fails, pass through — runtime will catch type mismatches
        }
    }

    private static SQLException notImpl(String feature) {
        return PgErrorException.error("0A000",
                "PL/pgSQL " + feature + " is not yet implemented").build();
    }
}
