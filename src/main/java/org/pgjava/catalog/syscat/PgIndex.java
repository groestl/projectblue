package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code pg_catalog.pg_index} — one row per index.
 */
public final class PgIndex implements VirtualTable {

    public static final PgIndex INSTANCE = new PgIndex();

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("indexrelid",      1,  REG.byOid(PgOid.OID)),
            ColumnDef.notNull("indrelid",         2,  REG.byOid(PgOid.OID)),
            ColumnDef.notNull("indnatts",         3,  REG.byOid(PgOid.INT2)),
            ColumnDef.notNull("indnkeyatts",      4,  REG.byOid(PgOid.INT2)),
            ColumnDef.notNull("indisunique",      5,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("indisprimary",     6,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("indisvalid",       7,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("indisready",       8,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("indislive",        9,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("indkey",           10, REG.byOid(PgOid.TEXT)),   // int2vector
            ColumnDef.notNull("indcollation",     11, REG.byOid(PgOid.TEXT)),   // oidvector
            ColumnDef.notNull("indclass",         12, REG.byOid(PgOid.TEXT)),   // oidvector
            ColumnDef.notNull("indoption",        13, REG.byOid(PgOid.TEXT)),   // int2vector
            ColumnDef.of     ("indexprs",         14, REG.byOid(PgOid.TEXT)),
            ColumnDef.of     ("indpred",          15, REG.byOid(PgOid.TEXT))
    );

    private PgIndex() {}

    @Override public String name()   { return "pg_index"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        List<Object[]> rows = new ArrayList<>();
        for (Schema schema : catalog.allSchemas().values()) {
            for (TableDef t : schema.tables().values()) {
                for (IndexDef idx : t.indexes()) {
                    int ncols = idx.columns().size();

                    // indkey: int2vector of column attnums
                    StringBuilder indkey = new StringBuilder();
                    for (int i = 0; i < ncols; i++) {
                        if (i > 0) indkey.append(' ');
                        IndexColumn ic = idx.columns().get(i);
                        ColumnDef col = t.column(ic.column());
                        indkey.append(col != null ? col.attnum() : 0);
                    }

                    // indoption: int2vector — 0 = ASC NULLS LAST (default)
                    // Bit 0 = DESC, Bit 1 = NULLS FIRST
                    StringBuilder indoption = new StringBuilder();
                    for (int i = 0; i < ncols; i++) {
                        if (i > 0) indoption.append(' ');
                        IndexColumn ic = idx.columns().get(i);
                        int opt = 0;
                        if (!ic.ascending()) opt |= 1;   // INDOPTION_DESC
                        if (ic.nullsFirst()) opt |= 2;   // INDOPTION_NULLS_FIRST
                        indoption.append(opt);
                    }

                    // indcollation / indclass: oidvector of zeros (no collation/opclass tracking)
                    StringBuilder zeros = new StringBuilder();
                    for (int i = 0; i < ncols; i++) {
                        if (i > 0) zeros.append(' ');
                        zeros.append('0');
                    }
                    String zeroVec = zeros.toString();

                    rows.add(new Object[]{
                            idx.oid(),              // indexrelid
                            t.oid(),                // indrelid
                            ncols,                  // indnatts
                            ncols,                  // indnkeyatts
                            idx.unique(),           // indisunique
                            idx.primary(),          // indisprimary
                            true,                   // indisvalid
                            true,                   // indisready
                            true,                   // indislive
                            indkey.toString(),      // indkey
                            zeroVec,                // indcollation
                            zeroVec,                // indclass
                            indoption.toString(),   // indoption
                            null,                   // indexprs
                            null                    // indpred
                    });
                }
            }
        }
        return rows;
    }
}
