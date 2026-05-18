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
 * JSON/JSONB operators and functions.
 */
class JsonTest {

    private Database db;
    private Session  sess;

    @BeforeEach
    void setUp() {
        db   = DatabaseRegistry.getOrCreate("json_test_" + System.nanoTime());
        sess = db.openSession();
    }

    @AfterEach
    void tearDown() { if (sess != null) sess.close(); }

    private Object scalar(String sql) throws SQLException {
        QueryResult r = sess.execute(sql);
        assertNotNull(r.rows());
        assertFalse(r.rows().isEmpty(), "expected one row for: " + sql);
        return r.rows().get(0)[0];
    }

    // =========================================================================
    // -> (extract as JSON)
    // =========================================================================

    @Test void extractObjectFieldAsJson() throws SQLException {
        assertEquals("1", scalar("""
                SELECT '{"a":"x","b":1}'::jsonb -> 'b'
                """));
    }

    @Test void extractObjectFieldString() throws SQLException {
        assertEquals("\"x\"", scalar("""
                SELECT '{"a":"x"}'::jsonb -> 'a'
                """));
    }

    @Test void extractArrayElement() throws SQLException {
        assertEquals("2", scalar("SELECT '[1,2,3]'::jsonb -> 1"));
    }

    @Test void extractArrayNegativeIndex() throws SQLException {
        assertEquals("3", scalar("SELECT '[1,2,3]'::jsonb -> -1"));
    }

    @Test void extractMissingKey() throws SQLException {
        assertNull(scalar("SELECT '{\"a\":1}'::jsonb -> 'missing'"));
    }

    @Test void extractOutOfBounds() throws SQLException {
        assertNull(scalar("SELECT '[1,2]'::jsonb -> 5"));
    }

    // =========================================================================
    // ->> (extract as text)
    // =========================================================================

    @Test void extractTextFromField() throws SQLException {
        assertEquals("hello", scalar("""
                SELECT '{"name":"hello"}'::jsonb ->> 'name'
                """));
    }

    @Test void extractTextFromNumber() throws SQLException {
        assertEquals("42", scalar("SELECT '{\"n\":42}'::jsonb ->> 'n'"));
    }

    @Test void extractTextFromArray() throws SQLException {
        assertEquals("b", scalar("SELECT '[\"a\",\"b\",\"c\"]'::jsonb ->> 1"));
    }

    @Test void extractTextNull() throws SQLException {
        assertNull(scalar("SELECT '{\"a\":null}'::jsonb ->> 'a'"));
    }

    // =========================================================================
    // #> and #>> (path extraction)
    // =========================================================================

    @Test void extractPathJson() throws SQLException {
        assertEquals("\"bar\"", scalar("""
                SELECT '{"a":{"b":"bar"}}'::jsonb #> ARRAY['a','b']
                """));
    }

    @Test void extractPathText() throws SQLException {
        assertEquals("bar", scalar("""
                SELECT '{"a":{"b":"bar"}}'::jsonb #>> ARRAY['a','b']
                """));
    }

    @Test void extractPathArray() throws SQLException {
        assertEquals("2", scalar("""
                SELECT '{"a":[1,2,3]}'::jsonb #> ARRAY['a','1']
                """));
    }

    @Test void extractPathMissing() throws SQLException {
        assertNull(scalar("""
                SELECT '{"a":1}'::jsonb #> ARRAY['x','y']
                """));
    }

    // =========================================================================
    // @> and <@ (containment)
    // =========================================================================

    @Test void containsObject() throws SQLException {
        assertEquals(true, scalar("""
                SELECT '{"a":1,"b":2}'::jsonb @> '{"a":1}'::jsonb
                """));
    }

    @Test void containsObjectFalse() throws SQLException {
        assertEquals(false, scalar("""
                SELECT '{"a":1}'::jsonb @> '{"a":2}'::jsonb
                """));
    }

    @Test void containsArray() throws SQLException {
        assertEquals(true, scalar("""
                SELECT '[1,2,3]'::jsonb @> '[1,3]'::jsonb
                """));
    }

    @Test void containedBy() throws SQLException {
        assertEquals(true, scalar("""
                SELECT '{"a":1}'::jsonb <@ '{"a":1,"b":2}'::jsonb
                """));
    }

    @Test void containsNested() throws SQLException {
        assertEquals(true, scalar("""
                SELECT '{"a":{"b":1},"c":2}'::jsonb @> '{"a":{"b":1}}'::jsonb
                """));
    }

    // =========================================================================
    // ? ?| ?& (key existence)
    // =========================================================================

    @Test void keyExists() throws SQLException {
        assertEquals(true, scalar("""
                SELECT '{"a":1,"b":2}'::jsonb ? 'a'
                """));
    }

    @Test void keyNotExists() throws SQLException {
        assertEquals(false, scalar("""
                SELECT '{"a":1}'::jsonb ? 'x'
                """));
    }

    @Test void anyKeyExists() throws SQLException {
        assertEquals(true, scalar("""
                SELECT '{"a":1,"b":2}'::jsonb ?| ARRAY['x','b']
                """));
    }

    @Test void allKeysExist() throws SQLException {
        assertEquals(true, scalar("""
                SELECT '{"a":1,"b":2,"c":3}'::jsonb ?& ARRAY['a','b']
                """));
    }

    @Test void allKeysExistFalse() throws SQLException {
        assertEquals(false, scalar("""
                SELECT '{"a":1}'::jsonb ?& ARRAY['a','b']
                """));
    }

    // =========================================================================
    // || (concatenation)
    // =========================================================================

    @Test void concatObjects() throws SQLException {
        String result = (String) scalar("""
                SELECT '{"a":1}'::jsonb || '{"b":2}'::jsonb
                """);
        // Should contain both keys
        assertTrue(result.contains("\"a\""));
        assertTrue(result.contains("\"b\""));
    }

    @Test void concatArrays() throws SQLException {
        assertEquals("[1,2,3,4]", scalar("""
                SELECT '[1,2]'::jsonb || '[3,4]'::jsonb
                """));
    }

    // =========================================================================
    // Functions
    // =========================================================================

    @Test void jsonTypeof() throws SQLException {
        assertEquals("object", scalar("SELECT jsonb_typeof('{\"a\":1}'::jsonb)"));
        assertEquals("array", scalar("SELECT jsonb_typeof('[1,2]'::jsonb)"));
        assertEquals("string", scalar("SELECT jsonb_typeof('\"hello\"'::jsonb)"));
        assertEquals("number", scalar("SELECT jsonb_typeof('42'::jsonb)"));
        assertEquals("boolean", scalar("SELECT jsonb_typeof('true'::jsonb)"));
        assertEquals("null", scalar("SELECT jsonb_typeof('null'::jsonb)"));
    }

    @Test void jsonArrayLength() throws SQLException {
        assertEquals(3, scalar("SELECT jsonb_array_length('[1,2,3]'::jsonb)"));
    }

    @Test void toJson() throws SQLException {
        assertEquals("\"hello\"", scalar("SELECT to_json('hello'::text)"));
    }

    @Test void toJsonb() throws SQLException {
        assertEquals("42", scalar("SELECT to_jsonb(42)"));
    }

    @Test void jsonBuildObject() throws SQLException {
        String result = (String) scalar("SELECT json_build_object('a', 1, 'b', 'hello')");
        assertTrue(result.contains("\"a\":1") || result.contains("\"a\": 1"));
        assertTrue(result.contains("\"b\":\"hello\"") || result.contains("\"b\": \"hello\""));
    }

    @Test void jsonBuildArray() throws SQLException {
        assertEquals("[1,2,3]", scalar("SELECT json_build_array(1, 2, 3)"));
    }

    @Test void jsonbSet() throws SQLException {
        assertEquals("{\"a\":99}", scalar("""
                SELECT jsonb_set('{"a":1}'::jsonb, ARRAY['a'], '99'::jsonb)
                """));
    }

    @Test void jsonbStripNulls() throws SQLException {
        assertEquals("{\"a\":1}", scalar("""
                SELECT jsonb_strip_nulls('{"a":1,"b":null}'::jsonb)
                """));
    }

    @Test void jsonbPretty() throws SQLException {
        String result = (String) scalar("SELECT jsonb_pretty('{\"a\":1}'::jsonb)");
        assertNotNull(result);
        assertTrue(result.contains("\n"), "pretty-printed JSON should have newlines");
    }

    @Test void jsonExtractPath() throws SQLException {
        assertEquals("bar", scalar("""
                SELECT jsonb_extract_path_text('{"a":{"b":"bar"}}'::jsonb, 'a', 'b')
                """));
    }

    // =========================================================================
    // Table with JSONB column
    // =========================================================================

    @Test void jsonbColumnCrud() throws SQLException {
        sess.execute("CREATE TABLE docs (id serial PRIMARY KEY, data jsonb)");
        sess.execute("""
                INSERT INTO docs (data) VALUES
                ('{"name":"Alice","age":30}'),
                ('{"name":"Bob","age":25}'),
                ('{"name":"Carol","age":35}')
                """);

        // Extract field
        QueryResult r = sess.execute("""
                SELECT data ->> 'name' AS name FROM docs ORDER BY data ->> 'name'
                """);
        assertEquals(3, r.rows().size());
        assertEquals("Alice", r.rows().get(0)[0]);
        assertEquals("Bob", r.rows().get(1)[0]);
        assertEquals("Carol", r.rows().get(2)[0]);

        // Filter by containment
        r = sess.execute("""
                SELECT data ->> 'name' FROM docs WHERE data @> '{"age":25}'::jsonb
                """);
        assertEquals(1, r.rows().size());
        assertEquals("Bob", r.rows().get(0)[0]);

        // Key existence
        r = sess.execute("""
                SELECT COUNT(*) FROM docs WHERE data ? 'name'
                """);
        assertEquals(3L, ((Number) r.rows().get(0)[0]).longValue());
    }

    @Test void jsonbUpdate() throws SQLException {
        sess.execute("CREATE TABLE config (id int PRIMARY KEY, settings jsonb)");
        sess.execute("INSERT INTO config VALUES (1, '{\"theme\":\"dark\",\"lang\":\"en\"}')");

        sess.execute("""
                UPDATE config SET settings = jsonb_set(settings, ARRAY['theme'], '"light"'::jsonb)
                WHERE id = 1
                """);

        assertEquals("light", scalar("SELECT settings ->> 'theme' FROM config WHERE id = 1"));
    }
}
