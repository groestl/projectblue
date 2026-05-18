package org.pgjava.sql.ast;

/**
 * {@code DO $$ ... $$ [LANGUAGE plpgsql]} — anonymous code block execution.
 *
 * @param body      the code block body text (between dollar-quotes)
 * @param language  the procedural language (default: plpgsql)
 */
public record DoStmt(String body, String language) implements Stmt {}
