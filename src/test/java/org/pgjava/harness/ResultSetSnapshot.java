package org.pgjava.harness;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Immutable capture of a {@link ResultSet}. Consumed eagerly so the ResultSet can be closed.
 */
public final class ResultSetSnapshot {

    public record Column(String name, int jdbcType, String typeName) {}

    private final List<Column> columns;
    private final List<Object[]> rows;
    /** True when the query contained ORDER BY — sequence comparison will be used. */
    private final boolean ordered;

    private ResultSetSnapshot(List<Column> columns, List<Object[]> rows, boolean ordered) {
        this.columns = Collections.unmodifiableList(columns);
        this.rows = Collections.unmodifiableList(rows);
        this.ordered = ordered;
    }

    /**
     * Capture the full contents of {@code rs}, excluding columns whose names appear in
     * {@code excludedColumns} (case-insensitive).
     *
     * @param ordered true if the query contained ORDER BY
     */
    public static ResultSetSnapshot capture(ResultSet rs, boolean ordered,
                                             Set<String> excludedColumns) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        List<Column> cols = new ArrayList<>(colCount);
        List<Integer> includedIdx = new ArrayList<>(colCount);

        for (int i = 1; i <= colCount; i++) {
            String name = meta.getColumnLabel(i);
            if (!excludedColumns.contains(name.toLowerCase())) {
                cols.add(new Column(name, meta.getColumnType(i), meta.getColumnTypeName(i)));
                includedIdx.add(i);
            }
        }

        List<Object[]> rows = new ArrayList<>();
        while (rs.next()) {
            Object[] row = new Object[includedIdx.size()];
            for (int j = 0; j < includedIdx.size(); j++) {
                row[j] = rs.getObject(includedIdx.get(j));
            }
            rows.add(row);
        }

        return new ResultSetSnapshot(cols, rows, ordered);
    }

    public List<Column> columns() { return columns; }
    public List<Object[]> rows()  { return rows; }
    public boolean isOrdered()    { return ordered; }
    public int rowCount()         { return rows.size(); }
}
