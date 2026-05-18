package org.pgjava.sql.ast;

/** SHOW name. */
public record ShowStmt(String name) implements Stmt {}
