package org.pgjava.sql.ast;

/**
 * ROWS/RANGE/GROUPS BETWEEN … AND … frame specification.
 *
 * @param mode        "ROWS", "RANGE", or "GROUPS"
 * @param startType   bound type for the frame start
 * @param startOffset non-null only when startType is N_PRECEDING or N_FOLLOWING
 * @param endType     bound type for the frame end
 * @param endOffset   non-null only when endType is N_PRECEDING or N_FOLLOWING
 */
public record WindowFrameClause(
        String mode,
        FrameBoundType startType,
        Expr startOffset,
        FrameBoundType endType,
        Expr endOffset
) implements Node {}
