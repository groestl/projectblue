package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.sql.ast.Expr;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code pg_catalog.pg_attrdef} — column default expressions.
 *
 * <p>One row per column that has a DEFAULT value. Queried by psql's {@code \d}
 * command and by {@code pg_get_expr(adbin, adrelid)} to display default values.
 */
public final class PgAttrdef implements VirtualTable {

    public static final PgAttrdef INSTANCE = new PgAttrdef();

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("oid",      1, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("adrelid",  2, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("adnum",    3, REG.byOid(PgOid.INT2)),
            ColumnDef.notNull("adbin",    4, REG.byOid(PgOid.TEXT))
    );

    private PgAttrdef() {}

    @Override public String name()   { return "pg_attrdef"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        List<Object[]> rows = new ArrayList<>();
        for (Schema schema : catalog.allSchemas().values()) {
            for (TableDef t : schema.tables().values()) {
                for (ColumnDef col : t.columns()) {
                    Expr def = col.defaultExpr();
                    if (def == null) continue;
                    // Deterministic OID based on table OID + attnum, stable across scans
                    long synOid = 60000L + t.oid() * 100L + col.attnum();
                    rows.add(new Object[]{
                            synOid,             // oid
                            t.oid(),            // adrelid
                            col.attnum(),       // adnum
                            def.toString()      // adbin (text representation)
                    });
                }
            }
        }
        return rows;
    }
}
