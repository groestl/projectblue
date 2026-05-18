package org.pgjava.types;

import org.pgjava.engine.PgErrorException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;

/**
 * Parses PostgreSQL text-format input strings into Java objects.
 * One static method per type: {@code parse(String, int oid)}.
 *
 * <p>Parse errors throw {@link SQLException} with SQLSTATE {@code 22P02}
 * (invalid text representation) or {@code 22003} (out of range).
 */
public final class TypeInput {

    private TypeInput() {}

    /**
     * Parse {@code text} as a value of the given PostgreSQL type OID.
     *
     * @throws SQLException SQLSTATE 22P02 on bad format, 22003 on overflow
     */
    public static Object parse(String text, int oid) throws SQLException {
        if (text == null) return null;
        try {
            return switch (oid) {
                case PgOid.BOOL        -> parseBool(text);
                case PgOid.INT2        -> { int v = Integer.parseInt(text.strip()); rangeCheck(v, Short.MIN_VALUE, Short.MAX_VALUE, "smallint"); yield v; }
                case PgOid.INT4        -> Integer.parseInt(text.strip());
                case PgOid.INT8        -> Long.parseLong(text.strip());
                case PgOid.OID         -> Long.parseLong(text.strip());
                case PgOid.FLOAT4      -> parseFloat4(text.strip());
                case PgOid.FLOAT8      -> parseFloat8(text.strip());
                case PgOid.NUMERIC     -> new BigDecimal(text.strip());
                case PgOid.MONEY       -> parseMoney(text.strip());
                case PgOid.TEXT,
                     PgOid.VARCHAR,
                     PgOid.BPCHAR,
                     PgOid.NAME,
                     PgOid.UNKNOWN     -> text;
                case PgOid.CHAR        -> text.isEmpty() ? " " : text.substring(0, 1);
                case PgOid.DATE        -> parseDate(text.strip());
                case PgOid.TIME        -> parseTime(text.strip());
                case PgOid.TIMETZ      -> parseTimeTz(text.strip());
                case PgOid.TIMESTAMP   -> parseTimestamp(text.strip());
                case PgOid.TIMESTAMPTZ -> parseTimestampTz(text.strip());
                case PgOid.INTERVAL    -> PgInterval.parse(text.strip());
                case PgOid.UUID        -> UUID.fromString(text.strip());
                case PgOid.BYTEA       -> parseBytea(text.strip());
                case PgOid.JSON,
                     PgOid.JSONB       -> { validateJson(text); yield text; }
                case PgOid.XML         -> text;
                case PgOid.BIT,
                     PgOid.VARBIT      -> parseBits(text.strip());
                case PgOid.VOID        -> null;
                case PgOid.INT4RANGE   -> PgRange.parse(text.strip(),
                        s -> Integer.parseInt(s.strip()));
                case PgOid.INT8RANGE   -> PgRange.parse(text.strip(),
                        s -> Long.parseLong(s.strip()));
                case PgOid.NUMRANGE    -> PgRange.parse(text.strip(),
                        s -> new BigDecimal(s.strip()));
                case PgOid.TSRANGE     -> PgRange.parse(text.strip(),
                        s -> parseTimestamp(s.strip()));
                case PgOid.TSTZRANGE   -> PgRange.parse(text.strip(),
                        s -> parseTimestampTz(s.strip()));
                case PgOid.DATERANGE   -> PgRange.parse(text.strip(),
                        s -> parseDate(s.strip()));
                default                -> text; // unknown type — keep as string
            };
        } catch (SQLException e) {
            throw e;
        } catch (NumberFormatException e) {
            throw PgErrorException.error("22P02",
                    "invalid input syntax for type " + oidName(oid) + ": \"" + text + "\"").cause(e).build();
        } catch (DateTimeParseException e) {
            throw PgErrorException.error("22007",
                    "invalid input syntax for type " + oidName(oid) + ": \"" + text + "\"").cause(e).build();
        } catch (IllegalArgumentException e) {
            throw PgErrorException.error("22P02",
                    "invalid input syntax for type " + oidName(oid) + ": \"" + text + "\"").cause(e).build();
        }
    }

    // -------------------------------------------------------------------------
    // Array literal: "{elem1,elem2,...}"  →  List<Object>
    //
    // Handles:
    //   • Unquoted elements: {red,blue}, {1,2,3}
    //   • Double-quoted elements: {"hello world","foo,bar"}
    //   • NULL elements (unquoted NULL keyword): {NULL,1}
    //   • Empty array: {}
    //
    // Element values are parsed via parse(elem, elementOid).

    /**
     * Parse a PostgreSQL array literal string (e.g. {@code {red,blue}}) into a
     * {@link List} of Java objects, with each element parsed as {@code elementOid}.
     *
     * @param text       the array literal, e.g. {@code "{red,blue}"}
     * @param elementOid OID of the element type
     * @return a mutable {@link List} of parsed element values (null for SQL NULLs)
     * @throws SQLException SQLSTATE 22P02 if the format is invalid
     */
    public static List<Object> parseArrayLiteral(String text, int elementOid) throws SQLException {
        if (text == null) return null;
        String t = text.strip();
        if (!t.startsWith("{") || !t.endsWith("}"))
            throw PgErrorException.error("22P02",
                    "malformed array literal: \"" + text + "\"").build();

        String inner = t.substring(1, t.length() - 1);
        List<Object> result = new ArrayList<>();

        if (inner.isEmpty()) return result; // empty array {}

        // Tokenize respecting double-quoted strings
        int i = 0;
        int len = inner.length();
        while (i <= len) {
            // skip leading whitespace
            while (i < len && inner.charAt(i) == ' ') i++;
            if (i >= len) break;

            String elem;
            boolean wasQuoted = false;
            if (inner.charAt(i) == '"') {
                // Quoted element: collect until closing unescaped '"'
                i++; // skip opening quote
                StringBuilder sb = new StringBuilder();
                while (i < len) {
                    char c = inner.charAt(i++);
                    if (c == '\\' && i < len) {
                        sb.append(inner.charAt(i++)); // escape sequence
                    } else if (c == '"') {
                        break; // closing quote
                    } else {
                        sb.append(c);
                    }
                }
                elem = sb.toString();
                wasQuoted = true;
            } else {
                // Unquoted element: collect until ',' or end
                int start = i;
                while (i < len && inner.charAt(i) != ',') i++;
                elem = inner.substring(start, i).strip();
            }

            // Advance past the ',' separator (if present)
            while (i < len && inner.charAt(i) == ' ') i++;
            if (i < len && inner.charAt(i) == ',') i++;

            // Unquoted NULL → SQL NULL
            if (!wasQuoted && "NULL".equalsIgnoreCase(elem)) {
                result.add(null);
            } else {
                result.add(parse(elem, elementOid));
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Boolean

    private static Boolean parseBool(String s) throws SQLException {
        return switch (s.strip().toLowerCase()) {
            case "true", "t", "yes", "y", "on",  "1" -> Boolean.TRUE;
            case "false","f", "no",  "n", "off", "0" -> Boolean.FALSE;
            default -> throw PgErrorException.error("22P02",
                    "invalid input syntax for type boolean: \"" + s + "\"").build();
        };
    }

    // -------------------------------------------------------------------------
    // Numeric

    private static float parseFloat4(String s) throws SQLException {
        return switch (s.toLowerCase()) {
            case "infinity", "+infinity", "inf", "+inf" ->  Float.POSITIVE_INFINITY;
            case "-infinity", "-inf"                    ->  Float.NEGATIVE_INFINITY;
            case "nan"                                  ->  Float.NaN;
            default -> Float.parseFloat(s);
        };
    }

    private static double parseFloat8(String s) throws SQLException {
        return switch (s.toLowerCase()) {
            case "infinity", "+infinity", "inf", "+inf" ->  Double.POSITIVE_INFINITY;
            case "-infinity", "-inf"                    ->  Double.NEGATIVE_INFINITY;
            case "nan"                                  ->  Double.NaN;
            default -> Double.parseDouble(s);
        };
    }

    private static BigDecimal parseMoney(String s) {
        // Strip currency symbol and grouping separators
        String clean = s.replace("$", "").replace(",", "").strip();
        return new BigDecimal(clean).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static void rangeCheck(long v, long min, long max, String type) throws SQLException {
        if (v < min || v > max)
            throw PgErrorException.error("22003", type + " out of range").build();
    }

    // -------------------------------------------------------------------------
    // Date / time — ISO 8601 primary, fallback formats

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ISO_LOCAL_DATE,                     // 2024-01-31
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),            // 01/31/2024
            DateTimeFormatter.ofPattern("dd-MMM-yyyy"),           // 31-Jan-2024
    };

    private static LocalDate parseDate(String s) throws SQLException {
        for (DateTimeFormatter f : DATE_FORMATS) {
            try { return LocalDate.parse(s, f); } catch (DateTimeParseException ignored) {}
        }
        throw PgErrorException.error("22007", "invalid input syntax for type date: \"" + s + "\"").build();
    }

    private static LocalTime parseTime(String s) throws SQLException {
        try {
            return LocalTime.parse(s, DateTimeFormatter.ISO_LOCAL_TIME);
        } catch (DateTimeParseException e) {
            throw PgErrorException.error("22007",
                    "invalid input syntax for type time: \"" + s + "\"").cause(e).build();
        }
    }

    private static OffsetTime parseTimeTz(String s) throws SQLException {
        try {
            return OffsetTime.parse(s, DateTimeFormatter.ISO_OFFSET_TIME);
        } catch (DateTimeParseException e) {
            throw PgErrorException.error("22007",
                    "invalid input syntax for type time with time zone: \"" + s + "\"").cause(e).build();
        }
    }

    private static LocalDateTime parseTimestamp(String s) throws SQLException {
        String norm = s.replace(' ', 'T');   // "2024-01-31 12:00:00" → ISO
        try { return LocalDateTime.parse(norm, DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
        catch (DateTimeParseException e) {
            throw PgErrorException.error("22007", "invalid input syntax for type timestamp: \"" + s + "\"").cause(e).build();
        }
    }

    private static OffsetDateTime parseTimestampTz(String s) throws SQLException {
        String norm = s.replace(' ', 'T');
        try {
            // Try with offset first
            try { return OffsetDateTime.parse(norm, DateTimeFormatter.ISO_OFFSET_DATE_TIME); }
            catch (DateTimeParseException ignored) {}
            // Try as local → assume UTC
            LocalDateTime ldt = LocalDateTime.parse(norm, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return ldt.atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw PgErrorException.error("22007", "invalid input syntax for type timestamp with time zone: \"" + s + "\"").cause(e).build();
        }
    }

    // -------------------------------------------------------------------------
    // Binary

    private static byte[] parseBytea(String s) throws SQLException {
        if (s.startsWith("\\x") || s.startsWith("\\X")) {
            // Hex format
            String hex = s.substring(2).replaceAll("\\s", "");
            if ((hex.length() & 1) != 0)
                throw PgErrorException.error("22P02", "invalid hexadecimal data: odd number of digits").build();
            byte[] b = new byte[hex.length() / 2];
            for (int i = 0; i < b.length; i++)
                b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            return b;
        }
        // Escape format: \\ → backslash, \ooo → octal
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '\\') { out.write('\\'); i++; }
                else if (i + 3 < s.length()) {
                    out.write(Integer.parseInt(s.substring(i + 1, i + 4), 8)); i += 3;
                }
            } else {
                out.write(c);
            }
        }
        return out.toByteArray();
    }

    // -------------------------------------------------------------------------
    // JSON validation (minimal — just check it parses)

    private static void validateJson(String s) throws SQLException {
        String t = s.strip();
        if (t.isEmpty()) throw PgErrorException.error("22032", "invalid input syntax for type json").build();
        // Full validation via Jackson
        JsonOps.parse(t);
    }

    // -------------------------------------------------------------------------
    // Bit strings

    private static BitSet parseBits(String s) {
        BitSet bs = new BitSet(s.length());
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '1') bs.set(i);
        }
        return bs;
    }

    /** Map OID to PG type name for error messages. */
    private static String oidName(int oid) {
        return switch (oid) {
            case PgOid.BOOL        -> "boolean";
            case PgOid.INT2        -> "smallint";
            case PgOid.INT4        -> "integer";
            case PgOid.INT8        -> "bigint";
            case PgOid.FLOAT4      -> "real";
            case PgOid.FLOAT8      -> "double precision";
            case PgOid.NUMERIC     -> "numeric";
            case PgOid.TEXT        -> "text";
            case PgOid.VARCHAR     -> "character varying";
            case PgOid.BPCHAR      -> "character";
            case PgOid.NAME        -> "name";
            case PgOid.DATE        -> "date";
            case PgOid.TIME        -> "time without time zone";
            case PgOid.TIMETZ      -> "time with time zone";
            case PgOid.TIMESTAMP   -> "timestamp without time zone";
            case PgOid.TIMESTAMPTZ -> "timestamp with time zone";
            case PgOid.INTERVAL    -> "interval";
            case PgOid.UUID        -> "uuid";
            case PgOid.BYTEA       -> "bytea";
            case PgOid.JSON        -> "json";
            case PgOid.JSONB       -> "jsonb";
            case PgOid.OID         -> "oid";
            case PgOid.MONEY       -> "money";
            default                -> String.valueOf(oid);
        };
    }
}
