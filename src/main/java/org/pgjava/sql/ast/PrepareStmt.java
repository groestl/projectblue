package org.pgjava.sql.ast;

import java.util.List;

/** {@code PREPARE name [(types)] AS statement} */
public record PrepareStmt(
        String name,
        List<TypeName> argTypes,
        Stmt query
) implements Stmt {
    /** Backwards-compatible constructor with no arg types. */
    public PrepareStmt(String name, Stmt query) {
        this(name, List.of(), query);
    }
}
