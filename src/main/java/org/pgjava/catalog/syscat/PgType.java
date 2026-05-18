package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.*;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code pg_catalog.pg_type} — one row per registered type.
 * Includes all built-in scalars and arrays from {@link PgTypeRegistry}.
 */
public final class PgType implements VirtualTable {

    public static final PgType INSTANCE = new PgType();

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("oid",           1,  REG.byOid(PgOid.OID)),
            ColumnDef.notNull("typname",        2,  REG.byOid(PgOid.NAME)),
            ColumnDef.notNull("typnamespace",   3,  REG.byOid(PgOid.OID)),
            ColumnDef.notNull("typowner",       4,  REG.byOid(PgOid.OID)),
            ColumnDef.notNull("typlen",         5,  REG.byOid(PgOid.INT2)),
            ColumnDef.notNull("typbyval",       6,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("typtype",        7,  REG.byOid(PgOid.CHAR)),   // b=base, c=composite, d=domain, e=enum, p=pseudo, r=range
            ColumnDef.notNull("typcategory",    8,  REG.byOid(PgOid.CHAR)),
            ColumnDef.notNull("typispreferred", 9,  REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("typisdefined",   10, REG.byOid(PgOid.BOOL)),
            ColumnDef.notNull("typdelim",       11, REG.byOid(PgOid.CHAR)),
            ColumnDef.of     ("typrelid",       12, REG.byOid(PgOid.OID)),
            ColumnDef.of     ("typelem",        13, REG.byOid(PgOid.OID)),    // element type for arrays
            ColumnDef.of     ("typarray",       14, REG.byOid(PgOid.OID)),    // array type OID
            ColumnDef.of     ("typnotnull",     15, REG.byOid(PgOid.BOOL)),
            ColumnDef.of     ("typbasetype",    16, REG.byOid(PgOid.OID)),    // for domains
            ColumnDef.of     ("typtypmod",      17, REG.byOid(PgOid.INT4)),
            ColumnDef.of     ("typndims",       18, REG.byOid(PgOid.INT4)),
            ColumnDef.of     ("typdefault",     19, REG.byOid(PgOid.TEXT))
    );

    private static final long PG_CATALOG_NSP_OID = 11L;

    private PgType() {}

    @Override public String name()   { return "pg_type"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        List<Object[]> rows = new ArrayList<>();

        // Built-in scalar types
        for (int oid : BUILTIN_SCALAR_OIDS) {
            org.pgjava.types.PgType t = REG.byOid(oid);
            if (t instanceof ScalarType st) {
                Integer arrayOid = ELEM_TO_ARRAY.get(oid);
                rows.add(new Object[]{
                        (long) oid,
                        st.name(),
                        PG_CATALOG_NSP_OID,
                        10L,                       // typowner
                        typlen(oid),               // typlen
                        typbyval(oid),             // typbyval
                        "b",                       // typtype = base
                        String.valueOf(st.category()), // typcategory
                        st.preferred(),            // typispreferred
                        true,                      // typisdefined
                        ",",                       // typdelim
                        null,                      // typrelid
                        null,                      // typelem (arrays only)
                        arrayOid != null ? (long) arrayOid.intValue() : null, // typarray
                        false,                     // typnotnull
                        null,                      // typbasetype
                        -1,                        // typtypmod
                        0,                         // typndims
                        null                       // typdefault
                });
            }
        }

        // Built-in array types
        for (var entry : ELEM_TO_ARRAY.entrySet()) {
            int elemOid  = entry.getKey();
            int arrayOid = entry.getValue();
            org.pgjava.types.PgType t = REG.byOid(arrayOid);
            if (t instanceof ArrayType at) {
                rows.add(new Object[]{
                        (long) arrayOid,
                        at.name(),
                        PG_CATALOG_NSP_OID,
                        10L,
                        -1,                        // typlen = variable
                        false,                     // typbyval
                        "b",                       // typtype
                        "A",                       // typcategory = array
                        false,                     // typispreferred
                        true,
                        ",",
                        null,
                        (long) elemOid,            // typelem
                        null,                      // typarray (arrays of arrays not tracked)
                        false, null, -1, 1, null
                });
            }
        }

        // User-defined types (ENUM, DOMAIN)
        for (Schema s : catalog.allSchemas().values()) {
            long nspOid = s.oid();
            for (org.pgjava.types.PgType udt : s.types().values()) {
                if (udt instanceof org.pgjava.types.EnumType et) {
                    rows.add(new Object[]{
                            (long) et.oid(),
                            et.name(),
                            nspOid,
                            10L,               // typowner
                            4,                 // typlen (enum stored as oid internally)
                            true,              // typbyval
                            "e",               // typtype = enum
                            "E",               // typcategory = enum
                            false,             // typispreferred
                            true,              // typisdefined
                            ",",               // typdelim
                            null, null, null,  // typrelid, typelem, typarray
                            false, null, -1, 0, null
                    });
                } else if (udt instanceof org.pgjava.types.DomainType dt) {
                    rows.add(new Object[]{
                            (long) dt.oid(),
                            dt.name(),
                            nspOid,
                            10L,
                            -1,                // typlen (same as base)
                            false,             // typbyval
                            "d",               // typtype = domain
                            "U",               // typcategory = user-defined
                            false,
                            true,
                            ",",
                            null, null, null,
                            dt.notNull(),                     // typnotnull
                            (long) dt.baseType().oid(),       // typbasetype
                            -1, 0, null
                    });
                }
            }
        }

        return rows;
    }

    // -------------------------------------------------------------------------

    private static int typlen(int oid) {
        return switch (oid) {
            case PgOid.BOOL   -> 1;
            case PgOid.INT2   -> 2;
            case PgOid.INT4, PgOid.FLOAT4, PgOid.OID, PgOid.DATE -> 4;
            case PgOid.INT8, PgOid.FLOAT8, PgOid.TIMESTAMP,
                 PgOid.TIMESTAMPTZ, PgOid.TIME,
                 PgOid.TIMETZ, PgOid.INTERVAL -> 8;
            default -> -1; // variable
        };
    }

    private static boolean typbyval(int oid) {
        return switch (oid) {
            case PgOid.BOOL, PgOid.INT2, PgOid.INT4, PgOid.FLOAT4, PgOid.OID -> true;
            default -> false;
        };
    }

    private static final int[] BUILTIN_SCALAR_OIDS = {
            PgOid.BOOL, PgOid.BYTEA, PgOid.CHAR, PgOid.NAME, PgOid.INT8,
            PgOid.INT2, PgOid.INT4, PgOid.TEXT, PgOid.OID, PgOid.XML,
            PgOid.JSON, PgOid.FLOAT4, PgOid.FLOAT8, PgOid.UNKNOWN,
            PgOid.MONEY, PgOid.MACADDR, PgOid.INET, PgOid.CIDR,
            PgOid.BPCHAR, PgOid.VARCHAR, PgOid.DATE, PgOid.TIME,
            PgOid.TIMESTAMP, PgOid.TIMESTAMPTZ, PgOid.INTERVAL, PgOid.TIMETZ,
            PgOid.BIT, PgOid.VARBIT, PgOid.NUMERIC, PgOid.VOID,
            PgOid.UUID, PgOid.PG_LSN, PgOid.JSONB
    };

    private static final java.util.Map<Integer, Integer> ELEM_TO_ARRAY =
            java.util.Map.ofEntries(
                    java.util.Map.entry(PgOid.BOOL,        PgOid.BOOL_ARRAY),
                    java.util.Map.entry(PgOid.BYTEA,       PgOid.BYTEA_ARRAY),
                    java.util.Map.entry(PgOid.INT2,        PgOid.INT2_ARRAY),
                    java.util.Map.entry(PgOid.INT4,        PgOid.INT4_ARRAY),
                    java.util.Map.entry(PgOid.INT8,        PgOid.INT8_ARRAY),
                    java.util.Map.entry(PgOid.TEXT,        PgOid.TEXT_ARRAY),
                    java.util.Map.entry(PgOid.OID,         PgOid.OID_ARRAY),
                    java.util.Map.entry(PgOid.FLOAT4,      PgOid.FLOAT4_ARRAY),
                    java.util.Map.entry(PgOid.FLOAT8,      PgOid.FLOAT8_ARRAY),
                    java.util.Map.entry(PgOid.BPCHAR,      PgOid.BPCHAR_ARRAY),
                    java.util.Map.entry(PgOid.VARCHAR,     PgOid.VARCHAR_ARRAY),
                    java.util.Map.entry(PgOid.DATE,        PgOid.DATE_ARRAY),
                    java.util.Map.entry(PgOid.TIME,        PgOid.TIME_ARRAY),
                    java.util.Map.entry(PgOid.TIMESTAMP,   PgOid.TIMESTAMP_ARRAY),
                    java.util.Map.entry(PgOid.TIMESTAMPTZ, PgOid.TIMESTAMPTZ_ARRAY),
                    java.util.Map.entry(PgOid.INTERVAL,    PgOid.INTERVAL_ARRAY),
                    java.util.Map.entry(PgOid.NUMERIC,     PgOid.NUMERIC_ARRAY),
                    java.util.Map.entry(PgOid.UUID,        PgOid.UUID_ARRAY),
                    java.util.Map.entry(PgOid.JSON,        PgOid.JSON_ARRAY),
                    java.util.Map.entry(PgOid.JSONB,       PgOid.JSONB_ARRAY),
                    java.util.Map.entry(PgOid.XML,         PgOid.XML_ARRAY)
            );
}
