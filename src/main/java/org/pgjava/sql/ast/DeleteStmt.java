package org.pgjava.sql.ast;

import java.util.List;

/** A DELETE statement. */
public record DeleteStmt(
        RangeVar relation,
        List<FromItem> usingClause,
        Expr whereClause,
        List<TargetEntry> returning,
        WithClause withClause
) implements Stmt {}
