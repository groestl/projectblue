package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.pgjava.engine.Database;
import org.pgjava.types.PgTypeRegistry;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that user-defined types (enums, domains) are isolated per database
 * and do not leak into other databases or the JVM-global {@link PgTypeRegistry#INSTANCE}.
 *
 * <p>The bug: {@code DdlExecutor} used {@code PgTypeRegistry.INSTANCE} for type
 * registration, and {@code CatalogSerializer.deserializeType()} called
 * {@code PgTypeRegistry.INSTANCE.register(type)}.  Any enum or domain created in
 * one database was immediately visible in all other databases in the same JVM.
 *
 * <p>The fix: each {@link Database} owns a private {@link PgTypeRegistry} (obtained
 * via {@code PgTypeRegistry.newDatabase()}).  DDL and deserialization register types
 * only into that per-database instance.
 */
class TypeRegistryIsolationTest {

    @Test
    void enumCreatedInDb1IsNotVisibleInDb2() throws SQLException {
        Database db1 = Database.create("iso_db1");
        Database db2 = Database.create("iso_db2");

        try (var s1 = db1.openSession()) {
            s1.execute("CREATE TYPE mood AS ENUM ('happy', 'sad', 'neutral')");
        }

        // db2 must not know about 'mood'
        try (var s2 = db2.openSession()) {
            SQLException ex = assertThrows(SQLException.class,
                    () -> s2.execute("CREATE TABLE t (x mood)"),
                    "Expected error: type 'mood' does not exist in db2");
            // PG SQLSTATE 42704 = "undefined_object" (type not found)
            assertEquals("42704", ex.getSQLState(),
                    "Expected SQLSTATE 42704 (type does not exist), got: " + ex.getSQLState()
                            + " — " + ex.getMessage());
        }
    }

    @Test
    void globalInstanceNotPollutedByUserType() throws SQLException {
        Database db = Database.create("iso_pollution_db");

        try (var s = db.openSession()) {
            s.execute("CREATE TYPE season AS ENUM ('spring', 'summer', 'autumn', 'winter')");
        }

        // The JVM-global INSTANCE must not contain 'season'
        assertNull(PgTypeRegistry.INSTANCE.byName("season"),
                "Global INSTANCE must not be polluted by per-database enum 'season'");
    }

    @Test
    void sameSchemaSameNameEnumInTwoDatabasesAreIndependent() throws SQLException {
        Database db1 = Database.create("iso_same1");
        Database db2 = Database.create("iso_same2");

        // Both databases define an enum with the same name but different labels
        try (var s1 = db1.openSession()) {
            s1.execute("CREATE TYPE status AS ENUM ('active', 'inactive')");
            s1.execute("CREATE TABLE t (x status)");
            s1.execute("INSERT INTO t VALUES ('active')");
        }

        try (var s2 = db2.openSession()) {
            s2.execute("CREATE TYPE status AS ENUM ('pending', 'done')");
            s2.execute("CREATE TABLE t (x status)");
            s2.execute("INSERT INTO t VALUES ('pending')");
        }

        // Verify each database sees only its own values
        try (var s1 = db1.openSession()) {
            var res = s1.execute("SELECT x FROM t");
            assertEquals("active", res.rows().get(0)[0].toString());

            // 'pending' must not be a valid value in db1's status enum
            assertThrows(SQLException.class,
                    () -> s1.execute("INSERT INTO t VALUES ('pending')"),
                    "'pending' should be invalid in db1 which only has active/inactive");
        }

        try (var s2 = db2.openSession()) {
            var res = s2.execute("SELECT x FROM t");
            assertEquals("pending", res.rows().get(0)[0].toString());
        }
    }

    @Test
    void clonedDatabaseInheritsEnumTypesFromOriginal() throws SQLException {
        Database original = Database.create("iso_clone_orig");

        try (var s = original.openSession()) {
            s.execute("CREATE TYPE priority AS ENUM ('low', 'medium', 'high')");
        }

        // Clone the database — the clone must also know about 'priority'
        Database clone = original.clone("iso_clone_copy");

        try (var sc = clone.openSession()) {
            // Creating a table with the enum type proves the type is in the clone's registry
            assertDoesNotThrow(() -> sc.execute("CREATE TABLE tasks_clone (id INT, p priority)"),
                    "Clone should know about the 'priority' enum from the original database");

            // Inserting a valid enum value proves the clone validates against the correct type
            assertDoesNotThrow(() -> sc.execute("INSERT INTO tasks_clone VALUES (1, 'high')"));

            // Inserting an invalid enum value must still fail
            assertThrows(SQLException.class,
                    () -> sc.execute("INSERT INTO tasks_clone VALUES (2, 'critical')"),
                    "'critical' is not a valid priority label");

            // The clone's type is independent from the original — mutating one doesn't affect the other
            var res = sc.execute("SELECT count(*) FROM tasks_clone");
            assertEquals(1L, ((Number) res.rows().get(0)[0]).longValue());
        }
    }

    @Test
    void domainCreatedInDb1IsNotVisibleInDb2() throws SQLException {
        Database db1 = Database.create("iso_domain_db1");
        Database db2 = Database.create("iso_domain_db2");

        try (var s1 = db1.openSession()) {
            s1.execute("CREATE DOMAIN positive_int AS int CHECK (VALUE > 0)");
        }

        // db2 must not know about 'positive_int'
        try (var s2 = db2.openSession()) {
            assertThrows(SQLException.class,
                    () -> s2.execute("CREATE TABLE t (n positive_int)"),
                    "Expected error: domain 'positive_int' does not exist in db2");
        }
    }

    @Test
    void perDatabaseRegistryHasBuiltinTypes() {
        // Each per-database registry must still know all built-in types
        PgTypeRegistry reg = PgTypeRegistry.newDatabase();
        assertNotNull(reg.byName("int4"),   "int4 must be available in per-database registry");
        assertNotNull(reg.byName("text"),   "text must be available in per-database registry");
        assertNotNull(reg.byName("bool"),   "bool must be available in per-database registry");
        assertNotNull(reg.byName("uuid"),   "uuid must be available in per-database registry");
        assertNotNull(reg.byName("numeric"),"numeric must be available in per-database registry");
    }
}
