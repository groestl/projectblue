package org.pgjava.sql.ast;

import java.util.List;

/**
 * A CASE expression.
 *
 * @param arg         CASE argument for simple CASE ({@code CASE x WHEN ...}); null for searched CASE
 * @param whenClauses list of WHEN … THEN … arms
 * @param defResult   ELSE expression; null if no ELSE
 */
public record CaseExpr(Expr arg, List<CaseWhen> whenClauses, Expr defResult) implements Expr {}
