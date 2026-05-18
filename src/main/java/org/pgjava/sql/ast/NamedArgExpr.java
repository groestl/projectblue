package org.pgjava.sql.ast;

/** Named function argument: {@code name => expr}. */
public record NamedArgExpr(String name, Expr arg) implements Expr {}
