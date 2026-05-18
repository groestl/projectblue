package org.pgjava.sql.ast;

/** {@code DEALLOCATE [PREPARE] name} or {@code DEALLOCATE ALL} */
public record DeallocateStmt(
        String name  // null means ALL
) implements Stmt {}
