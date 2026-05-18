package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.List;

/**
 * {@code pg_catalog.pg_rewrite} — query rewrite rules.
 *
 * <p>Stub: always empty. Rules are not implemented in pgjava.
 * Flyway queries this as part of its schema-empty check.
 */
public final class PgRewrite implements VirtualTable {

    public static final PgRewrite INSTANCE = new PgRewrite();

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("oid",       1, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("rulename",  2, REG.byOid(PgOid.NAME)),
            ColumnDef.notNull("ev_class",  3, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("ev_type",   4, REG.byOid(PgOid.CHAR)),
            ColumnDef.notNull("ev_enabled",5, REG.byOid(PgOid.CHAR)),
            ColumnDef.notNull("is_instead",6, REG.byOid(PgOid.BOOL))
    );

    private PgRewrite() {}

    @Override public String name()   { return "pg_rewrite"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        return List.of();
    }
}
