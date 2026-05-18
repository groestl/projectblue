package org.pgjava.sql.ast;

/** Floating-point literal stored as raw string to avoid precision loss. */
public record FloatLiteral(String rawStr) implements Expr {}
