package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code pg_catalog.pg_attribute} — one row per column per table.
 * Key columns used by JDBC metadata, Hibernate, and jOOQ.
 */
public final class PgAttribute implements VirtualTable {

    public static final PgAttribute INSTANCE = new PgAttribute();

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("attrelid",    1,  REG.byOid(PgOid.OID)),
            ColumnDef.notNull("attname",     2,  REG.byOid(PgOid.NAME)),
            ColumnDef.notNull("atttypid",    3,  REG.byOid(PgOid.OID)),
            ColumnDef.notNull("attnum",      4,  REG.byOid(PgOid.INT2)),
            ColumnDef.notNull("attnotnull",  5,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("atthasdef",   6,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("attisdropped",7,  REG.byOid(PgOid.BOOL)),
            ColumnDef.of     ("atttypmod",   8,  REG.byOid(PgOid.INT4)),
            ColumnDef.of     ("attlen",      9,  REG.byOid(PgOid.INT2)),
            ColumnDef.of     ("attidentity", 10, REG.byOid(PgOid.CHAR)),
            ColumnDef.of     ("attgenerated",11, REG.byOid(PgOid.CHAR)),
            ColumnDef.notNull("attstattarget",12,REG.byOid(PgOid.INT4)),
            ColumnDef.notNull("attndims",    13, REG.byOid(PgOid.INT4))
    );

    private PgAttribute() {}

    @Override public String name()   { return "pg_attribute"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        List<Object[]> rows = new ArrayList<>();
        for (Schema schema : catalog.allSchemas().values()) {
            for (TableDef t : schema.tables().values()) {
                for (ColumnDef col : t.columns()) {
                    rows.add(new Object[]{
                            t.oid(),                          // attrelid
                            col.name(),                       // attname
                            (long) col.type().oid(),          // atttypid
                            col.attnum(),                     // attnum
                            !col.nullable(),                  // attnotnull
                            col.defaultExpr() != null,        // atthasdef
                            false,                            // attisdropped
                            col.typmod(),                     // atttypmod
                            null,                             // attlen
                            col.generated() == GeneratedKind.BY_DEFAULT ? "d" :
                            col.generated() == GeneratedKind.ALWAYS     ? "a" : " ", // attidentity
                            col.generated() == GeneratedKind.ALWAYS     ? "s" : " ", // attgenerated
                            -1,                               // attstattarget
                            0                                 // attndims
                    });
                }
            }
        }
        return rows;
    }
}
