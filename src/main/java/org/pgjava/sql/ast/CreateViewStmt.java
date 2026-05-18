package org.pgjava.sql.ast;

import java.util.List;

/** CREATE [OR REPLACE] VIEW …. */
public record CreateViewStmt(
        RangeVar view,
        List<String> aliases,
        SelectStmt query,
        boolean replace,
        boolean temp,
        ViewCheckOption checkOption
) implements Stmt {
    public enum ViewCheckOption { NONE, LOCAL, CASCADED }

    /** Backwards-compatible constructor defaulting to no check option. */
    public CreateViewStmt(RangeVar view, List<String> aliases, SelectStmt query,
                          boolean replace, boolean temp) {
        this(view, aliases, query, replace, temp, ViewCheckOption.NONE);
    }
}
