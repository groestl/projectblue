package org.pgjava.sql.ast;

import java.util.List;

/** {@code GROUPING(col1, col2, …)} function. */
public record GroupingExpr(List<Expr> args) implements Expr {}
