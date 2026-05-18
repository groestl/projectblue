package org.pgjava.tests;

import org.junit.jupiter.api.*;
import org.pgjava.engine.ClusterConfig;
import org.pgjava.jdbc.ClusterRegistry;
import org.pgjava.jdbc.PgJavaCluster;
import org.pgjava.jdbc.SchemaHandle;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for multi-cluster support, instance configuration, and schema isolation.
 */
class ClusterTest {

    // =========================================================================
    // ClusterConfig builder
    // =========================================================================

    @Test void configDefaults() {
        ClusterConfig cfg = ClusterConfig.builder("test").build();
        assertEquals("test", cfg.name());
        assertEquals(0,    cfg.port());
        assertNull(cfg.dataDirectory());
        assertFalse(cfg.dropOnStop());
        assertFalse(cfg.hasServer());
        assertFalse(cfg.isPersistent());
    }

    @Test void configBuilder() {
        ClusterConfig cfg = ClusterConfig.builder("prod")
                .port(5433)
                .dropOnStop(true)
                .build();
        assertEquals("prod", cfg.name());
        assertEquals(5433,   cfg.port());
        assertTrue(cfg.hasServer());
        assertTrue(cfg.dropOnStop());
    }

    @Test void configBlankNameThrows() {
        assertThrows(IllegalArgumentException.class, () -> ClusterConfig.builder(""));
        assertThrows(IllegalArgumentException.class, () -> ClusterConfig.builder("  "));
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Test void startAndStop() {
        PgJavaCluster cluster = PgJavaCluster.create(
                ClusterConfig.builder("lifecycle_" + System.nanoTime())
                        .dropOnStop(true)
                        .build()
        );
        assertFalse(cluster.isRunning());
        cluster.start();
        assertTrue(cluster.isRunning());
        cluster.stop();
        assertFalse(cluster.isRunning());
    }

    @Test void startIsIdempotent() {
        PgJavaCluster cluster = PgJavaCluster.create(
                ClusterConfig.builder("idem_" + System.nanoTime())
                        .dropOnStop(true)
                        .build()
        ).start();
        assertDoesNotThrow(cluster::start); // second start is a no-op
        cluster.stop();
    }

    @Test void stopThenStartThrows() {
        PgJavaCluster cluster = PgJavaCluster.create(
                ClusterConfig.builder("stopped_" + System.nanoTime())
                        .dropOnStop(true)
                        .build()
        ).start();
        cluster.stop();
        assertThrows(IllegalStateException.class, cluster::start);
    }

    @Test void operationsRequireRunning() {
        PgJavaCluster cluster = PgJavaCluster.create(
                ClusterConfig.builder("notstarted_" + System.nanoTime()).build());
        assertThrows(IllegalStateException.class, () -> cluster.database("x"));
        assertThrows(IllegalStateException.class, () -> cluster.allocateSchema("x"));
    }

    // =========================================================================
    // ClusterRegistry
    // =========================================================================

    @Test void registryLookup() {
        String name = "reg_" + System.nanoTime();
        PgJavaCluster cluster = PgJavaCluster.create(
                ClusterConfig.builder(name).dropOnStop(true).build()
        ).start();
        assertSame(cluster, ClusterRegistry.get(name));
        cluster.stop();
        assertNull(ClusterRegistry.get(name)); // dropOnStop removed it
    }

    @Test void registryDuplicateNameThrows() {
        String name = "dup_" + System.nanoTime();
        PgJavaCluster c1 = PgJavaCluster.create(
                ClusterConfig.builder(name).dropOnStop(true).build()).start();
        assertThrows(IllegalStateException.class, () ->
                PgJavaCluster.create(ClusterConfig.builder(name).build()).start());
        c1.stop();
    }

    @Test void multipleClustersSameJvm() throws Exception {
        String a = "multi_a_" + System.nanoTime();
        String b = "multi_b_" + System.nanoTime();
        PgJavaCluster ca = PgJavaCluster.create(
                ClusterConfig.builder(a).dropOnStop(true).build()).start();
        PgJavaCluster cb = PgJavaCluster.create(
                ClusterConfig.builder(b).dropOnStop(true).build()).start();
        try {
            // Each cluster has isolated databases
            try (Connection conn = DriverManager.getConnection("jdbc:pgjava://" + a + "/db1");
                 Statement  st   = conn.createStatement()) {
                st.execute("CREATE TABLE t (id int)");
                st.execute("INSERT INTO t VALUES (1)");
            }
            try (Connection conn = DriverManager.getConnection("jdbc:pgjava://" + b + "/db1");
                 Statement  st   = conn.createStatement()) {
                // Same db name in different cluster — should be empty
                st.execute("CREATE TABLE t (id int)");
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t");
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        } finally {
            ca.stop();
            cb.stop();
        }
    }

    // =========================================================================
    // JDBC URL routing
    // =========================================================================

    @Test void clusterScopedUrlConnects() throws Exception {
        String name = "jdbc_" + System.nanoTime();
        PgJavaCluster cluster = PgJavaCluster.create(
                ClusterConfig.builder(name).dropOnStop(true).build()).start();
        try (Connection conn = DriverManager.getConnection("jdbc:pgjava://" + name + "/mydb");
             Statement  st   = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT 1");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        } finally {
            cluster.stop();
        }
    }

    @Test void unknownClusterThrows() {
        assertThrows(SQLException.class, () ->
                DriverManager.getConnection("jdbc:pgjava://nonexistent_cluster_xyz/db"));
    }

    @Test void legacyUrlStillWorks() throws Exception {
        // jdbc:pgjava:mem:name must continue to work unchanged
        String dbName = "legacy_" + System.nanoTime();
        try (Connection conn = DriverManager.getConnection("jdbc:pgjava:mem:" + dbName);
             Statement  st   = conn.createStatement()) {
            st.execute("CREATE TABLE t (x int)");
            st.execute("INSERT INTO t VALUES (42)");
            ResultSet rs = st.executeQuery("SELECT x FROM t");
            assertTrue(rs.next());
            assertEquals(42, rs.getInt(1));
        }
    }

    // =========================================================================
    // SchemaHandle — isolation
    // =========================================================================

    @Test void schemaHandleAllocatesIsolatedSchema() throws Exception {
        PgJavaCluster cluster = PgJavaCluster.create(
                ClusterConfig.builder("sh_" + System.nanoTime())
                        .dropOnStop(true).build()).start();
        try (SchemaHandle h = cluster.allocateSchema("testdb")) {
            assertNotNull(h.schemaName());
            assertTrue(h.schemaName().startsWith("test_"));
            // DataSource scoped to the schema
            try (Connection conn = h.dataSource().getConnection();
                 Statement  st   = conn.createStatement()) {
                st.execute("CREATE TABLE items (id int)");
                st.execute("INSERT INTO items VALUES (99)");
                ResultSet rs = st.executeQuery("SELECT id FROM items");
                assertTrue(rs.next());
                assertEquals(99, rs.getInt(1));
            }
        } finally {
            cluster.stop();
        }
    }

    @Test void schemasAreIsolatedFromEachOther() throws Exception {
        PgJavaCluster cluster = PgJavaCluster.create(
                ClusterConfig.builder("iso_" + System.nanoTime())
                        .dropOnStop(true).build()).start();
        try {
            try (SchemaHandle h1 = cluster.allocateSchema("testdb");
                 SchemaHandle h2 = cluster.allocateSchema("testdb")) {

                assertNotEquals(h1.schemaName(), h2.schemaName());

                // Create same-named table in both schemas
                for (SchemaHandle h : List.of(h1, h2)) {
                    try (Connection conn = h.dataSource().getConnection();
                         Statement  st   = conn.createStatement()) {
                        st.execute("CREATE TABLE items (val int)");
                    }
                }
                // Write to h1
                try (Connection conn = h1.dataSource().getConnection();
                     Statement  st   = conn.createStatement()) {
                    st.execute("INSERT INTO items VALUES (1)");
                }
                // h2 should still be empty
                try (Connection conn = h2.dataSource().getConnection();
                     Statement  st   = conn.createStatement()) {
                    ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM items");
                    rs.next();
                    assertEquals(0, rs.getInt(1));
                }
            }
        } finally {
            cluster.stop();
        }
    }

    @Test void schemaHandleDropCleansUp() throws Exception {
        PgJavaCluster cluster = PgJavaCluster.create(
                ClusterConfig.builder("drop_" + System.nanoTime())
                        .dropOnStop(true).build()).start();
        try {
            SchemaHandle h = cluster.allocateSchema("testdb");
            String schemaName = h.schemaName();
            h.drop();
            // Schema should no longer exist — a fresh connection to the same schema name should fail
            assertThrows(Exception.class, () -> {
                try (Connection conn = h.dataSource().getConnection();
                     Statement  st   = conn.createStatement()) {
                    st.execute("CREATE TABLE t (x int)"); // schema gone, should fail
                }
            });
        } finally {
            cluster.stop();
        }
    }

    @Test void schemaHandleDropIsIdempotent() throws Exception {
        PgJavaCluster cluster = PgJavaCluster.create(
                ClusterConfig.builder("idem2_" + System.nanoTime())
                        .dropOnStop(true).build()).start();
        try {
            SchemaHandle h = cluster.allocateSchema("testdb");
            h.drop();
            assertDoesNotThrow(h::drop); // second drop is a no-op
        } finally {
            cluster.stop();
        }
    }

    @Test void schemaHandleJdbcUrl() throws Exception {
        String clusterName = "url_" + System.nanoTime();
        PgJavaCluster cluster = PgJavaCluster.create(
                ClusterConfig.builder(clusterName).dropOnStop(true).build()).start();
        try (SchemaHandle h = cluster.allocateSchema("testdb")) {
            String url = h.jdbcUrl();
            assertTrue(url.startsWith("jdbc:pgjava://" + clusterName + "/testdb?schema="));
            // URL is connectable
            try (Connection conn = DriverManager.getConnection(url);
                 Statement  st   = conn.createStatement()) {
                st.execute("CREATE TABLE t (x int)");
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t");
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        } finally {
            cluster.stop();
        }
    }

    // =========================================================================
    // Parallel schema isolation
    // =========================================================================

    @Test void parallelSchemasDoNotInterfere() throws Exception {
        int nThreads = 8;
        PgJavaCluster cluster = PgJavaCluster.create(
                ClusterConfig.builder("par_" + System.nanoTime())
                        .dropOnStop(true).build()).start();
        try {
            ExecutorService exec = Executors.newFixedThreadPool(nThreads);
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 0; i < nThreads; i++) {
                final int threadId = i;
                futures.add(exec.submit(() -> {
                    try (SchemaHandle h = cluster.allocateSchema("testdb")) {
                        try (Connection conn = h.dataSource().getConnection();
                             Statement  st   = conn.createStatement()) {
                            st.execute("CREATE TABLE vals (n int)");
                            st.execute("INSERT INTO vals VALUES (" + threadId + ")");
                            ResultSet rs = st.executeQuery("SELECT n FROM vals");
                            rs.next();
                            int val = rs.getInt(1);
                            if (val != threadId)
                                throw new AssertionError("expected " + threadId + " got " + val);
                        }
                        return h.schemaName();
                    }
                }));
            }

            // All threads complete without cross-contamination
            List<String> schemas = new ArrayList<>();
            for (Future<String> f : futures) {
                schemas.add(f.get(10, TimeUnit.SECONDS));
            }
            // All schema names were distinct
            assertEquals(nThreads, schemas.stream().distinct().count());

            exec.shutdown();
        } finally {
            cluster.stop();
        }
    }
}
