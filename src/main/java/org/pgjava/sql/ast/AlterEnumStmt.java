package org.pgjava.sql.ast;

import java.util.List;

/**
 * ALTER TYPE ... ADD VALUE — adds a new label to an enum type.
 */
public record AlterEnumStmt(
    List<String> typeName,
    String newVal,
    String newValNeighbor,      // null when no BEFORE/AFTER
    boolean newValIsAfter,
    boolean skipIfNewValExists
) implements Stmt {}
