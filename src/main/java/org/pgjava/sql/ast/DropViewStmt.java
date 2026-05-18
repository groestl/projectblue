package org.pgjava.sql.ast;

import java.util.List;

/** DROP VIEW [IF EXISTS] …. */
public record DropViewStmt(List<RangeVar> views, boolean ifExists, DropBehavior behavior)
        implements Stmt {}
