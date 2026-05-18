package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.List;

/**
 * {@code pg_catalog.pg_description} — object comments/descriptions.
 *
 * <p>Stub implementation: always empty. psql's {@code \d} command LEFT JOINs on
 * this table to show the "Description" column; returning zero rows is correct
 * when no COMMENT ON statements have been executed.
 */
public final class PgDescription implements VirtualTable {

    public static final PgDescription INSTANCE = new PgDescription();

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("objoid",      1, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("classoid",    2, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("objsubid",    3, REG.byOid(PgOid.INT4)),
            ColumnDef.of     ("description", 4, REG.byOid(PgOid.TEXT))
    );

    private PgDescription() {}

    @Override public String name()   { return "pg_description"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        return List.of(); // no comments stored yet
    }
}
