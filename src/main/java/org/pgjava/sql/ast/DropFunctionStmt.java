package org.pgjava.sql.ast;

import java.util.List;

/**
 * DROP FUNCTION / PROCEDURE [IF EXISTS] name [(argtype, ...)] [, ...].
 *
 * <p>Each target carries the qualified name and optional argument types.
 * When {@code argTypes} is null, argument types were not specified — PostgreSQL
 * requires that the function be unambiguous (exactly one overload) in that case.
 */
public record DropFunctionStmt(List<Target> targets, boolean ifExists) implements Stmt {

    /**
     * A single function/procedure to drop.
     *
     * @param name     qualified name parts (e.g. ["public", "myfunc"] or ["myfunc"])
     * @param argTypes argument type names, or null if not specified
     */
    public record Target(List<String> name, List<TypeName> argTypes) {}
}
