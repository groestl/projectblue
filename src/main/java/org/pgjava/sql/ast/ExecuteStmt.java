package org.pgjava.sql.ast;

import java.util.List;

/** {@code EXECUTE name [(params)]} */
public record ExecuteStmt(
        String name,
        List<Expr> params
) implements Stmt {}
