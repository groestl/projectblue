package org.pgjava.sql.ast;

/** CREATE SCHEMA [IF NOT EXISTS] name. */
public record CreateSchemaStmt(String name, boolean ifNotExists) implements Stmt {}
