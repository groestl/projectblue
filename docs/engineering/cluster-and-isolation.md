# Engineering Plan: Multi-Cluster, Instance Configuration, and Test Isolation

## Background

The current implementation has three gaps that interact badly at scale:

1. **No instance configuration API.** `Database` is always created in-memory with no WAL
   persistence, regardless of what the JDBC URL requests. Server exposure (wire protocol)
   cannot be configured at all.

2. **Single flat namespace.** `DatabaseRegistry` is a JVM-global static map. There is no
   concept of a cluster — multiple independent PostgreSQL-like server instances in the same
   JVM are not supported.

3. **Test isolation does not scale.** The current pattern (one `Database` per test, named
   with `System.nanoTime()`) leaks objects for the lifetime of the JVM, creates one full
   catalog+storage+WAL stack per test class, and breaks down under massively parallel
   execution.

---

## Goals

- Multiple independent clusters in one JVM, each with its own databases, lifecycle, and
  optionally a wire protocol server on a distinct port.
- Instance configuration (persistence, server port) is decoupled from JDBC URLs. A cluster
  can be fully configured and started before any JDBC connection is made.
- JDBC URLs remain simple locators (`jdbc:pgjava:mem:dbname`) and resolve against a
  registered cluster by name.
- Schema-level isolation for parallel integration tests: one shared database per suite,
  one schema per test class, zero cross-contamination, full parallel safety.
- `CatalogManager` and related structures are safe for concurrent DDL from multiple threads.

## Design Decisions

These are explicit choices made during design, recorded with their rationale so future
contributors understand why the design is the way it is and can judge edge cases against
the same reasoning.

**1. URLs are locators, not configuration documents.**
`jdbc:pgjava:mem:mydb?wal=true` looks like configuration but is not — the URL is parsed
at connect time by the driver, which has no way to enforce that all callers use the same
parameters for the same database name. Configuration belongs at cluster/database
construction time, not in the URL. The URL is just a lookup key.
*Consequence:* `?wal=true` and `file:` mode in URLs are removed. Persistence is
configured on the `DatabaseConfig`, not in the JDBC URL.

**2. Instance configuration is independent of JDBC.**
A cluster can be started, a server exposed, and WAL persistence enabled with no JDBC
involved at all. JDBC is one consumer of an already-running cluster. This allows
non-JDBC use cases (wire protocol only, programmatic API, test utilities) to work without
pretending to be a JDBC datasource.
*Consequence:* `PgJavaCluster` and `ClusterRegistry` are the primary API. `PgJavaDriver`
is a thin adapter on top.

**3. Test infrastructure uses pgjava types, but only in one place per project.**
Complete opacity (hiding all pgjava types from test code) is over-engineering. It adds
abstraction layers that make the setup harder to understand and debug, and forces the
design to anticipate every framework integration upfront. The meaningful constraint is
*centralization* — pgjava types live in one support class per project, not scattered
across every test class.
*Consequence:* `PgTestExtension` is thin and framework-agnostic. Projects write one
`TestDatabase.java` that imports `org.pgjava.*`. All other test classes use plain
`DataSource`.

**4. No Spring Boot dependency in pgjava.**
A hard Spring Boot dependency would make pgjava unusable in non-Spring projects
(plain JDBC apps, Micronaut, Quarkus, command-line tools). Spring integration patterns
(`@DynamicPropertySource`, `@TestConfiguration`) are standard Spring mechanisms that
work without pgjava knowing about them. The cost of not having this dependency is that
each project writes ~10 lines of Spring wiring once; the benefit is that pgjava stays
usable everywhere.
*Consequence:* pgjava ships no Spring annotations, no `@AutoConfiguration`, no
`spring.factories`. Spring integration examples live in documentation, not in the JAR.

**5. The engine exposes a non-pooling DataSource; pooling is the caller's concern.**
Connection pooling (HikariCP, c3p0, DBCP) involves sizing, eviction, validation, and
metrics — decisions that belong to the application, not the database engine. A
`PgJavaConnection` is cheap to create (object allocation, no network), so a non-pooling
raw `DataSource` is correct and sufficient as the engine's output. Callers wrap it in
whatever pool they prefer.
*Consequence:* `Database.rawDataSource()` returns a non-pooling source.
`SchemaHandle.dataSource()` also returns non-pooling — wrap it if needed.
`SuiteClusterHolder` (in the test extension) wraps it in HikariCP for the shared
suite-level pool; this is the one place in pgjava's own infrastructure that owns a pool,
and it is in the test artifact, not the engine.

**6. `ExternalSchemaHandle` enables real-PostgreSQL switching without framework coupling.**
The ability to run the test suite against real PostgreSQL (for CI validation, dialect
conformance checks) must not require changes to test code or framework wiring. The
switching mechanism is a single env var; the mechanism works because `ExternalSchemaHandle`
mirrors the `SchemaHandle` interface using plain JDBC `CREATE/DROP SCHEMA`. pgjava
provides this utility so projects do not have to write it themselves, but it has no
dependency on the pgjava engine — it works against any PostgreSQL-compatible database.
*Consequence:* `ExternalSchemaHandle` lives in the `pgjava-test` artifact alongside
`PgTestExtension`, not in the core engine JAR.

---

## Non-Goals

- Query-level read isolation (MVCC, snapshot isolation). Read-committed serialization via
  table-level write locks is sufficient for the current correctness target.
- Dynamic cluster reconfiguration after `start()` (port changes, enabling WAL on a running
  instance, etc.).
- Cluster replication or logical streaming.

---

## Architecture Overview

```
ClusterRegistry  (JVM-global, ConcurrentHashMap<String, PgJavaCluster>)
  │
  ├── PgJavaCluster "prod"  (port=5432, dataDir=/var/pgjava/prod)
  │     ├── Database "appdb"
  │     └── Database "analyticsdb"
  │
  ├── PgJavaCluster "test"  (port=5433, in-memory)
  │     └── Database "testdb"
  │           ├── Schema "public"
  │           ├── Schema "test_3f8a..."   ← test class A
  │           └── Schema "test_c2d1..."   ← test class B
  │
  └── PgJavaCluster "default"  (no port, in-memory)
        └── Database "mydb"   ← backward-compat for jdbc:pgjava:mem:mydb
```

**Key principle:** A cluster is the unit of lifecycle and server exposure.
A database is the unit of catalog and storage.
A schema is the unit of test isolation.

---

## Component Design

### `ClusterConfig`

Immutable value object. All fields optional with sensible defaults.

```java
public record ClusterConfig(
    String  name,           // required; used as registry key
    int     port,           // 0 = no wire server
    Path    dataDirectory,  // null = in-memory
    boolean dropOnStop      // true = remove from ClusterRegistry on stop() — for test clusters
) {
    public static Builder builder(String name) { ... }
}
```

### `PgJavaCluster`

Lifecycle-managed container. Owns a `DatabaseRegistry` scoped to this cluster (not
JVM-global). Optionally owns a wire protocol server.

```java
public final class PgJavaCluster {

    // Factory
    public static PgJavaCluster create(ClusterConfig config);

    // Lifecycle
    public PgJavaCluster start();   // synchronous; blocks until ready (WAL replayed, server bound)
    public void          stop();    // drains connections, flushes WAL, unbinds port, optionally drops from registry

    // Database access
    public Database      database(String name);           // getOrCreate
    public Database      database(String name, DatabaseConfig config);  // configured getOrCreate
    public void          dropDatabase(String name);

    // Introspection
    public String        name();
    public ClusterConfig config();
    public boolean       isRunning();
}
```

### `ClusterRegistry`

Replaces the current `DatabaseRegistry` at the JVM-global level. Clusters are registered
here; databases live inside clusters.

```java
public final class ClusterRegistry {

    public static PgJavaCluster register(ClusterConfig config);  // creates + registers
    public static PgJavaCluster get(String name);                // null if not found
    public static PgJavaCluster getOrDefault();                  // the "default" cluster
    public static void          deregister(String name);

    // Default cluster is created lazily on first JDBC connect to any url
    // that does not specify a cluster name. Always in-memory, no server.
}
```

### `DatabaseConfig`

```java
public record DatabaseConfig(
    String  name,
    Path    walPath,      // null = in-memory WAL (buffered, discarded on stop)
    boolean recoveryMode  // true = replay WAL on start before accepting connections
) {}
```

### `SchemaHandle`

Returned by `Database.allocateSchema()`. Owns one schema in the database and exposes a
`DataSource` that scopes every borrowed connection to that schema via `SET search_path`.

```java
public final class SchemaHandle implements AutoCloseable {

    public String     schemaName();
    public DataSource dataSource();   // connections automatically SET search_path on borrow
    public void       drop();         // DROP SCHEMA <name> CASCADE + release
    public void       close();        // alias for drop() — supports try-with-resources
}
```

The `DataSource` returned by `SchemaHandle` wraps the cluster's shared per-database
connection pool. It intercepts `getConnection()` and executes
`SET search_path = <schema>, public` before returning the connection. The underlying pool
is shared — only the search_path injection is per-handle.

---

## JDBC URL Routing

Current URL format is preserved for backward compatibility:

```
jdbc:pgjava:mem:<dbname>          →  default cluster, database <dbname>
jdbc:pgjava:file:<path>           →  default cluster, database at <path>  [future]
```

New cluster-scoped format:

```
jdbc:pgjava://<cluster-name>/<dbname>
```

Examples:
```
jdbc:pgjava://test/testdb         →  cluster "test", database "testdb"
jdbc:pgjava://prod/appdb          →  cluster "prod", database "appdb"
```

The driver resolves `cluster-name` via `ClusterRegistry.get(name)`. If the cluster does
not exist at connect time, a `SQLException` is thrown (not silently created) — unlike the
current behavior which creates a database on demand. Pre-configuration is required for
named clusters.

Backward compatibility: `jdbc:pgjava:mem:<dbname>` routes to the default cluster, which
is lazily created in-memory on first use. Existing tests and configurations continue to
work with zero changes.

---

## Thread Safety Requirements

The current `CatalogManager` uses `ArrayList` for schema and table collections. This is
unsafe under concurrent DDL from parallel test threads. The following must be addressed
before schema-per-test isolation can be used safely:

| Component | Current | Required |
|---|---|---|
| `ClusterRegistry` map | `ConcurrentHashMap` ✅ | — |
| `DatabaseRegistry` within cluster | `ConcurrentHashMap` ✅ | — |
| `CatalogManager.schemas` | `HashMap` ❌ | `ConcurrentHashMap` |
| `Schema.tables` | `ArrayList` ❌ | `ConcurrentHashMap` or guarded |
| `Schema.indexes` | `ArrayList` ❌ | `ConcurrentHashMap` or guarded |
| `Schema.sequences` | `ArrayList` ❌ | `ConcurrentHashMap` or guarded |
| `Schema.views` | `ArrayList` ❌ | `ConcurrentHashMap` or guarded |
| `TableDef.columns` | `ArrayList` (append-only after create) | `synchronized` on DDL |
| `TableDef.constraints` | `ArrayList` ❌ | `synchronized` on DDL |
| `HeapTable.rows` | `CopyOnWriteArrayList` ✅ | — |
| `HeapTable.tombstones` | `ConcurrentHashMap` set ✅ | — |
| `BTreeIndex.tree` | `ConcurrentSkipListMap` ✅ | — |

The fix for `Schema` collections is to replace `ArrayList` with `ConcurrentHashMap` keyed
by name, which also eliminates O(n) name lookups. `TableDef.columns` and `.constraints`
are effectively append-only after table creation and can be guarded with `synchronized`
on the append path only.

**DDL vs DML concurrency:** DDL (`CREATE TABLE`, `DROP TABLE`) must not run concurrently
with DML against the same table. This is acceptable to enforce with a per-table read-write
lock: DML acquires read lock, DDL acquires write lock. For the test use case (each schema
is created before any DML runs in that schema), this is never contended.

---

## Test Infrastructure Integration

See the **Test Extension Design** section below for the full design. In summary:

- Tests are annotated `@PgTest` and receive a plain `DataSource` via `@PgDataSource`
- No pgjava-specific types appear in test code
- Schema lifecycle (allocate, migrate, drop) is handled entirely by `PgTestExtension`
- Switching to real PostgreSQL is a single environment variable — no test changes

### Schema naming

`allocateSchema()` generates names of the form `test_<8-hex-chars>` (random UUID prefix).
Collision probability is negligible. The handle retains the name for cleanup.

### Connection pool sharing

The pool is owned by `SuiteClusterHolder` — one pool per suite run, shared across all
test classes. Each `SchemaHandle` injects `SET search_path` on connection borrow but
does not hold the connection — it is returned immediately after the statement completes.
No per-schema connection starvation.

---

## Startup and Shutdown Ordering

### Start sequence (within `PgJavaCluster.start()`)

1. Validate config (port not already bound, dataDir accessible if set)
2. Initialize `DatabaseRegistry` for this cluster
3. For each pre-configured database: initialize `CatalogManager` + `HeapStorage` + `WalWriter`
4. If `recoveryMode`: replay WAL — synchronous, blocks until complete
5. If `port > 0`: bind TCP socket, start Netty pipeline — synchronous, blocks until port is bound
6. Register in `ClusterRegistry`
7. Return

`start()` does not return until the cluster is fully ready. Callers (Spring `@Bean`,
test `@BeforeAll`) can connect immediately after.

### Stop sequence (within `PgJavaCluster.stop()`)

1. Stop accepting new connections (wire server: stop accepting; JDBC: mark unavailable)
2. Wait for in-flight transactions to complete (configurable drain timeout, default 5s)
3. Rollback any transactions that did not complete within timeout
4. Flush WAL to disk (if persistent)
5. Unbind port (if server was running)
6. Deregister from `ClusterRegistry` (if `dropOnStop`)
7. Release all resources

### Spring bean ordering

```java
@Bean(destroyMethod = "stop")
PgJavaCluster testCluster() {
    return PgJavaCluster.create(...).start();
}

@Bean(destroyMethod = "close")
@DependsOn("testCluster")
DataSource dataSource(PgJavaCluster cluster) {
    return cluster.database("testdb").allocateSchema().dataSource();
}
```

Spring destroys in reverse dependency order: `dataSource` closes first (pool drains),
then `testCluster.stop()` flushes WAL. Without `@DependsOn` the order is undefined and
WAL data can be lost.

---

## Migration from Current Pattern

The current `DatabaseRegistry.getOrCreate(name)` static API is preserved as a delegation
to the default cluster:

```java
// Before (still works)
DatabaseRegistry.getOrCreate("mydb");

// After (delegates to)
ClusterRegistry.getOrDefault().database("mydb");
```

`Session.java` and `PgJavaDriver.java` need no changes for the default-cluster path.
Named-cluster JDBC URLs (`jdbc:pgjava://cluster/db`) require a one-line addition to
`JdbcUrl.parse()` and a routing branch in `PgJavaDriver.connect()`.

Existing tests using `DatabaseRegistry.getOrCreate("phase9_" + nanoTime())` continue to
work unchanged. They will accumulate in the default cluster's registry for the JVM
lifetime (current behavior). Tests that care about cleanup should migrate to
`SchemaHandle`.

---

## Implementation Phases

### Phase A — Core cluster abstraction (no wire server, no persistence)

- `ClusterConfig`, `DatabaseConfig` records
- `PgJavaCluster` with in-memory lifecycle (`start`/`stop`)
- `ClusterRegistry` replacing JVM-global `DatabaseRegistry`
- `CatalogManager` thread-safety fixes (concurrent maps throughout)
- `JdbcUrl` extended with cluster-scoped format
- `PgJavaDriver.connect()` updated for cluster routing
- Backward compatibility: default cluster, `jdbc:pgjava:mem:name` unchanged

### Phase B — Schema isolation for tests

- `SchemaHandle` + `allocateSchema()` on `Database`
- `SchemaIsolatedDataSource` wrapper (injects `SET search_path` on borrow)
- `Database.allocateSchema()` / `SchemaHandle.drop()`
- Thread-safety validation: parallel `CREATE SCHEMA` + DDL in isolated schemas

### Phase C — Instance configuration wired through

- `DatabaseConfig.walPath` honoured in `Database` constructor
- `WalWriter(Path)` constructor used when `walPath` is set
- WAL recovery on startup when `recoveryMode = true`
- `file:` JDBC URL mode wired to a persistent `DatabaseConfig`

### Phase D — Wire protocol server (depends on Phase 1 implementation)

- `ClusterConfig.port > 0` starts Netty TCP server scoped to the cluster's databases
- Server exposes all databases in the cluster (same as PostgreSQL: one port, N databases)
- `PgJavaCluster.stop()` shuts down server before flushing WAL

---

## Test Extension Design

### Goal

Tests should not have to manage database lifecycle or schema isolation themselves.
The pgjava-specific setup is **centralized in one place** — a base test class, a shared
extension config, or a project-level test utility — and individual tests remain agnostic
to it. Complete opacity is not a goal and not necessary; what matters is that the
pgjava surface area does not bleed into every test class.

pgjava has **no Spring Boot dependency**. Spring integration is the caller's
responsibility, using standard Spring mechanisms (e.g. `@DynamicPropertySource`). pgjava
provides the building blocks; it does not wire them into any framework.

### Typical project structure

```
src/test/java/
  com/example/
    support/
      TestDatabase.java      ← owns PgJavaCluster + pool lifecycle; pgjava types here
    OrderServiceTest.java    ← receives DataSource; no pgjava imports
    UserRepositoryTest.java  ← same
```

pgjava types appear in `support/` only. All other test classes use plain `DataSource`
and plain JDBC URLs.

### `TestDatabase` — project-level test utility

Each project writes this once. It is the single place that knows about pgjava.

```java
public final class TestDatabase {

    private static final PgJavaCluster CLUSTER = PgJavaCluster
        .create(ClusterConfig.builder("test-suite")
            .dropOnStop(true)
            .build())
        .start();

    // Shared non-pooling DataSource from the engine.
    // Wrap in your preferred pool (HikariCP, c3p0, etc.) above this layer.
    public static final DataSource RAW = CLUSTER
        .database("default")
        .rawDataSource();

    // Allocate a schema isolated to one test class. Call drop() in @AfterAll.
    public static SchemaHandle allocateSchema() {
        return CLUSTER.database("default").allocateSchema();
    }

    // For switching to real PostgreSQL: honour an env var, return a plain DataSource.
    // pgjava is not involved in the external path at all.
    public static DataSource resolve() {
        String url = System.getenv("TEST_PG_URL");
        return url != null
            ? externalDataSource(url)   // plain JDBC, no pgjava
            : RAW;
    }
}
```

### `PgTestExtension` — JUnit 5 extension (pgjava-provided)

pgjava ships `PgTestExtension` as a convenience. It is thin — it calls
`TestDatabase.allocateSchema()` and injects the resulting `DataSource` into the test
class. Projects that prefer a different setup style do not have to use it.

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(PgTestExtension.class)
public @interface PgTest {
    String database() default "default";
}
```

`PgTestExtension` implements `BeforeAllCallback` / `AfterAllCallback`:

1. `beforeAll`: call `cluster.database(name).allocateSchema()`, store `SchemaHandle`
   in JUnit extension store, inject `DataSource` into fields annotated `@PgDataSource`
2. `afterAll`: call `schemaHandle.drop()`

The extension does not know about Spring, HikariCP, Flyway, or any other framework. It
allocates a schema and provides a `DataSource`. Everything else is the project's concern.

### `SchemaHandle` — what the extension provides

```java
public final class SchemaHandle implements AutoCloseable {
    public String     schemaName();          // "test_3f8a1c..."
    public DataSource dataSource();          // non-pooling; wrap if needed
    public String     jdbcUrl();             // "jdbc:pgjava://test-suite/default?schema=test_3f8a1c..."
    public void       drop();                // DROP SCHEMA CASCADE
    public void       close();              // alias for drop()
}
```

`dataSource()` injects `SET search_path = <schema>, public` on every connection borrow.
It is non-pooling. If the project needs a pool (e.g. for connection validation in Spring
auto-config), wrap it:

```java
HikariConfig cfg = new HikariConfig();
cfg.setDataSource(schemaHandle.dataSource());
cfg.setMaximumPoolSize(10);
DataSource pooled = new HikariDataSource(cfg);
```

### Switching to real PostgreSQL

pgjava provides a `ExternalSchemaHandle` that mirrors `SchemaHandle` against a real
PostgreSQL connection — `CREATE SCHEMA` on construct, `DROP SCHEMA CASCADE` on close.

```java
// Project-level, in TestDatabase.java:
public static SchemaHandle allocateSchema() {
    String url = System.getenv("TEST_PG_URL");
    if (url != null) {
        return ExternalSchemaHandle.allocate(url);  // CREATE SCHEMA test_<uuid> on real PG
    }
    return CLUSTER.database("default").allocateSchema();
}
```

`ExternalSchemaHandle` is a pgjava utility class that wraps a plain JDBC connection and
issues `CREATE SCHEMA` / `DROP SCHEMA`. It has no dependency on the pgjava engine — it
works against any PostgreSQL-compatible database.

With this in place, `TEST_PG_URL=jdbc:postgresql://localhost/testdb ./gradlew test`
switches the entire suite to real PostgreSQL. Tests do not change. Flyway/Liquibase
migrations run identically.

### Spring integration — caller's responsibility

pgjava does not integrate with Spring. Projects that use Spring wire things up
themselves using standard Spring mechanisms:

```java
// In the project's test support, not in pgjava:
@TestConfiguration
class TestDatabaseConfig {

    @Bean
    SchemaHandle schemaHandle() {
        return TestDatabase.allocateSchema();   // pgjava call, centralized here
    }

    @Bean
    @Primary
    DataSource dataSource(SchemaHandle schema) {
        HikariConfig cfg = new HikariConfig();
        cfg.setDataSource(schema.dataSource());
        return new HikariDataSource(cfg);
    }

    @PreDestroy
    void cleanup(SchemaHandle schema) {
        schema.drop();
    }
}
```

Or with `@DynamicPropertySource` for `@SpringBootTest`:

```java
@SpringBootTest
class OrderServiceTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        SchemaHandle schema = TestDatabase.allocateSchema();
        r.add("spring.datasource.url", schema::jdbcUrl);
        r.add("spring.datasource.driver-class-name", () -> "org.pgjava.jdbc.PgJavaDriver");
        // schema.drop() called in @AfterAll
    }
}
```

pgjava provides `SchemaHandle.jdbcUrl()` so this pattern works. The Spring wiring is
entirely in the project — pgjava ships none of it.

---

## Database Cloning

Database cloning creates an independent, in-memory deep copy of an existing database.
The clone shares no mutable state with the source — mutations in either direction are
invisible to the other.

### When to use cloning vs schema isolation

| Approach | Use when | Trade-off |
|---|---|---|
| **Schema isolation** (`allocateSchema`) | Tests need only their own tables, no shared state | Cheapest; no data copy. Schemas share one OID space, one catalog, one storage |
| **Database cloning** (`cloneDatabase`) | Tests need an independent copy of a fully migrated database — different DDL per test, conflicting schema changes, or isolation of sequence state | More expensive (deep copies all heap data), but each clone is a complete database |

Schema isolation is sufficient for most test suites. Database cloning is for cases where
each test needs to start from the same migrated state but may then diverge structurally
(e.g. testing migration rollbacks, schema evolution, or sequences that must not collide).

### API

```java
// Cluster-level: clone "template" into a new database "test_1"
Database test1 = cluster.cloneDatabase("template", "test_1");

// Direct on Database object
Database clone = sourceDb.clone("myClone");
```

`cloneDatabase` is atomic and thread-safe — two concurrent calls with the same target
name will not produce duplicates (one succeeds, the other throws `IllegalArgumentException`).

### What is copied

| Component | Copy strategy |
|---|---|
| **Schemas** (tables, indexes, sequences, views, types) | Deep copy. Each schema's mutable objects (TableDef, SequenceDef, ViewDef) are cloned. Immutable records (IndexDef, ColumnDef, Constraint, TriggerDef, PgType) are shared safely |
| **Heap data** (rows + tombstones) | Deep copy. Each row's `Object[]` is copied via `Arrays.copyOf`. Tombstone map is duplicated |
| **B-tree indexes** | Deep copy. New `ConcurrentSkipListMap` with the same comparator; RowId lists are independent |
| **Sequences** | Deep copy. Current position is preserved; `lastValue` (session-scoped) is reset to null |
| **OID generator** | Clone starts from source's current OID value — no collisions with existing objects |
| **User-defined functions** | Copied (identified by non-null `source` field) |
| **Built-in functions** | Re-registered fresh by the CatalogManager constructor |
| **Sequence/catalog functions** (nextval, pg_get_*) | Re-registered fresh — closures capture the *new* CatalogManager, not the source |
| **TransactionManager, WalWriter** | Fresh instances (no session state carries over) |
| **NotificationBus, AdvisoryLockManager** | Fresh instances |
| **Virtual tables** (pg_catalog, information_schema) | Re-registered by constructor; `pg_locks` is wired to the clone's storage |

### What is NOT copied

- **Active transactions** — cloning should only be done when the source has no in-flight
  transactions. A clone captured mid-transaction may have partially-committed state.
- **Persistence** — the clone is always in-memory, even if the source is persistent.
- **Wire protocol sessions** — each clone gets its own `Session` objects.

### Typical test pattern

```java
// In @BeforeAll or test suite setup:
PgJavaCluster cluster = PgJavaCluster.create(
    ClusterConfig.builder("test").dropOnStop(true).build()
).start();

// Run migrations once against a template database
Database template = cluster.database("template");
try (Connection c = template.openSession().connection()) {
    Flyway.configure().dataSource(c).load().migrate();
}

// In each test class:
@BeforeEach
void setup() {
    Database testDb = cluster.cloneDatabase("template", "test_" + UUID.randomUUID());
    this.dataSource = new PgJavaDataSource(testDb);
}

@AfterEach
void teardown() {
    cluster.dropDatabase(testDb.name());
}
```

### JUnit 5 integration pattern

```java
// Project-level support class (one place with pgjava imports)
public final class TestDatabase {
    private static final PgJavaCluster CLUSTER = PgJavaCluster.create(
        ClusterConfig.builder("test-suite").dropOnStop(true).build()
    ).start();

    static {
        // Run migrations once
        Database template = CLUSTER.database("template");
        // ... apply Flyway/Liquibase against template ...
    }

    public static Database freshDatabase() {
        return CLUSTER.cloneDatabase("template",
            "test_" + Long.toHexString(System.nanoTime()));
    }

    public static void drop(Database db) {
        CLUSTER.dropDatabase(db.name());
    }
}
```

### Spring Boot integration pattern

```java
@TestConfiguration
class TestDatabaseConfig {

    @Bean(destroyMethod = "stop")
    PgJavaCluster cluster() {
        PgJavaCluster c = PgJavaCluster.create(
            ClusterConfig.builder("spring-test").dropOnStop(true).build()
        ).start();
        // Run migrations once against template
        Database template = c.database("template");
        Flyway.configure()
            .dataSource(new PgJavaDataSource(template))
            .load().migrate();
        return c;
    }

    @Bean
    @Primary
    DataSource dataSource(PgJavaCluster cluster) {
        Database clone = cluster.cloneDatabase("template",
            "test_" + Long.toHexString(System.nanoTime()));
        return new PgJavaDataSource(clone);
    }
}
```

### Design decisions

**1. Cloning uses constructor re-registration, not function copying.**
Sequence functions (`nextval`, `currval`, `setval`) and catalog functions (`pg_get_constraintdef`,
etc.) are lambdas that close over the `CatalogManager` instance. Copying them from the source
would create closures pointing at the wrong catalog. The clone constructs a fresh `CatalogManager`
(which re-registers these functions with closures over itself), then replaces the schemas with
deep copies. This is correct by construction — no stale references are possible.

**2. The clone is always in-memory.**
Cloning a persistent database to a persistent target would require serializing the heap to
a new data directory, which is a different operation (backup/restore). Cloning is designed
for test isolation where in-memory is the right default.

**3. Schema-level cloning is deferred.**
Cloning a schema within the same database requires OID remapping (the clone's tables need
new OIDs to coexist with the source's tables in the same `HeapStorage`). Database-level
cloning avoids this entirely because OIDs are per-database. Schema-level cloning can be
added later if needed.

---

## Open Questions

1. **Schema migration per SchemaHandle.** Each allocated schema starts empty. Callers are
   responsible for running Flyway/Liquibase before using it. Should `allocateSchema()`
   accept a migration callback, or is this always the caller's responsibility? Leaning
   toward: caller's responsibility — keeps the engine agnostic of migration tools.

2. **Cross-schema queries.** PostgreSQL allows `SELECT * FROM schema_a.t JOIN schema_b.t`.
   This works today via explicit schema qualification. No changes needed.

3. **OID space isolation between schemas.** Currently OIDs are allocated from a
   cluster-scoped counter in `CatalogManager`. Schemas within the same database share OID
   space — this is correct (mirrors PostgreSQL). No change needed.

4. **`dropOnStop` semantics.** If a cluster is stopped with `dropOnStop=true` and then
   `start()` is called again on the same object, should it resurrect or throw? Leaning
   toward: throw `IllegalStateException` — force callers to create a new instance.

5. **`ExternalSchemaHandle` scope.** Should this live in the main pgjava JAR or a
   separate `pgjava-test` artifact? Leaning toward a separate artifact so the main JAR
   has no test-only utilities. The test artifact depends on pgjava core but adds
   `PgTestExtension`, `ExternalSchemaHandle`, and `@PgTest`.

---

## Future Idea: Logical Replication Between Clusters

> Not planned for implementation. Recorded here to capture design constraints
> discovered while building the cluster model, so the architecture does not
> accidentally close off this path.

### Replication model

Physical (streaming) replication is not viable between pgjava clusters. WAL records
contain `RowId`s (tableOid + ArrayList position) which are positional — two clusters
with different insert histories assign different positions to the same logical row.
Physical WAL is not portable between them.

**Logical replication** is the correct model: WAL is decoded into row-level changes
(INSERT/UPDATE/DELETE with full column values, PK-identified, stripped of RowIds)
and applied as DML on the subscriber. This matches PostgreSQL's own logical replication
design and is feasible given the existing WAL infrastructure.

### Hard prerequisites

These must exist before replication can be built:

| Prerequisite | Status | Why needed |
|---|---|---|
| Persistent WAL (Phase C) | Not yet implemented | Primary must retain records until all subscribers consume them; in-memory WAL is discarded |
| `WalReader` (Phase 11) | Not yet implemented | Subscriber must read from an arbitrary LSN, not just the live tail |
| Wire protocol server (Phase D) | Stub | Cross-JVM replication requires a replication connection over TCP |

In-JVM replication (primary and replica in the same process) can proceed without
Phase D — changes can be passed as Java objects between clusters. Cross-JVM replication
is blocked on Phase D.

### New components required

**`ReplicationSlot`** — a named cursor into the WAL stream, per subscriber. Tracks the
confirmed-consumed LSN. The primary retains WAL records back to the oldest slot LSN.
Without slots, the primary cannot know how far back to keep records.

```java
record ReplicationSlot(String name, long confirmedLsn) {}
```

**`LogicalDecoder`** — converts raw `WalRecord` objects into portable `LogicalChange`
events. Groups records by transaction, emits only committed batches, resolves column
names from the catalog (WAL stores positional values; the decoder maps them to names
using the catalog state at record-write time), identifies rows by PK rather than RowId.

```java
sealed interface LogicalChange {
    record Insert(String schema, String table, Map<String, Object> values) ...
    record Update(String schema, String table, Map<String, Object> pk,
                  Map<String, Object> newValues) ...
    record Delete(String schema, String table, Map<String, Object> pk) ...
    record Commit(long lsn) ...
}
```

This is the hardest new piece. It requires the primary's catalog state at the time each
WAL record was written to resolve column names and identify PKs — the decoder must
either snapshot catalog state per-DDL or store enough metadata in DDL WAL records to
reconstruct it.

**`ReplicationWorker`** — background thread on the subscriber side. Reads `LogicalChange`
events from a slot on the primary, applies them via the subscriber's `TransactionManager`
using PK lookups instead of RowIds, advances the slot's confirmed LSN after each
committed batch.

### What is deliberately not in scope

- **Schema synchronisation** — DDL is not replicated. The subscriber schema must be
  set up manually before replication starts. This matches PostgreSQL's own behaviour:
  `CREATE TABLE` on the publisher does not automatically create it on the subscriber.
  DDL replication would require WAL records for DDL operations, which do not currently
  exist and add significant complexity.
- **Bidirectional replication** — both sides accepting writes creates conflicts that
  require a resolution strategy per table. One-way (primary → read replica) is the
  target. Bidirectional is BDR territory and explicitly out of scope.
- **Interoperability with real PostgreSQL** — subscribing to a real PostgreSQL primary
  or publishing to a real PostgreSQL subscriber requires the pgoutput logical decoding
  plugin wire format. This is a separate workstream from pgjava-to-pgjava replication.
- **Synchronous replication** — waiting for replica acknowledgement before committing
  on the primary. Async replication only.
- **Failover / switchover orchestration** — no automatic promotion of replica to primary.

### Architecture constraints to preserve now

The current design should not close off this path. Specifically:

1. **WAL records must retain full column values.** They currently do — each INSERT/UPDATE
   WAL record stores the complete row as typed values. Do not change this to store diffs
   only, as the logical decoder needs full before/after images.

2. **`WalWriter` must support a retained-records mode.** Currently records are buffered
   and can be discarded. When a replication slot exists, records at or after the slot's
   confirmed LSN must not be discarded. The slot LSN must be consulted before any WAL
   compaction or rotation.

3. **`TransactionManager` apply path is already sufficient.** `insert`, `update`, `delete`
   on the subscriber's `TransactionManager` is all the `ReplicationWorker` needs. No
   changes required to the storage layer for the subscriber side.

4. **`PgJavaCluster` is the right owner.** Replication slots belong to a cluster
   (not a database). `cluster.createReplicationSlot(name)` and
   `cluster.startReplication(slotName, subscriberCluster)` are the natural API entry
   points when this is eventually built.
