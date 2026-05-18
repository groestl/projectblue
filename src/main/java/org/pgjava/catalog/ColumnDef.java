package org.pgjava.catalog;

import org.pgjava.sql.ast.Expr;
import org.pgjava.types.PgType;

/**
 * Definition of a single column in a table.
 *
 * @param name          column name
 * @param attnum        1-based column number (matches pg_attribute.attnum)
 * @param type          resolved PostgreSQL type
 * @param typmod        type modifier (-1 = none; for varchar(n) = n + 4)
 * @param nullable      false if NOT NULL constraint applies
 * @param defaultExpr   parsed DEFAULT expression, or null
 * @param generated     GENERATED ALWAYS / BY DEFAULT / NONE
 * @param collation     explicit collation name, or null for database default
 */
public record ColumnDef(
        String        name,
        int           attnum,
        PgType        type,
        int           typmod,
        boolean       nullable,
        Expr          defaultExpr,
        GeneratedKind generated,
        String        collation
) {
    /** Convenience: simple nullable, no-default, no-collation column. */
    public static ColumnDef of(String name, int attnum, PgType type) {
        return new ColumnDef(name, attnum, type, -1, true, null, GeneratedKind.NONE, null);
    }

    public static ColumnDef notNull(String name, int attnum, PgType type) {
        return new ColumnDef(name, attnum, type, -1, false, null, GeneratedKind.NONE, null);
    }
}
