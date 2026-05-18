package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.engine.PgNotification;
import org.pgjava.engine.Session;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 12: LISTEN / NOTIFY / UNLISTEN / pg_notify().
 *
 * <p>Tests the per-database notification bus, transaction-scoped delivery
 * (hold until COMMIT, discard on ROLLBACK), deduplication, UNLISTEN, and
 * cross-session delivery.
 */
class Phase12NotifyTest {

    private Database db;

    @BeforeEach
    void setUp() {
        db = DatabaseRegistry.getOrCreate("phase12_notify_" + System.nanoTime());
    }

    private Session session() { return db.openSession(); }

    // =========================================================================
    // Basic LISTEN / NOTIFY
    // =========================================================================

    @Test void listenReceivesNotify() throws SQLException {
        try (Session listener = session(); Session sender = session()) {
            listener.execute("LISTEN mychannel");

            // Notify outside a transaction (auto-commit) → delivered immediately
            sender.execute("NOTIFY mychannel, 'hello'");

            PgNotification[] ns = listener.drainNotifications();
            assertEquals(1, ns.length);
            assertEquals("mychannel", ns[0].channel());
            assertEquals("hello", ns[0].payload());
        }
    }

    @Test void notifyWithoutPayload() throws SQLException {
        try (Session listener = session(); Session sender = session()) {
            listener.execute("LISTEN events");
            sender.execute("NOTIFY events");

            PgNotification[] ns = listener.drainNotifications();
            assertEquals(1, ns.length);
            assertEquals("events", ns[0].channel());
            assertEquals("", ns[0].payload());
        }
    }

    @Test void unlistenedChannelReceivesNothing() throws SQLException {
        try (Session listener = session(); Session sender = session()) {
            listener.execute("LISTEN channelA");

            // Notify on a different channel
            sender.execute("NOTIFY channelB, 'msg'");

            PgNotification[] ns = listener.drainNotifications();
            assertEquals(0, ns.length);
        }
    }

    // =========================================================================
    // Transaction semantics
    // =========================================================================

    @Test void notifyInTransactionDeliveredOnCommit() throws SQLException {
        try (Session listener = session(); Session sender = session()) {
            listener.execute("LISTEN jobs");

            sender.execute("BEGIN");
            sender.execute("NOTIFY jobs, 'payload1'");

            // Not yet delivered — still in transaction
            assertEquals(0, listener.drainNotifications().length,
                    "NOTIFY inside active tx must not deliver until COMMIT");

            sender.execute("COMMIT");

            PgNotification[] ns = listener.drainNotifications();
            assertEquals(1, ns.length);
            assertEquals("jobs", ns[0].channel());
            assertEquals("payload1", ns[0].payload());
        }
    }

    @Test void notifyInTransactionDiscardedOnRollback() throws SQLException {
        try (Session listener = session(); Session sender = session()) {
            listener.execute("LISTEN jobs");

            sender.execute("BEGIN");
            sender.execute("NOTIFY jobs, 'will be lost'");
            sender.execute("ROLLBACK");

            assertEquals(0, listener.drainNotifications().length,
                    "NOTIFY inside rolled-back tx must never be delivered");
        }
    }

    @Test void multipleNotifiesInTransaction() throws SQLException {
        try (Session listener = session(); Session sender = session()) {
            listener.execute("LISTEN ch");

            sender.execute("BEGIN");
            sender.execute("NOTIFY ch, 'a'");
            sender.execute("NOTIFY ch, 'b'");
            sender.execute("NOTIFY ch, 'c'");
            sender.execute("COMMIT");

            PgNotification[] ns = listener.drainNotifications();
            assertEquals(3, ns.length);
        }
    }

    // =========================================================================
    // Deduplication
    // =========================================================================

    @Test void deduplicateSameChannelPayload() throws SQLException {
        try (Session listener = session(); Session sender = session()) {
            listener.execute("LISTEN dedupch");

            sender.execute("BEGIN");
            sender.execute("NOTIFY dedupch, 'same'");
            sender.execute("NOTIFY dedupch, 'same'");
            sender.execute("NOTIFY dedupch, 'same'");
            sender.execute("COMMIT");

            // PostgreSQL deduplicates (channel, payload) within one transaction
            PgNotification[] ns = listener.drainNotifications();
            assertEquals(1, ns.length, "Duplicate (channel,payload) must be deduplicated");
            assertEquals("same", ns[0].payload());
        }
    }

    @Test void differentPayloadsSameChannelNotDeduped() throws SQLException {
        try (Session listener = session(); Session sender = session()) {
            listener.execute("LISTEN ch");

            sender.execute("BEGIN");
            sender.execute("NOTIFY ch, 'a'");
            sender.execute("NOTIFY ch, 'b'");
            sender.execute("COMMIT");

            PgNotification[] ns = listener.drainNotifications();
            assertEquals(2, ns.length, "Different payloads on same channel must not be deduped");
        }
    }

    // =========================================================================
    // UNLISTEN
    // =========================================================================

    @Test void unlistenStopsDelivery() throws SQLException {
        try (Session listener = session(); Session sender = session()) {
            listener.execute("LISTEN stopchan");

            sender.execute("NOTIFY stopchan, 'first'");
            assertEquals(1, listener.drainNotifications().length);

            listener.execute("UNLISTEN stopchan");

            sender.execute("NOTIFY stopchan, 'second'");
            assertEquals(0, listener.drainNotifications().length,
                    "UNLISTEN must stop future deliveries");
        }
    }

    @Test void unlistenStar() throws SQLException {
        try (Session listener = session(); Session sender = session()) {
            listener.execute("LISTEN ch1");
            listener.execute("LISTEN ch2");
            listener.execute("UNLISTEN *");

            sender.execute("NOTIFY ch1, 'x'");
            sender.execute("NOTIFY ch2, 'y'");

            assertEquals(0, listener.drainNotifications().length,
                    "UNLISTEN * must remove all subscriptions");
        }
    }

    // =========================================================================
    // pg_notify() function form
    // =========================================================================

    @Test void pgNotifyFunction() throws SQLException {
        try (Session listener = session(); Session sender = session()) {
            listener.execute("LISTEN fnch");

            // pg_notify called as a SELECT expression
            sender.execute("SELECT pg_notify('fnch', 'from_function')");

            PgNotification[] ns = listener.drainNotifications();
            assertEquals(1, ns.length);
            assertEquals("fnch", ns[0].channel());
            assertEquals("from_function", ns[0].payload());
        }
    }

    @Test void pgNotifyInTransaction() throws SQLException {
        try (Session listener = session(); Session sender = session()) {
            listener.execute("LISTEN txfn");

            sender.execute("BEGIN");
            sender.execute("SELECT pg_notify('txfn', 'buffered')");
            assertEquals(0, listener.drainNotifications().length,
                    "pg_notify inside tx must buffer until COMMIT");
            sender.execute("COMMIT");

            PgNotification[] ns = listener.drainNotifications();
            assertEquals(1, ns.length);
            assertEquals("buffered", ns[0].payload());
        }
    }

    // =========================================================================
    // Multi-listener
    // =========================================================================

    @Test void multipleListenersSameChannel() throws SQLException {
        try (Session l1 = session(); Session l2 = session(); Session sender = session()) {
            l1.execute("LISTEN broadcast");
            l2.execute("LISTEN broadcast");

            sender.execute("NOTIFY broadcast, 'msg'");

            assertEquals(1, l1.drainNotifications().length, "l1 must receive notification");
            assertEquals(1, l2.drainNotifications().length, "l2 must receive notification");
        }
    }

    // =========================================================================
    // Drain idempotency
    // =========================================================================

    @Test void drainTwiceSecondIsEmpty() throws SQLException {
        try (Session listener = session(); Session sender = session()) {
            listener.execute("LISTEN x");
            sender.execute("NOTIFY x, 'once'");

            PgNotification[] first  = listener.drainNotifications();
            PgNotification[] second = listener.drainNotifications();
            assertEquals(1, first.length);
            assertEquals(0, second.length, "draining twice should yield empty on second call");
        }
    }

    // =========================================================================
    // pg_notification_queue_usage stub
    // =========================================================================

    @Test void queueUsageReturnsZero() throws SQLException {
        try (Session s = session()) {
            var r = s.execute("SELECT pg_notification_queue_usage()");
            assertEquals(1, r.rows().size());
            assertEquals(0.0, ((Number) r.rows().get(0)[0]).doubleValue(), 1e-9);
        }
    }
}
