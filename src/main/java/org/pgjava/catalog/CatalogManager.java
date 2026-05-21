package org.pgjava.catalog;

import org.pgjava.catalog.syscat.*;
import org.pgjava.engine.PgErrorException;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-database catalog: owns all schemas, tables, indexes, sequences, views,
 * and the virtual-table registry for {@code pg_catalog}.
 *
 * <p>Thread-safe: individual schema/table maps are {@link ConcurrentHashMap}s,
 * and the OID generator is an {@link AtomicLong}.
 */
public final class CatalogManager {

    private final String dbName;

    /**
     * Supplier of all database names in the cluster (for pg_database).
     *
     * <p>In real PostgreSQL, pg_database is a cluster-wide shared relation.
     * Here, CatalogManager is per-database, so cross-database visibility is
     * achieved by injecting a supplier that reads the cluster/registry key set.
     * Wired by {@code PgJavaCluster.database()} and {@code DatabaseRegistry.getOrCreate()}.
     * Default: just this database's own name.
     */
    private volatile java.util.function.Supplier<java.util.Collection<String>> allDbNames;

    /** User schemas (excludes pg_catalog / information_schema). */
    private final Map<String, Schema> schemas = new ConcurrentHashMap<>();

    /** Virtual tables (pg_catalog.* and information_schema.*). */
    private final Map<String, VirtualTable> virtualTables = new ConcurrentHashMap<>();

    private final FunctionRegistry functions = new FunctionRegistry();

    private final AtomicLong oidGen = new AtomicLong(16384L);

    /** Session-level (per-thread) last value returned by nextval() — for lastval(). */
    private final ThreadLocal<Long> sessionLastVal = new ThreadLocal<>();

    // -------------------------------------------------------------------------

    public CatalogManager(String dbName) {
        this.dbName = dbName;
        this.allDbNames = () -> List.of(dbName);
        // Always create the public schema with its canonical PostgreSQL OID (2200).
        // This OID is hardcoded in PgNamespace.scan() and must match so that
        // the pg_class JOIN pg_namespace ON n.oid = c.relnamespace works correctly.
        Schema pub = new Schema(2200L, "public");
        schemas.put("public", pub);
        // Register system catalog virtual tables (Phase 5a)
        registerVirtualTable(PgNamespace.INSTANCE);
        registerVirtualTable(PgClass.INSTANCE);
        registerVirtualTable(PgAttribute.INSTANCE);
        registerVirtualTable(PgIndex.INSTANCE);
        registerVirtualTable(PgConstraint.INSTANCE);
        // Phase 5b: additional pg_catalog tables
        registerVirtualTable(org.pgjava.catalog.syscat.PgType.INSTANCE);
        registerVirtualTable(PgDatabase.INSTANCE);
        registerVirtualTable(PgRoles.INSTANCE);
        registerVirtualTable(PgSettings.INSTANCE);
        registerVirtualTable(PgSequence.INSTANCE);
        registerVirtualTable(PgAm.INSTANCE);
        registerVirtualTable(PgAttrdef.INSTANCE);
        registerVirtualTable(PgDescription.INSTANCE);
        // Phase 12: additional pg_catalog stubs for ORM compatibility
        registerVirtualTable(PgDepend.INSTANCE);
        registerVirtualTable(PgProc.INSTANCE);
        registerVirtualTable(PgTrigger.INSTANCE);
        registerVirtualTable(PgRewrite.INSTANCE);
        registerVirtualTable(PgCollationTable.INSTANCE);
        // information_schema virtual tables
        registerVirtualTable(InformationSchema.SCHEMATA);
        registerVirtualTable(InformationSchema.TABLES);
        registerVirtualTable(InformationSchema.COLUMNS);
        registerVirtualTable(InformationSchema.TABLE_CONSTRAINTS);
        registerVirtualTable(InformationSchema.KEY_COLUMN_USAGE);
        registerVirtualTable(InformationSchema.REFERENTIAL_CONSTRAINTS);
        registerVirtualTable(InformationSchema.SEQUENCES);
        registerVirtualTable(InformationSchema.ROUTINES);
        // Register built-in functions
        BuiltinFunctions.registerAll(functions);
        // Register functions that close over `this` for catalog access
        registerSequenceFunctions();
        registerCatalogFunctions();
    }

    // -------------------------------------------------------------------------
    // Sequence functions (nextval / currval / setval)

    private void registerSequenceFunctions() {
        var r = PgTypeRegistry.INSTANCE;
        var textType = r.byOid(PgOid.TEXT);
        var int8Type = r.byOid(PgOid.INT8);

        // nextval(text) → int8
        functions.register(new FunctionDef(
                1574L, "nextval", "pg_catalog",
                List.of(textType), int8Type,
                true, false,
                args -> {
                    long v = resolveSequenceByName((String) args[0]).nextval();
                    sessionLastVal.set(v);
                    return v;
                }
        ));

        // currval(text) → int8
        functions.register(new FunctionDef(
                1575L, "currval", "pg_catalog",
                List.of(textType), int8Type,
                true, false,
                args -> resolveSequenceByName((String) args[0]).currval()
        ));

        // setval(text, int8) → int8
        functions.register(new FunctionDef(
                1576L, "setval", "pg_catalog",
                List.of(textType, int8Type), int8Type,
                true, false,
                args -> {
                    SequenceDef seq = resolveSequenceByName((String) args[0]);
                    long val = toLong(args[1]);
                    seq.setval(val);
                    return val;
                }
        ));

        var boolType = PgTypeRegistry.INSTANCE.byOid(PgOid.BOOL);

        // setval(text, int8, bool) → int8
        // When is_called=false, next nextval() returns the given value (don't advance)
        functions.register(new FunctionDef(
                1219L, "setval", "pg_catalog",
                List.of(textType, int8Type, boolType), int8Type,
                true, false,
                args -> {
                    SequenceDef seq = resolveSequenceByName((String) args[0]);
                    long val = toLong(args[1]);
                    boolean isCalled = args[2] == null || Boolean.TRUE.equals(args[2]);
                    if (isCalled) {
                        seq.setval(val);
                    } else {
                        seq.setvalNotCalled(val);
                    }
                    return val;
                }
        ));

        // lastval() → int8
        functions.register(new FunctionDef(
                2559L, "lastval", "pg_catalog",
                List.of(), int8Type,
                true, false,
                args -> lastval()
        ));
    }

    // -------------------------------------------------------------------------
    // Catalog-aware functions (pg_get_constraintdef, pg_get_indexdef, pg_get_viewdef)

    private void registerCatalogFunctions() {
        var r = PgTypeRegistry.INSTANCE;
        var oidType  = r.byOid(PgOid.OID);
        var textType = r.byOid(PgOid.TEXT);
        var boolType = r.byOid(PgOid.BOOL);
        var int4Type = r.byOid(PgOid.INT4);

        // pg_get_constraintdef(constraint_oid) → text
        functions.register(new FunctionDef(
                1387L, "pg_get_constraintdef", "pg_catalog",
                List.of(oidType), textType, true, false,
                args -> getConstraintDef(toLong(args[0]))
        ));
        // pg_get_constraintdef(constraint_oid, pretty_print) → text
        functions.register(new FunctionDef(
                2508L, "pg_get_constraintdef", "pg_catalog",
                List.of(oidType, boolType), textType, true, false,
                args -> getConstraintDef(toLong(args[0]))
        ));

        // pg_get_indexdef(index_oid) → text
        functions.register(new FunctionDef(
                1643L, "pg_get_indexdef", "pg_catalog",
                List.of(oidType), textType, true, false,
                args -> getIndexDef(toLong(args[0]))
        ));
        // pg_get_indexdef(index_oid, column_no, pretty) → text
        functions.register(new FunctionDef(
                1644L, "pg_get_indexdef", "pg_catalog",
                List.of(oidType, int4Type, boolType), textType, true, false,
                args -> {
                    long oid = toLong(args[0]);
                    int colNo = ((Number) args[1]).intValue();
                    if (colNo == 0) return getIndexDef(oid);
                    return getIndexDefColumn(oid, colNo);
                }
        ));

        // pg_get_viewdef(view_oid) → text
        functions.register(new FunctionDef(
                1640L, "pg_get_viewdef", "pg_catalog",
                List.of(oidType), textType, true, false,
                args -> getViewDef(toLong(args[0]))
        ));
        // pg_get_viewdef(view_oid, pretty_print) → text
        functions.register(new FunctionDef(
                2505L, "pg_get_viewdef", "pg_catalog",
                List.of(oidType, boolType), textType, true, false,
                args -> getViewDef(toLong(args[0]))
        ));

        // pg_get_serial_sequence(table_name, column_name) → text
        // Returns the fully-qualified sequence name backing a SERIAL/IDENTITY column.
        // PG convention: SERIAL columns create a sequence named "table_column_seq".
        functions.register(new FunctionDef(
                1665L, "pg_get_serial_sequence", "pg_catalog",
                List.of(textType, textType), textType, true, false,
                args -> getSerialSequence(args[0].toString(), args[1].toString())
        ));
    }

    /**
     * Reconstruct a constraint definition SQL string from the catalog,
     * matching PostgreSQL's pg_get_constraintdef() output format.
     */
    private String getConstraintDef(long constraintOid) {
        // Constraint OIDs are synthetic, generated by PgConstraint.scan()
        // starting at 100000. We replicate the same iteration order to find the match.
        long syntheticOid = 100000L;
        for (Schema schema : allSchemas().values()) {
            for (TableDef t : schema.tables().values()) {
                for (Constraint c : t.constraints()) {
                    if (syntheticOid++ == constraintOid) {
                        return constraintToSql(c, t, schema);
                    }
                }
            }
        }
        return null;
    }

    private String constraintToSql(Constraint c, TableDef t, Schema schema) {
        return switch (c) {
            case Constraint.PrimaryKey pk ->
                "PRIMARY KEY (" + String.join(", ", pk.columns()) + ")";
            case Constraint.Unique u ->
                "UNIQUE (" + String.join(", ", u.columns()) + ")";
            case Constraint.Check ch ->
                "CHECK (" + (ch.exprSql() != null ? ch.exprSql() : "true") + ")";
            case Constraint.NotNull nn ->
                "NOT NULL";
            case Constraint.ForeignKey fk -> {
                StringBuilder sb = new StringBuilder("FOREIGN KEY (");
                sb.append(String.join(", ", fk.columns()));
                sb.append(") REFERENCES ");
                if (fk.refSchema() != null && !fk.refSchema().isEmpty()
                        && !fk.refSchema().equalsIgnoreCase(schema.name())) {
                    sb.append(fk.refSchema()).append('.');
                }
                sb.append(fk.refTable());
                sb.append('(').append(String.join(", ", fk.refColumns())).append(')');
                if (fk.onDelete() != null && fk.onDelete() != org.pgjava.sql.ast.FkAction.NO_ACTION) {
                    sb.append(" ON DELETE ").append(fkActionSql(fk.onDelete()));
                }
                if (fk.onUpdate() != null && fk.onUpdate() != org.pgjava.sql.ast.FkAction.NO_ACTION) {
                    sb.append(" ON UPDATE ").append(fkActionSql(fk.onUpdate()));
                }
                yield sb.toString();
            }
        };
    }

    private static String fkActionSql(org.pgjava.sql.ast.FkAction a) {
        return switch (a) {
            case NO_ACTION  -> "NO ACTION";
            case RESTRICT   -> "RESTRICT";
            case CASCADE    -> "CASCADE";
            case SET_NULL   -> "SET NULL";
            case SET_DEFAULT -> "SET DEFAULT";
        };
    }

    /** Full index definition: CREATE [UNIQUE] INDEX name ON table USING method (cols) */
    private String getIndexDef(long indexOid) {
        for (Schema schema : allSchemas().values()) {
            for (IndexDef idx : schema.indexes().values()) {
                if (idx.oid() == indexOid) {
                    return indexToSql(idx);
                }
            }
        }
        return null;
    }

    private String indexToSql(IndexDef idx) {
        StringBuilder sb = new StringBuilder("CREATE ");
        if (idx.unique()) sb.append("UNIQUE ");
        sb.append("INDEX ").append(idx.name()).append(" ON ");
        if (idx.schemaName() != null && !idx.schemaName().equalsIgnoreCase("public")) {
            sb.append(idx.schemaName()).append('.');
        }
        sb.append(idx.tableName()).append(" USING ").append(idx.accessMethod()).append(" (");
        for (int i = 0; i < idx.columns().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(idx.columns().get(i).column());
            if (!idx.columns().get(i).ascending()) sb.append(" DESC");
            if (idx.columns().get(i).nullsFirst()) sb.append(" NULLS FIRST");
        }
        sb.append(')');
        return sb.toString();
    }

    /** Single column expression from an index definition (1-based). */
    private String getIndexDefColumn(long indexOid, int colNo) {
        for (Schema schema : allSchemas().values()) {
            for (IndexDef idx : schema.indexes().values()) {
                if (idx.oid() == indexOid) {
                    if (colNo < 1 || colNo > idx.columns().size()) return null;
                    return idx.columns().get(colNo - 1).column();
                }
            }
        }
        return null;
    }

    /** View definition SQL text, deparsed from the AST to match PG's output format. */
    private String getViewDef(long viewOid) {
        for (Schema schema : allSchemas().values()) {
            for (ViewDef v : schema.views().values()) {
                if (v.oid() == viewOid) {
                    // Deparse from AST if available — matches PG's pg_get_viewdef behavior.
                    // PG's output has a leading space and trailing semicolon.
                    if (v.parsedDef() != null) {
                        return " " + org.pgjava.sql.SqlDeparser.deparse(v.parsedDef()) + ";";
                    }
                    return v.definitionSql();
                }
            }
        }
        return null;
    }

    /**
     * Look up the sequence backing a SERIAL/IDENTITY column.
     * PG convention: the auto-created sequence is named {@code table_column_seq}.
     * Returns the schema-qualified name ({@code "schema"."table_column_seq"}) or null.
     */
    private String getSerialSequence(String tableName, String columnName) {
        String tbl = tableName.toLowerCase().replace("\"", "");
        String col = columnName.toLowerCase().replace("\"", "");
        // Strip schema prefix from table name if present
        String schemaHint = null;
        int dot = tbl.indexOf('.');
        if (dot >= 0) {
            schemaHint = tbl.substring(0, dot);
            tbl = tbl.substring(dot + 1);
        }
        String seqName = tbl + "_" + col + "_seq";
        for (Schema s : allSchemas().values()) {
            if (schemaHint != null && !s.name().equalsIgnoreCase(schemaHint)) continue;
            if (s.sequence(seqName) != null) {
                return s.name() + "." + seqName;
            }
        }
        return null;
    }

    private long lastval() throws SQLException {
        Long v = sessionLastVal.get();
        if (v == null)
            throw PgErrorException.error("55000",
                    "lastval is not yet defined in this session").build();
        return v;
    }

    /**
     * Resolve a sequence by name (possibly schema-qualified).
     * Strips surrounding double-quotes, then searches schemas.
     */
    private SequenceDef resolveSequenceByName(String raw) throws SQLException {
        // Strip surrounding double-quotes and normalise case
        String name = raw.trim().replaceAll("\"", "").toLowerCase();
        int dot = name.indexOf('.');
        if (dot > 0) {
            String schemaName = name.substring(0, dot);
            String seqName    = name.substring(dot + 1);
            Schema s = getSchemaOrNull(schemaName);
            if (s != null) {
                SequenceDef seq = s.sequence(seqName);
                if (seq != null) return seq;
            }
        } else {
            for (Schema s : schemas.values()) {
                SequenceDef seq = s.sequence(name);
                if (seq != null) return seq;
            }
        }
        throw PgErrorException.error("42P01", "relation \"" + raw + "\" does not exist").build();
    }

    private static long toLong(Object v) {
        if (v instanceof Long l)    return l;
        if (v instanceof Number n)  return n.longValue();
        return Long.parseLong(v.toString());
    }

    public String dbName() { return dbName; }

    /** Set the supplier of all database names visible in pg_database. */
    public void setAllDbNames(java.util.function.Supplier<java.util.Collection<String>> supplier) {
        this.allDbNames = supplier;
    }

    /** All database names visible in pg_database. */
    public java.util.Collection<String> allDbNames() { return allDbNames.get(); }

    // -------------------------------------------------------------------------
    // OID allocation

    public long nextOid() { return oidGen.getAndIncrement(); }

    /** Returns the current (next-to-be-allocated) OID value — for persistence snapshots. */
    public long currentOid() { return oidGen.get(); }

    /** Override the OID generator — used during catalog recovery to restore saved state. */
    public void resetOidGen(long value) { oidGen.set(value); }

    /**
     * Recovery-only: register a schema that was reconstructed from disk with a specific OID,
     * bypassing OID allocation.
     */
    public Schema createSchemaWithOid(long oid, String name) {
        Schema s = new Schema(oid, name);
        schemas.put(name.toLowerCase(), s);
        return s;
    }

    // -------------------------------------------------------------------------
    // Schema operations

    /**
     * Create a new schema.
     *
     * @throws SQLException SQLSTATE 42P06 if schema already exists and {@code ifNotExists=false}
     */
    public Schema createSchema(String name, boolean ifNotExists) throws SQLException {
        String lc = name.toLowerCase();
        if (schemas.containsKey(lc)) {
            if (ifNotExists) return schemas.get(lc);
            throw PgErrorException.error("42P06", "schema \"" + name + "\" already exists").build();
        }
        Schema s = new Schema(nextOid(), lc);
        schemas.put(lc, s);
        return s;
    }

    /**
     * Drop a schema.
     *
     * @param cascade  if true, drop all objects in the schema; if false, fail if non-empty
     * @throws SQLException SQLSTATE 3F000 if not found; 2BP01 if non-empty and not cascade
     */
    public void dropSchema(String name, boolean cascade, boolean ifExists) throws SQLException {
        String lc = name.toLowerCase();
        if (!schemas.containsKey(lc)) {
            if (ifExists) return;
            throw PgErrorException.error("3F000", "schema \"" + name + "\" does not exist").build();
        }
        if (!cascade) {
            Schema s = schemas.get(lc);
            if (!s.tables().isEmpty() || !s.sequences().isEmpty() || !s.views().isEmpty()) {
                throw PgErrorException.error("2BP01",
                        "cannot drop schema " + name + " because other objects depend on it")
                        .hint("Use DROP ... CASCADE to drop the dependent objects too.")
                        .build();
            }
        }
        schemas.remove(lc);
    }

    /**
     * Lookup a schema by name.
     *
     * @throws SQLException SQLSTATE 3F000 if not found
     */
    public Schema getSchema(String name) throws SQLException {
        String lc = name.toLowerCase();
        Schema s = schemas.get(lc);
        if (s == null) throw PgErrorException.error("3F000", "schema \"" + name + "\" does not exist").build();
        return s;
    }

    public Schema getSchemaOrNull(String name) {
        return schemas.get(name.toLowerCase());
    }

    public Map<String, Schema> allSchemas() {
        return Collections.unmodifiableMap(schemas);
    }

    // -------------------------------------------------------------------------
    // Table operations

    /**
     * Create a table in the given schema.
     *
     * @throws SQLException SQLSTATE 42P07 if already exists; 3F000 if schema missing
     */
    public TableDef createTable(String schemaName, String tableName,
                                List<ColumnDef> columns, List<Constraint> constraints,
                                boolean isTemp, boolean ifNotExists) throws SQLException {
        Schema schema = getSchema(schemaName);
        String lc = tableName.toLowerCase();
        if (schema.hasTable(lc)) {
            if (ifNotExists) return schema.table(lc);
            throw PgErrorException.error("42P07",
                    "relation \"" + tableName + "\" already exists").build();
        }
        TableDef t = new TableDef(nextOid(), lc, schemaName, isTemp);
        for (ColumnDef col : columns)     t.addColumn(col);
        for (Constraint c : constraints)  t.addConstraint(c);
        schema.addTable(t);

        // Auto-create PK index if there's a primary key constraint
        Constraint.PrimaryKey pk = t.primaryKey();
        if (pk != null) {
            List<IndexColumn> idxCols = pk.columns().stream()
                    .map(IndexColumn::asc).toList();
            IndexDef pkIdx = new IndexDef(nextOid(),
                    tableName + "_pkey", schemaName, lc,
                    t.oid(), idxCols, true, true, "btree");
            t.addIndex(pkIdx);
            schema.addIndex(pkIdx);
        }
        // Auto-create UNIQUE indexes for UNIQUE constraints
        int uqSeq = 0;
        for (Constraint c : constraints) {
            if (c instanceof Constraint.Unique uq) {
                List<IndexColumn> idxCols = uq.columns().stream()
                        .map(IndexColumn::asc).toList();
                String idxName = uq.name() != null && !uq.name().isEmpty()
                        ? uq.name()
                        : lc + "_" + String.join("_", uq.columns()) + "_key"
                          + (uqSeq++ == 0 ? "" : uqSeq);
                IndexDef uqIdx = new IndexDef(nextOid(),
                        idxName, schemaName, lc,
                        t.oid(), idxCols, true, false, "btree");
                t.addIndex(uqIdx);
                schema.addIndex(uqIdx);
            }
        }
        return t;
    }

    /**
     * Drop a table.
     *
     * @throws SQLException SQLSTATE 42P01 if not found
     */
    public void dropTable(String schemaName, String tableName,
                          boolean ifExists, boolean cascade) throws SQLException {
        Schema schema = getSchemaOrNull(schemaName);
        if (schema == null || schema.table(tableName) == null) {
            if (ifExists) return;
            throw PgErrorException.error("42P01",
                    "table \"" + tableName + "\" does not exist").build();
        }
        TableDef t = schema.removeTable(tableName);
        // Drop associated indexes
        for (IndexDef idx : t.indexes()) schema.removeIndex(idx.name());
    }

    /**
     * Resolve a (possibly unqualified) table name against the session search path.
     *
     * @param searchPath  ordered list of schema names to search
     * @throws SQLException SQLSTATE 42P01 if not found in any searched schema
     */
    public TableDef resolveTable(String name, List<String> searchPath) throws SQLException {
        String lc = name.toLowerCase();
        // If qualified (schema.table), look up directly
        int dot = lc.indexOf('.');
        if (dot > 0) {
            String schema = lc.substring(0, dot);
            String table  = lc.substring(dot + 1);
            Schema s = getSchemaOrNull(schema);
            if (s != null) {
                TableDef t = s.table(table);
                if (t != null) return t;
            }
            throw PgErrorException.error("42P01", "relation \"" + name + "\" does not exist").build();
        }
        for (String schema : searchPath) {
            Schema s = getSchemaOrNull(schema);
            if (s != null) {
                TableDef t = s.table(lc);
                if (t != null) return t;
            }
        }
        throw PgErrorException.error("42P01", "relation \"" + name + "\" does not exist").build();
    }

    // -------------------------------------------------------------------------
    // Index operations

    public IndexDef createIndex(String schemaName, String indexName, String tableName,
                                List<IndexColumn> columns, boolean unique,
                                boolean ifNotExists) throws SQLException {
        Schema schema = getSchema(schemaName);
        if (schema.index(indexName) != null) {
            if (ifNotExists) return schema.index(indexName);
            throw PgErrorException.error("42P07", "relation \"" + indexName + "\" already exists").build();
        }
        TableDef t = schema.table(tableName);
        if (t == null) throw PgErrorException.error("42P01", "relation \"" + tableName + "\" does not exist").build();
        IndexDef idx = new IndexDef(nextOid(), indexName, schemaName, tableName,
                t.oid(), columns, unique, false, "btree");
        t.addIndex(idx);
        schema.addIndex(idx);
        return idx;
    }

    public void dropIndex(String schemaName, String indexName,
                          boolean ifExists, boolean cascade) throws SQLException {
        Schema schema = getSchemaOrNull(schemaName);
        if (schema == null || schema.index(indexName) == null) {
            if (ifExists) return;
            throw PgErrorException.error("42704", "index \"" + indexName + "\" does not exist").build();
        }
        IndexDef idx = schema.removeIndex(indexName);
        // Remove from its table too
        Schema s2 = getSchemaOrNull(idx.schemaName());
        if (s2 != null) {
            TableDef t = s2.table(idx.tableName());
            if (t != null) t.dropIndex(indexName);
        }
    }

    // -------------------------------------------------------------------------
    // Sequence operations

    public SequenceDef createSequence(String schemaName, String seqName,
                                      long start, long increment, long minVal, long maxVal,
                                      boolean cycle, boolean ifNotExists) throws SQLException {
        Schema schema = getSchema(schemaName);
        if (schema.sequence(seqName) != null) {
            if (ifNotExists) return schema.sequence(seqName);
            throw PgErrorException.error("42P07", "relation \"" + seqName + "\" already exists").build();
        }
        SequenceDef seq = new SequenceDef(nextOid(), seqName, schemaName,
                start, increment, minVal, maxVal, cycle);
        schema.addSequence(seq);
        return seq;
    }

    public void dropSequence(String schemaName, String seqName,
                             boolean ifExists, boolean cascade) throws SQLException {
        Schema schema = getSchemaOrNull(schemaName);
        if (schema == null || schema.sequence(seqName) == null) {
            if (ifExists) return;
            throw PgErrorException.error("42P01", "sequence \"" + seqName + "\" does not exist").build();
        }
        schema.removeSequence(seqName);
    }

    public SequenceDef resolveSequence(String name, List<String> searchPath) throws SQLException {
        String lc = name.toLowerCase();
        for (String schemaName : searchPath) {
            Schema s = getSchemaOrNull(schemaName);
            if (s != null) {
                SequenceDef seq = s.sequence(lc);
                if (seq != null) return seq;
            }
        }
        throw PgErrorException.error("42P01", "sequence \"" + name + "\" does not exist").build();
    }

    // -------------------------------------------------------------------------
    // View operations

    public ViewDef createView(String schemaName, String viewName,
                              String sql, org.pgjava.sql.ast.SelectStmt parsed,
                              List<String> aliases, boolean replace) throws SQLException {
        Schema schema = getSchema(schemaName);
        if (schema.view(viewName) != null && !replace) {
            throw PgErrorException.error("42P07", "relation \"" + viewName + "\" already exists").build();
        }
        ViewDef v = new ViewDef(nextOid(), viewName, schemaName, sql, parsed, aliases);
        schema.addView(v);
        return v;
    }

    public void dropView(String schemaName, String viewName,
                         boolean ifExists, boolean cascade) throws SQLException {
        Schema schema = getSchemaOrNull(schemaName);
        if (schema == null || schema.view(viewName) == null) {
            if (ifExists) return;
            throw PgErrorException.error("42P01", "view \"" + viewName + "\" does not exist").build();
        }
        schema.removeView(viewName);
    }

    // -------------------------------------------------------------------------
    // Virtual tables (pg_catalog / information_schema)

    public void registerVirtualTable(VirtualTable vt) {
        virtualTables.put(vt.schema() + "." + vt.name(), vt);
    }

    public VirtualTable getVirtualTable(String schema, String name) {
        return virtualTables.get(schema.toLowerCase() + "." + name.toLowerCase());
    }

    public Collection<VirtualTable> allVirtualTables() {
        return Collections.unmodifiableCollection(virtualTables.values());
    }

    // -------------------------------------------------------------------------
    // Function registry

    public FunctionRegistry functions() { return functions; }

    // -------------------------------------------------------------------------
    // Deep copy

    /**
     * Create an independent deep copy of this catalog for database cloning.
     *
     * <p>The new CatalogManager is constructed normally (re-registering all built-in
     * and closure-capturing functions fresh), then user schemas are replaced with
     * deep copies from this catalog.  This ensures that sequence functions
     * ({@code nextval}, {@code currval}, {@code setval}) and catalog functions
     * ({@code pg_get_constraintdef}, etc.) close over the <em>new</em> catalog
     * instance, not the source.
     *
     * @param newDbName the name for the cloned database
     * @return a map of table OID → cloned TableDef (needed for HeapStorage deep copy)
     */
    public DeepCopyResult deepCopy(String newDbName) {
        CatalogManager copy = new CatalogManager(newDbName);

        // Build a map of table OID → cloned TableDef for HeapStorage wiring
        Map<Long, TableDef> tableDefsByOid = new java.util.HashMap<>();

        // Replace "public" schema with deep copy from source
        Schema srcPublic = schemas.get("public");
        if (srcPublic != null) {
            Schema clonedPublic = srcPublic.deepCopy();
            copy.schemas.put("public", clonedPublic);
            for (TableDef t : clonedPublic.tables().values()) {
                tableDefsByOid.put(t.oid(), t);
            }
        }

        // Copy all non-system schemas
        Set<String> systemSchemas = Set.of("public", "pg_catalog", "information_schema");
        for (var entry : schemas.entrySet()) {
            if (systemSchemas.contains(entry.getKey())) continue;
            Schema clonedSchema = entry.getValue().deepCopy();
            copy.schemas.put(entry.getKey(), clonedSchema);
            for (TableDef t : clonedSchema.tables().values()) {
                tableDefsByOid.put(t.oid(), t);
            }
        }

        // Advance OID generator to avoid collisions
        copy.resetOidGen(this.currentOid());

        // Copy user-defined functions (source != null)
        for (FunctionDef fn : this.functions.allScalars()) {
            if (fn.source() != null) copy.functions.register(fn);
        }

        return new DeepCopyResult(copy, tableDefsByOid);
    }

    /** Result of {@link #deepCopy(String)}: the cloned catalog plus a table-OID map for storage wiring. */
    public record DeepCopyResult(CatalogManager catalog, Map<Long, TableDef> tableDefsByOid) {}
}
