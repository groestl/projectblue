package org.pgjava.sql.ast;

/** {@code INTERVAL '...' [fields]}. */
public record IntervalLiteral(String value, String fields) implements Expr {}
