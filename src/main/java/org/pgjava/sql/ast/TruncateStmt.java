package org.pgjava.sql.ast;

import java.util.List;

/** TRUNCATE TABLE …. */
public record TruncateStmt(List<RangeVar> relations, boolean restartSeqs, DropBehavior behavior)
        implements Stmt {}
