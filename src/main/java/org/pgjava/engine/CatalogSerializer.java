package org.pgjava.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.pgjava.catalog.*;
import org.pgjava.sql.SqlDeparser;
import org.pgjava.sql.ast.*;
import org.pgjava.sql.parser.ParseException;
import org.pgjava.sql.parser.ParserProvider;
import org.pgjava.types.DomainType;
import org.pgjava.types.EnumType;
import org.pgjava.types.PgType;
import org.pgjava.types.PgTypeRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes and deserializes a {@link CatalogManager} to/from a JSON file
 * ({@code catalog.json}) in the database data directory.
 *
 * <p>This forms the "catalog snapshot" half of Phase 11 persistence. The heap
 * data (actual row values) is handled by {@link HeapSerializer}.
 *
 * <p>Format is intentionally readable and simple — correctness over compactness.
 * Each schema, table, column, constraint, index, sequence, and view is a JSON
 * object with explicit fields. {@link PgType} is stored by OID; recovered via
 * {@link PgTypeRegistry#byOid}.
 */
final class CatalogSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CATALOG_FILE = "catalog.json";

    private CatalogSerializer() {}

    // =========================================================================
    // Save
    // =========================================================================

    static void save(CatalogManager catalog, Path dataDir) throws IOException {
        Files.createDirectories(dataDir);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", 1);
        root.put("nextOid", catalog.currentOid());

        ArrayNode schemas = root.putArray("schemas");
        for (Schema schema : catalog.allSchemas().values()) {
            schemas.add(serializeSchema(schema));
        }

        // Serialize user-defined functions (those with source != null)
        ArrayNode functions = root.putArray("functions");
        for (FunctionDef fn : catalog.functions().allScalars()) {
            if (fn.source() != null) {
                functions.add(serializeFunction(fn));
            }
        }

        MAPPER.writerWithDefaultPrettyPrinter()
              .writeValue(dataDir.resolve(CATALOG_FILE).toFile(), root);
    }

    private static ObjectNode serializeSchema(Schema schema) {
        ObjectNode s = MAPPER.createObjectNode();
        s.put("oid", schema.oid());
        s.put("name", schema.name());

        ArrayNode tables = s.putArray("tables");
        for (TableDef t : schema.tables().values()) tables.add(serializeTable(t));

        ArrayNode seqs = s.putArray("sequences");
        for (SequenceDef seq : schema.sequences().values()) seqs.add(serializeSequence(seq));

        ArrayNode views = s.putArray("views");
        for (ViewDef v : schema.views().values()) views.add(serializeView(v));

        ArrayNode types = s.putArray("types");
        for (PgType t : schema.types().values()) types.add(serializeType(t));

        return s;
    }

    private static ObjectNode serializeTable(TableDef t) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("oid", t.oid());
        node.put("name", t.name());
        node.put("schemaName", t.schemaName());
        node.put("temp", t.isTemp());

        ArrayNode cols = node.putArray("columns");
        for (ColumnDef c : t.columns()) cols.add(serializeColumn(c));

        ArrayNode constraints = node.putArray("constraints");
        for (Constraint c : t.constraints()) constraints.add(serializeConstraint(c));

        ArrayNode indexes = node.putArray("indexes");
        for (IndexDef idx : t.indexes()) indexes.add(serializeIndex(idx));

        if (!t.triggers().isEmpty()) {
            ArrayNode triggers = node.putArray("triggers");
            for (TriggerDef trig : t.triggers()) triggers.add(serializeTrigger(trig));
        }

        return node;
    }

    private static ObjectNode serializeColumn(ColumnDef c) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("name", c.name());
        node.put("attnum", c.attnum());
        node.put("typeOid", c.type().oid());
        node.put("typmod", c.typmod());
        node.put("nullable", c.nullable());
        node.put("generated", c.generated().name());
        if (c.collation() != null) node.put("collation", c.collation());
        if (c.defaultExpr() != null) {
            node.put("defaultSql", SqlDeparser.deparseExpr(c.defaultExpr()));
        }
        return node;
    }

    private static ObjectNode serializeConstraint(Constraint c) {
        ObjectNode node = MAPPER.createObjectNode();
        if (c.name() != null) node.put("name", c.name());
        switch (c) {
            case Constraint.PrimaryKey pk -> {
                node.put("type", "PRIMARY_KEY");
                ArrayNode cols = node.putArray("columns");
                pk.columns().forEach(cols::add);
            }
            case Constraint.Unique uq -> {
                node.put("type", "UNIQUE");
                ArrayNode cols = node.putArray("columns");
                uq.columns().forEach(cols::add);
            }
            case Constraint.NotNull nn -> {
                node.put("type", "NOT_NULL");
                node.put("column", nn.column());
            }
            case Constraint.Check ck -> {
                node.put("type", "CHECK");
                if (ck.column() != null) node.put("column", ck.column());
                if (ck.exprSql() != null) node.put("exprSql", ck.exprSql());
            }
            case Constraint.ForeignKey fk -> {
                node.put("type", "FOREIGN_KEY");
                ArrayNode cols = node.putArray("columns");
                fk.columns().forEach(cols::add);
                node.put("refSchema", fk.refSchema());
                node.put("refTable", fk.refTable());
                ArrayNode refCols = node.putArray("refColumns");
                fk.refColumns().forEach(refCols::add);
                node.put("onDelete", fk.onDelete().name());
                node.put("onUpdate", fk.onUpdate().name());
            }
        }
        return node;
    }

    private static ObjectNode serializeIndex(IndexDef idx) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("oid", idx.oid());
        node.put("name", idx.name());
        node.put("unique", idx.unique());
        node.put("primary", idx.primary());
        node.put("accessMethod", idx.accessMethod());
        ArrayNode cols = node.putArray("columns");
        for (IndexColumn ic : idx.columns()) {
            ObjectNode cn = MAPPER.createObjectNode();
            cn.put("column", ic.column());
            cn.put("ascending", ic.ascending());
            cn.put("nullsFirst", ic.nullsFirst());
            cols.add(cn);
        }
        return node;
    }

    private static ObjectNode serializeSequence(SequenceDef seq) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("oid", seq.oid());
        node.put("name", seq.name());
        node.put("schemaName", seq.schemaName());
        node.put("start", seq.start());
        node.put("increment", seq.increment());
        node.put("minVal", seq.minVal());
        node.put("maxVal", seq.maxVal());
        node.put("cycle", seq.cycle());
        node.put("current", seq.current()); // AtomicLong current value
        return node;
    }

    private static ObjectNode serializeView(ViewDef v) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("oid", v.oid());
        node.put("name", v.name());
        node.put("schemaName", v.schemaName());
        node.put("definitionSql", v.definitionSql());
        ArrayNode aliases = node.putArray("columnAliases");
        v.columnAliases().forEach(aliases::add);
        return node;
    }

    private static ObjectNode serializeType(PgType t) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("oid", t.oid());
        node.put("name", t.name());
        switch (t) {
            case EnumType e -> {
                node.put("kind", "ENUM");
                ArrayNode labels = node.putArray("labels");
                e.labels().forEach(labels::add);
            }
            case DomainType d -> {
                node.put("kind", "DOMAIN");
                node.put("baseTypeOid", d.baseType().oid());
                node.put("notNull", d.notNull());
                if (d.defaultExpr() != null) {
                    node.put("defaultSql", SqlDeparser.deparseExpr(d.defaultExpr()));
                }
                ArrayNode checks = node.putArray("checkConstraints");
                for (Expr check : d.checkConstraints()) {
                    checks.add(SqlDeparser.deparseExpr(check));
                }
            }
            default -> {
                // Unknown user-defined type kind — skip silently
                node.put("kind", "UNKNOWN");
            }
        }
        return node;
    }

    private static ObjectNode serializeFunction(FunctionDef fn) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("oid", fn.oid());
        node.put("name", fn.name());
        node.put("schemaName", fn.schemaName());
        node.put("strict", fn.strict());
        node.put("variadic", fn.variadic());
        node.put("source", fn.source());
        if (fn.returnType() != null) node.put("returnTypeOid", fn.returnType().oid());
        ArrayNode argTypesArr = node.putArray("argTypeOids");
        for (PgType pt : fn.argTypes()) argTypesArr.add(pt.oid());
        if (fn.argNames() != null) {
            ArrayNode argNamesArr = node.putArray("argNames");
            fn.argNames().forEach(argNamesArr::add);
        }
        if (fn.argDefaults() != null && !fn.argDefaults().isEmpty()) {
            ArrayNode defs = node.putArray("argDefaultsSql");
            for (Expr e : fn.argDefaults()) {
                defs.add(SqlDeparser.deparseExpr(e));
            }
        }
        return node;
    }

    private static ObjectNode serializeTrigger(TriggerDef t) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("oid", t.oid());
        node.put("name", t.name());
        node.put("tableOid", t.tableOid());
        node.put("tableName", t.tableName());
        node.put("tableSchema", t.tableSchema());
        node.put("functionOid", t.functionOid());
        node.put("functionName", t.functionName());
        node.put("functionSchema", t.functionSchema());
        node.put("row", t.row());
        node.put("timing", t.timing());
        node.put("events", t.events());
        if (t.columns() != null && !t.columns().isEmpty()) {
            ArrayNode cols = node.putArray("columns");
            t.columns().forEach(cols::add);
        }
        if (t.whenClause() != null) {
            node.put("whenSql", SqlDeparser.deparseExpr(t.whenClause()));
        }
        if (t.args() != null && !t.args().isEmpty()) {
            ArrayNode args = node.putArray("args");
            t.args().forEach(args::add);
        }
        return node;
    }

    // =========================================================================
    // Load
    // =========================================================================

    static boolean exists(Path dataDir) {
        return Files.exists(dataDir.resolve(CATALOG_FILE));
    }

    /**
     * Load catalog state from disk into the given (freshly-constructed) CatalogManager.
     * The "public" schema already exists in the manager; tables/sequences/views are added to it.
     * Other schemas are created with their saved OIDs.
     */
    static void load(CatalogManager catalog, Database database, Path dataDir) throws IOException {
        org.pgjava.types.PgTypeRegistry registry = (database != null)
                ? database.typeRegistry()
                : org.pgjava.types.PgTypeRegistry.INSTANCE;

        JsonNode root = MAPPER.readTree(dataDir.resolve(CATALOG_FILE).toFile());

        long nextOid = root.get("nextOid").asLong();

        for (JsonNode schemaNode : root.get("schemas")) {
            deserializeSchema(catalog, registry, schemaNode);
        }

        // Deserialize user-defined functions
        if (root.has("functions")) {
            for (JsonNode fnNode : root.get("functions")) {
                deserializeFunction(catalog, database, fnNode);
            }
        }

        // Restore OID generator last (schema creation above may allocate OIDs)
        catalog.resetOidGen(nextOid);
    }

    /** Backwards-compatible overload for callers that don't supply Database. */
    static void load(CatalogManager catalog, Path dataDir) throws IOException {
        load(catalog, null, dataDir);
    }

    private static void deserializeSchema(CatalogManager catalog,
                                          org.pgjava.types.PgTypeRegistry registry,
                                          JsonNode node) throws IOException {
        long   oid  = node.get("oid").asLong();
        String name = node.get("name").asText();

        // public schema already exists; for others, create with the saved OID
        Schema schema = catalog.getSchemaOrNull(name);
        if (schema == null) {
            schema = catalog.createSchemaWithOid(oid, name);
        }

        // Deserialize user-defined types BEFORE tables, since columns may reference
        // enum/domain types by OID (types array may be absent in older catalog files)
        if (node.has("types")) {
            for (JsonNode typeNode : node.get("types")) {
                deserializeType(schema, registry, typeNode);
            }
        }

        for (JsonNode tableNode : node.get("tables")) {
            deserializeTable(schema, registry, tableNode);
        }

        for (JsonNode seqNode : node.get("sequences")) {
            deserializeSequence(schema, seqNode);
        }

        for (JsonNode viewNode : node.get("views")) {
            deserializeView(schema, viewNode);
        }
    }

    private static void deserializeTable(Schema schema,
                                          org.pgjava.types.PgTypeRegistry registry,
                                          JsonNode node) {
        long   oid        = node.get("oid").asLong();
        String name       = node.get("name").asText();
        String schemaName = node.get("schemaName").asText();
        boolean temp      = node.get("temp").asBoolean();

        TableDef t = new TableDef(oid, name, schemaName, temp);

        for (JsonNode colNode : node.get("columns")) {
            t.addColumn(deserializeColumn(registry, colNode));
        }
        for (JsonNode conNode : node.get("constraints")) {
            t.addConstraint(deserializeConstraint(conNode));
        }
        for (JsonNode idxNode : node.get("indexes")) {
            IndexDef idx = deserializeIndex(idxNode, schemaName, name, oid);
            t.addIndex(idx);
            schema.addIndex(idx);
        }

        if (node.has("triggers")) {
            for (JsonNode trigNode : node.get("triggers")) {
                t.addTrigger(deserializeTrigger(trigNode));
            }
        }

        schema.addTable(t);
    }

    private static ColumnDef deserializeColumn(org.pgjava.types.PgTypeRegistry registry,
                                               JsonNode node) {
        String        colName  = node.get("name").asText();
        int           attnum   = node.get("attnum").asInt();
        int           typeOid  = node.get("typeOid").asInt();
        int           typmod   = node.get("typmod").asInt();
        boolean       nullable = node.get("nullable").asBoolean();
        GeneratedKind gen      = GeneratedKind.valueOf(node.get("generated").asText());

        // Look in the database-local registry first (finds user-defined types like enums/domains),
        // then fall back to the global built-in registry.
        PgType type = registry.byOid(typeOid);
        if (type == null) type = PgTypeRegistry.INSTANCE.byOid(typeOid);
        if (type == null) type = PgTypeRegistry.INSTANCE.text(); // fallback to text

        String collation = node.has("collation") ? node.get("collation").asText(null) : null;

        // Re-parse default expression from SQL text
        Expr defaultExpr = null;
        if (node.has("defaultSql")) {
            String defaultSql = node.get("defaultSql").asText();
            try {
                List<Stmt> stmts = ParserProvider.parse("SELECT " + defaultSql);
                if (!stmts.isEmpty() && stmts.get(0) instanceof SelectStmt sel
                        && sel.targetList() != null && !sel.targetList().isEmpty()) {
                    defaultExpr = sel.targetList().get(0).val();
                }
            } catch (ParseException ignored) {}
        }

        return new ColumnDef(colName, attnum, type, typmod, nullable, defaultExpr, gen, collation);
    }

    private static Constraint deserializeConstraint(JsonNode node) {
        String type = node.get("type").asText();
        String name = node.has("name") ? node.get("name").asText() : null;

        return switch (type) {
            case "PRIMARY_KEY" -> {
                List<String> cols = new ArrayList<>();
                node.get("columns").forEach(c -> cols.add(c.asText()));
                yield new Constraint.PrimaryKey(name, cols);
            }
            case "UNIQUE" -> {
                List<String> cols = new ArrayList<>();
                node.get("columns").forEach(c -> cols.add(c.asText()));
                yield new Constraint.Unique(name, cols);
            }
            case "NOT_NULL" -> new Constraint.NotNull(name, node.get("column").asText());
            case "CHECK" -> {
                String col = node.has("column") ? node.get("column").asText() : null;
                String sql = node.has("exprSql") ? node.get("exprSql").asText() : null;
                yield new Constraint.Check(name, col, null, sql);
            }
            case "FOREIGN_KEY" -> {
                List<String> cols = new ArrayList<>();
                node.get("columns").forEach(c -> cols.add(c.asText()));
                List<String> refCols = new ArrayList<>();
                node.get("refColumns").forEach(c -> refCols.add(c.asText()));
                FkAction onDelete = FkAction.valueOf(node.get("onDelete").asText());
                FkAction onUpdate = FkAction.valueOf(node.get("onUpdate").asText());
                yield new Constraint.ForeignKey(name, cols,
                        node.get("refSchema").asText(),
                        node.get("refTable").asText(),
                        refCols, onDelete, onUpdate);
            }
            default -> throw new IllegalArgumentException("Unknown constraint type: " + type);
        };
    }

    private static IndexDef deserializeIndex(JsonNode node, String schemaName,
                                              String tableName, long tableOid) {
        long   oid          = node.get("oid").asLong();
        String name         = node.get("name").asText();
        boolean unique      = node.get("unique").asBoolean();
        boolean primary     = node.get("primary").asBoolean();
        String accessMethod = node.get("accessMethod").asText();

        List<IndexColumn> cols = new ArrayList<>();
        for (JsonNode cn : node.get("columns")) {
            cols.add(new IndexColumn(
                    cn.get("column").asText(),
                    cn.get("ascending").asBoolean(),
                    cn.get("nullsFirst").asBoolean()
            ));
        }

        return new IndexDef(oid, name, schemaName, tableName, tableOid, cols, unique, primary, accessMethod);
    }

    private static void deserializeSequence(Schema schema, JsonNode node) {
        long    oid        = node.get("oid").asLong();
        String  name       = node.get("name").asText();
        String  schemaName = node.get("schemaName").asText();
        long    start      = node.get("start").asLong();
        long    increment  = node.get("increment").asLong();
        long    minVal     = node.get("minVal").asLong();
        long    maxVal     = node.get("maxVal").asLong();
        boolean cycle      = node.get("cycle").asBoolean();
        long    current    = node.get("current").asLong();

        SequenceDef seq = new SequenceDef(oid, name, schemaName,
                start, increment, minVal, maxVal, cycle);
        seq.setval(current);
        schema.addSequence(seq);
    }

    private static void deserializeView(Schema schema, JsonNode node) {
        long   oid        = node.get("oid").asLong();
        String name       = node.get("name").asText();
        String schemaName = node.get("schemaName").asText();
        String sql        = node.get("definitionSql").asText();

        List<String> aliases = new ArrayList<>();
        if (node.has("columnAliases")) {
            node.get("columnAliases").forEach(n -> aliases.add(n.asText()));
        }

        // Try to re-parse the view SELECT — required for the planner to expand views.
        SelectStmt parsedDef = null;
        try {
            List<Stmt> stmts = ParserProvider.parse(sql);
            if (!stmts.isEmpty() && stmts.get(0) instanceof SelectStmt sel) {
                parsedDef = sel;
            }
        } catch (ParseException ignored) {}

        schema.addView(new ViewDef(oid, name, schemaName, sql, parsedDef, aliases));
    }

    private static void deserializeType(Schema schema,
                                         org.pgjava.types.PgTypeRegistry registry,
                                         JsonNode node) {
        int    oid  = node.get("oid").asInt();
        String name = node.get("name").asText();
        String kind = node.get("kind").asText();

        PgType type = switch (kind) {
            case "ENUM" -> {
                List<String> labels = new ArrayList<>();
                node.get("labels").forEach(n -> labels.add(n.asText()));
                yield new EnumType(oid, name, labels);
            }
            case "DOMAIN" -> {
                int baseOid = node.get("baseTypeOid").asInt();
                // Look in per-database registry first (for domain-of-domain), then built-ins
                PgType baseType = registry.byOid(baseOid);
                if (baseType == null) baseType = PgTypeRegistry.INSTANCE.byOid(baseOid);
                if (baseType == null) baseType = PgTypeRegistry.INSTANCE.text();
                boolean notNull = node.has("notNull") && node.get("notNull").asBoolean();

                Expr defaultExpr = null;
                if (node.has("defaultSql")) {
                    defaultExpr = parseExpr(node.get("defaultSql").asText());
                }

                List<Expr> checks = new ArrayList<>();
                if (node.has("checkConstraints")) {
                    for (JsonNode checkSql : node.get("checkConstraints")) {
                        Expr e = parseExpr(checkSql.asText());
                        if (e != null) checks.add(e);
                    }
                }

                yield new DomainType(oid, name, baseType, checks, defaultExpr, notNull);
            }
            default -> null;
        };

        if (type != null) {
            schema.addType(type);
            // Register into the per-database registry only — not the JVM-global INSTANCE.
            registry.register(type);
        }
    }

    private static void deserializeFunction(CatalogManager catalog, Database database,
                                             JsonNode node) {
        long    oid        = node.get("oid").asLong();
        String  name       = node.get("name").asText();
        String  schemaName = node.get("schemaName").asText();
        boolean strict     = node.get("strict").asBoolean();
        boolean variadic   = node.get("variadic").asBoolean();
        String  source     = node.get("source").asText();

        PgType returnType = null;
        if (node.has("returnTypeOid")) {
            returnType = PgTypeRegistry.INSTANCE.byOid(node.get("returnTypeOid").asInt());
        }

        List<PgType> argTypes = new ArrayList<>();
        for (JsonNode oidNode : node.get("argTypeOids")) {
            PgType pt = PgTypeRegistry.INSTANCE.byOid(oidNode.asInt());
            argTypes.add(pt != null ? pt : PgTypeRegistry.INSTANCE.text());
        }

        List<String> argNames = null;
        if (node.has("argNames")) {
            argNames = new ArrayList<>();
            for (JsonNode n : node.get("argNames")) argNames.add(n.asText());
        }

        List<Expr> argDefaults = null;
        if (node.has("argDefaultsSql")) {
            argDefaults = new ArrayList<>();
            for (JsonNode n : node.get("argDefaultsSql")) {
                Expr e = parseExpr(n.asText());
                argDefaults.add(e);
            }
        }

        // Reconstruct the PL/pgSQL or SQL function implementation
        final String bodyText = source;
        final PgType funcReturnType = returnType;
        final List<String> funcArgNames = argNames;

        FunctionDef.ScalarImpl impl = args -> {
            if (database == null) {
                throw new SQLException("Cannot execute function '" + name
                        + "' — database context not available after cold load");
            }
            // Detect language from body heuristics: PL/pgSQL bodies typically start with
            // BEGIN/DECLARE, SQL bodies are plain statements
            String trimmed = bodyText.strip().toLowerCase();
            if (trimmed.startsWith("begin") || trimmed.startsWith("declare")
                    || trimmed.startsWith("<<")) {
                // PL/pgSQL
                var plBody = org.pgjava.sql.parser.PlPgSqlBodyParser.parse(bodyText);
                var interp = new org.pgjava.executor.plpgsql.PlPgSqlInterpreter(
                        database, List.of(schemaName, "public"), args, funcArgNames, funcReturnType);
                var trigCtx = org.pgjava.executor.TriggerExecutor.PENDING_TRIGGER_CTX.get();
                if (trigCtx != null) {
                    interp.setTriggerContext(trigCtx);
                    return interp.executeTrigger(plBody);
                }
                return interp.execute(plBody);
            } else {
                // SQL language
                var stmts = ParserProvider.parse(bodyText);
                if (stmts.isEmpty()) return null;
                var eval = new org.pgjava.executor.Evaluator(
                        database.catalog().functions(), database.collation());
                eval.setFunctionParams(args);
                var planner = new org.pgjava.executor.Planner(
                        database.catalog(), database.storage(),
                        database.txManager(), eval, List.of(schemaName, "public"));
                planner.setSnapshot(database.txManager().snapshotFor(0L));
                Object result = null;
                for (Stmt stmt : stmts) {
                    if (stmt instanceof SelectStmt sel) {
                        var op = planner.planSelect(sel);
                        op.open();
                        try {
                            org.pgjava.storage.Row row;
                            while ((row = op.next()) != null) {
                                result = row.columnCount() > 0 ? row.get(0) : null;
                            }
                        } finally {
                            op.close();
                        }
                    }
                }
                return result;
            }
        };

        FunctionDef fn = new FunctionDef(oid, name, schemaName, argTypes, returnType,
                strict, variadic, impl, source, argNames, argDefaults);
        catalog.functions().register(fn);
    }

    private static TriggerDef deserializeTrigger(JsonNode node) {
        long   oid            = node.get("oid").asLong();
        String name           = node.get("name").asText();
        long   tableOid       = node.get("tableOid").asLong();
        String tableName      = node.get("tableName").asText();
        String tableSchema    = node.get("tableSchema").asText();
        long   functionOid    = node.get("functionOid").asLong();
        String functionName   = node.get("functionName").asText();
        String functionSchema = node.get("functionSchema").asText();
        boolean row           = node.get("row").asBoolean();
        int    timing         = node.get("timing").asInt();
        int    events         = node.get("events").asInt();

        List<String> columns = new ArrayList<>();
        if (node.has("columns")) {
            node.get("columns").forEach(n -> columns.add(n.asText()));
        }

        Expr whenClause = null;
        if (node.has("whenSql")) {
            whenClause = parseExpr(node.get("whenSql").asText());
        }

        List<String> args = new ArrayList<>();
        if (node.has("args")) {
            node.get("args").forEach(n -> args.add(n.asText()));
        }

        return new TriggerDef(oid, name, tableOid, tableName, tableSchema,
                functionOid, functionName, functionSchema, row, timing, events,
                columns, whenClause, args);
    }

    /** Parse a SQL expression from text, returning null on failure. */
    private static Expr parseExpr(String sql) {
        try {
            List<Stmt> stmts = ParserProvider.parse("SELECT " + sql);
            if (!stmts.isEmpty() && stmts.get(0) instanceof SelectStmt sel
                    && sel.targetList() != null && !sel.targetList().isEmpty()) {
                return sel.targetList().get(0).val();
            }
        } catch (ParseException ignored) {}
        return null;
    }
}
