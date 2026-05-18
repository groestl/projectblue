package org.pgjava.types;

import java.sql.SQLException;
import java.text.Collator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a named collation for string comparisons.
 *
 * <p>PostgreSQL supports three levels of collation:
 * <ol>
 *   <li><b>Database default</b> — set at {@code CREATE DATABASE} via {@code LC_COLLATE}.</li>
 *   <li><b>Column-level</b> — {@code CREATE TABLE t (name text COLLATE "C")}.</li>
 *   <li><b>Expression-level</b> — {@code ORDER BY name COLLATE "en_US"}.</li>
 * </ol>
 *
 * <p>Each level overrides the one above it. This class is instantiable (not a
 * static utility) so that different databases, columns, and expressions can
 * each carry their own collation.
 *
 * <p>Well-known collations:
 * <ul>
 *   <li>{@link #C} / {@link #POSIX} — byte-order comparison ({@link String#compareTo}).</li>
 *   <li>{@link #DEFAULT} — {@code en_US.UTF-8} locale, matching PostgreSQL's typical default.</li>
 * </ul>
 */
public final class PgCollation {

    private final String   name;
    private final Collator collator; // null for C/POSIX (byte-order)

    // Well-known instances
    public static final PgCollation C       = new PgCollation("C", null);
    public static final PgCollation POSIX   = new PgCollation("POSIX", null);
    public static final PgCollation DEFAULT;

    static {
        Collator c = Collator.getInstance(Locale.US);
        c.setStrength(Collator.TERTIARY); // case-sensitive, accent-aware, locale-ordered
        DEFAULT = new PgCollation("default", c);
    }

    // Cache for locale-based collations
    private static final Map<String, PgCollation> CACHE = new ConcurrentHashMap<>();

    static {
        CACHE.put("c", C);
        CACHE.put("posix", POSIX);
        CACHE.put("default", DEFAULT);
        CACHE.put("en_us", DEFAULT);         // en_US maps to DEFAULT (Locale.US)
        CACHE.put("en_us.utf-8", DEFAULT);
        CACHE.put("en_us.utf8", DEFAULT);
        CACHE.put("ucs_basic", C);           // PG alias for C ordering
    }

    private PgCollation(String name, Collator collator) {
        this.name     = name;
        this.collator = collator;
    }

    /** Create a collation backed by the given Java {@link Locale}. */
    public static PgCollation forLocale(String name, Locale locale) {
        Collator c = Collator.getInstance(locale);
        c.setStrength(Collator.TERTIARY);
        return new PgCollation(name, c);
    }

    /**
     * Resolve a collation name to a {@link PgCollation} instance.
     *
     * @param name collation name (case-insensitive), e.g. "C", "POSIX", "en_US", "default"
     * @return the collation, or {@code null} if not recognized
     */
    public static PgCollation resolve(String name) {
        if (name == null) return null;
        return CACHE.get(name.toLowerCase().replace("\"", ""));
    }

    /**
     * Resolve a collation name, throwing 42704 if not found.
     */
    public static PgCollation resolveOrError(String name) throws SQLException {
        PgCollation coll = resolve(name);
        if (coll == null) {
            throw new SQLException(
                    "collation \"" + name + "\" for encoding \"UTF8\" does not exist",
                    "42704");
        }
        return coll;
    }

    /**
     * Resolve collation conflict between two operands (e.g. in a comparison).
     *
     * <p>Rules (matching PostgreSQL):
     * <ul>
     *   <li>If both are null (implicit) → use the provided default.</li>
     *   <li>If one is explicit and the other implicit → use the explicit one.</li>
     *   <li>If both are explicit and equal → use either.</li>
     *   <li>If both are explicit and different → error 42P21.</li>
     * </ul>
     *
     * @param a       left operand's collation (null = implicit/inherited)
     * @param b       right operand's collation (null = implicit/inherited)
     * @param fallback default collation if both are null
     * @return the resolved collation
     * @throws SQLException 42P21 if conflicting explicit collations
     */
    public static PgCollation resolveConflict(PgCollation a, PgCollation b,
                                               PgCollation fallback) throws SQLException {
        if (a == null && b == null) return fallback;
        if (a == null) return b;
        if (b == null) return a;
        if (a.name.equalsIgnoreCase(b.name)) return a;
        throw new SQLException(
                "collation mismatch between \"" + a.name + "\" and \"" + b.name + "\"",
                "42P21");
    }

    /**
     * Compare two strings using this collation's ordering.
     *
     * <p>For {@code C}/{@code POSIX}, uses {@link String#compareTo} (byte order).
     * For locale-based collations, uses {@link Collator#compare}.
     */
    public int compare(String a, String b) {
        if (collator == null) return a.compareTo(b); // C/POSIX = byte order
        return collator.compare(a, b);
    }

    public String name() { return name; }

    @Override
    public String toString() { return "PgCollation[" + name + "]"; }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PgCollation other)) return false;
        return name.equalsIgnoreCase(other.name);
    }

    @Override
    public int hashCode() {
        return name.toLowerCase().hashCode();
    }
}
