package org.pgjava.sql.ast;

/**
 * ROWS/RANGE/GROUPS BETWEEN … AND … frame specification.
 *
 * @param mode       "ROWS", "RANGE", or "GROUPS"
 * @param startBound e.g. UNBOUNDED PRECEDING, CURRENT ROW, or an expr offset
 * @param endBound   null for single-bound frames
 */
public record WindowFrameClause(String mode, Expr startBound, Expr endBound) implements Node {}
