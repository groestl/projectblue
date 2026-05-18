package org.pgjava.jdbc;

import org.pgjava.engine.ColumnMeta;
import org.pgjava.engine.QueryResult;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/** In-memory ResultSet backed by a {@link QueryResult}. */
final class PgJavaResultSet implements ResultSet {

    private final List<ColumnMeta> columns;
    private final List<Object[]>   rows;
    private final PgJavaResultSetMetaData meta;
    private Statement owningStatement;

    private int     cursor    = -1;   // -1 = before first
    private boolean closed    = false;
    private boolean lastWasNull = false;

    PgJavaResultSet(QueryResult result) {
        this.columns = result.columns();
        this.rows    = result.rows();
        this.meta    = new PgJavaResultSetMetaData(columns);
    }

    void setStatement(Statement stmt) { this.owningStatement = stmt; }

    /** Convenience: empty ResultSet with specific column schema. */
    static PgJavaResultSet empty(List<ColumnMeta> cols) {
        return new PgJavaResultSet(QueryResult.emptyQuery(cols));
    }

    // -------------------------------------------------------------------------
    // Cursor movement

    @Override public boolean next()               throws SQLException { checkOpen(); cursor++; return cursor < rows.size(); }
    @Override public boolean isBeforeFirst()      throws SQLException { checkOpen(); return cursor < 0 && !rows.isEmpty(); }
    @Override public boolean isAfterLast()        throws SQLException { checkOpen(); return cursor >= rows.size() && !rows.isEmpty(); }
    @Override public boolean isFirst()            throws SQLException { checkOpen(); return cursor == 0 && !rows.isEmpty(); }
    @Override public boolean isLast()             throws SQLException { checkOpen(); return cursor == rows.size() - 1 && !rows.isEmpty(); }
    @Override public void    beforeFirst()        throws SQLException { checkOpen(); cursor = -1; }
    @Override public void    afterLast()          throws SQLException { checkOpen(); cursor = rows.size(); }
    @Override public boolean first()              throws SQLException { checkOpen(); if (rows.isEmpty()) return false; cursor = 0; return true; }
    @Override public boolean last()               throws SQLException { checkOpen(); if (rows.isEmpty()) return false; cursor = rows.size() - 1; return true; }
    @Override public int     getRow()             throws SQLException { checkOpen(); return (cursor >= 0 && cursor < rows.size()) ? cursor + 1 : 0; }
    @Override public boolean absolute(int row) throws SQLException {
        checkOpen();
        if (row > 0) {
            cursor = row - 1;
        } else if (row < 0) {
            cursor = rows.size() + row; // -1 = last, -2 = second-to-last
        } else {
            cursor = -1; // row 0 = before first
        }
        return cursor >= 0 && cursor < rows.size();
    }
    @Override public boolean relative(int rows2)  throws SQLException { checkOpen(); cursor += rows2; return cursor >= 0 && cursor < rows.size(); }
    @Override public boolean previous()           throws SQLException { checkOpen(); cursor--; return cursor >= 0; }

    // -------------------------------------------------------------------------
    // Column access helpers

    private void checkOpen() throws SQLException {
        if (closed) throw new SQLException("ResultSet is closed");
    }

    private Object getColumn(int columnIndex) throws SQLException {
        checkOpen();
        if (cursor < 0 || cursor >= rows.size())
            throw new SQLException("No current row");
        if (columnIndex < 1 || columnIndex > columns.size())
            throw new SQLException("Column index out of range: " + columnIndex);
        Object val = rows.get(cursor)[columnIndex - 1];
        lastWasNull = (val == null);
        return val;
    }

    public int findColumn(String name) throws SQLException {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).label().equalsIgnoreCase(name)) return i + 1;
        }
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(name)) return i + 1;
        }
        throw new SQLException("Column not found: " + name);
    }

    // -------------------------------------------------------------------------
    // Typed getters — by index

    @Override public String getString(int i) throws SQLException {
        Object v = getColumn(i);
        return v == null ? null : v.toString();
    }
    @Override public boolean getBoolean(int i) throws SQLException {
        Object v = getColumn(i);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }
    @Override public byte getByte(int i) throws SQLException {
        Object v = getColumn(i);
        if (v == null) return 0;
        if (v instanceof Number n) return n.byteValue();
        return Byte.parseByte(v.toString());
    }
    @Override public short getShort(int i) throws SQLException {
        Object v = getColumn(i);
        if (v == null) return 0;
        if (v instanceof Number n) return n.shortValue();
        return Short.parseShort(v.toString());
    }
    @Override public int getInt(int i) throws SQLException {
        Object v = getColumn(i);
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }
    @Override public long getLong(int i) throws SQLException {
        Object v = getColumn(i);
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }
    @Override public float getFloat(int i) throws SQLException {
        Object v = getColumn(i);
        if (v == null) return 0f;
        if (v instanceof Number n) return n.floatValue();
        return Float.parseFloat(v.toString());
    }
    @Override public double getDouble(int i) throws SQLException {
        Object v = getColumn(i);
        if (v == null) return 0d;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }
    @Override public BigDecimal getBigDecimal(int i) throws SQLException {
        Object v = getColumn(i);
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        return new BigDecimal(v.toString());
    }
    @Override public BigDecimal getBigDecimal(int i, int scale) throws SQLException {
        BigDecimal bd = getBigDecimal(i);
        return bd == null ? null : bd.setScale(scale, java.math.RoundingMode.HALF_UP);
    }
    @Override public byte[] getBytes(int i) throws SQLException {
        Object v = getColumn(i);
        if (v == null) return null;
        if (v instanceof byte[] b) return b;
        return v.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    @Override public Date getDate(int i) throws SQLException {
        Object v = getColumn(i);
        if (v == null) return null;
        if (v instanceof Date d) return d;
        return Date.valueOf(v.toString());
    }
    @Override public Time getTime(int i) throws SQLException {
        Object v = getColumn(i);
        if (v == null) return null;
        if (v instanceof Time t) return t;
        return Time.valueOf(v.toString());
    }
    @Override public Timestamp getTimestamp(int i) throws SQLException {
        Object v = getColumn(i);
        if (v == null) return null;
        if (v instanceof Timestamp ts) return ts;
        if (v instanceof java.time.LocalDateTime ldt) return Timestamp.valueOf(ldt);
        if (v instanceof java.time.OffsetDateTime odt)
            return Timestamp.from(odt.toInstant());
        if (v instanceof java.time.LocalDate ld) return Timestamp.valueOf(ld.atStartOfDay());
        // String fallback — normalize space-separated to Timestamp.valueOf format
        String s = v.toString().replace('T', ' ').replaceAll("Z$", "").replaceAll("[+\\-]\\d{2}:?\\d{2}$", "");
        return Timestamp.valueOf(s);
    }
    @Override public Object getObject(int i) throws SQLException { return getColumn(i); }

    // -------------------------------------------------------------------------
    // Typed getters — by name (delegate to index variants)

    @Override public String    getString(String col)    throws SQLException { return getString(findColumn(col)); }
    @Override public boolean   getBoolean(String col)   throws SQLException { return getBoolean(findColumn(col)); }
    @Override public byte      getByte(String col)      throws SQLException { return getByte(findColumn(col)); }
    @Override public short     getShort(String col)     throws SQLException { return getShort(findColumn(col)); }
    @Override public int       getInt(String col)       throws SQLException { return getInt(findColumn(col)); }
    @Override public long      getLong(String col)      throws SQLException { return getLong(findColumn(col)); }
    @Override public float     getFloat(String col)     throws SQLException { return getFloat(findColumn(col)); }
    @Override public double    getDouble(String col)    throws SQLException { return getDouble(findColumn(col)); }
    @Override public BigDecimal getBigDecimal(String col, int scale) throws SQLException { return getBigDecimal(findColumn(col), scale); }
    @Override public byte[]    getBytes(String col)     throws SQLException { return getBytes(findColumn(col)); }
    @Override public Date      getDate(String col)      throws SQLException { return getDate(findColumn(col)); }
    @Override public Time      getTime(String col)      throws SQLException { return getTime(findColumn(col)); }
    @Override public Timestamp getTimestamp(String col) throws SQLException { return getTimestamp(findColumn(col)); }
    @Override public Object    getObject(String col)    throws SQLException { return getObject(findColumn(col)); }
    @Override public BigDecimal getBigDecimal(String col) throws SQLException { return getBigDecimal(findColumn(col)); }

    // -------------------------------------------------------------------------
    // Misc required methods

    @Override public boolean wasNull() { return lastWasNull; }

    @Override public ResultSetMetaData getMetaData() { return meta; }

    @Override public int getType()        { return ResultSet.TYPE_FORWARD_ONLY; }
    @Override public int getConcurrency() { return ResultSet.CONCUR_READ_ONLY; }
    @Override public int getFetchDirection() { return ResultSet.FETCH_FORWARD; }
    @Override public void setFetchDirection(int d) throws SQLException {}
    @Override public int getFetchSize() { return 0; }
    @Override public void setFetchSize(int rows) throws SQLException {}

    @Override public Statement getStatement() { return owningStatement; }

    @Override public void close() { closed = true; }
    @Override public boolean isClosed() { return closed; }

    // -------------------------------------------------------------------------
    // Streaming / unsupported

    @Override public InputStream getAsciiStream(int i) throws SQLException {
        String s = getString(i);
        return s == null ? null : new java.io.ByteArrayInputStream(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    @Override public InputStream getUnicodeStream(int i) throws SQLException {
        String s = getString(i);
        return s == null ? null : new java.io.ByteArrayInputStream(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Override public InputStream getBinaryStream(int i) throws SQLException {
        Object v = getColumn(i);
        if (v == null) return null;
        if (v instanceof byte[] ba) return new java.io.ByteArrayInputStream(ba);
        return new java.io.ByteArrayInputStream(v.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Override public InputStream getAsciiStream(String c)   throws SQLException { return getAsciiStream(findColumn(c)); }
    @Override public InputStream getUnicodeStream(String c) throws SQLException { return getUnicodeStream(findColumn(c)); }
    @Override public InputStream getBinaryStream(String c)  throws SQLException { return getBinaryStream(findColumn(c)); }
    @Override public SQLWarning  getWarnings() { return null; }
    @Override public void        clearWarnings() {}
    @Override public String      getCursorName() throws SQLException { throw notSupported("getCursorName"); }
    @Override public Reader getCharacterStream(int i) throws SQLException {
        String s = getString(i);
        return s == null ? null : new java.io.StringReader(s);
    }
    @Override public Reader getCharacterStream(String c) throws SQLException { return getCharacterStream(findColumn(c)); }
    @Override public boolean rowUpdated()  throws SQLException { return false; }
    @Override public boolean rowInserted() throws SQLException { return false; }
    @Override public boolean rowDeleted()  throws SQLException { return false; }
    @Override public void insertRow()  throws SQLException { throw notSupported("insertRow"); }
    @Override public void updateRow()  throws SQLException { throw notSupported("updateRow"); }
    @Override public void deleteRow()  throws SQLException { throw notSupported("deleteRow"); }
    @Override public void refreshRow() throws SQLException { throw notSupported("refreshRow"); }
    @Override public void cancelRowUpdates() throws SQLException { throw notSupported("cancelRowUpdates"); }
    @Override public void moveToInsertRow() throws SQLException { throw notSupported("moveToInsertRow"); }
    @Override public void moveToCurrentRow() throws SQLException { throw notSupported("moveToCurrentRow"); }

    // Update-by-index stubs
    @Override public void updateNull(int i) throws SQLException { throw notSupported("updateNull"); }
    @Override public void updateBoolean(int i, boolean x) throws SQLException { throw notSupported("updateBoolean"); }
    @Override public void updateByte(int i, byte x) throws SQLException { throw notSupported("updateByte"); }
    @Override public void updateShort(int i, short x) throws SQLException { throw notSupported("updateShort"); }
    @Override public void updateInt(int i, int x) throws SQLException { throw notSupported("updateInt"); }
    @Override public void updateLong(int i, long x) throws SQLException { throw notSupported("updateLong"); }
    @Override public void updateFloat(int i, float x) throws SQLException { throw notSupported("updateFloat"); }
    @Override public void updateDouble(int i, double x) throws SQLException { throw notSupported("updateDouble"); }
    @Override public void updateBigDecimal(int i, BigDecimal x) throws SQLException { throw notSupported("updateBigDecimal"); }
    @Override public void updateString(int i, String x) throws SQLException { throw notSupported("updateString"); }
    @Override public void updateBytes(int i, byte[] x) throws SQLException { throw notSupported("updateBytes"); }
    @Override public void updateDate(int i, Date x) throws SQLException { throw notSupported("updateDate"); }
    @Override public void updateTime(int i, Time x) throws SQLException { throw notSupported("updateTime"); }
    @Override public void updateTimestamp(int i, Timestamp x) throws SQLException { throw notSupported("updateTimestamp"); }
    @Override public void updateAsciiStream(int i, InputStream x, int length) throws SQLException { throw notSupported("updateAsciiStream"); }
    @Override public void updateBinaryStream(int i, InputStream x, int length) throws SQLException { throw notSupported("updateBinaryStream"); }
    @Override public void updateCharacterStream(int i, Reader x, int length) throws SQLException { throw notSupported("updateCharacterStream"); }
    @Override public void updateObject(int i, Object x, int scale) throws SQLException { throw notSupported("updateObject"); }
    @Override public void updateObject(int i, Object x) throws SQLException { throw notSupported("updateObject"); }

    // Update-by-name stubs
    @Override public void updateNull(String c) throws SQLException { throw notSupported("updateNull"); }
    @Override public void updateBoolean(String c, boolean x) throws SQLException { throw notSupported("updateBoolean"); }
    @Override public void updateByte(String c, byte x) throws SQLException { throw notSupported("updateByte"); }
    @Override public void updateShort(String c, short x) throws SQLException { throw notSupported("updateShort"); }
    @Override public void updateInt(String c, int x) throws SQLException { throw notSupported("updateInt"); }
    @Override public void updateLong(String c, long x) throws SQLException { throw notSupported("updateLong"); }
    @Override public void updateFloat(String c, float x) throws SQLException { throw notSupported("updateFloat"); }
    @Override public void updateDouble(String c, double x) throws SQLException { throw notSupported("updateDouble"); }
    @Override public void updateBigDecimal(String c, BigDecimal x) throws SQLException { throw notSupported("updateBigDecimal"); }
    @Override public void updateString(String c, String x) throws SQLException { throw notSupported("updateString"); }
    @Override public void updateBytes(String c, byte[] x) throws SQLException { throw notSupported("updateBytes"); }
    @Override public void updateDate(String c, Date x) throws SQLException { throw notSupported("updateDate"); }
    @Override public void updateTime(String c, Time x) throws SQLException { throw notSupported("updateTime"); }
    @Override public void updateTimestamp(String c, Timestamp x) throws SQLException { throw notSupported("updateTimestamp"); }
    @Override public void updateAsciiStream(String c, InputStream x, int length) throws SQLException { throw notSupported("updateAsciiStream"); }
    @Override public void updateBinaryStream(String c, InputStream x, int length) throws SQLException { throw notSupported("updateBinaryStream"); }
    @Override public void updateCharacterStream(String c, Reader x, int length) throws SQLException { throw notSupported("updateCharacterStream"); }
    @Override public void updateObject(String c, Object x, int scale) throws SQLException { throw notSupported("updateObject"); }
    @Override public void updateObject(String c, Object x) throws SQLException { throw notSupported("updateObject"); }

    // JDBC 3.0+
    @Override public URL  getURL(int i) throws SQLException { throw notSupported("getURL"); }
    @Override public URL  getURL(String c) throws SQLException { throw notSupported("getURL"); }
    @Override public void updateRef(int i, Ref x) throws SQLException { throw notSupported("updateRef"); }
    @Override public void updateRef(String c, Ref x) throws SQLException { throw notSupported("updateRef"); }
    @Override public void updateBlob(int i, Blob x) throws SQLException { throw notSupported("updateBlob"); }
    @Override public void updateBlob(String c, Blob x) throws SQLException { throw notSupported("updateBlob"); }
    @Override public void updateClob(int i, Clob x) throws SQLException { throw notSupported("updateClob"); }
    @Override public void updateClob(String c, Clob x) throws SQLException { throw notSupported("updateClob"); }
    @Override public void updateArray(int i, Array x) throws SQLException { throw notSupported("updateArray"); }
    @Override public void updateArray(String c, Array x) throws SQLException { throw notSupported("updateArray"); }
    @Override public Ref   getRef(int i) throws SQLException { throw notSupported("getRef"); }
    @Override public Blob  getBlob(int i) throws SQLException { throw notSupported("getBlob"); }
    @Override public Clob  getClob(int i) throws SQLException { throw notSupported("getClob"); }
    @Override public Array getArray(int i) throws SQLException {
        Object v = getColumn(i);
        if (v == null) return null;
        if (v instanceof PgJavaArray pa) return pa;
        if (v instanceof java.util.List<?> list) {
            String typeName = columns.get(i - 1).typeName();
            // Strip leading underscore for array type names (e.g. _int4 -> int4)
            if (typeName.startsWith("_")) typeName = typeName.substring(1);
            return PgJavaArray.fromList(list, typeName, PgJavaArray.sqlTypeForName(typeName));
        }
        throw new SQLException("Column " + i + " is not an array");
    }
    @Override public Ref   getRef(String c) throws SQLException { throw notSupported("getRef"); }
    @Override public Blob  getBlob(String c) throws SQLException { throw notSupported("getBlob"); }
    @Override public Clob  getClob(String c) throws SQLException { throw notSupported("getClob"); }
    @Override public Array getArray(String c) throws SQLException { return getArray(findColumn(c)); }
    @Override public Date getDate(int i, Calendar cal) throws SQLException {
        if (cal == null) return getDate(i);
        Timestamp ts = getTimestamp(i, cal);
        return ts == null ? null : new Date(ts.getTime());
    }
    @Override public Date  getDate(String c, Calendar cal) throws SQLException { return getDate(findColumn(c), cal); }
    @Override public Time getTime(int i, Calendar cal) throws SQLException {
        if (cal == null) return getTime(i);
        Timestamp ts = getTimestamp(i, cal);
        return ts == null ? null : new Time(ts.getTime());
    }
    @Override public Time  getTime(String c, Calendar cal) throws SQLException { return getTime(findColumn(c), cal); }
    @Override public Timestamp getTimestamp(int i, Calendar cal) throws SQLException {
        if (cal == null) return getTimestamp(i);
        Object v = getColumn(i);
        if (v == null) return null;
        // Get the raw timestamp, then adjust for the Calendar's timezone
        Timestamp raw = getTimestamp(i);
        if (raw == null) return null;
        cal.setTimeInMillis(raw.getTime());
        return new Timestamp(cal.getTimeInMillis());
    }
    @Override public Timestamp getTimestamp(String c, Calendar cal) throws SQLException { return getTimestamp(findColumn(c), cal); }

    // JDBC 4.0+
    @Override public RowId  getRowId(int i) throws SQLException { throw notSupported("getRowId"); }
    @Override public RowId  getRowId(String c) throws SQLException { throw notSupported("getRowId"); }
    @Override public void   updateRowId(int i, RowId x) throws SQLException { throw notSupported("updateRowId"); }
    @Override public void   updateRowId(String c, RowId x) throws SQLException { throw notSupported("updateRowId"); }
    @Override public int    getHoldability() { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public NClob  getNClob(int i) throws SQLException { throw notSupported("getNClob"); }
    @Override public NClob  getNClob(String c) throws SQLException { throw notSupported("getNClob"); }
    @Override public SQLXML getSQLXML(int i) throws SQLException { throw notSupported("getSQLXML"); }
    @Override public SQLXML getSQLXML(String c) throws SQLException { throw notSupported("getSQLXML"); }
    @Override public void   updateSQLXML(int i, SQLXML x) throws SQLException { throw notSupported("updateSQLXML"); }
    @Override public void   updateSQLXML(String c, SQLXML x) throws SQLException { throw notSupported("updateSQLXML"); }
    @Override public String getNString(int i) throws SQLException { return getString(i); }
    @Override public String getNString(String c) throws SQLException { return getString(c); }
    @Override public Reader getNCharacterStream(int i) throws SQLException { return getCharacterStream(i); }
    @Override public Reader getNCharacterStream(String c) throws SQLException { return getCharacterStream(c); }
    @Override public void   updateNString(int i, String x) throws SQLException { throw notSupported("updateNString"); }
    @Override public void   updateNString(String c, String x) throws SQLException { throw notSupported("updateNString"); }
    @Override public void   updateNClob(int i, NClob x) throws SQLException { throw notSupported("updateNClob"); }
    @Override public void   updateNClob(String c, NClob x) throws SQLException { throw notSupported("updateNClob"); }
    @Override public void   updateNCharacterStream(int i, Reader x, long length) throws SQLException { throw notSupported("updateNCharacterStream"); }
    @Override public void   updateNCharacterStream(String c, Reader x, long length) throws SQLException { throw notSupported("updateNCharacterStream"); }
    @Override public void   updateAsciiStream(int i, InputStream x, long length) throws SQLException { throw notSupported("updateAsciiStream"); }
    @Override public void   updateBinaryStream(int i, InputStream x, long length) throws SQLException { throw notSupported("updateBinaryStream"); }
    @Override public void   updateCharacterStream(int i, Reader x, long length) throws SQLException { throw notSupported("updateCharacterStream"); }
    @Override public void   updateAsciiStream(String c, InputStream x, long length) throws SQLException { throw notSupported("updateAsciiStream"); }
    @Override public void   updateBinaryStream(String c, InputStream x, long length) throws SQLException { throw notSupported("updateBinaryStream"); }
    @Override public void   updateCharacterStream(String c, Reader x, long length) throws SQLException { throw notSupported("updateCharacterStream"); }
    @Override public void   updateBlob(int i, InputStream x, long length) throws SQLException { throw notSupported("updateBlob"); }
    @Override public void   updateBlob(String c, InputStream x, long length) throws SQLException { throw notSupported("updateBlob"); }
    @Override public void   updateClob(int i, Reader x, long length) throws SQLException { throw notSupported("updateClob"); }
    @Override public void   updateClob(String c, Reader x, long length) throws SQLException { throw notSupported("updateClob"); }
    @Override public void   updateNClob(int i, Reader x, long length) throws SQLException { throw notSupported("updateNClob"); }
    @Override public void   updateNClob(String c, Reader x, long length) throws SQLException { throw notSupported("updateNClob"); }
    @Override public void   updateNClob(int i, Reader x)                 throws SQLException { throw notSupported("updateNClob"); }
    @Override public void   updateNClob(String c, Reader x)              throws SQLException { throw notSupported("updateNClob"); }
    @Override public void   updateClob(int i, Reader x)                  throws SQLException { throw notSupported("updateClob"); }
    @Override public void   updateClob(String c, Reader x)               throws SQLException { throw notSupported("updateClob"); }
    @Override public void   updateBlob(int i, InputStream x)             throws SQLException { throw notSupported("updateBlob"); }
    @Override public void   updateBlob(String c, InputStream x)          throws SQLException { throw notSupported("updateBlob"); }
    @Override public void   updateAsciiStream(int i, InputStream x)      throws SQLException { throw notSupported("updateAsciiStream"); }
    @Override public void   updateAsciiStream(String c, InputStream x)   throws SQLException { throw notSupported("updateAsciiStream"); }
    @Override public void   updateBinaryStream(int i, InputStream x)     throws SQLException { throw notSupported("updateBinaryStream"); }
    @Override public void   updateBinaryStream(String c, InputStream x)  throws SQLException { throw notSupported("updateBinaryStream"); }
    @Override public void   updateCharacterStream(int i, Reader x)       throws SQLException { throw notSupported("updateCharacterStream"); }
    @Override public void   updateCharacterStream(String c, Reader x)    throws SQLException { throw notSupported("updateCharacterStream"); }
    @Override public void   updateNCharacterStream(int i, Reader x)      throws SQLException { throw notSupported("updateNCharacterStream"); }
    @Override public void   updateNCharacterStream(String c, Reader x)   throws SQLException { throw notSupported("updateNCharacterStream"); }
    @Override public <T> T  getObject(int i, Class<T> type) throws SQLException {
        Object v = getColumn(i);
        if (v == null) return null;
        if (type.isInstance(v)) return type.cast(v);
        // Type conversions
        try {
            Object converted = convertTo(v, type);
            if (converted != null && type.isInstance(converted)) return type.cast(converted);
        } catch (Exception e) {
            throw new SQLException("Cannot convert " + v.getClass().getSimpleName()
                    + " to " + type.getName() + ": " + e.getMessage(), e);
        }
        throw new SQLException("Cannot convert " + v.getClass().getSimpleName() + " to " + type.getName());
    }

    private static Object convertTo(Object v, Class<?> type) {
        if (type == String.class) return v.toString();
        if (type == Integer.class || type == int.class) {
            if (v instanceof Number n) return n.intValue();
            return Integer.parseInt(v.toString());
        }
        if (type == Long.class || type == long.class) {
            if (v instanceof Number n) return n.longValue();
            return Long.parseLong(v.toString());
        }
        if (type == Double.class || type == double.class) {
            if (v instanceof Number n) return n.doubleValue();
            return Double.parseDouble(v.toString());
        }
        if (type == Float.class || type == float.class) {
            if (v instanceof Number n) return n.floatValue();
            return Float.parseFloat(v.toString());
        }
        if (type == Short.class || type == short.class) {
            if (v instanceof Number n) return n.shortValue();
            return Short.parseShort(v.toString());
        }
        if (type == Boolean.class || type == boolean.class) {
            if (v instanceof Boolean b) return b;
            return Boolean.parseBoolean(v.toString());
        }
        if (type == BigDecimal.class) {
            if (v instanceof BigDecimal bd) return bd;
            return new BigDecimal(v.toString());
        }
        if (type == java.time.LocalDateTime.class) {
            if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
            if (v instanceof java.time.OffsetDateTime odt) return odt.toLocalDateTime();
        }
        if (type == java.time.LocalDate.class) {
            if (v instanceof java.sql.Date d) return d.toLocalDate();
            if (v instanceof java.time.LocalDate ld) return ld;
        }
        if (type == java.time.OffsetDateTime.class) {
            if (v instanceof java.time.OffsetDateTime odt) return odt;
            if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().atOffset(java.time.ZoneOffset.UTC);
        }
        return null;
    }
    @Override public <T> T  getObject(String c, Class<T> type) throws SQLException { return getObject(findColumn(c), type); }
    @Override public Object getObject(int i, Map<String, Class<?>> map) throws SQLException { return getObject(i); }
    @Override public Object getObject(String c, Map<String, Class<?>> map) throws SQLException { return getObject(c); }

    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface);
    }
    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }

    private static SQLException notSupported(String method) {
        return new SQLFeatureNotSupportedException(method + " not supported");
    }
}
