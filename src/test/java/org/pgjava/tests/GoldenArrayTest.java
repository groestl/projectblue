package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;

/**
 * Golden tests for PostgreSQL array operations comparing pgjava against real PostgreSQL.
 *
 * <p>Covers: construction, indexing, slicing, operators (||, @>, <@, &&, =),
 * array functions (array_length, array_append, array_prepend, array_cat,
 * array_remove, array_replace, unnest, array_agg), array in table columns,
 * and multi-dimensional arrays.
 */
@ExtendWith(GoldenExtension.class)
class GoldenArrayTest {

    // ── Construction ──────────────────────────────────────────────────────────

    @Test void arrayLiteral(DualExecutor db) throws Exception {
        db.assertQuery("SELECT ARRAY[1, 2, 3, 4, 5]");
    }

    @Test void arrayTextLiteral(DualExecutor db) throws Exception {
        db.assertQuery("SELECT ARRAY['foo', 'bar', 'baz']");
    }

    @Test void arrayCast(DualExecutor db) throws Exception {
        db.assertQuery("SELECT '{1, 2, 3}'::int[]");
    }

    @Test void arrayWithNulls(DualExecutor db) throws Exception {
        db.assertQuery("SELECT ARRAY[1, NULL, 3]");
    }

    @Test void emptyArray(DualExecutor db) throws Exception {
        db.assertQuery("SELECT ARRAY[]::int[]");
    }

    // ── Indexing ──────────────────────────────────────────────────────────────

    @Test void arrayIndex(DualExecutor db) throws Exception {
        db.assertQuery("SELECT (ARRAY[10, 20, 30, 40])[1], (ARRAY[10, 20, 30, 40])[3]");
    }

    @Test void arrayIndexOutOfBounds(DualExecutor db) throws Exception {
        // PG returns NULL for out-of-bounds access
        db.assertQuery("SELECT (ARRAY[1, 2, 3])[0], (ARRAY[1, 2, 3])[99]");
    }

    @Test void arraySlice(DualExecutor db) throws Exception {
        db.assertQuery("SELECT (ARRAY[1, 2, 3, 4, 5])[2:4]");
    }

    // ── Length / bounds ───────────────────────────────────────────────────────

    @Test void arrayLength(DualExecutor db) throws Exception {
        db.assertQuery("SELECT array_length(ARRAY[1, 2, 3, 4], 1)");
    }

    @Test void arrayUpper(DualExecutor db) throws Exception {
        db.assertQuery("SELECT array_upper(ARRAY[10, 20, 30], 1)");
    }

    @Test void arrayLower(DualExecutor db) throws Exception {
        db.assertQuery("SELECT array_lower(ARRAY[10, 20, 30], 1)");
    }

    @Test void cardinalityOfArray(DualExecutor db) throws Exception {
        db.assertQuery("SELECT cardinality(ARRAY[1, 2, 3, NULL, 5])");
    }

    // ── Concatenation ─────────────────────────────────────────────────────────

    @Test void arrayConcatArrays(DualExecutor db) throws Exception {
        db.assertQuery("SELECT ARRAY[1, 2] || ARRAY[3, 4]");
    }

    @Test void arrayConcatElement(DualExecutor db) throws Exception {
        db.assertQuery("SELECT ARRAY[1, 2] || 3");
    }

    @Test void arrayConcatElementPrepend(DualExecutor db) throws Exception {
        db.assertQuery("SELECT 0 || ARRAY[1, 2, 3]");
    }

    // ── Append / prepend / remove ─────────────────────────────────────────────

    @Test void arrayAppend(DualExecutor db) throws Exception {
        db.assertQuery("SELECT array_append(ARRAY[1, 2, 3], 4)");
    }

    @Test void arrayPrepend(DualExecutor db) throws Exception {
        db.assertQuery("SELECT array_prepend(0, ARRAY[1, 2, 3])");
    }

    @Test void arrayCat(DualExecutor db) throws Exception {
        db.assertQuery("SELECT array_cat(ARRAY[1, 2], ARRAY[3, 4, 5])");
    }

    @Test void arrayRemove(DualExecutor db) throws Exception {
        db.assertQuery("SELECT array_remove(ARRAY[1, 2, 3, 2, 1], 2)");
    }

    @Test void arrayReplace(DualExecutor db) throws Exception {
        db.assertQuery("SELECT array_replace(ARRAY[1, 2, 3, 2], 2, 99)");
    }

    // ── Containment operators ─────────────────────────────────────────────────

    @Test void arrayContains(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT ARRAY[1, 2, 3] @> ARRAY[2, 3],
                       ARRAY[1, 2, 3] @> ARRAY[4],
                       ARRAY[1, 2, 3] @> ARRAY[1, 2, 3]
                """);
    }

    @Test void arrayContainedBy(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT ARRAY[2, 3] <@ ARRAY[1, 2, 3],
                       ARRAY[4]    <@ ARRAY[1, 2, 3]
                """);
    }

    @Test void arrayOverlap(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT ARRAY[1, 2, 3] && ARRAY[3, 4, 5],
                       ARRAY[1, 2]    && ARRAY[3, 4]
                """);
    }

    @Test void arrayEquality(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT ARRAY[1, 2, 3] = ARRAY[1, 2, 3],
                       ARRAY[1, 2, 3] = ARRAY[1, 2, 4],
                       ARRAY[1, 2]    = ARRAY[1, 2, 3]
                """);
    }

    // ── ANY / ALL ─────────────────────────────────────────────────────────────

    @Test void anyOperator(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT 3 = ANY(ARRAY[1, 2, 3, 4]),
                       9 = ANY(ARRAY[1, 2, 3, 4])
                """);
    }

    @Test void allOperator(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT 5 > ALL(ARRAY[1, 2, 3, 4]),
                       2 > ALL(ARRAY[1, 2, 3, 4])
                """);
    }

    // ── unnest ────────────────────────────────────────────────────────────────

    @Test void unnestSingleArray(DualExecutor db) throws Exception {
        db.assertQuery("SELECT unnest(ARRAY[10, 20, 30, 40]) ORDER BY 1");
    }

    @Test void unnestWithOrdinality(DualExecutor db) throws Exception {
        db.assertQuery("SELECT elem, ord FROM unnest(ARRAY['a', 'b', 'c']) WITH ORDINALITY AS t(elem, ord) ORDER BY ord");
    }

    @Test void unnestMultipleArrays(DualExecutor db) throws Exception {
        db.assertQuery("SELECT * FROM unnest(ARRAY[1,2,3], ARRAY['a','b','c']) AS t(n, s) ORDER BY n");
    }

    // ── array_agg ─────────────────────────────────────────────────────────────

    @Test void arrayAggBasic(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (n INT)");
        db.execute("INSERT INTO t VALUES (3), (1), (2)");
        db.assertQuery("SELECT array_agg(n ORDER BY n) FROM t");
    }

    @Test void arrayAggGroupBy(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (cat TEXT, val INT)");
        db.execute("INSERT INTO t VALUES ('a', 1), ('b', 2), ('a', 3), ('b', 4)");
        db.assertQuery("SELECT cat, array_agg(val ORDER BY val) FROM t GROUP BY cat ORDER BY cat");
    }

    // ── Table column ──────────────────────────────────────────────────────────

    @Test void arrayColumn(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, tags TEXT[])");
        db.execute("INSERT INTO t VALUES (1, ARRAY['foo', 'bar']), (2, ARRAY['baz'])");
        db.assertQuery("SELECT id, tags FROM t ORDER BY id");
    }

    @Test void arrayColumnContainmentQuery(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, nums INT[])");
        db.execute("INSERT INTO t VALUES (1, '{1,2,3}'), (2, '{4,5,6}'), (3, '{1,4,7}')");
        db.assertQuery("SELECT id FROM t WHERE nums @> ARRAY[1] ORDER BY id");
    }

    @Test void arrayColumnUpdate(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, vals INT[])");
        db.execute("INSERT INTO t VALUES (1, '{1,2,3}')");
        db.execute("UPDATE t SET vals = array_append(vals, 4) WHERE id = 1");
        db.assertQuery("SELECT vals FROM t WHERE id = 1");
    }

    // ── Positional text search ─────────────────────────────────────────────────

    @Test void arrayPosition(DualExecutor db) throws Exception {
        db.assertQuery("""
                SELECT array_position(ARRAY['a', 'b', 'c', 'b'], 'b'),
                       array_position(ARRAY['a', 'b', 'c'], 'z')
                """);
    }

    @Test void arrayPositions(DualExecutor db) throws Exception {
        db.assertQuery("SELECT array_positions(ARRAY['a', 'b', 'a', 'c', 'a'], 'a')");
    }

    // ── Dimensions ────────────────────────────────────────────────────────────

    @Test void arrayNdims(DualExecutor db) throws Exception {
        db.assertQuery("SELECT array_ndims(ARRAY[1,2,3]), array_ndims(ARRAY[[1,2],[3,4]])");
    }

    // ── to_array / string_to_array ────────────────────────────────────────────

    @Test void stringToArray(DualExecutor db) throws Exception {
        db.assertQuery("SELECT string_to_array('a,b,c,d', ',')");
    }

    @Test void stringToArrayWithNull(DualExecutor db) throws Exception {
        db.assertQuery("SELECT string_to_array('a,b,,d', ',', '')");
    }

    @Test void arrayToString(DualExecutor db) throws Exception {
        db.assertQuery("SELECT array_to_string(ARRAY[1, 2, 3], ', ')");
    }

    @Test void arrayToStringWithNull(DualExecutor db) throws Exception {
        db.assertQuery("SELECT array_to_string(ARRAY[1, NULL, 3], ',', 'N')");
    }
}
