package org.pgjava.sql.ast.plpgsql;

import java.util.List;

/** Sealed base for all PL/pgSQL declaration nodes. */
public sealed interface PlPgSqlDecl extends PlPgSqlNode {

    /**
     * Variable declaration: {@code name [CONSTANT] type [NOT NULL] [:= | DEFAULT expr]}
     *
     * @param name         variable name
     * @param typeName     data type as raw text (parsed/resolved at execution)
     * @param constant     CONSTANT keyword present
     * @param notNull      NOT NULL constraint
     * @param defaultExpr  raw SQL expression for default value (null if absent)
     * @param copyType     null, "%TYPE", or "%ROWTYPE"
     */
    record VarDecl(String name, String typeName, boolean constant, boolean notNull,
                   String defaultExpr, String copyType) implements PlPgSqlDecl {}

    /**
     * Record variable: {@code name RECORD}
     */
    record RecordDecl(String name) implements PlPgSqlDecl {}

    /**
     * ALIAS FOR declaration: {@code name ALIAS FOR $1} or {@code name ALIAS FOR param_name}
     */
    record AliasDecl(String name, String target) implements PlPgSqlDecl {}

    /**
     * Cursor declaration: {@code name [NO SCROLL] CURSOR [(params)] FOR query}
     *
     * @param name      cursor name
     * @param params    parameter declarations (may be empty)
     * @param querySql  raw SQL query text
     * @param scroll    null = unspecified, true = SCROLL, false = NO SCROLL
     */
    record CursorDecl(String name, List<CursorParam> params,
                      String querySql, Boolean scroll) implements PlPgSqlDecl {}

    /**
     * Cursor parameter in a CURSOR declaration.
     */
    record CursorParam(String name, String typeName) implements PlPgSqlNode {}
}
