package org.pgjava.sql.ast;

import java.util.List;

/** {@code GREATEST(…)} or {@code LEAST(…)}. */
public record MinMaxExpr(MinMaxOp op, List<Expr> args) implements Expr {}
