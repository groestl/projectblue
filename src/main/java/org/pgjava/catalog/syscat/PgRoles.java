package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code pg_catalog.pg_roles} — single superuser row.
 */
public final class PgRoles implements VirtualTable {

    public static final PgRoles INSTANCE = new PgRoles();

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("rolname",       1,  REG.byOid(PgOid.NAME)),
            ColumnDef.notNull("rolsuper",      2,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("rolinherit",    3,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("rolcreaterole", 4,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("rolcreatedb",   5,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("rolcanlogin",   6,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("rolreplication",7,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("rolbypassrls",  8,  REG.byOid(PgOid.BOOL)),
            ColumnDef.of     ("rolconnlimit",  9, REG.byOid(PgOid.INT4)),
            ColumnDef.of     ("rolpassword",   10, REG.byOid(PgOid.TEXT)),
            ColumnDef.of     ("rolvaliduntil", 11, REG.byOid(PgOid.TIMESTAMPTZ))
    );

    private PgRoles() {}

    @Override public String name()   { return "pg_roles"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{
                "postgres", true, true, true, true, true, true, true,
                -1, null, null
        });
        return rows;
    }
}
