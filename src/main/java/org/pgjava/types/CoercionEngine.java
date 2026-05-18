package org.pgjava.types;

import org.pgjava.engine.PgErrorException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

/**
 * Handles type coercion between PostgreSQL types.
 *
 * <p>Mirrors PostgreSQL's cast catalog:
 * <ul>
 *   <li>Implicit casts: allowed in expressions without explicit CAST syntax.</li>
 *   <li>Assignment casts: allowed when inserting/updating column values.</li>
 *   <li>Explicit-only casts: require CAST(x AS T) or x::T.</li>
 * </ul>
 *
 * <p>Coercion errors use SQLSTATE:
 * <ul>
 *   <li>{@code 42804} — datatype mismatch (no cast path)</li>
 *   <li>{@code 22003} — numeric value out of range</li>
 *   <li>{@code 22P02} — invalid text representation</li>
 * </ul>
 */
public final class CoercionEngine {

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private CoercionEngine() {}

    // -------------------------------------------------------------------------
    // Implicit cast graph: fromOid → Set<toOid>
    // These are casts that are safe to do automatically in expressions.

    private static final Map<Integer, Set<Integer>> IMPLICIT_CASTS = Map.ofEntries(
            // Numeric widening
            e(PgOid.INT2,    Set.of(PgOid.INT4, PgOid.INT8, PgOid.FLOAT4, PgOid.FLOAT8, PgOid.NUMERIC)),
            e(PgOid.INT4,    Set.of(PgOid.INT8, PgOid.FLOAT4, PgOid.FLOAT8, PgOid.NUMERIC)),
            e(PgOid.INT8,    Set.of(PgOid.FLOAT4, PgOid.FLOAT8, PgOid.NUMERIC)),
            e(PgOid.FLOAT4,  Set.of(PgOid.FLOAT8)),
            // String widening
            e(PgOid.VARCHAR, Set.of(PgOid.TEXT, PgOid.BPCHAR)),
            e(PgOid.BPCHAR,  Set.of(PgOid.TEXT, PgOid.VARCHAR)),
            e(PgOid.NAME,    Set.of(PgOid.TEXT)),
            // Datetime widening
            e(PgOid.DATE,    Set.of(PgOid.TIMESTAMP, PgOid.TIMESTAMPTZ)),
            e(PgOid.TIMESTAMP, Set.of(PgOid.TIMESTAMPTZ)),
            e(PgOid.TIME,    Set.of(PgOid.TIMETZ)),
            // unknown coerces to anything (untyped string literal)
            e(PgOid.UNKNOWN, Set.of(PgOid.TEXT, PgOid.VARCHAR, PgOid.BPCHAR,
                    PgOid.NAME, PgOid.BOOL, PgOid.INT2, PgOid.INT4, PgOid.INT8,
                    PgOid.FLOAT4, PgOid.FLOAT8, PgOid.NUMERIC,
                    PgOid.DATE, PgOid.TIME, PgOid.TIMETZ,
                    PgOid.TIMESTAMP, PgOid.TIMESTAMPTZ, PgOid.INTERVAL,
                    PgOid.UUID, PgOid.JSON, PgOid.JSONB, PgOid.XML,
                    PgOid.BYTEA))
    );

    // Assignment casts extend implicit casts
    private static final Map<Integer, Set<Integer>> ASSIGNMENT_CASTS = Map.ofEntries(
            e(PgOid.INT2,    Set.of(PgOid.MONEY)),
            e(PgOid.INT4,    Set.of(PgOid.INT2, PgOid.MONEY)),     // narrowing with overflow check
            e(PgOid.INT8,    Set.of(PgOid.INT2, PgOid.INT4, PgOid.MONEY)),
            e(PgOid.FLOAT4,  Set.of(PgOid.INT2, PgOid.INT4, PgOid.INT8)),
            e(PgOid.FLOAT8,  Set.of(PgOid.INT2, PgOid.INT4, PgOid.INT8, PgOid.FLOAT4, PgOid.NUMERIC)),
            e(PgOid.NUMERIC, Set.of(PgOid.INT2, PgOid.INT4, PgOid.INT8, PgOid.FLOAT4, PgOid.FLOAT8)),
            e(PgOid.TEXT,    Set.of(PgOid.VARCHAR, PgOid.BPCHAR, PgOid.NAME)),
            e(PgOid.TIMESTAMP, Set.of(PgOid.DATE, PgOid.TIME))
    );

    private static Map.Entry<Integer, Set<Integer>> e(int k, Set<Integer> v) {
        return Map.entry(k, v);
    }

    // -------------------------------------------------------------------------
    // Cast feasibility checks

    /**
     * Returns true if a value of {@code fromOid} can be coerced to {@code toOid}
     * in the given context without error.
     */
    public static boolean canCoerce(int fromOid, int toOid, CoercionContext ctx) {
        if (fromOid == toOid) return true;
        if (fromOid == PgOid.UNKNOWN) return true; // unknown is universally coercible
        if (isImplicit(fromOid, toOid)) return true;
        if (ctx == CoercionContext.IMPLICIT) return false;
        if (isAssignment(fromOid, toOid)) return true;
        if (ctx == CoercionContext.ASSIGNMENT) return false;
        // EXPLICIT: any pair that has some value-level cast path
        return hasExplicitCast(fromOid, toOid);
    }

    private static boolean isImplicit(int from, int to) {
        Set<Integer> targets = IMPLICIT_CASTS.get(from);
        return targets != null && targets.contains(to);
    }

    private static boolean isAssignment(int from, int to) {
        Set<Integer> targets = ASSIGNMENT_CASTS.get(from);
        return targets != null && targets.contains(to);
    }

    private static boolean hasExplicitCast(int from, int to) {
        // Most numeric/text combinations have explicit casts in PG
        int[] numeric = {PgOid.INT2, PgOid.INT4, PgOid.INT8, PgOid.FLOAT4, PgOid.FLOAT8, PgOid.NUMERIC};
        int[] textual = {PgOid.TEXT, PgOid.VARCHAR, PgOid.BPCHAR, PgOid.NAME, PgOid.UNKNOWN};
        boolean fromNum = contains(numeric, from); boolean toNum = contains(numeric, to);
        boolean fromText = contains(textual, from); boolean toText = contains(textual, to);
        if (fromNum && toNum) return true;
        if (fromNum && toText) return true;
        if (fromText && toNum) return true;
        if (fromText && toText) return true;
        if (from == PgOid.BOOL && (to == PgOid.INT4 || toText)) return true;
        if ((from == PgOid.INT4 || fromText) && to == PgOid.BOOL) return true;
        if (from == PgOid.TIMESTAMP && to == PgOid.DATE) return true;
        if (from == PgOid.TIMESTAMP && to == PgOid.TIME) return true;
        if (from == PgOid.TIMESTAMPTZ && (to == PgOid.DATE || to == PgOid.TIME || to == PgOid.TIMESTAMP)) return true;
        return false;
    }

    private static boolean contains(int[] arr, int v) {
        for (int x : arr) if (x == v) return true;
        return false;
    }

    // -------------------------------------------------------------------------
    // Value-level coercion

    /**
     * Coerce {@code value} from {@code fromType} to {@code toType}.
     *
     * @param value     the value to coerce (may be null)
     * @param fromOid   source type OID
     * @param toOid     target type OID
     * @param ctx       coercion context
     * @return coerced value
     * @throws SQLException with SQLSTATE 42804, 22003, or 22P02 on failure
     */
    public static Object coerce(Object value, int fromOid, int toOid, CoercionContext ctx)
            throws SQLException {
        if (value == null) return null;
        if (fromOid == toOid) return value;
        if (!canCoerce(fromOid, toOid, ctx)) {
            String sqlState = (ctx == CoercionContext.EXPLICIT) ? "42846" : "42804";
            throw PgErrorException.error(sqlState,
                    "cannot cast type " + displayTypeName(fromOid) +
                    " to " + displayTypeName(toOid)).build();
        }
        return doCoerce(value, fromOid, toOid);
    }

    private static Object doCoerce(Object v, int from, int to) throws SQLException {
        // String input (unknown/text) → target type
        if (from == PgOid.UNKNOWN || from == PgOid.TEXT || from == PgOid.VARCHAR
                || from == PgOid.BPCHAR || from == PgOid.NAME) {
            return TypeInput.parse(v.toString(), to);
        }

        // Numeric widening / narrowing
        if (v instanceof Number n) {
            return switch (to) {
                case PgOid.INT2   -> { long r = Math.round(n.doubleValue()); rangeCheck(r, Short.MIN_VALUE, Short.MAX_VALUE, "smallint");   yield (int) r; }
                case PgOid.INT4   -> { long r = Math.round(n.doubleValue()); rangeCheck(r, Integer.MIN_VALUE, Integer.MAX_VALUE, "integer"); yield (int) r; }
                case PgOid.INT8   -> Math.round(n.doubleValue());
                case PgOid.FLOAT4 -> n.floatValue();
                case PgOid.FLOAT8 -> n.doubleValue();
                case PgOid.NUMERIC -> {
                    if (v instanceof BigDecimal bd) yield bd;
                    yield BigDecimal.valueOf(n.doubleValue());
                }
                case PgOid.BOOL   -> {
                    long l = n.longValue();
                    yield l != 0;
                }
                case PgOid.TEXT, PgOid.VARCHAR, PgOid.BPCHAR ->
                    TypeOutput.format(v, from);
                default -> throw PgErrorException.error("42846", "cannot cast type " + displayTypeName(from) + " to " + displayTypeName(to)).build();
            };
        }

        // Boolean
        if (v instanceof Boolean b) {
            return switch (to) {
                case PgOid.INT4   -> b ? 1 : 0;
                case PgOid.INT8   -> b ? 1L : 0L;
                case PgOid.TEXT, PgOid.VARCHAR -> b ? "true" : "false";
                default -> throw PgErrorException.error("42846", "cannot cast type boolean to " + displayTypeName(to)).build();
            };
        }

        // Date → timestamp
        if (v instanceof LocalDate d) {
            return switch (to) {
                case PgOid.TIMESTAMP   -> d.atStartOfDay();
                case PgOid.TIMESTAMPTZ -> d.atStartOfDay().atOffset(ZoneOffset.UTC);
                default -> throw PgErrorException.error("42846", "cannot cast type date to " + displayTypeName(to)).build();
            };
        }

        // Timestamp → timestamptz
        if (v instanceof LocalDateTime dt) {
            return switch (to) {
                case PgOid.TIMESTAMPTZ -> dt.atOffset(ZoneOffset.UTC);
                case PgOid.DATE        -> dt.toLocalDate();
                case PgOid.TIME        -> dt.toLocalTime();
                default -> throw PgErrorException.error("42846", "cannot cast type timestamp without time zone to " + displayTypeName(to)).build();
            };
        }

        // Timestamptz → timestamp (lose tz)
        if (v instanceof OffsetDateTime odt) {
            return switch (to) {
                case PgOid.TIMESTAMP -> odt.toLocalDateTime();
                case PgOid.DATE      -> odt.toLocalDate();
                case PgOid.TIME      -> odt.toLocalTime();
                default -> throw PgErrorException.error("42846", "cannot cast type timestamp with time zone to " + displayTypeName(to)).build();
            };
        }

        // Fallback: toString → parse
        return TypeInput.parse(TypeOutput.format(v, from), to);
    }

    private static void rangeCheck(long v, long min, long max, String typeName) throws SQLException {
        if (v < min || v > max)
            throw PgErrorException.error("22003", typeName + " out of range").build();
    }

    // -------------------------------------------------------------------------
    // Resolve common type for a list of OIDs (used by CASE, UNION, etc.)

    /**
     * Find a common type that all input OIDs can be implicitly coerced to.
     * Returns the most specific common ancestor in the implicit cast graph.
     * Returns {@code PgOid.TEXT} if all inputs are unknown.
     * Throws 42804 if no common type exists.
     */
    public static int resolveCommonType(int[] oids) throws SQLException {
        if (oids.length == 0) return PgOid.TEXT;
        int result = oids[0];
        for (int i = 1; i < oids.length; i++) {
            result = mergeTypes(result, oids[i]);
        }
        return result;
    }

    private static int mergeTypes(int a, int b) throws SQLException {
        if (a == b) return a;
        if (a == PgOid.UNKNOWN) return b;
        if (b == PgOid.UNKNOWN) return a;
        if (isImplicit(a, b)) return b;
        if (isImplicit(b, a)) return a;
        throw PgErrorException.error("42804",
                "types " + displayTypeName(a) + " and " + displayTypeName(b) + " cannot be matched").build();
    }

    /** Map OID to PG-standard display name for error messages. */
    static String displayTypeName(int oid) {
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
            case PgOid.UNKNOWN     -> "unknown";
            default -> {
                PgType t = REG.byOid(oid);
                yield t != null ? t.name() : String.valueOf(oid);
            }
        };
    }
}
