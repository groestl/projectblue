package org.pgjava.sql.ast;

import java.util.List;

/** {@code CALL procedure_name(args...)} */
public record CallStmt(
        List<String> funcname,
        List<Expr> args
) implements Stmt {}
