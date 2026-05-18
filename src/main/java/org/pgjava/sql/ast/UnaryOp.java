package org.pgjava.sql.ast;

/**
 * A unary operator expression.
 *
 * @param op operator string: {@code "NOT"}, {@code "-"}, {@code "+"}, {@code "IS NULL"},
 *           {@code "IS NOT NULL"}, {@code "IS TRUE"}, {@code "IS FALSE"},
 *           {@code "IS UNKNOWN"}, {@code "ISNULL"}, {@code "NOTNULL"}
 */
public record UnaryOp(String op, Expr operand) implements Expr {}
