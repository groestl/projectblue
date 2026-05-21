package org.pgjava.executor;

import org.pgjava.catalog.*;
import org.pgjava.engine.Database;
import org.pgjava.engine.PgErrorException;
import org.pgjava.sql.ast.*;
import org.pgjava.types.PgType;
import org.pgjava.types.PgTypeRegistry;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DDL executor: translates DDL AST nodes into catalog and storage mutations.
 *
 * <p>Called from {@link org.pgjava.engine.Session} for each DDL statement.
 * Keeps no per-session state — all persistent state lives in {@link Database}.
 */
public final class DdlExecutor {

    private final Database           db;
    private final PgTypeRegistry     types;
    private       Evaluator          eval;   // set lazily by Session after construction
    private       org.pgjava.wal.Transaction activeTx; // null outside explicit transactions

    public DdlExecutor(Database db) {
        this.db    = db;
        this.types = db.typeRegistry();
    }

    /** Called by Session to wire in the expression evaluator (needed for ADD COLUMN DEFAULT). */
    public void setEvaluator(Evaluator evaluator) { this.eval = evaluator; }

    /** Called by Session before and after each DDL statement to provide rollback context. */
    public void setCurrentTransaction(org.pgjava.wal.Transaction tx) { this.activeTx = tx; }

    /** Register a catalog undo action with the active transaction (if any). */
    private void registerUndo(Runnable undo) {
        if (activeTx != null) activeTx.addCatalogUndo(undo);
    }

    // =========================================================================
    // CREATE TABLE

    public void executeCreateTable(CreateTableStmt s, List<String> searchPath)
            throws SQLException {

        String schemaName = resolveCreateSchema(s.relation(), searchPath);
        String tableName  = s.relation().relName().toLowerCase();

        List<ColumnDef>  cols        = new ArrayList<>();
        List<Constraint> constraints = new ArrayList<>();

        // First pass: columns
        int attnum = 1;
        for (ColumnDefNode cdn : s.columns()) {
            String  colName   = cdn.colname().toLowerCase();
            boolean isSerial  = isSerialType(cdn.typeName());
            TypeInfo ti       = resolveType(cdn.typeName(), searchPath);
            boolean nullable  = true;
            Expr    defExpr   = null;
            GeneratedKind gen = GeneratedKind.NONE;

            for (ColumnConstraintNode ccn : cdn.constraints()) {
                switch (ccn.type()) {
                    case NULL     -> nullable = true;
                    case NOT_NULL -> nullable = false;
                    case PRIMARY  -> {
                        nullable = false;
                        constraints.add(new Constraint.PrimaryKey(
                                ccn.constraintName(), List.of(colName)));
                    }
                    case UNIQUE   -> constraints.add(new Constraint.Unique(
                            ccn.constraintName(), List.of(colName)));
                    case DEFAULT  -> defExpr = ccn.rawExpr();
                    case CHECK    -> constraints.add(new Constraint.Check(
                            ccn.constraintName(), colName, ccn.rawExpr(), null));
                    case FK       -> constraints.add(buildFkFromColumn(
                            ccn, colName, schemaName));
                    case IDENTITY -> gen = ccn.generatedAlways()
                            ? GeneratedKind.ALWAYS : GeneratedKind.BY_DEFAULT;
                    case GENERATED -> {
                        // GENERATED ALWAYS AS (expr) STORED — computed column
                        gen = GeneratedKind.ALWAYS;
                        defExpr = ccn.rawExpr();
                    }
                    default -> { /* ignore */ }
                }
            }

            if (isSerial || gen == GeneratedKind.BY_DEFAULT
                    || (gen == GeneratedKind.ALWAYS && defExpr == null)) {
                // SERIAL / GENERATED AS IDENTITY → NOT NULL + auto-sequence + DEFAULT nextval
                // (GENERATED ALWAYS AS (expr) STORED has defExpr set above, skip sequence)
                nullable = false;
                String seqName = tableName + "_" + colName + "_seq";
                db.catalog().createSequence(schemaName, seqName,
                        1, 1, 1, Long.MAX_VALUE, false, /*ifNotExists*/ true);
                String seqRef = "\"" + schemaName + "\".\"" + seqName + "\"";
                defExpr = FunctionCall.simple("nextval",
                        List.of(new StringLiteral(seqRef)));
            }

            // Validate COLLATE clause
            String collation = cdn.collation();
            if (collation != null) {
                // Collation only valid on text-like types
                int typeOid = ti.type().oid();
                if (typeOid != org.pgjava.types.PgOid.TEXT
                        && typeOid != org.pgjava.types.PgOid.VARCHAR
                        && typeOid != org.pgjava.types.PgOid.BPCHAR
                        && typeOid != org.pgjava.types.PgOid.NAME) {
                    throw PgErrorException.error("42P21",
                            "collations are not supported by type "
                                    + ti.type().name()).build();
                }
                org.pgjava.types.PgCollation.resolveOrError(collation);
            }
            cols.add(new ColumnDef(colName, attnum++, ti.type(), ti.typmod(),
                    nullable, defExpr, gen, collation));
        }

        // Table-level constraints
        for (TableConstraintNode tcn : s.constraints()) {
            Constraint c = convertTableConstraint(tcn, schemaName);
            if (c != null) constraints.add(c);
        }

        // Check existence before creation (for IF NOT EXISTS + undo tracking)
        Schema targetSchema = db.catalog().getSchemaOrNull(schemaName);
        boolean alreadyExists = targetSchema != null && targetSchema.hasTable(tableName.toLowerCase());

        // Create the table in catalog (auto-creates PK index in catalog)
        TableDef t = db.catalog().createTable(schemaName, tableName,
                cols, constraints, s.temp(), s.ifNotExists());

        if (alreadyExists) return; // IF NOT EXISTS — table was pre-existing, nothing to undo

        // Register heap table in storage
        db.storage().createTable(t);

        // Register catalog indexes in storage (PK index already in t.indexes())
        for (IndexDef idx : t.indexes()) {
            org.pgjava.types.PgCollation idxColl = resolveIndexCollation(t, idx);
            db.storage().createIndex(idx, idxColl);
        }

        // Rollback: drop the table from storage and catalog
        final long tableOid = t.oid();
        final String finalSchema = schemaName, finalName = tableName;
        registerUndo(() -> {
            db.storage().dropTable(tableOid);
            try { db.catalog().dropTable(finalSchema, finalName, true, false); }
            catch (Exception e) { /* best-effort */ }
        });
    }

    // =========================================================================
    // DROP TABLE

    public void executeDropTable(DropTableStmt s, List<String> searchPath)
            throws SQLException {
        boolean cascade = s.behavior() == DropBehavior.CASCADE;
        for (RangeVar rv : s.relations()) {
            TableDef t = resolveTableDef(rv, searchPath, s.ifExists());
            if (t == null) continue; // ifExists + not found
            // Drop from storage first (has the OID)
            db.storage().dropTable(t.oid());
            db.catalog().dropTable(t.schemaName(), t.name(), false, cascade);
        }
    }

    // =========================================================================
    // TRUNCATE

    public void executeTruncate(TruncateStmt s, List<String> searchPath)
            throws SQLException {
        for (RangeVar rv : s.relations()) {
            TableDef t = db.catalog().resolveTable(rv.qualifiedName(), searchPath);

            // BEFORE STATEMENT TRUNCATE trigger
            TriggerExecutor.fireStatement(t, org.pgjava.catalog.TriggerDef.TRUNCATE,
                    org.pgjava.catalog.TriggerDef.BEFORE, db, searchPath);

            var ht = db.storage().table(t.oid());
            if (ht != null) ht.truncate();
            // Also clear all indexes on the table
            for (var idx : db.storage().indexesForTable(t.oid())) {
                idx.clear();
            }

            // RESTART IDENTITY: reset sequences owned by serial/identity columns
            if (s.restartSeqs()) {
                Schema schema = db.catalog().getSchemaOrNull(t.schemaName());
                if (schema != null) {
                    for (ColumnDef col : t.columns()) {
                        String seqName = t.name() + "_" + col.name() + "_seq";
                        SequenceDef seq = schema.sequence(seqName);
                        if (seq != null) seq.restart();
                    }
                }
            }

            // AFTER STATEMENT TRUNCATE trigger
            TriggerExecutor.fireStatement(t, org.pgjava.catalog.TriggerDef.TRUNCATE,
                    org.pgjava.catalog.TriggerDef.AFTER, db, searchPath);
        }
    }

    // =========================================================================
    // CREATE INDEX

    public void executeCreateIndex(CreateIndexStmt s, List<String> searchPath)
            throws SQLException {
        TableDef t = db.catalog().resolveTable(
                resolvedRelName(s.relation()), searchPath);

        List<IndexColumn> idxCols = s.indexParams().stream()
                .map(ie -> {
                    boolean asc        = ie.ordering() != SortByDir.DESC;
                    boolean nullsFirst = ie.nullsOrdering() == SortByNulls.FIRST;
                    String  col        = ie.colname() != null
                            ? ie.colname().toLowerCase()
                            : "__expr__"; // expression indexes — Phase 9
                    return new IndexColumn(col, asc, nullsFirst);
                })
                .toList();

        String indexName = s.name() != null
                ? s.name().toLowerCase()
                : t.name() + "_" + idxCols.stream()
                        .map(IndexColumn::column)
                        .collect(Collectors.joining("_")) + "_idx";

        // Check pre-existence for IF NOT EXISTS + undo tracking
        Schema indexSchema = db.catalog().getSchemaOrNull(t.schemaName());
        boolean idxAlreadyExists = indexSchema != null && indexSchema.index(indexName) != null;

        IndexDef idx = db.catalog().createIndex(
                t.schemaName(), indexName, t.name(),
                idxCols, s.unique(), s.ifNotExists());
        if (idxAlreadyExists) return; // IF NOT EXISTS — pre-existing
        org.pgjava.types.PgCollation idxColl = resolveIndexCollation(t, idx);
        org.pgjava.storage.BTreeIndex btree = db.storage().createIndex(idx, idxColl);

        // Populate index from existing rows (PostgreSQL builds the index immediately)
        org.pgjava.storage.HeapTable ht = db.storage().table(t.oid());
        if (ht != null) {
            Iterator<org.pgjava.storage.Row> scan = ht.fullScan();
            while (scan.hasNext()) {
                org.pgjava.storage.Row row = scan.next();
                Object[] keyVals = extractIndexKeys(idx, t, row.values());
                if (keyVals != null) {
                    btree.insert(keyVals, row.rowId());
                }
            }
        }

        // Rollback: drop the index
        final long idxOid = idx.oid();
        final String idxSchema = t.schemaName(), idxName = indexName;
        registerUndo(() -> {
            db.storage().dropIndex(idxOid);
            try { db.catalog().dropIndex(idxSchema, idxName, true, false); }
            catch (Exception e) { /* best-effort */ }
        });
    }

    /**
     * Extract index key values from a row's values array based on the index column definitions.
     */
    private Object[] extractIndexKeys(IndexDef idx, TableDef table, Object[] values) {
        var indexCols = idx.columns();
        Object[] keys = new Object[indexCols.size()];
        for (int i = 0; i < indexCols.size(); i++) {
            ColumnDef colDef = table.column(indexCols.get(i).column());
            if (colDef == null) return null;
            int pos = colDef.attnum() - 1;
            keys[i] = (pos >= 0 && pos < values.length) ? values[pos] : null;
        }
        return keys;
    }

    // =========================================================================
    // DROP INDEX

    public void executeDropIndex(DropIndexStmt s, List<String> searchPath)
            throws SQLException {
        boolean cascade = s.behavior() == DropBehavior.CASCADE;
        for (String rawName : s.indexNames()) {
            String[] parts = splitQualified(rawName);
            String   schema    = parts[0];
            String   indexName = parts[1];

            // Find schema if unqualified
            if (schema == null) {
                schema = findIndexSchema(indexName, searchPath);
            }
            if (schema == null) {
                if (s.ifExists()) continue;
                throw PgErrorException.error("42704", "index \"" + indexName + "\" does not exist").build();
            }
            // Get the IndexDef before dropping (need OID for storage)
            Schema sc = db.catalog().getSchemaOrNull(schema);
            IndexDef idx = (sc != null) ? sc.index(indexName) : null;
            if (idx == null) {
                if (s.ifExists()) continue;
                throw PgErrorException.error("42704", "index \"" + indexName + "\" does not exist").build();
            }
            db.storage().dropIndex(idx.oid());
            db.catalog().dropIndex(schema, indexName, false, cascade);
        }
    }

    // =========================================================================
    // CREATE SEQUENCE

    public void executeCreateSequence(CreateSequenceStmt s, List<String> searchPath)
            throws SQLException {
        String schemaName = resolveCreateSchema(s.sequence(), searchPath);
        String seqName    = s.sequence().relName().toLowerCase();
        SeqParams p       = parseSeqOptions(s.options());

        Schema seqSchema = db.catalog().getSchemaOrNull(schemaName);
        boolean seqAlreadyExists = seqSchema != null && seqSchema.sequence(seqName) != null;

        db.catalog().createSequence(schemaName, seqName,
                p.start, p.increment, p.minVal, p.maxVal, p.cycle, s.ifNotExists());

        if (!seqAlreadyExists) {
            final String fs = schemaName, fn = seqName;
            registerUndo(() -> {
                try { db.catalog().dropSequence(fs, fn, true, false); }
                catch (Exception e) { /* best-effort */ }
            });
        }
    }

    // =========================================================================
    // DROP SEQUENCE

    public void executeDropSequence(DropSequenceStmt s, List<String> searchPath)
            throws SQLException {
        boolean cascade = s.behavior() == DropBehavior.CASCADE;
        for (RangeVar rv : s.sequences()) {
            String schemaName = rv.schemaName() != null
                    ? rv.schemaName().toLowerCase()
                    : findSequenceSchema(rv.relName().toLowerCase(), searchPath);
            if (schemaName == null) {
                if (s.ifExists()) continue;
                throw PgErrorException.error("42P01",
                        "sequence \"" + rv.relName() + "\" does not exist").build();
            }
            db.catalog().dropSequence(schemaName, rv.relName().toLowerCase(),
                    s.ifExists(), cascade);
        }
    }

    // =========================================================================
    // ALTER SEQUENCE

    public void executeAlterSequence(AlterSequenceStmt s, List<String> searchPath)
            throws SQLException {
        String schemaName = s.sequence().schemaName() != null
                ? s.sequence().schemaName().toLowerCase()
                : findSequenceSchema(s.sequence().relName().toLowerCase(), searchPath);
        if (schemaName == null)
            throw PgErrorException.error("42P01",
                    "sequence \"" + s.sequence().relName() + "\" does not exist").build();
        Schema schema = db.catalog().getSchemaOrNull(schemaName);
        if (schema == null) throw PgErrorException.error("3F000",
                "schema \"" + schemaName + "\" does not exist").build();
        SequenceDef seq = schema.sequence(s.sequence().relName().toLowerCase());
        if (seq == null) throw PgErrorException.error("42P01",
                "sequence \"" + s.sequence().relName() + "\" does not exist").build();

        // RESTART [WITH n] — only mutation currently supported via setval
        for (DefElem de : s.options()) {
            if ("restart".equalsIgnoreCase(de.name())) {
                long val = de.value() instanceof IntegerLiteral il
                        ? il.value() : seq.start();
                seq.setval(val - seq.increment()); // setval positions so nextval returns val
            }
        }
    }

    // =========================================================================
    // CREATE VIEW

    public void executeCreateView(CreateViewStmt s, List<String> searchPath,
                                   String originalSql)
            throws SQLException {
        String schemaName = resolveCreateSchema(s.view(), searchPath);
        String viewName   = s.view().relName().toLowerCase();
        String viewSql = extractViewSelect(originalSql);

        Schema viewSchema = db.catalog().getSchemaOrNull(schemaName);
        boolean viewAlreadyExists = viewSchema != null && viewSchema.view(viewName) != null;

        db.catalog().createView(schemaName, viewName, viewSql, s.query(),
                s.aliases() != null ? s.aliases() : List.of(), s.replace());

        if (!viewAlreadyExists) {
            final String fs = schemaName, fn = viewName;
            registerUndo(() -> {
                try { db.catalog().dropView(fs, fn, true, false); }
                catch (Exception e) { /* best-effort */ }
            });
        }
    }

    /** Extract the SELECT body from a CREATE VIEW statement. */
    private static String extractViewSelect(String sql) {
        if (sql == null) return null;
        // Find "AS" keyword followed by SELECT — case-insensitive
        int idx = sql.toUpperCase().indexOf(" AS ");
        if (idx >= 0) {
            return sql.substring(idx + 4).trim().replaceAll(";\\s*$", "");
        }
        return sql;
    }

    // =========================================================================
    // DROP VIEW

    public void executeDropView(DropViewStmt s, List<String> searchPath)
            throws SQLException {
        boolean cascade = s.behavior() == DropBehavior.CASCADE;
        for (RangeVar rv : s.views()) {
            String schemaName = rv.schemaName() != null
                    ? rv.schemaName().toLowerCase()
                    : findViewSchema(rv.relName().toLowerCase(), searchPath);
            if (schemaName == null) {
                if (s.ifExists()) continue;
                throw PgErrorException.error("42P01",
                        "view \"" + rv.relName() + "\" does not exist").build();
            }
            db.catalog().dropView(schemaName, rv.relName().toLowerCase(),
                    s.ifExists(), cascade);
        }
    }

    // =========================================================================
    // ALTER TABLE

    public void executeAlterTable(AlterTableStmt s, List<String> searchPath)
            throws SQLException {
        TableDef t = db.catalog().resolveTable(resolvedRelName(s.relation()), searchPath);
        for (AlterTableCmd cmd : s.cmds()) {
            t = executeAlterCmd(t, cmd, searchPath);
        }
    }

    private TableDef executeAlterCmd(TableDef t, AlterTableCmd cmd, List<String> searchPath)
            throws SQLException {
        switch (cmd.subtype()) {

            case ADD_COLUMN -> {
                ColumnDefNode cdn    = cmd.def();
                String        colName = cdn.colname().toLowerCase();
                if (t.column(colName) != null)
                    throw PgErrorException.error("42701",
                            "column \"" + colName + "\" of relation \""
                                    + t.name() + "\" already exists").build();
                int      attnum   = t.columnCount() + 1;
                TypeInfo ti       = resolveType(cdn.typeName(), searchPath);
                boolean  nullable = true;
                Expr     defExpr  = null;
                GeneratedKind gen = GeneratedKind.NONE;
                List<Constraint> newConstraints = new ArrayList<>();

                for (ColumnConstraintNode ccn : cdn.constraints()) {
                    switch (ccn.type()) {
                        case NOT_NULL -> nullable = false;
                        case NULL     -> nullable = true;
                        case DEFAULT  -> defExpr = ccn.rawExpr();
                        case PRIMARY  -> {
                            nullable = false;
                            newConstraints.add(new Constraint.PrimaryKey(
                                    ccn.constraintName(), List.of(colName)));
                        }
                        case UNIQUE -> newConstraints.add(new Constraint.Unique(
                                ccn.constraintName(), List.of(colName)));
                        case CHECK -> newConstraints.add(new Constraint.Check(
                                ccn.constraintName(), colName, ccn.rawExpr(), null));
                        default -> { /* ignore */ }
                    }
                }

                ColumnDef newCol = new ColumnDef(colName, attnum, ti.type(), ti.typmod(),
                        nullable, defExpr, gen,
                        cmd.def() != null ? cmd.def().collation() : null);
                t.addColumn(newCol);
                for (Constraint c : newConstraints) t.addConstraint(c);

                // Fill the new column's default into all existing rows (matches PG behaviour
                // for ADD COLUMN ... NOT NULL DEFAULT ...).
                if (defExpr != null && eval != null) {
                    org.pgjava.storage.HeapTable ht = db.storage().table(t.oid());
                    if (ht != null && ht.totalRows() > 0) {
                        Object defaultVal = null;
                        try { defaultVal = eval.eval(defExpr, EvalContext.empty()); }
                        catch (SQLException e) { throw e; }
                        catch (Exception e) {
                            // Non-SQL errors (e.g. UnsupportedOperationException for
                            // complex defaults like nextval) — fall back to null
                        }
                        ht.constraintLock().lock();
                        try { ht.widenAllRows(attnum - 1, defaultVal); }
                        finally { ht.constraintLock().unlock(); }
                    }
                }
            }

            case DROP_COLUMN -> {
                String colName = cmd.name().toLowerCase();
                ColumnDef col = t.column(colName);
                if (col == null) {
                    if (cmd.behavior() == DropBehavior.RESTRICT)
                        throw PgErrorException.error("42703",
                                "column \"" + colName + "\" of relation \""
                                        + t.name() + "\" does not exist").build();
                    return t; // IF EXISTS implied
                }
                // Check if any triggers reference this column in UPDATE OF
                for (TriggerDef trig : t.triggers()) {
                    if (trig.columns() != null && trig.columns().stream()
                            .anyMatch(c -> c.equalsIgnoreCase(colName))) {
                        if (cmd.behavior() == DropBehavior.CASCADE) {
                            t.dropTrigger(trig.name());
                        } else {
                            throw PgErrorException.error("2BP01",
                                    "cannot drop column " + colName +
                                    " because trigger " + trig.name() + " depends on it"
                            ).hint("Use DROP ... CASCADE to drop dependent objects too.").build();
                        }
                    }
                }
                // Drop indexes that reference this column
                Schema dropColSchema = db.catalog().getSchemaOrNull(t.schemaName());
                for (IndexDef idx : new ArrayList<>(t.indexes())) {
                    boolean usesCol = idx.columns().stream()
                            .anyMatch(ic -> ic.column().equalsIgnoreCase(colName));
                    if (usesCol) {
                        t.dropIndex(idx.name());
                        if (dropColSchema != null) dropColSchema.removeIndex(idx.name());
                        db.storage().dropIndex(idx.oid());
                    }
                }
                t.dropColumn(colName);
            }

            case SET_NOT_NULL -> {
                String    colName = cmd.name().toLowerCase();
                ColumnDef col     = requireColumn(t, colName);
                t.updateColumn(new ColumnDef(col.name(), col.attnum(), col.type(),
                        col.typmod(), false, col.defaultExpr(), col.generated(),
                        col.collation()));
            }

            case DROP_NOT_NULL -> {
                String    colName = cmd.name().toLowerCase();
                ColumnDef col     = requireColumn(t, colName);
                t.updateColumn(new ColumnDef(col.name(), col.attnum(), col.type(),
                        col.typmod(), true, col.defaultExpr(), col.generated(),
                        col.collation()));
            }

            case SET_DEFAULT -> {
                String    colName = cmd.name().toLowerCase();
                ColumnDef col     = requireColumn(t, colName);
                // Default expression is in cmd.def().constraints()
                Expr defExpr = null;
                if (cmd.def() != null) {
                    for (ColumnConstraintNode ccn : cmd.def().constraints()) {
                        if (ccn.type() == ConstrType.DEFAULT) { defExpr = ccn.rawExpr(); break; }
                    }
                }
                t.updateColumn(new ColumnDef(col.name(), col.attnum(), col.type(),
                        col.typmod(), col.nullable(), defExpr, col.generated(),
                        col.collation()));
            }

            case DROP_DEFAULT -> {
                String    colName = cmd.name().toLowerCase();
                ColumnDef col     = requireColumn(t, colName);
                t.updateColumn(new ColumnDef(col.name(), col.attnum(), col.type(),
                        col.typmod(), col.nullable(), null, col.generated(),
                        col.collation()));
            }

            case ADD_CONSTRAINT -> {
                // Constraint is encoded in cmd.def() as a single-column ColumnDefNode
                // with the constraint in its constraints list, OR in a special form.
                // For table-level constraints (PK/UNIQUE/FK/CHECK), the AST converter
                // puts the constraint info in cmd.def().
                if (cmd.def() != null && cmd.def().constraints() != null) {
                    for (ColumnConstraintNode ccn : cmd.def().constraints()) {
                        Constraint c = convertColConstraintToTableConstraint(
                                ccn, t.schemaName(), cmd.name());
                        if (c != null) t.addConstraint(c);
                    }
                }
            }

            case DROP_CONSTRAINT -> {
                String cName = cmd.name();
                if (cName != null) t.dropConstraint(cName);
            }

            case ALTER_COLUMN_TYPE -> {
                String colName = cmd.name().toLowerCase();
                ColumnDef col  = requireColumn(t, colName);
                TypeInfo ti    = cmd.def() != null
                        ? resolveType(cmd.def().typeName(), searchPath)
                        : new TypeInfo(col.type(), col.typmod());
                t.updateColumn(new ColumnDef(col.name(), col.attnum(), ti.type(), ti.typmod(),
                        col.nullable(), col.defaultExpr(), col.generated(),
                        col.collation()));
            }

            case RENAME_COLUMN -> {
                // cmd.name() = old name, new name is in cmd.def().colname()
                if (cmd.def() != null) {
                    String oldName = cmd.name().toLowerCase();
                    String newName = cmd.def().colname().toLowerCase();
                    ColumnDef col = requireColumn(t, oldName);
                    t.dropColumn(oldName);
                    t.addColumn(new ColumnDef(newName, col.attnum(), col.type(),
                            col.typmod(), col.nullable(), col.defaultExpr(), col.generated(),
                            col.collation()));
                    // Update index definitions that reference the renamed column
                    renameColumnInIndexes(t, oldName, newName, searchPath);
                }
            }

            case RENAME_TABLE -> {
                // cmd.name() = new table name
                String newName = cmd.name();
                if (newName == null) throw PgErrorException.error("42601", "RENAME TABLE: no new name").build();
                newName = newName.toLowerCase();
                // Find the schema containing this table and re-register under new name
                Schema schema = db.catalog().getSchemaOrNull(t.schemaName());
                if (schema == null) throw PgErrorException.error("3F000", "schema \"" + t.schemaName() + "\" does not exist").build();
                schema.removeTable(t.name());
                // Create new TableDef with the new name but same OID, columns, constraints, indexes
                TableDef renamed = new TableDef(t.oid(), newName, t.schemaName(), t.isTemp());
                for (ColumnDef col : t.columns()) renamed.addColumn(col);
                for (Constraint con : t.constraints()) renamed.addConstraint(con);
                for (IndexDef idx : t.indexes()) renamed.addIndex(idx);
                schema.addTable(renamed);
                t = renamed;  // update reference for subsequent cmds in same ALTER
            }

            default -> { /* SET_OPTIONS, RESET_OPTIONS, OTHER — ignore */ }
        }
        return t;
    }

    // =========================================================================
    // Helpers

    /** Determine schema name for a CREATE operation. */
    private String resolveCreateSchema(RangeVar rv, List<String> searchPath) {
        if (rv.schemaName() != null) return rv.schemaName().toLowerCase();
        for (String s : searchPath) {
            if (!s.equalsIgnoreCase("pg_catalog") && !s.equalsIgnoreCase("information_schema"))
                return s.toLowerCase();
        }
        return "public";
    }

    /** Resolve a RangeVar to a qualified name string for catalog lookup. */
    private String resolvedRelName(RangeVar rv) {
        return rv.schemaName() != null
                ? rv.schemaName().toLowerCase() + "." + rv.relName().toLowerCase()
                : rv.relName().toLowerCase();
    }

    /**
     * Resolve a table reference for DROP/TRUNCATE. Returns null if ifExists=true and not found.
     */
    private TableDef resolveTableDef(RangeVar rv, List<String> searchPath, boolean ifExists)
            throws SQLException {
        try {
            return db.catalog().resolveTable(resolvedRelName(rv), searchPath);
        } catch (SQLException e) {
            if (ifExists && "42P01".equals(e.getSQLState())) return null;
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // Type resolution

    private record TypeInfo(PgType type, int typmod) {}

    private TypeInfo resolveType(TypeName tn) throws SQLException {
        return resolveType(tn, null);
    }

    private TypeInfo resolveType(TypeName tn, List<String> searchPath) throws SQLException {
        if (tn == null) throw PgErrorException.error("42601", "missing type specification").build();
        String simple = tn.simpleName();

        // Handle serial as the underlying integer type
        simple = switch (simple) {
            case "serial"      -> "int4";
            case "bigserial"   -> "int8";
            case "smallserial" -> "int2";
            default            -> simple;
        };

        // Check schema-local types first (user-defined enums/domains)
        PgType type = null;

        // If schema-qualified (e.g. s1.mood), look directly in that schema
        if (tn.names().size() > 1) {
            String schemaQual = tn.names().get(tn.names().size() - 2).toLowerCase();
            if (!schemaQual.equals("pg_catalog")) {
                Schema schema = db.catalog().allSchemas().get(schemaQual);
                if (schema != null) {
                    type = schema.type(simple);
                }
            }
        }

        // Otherwise search via search path
        if (type == null && searchPath != null) {
            for (String schemaName : searchPath) {
                Schema schema = db.catalog().allSchemas().get(schemaName);
                if (schema != null) {
                    type = schema.type(simple);
                    if (type != null) break;
                }
            }
        } else if (type == null) {
            // Fallback: check all schemas
            for (Schema schema : db.catalog().allSchemas().values()) {
                type = schema.type(simple);
                if (type != null) break;
            }
        }

        // Fall back to global registry (built-in types only)
        if (type == null) {
            type = types.byTypeName(simple);
        }
        if (type == null) {
            // Try with schema prefix stripped (pg_catalog.int4 → int4)
            type = types.byName(simple);
        }
        if (type == null)
            throw PgErrorException.error("42704", "type \"" + tn.simpleName() + "\" does not exist").build();

        int typmod = computeTypmod(simple, tn.typmods());

        // Array type
        if (tn.arrayBounds() > 0) {
            type = types.arrayOf(type);
        }

        return new TypeInfo(type, typmod);
    }

    private boolean isSerialType(TypeName tn) {
        if (tn == null) return false;
        return switch (tn.simpleName()) {
            case "serial", "bigserial", "smallserial" -> true;
            default -> false;
        };
    }

    private int computeTypmod(String typeName, List<Node> typmods) {
        if (typmods == null || typmods.isEmpty()) return -1;
        return switch (typeName) {
            case "varchar", "character varying", "bpchar", "character", "char" -> {
                int n = extractInt(typmods.get(0), 1);
                yield n + 4; // PostgreSQL convention: typmod = length + 4
            }
            case "numeric", "decimal" -> {
                int precision = extractInt(typmods.get(0), 0);
                int scale     = typmods.size() > 1 ? extractInt(typmods.get(1), 0) : 0;
                // ((precision << 16) | scale) + 4
                yield ((precision << 16) | scale) + 4;
            }
            case "time", "timestamp", "timestamptz", "timetz" -> {
                int frac = extractInt(typmods.get(0), 6);
                yield frac; // precision (0-6)
            }
            default -> -1;
        };
    }

    private int extractInt(Node node, int fallback) {
        if (node instanceof IntegerLiteral il) return (int) il.value();
        return fallback;
    }

    // -------------------------------------------------------------------------
    // Constraint conversion

    private Constraint convertTableConstraint(TableConstraintNode tcn, String schemaName) {
        return switch (tcn.type()) {
            case PRIMARY -> new Constraint.PrimaryKey(tcn.constraintName(),
                    toLower(tcn.keys()));
            case UNIQUE  -> new Constraint.Unique(tcn.constraintName(),
                    toLower(tcn.keys()));
            case CHECK   -> new Constraint.Check(tcn.constraintName(), null,
                    tcn.rawExpr(), null);
            case FK      -> new Constraint.ForeignKey(
                    tcn.constraintName(),
                    toLower(tcn.fkAttrs()),
                    tcn.pktable() != null && tcn.pktable().schemaName() != null
                            ? tcn.pktable().schemaName().toLowerCase() : schemaName,
                    tcn.pktable() != null ? tcn.pktable().relName().toLowerCase() : null,
                    toLower(tcn.keys()),
                    tcn.fkDelAction(),
                    tcn.fkUpdAction());
            default -> null;
        };
    }

    private Constraint convertColConstraintToTableConstraint(
            ColumnConstraintNode ccn, String schemaName, String colName) {
        return switch (ccn.type()) {
            case PRIMARY -> {
                // Table-level: column list in pkAttrs; column-level: colName
                List<String> cols = ccn.pkAttrs() != null && !ccn.pkAttrs().isEmpty()
                        ? toLower(ccn.pkAttrs())
                        : colName != null ? List.of(colName) : List.of();
                yield new Constraint.PrimaryKey(ccn.constraintName(), cols);
            }
            case UNIQUE  -> {
                List<String> cols = ccn.pkAttrs() != null && !ccn.pkAttrs().isEmpty()
                        ? toLower(ccn.pkAttrs())
                        : colName != null ? List.of(colName) : List.of();
                yield new Constraint.Unique(ccn.constraintName(), cols);
            }
            case CHECK   -> new Constraint.Check(ccn.constraintName(),
                    colName, ccn.rawExpr(), null);
            case FK      -> buildFkFromColumn(ccn,
                    ccn.fkAttrs() != null && !ccn.fkAttrs().isEmpty()
                            ? ccn.fkAttrs().get(0) : colName,
                    schemaName);
            default -> null;
        };
    }

    private Constraint.ForeignKey buildFkFromColumn(
            ColumnConstraintNode ccn, String colName, String schemaName) {
        return new Constraint.ForeignKey(
                ccn.constraintName(),
                List.of(colName),
                ccn.pktable() != null && ccn.pktable().schemaName() != null
                        ? ccn.pktable().schemaName().toLowerCase() : schemaName,
                ccn.pktable() != null ? ccn.pktable().relName().toLowerCase() : null,
                ccn.pkAttrs() != null ? toLower(ccn.pkAttrs()) : List.of(),
                ccn.fkDelAction(),
                ccn.fkUpdAction());
    }

    // -------------------------------------------------------------------------
    // Sequence options parsing

    private record SeqParams(long start, long increment, long minVal, long maxVal, boolean cycle) {}

    private SeqParams parseSeqOptions(List<DefElem> opts) {
        long    start     = 1L;
        long    increment = 1L;
        long    minVal    = Long.MIN_VALUE;
        long    maxVal    = Long.MAX_VALUE;
        boolean cycle     = false;

        if (opts == null) return new SeqParams(start, increment, minVal, maxVal, cycle);

        for (DefElem de : opts) {
            switch (de.name().toLowerCase()) {
                case "start"     -> start     = toLong(de.value(), 1L);
                case "increment" -> increment = toLong(de.value(), 1L);
                case "minvalue"  -> minVal    = toLong(de.value(), Long.MIN_VALUE);
                case "maxvalue"  -> maxVal    = toLong(de.value(), Long.MAX_VALUE);
                // pg_query: CYCLE → defname="cycle", arg=null (no-arg flag means true)
                //           NO CYCLE → defname="cycle", arg=Boolean(false)
                case "cycle"     -> cycle     = de.value() == null || toBoolean(de.value(), false);
                default          -> { /* cache, no_minvalue, etc. — ignore */ }
            }
        }
        // If start not explicitly set, default based on increment direction
        return new SeqParams(start, increment, minVal, maxVal, cycle);
    }

    private long toLong(Node n, long fallback) {
        if (n instanceof IntegerLiteral il) return il.value();
        if (n instanceof StringLiteral  sl) {
            try { return Long.parseLong(sl.value()); } catch (NumberFormatException ignore) {}
        }
        return fallback;
    }

    private boolean toBoolean(Node n, boolean fallback) {
        if (n instanceof BooleanLiteral bl) return bl.value();
        if (n instanceof IntegerLiteral il) return il.value() != 0;
        return fallback;
    }

    // -------------------------------------------------------------------------
    // Schema-search helpers (for DROP operations on unqualified names)

    private String findIndexSchema(String indexName, List<String> searchPath) {
        for (String sp : searchPath) {
            Schema s = db.catalog().getSchemaOrNull(sp);
            if (s != null && s.index(indexName) != null) return sp;
        }
        return null;
    }

    private String findSequenceSchema(String seqName, List<String> searchPath) {
        for (String sp : searchPath) {
            Schema s = db.catalog().getSchemaOrNull(sp);
            if (s != null && s.sequence(seqName) != null) return sp;
        }
        return null;
    }

    private String findViewSchema(String viewName, List<String> searchPath) {
        for (String sp : searchPath) {
            Schema s = db.catalog().getSchemaOrNull(sp);
            if (s != null && s.view(viewName) != null) return sp;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Misc utilities

    /**
     * Determine the collation to use for a BTreeIndex based on the indexed columns.
     * Uses the first text column's explicit collation if set, otherwise null (database default).
     */
    /**
     * After renaming a column, update all index definitions that reference the old name.
     */
    private void renameColumnInIndexes(TableDef t, String oldName, String newName,
                                       List<String> searchPath) {
        Schema schema = db.catalog().getSchemaOrNull(t.schemaName());
        if (schema == null) return;
        for (IndexDef idx : new ArrayList<>(t.indexes())) {
            boolean affected = idx.columns().stream()
                    .anyMatch(ic -> ic.column().equalsIgnoreCase(oldName));
            if (!affected) continue;
            List<IndexColumn> newCols = idx.columns().stream()
                    .map(ic -> ic.column().equalsIgnoreCase(oldName)
                            ? new IndexColumn(newName, ic.ascending(), ic.nullsFirst())
                            : ic)
                    .toList();
            IndexDef updated = new IndexDef(idx.oid(), idx.name(), idx.schemaName(),
                    idx.tableName(), idx.tableOid(), newCols, idx.unique(),
                    idx.primary(), idx.accessMethod());
            // Replace in schema registry
            schema.removeIndex(idx.name());
            schema.addIndex(updated);
            // Replace in table's index list
            t.dropIndex(idx.name());
            t.addIndex(updated);
        }
    }

    private org.pgjava.types.PgCollation resolveIndexCollation(TableDef t, IndexDef idx) {
        for (IndexColumn ic : idx.columns()) {
            ColumnDef col = t.column(ic.column());
            if (col != null && col.collation() != null) {
                return org.pgjava.types.PgCollation.resolve(col.collation());
            }
        }
        return null; // database default
    }

    private ColumnDef requireColumn(TableDef t, String colName) throws SQLException {
        ColumnDef col = t.column(colName);
        if (col == null) throw PgErrorException.error("42703",
                "column \"" + colName + "\" of relation \"" + t.name() + "\" does not exist").build();
        return col;
    }

    private List<String> toLower(List<String> names) {
        if (names == null) return List.of();
        return names.stream().map(String::toLowerCase).toList();
    }

    /** Split a possibly-qualified name into [schema, name]. schema may be null. */
    private String[] splitQualified(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) return new String[]{name.substring(0, dot).toLowerCase(),
                                         name.substring(dot + 1).toLowerCase()};
        return new String[]{null, name.toLowerCase()};
    }

    // =========================================================================
    // CREATE FUNCTION (SQL-language only)

    public void executeCreateFunction(CreateFunctionStmt s, List<String> searchPath)
            throws SQLException {

        // Resolve function name
        String funcName;
        String schemaName;
        if (s.funcname().size() == 2) {
            schemaName = s.funcname().get(0).toLowerCase();
            funcName   = s.funcname().get(1).toLowerCase();
        } else {
            schemaName = searchPath.isEmpty() ? "public" : searchPath.get(0);
            funcName   = s.funcname().get(0).toLowerCase();
        }

        // Extract options
        String language = "sql";
        String body = null;
        boolean strict = false;
        for (DefElem opt : s.options()) {
            switch (opt.name()) {
                case "language" -> {
                    if (opt.value() instanceof StringLiteral sl)
                        language = sl.value().toLowerCase();
                }
                case "as" -> {
                    // pg_query stores body in "as" option as a list of StringLiterals
                    if (opt.value() instanceof StringLiteral sl) body = sl.value();
                }
                case "strict" -> strict = true;
            }
        }

        if (body == null) {
            throw PgErrorException.error("42P13",
                    "no function body specified for \"" + funcName + "\"").build();
        }
        if (!"sql".equals(language) && !"plpgsql".equals(language)) {
            // Accept but ignore unknown languages (C, plperl, etc.)
            return;
        }

        // Resolve parameter types (IN, INOUT, VARIADIC); collect OUT params separately
        List<PgType> argTypes = new ArrayList<>();
        List<String> argNames = new ArrayList<>();
        List<org.pgjava.sql.ast.Expr> argDefaults = new ArrayList<>();
        List<FunctionParameter> outParams = new ArrayList<>();
        for (FunctionParameter p : s.params()) {
            if (p.mode() == FunctionParameterMode.OUT) {
                outParams.add(p);
                continue;
            }
            PgType pt = resolveType(p.argType(), searchPath).type();
            argTypes.add(pt);
            argNames.add(p.name());
            if (p.defexpr() != null) {
                argDefaults.add(p.defexpr());
            }
        }
        // INOUT params also appear as OUT — track them for OUT handling
        boolean isVariadic = false;
        for (FunctionParameter p : s.params()) {
            if (p.mode() == FunctionParameterMode.INOUT) {
                outParams.add(p);
            }
            if (p.mode() == FunctionParameterMode.VARIADIC) {
                isVariadic = true;
            }
        }

        // Resolve return type
        PgType returnType = null;
        boolean isTriggerFunction = false;
        if (s.returnType() != null) {
            String rtName = s.returnType().simpleName().toLowerCase();
            if ("trigger".equals(rtName)) {
                // Trigger functions don't have a real return type
                isTriggerFunction = true;
            } else {
                returnType = resolveType(s.returnType(), searchPath).type();
            }
        }

        final String bodyText = body;
        Database database = this.db;
        List<String> funcSearchPath = searchPath;
        final PgType funcReturnType = returnType;
        final List<String> funcArgNames = argNames;
        // Collect OUT parameter names for the interpreter
        final List<String> funcOutParamNames = outParams.isEmpty() ? null
                : outParams.stream().map(FunctionParameter::name).toList();

        boolean isSetOf = s.returnType() != null && s.returnType().setOf();

        if (isSetOf && "plpgsql".equals(language)) {
            // PL/pgSQL set-returning function: register as SRF
            org.pgjava.catalog.SrfDef.SrfImpl srfImpl = args -> {
                var plBody = org.pgjava.sql.parser.PlPgSqlBodyParser.parse(bodyText);
                var interp = new org.pgjava.executor.plpgsql.PlPgSqlInterpreter(
                        database, funcSearchPath, args, funcArgNames, funcReturnType);
                return interp.executeSrf(plBody);
            };

            long oid = db.catalog().nextOid();
            // Column name is the function name (PostgreSQL behavior: SELECT f() → column "f")
            String colName = funcName;
            var srf = new org.pgjava.catalog.SrfDef(oid, funcName, schemaName,
                    argTypes, java.util.List.of(colName), false, srfImpl);

            if (s.replace()) {
                db.catalog().functions().unregister(funcName, argTypes);
            }
            db.catalog().functions().registerSrf(srf);
            return;
        }

        FunctionDef.ScalarImpl impl;
        if ("plpgsql".equals(language)) {
            // PL/pgSQL: parse body into PL/pgSQL AST, execute via interpreter
            impl = args -> {
                var plBody = org.pgjava.sql.parser.PlPgSqlBodyParser.parse(bodyText);
                var interp = new org.pgjava.executor.plpgsql.PlPgSqlInterpreter(
                        database, funcSearchPath, args, funcArgNames, funcReturnType);
                if (funcOutParamNames != null) interp.setOutParamNames(funcOutParamNames);
                // Check if this is being called as a trigger function
                var trigCtx = TriggerExecutor.PENDING_TRIGGER_CTX.get();
                if (trigCtx != null) {
                    interp.setTriggerContext(trigCtx);
                    return interp.executeTrigger(plBody);
                }
                return interp.execute(plBody);
            };
        } else {
            // SQL language: parse body as SQL, execute all SELECT statements,
            // return result of last SELECT (PG behavior)
            impl = args -> {
                var stmts = org.pgjava.sql.parser.ParserProvider.parse(bodyText);
                if (stmts.isEmpty()) return null;

                Evaluator bodyEval = new Evaluator(database.catalog().functions(),
                        database.collation());
                bodyEval.setFunctionParams(args);
                Planner planner = new Planner(database.catalog(), database.storage(),
                        database.txManager(), bodyEval, funcSearchPath);
                planner.setSnapshot(database.txManager().snapshotFor(0L));

                Object result = null;
                for (Stmt bodyStmt : stmts) {
                    if (bodyStmt instanceof SelectStmt sel) {
                        Operator root = planner.planSelect(sel);
                        root.open();
                        try {
                            org.pgjava.storage.Row row = root.next();
                            if (row != null) {
                                result = row.values().length == 1 ? row.values()[0] : row.values();
                            } else {
                                result = null;
                            }
                        } finally {
                            root.close();
                        }
                    }
                    // Non-SELECT statements (DML) in SQL functions are skipped for now;
                    // full support requires session context threading.
                }
                return result;
            };
        }

        long oid = db.catalog().nextOid();
        FunctionDef fn = new FunctionDef(oid, funcName, schemaName, argTypes,
                returnType != null ? returnType : types.byName("text"),
                strict, isVariadic, impl, bodyText, argNames,
                argDefaults.isEmpty() ? null : argDefaults);

        // OR REPLACE: remove existing function with same name and exact arg types
        if (s.replace()) {
            db.catalog().functions().unregisterExact(funcName, argTypes);
        }

        db.catalog().functions().register(fn);

        // Rollback: unregister the function
        final String finalFuncName = funcName;
        final List<PgType> finalArgTypes = argTypes;
        registerUndo(() -> db.catalog().functions().unregisterExact(finalFuncName, finalArgTypes));
    }

    // =========================================================================
    // DROP FUNCTION

    public void executeDropFunction(DropFunctionStmt s, List<String> searchPath)
            throws SQLException {
        for (DropFunctionStmt.Target target : s.targets()) {
            String funcName;
            if (target.name().size() == 2) {
                funcName = target.name().get(1).toLowerCase();
            } else {
                funcName = target.name().get(0).toLowerCase();
            }

            FunctionRegistry reg = db.catalog().functions();

            if (target.argTypes() != null) {
                // Arg types specified — resolve and drop the exact overload
                List<PgType> resolvedArgs = new ArrayList<>();
                for (TypeName tn : target.argTypes()) {
                    // Use resolveType to handle both built-in and user-defined types
                    TypeInfo typeInfo = resolveType(tn, searchPath);
                    resolvedArgs.add(typeInfo.type());
                }

                // Check for dependent triggers
                handleTriggerDependencies(funcName);

                boolean removed = reg.unregisterExact(funcName, resolvedArgs);
                if (!removed && !s.ifExists()) {
                    throw PgErrorException.error("42883",
                            "function " + funcName + "(" +
                            resolvedArgs.stream().map(PgType::name)
                                    .collect(java.util.stream.Collectors.joining(", ")) +
                            ") does not exist").build();
                }
            } else {
                // No arg types — must be unambiguous (exactly one overload)
                List<FunctionDef> overloads = reg.findScalarOverloads(funcName);
                if (overloads.isEmpty()) {
                    if (!s.ifExists()) {
                        throw PgErrorException.error("42883",
                                "function " + funcName + " does not exist").build();
                    }
                } else if (overloads.size() > 1) {
                    throw PgErrorException.error("42725",
                            "function name \"" + funcName +
                            "\" is not unique, specify argument types").build();
                } else {
                    // Check for dependent triggers
                    handleTriggerDependencies(funcName);

                    reg.unregisterExact(funcName, overloads.get(0).argTypes());
                }
            }
        }
    }

    /**
     * Check if any triggers depend on the named function.
     * With CASCADE: drop the dependent triggers.
     * Without CASCADE (RESTRICT): error if dependents exist.
     */
    /**
     * Error if any triggers depend on the named function (RESTRICT semantics — PG default).
     */
    private void handleTriggerDependencies(String funcName) throws SQLException {
        for (var schema : db.catalog().allSchemas().values()) {
            for (var table : schema.tables().values()) {
                for (var trig : table.triggers()) {
                    if (trig.functionName().equalsIgnoreCase(funcName)) {
                        throw PgErrorException.error("2BP01",
                                "cannot drop function " + funcName +
                                "() because trigger " + trig.name() +
                                " on table " + table.name() + " depends on it"
                        ).hint("Use DROP ... CASCADE to drop dependent objects too.").build();
                    }
                }
            }
            // Also check triggers on views (INSTEAD OF triggers)
            for (var view : schema.views().values()) {
                for (var trig : view.triggers()) {
                    if (trig.functionName().equalsIgnoreCase(funcName)) {
                        throw PgErrorException.error("2BP01",
                                "cannot drop function " + funcName +
                                "() because trigger " + trig.name() +
                                " on view " + view.name() + " depends on it"
                        ).hint("Use DROP ... CASCADE to drop dependent objects too.").build();
                    }
                }
            }
        }
    }

    // =========================================================================
    // CREATE TRIGGER

    public void executeCreateTrigger(CreateTriggerStmt s, List<String> searchPath)
            throws SQLException {

        // Resolve the target relation (table or view).
        // INSTEAD OF triggers are only valid on views; BEFORE/AFTER on tables.
        TableDef table = null;
        ViewDef  view  = null;
        try {
            table = db.catalog().resolveTable(s.relation().qualifiedName(), searchPath);
        } catch (SQLException e) {
            // Not a table — try as a view (needed for INSTEAD OF triggers)
            view = resolveView(s.relation().qualifiedName(), searchPath);
            if (view == null) throw e; // neither table nor view
        }

        if (s.timing() == TriggerDef.INSTEAD_OF && table != null) {
            throw PgErrorException.error("42809",
                    "\"" + table.name() + "\" is a table, INSTEAD OF triggers are only valid on views").build();
        }
        if (s.timing() != TriggerDef.INSTEAD_OF && view != null) {
            throw PgErrorException.error("42809",
                    "\"" + view.name() + "\" is a view, only INSTEAD OF triggers are valid on views").build();
        }

        // Resolve trigger function — look up by name (trigger functions take no regular args)
        String funcName;
        String funcSchema;
        if (s.funcname().size() == 2) {
            funcSchema = s.funcname().get(0).toLowerCase();
            funcName   = s.funcname().get(1).toLowerCase();
        } else {
            funcSchema = searchPath.isEmpty() ? "public" : searchPath.get(0);
            funcName   = s.funcname().get(0).toLowerCase();
        }

        FunctionRegistry reg = db.catalog().functions();
        List<FunctionDef> overloads = reg.findScalarOverloads(funcName);
        if (overloads.isEmpty()) {
            throw PgErrorException.error("42883",
                    "function " + funcName + "() does not exist").build();
        }
        // Pick the zero-arg overload, or the first one (trigger functions are called specially)
        FunctionDef func = overloads.stream()
                .filter(f -> f.argTypes().isEmpty())
                .findFirst()
                .orElse(overloads.get(0));

        String relName   = table != null ? table.name()       : view.name();
        String relSchema = table != null ? table.schemaName() : view.schemaName();
        long   relOid    = table != null ? table.oid()        : view.oid();

        // Check for existing trigger with same name
        List<TriggerDef> existingTriggers = table != null ? table.triggers() : view.triggers();
        if (s.replace()) {
            if (table != null) table.dropTrigger(s.triggerName());
            else               view.dropTrigger(s.triggerName());
        } else {
            for (TriggerDef existing : existingTriggers) {
                if (existing.name().equalsIgnoreCase(s.triggerName())) {
                    throw PgErrorException.error("42710",
                            "trigger \"" + s.triggerName() + "\" for relation \""
                            + relName + "\" already exists").build();
                }
            }
        }

        long oid = db.catalog().nextOid();
        TriggerDef trig = new TriggerDef(
                oid,
                s.triggerName().toLowerCase(),
                relOid,
                relName,
                relSchema,
                func.oid(),
                func.name(),
                func.schemaName(),
                s.row(),
                s.timing(),
                s.events(),
                s.columns(),
                s.whenClause(),
                s.args() != null ? s.args() : List.of()
        );

        if (table != null) table.addTrigger(trig);
        else               view.addTrigger(trig);
    }

    /**
     * Resolve a view by name across the search path.
     */
    private ViewDef resolveView(String name, List<String> searchPath) {
        String lc = name.toLowerCase();
        int dot = lc.indexOf('.');
        if (dot > 0) {
            String schema = lc.substring(0, dot);
            String vname  = lc.substring(dot + 1);
            Schema s = db.catalog().getSchemaOrNull(schema);
            return s != null ? s.view(vname) : null;
        }
        for (String schema : searchPath) {
            Schema s = db.catalog().getSchemaOrNull(schema);
            if (s != null) {
                ViewDef v = s.view(lc);
                if (v != null) return v;
            }
        }
        return null;
    }

    // =========================================================================
    // ─── CREATE TYPE AS ENUM ─────────────────────────────────────────────────

    public void executeCreateEnum(CreateEnumStmt s, List<String> searchPath)
            throws SQLException {
        String typeName = s.typeName().getLast().toLowerCase();
        // Check if type already exists in schema or global registry
        String schemaName = s.typeName().size() > 1 ? s.typeName().get(0).toLowerCase() : searchPath.get(0);
        Schema schema = db.catalog().allSchemas().get(schemaName);
        if (schema == null) schema = db.catalog().allSchemas().get(searchPath.get(0));
        if (schema != null && schema.type(typeName) != null) {
            throw PgErrorException.error("42710",
                    "type \"" + typeName + "\" already exists").build();
        }
        int oid = (int) db.catalog().nextOid();
        var enumType = new org.pgjava.types.EnumType(oid, typeName, List.copyOf(s.labels()));
        if (schema != null) schema.addType(enumType);
        // Register in the per-database type registry so the evaluator can resolve it.
        // This does NOT affect PgTypeRegistry.INSTANCE (other databases).
        types.register(enumType);

        // Rollback: remove enum type from schema and registry
        final Schema finalSchema = schema;
        final String finalTypeName = typeName;
        final org.pgjava.types.EnumType finalEnumType = enumType;
        registerUndo(() -> {
            if (finalSchema != null) finalSchema.removeType(finalTypeName);
            types.unregister(finalEnumType);
        });
    }

    // ─── CREATE DOMAIN ───────────────────────────────────────────────────────

    public void executeCreateDomain(CreateDomainStmt s, List<String> searchPath)
            throws SQLException {
        String domainName = s.domainName().getLast().toLowerCase();
        String schemaName = s.domainName().size() > 1 ? s.domainName().get(0).toLowerCase() : searchPath.get(0);
        Schema schema = db.catalog().allSchemas().get(schemaName);
        if (schema == null) schema = db.catalog().allSchemas().get(searchPath.get(0));
        if (schema != null && schema.type(domainName) != null) {
            throw PgErrorException.error("42710",
                    "type \"" + domainName + "\" already exists").build();
        }
        TypeInfo baseInfo = resolveType(s.baseType(), searchPath);
        int oid = (int) db.catalog().nextOid();
        List<Expr> checkExprs = s.checkConstraints() != null
                ? s.checkConstraints().stream().map(CreateDomainStmt.DomainConstraint::expr).toList()
                : List.of();
        var domainType = new org.pgjava.types.DomainType(oid, domainName, baseInfo.type(),
                checkExprs, s.defaultExpr(), s.notNull());
        if (schema != null) schema.addType(domainType);
        // Register in the per-database type registry (not the JVM-global INSTANCE).
        types.register(domainType);

        // Rollback: remove domain type from schema and registry
        final Schema finalDomSchema = schema;
        final String finalDomName = domainName;
        final org.pgjava.types.DomainType finalDomType = domainType;
        registerUndo(() -> {
            if (finalDomSchema != null) finalDomSchema.removeType(finalDomName);
            types.unregister(finalDomType);
        });
    }

    // ─── ALTER TYPE ... ADD VALUE ────────────────────────────────────────────

    public void executeAlterEnum(AlterEnumStmt s, List<String> searchPath)
            throws SQLException {
        String typeName = s.typeName().getLast().toLowerCase();
        String schemaName = s.typeName().size() > 1 ? s.typeName().get(0).toLowerCase() : searchPath.get(0);
        Schema schema = db.catalog().allSchemas().get(schemaName);
        if (schema == null) schema = db.catalog().allSchemas().get(searchPath.get(0));
        if (schema == null)
            throw PgErrorException.error("3F000", "schema \"" + schemaName + "\" does not exist").build();
        PgType existing = schema.type(typeName);
        if (!(existing instanceof org.pgjava.types.EnumType enumType))
            throw PgErrorException.error("42704", "type \"" + typeName + "\" does not exist").build();
        if (enumType.labels().contains(s.newVal())) {
            if (s.skipIfNewValExists()) return;
            throw PgErrorException.error("42710",
                    "enum label \"" + s.newVal() + "\" already exists").build();
        }
        if (s.newValNeighbor() == null) {
            enumType.addLabel(s.newVal());
        } else {
            int idx = enumType.labels().indexOf(s.newValNeighbor());
            if (idx < 0)
                throw PgErrorException.error("22023",
                        "\"" + s.newValNeighbor() + "\" is not an existing enum label").build();
            enumType.addLabel(s.newValIsAfter() ? idx + 1 : idx, s.newVal());
        }
    }

    // ─── DROP TYPE / DROP DOMAIN ─────────────────────────────────────────────

    public void executeDropType(DropTypeStmt s, List<String> searchPath)
            throws SQLException {
        for (List<String> nameParts : s.typeNames()) {
            String typeName = nameParts.getLast().toLowerCase();
            String schemaName = nameParts.size() > 1 ? nameParts.get(0).toLowerCase() : searchPath.get(0);
            Schema schema = db.catalog().allSchemas().get(schemaName);
            if (schema == null) schema = db.catalog().allSchemas().get(searchPath.get(0));
            if (schema == null || schema.type(typeName) == null) {
                if (s.ifExists()) continue;
                throw PgErrorException.error("42704",
                        "type \"" + typeName + "\" does not exist").build();
            }
            // Check for dependent columns
            PgType typeObj = schema.type(typeName);
            for (Schema sc : db.catalog().allSchemas().values()) {
                for (TableDef table : sc.tables().values()) {
                    for (var col : table.columns()) {
                        if (col.type() == typeObj) {
                            throw PgErrorException.error("2BP01",
                                    "cannot drop type " + typeName
                                            + " because other objects depend on it").build();
                        }
                    }
                }
            }
            schema.removeType(typeName);
        }
    }

    // ─── DROP TRIGGER ────────────────────────────────────────────────────────

    public void executeDropTrigger(DropTriggerStmt s, List<String> searchPath)
            throws SQLException {

        String tableName = s.tableName().toLowerCase();
        TableDef table = null;
        ViewDef  view  = null;
        try {
            table = db.catalog().resolveTable(tableName, searchPath);
        } catch (SQLException e) {
            // Not a table — try as a view
            view = resolveView(tableName, searchPath);
            if (view == null) {
                if (s.ifExists()) return;
                throw e;
            }
        }

        List<TriggerDef> triggers = table != null ? table.triggers() : view.triggers();
        boolean found = triggers.stream()
                .anyMatch(t -> t.name().equalsIgnoreCase(s.triggerName()));
        if (!found && !s.ifExists()) {
            String relName = table != null ? table.name() : view.name();
            throw PgErrorException.error("42704",
                    "trigger \"" + s.triggerName() + "\" for table \""
                    + relName + "\" does not exist").build();
        }
        if (table != null) table.dropTrigger(s.triggerName());
        else               view.dropTrigger(s.triggerName());
    }
}
