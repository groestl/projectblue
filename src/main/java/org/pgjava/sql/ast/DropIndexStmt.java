package org.pgjava.sql.ast;

import java.util.List;

/** DROP INDEX [IF EXISTS] …. */
public record DropIndexStmt(List<String> indexNames, boolean ifExists, DropBehavior behavior)
        implements Stmt {}
