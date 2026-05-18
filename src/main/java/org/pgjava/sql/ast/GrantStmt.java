package org.pgjava.sql.ast;

/** GRANT / REVOKE — accepted as no-op. */
public record GrantStmt(boolean isGrant, String raw) implements Stmt {}
