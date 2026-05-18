package org.pgjava.engine;

import org.pgjava.types.PgOid;

import java.sql.Types;

/**
 * Describes a single column in a QueryResult — used by ResultSet and ResultSetMetaData.
 */
public record ColumnMeta(
        String label,       // AS alias or column name
        String name,        // base column name
        String tableName,
        String schemaName,
        int    sqlType,     // java.sql.Types constant
        String typeName,    // PostgreSQL type name
        int    precision,
        int    scale,
        int    nullable     // ResultSetMetaData.columnNullable / columnNoNulls / columnNullableUnknown
) {
    /** Convenience: VARCHAR column with unknown nullability. */
    public static ColumnMeta varchar(String label) {
        return new ColumnMeta(label, label, "", "", Types.VARCHAR, "text", 0, 0,
                java.sql.ResultSetMetaData.columnNullableUnknown);
    }

    /** Convenience: INTEGER column with unknown nullability. */
    public static ColumnMeta integer(String label) {
        return new ColumnMeta(label, label, "", "", Types.INTEGER, "int4", 10, 0,
                java.sql.ResultSetMetaData.columnNullableUnknown);
    }

    /** Convenience: SMALLINT column with unknown nullability. */
    public static ColumnMeta smallint(String label) {
        return new ColumnMeta(label, label, "", "", Types.SMALLINT, "int2", 5, 0,
                java.sql.ResultSetMetaData.columnNullableUnknown);
    }

    /** Map a PostgreSQL type OID to a java.sql.Types constant. */
    public static int pgOidToJdbcType(int oid) {
        return switch (oid) {
            case PgOid.INT2              -> Types.SMALLINT;
            case PgOid.INT4              -> Types.INTEGER;
            case PgOid.INT8              -> Types.BIGINT;
            case PgOid.FLOAT4            -> Types.REAL;
            case PgOid.FLOAT8            -> Types.DOUBLE;
            case PgOid.NUMERIC           -> Types.NUMERIC;
            case PgOid.BOOL              -> Types.BOOLEAN;
            case PgOid.TEXT, PgOid.VARCHAR, PgOid.BPCHAR, PgOid.NAME -> Types.VARCHAR;
            case PgOid.BYTEA             -> Types.VARBINARY;
            case PgOid.DATE              -> Types.DATE;
            case PgOid.TIME              -> Types.TIME;
            case PgOid.TIMETZ            -> Types.TIME_WITH_TIMEZONE;
            case PgOid.TIMESTAMP         -> Types.TIMESTAMP;
            case PgOid.TIMESTAMPTZ       -> Types.TIMESTAMP_WITH_TIMEZONE;
            case PgOid.UUID              -> Types.OTHER;
            case PgOid.JSON, PgOid.JSONB -> Types.OTHER;
            case PgOid.INTERVAL          -> Types.OTHER;
            default                      -> Types.OTHER;
        };
    }
}
