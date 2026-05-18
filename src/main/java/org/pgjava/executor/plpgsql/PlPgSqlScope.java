package org.pgjava.executor.plpgsql;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Block-scoped variable environment for PL/pgSQL.
 * Forms a linked chain (child → parent) — one scope per DECLARE/BEGIN block.
 * Name resolution walks up the chain; inner blocks shadow outer variables.
 */
public final class PlPgSqlScope {

    private final PlPgSqlScope parent;
    private final Map<String, PlPgSqlVariable> vars = new LinkedHashMap<>();
    private final Map<String, PlPgSqlCursor> cursors = new LinkedHashMap<>();
    private boolean found; // FOUND special variable (per-scope)
    private long rowCount; // ROW_COUNT for GET DIAGNOSTICS

    public PlPgSqlScope(PlPgSqlScope parent) {
        this.parent = parent;
    }

    /** Declare a new variable in this scope. */
    public void declare(PlPgSqlVariable var) {
        vars.put(var.name().toLowerCase(), var);
    }

    /**
     * Look up a variable by name, walking up the scope chain.
     * Returns null if not found.
     */
    public PlPgSqlVariable resolve(String name) {
        String key = name.toLowerCase();
        PlPgSqlVariable v = vars.get(key);
        if (v != null) return v;
        if (parent != null) return parent.resolve(key);
        return null;
    }

    /** Get the FOUND variable state for this scope chain. */
    public boolean isFound() {
        return found;
    }

    /** Set the FOUND variable. */
    public void setFound(boolean found) {
        this.found = found;
        // Also propagate to parent so FOUND is visible across scope boundaries
        if (parent != null) parent.setFound(found);
    }

    public long rowCount() { return rowCount; }
    public void setRowCount(long rowCount) {
        this.rowCount = rowCount;
        if (parent != null) parent.setRowCount(rowCount);
    }

    public PlPgSqlScope parent() { return parent; }

    /** Register a cursor in this scope. */
    public void declareCursor(PlPgSqlCursor cursor) {
        cursors.put(cursor.name().toLowerCase(), cursor);
    }

    /** Look up a cursor by name, walking up the scope chain. */
    public PlPgSqlCursor resolveCursor(String name) {
        String key = name.toLowerCase();
        PlPgSqlCursor c = cursors.get(key);
        if (c != null) return c;
        if (parent != null) return parent.resolveCursor(key);
        return null;
    }
}
