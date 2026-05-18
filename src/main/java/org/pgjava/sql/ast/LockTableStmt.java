package org.pgjava.sql.ast;

import java.util.List;

/** LOCK TABLE ... [IN mode MODE] [NOWAIT]. */
public record LockTableStmt(
        List<RangeVar> relations,
        int mode,       // 1–8, matching PostgreSQL LockStmt.mode
        boolean nowait
) implements Stmt {}
