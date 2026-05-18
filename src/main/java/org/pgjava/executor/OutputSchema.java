package org.pgjava.executor;

import org.pgjava.catalog.ColumnDef;
import org.pgjava.catalog.TableDef;
import org.pgjava.storage.Row;
import org.pgjava.storage.RowId;

import java.util.*;

/**
 * Describes the output columns of an {@link Operator}.
 *
 * <p>Each column has:
 * <ul>
 *   <li>An alias (the table alias from which the column came; empty string for
 *       computed columns and projections without a source table)</li>
 *   <li>A column name (lowercase)</li>
 * </ul>
 *
 * <p>This schema is used at runtime to build an {@link EvalContext} from any
 * row produced by the operator, enabling expression evaluation.
 */
public final class OutputSchema {

    /** Per-column alias (may be empty string). */
    private final String[] aliases;
    /** Per-column name (lowercase). */
    private final String[] names;

    // Lazy-built indices
    private volatile Map<String, List<Integer>> aliasPositions; // alias → list of col indices

    private OutputSchema(String[] aliases, String[] names) {
        this.aliases = aliases;
        this.names   = names;
    }

    // -------------------------------------------------------------------------
    // Factories

    /** Schema for a full table scan with a given alias. */
    public static OutputSchema ofTable(String alias, TableDef t) {
        List<ColumnDef> cols = t.columns();
        String[] a = new String[cols.size()];
        String[] n = new String[cols.size()];
        Arrays.fill(a, alias.toLowerCase());
        for (int i = 0; i < cols.size(); i++) n[i] = cols.get(i).name().toLowerCase();
        return new OutputSchema(a, n);
    }

    /** Schema for computed / unnamed columns (e.g. after Project). */
    public static OutputSchema ofNames(List<String> names) {
        String[] a = new String[names.size()];
        String[] n = new String[names.size()];
        Arrays.fill(a, "");
        for (int i = 0; i < names.size(); i++) n[i] = names.get(i).toLowerCase();
        return new OutputSchema(a, n);
    }

    /** Schema for computed columns all sharing the same table alias. */
    public static OutputSchema ofNamesWithAlias(String alias, List<String> names) {
        String[] a = new String[names.size()];
        String[] n = new String[names.size()];
        Arrays.fill(a, alias.toLowerCase());
        for (int i = 0; i < names.size(); i++) n[i] = names.get(i).toLowerCase();
        return new OutputSchema(a, n);
    }

    /** Schema for a single column with an explicit alias. */
    public static OutputSchema ofSingle(String alias, String name) {
        return new OutputSchema(new String[]{alias.toLowerCase()},
                new String[]{name.toLowerCase()});
    }

    // -------------------------------------------------------------------------
    // Structural

    /** Width of this schema (number of columns). */
    public int width() { return aliases.length; }

    public String alias(int i) { return aliases[i]; }
    public String name(int i)  { return names[i]; }

    /** Column names in order. */
    public List<String> names() { return List.of(names); }

    /**
     * Concatenate this schema with another (for JOIN output).
     * The other schema's positions are offset by {@code this.width()}.
     */
    public OutputSchema join(OutputSchema right) {
        int l = aliases.length, r = right.aliases.length;
        String[] a = Arrays.copyOf(aliases, l + r);
        String[] n = Arrays.copyOf(names,   l + r);
        System.arraycopy(right.aliases, 0, a, l, r);
        System.arraycopy(right.names,   0, n, l, r);
        return new OutputSchema(a, n);
    }

    // -------------------------------------------------------------------------
    // EvalContext building

    /**
     * Build an {@link EvalContext} that can evaluate expressions over the given
     * row produced by this operator.
     *
     * <p>Columns with the same alias are grouped into a synthetic sub-Row.
     * Column references like {@code alias.col} or just {@code col} resolve
     * correctly via the multi-alias EvalContext.
     */
    public EvalContext buildContext(Row row) {
        Map<String, List<Integer>> byAlias = aliasPositionsMap();

        Map<String, Row>                  rows    = new LinkedHashMap<>();
        Map<String, EvalContext.ColumnMap> colMaps = new LinkedHashMap<>();

        for (Map.Entry<String, List<Integer>> e : byAlias.entrySet()) {
            String       alias   = e.getKey();
            List<Integer> pos    = e.getValue();
            Object[]     vals    = new Object[pos.size()];
            List<String> colNames = new ArrayList<>(pos.size());
            for (int i = 0; i < pos.size(); i++) {
                vals[i] = row.get(pos.get(i));
                colNames.add(names[pos.get(i)]);
            }
            rows.put(alias, new Row(new RowId(0, 0), vals));
            colMaps.put(alias, EvalContext.ColumnMap.of(colNames));
        }
        return EvalContext.ofJoin(rows, colMaps);
    }

    /** Alias-to-position-list map (lazily computed, thread-safe). */
    private Map<String, List<Integer>> aliasPositionsMap() {
        if (aliasPositions == null) {
            synchronized (this) {
                if (aliasPositions == null) {
                    Map<String, List<Integer>> m = new LinkedHashMap<>();
                    for (int i = 0; i < aliases.length; i++) {
                        m.computeIfAbsent(aliases[i], k -> new ArrayList<>()).add(i);
                    }
                    aliasPositions = Collections.unmodifiableMap(m);
                }
            }
        }
        return aliasPositions;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("OutputSchema[");
        for (int i = 0; i < aliases.length; i++) {
            if (i > 0) sb.append(", ");
            if (!aliases[i].isEmpty()) sb.append(aliases[i]).append('.');
            sb.append(names[i]);
        }
        return sb.append(']').toString();
    }
}
