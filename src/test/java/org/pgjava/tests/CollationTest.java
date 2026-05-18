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
 * Tests for three-level collation support:
 * <ol>
 *   <li>Database-default collation (en_US.UTF-8 / locale-aware)</li>
 *   <li>Per-column COLLATE on CREATE TABLE</li>
 *   <li>Per-expression COLLATE override</li>
 * </ol>
 */
class CollationTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("collation_test_" + System.nanoTime());
        sess = db.openSession();
    }

    @AfterEach
    void tearDown() {
        if (sess != null) sess.close();
    }

    private QueryResult exec(String sql) throws SQLException {
        return sess.execute(sql);
    }

    private Object scalar(String sql) throws SQLException {
        QueryResult r = exec(sql);
        assertNotNull(r.rows());
        assertFalse(r.rows().isEmpty(), "expected one row");
        return r.rows().get(0)[0];
    }

    // =========================================================================
    // 1. Database default collation (locale-aware, en_US.UTF-8)
    // =========================================================================

    @Test
    void defaultCollationLocaleAware() throws SQLException {
        // In en_US locale, 'a' < 'B' (case-insensitive ordering at primary level)
        // PgCollation.DEFAULT uses Collator.TERTIARY which is case-sensitive
        // but locale-aware: lowercase 'a' sorts before uppercase 'B'
        Object result = scalar("SELECT 'a' < 'B'");
        assertEquals(true, result, "default collation: 'a' < 'B' should be true (locale-aware)");
    }

    @Test
    void defaultCollationCaseSensitiveEquality() throws SQLException {
        // Case-sensitive: 'abc' != 'ABC'
        Object result = scalar("SELECT 'abc' = 'ABC'");
        assertEquals(false, result, "default collation: 'abc' = 'ABC' should be false (case-sensitive)");
    }

    @Test
    void defaultCollationOrderBy() throws SQLException {
        exec("CREATE TABLE t_default (name text)");
        exec("INSERT INTO t_default VALUES ('banana'), ('Apple'), ('cherry'), ('avocado')");
        QueryResult r = exec("SELECT name FROM t_default ORDER BY name");
        List<Object[]> rows = r.rows();
        assertEquals(4, rows.size());
        // Locale-aware ordering: Apple, avocado, banana, cherry
        assertEquals("Apple", rows.get(0)[0]);
        assertEquals("avocado", rows.get(1)[0]);
        assertEquals("banana", rows.get(2)[0]);
        assertEquals("cherry", rows.get(3)[0]);
    }

    // =========================================================================
    // 2. Expression-level COLLATE
    // =========================================================================

    @Test
    void collateExpressionC() throws SQLException {
        // C collation: byte order → uppercase letters come before lowercase
        // 'a' (0x61) > 'B' (0x42) in byte order
        Object result = scalar("SELECT 'a' < 'B' COLLATE \"C\"");
        assertEquals(false, result, "C collation: 'a' < 'B' should be false (byte order)");
    }

    @Test
    void collateExpressionCOrderBy() throws SQLException {
        exec("CREATE TABLE t_collate_expr (name text)");
        exec("INSERT INTO t_collate_expr VALUES ('banana'), ('Apple'), ('cherry'), ('avocado')");
        QueryResult r = exec("SELECT name FROM t_collate_expr ORDER BY name COLLATE \"C\"");
        List<Object[]> rows = r.rows();
        assertEquals(4, rows.size());
        // C collation (byte order): uppercase before lowercase
        assertEquals("Apple", rows.get(0)[0]);
        assertEquals("avocado", rows.get(1)[0]);
        assertEquals("banana", rows.get(2)[0]);
        assertEquals("cherry", rows.get(3)[0]);
    }

    @Test
    void collateExpressionPOSIX() throws SQLException {
        // POSIX is identical to C
        Object result = scalar("SELECT 'a' > 'B' COLLATE \"POSIX\"");
        assertEquals(true, result, "POSIX collation: 'a' > 'B' should be true (byte order)");
    }

    @Test
    void collateExpressionComparison() throws SQLException {
        // C collation: 'Z' (0x5A) < 'a' (0x61)
        Object result = scalar("SELECT 'Z' COLLATE \"C\" < 'a' COLLATE \"C\"");
        assertEquals(true, result, "C collation: 'Z' < 'a' should be true");
    }

    // =========================================================================
    // 3. Per-column COLLATE
    // =========================================================================

    @Test
    void columnCollateC() throws SQLException {
        exec("CREATE TABLE t_col_c (name text COLLATE \"C\")");
        exec("INSERT INTO t_col_c VALUES ('banana'), ('Apple'), ('cherry'), ('avocado')");
        QueryResult r = exec("SELECT name FROM t_col_c ORDER BY name");
        List<Object[]> rows = r.rows();
        assertEquals(4, rows.size());
        // C collation: uppercase before lowercase in byte order
        assertEquals("Apple", rows.get(0)[0]);
        assertEquals("avocado", rows.get(1)[0]);
        assertEquals("banana", rows.get(2)[0]);
        assertEquals("cherry", rows.get(3)[0]);
    }

    @Test
    void columnCollateVarchar() throws SQLException {
        exec("CREATE TABLE t_col_vc (name varchar(100) COLLATE \"C\")");
        exec("INSERT INTO t_col_vc VALUES ('b'), ('A'), ('c')");
        QueryResult r = exec("SELECT name FROM t_col_vc ORDER BY name");
        List<Object[]> rows = r.rows();
        assertEquals(3, rows.size());
        assertEquals("A", rows.get(0)[0]);
        assertEquals("b", rows.get(1)[0]);
        assertEquals("c", rows.get(2)[0]);
    }

    // =========================================================================
    // 4. Error cases
    // =========================================================================

    @Test
    void collateOnNonTextTypeError() {
        SQLException ex = assertThrows(SQLException.class, () ->
                exec("CREATE TABLE t_bad (id integer COLLATE \"C\")"));
        assertEquals("42P21", ex.getSQLState());
        assertTrue(ex.getMessage().contains("collations are not supported by type"));
    }

    @Test
    void invalidCollationName() {
        SQLException ex = assertThrows(SQLException.class, () ->
                exec("CREATE TABLE t_bad2 (name text COLLATE \"nonexistent\")"));
        assertEquals("42704", ex.getSQLState());
        assertTrue(ex.getMessage().contains("does not exist"));
    }

    @Test
    void invalidCollationInExpression() {
        SQLException ex = assertThrows(SQLException.class, () ->
                scalar("SELECT 'a' COLLATE \"bogus_collation\""));
        assertEquals("42704", ex.getSQLState());
    }

    // =========================================================================
    // 5. pg_collation catalog
    // =========================================================================

    @Test
    void pgCollationCatalog() throws SQLException {
        QueryResult r = exec("SELECT collname FROM pg_catalog.pg_collation ORDER BY collname");
        List<Object[]> rows = r.rows();
        assertTrue(rows.size() >= 5, "should have at least 5 built-in collations");
        // Check that C and POSIX are present
        List<String> names = rows.stream().map(row -> (String) row[0]).toList();
        assertTrue(names.contains("C"), "should contain C collation");
        assertTrue(names.contains("POSIX"), "should contain POSIX collation");
        assertTrue(names.contains("default"), "should contain default collation");
    }

    // =========================================================================
    // 6. PgCollation instance behavior
    // =========================================================================

    @Test
    void collationResolveKnownNames() {
        assertNotNull(org.pgjava.types.PgCollation.resolve("C"));
        assertNotNull(org.pgjava.types.PgCollation.resolve("POSIX"));
        assertNotNull(org.pgjava.types.PgCollation.resolve("default"));
        assertNotNull(org.pgjava.types.PgCollation.resolve("en_US"));
        assertNotNull(org.pgjava.types.PgCollation.resolve("ucs_basic"));
        assertNull(org.pgjava.types.PgCollation.resolve("nonexistent"));
    }

    @Test
    void cCollationByteOrder() {
        var c = org.pgjava.types.PgCollation.C;
        // In C/byte order: 'A' (65) < 'a' (97)
        assertTrue(c.compare("A", "a") < 0);
        assertTrue(c.compare("Z", "a") < 0);
        assertTrue(c.compare("a", "b") < 0);
    }

    @Test
    void defaultCollationLocaleOrder() {
        var def = org.pgjava.types.PgCollation.DEFAULT;
        // In locale order: 'a' < 'B' (lowercase a sorts before uppercase B)
        assertTrue(def.compare("a", "B") < 0);
        // Case-sensitive: 'a' != 'A'
        assertNotEquals(0, def.compare("a", "A"));
    }
}
