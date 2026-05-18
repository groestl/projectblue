package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.QueryResult;
import org.pgjava.engine.Session;

import java.sql.SQLException;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the proper locking implementation:
 * - Table-level lock modes with compatibility matrix
 * - LOCK TABLE statement
 * - Row-level lock waiting (FOR UPDATE with BLOCK)
 * - Deadlock detection
 * - Advisory locks
 * - pg_locks catalog view
 */
class LockingTest {

    private Database db;

    @BeforeEach
    void setUp() {
        db = DatabaseRegistry.getOrCreate("locking_test_" + System.nanoTime());
    }

    private Session session() { return db.openSession(); }

    // =========================================================================
    // Table-level lock modes
    // =========================================================================

    @Test void concurrentSelectsSucceed() throws Exception {
        // ACCESS_SHARE is self-compatible
        try (Session s1 = session(); Session s2 = session()) {
            s1.execute("CREATE TABLE t (id int)");
            s1.execute("INSERT INTO t VALUES (1), (2)");

            s1.execute("BEGIN");
            s2.execute("BEGIN");

            QueryResult r1 = s1.execute("SELECT * FROM t");
            QueryResult r2 = s2.execute("SELECT * FROM t");

            assertEquals(2, r1.rows().size());
            assertEquals(2, r2.rows().size());

            s1.execute("COMMIT");
            s2.execute("COMMIT");
        }
    }

    @Test void concurrentInsertsSucceed() throws Exception {
        // ROW_EXCLUSIVE is self-compatible
        try (Session s1 = session(); Session s2 = session()) {
            s1.execute("CREATE TABLE t2 (id int)");

            s1.execute("BEGIN");
            s2.execute("BEGIN");

            s1.execute("INSERT INTO t2 VALUES (1)");
            s2.execute("INSERT INTO t2 VALUES (2)");

            s1.execute("COMMIT");
            s2.execute("COMMIT");

            try (Session s3 = session()) {
                QueryResult r = s3.execute("SELECT * FROM t2 ORDER BY id");
                assertEquals(2, r.rows().size());
            }
        }
    }

    // =========================================================================
    // LOCK TABLE statement
    // =========================================================================

    @Test void lockTableBasicMode() throws Exception {
        try (Session s = session()) {
            s.execute("CREATE TABLE lt (id int)");
            s.execute("BEGIN");
            // Should not throw — acquires ACCESS_EXCLUSIVE
            s.execute("LOCK TABLE lt");
            s.execute("COMMIT");
        }
    }

    @Test void lockTableInShareMode() throws Exception {
        try (Session s = session()) {
            s.execute("CREATE TABLE lt2 (id int)");
            s.execute("BEGIN");
            s.execute("LOCK TABLE lt2 IN SHARE MODE");
            s.execute("COMMIT");
        }
    }

    @Test void lockTableNowaitFails() throws Exception {
        try (Session s1 = session(); Session s2 = session()) {
            s1.execute("CREATE TABLE lt3 (id int)");

            s1.execute("BEGIN");
            s1.execute("LOCK TABLE lt3 IN ACCESS EXCLUSIVE MODE");

            s2.execute("BEGIN");
            SQLException ex = assertThrows(SQLException.class, () ->
                    s2.execute("LOCK TABLE lt3 IN ACCESS EXCLUSIVE MODE NOWAIT"));
            assertEquals("55P03", ex.getSQLState());

            s1.execute("COMMIT");
            s2.execute("ROLLBACK");
        }
    }

    @Test void lockTableReleasedOnCommit() throws Exception {
        try (Session s1 = session(); Session s2 = session()) {
            s1.execute("CREATE TABLE lt4 (id int)");

            // Session 1 locks then commits
            s1.execute("BEGIN");
            s1.execute("LOCK TABLE lt4 IN ACCESS EXCLUSIVE MODE");
            s1.execute("COMMIT");

            // Session 2 should now succeed
            s2.execute("BEGIN");
            s2.execute("LOCK TABLE lt4 IN ACCESS EXCLUSIVE MODE NOWAIT");
            s2.execute("COMMIT");
        }
    }

    // =========================================================================
    // Lock compatibility — DDL blocks SELECT
    // =========================================================================

    @Test void accessExclusiveBlocksAccessShare() throws Exception {
        try (Session s1 = session(); Session s2 = session()) {
            s1.execute("CREATE TABLE exc (id int)");
            s1.execute("INSERT INTO exc VALUES (1)");

            // Session 1 holds ACCESS_EXCLUSIVE
            s1.execute("BEGIN");
            s1.execute("LOCK TABLE exc IN ACCESS EXCLUSIVE MODE");

            // Session 2 tries a SELECT (needs ACCESS_SHARE)
            // Use tryAcquire semantics: attempt in a thread with short timeout
            s2.execute("BEGIN");
            s2.execute("SET lock_timeout = '200'");

            ExecutorService exec = Executors.newSingleThreadExecutor();
            Future<SQLException> future = exec.submit(() -> {
                try {
                    s2.execute("SELECT * FROM exc");
                    return null;
                } catch (SQLException e) {
                    return e;
                }
            });

            SQLException ex = future.get(5, TimeUnit.SECONDS);
            assertNotNull(ex, "Expected lock timeout error");
            assertEquals("55P03", ex.getSQLState(),
                    "Expected lock timeout (55P03), got: " + ex.getSQLState() + " " + ex.getMessage());

            s1.execute("COMMIT");
            try { s2.execute("ROLLBACK"); } catch (Exception ignored) {}
            exec.shutdown();
        }
    }

    // =========================================================================
    // Advisory locks
    // =========================================================================

    @Test void tryAdvisoryLockExclusive() throws Exception {
        try (Session s1 = session(); Session s2 = session()) {
            // Session 1 acquires advisory lock
            QueryResult r1 = s1.execute("SELECT pg_try_advisory_lock(42)");
            assertEquals(Boolean.TRUE, r1.rows().get(0)[0]);

            // Session 2 cannot acquire the same lock
            QueryResult r2 = s2.execute("SELECT pg_try_advisory_lock(42)");
            assertEquals(Boolean.FALSE, r2.rows().get(0)[0]);

            // Session 1 unlocks
            s1.execute("SELECT pg_advisory_unlock(42)");

            // Now session 2 can acquire
            QueryResult r3 = s2.execute("SELECT pg_try_advisory_lock(42)");
            assertEquals(Boolean.TRUE, r3.rows().get(0)[0]);

            // Cleanup
            s2.execute("SELECT pg_advisory_unlock(42)");
        }
    }

    @Test void advisoryXactLockReleasedOnCommit() throws Exception {
        try (Session s1 = session(); Session s2 = session()) {
            s1.execute("BEGIN");
            QueryResult r1 = s1.execute("SELECT pg_try_advisory_xact_lock(99)");
            assertEquals(Boolean.TRUE, r1.rows().get(0)[0]);

            // Session 2 cannot acquire
            QueryResult r2 = s2.execute("SELECT pg_try_advisory_xact_lock(99)");
            assertEquals(Boolean.FALSE, r2.rows().get(0)[0]);

            // Session 1 commits — releases xact-level lock
            s1.execute("COMMIT");

            // Now session 2 can acquire
            QueryResult r3 = s2.execute("SELECT pg_try_advisory_xact_lock(99)");
            assertEquals(Boolean.TRUE, r3.rows().get(0)[0]);
        }
    }

    @Test void advisorySessionLockSurvivesCommit() throws Exception {
        try (Session s1 = session(); Session s2 = session()) {
            s1.execute("BEGIN");
            s1.execute("SELECT pg_try_advisory_lock(55)");
            s1.execute("COMMIT");

            // Session-level lock persists after COMMIT
            QueryResult r2 = s2.execute("SELECT pg_try_advisory_lock(55)");
            assertEquals(Boolean.FALSE, r2.rows().get(0)[0]);

            // Explicit unlock
            s1.execute("SELECT pg_advisory_unlock(55)");
            QueryResult r3 = s2.execute("SELECT pg_try_advisory_lock(55)");
            assertEquals(Boolean.TRUE, r3.rows().get(0)[0]);
            s2.execute("SELECT pg_advisory_unlock(55)");
        }
    }

    @Test void advisoryUnlockAll() throws Exception {
        try (Session s1 = session(); Session s2 = session()) {
            s1.execute("SELECT pg_try_advisory_lock(10)");
            s1.execute("SELECT pg_try_advisory_lock(20)");

            // Both locked for s2
            assertEquals(Boolean.FALSE, s2.execute("SELECT pg_try_advisory_lock(10)").rows().get(0)[0]);
            assertEquals(Boolean.FALSE, s2.execute("SELECT pg_try_advisory_lock(20)").rows().get(0)[0]);

            // Unlock all
            s1.execute("SELECT pg_advisory_unlock_all()");

            // Now available
            assertEquals(Boolean.TRUE, s2.execute("SELECT pg_try_advisory_lock(10)").rows().get(0)[0]);
            assertEquals(Boolean.TRUE, s2.execute("SELECT pg_try_advisory_lock(20)").rows().get(0)[0]);

            s2.execute("SELECT pg_advisory_unlock_all()");
        }
    }

    // =========================================================================
    // pg_locks catalog view
    // =========================================================================

    @Test void pgLocksShowsRelationLocks() throws Exception {
        try (Session s = session()) {
            s.execute("CREATE TABLE pgl (id int)");
            s.execute("INSERT INTO pgl VALUES (1)");

            s.execute("BEGIN");
            s.execute("SELECT * FROM pgl");

            // pg_locks should show at least one ACCESS_SHARE lock
            QueryResult r = s.execute("SELECT locktype, mode, granted FROM pg_locks WHERE locktype = 'relation'");
            assertTrue(r.rows().size() > 0, "Expected relation locks in pg_locks");
            boolean foundAccessShare = r.rows().stream()
                    .anyMatch(row -> "AccessShareLock".equals(row[1]));
            assertTrue(foundAccessShare, "Expected AccessShareLock in pg_locks");

            s.execute("COMMIT");
        }
    }

    @Test void pgLocksShowsAdvisoryLocks() throws Exception {
        try (Session s = session()) {
            s.execute("SELECT pg_try_advisory_lock(123)");

            QueryResult r = s.execute("SELECT locktype, mode, granted FROM pg_locks WHERE locktype = 'advisory'");
            assertTrue(r.rows().size() > 0, "Expected advisory locks in pg_locks");
            boolean found = r.rows().stream()
                    .anyMatch(row -> "ExclusiveLock".equals(row[1]) && Boolean.TRUE.equals(row[2]));
            assertTrue(found, "Expected ExclusiveLock advisory lock");

            s.execute("SELECT pg_advisory_unlock(123)");
        }
    }

    // =========================================================================
    // Row-level lock waiting (FOR UPDATE BLOCK with timeout)
    // =========================================================================

    @Test void forUpdateBlockWaitsAndSucceeds() throws Exception {
        try (Session s1 = session(); Session s2 = session()) {
            s1.execute("CREATE TABLE q (id int PRIMARY KEY, status text)");
            s1.execute("INSERT INTO q VALUES (1, 'ready')");

            // Session 1 locks row 1
            s1.execute("BEGIN");
            s1.execute("SELECT * FROM q WHERE id = 1 FOR UPDATE");

            // Session 2 tries FOR UPDATE (BLOCK) in a separate thread
            s2.execute("BEGIN");
            s2.execute("SET lock_timeout = '5000'"); // 5 second timeout

            ExecutorService exec = Executors.newSingleThreadExecutor();
            Future<QueryResult> future = exec.submit(() ->
                    s2.execute("SELECT * FROM q WHERE id = 1 FOR UPDATE"));

            // Give s2 a moment to start waiting
            Thread.sleep(50);

            // Session 1 commits — releases the row lock
            s1.execute("COMMIT");

            // Session 2 should now succeed
            QueryResult r = future.get(10, TimeUnit.SECONDS);
            assertEquals(1, r.rows().size());

            s2.execute("COMMIT");
            exec.shutdown();
        }
    }

    @Test void forUpdateBlockTimeoutFails() throws Exception {
        try (Session s1 = session(); Session s2 = session()) {
            s1.execute("CREATE TABLE q2 (id int PRIMARY KEY)");
            s1.execute("INSERT INTO q2 VALUES (1)");

            s1.execute("BEGIN");
            s1.execute("SELECT * FROM q2 WHERE id = 1 FOR UPDATE");

            s2.execute("BEGIN");
            s2.execute("SET lock_timeout = '200'"); // 200ms timeout

            // Session 2 should timeout
            SQLException ex = assertThrows(SQLException.class, () ->
                    s2.execute("SELECT * FROM q2 WHERE id = 1 FOR UPDATE"));
            assertEquals("55P03", ex.getSQLState());

            s1.execute("COMMIT");
            s2.execute("ROLLBACK");
        }
    }

    // =========================================================================
    // Deadlock detection (table-level)
    // =========================================================================

    @Test void deadlockDetected() throws Exception {
        // Test deadlock detection: s1 holds A, s2 holds B.
        // s2 blocks on A (in a thread). Then s1 tries B — should detect cycle.
        try (Session s1 = session(); Session s2 = session()) {
            s1.execute("CREATE TABLE a (id int)");
            s1.execute("CREATE TABLE b (id int)");

            // Session 1 locks table A
            s1.execute("BEGIN");
            s1.execute("LOCK TABLE a IN ACCESS EXCLUSIVE MODE");

            // Session 2 locks table B
            s2.execute("BEGIN");
            s2.execute("LOCK TABLE b IN ACCESS EXCLUSIVE MODE");

            // Session 2 tries to lock table A in a background thread (will block)
            ExecutorService exec = Executors.newSingleThreadExecutor();
            Future<?> f = exec.submit(() -> {
                try {
                    s2.execute("LOCK TABLE a IN ACCESS EXCLUSIVE MODE");
                } catch (SQLException ignored) {
                    // May get deadlock or timeout — both acceptable
                }
            });

            // Give s2 time to enqueue as a waiter
            Thread.sleep(200);

            // Session 1 tries to lock table B — deadlock detection should fire
            boolean deadlockDetected = false;
            try {
                s1.execute("LOCK TABLE b IN ACCESS EXCLUSIVE MODE");
            } catch (SQLException e) {
                if ("40P01".equals(e.getSQLState())) {
                    deadlockDetected = true;
                }
            }
            assertTrue(deadlockDetected, "Expected deadlock to be detected (SQLSTATE 40P01)");

            // Cleanup — rollback releases locks, unblocking s2
            try { s1.execute("ROLLBACK"); } catch (Exception ignored) {}
            try { f.get(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
            try { s2.execute("ROLLBACK"); } catch (Exception ignored) {}
            exec.shutdown();
        }
    }

    // =========================================================================
    // lock_timeout GUC
    // =========================================================================

    @Test void lockTimeoutVariable() throws Exception {
        try (Session s = session()) {
            s.execute("SET lock_timeout = '1000'");
            // The variable is stored; actual enforcement tested above
            // Just verify it's accepted
        }
    }
}
