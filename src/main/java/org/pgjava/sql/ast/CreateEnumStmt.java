package org.pgjava.sql.ast;

import java.util.List;

/** {@code CREATE TYPE name AS ENUM ('label1', 'label2', ...)} */
public record CreateEnumStmt(
        List<String> typeName,
        List<String> labels
) implements Stmt {}
