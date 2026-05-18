package org.pgjava.sql.ast;

import java.util.List;

/** ALTER TABLE statement. */
public record AlterTableStmt(RangeVar relation, List<AlterTableCmd> cmds) implements Stmt {}
