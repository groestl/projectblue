package org.pgjava.sql.ast;

/**
 * One item in a SELECT target list: {@code expr [AS alias]}.
 * A {@code null} name means no alias; {@code "*"} as val is represented as a
 * {@link ColumnRef} with a single {@code "*"} field.
 */
public record TargetEntry(Expr val, String name) implements Node {}
