package org.pgjava.sql.ast;

import java.util.List;

/**
 * DROP TYPE / DROP DOMAIN — removes user-defined types.
 */
public record DropTypeStmt(
    List<List<String>> typeNames,
    boolean ifExists,
    DropBehavior behavior
) implements Stmt {
    /** Backwards-compatible constructor defaulting to RESTRICT. */
    public DropTypeStmt(List<List<String>> typeNames, boolean ifExists) {
        this(typeNames, ifExists, DropBehavior.RESTRICT);
    }
}
