package org.pgjava.executor;

import org.pgjava.sql.ast.Expr;
import org.pgjava.sql.ast.SortByDir;
import org.pgjava.sql.ast.SortByNulls;
import org.pgjava.sql.ast.SortKey;
import org.pgjava.storage.Row;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Materializes the input and sorts it by the ORDER BY key expressions.
 * Sort keys are pre-evaluated once per row (like PG's tuplesort) so that
 * side-effecting expressions such as {@code random()} or {@code nextval()}
 * produce a stable value per row rather than being re-evaluated on every
 * comparison.
 */
public final class Sort extends Operator {

    private final Operator     source;
    private final List<SortKey> keys;
    private final Evaluator    eval;

    /** A row together with its pre-evaluated sort-key values. */
    private record SortEntry(Row row, Object[] keyValues) {}

    private List<SortEntry> buffer;
    private int             pos;

    public Sort(Operator source, List<SortKey> keys, Evaluator eval) {
        super(source.schema());
        this.source = source;
        this.keys   = keys;
        this.eval   = eval;
    }

    @Override
    public void open() throws SQLException {
        source.open();
        buffer = new ArrayList<>();
        Row row;
        while ((row = source.next()) != null) {
            // Pre-evaluate every sort-key expression once per row.
            Object[] keyValues = new Object[keys.size()];
            EvalContext ctx = schema.buildContext(row);
            for (int i = 0; i < keys.size(); i++) {
                keyValues[i] = eval.eval(keys.get(i).node(), ctx);
            }
            buffer.add(new SortEntry(row, keyValues));
        }
        source.close();

        // Build comparator over pre-evaluated keys
        Comparator<SortEntry> cmp = buildComparator();
        buffer.sort(cmp);
        pos = 0;
    }

    @Override
    public Row next() {
        if (buffer == null || pos >= buffer.size()) return null;
        return buffer.get(pos++).row();
    }

    @Override
    public void close() {
        buffer = null;
    }

    // -------------------------------------------------------------------------

    private Comparator<SortEntry> buildComparator() {
        return (a, b) -> {
            for (int i = 0; i < keys.size(); i++) {
                SortKey k = keys.get(i);
                boolean desc     = k.dir() == SortByDir.DESC;
                boolean nullsFirst = k.nulls() == SortByNulls.FIRST
                        || (k.nulls() == SortByNulls.DEFAULT && desc);
                Object av = a.keyValues()[i];
                Object bv = b.keyValues()[i];
                int c = compareValues(av, bv, nullsFirst);
                if (c != 0) return desc ? -c : c;
            }
            return 0;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object a, Object b, boolean nullsFirst) {
        if (a == null && b == null) return 0;
        if (a == null) return nullsFirst ? -1 : 1;
        if (b == null) return nullsFirst ? 1 : -1;

        // Unwrap CollatedValue
        org.pgjava.types.PgCollation aColl = CollatedValue.collationOf(a);
        org.pgjava.types.PgCollation bColl = CollatedValue.collationOf(b);
        a = CollatedValue.unwrap(a);
        b = CollatedValue.unwrap(b);

        // Numeric promotion
        if (a instanceof Number && b instanceof Number) {
            BigDecimal ba = toBD(a), bb = toBD(b);
            return ba.compareTo(bb);
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
        // String
        if (a instanceof String sa && b instanceof String sb) {
            try {
                org.pgjava.types.PgCollation coll =
                        org.pgjava.types.PgCollation.resolveConflict(aColl, bColl, eval.defaultCollation());
                return coll.compare(sa, sb);
            } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
        }
        // Boolean
        if (a instanceof Boolean ba && b instanceof Boolean bb)
            return Boolean.compare(ba, bb);
        // Row expression: element-wise comparison
        if (a instanceof Object[] aa && b instanceof Object[] bb) {
            int len = Math.min(aa.length, bb.length);
            for (int i = 0; i < len; i++) {
                int c = compareValues(aa[i], bb[i], nullsFirst);
                if (c != 0) return c;
            }
            return Integer.compare(aa.length, bb.length);
        }
        // Temporal cross-type normalization (e.g. LocalDateTime vs OffsetDateTime)
        if (isTemporalType(a) && isTemporalType(b) && !a.getClass().equals(b.getClass())) {
            Object[] norm = normalizeTemporal(a, b);
            a = norm[0]; b = norm[1];
        }
        // Comparable fallback
        if (a instanceof Comparable ca) return ca.compareTo(b);
        return 0;
    }

    private static boolean isTemporalType(Object v) {
        return v instanceof java.time.temporal.Temporal || v instanceof java.time.LocalDate;
    }

    private static Object[] normalizeTemporal(Object l, Object r) {
        if (l instanceof java.time.LocalDateTime ldt && r instanceof java.time.OffsetDateTime) {
            return new Object[]{ldt.atOffset(java.time.ZoneOffset.UTC), r};
        }
        if (l instanceof java.time.OffsetDateTime && r instanceof java.time.LocalDateTime rdt) {
            return new Object[]{l, rdt.atOffset(java.time.ZoneOffset.UTC)};
        }
        if (l instanceof java.time.LocalDate ld && r instanceof java.time.LocalDateTime) {
            return new Object[]{ld.atStartOfDay(), r};
        }
        if (l instanceof java.time.LocalDateTime && r instanceof java.time.LocalDate rd) {
            return new Object[]{l, rd.atStartOfDay()};
        }
        if (l instanceof java.time.LocalDate ld && r instanceof java.time.OffsetDateTime) {
            return new Object[]{ld.atStartOfDay().atOffset(java.time.ZoneOffset.UTC), r};
        }
        if (l instanceof java.time.OffsetDateTime && r instanceof java.time.LocalDate rd) {
            return new Object[]{l, rd.atStartOfDay().atOffset(java.time.ZoneOffset.UTC)};
        }
        if (l instanceof java.time.LocalTime lt && r instanceof java.time.OffsetTime) {
            return new Object[]{lt.atOffset(java.time.ZoneOffset.UTC), r};
        }
        if (l instanceof java.time.OffsetTime && r instanceof java.time.LocalTime rt) {
            return new Object[]{l, rt.atOffset(java.time.ZoneOffset.UTC)};
        }
        return new Object[]{l, r};
    }

    private BigDecimal toBD(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Double d)     return BigDecimal.valueOf(d);
        if (v instanceof Float f)      return BigDecimal.valueOf(f);
        if (v instanceof Long l)       return BigDecimal.valueOf(l);
        return BigDecimal.valueOf(((Number) v).longValue());
    }

    @Override protected java.util.List<Operator> planChildren() { return java.util.List.of(source); }
}
