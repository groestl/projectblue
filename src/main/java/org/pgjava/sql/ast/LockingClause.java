package org.pgjava.sql.ast;

import java.util.List;

/** FOR UPDATE / FOR SHARE clause. */
public record LockingClause(
        LockClauseStrength strength,
        LockWaitPolicy waitPolicy,
        List<RangeVar> lockedRels
) implements Node {}
