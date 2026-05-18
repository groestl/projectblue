package org.pgjava.sql.ast;

/** One WHEN … THEN … arm inside a CASE expression. */
public record CaseWhen(Expr condition, Expr result) implements Node {}
