package org.pgjava.jdbc;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;

/** ParameterMetaData for a prepared statement. Phase 2: reports all params as VARCHAR. */
final class PgJavaParameterMetaData implements ParameterMetaData {

    private final int paramCount;

    PgJavaParameterMetaData(int paramCount) {
        this.paramCount = paramCount;
    }

    @Override public int     getParameterCount()            { return paramCount; }
    @Override public int     getParameterType(int param)    throws SQLException { checkIdx(param); return Types.VARCHAR; }
    @Override public String  getParameterTypeName(int param) throws SQLException { checkIdx(param); return "text"; }
    @Override public String  getParameterClassName(int param) throws SQLException { checkIdx(param); return String.class.getName(); }
    @Override public int     getParameterMode(int param)    throws SQLException { checkIdx(param); return ParameterMetaData.parameterModeIn; }
    @Override public int     isNullable(int param)          throws SQLException { checkIdx(param); return ParameterMetaData.parameterNullableUnknown; }
    @Override public boolean isSigned(int param)            throws SQLException { checkIdx(param); return false; }
    @Override public int     getPrecision(int param)        throws SQLException { checkIdx(param); return 0; }
    @Override public int     getScale(int param)            throws SQLException { checkIdx(param); return 0; }

    private void checkIdx(int i) throws SQLException {
        if (i < 1 || i > paramCount) throw new SQLException("Parameter index out of range: " + i);
    }

    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface);
    }
    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}
