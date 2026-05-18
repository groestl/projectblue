package org.pgjava.sql.ast;

import java.util.List;

/** WINDOW definition or inline OVER clause. */
public record WindowDef(
        String name,
        String refname,
        List<Expr> partitionClause,
        List<SortKey> orderClause,
        WindowFrameClause frame
) implements Node {}
