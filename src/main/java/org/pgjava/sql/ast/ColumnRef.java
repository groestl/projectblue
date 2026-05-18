package org.pgjava.sql.ast;

import java.util.List;

/**
 * A column reference, e.g. {@code col}, {@code t.col}, {@code t.*}, {@code *}.
 *
 * @param fields path segments; last may be {@code "*"} for star expansion
 */
public record ColumnRef(List<String> fields) implements Expr {

    public static ColumnRef of(String... parts) {
        return new ColumnRef(List.of(parts));
    }

    public boolean isStar() {
        return !fields.isEmpty() && "*".equals(fields.getLast());
    }
}
