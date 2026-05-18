package org.pgjava.sql.ast.plpgsql;

import java.util.List;

/**
 * EXCEPTION WHEN condition [OR condition ...] THEN statements
 *
 * @param conditions  list of condition names or SQLSTATE codes
 * @param stmts       handler body statements
 */
public record ExceptionHandler(
        List<String> conditions,
        List<PlPgSqlStmt> stmts
) implements PlPgSqlNode {}
