package org.pgjava.sql.ast;

import java.util.List;

/**
 * A table reference used in FROM clauses and DDL.
 *
 * @param schemaName  null when unqualified
 * @param relName     table/view name
 * @param alias       alias name, null if none
 * @param inh         true unless ONLY keyword present
 * @param colAliases  column aliases from {@code AS alias(col1,col2,...)}; empty list if none
 */
public record RangeVar(String schemaName, String relName, String alias, boolean inh,
                       List<String> colAliases)
        implements FromItem {

    /** Backwards-compatible constructor with no column aliases. */
    public RangeVar(String schemaName, String relName, String alias, boolean inh) {
        this(schemaName, relName, alias, inh, List.of());
    }

    public static RangeVar of(String schema, String rel) {
        return new RangeVar(schema, rel, null, true);
    }
    public static RangeVar of(String rel) {
        return new RangeVar(null, rel, null, true);
    }

    /** Qualified name suitable for display: {@code schema.rel} or just {@code rel}. */
    public String qualifiedName() {
        return schemaName != null ? schemaName + "." + relName : relName;
    }
}
