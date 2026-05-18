# Limitations & Not-Yet-Implemented Features

pgjava aims to be a correctness-first PostgreSQL reimplementation for use as an
in-memory test database. This document lists what is **not implemented** or
**out of scope**, so you can quickly judge whether pgjava fits your use case.

---

## Fully Implemented

These features work and are validated against real PostgreSQL via golden tests:

- **DML**: SELECT, INSERT, UPDATE, DELETE with full expression support
- **Joins**: INNER, LEFT, RIGHT, FULL, CROSS, LATERAL, NATURAL, USING
- **Aggregates**: COUNT, SUM, AVG, MIN, MAX, STRING_AGG, ARRAY_AGG, BOOL_AND/OR, with DISTINCT, ORDER BY, FILTER
- **Window functions**: ROW_NUMBER, RANK, DENSE_RANK, NTILE, LAG, LEAD, FIRST_VALUE, LAST_VALUE, NTH_VALUE, aggregate windows, all frame types
- **Subqueries**: EXISTS, IN, ANY, ALL, scalar, correlated
- **CTEs**: Non-recursive and recursive (WITH RECURSIVE)
- **Set operations**: UNION, INTERSECT, EXCEPT (with ALL variants)
- **DDL**: CREATE/ALTER/DROP TABLE, INDEX, SCHEMA, SEQUENCE, VIEW; TRUNCATE
- **Constraints**: PRIMARY KEY, UNIQUE, NOT NULL, CHECK, FOREIGN KEY (CASCADE, RESTRICT, SET NULL, SET DEFAULT)
- **Transactions**: BEGIN, COMMIT, ROLLBACK, SAVEPOINT, ROLLBACK TO
- **Types**: boolean, int2/4/8, float4/8, numeric, text, varchar, char, bytea, date, time, timetz, timestamp, timestamptz, interval, uuid, json, jsonb, arrays
- **UPSERT**: INSERT ... ON CONFLICT DO NOTHING / DO UPDATE
- **RETURNING**: On INSERT, UPDATE, DELETE
- **Collation**: Three-level (database default, per-column COLLATE, per-expression COLLATE). Supports C, POSIX, en_US. Does not support CREATE COLLATION, ICU provider, or non-deterministic collations — these are irrelevant for ORM use.
- **Wire protocol**: Full PostgreSQL v3 frontend/backend protocol (psql, DBeaver, any PG JDBC client)
- **JDBC driver**: Embedded `jdbc:pgjava:mem:dbname` with Statement, PreparedStatement, ResultSet, DatabaseMetaData
- **pg_catalog**: pg_namespace, pg_class, pg_attribute, pg_index, pg_constraint, pg_type, pg_database, pg_roles, pg_settings, pg_sequence, pg_proc, pg_am, pg_attrdef, pg_description, pg_depend, pg_collation
- **information_schema**: schemata, tables, columns, table_constraints, key_column_usage, referential_constraints, sequences
- **COPY**: FROM/TO with CSV and text format
- **EXPLAIN / EXPLAIN ANALYZE**

---

## Not Yet Implemented

These are planned but not yet built:

### JSONB (operators parsed, partial function coverage)

JSONB values are stored and basic operators work (`->`, `->>`), but the full
function set (`jsonb_set`, `jsonb_insert`, `jsonb_path_query`, `@>`, `<@`, `?`,
`?|`, `?&`) has gaps. Golden test coverage is thin.

### Cursors

`DECLARE`, `FETCH`, `MOVE`, `CLOSE` are not implemented. Queries that use
server-side cursors (e.g. large result set pagination via JDBC `setFetchSize`
with autocommit off) will fail.

### LISTEN / NOTIFY

Parsed and partially stubbed. `pg_notify()` delivers to same-session listeners
but cross-session and cross-connection notification delivery is not implemented.

### Advisory Locks

`pg_advisory_lock`, `pg_try_advisory_lock`, `pg_advisory_unlock` are not
implemented. Code that uses advisory locks for distributed coordination will
fail.

### Explicit Locking

`SELECT ... FOR UPDATE/SHARE` is parsed and accepted but does not acquire real
locks. `LOCK TABLE` is accepted as a no-op. In a single-connection test
scenario this is fine; concurrent access patterns will not see correct blocking
behavior.

### Advanced Types

| Type | Status |
|------|--------|
| Ranges (`int4range`, `tsrange`, etc.) | Basic storage, no operators/functions |
| Domains (`CREATE DOMAIN`) | Not implemented |
| Composite types (`CREATE TYPE ... AS (...)`) | Not implemented |
| Enums (`CREATE TYPE ... AS ENUM`) | Not implemented |

### Large Objects

`lo_create`, `lo_open`, `lo_read`, `lo_write`, `lo_close`, `lo_unlink` and the
`bytea`-based large object API are not implemented.

### Event Triggers

`CREATE EVENT TRIGGER` is not implemented. DDL event hooks do not fire.

### Publication / Subscription

`CREATE PUBLICATION`, `CREATE SUBSCRIPTION` and logical replication slot
management are not implemented.

### PL/pgSQL & Stored Procedures

`CREATE FUNCTION ... LANGUAGE plpgsql` is not implemented. `DO $$ ... $$`
blocks with `LANGUAGE sql` work for simple SQL; PL/pgSQL control flow
(IF/LOOP/RAISE/EXCEPTION) does not.

### Triggers

`CREATE TRIGGER` is parsed but triggers do not fire. Code relying on
BEFORE/AFTER triggers for audit logs, validation, or cascading logic will
not behave correctly.

---

## Out of Scope (No Plans to Implement)

These features are intentionally excluded — they don't apply to the in-memory
test database use case:

| Feature | Reason |
|---------|--------|
| Query optimizer / cost model | Not needed at test-data volumes (<=10k rows) |
| VACUUM / ANALYZE / autovacuum | No MVCC garbage; in-memory storage |
| Tablespaces | Single in-memory heap |
| Physical / logical replication | Not a production database |
| Foreign data wrappers (FDW) | Out of scope |
| Row-level security (RLS) | Not needed for test isolation |
| Partitioning (PARTITION BY) | Not needed at test volumes |
| Full-text search (tsvector/tsquery) | Large feature surface, low ORM usage |
| PostGIS / extensions | No extension loading mechanism |
| SSL/TLS on wire protocol | Use for local testing only |
| Authentication (password, SCRAM, certificate) | Trust-only; test database |
| Parallel query execution | Single-threaded by design |
| Connection pooling (internal) | One session = one thread; use external pooler if needed |
| `pg_dump` / `pg_restore` compatibility | Use SQL scripts instead |
| Statistics / `pg_stat_*` views (real data) | Stubs return zeros |
| Collation: CREATE COLLATION, ICU provider, non-deterministic | Not needed for ORM testing |

---

## ORM Compatibility

pgjava is tested against and compatible with:

- **Hibernate 6** — entity mapping, `@GeneratedValue(IDENTITY)`, schema validation, HQL/JPQL
- **jOOQ** — code generation against pgjava metadata, full DML
- **Flyway** — migration execution, schema history table
- **HikariCP** — connection pooling, validation queries

If you hit an incompatibility with these tools, it's a bug — please report it.
