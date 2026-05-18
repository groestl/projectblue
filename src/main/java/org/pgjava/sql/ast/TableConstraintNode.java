package org.pgjava.sql.ast;

import java.util.List;

/** Table-level constraint (PRIMARY KEY, UNIQUE, FOREIGN KEY, CHECK). */
public record TableConstraintNode(
        ConstrType type,
        String constraintName,
        List<String> keys,
        RangeVar pktable,
        List<String> fkAttrs,
        FkAction fkDelAction,
        FkAction fkUpdAction,
        Expr rawExpr
) implements Node {}
