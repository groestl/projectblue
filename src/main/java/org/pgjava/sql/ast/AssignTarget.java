package org.pgjava.sql.ast;

import java.util.List;

/**
 * One assignment in an UPDATE SET clause: {@code col = expr} or
 * {@code (col1, col2) = (SELECT ...)}.
 *
 * @param names     column name path, e.g. {@code ["col"]} or {@code ["col","field"]}
 * @param val       the value expression (null when source is a subselect)
 * @param subselect the subselect source (null when val is non-null)
 */
public record AssignTarget(List<String> names, Expr val, SelectStmt subselect) implements Node {}
