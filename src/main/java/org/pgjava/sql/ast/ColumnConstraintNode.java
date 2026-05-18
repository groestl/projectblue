package org.pgjava.sql.ast;

import java.util.List;

/** Inline constraint on a column definition. */
public record ColumnConstraintNode(
        ConstrType type,
        String constraintName,
        Expr rawExpr,
        List<String> fkAttrs,
        RangeVar pktable,
        List<String> pkAttrs,
        FkAction fkDelAction,
        FkAction fkUpdAction,
        boolean generatedAlways,
        boolean notNull
) implements Node {

    public static ColumnConstraintNode notNull(String name) {
        return new ColumnConstraintNode(ConstrType.NOT_NULL, name, null,
                List.of(), null, List.of(), null, null, false, true);
    }

    public static ColumnConstraintNode primaryKey(String name) {
        return new ColumnConstraintNode(ConstrType.PRIMARY, name, null,
                List.of(), null, List.of(), null, null, false, false);
    }

    public static ColumnConstraintNode unique(String name) {
        return new ColumnConstraintNode(ConstrType.UNIQUE, name, null,
                List.of(), null, List.of(), null, null, false, false);
    }

    public static ColumnConstraintNode defaultExpr(String name, Expr expr) {
        return new ColumnConstraintNode(ConstrType.DEFAULT, name, expr,
                List.of(), null, List.of(), null, null, false, false);
    }

    public static ColumnConstraintNode check(String name, Expr expr) {
        return new ColumnConstraintNode(ConstrType.CHECK, name, expr,
                List.of(), null, List.of(), null, null, false, false);
    }
}
