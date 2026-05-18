package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.*;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 12: COPY FROM STDIN (bulk load) and COPY TO STDOUT (bulk export).
 *
 * <p>Tests use the embedded Session API directly so we can pass inline COPY data
 * without going through the full psql-to-JDBC pipeline.
 */
class Phase12CopyTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("phase12_copy_" + System.nanoTime());
        sess = db.openSession();
    }

    @AfterEach
    void tearDown() {
        sess.close();
    }

    private QueryResult q(String sql) throws SQLException { return sess.execute(sql); }
    private void exec(String sql)       throws SQLException { sess.execute(sql); }

    // =========================================================================
    // COPY FROM STDIN — TEXT format (tab-delimited)
    // =========================================================================

    @Test void copyFromStdinText_basic() throws SQLException {
        exec("CREATE TABLE animals (name text, legs int)");
        exec("""
             COPY animals FROM stdin;
             dog\t4
             cat\t4
             bird\t2
             \\.""");
        QueryResult r = q("SELECT name, legs FROM animals ORDER BY name");
        assertEquals(3, r.rows().size());
        assertEquals("bird", r.rows().get(0)[0]);
        assertEquals(2,      ((Number) r.rows().get(0)[1]).intValue());
        assertEquals("cat",  r.rows().get(1)[0]);
        assertEquals("dog",  r.rows().get(2)[0]);
    }

    @Test void copyFromStdinText_nullMarker() throws SQLException {
        exec("CREATE TABLE nullable (id int, val text)");
        exec("COPY nullable FROM stdin;\n1\t\\N\n2\thello\n\\.");
        QueryResult r = q("SELECT id, val FROM nullable ORDER BY id");
        assertEquals(2, r.rows().size());
        assertNull(r.rows().get(0)[1],     "\\N should decode to NULL");
        assertEquals("hello", r.rows().get(1)[1]);
    }

    @Test void copyFromStdinText_columnList() throws SQLException {
        exec("CREATE TABLE partial (a int, b text, c boolean)");
        exec("COPY partial (a, b) FROM stdin;\n10\thello\n20\tworld\n\\.");
        QueryResult r = q("SELECT a, b, c FROM partial ORDER BY a");
        assertEquals(2, r.rows().size());
        assertEquals(10,      ((Number) r.rows().get(0)[0]).intValue());
        assertEquals("hello", r.rows().get(0)[1]);
        assertNull(r.rows().get(0)[2]);  // c not provided → null
    }

    @Test void copyFromStdinText_rowCount() throws SQLException {
        exec("CREATE TABLE rc (x int)");
        QueryResult r = exec_and_return("""
                COPY rc FROM stdin;
                1
                2
                3
                4
                5
                \\.""");
        assertEquals(5, r.updateCount());
    }

    @Test void copyFromStdinText_noData() throws SQLException {
        exec("CREATE TABLE empty_copy (x int)");
        // COPY FROM STDIN with no data → 0 rows, no error
        QueryResult r = q("COPY empty_copy FROM stdin");
        assertEquals(0, r.updateCount());
        assertEquals(0L, ((Number) q("SELECT COUNT(*) FROM empty_copy").rows().get(0)[0]).longValue());
    }

    @Test void copyFromStdinText_backslashEscape() throws SQLException {
        exec("CREATE TABLE escapes (val text)");
        exec("COPY escapes FROM stdin;\nhello\\\\world\n\\.");
        QueryResult r = q("SELECT val FROM escapes");
        assertEquals("hello\\world", r.rows().get(0)[0]);
    }

    @Test void copyFromStdinText_numericTypes() throws SQLException {
        exec("CREATE TABLE nums (i int, b bigint, f double precision)");
        exec("COPY nums FROM stdin;\n42\t1234567890123\t3.14\n\\.");
        QueryResult r = q("SELECT i, b, f FROM nums");
        Object[] row = r.rows().get(0);
        assertEquals(42,            ((Number) row[0]).intValue());
        assertEquals(1234567890123L, ((Number) row[1]).longValue());
        assertEquals(3.14,          ((Number) row[2]).doubleValue(), 1e-9);
    }

    // =========================================================================
    // COPY FROM STDIN — CSV format
    // =========================================================================

    @Test void copyFromStdinCsv_basic() throws SQLException {
        exec("CREATE TABLE csv_tbl (name text, score int)");
        exec("""
             COPY csv_tbl FROM stdin (FORMAT CSV);
             alice,95
             bob,87
             charlie,72
             \\.""");
        QueryResult r = q("SELECT name, score FROM csv_tbl ORDER BY name");
        assertEquals(3, r.rows().size());
        assertEquals("alice",   r.rows().get(0)[0]);
        assertEquals(95,        ((Number) r.rows().get(0)[1]).intValue());
    }

    @Test void copyFromStdinCsv_quotedFields() throws SQLException {
        exec("CREATE TABLE quoted (a text, b text)");
        exec("COPY quoted FROM stdin (FORMAT CSV);\n\"hello, world\",plain\n\\.");
        QueryResult r = q("SELECT a, b FROM quoted");
        assertEquals("hello, world", r.rows().get(0)[0]);
        assertEquals("plain",        r.rows().get(0)[1]);
    }

    @Test void copyFromStdinCsv_header() throws SQLException {
        exec("CREATE TABLE hdr (x int, y text)");
        exec("COPY hdr FROM stdin (FORMAT CSV, HEADER);\nx,y\n1,hello\n2,world\n\\.");
        QueryResult r = q("SELECT x FROM hdr ORDER BY x");
        assertEquals(2, r.rows().size());  // header line skipped
        assertEquals(1, ((Number) r.rows().get(0)[0]).intValue());
    }

    @Test void copyFromStdinCsv_customDelimiter() throws SQLException {
        exec("CREATE TABLE pipe_delim (a text, b int)");
        exec("COPY pipe_delim FROM stdin (FORMAT CSV, DELIMITER '|');\nalpha|1\nbeta|2\n\\.");
        QueryResult r = q("SELECT a, b FROM pipe_delim ORDER BY b");
        assertEquals("alpha", r.rows().get(0)[0]);
        assertEquals("beta",  r.rows().get(1)[0]);
    }

    // =========================================================================
    // COPY TO STDOUT — TEXT format
    // =========================================================================

    @Test void copyToStdoutText_basic() throws SQLException {
        exec("CREATE TABLE export_t (name text, age int)");
        exec("INSERT INTO export_t VALUES ('alice', 30), ('bob', 25)");
        QueryResult r = q("COPY export_t TO STDOUT");
        assertTrue(r.isQuery());
        assertEquals(1, r.columns().size());
        assertEquals("data", r.columns().get(0).name());
        assertEquals(2, r.rows().size());
        // Each row is a tab-delimited line
        List<String> lines = r.rows().stream()
                .map(row -> row[0].toString())
                .sorted().toList();
        assertTrue(lines.get(0).contains("\t"), "TEXT format uses tab delimiter");
    }

    @Test void copyToStdoutText_nullOutput() throws SQLException {
        exec("CREATE TABLE null_out (id int, val text)");
        exec("INSERT INTO null_out VALUES (1, NULL)");
        QueryResult r = q("COPY null_out TO STDOUT");
        assertEquals(1, r.rows().size());
        String line = r.rows().get(0)[0].toString();
        assertTrue(line.contains("\\N"), "NULL should be output as \\N in TEXT format");
    }

    @Test void copyToStdoutCsv() throws SQLException {
        exec("CREATE TABLE csv_out (name text, n int)");
        exec("INSERT INTO csv_out VALUES ('alice', 1), ('bob', 2)");
        QueryResult r = q("COPY csv_out TO STDOUT (FORMAT CSV)");
        assertTrue(r.isQuery());
        assertEquals(2, r.rows().size());
        // CSV: comma-delimited, no tab
        for (Object[] row : r.rows()) {
            String line = row[0].toString();
            assertTrue(line.contains(","), "CSV format uses comma delimiter");
        }
    }

    @Test void copyToStdoutQuery() throws SQLException {
        exec("CREATE TABLE qsrc (x int, y text)");
        exec("INSERT INTO qsrc VALUES (3, 'three'), (1, 'one'), (2, 'two')");
        QueryResult r = q("COPY (SELECT x, y FROM qsrc ORDER BY x) TO STDOUT");
        assertTrue(r.isQuery());
        assertEquals(3, r.rows().size());
        // First row should be x=1
        assertTrue(r.rows().get(0)[0].toString().startsWith("1\t"));
    }

    @Test void copyToStdoutColumnList() throws SQLException {
        exec("CREATE TABLE col_out (a int, b text, c boolean)");
        exec("INSERT INTO col_out VALUES (1, 'hello', true)");
        QueryResult r = q("COPY col_out (a, b) TO STDOUT");
        assertEquals(1, r.rows().size());
        String line = r.rows().get(0)[0].toString();
        String[] parts = line.split("\t");
        assertEquals(2, parts.length);   // only 2 columns exported
        assertEquals("1",     parts[0]);
        assertEquals("hello", parts[1]);
    }

    // =========================================================================
    // splitCopyInline helper
    // =========================================================================

    @Test void splitCopyInline_detectsBackslashDot() {
        String sql = "COPY foo FROM stdin;\n1\t2\n3\t4\n\\.";
        String[] parts = Session.splitCopyInline(sql);
        assertNotNull(parts);
        assertEquals("COPY foo FROM stdin;", parts[0]);
        assertEquals("1\t2\n3\t4\n", parts[1]);
    }

    @Test void splitCopyInline_noInlineData() {
        assertNull(Session.splitCopyInline("COPY foo FROM stdin"));
        assertNull(Session.splitCopyInline("SELECT 1"));
    }

    @Test void splitCopyInline_windowsLineEndings() {
        String sql = "COPY foo FROM stdin;\r\nhello\r\n\\.";
        String[] parts = Session.splitCopyInline(sql);
        assertNotNull(parts);
    }

    // =========================================================================
    // Integration: COPY round-trip
    // =========================================================================

    @Test void roundTrip_copyFromThenTo() throws SQLException {
        exec("CREATE TABLE roundtrip (id int, name text, score double precision)");
        exec("COPY roundtrip FROM stdin;\n1\talice\t95.5\n2\tbob\t87.3\n\\.");
        QueryResult out = q("COPY (SELECT id, name, score FROM roundtrip ORDER BY id) TO STDOUT");
        // fallback: just verify we get 2 rows and they contain expected values
        List<String> lines = out.rows().stream().map(r -> r[0].toString()).toList();
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("alice"));
        assertTrue(lines.get(1).contains("bob"));
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private QueryResult exec_and_return(String sql) throws SQLException {
        return sess.execute(sql);
    }
}
