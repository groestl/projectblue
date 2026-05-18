package org.pgjava.sql.ast;

/** ROLLBACK TO [SAVEPOINT] name. */
public record RollbackToSavepointStmt(String name) implements Stmt {}
