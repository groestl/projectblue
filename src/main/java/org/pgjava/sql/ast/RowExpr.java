package org.pgjava.sql.ast;

import java.util.List;

/** Row constructor: {@code ROW(e1, e2, …)} or {@code (e1, e2, …)}. */
public record RowExpr(List<Expr> args) implements Expr {}
