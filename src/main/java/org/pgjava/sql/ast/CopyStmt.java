package org.pgjava.sql.ast;

import java.util.List;

/** COPY table FROM/TO …. */
public record CopyStmt(
        RangeVar relation,
        SelectStmt query,
        boolean isFrom,
        List<String> attlist,
        List<DefElem> options
) implements Stmt {}
