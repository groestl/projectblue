package org.pgjava.sql.ast;

import java.util.List;

/** One CTE definition inside a WITH clause. */
public record CommonTableExpr(
        String ctename,
        List<String> aliasColNames,
        SelectStmt query,
        CTEMaterialize materialized
) implements Node {}
