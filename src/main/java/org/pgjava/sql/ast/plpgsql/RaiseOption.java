package org.pgjava.sql.ast.plpgsql;

/**
 * RAISE USING option: MESSAGE = expr, DETAIL = expr, etc.
 *
 * @param name   option name (MESSAGE, DETAIL, HINT, ERRCODE, etc.)
 * @param expr   raw SQL expression text
 */
public record RaiseOption(
        String name,
        String expr
) implements PlPgSqlNode {}
