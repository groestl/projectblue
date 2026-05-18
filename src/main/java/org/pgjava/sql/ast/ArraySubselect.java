package org.pgjava.sql.ast;

/** {@code ARRAY(SELECT …)} expression. */
public record ArraySubselect(SelectStmt subselect) implements Expr {}
