package org.pgjava.sql.ast;

/** {@code x [NOT] LIKE/ILIKE/SIMILAR TO pattern [ESCAPE esc]}. */
public record LikeExpr(Expr arg, Expr pattern, Expr escape, LikeType type, boolean negated)
        implements Expr {}
