# pgjava

A correctness-first reimplementation of PostgreSQL in Java. Drop it into your test suite as an embedded database — no external PostgreSQL process needed.

## Use cases

- **Unit / integration tests** — in-memory database, zero setup, disposable per test
- **Dev instances** — optional WAL-backed persistence for local development
- **CI** — no Docker, no port conflicts, no cleanup

## Quick start

### Embedded JDBC (no network)

```java
// In-memory, disposable
Connection conn = DriverManager.getConnection("jdbc:pgjava:mem:testdb");

// With WAL persistence
Connection conn = DriverManager.getConnection("jdbc:pgjava:file:/tmp/mydb");
```

The driver registers automatically via `ServiceLoader`. Just add pgjava to your classpath.

### Wire protocol server

```java
PgJavaCluster cluster = PgJavaCluster.create(
    ClusterConfig.builder("myserver")
        .port(5433)
        .build()
).start();
```

Then connect with `psql`, DBeaver, or any PostgreSQL client:

```
psql -h localhost -p 5433 -U postgres
```

### Database cloning for test isolation

Clone an already-migrated database so each test starts from the same state without re-running migrations:

```java
PgJavaCluster cluster = PgJavaCluster.create(
    ClusterConfig.builder("test").dropOnStop(true).build()
).start();

// Run migrations once against a template database
Database template = cluster.database("template");
// ... apply Flyway/Liquibase ...

// Each test gets an independent clone (deep copy, no shared mutable state)
Database test1 = cluster.cloneDatabase("template", "test_1");
Database test2 = cluster.cloneDatabase("template", "test_2");
```

### Spring Boot / test frameworks

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class MyRepositoryTest {
    // application-test.properties:
    // spring.datasource.url=jdbc:pgjava:mem:testdb
    // spring.datasource.driver-class-name=org.pgjava.jdbc.PgJavaDriver
}
```

## What works

pgjava implements PostgreSQL's SQL semantics, not just the syntax. Every feature is tested against real PostgreSQL to verify identical behavior.

**SQL**: SELECT, INSERT, UPDATE, DELETE, CTEs (including recursive), window functions, subqueries (correlated, lateral), UNION/INTERSECT/EXCEPT, DISTINCT ON, RETURNING, ON CONFLICT (upsert), COPY, VALUES, TABLE

**DDL**: CREATE/ALTER/DROP TABLE, INDEX, VIEW, SEQUENCE, SCHEMA, FUNCTION. Column constraints, foreign keys, check constraints, serial types, GENERATED AS IDENTITY, GENERATED ALWAYS AS (expr) STORED

**Types**: All standard types (integer, bigint, numeric, text, varchar, boolean, date, timestamp, timestamptz, interval, uuid, json, jsonb, bytea, arrays, range types). Full type coercion following PostgreSQL's resolution rules

**Transactions**: BEGIN/COMMIT/ROLLBACK, savepoints, auto-commit. READ COMMITTED isolation via MVCC snapshots (xmin/xmax visibility). WAL-based undo for rollback. Row-level locking with SELECT FOR UPDATE/SHARE, SKIP LOCKED, NOWAIT. Table-level lock manager with 8-mode compatibility matrix

**Functions**: 140+ built-in functions (string, math, date/time, aggregate, window, array, JSON). CREATE FUNCTION for SQL and PL/pgSQL user-defined functions. Set-returning functions (RETURNS SETOF/TABLE)

**PL/pgSQL**: Full procedural language support — variables, IF/ELSIF/ELSE, LOOP/WHILE/FOR, CASE, EXCEPTION handling with savepoint rollback, RAISE, PERFORM, EXECUTE dynamic SQL, cursors (OPEN/FETCH/MOVE/CLOSE), RETURN NEXT/QUERY, DO blocks

**Triggers**: CREATE TRIGGER with BEFORE/AFTER ROW/STATEMENT for INSERT/UPDATE/DELETE. NEW/OLD row variables, TG_* special variables, WHEN clause, UPDATE OF columns, alphabetical firing order

**Async**: LISTEN/NOTIFY with transaction-scoped delivery, deduplication, pg_notify()

**Catalog**: pg_catalog virtual tables, information_schema views, DatabaseMetaData

**Collation**: Three-level collation support (database, column, expression). C/POSIX and locale-aware ordering

**ORM compatibility**: Tested with Hibernate, Flyway, Liquibase, jOOQ, Spring Data JPA, HikariCP

## Build

```bash
./gradlew build        # compile + test
./gradlew test         # tests only
./gradlew run          # start wire protocol server on port 5433
```

Requires JDK 24+.

## Parser

pgjava uses a two-tier parsing strategy:

1. **Primary**: [pg_query_java](https://github.com/pganalyze/pg_query_java) — JNI binding to PostgreSQL's actual C parser. 100% parse compatibility. Works on Linux (x86_64, aarch64), macOS, Windows.

2. **Fallback**: ANTLR4 grammar — pure Java, works everywhere including Android. Covers common SQL but may fail on obscure syntax.

The parser is selected automatically at startup. No configuration needed.

## Known limitations

- No query optimizer (no statistics, no cost model, no join reordering)
- MVCC is READ COMMITTED only (no REPEATABLE READ or SERIALIZABLE)
- No replication, partitioning, or table inheritance
- No extensions (PostGIS, pg_trgm, etc.)
- Designed for test datasets (up to ~10k rows per table)

## License

[MIT](LICENSE)
