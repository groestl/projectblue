package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code pg_catalog.pg_sequence} — one row per sequence.
 */
public final class PgSequence implements VirtualTable {

    public static final PgSequence INSTANCE = new PgSequence();

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("seqrelid",    1, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("seqtypid",    2, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("seqstart",    3, REG.byOid(PgOid.INT8)),
            ColumnDef.notNull("seqincrement",4, REG.byOid(PgOid.INT8)),
            ColumnDef.notNull("seqmax",      5, REG.byOid(PgOid.INT8)),
            ColumnDef.notNull("seqmin",      6, REG.byOid(PgOid.INT8)),
            ColumnDef.notNull("seqcache",    7, REG.byOid(PgOid.INT8)),
            ColumnDef.notNull("seqcycle",    8, REG.byOid(PgOid.BOOL))
    );

    private PgSequence() {}

    @Override public String name()   { return "pg_sequence"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        List<Object[]> rows = new ArrayList<>();
        for (Schema s : catalog.allSchemas().values()) {
            for (SequenceDef seq : s.sequences().values()) {
                rows.add(new Object[]{
                        seq.oid(),
                        (long) PgOid.INT8,   // seqtypid (bigint)
                        seq.start(),
                        seq.increment(),
                        seq.maxVal(),
                        seq.minVal(),
                        1L,                   // seqcache
                        seq.cycle()
                });
            }
        }
        return rows;
    }
}
