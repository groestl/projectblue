package org.pgjava.engine;

import java.util.List;

/**
 * Result of executing a single SQL statement.
 *
 * <ul>
 *   <li>Query result (SELECT): {@code updateCount < 0}, {@code columns} and {@code rows} populated.
 *   <li>DML result (INSERT/UPDATE/DELETE): {@code updateCount >= 0}, rows affected.
 *   <li>DDL / utility: {@code updateCount == 0}, empty columns/rows.
 * </ul>
 */
public record QueryResult(
        int updateCount,
        List<ColumnMeta> columns,
        List<Object[]> rows
) {
    /** Empty DML result (UPDATE 0, DDL, utility). */
    public static final QueryResult EMPTY_DML = new QueryResult(0, List.of(), List.of());

    /** Single-column, single-row query result — used for SHOW. */
    public static QueryResult singleValue(ColumnMeta col, Object value) {
        List<Object[]> rowList = new java.util.ArrayList<>();
        rowList.add(new Object[]{value});
        return new QueryResult(-1, List.of(col), rowList);
    }

    /** Empty SELECT result with given columns — used for stub catalog methods. */
    public static QueryResult emptyQuery(List<ColumnMeta> columns) {
        return new QueryResult(-1, columns, List.of());
    }

    public boolean isQuery() {
        return updateCount < 0;
    }
}
