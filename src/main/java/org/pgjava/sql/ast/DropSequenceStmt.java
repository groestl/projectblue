package org.pgjava.sql.ast;

import java.util.List;

/** DROP SEQUENCE [IF EXISTS] …. */
public record DropSequenceStmt(List<RangeVar> sequences, boolean ifExists, DropBehavior behavior)
        implements Stmt {}
