package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.List;

/**
 * {@code pg_catalog.pg_collation} — collation definitions.
 *
 * <p>Pre-populated with the built-in collations that PostgreSQL provides:
 * {@code default}, {@code C}, {@code POSIX}, {@code en_US}, {@code ucs_basic}.
 */
public final class PgCollationTable implements VirtualTable {

    public static final PgCollationTable INSTANCE = new PgCollationTable();

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    // Real PostgreSQL OIDs for built-in collations
    public static final long DEFAULT_OID   = 100;
    public static final long C_OID         = 950;
    public static final long POSIX_OID     = 951;
    public static final long EN_US_OID     = 12600;
    public static final long UCS_BASIC_OID = 12584;

    // pg_catalog namespace OID
    private static final long PG_CATALOG_NS = 11L;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("oid",            1, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("collname",       2, REG.byOid(PgOid.NAME)),
            ColumnDef.notNull("collnamespace",  3, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("collowner",      4, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("collprovider",   5, REG.byOid(PgOid.CHAR)),
            ColumnDef.notNull("collisdeterministic", 6, REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("collencoding",   7, REG.byOid(PgOid.INT4)),
            ColumnDef.of("collcollate",         8, REG.byOid(PgOid.NAME)),
            ColumnDef.of("collctype",           9, REG.byOid(PgOid.NAME)),
            ColumnDef.of("colliculocale",      10, REG.byOid(PgOid.TEXT)),
            ColumnDef.of("collversion",        11, REG.byOid(PgOid.TEXT))
    );

    private PgCollationTable() {}

    @Override public String name()   { return "pg_collation"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        return List.of(
                new Object[]{ DEFAULT_OID,   "default",   PG_CATALOG_NS, 10L, "d", true, -1, "en_US.UTF-8", "en_US.UTF-8", null, null },
                new Object[]{ C_OID,         "C",         PG_CATALOG_NS, 10L, "c", true, -1, "C",           "C",           null, null },
                new Object[]{ POSIX_OID,     "POSIX",     PG_CATALOG_NS, 10L, "c", true, -1, "C",           "C",           null, null },
                new Object[]{ EN_US_OID,     "en_US",     PG_CATALOG_NS, 10L, "c", true,  6, "en_US.UTF-8", "en_US.UTF-8", null, null },
                new Object[]{ UCS_BASIC_OID, "ucs_basic", PG_CATALOG_NS, 10L, "c", true,  6, "C",           "C",           null, null }
        );
    }
}
