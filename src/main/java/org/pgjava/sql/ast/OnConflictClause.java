package org.pgjava.sql.ast;

import java.util.List;

/** ON CONFLICT clause of an INSERT statement. */
public record OnConflictClause(
        OnConflictAction action,
        InferClause infer,
        List<AssignTarget> targetList,
        Expr whereClause
) implements Node {}
