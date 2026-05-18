package org.pgjava.types;

/**
 * PostgreSQL built-in type OIDs — must match real PostgreSQL exactly.
 * Source: {@code pg_type.h} / {@code SELECT oid, typname FROM pg_type WHERE oid < 10000}.
 */
public final class PgOid {
    private PgOid() {}

    // -------------------------------------------------------------------------
    // Scalar types

    public static final int BOOL         = 16;
    public static final int BYTEA        = 17;
    public static final int CHAR         = 18;    // "char" (1-byte internal type)
    public static final int NAME         = 19;
    public static final int INT8         = 20;
    public static final int INT2         = 21;
    public static final int INT4         = 23;
    public static final int TEXT         = 25;
    public static final int OID          = 26;
    public static final int XML          = 142;
    public static final int JSON         = 114;
    public static final int FLOAT4       = 700;
    public static final int FLOAT8       = 701;
    public static final int UNKNOWN      = 705;
    public static final int MONEY        = 790;
    public static final int MACADDR      = 829;
    public static final int INET         = 869;
    public static final int CIDR         = 650;
    public static final int BPCHAR       = 1042;
    public static final int VARCHAR      = 1043;
    public static final int DATE         = 1082;
    public static final int TIME         = 1083;
    public static final int TIMESTAMP    = 1114;
    public static final int TIMESTAMPTZ  = 1184;
    public static final int INTERVAL     = 1186;
    public static final int TIMETZ       = 1266;
    public static final int BIT          = 1560;
    public static final int VARBIT       = 1562;
    public static final int NUMERIC      = 1700;
    public static final int VOID         = 2278;
    // Pseudo-types
    public static final int ANY          = 2276;   // "any" — matches any type
    public static final int ANYARRAY     = 2277;   // "anyarray" — matches any array type
    public static final int ANYELEMENT   = 2283;   // "anyelement" — matches any element type
    public static final int UUID         = 2950;
    public static final int PG_LSN       = 3220;
    public static final int JSONB        = 3802;

    // -------------------------------------------------------------------------
    // Array types (OIDs match pg_type._<type>)

    public static final int BOOL_ARRAY        = 1000;
    public static final int BYTEA_ARRAY       = 1001;
    public static final int CHAR_ARRAY        = 1002;
    public static final int NAME_ARRAY        = 1003;
    public static final int INT2_ARRAY        = 1005;
    public static final int INT4_ARRAY        = 1007;
    public static final int TEXT_ARRAY        = 1009;
    public static final int OID_ARRAY         = 1028;
    public static final int FLOAT4_ARRAY      = 1021;
    public static final int FLOAT8_ARRAY      = 1022;
    public static final int MONEY_ARRAY       = 791;
    public static final int BPCHAR_ARRAY      = 1014;
    public static final int VARCHAR_ARRAY     = 1015;
    public static final int INT8_ARRAY        = 1016;
    public static final int DATE_ARRAY        = 1182;
    public static final int TIME_ARRAY        = 1183;
    public static final int TIMESTAMP_ARRAY   = 1115;
    public static final int TIMESTAMPTZ_ARRAY = 1185;
    public static final int INTERVAL_ARRAY    = 1187;
    public static final int NUMERIC_ARRAY     = 1231;
    public static final int UUID_ARRAY        = 2951;
    public static final int JSONB_ARRAY       = 3807;
    public static final int JSON_ARRAY        = 199;
    public static final int XML_ARRAY         = 143;
    public static final int TIMETZ_ARRAY      = 1270;
    public static final int MACADDR_ARRAY     = 1040;
    public static final int INET_ARRAY        = 1041;
    public static final int CIDR_ARRAY        = 651;
    public static final int BIT_ARRAY         = 1561;
    public static final int VARBIT_ARRAY      = 1563;

    // -------------------------------------------------------------------------
    // Range types

    public static final int INT4RANGE  = 3904;
    public static final int NUMRANGE   = 3906;
    public static final int TSRANGE    = 3908;
    public static final int TSTZRANGE  = 3910;
    public static final int DATERANGE  = 3912;
    public static final int INT8RANGE  = 3926;

    // -------------------------------------------------------------------------
    // First OID available for user-defined types

    public static final int FIRST_USER_OID = 16384;
}
