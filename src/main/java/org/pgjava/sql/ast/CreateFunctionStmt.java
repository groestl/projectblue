package org.pgjava.sql.ast;

import java.util.List;

/** CREATE [OR REPLACE] FUNCTION / PROCEDURE. */
public record CreateFunctionStmt(
        List<String> funcname,
        List<FunctionParameter> params,
        TypeName returnType,
        List<DefElem> options,
        boolean replace,
        boolean isProcedure
) implements Stmt {}
