package org.pgjava.tests;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.jdbc.PgJavaDataSource;

import javax.sql.DataSource;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 12 — ORM Compatibility Smoke Tests.
 *
 * <p>Exercises HikariCP, Flyway, and Hibernate against the pgjava embedded JDBC driver.
 * Each section uses an isolated in-memory database to avoid inter-test interference.
 */
class Phase12OrmSmokeTest {

    // =========================================================================
    // HikariCP
    // =========================================================================

    /**
     * HikariCP wrapping PgJavaDataSource. Verifies the pool can checkout connections,
     * execute queries, and release connections back to the pool.
     */
    @Test
    void hikariCpBasicPool() throws Exception {
        Database db = DatabaseRegistry.getOrCreate("orm_hikari_" + System.nanoTime());

        var rawDs = PgJavaDataSource.forDatabase(db);
        var config = new HikariConfig();
        config.setDataSource(rawDs);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTestQuery("SELECT 1");
        config.setInitializationFailTimeout(-1); // don't fail on pool init

        try (var pool = new HikariDataSource(config)) {
            // First connection: create and populate a table
            try (Connection c = pool.getConnection();
                 Statement s = c.createStatement()) {
                s.execute("CREATE TABLE items (id INT, label TEXT)");
                s.execute("INSERT INTO items VALUES (1, 'alpha'), (2, 'beta')");
            }

            // Second connection checkout: query the same data
            try (Connection c = pool.getConnection();
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT count(*) FROM items")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }

            // Concurrent connections (up to pool max)
            Connection[] conns = new Connection[3];
            try {
                for (int i = 0; i < 3; i++) conns[i] = pool.getConnection();
                assertNotNull(conns[2]);
            } finally {
                for (Connection c : conns) if (c != null) c.close();
            }
        }
    }

    /**
     * HikariCP with transaction: auto-commit off, explicit commit, verify persistence.
     */
    @Test
    void hikariCpTransaction() throws Exception {
        Database db = DatabaseRegistry.getOrCreate("orm_hikari_tx_" + System.nanoTime());

        var rawDs = PgJavaDataSource.forDatabase(db);
        var config = new HikariConfig();
        config.setDataSource(rawDs);
        config.setMaximumPoolSize(3);
        config.setConnectionTestQuery("SELECT 1");
        config.setInitializationFailTimeout(-1);

        try (var pool = new HikariDataSource(config)) {
            try (Connection c = pool.getConnection()) {
                c.setAutoCommit(false);
                try (Statement s = c.createStatement()) {
                    s.execute("CREATE TABLE accounts (id INT PRIMARY KEY, balance NUMERIC)");
                    s.execute("INSERT INTO accounts VALUES (1, 1000.00), (2, 500.00)");
                }
                c.commit();
            }

            // Verify committed data visible in new connection
            try (Connection c = pool.getConnection();
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT sum(balance) FROM accounts")) {
                assertTrue(rs.next());
                assertEquals(1500.0, rs.getDouble(1), 0.01);
            }
        }
    }

    // =========================================================================
    // Flyway
    // =========================================================================

    /**
     * Flyway migrates V1 (CREATE TABLE) and V2 (INSERT) against pgjava.
     */
    @Test
    void flywayMigrateCreateAndSeed() throws Exception {
        Database db = DatabaseRegistry.getOrCreate("orm_flyway_" + System.nanoTime());
        DataSource ds = PgJavaDataSource.forDatabase(db);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration/orm_smoke")
                .baselineOnMigrate(false)
                .load();

        MigrateResult result = flyway.migrate();
        assertEquals(2, result.migrationsExecuted,
                "Expected 2 migrations: V1 (create table) and V2 (seed data)");

        // Verify the products table was created and seeded
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM products")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        }
    }

    /**
     * Flyway validate: after migrate, validate() should find no pending migrations.
     */
    @Test
    void flywayValidateAfterMigrate() throws Exception {
        Database db = DatabaseRegistry.getOrCreate("orm_flyway_val_" + System.nanoTime());
        DataSource ds = PgJavaDataSource.forDatabase(db);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration/orm_smoke")
                .load();

        flyway.migrate();
        // validate() throws if there are pending or failed migrations
        assertDoesNotThrow(flyway::validate);

        // Check migration history via info()
        MigrationInfo[] applied = flyway.info().applied();
        assertEquals(2, applied.length);
        assertEquals("1", applied[0].getVersion().getVersion());
        assertEquals("2", applied[1].getVersion().getVersion());
    }

    /**
     * Flyway repair: baseline + repair round-trip.
     */
    @Test
    void flywayBaselineAndMigrate() throws Exception {
        Database db = DatabaseRegistry.getOrCreate("orm_flyway_bl_" + System.nanoTime());
        DataSource ds = PgJavaDataSource.forDatabase(db);

        // Pre-create the products table manually (simulate pre-existing schema)
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE products (id SERIAL PRIMARY KEY, name VARCHAR(100) NOT NULL, price NUMERIC(10,2) NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE)");
            s.execute("INSERT INTO products (name, price) VALUES ('Manual', 1.00)");
        }

        // baseline at V1 (marks V1 as already applied), then migrate V2
        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration/orm_smoke")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();

        MigrateResult result = flyway.migrate();
        // V2 (seed) runs; V1 is skipped (baselined)
        assertEquals(1, result.migrationsExecuted);
    }

    // =========================================================================
    // Hibernate
    // =========================================================================

    /**
     * Hibernate SessionFactory: create schema from entity, persist and retrieve.
     */
    @Test
    void hibernatePersistAndLoad() throws Exception {
        Database db = DatabaseRegistry.getOrCreate("orm_hibernate_" + System.nanoTime());

        var serviceRegistry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_DRIVER, "org.pgjava.jdbc.PgJavaDriver")
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL,
                        "jdbc:pgjava:mem:" + db.name())
                .applySetting(AvailableSettings.DIALECT,
                        "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                .applySetting(AvailableSettings.SHOW_SQL, "false")
                // Disable second-level cache for simplicity
                .applySetting("hibernate.cache.use_second_level_cache", "false")
                .build();

        try (SessionFactory sf = new MetadataSources(serviceRegistry)
                .addAnnotatedClass(OrderEntity.class)
                .buildMetadata()
                .buildSessionFactory()) {

            // Persist an entity
            Long generatedId;
            try (var session = sf.openSession()) {
                var tx = session.beginTransaction();
                var order = new OrderEntity();
                order.setCustomer("Alice");
                order.setTotal(java.math.BigDecimal.valueOf(99.95));
                session.persist(order);
                tx.commit();
                generatedId = order.getId();
            }

            assertNotNull(generatedId);

            // Load it back
            try (var session = sf.openSession()) {
                var loaded = session.find(OrderEntity.class, generatedId);
                assertNotNull(loaded);
                assertEquals("Alice", loaded.getCustomer());
                assertEquals(0, java.math.BigDecimal.valueOf(99.95).compareTo(loaded.getTotal()));
            }

            // Query via HQL
            try (var session = sf.openSession()) {
                var list = session.createQuery(
                        "FROM OrderEntity WHERE customer = :c", OrderEntity.class)
                        .setParameter("c", "Alice")
                        .list();
                assertEquals(1, list.size());
            }
        }
    }

    /**
     * Hibernate schema-validate: existing table matches entity definition.
     */
    @Test
    void hibernateSchemaValidate() throws Exception {
        Database db = DatabaseRegistry.getOrCreate("orm_hibernate_val_" + System.nanoTime());

        // First pass: create-drop to generate the schema
        var createRegistry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_DRIVER, "org.pgjava.jdbc.PgJavaDriver")
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL,
                        "jdbc:pgjava:mem:orm_hibernate_val_" + db.name())
                .applySetting(AvailableSettings.DIALECT, "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create")
                .applySetting("hibernate.cache.use_second_level_cache", "false")
                .build();

        try (SessionFactory ignored = new MetadataSources(createRegistry)
                .addAnnotatedClass(OrderEntity.class)
                .buildMetadata()
                .buildSessionFactory()) {
            // schema created; factory opened and closed to apply DDL
        }

        // Second pass: validate — should not throw
        var validateRegistry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_DRIVER, "org.pgjava.jdbc.PgJavaDriver")
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL,
                        "jdbc:pgjava:mem:orm_hibernate_val_" + db.name())
                .applySetting(AvailableSettings.DIALECT, "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "validate")
                .applySetting("hibernate.cache.use_second_level_cache", "false")
                .build();

        assertDoesNotThrow(() -> {
            try (SessionFactory sf = new MetadataSources(validateRegistry)
                    .addAnnotatedClass(OrderEntity.class)
                    .buildMetadata()
                    .buildSessionFactory()) {
                // no-op — success means schema is valid
            }
        });
    }

    // =========================================================================
    // Hibernate entity (nested class)
    // =========================================================================

    @Entity(name = "OrderEntity")
    @Table(name = "orders")
    static class OrderEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE,
                        generator = "orders_id_seq")
        @SequenceGenerator(name = "orders_id_seq",
                           sequenceName = "orders_id_seq",
                           allocationSize = 1)
        private Long id;

        @Column(nullable = false, length = 100)
        private String customer;

        @Column(nullable = false, precision = 10, scale = 2)
        private java.math.BigDecimal total;

        public Long getId()                                { return id; }
        public void setId(Long id)                        { this.id = id; }
        public String getCustomer()                       { return customer; }
        public void setCustomer(String customer)          { this.customer = customer; }
        public java.math.BigDecimal getTotal()            { return total; }
        public void setTotal(java.math.BigDecimal total)  { this.total = total; }
    }
}
