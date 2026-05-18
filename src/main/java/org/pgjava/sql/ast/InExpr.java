package org.pgjava.sql.ast;

import java.util.List;

/** {@code x [NOT] IN (v1, v2, …)} with a literal value list. */
public record InExpr(Expr arg, List<Expr> list, boolean negated) implements Expr {}
