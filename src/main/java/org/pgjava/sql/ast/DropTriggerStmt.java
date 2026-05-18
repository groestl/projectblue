package org.pgjava.sql.ast;

/** {@code DROP TRIGGER [IF EXISTS] name ON table [CASCADE|RESTRICT]} */
public record DropTriggerStmt(
        String triggerName,
        String tableName,
        boolean ifExists,
        DropBehavior behavior
) implements Stmt {
    /** Backwards-compatible constructor defaulting to RESTRICT. */
    public DropTriggerStmt(String triggerName, String tableName, boolean ifExists) {
        this(triggerName, tableName, ifExists, DropBehavior.RESTRICT);
    }
}
