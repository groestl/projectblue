package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;

/**
 * Golden window function tests: ROWS frame, RANGE frame, FIRST/LAST/NTH_VALUE,
 * running aggregates, and named windows — validated against real PostgreSQL.
 */
@ExtendWith(GoldenExtension.class)
class GoldenWindowTest {

    // ── ROWS frame ────────────────────────────────────────────────────────────

    @Test void rowsUnboundedPrecedingToCurrentRow(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, val int)");
        db.execute("INSERT INTO t VALUES (1,10),(2,20),(3,30),(4,40),(5,50)");
        db.assertQuery("""
            SELECT id, val,
                   SUM(val) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running_sum
            FROM t ORDER BY id
            """);
    }

    @Test void rowsNPrecedingToCurrentRow(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, val int)");
        db.execute("INSERT INTO t VALUES (1,10),(2,20),(3,30),(4,40),(5,50)");
        db.assertQuery("""
            SELECT id, val,
                   SUM(val) OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) AS rolling_2
            FROM t ORDER BY id
            """);
    }

    @Test void rowsNPrecedingToNFollowing(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, val int)");
        db.execute("INSERT INTO t VALUES (1,10),(2,20),(3,30),(4,40),(5,50)");
        db.assertQuery("""
            SELECT id, val,
                   SUM(val) OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS rolling_3,
                   AVG(val) OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS rolling_avg,
                   COUNT(*) OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS cnt
            FROM t ORDER BY id
            """);
    }

    @Test void rowsCurrentRowToUnboundedFollowing(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, val int)");
        db.execute("INSERT INTO t VALUES (1,10),(2,20),(3,30)");
        db.assertQuery("""
            SELECT id, val,
                   SUM(val) OVER (ORDER BY id ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING) AS suffix_sum
            FROM t ORDER BY id
            """);
    }

    @Test void rowsUnboundedPrecedingToUnboundedFollowing(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, val int)");
        db.execute("INSERT INTO t VALUES (1,10),(2,20),(3,30)");
        db.assertQuery("""
            SELECT id, val,
                   SUM(val) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS total
            FROM t ORDER BY id
            """);
    }

    // ── RANGE frame ───────────────────────────────────────────────────────────

    @Test void rangeDefaultWithOrderBy(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text, val int)");
        db.execute("INSERT INTO t VALUES ('a',10),('a',20),('b',30),('b',30),('c',40)");
        db.assertQuery("""
            SELECT dept, val,
                   SUM(val) OVER (ORDER BY val) AS running_range
            FROM t ORDER BY val, dept
            """);
    }

    // ── FIRST_VALUE / LAST_VALUE ──────────────────────────────────────────────

    @Test void firstValueNoFrame(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, val int)");
        db.execute("INSERT INTO t VALUES (1,10),(2,20),(3,30)");
        db.assertQuery("""
            SELECT id, val,
                   FIRST_VALUE(val) OVER (ORDER BY id) AS fv
            FROM t ORDER BY id
            """);
    }

    @Test void lastValueDefaultFrame(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, val int)");
        db.execute("INSERT INTO t VALUES (1,10),(2,20),(3,30)");
        // With ORDER BY and no explicit frame, LAST_VALUE uses
        // RANGE UNBOUNDED PRECEDING TO CURRENT ROW — returns current row's value
        db.assertQuery("""
            SELECT id, val,
                   LAST_VALUE(val) OVER (ORDER BY id) AS lv
            FROM t ORDER BY id
            """);
    }

    @Test void lastValueUnboundedFrame(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, val int)");
        db.execute("INSERT INTO t VALUES (1,10),(2,20),(3,30)");
        db.assertQuery("""
            SELECT id, val,
                   LAST_VALUE(val) OVER (ORDER BY id
                       ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS lv
            FROM t ORDER BY id
            """);
    }

    @Test void firstValueRowsFrame(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, val int)");
        db.execute("INSERT INTO t VALUES (1,10),(2,20),(3,30),(4,40),(5,50)");
        db.assertQuery("""
            SELECT id, val,
                   FIRST_VALUE(val) OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS fv,
                   LAST_VALUE(val)  OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS lv
            FROM t ORDER BY id
            """);
    }

    // ── NTH_VALUE ─────────────────────────────────────────────────────────────

    @Test void nthValueFullPartition(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, val int)");
        db.execute("INSERT INTO t VALUES (1,10),(2,20),(3,30),(4,40)");
        db.assertQuery("""
            SELECT id, val,
                   NTH_VALUE(val, 2) OVER (ORDER BY id
                       ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS second
            FROM t ORDER BY id
            """);
    }

    // ── Partitioned frames ────────────────────────────────────────────────────

    @Test void rowsFrameWithPartition(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (dept text, id int, val int)");
        db.execute("INSERT INTO t VALUES ('a',1,10),('a',2,20),('a',3,30),('b',1,5),('b',2,15)");
        db.assertQuery("""
            SELECT dept, id, val,
                   SUM(val) OVER (PARTITION BY dept ORDER BY id
                       ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS rs
            FROM t ORDER BY dept, id
            """);
    }

    // ── MIN/MAX with frame ─────────────────────────────────────────────────────

    @Test void minMaxRowsFrame(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE t (id int, val int)");
        db.execute("INSERT INTO t VALUES (1,30),(2,10),(3,50),(4,20),(5,40)");
        db.assertQuery("""
            SELECT id, val,
                   MIN(val) OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS rolling_min,
                   MAX(val) OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS rolling_max
            FROM t ORDER BY id
            """);
    }
}
