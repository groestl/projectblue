package org.pgjava.sql.ast;

/**
 * A binary operator expression.
 *
 * @param op operator string: {@code "="}, {@code "<>"}, {@code "+"}, {@code "AND"},
 *           {@code "OR"}, {@code "||"}, {@code "@>"}, etc.
 */
public record BinaryOp(String op, Expr left, Expr right) implements Expr {}
