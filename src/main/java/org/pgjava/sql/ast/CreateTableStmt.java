package org.pgjava.sql.ast;

import java.util.List;

/** A CREATE TABLE statement. */
public record CreateTableStmt(
        RangeVar relation,
        List<ColumnDefNode> columns,
        List<TableConstraintNode> constraints,
        boolean ifNotExists,
        boolean temp
) implements Stmt {}
