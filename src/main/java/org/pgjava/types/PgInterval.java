package org.pgjava.types;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL interval value.
 *
 * <p>PostgreSQL intervals store three independent components that are NOT
 * automatically converted into each other:
 * <ul>
 *   <li>{@code months} — years * 12 + months</li>
 *   <li>{@code days}   — calendar days (kept separate from months)</li>
 *   <li>{@code micros} — microseconds (hours, minutes, seconds, fractional seconds)</li>
 * </ul>
 *
 * <p>This matches PostgreSQL's internal {@code Interval} struct exactly.
 * Java's {@code Period} and {@code Duration} cannot represent this because
 * a PG interval mixes calendar units (months/days) with time units.
 */
public record PgInterval(int months, int days, long micros) {

    public static final PgInterval ZERO = new PgInterval(0, 0, 0L);

    // -------------------------------------------------------------------------
    // Factory helpers

    public static PgInterval ofYears(int y)        { return new PgInterval(y * 12, 0, 0); }
    public static PgInterval ofMonths(int m)        { return new PgInterval(m, 0, 0); }
    public static PgInterval ofDays(int d)          { return new PgInterval(0, d, 0); }
    public static PgInterval ofHours(long h)        { return new PgInterval(0, 0, h * 3_600_000_000L); }
    public static PgInterval ofMinutes(long m)      { return new PgInterval(0, 0, m * 60_000_000L); }
    public static PgInterval ofSeconds(long s)      { return new PgInterval(0, 0, s * 1_000_000L); }
    public static PgInterval ofMicros(long us)      { return new PgInterval(0, 0, us); }

    // -------------------------------------------------------------------------
    // Arithmetic

    public PgInterval plus(PgInterval other) {
        return new PgInterval(months + other.months, days + other.days, micros + other.micros);
    }

    public PgInterval negate() {
        return new PgInterval(-months, -days, -micros);
    }

    // -------------------------------------------------------------------------
    // Parsing — PostgreSQL "postgres" output style: "1 year 2 months 3 days 04:05:06"

    private static final Pattern VERBOSE = Pattern.compile(
            "(?:(-?\\d+)\\s+years?\\s*)?" +
            "(?:(-?\\d+)\\s+mons?\\s*)?" +
            "(?:(-?\\d+)\\s+days?\\s*)?" +
            "(?:(-?)(\\d+):(\\d+):(\\d+(?:\\.\\d+)?))?",
            Pattern.CASE_INSENSITIVE);

    // ISO 8601: P1Y2M3DT4H5M6.789S
    private static final Pattern ISO8601 = Pattern.compile(
            "P(?:(-?\\d+)Y)?(?:(-?\\d+)M)?(?:(-?\\d+)D)?" +
            "(?:T(?:(-?\\d+)H)?(?:(-?\\d+)M)?(?:(-?\\d+(?:\\.\\d+)?)S)?)?",
            Pattern.CASE_INSENSITIVE);

    /**
     * Parse a PostgreSQL interval string.
     * Supports postgres style ("1 year 2 mons 3 days 04:05:06"),
     * ISO 8601 ("P1Y2M3DT4H5M6S"), and simple numeric forms.
     */
    public static PgInterval parse(String s) {
        if (s == null || s.isBlank()) return ZERO;
        String t = s.strip();

        if (t.startsWith("P") || t.startsWith("p")) {
            return parseIso(t);
        }
        return parseVerbose(t);
    }

    private static PgInterval parseIso(String s) {
        Matcher m = ISO8601.matcher(s);
        if (!m.matches()) throw new IllegalArgumentException("Invalid ISO 8601 interval: " + s);
        int years   = parseIntOr0(m.group(1));
        int months2 = parseIntOr0(m.group(2));
        int days    = parseIntOr0(m.group(3));
        long hours  = parseLongOr0(m.group(4));
        long mins   = parseLongOr0(m.group(5));
        double secs = parseDoubleOr0(m.group(6));
        long micros = hours * 3_600_000_000L + mins * 60_000_000L + Math.round(secs * 1_000_000L);
        return new PgInterval(years * 12 + months2, days, micros);
    }

    // Unit-keyword pattern: "1 year 2 months 3 days 4 hours 5 minutes 6 seconds"
    private static final Pattern UNIT_PAIR = Pattern.compile(
            "(-?\\d+(?:\\.\\d+)?)\\s+(years?|mons?|months?|days?|hours?|hrs?|minutes?|mins?|seconds?|secs?|milliseconds?|msecs?|microseconds?|usecs?)",
            Pattern.CASE_INSENSITIVE);

    private static PgInterval parseVerbose(String s) {
        // Handle "ago" suffix — negates the entire interval
        boolean ago = false;
        if (s.toLowerCase().endsWith("ago")) {
            ago = true;
            s = s.substring(0, s.length() - 3).strip();
        }

        // First try the output-format regex (e.g. "1 year 2 mons 04:05:06")
        Matcher m = VERBOSE.matcher(s);
        if (m.matches() && m.group(0) != null && !m.group(0).isBlank()) {
            int years   = parseIntOr0(m.group(1));
            int mons    = parseIntOr0(m.group(2));
            int days    = parseIntOr0(m.group(3));
            String sign = m.group(4);
            long hours  = parseLongOr0(m.group(5));
            long mins   = parseLongOr0(m.group(6));
            double secs = parseDoubleOr0(m.group(7));
            long timeMicros = hours * 3_600_000_000L + mins * 60_000_000L + Math.round(secs * 1_000_000L);
            if ("-".equals(sign)) timeMicros = -timeMicros;
            // Check if we matched anything meaningful
            if (years != 0 || mons != 0 || days != 0 || timeMicros != 0) {
                int m2 = years * 12 + mons;
                return ago ? new PgInterval(-m2, -days, -timeMicros) : new PgInterval(m2, days, timeMicros);
            }
        }
        // Try unit-keyword parsing: "1 month", "2 hours 30 minutes", etc.
        Matcher um = UNIT_PAIR.matcher(s);
        int totalMonths = 0;
        int totalDays = 0;
        long totalMicros = 0;
        boolean found = false;
        while (um.find()) {
            found = true;
            double val = Double.parseDouble(um.group(1));
            String unit = um.group(2).toLowerCase();
            if (unit.startsWith("year"))        totalMonths += (int)(val * 12);
            else if (unit.startsWith("mon"))    totalMonths += (int) val;
            else if (unit.startsWith("day"))    totalDays   += (int) val;
            else if (unit.startsWith("hour") || unit.startsWith("hr"))
                                                totalMicros += Math.round(val * 3_600_000_000L);
            else if (unit.startsWith("minute") || unit.startsWith("min"))
                                                totalMicros += Math.round(val * 60_000_000L);
            else if (unit.startsWith("second") || unit.startsWith("sec"))
                                                totalMicros += Math.round(val * 1_000_000L);
            else if (unit.startsWith("millisecond") || unit.startsWith("msec"))
                                                totalMicros += Math.round(val * 1_000L);
            else if (unit.startsWith("microsecond") || unit.startsWith("usec"))
                                                totalMicros += Math.round(val);
        }
        if (found) return ago ? new PgInterval(-totalMonths, -totalDays, -totalMicros)
                : new PgInterval(totalMonths, totalDays, totalMicros);
        // Try as pure HH:MM:SS
        if (s.contains(":")) {
            Matcher hm = Pattern.compile("(-?)(\\d+):(\\d+)(?::(\\d+(?:\\.\\d+)?))?").matcher(s);
            if (hm.matches()) {
                long h = Long.parseLong(hm.group(2));
                long mn = Long.parseLong(hm.group(3));
                double sc = hm.group(4) != null ? Double.parseDouble(hm.group(4)) : 0;
                long mic = h * 3_600_000_000L + mn * 60_000_000L + Math.round(sc * 1_000_000L);
                if ("-".equals(hm.group(1))) mic = -mic;
                return ago ? new PgInterval(0, 0, -mic) : new PgInterval(0, 0, mic);
            }
        }
        throw new IllegalArgumentException("Invalid interval: " + s);
    }

    private static int    parseIntOr0(String s)    { return s == null ? 0 : Integer.parseInt(s); }
    private static long   parseLongOr0(String s)   { return s == null ? 0L : Long.parseLong(s); }
    private static double parseDoubleOr0(String s) { return s == null ? 0.0 : Double.parseDouble(s); }

    // -------------------------------------------------------------------------
    // Output — PostgreSQL "postgres" style

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        int y = months / 12;
        int mo = months % 12;
        if (y != 0) sb.append(y).append(y == 1 || y == -1 ? " year " : " years ");
        if (mo != 0) sb.append(mo).append(mo == 1 || mo == -1 ? " mon " : " mons ");
        if (days != 0) sb.append(days).append(days == 1 || days == -1 ? " day " : " days ");

        if (micros != 0 || sb.isEmpty()) {
            long us = micros;
            boolean neg = us < 0;
            if (neg) us = -us;
            long totalSecs = us / 1_000_000L;
            long fracUs    = us % 1_000_000L;
            long h = totalSecs / 3600;
            long mn = (totalSecs % 3600) / 60;
            long sc = totalSecs % 60;
            if (neg) sb.append('-');
            sb.append(String.format("%02d:%02d:%02d", h, mn, sc));
            if (fracUs != 0) sb.append(String.format(".%06d", fracUs).replaceAll("0+$", ""));
        }
        return sb.toString().strip();
    }
}
