package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.pgjava.engine.Database;

import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@code currval()} is isolated per session (per thread).
 *
 * <p>The bug: {@code SequenceDef.lastValue} was a {@code volatile Long} on the shared
 * {@code SequenceDef} instance. Session A's {@code nextval()} write was visible to
 * session B's {@code currval()}, violating the PostgreSQL spec which says currval()
 * returns the last value obtained by nextval() <em>in the current session</em>.
 *
 * <p>The fix: {@code lastValue} is now a {@code ThreadLocal<Long>}, isolating the
 * last-value state per thread (= per session, since each session uses its own thread).
 */
class SequenceSessionIsolationTest {

    @Test
    void currvalNotVisibleAcrossSessions() throws Exception {
        Database db = Database.create("seq_isolation_db");

        try (var s = db.openSession()) {
            s.execute("CREATE SEQUENCE myseq START 1");
        }

        CountDownLatch session1Advanced = new CountDownLatch(1);
        CountDownLatch session2Done     = new CountDownLatch(1);

        AtomicReference<SQLException> session2Error = new AtomicReference<>();

        // Session 1: advance the sequence
        Thread t1 = Thread.ofVirtual().start(() -> {
            try (var s1 = db.openSession()) {
                s1.execute("SELECT nextval('myseq')"); // advances to 1, sets thread-local lastValue=1
                session1Advanced.countDown();
                session2Done.await(); // keep session alive while session 2 runs
            } catch (Exception e) {
                session1Advanced.countDown();
            }
        });

        // Session 2: must NOT be able to call currval — nextval never called in this session
        Thread t2 = Thread.ofVirtual().start(() -> {
            try {
                session1Advanced.await();
                try (var s2 = db.openSession()) {
                    // currval must throw SQLSTATE 55000 because nextval was called by session 1, not us
                    s2.execute("SELECT currval('myseq')");
                    session2Error.set(new SQLException("Expected currval to throw 55000 but it didn't"));
                } catch (SQLException ex) {
                    if (!"55000".equals(ex.getSQLState())) {
                        session2Error.set(new SQLException(
                                "Expected SQLSTATE 55000, got " + ex.getSQLState() + ": " + ex.getMessage()));
                    }
                    // 55000 = correct: currval not defined in this session
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                session2Done.countDown();
            }
        });

        t1.join(5000);
        t2.join(5000);

        if (session2Error.get() != null) {
            throw session2Error.get();
        }
    }

    @Test
    void nextvalAndCurrvalInSameSession() throws SQLException {
        Database db = Database.create("seq_same_session_db");
        try (var s = db.openSession()) {
            s.execute("CREATE SEQUENCE counter START 10 INCREMENT 5");

            var res1 = s.execute("SELECT nextval('counter')");
            long v1 = ((Number) res1.rows().get(0)[0]).longValue();
            assertEquals(10L, v1);

            var res2 = s.execute("SELECT currval('counter')");
            long cv = ((Number) res2.rows().get(0)[0]).longValue();
            assertEquals(10L, cv, "currval must equal the last nextval in same session");

            var res3 = s.execute("SELECT nextval('counter')");
            long v2 = ((Number) res3.rows().get(0)[0]).longValue();
            assertEquals(15L, v2);

            var res4 = s.execute("SELECT currval('counter')");
            assertEquals(15L, ((Number) res4.rows().get(0)[0]).longValue());
        }
    }

    @Test
    void currvalBeforeNextvalThrows() throws SQLException {
        Database db = Database.create("seq_currval_early_db");
        try (var s = db.openSession()) {
            s.execute("CREATE SEQUENCE fresh START 1");
            SQLException ex = assertThrows(SQLException.class,
                    () -> s.execute("SELECT currval('fresh')"));
            assertEquals("55000", ex.getSQLState(),
                    "Expected SQLSTATE 55000 (currval not yet defined), got: " + ex.getSQLState());
        }
    }

    @Test
    void setvalMakesCurrvalAvailableInSameSession() throws SQLException {
        Database db = Database.create("seq_setval_db");
        try (var s = db.openSession()) {
            s.execute("CREATE SEQUENCE sv_seq START 1");
            s.execute("SELECT setval('sv_seq', 42)");

            var res = s.execute("SELECT currval('sv_seq')");
            assertEquals(42L, ((Number) res.rows().get(0)[0]).longValue());
        }
    }

    @Test
    void twoSessionsGetDistinctNextvals() throws Exception {
        Database db = Database.create("seq_two_sessions_db");
        try (var s = db.openSession()) {
            s.execute("CREATE SEQUENCE shared START 1");
        }

        AtomicLong val1 = new AtomicLong();
        AtomicLong val2 = new AtomicLong();

        Thread t1 = Thread.ofVirtual().start(() -> {
            try (var s1 = db.openSession()) {
                var r = s1.execute("SELECT nextval('shared')");
                val1.set(((Number) r.rows().get(0)[0]).longValue());
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        Thread t2 = Thread.ofVirtual().start(() -> {
            try (var s2 = db.openSession()) {
                var r = s2.execute("SELECT nextval('shared')");
                val2.set(((Number) r.rows().get(0)[0]).longValue());
            } catch (SQLException e) { throw new RuntimeException(e); }
        });

        t1.join(5000);
        t2.join(5000);

        // Both sessions must get a value; they must be different (sequence is global)
        assertTrue(val1.get() > 0, "Session 1 nextval must be positive");
        assertTrue(val2.get() > 0, "Session 2 nextval must be positive");
        assertNotEquals(val1.get(), val2.get(), "Two concurrent nextval calls must return distinct values");
    }
}
