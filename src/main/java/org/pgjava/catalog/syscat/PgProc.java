package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.List;

/**
 * {@code pg_catalog.pg_proc} — functions and procedures.
 *
 * <p>Stub: returns rows for all registered built-in functions.
 * ORM tools (Flyway, Hibernate) query this to enumerate user-defined functions;
 * returning only pg_catalog builtins keeps the public schema appearing empty.
 */
public final class PgProc implements VirtualTable {

    public static final PgProc INSTANCE = new PgProc();

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("oid",            1, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("proname",         2, REG.byOid(PgOid.NAME)),
            ColumnDef.notNull("pronamespace",    3, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("proowner",        4, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("prolang",         5, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("prokind",         6, REG.byOid(PgOid.CHAR)),    // f/a/p/w
            ColumnDef.notNull("prosecdef",       7, REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("proisstrict",     8, REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("proretset",       9, REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("provolatile",     10, REG.byOid(PgOid.CHAR)),
            ColumnDef.notNull("pronargs",        11, REG.byOid(PgOid.INT2)),
            ColumnDef.notNull("proargtypes",     12, REG.byOid(PgOid.TEXT)),    // oidvector
            ColumnDef.of     ("proallargtypes",  13, REG.byOid(PgOid.TEXT)),    // oid[]
            ColumnDef.of     ("proargmodes",     14, REG.byOid(PgOid.TEXT)),    // char[]
            ColumnDef.of     ("proargnames",     15, REG.byOid(PgOid.TEXT)),    // text[]
            ColumnDef.of     ("prorettype",      16, REG.byOid(PgOid.OID)),
            ColumnDef.of     ("prosrc",          17, REG.byOid(PgOid.TEXT)),
            ColumnDef.of     ("probin",          18, REG.byOid(PgOid.TEXT))
    );

    private PgProc() {}

    @Override public String name()   { return "pg_proc"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    // pg_catalog namespace OID (used as pronamespace for built-ins)
    private static final long PG_CATALOG_NSP_OID = 11L;
    private static final long INTERNAL_LANG_OID  = 12L;

    private static final long PLPGSQL_LANG_OID = 13138L; // pg_language oid for plpgsql

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        var rows = new java.util.ArrayList<Object[]>();

        // Scalar / set-returning functions
        for (var fn : catalog.functions().allScalars()) {
            long nspOid = PG_CATALOG_NSP_OID;
            boolean isUserDefined = false;
            if (fn.schemaName() != null && !"pg_catalog".equals(fn.schemaName())) {
                Schema schema = catalog.allSchemas().get(fn.schemaName());
                nspOid = schema != null ? schema.oid() : 2200L;
                isUserDefined = true;
            }
            long langOid = isUserDefined ? PLPGSQL_LANG_OID : INTERNAL_LANG_OID;

            // proargtypes: oidvector of input argument type OIDs
            String proargtypes = buildOidVector(fn.argTypes());

            // proargnames: text array literal, or null if no names
            String proargnames = fn.argNames() != null && !fn.argNames().isEmpty()
                    ? buildTextArray(fn.argNames()) : null;

            rows.add(new Object[]{
                fn.oid(),                       // oid
                fn.name(),                      // proname
                nspOid,                         // pronamespace
                10L,                            // proowner
                langOid,                        // prolang
                "f",                            // prokind (function)
                false,                          // prosecdef
                fn.strict(),                    // proisstrict
                false,                          // proretset
                isUserDefined ? "v" : "i",      // provolatile
                (int) fn.argTypes().size(),      // pronargs
                proargtypes,                    // proargtypes
                null,                           // proallargtypes (null = all are IN)
                null,                           // proargmodes    (null = all are IN)
                proargnames,                    // proargnames
                fn.returnType() != null ? (long) fn.returnType().oid() : null, // prorettype
                fn.source(),                    // prosrc
                null                            // probin
            });
        }

        // Aggregate functions
        for (var agg : catalog.functions().allAggregates()) {
            long nspOid = PG_CATALOG_NSP_OID;
            if (agg.schemaName() != null && !"pg_catalog".equals(agg.schemaName())) {
                Schema schema = catalog.allSchemas().get(agg.schemaName());
                nspOid = schema != null ? schema.oid() : 2200L;
            }

            String proargtypes = buildOidVector(agg.argTypes());

            rows.add(new Object[]{
                agg.oid(),                      // oid
                agg.name(),                     // proname
                nspOid,                         // pronamespace
                10L,                            // proowner
                INTERNAL_LANG_OID,              // prolang
                "a",                            // prokind (aggregate)
                false,                          // prosecdef
                false,                          // proisstrict
                false,                          // proretset
                "i",                            // provolatile (aggregates are immutable)
                (int) agg.argTypes().size(),     // pronargs
                proargtypes,                    // proargtypes
                null,                           // proallargtypes
                null,                           // proargmodes
                null,                           // proargnames
                agg.returnType() != null ? (long) agg.returnType().oid() : null, // prorettype
                null,                           // prosrc
                null                            // probin
            });
        }

        return rows;
    }

    /** Build an oidvector string (space-separated type OIDs). */
    private static String buildOidVector(java.util.List<org.pgjava.types.PgType> types) {
        if (types.isEmpty()) return "";
        var sb = new StringBuilder();
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(types.get(i).oid());
        }
        return sb.toString();
    }

    /** Build a PostgreSQL text array literal: {name1,name2,...}. */
    private static String buildTextArray(java.util.List<String> names) {
        var sb = new StringBuilder("{");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(names.get(i));
        }
        sb.append('}');
        return sb.toString();
    }
}
