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
 * Phase 12: set-returning functions (SRF) infrastructure + pg_listening_channels().
 *
 * <p>Tests FROM-clause SRF invocation, output schema, column aliases, and the
 * session-specific pg_listening_channels() function.
 */
class Phase12SrfTest {

    private Database db;

    @BeforeEach
    void setUp() {
        db = DatabaseRegistry.getOrCreate("phase12_srf_" + System.nanoTime());
    }

    private Session session() { return db.openSession(); }

    // =========================================================================
    // pg_listening_channels() — basic
    // =========================================================================

    @Test void noChannels() throws SQLException {
        try (Session s = session()) {
            QueryResult r = s.execute("SELECT * FROM pg_listening_channels()");
            assertTrue(r.isQuery());
            assertEquals(0, r.rows().size(), "No channels yet — must return 0 rows");
        }
    }

    @Test void oneChannel() throws SQLException {
        try (Session s = session()) {
            s.execute("LISTEN mychan");
            QueryResult r = s.execute("SELECT * FROM pg_listening_channels()");
            assertEquals(1, r.rows().size());
            assertEquals("mychan", r.rows().get(0)[0].toString());
        }
    }

    @Test void multipleChannelsSorted() throws SQLException {
        try (Session s = session()) {
            s.execute("LISTEN zebra");
            s.execute("LISTEN apple");
            s.execute("LISTEN mango");

            QueryResult r = s.execute("SELECT * FROM pg_listening_channels()");
            assertEquals(3, r.rows().size());
            // Output is sorted (deterministic)
            List<String> channels = r.rows().stream()
                    .map(row -> row[0].toString())
                    .toList();
            assertEquals(List.of("apple", "mango", "zebra"), channels);
        }
    }

    @Test void unlistenRemovesChannel() throws SQLException {
        try (Session s = session()) {
            s.execute("LISTEN ch1");
            s.execute("LISTEN ch2");
            s.execute("UNLISTEN ch1");

            QueryResult r = s.execute("SELECT * FROM pg_listening_channels()");
            assertEquals(1, r.rows().size());
            assertEquals("ch2", r.rows().get(0)[0].toString());
        }
    }

    @Test void unlistenStarClearsAll() throws SQLException {
        try (Session s = session()) {
            s.execute("LISTEN a");
            s.execute("LISTEN b");
            s.execute("UNLISTEN *");

            QueryResult r = s.execute("SELECT * FROM pg_listening_channels()");
            assertEquals(0, r.rows().size());
        }
    }

    // =========================================================================
    // Column alias
    // =========================================================================

    @Test void columnAliasRenames() throws SQLException {
        try (Session s = session()) {
            s.execute("LISTEN events");
            QueryResult r = s.execute(
                    "SELECT channel FROM pg_listening_channels() AS t(channel)");
            assertEquals(1, r.rows().size());
            assertEquals("events", r.rows().get(0)[0].toString());
            // Output column should be named "channel" (from alias)
            assertEquals("channel", r.columns().get(0).name());
        }
    }

    @Test void tableAliasOnly() throws SQLException {
        try (Session s = session()) {
            s.execute("LISTEN jobs");
            // Table alias without column alias — column name stays as function name
            QueryResult r = s.execute(
                    "SELECT * FROM pg_listening_channels() AS chans");
            assertEquals(1, r.rows().size());
            assertEquals("jobs", r.rows().get(0)[0].toString());
        }
    }

    // =========================================================================
    // WHERE filter on SRF output
    // =========================================================================

    @Test void whereFilterOnSrf() throws SQLException {
        try (Session s = session()) {
            s.execute("LISTEN alpha");
            s.execute("LISTEN beta");
            s.execute("LISTEN gamma");

            QueryResult r = s.execute(
                    "SELECT * FROM pg_listening_channels() AS t(ch)" +
                    " WHERE ch LIKE 'a%'");
            assertEquals(1, r.rows().size());
            assertEquals("alpha", r.rows().get(0)[0].toString());
        }
    }

    // =========================================================================
    // SRF does not affect session state of another session
    // =========================================================================

    @Test void independentPerSession() throws SQLException {
        try (Session s1 = session(); Session s2 = session()) {
            s1.execute("LISTEN shared_chan");
            // s2 never listened

            QueryResult r1 = s1.execute("SELECT * FROM pg_listening_channels()");
            QueryResult r2 = s2.execute("SELECT * FROM pg_listening_channels()");

            assertEquals(1, r1.rows().size());
            assertEquals(0, r2.rows().size(),
                    "pg_listening_channels must reflect per-session LISTEN state");
        }
    }
}
