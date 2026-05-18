package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.catalog.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.Session;
import org.pgjava.storage.HeapStorage;
import org.pgjava.storage.HeapTable;
import org.pgjava.types.PgOid;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6: DDL execution — CREATE/DROP TABLE, INDEX, SEQUENCE, VIEW;
 * ALTER TABLE; TRUNCATE.
 *
 * Tests run directly against pgjava internals (no DualExecutor / golden PG).
 * SQL is parsed and executed by Session; catalog and storage state are
 * inspected via the internal API.
 */
class Phase6DdlTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("phase6_ddl_test_" + System.nanoTime());
        sess = db.openSession();
    }

    @AfterEach
    void tearDown() throws Exception {
        sess.close();
    }

    // =========================================================================
    // CREATE TABLE

    @Test void createTableSimple() throws Exception {
        sess.execute("CREATE TABLE t1 (id integer, name text)");
        TableDef t = resolveTable("t1");
        assertEquals("t1", t.name());
        assertEquals("public", t.schemaName());
        assertEquals(2, t.columnCount());

        ColumnDef id = t.column("id");
        assertNotNull(id);
        assertEquals(PgOid.INT4, id.type().oid());
        assertEquals(1, id.attnum());

        ColumnDef name = t.column("name");
        assertNotNull(name);
        assertEquals(PgOid.TEXT, name.type().oid());
        assertEquals(2, name.attnum());
    }

    @Test void createTableStorageRegistered() throws Exception {
        sess.execute("CREATE TABLE t2 (id integer)");
        TableDef t = resolveTable("t2");
        HeapTable ht = db.storage().table(t.oid());
        assertNotNull(ht, "HeapTable must be registered in storage after CREATE TABLE");
    }

    @Test void createTableIfNotExists() throws Exception {
        sess.execute("CREATE TABLE t3 (x integer)");
        // Should not throw
        sess.execute("CREATE TABLE IF NOT EXISTS t3 (x integer)");
        TableDef t = resolveTable("t3");
        assertEquals(1, t.columnCount());
    }

    @Test void createTableDuplicateThrows() throws Exception {
        sess.execute("CREATE TABLE t4 (x integer)");
        SQLException ex = assertThrows(SQLException.class,
                () -> sess.execute("CREATE TABLE t4 (x integer)"));
        assertEquals("42P07", ex.getSQLState());
    }

    @Test void createTableNotNull() throws Exception {
        sess.execute("CREATE TABLE t5 (id integer NOT NULL, name text)");
        TableDef t = resolveTable("t5");
        assertFalse(t.column("id").nullable(), "id should be NOT NULL");
        assertTrue(t.column("name").nullable(), "name should be nullable");
    }

    @Test void createTablePrimaryKey() throws Exception {
        sess.execute("CREATE TABLE t6 (id integer PRIMARY KEY, name text)");
        TableDef t = resolveTable("t6");
        Constraint.PrimaryKey pk = t.primaryKey();
        assertNotNull(pk);
        assertEquals(List.of("id"), pk.columns());
        // PK implies NOT NULL
        assertFalse(t.column("id").nullable());
        // PK index in storage
        assertFalse(db.storage().indexesForTable(t.oid()).isEmpty(),
                "PK index should be in storage");
    }

    @Test void createTableCompositePrimaryKey() throws Exception {
        sess.execute("""
                CREATE TABLE t7 (
                    a integer,
                    b text,
                    PRIMARY KEY (a, b)
                )
                """);
        TableDef t = resolveTable("t7");
        Constraint.PrimaryKey pk = t.primaryKey();
        assertNotNull(pk);
        assertEquals(List.of("a", "b"), pk.columns());
    }

    @Test void createTableUniqueConstraint() throws Exception {
        sess.execute("CREATE TABLE t8 (id integer, email text UNIQUE)");
        TableDef t = resolveTable("t8");
        boolean hasUnique = t.constraints().stream()
                .anyMatch(c -> c instanceof Constraint.Unique u
                        && u.columns().contains("email"));
        assertTrue(hasUnique, "UNIQUE constraint should exist on email");
    }

    @Test void createTableForeignKey() throws Exception {
        sess.execute("CREATE TABLE parent (id integer PRIMARY KEY)");
        sess.execute("""
                CREATE TABLE child (
                    id integer PRIMARY KEY,
                    pid integer REFERENCES parent(id)
                )
                """);
        TableDef child = resolveTable("child");
        boolean hasFk = child.constraints().stream()
                .anyMatch(c -> c instanceof Constraint.ForeignKey fk
                        && fk.refTable().equals("parent"));
        assertTrue(hasFk, "FK constraint on pid should reference parent");
    }

    @Test void createTableVarchar() throws Exception {
        sess.execute("CREATE TABLE t9 (name varchar(100))");
        TableDef t = resolveTable("t9");
        ColumnDef col = t.column("name");
        assertEquals(PgOid.VARCHAR, col.type().oid());
        assertEquals(104, col.typmod()); // 100 + 4
    }

    @Test void createTableNumeric() throws Exception {
        sess.execute("CREATE TABLE t10 (price numeric(10,2))");
        TableDef t = resolveTable("t10");
        ColumnDef col = t.column("price");
        assertEquals(PgOid.NUMERIC, col.type().oid());
        // typmod = ((10 << 16) | 2) + 4
        int expected = ((10 << 16) | 2) + 4;
        assertEquals(expected, col.typmod());
    }

    @Test void createTableSerial() throws Exception {
        sess.execute("CREATE TABLE t11 (id serial, name text)");
        TableDef t = resolveTable("t11");
        // Serial → int4
        assertEquals(PgOid.INT4, t.column("id").type().oid());
        // Serial → NOT NULL
        assertFalse(t.column("id").nullable());
        // Serial → DEFAULT nextval(...)
        assertNotNull(t.column("id").defaultExpr(), "SERIAL column should have DEFAULT nextval(...)");
        // Sequence created
        SequenceDef seq = findSequenceInSchema("public", "t11_id_seq");
        assertNotNull(seq, "Sequence t11_id_seq should be created for SERIAL column");
    }

    @Test void createTableBigserial() throws Exception {
        sess.execute("CREATE TABLE t12 (id bigserial PRIMARY KEY)");
        TableDef t = resolveTable("t12");
        assertEquals(PgOid.INT8, t.column("id").type().oid());
    }

    @Test void createTableInExplicitSchema() throws Exception {
        sess.execute("CREATE SCHEMA myschema");
        sess.execute("CREATE TABLE myschema.mytable (x integer)");
        Schema s = db.catalog().getSchemaOrNull("myschema");
        assertNotNull(s);
        assertNotNull(s.table("mytable"));
    }

    @Test void createTableAllTypeAliases() throws Exception {
        // Verify common type aliases compile and resolve without error
        sess.execute("""
                CREATE TABLE t_types (
                    a int,
                    b integer,
                    c bigint,
                    d smallint,
                    e real,
                    f double precision,
                    g boolean,
                    h text,
                    i varchar(50),
                    j char(10),
                    k date,
                    l timestamp,
                    m timestamptz,
                    n uuid,
                    o numeric(8,4)
                )
                """);
        TableDef t = resolveTable("t_types");
        assertEquals(15, t.columnCount());
        assertEquals(PgOid.INT4, t.column("a").type().oid());
        assertEquals(PgOid.INT8, t.column("c").type().oid());
        assertEquals(PgOid.INT2, t.column("d").type().oid());
        assertEquals(PgOid.BOOL, t.column("g").type().oid());
        assertEquals(PgOid.UUID, t.column("n").type().oid());
    }

    // =========================================================================
    // DROP TABLE

    @Test void dropTable() throws Exception {
        sess.execute("CREATE TABLE dtbl (x integer)");
        TableDef t = resolveTable("dtbl");
        long oid = t.oid();
        sess.execute("DROP TABLE dtbl");
        assertNull(db.catalog().getSchemaOrNull("public").table("dtbl"),
                "Table should not exist in catalog after DROP");
        assertNull(db.storage().table(oid),
                "Table should not exist in storage after DROP");
    }

    @Test void dropTableIfExists() throws Exception {
        // Should not throw even if table doesn't exist
        sess.execute("DROP TABLE IF EXISTS nonexistent_table");
    }

    @Test void dropTableNotFoundThrows() throws Exception {
        SQLException ex = assertThrows(SQLException.class,
                () -> sess.execute("DROP TABLE nonexistent_table_xyz"));
        assertEquals("42P01", ex.getSQLState());
    }

    @Test void dropTableClearsIndexesFromStorage() throws Exception {
        sess.execute("CREATE TABLE dtbl2 (id integer PRIMARY KEY)");
        TableDef t = resolveTable("dtbl2");
        long tableOid = t.oid();
        assertFalse(db.storage().indexesForTable(tableOid).isEmpty());
        sess.execute("DROP TABLE dtbl2");
        assertTrue(db.storage().indexesForTable(tableOid).isEmpty(),
                "Indexes should be cleared from storage when table is dropped");
    }

    // =========================================================================
    // TRUNCATE

    @Test void truncateEmptyTable() throws Exception {
        sess.execute("CREATE TABLE trunc1 (id integer)");
        // Truncating an empty table should succeed
        sess.execute("TRUNCATE TABLE trunc1");
        // Table still exists
        assertNotNull(resolveTable("trunc1"));
    }

    // =========================================================================
    // CREATE INDEX

    @Test void createIndex() throws Exception {
        sess.execute("CREATE TABLE idx_t (id integer, name text)");
        sess.execute("CREATE INDEX idx_name ON idx_t (name)");
        TableDef t = resolveTable("idx_t");
        IndexDef idx = db.catalog().getSchemaOrNull("public").index("idx_name");
        assertNotNull(idx);
        assertEquals("name", idx.columns().get(0).column());
        assertFalse(idx.unique());
        assertNotNull(db.storage().index(idx.oid()),
                "Index should be registered in storage");
    }

    @Test void createUniqueIndex() throws Exception {
        sess.execute("CREATE TABLE idx_t2 (id integer, email text)");
        sess.execute("CREATE UNIQUE INDEX idx_email ON idx_t2 (email)");
        IndexDef idx = db.catalog().getSchemaOrNull("public").index("idx_email");
        assertNotNull(idx);
        assertTrue(idx.unique());
    }

    @Test void createCompositeIndex() throws Exception {
        sess.execute("CREATE TABLE idx_t3 (a integer, b integer, c text)");
        sess.execute("CREATE INDEX idx_ab ON idx_t3 (a, b)");
        IndexDef idx = db.catalog().getSchemaOrNull("public").index("idx_ab");
        assertNotNull(idx);
        assertEquals(2, idx.columns().size());
        assertEquals("a", idx.columns().get(0).column());
        assertEquals("b", idx.columns().get(1).column());
    }

    @Test void createIndexIfNotExists() throws Exception {
        sess.execute("CREATE TABLE idx_t4 (x integer)");
        sess.execute("CREATE INDEX idx_x ON idx_t4 (x)");
        // Should not throw
        sess.execute("CREATE INDEX IF NOT EXISTS idx_x ON idx_t4 (x)");
    }

    @Test void createIndexAutoName() throws Exception {
        // Without explicit name, index should be auto-named
        sess.execute("CREATE TABLE idx_t5 (x integer, y text)");
        sess.execute("CREATE INDEX ON idx_t5 (x)");
        // At least one index (besides any PK) should exist
        Schema pub = db.catalog().getSchemaOrNull("public");
        long indexCount = pub.indexes().values().stream()
                .filter(i -> i.tableName().equals("idx_t5"))
                .count();
        assertTrue(indexCount >= 1);
    }

    // =========================================================================
    // DROP INDEX

    @Test void dropIndex() throws Exception {
        sess.execute("CREATE TABLE didx_t (x integer)");
        sess.execute("CREATE INDEX idx_to_drop ON didx_t (x)");
        IndexDef idx = db.catalog().getSchemaOrNull("public").index("idx_to_drop");
        long oid = idx.oid();
        sess.execute("DROP INDEX idx_to_drop");
        assertNull(db.catalog().getSchemaOrNull("public").index("idx_to_drop"));
        assertNull(db.storage().index(oid));
    }

    @Test void dropIndexIfExists() throws Exception {
        sess.execute("DROP INDEX IF EXISTS nonexistent_idx_xyz");
    }

    @Test void dropIndexNotFoundThrows() throws Exception {
        SQLException ex = assertThrows(SQLException.class,
                () -> sess.execute("DROP INDEX nonexistent_idx_xyz"));
        assertEquals("42704", ex.getSQLState());
    }

    // =========================================================================
    // CREATE SEQUENCE

    @Test void createSequenceDefault() throws Exception {
        sess.execute("CREATE SEQUENCE seq1");
        SequenceDef seq = findSequenceInSchema("public", "seq1");
        assertNotNull(seq);
        assertEquals(1L, seq.start());
        assertEquals(1L, seq.increment());
    }

    @Test void createSequenceWithOptions() throws Exception {
        sess.execute("CREATE SEQUENCE seq2 START WITH 10 INCREMENT BY 5 NO CYCLE");
        SequenceDef seq = findSequenceInSchema("public", "seq2");
        assertNotNull(seq);
        assertEquals(10L, seq.start());
        assertEquals(5L, seq.increment());
        assertFalse(seq.cycle());
    }

    @Test void createSequenceIfNotExists() throws Exception {
        sess.execute("CREATE SEQUENCE seq3");
        sess.execute("CREATE SEQUENCE IF NOT EXISTS seq3");
    }

    @Test void createSequenceDuplicateThrows() throws Exception {
        sess.execute("CREATE SEQUENCE seq4");
        assertThrows(SQLException.class, () -> sess.execute("CREATE SEQUENCE seq4"));
    }

    @Test void sequenceNextval() throws Exception {
        sess.execute("CREATE SEQUENCE myseq START WITH 1 INCREMENT BY 1");
        SequenceDef seq = findSequenceInSchema("public", "myseq");
        assertEquals(1L, seq.nextval());
        assertEquals(2L, seq.nextval());
        assertEquals(3L, seq.nextval());
    }

    // =========================================================================
    // DROP SEQUENCE

    @Test void dropSequence() throws Exception {
        sess.execute("CREATE SEQUENCE seq_to_drop");
        sess.execute("DROP SEQUENCE seq_to_drop");
        assertNull(findSequenceInSchema("public", "seq_to_drop"));
    }

    @Test void dropSequenceIfExists() throws Exception {
        sess.execute("DROP SEQUENCE IF EXISTS nonexistent_seq_xyz");
    }

    @Test void dropSequenceNotFoundThrows() throws Exception {
        assertThrows(SQLException.class,
                () -> sess.execute("DROP SEQUENCE nonexistent_seq_xyz"));
    }

    // =========================================================================
    // CREATE VIEW / DROP VIEW

    @Test void createView() throws Exception {
        sess.execute("CREATE TABLE vt (id integer, name text)");
        sess.execute("CREATE VIEW v1 AS SELECT id, name FROM vt WHERE id > 0");
        ViewDef v = db.catalog().getSchemaOrNull("public").view("v1");
        assertNotNull(v);
        assertEquals("v1", v.name());
        assertNotNull(v.parsedDef(), "Parsed SELECT should be stored");
    }

    @Test void createOrReplaceView() throws Exception {
        sess.execute("CREATE TABLE vt2 (x integer)");
        sess.execute("CREATE VIEW v2 AS SELECT x FROM vt2");
        sess.execute("CREATE OR REPLACE VIEW v2 AS SELECT x FROM vt2 WHERE x > 0");
        assertNotNull(db.catalog().getSchemaOrNull("public").view("v2"));
    }

    @Test void dropView() throws Exception {
        sess.execute("CREATE TABLE vt3 (x integer)");
        sess.execute("CREATE VIEW v3 AS SELECT x FROM vt3");
        sess.execute("DROP VIEW v3");
        assertNull(db.catalog().getSchemaOrNull("public").view("v3"));
    }

    @Test void dropViewIfExists() throws Exception {
        sess.execute("DROP VIEW IF EXISTS nonexistent_view_xyz");
    }

    // =========================================================================
    // ALTER TABLE

    @Test void alterTableAddColumn() throws Exception {
        sess.execute("CREATE TABLE alt1 (id integer)");
        sess.execute("ALTER TABLE alt1 ADD COLUMN email text");
        TableDef t = resolveTable("alt1");
        assertEquals(2, t.columnCount());
        assertNotNull(t.column("email"));
        assertEquals(PgOid.TEXT, t.column("email").type().oid());
        assertEquals(2, t.column("email").attnum());
    }

    @Test void alterTableAddColumnNotNull() throws Exception {
        sess.execute("CREATE TABLE alt2 (id integer)");
        sess.execute("ALTER TABLE alt2 ADD COLUMN code text NOT NULL");
        assertFalse(resolveTable("alt2").column("code").nullable());
    }

    @Test void alterTableAddColumnDuplicateThrows() throws Exception {
        sess.execute("CREATE TABLE alt3 (id integer)");
        assertThrows(SQLException.class,
                () -> sess.execute("ALTER TABLE alt3 ADD COLUMN id text"));
    }

    @Test void alterTableDropColumn() throws Exception {
        sess.execute("CREATE TABLE alt4 (id integer, name text, extra boolean)");
        sess.execute("ALTER TABLE alt4 DROP COLUMN extra");
        TableDef t = resolveTable("alt4");
        assertEquals(2, t.columnCount());
        assertNull(t.column("extra"));
        // Remaining columns should have contiguous attnums
        assertEquals(1, t.column("id").attnum());
        assertEquals(2, t.column("name").attnum());
    }

    @Test void alterTableDropColumnNotFoundThrows() throws Exception {
        sess.execute("CREATE TABLE alt5 (id integer)");
        assertThrows(SQLException.class,
                () -> sess.execute("ALTER TABLE alt5 DROP COLUMN nonexistent"));
    }

    @Test void alterTableSetNotNull() throws Exception {
        sess.execute("CREATE TABLE alt6 (id integer, name text)");
        sess.execute("ALTER TABLE alt6 ALTER COLUMN name SET NOT NULL");
        assertFalse(resolveTable("alt6").column("name").nullable());
    }

    @Test void alterTableDropNotNull() throws Exception {
        sess.execute("CREATE TABLE alt7 (id integer NOT NULL, name text)");
        sess.execute("ALTER TABLE alt7 ALTER COLUMN id DROP NOT NULL");
        assertTrue(resolveTable("alt7").column("id").nullable());
    }

    @Test void alterTableDropDefault() throws Exception {
        sess.execute("CREATE TABLE alt8 (id integer DEFAULT 42)");
        assertNotNull(resolveTable("alt8").column("id").defaultExpr());
        sess.execute("ALTER TABLE alt8 ALTER COLUMN id DROP DEFAULT");
        assertNull(resolveTable("alt8").column("id").defaultExpr());
    }

    @Test void alterTableAddConstraintUnique() throws Exception {
        sess.execute("CREATE TABLE alt9 (id integer, email text)");
        sess.execute("ALTER TABLE alt9 ADD CONSTRAINT uq_email UNIQUE (email)");
        TableDef t = resolveTable("alt9");
        boolean hasUq = t.constraints().stream()
                .anyMatch(c -> c instanceof Constraint.Unique u
                        && "uq_email".equalsIgnoreCase(u.name()));
        assertTrue(hasUq);
    }

    @Test void alterTableDropConstraint() throws Exception {
        sess.execute("CREATE TABLE alt10 (id integer CONSTRAINT chk_pos CHECK (id > 0))");
        sess.execute("ALTER TABLE alt10 DROP CONSTRAINT chk_pos");
        TableDef t = resolveTable("alt10");
        boolean hasChk = t.constraints().stream()
                .anyMatch(c -> "chk_pos".equalsIgnoreCase(c.name()));
        assertFalse(hasChk, "Constraint should be dropped");
    }

    @Test void alterTableNotFoundThrows() throws Exception {
        assertThrows(SQLException.class,
                () -> sess.execute("ALTER TABLE nonexistent_xyz ADD COLUMN x integer"));
    }

    // =========================================================================
    // Multi-statement

    @Test void multiStatement() throws Exception {
        sess.execute("""
                CREATE TABLE ms1 (id integer PRIMARY KEY);
                CREATE TABLE ms2 (id integer, ms1_id integer);
                CREATE INDEX idx_ms2 ON ms2 (ms1_id);
                """);
        assertNotNull(resolveTable("ms1"));
        assertNotNull(resolveTable("ms2"));
        assertNotNull(db.catalog().getSchemaOrNull("public").index("idx_ms2"));
    }

    @Test void createDropRecreate() throws Exception {
        sess.execute("CREATE TABLE cr1 (x integer)");
        sess.execute("DROP TABLE cr1");
        sess.execute("CREATE TABLE cr1 (x integer, y text)"); // different schema
        TableDef t = resolveTable("cr1");
        assertEquals(2, t.columnCount());
    }

    // =========================================================================
    // Helpers

    private TableDef resolveTable(String name) throws SQLException {
        return db.catalog().resolveTable(name, sess.searchPath());
    }

    private SequenceDef findSequenceInSchema(String schemaName, String seqName) {
        Schema s = db.catalog().getSchemaOrNull(schemaName);
        return s != null ? s.sequence(seqName.toLowerCase()) : null;
    }
}
