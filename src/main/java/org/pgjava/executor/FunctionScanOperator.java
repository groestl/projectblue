package org.pgjava.executor;

import org.pgjava.catalog.SrfDef;
import org.pgjava.sql.ast.Expr;
import org.pgjava.storage.Row;

import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;

/**
 * Evaluates a set-returning function (SRF) and streams its rows.
 *
 * <p>Used for FROM-clause function calls such as:
 * <ul>
 *   <li>{@code SELECT * FROM pg_listening_channels()}</li>
 *   <li>{@code SELECT * FROM generate_series(1, 10)}</li>
 *   <li>{@code SELECT * FROM unnest(array[1,2,3])}</li>
 * </ul>
 *
 * <p>Arguments are evaluated once at {@link #open()} time using an empty
 * {@link EvalContext} (i.e. no outer row context — LATERAL is not supported).
 * Each output row has no {@link org.pgjava.storage.RowId} (synthetic rows).
 */
public final class FunctionScanOperator extends Operator {

    private final SrfDef      srf;
    private final List<Expr>  argExprs;
    private final Evaluator   eval;
    private EvalContext        outerContext; // for LATERAL references

    private Iterator<Object[]> iterator;

    public FunctionScanOperator(SrfDef srf, List<Expr> argExprs,
                                Evaluator eval, OutputSchema schema) {
        super(schema);
        this.srf      = srf;
        this.argExprs = argExprs;
        this.eval     = eval;
    }

    /** Set the outer context for LATERAL function scans. */
    public void setOuterContext(EvalContext ctx) { this.outerContext = ctx; }

    @Override
    public void open() throws SQLException {
        // Evaluate argument expressions, using outer context for LATERAL if available.
        Object[] args = new Object[argExprs.size()];
        EvalContext ctx = outerContext != null ? outerContext : EvalContext.empty();
        for (int i = 0; i < argExprs.size(); i++) {
            args[i] = eval.eval(argExprs.get(i), ctx);
        }
        Iterable<Object[]> rows = srf.impl().invoke(args);
        iterator = rows.iterator();
    }

    @Override
    public Row next() throws SQLException {
        if (iterator == null || !iterator.hasNext()) return null;
        Object[] values = iterator.next();
        // Pad or trim to match schema width if necessary
        int w = schema().width();
        if (values.length != w) {
            Object[] padded = new Object[w];
            System.arraycopy(values, 0, padded, 0, Math.min(values.length, w));
            values = padded;
        }
        return new Row(null, values);
    }

    @Override
    public void close() {
        iterator = null;
    }
}
