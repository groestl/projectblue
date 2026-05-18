package org.pgjava.executor;

import org.pgjava.sql.ast.Expr;
import org.pgjava.sql.ast.SelectStmt;
import org.pgjava.storage.Row;
import org.pgjava.storage.RowId;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Implements {@code JOIN LATERAL (subquery) AS alias} and implicit lateral
 * cross-joins ({@code FROM t, LATERAL (subquery) AS alias}).
 *
 * <p>For each row from the left operator, re-executes the right-side subquery
 * with the left row as outer context (enabling correlated column references).
 * The result is an inner join: if the subquery returns no rows, the left row
 * is dropped (INNER JOIN semantics).
 *
 * <p>Columns: left columns followed by right columns.
 */
public final class LateralJoin extends Operator {

    /** Result of executing a lateral subquery: column names + rows. */
    public record SubqueryResult(List<String> columnNames, List<Row> rows) {}

    /** Executes a subquery with an outer EvalContext and returns the result. */
    @FunctionalInterface
    public interface SubqueryRunner {
        SubqueryResult run(SelectStmt stmt, EvalContext outerCtx) throws SQLException;
    }

    private static final RowId LATERAL_ROWID = new RowId(-3, -3);

    private final Operator      left;
    private final SelectStmt    rightStmt;
    private final String        rightAlias;
    private final List<String>  rightColAliases; // explicit column aliases from SQL, e.g. AS t(a,b)
    private final SubqueryRunner runner;
    private final Expr          condition;       // ON clause; null = cross/implicit lateral join
    private final Evaluator     eval;

    // Lazily built combined schema (left + right); updated on first right result
    // Iterator state
    private Row       leftRow;
    private List<Row> rightRows;
    private int       rightPos;

    /**
     * @param left            left-side operator
     * @param rightSchema     pre-computed output schema of the lateral subquery (with alias applied)
     * @param rightStmt       the lateral subquery AST
     * @param runner          executes the subquery per left row
     * @param condition       ON clause expression; null for cross/implicit lateral joins
     * @param eval            evaluator for the ON condition
     */
    public LateralJoin(Operator left, OutputSchema rightSchema, SelectStmt rightStmt,
                       String rightAlias, List<String> rightColAliases,
                       SubqueryRunner runner, Expr condition, Evaluator eval) {
        super(left.schema().join(rightSchema));
        this.left            = left;
        this.rightStmt       = rightStmt;
        this.rightAlias      = rightAlias;
        this.rightColAliases = rightColAliases;
        this.runner          = runner;
        this.condition       = condition;
        this.eval            = eval;
    }

    @Override
    public void open() throws SQLException {
        left.open();
        leftRow   = null;
        rightRows = List.of();
        rightPos  = 0;
    }

    @Override
    public Row next() throws SQLException {
        while (true) {
            // Exhaust current right batch
            while (rightPos < rightRows.size()) {
                Row rr = rightRows.get(rightPos++);
                Row merged = merge(leftRow, rr);
                if (condition != null) {
                    EvalContext ctx = schema.buildContext(merged);
                    Object matches = eval.eval(condition, ctx);
                    if (!Boolean.TRUE.equals(matches)) continue;
                }
                return merged;
            }

            // Advance left
            leftRow = left.next();
            if (leftRow == null) return null;

            EvalContext outerCtx = left.schema().buildContext(leftRow);
            SubqueryResult result = runner.run(rightStmt, outerCtx);
            rightRows = result.rows();
            rightPos  = 0;
        }
    }

    @Override
    public void close() {
        left.close();
        rightRows = null;
    }

    private Row merge(Row l, Row r) {
        Object[] lv = l.values(), rv = r.values();
        Object[] merged = new Object[lv.length + rv.length];
        System.arraycopy(lv, 0, merged, 0, lv.length);
        System.arraycopy(rv, 0, merged, lv.length, rv.length);
        return new Row(LATERAL_ROWID, merged);
    }
}
