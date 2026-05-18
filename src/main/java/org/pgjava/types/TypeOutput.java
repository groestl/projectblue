package org.pgjava.types;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.BitSet;

/**
 * Formats Java values as PostgreSQL text-format output strings.
 *
 * <p>The output format matches PostgreSQL's default text representation
 * (DateStyle = ISO, MDY; IntervalStyle = postgres).
 */
public final class TypeOutput {

    private TypeOutput() {}

    private static final DateTimeFormatter DATE_FMT       = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FMT       = DateTimeFormatter.ISO_LOCAL_TIME;
    private static final DateTimeFormatter TIMETZ_FMT     = DateTimeFormatter.ISO_OFFSET_TIME;
    private static final DateTimeFormatter TIMESTAMP_FMT  = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIMESTAMP_MICROS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final DateTimeFormatter TIMESTAMPTZ_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssxxx");

    /**
     * Format a value for PostgreSQL text output.
     *
     * @param value  the Java value (must not be null)
     * @param oid    PostgreSQL type OID
     * @return text representation
     */
    public static String format(Object value, int oid) {
        if (value == null) return null;
        return switch (oid) {
            case PgOid.BOOL        -> (Boolean) value ? "t" : "f";
            case PgOid.INT2,
                 PgOid.INT4,
                 PgOid.OID         -> value.toString();
            case PgOid.INT8        -> value.toString();
            case PgOid.FLOAT4      -> formatFloat4(value instanceof Float f ? f : ((Number) value).floatValue());
            case PgOid.FLOAT8      -> formatFloat8(value instanceof Double d ? d : ((Number) value).doubleValue());
            case PgOid.NUMERIC,
                 PgOid.MONEY       -> (value instanceof BigDecimal bd) ? bd.toPlainString()
                                       : new BigDecimal(value.toString()).toPlainString();
            case PgOid.TEXT,
                 PgOid.VARCHAR,
                 PgOid.BPCHAR,
                 PgOid.NAME,
                 PgOid.UNKNOWN,
                 PgOid.XML,
                 PgOid.JSON,
                 PgOid.JSONB       -> value.toString();
            case PgOid.DATE        -> ((LocalDate) value).format(DATE_FMT);
            case PgOid.TIME        -> ((LocalTime) value).format(TIME_FMT);
            case PgOid.TIMETZ      -> ((OffsetTime) value).format(TIMETZ_FMT);
            case PgOid.TIMESTAMP   -> formatTimestamp((LocalDateTime) value);
            case PgOid.TIMESTAMPTZ -> formatTimestampTz((OffsetDateTime) value);
            case PgOid.INTERVAL    -> value.toString();  // PgInterval.toString() uses postgres style
            case PgOid.UUID        -> value.toString();
            case PgOid.BYTEA       -> formatBytea((byte[]) value);
            case PgOid.BIT,
                 PgOid.VARBIT      -> formatBits((BitSet) value);
            default                -> value.toString();
        };
    }

    // -------------------------------------------------------------------------

    private static String formatFloat4(float v) {
        if (Float.isInfinite(v)) return v > 0 ? "Infinity" : "-Infinity";
        if (Float.isNaN(v)) return "NaN";
        return Float.toString(v);
    }

    private static String formatFloat8(double v) {
        if (Double.isInfinite(v)) return v > 0 ? "Infinity" : "-Infinity";
        if (Double.isNaN(v)) return "NaN";
        // Match PostgreSQL's default: use toString() but avoid scientific notation for moderate values
        String s = Double.toString(v);
        // Java uses 'E' notation for large/small; PG uses 'e' — normalize
        return s.replace('E', 'e');
    }

    private static String formatTimestamp(LocalDateTime dt) {
        if (dt.getNano() == 0) return dt.format(TIMESTAMP_FMT);
        return dt.format(TIMESTAMP_MICROS);
    }

    private static String formatTimestampTz(OffsetDateTime dt) {
        return dt.format(TIMESTAMPTZ_FMT);
    }

    private static String formatBytea(byte[] b) {
        // Always output in \\x hex format (PostgreSQL default since 9.0)
        StringBuilder sb = new StringBuilder(b.length * 2 + 2);
        sb.append("\\x");
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    private static String formatBits(BitSet bs) {
        if (bs.isEmpty()) return "";
        int len = bs.length();
        char[] c = new char[len];
        for (int i = 0; i < len; i++) c[i] = bs.get(i) ? '1' : '0';
        return new String(c);
    }
}
