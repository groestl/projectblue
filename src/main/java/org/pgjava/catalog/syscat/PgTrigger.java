package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code pg_catalog.pg_trigger} — triggers on tables/views.
 *
 * <p>Returns real trigger data from the catalog.
 */
public final class PgTrigger implements VirtualTable {

    public static final PgTrigger INSTANCE = new PgTrigger();

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("oid",            1, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("tgrelid",        2, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("tgname",         3, REG.byOid(PgOid.NAME)),
            ColumnDef.notNull("tgfoid",         4, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("tgtype",         5, REG.byOid(PgOid.INT2)),
            ColumnDef.notNull("tgenabled",      6, REG.byOid(PgOid.CHAR)),
            ColumnDef.notNull("tgisinternal",   7, REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("tgconstrrelid",  8, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("tgconstrindid",  9, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("tgconstraint",  10, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("tgdeferrable",  11, REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("tginitdeferred",12, REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("tgnargs",       13, REG.byOid(PgOid.INT2)),
            ColumnDef.of     ("tgattr",        14, REG.byOid(PgOid.TEXT)),
            ColumnDef.of     ("tgargs",        15, REG.byOid(PgOid.TEXT)),
            ColumnDef.of     ("tgqual",        16, REG.byOid(PgOid.TEXT)),
            ColumnDef.of     ("tgoldtable",    17, REG.byOid(PgOid.NAME)),
            ColumnDef.of     ("tgnewtable",    18, REG.byOid(PgOid.NAME))
    );

    private PgTrigger() {}

    @Override public String name()   { return "pg_trigger"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        List<Object[]> rows = new ArrayList<>();
        for (Schema s : catalog.allSchemas().values()) {
            for (TableDef t : s.tables().values()) {
                for (TriggerDef trig : t.triggers()) {
                    rows.add(buildTriggerRow(trig, t));
                }
            }
            // Include INSTEAD OF triggers on views
            for (ViewDef v : s.views().values()) {
                for (TriggerDef trig : v.triggers()) {
                    rows.add(buildTriggerRow(trig, null));
                }
            }
        }
        return rows;
    }

    private Object[] buildTriggerRow(TriggerDef trig, TableDef t) {
        // Build tgattr: column attnum array for UPDATE OF columns
        String tgattr = null;
        if (trig.columns() != null && !trig.columns().isEmpty() && t != null) {
            StringBuilder sb = new StringBuilder();
            for (String col : trig.columns()) {
                ColumnDef cd = t.column(col);
                if (cd != null) {
                    if (!sb.isEmpty()) sb.append(' ');
                    sb.append(cd.attnum());
                }
            }
            tgattr = sb.toString();
        }
        // Build tgargs: trigger function arguments
        String tgargs = trig.args() != null && !trig.args().isEmpty()
                ? String.join("\\000", trig.args()) : null;
        return new Object[]{
                trig.oid(),              // oid
                trig.tableOid(),         // tgrelid
                trig.name(),             // tgname
                trig.functionOid(),      // tgfoid
                trig.tgtype(),           // tgtype
                "O",                     // tgenabled
                false,                   // tgisinternal
                0L,                      // tgconstrrelid
                0L,                      // tgconstrindid
                0L,                      // tgconstraint
                false,                   // tgdeferrable
                false,                   // tginitdeferred
                (short)(trig.args() != null ? trig.args().size() : 0), // tgnargs
                tgattr,                  // tgattr
                tgargs,                  // tgargs
                trig.whenClause() != null ? trig.whenClause().toString() : null, // tgqual
                null,                    // tgoldtable (transition table not yet modeled)
                null                     // tgnewtable
        };
    }
}
