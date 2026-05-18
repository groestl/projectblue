package org.pgjava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pgjava.catalog.*;
import org.pgjava.catalog.syscat.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.Session;
import org.pgjava.types.PgTypeRegistry;
import org.pgjava.types.PgOid;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5a: Core catalog structures and CatalogManager unit tests.
 */
class Phase5aCatalogTest {

    private CatalogManager cat;

    @BeforeEach
    void setUp() {
        cat = new CatalogManager("testdb");
    }

    private static PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    // -------------------------------------------------------------------------
    // Schema operations

    @Test
    void publicSchemaCreatedByDefault() {
        assertNotNull(cat.getSchemaOrNull("public"));
    }

    @Test
    void createAndGetSchema() throws SQLException {
        Schema s = cat.createSchema("myschema", false);
        assertNotNull(s);
        assertEquals("myschema", s.name());
        assertSame(s, cat.getSchema("myschema"));
    }

    @Test
    void createSchemaDuplicateFails() {
        var ex = assertThrows(SQLException.class,
                () -> cat.createSchema("public", false));
        assertEquals("42P06", ex.getSQLState());
    }

    @Test
    void createSchemaIfNotExistsIsIdempotent() throws SQLException {
        Schema a = cat.createSchema("dup", false);
        Schema b = cat.createSchema("dup", true);
        assertSame(a, b);
    }

    @Test
    void getSchemaNotFoundThrows() {
        var ex = assertThrows(SQLException.class,
                () -> cat.getSchema("nonexistent"));
        assertEquals("3F000", ex.getSQLState());
    }

    @Test
    void dropSchema() throws SQLException {
        cat.createSchema("todelete", false);
        cat.dropSchema("todelete", false, false);
        assertNull(cat.getSchemaOrNull("todelete"));
    }

    @Test
    void dropSchemaIfExistsNoThrow() throws SQLException {
        // Should not throw
        cat.dropSchema("doesnotexist", false, true);
    }

    @Test
    void dropSchemaNotFoundThrows() {
        var ex = assertThrows(SQLException.class,
                () -> cat.dropSchema("nonexistent", false, false));
        assertEquals("3F000", ex.getSQLState());
    }

    @Test
    void dropSchemaWithTablesFails() throws SQLException {
        cat.createSchema("full", false);
        cat.createTable("full", "t", List.of(ColumnDef.of("id", 1, REG.int4())),
                List.of(), false, false);
        var ex = assertThrows(SQLException.class,
                () -> cat.dropSchema("full", false, false));
        assertEquals("2BP01", ex.getSQLState());
    }

    @Test
    void dropSchemaCascadeDropsTables() throws SQLException {
        cat.createSchema("full", false);
        cat.createTable("full", "t", List.of(ColumnDef.of("id", 1, REG.int4())),
                List.of(), false, false);
        cat.dropSchema("full", true, false);
        assertNull(cat.getSchemaOrNull("full"));
    }

    // -------------------------------------------------------------------------
    // Table operations

    @Test
    void createTable() throws SQLException {
        List<ColumnDef> cols = List.of(
                ColumnDef.notNull("id",   1, REG.int4()),
                ColumnDef.of     ("name", 2, REG.text())
        );
        TableDef t = cat.createTable("public", "users", cols, List.of(), false, false);
        assertNotNull(t);
        assertEquals("users", t.name());
        assertEquals(2, t.columnCount());
    }

    @Test
    void createTableDuplicateFails() throws SQLException {
        List<ColumnDef> cols = List.of(ColumnDef.of("id", 1, REG.int4()));
        cat.createTable("public", "dup", cols, List.of(), false, false);
        var ex = assertThrows(SQLException.class,
                () -> cat.createTable("public", "dup", cols, List.of(), false, false));
        assertEquals("42P07", ex.getSQLState());
    }

    @Test
    void createTableIfNotExistsIdempotent() throws SQLException {
        List<ColumnDef> cols = List.of(ColumnDef.of("id", 1, REG.int4()));
        TableDef a = cat.createTable("public", "t1", cols, List.of(), false, false);
        TableDef b = cat.createTable("public", "t1", cols, List.of(), false, true);
        assertSame(a, b);
    }

    @Test
    void dropTableNotFoundThrows() {
        var ex = assertThrows(SQLException.class,
                () -> cat.dropTable("public", "nope", false, false));
        assertEquals("42P01", ex.getSQLState());
    }

    @Test
    void dropTableIfExistsNoThrow() throws SQLException {
        cat.dropTable("public", "nope", true, false);
    }

    @Test
    void tableWithPrimaryKeyAutoCreatesIndex() throws SQLException {
        List<ColumnDef> cols = List.of(
                ColumnDef.notNull("id", 1, REG.int4()));
        List<Constraint> cons = List.of(
                new Constraint.PrimaryKey("users_pkey", List.of("id")));
        TableDef t = cat.createTable("public", "users", cols, cons, false, false);
        assertFalse(t.indexes().isEmpty());
        IndexDef pk = t.indexes().get(0);
        assertTrue(pk.primary());
        assertTrue(pk.unique());
        assertEquals("users_pkey", pk.name());
    }

    // -------------------------------------------------------------------------
    // Column operations

    @Test
    void columnLookupByName() throws SQLException {
        List<ColumnDef> cols = List.of(
                ColumnDef.notNull("id",   1, REG.int4()),
                ColumnDef.of     ("name", 2, REG.text())
        );
        TableDef t = cat.createTable("public", "t", cols, List.of(), false, false);
        assertNotNull(t.column("id"));
        assertNotNull(t.column("NAME"));  // case-insensitive
        assertNull(t.column("nonexistent"));
    }

    @Test
    void addDropColumn() throws SQLException {
        List<ColumnDef> cols = new ArrayList<>(List.of(ColumnDef.notNull("id", 1, REG.int4())));
        TableDef t = cat.createTable("public", "t", cols, List.of(), false, false);
        t.addColumn(ColumnDef.of("extra", 2, REG.text()));
        assertEquals(2, t.columnCount());
        t.dropColumn("extra");
        assertEquals(1, t.columnCount());
    }

    // -------------------------------------------------------------------------
    // Search path resolution

    @Test
    void resolveTableViaSearchPath() throws SQLException {
        List<ColumnDef> cols = List.of(ColumnDef.of("id", 1, REG.int4()));
        cat.createTable("public", "orders", cols, List.of(), false, false);
        TableDef t = cat.resolveTable("orders", List.of("public"));
        assertEquals("orders", t.name());
    }

    @Test
    void resolveQualifiedTable() throws SQLException {
        cat.createSchema("myschema", false);
        List<ColumnDef> cols = List.of(ColumnDef.of("id", 1, REG.int4()));
        cat.createTable("myschema", "things", cols, List.of(), false, false);
        TableDef t = cat.resolveTable("myschema.things", List.of("public"));
        assertEquals("things", t.name());
    }

    @Test
    void resolveTableNotFoundThrows() {
        var ex = assertThrows(SQLException.class,
                () -> cat.resolveTable("nonexistent", List.of("public")));
        assertEquals("42P01", ex.getSQLState());
    }

    // -------------------------------------------------------------------------
    // Sequence operations

    @Test
    void createAndUseSequence() throws SQLException {
        SequenceDef seq = cat.createSequence("public", "myseq",
                1, 1, Long.MIN_VALUE, Long.MAX_VALUE, false, false);
        assertEquals(1L, seq.nextval());
        assertEquals(2L, seq.nextval());
        assertEquals(3L, seq.nextval());
        assertEquals(3L, seq.currval());
    }

    @Test
    void sequenceCurrvalBeforeNextvalThrows() throws SQLException {
        SequenceDef seq = cat.createSequence("public", "s2",
                1, 1, Long.MIN_VALUE, Long.MAX_VALUE, false, false);
        var ex = assertThrows(SQLException.class, seq::currval);
        assertEquals("55000", ex.getSQLState());
    }

    @Test
    void sequenceSetval() throws SQLException {
        SequenceDef seq = cat.createSequence("public", "s3",
                1, 1, Long.MIN_VALUE, Long.MAX_VALUE, false, false);
        seq.setval(100L);
        assertEquals(101L, seq.nextval());
    }

    // -------------------------------------------------------------------------
    // Index operations

    @Test
    void createAndDropIndex() throws SQLException {
        List<ColumnDef> cols = List.of(
                ColumnDef.of("id",    1, REG.int4()),
                ColumnDef.of("email", 2, REG.text())
        );
        cat.createTable("public", "users", cols, List.of(), false, false);
        IndexDef idx = cat.createIndex("public", "users_email_idx", "users",
                List.of(IndexColumn.asc("email")), true, false);
        assertNotNull(idx);
        assertTrue(idx.unique());
        cat.dropIndex("public", "users_email_idx", false, false);
    }

    // -------------------------------------------------------------------------
    // Virtual tables

    @Test
    void virtualTablesRegistered() {
        assertNotNull(cat.getVirtualTable("pg_catalog", "pg_namespace"));
        assertNotNull(cat.getVirtualTable("pg_catalog", "pg_class"));
        assertNotNull(cat.getVirtualTable("pg_catalog", "pg_attribute"));
        assertNotNull(cat.getVirtualTable("pg_catalog", "pg_index"));
        assertNotNull(cat.getVirtualTable("pg_catalog", "pg_constraint"));
    }

    @Test
    void pgNamespaceScan() throws SQLException {
        cat.createSchema("testschema", false);
        VirtualTable vt = cat.getVirtualTable("pg_catalog", "pg_namespace");
        List<Object[]> rows = new ArrayList<>();
        vt.scan(cat).forEach(rows::add);
        assertTrue(rows.size() >= 2, "should have at least pg_catalog and public");
        boolean foundPublic = rows.stream().anyMatch(r -> "public".equals(r[1]));
        assertTrue(foundPublic);
        boolean foundTest = rows.stream().anyMatch(r -> "testschema".equals(r[1]));
        assertTrue(foundTest);
    }

    @Test
    void pgClassScan() throws SQLException {
        List<ColumnDef> cols = List.of(ColumnDef.of("id", 1, REG.int4()));
        cat.createTable("public", "pg5a_test", cols, List.of(), false, false);
        VirtualTable vt = cat.getVirtualTable("pg_catalog", "pg_class");
        List<Object[]> rows = new ArrayList<>();
        vt.scan(cat).forEach(rows::add);
        boolean found = rows.stream().anyMatch(r -> "pg5a_test".equals(r[1]));
        assertTrue(found);
    }

    @Test
    void pgAttributeScan() throws SQLException {
        List<ColumnDef> cols = List.of(
                ColumnDef.notNull("id",   1, REG.int4()),
                ColumnDef.of     ("name", 2, REG.text())
        );
        cat.createTable("public", "pg5a_attr_test", cols, List.of(), false, false);
        VirtualTable vt = cat.getVirtualTable("pg_catalog", "pg_attribute");
        List<Object[]> rows = new ArrayList<>();
        vt.scan(cat).forEach(rows::add);
        long count = rows.stream().filter(r -> "id".equals(r[1]) || "name".equals(r[1])).count();
        assertTrue(count >= 2);
    }

    @Test
    void pgConstraintScanShowsPrimaryKey() throws SQLException {
        List<ColumnDef> cols = List.of(ColumnDef.notNull("id", 1, REG.int4()));
        List<Constraint> cons = List.of(new Constraint.PrimaryKey("pk_c5a", List.of("id")));
        cat.createTable("public", "pg5a_con_test", cols, cons, false, false);
        VirtualTable vt = cat.getVirtualTable("pg_catalog", "pg_constraint");
        List<Object[]> rows = new ArrayList<>();
        vt.scan(cat).forEach(rows::add);
        boolean found = rows.stream().anyMatch(r -> "p".equals(r[3]));
        assertTrue(found, "expected at least one primary key constraint row");
    }

    // -------------------------------------------------------------------------
    // FunctionRegistry

    @Test
    void functionRegistryHasBuiltins() {
        // Phase 5b registers built-in functions into CatalogManager automatically
        assertNotNull(cat.functions().findScalar("now", 0));
        assertNotNull(cat.functions().findAggregate("count"));
    }

    // -------------------------------------------------------------------------
    // Session integration

    @Test
    void sessionCatalogIntegration() throws Exception {
        DatabaseRegistry.drop("sess_cat_test");
        try {
            var db    = DatabaseRegistry.getOrCreate("sess_cat_test");
            var sess  = db.openSession();
            assertNotNull(sess.catalog());
            assertNotNull(sess.catalog().getSchemaOrNull("public"));

            // CREATE SCHEMA via SQL
            sess.execute("CREATE SCHEMA IF NOT EXISTS myapp");
            assertNotNull(sess.catalog().getSchemaOrNull("myapp"));

            // DROP SCHEMA
            sess.execute("DROP SCHEMA IF EXISTS myapp");
            assertNull(sess.catalog().getSchemaOrNull("myapp"));

            // Search path
            sess.execute("SET search_path TO public");
            assertEquals(List.of("public"), sess.searchPath());
        } finally {
            DatabaseRegistry.drop("sess_cat_test");
        }
    }

    @Test
    void oidGeneratorMonotonicallyIncreases() {
        long a = cat.nextOid();
        long b = cat.nextOid();
        long c = cat.nextOid();
        assertTrue(a < b && b < c);
        assertTrue(a >= 16384L);
    }
}
