package org.pgjava.sql.ast.plpgsql;

import java.util.List;

/**
 * ELSIF condition THEN statements
 *
 * @param condition  raw SQL expression text
 * @param stmts      body statements
 */
public record ElsifClause(
        String condition,
        List<PlPgSqlStmt> stmts
) implements PlPgSqlNode {}
