package org.pgjava.executor;

import org.pgjava.sql.ast.SetOpType;
import org.pgjava.storage.Row;

import java.sql.SQLException;
import java.util.*;

/**
 * UNION / INTERSECT / EXCEPT (both ALL and distinct variants).
 */
public final class SetOpOperator extends Operator {

    private final Operator left;
    private final Operator right;
    private final SetOpType opType;
    private final boolean all;

    private Iterator<Row> results;

    public SetOpOperator(Operator left, Operator right, SetOpType opType, boolean all) {
        super(left.schema()); // both sides must have same column count
        this.left   = left;
        this.right  = right;
        this.opType = opType;
        this.all    = all;
    }

    @Override
    public void open() throws SQLException {
        left.open();
        right.open();

        List<Row>          leftRows  = materialize(left);
        List<Row>          rightRows = materialize(right);
        List<Row>          output    = new ArrayList<>();

        switch (opType) {
            case UNION -> {
                if (all) {
                    output.addAll(leftRows);
                    output.addAll(rightRows);
                } else {
                    Set<List<Object>> seen = new LinkedHashSet<>();
                    for (Row r : leftRows)  { seen.add(normalizeKey(r.values())); }
                    for (Row r : rightRows) { seen.add(normalizeKey(r.values())); }
                    for (List<Object> k : seen) output.add(rowOf(k));
                }
            }
            case INTERSECT -> {
                if (all) {
                    // multiset intersection
                    List<List<Object>> rightList = new ArrayList<>();
                    for (Row r : rightRows) rightList.add(normalizeKey(r.values()));
                    for (Row r : leftRows) {
                        List<Object> k = normalizeKey(r.values());
                        if (rightList.remove(k)) output.add(r);
                    }
                } else {
                    Set<List<Object>> rightSet = new HashSet<>();
                    for (Row r : rightRows) rightSet.add(normalizeKey(r.values()));
                    Set<List<Object>> seen = new HashSet<>();
                    for (Row r : leftRows) {
                        List<Object> k = normalizeKey(r.values());
                        if (rightSet.contains(k) && seen.add(k)) output.add(r);
                    }
                }
            }
            case EXCEPT -> {
                if (all) {
                    List<List<Object>> rightList = new ArrayList<>();
                    for (Row r : rightRows) rightList.add(normalizeKey(r.values()));
                    for (Row r : leftRows) {
                        List<Object> k = normalizeKey(r.values());
                        if (!rightList.remove(k)) output.add(r);
                    }
                } else {
                    Set<List<Object>> rightSet = new HashSet<>();
                    for (Row r : rightRows) rightSet.add(normalizeKey(r.values()));
                    Set<List<Object>> seen = new HashSet<>();
                    for (Row r : leftRows) {
                        List<Object> k = normalizeKey(r.values());
                        if (!rightSet.contains(k) && seen.add(k)) output.add(r);
                    }
                }
            }
        }

        left.close();
        right.close();
        results = output.iterator();
    }

    @Override
    public Row next() {
        if (results == null || !results.hasNext()) return null;
        return results.next();
    }

    @Override
    public void close() {
        results = null;
    }

    private List<Row> materialize(Operator op) throws SQLException {
        List<Row> rows = new ArrayList<>();
        Row r;
        while ((r = op.next()) != null) rows.add(r);
        return rows;
    }

    private Row rowOf(List<Object> vals) {
        return new Row(new org.pgjava.storage.RowId(-6, -6), vals.toArray());
    }

    /** Normalize array values to Lists for correct equals/hashCode in Set operations. */
    private static List<Object> normalizeKey(Object[] vals) {
        List<Object> key = new ArrayList<>(vals.length);
        for (Object v : vals) {
            key.add(v instanceof Object[] arr ? Arrays.asList(arr) : v);
        }
        return key;
    }
}
