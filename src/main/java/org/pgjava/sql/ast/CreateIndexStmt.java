package org.pgjava.sql.ast;

import java.util.List;

/** CREATE [UNIQUE] INDEX [CONCURRENTLY] … ON table (columns). */
public record CreateIndexStmt(
        String name,
        RangeVar relation,
        List<IndexElem> indexParams,
        Expr whereClause,
        boolean unique,
        boolean ifNotExists,
        String accessMethod,
        boolean concurrent
) implements Stmt {
    /** Backwards-compatible constructor with concurrent=false. */
    public CreateIndexStmt(String name, RangeVar relation, List<IndexElem> indexParams,
                           Expr whereClause, boolean unique, boolean ifNotExists,
                           String accessMethod) {
        this(name, relation, indexParams, whereClause, unique, ifNotExists, accessMethod, false);
    }
}
