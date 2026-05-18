package org.pgjava.executor;

import org.pgjava.sql.ast.Expr;
import org.pgjava.sql.ast.JoinType;
import org.pgjava.storage.Row;
import org.pgjava.storage.RowId;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Nested-loop join.  Supports INNER, LEFT, RIGHT, FULL OUTER, and CROSS joins.
 *
 * <p>The right side is fully materialized in memory (acceptable for ≤10k rows).
 */
public final class NestedLoopJoin extends Operator {

    private static final RowId JOIN_ROWID = new RowId(-4, -4);

    private final Operator  left;
    private final Operator  right;
    private final Expr      condition;   // ON clause; null = cross join
    private final JoinType  joinType;
    private final Evaluator eval;

    // Runtime state
    private List<Row>  rightBuffer;
    private Row        currentLeft;
    private int        rightIdx;
    private boolean    leftMatched;      // for LEFT/FULL outer joins
    private Set<Row>   rightUnmatched;   // for RIGHT/FULL outer joins

    private boolean    rightPhase;       // true when emitting RIGHT OUTER unmatched rows
    private java.util.Iterator<Row> unmatchedIter;

    public NestedLoopJoin(Operator left, Operator right, Expr condition, JoinType joinType,
                          Evaluator eval) {
        super(left.schema().join(right.schema()));
        this.left      = left;
        this.right     = right;
        this.condition = condition;
        this.joinType  = joinType;
        this.eval      = eval;
    }

    @Override
    public void open() throws SQLException {
        left.open();
        right.open();
        // Materialize right side
        rightBuffer = new ArrayList<>();
        Row r;
        while ((r = right.next()) != null) rightBuffer.add(r);
        right.close();

        if (joinType == JoinType.RIGHT || joinType == JoinType.FULL) {
            rightUnmatched = new LinkedHashSet<>(rightBuffer); // will remove matched ones
        }

        currentLeft = null;
        rightIdx    = 0;
        leftMatched = false;
        rightPhase  = false;
        unmatchedIter = null;
    }

    @Override
    public Row next() throws SQLException {
        if (rightPhase) {
            // Emit RIGHT/FULL unmatched right rows (null-padded on left)
            if (unmatchedIter != null && unmatchedIter.hasNext()) {
                return makeNullLeft(unmatchedIter.next());
            }
            return null;
        }

        while (true) {
            // Need a left row?
            if (currentLeft == null || rightIdx >= rightBuffer.size()) {
                // Emit null-padded left row if LEFT/FULL outer and no right match
                if (currentLeft != null && !leftMatched
                        && (joinType == JoinType.LEFT || joinType == JoinType.FULL)) {
                    leftMatched = true; // prevent double-emit
                    return makeNullRight(currentLeft);
                }
                // Advance left
                currentLeft = left.next();
                if (currentLeft == null) {
                    // Left exhausted: emit right unmatched for RIGHT/FULL
                    if ((joinType == JoinType.RIGHT || joinType == JoinType.FULL)
                            && rightUnmatched != null && !rightUnmatched.isEmpty()) {
                        rightPhase   = true;
                        unmatchedIter = rightUnmatched.iterator();
                        return next();
                    }
                    return null;
                }
                rightIdx    = 0;
                leftMatched = false;
            }

            // Try each right row
            while (rightIdx < rightBuffer.size()) {
                Row rRow = rightBuffer.get(rightIdx++);
                Row joined = join(currentLeft, rRow);
                if (condition == null) {
                    // Cross / NATURAL / USING (condition is null for cross join)
                    markRightMatched(rRow);
                    leftMatched = true;
                    return joined;
                }
                EvalContext ctx = schema.buildContext(joined);
                Object matches = eval.eval(condition, ctx);
                if (Boolean.TRUE.equals(matches)) {
                    markRightMatched(rRow);
                    leftMatched = true;
                    return joined;
                }
            }
            // All right rows consumed for this left row — loop to advance left
        }
    }

    @Override
    public void close() {
        left.close();
        rightBuffer   = null;
        rightUnmatched = null;
    }

    // -------------------------------------------------------------------------

    private Row join(Row lRow, Row rRow) {
        Object[] lv = lRow.values();
        Object[] rv = rRow.values();
        Object[] combined = new Object[lv.length + rv.length];
        System.arraycopy(lv, 0, combined, 0, lv.length);
        System.arraycopy(rv, 0, combined, lv.length, rv.length);
        return new Row(lRow.rowId(), combined);
    }

    private Row makeNullRight(Row lRow) {
        int rWidth = right.schema().width();
        Object[] lv = lRow.values();
        Object[] combined = new Object[lv.length + rWidth];
        System.arraycopy(lv, 0, combined, 0, lv.length);
        // right part stays null
        return new Row(lRow.rowId(), combined);
    }

    private Row makeNullLeft(Row rRow) {
        int lWidth = left.schema().width();
        Object[] rv = rRow.values();
        Object[] combined = new Object[lWidth + rv.length];
        // left part stays null
        System.arraycopy(rv, 0, combined, lWidth, rv.length);
        return new Row(JOIN_ROWID, combined);
    }

    private void markRightMatched(Row rRow) {
        if (rightUnmatched != null) rightUnmatched.remove(rRow);
    }

    @Override protected String planNodeName() { return "Nested Loop"; }
    @Override protected String planDetail()   { return joinType.name(); }
    @Override protected java.util.List<Operator> planChildren() { return java.util.List.of(left, right); }
}
