package org.pgjava.sql.ast;

/** A positional parameter reference: {@code $1}, {@code $2}, … */
public record ParamRef(int number) implements Expr {}
