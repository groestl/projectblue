package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.List;

/**
 * {@code pg_catalog.pg_depend} — dependency relationships between database objects.
 *
 * <p>Stub implementation: always empty. Flyway and other tools LEFT JOIN on this table
 * to find extension-owned objects; returning no rows is semantically correct for our
 * single-schema in-memory database (nothing is extension-owned).
 *
 * <p>deptype values used in the wild:
 * <ul>
 *   <li>'e' — extension dependency (flyway checks for these)
 *   <li>'n' — normal dependency
 *   <li>'a' — auto dependency
 *   <li>'i' — internal dependency
 *   <li>'p' — pin (system-pinned)
 * </ul>
 */
public final class PgDepend implements VirtualTable {

    public static final PgDepend INSTANCE = new PgDepend();

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("classid",      1, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("objid",        2, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("objsubid",     3, REG.byOid(PgOid.INT4)),
            ColumnDef.notNull("refclassid",   4, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("refobjid",     5, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("refobjsubid",  6, REG.byOid(PgOid.INT4)),
            ColumnDef.notNull("deptype",      7, REG.byOid(PgOid.CHAR))
    );

    private PgDepend() {}

    @Override public String name()   { return "pg_depend"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        return List.of(); // always empty — no extension dependencies
    }
}
