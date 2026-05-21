package org.pgjava.harness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compares two {@link ResultSetSnapshot} instances according to the dual-executor rules:
 *
 * <ul>
 *   <li><b>Set mode</b> (default): row order is irrelevant; rows treated as multisets.
 *   <li><b>Sequence mode</b>: used when both snapshots are {@link ResultSetSnapshot#isOrdered()};
 *       row order must match exactly.
 *   <li><b>Float epsilon</b>: {@code float8} within 1e-9, {@code float4} within 1e-6.
 *   <li><b>NULL == NULL</b>: SQL NULLs are considered equal for comparison purposes.
 *   <li><b>Column count</b>: must match.
 * </ul>
 */
public final class ResultSetComparator {

    private static final double EPSILON_FLOAT8 = 1e-9;
    private static final double EPSILON_FLOAT4 = 1e-6;

    private ResultSetComparator() {}

    /**
     * Assert that {@code expected} (postgres) and {@code actual} (pgjava) contain
     * the same data. Fails with a human-readable diff on divergence.
     */
    public static void assertEqual(ResultSetSnapshot expected, ResultSetSnapshot actual,
                                   String sql) {
        checkColumnCount(expected, actual, sql);
        checkColumnNames(expected, actual, sql);

        boolean ordered = expected.isOrdered() && actual.isOrdered();

        if (ordered) {
            assertSequenceEqual(expected, actual, sql);
        } else {
            assertSetEqual(expected, actual, sql);
        }
    }

    // -------------------------------------------------------------------------

    private static void checkColumnCount(ResultSetSnapshot exp, ResultSetSnapshot act, String sql) {
        if (exp.columns().size() != act.columns().size()) {
            fail(String.format(
                    "Column count mismatch for query:\n  %s\n  postgres: %d columns %s\n  pgjava:   %d columns %s",
                    sql,
                    exp.columns().size(), columnNames(exp),
                    act.columns().size(), columnNames(act)));
        }
    }

    private static void checkColumnNames(ResultSetSnapshot exp, ResultSetSnapshot act, String sql) {
        for (int i = 0; i < exp.columns().size(); i++) {
            String expName = exp.columns().get(i).name().toLowerCase();
            String actName = act.columns().get(i).name().toLowerCase();
            if (!expName.equals(actName)) {
                fail(String.format(
                        "Column name mismatch at position %d for query:\n  %s\n  postgres: %s\n  pgjava:   %s",
                        i + 1, sql, expName, actName));
            }
        }
    }

    private static void assertSequenceEqual(ResultSetSnapshot exp, ResultSetSnapshot act, String sql) {
        if (exp.rowCount() != act.rowCount()) {
            fail(rowCountMismatch(exp, act, sql));
        }
        for (int r = 0; r < exp.rowCount(); r++) {
            Object[] expRow = exp.rows().get(r);
            Object[] actRow = act.rows().get(r);
            if (!rowsEqual(expRow, actRow)) {
                fail(String.format(
                        "Row %d mismatch (ordered) for query:\n  %s\n  postgres: %s\n  pgjava:   %s",
                        r + 1, sql, rowToString(expRow), rowToString(actRow)));
            }
        }
    }

    private static void assertSetEqual(ResultSetSnapshot exp, ResultSetSnapshot act, String sql) {
        if (exp.rowCount() != act.rowCount()) {
            fail(rowCountMismatch(exp, act, sql));
        }

        // Match rows as multisets: for each expected row, find an unmatched actual row equal to it
        List<Object[]> remaining = new ArrayList<>(act.rows());
        List<Object[]> unmatched = new ArrayList<>();

        for (Object[] expRow : exp.rows()) {
            boolean found = false;
            for (int i = 0; i < remaining.size(); i++) {
                if (rowsEqual(expRow, remaining.get(i))) {
                    remaining.remove(i);
                    found = true;
                    break;
                }
            }
            if (!found) unmatched.add(expRow);
        }

        if (!unmatched.isEmpty()) {
            var sb = new StringBuilder();
            sb.append("Result set mismatch (set mode) for query:\n  ").append(sql).append('\n');
            sb.append("  In postgres but not pgjava:\n");
            unmatched.forEach(r -> sb.append("    ").append(rowToString(r)).append('\n'));
            sb.append("  In pgjava but not postgres:\n");
            remaining.forEach(r -> sb.append("    ").append(rowToString(r)).append('\n'));
            fail(sb.toString());
        }
    }

    static boolean rowsEqual(Object[] a, Object[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!valuesEqual(a[i], b[i])) return false;
        }
        return true;
    }

    static boolean valuesEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;

        // Float epsilon comparison — handle Infinity and NaN before subtraction
        if (a instanceof Double da && b instanceof Double db) {
            if (da.equals(db)) return true;  // Infinity==Infinity, NaN==NaN (by bit pattern)
            return Math.abs(da - db) <= EPSILON_FLOAT8;
        }
        if (a instanceof Float fa && b instanceof Float fb) {
            if (fa.equals(fb)) return true;
            return Math.abs(fa - fb) <= EPSILON_FLOAT4;
        }
        // BigDecimal: compare by value (ignore scale differences e.g. 1.0 vs 1.00)
        if (a instanceof BigDecimal ba && b instanceof BigDecimal bb) {
            return ba.compareTo(bb) == 0;
        }
        // Numeric cross-type: int vs long, etc. — compare as strings of canonical form
        if (isNumeric(a) && isNumeric(b)) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        // Temporal cross-type: java.sql.Timestamp vs java.time.LocalDateTime, etc.
        // Both sides may use different representations; normalize to string and compare
        if (isTemporal(a) || isTemporal(b)) {
            return normalizeTemporalString(a).equals(normalizeTemporalString(b));
        }
        // Interval comparison: PG returns PGInterval, pgjava returns PgInterval
        if (isInterval(a) || isInterval(b)) {
            return normalizeIntervalMicros(a) == normalizeIntervalMicros(b);
        }
        // JSON comparison: must come BEFORE array check because JSON objects also start with '{'.
        // Try semantic JSON equality first; only fall back to array normalization for PG array literals.
        String as = a.toString(), bs = b.toString();
        if (isJsonLike(as) && isJsonLike(bs)) {
            if (as.equals(bs)) return true;
            // Try JSON comparison; if both parse as JSON they compare semantically
            if (jsonEquals(as, bs)) return true;
        }
        // Array comparison: PG returns java.sql.Array ("{1,2,3}"), pgjava returns List ("[1, 2, 3]")
        if (isArray(a) || isArray(b)) {
            return normalizeArrayString(a).equals(normalizeArrayString(b));
        }
        return Objects.equals(as, bs);
    }

    private static boolean isJsonLike(String s) {
        if (s == null || s.isEmpty()) return false;
        char c = s.charAt(0);
        return c == '{' || c == '[';
    }

    private static boolean jsonEquals(String a, String b) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode na = mapper.readTree(a);
            com.fasterxml.jackson.databind.JsonNode nb = mapper.readTree(b);
            return na.equals(nb);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isTemporal(Object v) {
        return v instanceof java.sql.Timestamp || v instanceof java.sql.Date
                || v instanceof java.sql.Time
                || v instanceof java.time.LocalDateTime || v instanceof java.time.LocalDate
                || v instanceof java.time.LocalTime || v instanceof java.time.OffsetDateTime
                || v instanceof java.time.OffsetTime;
    }

    /**
     * Normalize temporal values to a common string format for comparison.
     * PG JDBC returns java.sql.Timestamp ("2024-01-01 10:00:00.0"),
     * pgjava returns LocalDateTime ("2024-01-01T10:00").
     * Normalize to "yyyy-MM-dd HH:mm:ss[.fraction]" or "HH:mm:ss" for time.
     */
    private static String normalizeTemporalString(Object v) {
        String s = v.toString();
        // Replace T separator with space (Java LocalDateTime format)
        s = s.replace('T', ' ');
        // Remove trailing ".0" (Timestamp adds .0 for zero nanos)
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        // Ensure seconds are present: "10:30" → "10:30:00"
        // Extract time part (after last space, or the whole string for time-only values)
        int spaceIdx = s.lastIndexOf(' ');
        String timePart = spaceIdx >= 0 ? s.substring(spaceIdx + 1) : s;
        // If time part has only HH:MM (one colon), add :00 for seconds
        long colonCount = timePart.chars().filter(c -> c == ':').count();
        if (colonCount == 1) s += ":00";
        return s;
    }

    private static boolean isInterval(Object v) {
        String cn = v.getClass().getName();
        return cn.contains("Interval") || cn.contains("interval");
    }

    /**
     * Normalize an interval to total microseconds for comparison.
     * Handles both PG JDBC's PGInterval ("0 years 0 mons 0 days 1 hours 30 mins 0.0 secs")
     * and pgjava's PgInterval ("01:30:00").
     */
    private static long normalizeIntervalMicros(Object v) {
        String s = v.toString().strip();
        // Try PG JDBC PGInterval format: "X years Y mons Z days H hours M mins S secs"
        long months = 0, days = 0, micros = 0;
        java.util.regex.Matcher m;
        m = java.util.regex.Pattern.compile("(-?\\d+)\\s+years?").matcher(s);
        if (m.find()) months += Long.parseLong(m.group(1)) * 12;
        m = java.util.regex.Pattern.compile("(-?\\d+)\\s+mons?").matcher(s);
        if (m.find()) months += Long.parseLong(m.group(1));
        m = java.util.regex.Pattern.compile("(-?\\d+)\\s+days?").matcher(s);
        if (m.find()) days += Long.parseLong(m.group(1));
        m = java.util.regex.Pattern.compile("(-?\\d+)\\s+hours?").matcher(s);
        if (m.find()) micros += Long.parseLong(m.group(1)) * 3_600_000_000L;
        m = java.util.regex.Pattern.compile("(-?\\d+)\\s+mins?").matcher(s);
        if (m.find()) micros += Long.parseLong(m.group(1)) * 60_000_000L;
        m = java.util.regex.Pattern.compile("(-?[\\d.]+)\\s+secs?").matcher(s);
        if (m.find()) micros += Math.round(Double.parseDouble(m.group(1)) * 1_000_000L);
        // Try HH:MM:SS format
        m = java.util.regex.Pattern.compile("(-?)(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").matcher(s);
        if (m.find()) {
            long h = Long.parseLong(m.group(2));
            long mi = Long.parseLong(m.group(3));
            double sec = Double.parseDouble(m.group(4));
            long t = h * 3_600_000_000L + mi * 60_000_000L + Math.round(sec * 1_000_000L);
            if ("-".equals(m.group(1))) t = -t;
            micros += t;
        }
        m = java.util.regex.Pattern.compile("(-?\\d+)\\s+months?").matcher(s);
        if (m.find()) months += Long.parseLong(m.group(1));
        // Combine: months are kept separate from days in PG, but for equality check we compare all
        return months * 2_592_000_000_000L + days * 86_400_000_000L + micros;
    }

    private static boolean isNumeric(Object v) {
        return v instanceof Number;
    }

    private static boolean isArray(Object v) {
        return v instanceof java.sql.Array
                || v instanceof java.util.List
                || (v != null && v.toString().startsWith("{") && v.toString().endsWith("}"));
    }

    /**
     * Normalize array representation to a canonical comma-separated format.
     * PG JDBC: "{1,2,3}" or "{a,b,c}" or "{1,2,3,NULL}"
     * pgjava:  "[1, 2, 3]" or "[a, b, c, null]"
     * Normalized: "1,2,3" or "a,b,c" or "1,2,3,NULL"
     */
    private static String normalizeArrayString(Object v) {
        String s;
        if (v instanceof java.sql.Array sqlArr) {
            try { s = sqlArr.toString(); } catch (Exception e) { s = v.toString(); }
        } else {
            s = v.toString();
        }
        // Strip outer braces/brackets
        s = s.trim();
        if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length() - 1);
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        // Normalize whitespace around commas and NULL case
        String[] parts = s.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(',');
            String part = parts[i].trim();
            // Only normalize NULL literal casing, preserve element case for enum/text values
            sb.append("null".equalsIgnoreCase(part) ? "NULL" : part);
        }
        return sb.toString();
    }

    private static String rowCountMismatch(ResultSetSnapshot exp, ResultSetSnapshot act, String sql) {
        return String.format(
                "Row count mismatch for query:\n  %s\n  postgres: %d rows\n  pgjava:   %d rows",
                sql, exp.rowCount(), act.rowCount());
    }

    private static String columnNames(ResultSetSnapshot snap) {
        return snap.columns().stream()
                .map(ResultSetSnapshot.Column::name)
                .toList()
                .toString();
    }

    private static String rowToString(Object[] row) {
        return Arrays.toString(row);
    }
}
