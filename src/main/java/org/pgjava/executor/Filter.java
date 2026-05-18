package org.pgjava.executor;

import org.pgjava.sql.ast.Expr;
import org.pgjava.storage.Row;

import java.sql.SQLException;
import java.util.List;

/**
 * Applies a predicate to the input, discarding rows for which the predicate
 * evaluates to FALSE or NULL (SQL three-valued logic).
 *
 * <p>Used for WHERE and HAVING clauses.
 */
public final class Filter extends Operator {

    private final Operator source;
    private final Expr     predicate;
    private final Evaluator eval;

    public Filter(Operator source, Expr predicate, Evaluator eval) {
        super(source.schema());
        this.source    = source;
        this.predicate = predicate;
        this.eval      = eval;
    }

    @Override
    public void open() throws SQLException {
        source.open();
    }

    @Override
    public Row next() throws SQLException {
        Row row;
        while ((row = source.next()) != null) {
            EvalContext ctx = schema.buildContext(row);
            Object result = eval.eval(predicate, ctx);
            if (Boolean.TRUE.equals(result)) return row;
        }
        return null;
    }

    @Override
    public void close() {
        source.close();
    }

    @Override protected String planNodeName()       { return "Filter"; }
    @Override protected String planDetail()          { return "(" + predicate + ")"; }
    @Override protected List<Operator> planChildren() { return List.of(source); }
}
