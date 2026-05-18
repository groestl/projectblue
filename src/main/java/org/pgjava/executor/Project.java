package org.pgjava.executor;

import org.pgjava.sql.ast.Expr;
import org.pgjava.storage.Row;
import org.pgjava.storage.RowId;

import java.sql.SQLException;
import java.util.List;

/**
 * Computes the SELECT target list expressions, yielding a new row for each
 * input row with only the projected values.
 *
 * <p>Also handles SELECT * expansion (the planner replaces * with explicit
 * column references before creating this operator).
 */
public final class Project extends Operator {

    public record Projection(Expr expr, String name) {}

    private static final RowId PROJECT_ROWID = new RowId(-3, -3);

    private final Operator         source;
    private final List<Projection> projections;
    private final Evaluator        eval;

    public Project(Operator source, List<Projection> projections, Evaluator eval) {
        super(OutputSchema.ofNames(projections.stream().map(Projection::name).toList()));
        this.source      = source;
        this.projections = projections;
        this.eval        = eval;
    }

    /**
     * Variant with an explicit table alias for the output schema.
     * Used when wrapping a subquery or view with an alias.
     */
    public Project(Operator source, List<Projection> projections, Evaluator eval,
                   String tableAlias) {
        super(OutputSchema.ofNamesWithAlias(tableAlias,
                projections.stream().map(Projection::name).toList()));
        this.source      = source;
        this.projections = projections;
        this.eval        = eval;
    }

    @Override
    public void open() throws SQLException {
        source.open();
    }

    @Override
    public Row next() throws SQLException {
        Row in = source.next();
        if (in == null) return null;
        EvalContext ctx = source.schema().buildContext(in);
        Object[] out = new Object[projections.size()];
        for (int i = 0; i < projections.size(); i++) {
            out[i] = CollatedValue.unwrap(eval.eval(projections.get(i).expr(), ctx));
        }
        // Preserve source RowId so DML operators can use it (e.g. UPDATE … RETURNING)
        RowId rid = in.rowId() != null ? in.rowId() : PROJECT_ROWID;
        return new Row(rid, out);
    }

    @Override
    public void close() {
        source.close();
    }

    @Override protected java.util.List<Operator> planChildren() { return java.util.List.of(source); }
}
