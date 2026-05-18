package org.pgjava.catalog;

import org.pgjava.engine.PgErrorException;
import org.pgjava.types.JsonOps;
import org.pgjava.types.PgInterval;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgRange;
import org.pgjava.types.PgType;
import org.pgjava.types.PgTypeRegistry;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Registers all built-in scalar and aggregate functions into a {@link FunctionRegistry}.
 *
 * <p>Phase 5b: minimal set needed for catalog queries, JDBC metadata, and common SQL idioms.
 * The expression evaluator (Phase 8) will call these implementations.
 *
 * <p>Built-in OIDs use real PostgreSQL function OIDs where possible, but since the executor
 * looks up functions by name+arity, OID uniqueness is more important than exact PG OID values
 * for Phase 5b.
 */
public final class BuiltinFunctions {

    private BuiltinFunctions() {}

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private static PgType t(int oid) { return REG.byOid(oid); }

    // -------------------------------------------------------------------------
    // OIDs (real PG OIDs for common built-ins)

    private static long nextOid = 100L; // well below user OID range; real OIDs for named fns below

    private static long oid(long pgOid) { return pgOid; }

    // -------------------------------------------------------------------------

    public static void registerAll(FunctionRegistry reg) {
        registerDatetime(reg);
        registerComparison(reg);
        registerString(reg);
        registerMath(reg);
        registerAggregates(reg);
        registerMisc(reg);
        registerRangeFunctions(reg);
        registerJsonFunctions(reg);
        registerSrfs(reg);
    }

    // -------------------------------------------------------------------------
    // Datetime

    private static void registerDatetime(FunctionRegistry reg) {
        // now() → timestamptz
        reg.register(new FunctionDef(
                1299L, "now", "pg_catalog", List.of(), t(PgOid.TIMESTAMPTZ),
                false, false,
                args -> OffsetDateTime.now(ZoneOffset.UTC)
        ));
        // current_timestamp → timestamptz (same as now())
        reg.register(new FunctionDef(
                1300L, "current_timestamp", "pg_catalog", List.of(), t(PgOid.TIMESTAMPTZ),
                false, false,
                args -> OffsetDateTime.now(ZoneOffset.UTC)
        ));
        // current_date → date
        reg.register(new FunctionDef(
                1301L, "current_date", "pg_catalog", List.of(), t(PgOid.DATE),
                false, false,
                args -> LocalDate.now(ZoneOffset.UTC)
        ));
        // current_time → timetz
        reg.register(new FunctionDef(
                1302L, "current_time", "pg_catalog", List.of(), t(PgOid.TIMETZ),
                false, false,
                args -> OffsetTime.now(ZoneOffset.UTC)
        ));
        // clock_timestamp() → timestamptz (non-deterministic, same impl here)
        reg.register(new FunctionDef(
                2647L, "clock_timestamp", "pg_catalog", List.of(), t(PgOid.TIMESTAMPTZ),
                false, false,
                args -> OffsetDateTime.now(ZoneOffset.UTC)
        ));
        // statement_timestamp() → timestamptz
        reg.register(new FunctionDef(
                2648L, "statement_timestamp", "pg_catalog", List.of(), t(PgOid.TIMESTAMPTZ),
                false, false,
                args -> OffsetDateTime.now(ZoneOffset.UTC)
        ));
        // transaction_timestamp() → timestamptz
        reg.register(new FunctionDef(
                2649L, "transaction_timestamp", "pg_catalog", List.of(), t(PgOid.TIMESTAMPTZ),
                false, false,
                args -> OffsetDateTime.now(ZoneOffset.UTC)
        ));
        // extract(text, date/timestamp) → float8
        // EXTRACT(field FROM source) is parsed as FunctionCall("extract", [field_str, source])
        reg.register(new FunctionDef(
                2650L, "extract", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.ANY)), t(PgOid.FLOAT8),
                true, false,
                args -> {
                    String field = args[0].toString().toLowerCase().strip();
                    Object src = args[1];
                    if (src instanceof LocalDate d) {
                        return switch (field) {
                            case "year"    -> (double) d.getYear();
                            case "month"   -> (double) d.getMonthValue();
                            case "day"     -> (double) d.getDayOfMonth();
                            case "dow"     -> (double) (d.getDayOfWeek().getValue() % 7);
                            case "isodow"  -> (double) d.getDayOfWeek().getValue();
                            case "doy"     -> (double) d.getDayOfYear();
                            case "quarter" -> (double) ((d.getMonthValue() - 1) / 3 + 1);
                            case "week"    -> (double) d.get(WeekFields.ISO.weekOfWeekBasedYear());
                            case "epoch"   -> (double) (d.toEpochDay() * 86400L);
                            case "decade"  -> (double) (d.getYear() / 10);
                            case "century" -> (double) ((d.getYear() - 1) / 100 + 1);
                            default -> throw new RuntimeException("extract field not supported: " + field);
                        };
                    }
                    if (src instanceof LocalDateTime dt) {
                        return switch (field) {
                            case "year"    -> (double) dt.getYear();
                            case "month"   -> (double) dt.getMonthValue();
                            case "day"     -> (double) dt.getDayOfMonth();
                            case "hour"    -> (double) dt.getHour();
                            case "minute"  -> (double) dt.getMinute();
                            case "second"  -> dt.getSecond() + dt.getNano() / 1e9;
                            case "dow"     -> (double) (dt.getDayOfWeek().getValue() % 7);
                            case "doy"     -> (double) dt.getDayOfYear();
                            case "quarter" -> (double) ((dt.getMonthValue() - 1) / 3 + 1);
                            case "epoch"   -> (double) dt.toEpochSecond(ZoneOffset.UTC) + dt.getNano() / 1e9;
                            default -> throw new RuntimeException("extract field not supported: " + field);
                        };
                    }
                    if (src instanceof OffsetDateTime dt) {
                        return switch (field) {
                            case "year"    -> (double) dt.getYear();
                            case "month"   -> (double) dt.getMonthValue();
                            case "day"     -> (double) dt.getDayOfMonth();
                            case "hour"    -> (double) dt.getHour();
                            case "minute"  -> (double) dt.getMinute();
                            case "second"  -> dt.getSecond() + dt.getNano() / 1e9;
                            case "dow"     -> (double) (dt.getDayOfWeek().getValue() % 7);
                            case "doy"     -> (double) dt.getDayOfYear();
                            case "quarter" -> (double) ((dt.getMonthValue() - 1) / 3 + 1);
                            case "epoch"   -> (double) dt.toEpochSecond() + dt.getNano() / 1e9;
                            default -> throw new RuntimeException("extract field not supported: " + field);
                        };
                    }
                    if (src instanceof org.pgjava.types.PgInterval iv) {
                        return switch (field) {
                            case "year"    -> (double) (iv.months() / 12);
                            case "month"   -> (double) (iv.months() % 12);
                            case "day"     -> (double) iv.days();
                            case "hour"    -> (double) (iv.micros() / 3_600_000_000L);
                            case "minute"  -> (double) ((iv.micros() % 3_600_000_000L) / 60_000_000L);
                            case "second"  -> (iv.micros() % 60_000_000L) / 1_000_000.0;
                            case "epoch"   -> iv.months() * 2_592_000.0 + iv.days() * 86400.0 + iv.micros() / 1_000_000.0;
                            default -> throw new RuntimeException("extract field not supported for interval: " + field);
                        };
                    }
                    if (src instanceof LocalTime lt) {
                        return switch (field) {
                            case "hour"   -> (double) lt.getHour();
                            case "minute" -> (double) lt.getMinute();
                            case "second" -> lt.getSecond() + lt.getNano() / 1e9;
                            case "epoch"  -> (double) (lt.toSecondOfDay()) + lt.getNano() / 1e9;
                            default -> throw new RuntimeException("extract field not supported for time: " + field);
                        };
                    }
                    throw new RuntimeException("extract: unsupported source type " + src.getClass().getSimpleName());
                }
        ));
        // date_part(text, date/timestamp) → float8 — synonym for extract used by some clients
        reg.register(new FunctionDef(
                2651L, "date_part", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.ANY)), t(PgOid.FLOAT8),
                true, false,
                args -> {
                    String field = args[0].toString().toLowerCase().strip();
                    Object src = args[1];
                    if (src instanceof LocalDate d) {
                        return switch (field) {
                            case "year"  -> (double) d.getYear();
                            case "month" -> (double) d.getMonthValue();
                            case "day"   -> (double) d.getDayOfMonth();
                            case "epoch" -> (double) (d.toEpochDay() * 86400L);
                            default -> throw new RuntimeException("date_part field not supported: " + field);
                        };
                    }
                    throw new RuntimeException("date_part: unsupported source type");
                }
        ));
    }

    // -------------------------------------------------------------------------
    // Comparison / conditional

    private static void registerComparison(FunctionRegistry reg) {
        // coalesce(variadic "any") → anyelement — variadic, non-strict
        reg.register(new FunctionDef(
                2725L, "coalesce", "pg_catalog",
                List.of(t(PgOid.ANY)),
                t(PgOid.ANY),
                false, true,
                args -> {
                    for (Object a : args) if (a != null) return a;
                    return null;
                }
        ));
        // nullif(anyelement, anyelement) → anyelement
        reg.register(new FunctionDef(
                2726L, "nullif", "pg_catalog",
                List.of(t(PgOid.ANY), t(PgOid.ANY)),
                t(PgOid.ANY),
                false, false,
                args -> {
                    Object a = args[0], b = args[1];
                    if (a == null) return null;
                    return a.equals(b) ? null : a;
                }
        ));
        // greatest(variadic "any") → anyelement
        reg.register(new FunctionDef(
                2726L + 1, "greatest", "pg_catalog",
                List.of(t(PgOid.ANY)),
                t(PgOid.ANY),
                false, true,
                args -> {
                    Object max = null;
                    for (Object a : args) {
                        if (a == null) continue;
                        if (max == null || compareObjects(a, max) > 0) max = a;
                    }
                    return max;
                }
        ));
        // least(variadic "any") → anyelement
        reg.register(new FunctionDef(
                2727L + 1, "least", "pg_catalog",
                List.of(t(PgOid.ANY)),
                t(PgOid.ANY),
                false, true,
                args -> {
                    Object min = null;
                    for (Object a : args) {
                        if (a == null) continue;
                        if (min == null || compareObjects(a, min) < 0) min = a;
                    }
                    return min;
                }
        ));
    }

    @SuppressWarnings("unchecked")
    private static int compareObjects(Object a, Object b) {
        // Unwrap CollatedValue and resolve collation
        org.pgjava.types.PgCollation aColl = org.pgjava.executor.CollatedValue.collationOf(a);
        org.pgjava.types.PgCollation bColl = org.pgjava.executor.CollatedValue.collationOf(b);
        a = org.pgjava.executor.CollatedValue.unwrap(a);
        b = org.pgjava.executor.CollatedValue.unwrap(b);
        if (a instanceof String sa && b instanceof String sb) {
            try {
                org.pgjava.types.PgCollation coll =
                        org.pgjava.types.PgCollation.resolveConflict(aColl, bColl, org.pgjava.types.PgCollation.DEFAULT);
                return coll.compare(sa, sb);
            } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
        }
        if (a instanceof Comparable c) return c.compareTo(b);
        try {
            org.pgjava.types.PgCollation coll =
                    org.pgjava.types.PgCollation.resolveConflict(aColl, bColl, org.pgjava.types.PgCollation.DEFAULT);
            return coll.compare(a.toString(), b.toString());
        } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
    }

    // -------------------------------------------------------------------------
    // String functions

    private static void registerString(FunctionRegistry reg) {
        // length(text) → int4
        reg.register(new FunctionDef(
                1317L, "length", "pg_catalog",
                List.of(t(PgOid.TEXT)), t(PgOid.INT4),
                true, false,
                args -> ((String) args[0]).length()
        ));
        // char_length(text) → int4
        reg.register(new FunctionDef(
                1372L, "char_length", "pg_catalog",
                List.of(t(PgOid.TEXT)), t(PgOid.INT4),
                true, false,
                args -> ((String) args[0]).length()
        ));
        // character_length(text) → int4
        reg.register(new FunctionDef(
                1373L, "character_length", "pg_catalog",
                List.of(t(PgOid.TEXT)), t(PgOid.INT4),
                true, false,
                args -> ((String) args[0]).length()
        ));
        // lower(text) → text
        reg.register(new FunctionDef(
                870L, "lower", "pg_catalog",
                List.of(t(PgOid.TEXT)), t(PgOid.TEXT),
                true, false,
                args -> ((String) args[0]).toLowerCase()
        ));
        // upper(text) → text
        reg.register(new FunctionDef(
                871L, "upper", "pg_catalog",
                List.of(t(PgOid.TEXT)), t(PgOid.TEXT),
                true, false,
                args -> ((String) args[0]).toUpperCase()
        ));
        // trim(text) → text  (btrim)
        reg.register(new FunctionDef(
                885L, "btrim", "pg_catalog",
                List.of(t(PgOid.TEXT)), t(PgOid.TEXT),
                true, false,
                args -> ((String) args[0]).strip()
        ));
        reg.register(new FunctionDef(
                885L + 1, "trim", "pg_catalog",
                List.of(t(PgOid.TEXT)), t(PgOid.TEXT),
                true, false,
                args -> ((String) args[0]).strip()
        ));
        // ltrim(text) → text
        reg.register(new FunctionDef(
                875L, "ltrim", "pg_catalog",
                List.of(t(PgOid.TEXT)), t(PgOid.TEXT),
                true, false,
                args -> ((String) args[0]).stripLeading()
        ));
        // rtrim(text) → text
        reg.register(new FunctionDef(
                876L, "rtrim", "pg_catalog",
                List.of(t(PgOid.TEXT)), t(PgOid.TEXT),
                true, false,
                args -> ((String) args[0]).stripTrailing()
        ));
        // substr(text, int4) → text
        reg.register(new FunctionDef(
                936L, "substr", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.INT4)), t(PgOid.TEXT),
                true, false,
                args -> {
                    String s = (String) args[0];
                    int start = toInt(args[1]) - 1; // PG is 1-based
                    if (start < 0) start = 0;
                    return start >= s.length() ? "" : s.substring(start);
                }
        ));
        // substr(text, int4, int4) → text
        reg.register(new FunctionDef(
                937L, "substr", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.INT4), t(PgOid.INT4)), t(PgOid.TEXT),
                true, false,
                args -> {
                    String s = (String) args[0];
                    int start = toInt(args[1]) - 1;
                    int len   = toInt(args[2]);
                    if (start < 0) { len += start; start = 0; }
                    if (len <= 0 || start >= s.length()) return "";
                    int end = Math.min(start + len, s.length());
                    return s.substring(start, end);
                }
        ));
        // substring(text, int4) — alias for substr
        reg.register(new FunctionDef(
                938L, "substring", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.INT4)), t(PgOid.TEXT),
                true, false,
                args -> {
                    String s = (String) args[0];
                    int start = toInt(args[1]) - 1;
                    if (start < 0) start = 0;
                    return start >= s.length() ? "" : s.substring(start);
                }
        ));
        // substring(text, int4, int4) — alias for substr
        reg.register(new FunctionDef(
                939L, "substring", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.INT4), t(PgOid.INT4)), t(PgOid.TEXT),
                true, false,
                args -> {
                    String s = (String) args[0];
                    int start = toInt(args[1]) - 1;
                    int len   = toInt(args[2]);
                    if (start < 0) { len += start; start = 0; }
                    if (len <= 0 || start >= s.length()) return "";
                    int end = Math.min(start + len, s.length());
                    return s.substring(start, end);
                }
        ));
        // concat(variadic "any") → text
        reg.register(new FunctionDef(
                3058L, "concat", "pg_catalog",
                List.of(t(PgOid.ANY)), t(PgOid.TEXT),
                false, true,
                args -> {
                    StringBuilder sb = new StringBuilder();
                    for (Object a : args) if (a != null) sb.append(a);
                    return sb.toString();
                }
        ));
        // concat_ws(sep text, variadic "any") → text
        reg.register(new FunctionDef(
                3059L, "concat_ws", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.ANY)), t(PgOid.TEXT),
                false, true,
                args -> {
                    String sep = args[0] == null ? "" : (String) args[0];
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i < args.length; i++) {
                        if (args[i] == null) continue;
                        if (sb.length() > 0) sb.append(sep);
                        sb.append(args[i]);
                    }
                    return sb.toString();
                }
        ));
        // left(text, int4) → text
        reg.register(new FunctionDef(
                3060L, "left", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.INT4)), t(PgOid.TEXT),
                true, false,
                args -> {
                    String s = (String) args[0];
                    int n = toInt(args[1]);
                    if (n < 0) n = Math.max(0, s.length() + n);
                    return s.substring(0, Math.min(n, s.length()));
                }
        ));
        // right(text, int4) → text
        reg.register(new FunctionDef(
                3061L, "right", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.INT4)), t(PgOid.TEXT),
                true, false,
                args -> {
                    String s = (String) args[0];
                    int n = toInt(args[1]);
                    if (n < 0) n = Math.max(0, s.length() + n);
                    int start = Math.max(0, s.length() - n);
                    return s.substring(start);
                }
        ));
        // repeat(text, int4) → text
        reg.register(new FunctionDef(
                1333L, "repeat", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.INT4)), t(PgOid.TEXT),
                true, false,
                args -> ((String) args[0]).repeat(Math.max(0, toInt(args[1])))
        ));
        // replace(text, text, text) → text
        reg.register(new FunctionDef(
                2087L, "replace", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.TEXT), t(PgOid.TEXT)), t(PgOid.TEXT),
                true, false,
                args -> ((String) args[0]).replace((String) args[1], (String) args[2])
        ));
        // split_part(text, text, int4) → text
        reg.register(new FunctionDef(
                2088L, "split_part", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.TEXT), t(PgOid.INT4)), t(PgOid.TEXT),
                true, false,
                args -> {
                    String[] parts = ((String) args[0]).split(java.util.regex.Pattern.quote((String) args[1]), -1);
                    int idx = toInt(args[2]);
                    if (idx < 1 || idx > parts.length) return "";
                    return parts[idx - 1];
                }
        ));
        // string_to_array(text, text) → text[]
        reg.register(new FunctionDef(
                394L, "string_to_array", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.TEXT)), t(PgOid.TEXT_ARRAY),
                true, false,
                args -> Arrays.asList(((String) args[0]).split(java.util.regex.Pattern.quote((String) args[1]), -1))
        ));
        // array_to_string(anyarray, text) → text
        reg.register(new FunctionDef(
                395L, "array_to_string", "pg_catalog",
                List.of(t(PgOid.ANY), t(PgOid.TEXT)), t(PgOid.TEXT),
                true, false,
                args -> {
                    Object arr = args[0];
                    String sep = (String) args[1];
                    if (arr instanceof List<?> list) {
                        StringBuilder sb = new StringBuilder();
                        for (Object item : list) {
                            if (item == null) continue;
                            if (sb.length() > 0) sb.append(sep);
                            sb.append(item);
                        }
                        return sb.toString();
                    }
                    return arr == null ? null : arr.toString();
                }
        ));
        // lpad(text, int4) → text
        reg.register(new FunctionDef(
                879L, "lpad", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.INT4)), t(PgOid.TEXT),
                true, false,
                args -> {
                    String s = (String) args[0];
                    int n = toInt(args[1]);
                    if (s.length() >= n) return s.substring(0, n);
                    return " ".repeat(n - s.length()) + s;
                }
        ));
        // lpad(text, int4, text) → text
        reg.register(new FunctionDef(
                880L, "lpad", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.INT4), t(PgOid.TEXT)), t(PgOid.TEXT),
                true, false,
                args -> {
                    String s = (String) args[0];
                    int n = toInt(args[1]);
                    String fill = (String) args[2];
                    if (fill.isEmpty()) fill = " ";
                    if (s.length() >= n) return s.substring(0, n);
                    StringBuilder pad = new StringBuilder();
                    while (pad.length() < n - s.length()) pad.append(fill);
                    return pad.substring(0, n - s.length()) + s;
                }
        ));
        // rpad(text, int4) → text
        reg.register(new FunctionDef(
                881L, "rpad", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.INT4)), t(PgOid.TEXT),
                true, false,
                args -> {
                    String s = (String) args[0];
                    int n = toInt(args[1]);
                    if (s.length() >= n) return s.substring(0, n);
                    return s + " ".repeat(n - s.length());
                }
        ));
        // rpad(text, int4, text) → text
        reg.register(new FunctionDef(
                882L, "rpad", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.INT4), t(PgOid.TEXT)), t(PgOid.TEXT),
                true, false,
                args -> {
                    String s = (String) args[0];
                    int n = toInt(args[1]);
                    String fill = (String) args[2];
                    if (fill.isEmpty()) fill = " ";
                    if (s.length() >= n) return s.substring(0, n);
                    StringBuilder pad = new StringBuilder(s);
                    while (pad.length() < n) pad.append(fill);
                    return pad.substring(0, n);
                }
        ));
        // strpos(text, text) / position(text in text) → int4
        reg.register(new FunctionDef(
                849L, "strpos", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.TEXT)), t(PgOid.INT4),
                true, false,
                args -> ((String) args[0]).indexOf((String) args[1]) + 1
        ));
        // position: POSITION(needle IN haystack) → FunctionCall("position", [haystack, needle])
        reg.register(new FunctionDef(
                850L, "position", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.TEXT)), t(PgOid.INT4),
                true, false,
                args -> ((String) args[0]).indexOf((String) args[1]) + 1
        ));
        // reverse(text) → text
        reg.register(new FunctionDef(
                851L, "reverse", "pg_catalog",
                List.of(t(PgOid.TEXT)), t(PgOid.TEXT),
                true, false,
                args -> new StringBuilder((String) args[0]).reverse().toString()
        ));
        // starts_with(text, text) → bool
        reg.register(new FunctionDef(
                852L, "starts_with", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.TEXT)), t(PgOid.BOOL),
                true, false,
                args -> ((String) args[0]).startsWith((String) args[1])
        ));
        // chr(int) → text
        reg.register(new FunctionDef(
                1370L, "chr", "pg_catalog",
                List.of(t(PgOid.INT4)), t(PgOid.TEXT),
                true, false,
                args -> String.valueOf((char) ((Number) args[0]).intValue())
        ));
        // ascii(text) → int
        reg.register(new FunctionDef(
                1371L, "ascii", "pg_catalog",
                List.of(t(PgOid.TEXT)), t(PgOid.INT4),
                true, false,
                args -> {
                    String s = (String) args[0];
                    return s.isEmpty() ? 0 : (int) s.charAt(0);
                }
        ));
        // initcap(text) → text
        reg.register(new FunctionDef(
                872L, "initcap", "pg_catalog",
                List.of(t(PgOid.TEXT)), t(PgOid.TEXT),
                true, false,
                args -> {
                    String s = (String) args[0];
                    StringBuilder sb = new StringBuilder(s.length());
                    boolean newWord = true;
                    for (char c : s.toCharArray()) {
                        if (Character.isLetterOrDigit(c)) {
                            sb.append(newWord ? Character.toUpperCase(c) : Character.toLowerCase(c));
                            newWord = false;
                        } else {
                            sb.append(c);
                            newWord = true;
                        }
                    }
                    return sb.toString();
                }
        ));
        // md5(text) → text
        reg.register(new FunctionDef(
                2321L, "md5", "pg_catalog",
                List.of(t(PgOid.TEXT)), t(PgOid.TEXT),
                true, false,
                args -> {
                    try {
                        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                        byte[] digest = md.digest(((String) args[0]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        StringBuilder hex = new StringBuilder();
                        for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
                        return hex.toString();
                    } catch (java.security.NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                }
        ));
        // overlay(text PLACING text FROM int FOR int) → text
        reg.register(new FunctionDef(
                1404L, "overlay", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.TEXT), t(PgOid.INT4), t(PgOid.INT4)), t(PgOid.TEXT),
                true, false,
                args -> {
                    String base = (String) args[0];
                    String repl = (String) args[1];
                    int start = ((Number) args[2]).intValue() - 1; // 1-based to 0-based
                    int count = ((Number) args[3]).intValue();
                    int end = Math.min(start + count, base.length());
                    return base.substring(0, Math.max(0, start)) + repl + base.substring(end);
                }
        ));
        // pg_catalog.pg_encoding_to_char(int4) → name — used by JDBC metadata
        reg.register(new FunctionDef(
                1597L, "pg_encoding_to_char", "pg_catalog",
                List.of(t(PgOid.INT4)), t(PgOid.NAME),
                true, false,
                args -> {
                    int enc = toInt(args[0]);
                    return enc == 6 ? "UTF8" : "unknown";
                }
        ));
        // format(text, variadic "any") → text  (basic %s/%L/%I only)
        reg.register(new FunctionDef(
                3051L, "format", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.ANY)), t(PgOid.TEXT),
                true, true,
                args -> pgFormat((String) args[0], args, 1)
        ));
        // quote_ident(text) → text
        reg.register(new FunctionDef(
                1282L, "quote_ident", "pg_catalog",
                List.of(t(PgOid.TEXT)), t(PgOid.TEXT),
                true, false,
                args -> '"' + ((String) args[0]).replace("\"", "\"\"") + '"'
        ));
        // quote_literal(text) → text
        reg.register(new FunctionDef(
                1283L, "quote_literal", "pg_catalog",
                List.of(t(PgOid.TEXT)), t(PgOid.TEXT),
                true, false,
                args -> '\'' + ((String) args[0]).replace("'", "''") + '\''
        ));
    }

    private static String pgFormat(String fmt, Object[] args, int start) {
        StringBuilder sb = new StringBuilder();
        int argIdx = start;
        for (int i = 0; i < fmt.length(); i++) {
            char c = fmt.charAt(i);
            if (c != '%' || i + 1 >= fmt.length()) { sb.append(c); continue; }
            char spec = fmt.charAt(++i);
            Object v = argIdx < args.length ? args[argIdx++] : null;
            switch (spec) {
                case 's' -> sb.append(v == null ? "" : v.toString());
                case 'L' -> { if (v == null) sb.append("NULL");
                              else sb.append('\'').append(v.toString().replace("'","''")).append('\''); }
                case 'I' -> sb.append('"').append(v == null ? "" : v.toString().replace("\"","\"\"")).append('"');
                case '%' -> { sb.append('%'); argIdx--; }
                default  -> sb.append('%').append(spec);
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Math functions

    private static void registerMath(FunctionRegistry reg) {
        // abs(int4), abs(int8), abs(float8), abs(numeric)
        reg.register(new FunctionDef(1397L, "abs", "pg_catalog",
                List.of(t(PgOid.INT4)), t(PgOid.INT4), true, false,
                args -> Math.abs(toInt(args[0]))));
        reg.register(new FunctionDef(1398L, "abs", "pg_catalog",
                List.of(t(PgOid.INT8)), t(PgOid.INT8), true, false,
                args -> Math.abs(toLong(args[0]))));
        reg.register(new FunctionDef(1399L, "abs", "pg_catalog",
                List.of(t(PgOid.FLOAT8)), t(PgOid.FLOAT8), true, false,
                args -> Math.abs(toDouble(args[0]))));
        reg.register(new FunctionDef(1700L, "abs", "pg_catalog",
                List.of(t(PgOid.NUMERIC)), t(PgOid.NUMERIC), true, false,
                args -> ((BigDecimal) args[0]).abs()));
        // ceil/ceiling
        reg.register(new FunctionDef(1215L, "ceil", "pg_catalog",
                List.of(t(PgOid.FLOAT8)), t(PgOid.FLOAT8), true, false,
                args -> Math.ceil(toDouble(args[0]))));
        reg.register(new FunctionDef(1215L + 1, "ceiling", "pg_catalog",
                List.of(t(PgOid.FLOAT8)), t(PgOid.FLOAT8), true, false,
                args -> Math.ceil(toDouble(args[0]))));
        // floor
        reg.register(new FunctionDef(1216L, "floor", "pg_catalog",
                List.of(t(PgOid.FLOAT8)), t(PgOid.FLOAT8), true, false,
                args -> Math.floor(toDouble(args[0]))));
        // round(float8) → float8 — round half away from zero (matches PG numeric semantics)
        reg.register(new FunctionDef(1220L, "round", "pg_catalog",
                List.of(t(PgOid.FLOAT8)), t(PgOid.FLOAT8), true, false,
                args -> {
                    double d = toDouble(args[0]);
                    return d >= 0 ? Math.floor(d + 0.5) : Math.ceil(d - 0.5);
                }));
        // round(numeric, int4) → numeric
        reg.register(new FunctionDef(1701L, "round", "pg_catalog",
                List.of(t(PgOid.NUMERIC), t(PgOid.INT4)), t(PgOid.NUMERIC), true, false,
                args -> ((BigDecimal) args[0]).setScale(toInt(args[1]), java.math.RoundingMode.HALF_UP)));
        // trunc(float8) → float8
        reg.register(new FunctionDef(1223L, "trunc", "pg_catalog",
                List.of(t(PgOid.FLOAT8)), t(PgOid.FLOAT8), true, false,
                args -> { double d = toDouble(args[0]); return d < 0 ? Math.ceil(d) : Math.floor(d); }));
        // mod(int4, int4)
        reg.register(new FunctionDef(1230L, "mod", "pg_catalog",
                List.of(t(PgOid.INT4), t(PgOid.INT4)), t(PgOid.INT4), true, false,
                args -> toInt(args[0]) % toInt(args[1])));
        // mod(int8, int8)
        reg.register(new FunctionDef(1231L, "mod", "pg_catalog",
                List.of(t(PgOid.INT8), t(PgOid.INT8)), t(PgOid.INT8), true, false,
                args -> toLong(args[0]) % toLong(args[1])));
        // power(float8, float8)
        reg.register(new FunctionDef(1232L, "power", "pg_catalog",
                List.of(t(PgOid.FLOAT8), t(PgOid.FLOAT8)), t(PgOid.FLOAT8), true, false,
                args -> Math.pow(toDouble(args[0]), toDouble(args[1]))));
        // sqrt(float8)
        reg.register(new FunctionDef(1233L, "sqrt", "pg_catalog",
                List.of(t(PgOid.FLOAT8)), t(PgOid.FLOAT8), true, false,
                args -> Math.sqrt(toDouble(args[0]))));
        // log(float8) = log10
        reg.register(new FunctionDef(1234L, "log", "pg_catalog",
                List.of(t(PgOid.FLOAT8)), t(PgOid.FLOAT8), true, false,
                args -> Math.log10(toDouble(args[0]))));
        // ln(float8) = natural log
        reg.register(new FunctionDef(1235L, "ln", "pg_catalog",
                List.of(t(PgOid.FLOAT8)), t(PgOid.FLOAT8), true, false,
                args -> Math.log(toDouble(args[0]))));
        // exp(float8)
        reg.register(new FunctionDef(1236L, "exp", "pg_catalog",
                List.of(t(PgOid.FLOAT8)), t(PgOid.FLOAT8), true, false,
                args -> Math.exp(toDouble(args[0]))));
        // sign(float8)
        reg.register(new FunctionDef(1243L, "sign", "pg_catalog",
                List.of(t(PgOid.FLOAT8)), t(PgOid.FLOAT8), true, false,
                args -> Math.signum(toDouble(args[0]))));
        // random() → float8
        reg.register(new FunctionDef(1244L, "random", "pg_catalog",
                List.of(), t(PgOid.FLOAT8), false, false,
                args -> Math.random()));
        // pi() → float8
        reg.register(new FunctionDef(1245L, "pi", "pg_catalog",
                List.of(), t(PgOid.FLOAT8), false, false,
                args -> Math.PI));
        // generate_series is registered as SRF only (see registerSrfs)
    }

    // -------------------------------------------------------------------------
    // Aggregate functions

    private static void registerAggregates(FunctionRegistry reg) {
        // count(*)
        reg.registerAggregate(new AggregateDef(
                2147L, "count", "pg_catalog", List.of(), t(PgOid.INT8),
                () -> new AggregateDef.Accumulator() {
                    long n = 0;
                    @Override public void accumulate(Object v) { n++; }
                    @Override public Object result() { return n; }
                }
        ));
        // count(any)
        reg.registerAggregate(new AggregateDef(
                2803L, "count", "pg_catalog", List.of(t(PgOid.ANY)), t(PgOid.INT8),
                () -> new AggregateDef.Accumulator() {
                    long n = 0;
                    @Override public void accumulate(Object v) { if (v != null) n++; }
                    @Override public Object result() { return n; }
                }
        ));
        // sum(int4) → int8
        reg.registerAggregate(new AggregateDef(
                2108L, "sum", "pg_catalog", List.of(t(PgOid.INT4)), t(PgOid.INT8),
                () -> new AggregateDef.Accumulator() {
                    Long total = null;
                    @Override public void accumulate(Object v) {
                        if (v != null) total = (total == null ? 0L : total) + toInt(v);
                    }
                    @Override public Object result() { return total; }
                }
        ));
        // sum(int8) → numeric
        reg.registerAggregate(new AggregateDef(
                2109L, "sum", "pg_catalog", List.of(t(PgOid.INT8)), t(PgOid.NUMERIC),
                () -> new AggregateDef.Accumulator() {
                    BigDecimal total = null;
                    @Override public void accumulate(Object v) {
                        if (v != null) total = (total == null ? BigDecimal.ZERO : total).add(BigDecimal.valueOf(toLong(v)));
                    }
                    @Override public Object result() { return total; }
                }
        ));
        // sum(float8) → float8
        reg.registerAggregate(new AggregateDef(
                2110L, "sum", "pg_catalog", List.of(t(PgOid.FLOAT8)), t(PgOid.FLOAT8),
                () -> new AggregateDef.Accumulator() {
                    Double total = null;
                    @Override public void accumulate(Object v) {
                        if (v != null) total = (total == null ? 0.0 : total) + toDouble(v);
                    }
                    @Override public Object result() { return total; }
                }
        ));
        // sum(numeric) → numeric
        reg.registerAggregate(new AggregateDef(
                2114L, "sum", "pg_catalog", List.of(t(PgOid.NUMERIC)), t(PgOid.NUMERIC),
                () -> new AggregateDef.Accumulator() {
                    BigDecimal total = null;
                    @Override public void accumulate(Object v) {
                        if (v != null) {
                            BigDecimal bd = (v instanceof BigDecimal b) ? b : new BigDecimal(v.toString());
                            total = (total == null) ? bd : total.add(bd);
                        }
                    }
                    @Override public Object result() { return total; }
                }
        ));
        // avg(int4) → numeric
        reg.registerAggregate(new AggregateDef(
                2101L, "avg", "pg_catalog", List.of(t(PgOid.INT4)), t(PgOid.NUMERIC),
                () -> new AggregateDef.Accumulator() {
                    long sum = 0; long cnt = 0;
                    @Override public void accumulate(Object v) { if (v != null) { sum += toInt(v); cnt++; } }
                    @Override public Object result() { return cnt == 0 ? null : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(cnt), 10, java.math.RoundingMode.HALF_UP); }
                }
        ));
        // avg(int8) → numeric
        reg.registerAggregate(new AggregateDef(
                2100L, "avg", "pg_catalog", List.of(t(PgOid.INT8)), t(PgOid.NUMERIC),
                () -> new AggregateDef.Accumulator() {
                    BigDecimal sum = BigDecimal.ZERO; long cnt = 0;
                    @Override public void accumulate(Object v) { if (v != null) { sum = sum.add(BigDecimal.valueOf(toLong(v))); cnt++; } }
                    @Override public Object result() { return cnt == 0 ? null : sum.divide(BigDecimal.valueOf(cnt), 10, java.math.RoundingMode.HALF_UP); }
                }
        ));
        // avg(numeric) → numeric
        reg.registerAggregate(new AggregateDef(
                2103L, "avg", "pg_catalog", List.of(t(PgOid.NUMERIC)), t(PgOid.NUMERIC),
                () -> new AggregateDef.Accumulator() {
                    BigDecimal sum = BigDecimal.ZERO; long cnt = 0;
                    @Override public void accumulate(Object v) {
                        if (v != null) {
                            BigDecimal bd = (v instanceof BigDecimal b) ? b : new BigDecimal(v.toString());
                            sum = sum.add(bd); cnt++;
                        }
                    }
                    @Override public Object result() { return cnt == 0 ? null : sum.divide(BigDecimal.valueOf(cnt), 10, java.math.RoundingMode.HALF_UP); }
                }
        ));
        // avg(float8) → float8
        reg.registerAggregate(new AggregateDef(
                2105L, "avg", "pg_catalog", List.of(t(PgOid.FLOAT8)), t(PgOid.FLOAT8),
                () -> new AggregateDef.Accumulator() {
                    double sum = 0; long cnt = 0;
                    @Override public void accumulate(Object v) { if (v != null) { sum += toDouble(v); cnt++; } }
                    @Override public Object result() { return cnt == 0 ? null : sum / cnt; }
                }
        ));
        // max(int4) → int4
        reg.registerAggregate(new AggregateDef(
                2115L, "max", "pg_catalog", List.of(t(PgOid.INT4)), t(PgOid.INT4),
                () -> new AggregateDef.Accumulator() {
                    Integer max = null;
                    @Override public void accumulate(Object v) {
                        if (v != null) { int i = toInt(v); max = max == null ? i : Math.max(max, i); }
                    }
                    @Override public Object result() { return max; }
                }
        ));
        // max(int8) → int8
        reg.registerAggregate(new AggregateDef(
                2116L, "max", "pg_catalog", List.of(t(PgOid.INT8)), t(PgOid.INT8),
                () -> new AggregateDef.Accumulator() {
                    Long max = null;
                    @Override public void accumulate(Object v) {
                        if (v != null) { long l = toLong(v); max = max == null ? l : Math.max(max, l); }
                    }
                    @Override public Object result() { return max; }
                }
        ));
        // max(float8) → float8
        reg.registerAggregate(new AggregateDef(
                2120L, "max", "pg_catalog", List.of(t(PgOid.FLOAT8)), t(PgOid.FLOAT8),
                () -> new AggregateDef.Accumulator() {
                    Double max = null;
                    @Override public void accumulate(Object v) {
                        if (v != null) { double d = toDouble(v); max = max == null ? d : Math.max(max, d); }
                    }
                    @Override public Object result() { return max; }
                }
        ));
        // max(text) → text
        reg.registerAggregate(new AggregateDef(
                2129L, "max", "pg_catalog", List.of(t(PgOid.TEXT)), t(PgOid.TEXT),
                () -> new AggregateDef.Accumulator() {
                    String max = null;
                    @Override public void accumulate(Object v) {
                        if (v != null) { String s = (String) v; max = max == null ? s : (org.pgjava.types.PgCollation.DEFAULT.compare(s, max) > 0 ? s : max); }
                    }
                    @Override public Object result() { return max; }
                }
        ));
        // max(date) → date
        reg.registerAggregate(new AggregateDef(
                2122L, "max", "pg_catalog", List.of(t(PgOid.DATE)), t(PgOid.DATE),
                () -> new AggregateDef.Accumulator() {
                    LocalDate max = null;
                    @Override public void accumulate(Object v) {
                        if (v instanceof LocalDate d) { max = max == null ? d : (d.isAfter(max) ? d : max); }
                    }
                    @Override public Object result() { return max; }
                }
        ));
        // max(timestamp) → timestamp
        reg.registerAggregate(new AggregateDef(
                2126L, "max", "pg_catalog", List.of(t(PgOid.TIMESTAMP)), t(PgOid.TIMESTAMP),
                () -> new AggregateDef.Accumulator() {
                    LocalDateTime max = null;
                    @Override public void accumulate(Object v) {
                        if (v instanceof LocalDateTime d) { max = max == null ? d : (d.isAfter(max) ? d : max); }
                    }
                    @Override public Object result() { return max; }
                }
        ));
        // max(timestamptz) → timestamptz
        reg.registerAggregate(new AggregateDef(
                2127L, "max", "pg_catalog", List.of(t(PgOid.TIMESTAMPTZ)), t(PgOid.TIMESTAMPTZ),
                () -> new AggregateDef.Accumulator() {
                    OffsetDateTime max = null;
                    @Override public void accumulate(Object v) {
                        if (v instanceof OffsetDateTime d) { max = max == null ? d : (d.isAfter(max) ? d : max); }
                    }
                    @Override public Object result() { return max; }
                }
        ));
        // max(numeric) → numeric
        reg.registerAggregate(new AggregateDef(
                2130L, "max", "pg_catalog", List.of(t(PgOid.NUMERIC)), t(PgOid.NUMERIC),
                () -> new AggregateDef.Accumulator() {
                    BigDecimal max = null;
                    @Override public void accumulate(Object v) {
                        if (v != null) {
                            BigDecimal bd = (v instanceof BigDecimal b) ? b : new BigDecimal(v.toString());
                            max = max == null ? bd : max.max(bd);
                        }
                    }
                    @Override public Object result() { return max; }
                }
        ));
        // min(int4) → int4
        reg.registerAggregate(new AggregateDef(
                2132L, "min", "pg_catalog", List.of(t(PgOid.INT4)), t(PgOid.INT4),
                () -> new AggregateDef.Accumulator() {
                    Integer min = null;
                    @Override public void accumulate(Object v) {
                        if (v != null) { int i = toInt(v); min = min == null ? i : Math.min(min, i); }
                    }
                    @Override public Object result() { return min; }
                }
        ));
        // min(int8) → int8
        reg.registerAggregate(new AggregateDef(
                2133L, "min", "pg_catalog", List.of(t(PgOid.INT8)), t(PgOid.INT8),
                () -> new AggregateDef.Accumulator() {
                    Long min = null;
                    @Override public void accumulate(Object v) {
                        if (v != null) { long l = toLong(v); min = min == null ? l : Math.min(min, l); }
                    }
                    @Override public Object result() { return min; }
                }
        ));
        // min(float8) → float8
        reg.registerAggregate(new AggregateDef(
                2136L, "min", "pg_catalog", List.of(t(PgOid.FLOAT8)), t(PgOid.FLOAT8),
                () -> new AggregateDef.Accumulator() {
                    Double min = null;
                    @Override public void accumulate(Object v) {
                        if (v != null) { double d = toDouble(v); min = min == null ? d : Math.min(min, d); }
                    }
                    @Override public Object result() { return min; }
                }
        ));
        // min(text) → text
        reg.registerAggregate(new AggregateDef(
                2145L, "min", "pg_catalog", List.of(t(PgOid.TEXT)), t(PgOid.TEXT),
                () -> new AggregateDef.Accumulator() {
                    String min = null;
                    @Override public void accumulate(Object v) {
                        if (v != null) { String s = (String) v; min = min == null ? s : (org.pgjava.types.PgCollation.DEFAULT.compare(s, min) < 0 ? s : min); }
                    }
                    @Override public Object result() { return min; }
                }
        ));
        // min(date) → date
        reg.registerAggregate(new AggregateDef(
                2138L, "min", "pg_catalog", List.of(t(PgOid.DATE)), t(PgOid.DATE),
                () -> new AggregateDef.Accumulator() {
                    LocalDate min = null;
                    @Override public void accumulate(Object v) {
                        if (v instanceof LocalDate d) { min = min == null ? d : (d.isBefore(min) ? d : min); }
                    }
                    @Override public Object result() { return min; }
                }
        ));
        // min(timestamp) → timestamp
        reg.registerAggregate(new AggregateDef(
                2142L, "min", "pg_catalog", List.of(t(PgOid.TIMESTAMP)), t(PgOid.TIMESTAMP),
                () -> new AggregateDef.Accumulator() {
                    LocalDateTime min = null;
                    @Override public void accumulate(Object v) {
                        if (v instanceof LocalDateTime d) { min = min == null ? d : (d.isBefore(min) ? d : min); }
                    }
                    @Override public Object result() { return min; }
                }
        ));
        // min(timestamptz) → timestamptz
        reg.registerAggregate(new AggregateDef(
                2143L, "min", "pg_catalog", List.of(t(PgOid.TIMESTAMPTZ)), t(PgOid.TIMESTAMPTZ),
                () -> new AggregateDef.Accumulator() {
                    OffsetDateTime min = null;
                    @Override public void accumulate(Object v) {
                        if (v instanceof OffsetDateTime d) { min = min == null ? d : (d.isBefore(min) ? d : min); }
                    }
                    @Override public Object result() { return min; }
                }
        ));
        // min(numeric) → numeric
        reg.registerAggregate(new AggregateDef(
                2146L, "min", "pg_catalog", List.of(t(PgOid.NUMERIC)), t(PgOid.NUMERIC),
                () -> new AggregateDef.Accumulator() {
                    BigDecimal min = null;
                    @Override public void accumulate(Object v) {
                        if (v != null) {
                            BigDecimal bd = (v instanceof BigDecimal b) ? b : new BigDecimal(v.toString());
                            min = min == null ? bd : min.min(bd);
                        }
                    }
                    @Override public Object result() { return min; }
                }
        ));
        // bool_and(bool)
        reg.registerAggregate(new AggregateDef(
                2517L, "bool_and", "pg_catalog", List.of(t(PgOid.BOOL)), t(PgOid.BOOL),
                () -> new AggregateDef.Accumulator() {
                    Boolean result = null;
                    @Override public void accumulate(Object v) {
                        if (v != null) result = result == null ? (Boolean) v : result && (Boolean) v;
                    }
                    @Override public Object result() { return result; }
                }
        ));
        // bool_or(bool)
        reg.registerAggregate(new AggregateDef(
                2518L, "bool_or", "pg_catalog", List.of(t(PgOid.BOOL)), t(PgOid.BOOL),
                () -> new AggregateDef.Accumulator() {
                    Boolean result = null;
                    @Override public void accumulate(Object v) {
                        if (v != null) result = result == null ? (Boolean) v : result || (Boolean) v;
                    }
                    @Override public Object result() { return result; }
                }
        ));
        // string_agg(text, text) → text
        reg.registerAggregate(new AggregateDef(
                3538L, "string_agg", "pg_catalog", List.of(t(PgOid.TEXT), t(PgOid.TEXT)), t(PgOid.TEXT),
                () -> new AggregateDef.Accumulator() {
                    StringBuilder sb = new StringBuilder();
                    boolean hasValue = false;
                    String delimiter = ","; // default; real delimiter comes from second arg via AggAccumulator
                    @Override public void accumulate(Object v) throws SQLException {
                        if (v == null) return;
                        if (v instanceof Object[] pair) {
                            // two-arg form: pair[0] = value, pair[1] = delimiter
                            Object val = pair[0];
                            if (val == null) return;
                            if (pair.length > 1 && pair[1] != null) delimiter = pair[1].toString();
                            if (hasValue) sb.append(delimiter);
                            sb.append(val.toString());
                        } else {
                            if (hasValue) sb.append(delimiter);
                            sb.append(v.toString());
                        }
                        hasValue = true;
                    }
                    @Override public Object result() { return hasValue ? sb.toString() : null; }
                }
        ));
    }

    // -------------------------------------------------------------------------
    // Miscellaneous

    private static void registerMisc(FunctionRegistry reg) {
        // pg_backend_pid() → int4
        reg.register(new FunctionDef(
                2026L, "pg_backend_pid", "pg_catalog",
                List.of(), t(PgOid.INT4), false, false,
                args -> (int) ProcessHandle.current().pid()
        ));
        // version() → text
        reg.register(new FunctionDef(
                89L, "version", "pg_catalog",
                List.of(), t(PgOid.TEXT), false, false,
                args -> "PostgreSQL 15.0 on pgjava"
        ));
        // pg_postmaster_start_time() → timestamptz
        reg.register(new FunctionDef(
                2560L, "pg_postmaster_start_time", "pg_catalog",
                List.of(), t(PgOid.TIMESTAMPTZ), false, false,
                args -> OffsetDateTime.now(ZoneOffset.UTC)
        ));
        // txid_current() → int8
        reg.register(new FunctionDef(
                3997L, "txid_current", "pg_catalog",
                List.of(), t(PgOid.INT8), false, false,
                args -> 1L
        ));
        // gen_random_uuid() → uuid
        reg.register(new FunctionDef(
                3997L + 1, "gen_random_uuid", "pg_catalog",
                List.of(), t(PgOid.UUID), false, false,
                args -> java.util.UUID.randomUUID()
        ));
        // pg_typeof(any) → regtype
        reg.register(new FunctionDef(
                3445L, "pg_typeof", "pg_catalog",
                List.of(t(PgOid.ANY)), t(PgOid.TEXT), false, false,
                args -> {
                    Object v = args[0];
                    if (v == null) return "unknown";
                    if (v instanceof Integer) return "integer";
                    if (v instanceof Long) return "bigint";
                    if (v instanceof Double) return "double precision";
                    if (v instanceof Boolean) return "boolean";
                    if (v instanceof BigDecimal) return "numeric";
                    if (v instanceof java.time.LocalDate) return "date";
                    if (v instanceof OffsetDateTime) return "timestamp with time zone";
                    return "text";
                }
        ));
        // array_length(anyarray, int4) → int4
        reg.register(new FunctionDef(
                2176L, "array_length", "pg_catalog",
                List.of(t(PgOid.ANY), t(PgOid.INT4)), t(PgOid.INT4),
                true, false,
                args -> {
                    Object arr = args[0];
                    if (arr instanceof java.util.List<?> l) return l.size();
                    if (arr instanceof Object[] a) return a.length;
                    return null;
                }
        ));
        // cardinality(anyarray) → int4
        reg.register(new FunctionDef(
                2177L, "cardinality", "pg_catalog",
                List.of(t(PgOid.ANY)), t(PgOid.INT4),
                true, false,
                args -> {
                    Object arr = args[0];
                    if (arr instanceof java.util.List<?> l) return l.size();
                    if (arr instanceof Object[] a) return a.length;
                    return null;
                }
        ));
        // unnest(anyarray) → setof anyelement
        reg.register(new FunctionDef(
                2178L, "unnest", "pg_catalog",
                List.of(t(PgOid.ANY)), t(PgOid.ANY),
                true, false,
                args -> args[0] instanceof java.util.List<?> l ? l : java.util.List.of(args[0])
        ));
        // pg_catalog.obj_description(oid, text) → text  (used by some clients)
        reg.register(new FunctionDef(
                1215L + 1000, "obj_description", "pg_catalog",
                List.of(t(PgOid.OID), t(PgOid.TEXT)), t(PgOid.TEXT),
                false, false,
                args -> null
        ));
        // has_schema_privilege(text, text) → bool  (used by pgAdmin / DBeaver)
        reg.register(new FunctionDef(
                2175L, "has_schema_privilege", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.TEXT)), t(PgOid.BOOL),
                true, false,
                args -> true  // embedded DB: all privileges granted
        ));
        // has_table_privilege(text, text, text) → bool
        reg.register(new FunctionDef(
                1923L, "has_table_privilege", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.TEXT), t(PgOid.TEXT)), t(PgOid.BOOL),
                true, false,
                args -> true
        ));
        // has_column_privilege(text, text, text, text) → bool
        reg.register(new FunctionDef(
                1924L, "has_column_privilege", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.TEXT), t(PgOid.TEXT), t(PgOid.TEXT)), t(PgOid.BOOL),
                true, false,
                args -> true
        ));
        // current_schema() → text
        reg.register(new FunctionDef(
                1402L, "current_schema", "pg_catalog",
                List.of(), t(PgOid.TEXT), false, false,
                args -> "public"
        ));
        // current_schemas(bool) → text[]
        reg.register(new FunctionDef(
                1403L, "current_schemas", "pg_catalog",
                List.of(t(PgOid.BOOL)), t(PgOid.TEXT_ARRAY), true, false,
                args -> {
                    boolean includeImplicit = (Boolean) args[0];
                    java.util.List<String> paths = new java.util.ArrayList<>();
                    if (includeImplicit) paths.add("pg_catalog");
                    paths.add("public");
                    return paths;
                }
        ));
        // current_user → text (implemented as function for expression eval)
        reg.register(new FunctionDef(
                9001L, "current_user", "pg_catalog",
                List.of(), t(PgOid.TEXT), false, false,
                args -> "postgres"
        ));
        // session_user → text
        reg.register(new FunctionDef(
                1405L, "session_user", "pg_catalog",
                List.of(), t(PgOid.TEXT), false, false,
                args -> "postgres"
        ));

        // current_database() → name  (used by Flyway, Hibernate, etc.)
        reg.register(new FunctionDef(
                861L, "current_database", "pg_catalog",
                List.of(), t(PgOid.NAME), false, false,
                args -> "pgjava"
        ));

        // Advisory lock functions are registered as session-local functions in Session.java
        // so they have access to the session's transaction context and AdvisoryLockManager.
        // format(text, variadic any) is registered in registerString() with OID 3051
        // pg_extension_update_paths(name) → setof record  — stub
        reg.register(new FunctionDef(
                3901L, "pg_extension_update_paths", "pg_catalog",
                List.of(t(PgOid.NAME)), t(PgOid.TEXT), true, false,
                args -> null
        ));

        // format_type(type_oid, typemod) → text  — used by psql \d, pg_dump, JDBC drivers
        reg.register(new FunctionDef(
                1081L, "format_type", "pg_catalog",
                List.of(t(PgOid.OID), t(PgOid.INT4)), t(PgOid.TEXT), true, false,
                args -> {
                    int oid = toInt(args[0]);
                    int typmod = args[1] != null ? toInt(args[1]) : -1;
                    var type = REG.byOid(oid);
                    if (type == null) return "???";
                    String base = switch (oid) {
                        case PgOid.INT2        -> "smallint";
                        case PgOid.INT4        -> "integer";
                        case PgOid.INT8        -> "bigint";
                        case PgOid.FLOAT4      -> "real";
                        case PgOid.FLOAT8      -> "double precision";
                        case PgOid.NUMERIC     -> typmod > 0
                                ? "numeric(" + ((typmod - 4) >> 16) + "," + ((typmod - 4) & 0xFFFF) + ")"
                                : "numeric";
                        case PgOid.BOOL        -> "boolean";
                        case PgOid.TEXT        -> "text";
                        case PgOid.VARCHAR     -> typmod > 0
                                ? "character varying(" + (typmod - 4) + ")"
                                : "character varying";
                        case PgOid.BPCHAR      -> typmod > 0
                                ? "character(" + (typmod - 4) + ")"
                                : "character";
                        case PgOid.NAME        -> "name";
                        case PgOid.BYTEA       -> "bytea";
                        case PgOid.DATE        -> "date";
                        case PgOid.TIME        -> "time without time zone";
                        case PgOid.TIMETZ      -> "time with time zone";
                        case PgOid.TIMESTAMP   -> "timestamp without time zone";
                        case PgOid.TIMESTAMPTZ -> "timestamp with time zone";
                        case PgOid.INTERVAL    -> "interval";
                        case PgOid.UUID        -> "uuid";
                        case PgOid.JSON        -> "json";
                        case PgOid.JSONB       -> "jsonb";
                        case PgOid.OID         -> "oid";
                        default                -> type.name();
                    };
                    return base;
                }
        ));

        // pg_get_expr(expr_text, rel_oid) → text — deparse a pg_node_tree expression
        // Used by psql to display column defaults from pg_attrdef.adbin
        reg.register(new FunctionDef(
                1716L, "pg_get_expr", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.OID)), t(PgOid.TEXT), true, false,
                args -> args[0] != null ? args[0].toString() : null
        ));
        // pg_get_expr(expr_text, rel_oid, pretty_print) → text — 3-arg variant
        reg.register(new FunctionDef(
                1717L, "pg_get_expr", "pg_catalog",
                List.of(t(PgOid.TEXT), t(PgOid.OID), t(PgOid.BOOL)), t(PgOid.TEXT), true, false,
                args -> args[0] != null ? args[0].toString() : null
        ));

        // pg_get_constraintdef and pg_get_indexdef are registered in
        // CatalogManager.registerCatalogFunctions() because they need catalog access.

        // col_description(table_oid, column_number) → text
        reg.register(new FunctionDef(
                1214L, "col_description", "pg_catalog",
                List.of(t(PgOid.OID), t(PgOid.INT4)), t(PgOid.TEXT), true, false,
                args -> null // no comments stored
        ));

        // pg_get_userbyid(role_oid) → name — returns role name for a given OID
        // Used by psql \d to show table owner
        reg.register(new FunctionDef(
                1642L, "pg_get_userbyid", "pg_catalog",
                List.of(t(PgOid.OID)), t(PgOid.NAME), true, false,
                args -> "postgres" // single-user embedded DB
        ));

        // pg_table_is_visible(table_oid) → bool — is table visible in search_path?
        // Used by psql \d and \dt to filter visible tables
        reg.register(new FunctionDef(
                2079L, "pg_table_is_visible", "pg_catalog",
                List.of(t(PgOid.OID)), t(PgOid.BOOL), true, false,
                args -> true // all user tables are visible
        ));

        // pg_type_is_visible(type_oid) → bool
        reg.register(new FunctionDef(
                2080L, "pg_type_is_visible", "pg_catalog",
                List.of(t(PgOid.OID)), t(PgOid.BOOL), true, false,
                args -> true
        ));

        // pg_function_is_visible(func_oid) → bool
        reg.register(new FunctionDef(
                2081L, "pg_function_is_visible", "pg_catalog",
                List.of(t(PgOid.OID)), t(PgOid.BOOL), true, false,
                args -> true
        ));

        // pg_operator_is_visible(oper_oid) → bool
        reg.register(new FunctionDef(
                2082L, "pg_operator_is_visible", "pg_catalog",
                List.of(t(PgOid.OID)), t(PgOid.BOOL), true, false,
                args -> true
        ));

        // pg_get_serial_sequence is registered in
        // CatalogManager.registerCatalogFunctions() because it needs catalog access.
    }

    // -------------------------------------------------------------------------
    // Type coercion helpers

    private static int toInt(Object v) {
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l.intValue();
        if (v instanceof Short s) return s;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }

    private static long toLong(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    private static double toDouble(Object v) {
        if (v instanceof Double d) return d;
        if (v instanceof Float f) return f;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }

    // =========================================================================
    // Range functions

    private static void registerRangeFunctions(FunctionRegistry reg) {
        PgType text  = t(PgOid.TEXT);
        PgType bool  = t(PgOid.BOOL);
        PgType any   = t(PgOid.ANY);
        PgType int4  = t(PgOid.INT4);
        PgType int8  = t(PgOid.INT8);
        PgType num   = t(PgOid.NUMERIC);
        PgType ts    = t(PgOid.TIMESTAMP);
        PgType tstz  = t(PgOid.TIMESTAMPTZ);
        PgType date  = t(PgOid.DATE);

        PgType i4r   = t(PgOid.INT4RANGE);
        PgType i8r   = t(PgOid.INT8RANGE);
        PgType numr  = t(PgOid.NUMRANGE);
        PgType tsr   = t(PgOid.TSRANGE);
        PgType tstzr = t(PgOid.TSTZRANGE);
        PgType dater = t(PgOid.DATERANGE);

        // lower(range) / upper(range) — return the lower/upper bound, or NULL if infinite
        for (var rangeType : List.of(i4r, i8r, numr, tsr, tstzr, dater)) {
            reg.register(new FunctionDef(0L, "lower", "pg_catalog", List.of(rangeType), any, true, false,
                    args -> args[0] instanceof PgRange r ? (r.lowerInf() || r.isEmpty() ? null : r.lower()) : null));
            reg.register(new FunctionDef(0L, "upper", "pg_catalog", List.of(rangeType), any, true, false,
                    args -> args[0] instanceof PgRange r ? (r.upperInf() || r.isEmpty() ? null : r.upper()) : null));
            reg.register(new FunctionDef(0L, "isempty", "pg_catalog", List.of(rangeType), bool, true, false,
                    args -> args[0] instanceof PgRange r ? r.isEmpty() : null));
            reg.register(new FunctionDef(0L, "lower_inc", "pg_catalog", List.of(rangeType), bool, true, false,
                    args -> args[0] instanceof PgRange r ? r.lowerInc() : null));
            reg.register(new FunctionDef(0L, "upper_inc", "pg_catalog", List.of(rangeType), bool, true, false,
                    args -> args[0] instanceof PgRange r ? r.upperInc() : null));
            reg.register(new FunctionDef(0L, "lower_inf", "pg_catalog", List.of(rangeType), bool, true, false,
                    args -> args[0] instanceof PgRange r ? r.lowerInf() : null));
            reg.register(new FunctionDef(0L, "upper_inf", "pg_catalog", List.of(rangeType), bool, true, false,
                    args -> args[0] instanceof PgRange r ? r.upperInf() : null));
        }

        // range_merge(range, range) — smallest range containing both
        for (var rangeType : List.of(i4r, i8r, numr, tsr, tstzr, dater)) {
            reg.register(new FunctionDef(0L, "range_merge", "pg_catalog",
                    List.of(rangeType, rangeType), rangeType, true, false,
                    args -> (args[0] instanceof PgRange a && args[1] instanceof PgRange b)
                            ? PgRange.merge(a, b) : null));
        }

        // int4range(lower, upper) / int4range(lower, upper, '[)') constructors
        reg.register(new FunctionDef(0L, "int4range", "pg_catalog",
                List.of(int4, int4), i4r, false, false,
                args -> PgRange.of(args[0], args[1], true, false)));
        reg.register(new FunctionDef(0L, "int4range", "pg_catalog",
                List.of(int4, int4, text), i4r, false, false,
                args -> buildRange(args[0], args[1], args[2] != null ? args[2].toString() : "[)")));
        reg.register(new FunctionDef(0L, "int8range", "pg_catalog",
                List.of(int8, int8), i8r, false, false,
                args -> PgRange.of(args[0], args[1], true, false)));
        reg.register(new FunctionDef(0L, "int8range", "pg_catalog",
                List.of(int8, int8, text), i8r, false, false,
                args -> buildRange(args[0], args[1], args[2] != null ? args[2].toString() : "[)")));
        reg.register(new FunctionDef(0L, "numrange", "pg_catalog",
                List.of(num, num), numr, false, false,
                args -> PgRange.of(args[0], args[1], true, false)));
        reg.register(new FunctionDef(0L, "numrange", "pg_catalog",
                List.of(num, num, text), numr, false, false,
                args -> buildRange(args[0], args[1], args[2] != null ? args[2].toString() : "[)")));
        reg.register(new FunctionDef(0L, "tsrange", "pg_catalog",
                List.of(ts, ts), tsr, false, false,
                args -> PgRange.of(args[0], args[1], true, false)));
        reg.register(new FunctionDef(0L, "tsrange", "pg_catalog",
                List.of(ts, ts, text), tsr, false, false,
                args -> buildRange(args[0], args[1], args[2] != null ? args[2].toString() : "[)")));
        reg.register(new FunctionDef(0L, "tstzrange", "pg_catalog",
                List.of(tstz, tstz), tstzr, false, false,
                args -> PgRange.of(args[0], args[1], true, false)));
        reg.register(new FunctionDef(0L, "tstzrange", "pg_catalog",
                List.of(tstz, tstz, text), tstzr, false, false,
                args -> buildRange(args[0], args[1], args[2] != null ? args[2].toString() : "[)")));
        reg.register(new FunctionDef(0L, "daterange", "pg_catalog",
                List.of(date, date), dater, false, false,
                args -> PgRange.of(args[0], args[1], true, false)));
        reg.register(new FunctionDef(0L, "daterange", "pg_catalog",
                List.of(date, date, text), dater, false, false,
                args -> buildRange(args[0], args[1], args[2] != null ? args[2].toString() : "[)")));
    }

    /** Build a PgRange from bounds and a bounds-style string like "[)", "(]", etc. */
    private static PgRange buildRange(Object lower, Object upper, String style) {
        boolean lowerInc = style.startsWith("[");
        boolean upperInc = style.endsWith("]");
        return PgRange.of(lower, upper, lowerInc, upperInc);
    }

    // =========================================================================
    // JSON / JSONB functions

    private static void registerJsonFunctions(FunctionRegistry reg) {
        PgType text = t(PgOid.TEXT);
        PgType json = t(PgOid.JSON);
        PgType jsonb = t(PgOid.JSONB);
        PgType bool = t(PgOid.BOOL);
        PgType int4 = t(PgOid.INT4);
        PgType any = t(PgOid.ANY);

        // json_typeof / jsonb_typeof
        reg.register(new FunctionDef(0L, "json_typeof", "pg_catalog", List.of(json), text, true, false,
                args -> JsonOps.jsonTypeof(asStr(args[0]))));
        reg.register(new FunctionDef(0L, "jsonb_typeof", "pg_catalog", List.of(jsonb), text, true, false,
                args -> JsonOps.jsonTypeof(asStr(args[0]))));

        // json_array_length / jsonb_array_length
        reg.register(new FunctionDef(0L, "json_array_length", "pg_catalog", List.of(json), int4, true, false,
                args -> JsonOps.jsonArrayLength(asStr(args[0]))));
        reg.register(new FunctionDef(0L, "jsonb_array_length", "pg_catalog", List.of(jsonb), int4, true, false,
                args -> JsonOps.jsonArrayLength(asStr(args[0]))));

        // to_json / to_jsonb
        reg.register(new FunctionDef(0L, "to_json", "pg_catalog", List.of(any), json, false, false,
                args -> JsonOps.toJsonValue(args[0])));
        reg.register(new FunctionDef(0L, "to_jsonb", "pg_catalog", List.of(any), jsonb, false, false,
                args -> JsonOps.toJsonValue(args[0])));

        // json_build_object / jsonb_build_object (variadic)
        reg.register(new FunctionDef(0L, "json_build_object", "pg_catalog", List.of(any), json, false, true,
                args -> JsonOps.jsonBuildObject(args)));
        reg.register(new FunctionDef(0L, "jsonb_build_object", "pg_catalog", List.of(any), jsonb, false, true,
                args -> JsonOps.jsonBuildObject(args)));

        // json_build_array / jsonb_build_array (variadic)
        reg.register(new FunctionDef(0L, "json_build_array", "pg_catalog", List.of(any), json, false, true,
                args -> JsonOps.jsonBuildArray(args)));
        reg.register(new FunctionDef(0L, "jsonb_build_array", "pg_catalog", List.of(any), jsonb, false, true,
                args -> JsonOps.jsonBuildArray(args)));

        // jsonb_set(target jsonb, path text[], new_value jsonb [, create_if_missing bool])
        reg.register(new FunctionDef(0L, "jsonb_set", "pg_catalog", List.of(jsonb, any, jsonb), jsonb, false, false,
                args -> JsonOps.jsonbSet(asStr(args[0]), args[1], asStr(args[2]), true)));
        reg.register(new FunctionDef(0L, "jsonb_set", "pg_catalog", List.of(jsonb, any, jsonb, bool), jsonb, false, false,
                args -> JsonOps.jsonbSet(asStr(args[0]), args[1], asStr(args[2]),
                        args[3] == null || Boolean.TRUE.equals(args[3]))));

        // jsonb_strip_nulls
        reg.register(new FunctionDef(0L, "jsonb_strip_nulls", "pg_catalog", List.of(jsonb), jsonb, true, false,
                args -> JsonOps.jsonStripNulls(asStr(args[0]))));
        reg.register(new FunctionDef(0L, "json_strip_nulls", "pg_catalog", List.of(json), json, true, false,
                args -> JsonOps.jsonStripNulls(asStr(args[0]))));

        // jsonb_pretty
        reg.register(new FunctionDef(0L, "jsonb_pretty", "pg_catalog", List.of(jsonb), text, true, false,
                args -> JsonOps.jsonPretty(asStr(args[0]))));

        // json_extract_path / jsonb_extract_path (variadic — equivalent to #>)
        reg.register(new FunctionDef(0L, "json_extract_path", "pg_catalog", List.of(json, any), json, false, true,
                args -> {
                    if (args.length < 1) return null;
                    List<Object> path = new java.util.ArrayList<>();
                    for (int i = 1; i < args.length; i++) if (args[i] != null) path.add(args[i].toString());
                    return JsonOps.extractPath(asStr(args[0]), path);
                }));
        reg.register(new FunctionDef(0L, "jsonb_extract_path", "pg_catalog", List.of(jsonb, any), jsonb, false, true,
                args -> {
                    if (args.length < 1) return null;
                    List<Object> path = new java.util.ArrayList<>();
                    for (int i = 1; i < args.length; i++) if (args[i] != null) path.add(args[i].toString());
                    return JsonOps.extractPath(asStr(args[0]), path);
                }));

        // json_extract_path_text / jsonb_extract_path_text (variadic — equivalent to #>>)
        reg.register(new FunctionDef(0L, "json_extract_path_text", "pg_catalog", List.of(json, any), text, false, true,
                args -> {
                    if (args.length < 1) return null;
                    List<Object> path = new java.util.ArrayList<>();
                    for (int i = 1; i < args.length; i++) if (args[i] != null) path.add(args[i].toString());
                    return JsonOps.extractPathText(asStr(args[0]), path);
                }));
        reg.register(new FunctionDef(0L, "jsonb_extract_path_text", "pg_catalog", List.of(jsonb, any), text, false, true,
                args -> {
                    if (args.length < 1) return null;
                    List<Object> path = new java.util.ArrayList<>();
                    for (int i = 1; i < args.length; i++) if (args[i] != null) path.add(args[i].toString());
                    return JsonOps.extractPathText(asStr(args[0]), path);
                }));
    }

    private static String asStr(Object v) { return v == null ? null : v.toString(); }

    // =========================================================================
    // Set-returning functions (SRFs) — registered for FROM-clause use

    private static void registerSrfs(FunctionRegistry reg) {
        PgType int4  = t(PgOid.INT4);
        PgType int8  = t(PgOid.INT8);
        PgType ts    = t(PgOid.TIMESTAMP);
        PgType itvl  = t(PgOid.INTERVAL);
        PgType any   = t(PgOid.ANY);
        PgType date  = t(PgOid.DATE);

        // generate_series(int4, int4) → setof int4
        reg.registerSrf(new SrfDef(1067L, "generate_series", "pg_catalog",
                List.of(int4, int4), List.of("generate_series"), false,
                args -> {
                    int lo = toInt(args[0]), hi = toInt(args[1]);
                    List<Object[]> rows = new ArrayList<>();
                    for (int i = lo; i <= hi; i++) rows.add(new Object[]{i});
                    return rows;
                }));

        // generate_series(int4, int4, int4) → setof int4  (with step)
        reg.registerSrf(new SrfDef(1067L + 10000, "generate_series", "pg_catalog",
                List.of(int4, int4, int4), List.of("generate_series"), false,
                args -> {
                    int lo = toInt(args[0]), hi = toInt(args[1]), step = toInt(args[2]);
                    if (step == 0) throw PgErrorException.error("22023", "step size cannot equal zero").build();
                    List<Object[]> rows = new ArrayList<>();
                    if (step > 0) for (int i = lo; i <= hi; i += step) rows.add(new Object[]{i});
                    else          for (int i = lo; i >= hi; i += step) rows.add(new Object[]{i});
                    return rows;
                }));

        // generate_series(int8, int8) → setof int8
        reg.registerSrf(new SrfDef(1068L, "generate_series", "pg_catalog",
                List.of(int8, int8), List.of("generate_series"), false,
                args -> {
                    long lo = toLong(args[0]), hi = toLong(args[1]);
                    List<Object[]> rows = new ArrayList<>();
                    for (long i = lo; i <= hi; i++) rows.add(new Object[]{i});
                    return rows;
                }));

        // generate_series(int8, int8, int8) → setof int8  (with step)
        reg.registerSrf(new SrfDef(1068L + 10000, "generate_series", "pg_catalog",
                List.of(int8, int8, int8), List.of("generate_series"), false,
                args -> {
                    long lo = toLong(args[0]), hi = toLong(args[1]), step = toLong(args[2]);
                    if (step == 0) throw PgErrorException.error("22023", "step size cannot equal zero").build();
                    List<Object[]> rows = new ArrayList<>();
                    if (step > 0) for (long i = lo; i <= hi; i += step) rows.add(new Object[]{i});
                    else          for (long i = lo; i >= hi; i += step) rows.add(new Object[]{i});
                    return rows;
                }));

        // generate_series(timestamp, timestamp, interval) → setof timestamp
        reg.registerSrf(new SrfDef(1069L, "generate_series", "pg_catalog",
                List.of(ts, ts, itvl), List.of("generate_series"), false,
                args -> {
                    if (!(args[0] instanceof LocalDateTime start)
                            || !(args[1] instanceof LocalDateTime stop)
                            || !(args[2] instanceof PgInterval step))
                        throw PgErrorException.error("22023", "invalid arguments for generate_series(timestamp,timestamp,interval)").build();
                    if (step.equals(PgInterval.ZERO))
                        throw PgErrorException.error("22023", "step size cannot equal zero").build();
                    List<Object[]> rows = new ArrayList<>();
                    LocalDateTime cur = start;
                    boolean forward = step.months() > 0 || step.days() > 0 || step.micros() > 0;
                    while (forward ? !cur.isAfter(stop) : !cur.isBefore(stop)) {
                        rows.add(new Object[]{cur});
                        cur = cur.plusMonths(step.months()).plusDays(step.days())
                                  .plusNanos(step.micros() * 1000L);
                        if (rows.size() > 100_000) break; // safety cap
                    }
                    return rows;
                }));

        // generate_series(date, date, interval) → setof date
        reg.registerSrf(new SrfDef(1070L, "generate_series", "pg_catalog",
                List.of(date, date, itvl), List.of("generate_series"), false,
                args -> {
                    if (!(args[0] instanceof LocalDate start)
                            || !(args[1] instanceof LocalDate stop)
                            || !(args[2] instanceof PgInterval step))
                        throw PgErrorException.error("22023", "invalid arguments for generate_series(date,date,interval)").build();
                    List<Object[]> rows = new ArrayList<>();
                    LocalDate cur = start;
                    boolean forward = step.months() > 0 || step.days() > 0;
                    while (forward ? !cur.isAfter(stop) : !cur.isBefore(stop)) {
                        rows.add(new Object[]{cur});
                        cur = cur.plusMonths(step.months()).plusDays(step.days());
                        if (rows.size() > 100_000) break;
                    }
                    return rows;
                }));

        // unnest(anyarray) → setof anyelement
        // JSON SRFs
        PgType text = t(PgOid.TEXT);
        PgType json = t(PgOid.JSON);
        PgType jsonb = t(PgOid.JSONB);

        // json_each(json) → setof (key text, value json)
        reg.registerSrf(new SrfDef(3208L, "json_each", "pg_catalog",
                List.of(json), List.of("key", "value"), false,
                args -> org.pgjava.types.JsonOps.jsonEach(args[0] == null ? null : args[0].toString(), false)));
        // jsonb_each(jsonb) → setof (key text, value jsonb)
        reg.registerSrf(new SrfDef(3932L, "jsonb_each", "pg_catalog",
                List.of(jsonb), List.of("key", "value"), false,
                args -> org.pgjava.types.JsonOps.jsonEach(args[0] == null ? null : args[0].toString(), false)));
        // json_each_text(json) → setof (key text, value text)
        reg.registerSrf(new SrfDef(3932L + 1, "json_each_text", "pg_catalog",
                List.of(json), List.of("key", "value"), false,
                args -> org.pgjava.types.JsonOps.jsonEach(args[0] == null ? null : args[0].toString(), true)));
        // jsonb_each_text(jsonb) → setof (key text, value text)
        reg.registerSrf(new SrfDef(3932L + 2, "jsonb_each_text", "pg_catalog",
                List.of(jsonb), List.of("key", "value"), false,
                args -> org.pgjava.types.JsonOps.jsonEach(args[0] == null ? null : args[0].toString(), true)));
        // json_object_keys(json) → setof text
        reg.registerSrf(new SrfDef(3957L, "json_object_keys", "pg_catalog",
                List.of(json), List.of("json_object_keys"), false,
                args -> org.pgjava.types.JsonOps.jsonObjectKeys(args[0] == null ? null : args[0].toString())));
        // jsonb_object_keys(jsonb) → setof text
        reg.registerSrf(new SrfDef(3931L, "jsonb_object_keys", "pg_catalog",
                List.of(jsonb), List.of("jsonb_object_keys"), false,
                args -> org.pgjava.types.JsonOps.jsonObjectKeys(args[0] == null ? null : args[0].toString())));
        // json_array_elements(json) → setof json
        reg.registerSrf(new SrfDef(3219L, "json_array_elements", "pg_catalog",
                List.of(json), List.of("value"), false,
                args -> org.pgjava.types.JsonOps.jsonArrayElements(args[0] == null ? null : args[0].toString(), false)));
        // jsonb_array_elements(jsonb) → setof jsonb
        reg.registerSrf(new SrfDef(3219L + 1, "jsonb_array_elements", "pg_catalog",
                List.of(jsonb), List.of("value"), false,
                args -> org.pgjava.types.JsonOps.jsonArrayElements(args[0] == null ? null : args[0].toString(), false)));
        // json_array_elements_text(json) → setof text
        reg.registerSrf(new SrfDef(3465L, "json_array_elements_text", "pg_catalog",
                List.of(json), List.of("value"), false,
                args -> org.pgjava.types.JsonOps.jsonArrayElements(args[0] == null ? null : args[0].toString(), true)));
        // jsonb_array_elements_text(jsonb) → setof text
        reg.registerSrf(new SrfDef(3465L + 1, "jsonb_array_elements_text", "pg_catalog",
                List.of(jsonb), List.of("value"), false,
                args -> org.pgjava.types.JsonOps.jsonArrayElements(args[0] == null ? null : args[0].toString(), true)));

        reg.registerSrf(new SrfDef(2178L, "unnest", "pg_catalog",
                List.of(any), List.of("unnest"), false,
                args -> {
                    Object arr = args[0];
                    if (arr == null) return List.<Object[]>of();
                    List<Object[]> rows = new ArrayList<>();
                    if (arr instanceof List<?> l) {
                        for (Object e : l) rows.add(new Object[]{e});
                    } else if (arr instanceof Object[] a) {
                        for (Object e : a) rows.add(new Object[]{e});
                    } else {
                        rows.add(new Object[]{arr});
                    }
                    return rows;
                }));
    }
}
