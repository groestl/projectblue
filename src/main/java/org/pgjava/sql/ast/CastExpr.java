package org.pgjava.sql.ast;

/**
 * A type cast: {@code CAST(x AS type)} or {@code x::type}.
 * Both syntaxes produce the same node.
 */
public record CastExpr(Expr arg, TypeName targetType) implements Expr {}
