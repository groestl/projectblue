package org.pgjava.executor.plpgsql;

import org.pgjava.storage.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime state for a PL/pgSQL cursor.
 *
 * <p>Materializes the query result into a list of rows for bidirectional traversal.
 * This matches PostgreSQL's SCROLL cursor behavior and simplifies ABSOLUTE/RELATIVE.
 */
public final class PlPgSqlCursor {

    private final String name;
    private final String querySql;       // for bound cursors, the SQL query
    private final List<String> paramNames; // cursor parameter names (for substitution)
    private final List<Object[]> rows;   // materialized result set
    private List<String> columnNames;    // column names from query output schema
    private int position;                // 0 = before first, rows.size()+1 = after last
    private boolean open;

    PlPgSqlCursor(String name, String querySql) {
        this(name, querySql, List.of());
    }

    PlPgSqlCursor(String name, String querySql, List<String> paramNames) {
        this.name = name;
        this.querySql = querySql;
        this.paramNames = paramNames;
        this.rows = new ArrayList<>();
        this.position = 0; // before first
        this.open = false;
    }

    public String name() { return name; }
    public String querySql() { return querySql; }
    public List<String> paramNames() { return paramNames; }
    public boolean isOpen() { return open; }

    void open(List<Object[]> materializedRows) {
        open(materializedRows, List.of());
    }

    void open(List<Object[]> materializedRows, List<String> colNames) {
        this.rows.clear();
        this.rows.addAll(materializedRows);
        this.columnNames = colNames;
        this.position = 0; // before first
        this.open = true;
    }

    /** Column names from the cursor's query output schema. */
    public List<String> columnNames() { return columnNames != null ? columnNames : List.of(); }

    void close() {
        this.rows.clear();
        this.position = 0;
        this.open = false;
    }

    /**
     * Fetch the next row (FETCH NEXT). Returns null if no more rows.
     */
    Object[] fetchNext() {
        if (position < rows.size()) {
            position++;
            return rows.get(position - 1);
        }
        position = rows.size() + 1;
        return null;
    }

    /**
     * Fetch the prior row (FETCH PRIOR). Returns null if before first.
     */
    Object[] fetchPrior() {
        if (position > 1) {
            position--;
            return rows.get(position - 1);
        }
        position = 0;
        return null;
    }

    /**
     * Fetch the first row.
     */
    Object[] fetchFirst() {
        if (rows.isEmpty()) return null;
        position = 1;
        return rows.get(0);
    }

    /**
     * Fetch the last row.
     */
    Object[] fetchLast() {
        if (rows.isEmpty()) return null;
        position = rows.size();
        return rows.get(position - 1);
    }

    /**
     * Fetch by absolute position (1-based). Negative counts from end.
     */
    Object[] fetchAbsolute(int n) {
        if (n > 0 && n <= rows.size()) {
            position = n;
            return rows.get(position - 1);
        }
        if (n < 0) {
            int idx = rows.size() + n + 1;
            if (idx >= 1 && idx <= rows.size()) {
                position = idx;
                return rows.get(position - 1);
            }
        }
        if (n == 0) position = 0;
        else position = n > 0 ? rows.size() + 1 : 0;
        return null;
    }

    /**
     * Fetch by relative offset from current position.
     */
    Object[] fetchRelative(int n) {
        return fetchAbsolute(position + n);
    }

    /**
     * Return all remaining rows (FORWARD ALL) from current position.
     */
    List<Object[]> fetchForwardAll() {
        List<Object[]> result = new ArrayList<>();
        while (position < rows.size()) {
            position++;
            result.add(rows.get(position - 1));
        }
        position = rows.size() + 1;
        return result;
    }

    /**
     * Move without returning data — same logic as fetch but discards results.
     * Returns true if the cursor lands on a valid row.
     */
    boolean moveNext() { return fetchNext() != null; }
    boolean movePrior() { return fetchPrior() != null; }
    boolean moveFirst() { return fetchFirst() != null; }
    boolean moveLast() { return fetchLast() != null; }
    boolean moveAbsolute(int n) { return fetchAbsolute(n) != null; }
    boolean moveRelative(int n) { return fetchRelative(n) != null; }
}
