package org.pgjava.sql.ast;

/** A parameter in a function/procedure definition. */
public record FunctionParameter(
        String name,
        TypeName argType,
        FunctionParameterMode mode,
        Expr defexpr
) implements Node {}
