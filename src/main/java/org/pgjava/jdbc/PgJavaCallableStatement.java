package org.pgjava.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.Calendar;
import java.util.Map;

/**
 * Minimal {@link CallableStatement} implementation — delegates all {@link PreparedStatement}
 * behaviour to {@link PgJavaPreparedStatement}.  Callable-specific methods (OUT parameters,
 * stored-procedure registers, etc.) throw {@link SQLFeatureNotSupportedException}.
 *
 * <p>This class exists because some JDBC clients (e.g. Liquibase) use {@code prepareCall()}
 * for simple SELECT queries.  pgjava treats every callable statement as a plain prepared
 * statement; stored-procedure semantics are not supported.
 */
final class PgJavaCallableStatement extends PgJavaPreparedStatement implements CallableStatement {

    PgJavaCallableStatement(PgJavaConnection conn, String sql) {
        super(conn, sql);
    }

    // -------------------------------------------------------------------------
    // All CallableStatement methods — not supported; throw consistently.

    private static SQLFeatureNotSupportedException noCall() {
        return new SQLFeatureNotSupportedException("CallableStatement OUT parameters not supported");
    }

    @Override public void registerOutParameter(int i, int sqlType) throws SQLException { throw noCall(); }
    @Override public void registerOutParameter(int i, int sqlType, int scale) throws SQLException { throw noCall(); }
    @Override public boolean wasNull() throws SQLException { throw noCall(); }
    @Override public String getString(int i) throws SQLException { throw noCall(); }
    @Override public boolean getBoolean(int i) throws SQLException { throw noCall(); }
    @Override public byte getByte(int i) throws SQLException { throw noCall(); }
    @Override public short getShort(int i) throws SQLException { throw noCall(); }
    @Override public int getInt(int i) throws SQLException { throw noCall(); }
    @Override public long getLong(int i) throws SQLException { throw noCall(); }
    @Override public float getFloat(int i) throws SQLException { throw noCall(); }
    @Override public double getDouble(int i) throws SQLException { throw noCall(); }
    @Override public BigDecimal getBigDecimal(int i, int scale) throws SQLException { throw noCall(); }
    @Override public byte[] getBytes(int i) throws SQLException { throw noCall(); }
    @Override public Date getDate(int i) throws SQLException { throw noCall(); }
    @Override public Time getTime(int i) throws SQLException { throw noCall(); }
    @Override public Timestamp getTimestamp(int i) throws SQLException { throw noCall(); }
    @Override public Object getObject(int i) throws SQLException { throw noCall(); }
    @Override public BigDecimal getBigDecimal(int i) throws SQLException { throw noCall(); }
    @Override public Object getObject(int i, Map<String, Class<?>> map) throws SQLException { throw noCall(); }
    @Override public Ref getRef(int i) throws SQLException { throw noCall(); }
    @Override public Blob getBlob(int i) throws SQLException { throw noCall(); }
    @Override public Clob getClob(int i) throws SQLException { throw noCall(); }
    @Override public Array getArray(int i) throws SQLException { throw noCall(); }
    @Override public Date getDate(int i, Calendar cal) throws SQLException { throw noCall(); }
    @Override public Time getTime(int i, Calendar cal) throws SQLException { throw noCall(); }
    @Override public Timestamp getTimestamp(int i, Calendar cal) throws SQLException { throw noCall(); }
    @Override public void registerOutParameter(int i, int sqlType, String typeName) throws SQLException { throw noCall(); }
    @Override public void registerOutParameter(String n, int sqlType) throws SQLException { throw noCall(); }
    @Override public void registerOutParameter(String n, int sqlType, int scale) throws SQLException { throw noCall(); }
    @Override public void registerOutParameter(String n, int sqlType, String typeName) throws SQLException { throw noCall(); }
    @Override public URL getURL(int i) throws SQLException { throw noCall(); }
    @Override public void setURL(String n, URL val) throws SQLException { throw noCall(); }
    @Override public void setNull(String n, int sqlType) throws SQLException { throw noCall(); }
    @Override public void setBoolean(String n, boolean x) throws SQLException { throw noCall(); }
    @Override public void setByte(String n, byte x) throws SQLException { throw noCall(); }
    @Override public void setShort(String n, short x) throws SQLException { throw noCall(); }
    @Override public void setInt(String n, int x) throws SQLException { throw noCall(); }
    @Override public void setLong(String n, long x) throws SQLException { throw noCall(); }
    @Override public void setFloat(String n, float x) throws SQLException { throw noCall(); }
    @Override public void setDouble(String n, double x) throws SQLException { throw noCall(); }
    @Override public void setBigDecimal(String n, BigDecimal x) throws SQLException { throw noCall(); }
    @Override public void setString(String n, String x) throws SQLException { throw noCall(); }
    @Override public void setBytes(String n, byte[] x) throws SQLException { throw noCall(); }
    @Override public void setDate(String n, Date x) throws SQLException { throw noCall(); }
    @Override public void setTime(String n, Time x) throws SQLException { throw noCall(); }
    @Override public void setTimestamp(String n, Timestamp x) throws SQLException { throw noCall(); }
    @Override public void setAsciiStream(String n, InputStream x, int length) throws SQLException { throw noCall(); }
    @Override public void setBinaryStream(String n, InputStream x, int length) throws SQLException { throw noCall(); }
    @Override public void setObject(String n, Object x, int targetSqlType, int scale) throws SQLException { throw noCall(); }
    @Override public void setObject(String n, Object x, int targetSqlType) throws SQLException { throw noCall(); }
    @Override public void setObject(String n, Object x) throws SQLException { throw noCall(); }
    @Override public void setCharacterStream(String n, Reader reader, int length) throws SQLException { throw noCall(); }
    @Override public void setDate(String n, Date x, Calendar cal) throws SQLException { throw noCall(); }
    @Override public void setTime(String n, Time x, Calendar cal) throws SQLException { throw noCall(); }
    @Override public void setTimestamp(String n, Timestamp x, Calendar cal) throws SQLException { throw noCall(); }
    @Override public void setNull(String n, int sqlType, String typeName) throws SQLException { throw noCall(); }
    @Override public String getString(String n) throws SQLException { throw noCall(); }
    @Override public boolean getBoolean(String n) throws SQLException { throw noCall(); }
    @Override public byte getByte(String n) throws SQLException { throw noCall(); }
    @Override public short getShort(String n) throws SQLException { throw noCall(); }
    @Override public int getInt(String n) throws SQLException { throw noCall(); }
    @Override public long getLong(String n) throws SQLException { throw noCall(); }
    @Override public float getFloat(String n) throws SQLException { throw noCall(); }
    @Override public double getDouble(String n) throws SQLException { throw noCall(); }
    @Override public byte[] getBytes(String n) throws SQLException { throw noCall(); }
    @Override public Date getDate(String n) throws SQLException { throw noCall(); }
    @Override public Time getTime(String n) throws SQLException { throw noCall(); }
    @Override public Timestamp getTimestamp(String n) throws SQLException { throw noCall(); }
    @Override public Object getObject(String n) throws SQLException { throw noCall(); }
    @Override public BigDecimal getBigDecimal(String n) throws SQLException { throw noCall(); }
    @Override public Object getObject(String n, Map<String, Class<?>> map) throws SQLException { throw noCall(); }
    @Override public Ref getRef(String n) throws SQLException { throw noCall(); }
    @Override public Blob getBlob(String n) throws SQLException { throw noCall(); }
    @Override public Clob getClob(String n) throws SQLException { throw noCall(); }
    @Override public Array getArray(String n) throws SQLException { throw noCall(); }
    @Override public Date getDate(String n, Calendar cal) throws SQLException { throw noCall(); }
    @Override public Time getTime(String n, Calendar cal) throws SQLException { throw noCall(); }
    @Override public Timestamp getTimestamp(String n, Calendar cal) throws SQLException { throw noCall(); }
    @Override public URL getURL(String n) throws SQLException { throw noCall(); }
    @Override public RowId getRowId(int i) throws SQLException { throw noCall(); }
    @Override public RowId getRowId(String n) throws SQLException { throw noCall(); }
    @Override public void setRowId(String n, RowId x) throws SQLException { throw noCall(); }
    @Override public void setNString(String n, String value) throws SQLException { throw noCall(); }
    @Override public void setNCharacterStream(String n, Reader value, long length) throws SQLException { throw noCall(); }
    @Override public void setNClob(String n, NClob value) throws SQLException { throw noCall(); }
    @Override public void setClob(String n, Reader reader, long length) throws SQLException { throw noCall(); }
    @Override public void setBlob(String n, InputStream inputStream, long length) throws SQLException { throw noCall(); }
    @Override public void setNClob(String n, Reader reader, long length) throws SQLException { throw noCall(); }
    @Override public NClob getNClob(int i) throws SQLException { throw noCall(); }
    @Override public NClob getNClob(String n) throws SQLException { throw noCall(); }
    @Override public void setSQLXML(String n, SQLXML xmlObject) throws SQLException { throw noCall(); }
    @Override public SQLXML getSQLXML(int i) throws SQLException { throw noCall(); }
    @Override public SQLXML getSQLXML(String n) throws SQLException { throw noCall(); }
    @Override public String getNString(int i) throws SQLException { throw noCall(); }
    @Override public String getNString(String n) throws SQLException { throw noCall(); }
    @Override public Reader getNCharacterStream(int i) throws SQLException { throw noCall(); }
    @Override public Reader getNCharacterStream(String n) throws SQLException { throw noCall(); }
    @Override public Reader getCharacterStream(int i) throws SQLException { throw noCall(); }
    @Override public Reader getCharacterStream(String n) throws SQLException { throw noCall(); }
    @Override public void setBlob(String n, Blob x) throws SQLException { throw noCall(); }
    @Override public void setClob(String n, Clob x) throws SQLException { throw noCall(); }
    @Override public void setAsciiStream(String n, InputStream x, long length) throws SQLException { throw noCall(); }
    @Override public void setBinaryStream(String n, InputStream x, long length) throws SQLException { throw noCall(); }
    @Override public void setCharacterStream(String n, Reader reader, long length) throws SQLException { throw noCall(); }
    @Override public void setAsciiStream(String n, InputStream x) throws SQLException { throw noCall(); }
    @Override public void setBinaryStream(String n, InputStream x) throws SQLException { throw noCall(); }
    @Override public void setCharacterStream(String n, Reader reader) throws SQLException { throw noCall(); }
    @Override public void setNCharacterStream(String n, Reader value) throws SQLException { throw noCall(); }
    @Override public void setNClob(String n, Reader reader) throws SQLException { throw noCall(); }
    @Override public void setBlob(String n, InputStream x) throws SQLException { throw noCall(); }
    @Override public void setClob(String n, Reader reader) throws SQLException { throw noCall(); }
    @Override public <T> T getObject(int i, Class<T> type) throws SQLException { throw noCall(); }
    @Override public <T> T getObject(String n, Class<T> type) throws SQLException { throw noCall(); }
}
