package org.pgjava.sql.ast.plpgsql;

import java.util.List;

/**
 * CASE WHEN expression-list THEN statements
 *
 * @param exprs  comma-separated expression texts
 * @param stmts  body statements
 */
public record CaseWhenClause(
        List<String> exprs,
        List<PlPgSqlStmt> stmts
) implements PlPgSqlNode {}
