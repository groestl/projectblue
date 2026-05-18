package org.pgjava.executor;

import org.pgjava.engine.PgErrorException;
import org.pgjava.sql.ast.*;
import org.pgjava.storage.Row;
import org.pgjava.storage.RowId;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.SQLException;
import java.util.*;

/**
 * Window-function operator (Volcano model).
 *
 * <p>Buffers the entire input, computes each window function over the
 * appropriate partition/frame, then emits rows with the window results
 * appended as extra columns named {@code _win_0}, {@code _win_1}, etc.
 *
 * <p>Supported functions:
 * <ul>
 *   <li>Ranking: {@code ROW_NUMBER()}, {@code RANK()}, {@code DENSE_RANK()},
 *       {@code NTILE(n)}, {@code CUME_DIST()}, {@code PERCENT_RANK()}</li>
 *   <li>Offset:  {@code LAG(expr[,offset[,default]])},
 *                {@code LEAD(expr[,offset[,default]])},
 *                {@code FIRST_VALUE(expr)}, {@code LAST_VALUE(expr)},
 *                {@code NTH_VALUE(expr,n)}</li>
 *   <li>Aggregate: {@code SUM}, {@code COUNT}, {@code MIN}, {@code MAX},
 *                  {@code AVG} — computed over the current frame (default:
 *                  RANGE UNBOUNDED PRECEDING to CURRENT ROW when ORDER BY
 *                  is present; full partition otherwise).</li>
 * </ul>
 */
public final class WindowAgg extends Operator {

    /** Sentinel object for COUNT(*) — identity-compared, never confused with real values. */
    private static final Object COUNT_STAR_SENTINEL = new Object();

    /** One entry per window function in the SELECT list. */
    public record WinFunc(
            FunctionCall call,   // the AST node (funcname + args + over)
            String       outCol  // placeholder name: _win_0, _win_1, …
    ) {}

    private final Operator       source;
    private final List<WinFunc>  funcs;
    private final Evaluator      eval;

    // Populated during open()
    private List<Row> outputRows;
    private int       cursor;

    public WindowAgg(Operator source, List<WinFunc> funcs, Evaluator eval) {
        super(source.schema().join(
                OutputSchema.ofNames(funcs.stream().map(WinFunc::outCol).toList())));
        this.source = source;
        this.funcs  = funcs;
        this.eval   = eval;
    }

    @Override
    public void open() throws SQLException {
        source.open();
        List<Row> input = new ArrayList<>();
        Row r;
        while ((r = source.next()) != null) input.add(r);
        source.close();

        int n = input.size();
        Object[][] winVals = new Object[funcs.size()][n];  // [funcIdx][rowIdx]

        // Compute each window function independently
        for (int fi = 0; fi < funcs.size(); fi++) {
            WinFunc wf = funcs.get(fi);
            WindowDef over = wf.call().over();
            computeWindowFunc(fi, wf.call(), over, input, winVals[fi]);
        }

        // Build output rows: original values + window columns appended
        outputRows = new ArrayList<>(n);
        int srcWidth = source.schema().width();
        for (int i = 0; i < n; i++) {
            Object[] src = input.get(i).values();
            Object[] out = Arrays.copyOf(src, srcWidth + funcs.size());
            for (int fi = 0; fi < funcs.size(); fi++) {
                out[srcWidth + fi] = winVals[fi][i];
            }
            outputRows.add(new Row(input.get(i).rowId(), out));
        }
        cursor = 0;
    }

    @Override
    public Row next() {
        if (cursor >= outputRows.size()) return null;
        return outputRows.get(cursor++);
    }

    @Override
    public void close() {
        outputRows = null;
    }

    // =========================================================================
    // Window function computation

    private void computeWindowFunc(int fi, FunctionCall fc, WindowDef over,
                                   List<Row> input, Object[] results)
            throws SQLException {
        String func = fc.funcname().getLast().toLowerCase();

        // Group input rows by partition key
        List<List<Integer>> partitions = buildPartitions(over, input);

        for (List<Integer> partition : partitions) {
            // Sort partition by ORDER BY (stable)
            List<Integer> sorted = new ArrayList<>(partition);
            sortByOrderBy(sorted, over, input);

            switch (func) {
                case "row_number"    -> computeRowNumber(sorted, results);
                case "rank"          -> computeRank(sorted, over, input, results);
                case "dense_rank"    -> computeDenseRank(sorted, over, input, results);
                case "ntile"         -> computeNtile(sorted, fc, input, results);
                case "cume_dist"     -> computeCumeDist(sorted, over, input, results);
                case "percent_rank"  -> computePercentRank(sorted, over, input, results);
                case "lag"           -> computeLag(sorted, fc, input, results, false);
                case "lead"          -> computeLag(sorted, fc, input, results, true);
                case "first_value"   -> computeFirstLast(sorted, fc, input, results, true);
                case "last_value"    -> computeFirstLast(sorted, fc, input, results, false);
                case "nth_value"     -> computeNthValue(sorted, fc, input, results);
                default              -> computeFrameAgg(sorted, func, over, fc, input, results);
            }
        }
    }

    // ── Partition building ────────────────────────────────────────────────────

    private List<List<Integer>> buildPartitions(WindowDef over, List<Row> input)
            throws SQLException {
        if (over == null || over.partitionClause() == null || over.partitionClause().isEmpty()) {
            List<Integer> all = new ArrayList<>(input.size());
            for (int i = 0; i < input.size(); i++) all.add(i);
            return List.of(all);
        }

        // Group by PARTITION BY key
        LinkedHashMap<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < input.size(); i++) {
            EvalContext ctx = source.schema().buildContext(input.get(i));
            StringBuilder key = new StringBuilder();
            for (Expr pe : over.partitionClause()) {
                Object v = eval.eval(pe, ctx);
                key.append(v == null ? "\u0000NULL" : v.toString()).append('\u0001');
            }
            groups.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(i);
        }
        return new ArrayList<>(groups.values());
    }

    // ── Partition sorting ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void sortByOrderBy(List<Integer> indices, WindowDef over, List<Row> input)
            throws SQLException {
        if (over == null || over.orderClause() == null || over.orderClause().isEmpty()) return;
        List<SortKey> keys = over.orderClause();
        // Build a key cache to avoid re-evaluating
        Map<Integer, Object[]> keyCache = new HashMap<>();
        for (int idx : indices) {
            Object[] ks = new Object[keys.size()];
            EvalContext ctx = source.schema().buildContext(input.get(idx));
            for (int k = 0; k < keys.size(); k++) {
                ks[k] = eval.eval(keys.get(k).node(), ctx);
            }
            keyCache.put(idx, ks);
        }
        indices.sort((a, b) -> {
            Object[] ka = keyCache.get(a), kb = keyCache.get(b);
            for (int k = 0; k < keys.size(); k++) {
                SortKey sk = keys.get(k);
                boolean nullsLast = sk.nulls() == SortByNulls.LAST
                        || ((sk.nulls() == null || sk.nulls() == SortByNulls.DEFAULT) && sk.dir() != SortByDir.DESC);
                int cmp = compareNullable(ka[k], kb[k], nullsLast);
                if (cmp != 0) return sk.dir() == SortByDir.DESC ? -cmp : cmp;
            }
            return 0;
        });
    }

    // ── Ranking functions ────────────────────────────────────────────────────

    private static void computeRowNumber(List<Integer> sorted, Object[] results) {
        for (int i = 0; i < sorted.size(); i++) {
            results[sorted.get(i)] = (long)(i + 1);
        }
    }

    private void computeRank(List<Integer> sorted, WindowDef over, List<Row> input,
                              Object[] results) throws SQLException {
        long rank = 1;
        for (int i = 0; i < sorted.size(); ) {
            int j = i;
            // Find all rows with the same ORDER BY value (tied rows get the same rank)
            while (j < sorted.size() - 1 && sameOrderByValue(sorted.get(j), sorted.get(j + 1), over, input)) j++;
            for (int k = i; k <= j; k++) results[sorted.get(k)] = rank;
            rank += (j - i + 1);
            i = j + 1;
        }
    }

    private void computeDenseRank(List<Integer> sorted, WindowDef over, List<Row> input,
                                   Object[] results) throws SQLException {
        long rank = 1;
        for (int i = 0; i < sorted.size(); ) {
            int j = i;
            while (j < sorted.size() - 1 && sameOrderByValue(sorted.get(j), sorted.get(j + 1), over, input)) j++;
            for (int k = i; k <= j; k++) results[sorted.get(k)] = rank;
            rank++;
            i = j + 1;
        }
    }

    private void computeNtile(List<Integer> sorted, FunctionCall fc, List<Row> input,
                               Object[] results) throws SQLException {
        int n = sorted.size();
        if (n == 0) return;
        long buckets = 1;
        if (!fc.args().isEmpty()) {
            Object bv = evalArg(fc, 0, input.isEmpty() ? null : input.get(sorted.get(0)));
            if (bv instanceof Number nb) buckets = nb.longValue();
        }
        if (buckets <= 0) buckets = 1;
        long base = n / buckets, extra = n % buckets;
        long bucket = 1, count = 0, limit = base + (bucket <= extra ? 1 : 0);
        for (int i = 0; i < n; i++) {
            results[sorted.get(i)] = bucket;
            if (++count >= limit) {
                bucket++;
                count = 0;
                limit = base + (bucket <= extra ? 1 : 0);
            }
        }
    }

    private void computeCumeDist(List<Integer> sorted, WindowDef over, List<Row> input,
                                  Object[] results) throws SQLException {
        int n = sorted.size();
        for (int i = 0; i < n; ) {
            int j = i;
            while (j < n - 1 && sameOrderByValue(sorted.get(j), sorted.get(j + 1), over, input)) j++;
            double cd = (double)(j + 1) / n;
            for (int k = i; k <= j; k++) results[sorted.get(k)] = cd;
            i = j + 1;
        }
    }

    private void computePercentRank(List<Integer> sorted, WindowDef over, List<Row> input,
                                     Object[] results) throws SQLException {
        int n = sorted.size();
        if (n <= 1) {
            for (int idx : sorted) results[idx] = 0.0;
            return;
        }
        long rank = 1;
        for (int i = 0; i < n; ) {
            int j = i;
            while (j < n - 1 && sameOrderByValue(sorted.get(j), sorted.get(j + 1), over, input)) j++;
            double pr = (double)(rank - 1) / (n - 1);
            for (int k = i; k <= j; k++) results[sorted.get(k)] = pr;
            rank += (j - i + 1);
            i = j + 1;
        }
    }

    // ── Offset functions ─────────────────────────────────────────────────────

    private void computeLag(List<Integer> sorted, FunctionCall fc, List<Row> input,
                             Object[] results, boolean isLead) throws SQLException {
        int n = sorted.size();
        int offset = 1;
        Object defVal = null;
        if (fc.args().size() >= 2) {
            Object ov = evalArg(fc, 1, n > 0 ? input.get(sorted.get(0)) : null);
            if (ov instanceof Number nb) offset = nb.intValue();
        }
        if (fc.args().size() >= 3) {
            defVal = evalArg(fc, 2, n > 0 ? input.get(sorted.get(0)) : null);
        }
        for (int i = 0; i < n; i++) {
            int src = isLead ? i + offset : i - offset;
            if (src < 0 || src >= n) {
                results[sorted.get(i)] = defVal;
            } else {
                results[sorted.get(i)] = evalArg(fc, 0, input.get(sorted.get(src)));
            }
        }
    }

    private void computeFirstLast(List<Integer> sorted, FunctionCall fc, List<Row> input,
                                   Object[] results, boolean isFirst) throws SQLException {
        int n = sorted.size();
        if (n == 0) return;
        if (fc.args().isEmpty()) return;

        // Note: frame clause parsing is not yet implemented (WindowDef.frame() is always null).
        // Once frame parsing is available, LAST_VALUE with default frame (no explicit frame +
        // ORDER BY present) should return current-row value, not partition-last.
        // For now, use partition-first / partition-last for all cases.
        Object val = isFirst
                ? evalArg(fc, 0, input.get(sorted.get(0)))
                : evalArg(fc, 0, input.get(sorted.get(n - 1)));
        for (int i = 0; i < n; i++) {
            results[sorted.get(i)] = val;
        }
    }

    private void computeNthValue(List<Integer> sorted, FunctionCall fc, List<Row> input,
                                  Object[] results) throws SQLException {
        int n = sorted.size();
        if (fc.args().size() < 2) return;
        Object nv = evalArg(fc, 1, n > 0 ? input.get(sorted.get(0)) : null);
        int nth = nv instanceof Number nb ? nb.intValue() : 1;
        Object val = (nth >= 1 && nth <= n) ? evalArg(fc, 0, input.get(sorted.get(nth - 1))) : null;
        for (int i = 0; i < n; i++) results[sorted.get(i)] = val;
    }

    // ── Aggregate window functions ────────────────────────────────────────────

    /**
     * Compute SUM/COUNT/MIN/MAX/AVG over a frame.
     *
     * <p>Default frame semantics (PostgreSQL):
     * <ul>
     *   <li>ORDER BY present: RANGE UNBOUNDED PRECEDING TO CURRENT ROW (running agg)</li>
     *   <li>No ORDER BY:      full partition</li>
     * </ul>
     */
    private void computeFrameAgg(List<Integer> sorted, String func, WindowDef over,
                                  FunctionCall fc, List<Row> input, Object[] results)
            throws SQLException {
        int n = sorted.size();
        if (n == 0) return;

        boolean hasOrderBy = over != null && over.orderClause() != null && !over.orderClause().isEmpty();
        boolean hasFrame   = over != null && over.frame() != null;

        // Evaluate the argument expression for each row in the partition
        Object[] argVals = new Object[n];
        for (int i = 0; i < n; i++) {
            argVals[i] = fc.aggStar() ? COUNT_STAR_SENTINEL
                    : (fc.args().isEmpty() ? null : evalArg(fc, 0, input.get(sorted.get(i))));
        }

        if (!hasOrderBy && !hasFrame) {
            // No ORDER BY, no frame: aggregate over entire partition
            Object agg = computeAgg(func, argVals, 0, n - 1, fc.aggDistinct());
            for (int i = 0; i < n; i++) results[sorted.get(i)] = agg;
        } else {
            // ORDER BY present (or frame explicit): running aggregate ending at current row
            // Handle RANGE ties: all rows with same ORDER BY value get the same running value
            for (int i = 0; i < n; ) {
                // Find the end of the tie group
                int j = i;
                if (hasOrderBy) {
                    while (j < n - 1 && sameOrderByValue(sorted.get(j), sorted.get(j + 1), over, input)) j++;
                }
                Object agg = computeAgg(func, argVals, 0, j, fc.aggDistinct());
                for (int k = i; k <= j; k++) results[sorted.get(k)] = agg;
                i = j + 1;
            }
        }
    }

    private Object computeAgg(String func, Object[] vals, int from, int to, boolean distinct)
            throws SQLException {
        // Collect values (possibly distinct)
        List<Object> list = new ArrayList<>();
        Set<String> seen = distinct ? new HashSet<>() : null;
        for (int i = from; i <= to; i++) {
            if (vals[i] == null) continue;
            if (distinct) {
                String key = vals[i].toString();
                if (!seen.add(key)) continue;
            }
            list.add(vals[i]);
        }

        return switch (func) {
            case "count" -> {
                if (vals[from] == COUNT_STAR_SENTINEL) {
                    // COUNT(*) — count all rows in frame
                    yield (long)(to - from + 1);
                }
                yield (long) list.size();
            }
            case "sum" -> {
                if (list.isEmpty()) yield null;
                BigDecimal sum = BigDecimal.ZERO;
                for (Object v : list) sum = sum.add(toBigDecimal(v));
                yield coerce(vals, from, to, sum);
            }
            case "avg" -> {
                if (list.isEmpty()) yield null;
                BigDecimal sum = BigDecimal.ZERO;
                for (Object v : list) sum = sum.add(toBigDecimal(v));
                yield sum.divide(BigDecimal.valueOf(list.size()), MathContext.DECIMAL128);
            }
            case "min" -> {
                if (list.isEmpty()) yield null;
                yield list.stream().min((a, b) -> compareNullable(a, b, true)).orElse(null);
            }
            case "max" -> {
                if (list.isEmpty()) yield null;
                yield list.stream().max((a, b) -> compareNullable(a, b, true)).orElse(null);
            }
            default -> throw PgErrorException.error("42883", "function " + func + " is not a window function").build();
        };
    }

    /** Return sum in the same numeric type as the input (int→long, else BigDecimal). */
    private Object coerce(Object[] vals, int from, int to, BigDecimal sum) {
        for (int i = from; i <= to; i++) {
            if (vals[i] instanceof Integer || vals[i] instanceof Long) {
                return sum.longValueExact();
            }
            if (vals[i] instanceof Double || vals[i] instanceof Float) {
                return sum.doubleValue();
            }
        }
        return sum;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Object evalArg(FunctionCall fc, int argIdx, Row row) throws SQLException {
        if (row == null || argIdx >= fc.args().size()) return null;
        EvalContext ctx = source.schema().buildContext(row);
        return eval.eval(fc.args().get(argIdx), ctx);
    }

    private boolean sameOrderByValue(int a, int b, WindowDef over, List<Row> input)
            throws SQLException {
        if (over == null || over.orderClause() == null || over.orderClause().isEmpty()) return true;
        for (SortKey sk : over.orderClause()) {
            EvalContext ca = source.schema().buildContext(input.get(a));
            EvalContext cb = source.schema().buildContext(input.get(b));
            Object va = eval.eval(sk.node(), ca);
            Object vb = eval.eval(sk.node(), cb);
            if (compareNullable(va, vb, true) != 0) return false;
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareNullable(Object a, Object b, boolean nullsLast) {
        if (a == null && b == null) return 0;
        if (a == null) return nullsLast ? 1 : -1;
        if (b == null) return nullsLast ? -1 : 1;
        org.pgjava.types.PgCollation aColl = CollatedValue.collationOf(a);
        org.pgjava.types.PgCollation bColl = CollatedValue.collationOf(b);
        a = CollatedValue.unwrap(a);
        b = CollatedValue.unwrap(b);
        if (a instanceof String sa && b instanceof String sb) {
            try {
                org.pgjava.types.PgCollation coll =
                        org.pgjava.types.PgCollation.resolveConflict(aColl, bColl, eval.defaultCollation());
                return coll.compare(sa, sb);
            } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
        }
        if (a instanceof Comparable ca) {
            try { return ca.compareTo(b); } catch (ClassCastException e) { /* fall through */ }
        }
        org.pgjava.types.PgCollation coll;
        try { coll = org.pgjava.types.PgCollation.resolveConflict(aColl, bColl, eval.defaultCollation()); }
        catch (java.sql.SQLException e) { throw new RuntimeException(e); }
        return coll.compare(a.toString(), b.toString());
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Double d)  return BigDecimal.valueOf(d);
        if (v instanceof Float f)   return BigDecimal.valueOf(f);
        if (v instanceof Long l)    return BigDecimal.valueOf(l);
        if (v instanceof Integer i) return BigDecimal.valueOf(i);
        return new BigDecimal(v.toString());
    }
}
