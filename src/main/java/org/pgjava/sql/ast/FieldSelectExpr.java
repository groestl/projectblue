package org.pgjava.sql.ast;

/** Composite field access: {@code (row_expr).fieldname}. */
public record FieldSelectExpr(Expr arg, String fieldName) implements Expr {}
