package org.pgjava.sql.ast;

/**
 * Catch-all for statement types that parse correctly but are not yet mapped to
 * a specific AST node. The {@code stmtType} names the grammar rule or SQL keyword.
 * The executor will throw {@code UnsupportedOperationException} for these.
 */
public record UnsupportedStmt(String stmtType, String rawText) implements Stmt {}
