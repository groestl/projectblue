package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code pg_catalog.pg_class} — one row per table, index, sequence, or view.
 *
 * <p>relkind values: r=table, i=index, S=sequence, v=view, m=matview, c=composite, t=TOAST, f=foreign
 *
 * <p>Subset of columns implemented here — full column list added in Phase 5b.
 */
public final class PgClass implements VirtualTable {

    public static final PgClass INSTANCE = new PgClass();

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("oid",           1,  REG.byOid(PgOid.OID)),
            ColumnDef.notNull("relname",        2,  REG.byOid(PgOid.NAME)),
            ColumnDef.notNull("relnamespace",   3,  REG.byOid(PgOid.OID)),
            ColumnDef.notNull("relkind",        4,  REG.byOid(PgOid.CHAR)),
            ColumnDef.notNull("relnatts",       5,  REG.byOid(PgOid.INT2)),
            ColumnDef.notNull("relhasindex",    6,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("relpersistence", 7,  REG.byOid(PgOid.CHAR)),
            ColumnDef.notNull("relisshared",    8,  REG.byOid(PgOid.BOOL)),
            ColumnDef.of     ("relowner",       9,  REG.byOid(PgOid.OID)),
            ColumnDef.of     ("reltype",        10, REG.byOid(PgOid.OID)),
            ColumnDef.of     ("relam",          11, REG.byOid(PgOid.OID)),
            ColumnDef.of     ("relpages",       12, REG.byOid(PgOid.INT4)),
            ColumnDef.of     ("reltuples",      13, REG.byOid(PgOid.FLOAT4))
    );

    private PgClass() {}

    @Override public String name()   { return "pg_class"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        List<Object[]> rows = new ArrayList<>();
        for (Schema schema : catalog.allSchemas().values()) {
            long nspOid = schema.oid();
            // Tables
            for (TableDef t : schema.tables().values()) {
                rows.add(row(t.oid(), t.name(), nspOid, "r",
                        t.columnCount(), !t.indexes().isEmpty(),
                        t.isTemp() ? "t" : "p"));
            }
            // Indexes
            for (IndexDef idx : schema.indexes().values()) {
                rows.add(row(idx.oid(), idx.name(), nspOid, "i",
                        idx.columns().size(), false, "p"));
            }
            // Sequences
            for (SequenceDef seq : schema.sequences().values()) {
                rows.add(row(seq.oid(), seq.name(), nspOid, "S", 0, false, "p"));
            }
            // Views
            for (ViewDef v : schema.views().values()) {
                int viewCols = !v.columnAliases().isEmpty() ? v.columnAliases().size()
                        : (v.parsedDef() != null && v.parsedDef().targetList() != null
                                ? v.parsedDef().targetList().size() : 0);
                rows.add(row(v.oid(), v.name(), nspOid, "v", viewCols, false, "p"));
            }
        }
        return rows;
    }

    private static Object[] row(long oid, String name, long nspOid, String kind,
                                 int natts, boolean hasIndex, String persistence) {
        Long relam = switch (kind) {
            case "r" -> PgAm.HEAP_OID;   // tables use heap AM
            case "i" -> PgAm.BTREE_OID;  // indexes use btree AM
            default  -> null;
        };
        return new Object[]{
                oid, name, nspOid, kind,
                natts, hasIndex, persistence,
                false,   // relisshared
                10L,     // relowner (superuser)
                null,    // reltype
                relam,   // relam
                0,       // relpages
                0.0f     // reltuples
        };
    }
}
