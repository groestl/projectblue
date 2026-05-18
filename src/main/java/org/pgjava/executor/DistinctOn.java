package org.pgjava.executor;

import org.pgjava.sql.ast.Expr;
import org.pgjava.storage.Row;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Streaming operator that implements {@code SELECT DISTINCT ON (exprs)}.
 *
 * <p>Input must already be sorted so that rows with the same DISTINCT ON key
 * are contiguous (i.e. Sort must precede this operator with the DISTINCT ON
 * expressions as the leading sort keys).
 *
 * <p>Emits the first row in each group of equal DISTINCT ON key values.
 */
public final class DistinctOn extends Operator {

    private final Operator    source;
    private final List<Expr>  keys;
    private final Evaluator   eval;

    private List<Object> lastKey;

    public DistinctOn(Operator source, List<Expr> keys, Evaluator eval) {
        super(source.schema());
        this.source = source;
        this.keys   = keys;
        this.eval   = eval;
    }

    @Override
    public void open() throws SQLException {
        source.open();
        lastKey = null;
    }

    @Override
    public Row next() throws SQLException {
        Row row;
        while ((row = source.next()) != null) {
            EvalContext ctx = source.schema().buildContext(row);
            List<Object> key = new ArrayList<>(keys.size());
            for (Expr k : keys) key.add(eval.eval(k, ctx));
            if (!keyEquals(key, lastKey)) {
                lastKey = key;
                return row;
            }
        }
        return null;
    }

    @Override
    public void close() {
        source.close();
        lastKey = null;
    }

    private static boolean keyEquals(List<Object> a, List<Object> b) {
        if (b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!deepEquals(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private static boolean deepEquals(Object a, Object b) {
        if (a instanceof Object[] aa && b instanceof Object[] ba)
            return java.util.Arrays.deepEquals(aa, ba);
        return Objects.equals(a, b);
    }
}
