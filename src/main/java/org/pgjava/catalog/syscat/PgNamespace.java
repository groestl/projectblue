package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.List;

/**
 * {@code pg_catalog.pg_namespace} — one row per schema.
 * Columns: oid, nspname, nspowner, nspacl
 */
public final class PgNamespace implements VirtualTable {

    public static final PgNamespace INSTANCE = new PgNamespace();

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("oid",      1, PgTypeRegistry.INSTANCE.byOid(PgOid.OID)),
            ColumnDef.notNull("nspname",  2, PgTypeRegistry.INSTANCE.byOid(PgOid.NAME)),
            ColumnDef.of     ("nspowner", 3, PgTypeRegistry.INSTANCE.byOid(PgOid.OID)),
            ColumnDef.of     ("nspacl",   4, PgTypeRegistry.INSTANCE.byOid(PgOid.TEXT))
    );

    private PgNamespace() {}

    @Override public String name()   { return "pg_namespace"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        List<Object[]> rows = new java.util.ArrayList<>();
        // System namespaces
        rows.add(row(11L, "pg_catalog", 10L));
        rows.add(row(13182L, "information_schema", 10L));
        rows.add(row(2200L, "public", 10L));
        // User schemas
        for (Schema s : catalog.allSchemas().values()) {
            if (!s.name().equals("public")) {
                rows.add(row(s.oid(), s.name(), 10L));
            }
        }
        return rows;
    }

    private static Object[] row(long oid, String name, long owner) {
        return new Object[]{oid, name, owner, null};
    }
}
