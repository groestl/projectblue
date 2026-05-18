package org.pgjava.sql.ast;

/** One element in an ORDER BY clause. */
public record SortKey(Expr node, SortByDir dir, SortByNulls nulls) implements Node {}
