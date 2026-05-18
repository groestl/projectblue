package org.pgjava.sql.ast;

/** String literal with escape processing already applied. */
public record StringLiteral(String value) implements Expr {}
