package org.pgjava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pgjava.catalog.BuiltinFunctions;
import org.pgjava.catalog.CatalogManager;
import org.pgjava.catalog.FunctionDef;
import org.pgjava.catalog.FunctionRegistry;
import org.pgjava.catalog.VirtualTable;
import org.pgjava.catalog.syscat.InformationSchema;
import org.pgjava.catalog.syscat.PgDatabase;
import org.pgjava.catalog.syscat.PgRoles;
import org.pgjava.catalog.syscat.PgSequence;
import org.pgjava.catalog.syscat.PgSettings;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5b: pg_type, pg_database, pg_roles, pg_settings, pg_sequence,
 * information_schema virtual tables, and built-in function registry.
 */
class Phase5bCatalogTest {

    private CatalogManager catalog;

    @BeforeEach
    void setUp() {
        catalog = new CatalogManager("phase5b_test");
    }

    // =========================================================================
    // Helper

    private List<Object[]> scanVt(String schema, String name) {
        VirtualTable vt = catalog.getVirtualTable(schema, name);
        assertNotNull(vt, "virtual table " + schema + "." + name + " not found");
        List<Object[]> rows = new ArrayList<>();
        for (Object[] row : vt.scan(catalog)) rows.add(row);
        return rows;
    }

    // =========================================================================
    // pg_catalog.pg_type

    @Test
    void pgTypeHasCommonOids() {
        List<Object[]> rows = scanVt("pg_catalog", "pg_type");
        // Find int4 (OID 23)
        boolean foundInt4 = rows.stream().anyMatch(r -> Long.valueOf(23).equals(r[0]) && "int4".equals(r[1]));
        assertTrue(foundInt4, "pg_type should have int4 (OID 23)");
        // Find text (OID 25)
        boolean foundText = rows.stream().anyMatch(r -> Long.valueOf(25).equals(r[0]) && "text".equals(r[1]));
        assertTrue(foundText, "pg_type should have text (OID 25)");
        // Find bool (OID 16)
        boolean foundBool = rows.stream().anyMatch(r -> Long.valueOf(16).equals(r[0]) && "bool".equals(r[1]));
        assertTrue(foundBool, "pg_type should have bool (OID 16)");
        // Find uuid (OID 2950)
        boolean foundUuid = rows.stream().anyMatch(r -> Long.valueOf(2950).equals(r[0]) && "uuid".equals(r[1]));
        assertTrue(foundUuid, "pg_type should have uuid (OID 2950)");
    }

    @Test
    void pgTypeHasArrayTypes() {
        List<Object[]> rows = scanVt("pg_catalog", "pg_type");
        // int4[] (OID 1007) — pg_type shows array types with "_" prefix
        boolean foundInt4Array = rows.stream().anyMatch(r -> Long.valueOf(1007).equals(r[0]));
        assertTrue(foundInt4Array, "pg_type should have _int4 array (OID 1007)");
    }

    @Test
    void pgTypeColumnsCount() {
        VirtualTable vt = catalog.getVirtualTable("pg_catalog", "pg_type");
        assertNotNull(vt);
        // pg_type has many columns — check at least 15
        assertTrue(vt.columns().size() >= 15,
                "pg_type should have at least 15 columns, got " + vt.columns().size());
    }

    // =========================================================================
    // pg_catalog.pg_database

    @Test
    void pgDatabaseHasDbName() {
        List<Object[]> rows = scanVt("pg_catalog", "pg_database");
        assertEquals(1, rows.size(), "pg_database should have exactly one row");
        Object[] row = rows.get(0);
        assertEquals("phase5b_test", row[1], "datname should match database name");
    }

    @Test
    void pgDatabaseEncoding() {
        List<Object[]> rows = scanVt("pg_catalog", "pg_database");
        Object[] row = rows.get(0);
        assertEquals(6, row[3], "encoding 6 = UTF8");
        assertEquals(true, row[11], "datallowconn");
    }

    // =========================================================================
    // pg_catalog.pg_roles

    @Test
    void pgRolesHasSuperuser() {
        List<Object[]> rows = scanVt("pg_catalog", "pg_roles");
        assertEquals(1, rows.size(), "pg_roles should have exactly one row");
        Object[] row = rows.get(0);
        assertEquals("postgres", row[0], "rolname");
        assertEquals(true,       row[1], "rolsuper");
        assertEquals(true,       row[5], "rolcanlogin");
    }

    // =========================================================================
    // pg_catalog.pg_settings

    @Test
    void pgSettingsHasServerVersion() {
        List<Object[]> rows = scanVt("pg_catalog", "pg_settings");
        Optional<Object[]> sv = rows.stream().filter(r -> "server_version".equals(r[0])).findFirst();
        assertTrue(sv.isPresent(), "pg_settings should have server_version");
        assertEquals("15.0", sv.get()[1]);
    }

    @Test
    void pgSettingsHasSearchPath() {
        List<Object[]> rows = scanVt("pg_catalog", "pg_settings");
        Optional<Object[]> sp = rows.stream().filter(r -> "search_path".equals(r[0])).findFirst();
        assertTrue(sp.isPresent(), "pg_settings should have search_path");
        assertNotNull(sp.get()[1]);
    }

    @Test
    void pgSettingsHasStandardConformingStrings() {
        List<Object[]> rows = scanVt("pg_catalog", "pg_settings");
        Optional<Object[]> scs = rows.stream()
                .filter(r -> "standard_conforming_strings".equals(r[0])).findFirst();
        assertTrue(scs.isPresent());
        assertEquals("on", scs.get()[1]);
    }

    // =========================================================================
    // pg_catalog.pg_sequence

    @Test
    void pgSequenceEmptyWithNoSequences() {
        List<Object[]> rows = scanVt("pg_catalog", "pg_sequence");
        assertEquals(0, rows.size(), "pg_sequence should be empty initially");
    }

    @Test
    void pgSequenceShowsCreatedSequence() throws Exception {
        // Create a schema and sequence
        catalog.createSchema("myns", false);
        var schema = catalog.getSchema("myns");
        var seq = new org.pgjava.catalog.SequenceDef(
                catalog.nextOid(), "myseq", "myns", 1L, 1L, Long.MIN_VALUE, Long.MAX_VALUE, false);
        schema.addSequence(seq);

        List<Object[]> rows = scanVt("pg_catalog", "pg_sequence");
        assertEquals(1, rows.size(), "pg_sequence should have one row for myseq");
        Object[] row = rows.get(0);
        assertEquals(1L, row[2], "seqstart");
        assertEquals(1L, row[3], "seqincrement");
    }

    // =========================================================================
    // information_schema.schemata

    @Test
    void informationSchemaSchemataHasPublic() {
        List<Object[]> rows = scanVt("information_schema", "schemata");
        Optional<Object[]> pub = rows.stream().filter(r -> "public".equals(r[1])).findFirst();
        assertTrue(pub.isPresent(), "schemata should include the public schema");
    }

    @Test
    void informationSchemaSchemataHasSystemSchemas() {
        List<Object[]> rows = scanVt("information_schema", "schemata");
        boolean hasPgCatalog = rows.stream().anyMatch(r -> "pg_catalog".equals(r[1]));
        boolean hasInfoSchema = rows.stream().anyMatch(r -> "information_schema".equals(r[1]));
        assertTrue(hasPgCatalog, "schemata should list pg_catalog");
        assertTrue(hasInfoSchema, "schemata should list information_schema");
    }

    @Test
    void informationSchemaSchemataGrowsOnCreateSchema() throws Exception {
        int before = scanVt("information_schema", "schemata").size();
        catalog.createSchema("myapp", false);
        int after = scanVt("information_schema", "schemata").size();
        assertEquals(before + 1, after, "schemata should grow by 1 after CREATE SCHEMA");
    }

    // =========================================================================
    // information_schema.tables

    @Test
    void informationSchemaTablesEmptyInitially() {
        List<Object[]> rows = scanVt("information_schema", "tables");
        assertEquals(0, rows.size(), "tables should be empty before any CREATE TABLE");
    }

    @Test
    void informationSchemaTablesShowsCreatedTable() throws Exception {
        // Create table directly in catalog
        var colDef = org.pgjava.catalog.ColumnDef.notNull(
                "id", 1, org.pgjava.types.PgTypeRegistry.INSTANCE.byOid(org.pgjava.types.PgOid.INT4));
        var tableDef = new org.pgjava.catalog.TableDef(
                catalog.nextOid(), "mytable", "public", false);
        tableDef.addColumn(colDef);
        catalog.getSchema("public").addTable(tableDef);

        List<Object[]> rows = scanVt("information_schema", "tables");
        Optional<Object[]> found = rows.stream().filter(r -> "mytable".equals(r[2])).findFirst();
        assertTrue(found.isPresent(), "tables should show mytable");
        assertEquals("BASE TABLE", found.get()[3], "table_type for a regular table");
    }

    // =========================================================================
    // information_schema.columns

    @Test
    void informationSchemaColumnsHasCorrectColumnCount() {
        VirtualTable vt = catalog.getVirtualTable("information_schema", "columns");
        assertNotNull(vt);
        assertEquals(44, vt.columns().size(),
                "information_schema.columns must have exactly 44 columns per JDBC spec");
    }

    @Test
    void informationSchemaColumnsShowsTableColumns() throws Exception {
        var int4Type = org.pgjava.types.PgTypeRegistry.INSTANCE.byOid(org.pgjava.types.PgOid.INT4);
        var textType = org.pgjava.types.PgTypeRegistry.INSTANCE.byOid(org.pgjava.types.PgOid.TEXT);
        var col1 = org.pgjava.catalog.ColumnDef.notNull("id",   1, int4Type);
        var col2 = org.pgjava.catalog.ColumnDef.of("name",      2, textType);
        var tableDef = new org.pgjava.catalog.TableDef(
                catalog.nextOid(), "emp", "public", false);
        tableDef.addColumn(col1);
        tableDef.addColumn(col2);
        catalog.getSchema("public").addTable(tableDef);

        List<Object[]> rows = scanVt("information_schema", "columns");
        // 2 column rows for emp
        List<Object[]> empCols = rows.stream().filter(r -> "emp".equals(r[2])).toList();
        assertEquals(2, empCols.size(), "columns should have 2 rows for emp");

        // ordinal_position is at index 4 (zero-based: catalog, schema, table, column_name, ordinal_position, ...)
        Object[] idRow   = empCols.stream().filter(r -> "id".equals(r[3])).findFirst().orElseThrow();
        Object[] nameRow = empCols.stream().filter(r -> "name".equals(r[3])).findFirst().orElseThrow();
        assertEquals(1, idRow[4],   "ordinal_position for id should be 1");
        assertEquals(2, nameRow[4], "ordinal_position for name should be 2");

        // is_nullable is at index 6 (column_default is at index 5, then is_nullable at 6)
        assertEquals("NO",  idRow[6],   "id is NOT NULL → is_nullable=NO");
        assertEquals("YES", nameRow[6], "name is nullable → is_nullable=YES");
    }

    // =========================================================================
    // information_schema.table_constraints

    @Test
    void informationSchemaTableConstraintsColumnsCorrect() {
        VirtualTable vt = catalog.getVirtualTable("information_schema", "table_constraints");
        assertNotNull(vt);
        assertEquals(10, vt.columns().size(),
                "table_constraints should have 10 columns");
    }

    // =========================================================================
    // information_schema.key_column_usage

    @Test
    void informationSchemaKeyColumnUsageColumns() {
        VirtualTable vt = catalog.getVirtualTable("information_schema", "key_column_usage");
        assertNotNull(vt);
        assertEquals(9, vt.columns().size(),
                "key_column_usage should have 9 columns");
    }

    // =========================================================================
    // information_schema.referential_constraints

    @Test
    void informationSchemaReferentialConstraintsColumns() {
        VirtualTable vt = catalog.getVirtualTable("information_schema", "referential_constraints");
        assertNotNull(vt);
        assertEquals(9, vt.columns().size(),
                "referential_constraints should have 9 columns");
    }

    // =========================================================================
    // FunctionRegistry / BuiltinFunctions

    @Test
    void builtinFunctionsRegistered() {
        FunctionRegistry reg = new FunctionRegistry();
        BuiltinFunctions.registerAll(reg);

        assertNotNull(reg.findScalar("now", 0),              "now() must be registered");
        assertNotNull(reg.findScalar("current_timestamp", 0),"current_timestamp() must be registered");
        assertNotNull(reg.findScalar("current_date", 0),     "current_date() must be registered");
        assertNotNull(reg.findScalar("coalesce", 0),         "coalesce (variadic) must be registered");
        assertNotNull(reg.findScalar("nullif", 2),           "nullif(a,b) must be registered");
        assertNotNull(reg.findScalar("greatest", 0),         "greatest (variadic) must be registered");
        assertNotNull(reg.findScalar("least", 0),            "least (variadic) must be registered");
        assertNotNull(reg.findScalar("lower", 1),            "lower(text) must be registered");
        assertNotNull(reg.findScalar("upper", 1),            "upper(text) must be registered");
        assertNotNull(reg.findScalar("length", 1),           "length(text) must be registered");
        assertNotNull(reg.findScalar("abs", 1),              "abs (int4) must be registered");
        assertNotNull(reg.findScalar("round", 1),            "round(float8) must be registered");
        assertNotNull(reg.findScalar("random", 0),           "random() must be registered");
        assertNotNull(reg.findScalar("version", 0),          "version() must be registered");
    }

    @Test
    void aggregatesRegistered() {
        FunctionRegistry reg = new FunctionRegistry();
        BuiltinFunctions.registerAll(reg);

        assertNotNull(reg.findAggregate("count"), "count aggregate must be registered");
        assertNotNull(reg.findAggregate("sum"),   "sum aggregate must be registered");
        assertNotNull(reg.findAggregate("avg"),   "avg aggregate must be registered");
        assertNotNull(reg.findAggregate("max"),   "max aggregate must be registered");
        assertNotNull(reg.findAggregate("min"),   "min aggregate must be registered");
    }

    @Test
    void nowReturnsTodaysDate() throws Exception {
        FunctionRegistry reg = new FunctionRegistry();
        BuiltinFunctions.registerAll(reg);

        FunctionDef nowFn = reg.findScalar("now", 0);
        assertNotNull(nowFn);
        Object result = nowFn.impl().invoke(new Object[0]);
        assertNotNull(result);
        // Should be an OffsetDateTime
        assertTrue(result instanceof java.time.OffsetDateTime,
                "now() should return OffsetDateTime, got " + result.getClass());
    }

    @Test
    void coalesceReturnsFirstNonNull() throws Exception {
        FunctionRegistry reg = new FunctionRegistry();
        BuiltinFunctions.registerAll(reg);

        FunctionDef coalesce = reg.findScalar("coalesce", 0); // variadic matches any arity
        assertNotNull(coalesce);
        Object r1 = coalesce.impl().invoke(new Object[]{null, null, "hello", "world"});
        assertEquals("hello", r1);

        Object r2 = coalesce.impl().invoke(new Object[]{null, null, null});
        assertNull(r2);
    }

    @Test
    void stringFunctions() throws Exception {
        FunctionRegistry reg = new FunctionRegistry();
        BuiltinFunctions.registerAll(reg);

        FunctionDef lower = reg.findScalar("lower", 1);
        assertEquals("hello", lower.impl().invoke(new Object[]{"HELLO"}));

        FunctionDef upper = reg.findScalar("upper", 1);
        assertEquals("WORLD", upper.impl().invoke(new Object[]{"world"}));

        FunctionDef len = reg.findScalar("length", 1);
        assertEquals(5, len.impl().invoke(new Object[]{"hello"}));

        FunctionDef substr2 = reg.findScalar("substr", 2);
        assertEquals("llo", substr2.impl().invoke(new Object[]{"hello", 3}));

        FunctionDef substr3 = reg.findScalar("substr", 3);
        assertEquals("ll", substr3.impl().invoke(new Object[]{"hello", 3, 2}));

        FunctionDef concat = reg.findScalar("concat", 0);
        assertEquals("ab", concat.impl().invoke(new Object[]{"a", "b"}));
        assertEquals("ac", concat.impl().invoke(new Object[]{"a", null, "c"}));
    }

    @Test
    void mathFunctions() throws Exception {
        FunctionRegistry reg = new FunctionRegistry();
        BuiltinFunctions.registerAll(reg);

        FunctionDef abs = reg.findScalar("abs", 1);
        assertEquals(5, abs.impl().invoke(new Object[]{-5}));

        FunctionDef round = reg.findScalar("round", 1);
        assertEquals(3.0, round.impl().invoke(new Object[]{3.4}));
        assertEquals(4.0, round.impl().invoke(new Object[]{3.5}));

        FunctionDef mod = reg.findScalar("mod", 2);
        assertEquals(1, mod.impl().invoke(new Object[]{10, 3}));
    }

    @Test
    void catalogManagerFunctionRegistry() {
        // CatalogManager now auto-registers built-ins
        FunctionRegistry reg = catalog.functions();
        assertNotNull(reg.findScalar("now", 0), "catalog's FunctionRegistry should have now()");
        assertNotNull(reg.findAggregate("count"), "catalog's FunctionRegistry should have count");
    }
}
