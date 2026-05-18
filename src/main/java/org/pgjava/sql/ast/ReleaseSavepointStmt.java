package org.pgjava.sql.ast;

/** RELEASE [SAVEPOINT] name. */
public record ReleaseSavepointStmt(String name) implements Stmt {}
