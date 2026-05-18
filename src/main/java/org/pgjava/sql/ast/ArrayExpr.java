package org.pgjava.sql.ast;

import java.util.List;

/** {@code ARRAY[e1, e2, …]} constructor expression. */
public record ArrayExpr(List<Expr> elements) implements Expr {}
