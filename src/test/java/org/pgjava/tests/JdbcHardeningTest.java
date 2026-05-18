package org.pgjava.tests;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JDBC driver completeness: batch operations, getObject type conversion,
 * ResultSet edge cases, PreparedStatement batch.
 */
class JdbcHardeningTest {

    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:pgjava:mem:jdbc_hard_" + System.nanoTime());
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (conn != null) conn.close();
    }

    // ── Statement batch ───────────────────────────────────────────────────────

    @Test void statementBatch() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("CREATE TABLE t (id int, val text)");
        st.addBatch("INSERT INTO t VALUES (1, 'a')");
        st.addBatch("INSERT INTO t VALUES (2, 'b')");
        st.addBatch("INSERT INTO t VALUES (3, 'c')");
        int[] results = st.executeBatch();
        assertEquals(3, results.length);
        for (int r : results) assertEquals(1, r);

        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t");
        assertTrue(rs.next());
        assertEquals(3, rs.getInt(1));
        st.close();
    }

    @Test void statementBatchClear() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("CREATE TABLE t (id int)");
        st.addBatch("INSERT INTO t VALUES (1)");
        st.clearBatch();
        int[] results = st.executeBatch();
        assertEquals(0, results.length);
        st.close();
    }

    // ── PreparedStatement batch ───────────────────────────────────────────────

    @Test void preparedStatementBatch() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("CREATE TABLE t (id int, name text)");
        st.close();

        PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?, ?)");
        ps.setInt(1, 1); ps.setString(2, "alice"); ps.addBatch();
        ps.setInt(1, 2); ps.setString(2, "bob"); ps.addBatch();
        ps.setInt(1, 3); ps.setString(2, "carol"); ps.addBatch();
        int[] results = ps.executeBatch();
        assertEquals(3, results.length);
        for (int r : results) assertEquals(1, r);
        ps.close();

        st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t");
        assertTrue(rs.next());
        assertEquals(3, rs.getInt(1));
        st.close();
    }

    @Test void preparedStatementBatchClear() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("CREATE TABLE t (id int)");
        st.close();

        PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?)");
        ps.setInt(1, 1); ps.addBatch();
        ps.clearBatch();
        int[] results = ps.executeBatch();
        assertEquals(0, results.length);
        ps.close();
    }

    // ── getObject with type conversion ────────────────────────────────────────

    @Test void getObjectIntToLong() throws SQLException {
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT 42");
        assertTrue(rs.next());
        assertEquals(42L, rs.getObject(1, Long.class));
        st.close();
    }

    @Test void getObjectIntToString() throws SQLException {
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT 42");
        assertTrue(rs.next());
        assertEquals("42", rs.getObject(1, String.class));
        st.close();
    }

    @Test void getObjectIntToDouble() throws SQLException {
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT 42");
        assertTrue(rs.next());
        assertEquals(42.0, rs.getObject(1, Double.class));
        st.close();
    }

    @Test void getObjectStringToBigDecimal() throws SQLException {
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT 3.14::numeric");
        assertTrue(rs.next());
        BigDecimal bd = rs.getObject(1, BigDecimal.class);
        assertNotNull(bd);
        assertEquals(0, bd.compareTo(new BigDecimal("3.14")));
        st.close();
    }

    @Test void getObjectNull() throws SQLException {
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT NULL::int");
        assertTrue(rs.next());
        assertNull(rs.getObject(1, Integer.class));
        st.close();
    }

    // ── ResultSet edge cases ──────────────────────────────────────────────────

    @Test void wasNullAfterGetInt() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("CREATE TABLE t (val int)");
        st.execute("INSERT INTO t VALUES (NULL)");
        ResultSet rs = st.executeQuery("SELECT val FROM t");
        assertTrue(rs.next());
        assertEquals(0, rs.getInt(1)); // NULL → 0 for getInt
        assertTrue(rs.wasNull());
        st.close();
    }

    @Test void wasNullAfterGetString() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("CREATE TABLE t (val text)");
        st.execute("INSERT INTO t VALUES (NULL)");
        ResultSet rs = st.executeQuery("SELECT val FROM t");
        assertTrue(rs.next());
        assertNull(rs.getString(1));
        assertTrue(rs.wasNull());
        st.close();
    }

    @Test void findColumnCaseInsensitive() throws SQLException {
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT 42 AS MyColumn");
        assertTrue(rs.next());
        assertEquals(42, rs.getInt("mycolumn"));
        assertEquals(42, rs.getInt("MYCOLUMN"));
        st.close();
    }

    @Test void resultSetMetaData() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("CREATE TABLE t (id int, name text, salary numeric)");
        st.execute("INSERT INTO t VALUES (1, 'alice', 50000)");
        ResultSet rs = st.executeQuery("SELECT id, name, salary FROM t");
        ResultSetMetaData md = rs.getMetaData();
        assertEquals(3, md.getColumnCount());
        assertEquals("id", md.getColumnName(1));
        assertEquals("name", md.getColumnName(2));
        assertEquals("salary", md.getColumnName(3));
        st.close();
    }

    @Test void emptyResultSet() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("CREATE TABLE t (id int)");
        ResultSet rs = st.executeQuery("SELECT id FROM t");
        assertFalse(rs.next());
        assertTrue(rs.isAfterLast() || rs.getRow() == 0);
        st.close();
    }

    // ── Connection / Statement lifecycle ──────────────────────────────────────

    @Test void statementCloseIdempotent() throws SQLException {
        Statement st = conn.createStatement();
        st.close();
        st.close(); // should not throw
        assertTrue(st.isClosed());
    }

    @Test void connectionIsValid() throws SQLException {
        assertTrue(conn.isValid(1));
        conn.close();
        assertFalse(conn.isValid(1));
    }

    @Test void getAutoCommitDefault() throws SQLException {
        assertTrue(conn.getAutoCommit());
    }

    @Test void setAutoCommitAndTransact() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("CREATE TABLE t (id int)");
        conn.setAutoCommit(false);
        st.execute("INSERT INTO t VALUES (1)");
        conn.commit();
        conn.setAutoCommit(true);
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        st.close();
    }

    // ── PreparedStatement parameter types ─────────────────────────────────────

    @Test void preparedStatementAllTypes() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("CREATE TABLE t (b boolean, i int, bi bigint, f float8, s text, ts timestamp)");
        st.close();

        PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t VALUES (?, ?, ?, ?, ?, ?)");
        ps.setBoolean(1, true);
        ps.setInt(2, 42);
        ps.setLong(3, 9999999999L);
        ps.setDouble(4, 3.14);
        ps.setString(5, "hello");
        ps.setTimestamp(6, Timestamp.valueOf("2024-01-15 10:30:00"));
        assertEquals(1, ps.executeUpdate());
        ps.close();

        st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT * FROM t");
        assertTrue(rs.next());
        assertTrue(rs.getBoolean(1));
        assertEquals(42, rs.getInt(2));
        assertEquals(9999999999L, rs.getLong(3));
        assertEquals(3.14, rs.getDouble(4), 1e-9);
        assertEquals("hello", rs.getString(5));
        assertNotNull(rs.getTimestamp(6));
        st.close();
    }

    // ── PreparedStatement setNull ─────────────────────────────────────────────

    @Test void preparedStatementSetNull() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("CREATE TABLE t (id int, val text)");
        st.close();

        PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?, ?)");
        ps.setInt(1, 1);
        ps.setNull(2, Types.VARCHAR);
        ps.executeUpdate();
        ps.close();

        st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT val FROM t WHERE id = 1");
        assertTrue(rs.next());
        assertNull(rs.getString(1));
        st.close();
    }

    // ── DatabaseMetaData basics ───────────────────────────────────────────────

    @Test void databaseMetaDataBasics() throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        assertEquals("PostgreSQL", md.getDatabaseProductName());
        assertEquals("pgjava-jdbc", md.getDriverName());
        assertTrue(md.supportsTransactions());
    }

    @Test void databaseMetaDataGetTables() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("CREATE TABLE meta_test (id int)");
        st.close();

        DatabaseMetaData md = conn.getMetaData();
        ResultSet rs = md.getTables(null, "public", "meta_test", null);
        assertTrue(rs.next());
        assertEquals("meta_test", rs.getString("TABLE_NAME"));
    }

    @Test void databaseMetaDataGetColumns() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("CREATE TABLE col_test (id int PRIMARY KEY, name text NOT NULL, salary numeric)");
        st.close();

        DatabaseMetaData md = conn.getMetaData();
        ResultSet rs = md.getColumns(null, "public", "col_test", null);
        assertTrue(rs.next()); assertEquals("id", rs.getString("COLUMN_NAME"));
        assertTrue(rs.next()); assertEquals("name", rs.getString("COLUMN_NAME"));
        assertTrue(rs.next()); assertEquals("salary", rs.getString("COLUMN_NAME"));
        assertFalse(rs.next());
    }

    @Test void databaseMetaDataGetPrimaryKeys() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("CREATE TABLE pk_test (id int PRIMARY KEY, val text)");
        st.close();

        DatabaseMetaData md = conn.getMetaData();
        ResultSet rs = md.getPrimaryKeys(null, "public", "pk_test");
        assertTrue(rs.next());
        assertEquals("id", rs.getString("COLUMN_NAME"));
    }
}
