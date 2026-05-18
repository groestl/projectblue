package org.pgjava.sql.ast;

import java.util.List;

/** DROP SCHEMA [IF EXISTS] …. */
public record DropSchemaStmt(List<String> names, boolean ifExists, DropBehavior behavior)
        implements Stmt {}
