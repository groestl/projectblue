package org.pgjava.types;

import org.pgjava.engine.PgErrorException;

import java.sql.SQLException;
import java.util.Objects;

/**
 * Represents a PostgreSQL range value: {@code [lower,upper]}, {@code (lower,upper)},
 * {@code empty}, or any mix of inclusive/exclusive/infinite bounds.
 *
 * <p>Bounds:
 * <ul>
 *   <li>{@code lowerInf} — lower bound is {@code -infinity} (unbounded below)</li>
 *   <li>{@code upperInf} — upper bound is {@code +infinity} (unbounded above)</li>
 *   <li>{@code lowerInc} — lower bound is inclusive ({@code [})</li>
 *   <li>{@code upperInc} — upper bound is inclusive ({@code ]})</li>
 *   <li>{@code empty}    — the empty range (no elements)</li>
 * </ul>
 *
 * <p>Element values are stored as the appropriate Java type:
 * int4range → {@link Integer}, int8range → {@link Long}, numrange → {@link java.math.BigDecimal},
 * tsrange/tstzrange → {@link java.time.LocalDateTime}/{@link java.time.OffsetDateTime},
 * daterange → {@link java.time.LocalDate}.
 *
 * <p>Serialization: {@link #toString()} produces the canonical PG text format,
 * e.g. {@code [1,5)}, {@code (,10]}, {@code empty}.
 */
public final class PgRange {

    public static final PgRange EMPTY = new PgRange(true, null, null, false, false, false, false);

    private final boolean empty;
    private final Object  lower;
    private final Object  upper;
    private final boolean lowerInc;
    private final boolean upperInc;
    private final boolean lowerInf;
    private final boolean upperInf;

    private PgRange(boolean empty,
                    Object lower, Object upper,
                    boolean lowerInc, boolean upperInc,
                    boolean lowerInf, boolean upperInf) {
        this.empty    = empty;
        this.lower    = lower;
        this.upper    = upper;
        this.lowerInc = lowerInc;
        this.upperInc = upperInc;
        this.lowerInf = lowerInf;
        this.upperInf = upperInf;
    }

    public static PgRange of(Object lower, Object upper,
                             boolean lowerInc, boolean upperInc) {
        return new PgRange(false, lower, upper, lowerInc, upperInc,
                lower == null, upper == null);
    }

    /**
     * Canonicalize a discrete range (int4range, int8range, daterange).
     * Converts to [lower, upper) form per PostgreSQL semantics.
     */
    public PgRange canonicalize() {
        if (empty || (lowerInf() && upperInf())) return this;
        Object lo = lower;
        Object up = upper;
        boolean loInc = lowerInc;
        boolean upInc = upperInc;
        // Canonicalize lower bound: exclusive → add 1, set inclusive
        if (lo != null && !loInc) {
            lo = increment(lo);
            if (lo != null) loInc = true;
        }
        // Canonicalize upper bound: inclusive → add 1, set exclusive
        if (up != null && upInc) {
            up = increment(up);
            if (up != null) upInc = false;
        }
        PgRange result = new PgRange(false, lo, up, loInc, upInc,
                lo == null, up == null);
        return result.isEmpty() ? EMPTY : result;
    }

    private static Object increment(Object v) {
        if (v instanceof Integer i)  return i + 1;
        if (v instanceof Long l)     return l + 1;
        if (v instanceof java.time.LocalDate d) return d.plusDays(1);
        return null; // not a discrete type — can't canonicalize
    }

    public static PgRange ofEmpty() { return EMPTY; }

    // -------------------------------------------------------------------------
    // Accessors

    public boolean isEmpty() {
        if (empty) return true;
        // Check for effectively empty ranges (e.g. [3,3) or (3,3])
        if (!lowerInf() && !upperInf() && lower != null && upper != null) {
            @SuppressWarnings("unchecked")
            int cmp = ((Comparable<Object>) lower).compareTo(upper);
            if (cmp > 0) return true;
            if (cmp == 0 && (!lowerInc || !upperInc)) return true;
        }
        return false;
    }
    public Object  lower()     { return lower; }
    public Object  upper()     { return upper; }
    public boolean lowerInc()  { return !lowerInf && lowerInc; }
    public boolean upperInc()  { return !upperInf && upperInc; }
    public boolean lowerInf()  { return lowerInf || lower == null; }
    public boolean upperInf()  { return upperInf || upper == null; }

    // -------------------------------------------------------------------------
    // Text format parsing

    /**
     * Parse a PostgreSQL range literal such as {@code [1,5)}, {@code (,10]},
     * or {@code empty}, given a function to parse individual bound values.
     *
     * @param text       the range literal (with or without surrounding whitespace)
     * @param parseValue converts a bound string to its typed value
     */
    public static PgRange parse(String text, BoundParser parseValue) throws SQLException {
        String s = text.strip();
        if (s.equalsIgnoreCase("empty")) return EMPTY;

        if (s.length() < 2)
            throw PgErrorException.error("22P02", "malformed range literal: \"" + text + "\"").build();

        char first = s.charAt(0);
        char last  = s.charAt(s.length() - 1);

        boolean lowerInc = (first == '[');
        boolean upperInc = (last  == ']');

        if (first != '[' && first != '(')
            throw PgErrorException.error("22P02", "malformed range literal: \"" + text + "\"")
                    .detail("Missing lower bound.").build();
        if (last != ']' && last != ')')
            throw PgErrorException.error("22P02", "malformed range literal: \"" + text + "\"")
                    .detail("Missing upper bound.").build();

        String inner = s.substring(1, s.length() - 1);

        // Split at the comma, handling quoted values
        int commaIdx = findComma(inner);
        if (commaIdx < 0)
            throw PgErrorException.error("22P02", "malformed range literal: \"" + text + "\"")
                    .detail("Missing comma.").build();

        String lowerStr = inner.substring(0, commaIdx).strip();
        String upperStr = inner.substring(commaIdx + 1).strip();

        Object lowerVal = lowerStr.isEmpty() ? null : parseValue.parse(lowerStr);
        Object upperVal = upperStr.isEmpty() ? null : parseValue.parse(upperStr);

        PgRange result = new PgRange(false, lowerVal, upperVal, lowerInc, upperInc,
                lowerVal == null, upperVal == null);
        // Canonicalize discrete ranges (integer, date bounds)
        if (lowerVal instanceof Integer || lowerVal instanceof Long
                || lowerVal instanceof java.time.LocalDate
                || upperVal instanceof Integer || upperVal instanceof Long
                || upperVal instanceof java.time.LocalDate) {
            result = result.canonicalize();
        }
        return result;
    }

    /** Finds the comma separating the two bounds (not inside quotes). */
    private static int findComma(String s) {
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inQuote = !inQuote;
            else if (c == ',' && !inQuote) return i;
        }
        return -1;
    }

    @FunctionalInterface
    public interface BoundParser {
        Object parse(String s) throws SQLException;
    }

    // -------------------------------------------------------------------------
    // Operators

    /**
     * Returns true if {@code element} is contained in this range.
     * The element must be {@link Comparable} and of the same type as the bounds.
     */
    @SuppressWarnings("unchecked")
    public boolean contains(Object element) {
        if (empty || element == null) return false;
        Comparable<Object> el = (Comparable<Object>) element;
        if (!lowerInf()) {
            int cmp = el.compareTo(lower);
            if (lowerInc() ? cmp < 0 : cmp <= 0) return false;
        }
        if (!upperInf()) {
            int cmp = el.compareTo(upper);
            if (upperInc() ? cmp > 0 : cmp >= 0) return false;
        }
        return true;
    }

    /** Returns true if {@code other} is fully contained in this range. */
    public boolean containsRange(PgRange other) {
        if (empty) return other.empty;
        if (other.empty) return true;
        return lowerLE(this, other) && upperGE(this, other);
    }

    /** Returns true if this range overlaps {@code other} (they share at least one point). */
    public boolean overlaps(PgRange other) {
        if (empty || other.empty) return false;
        // They don't overlap iff one ends before the other starts
        return !strictlyBefore(this, other) && !strictlyBefore(other, this);
    }

    // -------------------------------------------------------------------------
    // range_merge: smallest range containing both

    public static PgRange merge(PgRange a, PgRange b) {
        if (a.empty) return b;
        if (b.empty) return a;
        // Pick the lesser lower bound and greater upper bound
        Object newLower; boolean newLowerInc; boolean newLowerInf;
        if (a.lowerInf() || b.lowerInf()) {
            newLower = null; newLowerInc = false; newLowerInf = true;
        } else {
            int cmp = compareValues(a.lower, b.lower);
            if (cmp < 0) { newLower = a.lower; newLowerInc = a.lowerInc(); newLowerInf = false; }
            else if (cmp > 0) { newLower = b.lower; newLowerInc = b.lowerInc(); newLowerInf = false; }
            else { newLower = a.lower; newLowerInc = a.lowerInc() || b.lowerInc(); newLowerInf = false; }
        }
        Object newUpper; boolean newUpperInc; boolean newUpperInf;
        if (a.upperInf() || b.upperInf()) {
            newUpper = null; newUpperInc = false; newUpperInf = true;
        } else {
            int cmp = compareValues(a.upper, b.upper);
            if (cmp > 0) { newUpper = a.upper; newUpperInc = a.upperInc(); newUpperInf = false; }
            else if (cmp < 0) { newUpper = b.upper; newUpperInc = b.upperInc(); newUpperInf = false; }
            else { newUpper = a.upper; newUpperInc = a.upperInc() || b.upperInc(); newUpperInf = false; }
        }
        return new PgRange(false, newLower, newUpper, newLowerInc, newUpperInc,
                newLowerInf, newUpperInf);
    }

    // -------------------------------------------------------------------------
    // Comparison helpers

    @SuppressWarnings("unchecked")
    private static int compareValues(Object a, Object b) {
        return ((Comparable<Object>) a).compareTo(b);
    }

    /** True if a's lower bound ≤ b's lower bound. */
    private static boolean lowerLE(PgRange a, PgRange b) {
        if (a.lowerInf()) return true;
        if (b.lowerInf()) return false;
        int cmp = compareValues(a.lower, b.lower);
        if (cmp < 0) return true;
        if (cmp > 0) return false;
        // same value: [lower ≤ (lower (inclusive ≤ exclusive)
        return a.lowerInc() || !b.lowerInc();
    }

    /** True if a's upper bound ≥ b's upper bound. */
    private static boolean upperGE(PgRange a, PgRange b) {
        if (a.upperInf()) return true;
        if (b.upperInf()) return false;
        int cmp = compareValues(a.upper, b.upper);
        if (cmp > 0) return true;
        if (cmp < 0) return false;
        return a.upperInc() || !b.upperInc();
    }

    /** True if a ends strictly before b starts. */
    private static boolean strictlyBefore(PgRange a, PgRange b) {
        if (a.upperInf() || b.lowerInf()) return false;
        if (a.upper == null || b.lower == null) return false;
        int cmp = compareValues(a.upper, b.lower);
        if (cmp < 0) return true;
        if (cmp > 0) return false;
        // touching: [1,3) and [3,5) — not overlapping
        return !a.upperInc() || !b.lowerInc();
    }

    // -------------------------------------------------------------------------
    // equals / hashCode / toString

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof PgRange o)) return false;
        if (empty && o.empty) return true;
        if (empty != o.empty) return false;
        return lowerInf() == o.lowerInf() && upperInf() == o.upperInf()
                && lowerInc() == o.lowerInc() && upperInc() == o.upperInc()
                && Objects.equals(lower, o.lower)
                && Objects.equals(upper, o.upper);
    }

    @Override
    public int hashCode() {
        return empty ? 0 : Objects.hash(lower, upper, lowerInc(), upperInc(), lowerInf(), upperInf());
    }

    @Override
    public String toString() {
        if (empty) return "empty";
        StringBuilder sb = new StringBuilder();
        sb.append(lowerInc() ? '[' : '(');
        if (!lowerInf() && lower != null) sb.append(lower);
        sb.append(',');
        if (!upperInf() && upper != null) sb.append(upper);
        sb.append(upperInc() ? ']' : ')');
        return sb.toString();
    }
}
