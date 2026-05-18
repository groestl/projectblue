package org.pgjava.sql.ast;

/**
 * A subquery expression: EXISTS, ANY, ALL, or scalar subquery.
 *
 * @param type       the sublink type
 * @param testExpr   left-hand expression for ANY/ALL; null for EXISTS/scalar
 * @param operName   operator for ANY/ALL (e.g. "="); null otherwise
 * @param subselect  the subquery
 */
public record SubLink(
        SubLinkType type,
        Expr testExpr,
        String operName,
        SelectStmt subselect
) implements Expr {}
