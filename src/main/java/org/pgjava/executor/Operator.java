package org.pgjava.executor;

import org.pgjava.storage.Row;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base class for Volcano-model operators.
 *
 * <p>Usage pattern:
 * <pre>{@code
 *     op.open();
 *     try {
 *         Row row;
 *         while ((row = op.next()) != null) {
 *             // process row
 *         }
 *     } finally {
 *         op.close();
 *     }
 * }</pre>
 */
public abstract class Operator implements AutoCloseable {

    protected final OutputSchema schema;

    protected Operator(OutputSchema schema) {
        this.schema = schema;
    }

    /** The output schema of this operator. */
    public OutputSchema schema() { return schema; }

    /** Open the operator (and all children). Must be called before {@link #next()}. */
    public void open() throws SQLException {}

    /**
     * Return the next output row, or {@code null} when exhausted.
     * Each call may evaluate expressions, read storage, etc.
     */
    public abstract Row next() throws SQLException;

    /** Release all resources held by this operator (and all children). */
    @Override
    public void close() {}

    /**
     * Returns plan description lines for EXPLAIN output.
     * Subclasses may override {@link #planNodeName()} and {@link #planDetail()}
     * and add children via {@link #planChildren()}.
     */
    public List<String> explain(int depth) {
        List<String> lines = new ArrayList<>();
        String indent = "  ".repeat(depth);
        String arrow = depth > 0 ? "->  " : "";
        String nodeDesc = planNodeName();
        String detail = planDetail();
        if (detail != null && !detail.isEmpty()) nodeDesc += "  " + detail;
        lines.add(indent + arrow + nodeDesc);
        for (Operator child : planChildren()) {
            lines.addAll(child.explain(depth + 1));
        }
        return lines;
    }

    /** Name of this plan node (e.g., "Seq Scan", "Hash Join"). */
    protected String planNodeName() {
        String name = getClass().getSimpleName();
        // Convert camelCase to spaced: SeqScan → Seq Scan, NestedLoopJoin → Nested Loop Join
        return name.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    /** Optional detail string (e.g., "on tablename", "Filter: (x > 5)"). */
    protected String planDetail() { return ""; }

    /** Child operators for plan tree. */
    protected List<Operator> planChildren() { return List.of(); }
}
