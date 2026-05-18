package org.pgjava.executor;

import org.pgjava.engine.PgErrorException;
import org.pgjava.storage.Row;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Evaluation context for a single expression evaluation.
 *
 * <p>Carries:
 * <ul>
 *   <li>The current row scope: table-alias → Row (for column references)</li>
 *   <li>Parameter bindings ($1, $2, …)</li>
 *   <li>Column name → 0-based index mapping per table alias</li>
 *   <li>Statement timestamp (fixed for the duration of a statement)</li>
 *   <li>Optional outer context (for correlated subqueries)</li>
 * </ul>
 *
 * <p>Column lookup: {@code ColumnRef(["t","col"])} looks in alias "t";
 * {@code ColumnRef(["col"])} searches all aliases (error if ambiguous).
 */
public final class EvalContext {

    /**
     * Thread-local statement timestamp — set once at statement start so all
     * EvalContexts created within the same statement share the same {@code now()}.
     */
    private static final ThreadLocal<OffsetDateTime> STMT_TS = new ThreadLocal<>();

    /** Set the statement timestamp for the current thread (call once per statement). */
    public static void setStatementTimestamp(OffsetDateTime ts) { STMT_TS.set(ts); }

    /** Clear the statement timestamp (call in finally block). */
    public static void clearStatementTimestamp() { STMT_TS.remove(); }

    /** Return the current statement timestamp, or {@code now()} if none set. */
    private static OffsetDateTime currentStmtTs() {
        OffsetDateTime ts = STMT_TS.get();
        return ts != null ? ts : OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Column metadata: name → 0-based position within Row.values(). */
    public record ColumnMap(Map<String, Integer> nameToIndex) {
        public static ColumnMap of(List<String> names) {
            Map<String, Integer> m = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) m.put(names.get(i).toLowerCase(), i);
            return new ColumnMap(Collections.unmodifiableMap(m));
        }

        public int resolve(String name) throws SQLException {
            Integer idx = nameToIndex.get(name.toLowerCase());
            if (idx == null)
                throw PgErrorException.error("42703", "column \"" + name + "\" does not exist").build();
            return idx;
        }
    }

    // -------------------------------------------------------------------------

    private final Map<String, Row>       rows;        // alias → Row
    private final Map<String, ColumnMap> columnMaps;  // alias → column positions
    private final Object[]               params;      // $1 … $N (0-based here)
    private final OffsetDateTime         stmtTimestamp;
    private final EvalContext            outer;       // for correlated subqueries

    private EvalContext(Map<String, Row> rows, Map<String, ColumnMap> columnMaps,
                        Object[] params, OffsetDateTime stmtTimestamp, EvalContext outer) {
        this.rows          = rows;
        this.columnMaps    = columnMaps;
        this.params        = params;
        this.stmtTimestamp = stmtTimestamp;
        this.outer         = outer;
    }

    // -------------------------------------------------------------------------
    // Factories

    /** Empty context (no rows, no params) — for constant expressions like DEFAULT. */
    public static EvalContext empty() {
        return new EvalContext(Map.of(), Map.of(), new Object[0],
                currentStmtTs(), null);
    }

    /** Context with a single unnamed row (most DML / WHERE evaluation). */
    public static EvalContext of(Row row, ColumnMap cols) {
        return ofAliased("", row, cols);
    }

    /** Context with a single aliased row. */
    public static EvalContext ofAliased(String alias, Row row, ColumnMap cols) {
        return new EvalContext(
                Map.of(alias, row),
                Map.of(alias, cols),
                new Object[0],
                OffsetDateTime.now(ZoneOffset.UTC),
                null);
    }

    /** Context with multiple aliased rows (for JOINs). */
    public static EvalContext ofJoin(Map<String, Row> rows, Map<String, ColumnMap> cols) {
        return new EvalContext(rows, cols, new Object[0],
                currentStmtTs(), null);
    }

    /** Builder: add parameter bindings to an existing context. */
    public EvalContext withParams(Object[] params) {
        return new EvalContext(rows, columnMaps, params, stmtTimestamp, outer);
    }

    /** Builder: fix the statement timestamp. */
    public EvalContext withTimestamp(OffsetDateTime ts) {
        return new EvalContext(rows, columnMaps, params, ts, outer);
    }

    /** Builder: create a child context for a subquery. */
    public EvalContext child(String alias, Row row, ColumnMap cols) {
        Map<String, Row>       childRows = new HashMap<>(rows);
        Map<String, ColumnMap> childCols = new HashMap<>(columnMaps);
        childRows.put(alias, row);
        childCols.put(alias, cols);
        return new EvalContext(childRows, childCols, params, stmtTimestamp, this);
    }

    /** Builder: attach an outer context (for correlated subquery execution). */
    public EvalContext withOuter(EvalContext outerCtx) {
        return new EvalContext(rows, columnMaps, params, stmtTimestamp, outerCtx);
    }

    // -------------------------------------------------------------------------
    // Column resolution

    /**
     * Resolve a column reference. Handles:
     * <ul>
     *   <li>{@code ["col"]} — search all aliases (error if ambiguous)</li>
     *   <li>{@code ["alias","col"]} — look in that specific alias</li>
     * </ul>
     *
     * @throws SQLException 42703 if not found, 42702 if ambiguous
     */
    public Object resolveColumn(List<String> fields) throws SQLException {
        if (fields.isEmpty())
            throw PgErrorException.error("42703", "empty column reference").build();

        String colName = fields.getLast().toLowerCase();

        if (fields.size() >= 2) {
            // Qualified: alias.col or schema.table.col — use the second-to-last as alias
            String alias = fields.get(fields.size() - 2).toLowerCase();
            // Try locally first (exact alias, then empty-string alias)
            Row row = rows.get(alias);
            ColumnMap cm = columnMaps.get(alias);
            if ((row == null || cm == null) && rows.containsKey("")) {
                row = rows.get(""); cm = columnMaps.get("");
            }
            if (row != null && cm != null && cm.nameToIndex().containsKey(colName)) {
                return row.get(cm.resolve(colName));
            }
            // Not found locally — walk outer context for correlated references
            if (outer != null) return outer.resolveColumn(fields);
            throw PgErrorException.error("42703",
                    "column \"" + alias + "." + colName + "\" does not exist").build();
        }

        // Unqualified: search all aliases
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, Row> e : rows.entrySet()) {
            ColumnMap cm = columnMaps.get(e.getKey());
            if (cm != null && cm.nameToIndex().containsKey(colName)) {
                found.add(e.getKey());
            }
        }
        if (found.isEmpty()) {
            // Try outer context (correlated subquery)
            if (outer != null) return outer.resolveColumn(fields);
            throw PgErrorException.error("42703", "column \"" + colName + "\" does not exist").build();
        }
        if (found.size() > 1)
            throw PgErrorException.error("42702", "column reference \"" + colName + "\" is ambiguous").build();

        String alias = found.get(0);
        Row row = rows.get(alias);
        int pos = columnMaps.get(alias).resolve(colName);
        return row.get(pos);
    }

    private Object resolveIn(String alias, String col) throws SQLException {
        // Try exact alias match first, then empty-string alias (unnamed row)
        Row row = rows.get(alias);
        ColumnMap cm = columnMaps.get(alias);
        if (row == null || cm == null) {
            // Try empty alias (from EvalContext.of())
            row = rows.get("");
            cm  = columnMaps.get("");
        }
        if (row == null || cm == null)
            throw PgErrorException.error("42P01", "missing FROM-clause entry for table \"" + alias + "\"").build();
        return row.get(cm.resolve(col));
    }

    // -------------------------------------------------------------------------
    // Parameter resolution

    /** Resolve $N (1-based). */
    public Object resolveParam(int number) throws SQLException {
        int idx = number - 1;
        if (idx < 0 || idx >= params.length)
            throw PgErrorException.error("P0001", "parameter $" + number + " is out of range").build();
        return params[idx];
    }

    // -------------------------------------------------------------------------
    // Accessors

    public OffsetDateTime statementTimestamp() { return stmtTimestamp; }
    public Object[]       params()             { return params; }
    public EvalContext    outer()              { return outer; }

    /** True if there are no rows in scope (useful for constant-fold checks). */
    public boolean isEmpty() { return rows.isEmpty(); }
}
