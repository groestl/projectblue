package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;

/**
 * Golden tests for JSON/JSONB operations comparing pgjava against real PostgreSQL.
 *
 * <p>Covers: field extraction (-> ->>), path extraction (#> #>>), containment (@>),
 * existence (?), JSON construction (json_build_object, json_build_array, row_to_json),
 * modification (jsonb_set, jsonb_insert, jsonb_strip_nulls), aggregation (json_agg,
 * jsonb_agg), expansion (json_each, jsonb_each, json_object_keys, json_array_elements),
 * casting, and JSON in table columns.
 *
 * <p>Note: GoldenCrudTest already covers basic operators; this file adds breadth.
 */
@ExtendWith(GoldenExtension.class)
class GoldenJsonTest {

    // ── Field extraction ──────────────────────────────────────────────────────

    @Test void fieldExtractArrow(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '{\"a\": 1, \"b\": 2}'::jsonb -> 'a'");
    }

    @Test void fieldExtractTextArrow(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '{\"name\": \"Alice\"}'::jsonb ->> 'name'");
    }

    @Test void nestedFieldExtract(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '{\"a\": {\"b\": {\"c\": 42}}}'::jsonb -> 'a' -> 'b' -> 'c'");
    }

    @Test void arrayIndexExtract(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '[1, 2, 3, 4]'::jsonb -> 2");
    }

    @Test void pathExtract(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '{\"a\": {\"b\": 99}}'::jsonb #> '{a,b}'");
    }

    @Test void pathExtractText(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '{\"a\": {\"b\": \"hello\"}}'::jsonb #>> '{a,b}'");
    }

    @Test void missingKeyReturnsNull(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '{\"a\": 1}'::jsonb -> 'z'");
    }

    // ── Containment ───────────────────────────────────────────────────────────

    @Test void jsonbContains(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT '{\"a\":1,\"b\":2}'::jsonb @> '{\"a\":1}'::jsonb,
                       '{\"a\":1}'::jsonb @> '{\"a\":1,\"b\":2}'::jsonb
                """);
    }

    @Test void jsonbContainedBy(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '{\"a\":1}'::jsonb <@ '{\"a\":1,\"b\":2}'::jsonb");
    }

    @Test void jsonbArrayContains(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT '[1,2,3]'::jsonb @> '[2,3]'::jsonb,
                       '[1,2,3]'::jsonb @> '[4]'::jsonb
                """);
    }

    // ── Existence ─────────────────────────────────────────────────────────────

    @Test void jsonbKeyExists(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT '{\"a\":1,\"b\":2}'::jsonb ? 'a',
                       '{\"a\":1}'::jsonb ? 'z'
                """);
    }

    @Test void jsonbAnyKeyExists(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '{\"a\":1,\"b\":2}'::jsonb ?| ARRAY['b','c']");
    }

    @Test void jsonbAllKeysExist(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT '{\"a\":1,\"b\":2}'::jsonb ?& ARRAY['a','b'],
                       '{\"a\":1}'::jsonb ?& ARRAY['a','b']
                """);
    }

    // ── Concatenation / merge ─────────────────────────────────────────────────

    @Test void jsonbConcat(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '{\"a\":1}'::jsonb || '{\"b\":2}'::jsonb");
    }

    @Test void jsonbConcatOverwrites(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '{\"a\":1,\"b\":2}'::jsonb || '{\"b\":99}'::jsonb");
    }

    // ── Key deletion ──────────────────────────────────────────────────────────

    @Test void jsonbDeleteKey(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '{\"a\":1,\"b\":2,\"c\":3}'::jsonb - 'b'");
    }

    @Test void jsonbDeleteIndex(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '[1,2,3,4]'::jsonb - 2");
    }

    @Test void jsonbDeletePath(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '{\"a\":{\"b\":1,\"c\":2}}'::jsonb #- '{a,b}'");
    }

    // ── jsonb_set ─────────────────────────────────────────────────────────────

    @Test void jsonbSet(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT jsonb_set('{\"a\":1,\"b\":2}'::jsonb, '{b}', '99'::jsonb)
                """);
    }

    @Test void jsonbSetNested(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT jsonb_set('{\"a\":{\"b\":1}}'::jsonb, '{a,b}', '\"updated\"'::jsonb)
                """);
    }

    @Test void jsonbStripNulls(DualExecutor db) throws Exception {
        db.assertQuery("SELECT jsonb_strip_nulls('{\"a\":1,\"b\":null,\"c\":3}'::jsonb)");
    }

    // ── Construction ──────────────────────────────────────────────────────────

    @Test void jsonBuildObject(DualExecutor db) throws Exception {
        db.assertQuery("SELECT json_build_object('name', 'Alice', 'age', 30)");
    }

    @Test void jsonBuildArray(DualExecutor db) throws Exception {
        db.assertQuery("SELECT json_build_array(1, 'two', true, null)");
    }

    @Test void jsonbBuildObject(DualExecutor db) throws Exception {
        db.assertQuery("SELECT jsonb_build_object('x', 1, 'y', 2)");
    }

    @Test void toJson(DualExecutor db) throws Exception {
        db.assertQuery("SELECT to_json(42), to_json('hello'::text), to_json(true)");
    }

    @Test void toJsonb(DualExecutor db) throws Exception {
        db.assertQuery("SELECT to_jsonb(42), to_jsonb('hello'::text)");
    }

    @Test void rowToJson(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT, name TEXT)");
        db.execute("INSERT INTO t VALUES (1, 'Alice')");
        db.assertQuery("SELECT row_to_json(t) FROM t");
    }

    @Test void jsonObject(DualExecutor db) throws Exception {
        db.assertQuery("SELECT json_object('{a,b,c}', '{1,2,3}')");
    }

    // ── Type info ─────────────────────────────────────────────────────────────

    @Test void jsonbTypeof(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT jsonb_typeof('42'::jsonb),
                       jsonb_typeof('\"hello\"'::jsonb),
                       jsonb_typeof('true'::jsonb),
                       jsonb_typeof('null'::jsonb),
                       jsonb_typeof('[1,2]'::jsonb),
                       jsonb_typeof('{\"a\":1}'::jsonb)
                """);
    }

    @Test void jsonbArrayLength(DualExecutor db) throws Exception {
        db.assertQuery("SELECT jsonb_array_length('[1,2,3,4,5]'::jsonb)");
    }

    @Test void jsonbObjectKeys(DualExecutor db) throws Exception {
        db.assertQuery("SELECT jsonb_object_keys('{\"c\":3,\"a\":1,\"b\":2}'::jsonb) ORDER BY 1");
    }

    // ── Expansion SRFs ────────────────────────────────────────────────────────

    @Test void jsonEach(DualExecutor db) throws Exception {
        db.assertQuery("SELECT key, value FROM json_each('{\"a\":1,\"b\":2}'::json) ORDER BY key");
    }

    @Test void jsonbEach(DualExecutor db) throws Exception {
        db.assertQuery("SELECT key, value FROM jsonb_each('{\"x\":10,\"y\":20}'::jsonb) ORDER BY key");
    }

    @Test void jsonEachText(DualExecutor db) throws Exception {
        db.assertQuery("SELECT key, value FROM json_each_text('{\"name\":\"Bob\",\"age\":\"25\"}'::json) ORDER BY key");
    }

    @Test void jsonArrayElements(DualExecutor db) throws Exception {
        db.assertQuery("SELECT value FROM json_array_elements('[1,2,3,4]'::json) ORDER BY value::text");
    }

    @Test void jsonbArrayElements(DualExecutor db) throws Exception {
        db.assertQuery("SELECT value FROM jsonb_array_elements('[10,20,30]'::jsonb) ORDER BY value::text");
    }

    @Test void jsonArrayElementsText(DualExecutor db) throws Exception {
        db.assertQuery("SELECT value FROM json_array_elements_text('[\"a\",\"b\",\"c\"]'::json) ORDER BY value");
    }

    // ── Aggregation ───────────────────────────────────────────────────────────

    @Test void jsonAgg(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (n INT)");
        db.execute("INSERT INTO t VALUES (1), (2), (3)");
        db.assertQuery("SELECT json_agg(n ORDER BY n) FROM t");
    }

    @Test void jsonbAgg(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (n INT)");
        db.execute("INSERT INTO t VALUES (3), (1), (2)");
        db.assertQuery("SELECT jsonb_agg(n ORDER BY n) FROM t");
    }

    @Test void jsonObjectAgg(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE kv (k TEXT, v INT)");
        db.execute("INSERT INTO kv VALUES ('a', 1), ('b', 2), ('c', 3)");
        db.assertQuery("SELECT json_object_agg(k, v ORDER BY k) FROM kv");
    }

    // ── JSONB in table ────────────────────────────────────────────────────────

    @Test void jsonbColumnInsertAndQuery(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE products (id INT PRIMARY KEY, attrs JSONB)");
        db.execute("INSERT INTO products VALUES (1, '{\"color\":\"red\",\"size\":\"L\"}')");
        db.execute("INSERT INTO products VALUES (2, '{\"color\":\"blue\",\"size\":\"M\"}')");
        db.assertQuery("SELECT id, attrs->>'color' AS color FROM products ORDER BY id");
    }

    @Test void jsonbContainmentInWhereClause(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE products (id INT PRIMARY KEY, attrs JSONB)");
        db.execute("INSERT INTO products VALUES (1, '{\"tags\":[\"sale\",\"new\"]}')");
        db.execute("INSERT INTO products VALUES (2, '{\"tags\":[\"new\"]}')");
        db.execute("INSERT INTO products VALUES (3, '{\"tags\":[\"sale\"]}')");
        db.assertQuery("SELECT id FROM products WHERE attrs @> '{\"tags\":[\"sale\"]}' ORDER BY id");
    }

    @Test void jsonbUpdateColumn(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, data JSONB)");
        db.execute("INSERT INTO t VALUES (1, '{\"count\":0}')");
        db.execute("UPDATE t SET data = jsonb_set(data, '{count}', '42') WHERE id = 1");
        db.assertQuery("SELECT data->>'count' FROM t WHERE id = 1");
    }
}
