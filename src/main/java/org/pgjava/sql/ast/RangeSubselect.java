package org.pgjava.sql.ast;

import java.util.List;

/** A subquery in a FROM clause: {@code [LATERAL] (SELECT ...) AS alias}. */
public record RangeSubselect(SelectStmt subquery, String alias, List<String> colAliases,
                              boolean lateral)
        implements FromItem {}
