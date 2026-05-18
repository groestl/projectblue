package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code pg_catalog.pg_constraint} — one row per constraint.
 * contype: p=primary, u=unique, c=check, f=foreign, n=not-null
 */
public final class PgConstraint implements VirtualTable {

    public static final PgConstraint INSTANCE = new PgConstraint();

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("oid",          1,  REG.byOid(PgOid.OID)),
            ColumnDef.notNull("conname",       2,  REG.byOid(PgOid.NAME)),
            ColumnDef.notNull("connamespace",  3,  REG.byOid(PgOid.OID)),
            ColumnDef.notNull("contype",       4,  REG.byOid(PgOid.CHAR)),
            ColumnDef.notNull("condeferrable", 5,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("condeferred",   6,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("convalidated",  7,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("conrelid",      8,  REG.byOid(PgOid.OID)),
            ColumnDef.of     ("confrelid",     9,  REG.byOid(PgOid.OID)),
            ColumnDef.of     ("conkey",        10, REG.byOid(PgOid.INT2_ARRAY)),
            ColumnDef.of     ("confkey",       11, REG.byOid(PgOid.INT2_ARRAY)),
            ColumnDef.of     ("conbin",        12, REG.byOid(PgOid.TEXT))
    );

    private PgConstraint() {}

    @Override public String name()   { return "pg_constraint"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        List<Object[]> rows = new ArrayList<>();
        long syntheticOid = 100000L; // synthetic OIDs well above CatalogManager.oidGen range
        for (Schema schema : catalog.allSchemas().values()) {
            for (TableDef t : schema.tables().values()) {
                for (Constraint c : t.constraints()) {
                    String contype = switch (c) {
                        case Constraint.PrimaryKey pk -> "p";
                        case Constraint.Unique u      -> "u";
                        case Constraint.Check ch      -> "c";
                        case Constraint.ForeignKey fk -> "f";
                        case Constraint.NotNull nn    -> "n";
                    };
                    Long confrelid = null;
                    List<Integer> confkey = null;
                    if (c instanceof Constraint.ForeignKey fk) {
                        // Try to resolve ref table OID
                        try {
                            String refName = fk.refTable();
                            if (fk.refSchema() != null && !fk.refSchema().isEmpty()) {
                                refName = fk.refSchema() + "." + refName;
                            }
                            TableDef ref = catalog.resolveTable(refName,
                                    List.of(schema.name()));
                            confrelid = ref.oid();
                            confkey = columnsToAttnums(fk.refColumns(), ref);
                        } catch (SQLException ignored) {
                            // Referenced table not found — leave confrelid/confkey null
                        }
                    }
                    // Build conkey from constraint columns
                    List<Integer> conkey = columnsToConkey(c, t);

                    rows.add(new Object[]{
                            syntheticOid++,    // oid
                            c.name() != null ? c.name() : "",   // conname
                            schema.oid(),      // connamespace
                            contype,           // contype
                            false,             // condeferrable
                            false,             // condeferred
                            true,              // convalidated
                            t.oid(),           // conrelid
                            confrelid,         // confrelid
                            conkey,            // conkey
                            confkey,           // confkey
                            null               // conbin
                    });
                }
            }
        }
        return rows;
    }

    /** Convert constraint columns to a list of attnums */
    private static List<Integer> columnsToConkey(Constraint c, TableDef t) {
        List<String> cols = switch (c) {
            case Constraint.PrimaryKey pk -> pk.columns();
            case Constraint.Unique u      -> u.columns();
            case Constraint.ForeignKey fk -> fk.columns();
            case Constraint.NotNull nn    -> List.of(nn.column());
            case Constraint.Check ch      -> ch.column() != null ? List.of(ch.column()) : List.of();
        };
        return columnsToAttnums(cols, t);
    }

    /** Convert column names to a list of attnums */
    private static List<Integer> columnsToAttnums(List<String> cols, TableDef t) {
        if (cols == null || cols.isEmpty()) return null;
        List<Integer> attnums = new ArrayList<>();
        for (String colName : cols) {
            int attnum = 0;
            for (var cd : t.columns()) {
                if (cd.name().equalsIgnoreCase(colName)) {
                    attnum = cd.attnum();
                    break;
                }
            }
            attnums.add(attnum);
        }
        return attnums.isEmpty() ? null : attnums;
    }
}
