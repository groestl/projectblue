package org.pgjava.sql.ast;

/** Type-prefixed literal: {@code DATE '2024-01-01'}, {@code TIMESTAMP '...'}. */
public record TypedLiteral(TypeName typeName, String value) implements Expr {}
