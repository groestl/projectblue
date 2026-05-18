package org.pgjava.sql.ast;

/** SAVEPOINT name. */
public record SavepointStmt(String name) implements Stmt {}
