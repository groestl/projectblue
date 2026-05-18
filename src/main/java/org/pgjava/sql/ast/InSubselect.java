package org.pgjava.sql.ast;

/** {@code x [NOT] IN (SELECT …)}. */
public record InSubselect(Expr arg, SelectStmt subselect, boolean negated) implements Expr {}
