package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.QueryResult;
import org.pgjava.engine.Session;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 12: VALUES (...) as standalone query, TABLE tablename shorthand.
 */
class Phase12ValuesTableTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("phase12_vt_" + System.nanoTime());
        sess = db.openSession();
    }

    @AfterEach
    void tearDown() {
        sess.close();
    }

    private QueryResult q(String sql) throws SQLException { return sess.execute(sql); }
    private void exec(String sql)       throws SQLException { sess.execute(sql); }

    // =========================================================================
    // VALUES (...) standalone
    // =========================================================================

    @Test void valuesStandaloneSingleRow() throws SQLException {
        QueryResult r = q("VALUES (1, 'hello', true)");
        assertTrue(r.isQuery());
        assertEquals(1, r.rows().size());
        Object[] row = r.rows().get(0);
        assertEquals(3, row.length);
        assertEquals(1,       ((Number) row[0]).intValue());
        assertEquals("hello", row[1].toString());
    }

    @Test void valuesStandaloneMultiRow() throws SQLException {
        QueryResult r = q("VALUES (1, 'a'), (2, 'b'), (3, 'c')");
        assertTrue(r.isQuery());
        assertEquals(3, r.rows().size());
        assertEquals(1, ((Number) r.rows().get(0)[0]).intValue());
        assertEquals(2, ((Number) r.rows().get(1)[0]).intValue());
        assertEquals(3, ((Number) r.rows().get(2)[0]).intValue());
    }

    @Test void valuesStandaloneColumnNaming() throws SQLException {
        QueryResult r = q("VALUES (10, 20)");
        assertTrue(r.isQuery());
        // PostgreSQL names columns "column1", "column2"
        List<org.pgjava.engine.ColumnMeta> cols = r.columns();
        assertEquals("column1", cols.get(0).name());
        assertEquals("column2", cols.get(1).name());
    }

    @Test void valuesWithOrderBy() throws SQLException {
        QueryResult r = q("VALUES (3), (1), (2) ORDER BY 1");
        assertTrue(r.isQuery());
        assertEquals(3, r.rows().size());
        assertEquals(1, ((Number) r.rows().get(0)[0]).intValue());
        assertEquals(2, ((Number) r.rows().get(1)[0]).intValue());
        assertEquals(3, ((Number) r.rows().get(2)[0]).intValue());
    }

    @Test void valuesWithLimit() throws SQLException {
        QueryResult r = q("VALUES (1), (2), (3), (4) LIMIT 2");
        assertTrue(r.isQuery());
        assertEquals(2, r.rows().size());
    }

    @Test void valuesNullLiteral() throws SQLException {
        QueryResult r = q("VALUES (1, NULL, 'x')");
        assertTrue(r.isQuery());
        assertEquals(1, r.rows().size());
        assertNull(r.rows().get(0)[1]);
    }

    @Test void valuesAsSubquery() throws SQLException {
        // VALUES used inside a FROM clause with alias
        QueryResult r = q("""
                SELECT v.a, v.b
                FROM (VALUES (1, 'one'), (2, 'two')) AS v(a, b)
                ORDER BY v.a
                """);
        assertTrue(r.isQuery());
        assertEquals(2, r.rows().size());
        assertEquals("one", r.rows().get(0)[1].toString());
        assertEquals("two", r.rows().get(1)[1].toString());
    }

    @Test void valuesInsertSelect() throws SQLException {
        // INSERT ... SELECT from VALUES subquery
        exec("CREATE TABLE dest (x int, y text)");
        exec("""
             INSERT INTO dest
             SELECT a, b FROM (VALUES (10, 'ten'), (20, 'twenty')) AS v(a, b)
             """);
        QueryResult r = q("SELECT x, y FROM dest ORDER BY x");
        assertEquals(2, r.rows().size());
        assertEquals("ten",    r.rows().get(0)[1].toString());
        assertEquals("twenty", r.rows().get(1)[1].toString());
    }

    // =========================================================================
    // TABLE tablename
    // =========================================================================

    @Test void tableShorthand() throws SQLException {
        exec("CREATE TABLE tbl (id int, name text)");
        exec("INSERT INTO tbl VALUES (1, 'alice'), (2, 'bob')");
        QueryResult r = q("TABLE tbl");
        assertTrue(r.isQuery());
        assertEquals(2, r.rows().size());
    }

    @Test void tableShorthandColumnCount() throws SQLException {
        exec("CREATE TABLE cols3 (a int, b text, c boolean)");
        exec("INSERT INTO cols3 VALUES (1, 'x', true)");
        QueryResult r = q("TABLE cols3");
        assertTrue(r.isQuery());
        assertEquals(1, r.rows().size());
        assertEquals(3, r.rows().get(0).length);
    }

    @Test void tableShorthandEmpty() throws SQLException {
        exec("CREATE TABLE empty_tbl (x int)");
        QueryResult r = q("TABLE empty_tbl");
        assertTrue(r.isQuery());
        assertEquals(0, r.rows().size());
    }
}
