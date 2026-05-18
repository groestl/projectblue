package org.pgjava.executor;

import org.pgjava.storage.Row;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Eliminates duplicate rows using a hash set.
 * Used for SELECT DISTINCT.
 */
public final class Distinct extends Operator {

    private final Operator source;
    private Set<List<Object>> seen;

    public Distinct(Operator source) {
        super(source.schema());
        this.source = source;
    }

    @Override
    public void open() throws SQLException {
        source.open();
        seen = new HashSet<>();
    }

    @Override
    public Row next() throws SQLException {
        Row row;
        while ((row = source.next()) != null) {
            List<Object> key = normalizeKey(row.values());
            if (seen.add(key)) return row;
        }
        return null;
    }

    /** Wrap Object[] elements as Lists so that List.equals() compares by value. */
    private static List<Object> normalizeKey(Object[] vals) {
        List<Object> key = new java.util.ArrayList<>(vals.length);
        for (Object v : vals) {
            key.add(v instanceof Object[] arr ? Arrays.asList(arr) : v);
        }
        return key;
    }

    @Override
    public void close() {
        source.close();
        seen = null;
    }

    @Override protected java.util.List<Operator> planChildren() { return java.util.List.of(source); }
}
