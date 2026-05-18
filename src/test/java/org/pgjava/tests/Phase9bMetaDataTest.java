package org.pgjava.tests;

import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 9b Priority 3: DatabaseMetaData correctness.
 *
 * Tests run via JDBC directly (DriverManager) so we exercise the full JDBC stack.
 */
class Phase9bMetaDataTest {

    private static String dbUrl;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        dbUrl = "jdbc:pgjava:mem:phase9b_meta_" + System.nanoTime();
        conn = DriverManager.getConnection(dbUrl);
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE employees (
                        id     int  NOT NULL,
                        name   text NOT NULL,
                        salary int,
                        CONSTRAINT pk_emp PRIMARY KEY (id)
                    )
                    """);
            st.execute("""
                    CREATE TABLE departments (
                        id   int  NOT NULL,
                        name text NOT NULL,
                        CONSTRAINT pk_dept PRIMARY KEY (id)
                    )
                    """);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    // =========================================================================
    // getTables
    // =========================================================================

    @Test void getTablesReturnsUserTables() throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        ResultSet rs = meta.getTables(null, null, null, new String[]{"TABLE"});
        Set<String> tables = new HashSet<>();
        while (rs.next()) {
            String schema = rs.getString("TABLE_SCHEM");
            String table  = rs.getString("TABLE_NAME");
            String type   = rs.getString("TABLE_TYPE");
            // Only user tables, not system tables
            if (!"pg_catalog".equalsIgnoreCase(schema) &&
                !"information_schema".equalsIgnoreCase(schema)) {
                tables.add(schema + "." + table);
                assertEquals("TABLE", type);
            }
        }
        assertTrue(tables.contains("public.employees"),
                "Expected public.employees in " + tables);
        assertTrue(tables.contains("public.departments"),
                "Expected public.departments in " + tables);
    }

    @Test void getTablesFilterByName() throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        ResultSet rs = meta.getTables(null, null, "employees", new String[]{"TABLE"});
        Set<String> names = new HashSet<>();
        while (rs.next()) names.add(rs.getString("TABLE_NAME"));
        assertTrue(names.contains("employees"));
        assertFalse(names.contains("departments"));
    }

    // =========================================================================
    // getSchemas
    // =========================================================================

    @Test void getSchemasContainsPublic() throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        ResultSet rs = meta.getSchemas();
        Set<String> schemas = new HashSet<>();
        while (rs.next()) schemas.add(rs.getString("TABLE_SCHEM"));
        assertTrue(schemas.contains("public"), "Expected 'public' in " + schemas);
        // System schemas must not appear
        assertFalse(schemas.contains("pg_catalog"));
        assertFalse(schemas.contains("information_schema"));
    }

    // =========================================================================
    // getColumns
    // =========================================================================

    @Test void getColumnsReturnsCorrectTypes() throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        ResultSet rs = meta.getColumns(null, "public", "employees", null);
        Map<String, Integer> typeByCol = new LinkedHashMap<>();
        Map<String, Integer> nullableByCol = new LinkedHashMap<>();
        Map<String, Integer> ordinalByCol = new LinkedHashMap<>();
        while (rs.next()) {
            String col      = rs.getString("COLUMN_NAME");
            int    dataType = rs.getInt("DATA_TYPE");
            int    nullable = rs.getInt("NULLABLE");
            int    ordinal  = rs.getInt("ORDINAL_POSITION");
            typeByCol.put(col, dataType);
            nullableByCol.put(col, nullable);
            ordinalByCol.put(col, ordinal);
        }
        // id: int → INTEGER (4)
        assertEquals(Types.INTEGER, typeByCol.get("id"), "id should be INTEGER");
        // name: text → VARCHAR (12)
        assertEquals(Types.VARCHAR, typeByCol.get("name"), "name should be VARCHAR");
        // salary: int → INTEGER
        assertEquals(Types.INTEGER, typeByCol.get("salary"), "salary should be INTEGER");

        // NOT NULL checks
        assertEquals(ResultSetMetaData.columnNoNulls, (int) nullableByCol.get("id"),
                "id should be NOT NULL");
        assertEquals(ResultSetMetaData.columnNoNulls, (int) nullableByCol.get("name"),
                "name should be NOT NULL");
        assertEquals(ResultSetMetaData.columnNullable, (int) nullableByCol.get("salary"),
                "salary should be nullable");

        // Ordinal positions must be 1-based and ordered
        assertEquals(1, (int) ordinalByCol.get("id"));
        assertEquals(2, (int) ordinalByCol.get("name"));
        assertEquals(3, (int) ordinalByCol.get("salary"));
    }

    @Test void getColumnsIsNullableString() throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        ResultSet rs = meta.getColumns(null, "public", "employees", "id");
        assertTrue(rs.next());
        assertEquals("NO", rs.getString("IS_NULLABLE"));
    }

    @Test void getColumnsFilterByName() throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        ResultSet rs = meta.getColumns(null, "public", "employees", "salary");
        int count = 0;
        while (rs.next()) {
            assertEquals("salary", rs.getString("COLUMN_NAME"));
            count++;
        }
        assertEquals(1, count);
    }

    // =========================================================================
    // getPrimaryKeys
    // =========================================================================

    @Test void getPrimaryKeys() throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        ResultSet rs = meta.getPrimaryKeys(null, "public", "employees");
        assertTrue(rs.next(), "expected at least one PK row");
        assertEquals("id",     rs.getString("COLUMN_NAME"));
        assertEquals(1,        rs.getShort("KEY_SEQ"));
        assertEquals("pk_emp", rs.getString("PK_NAME"));
        assertFalse(rs.next(), "expected exactly one PK column");
    }

    @Test void getPrimaryKeysNoMatch() throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE nopk (x int)");
        }
        ResultSet rs = meta.getPrimaryKeys(null, "public", "nopk");
        assertFalse(rs.next(), "table with no PK should return empty");
    }

    // =========================================================================
    // getIndexInfo
    // =========================================================================

    @Test void getIndexInfoPrimaryKey() throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        ResultSet rs = meta.getIndexInfo(null, "public", "employees", false, false);
        boolean foundPk = false;
        while (rs.next()) {
            String indexName = rs.getString("INDEX_NAME");
            String colName   = rs.getString("COLUMN_NAME");
            if ("id".equals(colName)) {
                foundPk = true;
                assertFalse(rs.getBoolean("NON_UNIQUE"), "PK index should be unique");
            }
        }
        assertTrue(foundPk, "Expected PK index on 'id' column");
    }

    @Test void getIndexInfoUniqueFilter() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (a int, b text, UNIQUE (a))");
        }
        DatabaseMetaData meta = conn.getMetaData();
        ResultSet rs = meta.getIndexInfo(null, "public", "t", true, false);
        boolean foundUnique = false;
        while (rs.next()) {
            foundUnique = true;
            assertFalse(rs.getBoolean("NON_UNIQUE"));
        }
        assertTrue(foundUnique, "Expected at least one unique index");
    }

    // =========================================================================
    // supportsGetGeneratedKeys
    // =========================================================================

    @Test void supportsGetGeneratedKeys() throws Exception {
        assertTrue(conn.getMetaData().supportsGetGeneratedKeys());
    }
}
