package org.pgjava.sql.ast;

import java.util.List;

/** DROP TABLE [IF EXISTS] …. */
public record DropTableStmt(
        List<RangeVar> relations,
        boolean ifExists,
        DropBehavior behavior
) implements Stmt {}
