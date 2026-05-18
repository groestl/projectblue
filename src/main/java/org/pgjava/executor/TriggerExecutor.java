package org.pgjava.executor;

import org.pgjava.catalog.*;
import org.pgjava.engine.Database;
import org.pgjava.executor.plpgsql.PlPgSqlInterpreter;
import org.pgjava.executor.plpgsql.TriggerContext;
import org.pgjava.sql.ast.Expr;
import org.pgjava.sql.parser.PlPgSqlBodyParser;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Fires triggers attached to a table during DML operations.
 *
 * <p>Triggers are fired in name-sorted order (PostgreSQL semantics).
 * BEFORE ROW triggers can modify the NEW row or suppress the operation
 * by returning null.
 */
public final class TriggerExecutor {

    private TriggerExecutor() {}

    /** Maximum trigger recursion depth (PG default: no hard limit, but stack depth limits it). */
    private static final int MAX_TRIGGER_DEPTH = 64;
    private static final ThreadLocal<Integer> triggerDepth = ThreadLocal.withInitial(() -> 0);

    /**
     * Fire BEFORE ROW triggers for the given event.
     *
     * @return the (possibly modified) NEW row, or null to suppress the operation
     */
    public static Object[] fireBeforeRow(TableDef def, int event,
                                          Object[] newVals, Object[] oldVals,
                                          Database db, List<String> searchPath)
            throws SQLException {
        List<TriggerDef> triggers = collectTriggers(def, TriggerDef.BEFORE, event, true);
        // For DELETE, newVals is null; use oldVals as the "proceed" sentinel
        Object[] proceedVal = newVals != null ? newVals : oldVals;
        if (triggers.isEmpty()) return proceedVal;

        // Clone so trigger modifications don't affect the original until we're done
        Object[] current = newVals != null ? newVals.clone() : null;
        // For UPDATE OF checks: always compare against the original NEW, not the trigger-modified one
        Object[] originalNew = newVals != null ? newVals.clone() : null;

        for (TriggerDef trig : triggers) {
            if (!shouldFire(trig, event, def, originalNew, oldVals, db)) continue;

            Object[] result = invokeTriggerFunction(trig, def, event, TriggerDef.BEFORE,
                    current, oldVals, db, searchPath);
            if (result == null) return null; // trigger returned NULL — suppress row
            current = result;
        }
        // Copy modified values back into newVals (for INSERT/UPDATE)
        if (current != null && newVals != null) {
            System.arraycopy(current, 0, newVals, 0, newVals.length);
        }
        return proceedVal;
    }

    /**
     * Fire AFTER ROW triggers for the given event.
     */
    public static void fireAfterRow(TableDef def, int event,
                                     Object[] newVals, Object[] oldVals,
                                     Database db, List<String> searchPath)
            throws SQLException {
        List<TriggerDef> triggers = collectTriggers(def, TriggerDef.AFTER, event, true);
        if (triggers.isEmpty()) return;

        for (TriggerDef trig : triggers) {
            if (!shouldFire(trig, event, def, newVals, oldVals, db)) continue;
            invokeTriggerFunction(trig, def, event, TriggerDef.AFTER,
                    newVals, oldVals, db, searchPath);
        }
    }

    /**
     * Fire INSTEAD OF ROW triggers for the given event on a view.
     *
     * <p>INSTEAD OF triggers replace the normal DML operation: the trigger function
     * is responsible for performing whatever actual modifications are needed on
     * the underlying base tables.
     *
     * @param viewTriggers the triggers defined on the view
     * @param viewName     the view name (for TriggerContext)
     * @param viewSchema   the view schema (for TriggerContext)
     * @param viewDef      a synthetic TableDef for column resolution (may be null)
     * @return true if at least one INSTEAD OF trigger fired (i.e. the DML should
     *         be considered handled), false if no matching triggers exist
     */
    public static boolean fireInsteadOf(List<TriggerDef> viewTriggers, int event,
                                         Object[] newVals, Object[] oldVals,
                                         String viewName, String viewSchema,
                                         TableDef viewTableDef,
                                         Database db, List<String> searchPath)
            throws SQLException {
        List<TriggerDef> matching = new ArrayList<>();
        for (TriggerDef t : viewTriggers) {
            if (t.timing() == TriggerDef.INSTEAD_OF && t.firesOn(event) && t.row()) {
                matching.add(t);
            }
        }
        matching.sort(Comparator.comparing(TriggerDef::name));
        if (matching.isEmpty()) return false;

        for (TriggerDef trig : matching) {
            // INSTEAD OF triggers do not support WHEN clauses or UPDATE OF columns
            // (PostgreSQL restriction), so we skip shouldFire and invoke directly.
            if (viewTableDef != null) {
                invokeTriggerFunction(trig, viewTableDef, event, TriggerDef.INSTEAD_OF,
                        newVals, oldVals, db, searchPath);
            } else {
                // Fallback: build a minimal trigger context directly
                invokeTriggerFunctionMinimal(trig, event, newVals, oldVals,
                        viewName, viewSchema, db, searchPath);
            }
        }
        return true;
    }

    /**
     * Fire statement-level triggers (BEFORE or AFTER).
     */
    public static void fireStatement(TableDef def, int event, int timing,
                                      Database db, List<String> searchPath)
            throws SQLException {
        List<TriggerDef> triggers = collectTriggers(def, timing, event, false);
        if (triggers.isEmpty()) return;

        for (TriggerDef trig : triggers) {
            invokeTriggerFunction(trig, def, event, timing,
                    null, null, db, searchPath);
        }
    }

    /**
     * Collect triggers matching the given criteria, sorted alphabetically by name.
     */
    private static List<TriggerDef> collectTriggers(TableDef def, int timing,
                                                     int event, boolean row) {
        List<TriggerDef> result = new ArrayList<>();
        for (TriggerDef t : def.triggers()) {
            if (t.timing() == timing && t.firesOn(event) && t.row() == row) {
                result.add(t);
            }
        }
        result.sort(Comparator.comparing(TriggerDef::name));
        return result;
    }

    /**
     * Check if a trigger should fire, considering UPDATE OF columns and WHEN clause.
     */
    private static boolean shouldFire(TriggerDef trig, int event, TableDef def,
                                       Object[] newVals, Object[] oldVals,
                                       Database db)
            throws SQLException {
        // UPDATE OF specific columns: only fire if at least one listed column changed
        if (event == TriggerDef.UPDATE && trig.columns() != null && !trig.columns().isEmpty()) {
            if (oldVals != null && newVals != null) {
                boolean anyChanged = false;
                for (String colName : trig.columns()) {
                    ColumnDef col = def.column(colName);
                    if (col != null) {
                        int idx = col.attnum() - 1;
                        Object oldVal = idx < oldVals.length ? oldVals[idx] : null;
                        Object newVal = idx < newVals.length ? newVals[idx] : null;
                        if (!java.util.Objects.equals(oldVal, newVal)) {
                            anyChanged = true;
                            break;
                        }
                    }
                }
                if (!anyChanged) return false;
            }
        }

        // WHEN clause: evaluate with NEW/OLD in scope
        if (trig.whenClause() != null) {
            Evaluator eval = new Evaluator(db.catalog().functions(), db.collation());
            eval.setVariableResolver(new Evaluator.VariableResolver() {
                @Override
                public Object resolve(String name) {
                    return null;
                }

                @Override
                public Object resolveField(String qualifier, String field) {
                    Object[] row = null;
                    if ("new".equalsIgnoreCase(qualifier)) row = newVals;
                    else if ("old".equalsIgnoreCase(qualifier)) row = oldVals;
                    if (row != null) {
                        ColumnDef col = def.column(field);
                        if (col != null) {
                            int idx = col.attnum() - 1;
                            return idx < row.length ? row[idx] : null;
                        }
                    }
                    return UNRESOLVED;
                }
            });
            Object result = eval.eval(trig.whenClause(), EvalContext.empty());
            return Boolean.TRUE.equals(result);
        }

        return true;
    }

    /**
     * Invoke the trigger function via PL/pgSQL interpreter.
     */
    private static Object[] invokeTriggerFunction(TriggerDef trig, TableDef def,
                                                    int event, int timing,
                                                    Object[] newVals, Object[] oldVals,
                                                    Database db, List<String> searchPath)
            throws SQLException {
        int depth = triggerDepth.get();
        if (depth >= MAX_TRIGGER_DEPTH) {
            throw new SQLException("trigger recursion depth exceeded (max " + MAX_TRIGGER_DEPTH + ")");
        }
        triggerDepth.set(depth + 1);
        try {
            return invokeTriggerFunctionInner(trig, def, event, timing, newVals, oldVals, db, searchPath);
        } finally {
            triggerDepth.set(depth);
        }
    }

    private static Object[] invokeTriggerFunctionInner(TriggerDef trig, TableDef def,
                                                        int event, int timing,
                                                        Object[] newVals, Object[] oldVals,
                                                        Database db, List<String> searchPath)
            throws SQLException {
        // Resolve the function body from the catalog
        FunctionDef func = null;
        for (FunctionDef f : db.catalog().functions().findScalarOverloads(trig.functionName())) {
            if (f.oid() == trig.functionOid()) { func = f; break; }
        }
        if (func == null) {
            // Try by name only (function OID may not match after replace)
            List<FunctionDef> overloads = db.catalog().functions().findScalarOverloads(trig.functionName());
            if (!overloads.isEmpty()) {
                func = overloads.stream()
                        .filter(f -> f.argTypes().isEmpty())
                        .findFirst()
                        .orElse(overloads.get(0));
            }
        }
        if (func == null) {
            throw new SQLException("trigger function \"" + trig.functionName() + "\" does not exist");
        }

        // Build trigger context
        String tgWhen = switch (timing) {
            case TriggerDef.BEFORE     -> "BEFORE";
            case TriggerDef.INSTEAD_OF -> "INSTEAD OF";
            default                    -> "AFTER";
        };
        String tgOp = switch (event) {
            case TriggerDef.INSERT -> "INSERT";
            case TriggerDef.UPDATE -> "UPDATE";
            case TriggerDef.DELETE -> "DELETE";
            case TriggerDef.TRUNCATE -> "TRUNCATE";
            default -> "UNKNOWN";
        };
        String tgLevel = trig.row() ? "ROW" : "STATEMENT";

        TriggerContext trigCtx = new TriggerContext(
                trig.name(), tgWhen, tgLevel, tgOp,
                trig.tableName(), trig.tableSchema(),
                trig.args() != null ? trig.args().toArray(new String[0]) : new String[0], def,
                newVals != null ? newVals.clone() : null,
                oldVals != null ? oldVals.clone() : null
        );

        // The trigger function is stored as a ScalarImpl that creates a PlPgSqlInterpreter.
        // We need to invoke it specially with trigger context.
        // Since trigger functions are PL/pgSQL, we parse and execute the body directly.
        // The function's impl is a lambda that creates a PlPgSqlInterpreter —
        // but we need to inject trigger context, so we create the interpreter ourselves.

        // Get the function body from the impl by invoking it in a special way.
        // Actually, the ScalarImpl lambda captures the body text. We can't extract it.
        // Instead, trigger functions should be invoked via a dedicated path.
        // Let's use the impl directly — trigger functions always return the trigger row.
        // The ScalarImpl will execute the body and return a value.

        // For trigger functions, we call impl.invoke() with an empty args array.
        // The impl creates a PlPgSqlInterpreter, but without trigger context.
        // We need a different approach: store the body text and invoke it with trigger context.

        // New approach: call the function's invoke, but first set up thread-local trigger context
        // so the PlPgSqlInterpreter can pick it up.
        PENDING_TRIGGER_CTX.set(trigCtx);
        try {
            Object result = func.impl().invoke(new Object[0]);
            // For BEFORE ROW: result is the return value (NEW row or null)
            if (result instanceof Object[] arr) return arr;
            if (result == null && timing == TriggerDef.BEFORE && trig.row()) return null;
            // If the function returned non-null for BEFORE ROW, return newVals
            return newVals;
        } finally {
            PENDING_TRIGGER_CTX.remove();
        }
    }

    /**
     * Invoke a trigger function with minimal context (no TableDef available).
     * Used for INSTEAD OF triggers on views where no real TableDef exists.
     */
    private static void invokeTriggerFunctionMinimal(TriggerDef trig, int event,
                                                      Object[] newVals, Object[] oldVals,
                                                      String viewName, String viewSchema,
                                                      Database db, List<String> searchPath)
            throws SQLException {
        int depth = triggerDepth.get();
        if (depth >= MAX_TRIGGER_DEPTH) {
            throw new SQLException("trigger recursion depth exceeded (max " + MAX_TRIGGER_DEPTH + ")");
        }
        triggerDepth.set(depth + 1);
        try {
            // Resolve the function
            FunctionDef func = null;
            for (FunctionDef f : db.catalog().functions().findScalarOverloads(trig.functionName())) {
                if (f.oid() == trig.functionOid()) { func = f; break; }
            }
            if (func == null) {
                List<FunctionDef> overloads = db.catalog().functions().findScalarOverloads(trig.functionName());
                if (!overloads.isEmpty()) {
                    func = overloads.stream()
                            .filter(f -> f.argTypes().isEmpty())
                            .findFirst()
                            .orElse(overloads.get(0));
                }
            }
            if (func == null) {
                throw new SQLException("trigger function \"" + trig.functionName() + "\" does not exist");
            }

            String tgOp = switch (event) {
                case TriggerDef.INSERT -> "INSERT";
                case TriggerDef.UPDATE -> "UPDATE";
                case TriggerDef.DELETE -> "DELETE";
                default -> "UNKNOWN";
            };

            TriggerContext trigCtx = new TriggerContext(
                    trig.name(), "INSTEAD OF", "ROW", tgOp,
                    viewName, viewSchema,
                    trig.args() != null ? trig.args().toArray(new String[0]) : new String[0], null,
                    newVals != null ? newVals.clone() : null,
                    oldVals != null ? oldVals.clone() : null
            );

            PENDING_TRIGGER_CTX.set(trigCtx);
            try {
                func.impl().invoke(new Object[0]);
            } finally {
                PENDING_TRIGGER_CTX.remove();
            }
        } finally {
            triggerDepth.set(depth);
        }
    }

    /**
     * Thread-local trigger context used to pass trigger info to PlPgSqlInterpreter
     * created inside the function's ScalarImpl lambda.
     */
    public static final ThreadLocal<TriggerContext> PENDING_TRIGGER_CTX = new ThreadLocal<>();
}
