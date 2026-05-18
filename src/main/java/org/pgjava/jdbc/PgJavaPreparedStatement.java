package org.pgjava.jdbc;

import org.pgjava.engine.QueryResult;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.time.*;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * PreparedStatement for pgjava.
 *
 * <p>Phase 2: parameter substitution is done by simple string replacement of {@code ?}
 * placeholders with properly-escaped SQL literals.  Phase 9 will replace this with
 * a typed, injection-safe bind mechanism once the execution engine is in place.
 *
 * <p>Parameter indexes are 1-based.
 */
class PgJavaPreparedStatement extends PgJavaStatement implements PreparedStatement {

    private final String template;
    private final Object[] params;

    PgJavaPreparedStatement(PgJavaConnection conn, String sql) {
        super(conn);
        this.template = sql;
        int count = 0;
        for (char c : sql.toCharArray()) if (c == '?') count++;
        this.params = new Object[count];
    }

    // -------------------------------------------------------------------------
    // Execution

    @Override
    public ResultSet executeQuery() throws SQLException {
        return executeQuery(buildSql());
    }

    @Override
    public int executeUpdate() throws SQLException {
        return executeUpdate(buildSql());
    }

    @Override
    public boolean execute() throws SQLException {
        return execute(buildSql());
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        // Return metadata from the last execution's ResultSet, if available.
        // Per JDBC spec, may return null if no ResultSet has been produced yet.
        ResultSet rs = getResultSet();
        return rs != null ? rs.getMetaData() : null;
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        return new PgJavaParameterMetaData(params.length);
    }

    // -------------------------------------------------------------------------
    // Parameter setters

    private void set(int i, Object value) throws SQLException {
        if (i < 1 || i > params.length)
            throw new SQLException("Parameter index out of range: " + i);
        params[i - 1] = value;
    }

    @Override public void setNull(int i, int sqlType)              throws SQLException { set(i, null); }
    @Override public void setNull(int i, int sqlType, String type) throws SQLException { set(i, null); }
    @Override public void setBoolean(int i, boolean x)             throws SQLException { set(i, x); }
    @Override public void setByte(int i, byte x)                   throws SQLException { set(i, x); }
    @Override public void setShort(int i, short x)                 throws SQLException { set(i, x); }
    @Override public void setInt(int i, int x)                     throws SQLException { set(i, x); }
    @Override public void setLong(int i, long x)                   throws SQLException { set(i, x); }
    @Override public void setFloat(int i, float x)                 throws SQLException { set(i, x); }
    @Override public void setDouble(int i, double x)               throws SQLException { set(i, x); }
    @Override public void setBigDecimal(int i, BigDecimal x)       throws SQLException { set(i, x); }
    @Override public void setString(int i, String x)               throws SQLException { set(i, x); }
    @Override public void setBytes(int i, byte[] x)                throws SQLException { set(i, x); }
    @Override public void setDate(int i, Date x)                   throws SQLException { set(i, x); }
    @Override public void setTime(int i, Time x)                   throws SQLException { set(i, x); }
    @Override public void setTimestamp(int i, Timestamp x)         throws SQLException { set(i, x); }
    @Override public void setDate(int i, Date x, Calendar c)       throws SQLException { set(i, x); }
    @Override public void setTime(int i, Time x, Calendar c)       throws SQLException { set(i, x); }
    @Override public void setTimestamp(int i, Timestamp x, Calendar c) throws SQLException { set(i, x); }
    @Override public void setObject(int i, Object x)               throws SQLException { set(i, x); }
    @Override public void setObject(int i, Object x, int targetSqlType) throws SQLException { set(i, x); }
    @Override public void setObject(int i, Object x, int targetSqlType, int scale) throws SQLException { set(i, x); }
    @Override public void setURL(int i, URL x)                     throws SQLException { set(i, x == null ? null : x.toString()); }
    @Override public void setRef(int i, Ref x)                     throws SQLException { throw new SQLFeatureNotSupportedException("setRef"); }
    @Override public void setBlob(int i, Blob x)                   throws SQLException { throw new SQLFeatureNotSupportedException("setBlob"); }
    @Override public void setBlob(int i, InputStream x, long l)    throws SQLException { throw new SQLFeatureNotSupportedException("setBlob"); }
    @Override public void setBlob(int i, InputStream x)            throws SQLException { throw new SQLFeatureNotSupportedException("setBlob"); }
    @Override public void setClob(int i, Clob x)                   throws SQLException { throw new SQLFeatureNotSupportedException("setClob"); }
    @Override public void setClob(int i, Reader x, long l)         throws SQLException { throw new SQLFeatureNotSupportedException("setClob"); }
    @Override public void setClob(int i, Reader x)                 throws SQLException { throw new SQLFeatureNotSupportedException("setClob"); }
    @Override public void setArray(int i, Array x)                 throws SQLException {
        if (x == null) { set(i, null); return; }
        if (x instanceof PgJavaArray pa) { set(i, pa.toList()); return; }
        // Foreign Array impl — extract via getArray()
        Object arr = x.getArray();
        if (arr instanceof Object[] oa) { set(i, java.util.Arrays.asList(oa)); return; }
        set(i, arr);
    }
    @Override public void setRowId(int i, RowId x)                 throws SQLException { throw new SQLFeatureNotSupportedException("setRowId"); }
    @Override public void setNString(int i, String x)              throws SQLException { set(i, x); }
    @Override public void setNCharacterStream(int i, Reader x, long l) throws SQLException { throw new SQLFeatureNotSupportedException("setNCharacterStream"); }
    @Override public void setNCharacterStream(int i, Reader x)     throws SQLException { throw new SQLFeatureNotSupportedException("setNCharacterStream"); }
    @Override public void setNClob(int i, NClob x)                 throws SQLException { throw new SQLFeatureNotSupportedException("setNClob"); }
    @Override public void setNClob(int i, Reader x, long l)        throws SQLException { throw new SQLFeatureNotSupportedException("setNClob"); }
    @Override public void setNClob(int i, Reader x)                throws SQLException { throw new SQLFeatureNotSupportedException("setNClob"); }
    @Override public void setSQLXML(int i, SQLXML x)               throws SQLException { throw new SQLFeatureNotSupportedException("setSQLXML"); }
    @Override public void setAsciiStream(int i, InputStream x, int l)  throws SQLException { throw new SQLFeatureNotSupportedException("setAsciiStream"); }
    @Override public void setAsciiStream(int i, InputStream x, long l) throws SQLException { throw new SQLFeatureNotSupportedException("setAsciiStream"); }
    @Override public void setAsciiStream(int i, InputStream x)     throws SQLException { throw new SQLFeatureNotSupportedException("setAsciiStream"); }
    @Override public void setBinaryStream(int i, InputStream x, int l)  throws SQLException { throw new SQLFeatureNotSupportedException("setBinaryStream"); }
    @Override public void setBinaryStream(int i, InputStream x, long l) throws SQLException { throw new SQLFeatureNotSupportedException("setBinaryStream"); }
    @Override public void setBinaryStream(int i, InputStream x)    throws SQLException { throw new SQLFeatureNotSupportedException("setBinaryStream"); }
    @Override public void setCharacterStream(int i, Reader x, int l)    throws SQLException { throw new SQLFeatureNotSupportedException("setCharacterStream"); }
    @Override public void setCharacterStream(int i, Reader x, long l)   throws SQLException { throw new SQLFeatureNotSupportedException("setCharacterStream"); }
    @Override public void setCharacterStream(int i, Reader x)      throws SQLException { throw new SQLFeatureNotSupportedException("setCharacterStream"); }
    @Override public void setUnicodeStream(int i, InputStream x, int l) throws SQLException { throw new SQLFeatureNotSupportedException("setUnicodeStream"); }
    @Override public void clearParameters() throws SQLException { for (int i = 0; i < params.length; i++) params[i] = null; }

    private java.util.List<String> psBatch;

    @Override public void addBatch() throws SQLException {
        if (psBatch == null) psBatch = new java.util.ArrayList<>();
        psBatch.add(buildSql());
    }

    @Override public void clearBatch() throws SQLException {
        if (psBatch != null) psBatch.clear();
    }

    @Override public int[] executeBatch() throws SQLException {
        if (psBatch == null || psBatch.isEmpty()) return new int[0];
        int[] results = new int[psBatch.size()];
        for (int i = 0; i < psBatch.size(); i++) {
            try {
                results[i] = executeUpdate(psBatch.get(i));
            } catch (SQLException e) {
                throw new java.sql.BatchUpdateException(
                        e.getMessage(), e.getSQLState(), e.getErrorCode(),
                        java.util.Arrays.copyOf(results, i + 1), e);
            }
        }
        psBatch.clear();
        return results;
    }

    // -------------------------------------------------------------------------
    // SQL construction (simple ? substitution)

    private String buildSql() throws SQLException {
        if (params.length == 0) return template;
        StringBuilder sb = new StringBuilder();
        int paramIdx = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            char next = (i + 1 < template.length()) ? template.charAt(i + 1) : 0;

            if (inLineComment) {
                sb.append(c);
                if (c == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                sb.append(c);
                if (c == '*' && next == '/') { sb.append('/'); i++; inBlockComment = false; }
                continue;
            }
            if (inSingleQuote) {
                sb.append(c);
                if (c == '\'' && next == '\'') { sb.append('\''); i++; } // escaped quote
                else if (c == '\'') inSingleQuote = false;
                continue;
            }
            if (inDoubleQuote) {
                sb.append(c);
                if (c == '"') inDoubleQuote = false;
                continue;
            }

            if (c == '\'') { inSingleQuote = true; sb.append(c); }
            else if (c == '"') { inDoubleQuote = true; sb.append(c); }
            else if (c == '-' && next == '-') { inLineComment = true; sb.append(c); }
            else if (c == '/' && next == '*') { inBlockComment = true; sb.append(c); }
            else if (c == '?') {
                if (paramIdx >= params.length)
                    throw new SQLException("More ? placeholders than parameters");
                sb.append(toSqlLiteral(params[paramIdx++]));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String toSqlLiteral(Object v) {
        if (v == null)    return "NULL";
        if (v instanceof Boolean b) return b ? "TRUE" : "FALSE";
        if (v instanceof Number)    return v.toString();
        if (v instanceof Date d)    return "'" + d + "'";
        if (v instanceof Time t)    return "'" + t + "'";
        if (v instanceof Timestamp ts) return "'" + ts + "'";
        if (v instanceof LocalDate ld) return "'" + ld + "'";
        if (v instanceof LocalTime lt) return "'" + lt + "'";
        if (v instanceof LocalDateTime ldt) return "'" + ldt + "'";
        if (v instanceof OffsetDateTime odt) return "'" + odt + "'";
        if (v instanceof OffsetTime ot) return "'" + ot + "'";
        if (v instanceof Instant inst) return "'" + Timestamp.from(inst) + "'";
        if (v instanceof List<?> list) {
            var sb2 = new StringBuilder("ARRAY[");
            for (int j = 0; j < list.size(); j++) {
                if (j > 0) sb2.append(',');
                sb2.append(toSqlLiteral(list.get(j)));
            }
            sb2.append(']');
            return sb2.toString();
        }
        // String — escape single quotes
        return "'" + v.toString().replace("'", "''") + "'";
    }
}
