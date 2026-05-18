package org.pgjava.sql.ast;

import java.util.List;

/** Column definition inside CREATE TABLE. */
public record ColumnDefNode(
        String colname,
        TypeName typeName,
        List<ColumnConstraintNode> constraints,
        String collation
) implements Node {}
