package org.pgjava.sql.ast;

/** {@code x [NOT] BETWEEN [SYMMETRIC] low AND high}. */
public record BetweenExpr(Expr arg, Expr low, Expr high, boolean negated, boolean symmetric)
        implements Expr {}
