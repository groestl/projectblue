package org.pgjava.executor;

import org.pgjava.catalog.AggregateDef;
import org.pgjava.catalog.FunctionRegistry;
import org.pgjava.sql.ast.Expr;
import org.pgjava.sql.ast.FunctionCall;
import org.pgjava.sql.ast.SortByDir;
import org.pgjava.sql.ast.SortByNulls;
import org.pgjava.sql.ast.SortKey;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable accumulator for a single aggregate function.
 *
 * <p>Supported aggregates: COUNT(*), COUNT(x), SUM, AVG, MIN, MAX,
 * BOOL_AND, BOOL_OR, STRING_AGG, ARRAY_AGG, and any user-defined aggregate
 * registered in {@link FunctionRegistry}.
 */
public final class AggAccumulator {

    private final String         funcName;
    private final List<Expr>     args;
    private final boolean        aggStar;
    private final boolean        aggDistinct; // COUNT(DISTINCT x)
    private final Expr           aggFilter;   // FILTER (WHERE ...) clause; null if absent
    private final Evaluator      eval;
    private final String         separator; // for string_agg
    private final List<SortKey>  aggOrder;  // ORDER BY within aggregate
    private final FunctionRegistry registry; // nullable; used for UDA lookup

    // For user-defined aggregates (UDAs)
    private final AggregateDef   udaDef;    // null for built-in aggregates
    private AggregateDef.Accumulator udaAcc; // per-group state for UDAs

    // Accumulator state (built-in aggregates)
    private long         count     = 0;
    private Object       runningSum;     // for SUM/AVG
    private Object       runningMin;
    private Object       runningMax;
    private boolean      runningBool;    // for BOOL_AND/BOOL_OR
    private boolean      hasValue  = false;
    private List<Object> items     = null; // for STRING_AGG/ARRAY_AGG
    private List<Object[]> orderedItems = null; // for ORDER BY: [sortKeys..., value]
    private java.util.Set<String> distinctSeen = null; // for COUNT(DISTINCT x)

    /** Built-in aggregate constructor (no registry lookup). */
    public AggAccumulator(FunctionCall fc, Evaluator eval) throws java.sql.SQLException {
        this(fc, eval, null);
    }

    /**
     * Full constructor. When {@code registry} is non-null and the function name is
     * not a built-in aggregate, the registry is consulted for a user-defined aggregate.
     */
    public AggAccumulator(FunctionCall fc, Evaluator eval, FunctionRegistry registry)
            throws java.sql.SQLException {
        this.funcName    = fc.funcname().getLast().toLowerCase();
        this.args        = fc.args();
        this.aggStar     = fc.aggStar();
        this.aggDistinct = fc.aggDistinct();
        this.aggFilter   = fc.aggFilter();
        this.eval        = eval;
        this.registry    = registry;
        this.runningBool = "bool_and".equals(funcName) || "every".equals(funcName);
        // extract separator for string_agg
        String sep = ", ";
        if ("string_agg".equals(funcName) && args.size() >= 2) {
            // separator is the second arg — evaluated as a constant here
            try {
                Object sv = eval.eval(args.get(1), EvalContext.empty());
                if (sv != null) sep = sv.toString();
            } catch (SQLException e) {
                throw e;
            } catch (Exception e) {
                throw new java.sql.SQLException(
                        "Failed to evaluate string_agg separator: " + e.getMessage(), "42601", e);
            }
        }
        this.separator = sep;
        this.aggOrder  = fc.aggOrder() != null ? fc.aggOrder() : List.of();
        // Look up user-defined aggregate if not a known built-in
        AggregateDef def = null;
        if (registry != null && !BUILTIN_AGG_NAMES.contains(funcName)) {
            def = registry.findAggregate(funcName);
        }
        this.udaDef = def;
        this.udaAcc = def != null ? def.factory().create() : null;
    }

    static final java.util.Set<String> BUILTIN_AGG_NAMES = java.util.Set.of(
            "count", "sum", "avg", "min", "max",
            "bool_and", "bool_or", "every", "string_agg", "array_agg",
            "json_agg", "jsonb_agg", "json_object_agg", "jsonb_object_agg");

    /** Process one input row. */
    public void accumulate(EvalContext ctx) throws SQLException {
        // FILTER (WHERE ...) — skip row if predicate is false or NULL
        if (aggFilter != null && !Boolean.TRUE.equals(eval.eval(aggFilter, ctx))) return;
        switch (funcName) {
            case "count" -> {
                if (aggStar || args.isEmpty()) {
                    count++; // COUNT(*)
                } else {
                    Object v = eval.eval(args.get(0), ctx);
                    if (v != null) {
                        if (aggDistinct) {
                            // COUNT(DISTINCT x) — only count each distinct value once
                            if (distinctSeen == null) distinctSeen = new java.util.HashSet<>();
                            if (distinctSeen.add(v.toString())) count++;
                        } else {
                            count++;
                        }
                    }
                }
            }
            case "sum" -> {
                if (!args.isEmpty()) {
                    Object v = eval.eval(args.get(0), ctx);
                    if (v != null) {
                        runningSum = hasValue ? addNumbers(runningSum, v) : v;
                        hasValue   = true;
                    }
                }
            }
            case "avg" -> {
                if (!args.isEmpty()) {
                    Object v = eval.eval(args.get(0), ctx);
                    if (v != null) {
                        runningSum = hasValue ? addNumbers(runningSum, v) : v;
                        count++;
                        hasValue = true;
                    }
                }
            }
            case "min" -> {
                if (!args.isEmpty()) {
                    Object v = eval.eval(args.get(0), ctx);
                    if (v != null) {
                        if (!hasValue || compareValues(v, runningMin) < 0) runningMin = v;
                        hasValue = true;
                    }
                }
            }
            case "max" -> {
                if (!args.isEmpty()) {
                    Object v = eval.eval(args.get(0), ctx);
                    if (v != null) {
                        if (!hasValue || compareValues(v, runningMax) > 0) runningMax = v;
                        hasValue = true;
                    }
                }
            }
            case "bool_and", "every" -> {
                if (!args.isEmpty()) {
                    Object v = eval.eval(args.get(0), ctx);
                    if (v != null) { runningBool = runningBool && Boolean.TRUE.equals(v); hasValue = true; }
                }
            }
            case "bool_or" -> {
                if (!args.isEmpty()) {
                    Object v = eval.eval(args.get(0), ctx);
                    if (v != null) { runningBool = runningBool || Boolean.TRUE.equals(v); hasValue = true; }
                }
            }
            case "string_agg" -> {
                if (!args.isEmpty()) {
                    Object v = eval.eval(args.get(0), ctx);
                    if (v != null) {
                        if (!aggOrder.isEmpty()) {
                            if (orderedItems == null) orderedItems = new ArrayList<>();
                            Object[] entry = new Object[aggOrder.size() + 1];
                            for (int i = 0; i < aggOrder.size(); i++)
                                entry[i] = eval.eval(aggOrder.get(i).node(), ctx);
                            entry[aggOrder.size()] = v.toString();
                            orderedItems.add(entry);
                        } else {
                            if (items == null) items = new ArrayList<>();
                            items.add(v.toString());
                        }
                    }
                }
            }
            case "array_agg" -> {
                if (!args.isEmpty()) {
                    Object v = eval.eval(args.get(0), ctx);
                    if (!aggOrder.isEmpty()) {
                        if (orderedItems == null) orderedItems = new ArrayList<>();
                        Object[] entry = new Object[aggOrder.size() + 1];
                        for (int i = 0; i < aggOrder.size(); i++)
                            entry[i] = eval.eval(aggOrder.get(i).node(), ctx);
                        entry[aggOrder.size()] = v;
                        orderedItems.add(entry);
                    } else {
                        if (items == null) items = new ArrayList<>();
                        items.add(v); // includes NULLs
                    }
                }
            }
            case "json_agg", "jsonb_agg" -> {
                if (!args.isEmpty()) {
                    Object v = eval.eval(args.get(0), ctx);
                    if (!aggOrder.isEmpty()) {
                        if (orderedItems == null) orderedItems = new ArrayList<>();
                        Object[] entry = new Object[aggOrder.size() + 1];
                        for (int i = 0; i < aggOrder.size(); i++)
                            entry[i] = eval.eval(aggOrder.get(i).node(), ctx);
                        entry[aggOrder.size()] = v;
                        orderedItems.add(entry);
                    } else {
                        if (items == null) items = new ArrayList<>();
                        items.add(v);
                    }
                }
            }
            case "json_object_agg", "jsonb_object_agg" -> {
                if (args.size() >= 2) {
                    Object key = eval.eval(args.get(0), ctx);
                    Object val = eval.eval(args.get(1), ctx);
                    if (!aggOrder.isEmpty()) {
                        if (orderedItems == null) orderedItems = new ArrayList<>();
                        Object[] entry = new Object[aggOrder.size() + 2];
                        for (int i = 0; i < aggOrder.size(); i++)
                            entry[i] = eval.eval(aggOrder.get(i).node(), ctx);
                        entry[aggOrder.size()]     = key;
                        entry[aggOrder.size() + 1] = val;
                        orderedItems.add(entry);
                    } else {
                        if (items == null) items = new ArrayList<>();
                        items.add(new Object[]{key, val});
                    }
                }
            }
            default -> {
                // User-defined aggregate: evaluate first arg and pass to the UDA accumulator
                if (udaAcc != null && !args.isEmpty()) {
                    Object v = eval.eval(args.get(0), ctx);
                    udaAcc.accumulate(v);
                }
            }
        }
    }

    /** Compute and return the final aggregate value. */
    public Object result() throws SQLException {
        return switch (funcName) {
            case "count"                -> count;
            case "sum"                  -> hasValue ? runningSum : null;
            case "avg"                  -> {
                if (!hasValue || count == 0) yield null;
                BigDecimal sum = toBD(runningSum);
                yield sum.divide(BigDecimal.valueOf(count), 10, RoundingMode.HALF_UP)
                        .stripTrailingZeros();
            }
            case "min"                  -> hasValue ? runningMin : null;
            case "max"                  -> hasValue ? runningMax : null;
            case "bool_and", "every"    -> hasValue ? runningBool : null;
            case "bool_or"              -> hasValue ? runningBool : null;
            case "string_agg" -> {
                if (orderedItems != null && !orderedItems.isEmpty()) {
                    sortOrderedItems();
                    yield String.join(separator, orderedItems.stream()
                            .map(e -> e[aggOrder.size()].toString()).toList());
                }
                yield items != null && !items.isEmpty() ? String.join(separator, items.stream()
                        .map(Object::toString).toList()) : null;
            }
            case "array_agg" -> {
                if (orderedItems != null && !orderedItems.isEmpty()) {
                    sortOrderedItems();
                    List<Object> sorted = new ArrayList<>(orderedItems.size());
                    for (Object[] e : orderedItems) sorted.add(e[aggOrder.size()]);
                    yield sorted;
                }
                yield items != null && !items.isEmpty() ? new ArrayList<>(items) : null;
            }
            case "json_agg", "jsonb_agg" -> {
                List<Object> elems;
                if (orderedItems != null && !orderedItems.isEmpty()) {
                    sortOrderedItems();
                    elems = orderedItems.stream().map(e -> e[aggOrder.size()]).toList();
                } else {
                    elems = items;
                }
                yield (elems != null && !elems.isEmpty()) ? org.pgjava.types.JsonOps.toJsonArray(elems) : null;
            }
            case "json_object_agg", "jsonb_object_agg" -> {
                java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
                if (orderedItems != null && !orderedItems.isEmpty()) {
                    sortOrderedItems();
                    for (Object[] e : orderedItems) {
                        int ks = aggOrder.size();
                        String k = e[ks] == null ? null : e[ks].toString();
                        map.put(k, e[ks + 1]);
                    }
                } else if (items != null) {
                    for (Object it : items) {
                        if (it instanceof Object[] pair && pair.length >= 2) {
                            String k = pair[0] == null ? null : pair[0].toString();
                            map.put(k, pair[1]);
                        }
                    }
                }
                yield map.isEmpty() ? null : org.pgjava.types.JsonOps.jsonObjectFromMap(map);
            }
            default -> {
                if (udaAcc != null) yield udaAcc.result();
                yield null;
            }
        };
    }

    /** Clone this accumulator (fresh state, same config). */
    public AggAccumulator fresh() throws java.sql.SQLException {
        return new AggAccumulator(
                new FunctionCall(List.of(funcName), args, aggDistinct, aggOrder, aggFilter, aggStar, false, null),
                eval, registry);
    }

    // -------------------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void sortOrderedItems() {
        if (orderedItems == null || aggOrder.isEmpty()) return;
        orderedItems.sort((a, b) -> {
            for (int i = 0; i < aggOrder.size(); i++) {
                SortKey sk = aggOrder.get(i);
                boolean nullsLast = sk.nulls() == SortByNulls.LAST
                        || ((sk.nulls() == null || sk.nulls() == SortByNulls.DEFAULT) && sk.dir() != SortByDir.DESC);
                int cmp = compareNullable(a[i], b[i], nullsLast);
                if (cmp != 0) return sk.dir() == SortByDir.DESC ? -cmp : cmp;
            }
            return 0;
        });
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
        // Enum values — compare by ordinal
        if (a instanceof org.pgjava.types.EnumValue ea && b instanceof org.pgjava.types.EnumValue eb) {
            return ea.compareTo(eb);
        }
        if (a instanceof org.pgjava.types.EnumValue ea && b instanceof String sb) {
            return ea.compareToLabel(sb);
        }
        if (a instanceof String sa && b instanceof org.pgjava.types.EnumValue eb) {
            return -eb.compareToLabel(sa);
        }
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) return toBD(na).compareTo(toBD(nb));
        // Enum values — compare by ordinal
        if (a instanceof org.pgjava.types.EnumValue ea && b instanceof org.pgjava.types.EnumValue eb) {
            return ea.compareTo(eb);
        }
        if (a instanceof org.pgjava.types.EnumValue ea && b instanceof String sb) {
            return ea.compareToLabel(sb);
        }
        if (a instanceof String sa && b instanceof org.pgjava.types.EnumValue eb) {
            return -eb.compareToLabel(sa);
        }
        if (a instanceof Comparable ca) return ca.compareTo(b);
        return 0;
    }

    private Object addNumbers(Object a, Object b) {
        if (a instanceof BigDecimal || b instanceof BigDecimal)
            return toBD(a).add(toBD(b));
        if (a instanceof Double || b instanceof Double)
            return ((Number) a).doubleValue() + ((Number) b).doubleValue();
        // PG: SUM(integer) returns bigint to avoid overflow
        return ((Number) a).longValue() + ((Number) b).longValue();
    }

    private BigDecimal toBD(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Double d)     return BigDecimal.valueOf(d);
        if (v instanceof Float f)      return BigDecimal.valueOf(f);
        if (v instanceof Long l)       return BigDecimal.valueOf(l);
        return BigDecimal.valueOf(((Number) v).longValue());
    }
}
