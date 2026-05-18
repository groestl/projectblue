package org.pgjava.sql.ast;

import java.util.List;

/**
 * A function or aggregate call.
 *
 * @param funcname   qualified function name, e.g. {@code ["count"]}, {@code ["pg_catalog","now"]}
 * @param args       argument list; empty for {@code f()}
 * @param aggDistinct  true when DISTINCT keyword present
 * @param aggOrder   ORDER BY inside an aggregate
 * @param aggFilter  FILTER (WHERE …) clause
 * @param aggStar    true for {@code count(*)}
 * @param withinGroup true for ordered-set aggregates
 * @param over       OVER clause; null for non-window functions
 */
public record FunctionCall(
        List<String> funcname,
        List<Expr> args,
        boolean aggDistinct,
        List<SortKey> aggOrder,
        Expr aggFilter,
        boolean aggStar,
        boolean withinGroup,
        WindowDef over
) implements Expr {

    public static FunctionCall simple(String name, List<Expr> args) {
        return new FunctionCall(List.of(name), args, false, List.of(), null, false, false, null);
    }
}
