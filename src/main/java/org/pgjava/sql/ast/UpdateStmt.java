package org.pgjava.sql.ast;

import java.util.List;

/** An UPDATE statement. */
public record UpdateStmt(
        RangeVar relation,
        List<AssignTarget> targets,
        List<FromItem> fromClause,
        Expr whereClause,
        List<TargetEntry> returning,
        WithClause withClause
) implements Stmt {}
