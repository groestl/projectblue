package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code information_schema.*} virtual tables.
 *
 * <p>In real PostgreSQL, information_schema views are SQL views over pg_catalog.
 * In Phase 5b, we implement them as virtual tables that directly scan the
 * CatalogManager — the SQL view approach requires the full query engine (Phase 9).
 *
 * <p>Each inner class implements one information_schema table.
 */
public final class InformationSchema {

    private InformationSchema() {}

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    // -------------------------------------------------------------------------
    // information_schema.schemata

    public static final VirtualTable SCHEMATA = new VirtualTable() {
        private final List<ColumnDef> cols = List.of(
                ColumnDef.notNull("catalog_name",                1, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("schema_name",                 2, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("schema_owner",                3, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("default_character_set_catalog",4,REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("default_character_set_schema", 5,REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("default_character_set_name",  6, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("sql_path",                    7, REG.byOid(PgOid.TEXT))
        );
        @Override public String name()   { return "schemata"; }
        @Override public String schema() { return "information_schema"; }
        @Override public List<ColumnDef> columns() { return cols; }
        @Override public Iterable<Object[]> scan(CatalogManager catalog) {
            List<Object[]> rows = new ArrayList<>();
            String dbName = catalog.dbName();
            // pg_catalog and information_schema are always present
            rows.add(new Object[]{dbName, "pg_catalog",         "postgres", null, null, null, null});
            rows.add(new Object[]{dbName, "information_schema", "postgres", null, null, null, null});
            for (Schema s : catalog.allSchemas().values()) {
                rows.add(new Object[]{dbName, s.name(), "postgres", null, null, null, null});
            }
            return rows;
        }
    };

    // -------------------------------------------------------------------------
    // information_schema.tables

    public static final VirtualTable TABLES = new VirtualTable() {
        private final List<ColumnDef> cols = List.of(
                ColumnDef.notNull("table_catalog",   1, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("table_schema",    2, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("table_name",      3, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("table_type",      4, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("self_referencing_column_name", 5, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("reference_generation",         6, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("user_defined_type_catalog",    7, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("user_defined_type_schema",     8, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("user_defined_type_name",       9, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("is_insertable_into",           10, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("is_typed",                     11, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("commit_action",                12, REG.byOid(PgOid.TEXT))
        );
        @Override public String name()   { return "tables"; }
        @Override public String schema() { return "information_schema"; }
        @Override public List<ColumnDef> columns() { return cols; }
        @Override public Iterable<Object[]> scan(CatalogManager catalog) {
            List<Object[]> rows = new ArrayList<>();
            String dbName = catalog.dbName();
            for (Schema s : catalog.allSchemas().values()) {
                for (TableDef t : s.tables().values()) {
                    rows.add(new Object[]{
                            dbName, s.name(), t.name(), "BASE TABLE",
                            null, null, null, null, null, "YES", "NO", null
                    });
                }
                for (ViewDef v : s.views().values()) {
                    rows.add(new Object[]{
                            dbName, s.name(), v.name(), "VIEW",
                            null, null, null, null, null, "NO", "NO", null
                    });
                }
            }
            return rows;
        }
    };

    // -------------------------------------------------------------------------
    // information_schema.columns

    public static final VirtualTable COLUMNS = new VirtualTable() {
        private final List<ColumnDef> cols = buildColumnsCols();
        @Override public String name()   { return "columns"; }
        @Override public String schema() { return "information_schema"; }
        @Override public List<ColumnDef> columns() { return cols; }
        @Override public Iterable<Object[]> scan(CatalogManager catalog) {
            List<Object[]> rows = new ArrayList<>();
            String dbName = catalog.dbName();
            for (Schema s : catalog.allSchemas().values()) {
                for (TableDef t : s.tables().values()) {
                    for (ColumnDef c : t.columns()) {
                        rows.add(buildColumnRow(dbName, s.name(), t.name(), c));
                    }
                }
            }
            return rows;
        }
    };

    private static List<ColumnDef> buildColumnsCols() {
        return List.of(
                ColumnDef.notNull("table_catalog",           1,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("table_schema",            2,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("table_name",              3,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("column_name",             4,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("ordinal_position",        5,  REG.byOid(PgOid.INT4)),
                ColumnDef.of     ("column_default",          6,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("is_nullable",             7,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("data_type",               8,  REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("character_maximum_length",9,  REG.byOid(PgOid.INT4)),
                ColumnDef.of     ("character_octet_length",  10, REG.byOid(PgOid.INT4)),
                ColumnDef.of     ("numeric_precision",       11, REG.byOid(PgOid.INT4)),
                ColumnDef.of     ("numeric_precision_radix", 12, REG.byOid(PgOid.INT4)),
                ColumnDef.of     ("numeric_scale",           13, REG.byOid(PgOid.INT4)),
                ColumnDef.of     ("datetime_precision",      14, REG.byOid(PgOid.INT4)),
                ColumnDef.of     ("interval_type",           15, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("interval_precision",      16, REG.byOid(PgOid.INT4)),
                ColumnDef.of     ("character_set_catalog",   17, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("character_set_schema",    18, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("character_set_name",      19, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("collation_catalog",       20, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("collation_schema",        21, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("collation_name",          22, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("domain_catalog",          23, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("domain_schema",           24, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("domain_name",             25, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("udt_catalog",             26, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("udt_schema",              27, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("udt_name",                28, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("scope_catalog",           29, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("scope_schema",            30, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("scope_name",              31, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("maximum_cardinality",     32, REG.byOid(PgOid.INT4)),
                ColumnDef.notNull("dtd_identifier",          33, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("is_self_referencing",     34, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("is_identity",             35, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("identity_generation",     36, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("identity_start",          37, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("identity_increment",      38, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("identity_maximum",        39, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("identity_minimum",        40, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("identity_cycle",          41, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("is_generated",            42, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("generation_expression",   43, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("is_updatable",            44, REG.byOid(PgOid.TEXT))
        );
    }

    private static Object[] buildColumnRow(String catalog, String schema, String table, ColumnDef c) {
        String dataType = sqlDataType(c.type());
        Integer charMaxLen = charMaxLen(c);
        Integer numPrec    = numericPrecision(c);
        Integer numScale   = numericScale(c);
        boolean isIdentity = c.generated() == GeneratedKind.BY_DEFAULT
                          || c.generated() == GeneratedKind.ALWAYS;
        String identGen = c.generated() == GeneratedKind.ALWAYS ? "ALWAYS"
                        : c.generated() == GeneratedKind.BY_DEFAULT ? "BY DEFAULT" : null;
        boolean isGenerated = c.generated() == GeneratedKind.ALWAYS;

        return new Object[]{
                catalog, schema, table, c.name(),
                c.attnum(),                         // ordinal_position
                null,                               // column_default
                c.nullable() ? "YES" : "NO",        // is_nullable
                dataType,                           // data_type
                charMaxLen,                         // character_maximum_length
                charMaxLen,                         // character_octet_length
                numPrec,                            // numeric_precision
                numPrec != null ? 10 : null,        // numeric_precision_radix
                numScale,                           // numeric_scale
                null, null, null,                   // datetime_precision, interval_type, interval_precision
                null, null, null,                   // char set catalog/schema/name
                null, null, null,                   // collation catalog/schema/name
                null, null, null,                   // domain catalog/schema/name
                null, null, c.type().name(),        // udt catalog/schema/name
                null, null, null,                   // scope catalog/schema/name
                null,                               // maximum_cardinality
                String.valueOf(c.attnum()),         // dtd_identifier
                "NO",                               // is_self_referencing
                isIdentity ? "YES" : "NO",          // is_identity
                identGen,                           // identity_generation
                null, null, null, null, null,       // identity_start/increment/max/min/cycle
                isGenerated ? "ALWAYS" : "NEVER",   // is_generated
                null,                               // generation_expression
                "YES"                               // is_updatable
        };
    }

    private static String sqlDataType(org.pgjava.types.PgType t) {
        return switch (t.oid()) {
            case PgOid.INT2     -> "smallint";
            case PgOid.INT4     -> "integer";
            case PgOid.INT8     -> "bigint";
            case PgOid.FLOAT4   -> "real";
            case PgOid.FLOAT8   -> "double precision";
            case PgOid.NUMERIC  -> "numeric";
            case PgOid.BOOL     -> "boolean";
            case PgOid.TEXT     -> "text";
            case PgOid.VARCHAR  -> "character varying";
            case PgOid.BPCHAR   -> "character";
            case PgOid.DATE     -> "date";
            case PgOid.TIME     -> "time without time zone";
            case PgOid.TIMETZ   -> "time with time zone";
            case PgOid.TIMESTAMP  -> "timestamp without time zone";
            case PgOid.TIMESTAMPTZ-> "timestamp with time zone";
            case PgOid.INTERVAL -> "interval";
            case PgOid.UUID     -> "uuid";
            case PgOid.BYTEA    -> "bytea";
            case PgOid.JSON     -> "json";
            case PgOid.JSONB    -> "jsonb";
            case PgOid.XML      -> "xml";
            default -> t.name();
        };
    }

    private static Integer charMaxLen(ColumnDef c) {
        int oid = c.type().oid();
        if (oid == PgOid.VARCHAR || oid == PgOid.BPCHAR) {
            int mod = c.typmod();
            return mod > 4 ? mod - 4 : null;
        }
        return null;
    }

    private static Integer numericPrecision(ColumnDef c) {
        return switch (c.type().oid()) {
            case PgOid.INT2   -> 16;
            case PgOid.INT4   -> 32;
            case PgOid.INT8   -> 64;
            case PgOid.FLOAT4 -> 24;
            case PgOid.FLOAT8 -> 53;
            case PgOid.NUMERIC -> c.typmod() > 4 ? ((c.typmod() - 4) >> 16) & 0xFFFF : null;
            default -> null;
        };
    }

    private static Integer numericScale(ColumnDef c) {
        if (c.type().oid() == PgOid.NUMERIC && c.typmod() > 4) {
            return (c.typmod() - 4) & 0xFFFF;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // information_schema.table_constraints

    public static final VirtualTable TABLE_CONSTRAINTS = new VirtualTable() {
        private final List<ColumnDef> cols = List.of(
                ColumnDef.notNull("constraint_catalog", 1, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("constraint_schema",  2, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("constraint_name",    3, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("table_catalog",      4, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("table_schema",       5, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("table_name",         6, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("constraint_type",    7, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("is_deferrable",      8, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("initially_deferred", 9, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("enforced",           10,REG.byOid(PgOid.TEXT))
        );
        @Override public String name()   { return "table_constraints"; }
        @Override public String schema() { return "information_schema"; }
        @Override public List<ColumnDef> columns() { return cols; }
        @Override public Iterable<Object[]> scan(CatalogManager catalog) {
            List<Object[]> rows = new ArrayList<>();
            String dbName = catalog.dbName();
            for (Schema s : catalog.allSchemas().values()) {
                for (TableDef t : s.tables().values()) {
                    for (Constraint c : t.constraints()) {
                        String type = switch (c) {
                            case Constraint.PrimaryKey pk -> "PRIMARY KEY";
                            case Constraint.Unique u      -> "UNIQUE";
                            case Constraint.Check ch      -> "CHECK";
                            case Constraint.ForeignKey fk -> "FOREIGN KEY";
                            case Constraint.NotNull nn    -> "CHECK"; // NOT NULL is a CHECK in IS
                        };
                        String name = c.name() != null ? c.name() : "";
                        rows.add(new Object[]{
                                dbName, s.name(), name, dbName, s.name(), t.name(),
                                type, "NO", "NO", "YES"
                        });
                    }
                }
            }
            return rows;
        }
    };

    // -------------------------------------------------------------------------
    // information_schema.key_column_usage

    public static final VirtualTable KEY_COLUMN_USAGE = new VirtualTable() {
        private final List<ColumnDef> cols = List.of(
                ColumnDef.notNull("constraint_catalog",        1, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("constraint_schema",         2, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("constraint_name",           3, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("table_catalog",             4, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("table_schema",              5, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("table_name",                6, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("column_name",               7, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("ordinal_position",          8, REG.byOid(PgOid.INT4)),
                ColumnDef.of     ("position_in_unique_constraint",9,REG.byOid(PgOid.INT4))
        );
        @Override public String name()   { return "key_column_usage"; }
        @Override public String schema() { return "information_schema"; }
        @Override public List<ColumnDef> columns() { return cols; }
        @Override public Iterable<Object[]> scan(CatalogManager catalog) {
            List<Object[]> rows = new ArrayList<>();
            String dbName = catalog.dbName();
            for (Schema s : catalog.allSchemas().values()) {
                for (TableDef t : s.tables().values()) {
                    for (Constraint c : t.constraints()) {
                        List<String> colNames = switch (c) {
                            case Constraint.PrimaryKey pk -> pk.columns();
                            case Constraint.Unique u      -> u.columns();
                            case Constraint.ForeignKey fk -> fk.columns();
                            default -> List.of();
                        };
                        String cname = c.name() != null ? c.name() : "";
                        for (int i = 0; i < colNames.size(); i++) {
                            rows.add(new Object[]{
                                    dbName, s.name(), cname, dbName, s.name(), t.name(),
                                    colNames.get(i), i + 1, null
                            });
                        }
                    }
                }
            }
            return rows;
        }
    };

    // -------------------------------------------------------------------------
    // information_schema.sequences

    public static final VirtualTable SEQUENCES = new VirtualTable() {
        private final List<ColumnDef> cols = List.of(
                ColumnDef.notNull("sequence_catalog",         1,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("sequence_schema",          2,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("sequence_name",            3,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("data_type",                4,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("numeric_precision",        5,  REG.byOid(PgOid.INT4)),
                ColumnDef.notNull("numeric_precision_radix",  6,  REG.byOid(PgOid.INT4)),
                ColumnDef.notNull("numeric_scale",            7,  REG.byOid(PgOid.INT4)),
                ColumnDef.notNull("start_value",              8,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("minimum_value",            9,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("maximum_value",            10, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("increment",                11, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("cycle_option",             12, REG.byOid(PgOid.TEXT))
        );
        @Override public String name()   { return "sequences"; }
        @Override public String schema() { return "information_schema"; }
        @Override public List<ColumnDef> columns() { return cols; }
        @Override public Iterable<Object[]> scan(CatalogManager catalog) {
            List<Object[]> rows = new ArrayList<>();
            String dbName = catalog.dbName();
            for (Schema s : catalog.allSchemas().values()) {
                for (SequenceDef seq : s.sequences().values()) {
                    rows.add(new Object[]{
                            dbName, s.name(), seq.name(),
                            "bigint",   // PostgreSQL sequences are bigint
                            64,         // numeric_precision (bits for bigint)
                            2,          // numeric_precision_radix
                            0,          // numeric_scale
                            String.valueOf(seq.start()),
                            String.valueOf(seq.minVal()),
                            String.valueOf(seq.maxVal()),
                            String.valueOf(seq.increment()),
                            seq.cycle() ? "YES" : "NO"
                    });
                }
            }
            return rows;
        }
    };

    // -------------------------------------------------------------------------
    // information_schema.routines

    public static final VirtualTable ROUTINES = new VirtualTable() {
        private final List<ColumnDef> cols = List.of(
                ColumnDef.notNull("specific_catalog",           1,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("specific_schema",            2,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("specific_name",              3,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("routine_catalog",            4,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("routine_schema",             5,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("routine_name",               6,  REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("routine_type",               7,  REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("data_type",                  8,  REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("type_udt_catalog",           9,  REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("type_udt_schema",            10, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("type_udt_name",              11, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("routine_body",               12, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("routine_definition",         13, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("external_name",              14, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("external_language",          15, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("is_deterministic",           16, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("sql_data_access",            17, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("is_null_call",               18, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("schema_level_routine",       19, REG.byOid(PgOid.TEXT)),
                ColumnDef.of     ("security_type",              20, REG.byOid(PgOid.TEXT))
        );
        @Override public String name()   { return "routines"; }
        @Override public String schema() { return "information_schema"; }
        @Override public List<ColumnDef> columns() { return cols; }
        @Override public Iterable<Object[]> scan(CatalogManager catalog) {
            List<Object[]> rows = new ArrayList<>();
            String dbName = catalog.dbName();
            for (FunctionDef fn : catalog.functions().allScalars()) {
                if (fn.schemaName() == null || "pg_catalog".equals(fn.schemaName())) continue;
                String specificName = fn.name() + "_" + fn.oid();
                String dataType = fn.returnType() != null ? sqlDataType(fn.returnType()) : null;
                String udtName = fn.returnType() != null ? fn.returnType().name() : null;
                boolean isUdf = fn.source() != null;
                String routineBody = isUdf ? "SQL" : "EXTERNAL";
                String extLang = isUdf ? "PLPGSQL" : null;
                rows.add(new Object[]{
                        dbName, fn.schemaName(), specificName,
                        dbName, fn.schemaName(), fn.name(),
                        "FUNCTION",
                        dataType,
                        dbName, "pg_catalog", udtName,
                        routineBody,
                        fn.source(),            // routine_definition
                        null,                   // external_name
                        extLang,                // external_language
                        "NO",                   // is_deterministic
                        "CONTAINS SQL",         // sql_data_access
                        fn.strict() ? "NO" : "YES", // is_null_call (inverse of strict)
                        "YES",                  // schema_level_routine
                        "DEFINER"               // security_type
                });
            }
            // Also include set-returning functions
            for (var srf : catalog.functions().allSrfs()) {
                if (srf.schemaName() == null || "pg_catalog".equals(srf.schemaName())) continue;
                String specificName = srf.name() + "_" + srf.oid();
                rows.add(new Object[]{
                        dbName, srf.schemaName(), specificName,
                        dbName, srf.schemaName(), srf.name(),
                        "FUNCTION",
                        "SETOF record",
                        dbName, "pg_catalog", "record",
                        "SQL",
                        null,                   // routine_definition
                        null,                   // external_name
                        "PLPGSQL",              // external_language
                        "NO",                   // is_deterministic
                        "CONTAINS SQL",         // sql_data_access
                        "YES",                  // is_null_call
                        "YES",                  // schema_level_routine
                        "DEFINER"               // security_type
                });
            }
            return rows;
        }
    };

    // -------------------------------------------------------------------------
    // information_schema.referential_constraints

    public static final VirtualTable REFERENTIAL_CONSTRAINTS = new VirtualTable() {
        private final List<ColumnDef> cols = List.of(
                ColumnDef.notNull("constraint_catalog",        1, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("constraint_schema",         2, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("constraint_name",           3, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("unique_constraint_catalog", 4, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("unique_constraint_schema",  5, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("unique_constraint_name",    6, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("match_option",              7, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("update_rule",               8, REG.byOid(PgOid.TEXT)),
                ColumnDef.notNull("delete_rule",               9, REG.byOid(PgOid.TEXT))
        );
        @Override public String name()   { return "referential_constraints"; }
        @Override public String schema() { return "information_schema"; }
        @Override public List<ColumnDef> columns() { return cols; }
        @Override public Iterable<Object[]> scan(CatalogManager catalog) {
            List<Object[]> rows = new ArrayList<>();
            String dbName = catalog.dbName();
            for (Schema s : catalog.allSchemas().values()) {
                for (TableDef t : s.tables().values()) {
                    for (Constraint c : t.constraints()) {
                        if (c instanceof Constraint.ForeignKey fk) {
                            rows.add(new Object[]{
                                    dbName, s.name(), fk.name() != null ? fk.name() : "",
                                    dbName, fk.refSchema(), "", // ref constraint name unknown without catalog lookup
                                    "NONE", fkActionName(fk.onUpdate()), fkActionName(fk.onDelete())
                            });
                        }
                    }
                }
            }
            return rows;
        }
        private String fkActionName(org.pgjava.sql.ast.FkAction a) {
            return switch (a) {
                case CASCADE    -> "CASCADE";
                case SET_NULL   -> "SET NULL";
                case SET_DEFAULT-> "SET DEFAULT";
                case RESTRICT   -> "RESTRICT";
                case NO_ACTION  -> "NO ACTION";
            };
        }
    };
}
