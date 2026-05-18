package org.pgjava.sql.ast;

/**
 * Array subscript or slice: {@code a[i]} or {@code a[i:j]}.
 *
 * @param isSlice true when this is a slice ({@code a[i:j]})
 * @param idxUpper null for simple subscript
 */
public record SubscriptExpr(Expr target, Expr idx, Expr idxUpper, boolean isSlice) implements Expr {}
