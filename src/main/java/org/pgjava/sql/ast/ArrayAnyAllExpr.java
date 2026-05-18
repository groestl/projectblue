package org.pgjava.sql.ast;

/**
 * {@code expr op ANY(array_expr)} or {@code expr op ALL(array_expr)}.
 *
 * @param testExpr the left-hand expression
 * @param arrayExpr the array expression
 * @param op the comparison operator (=, <>, <, >, <=, >=)
 * @param useAll true for ALL, false for ANY
 */
public record ArrayAnyAllExpr(Expr testExpr, Expr arrayExpr, String op, boolean useAll)
        implements Expr {}
