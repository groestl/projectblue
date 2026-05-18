package org.pgjava.sql.ast;

import java.util.List;

/** Conflict target for ON CONFLICT. */
public record InferClause(
        List<IndexElem> indexElems,
        Expr whereClause,
        String constraintName
) implements Node {}
