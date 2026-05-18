package org.pgjava.sql.ast.plpgsql;

import java.util.List;

/**
 * Top-level PL/pgSQL function/DO body.
 *
 * <pre>
 * [&lt;&lt;label&gt;&gt;]
 * [DECLARE declarations]
 * BEGIN
 *     statements
 * [EXCEPTION
 *     WHEN condition THEN statements ...]
 * END [label];
 * </pre>
 */
public record PlPgSqlBody(
        String label,
        List<PlPgSqlDecl> decls,
        List<PlPgSqlStmt> stmts,
        List<ExceptionHandler> handlers
) implements PlPgSqlNode {}
