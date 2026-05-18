package org.pgjava.storage;

import org.pgjava.types.PgCollation;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;

/**
 * A composite index key: an array of column values with lexicographic ordering.
 *
 * <p>Ordering rules:
 * <ul>
 *   <li>NULL sorts last (NULLS LAST — PostgreSQL default for ASC indexes).</li>
 *   <li>Numeric types are compared by value after promotion to {@link BigDecimal}.</li>
 *   <li>All other {@link Comparable} types are compared naturally.</li>
 *   <li>Columns at equal depth are compared left-to-right.</li>
 * </ul>
 *
 * <p>Used as the {@code TreeMap} key type in {@link BTreeIndex}.
 */
public final class IndexKey implements Comparable<IndexKey> {

    /** Shared comparator — same logic as compareTo but callable without construction. */
    public static final Comparator<IndexKey> COMPARATOR = Comparator.naturalOrder();

    private final Object[] values;

    public IndexKey(Object[] values) {
        this.values = values;
    }

    public Object[] values() { return values; }

    public Object get(int i) { return values[i]; }

    /**
     * Natural ordering — uses database default collation for string comparisons.
     * {@link BTreeIndex} uses a custom {@link Comparator} with the column's collation instead.
     */
    @Override
    public int compareTo(IndexKey other) {
        return compareTo(other, PgCollation.DEFAULT);
    }

    /** Compare using the specified collation for string values. */
    public int compareTo(IndexKey other, PgCollation collation) {
        int len = Math.min(values.length, other.values.length);
        for (int i = 0; i < len; i++) {
            int c = compareValues(values[i], other.values[i], collation);
            if (c != 0) return c;
        }
        return Integer.compare(values.length, other.values.length);
    }

    /**
     * Compare two index values with NULL-last semantics, using the given collation
     * for string comparisons.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compareValues(Object a, Object b, PgCollation collation) {
        if (a == null && b == null) return 0;
        if (a == null) return  1; // NULL last
        if (b == null) return -1; // NULL last

        // Numeric promotion: compare all numeric types via BigDecimal
        if (isNumeric(a) || isNumeric(b)) {
            BigDecimal da = toBigDecimal(a);
            BigDecimal db = toBigDecimal(b);
            return da.compareTo(db);
        }

        // Boolean
        if (a instanceof Boolean ba && b instanceof Boolean bb) {
            return Boolean.compare(ba, bb);
        }

        // byte[] (bytea): lexicographic on bytes
        if (a instanceof byte[] ba && b instanceof byte[] bb) {
            return Arrays.compare(ba, bb);
        }

        // Enum values — compare by ordinal (declaration order)
        if (a instanceof org.pgjava.types.EnumValue ea && b instanceof org.pgjava.types.EnumValue eb) {
            return ea.compareTo(eb);
        }
        if (a instanceof org.pgjava.types.EnumValue ea && b instanceof String sb) {
            return ea.compareToLabel(sb);
        }
        if (a instanceof String sa && b instanceof org.pgjava.types.EnumValue eb) {
            return -eb.compareToLabel(sa);
        }

        // String: use collation
        if (a instanceof String sa && b instanceof String sb) {
            return collation.compare(sa, sb);
        }

        // Temporal types (LocalDate, LocalDateTime, OffsetDateTime, etc.)
        if (a instanceof Comparable ca) {
            return ca.compareTo(b);
        }

        // Fall back to collation-aware string comparison
        return collation.compare(a.toString(), b.toString());
    }

    /** Convenience overload using database default collation. */
    public static int compareValues(Object a, Object b) {
        return compareValues(a, b, PgCollation.DEFAULT);
    }

    /** Build a comparator for IndexKeys that uses the given collation. */
    public static Comparator<IndexKey> comparator(PgCollation collation) {
        if (collation == null || collation.equals(PgCollation.DEFAULT)) return COMPARATOR;
        return (k1, k2) -> k1.compareTo(k2, collation);
    }

    private static boolean isNumeric(Object v) {
        return v instanceof Integer || v instanceof Long || v instanceof Short
                || v instanceof Float || v instanceof Double || v instanceof BigDecimal;
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Double d) return BigDecimal.valueOf(d);
        if (v instanceof Float f) return BigDecimal.valueOf(f);
        if (v instanceof Long l) return BigDecimal.valueOf(l);
        if (v instanceof Integer i) return BigDecimal.valueOf(i);
        if (v instanceof Short s) return BigDecimal.valueOf(s);
        return new BigDecimal(v.toString());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof IndexKey other)) return false;
        return Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }
}
