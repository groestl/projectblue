package org.pgjava.jdbc;

import org.pgjava.engine.ColumnMeta;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

/** ResultSetMetaData backed by a list of {@link ColumnMeta}. */
final class PgJavaResultSetMetaData implements ResultSetMetaData {

    private final List<ColumnMeta> columns;

    PgJavaResultSetMetaData(List<ColumnMeta> columns) {
        this.columns = List.copyOf(columns);
    }

    // -------------------------------------------------------------------------

    @Override public int getColumnCount() { return columns.size(); }

    private ColumnMeta col(int i) throws SQLException {
        if (i < 1 || i > columns.size())
            throw new SQLException("Column index out of range: " + i);
        return columns.get(i - 1);
    }

    @Override public String getColumnLabel(int i)     throws SQLException { return col(i).label(); }
    @Override public String getColumnName(int i)      throws SQLException { return col(i).name(); }
    @Override public String getTableName(int i)       throws SQLException { return col(i).tableName(); }
    @Override public String getSchemaName(int i)      throws SQLException { return col(i).schemaName(); }
    @Override public String getCatalogName(int i)     throws SQLException { return ""; }
    @Override public int    getColumnType(int i)      throws SQLException { return col(i).sqlType(); }
    @Override public String getColumnTypeName(int i)  throws SQLException { return col(i).typeName(); }
    @Override public String getColumnClassName(int i) throws SQLException {
        return switch (col(i).sqlType()) {
            case java.sql.Types.INTEGER,
                 java.sql.Types.SMALLINT,
                 java.sql.Types.TINYINT  -> Integer.class.getName();
            case java.sql.Types.BIGINT   -> Long.class.getName();
            case java.sql.Types.FLOAT,
                 java.sql.Types.DOUBLE   -> Double.class.getName();
            case java.sql.Types.BOOLEAN  -> Boolean.class.getName();
            default                      -> String.class.getName();
        };
    }
    @Override public int     getPrecision(int i)      throws SQLException { return col(i).precision(); }
    @Override public int     getScale(int i)          throws SQLException { return col(i).scale(); }
    @Override public int     isNullable(int i)        throws SQLException { return col(i).nullable(); }
    @Override public boolean isAutoIncrement(int i)   throws SQLException { return false; }
    @Override public boolean isCaseSensitive(int i)   throws SQLException { return true; }
    @Override public boolean isSearchable(int i)      throws SQLException { return true; }
    @Override public boolean isCurrency(int i)        throws SQLException { return false; }
    @Override public boolean isSigned(int i)          throws SQLException {
        return switch (col(i).sqlType()) {
            case java.sql.Types.INTEGER, java.sql.Types.SMALLINT, java.sql.Types.TINYINT,
                 java.sql.Types.BIGINT, java.sql.Types.FLOAT, java.sql.Types.DOUBLE,
                 java.sql.Types.REAL, java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> true;
            default -> false;
        };
    }
    @Override public int     getColumnDisplaySize(int i) throws SQLException { return 256; }
    @Override public boolean isReadOnly(int i)        throws SQLException { return true; }
    @Override public boolean isWritable(int i)        throws SQLException { return false; }
    @Override public boolean isDefinitelyWritable(int i) throws SQLException { return false; }

    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface);
    }
    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}
