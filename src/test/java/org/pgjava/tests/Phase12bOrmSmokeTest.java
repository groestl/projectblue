package org.pgjava.tests;

import jakarta.persistence.*;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.*;
import org.junit.jupiter.api.Test;
import org.pgjava.engine.Database;
import org.pgjava.engine.DatabaseRegistry;
import org.pgjava.jdbc.PgJavaDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 12b — ORM Compatibility Smoke Tests (Part 2).
 *
 * <p>Exercises Liquibase, Spring Data JPA, and jOOQ codegen against pgjava.
 */
class Phase12bOrmSmokeTest {

    // =========================================================================
    // Liquibase
    // =========================================================================

    /**
     * Liquibase applies a 3-changeSet XML changelog: CREATE TABLE, INSERT rows,
     * ADD COLUMN + UPDATE.  Verifies all changesets succeed and data is correct.
     */
    @Test
    void liquibaseApplyChangelog() throws Exception {
        Database db = DatabaseRegistry.getOrCreate("liq_" + System.nanoTime());
        DataSource ds = PgJavaDataSource.forDatabase(db);

        try (Connection c = ds.getConnection()) {
            liquibase.database.Database liqDb = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(c));

            try (Liquibase liq = new Liquibase(
                    "db/migration/liquibase/db.changelog-master.xml",
                    new ClassLoaderResourceAccessor(),
                    liqDb)) {
                liq.update("");  // apply all changesets
            }
        }

        // Verify rows were inserted
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM customers")) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
        }

        // Verify ADD COLUMN + UPDATE (changeSet 3)
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT score FROM customers WHERE name = 'Alice'")) {
            assertTrue(rs.next());
            assertEquals(10, rs.getInt(1));
        }

        // Verify other rows got default score=0
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM customers WHERE score = 0")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        }
    }

    /**
     * Liquibase idempotency: running update() twice does not re-apply changesets.
     */
    @Test
    void liquibaseIdempotent() throws Exception {
        Database db = DatabaseRegistry.getOrCreate("liq_idem_" + System.nanoTime());
        DataSource ds = PgJavaDataSource.forDatabase(db);

        // Apply twice
        for (int i = 0; i < 2; i++) {
            try (Connection c = ds.getConnection()) {
                liquibase.database.Database liqDb = DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(new JdbcConnection(c));
                try (Liquibase liq = new Liquibase(
                        "db/migration/liquibase/db.changelog-master.xml",
                        new ClassLoaderResourceAccessor(),
                        liqDb)) {
                    liq.update("");
                }
            }
        }

        // Still 3 rows — changesets not re-applied
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM customers")) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
        }
    }

    // =========================================================================
    // Spring Data JPA
    // =========================================================================

    /**
     * Thread-local DataSource for the Spring context (avoids static mutable state
     * if tests ever run in parallel).
     */
    private static final ThreadLocal<DataSource> SDJ_DS = new ThreadLocal<>();

    /**
     * Spring Data JPA: auto-DDL creates table, repository CRUD + derived query work.
     *
     * <p>Uses {@link JpaRepositoryFactory} directly rather than classpath-scanning via
     * {@code @EnableJpaRepositories} — Spring Data skips nested interfaces at scan time
     * ({@code RepositoryComponentProvider.isCandidateComponent} requires top-level types).
     * Direct factory construction is equivalent for a smoke test.
     */
    @Test
    void springDataJpaRepository() throws Exception {
        Database db = DatabaseRegistry.getOrCreate("sdj_" + System.nanoTime());
        SDJ_DS.set(PgJavaDataSource.forDatabase(db));

        try (var ctx = new AnnotationConfigApplicationContext(SdjConfig.class)) {
            var emf   = ctx.getBean(jakarta.persistence.EntityManagerFactory.class);
            var txMgr = ctx.getBean(JpaTransactionManager.class);
            var tt    = new TransactionTemplate(txMgr);

            // Shared proxy EntityManager — participates in TransactionTemplate transactions
            var sharedEm = SharedEntityManagerCreator.createSharedEntityManager(emf);
            var repoFactory = new JpaRepositoryFactory(sharedEm);
            SdjItemRepository repo = repoFactory.getRepository(SdjItemRepository.class);

            // Save two items
            Long id1 = tt.execute(s -> repo.save(new SdjItem("Widget", 10)).getId());
            Long id2 = tt.execute(s -> repo.save(new SdjItem("Gadget", 5)).getId());
            assertNotNull(id1, "id must be generated after save");
            assertNotNull(id2);

            // FindById
            Optional<SdjItem> found = tt.execute(s -> repo.findById(id1));
            assertTrue(found.isPresent());
            assertEquals("Widget", found.get().getName());
            assertEquals(10, found.get().getQuantity());

            // FindAll
            List<SdjItem> all = tt.execute(s -> repo.findAll());
            assertEquals(2, all.size());

            // Derived query
            List<SdjItem> widgets = tt.execute(s -> repo.findByName("Widget"));
            assertEquals(1, widgets.size());

            // Update via save (merge)
            tt.execute(s -> {
                SdjItem item = repo.findById(id1).orElseThrow();
                item.setQuantity(99);
                return repo.save(item);
            });
            Integer qty = tt.execute(s -> repo.findById(id1).orElseThrow().getQuantity());
            assertEquals(99, qty);

            // Delete
            tt.execute(s -> { repo.deleteById(id2); return null; });
            long cnt = tt.execute(s -> repo.count());
            assertEquals(1L, cnt);
        } finally {
            SDJ_DS.remove();
        }
    }

    // -------------------------------------------------------------------------
    // Spring Data JPA supporting types (entity, repository, config)
    // -------------------------------------------------------------------------

    @Entity(name = "SdjItem")
    @Table(name = "sdj_items")
    static class SdjItem {

        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sdj_items_seq")
        @SequenceGenerator(name = "sdj_items_seq", sequenceName = "sdj_items_id_seq", allocationSize = 1)
        private Long id;

        @Column(nullable = false, length = 100)
        private String name;

        @Column(nullable = false)
        private Integer quantity;

        protected SdjItem() {}

        SdjItem(String name, int quantity) {
            this.name = name;
            this.quantity = quantity;
        }

        public Long    getId()           { return id; }
        public void    setId(Long id)    { this.id = id; }
        public String  getName()         { return name; }
        public void    setName(String n) { this.name = n; }
        public Integer getQuantity()     { return quantity; }
        public void    setQuantity(int q){ this.quantity = q; }
    }

    interface SdjItemRepository extends JpaRepository<SdjItem, Long> {
        List<SdjItem> findByName(String name);
    }

    @Configuration
    @EnableTransactionManagement
    static class SdjConfig {

        @Bean
        DataSource dataSource() {
            return SDJ_DS.get();
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            // Only register SdjItem — avoid collisions with OrderEntity in other test classes
            factory.setPackagesToScan("__none__");  // disable scanning
            factory.setPersistenceUnitPostProcessors(pui ->
                    pui.addManagedClassName(SdjItem.class.getName()));
            var adapter = new HibernateJpaVendorAdapter();
            adapter.setGenerateDdl(true);
            factory.setJpaVendorAdapter(adapter);
            var props = new Properties();
            props.setProperty("hibernate.hbm2ddl.auto",          "create-drop");
            props.setProperty("hibernate.dialect",                "org.hibernate.dialect.PostgreSQLDialect");
            props.setProperty("hibernate.cache.use_second_level_cache", "false");
            props.setProperty("hibernate.show_sql",               "false");
            factory.setJpaProperties(props);
            return factory;
        }

        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }
    }

    // =========================================================================
    // jOOQ codegen
    // =========================================================================

    /**
     * jOOQ code generation: creates a schema in pgjava, runs GenerationTool with
     * JDBCDatabase (uses standard DatabaseMetaData), verifies Java sources are
     * produced for the expected tables.
     */
    @Test
    void jooqCodegenGeneratesClasses() throws Exception {
        Database db = DatabaseRegistry.getOrCreate("jooq_" + System.nanoTime());
        DataSource ds = PgJavaDataSource.forDatabase(db);

        // Create a simple schema for codegen to introspect
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE jooq_products (
                    id      BIGSERIAL PRIMARY KEY,
                    name    VARCHAR(100) NOT NULL,
                    price   NUMERIC(10,2) NOT NULL,
                    active  BOOLEAN NOT NULL DEFAULT TRUE
                )
                """);
            s.execute("""
                CREATE TABLE jooq_categories (
                    id    SERIAL PRIMARY KEY,
                    label VARCHAR(50) NOT NULL UNIQUE
                )
                """);
        }

        // Use the same JDBC URL that was just used (extract from DataSource)
        String url = "jdbc:pgjava:mem:" + db.name();

        // Run codegen to a temp directory
        Path outDir = Files.createTempDirectory("jooq-gen-");
        try {
            var cfg = new org.jooq.meta.jaxb.Configuration()
                .withJdbc(new Jdbc()
                    .withDriver("org.pgjava.jdbc.PgJavaDriver")
                    .withUrl(url)
                    .withUser("")
                    .withPassword(""))
                .withGenerator(new Generator()
                    .withName("org.jooq.codegen.JavaGenerator")
                    .withDatabase(new org.jooq.meta.jaxb.Database()
                        .withName("org.jooq.meta.jdbc.JDBCDatabase")
                        .withIncludes("jooq_.*")
                        .withExcludes("")
                        .withInputSchema("public"))
                    .withGenerate(new Generate()
                        .withPojos(true)
                        .withDaos(false))
                    .withTarget(new Target()
                        .withPackageName("org.pgjava.jooqtest.generated")
                        .withDirectory(outDir.toString())));

            GenerationTool.generate(cfg);

            // Verify core generated artifacts exist
            Path genRoot = outDir.resolve("org/pgjava/jooqtest/generated");
            assertTrue(Files.exists(genRoot), "generated package directory must exist");

            // Tables directory should contain a class per table
            Path tablesDir = genRoot.resolve("tables");
            assertTrue(Files.exists(tablesDir), "tables/ directory must be generated");

            // At least the two tables we created
            assertTrue(
                Files.list(tablesDir).anyMatch(p -> p.getFileName().toString()
                        .equalsIgnoreCase("JooqProducts.java")),
                "JooqProducts.java must be generated");
            assertTrue(
                Files.list(tablesDir).anyMatch(p -> p.getFileName().toString()
                        .equalsIgnoreCase("JooqCategories.java")),
                "JooqCategories.java must be generated");
        } finally {
            // Clean up temp directory
            deleteRecursively(outDir);
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        }
    }
}
