package org.pgjava.sql.ast;

import java.util.List;

/** WITH [RECURSIVE] clause. */
public record WithClause(List<CommonTableExpr> ctes, boolean recursive) implements Node {}
