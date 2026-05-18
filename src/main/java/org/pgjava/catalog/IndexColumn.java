package org.pgjava.catalog;

/** One column entry in an index definition. */
public record IndexColumn(
        String  column,       // column name (or expression — Phase 9+)
        boolean ascending,    // ASC=true, DESC=false
        boolean nullsFirst    // NULLS FIRST / NULLS LAST
) {
    public static IndexColumn asc(String column) {
        return new IndexColumn(column, true, false);
    }
}
