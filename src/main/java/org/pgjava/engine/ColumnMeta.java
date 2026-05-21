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

    /** Build a ColumnMeta from a PostgreSQL type OID. */
    public static ColumnMeta ofOid(String label, int oid) {
        int sqlType = pgOidToJdbcType(oid);
        String typeName = pgOidToTypeName(oid);
        int precision = switch (oid) {
            case PgOid.INT2    -> 5;
            case PgOid.INT4    -> 10;
            case PgOid.INT8    -> 19;
            case PgOid.FLOAT4  -> 7;
            case PgOid.FLOAT8  -> 15;
            case PgOid.NUMERIC -> 131089; // variable
            default -> 0;
        };
        int scale = switch (oid) {
            case PgOid.FLOAT4, PgOid.FLOAT8 -> 0;
            default -> 0;
        };
        return new ColumnMeta(label, label, "", "", sqlType, typeName, precision, scale,
                java.sql.ResultSetMetaData.columnNullableUnknown);
    }

    /** Map a PostgreSQL type OID to a PostgreSQL type name string. */
    public static String pgOidToTypeName(int oid) {
        return switch (oid) {
            case PgOid.INT2      -> "int2";
            case PgOid.INT4      -> "int4";
            case PgOid.INT8      -> "int8";
            case PgOid.FLOAT4    -> "float4";
            case PgOid.FLOAT8    -> "float8";
            case PgOid.NUMERIC   -> "numeric";
            case PgOid.BOOL      -> "bool";
            case PgOid.TEXT      -> "text";
            case PgOid.VARCHAR   -> "varchar";
            case PgOid.BPCHAR    -> "bpchar";
            case PgOid.NAME      -> "name";
            case PgOid.BYTEA     -> "bytea";
            case PgOid.DATE      -> "date";
            case PgOid.TIME      -> "time";
            case PgOid.TIMETZ    -> "timetz";
            case PgOid.TIMESTAMP -> "timestamp";
            case PgOid.TIMESTAMPTZ -> "timestamptz";
            case PgOid.INTERVAL  -> "interval";
            case PgOid.UUID      -> "uuid";
            case PgOid.JSON      -> "json";
            case PgOid.JSONB     -> "jsonb";
            case PgOid.INT2_ARRAY -> "int2[]";
            case PgOid.INT4_ARRAY -> "int4[]";
            case PgOid.INT8_ARRAY -> "int8[]";
            case PgOid.TEXT_ARRAY -> "text[]";
            case PgOid.FLOAT4_ARRAY -> "float4[]";
            case PgOid.FLOAT8_ARRAY -> "float8[]";
            case PgOid.BOOL_ARRAY   -> "bool[]";
            case PgOid.NUMERIC_ARRAY -> "numeric[]";
            case PgOid.DATE_ARRAY    -> "date[]";
            case PgOid.TIMESTAMPTZ_ARRAY -> "timestamptz[]";
            default -> "text";
        };
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
