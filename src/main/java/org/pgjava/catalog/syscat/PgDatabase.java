package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code pg_catalog.pg_database} — single row for the current database.
 */
public final class PgDatabase implements VirtualTable {

    public static final PgDatabase INSTANCE = new PgDatabase();

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("oid",            1,  REG.byOid(PgOid.OID)),
            ColumnDef.notNull("datname",         2,  REG.byOid(PgOid.NAME)),
            ColumnDef.notNull("datdba",          3,  REG.byOid(PgOid.OID)),
            ColumnDef.notNull("encoding",        4,  REG.byOid(PgOid.INT4)),
            ColumnDef.notNull("datlocprovider",  5,  REG.byOid(PgOid.CHAR)),
            ColumnDef.notNull("datcollate",      6,  REG.byOid(PgOid.NAME)),
            ColumnDef.notNull("datctype",        7,  REG.byOid(PgOid.NAME)),
            ColumnDef.of     ("daticulocale",    8,  REG.byOid(PgOid.NAME)),
            ColumnDef.of     ("daticurules",     9,  REG.byOid(PgOid.TEXT)),
            ColumnDef.of     ("datcollversion",  10, REG.byOid(PgOid.TEXT)),
            ColumnDef.notNull("datistemplate",   11, REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("datallowconn",    12, REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("datconnlimit",    13, REG.byOid(PgOid.INT4)),
            ColumnDef.notNull("datfrozenxid",    14, REG.byOid(PgOid.INT4)),
            ColumnDef.notNull("datminmxid",      15, REG.byOid(PgOid.INT4)),
            ColumnDef.of     ("dattablespace",   16, REG.byOid(PgOid.OID)),
            ColumnDef.of     ("datacl",          17, REG.byOid(PgOid.TEXT))
    );

    private PgDatabase() {}

    @Override public String name()   { return "pg_database"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        List<Object[]> rows = new ArrayList<>();
        long oid = 1L;
        for (String dbName : catalog.allDbNames()) {
            rows.add(new Object[]{
                    oid++,                     // oid
                    dbName,                    // datname
                    10L,                       // datdba (superuser)
                    6,                         // encoding (UTF8 = 6)
                    "c",                       // datlocprovider ('c' = libc)
                    "en_US.UTF-8",             // datcollate
                    "en_US.UTF-8",             // datctype
                    null,                      // daticulocale
                    null,                      // daticurules
                    null,                      // datcollversion
                    false,                     // datistemplate
                    true,                      // datallowconn
                    -1,                        // datconnlimit (-1 = unlimited)
                    0,                         // datfrozenxid
                    0,                         // datminmxid
                    1663L,                     // dattablespace (pg_default)
                    null                       // datacl
            });
        }
        return rows;
    }
}
