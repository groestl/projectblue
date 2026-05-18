package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.QueryResult;
import org.pgjava.engine.Session;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 12: SELECT … FOR UPDATE / FOR SHARE / SKIP LOCKED / NOWAIT.
 *
 * <p>Tests the row-level lock table, lock acquisition, SKIP LOCKED filtering,
 * NOWAIT error semantics, and lock release on COMMIT/ROLLBACK.
 */
class Phase12ForUpdateTest {

    private Database db;

    @BeforeEach
    void setUp() {
        db = DatabaseRegistry.getOrCreate("phase12_fu_" + System.nanoTime());
    }

    private Session session() { return db.openSession(); }

    private void setup(Session s) throws SQLException {
        s.execute("CREATE TABLE IF NOT EXISTS jobs (id int PRIMARY KEY, status text)");
        s.execute("INSERT INTO jobs VALUES (1, 'pending'), (2, 'pending'), (3, 'pending')");
        s.execute("COMMIT");
    }

    // =========================================================================
    // Basic FOR UPDATE
    // =========================================================================

    @Test void forUpdateBasic() throws SQLException {
        try (Session s = session()) {
            s.execute("CREATE TABLE items (id int PRIMARY KEY, val text)");
            s.execute("INSERT INTO items VALUES (1, 'a'), (2, 'b')");

            s.execute("BEGIN");
            QueryResult r = s.execute("SELECT id, val FROM items WHERE id = 1 FOR UPDATE");
            assertTrue(r.isQuery());
            assertEquals(1, r.rows().size());
            assertEquals(1, ((Number) r.rows().get(0)[0]).intValue());
            s.execute("COMMIT");
        }
    }

    @Test void forUpdateReturnsRows() throws SQLException {
        try (Session s = session()) {
            s.execute("CREATE TABLE data (id int, x text)");
            s.execute("INSERT INTO data VALUES (1,'a'),(2,'b'),(3,'c')");

            s.execute("BEGIN");
            QueryResult r = s.execute("SELECT id FROM data ORDER BY id FOR UPDATE");
            assertEquals(3, r.rows().size());
            s.execute("COMMIT");
        }
    }

    @Test void forShareBasic() throws SQLException {
        try (Session s = session()) {
            s.execute("CREATE TABLE shared (id int)");
            s.execute("INSERT INTO shared VALUES (1)");

            s.execute("BEGIN");
            QueryResult r = s.execute("SELECT id FROM shared FOR SHARE");
            assertEquals(1, r.rows().size());
            s.execute("COMMIT");
        }
    }

    // =========================================================================
    // SKIP LOCKED — core queue worker pattern
    // =========================================================================

    @Test void skipLockedFiltersLockedRows() throws SQLException {
        try (Session s1 = session(); Session s2 = session()) {
            // Session 1: lock rows 1 and 2
            s1.execute("CREATE TABLE queue (id int)");
            s1.execute("INSERT INTO queue VALUES (1),(2),(3)");
            s1.execute("BEGIN");
            s1.execute("SELECT id FROM queue WHERE id IN (1,2) FOR UPDATE");

            // Session 2: SKIP LOCKED — should only see row 3
            s2.execute("BEGIN");
            QueryResult r = s2.execute("SELECT id FROM queue ORDER BY id FOR UPDATE SKIP LOCKED");
            assertEquals(1, r.rows().size(), "Only unlocked row (3) should be returned");
            assertEquals(3, ((Number) r.rows().get(0)[0]).intValue());
            s2.execute("COMMIT");

            s1.execute("ROLLBACK");
        }
    }

    @Test void skipLockedAllRowsLocked() throws SQLException {
        try (Session s1 = session(); Session s2 = session()) {
            s1.execute("CREATE TABLE alllock (id int)");
            s1.execute("INSERT INTO alllock VALUES (1),(2)");

            s1.execute("BEGIN");
            s1.execute("SELECT id FROM alllock FOR UPDATE");

            // All rows locked — SKIP LOCKED returns empty
            s2.execute("BEGIN");
            QueryResult r = s2.execute("SELECT id FROM alllock FOR UPDATE SKIP LOCKED");
            assertEquals(0, r.rows().size(), "No rows available when all are locked");
            s2.execute("COMMIT");

            s1.execute("ROLLBACK");
        }
    }

    @Test void skipLockedWithLimit() throws SQLException {
        try (Session s1 = session(); Session s2 = session()) {
            s1.execute("CREATE TABLE limited (id int)");
            s1.execute("INSERT INTO limited VALUES (1),(2),(3),(4),(5)");

            // Lock rows 1 and 2 in s1
            s1.execute("BEGIN");
            s1.execute("SELECT id FROM limited WHERE id <= 2 FOR UPDATE");

            // s2: claim at most 2 rows, skipping locked ones → should get 3,4
            s2.execute("BEGIN");
            QueryResult r = s2.execute(
                    "SELECT id FROM limited ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 2");
            assertEquals(2, r.rows().size());
            assertEquals(3, ((Number) r.rows().get(0)[0]).intValue());
            assertEquals(4, ((Number) r.rows().get(1)[0]).intValue());
            s2.execute("COMMIT");

            s1.execute("ROLLBACK");
        }
    }

    // =========================================================================
    // NOWAIT
    // =========================================================================

    @Test void nowaitThrows55P03() throws SQLException {
        try (Session s1 = session(); Session s2 = session()) {
            s1.execute("CREATE TABLE nowt (id int)");
            s1.execute("INSERT INTO nowt VALUES (1)");

            s1.execute("BEGIN");
            s1.execute("SELECT id FROM nowt FOR UPDATE");

            s2.execute("BEGIN");
            SQLException ex = assertThrows(SQLException.class,
                    () -> s2.execute("SELECT id FROM nowt FOR UPDATE NOWAIT"));
            assertEquals("55P03", ex.getSQLState(),
                    "NOWAIT on locked row must throw SQLSTATE 55P03");
            s2.execute("ROLLBACK");

            s1.execute("ROLLBACK");
        }
    }

    // =========================================================================
    // Lock release on COMMIT / ROLLBACK
    // =========================================================================

    @Test void locksReleasedOnCommit() throws SQLException {
        try (Session s1 = session(); Session s2 = session()) {
            s1.execute("CREATE TABLE rel (id int)");
            s1.execute("INSERT INTO rel VALUES (1)");

            // s1 locks row
            s1.execute("BEGIN");
            s1.execute("SELECT id FROM rel FOR UPDATE");

            // s1 commits → lock released
            s1.execute("COMMIT");

            // s2 can now lock the same row
            s2.execute("BEGIN");
            QueryResult r = s2.execute("SELECT id FROM rel FOR UPDATE NOWAIT");
            assertEquals(1, r.rows().size());
            s2.execute("COMMIT");
        }
    }

    @Test void locksReleasedOnRollback() throws SQLException {
        try (Session s1 = session(); Session s2 = session()) {
            s1.execute("CREATE TABLE rellb (id int)");
            s1.execute("INSERT INTO rellb VALUES (1)");

            s1.execute("BEGIN");
            s1.execute("SELECT id FROM rellb FOR UPDATE");

            s1.execute("ROLLBACK");

            // s2 can now lock
            s2.execute("BEGIN");
            QueryResult r = s2.execute("SELECT id FROM rellb FOR UPDATE NOWAIT");
            assertEquals(1, r.rows().size());
            s2.execute("COMMIT");
        }
    }

    // =========================================================================
    // Same transaction can re-lock its own rows
    // =========================================================================

    @Test void sameTransactionReLock() throws SQLException {
        try (Session s = session()) {
            s.execute("CREATE TABLE relock (id int)");
            s.execute("INSERT INTO relock VALUES (1)");

            s.execute("BEGIN");
            s.execute("SELECT id FROM relock FOR UPDATE");
            // Second lock in same tx — should not fail
            QueryResult r = s.execute("SELECT id FROM relock FOR UPDATE");
            assertEquals(1, r.rows().size());
            s.execute("COMMIT");
        }
    }

    // =========================================================================
    // FOR UPDATE + WHERE + LIMIT — selective locking
    // =========================================================================

    @Test void forUpdateWithWhere() throws SQLException {
        try (Session s = session()) {
            s.execute("CREATE TABLE filtered (id int, status text)");
            s.execute("INSERT INTO filtered VALUES (1,'done'),(2,'pending'),(3,'pending')");

            s.execute("BEGIN");
            QueryResult r = s.execute(
                    "SELECT id FROM filtered WHERE status='pending' FOR UPDATE");
            assertEquals(2, r.rows().size());
            s.execute("COMMIT");
        }
    }

    @Test void queueWorkerPattern() throws SQLException {
        // Classic: multiple workers each claim one job with SKIP LOCKED
        try (Session worker1 = session(); Session worker2 = session()) {
            worker1.execute("CREATE TABLE tasks (id int, owner text)");
            worker1.execute("""
                    INSERT INTO tasks VALUES (1,null),(2,null),(3,null),(4,null),(5,null)
                    """);

            // Worker 1 claims one job
            worker1.execute("BEGIN");
            QueryResult r1 = worker1.execute(
                    "SELECT id FROM tasks WHERE owner IS NULL ORDER BY id " +
                    "FOR UPDATE SKIP LOCKED LIMIT 1");
            assertEquals(1, r1.rows().size());
            int job1 = ((Number) r1.rows().get(0)[0]).intValue();

            // Worker 2 claims the next available (different) job
            worker2.execute("BEGIN");
            QueryResult r2 = worker2.execute(
                    "SELECT id FROM tasks WHERE owner IS NULL ORDER BY id " +
                    "FOR UPDATE SKIP LOCKED LIMIT 1");
            assertEquals(1, r2.rows().size());
            int job2 = ((Number) r2.rows().get(0)[0]).intValue();

            // Workers claimed different jobs
            assertNotEquals(job1, job2, "Each worker should claim a distinct job");

            worker1.execute("COMMIT");
            worker2.execute("COMMIT");
        }
    }

    // =========================================================================
    // End-to-end job queue: NOTIFY-driven dispatch + SKIP LOCKED claiming
    // =========================================================================

    /**
     * Simulates a realistic job queue where:
     * <ol>
     *   <li>A producer inserts jobs and sends NOTIFY to wake workers</li>
     *   <li>Multiple workers each claim a distinct job via
     *       {@code SELECT FOR UPDATE SKIP LOCKED LIMIT 1}</li>
     *   <li>Workers mark jobs as done and COMMIT (releasing locks)</li>
     *   <li>A second batch is produced and workers process those too</li>
     * </ol>
     *
     * <p>Validates that no two workers ever process the same job and that
     * NOTIFY notifications are delivered correctly across sessions.
     */
    @Test void notifyDrivenJobQueue() throws Exception {
        final int workerCount = 3;

        try (Session producer = session()) {
            producer.execute("""
                    CREATE TABLE job_queue (
                        id serial PRIMARY KEY,
                        payload text NOT NULL,
                        status text NOT NULL DEFAULT 'pending',
                        claimed_by text
                    )
                    """);

            // --- Open worker sessions and LISTEN ---
            Session[] workers = new Session[workerCount];
            for (int i = 0; i < workerCount; i++) {
                workers[i] = session();
                workers[i].execute("LISTEN new_job");
            }

            // --- Producer: insert 3 jobs and notify ---
            producer.execute("BEGIN");
            producer.execute("INSERT INTO job_queue (payload) VALUES ('job-a')");
            producer.execute("INSERT INTO job_queue (payload) VALUES ('job-b')");
            producer.execute("INSERT INTO job_queue (payload) VALUES ('job-c')");
            producer.execute("NOTIFY new_job, 'batch-1'");
            producer.execute("COMMIT");

            // All workers should receive the notification
            for (int i = 0; i < workerCount; i++) {
                var ns = workers[i].drainNotifications();
                assertEquals(1, ns.length,
                        "worker " + i + " must receive new_job notification");
                assertEquals("batch-1", ns[0].payload());
            }

            // --- Each worker claims one distinct job ---
            Set<Integer> claimedIds = ConcurrentHashMap.newKeySet();
            for (int i = 0; i < workerCount; i++) {
                workers[i].execute("BEGIN");
                QueryResult r = workers[i].execute("""
                        SELECT id, payload FROM job_queue
                        WHERE status = 'pending'
                        ORDER BY id
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                        """);
                assertEquals(1, r.rows().size(),
                        "worker " + i + " must claim exactly one job");

                int jobId = ((Number) r.rows().get(0)[0]).intValue();
                assertTrue(claimedIds.add(jobId),
                        "job " + jobId + " was already claimed by another worker");

                // Mark as done
                workers[i].execute(
                        "UPDATE job_queue SET status = 'done', claimed_by = 'w" + i
                                + "' WHERE id = " + jobId);
            }

            // Commit all workers — releases locks
            for (int i = 0; i < workerCount; i++) {
                workers[i].execute("COMMIT");
            }

            // Verify: all 3 jobs done, each by a different worker
            assertEquals(3, claimedIds.size());
            QueryResult done = producer.execute(
                    "SELECT COUNT(*) FROM job_queue WHERE status = 'done'");
            assertEquals(3L, ((Number) done.rows().get(0)[0]).longValue());

            // --- Batch 2: produce more jobs, workers claim again ---
            producer.execute("BEGIN");
            producer.execute("INSERT INTO job_queue (payload) VALUES ('job-d')");
            producer.execute("INSERT INTO job_queue (payload) VALUES ('job-e')");
            producer.execute("NOTIFY new_job, 'batch-2'");
            producer.execute("COMMIT");

            for (int i = 0; i < workerCount; i++) {
                var ns = workers[i].drainNotifications();
                assertEquals(1, ns.length);
                assertEquals("batch-2", ns[0].payload());
            }

            // Only 2 jobs available — first 2 workers claim, third gets nothing
            Set<Integer> batch2Ids = ConcurrentHashMap.newKeySet();
            int emptyWorkers = 0;
            for (int i = 0; i < workerCount; i++) {
                workers[i].execute("BEGIN");
                QueryResult r = workers[i].execute("""
                        SELECT id FROM job_queue
                        WHERE status = 'pending'
                        ORDER BY id
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                        """);
                if (r.rows().isEmpty()) {
                    emptyWorkers++;
                } else {
                    int jobId = ((Number) r.rows().get(0)[0]).intValue();
                    assertTrue(batch2Ids.add(jobId));
                    workers[i].execute(
                            "UPDATE job_queue SET status = 'done', claimed_by = 'w" + i
                                    + "' WHERE id = " + jobId);
                }
                workers[i].execute("COMMIT");
            }

            assertEquals(2, batch2Ids.size(), "exactly 2 batch-2 jobs should be claimed");
            assertEquals(1, emptyWorkers, "one worker should find no pending jobs");

            // All 5 jobs processed
            QueryResult total = producer.execute(
                    "SELECT COUNT(*) FROM job_queue WHERE status = 'done'");
            assertEquals(5L, ((Number) total.rows().get(0)[0]).longValue());

            // Cleanup
            for (Session w : workers) w.close();
        }
    }

    /**
     * Concurrent workers on separate threads claim jobs simultaneously.
     * Verifies thread-safety of row-level locking and notification delivery.
     */
    @Test void concurrentWorkerThreads() throws Exception {
        final int jobCount = 10;
        final int workerCount = 3;

        try (Session setup = session()) {
            setup.execute("""
                    CREATE TABLE thread_queue (
                        id serial PRIMARY KEY,
                        status text NOT NULL DEFAULT 'pending'
                    )
                    """);
            StringBuilder ins = new StringBuilder(
                    "INSERT INTO thread_queue (status) VALUES ");
            for (int i = 0; i < jobCount; i++) {
                if (i > 0) ins.append(',');
                ins.append("('pending')");
            }
            setup.execute(ins.toString());
        }

        Set<Integer> allClaimed = ConcurrentHashMap.newKeySet();
        AtomicInteger totalProcessed = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch go = new CountDownLatch(1);

        Thread[] threads = new Thread[workerCount];
        for (int w = 0; w < workerCount; w++) {
            threads[w] = new Thread(() -> {
                try (Session s = session()) {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);

                    // Each worker loops, claiming one job at a time
                    while (true) {
                        s.execute("BEGIN");
                        QueryResult r = s.execute("""
                                SELECT id FROM thread_queue
                                WHERE status = 'pending'
                                ORDER BY id
                                FOR UPDATE SKIP LOCKED
                                LIMIT 1
                                """);
                        if (r.rows().isEmpty()) {
                            s.execute("COMMIT");
                            break;
                        }
                        int id = ((Number) r.rows().get(0)[0]).intValue();
                        assertTrue(allClaimed.add(id),
                                "job " + id + " claimed by two workers");
                        s.execute("UPDATE thread_queue SET status = 'done' WHERE id = " + id);
                        s.execute("COMMIT");
                        totalProcessed.incrementAndGet();
                    }
                } catch (Exception e) {
                    fail("Worker thread failed: " + e.getMessage());
                }
            });
            threads[w].start();
        }

        // Start all workers simultaneously
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();

        for (Thread t : threads) t.join(10_000);

        assertEquals(jobCount, totalProcessed.get(),
                "all " + jobCount + " jobs must be processed");
        assertEquals(jobCount, allClaimed.size(),
                "no duplicates — each job claimed exactly once");
    }
}
