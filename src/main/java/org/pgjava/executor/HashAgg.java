package org.pgjava.executor;

import org.pgjava.sql.ast.Expr;
import org.pgjava.storage.Row;
import org.pgjava.storage.RowId;

import java.sql.SQLException;
import java.util.*;

/**
 * Hash-based GROUP BY + aggregate evaluation.
 *
 * <p>Materializes the input, groups by the key expressions, accumulates
 * aggregates per group, and emits one output row per group.
 *
 * <p>For queries without GROUP BY but with aggregates, produces exactly one
 * output row (even if the input is empty).
 */
public final class HashAgg extends Operator {

    private static final RowId AGG_ROWID = new RowId(-5, -5);

    private final Operator              source;
    private final List<Expr>            groupKeys;    // may be empty
    private final List<AggAccumulator>  aggTemplates; // one per aggregate
    private final List<Expr>            aggExprs;     // the original FunctionCall AST nodes (as Expr)
    private final Evaluator             eval;

    private Iterator<Row>  results;

    /**
     * @param groupKeys    GROUP BY expressions (empty = no GROUP BY)
     * @param aggTemplates one AggAccumulator template per aggregate function
     *                     in the SELECT list (in the same order they appear)
     */
    public HashAgg(Operator source,
                   List<Expr> groupKeys,
                   List<AggAccumulator> aggTemplates,
                   Evaluator eval) {
        super(buildSchema(groupKeys.size(), aggTemplates.size()));
        this.source       = source;
        this.groupKeys    = groupKeys;
        this.aggTemplates = aggTemplates;
        this.aggExprs     = List.of(); // not needed — AggAccumulator carries its own expr
        this.eval         = eval;
    }

    @Override
    public void open() throws SQLException {
        source.open();

        // Map from group-key tuple → per-agg accumulators
        Map<List<Object>, List<AggAccumulator>> groups = new LinkedHashMap<>();

        // Sentinel for no-group case
        List<Object> NO_GROUP_KEY = List.of();

        Row row;
        while ((row = source.next()) != null) {
            EvalContext ctx = source.schema().buildContext(row);

            // Build group key
            List<Object> key;
            if (groupKeys.isEmpty()) {
                key = NO_GROUP_KEY;
            } else {
                key = new ArrayList<>(groupKeys.size());
                for (Expr ke : groupKeys) {
                    Object v = eval.eval(ke, ctx);
                    // Wrap Object[] as List so HashMap equality works by value
                    key.add(v instanceof Object[] arr ? java.util.Arrays.asList(arr) : v);
                }
            }

            // Get or create accumulators for this group
            List<AggAccumulator> accs = groups.get(key);
            if (accs == null) {
                accs = new ArrayList<>(aggTemplates.size());
                for (AggAccumulator t : aggTemplates) accs.add(t.fresh());
                groups.put(key, accs);
            }

            // Accumulate
            for (AggAccumulator acc : accs) acc.accumulate(ctx);
        }
        source.close();

        // If no GROUP BY and no rows, still emit one row (e.g. COUNT(*) = 0)
        if (groupKeys.isEmpty() && groups.isEmpty()) {
            List<AggAccumulator> accs = new ArrayList<>();
            for (AggAccumulator t : aggTemplates) accs.add(t.fresh());
            groups.put(NO_GROUP_KEY, accs);
        }

        // Build output rows
        List<Row> outputRows = new ArrayList<>(groups.size());
        for (Map.Entry<List<Object>, List<AggAccumulator>> e : groups.entrySet()) {
            List<Object>         key  = e.getKey();
            List<AggAccumulator> accs = e.getValue();
            Object[] vals = new Object[key.size() + accs.size()];
            for (int i = 0; i < key.size(); i++) vals[i] = key.get(i);
            for (int i = 0; i < accs.size(); i++) vals[key.size() + i] = accs.get(i).result();
            outputRows.add(new Row(AGG_ROWID, vals));
        }
        results = outputRows.iterator();
    }

    @Override
    public Row next() {
        if (results == null || !results.hasNext()) return null;
        return results.next();
    }

    @Override
    public void close() {
        results = null;
    }

    // -------------------------------------------------------------------------

    private static OutputSchema buildSchema(int keyCount, int aggCount) {
        List<String> names = new ArrayList<>(keyCount + aggCount);
        for (int i = 0; i < keyCount;  i++) names.add("_key_"  + i);
        for (int i = 0; i < aggCount;  i++) names.add("_agg_"  + i);
        return OutputSchema.ofNames(names);
    }

    @Override protected String planNodeName() { return "HashAggregate"; }
    @Override protected java.util.List<Operator> planChildren() { return java.util.List.of(source); }
}
