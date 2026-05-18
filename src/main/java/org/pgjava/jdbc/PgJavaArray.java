package org.pgjava.jdbc;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.pgjava.engine.ColumnMeta;
import org.pgjava.engine.QueryResult;

/**
 * Minimal {@link java.sql.Array} implementation backed by a Java object array.
 * Matches PG JDBC's observable behaviour for the common case: create via
 * {@link Connection#createArrayOf}, pass to {@link PreparedStatement#setArray},
 * read back via {@link ResultSet#getArray}.
 */
final class PgJavaArray implements Array {

    private final String baseTypeName;
    private final int baseType;
    private final Object[] elements;
    private boolean freed = false;

    PgJavaArray(String baseTypeName, int baseType, Object[] elements) {
        this.baseTypeName = baseTypeName;
        this.baseType = baseType;
        this.elements = elements;
    }

    /** Construct from an internal {@code List<Object>} (how pgjava stores arrays). */
    static PgJavaArray fromList(List<?> list, String typeName, int sqlType) {
        return new PgJavaArray(typeName, sqlType, list.toArray());
    }

    @Override
    public String getBaseTypeName() throws SQLException {
        checkFreed();
        return baseTypeName;
    }

    @Override
    public int getBaseType() throws SQLException {
        checkFreed();
        return baseType;
    }

    @Override
    public Object getArray() throws SQLException {
        checkFreed();
        return elements.clone();
    }

    @Override
    public Object getArray(Map<String, Class<?>> map) throws SQLException {
        return getArray();
    }

    @Override
    public Object getArray(long index, int count) throws SQLException {
        checkFreed();
        int start = (int) index - 1; // JDBC arrays are 1-based
        return Arrays.copyOfRange(elements, start, start + count);
    }

    @Override
    public Object getArray(long index, int count, Map<String, Class<?>> map) throws SQLException {
        return getArray(index, count);
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        checkFreed();
        return createResultSet(1, elements.length);
    }

    @Override
    public ResultSet getResultSet(Map<String, Class<?>> map) throws SQLException {
        return getResultSet();
    }

    @Override
    public ResultSet getResultSet(long index, int count) throws SQLException {
        checkFreed();
        int start = (int) index;
        int end = Math.min(start + count - 1, elements.length);
        if (start < 1 || start > elements.length || count < 0) {
            throw new SQLException("Invalid index or count");
        }
        return createResultSet(start, end);
    }

    @Override
    public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map) throws SQLException {
        return getResultSet(index, count);
    }

    /**
     * Helper to create a ResultSet with INDEX and VALUE columns.
     * @param startIndex 1-based index (inclusive)
     * @param endIndex 1-based index (inclusive)
     */
    private ResultSet createResultSet(int startIndex, int endIndex) {
        List<ColumnMeta> cols = List.of(
                ColumnMeta.integer("INDEX"),
                ColumnMeta.varchar("VALUE")
        );
        List<Object[]> rows = new ArrayList<>();

        for (int i = startIndex; i <= endIndex; i++) {
            int arrayIdx = i - 1; // Convert to 0-based
            Object value = arrayIdx < elements.length ? elements[arrayIdx] : null;
            rows.add(new Object[]{i, value == null ? null : value.toString()});
        }

        return new PgJavaResultSet(new QueryResult(-1, cols, rows));
    }

    @Override
    public void free() {
        freed = true;
    }

    /** Return the backing elements as a {@link List} for internal pgjava storage. */
    List<Object> toList() {
        return Arrays.asList(elements);
    }

    @Override
    public String toString() {
        // PG format: {1,2,3} with proper quoting for string elements
        var sb = new StringBuilder("{");
        for (int i = 0; i < elements.length; i++) {
            if (i > 0) sb.append(',');
            if (elements[i] == null) {
                sb.append("NULL");
            } else {
                String s = elements[i].toString();
                if (needsQuoting(s)) {
                    sb.append('"');
                    sb.append(s.replace("\\", "\\\\").replace("\"", "\\\""));
                    sb.append('"');
                } else {
                    sb.append(s);
                }
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static boolean needsQuoting(String s) {
        if (s.isEmpty() || s.equalsIgnoreCase("NULL")) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ',' || c == '{' || c == '}' || c == '"' || c == '\\' || c == ' ') return true;
        }
        return false;
    }

    private void checkFreed() throws SQLException {
        if (freed) throw new SQLException("Array has been freed");
    }

    // -------------------------------------------------------------------------
    // SQL type mapping for createArrayOf
    // -------------------------------------------------------------------------

    static int sqlTypeForName(String typeName) {
        return switch (typeName.toLowerCase()) {
            case "int2", "smallint" -> Types.SMALLINT;
            case "int4", "integer", "int" -> Types.INTEGER;
            case "int8", "bigint" -> Types.BIGINT;
            case "float4", "real" -> Types.REAL;
            case "float8", "double precision" -> Types.DOUBLE;
            case "numeric", "decimal" -> Types.NUMERIC;
            case "bool", "boolean" -> Types.BOOLEAN;
            case "text", "name" -> Types.VARCHAR;
            case "varchar", "character varying" -> Types.VARCHAR;
            case "char", "character", "bpchar" -> Types.CHAR;
            case "bytea" -> Types.BINARY;
            case "date" -> Types.DATE;
            case "time", "time without time zone" -> Types.TIME;
            case "timetz", "time with time zone" -> Types.TIME_WITH_TIMEZONE;
            case "timestamp", "timestamp without time zone" -> Types.TIMESTAMP;
            case "timestamptz", "timestamp with time zone" -> Types.TIMESTAMP_WITH_TIMEZONE;
            case "uuid" -> Types.OTHER;
            case "json", "jsonb" -> Types.OTHER;
            default -> Types.OTHER;
        };
    }
}
