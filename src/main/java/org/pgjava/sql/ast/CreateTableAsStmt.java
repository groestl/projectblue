package org.pgjava.sql.ast;

/** CREATE TABLE … AS SELECT. */
public record CreateTableAsStmt(
        RangeVar relation,
        SelectStmt query,
        boolean ifNotExists,
        boolean temp
) implements Stmt {}
