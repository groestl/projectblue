package org.pgjava.types;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of all known PostgreSQL types.
 *
 * <p>Built-in types are registered at class-load time.  User-defined types
 * (domains, enums) are added at DDL time via {@link #register(PgType)}.
 *
 * <p>The singleton {@link #INSTANCE} is shared across the JVM.  Per-database
 * type namespacing will be handled in Phase 5 (catalog); for now all types
 * live in one flat namespace.
 */
public final class PgTypeRegistry {

    // ELEM_TO_ARRAY must be declared before INSTANCE so it is initialized before the constructor runs
    private static final Map<Integer, Integer> ELEM_TO_ARRAY = Map.ofEntries(
            Map.entry(PgOid.BOOL,        PgOid.BOOL_ARRAY),
            Map.entry(PgOid.BYTEA,       PgOid.BYTEA_ARRAY),
            Map.entry(PgOid.CHAR,        PgOid.CHAR_ARRAY),
            Map.entry(PgOid.NAME,        PgOid.NAME_ARRAY),
            Map.entry(PgOid.INT2,        PgOid.INT2_ARRAY),
            Map.entry(PgOid.INT4,        PgOid.INT4_ARRAY),
            Map.entry(PgOid.INT8,        PgOid.INT8_ARRAY),
            Map.entry(PgOid.TEXT,        PgOid.TEXT_ARRAY),
            Map.entry(PgOid.OID,         PgOid.OID_ARRAY),
            Map.entry(PgOid.FLOAT4,      PgOid.FLOAT4_ARRAY),
            Map.entry(PgOid.FLOAT8,      PgOid.FLOAT8_ARRAY),
            Map.entry(PgOid.MONEY,       PgOid.MONEY_ARRAY),
            Map.entry(PgOid.BPCHAR,      PgOid.BPCHAR_ARRAY),
            Map.entry(PgOid.VARCHAR,     PgOid.VARCHAR_ARRAY),
            Map.entry(PgOid.DATE,        PgOid.DATE_ARRAY),
            Map.entry(PgOid.TIME,        PgOid.TIME_ARRAY),
            Map.entry(PgOid.TIMESTAMP,   PgOid.TIMESTAMP_ARRAY),
            Map.entry(PgOid.TIMESTAMPTZ, PgOid.TIMESTAMPTZ_ARRAY),
            Map.entry(PgOid.INTERVAL,    PgOid.INTERVAL_ARRAY),
            Map.entry(PgOid.NUMERIC,     PgOid.NUMERIC_ARRAY),
            Map.entry(PgOid.UUID,        PgOid.UUID_ARRAY),
            Map.entry(PgOid.JSON,        PgOid.JSON_ARRAY),
            Map.entry(PgOid.JSONB,       PgOid.JSONB_ARRAY),
            Map.entry(PgOid.XML,         PgOid.XML_ARRAY),
            Map.entry(PgOid.TIMETZ,      PgOid.TIMETZ_ARRAY),
            Map.entry(PgOid.MACADDR,     PgOid.MACADDR_ARRAY),
            Map.entry(PgOid.INET,        PgOid.INET_ARRAY),
            Map.entry(PgOid.CIDR,        PgOid.CIDR_ARRAY),
            Map.entry(PgOid.BIT,         PgOid.BIT_ARRAY),
            Map.entry(PgOid.VARBIT,      PgOid.VARBIT_ARRAY)
    );

    public static final PgTypeRegistry INSTANCE = new PgTypeRegistry();

    /**
     * Create a fresh per-database registry pre-populated with all built-in types.
     * User-defined types registered here do not affect {@link #INSTANCE} or any
     * other database's registry — this is the fix for cross-database type leakage.
     */
    public static PgTypeRegistry newDatabase() {
        return new PgTypeRegistry();
    }

    private final Map<Integer, PgType>  byOid  = new ConcurrentHashMap<>();
    private final Map<String,  PgType>  byName = new ConcurrentHashMap<>();

    private final Map<String, PgType> ALIASES;

    private PgTypeRegistry() {
        registerBuiltins();
        ALIASES = buildAliases();
    }

    // -------------------------------------------------------------------------
    // Lookup

    /** Lookup by OID — returns null if unknown. */
    public PgType byOid(int oid) { return byOid.get(oid); }

    /** Lookup by canonical name — returns null if unknown. */
    public PgType byName(String name) { return byName.get(name.toLowerCase()); }

    /**
     * Lookup by SQL type name, resolving aliases (e.g. "integer" → int4,
     * "double precision" → float8, "character varying" → varchar).
     */
    public PgType byTypeName(String name) {
        if (name == null) return null;
        String lc = name.strip().toLowerCase();
        // Multi-word aliases first
        PgType t = ALIASES.get(lc);
        if (t != null) return t;
        return byName.get(lc);
    }

    // -------------------------------------------------------------------------
    // Registration (for DDL — user-defined types)

    public void register(PgType type) {
        byOid.put(type.oid(), type);
        byName.put(type.name().toLowerCase(), type);
    }

    public void unregister(PgType type) {
        byOid.remove(type.oid());
        byName.remove(type.name().toLowerCase());
    }

    // -------------------------------------------------------------------------
    // Convenience accessors for common built-in types

    public ScalarType bool()        { return (ScalarType) byOid.get(PgOid.BOOL); }
    public ScalarType int2()        { return (ScalarType) byOid.get(PgOid.INT2); }
    public ScalarType int4()        { return (ScalarType) byOid.get(PgOid.INT4); }
    public ScalarType int8()        { return (ScalarType) byOid.get(PgOid.INT8); }
    public ScalarType float4()      { return (ScalarType) byOid.get(PgOid.FLOAT4); }
    public ScalarType float8()      { return (ScalarType) byOid.get(PgOid.FLOAT8); }
    public ScalarType numeric()     { return (ScalarType) byOid.get(PgOid.NUMERIC); }
    public ScalarType text()        { return (ScalarType) byOid.get(PgOid.TEXT); }
    public ScalarType varchar()     { return (ScalarType) byOid.get(PgOid.VARCHAR); }
    public ScalarType bpchar()      { return (ScalarType) byOid.get(PgOid.BPCHAR); }
    public ScalarType date()        { return (ScalarType) byOid.get(PgOid.DATE); }
    public ScalarType timestamp()   { return (ScalarType) byOid.get(PgOid.TIMESTAMP); }
    public ScalarType timestamptz() { return (ScalarType) byOid.get(PgOid.TIMESTAMPTZ); }
    public ScalarType interval()    { return (ScalarType) byOid.get(PgOid.INTERVAL); }
    public ScalarType uuid()        { return (ScalarType) byOid.get(PgOid.UUID); }
    public ScalarType unknown()     { return (ScalarType) byOid.get(PgOid.UNKNOWN); }
    public ArrayType  int4Array()   { return (ArrayType)  byOid.get(PgOid.INT4_ARRAY); }
    public ArrayType  textArray()   { return (ArrayType)  byOid.get(PgOid.TEXT_ARRAY); }

    public ArrayType arrayOf(PgType elementType) {
        Integer arrOid = ELEM_TO_ARRAY.get(elementType.oid());
        if (arrOid != null) {
            PgType t = byOid.get(arrOid);
            if (t instanceof ArrayType at) return at;
        }
        // User-defined: allocate synthetic OID in a range that won't collide with CatalogManager.oidGen
        int syntheticOid = 200000 + elementType.oid();
        return new ArrayType(syntheticOid, elementType);
    }

    // -------------------------------------------------------------------------
    // Bootstrap: register all built-in scalar types

    // Category characters matching pg_type.typcategory:
    //  A=array B=boolean C=composite D=datetime E=enum G=geometric I=network N=numeric P=pseudo R=range S=string T=timespan U=userdefined V=bit X=unknown Z=internal

    private void registerBuiltins() {
        List<ScalarType> scalars = List.of(
                s(PgOid.BOOL,        "bool",         Boolean.class,    'B', true),
                s(PgOid.BYTEA,       "bytea",        byte[].class,     'U', false),
                s(PgOid.CHAR,        "char",         String.class,     'S', false),  // "char" internal
                s(PgOid.NAME,        "name",         String.class,     'S', false),
                s(PgOid.INT8,        "int8",         Long.class,       'N', false),
                s(PgOid.INT2,        "int2",         Integer.class,    'N', false),
                s(PgOid.INT4,        "int4",         Integer.class,    'N', false),
                s(PgOid.TEXT,        "text",         String.class,     'S', true),
                s(PgOid.OID,         "oid",          Long.class,       'N', false),
                s(PgOid.XML,         "xml",          String.class,     'U', false),
                s(PgOid.JSON,        "json",         String.class,     'U', false),
                s(PgOid.FLOAT4,      "float4",       Float.class,      'N', false),
                s(PgOid.FLOAT8,      "float8",       Double.class,     'N', true),
                s(PgOid.UNKNOWN,     "unknown",      String.class,     'X', false),
                s(PgOid.MONEY,       "money",        BigDecimal.class, 'U', false),
                s(PgOid.MACADDR,     "macaddr",      byte[].class,     'U', false),
                s(PgOid.INET,        "inet",         String.class,     'I', true),
                s(PgOid.CIDR,        "cidr",         String.class,     'I', false),
                s(PgOid.BPCHAR,      "bpchar",       String.class,     'S', false),
                s(PgOid.VARCHAR,     "varchar",      String.class,     'S', false),
                s(PgOid.DATE,        "date",         LocalDate.class,  'D', false),
                s(PgOid.TIME,        "time",         LocalTime.class,  'D', false),
                s(PgOid.TIMESTAMP,   "timestamp",    LocalDateTime.class,'D', false),
                s(PgOid.TIMESTAMPTZ, "timestamptz",  OffsetDateTime.class,'D', true),
                s(PgOid.INTERVAL,    "interval",     PgInterval.class, 'T', true),
                s(PgOid.TIMETZ,      "timetz",       OffsetTime.class, 'D', false),
                s(PgOid.BIT,         "bit",          java.util.BitSet.class, 'V', false),
                s(PgOid.VARBIT,      "varbit",       java.util.BitSet.class, 'V', true),
                s(PgOid.NUMERIC,     "numeric",      BigDecimal.class, 'N', true),
                s(PgOid.VOID,        "void",         Void.class,       'P', false),
                s(PgOid.UUID,        "uuid",         java.util.UUID.class,'U', false),
                s(PgOid.PG_LSN,      "pg_lsn",       Long.class,       'U', false),
                s(PgOid.JSONB,       "jsonb",        String.class,     'U', false),
                // Pseudo-types used in function signatures
                s(PgOid.ANY,         "any",          Object.class,     'P', false),
                s(PgOid.ANYARRAY,    "anyarray",     Object.class,     'P', false),
                s(PgOid.ANYELEMENT,  "anyelement",   Object.class,     'P', false),
                // Range types
                s(PgOid.INT4RANGE,   "int4range",    PgRange.class,    'R', false),
                s(PgOid.INT8RANGE,   "int8range",    PgRange.class,    'R', false),
                s(PgOid.NUMRANGE,    "numrange",     PgRange.class,    'R', false),
                s(PgOid.TSRANGE,     "tsrange",      PgRange.class,    'R', false),
                s(PgOid.TSTZRANGE,   "tstzrange",    PgRange.class,    'R', false),
                s(PgOid.DATERANGE,   "daterange",    PgRange.class,    'R', false)
        );

        for (ScalarType t : scalars) {
            byOid.put(t.oid(), t);
            byName.put(t.name(), t);
        }

        // Array types
        for (Map.Entry<Integer, Integer> e : ELEM_TO_ARRAY.entrySet()) {
            PgType elem = byOid.get(e.getKey());
            if (elem != null) {
                ArrayType at = new ArrayType(e.getValue(), elem);
                byOid.put(at.oid(), at);
                byName.put(at.name(), at);
            }
        }
    }

    private static ScalarType s(int oid, String name, Class<?> cls, char cat, boolean preferred) {
        return new ScalarType(oid, name, cls, cat, preferred);
    }

    // -------------------------------------------------------------------------
    // SQL alias → canonical type mapping
    // Must be consulted before byName() because aliases don't appear in byName
    // (ALIASES is initialized in constructor, after registerBuiltins())

    private Map<String, PgType> buildAliases() {
        Map<String, PgType> m = new HashMap<>();
        // Numeric aliases
        put(m, "integer",            PgOid.INT4);
        put(m, "int",                PgOid.INT4);
        put(m, "smallint",           PgOid.INT2);
        put(m, "bigint",             PgOid.INT8);
        put(m, "real",               PgOid.FLOAT4);
        put(m, "double precision",   PgOid.FLOAT8);
        put(m, "float",              PgOid.FLOAT8);
        put(m, "decimal",            PgOid.NUMERIC);
        // String aliases
        put(m, "character varying",  PgOid.VARCHAR);
        put(m, "character",          PgOid.BPCHAR);
        put(m, "char varying",       PgOid.VARCHAR);
        // Datetime aliases
        put(m, "timestamp without time zone", PgOid.TIMESTAMP);
        put(m, "timestamp with time zone",    PgOid.TIMESTAMPTZ);
        put(m, "time without time zone",      PgOid.TIME);
        put(m, "time with time zone",         PgOid.TIMETZ);
        // Boolean alias
        put(m, "boolean",            PgOid.BOOL);
        // Bit aliases
        put(m, "bit varying",        PgOid.VARBIT);
        // Serial (used in DDL, resolves to underlying type at DDL time)
        put(m, "serial",             PgOid.INT4);
        put(m, "bigserial",          PgOid.INT8);
        put(m, "smallserial",        PgOid.INT2);
        return Collections.unmodifiableMap(m);
    }

    private void put(Map<String, PgType> m, String alias, int oid) {
        PgType t = byOid.get(oid);
        if (t != null) m.put(alias, t);
    }
}
