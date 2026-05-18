package org.pgjava.sql.ast;

import java.util.List;

/**
 * A SELECT statement (including set operations).
 *
 * <p>For set operations (UNION/INTERSECT/EXCEPT) the {@code left} and {@code right} fields
 * are non-null and {@code targetList}/{@code fromClause}/etc. are empty/null.
 *
 * @param targetList    SELECT target list (empty = SELECT *)
 * @param fromClause    FROM clause items
 * @param whereClause   WHERE predicate; null if absent
 * @param groupBy       GROUP BY expressions
 * @param having        HAVING predicate; null if absent
 * @param orderBy       ORDER BY keys
 * @param limitCount    LIMIT expression; null if absent; IntegerLiteral(0) for LIMIT ALL
 * @param limitOffset   OFFSET expression; null if absent
 * @param windows       named WINDOW definitions
 * @param distinct      true for SELECT DISTINCT
 * @param distinctOn    non-empty for SELECT DISTINCT ON (exprs)
 * @param setOp         set operation type; null for a plain SELECT
 * @param left          left operand of set operation
 * @param right         right operand of set operation
 * @param setAll        true for UNION/INTERSECT/EXCEPT ALL
 * @param locking       FOR UPDATE / FOR SHARE clauses
 * @param withClause    WITH clause; null if absent
 * @param valuesLists   non-empty for VALUES (...), (...) — each inner list is one row
 */
public record SelectStmt(
        List<TargetEntry> targetList,
        List<FromItem> fromClause,
        Expr whereClause,
        List<Expr> groupBy,
        Expr having,
        List<SortKey> orderBy,
        Expr limitCount,
        Expr limitOffset,
        List<WindowDef> windows,
        boolean distinct,
        List<Expr> distinctOn,
        SetOpType setOp,
        SelectStmt left,
        SelectStmt right,
        boolean setAll,
        List<LockingClause> locking,
        WithClause withClause,
        List<List<Expr>> valuesLists
) implements Stmt {}
