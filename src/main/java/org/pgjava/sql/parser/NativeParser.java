package org.pgjava.sql.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pganalyze.pg_query.PgQuery;
import com.pganalyze.pg_query.PgQueryException;
import org.pgjava.sql.ast.*;
import org.pgjava.sql.ast.FrameBoundType;

import java.util.*;

/**
 * Parses SQL using the native pg_query library and converts the returned JSON AST
 * to pgjava's typed {@code ast.*} node hierarchy.
 *
 * <p>Calls {@link PgQuery#parseToJson(String)} which invokes PostgreSQL's own Bison
 * parser via JNI and returns a JSON encoding of the internal parse tree.  The JSON
 * is then walked node-by-node and translated to our sealed {@link Stmt}/{@link Expr}/
 * {@link FromItem} types.
 */
final class NativeParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─── Entry point ─────────────────────────────────────────────────────────

    static List<Stmt> parse(String sql) throws ParseException {
        String json;
        try {
            json = PgQuery.parseToJson(sql);
        } catch (PgQueryException e) {
            throw toParse(e, sql);
        } catch (Exception e) {
            throw new ParseException("pg_query internal error: " + e.getMessage());
        }
        try {
            return convertRoot(MAPPER.readTree(json));
        } catch (Exception e) {
            if (e instanceof ParseException pe) throw pe;
            throw new ParseException("AST conversion error: " + e.getMessage());
        }
    }

    // ─── Root ────────────────────────────────────────────────────────────────

    private static List<Stmt> convertRoot(JsonNode root) {
        var out = new ArrayList<Stmt>();
        for (JsonNode w : root.path("stmts")) {
            JsonNode s = w.path("stmt");
            if (!s.isMissingNode() && !s.isEmpty()) out.add(convertStmtNode(s));
        }
        return out;
    }

    // ─── Statement dispatch ──────────────────────────────────────────────────

    /** n is a node-type wrapper: {@code {"SelectStmt":{…}}} etc. */
    private static Stmt convertStmtNode(JsonNode n) {
        String type = firstKey(n);
        JsonNode c = n.path(type);
        return switch (type) {
            case "SelectStmt"          -> convertSelect(c);
            case "InsertStmt"          -> convertInsert(c);
            case "UpdateStmt"          -> convertUpdate(c);
            case "DeleteStmt"          -> convertDelete(c);
            case "CreateStmt"          -> convertCreateTable(c);
            case "IndexStmt"           -> convertIndex(c);
            case "DropStmt"            -> convertDrop(c);
            case "AlterTableStmt"      -> convertAlterTable(c);
            case "ViewStmt"            -> convertView(c);
            case "CreateTableAsStmt"   -> convertCreateTableAs(c);
            case "CreateSchemaStmt"    -> convertCreateSchema(c);
            case "CreateSeqStmt"       -> convertCreateSeq(c);
            case "AlterSeqStmt"        -> convertAlterSeq(c);
            case "TransactionStmt"     -> convertTransaction(c);
            case "VariableSetStmt"     -> convertVariableSet(c);
            case "VariableShowStmt"    -> new ShowStmt(c.path("name").asText());
            case "CreateFunctionStmt"  -> convertCreateFunction(c, false);
            case "CreateProcedureStmt" -> convertCreateFunction(c, true);
            case "TruncateStmt"        -> convertTruncate(c);
            case "ExplainStmt"         -> convertExplain(c);
            case "CopyStmt"            -> convertCopy(c);
            case "NotifyStmt"          -> new NotifyStmt(
                    c.path("conditionname").asText(null),
                    c.path("payload").asText(null));
            case "ListenStmt"          -> new ListenStmt(c.path("conditionname").asText());
            case "UnlistenStmt"        -> new UnlistenStmt(c.path("conditionname").asText());
            case "RenameStmt"          -> convertRename(c);
            case "VacuumStmt"          -> convertVacuum(c);
            case "GrantStmt", "GrantRoleStmt" -> new GrantStmt(c.path("is_grant").booleanValue(), type);
            case "LockStmt"            -> convertLockTable(c);
            case "DoStmt"              -> convertDoStmt(c);
            case "CreateTrigStmt"      -> convertCreateTrigger(c);
            case "CreateEnumStmt"      -> convertCreateEnum(c);
            case "CreateDomainStmt"    -> convertCreateDomain(c);
            case "AlterEnumStmt"       -> convertAlterEnum(c);
            case "CallStmt"            -> convertCall(c);
            case "PrepareStmt"         -> convertPrepare(c);
            case "ExecuteStmt"         -> convertExecute(c);
            case "DeallocateStmt"      -> convertDeallocate(c);
            default                    -> new UnsupportedStmt(type, "");
        };
    }

    // ─── DML ─────────────────────────────────────────────────────────────────

    private static SelectStmt convertSelect(JsonNode c) {
        // Set operation
        String op = c.path("op").asText("SETOP_NONE");
        if (!"SETOP_NONE".equals(op)) {
            SetOpType setOp = switch (op) {
                case "SETOP_UNION"     -> SetOpType.UNION;
                case "SETOP_INTERSECT" -> SetOpType.INTERSECT;
                case "SETOP_EXCEPT"    -> SetOpType.EXCEPT;
                default -> SetOpType.UNION;
            };
            // larg/rarg are bare SelectStmt content (not wrapped)
            SelectStmt left  = convertSelect(c.path("larg"));
            SelectStmt right = convertSelect(c.path("rarg"));
            boolean all = c.path("all").booleanValue();
            // ORDER BY / LIMIT / OFFSET apply to the combined set-op result
            List<SortKey> setOpOrder = List.of();
            if (!c.path("sortClause").isMissingNode()) setOpOrder = sortList(c.path("sortClause"));
            Expr setOpLimit  = exprOpt(c.path("limitCount"));
            Expr setOpOffset = exprOpt(c.path("limitOffset"));
            return new SelectStmt(
                    List.of(), List.of(), null, List.of(), null,
                    setOpOrder, setOpLimit, setOpOffset, List.of(),
                    false, List.of(), setOp, left, right, all,
                    List.of(), withClauseOpt(c), List.of());
        }

        // Distinct
        boolean distinct = false;
        List<Expr> distinctOn = List.of();
        JsonNode dc = c.path("distinctClause");
        if (!dc.isMissingNode() && dc.isArray() && !dc.isEmpty()) {
            // DISTINCT: [{}] means plain DISTINCT; [{ColumnRef:...}, ...] = DISTINCT ON
            JsonNode first = dc.get(0);
            if (first != null && !first.isEmpty()) {
                distinctOn = exprList(dc);
            } else {
                distinct = true;
            }
        }

        // Target list
        List<TargetEntry> targets = List.of();
        if (!c.path("targetList").isMissingNode()) targets = convertTargetList(c.path("targetList"));

        // FROM
        List<FromItem> from = List.of();
        if (!c.path("fromClause").isMissingNode()) from = convertFromList(c.path("fromClause"));

        // WHERE
        Expr where = exprOpt(c.path("whereClause"));

        // GROUP BY
        List<Expr> groupBy = List.of();
        if (!c.path("groupClause").isMissingNode()) groupBy = exprList(c.path("groupClause"));

        // HAVING
        Expr having = exprOpt(c.path("havingClause"));

        // ORDER BY
        List<SortKey> orderBy = List.of();
        if (!c.path("sortClause").isMissingNode()) orderBy = sortList(c.path("sortClause"));

        // LIMIT / OFFSET
        Expr limitCount  = exprOpt(c.path("limitCount"));
        Expr limitOffset = exprOpt(c.path("limitOffset"));

        // WINDOW
        List<WindowDef> windows = List.of();
        if (!c.path("windowClause").isMissingNode()) {
            var ws = new ArrayList<WindowDef>();
            for (JsonNode w : c.path("windowClause")) ws.add(convertWindowDef(w.path("WindowDef")));
            windows = ws;
        }

        // FOR UPDATE / SHARE
        List<LockingClause> locking = List.of();
        if (!c.path("lockingClause").isMissingNode()) {
            var lcs = new ArrayList<LockingClause>();
            for (JsonNode l : c.path("lockingClause")) lcs.add(convertLockingClause(l.path("LockingClause")));
            locking = lcs;
        }

        // VALUES
        List<List<Expr>> valuesLists = List.of();
        if (!c.path("valuesLists").isMissingNode()) {
            var vl = new ArrayList<List<Expr>>();
            for (JsonNode row : c.path("valuesLists")) vl.add(exprList(row.path("List").path("items")));
            valuesLists = vl;
        }

        return new SelectStmt(targets, from, where, groupBy, having, orderBy,
                limitCount, limitOffset, windows, distinct, distinctOn,
                null, null, null, false, locking, withClauseOpt(c), valuesLists);
    }

    private static InsertStmt convertInsert(JsonNode c) {
        RangeVar rel = convertRangeVar(c.path("relation"));
        // cols: ResTarget list where only "name" is set
        List<String> cols = new ArrayList<>();
        for (JsonNode col : c.path("cols"))
            cols.add(col.path("ResTarget").path("name").asText());

        // selectStmt is wrapped: {"SelectStmt": {...}} or absent for DEFAULT VALUES
        boolean defValues = c.path("selectStmt").isMissingNode();
        SelectStmt source = defValues ? null : convertSelectNode(c.path("selectStmt"));

        OnConflictClause onConflict = null;
        JsonNode oc = c.path("onConflictClause");
        if (!oc.isMissingNode()) onConflict = convertOnConflict(oc);

        List<TargetEntry> returning = convertTargetList(c.path("returningList"));
        WithClause with = withClauseOpt(c);
        return new InsertStmt(rel, cols, source, defValues, onConflict, returning, with);
    }

    private static UpdateStmt convertUpdate(JsonNode c) {
        RangeVar rel = convertRangeVar(c.path("relation"));
        List<AssignTarget> targets = convertAssignTargets(c.path("targetList"));
        List<FromItem> from = convertFromList(c.path("fromClause"));
        Expr where = exprOpt(c.path("whereClause"));
        List<TargetEntry> returning = convertTargetList(c.path("returningList"));
        WithClause with = withClauseOpt(c);
        return new UpdateStmt(rel, targets, from, where, returning, with);
    }

    private static DeleteStmt convertDelete(JsonNode c) {
        RangeVar rel = convertRangeVar(c.path("relation"));
        List<FromItem> using = convertFromList(c.path("usingClause"));
        Expr where = exprOpt(c.path("whereClause"));
        List<TargetEntry> returning = convertTargetList(c.path("returningList"));
        WithClause with = withClauseOpt(c);
        return new DeleteStmt(rel, using, where, returning, with);
    }

    // ─── DDL ─────────────────────────────────────────────────────────────────

    private static CreateTableStmt convertCreateTable(JsonNode c) {
        RangeVar rel = convertRangeVar(c.path("relation"));
        var cols = new ArrayList<ColumnDefNode>();
        var constraints = new ArrayList<TableConstraintNode>();
        for (JsonNode elt : c.path("tableElts")) {
            String k = firstKey(elt);
            if ("ColumnDef".equals(k))   cols.add(convertColumnDef(elt.path("ColumnDef")));
            else if ("Constraint".equals(k)) constraints.add(convertTableConstraint(elt.path("Constraint")));
        }
        boolean ifNotExists = c.path("if_not_exists").booleanValue();
        boolean temp = c.path("relpersistence").asText("p").equals("t");
        return new CreateTableStmt(rel, cols, constraints, ifNotExists, temp);
    }

    private static CreateTableAsStmt convertCreateTableAs(JsonNode c) {
        RangeVar rel = convertRangeVar(c.path("into").path("rel"));
        // query may be nested under "query" key
        JsonNode q = c.path("query");
        SelectStmt query = q.isMissingNode() ? null : convertSelectNode(q);
        boolean ifNotExists = c.path("if_not_exists").booleanValue();
        boolean temp = c.path("into").path("rel").path("relpersistence").asText("p").equals("t");
        return new CreateTableAsStmt(rel, query, ifNotExists, temp);
    }

    private static CreateIndexStmt convertIndex(JsonNode c) {
        String name = c.path("idxname").asText(null);
        RangeVar rel = convertRangeVar(c.path("relation"));
        var params = new ArrayList<IndexElem>();
        for (JsonNode ie : c.path("indexParams")) params.add(convertIndexElem(ie.path("IndexElem")));
        Expr where = exprOpt(c.path("whereClause"));
        boolean unique = c.path("unique").booleanValue();
        boolean ifNotExists = c.path("if_not_exists").booleanValue();
        String am = c.path("accessMethod").asText("btree");
        boolean concurrent = c.path("concurrent").booleanValue();
        return new CreateIndexStmt(name, rel, params, where, unique, ifNotExists, am, concurrent);
    }

    private static Stmt convertDrop(JsonNode c) {
        String removeType = c.path("removeType").asText("");
        boolean ifExists = c.path("missing_ok").booleanValue();
        DropBehavior beh = "DROP_CASCADE".equals(c.path("behavior").asText()) ? DropBehavior.CASCADE : DropBehavior.RESTRICT;
        JsonNode objects = c.path("objects");

        return switch (removeType) {
            case "OBJECT_TABLE" -> {
                var rels = new ArrayList<RangeVar>();
                for (JsonNode obj : objects) rels.add(rangeVarFromList(obj.path("List").path("items")));
                yield new DropTableStmt(rels, ifExists, beh);
            }
            case "OBJECT_INDEX" -> {
                var names = new ArrayList<String>();
                for (JsonNode obj : objects) {
                    // Index names are List of strings
                    JsonNode items = obj.path("List").path("items");
                    if (!items.isMissingNode()) names.add(stringFromItems(items));
                    else names.add(getString(obj));
                }
                yield new DropIndexStmt(names, ifExists, beh);
            }
            case "OBJECT_SCHEMA" -> {
                var names = new ArrayList<String>();
                for (JsonNode obj : objects) names.add(getString(obj));
                yield new DropSchemaStmt(names, ifExists, beh);
            }
            case "OBJECT_SEQUENCE" -> {
                var seqs = new ArrayList<RangeVar>();
                for (JsonNode obj : objects) seqs.add(rangeVarFromList(obj.path("List").path("items")));
                yield new DropSequenceStmt(seqs, ifExists, beh);
            }
            case "OBJECT_VIEW", "OBJECT_MATVIEW" -> {
                var views = new ArrayList<RangeVar>();
                for (JsonNode obj : objects) views.add(rangeVarFromList(obj.path("List").path("items")));
                yield new DropViewStmt(views, ifExists, beh);
            }
            case "OBJECT_FUNCTION", "OBJECT_PROCEDURE" -> {
                var targets = new ArrayList<DropFunctionStmt.Target>();
                for (JsonNode obj : objects) {
                    JsonNode owa = obj.path("ObjectWithArgs");
                    var nameParts = new ArrayList<String>();
                    for (JsonNode s : owa.path("objname")) nameParts.add(getString(s));
                    // objargs: list of TypeName nodes; absent/empty when args not specified
                    List<TypeName> argTypes = null;
                    JsonNode argsNode = owa.path("objargs");
                    boolean argsUnspecified = owa.path("args_unspecified").booleanValue();
                    if (!argsUnspecified && !argsNode.isMissingNode() && argsNode.isArray()) {
                        argTypes = new ArrayList<>();
                        for (JsonNode a : argsNode) {
                            argTypes.add(convertTypeName(a));
                        }
                    }
                    targets.add(new DropFunctionStmt.Target(nameParts, argTypes));
                }
                yield new DropFunctionStmt(targets, ifExists);
            }
            case "OBJECT_TRIGGER" -> {
                // objects: [ List{ items: [ [schema,] tableName, triggerName ] } ]
                JsonNode items = objects.path(0).path("List").path("items");
                int sz = items.size();
                // Last item is trigger name, preceding items form the table reference
                String trigName = getString(items.path(sz - 1));
                String tblName = sz >= 3
                        ? getString(items.path(0)) + "." + getString(items.path(1))
                        : getString(items.path(0));
                yield new DropTriggerStmt(trigName, tblName, ifExists, beh);
            }
            case "OBJECT_TYPE", "OBJECT_DOMAIN" -> {
                var typeNames = new ArrayList<List<String>>();
                for (JsonNode obj : objects) {
                    TypeName tn = convertTypeName(obj);
                    typeNames.add(tn.names());
                }
                yield new DropTypeStmt(typeNames, ifExists, beh);
            }
            default -> new UnsupportedStmt("DropStmt/" + removeType, "");
        };
    }

    private static AlterTableStmt convertAlterTable(JsonNode c) {
        RangeVar rel = convertRangeVar(c.path("relation"));
        var cmds = new ArrayList<AlterTableCmd>();
        for (JsonNode cmd : c.path("cmds")) cmds.add(convertAlterCmd(cmd.path("AlterTableCmd")));
        return new AlterTableStmt(rel, cmds);
    }

    private static AlterTableCmd convertAlterCmd(JsonNode c) {
        String sub = c.path("subtype").asText("");
        AlterTableType t = switch (sub) {
            case "AT_AddColumn"       -> AlterTableType.ADD_COLUMN;
            case "AT_DropColumn"      -> AlterTableType.DROP_COLUMN;
            case "AT_AlterColumnType" -> AlterTableType.ALTER_COLUMN_TYPE;
            case "AT_SetNotNull"      -> AlterTableType.SET_NOT_NULL;
            case "AT_DropNotNull"     -> AlterTableType.DROP_NOT_NULL;
            case "AT_ColumnDefault"   -> AlterTableType.SET_DEFAULT;
            case "AT_DropColumnDefault" -> AlterTableType.DROP_DEFAULT;
            case "AT_AddConstraint"   -> AlterTableType.ADD_CONSTRAINT;
            case "AT_DropConstraint"  -> AlterTableType.DROP_CONSTRAINT;
            case "AT_ValidateConstraint" -> AlterTableType.VALIDATE_CONSTRAINT;
            case "AT_RenameColumn"    -> AlterTableType.RENAME_COLUMN;
            case "AT_RenameRelation"  -> AlterTableType.RENAME_TABLE;
            default -> AlterTableType.OTHER;
        };
        String name = c.path("name").asText(null);
        ColumnDefNode def;
        if (t == AlterTableType.ADD_CONSTRAINT) {
            // def is a Constraint node, not a ColumnDef; wrap it as executor expects
            def = convertConstraintAsColumnDef(c.path("def"));
        } else {
            def = c.path("def").isMissingNode() ? null
                    : convertColumnDef(c.path("def").path("ColumnDef").isMissingNode()
                            ? c.path("def") : c.path("def").path("ColumnDef"));
        }
        DropBehavior beh = "DROP_CASCADE".equals(c.path("behavior").asText()) ? DropBehavior.CASCADE : DropBehavior.RESTRICT;
        return new AlterTableCmd(t, name, def, beh);
    }

    /** Convert a libpg_query Constraint node into the ColumnDefNode wrapper that DdlExecutor.ADD_CONSTRAINT expects. */
    private static ColumnDefNode convertConstraintAsColumnDef(JsonNode defNode) {
        if (defNode.isMissingNode()) return null;
        JsonNode con = defNode.has("Constraint") ? defNode.path("Constraint") : defNode;
        String contype = con.path("contype").asText("");
        String conname = con.path("conname").asText(null);

        ColumnConstraintNode ccn = switch (contype) {
            case "CONSTR_UNIQUE" -> {
                List<String> cols = new ArrayList<>();
                for (JsonNode k : con.path("keys")) {
                    String col = k.has("String") ? k.path("String").path("sval").asText()
                               : k.has("IndexElem") ? k.path("IndexElem").path("name").asText()
                               : k.asText();
                    if (col != null && !col.isEmpty()) cols.add(col.toLowerCase());
                }
                yield new ColumnConstraintNode(ConstrType.UNIQUE, conname,
                        null, List.of(), null, cols, null, null, false, false);
            }
            case "CONSTR_PRIMARY" -> {
                List<String> cols = new ArrayList<>();
                for (JsonNode k : con.path("keys")) {
                    String col = k.has("String") ? k.path("String").path("sval").asText()
                               : k.has("IndexElem") ? k.path("IndexElem").path("name").asText()
                               : k.asText();
                    if (col != null && !col.isEmpty()) cols.add(col.toLowerCase());
                }
                yield new ColumnConstraintNode(ConstrType.PRIMARY, conname,
                        null, List.of(), null, cols, null, null, false, false);
            }
            case "CONSTR_CHECK" -> {
                Expr expr = con.path("raw_expr").isMissingNode() ? null
                        : convertExpr(con.path("raw_expr"));
                yield ColumnConstraintNode.check(conname, expr);
            }
            default -> null;
        };

        if (ccn == null) return null;
        return new ColumnDefNode("", TypeName.of("text"), List.of(ccn), null);
    }

    private static CreateViewStmt convertView(JsonNode c) {
        RangeVar view = convertRangeVar(c.path("view"));
        var aliases = new ArrayList<String>();
        for (JsonNode a : c.path("aliases")) aliases.add(a.asText());
        SelectStmt query = convertSelectNode(c.path("query"));
        boolean replace = c.path("replace").booleanValue();
        boolean temp = c.path("view").path("relpersistence").asText("p").equals("t");
        CreateViewStmt.ViewCheckOption chk = switch (c.path("withCheckOption").asText("")) {
            case "LOCAL_CHECK_OPTION"    -> CreateViewStmt.ViewCheckOption.LOCAL;
            case "CASCADED_CHECK_OPTION" -> CreateViewStmt.ViewCheckOption.CASCADED;
            default -> CreateViewStmt.ViewCheckOption.NONE;
        };
        return new CreateViewStmt(view, aliases, query, replace, temp, chk);
    }

    private static CreateSchemaStmt convertCreateSchema(JsonNode c) {
        String name = c.path("schemaname").asText(null);
        boolean ifNotExists = c.path("if_not_exists").booleanValue();
        return new CreateSchemaStmt(name, ifNotExists);
    }

    private static CreateSequenceStmt convertCreateSeq(JsonNode c) {
        RangeVar seq = convertRangeVar(c.path("sequence"));
        List<DefElem> opts = convertDefElems(c.path("options"));
        boolean ifNotExists = c.path("if_not_exists").booleanValue();
        return new CreateSequenceStmt(seq, opts, ifNotExists);
    }

    private static AlterSequenceStmt convertAlterSeq(JsonNode c) {
        RangeVar seq = convertRangeVar(c.path("sequence"));
        List<DefElem> opts = convertDefElems(c.path("options"));
        boolean ifExists = c.path("missing_ok").booleanValue();
        return new AlterSequenceStmt(seq, opts, ifExists);
    }

    private static CreateFunctionStmt convertCreateFunction(JsonNode c, boolean isProc) {
        var funcname = new ArrayList<String>();
        for (JsonNode n : c.path("funcname")) funcname.add(getString(n));
        var params = new ArrayList<FunctionParameter>();
        for (JsonNode p : c.path("parameters")) params.add(convertFunctionParam(p.path("FunctionParameter")));
        TypeName retType = c.path("returnType").isMissingNode() ? null : convertTypeName(c.path("returnType"));
        List<DefElem> opts = convertDefElems(c.path("options"));
        boolean replace = c.path("replace").booleanValue();
        return new CreateFunctionStmt(funcname, params, retType, opts, replace, isProc);
    }

    private static FunctionParameter convertFunctionParam(JsonNode c) {
        String name = c.path("name").asText(null);
        TypeName argType = convertTypeName(c.path("argType"));
        FunctionParameterMode mode = switch (c.path("mode").asText("FUNC_PARAM_DEFAULT")) {
            case "FUNC_PARAM_IN"       -> FunctionParameterMode.IN;
            case "FUNC_PARAM_OUT"      -> FunctionParameterMode.OUT;
            case "FUNC_PARAM_INOUT"    -> FunctionParameterMode.INOUT;
            case "FUNC_PARAM_VARIADIC" -> FunctionParameterMode.VARIADIC;
            case "FUNC_PARAM_TABLE"    -> FunctionParameterMode.TABLE;
            default                    -> FunctionParameterMode.IN;
        };
        Expr defexpr = exprOpt(c.path("defexpr"));
        return new FunctionParameter(name, argType, mode, defexpr);
    }

    private static TruncateStmt convertTruncate(JsonNode c) {
        var rels = new ArrayList<RangeVar>();
        for (JsonNode r : c.path("relations")) rels.add(convertRangeVar(r.path("RangeVar").isMissingNode() ? r : r.path("RangeVar")));
        boolean restartSeqs = c.path("restart_seqs").booleanValue();
        DropBehavior beh = "DROP_CASCADE".equals(c.path("behavior").asText()) ? DropBehavior.CASCADE : DropBehavior.RESTRICT;
        return new TruncateStmt(rels, restartSeqs, beh);
    }

    private static ExplainStmt convertExplain(JsonNode c) {
        Stmt query = convertStmtNode(c.path("query"));
        boolean analyze = false, verbose = false, buffers = false;
        for (JsonNode opt : c.path("options")) {
            String name = opt.path("DefElem").path("defname").asText();
            if ("analyze".equals(name)) analyze = true;
            if ("verbose".equals(name)) verbose = true;
            if ("buffers".equals(name)) buffers = true;
        }
        return new ExplainStmt(query, analyze, verbose, buffers);
    }

    private static CopyStmt convertCopy(JsonNode c) {
        RangeVar rel = c.path("relation").isMissingNode() ? null : convertRangeVar(c.path("relation"));
        SelectStmt query = c.path("query").isMissingNode() ? null : convertSelectNode(c.path("query"));
        boolean isFrom = c.path("is_from").booleanValue();
        var attlist = new ArrayList<String>();
        for (JsonNode a : c.path("attlist")) {
            // In pg_query, COPY attlist entries are String nodes: {"String": {"sval": "col"}}
            // For COPY FROM, some versions may emit ResTarget nodes instead.
            String colName;
            if (a.has("String")) {
                colName = a.path("String").path("sval").asText();
            } else if (a.has("ResTarget")) {
                colName = a.path("ResTarget").path("name").asText();
            } else {
                colName = a.asText();
            }
            if (colName != null && !colName.isEmpty()) attlist.add(colName);
        }
        List<DefElem> opts = convertDefElems(c.path("options"));
        return new CopyStmt(rel, query, isFrom, attlist, opts);
    }

    private static Stmt convertTransaction(JsonNode c) {
        return switch (c.path("kind").asText()) {
            case "TRANS_STMT_BEGIN"       -> new BeginStmt();
            case "TRANS_STMT_COMMIT", "TRANS_STMT_COMMIT_PREPARED" -> new CommitStmt();
            case "TRANS_STMT_ROLLBACK", "TRANS_STMT_ROLLBACK_PREPARED" -> new RollbackStmt();
            case "TRANS_STMT_SAVEPOINT"   -> new SavepointStmt(c.path("savepoint_name").asText());
            case "TRANS_STMT_ROLLBACK_TO" -> new RollbackToSavepointStmt(c.path("savepoint_name").asText());
            case "TRANS_STMT_RELEASE"     -> new ReleaseSavepointStmt(c.path("savepoint_name").asText());
            default -> new UnsupportedStmt("TransactionStmt/" + c.path("kind").asText(), "");
        };
    }

    private static SetStmt convertVariableSet(JsonNode c) {
        String name = c.path("name").asText("");
        var args = new ArrayList<Node>();
        for (JsonNode a : c.path("args")) args.add(convertExpr(a));
        boolean isLocal = c.path("is_local").booleanValue();
        SetScope scope = isLocal ? SetScope.LOCAL : SetScope.DEFAULT;
        // VAR_SET_DEFAULT / VAR_RESET → empty args list, which is fine
        return new SetStmt(name, args, scope);
    }

    private static VacuumStmt convertVacuum(JsonNode c) {
        var rels = new ArrayList<RangeVar>();
        for (JsonNode r : c.path("rels")) {
            JsonNode rv = r.path("VacuumRelation").path("relation");
            if (!rv.isMissingNode()) rels.add(convertRangeVar(rv));
        }
        boolean analyze = c.path("is_vacuumcmd").isMissingNode(); // absent → it's ANALYZE
        for (JsonNode opt : c.path("options")) {
            if ("analyze".equals(opt.path("DefElem").path("defname").asText())) analyze = true;
        }
        return new VacuumStmt(rels, analyze);
    }

    private static LockTableStmt convertLockTable(JsonNode c) {
        var rels = new ArrayList<RangeVar>();
        for (JsonNode r : c.path("relations")) {
            JsonNode rv = r.path("RangeVar");
            rels.add(convertRangeVar(rv.isMissingNode() ? r : rv));
        }
        // pg_query mode field: 1=AccessShare through 8=AccessExclusive
        // Default (bare LOCK TABLE) = 8 (AccessExclusive)
        int mode = c.path("mode").asInt(8);
        boolean nowait = c.path("nowait").booleanValue();
        return new LockTableStmt(rels, mode, nowait);
    }

    /**
     * Convert a RenameStmt (ALTER TABLE ... RENAME TO / RENAME COLUMN) into an
     * AlterTableStmt so the DDL executor handles it uniformly.
     */
    private static AlterTableStmt convertRename(JsonNode c) {
        RangeVar rel = convertRangeVar(c.path("relation"));
        String renameType = c.path("renameType").asText("");
        String newName = c.path("newname").asText(null);
        var cmds = new ArrayList<AlterTableCmd>();

        switch (renameType) {
            case "OBJECT_TABLE" -> {
                // ALTER TABLE x RENAME TO y  — newname is the new table name
                cmds.add(new AlterTableCmd(AlterTableType.RENAME_TABLE, newName, null,
                        DropBehavior.RESTRICT));
            }
            case "OBJECT_COLUMN" -> {
                // ALTER TABLE x RENAME COLUMN a TO b  — subname=old, newname=new
                String oldName = c.path("subname").asText(null);
                ColumnDefNode def = new ColumnDefNode(newName, null, List.of(), null);
                cmds.add(new AlterTableCmd(AlterTableType.RENAME_COLUMN, oldName, def,
                        DropBehavior.RESTRICT));
            }
            default -> {
                // Other rename types (index, schema, etc.) — unsupported for now
                cmds.add(new AlterTableCmd(AlterTableType.OTHER, null, null,
                        DropBehavior.RESTRICT));
            }
        }
        return new AlterTableStmt(rel, cmds);
    }

    // ─── Expression dispatch ─────────────────────────────────────────────────

    /** n is a node-type wrapper: {@code {"ColumnRef":{…}}} etc. */
    private static Expr convertExpr(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode() || n.isEmpty()) return null;
        String type = firstKey(n);
        JsonNode c = n.path(type);
        return switch (type) {
            case "A_Const"        -> convertConst(n.path("A_Const"));
            case "ColumnRef"      -> convertColumnRef(c);
            case "A_Expr"         -> convertAExpr(c);
            case "TypeCast"       -> convertTypeCast(c);
            case "FuncCall"       -> convertFuncCall(c);
            case "CoalesceExpr"   -> convertCoalesceExpr(c);
            case "MinMaxExpr"     -> convertMinMaxExpr(c);
            case "NullIfExpr"     -> FunctionCall.simple("nullif",
                    List.of(convertExpr(c.path("args").get(0)), convertExpr(c.path("args").get(1))));
            case "BoolExpr"       -> convertBoolExpr(c);
            case "NullTest"       -> convertNullTest(c);
            case "BooleanTest"    -> convertBooleanTest(c);
            case "SubLink"        -> convertSubLink(c);
            case "CaseExpr"       -> convertCaseExpr(c);
            case "A_ArrayExpr"    -> new ArrayExpr(exprList(c.path("elements")));
            case "RowExpr"        -> new RowExpr(exprList(c.path("args")));
            case "A_Indirection"  -> convertIndirection(c);
            case "NamedArgExpr"   -> new NamedArgExpr(c.path("name").asText(), convertExpr(c.path("arg")));
            case "CollateClause"  -> new CollateExpr(convertExpr(c.path("arg")), stringList(c.path("collname")));
            case "ParamRef"       -> new ParamRef(c.path("number").intValue());
            case "GroupingFunc"   -> new GroupingExpr(exprList(c.path("args")));
            case "SetToDefault"   -> new SetToDefault();
            case "SQLValueFunction" -> convertSqlValueFunction(c);
            case "Integer"        -> new IntegerLiteral(c.path("ival").longValue());
            case "String"         -> new StringLiteral(c.path("sval").asText());
            case "Float"          -> new FloatLiteral(c.path("fval").asText());
            case "Boolean"        -> new BooleanLiteral(c.path("boolval").booleanValue());
            default -> throw new UnsupportedOperationException("Unknown expr node: " + type);
        };
    }

    private static Expr convertConst(JsonNode c) {
        if (c.path("isnull").booleanValue()) return new NullLiteral();
        if (c.has("ival")) {
            JsonNode ival = c.path("ival");
            return new IntegerLiteral(ival.path("ival").longValue()); // {} = 0
        }
        if (c.has("fval")) return new FloatLiteral(c.path("fval").path("fval").asText());
        if (c.has("sval")) return new StringLiteral(c.path("sval").path("sval").asText(""));
        if (c.has("boolval")) return new BooleanLiteral(c.path("boolval").path("boolval").booleanValue());
        return new NullLiteral(); // empty A_Const = NULL
    }

    private static ColumnRef convertColumnRef(JsonNode c) {
        var fields = new ArrayList<String>();
        for (JsonNode f : c.path("fields")) {
            if (f.has("String"))   fields.add(f.path("String").path("sval").asText());
            else if (f.has("A_Star")) fields.add("*");
        }
        return new ColumnRef(fields);
    }

    private static Expr convertAExpr(JsonNode c) {
        String kind = c.path("kind").asText("AEXPR_OP");
        String opName = firstOpName(c.path("name"));
        JsonNode lexpr = c.path("lexpr");
        JsonNode rexpr = c.path("rexpr");

        return switch (kind) {
            case "AEXPR_OP" -> {
                if (lexpr.isMissingNode()) {
                    // Unary operator (e.g. + or -)
                    yield new UnaryOp(opName, convertExpr(rexpr));
                }
                yield new BinaryOp(opName, convertExpr(lexpr), convertExpr(rexpr));
            }
            case "AEXPR_BETWEEN", "AEXPR_NOT_BETWEEN",
                 "AEXPR_BETWEEN_SYM", "AEXPR_NOT_BETWEEN_SYM" -> {
                boolean neg = kind.contains("NOT");
                boolean sym = kind.contains("SYM");
                JsonNode bounds = rexpr.path("List").path("items");
                Expr low  = convertExpr(bounds.get(0));
                Expr high = convertExpr(bounds.get(1));
                yield new BetweenExpr(convertExpr(lexpr), low, high, neg, sym);
            }
            case "AEXPR_LIKE", "AEXPR_NOT_LIKE" -> {
                // pg_query v5: NOT LIKE uses kind=AEXPR_LIKE with name="!~~"
                boolean neg = kind.contains("NOT") || "!~~".equals(opName);
                yield new LikeExpr(convertExpr(lexpr), convertExpr(rexpr), null, LikeType.LIKE, neg);
            }
            case "AEXPR_ILIKE", "AEXPR_NOT_ILIKE" -> {
                // pg_query v5: NOT ILIKE uses kind=AEXPR_ILIKE with name="!~~*"
                boolean neg = kind.contains("NOT") || "!~~*".equals(opName);
                yield new LikeExpr(convertExpr(lexpr), convertExpr(rexpr), null, LikeType.ILIKE, neg);
            }
            case "AEXPR_SIMILAR" -> {
                // rexpr is a FuncCall wrapping the pattern through similar_to_escape
                // extract the original pattern from the first arg
                Expr pattern = extractSimilarPattern(rexpr);
                // pg_query v5: NOT SIMILAR TO uses name="!~"
                boolean neg = "!~".equals(opName);
                yield new LikeExpr(convertExpr(lexpr), pattern, null, LikeType.SIMILAR_TO, neg);
            }
            case "AEXPR_NULLIF" ->
                FunctionCall.simple("nullif", List.of(convertExpr(lexpr), convertExpr(rexpr)));
            case "AEXPR_DISTINCT" ->
                new BinaryOp("IS DISTINCT FROM", convertExpr(lexpr), convertExpr(rexpr));
            case "AEXPR_NOT_DISTINCT" ->
                new BinaryOp("IS NOT DISTINCT FROM", convertExpr(lexpr), convertExpr(rexpr));
            case "AEXPR_OP_ANY" ->
                new ArrayAnyAllExpr(convertExpr(lexpr), convertExpr(rexpr), opName, false);
            case "AEXPR_OP_ALL" ->
                new ArrayAnyAllExpr(convertExpr(lexpr), convertExpr(rexpr), opName, true);
            case "AEXPR_IN" -> {
                // "=" = IN, "<>" = NOT IN
                boolean neg = "<>".equals(opName);
                JsonNode items = rexpr.path("List").path("items");
                if (!items.isMissingNode()) {
                    yield new InExpr(convertExpr(lexpr), exprList(items), neg);
                }
                yield new InExpr(convertExpr(lexpr), List.of(), neg);
            }
            default -> new BinaryOp(opName, convertExpr(lexpr), convertExpr(rexpr));
        };
    }

    private static Expr extractSimilarPattern(JsonNode rexpr) {
        // SIMILAR TO wraps pattern in similar_to_escape(pattern) call
        JsonNode fc = rexpr.path("FuncCall");
        if (!fc.isMissingNode()) {
            JsonNode args = fc.path("args");
            if (args.isArray() && args.size() > 0) return convertExpr(args.get(0));
        }
        return convertExpr(rexpr);
    }

    private static Expr convertTypeCast(JsonNode c) {
        TypeName tn = convertTypeName(c.path("typeName"));
        JsonNode arg = c.path("arg");

        // Detect typed literal syntax (location = -1): DATE '...', INTERVAL '...' etc.
        JsonNode locNode = c.path("location");
        int location = locNode.isMissingNode() ? -1 : locNode.intValue();
        if (location == -1 && arg.has("A_Const") && arg.path("A_Const").has("sval")) {
            String val = arg.path("A_Const").path("sval").path("sval").asText();
            String simpleName = tn.simpleName();
            if ("interval".equals(simpleName)) {
                // Extract interval fields from type modifiers or leave null
                return new IntervalLiteral(val, null);
            }
            // Other typed literals: DATE, TIMESTAMP, TIME, etc.
            return new TypedLiteral(tn, val);
        }
        return new CastExpr(convertExpr(arg), tn);
    }

    private static FunctionCall convertFuncCall(JsonNode c) {
        var name = new ArrayList<String>();
        for (JsonNode fn : c.path("funcname")) name.add(getString(fn));

        // GREATEST / LEAST → delegate to MinMaxExpr logic via name
        if (name.size() == 1) {
            String n = name.get(0).toLowerCase();
            if ("greatest".equals(n)) return new FunctionCall(
                    List.of("greatest"), exprList(c.path("args")), false, List.of(), null, false, false, null);
            if ("least".equals(n)) return new FunctionCall(
                    List.of("least"), exprList(c.path("args")), false, List.of(), null, false, false, null);
        }

        List<Expr> args = exprList(c.path("args"));
        boolean aggDistinct = c.path("agg_distinct").booleanValue();
        boolean aggStar     = c.path("agg_star").booleanValue();
        boolean withinGroup = c.path("agg_within_group").booleanValue();
        List<SortKey> aggOrder = sortList(c.path("agg_order"));
        Expr aggFilter = exprOpt(c.path("agg_filter"));
        WindowDef over = null;
        if (!c.path("over").isMissingNode()) over = convertWindowDef(c.path("over"));
        return new FunctionCall(name, args, aggDistinct, aggOrder, aggFilter, aggStar, withinGroup, over);
    }

    private static FunctionCall convertCoalesceExpr(JsonNode c) {
        return FunctionCall.simple("coalesce", exprList(c.path("args")));
    }

    private static Expr convertMinMaxExpr(JsonNode c) {
        MinMaxOp op = "IS_GREATEST".equals(c.path("op").asText()) ? MinMaxOp.GREATEST : MinMaxOp.LEAST;
        return new MinMaxExpr(op, exprList(c.path("args")));
    }

    private static Expr convertBoolExpr(JsonNode c) {
        String boolop = c.path("boolop").asText();
        JsonNode args = c.path("args");
        if ("NOT_EXPR".equals(boolop)) return new UnaryOp("NOT", convertExpr(args.get(0)));
        // AND / OR — may have >2 args; left-fold
        String op = "AND_EXPR".equals(boolop) ? "AND" : "OR";
        Expr acc = convertExpr(args.get(0));
        for (int i = 1; i < args.size(); i++) acc = new BinaryOp(op, acc, convertExpr(args.get(i)));
        return acc;
    }

    private static Expr convertNullTest(JsonNode c) {
        String ntt = c.path("nulltesttype").asText();
        String op = "IS_NULL".equals(ntt) ? "IS NULL" : "IS NOT NULL";
        return new UnaryOp(op, convertExpr(c.path("arg")));
    }

    private static Expr convertBooleanTest(JsonNode c) {
        String op = switch (c.path("booltesttype").asText()) {
            case "IS_TRUE"       -> "IS TRUE";
            case "IS_FALSE"      -> "IS FALSE";
            case "IS_UNKNOWN"    -> "IS UNKNOWN";
            case "IS_NOT_TRUE"   -> "IS NOT TRUE";
            case "IS_NOT_FALSE"  -> "IS NOT FALSE";
            case "IS_NOT_UNKNOWN"-> "IS NOT UNKNOWN";
            default -> "IS TRUE";
        };
        return new UnaryOp(op, convertExpr(c.path("arg")));
    }

    private static Expr convertSubLink(JsonNode c) {
        String linkType = c.path("subLinkType").asText();
        SelectStmt sub = convertSelectNode(c.path("subselect"));

        return switch (linkType) {
            case "EXISTS_SUBLINK" -> new SubLink(SubLinkType.EXISTS, null, null, sub);
            case "EXPR_SUBLINK"   -> new SubLink(SubLinkType.EXPR,   null, null, sub);
            case "ARRAY_SUBLINK"  -> new ArraySubselect(sub);
            case "ANY_SUBLINK" -> {
                Expr testExpr = exprOpt(c.path("testexpr"));
                JsonNode on = c.path("operName");
                // operName absent (or "=") = IN, otherwise = ANY with operator
                if (on.isMissingNode() || (on.isArray() && on.size() == 1 && "=".equals(getString(on.get(0))))) {
                    yield new InSubselect(testExpr, sub, false);
                }
                yield new SubLink(SubLinkType.ANY, testExpr, firstOpName(on), sub);
            }
            case "ALL_SUBLINK" -> {
                Expr testExpr = exprOpt(c.path("testexpr"));
                yield new SubLink(SubLinkType.ALL, testExpr, firstOpName(c.path("operName")), sub);
            }
            default -> new SubLink(SubLinkType.EXPR, null, null, sub);
        };
    }

    private static CaseExpr convertCaseExpr(JsonNode c) {
        Expr arg = exprOpt(c.path("arg"));
        var whens = new ArrayList<CaseWhen>();
        for (JsonNode w : c.path("args")) {
            JsonNode cw = w.path("CaseWhen");
            whens.add(new CaseWhen(convertExpr(cw.path("expr")), convertExpr(cw.path("result"))));
        }
        Expr def = exprOpt(c.path("defresult"));
        return new CaseExpr(arg, whens, def);
    }

    private static Expr convertIndirection(JsonNode c) {
        Expr target = convertExpr(c.path("arg"));
        // Process each indirection level
        for (JsonNode ind : c.path("indirection")) {
            if (ind.has("A_Indices")) {
                JsonNode ai = ind.path("A_Indices");
                boolean isSlice = ai.path("is_slice").booleanValue();
                Expr idx      = exprOpt(ai.path("uidx"));
                Expr idxLower = exprOpt(ai.path("lidx"));
                target = new SubscriptExpr(target, isSlice ? idxLower : idx, isSlice ? idx : null, isSlice);
            } else if (ind.has("String")) {
                String field = ind.path("String").path("sval").asText();
                target = new FieldSelectExpr(target, field);
            }
        }
        return target;
    }

    private static Expr convertSqlValueFunction(JsonNode c) {
        // CURRENT_DATE, CURRENT_TIMESTAMP, etc. → FunctionCall
        String op = c.path("op").asText("");
        String name = switch (op) {
            case "SVFOP_CURRENT_DATE"         -> "current_date";
            case "SVFOP_CURRENT_TIME"         -> "current_time";
            case "SVFOP_CURRENT_TIMESTAMP"    -> "current_timestamp";
            case "SVFOP_LOCALTIME"            -> "localtime";
            case "SVFOP_LOCALTIMESTAMP"       -> "localtimestamp";
            case "SVFOP_CURRENT_ROLE"         -> "current_role";
            case "SVFOP_CURRENT_USER"         -> "current_user";
            case "SVFOP_SESSION_USER"         -> "session_user";
            default                           -> op.toLowerCase().replace("svfop_", "");
        };
        return FunctionCall.simple(name, List.of());
    }

    // ─── FROM items ──────────────────────────────────────────────────────────

    private static FromItem convertFromItem(JsonNode n) {
        String type = firstKey(n);
        JsonNode c = n.path(type);
        return switch (type) {
            case "RangeVar"        -> convertRangeVar(c);
            case "JoinExpr"        -> convertJoinExpr(c);
            case "RangeSubselect"  -> convertRangeSubselect(c);
            case "RangeFunction"   -> convertRangeFunction(c);
            default -> convertRangeVar(n); // best-effort fallback
        };
    }

    private static RangeVar convertRangeVar(JsonNode c) {
        if (c == null || c.isMissingNode()) return null;
        String schema = c.path("schemaname").asText(null);
        String rel    = c.path("relname").asText("");
        String alias  = null;
        JsonNode aliasNode = c.path("alias");
        if (!aliasNode.isMissingNode()) {
            alias = aliasNode.path("aliasname").asText(null);
            if (alias == null) alias = aliasNode.path("Alias").path("aliasname").asText(null);
        }
        JsonNode inhNode = c.path("inh");
        boolean inh = inhNode.isMissingNode() || inhNode.booleanValue();
        return new RangeVar(schema, rel, alias, inh);
    }

    private static JoinExpr convertJoinExpr(JsonNode c) {
        String jt = c.path("jointype").asText("JOIN_INNER");
        JoinType joinType = switch (jt) {
            case "JOIN_LEFT"  -> JoinType.LEFT;
            case "JOIN_RIGHT" -> JoinType.RIGHT;
            case "JOIN_FULL"  -> JoinType.FULL;
            default           -> JoinType.INNER;
        };
        // CROSS JOIN has JOIN_INNER but no quals/using (handled by joinType staying INNER)
        boolean natural = c.path("isNatural").booleanValue();
        FromItem larg = convertFromItem(c.path("larg"));
        FromItem rarg = convertFromItem(c.path("rarg"));
        Expr quals = exprOpt(c.path("quals"));
        var usingCols = new ArrayList<String>();
        for (JsonNode u : c.path("usingClause")) usingCols.add(getString(u));
        return new JoinExpr(joinType, larg, rarg, quals, usingCols, natural);
    }

    private static RangeSubselect convertRangeSubselect(JsonNode c) {
        SelectStmt sub = convertSelectNode(c.path("subquery"));
        String alias = c.path("alias").path("aliasname").asText(null);
        var colAliases = new ArrayList<String>();
        for (JsonNode a : c.path("alias").path("colnames")) colAliases.add(getString(a));
        boolean lateral = c.path("lateral").booleanValue();
        return new RangeSubselect(sub, alias, colAliases, lateral);
    }

    private static RangeFunction convertRangeFunction(JsonNode c) {
        // pg_query encodes functions as an array of List-wrapped [funcCall, coldefList] pairs:
        //   "functions": [{"List":{"items":[{"FuncCall":{...}},{}]}}]
        // Older or alternate encodings may use a plain array or direct FuncCall.
        FunctionCall fn = null;
        JsonNode fns = c.path("functions");
        if (fns.isArray() && !fns.isEmpty()) {
            JsonNode pair = fns.get(0);
            if (!pair.path("List").isMissingNode()) {
                // Standard pg_query format: {"List":{"items":[FuncCall, colDefList]}}
                JsonNode items = pair.path("List").path("items");
                if (items.isArray() && !items.isEmpty()) {
                    fn = convertFuncCall(items.get(0).path("FuncCall"));
                }
            } else if (pair.isArray() && !pair.isEmpty()) {
                fn = convertFuncCall(pair.get(0).path("FuncCall"));
            } else {
                fn = convertFuncCall(pair.path("FuncCall"));
            }
        }
        String alias = c.path("alias").path("aliasname").asText(null);
        var colAliases = new ArrayList<String>();
        for (JsonNode a : c.path("alias").path("colnames")) colAliases.add(getString(a));
        boolean lateral = c.path("lateral").booleanValue();
        boolean withOrd = c.path("ordinality").booleanValue();
        return new RangeFunction(fn, alias, colAliases, lateral, withOrd);
    }

    // ─── Structural nodes ────────────────────────────────────────────────────

    private static List<TargetEntry> convertTargetList(JsonNode arr) {
        if (arr == null || arr.isMissingNode()) return List.of();
        var out = new ArrayList<TargetEntry>();
        for (JsonNode te : arr) {
            JsonNode rt = te.path("ResTarget");
            if (rt.isMissingNode()) continue;
            Expr val = convertExpr(rt.path("val"));
            String name = rt.path("name").asText(null);
            out.add(new TargetEntry(val, name));
        }
        return out;
    }

    private static List<AssignTarget> convertAssignTargets(JsonNode arr) {
        if (arr == null || arr.isMissingNode()) return List.of();
        var out = new ArrayList<AssignTarget>();
        for (JsonNode te : arr) {
            JsonNode rt = te.path("ResTarget");
            if (rt.isMissingNode()) continue;
            String name = rt.path("name").asText(null);
            Expr val = exprOpt(rt.path("val"));
            // multi-column assign: (col1, col2) = (subselect) — rare
            out.add(new AssignTarget(name != null ? List.of(name) : List.of(), val, null));
        }
        return out;
    }

    private static List<SortKey> sortList(JsonNode arr) {
        if (arr == null || arr.isMissingNode()) return List.of();
        var out = new ArrayList<SortKey>();
        for (JsonNode sk : arr) {
            JsonNode s = sk.path("SortBy");
            if (s.isMissingNode()) s = sk;
            Expr node = convertExpr(s.path("node"));
            SortByDir dir = switch (s.path("sortby_dir").asText("SORTBY_DEFAULT")) {
                case "SORTBY_ASC"   -> SortByDir.ASC;
                case "SORTBY_DESC"  -> SortByDir.DESC;
                case "SORTBY_USING" -> SortByDir.USING;
                default             -> SortByDir.DEFAULT;
            };
            SortByNulls nulls = switch (s.path("sortby_nulls").asText("SORTBY_NULLS_DEFAULT")) {
                case "SORTBY_NULLS_FIRST" -> SortByNulls.FIRST;
                case "SORTBY_NULLS_LAST"  -> SortByNulls.LAST;
                default                   -> SortByNulls.DEFAULT;
            };
            out.add(new SortKey(node, dir, nulls));
        }
        return out;
    }

    private static TypeName convertTypeName(JsonNode n) {
        // n may be the TypeName content directly or wrapped in {"TypeName": {...}}
        JsonNode c = n.has("TypeName") ? n.path("TypeName") : n;
        if (c.isMissingNode()) return null;
        var names = new ArrayList<String>();
        for (JsonNode nm : c.path("names")) names.add(getString(nm));
        // Type modifiers (precision, scale, etc.)
        var typmods = new ArrayList<Node>();
        for (JsonNode tm : c.path("typmods")) {
            Expr e = tryConvertExpr(tm);
            if (e != null) typmods.add(e);
        }
        // Array dimensions
        int arrayBounds = 0;
        JsonNode ab = c.path("arrayBounds");
        if (!ab.isMissingNode() && ab.isArray()) arrayBounds = ab.size();
        boolean setOf = c.path("setof").booleanValue();
        return new TypeName(names, typmods, arrayBounds, setOf);
    }

    private static WithClause withClauseOpt(JsonNode parent) {
        JsonNode wc = parent.path("withClause");
        if (wc.isMissingNode()) return null;
        boolean recursive = wc.path("recursive").booleanValue();
        var ctes = new ArrayList<CommonTableExpr>();
        for (JsonNode ct : wc.path("ctes")) {
            JsonNode cte = ct.path("CommonTableExpr");
            String name = cte.path("ctename").asText();
            var colNames = new ArrayList<String>();
            for (JsonNode cn : cte.path("aliascolnames")) colNames.add(getString(cn));
            SelectStmt query = convertSelectNode(cte.path("ctequery"));
            CTEMaterialize mat = switch (cte.path("ctematerialized").asText("CTEMaterializeDefault")) {
                case "CTEMaterializeAlways" -> CTEMaterialize.MATERIALIZED;
                case "CTEMaterializeNever"  -> CTEMaterialize.NOT_MATERIALIZED;
                default                     -> CTEMaterialize.DEFAULT;
            };
            ctes.add(new CommonTableExpr(name, colNames, query, mat));
        }
        return new WithClause(ctes, recursive);
    }

    private static WindowDef convertWindowDef(JsonNode c) {
        if (c == null || c.isMissingNode()) return null;
        String name = c.path("name").asText(null);
        String refname = c.path("refname").asText(null);
        List<Expr> partition = exprList(c.path("partitionClause"));
        List<SortKey> order  = sortList(c.path("orderClause"));
        WindowFrameClause frame = convertFrameClause(c);
        return new WindowDef(name, refname, partition, order, frame);
    }

    // PostgreSQL FRAMEOPTION_ constants from windowfuncs.h
    private static final int FO_NONDEFAULT               = 0x00001;
    private static final int FO_RANGE                    = 0x00002;
    private static final int FO_ROWS                     = 0x00004;
    private static final int FO_GROUPS                   = 0x00008;
    private static final int FO_BETWEEN                  = 0x00010;
    private static final int FO_START_UNBOUNDED_PRECEDING = 0x00020;
    private static final int FO_END_UNBOUNDED_PRECEDING   = 0x00040;
    private static final int FO_START_UNBOUNDED_FOLLOWING = 0x00080;
    private static final int FO_END_UNBOUNDED_FOLLOWING   = 0x00100;
    private static final int FO_START_CURRENT_ROW        = 0x00200;
    private static final int FO_END_CURRENT_ROW          = 0x00400;
    private static final int FO_START_OFFSET_PRECEDING   = 0x00800;
    private static final int FO_END_OFFSET_PRECEDING     = 0x01000;
    private static final int FO_START_OFFSET_FOLLOWING   = 0x02000;
    private static final int FO_END_OFFSET_FOLLOWING     = 0x04000;

    private static WindowFrameClause convertFrameClause(JsonNode c) {
        int opts = c.path("frameOptions").asInt(0);
        if (opts == 0) return null;
        // Suppress pg_query's injected default frame — WindowAgg already handles this.
        if ((opts & FO_NONDEFAULT) == 0) return null;

        String mode;
        if ((opts & FO_ROWS) != 0) mode = "ROWS";
        else if ((opts & FO_GROUPS) != 0) mode = "GROUPS";
        else mode = "RANGE";

        FrameBoundType startType = extractStartType(opts);
        Expr startOffset = needsOffsetExpr(startType)
                ? convertExpr(c.path("startOffset")) : null;

        FrameBoundType endType;
        Expr endOffset;
        if ((opts & FO_BETWEEN) != 0) {
            endType = extractEndType(opts);
            endOffset = needsOffsetExpr(endType)
                    ? convertExpr(c.path("endOffset")) : null;
        } else {
            endType   = FrameBoundType.CURRENT_ROW;
            endOffset = null;
        }

        return new WindowFrameClause(mode, startType, startOffset, endType, endOffset);
    }

    private static FrameBoundType extractStartType(int opts) {
        if ((opts & FO_START_UNBOUNDED_PRECEDING) != 0) return FrameBoundType.UNBOUNDED_PRECEDING;
        if ((opts & FO_START_CURRENT_ROW) != 0)         return FrameBoundType.CURRENT_ROW;
        if ((opts & FO_START_OFFSET_PRECEDING) != 0)    return FrameBoundType.N_PRECEDING;
        if ((opts & FO_START_OFFSET_FOLLOWING) != 0)    return FrameBoundType.N_FOLLOWING;
        // FO_START_UNBOUNDED_FOLLOWING is technically invalid for start bound
        return FrameBoundType.UNBOUNDED_PRECEDING;
    }

    private static FrameBoundType extractEndType(int opts) {
        if ((opts & FO_END_UNBOUNDED_FOLLOWING) != 0)   return FrameBoundType.UNBOUNDED_FOLLOWING;
        if ((opts & FO_END_CURRENT_ROW) != 0)            return FrameBoundType.CURRENT_ROW;
        if ((opts & FO_END_OFFSET_PRECEDING) != 0)       return FrameBoundType.N_PRECEDING;
        if ((opts & FO_END_OFFSET_FOLLOWING) != 0)       return FrameBoundType.N_FOLLOWING;
        return FrameBoundType.CURRENT_ROW;
    }

    private static boolean needsOffsetExpr(FrameBoundType type) {
        return type == FrameBoundType.N_PRECEDING || type == FrameBoundType.N_FOLLOWING;
    }

    private static IndexElem convertIndexElem(JsonNode c) {
        String colname = c.path("name").asText(null);
        Expr expr = c.path("expr").isMissingNode() ? null : convertExpr(c.path("expr"));
        String opclass = c.path("opclass").asText(null);
        SortByDir ord = switch (c.path("ordering").asText("SORTBY_DEFAULT")) {
            case "SORTBY_ASC"  -> SortByDir.ASC;
            case "SORTBY_DESC" -> SortByDir.DESC;
            default            -> SortByDir.DEFAULT;
        };
        SortByNulls nullsOrd = switch (c.path("nulls_ordering").asText("SORTBY_NULLS_DEFAULT")) {
            case "SORTBY_NULLS_FIRST" -> SortByNulls.FIRST;
            case "SORTBY_NULLS_LAST"  -> SortByNulls.LAST;
            default                   -> SortByNulls.DEFAULT;
        };
        return new IndexElem(colname, expr, opclass, ord, nullsOrd);
    }

    private static ColumnDefNode convertColumnDef(JsonNode c) {
        String colname = c.path("colname").asText("");
        TypeName typeName = convertTypeName(c.path("typeName"));
        var constraints = new ArrayList<ColumnConstraintNode>();
        for (JsonNode con : c.path("constraints")) {
            JsonNode ct = con.path("Constraint");
            if (!ct.isMissingNode()) constraints.add(convertColumnConstraint(ct));
        }
        String collation = null;
        for (JsonNode cn : c.path("collClause").path("collname")) collation = getString(cn);
        return new ColumnDefNode(colname, typeName, constraints, collation);
    }

    private static ColumnConstraintNode convertColumnConstraint(JsonNode c) {
        String ct = c.path("contype").asText("");
        String name = c.path("conname").asText(null);
        return switch (ct) {
            case "CONSTR_PRIMARY" -> ColumnConstraintNode.primaryKey(name);
            case "CONSTR_UNIQUE"  -> ColumnConstraintNode.unique(name);
            case "CONSTR_NOTNULL", "CONSTR_NOT_NULL" -> ColumnConstraintNode.notNull(name);
            case "CONSTR_NULL"    -> new ColumnConstraintNode(ConstrType.NULL, name, null, List.of(), null, List.of(), null, null, false, false);
            case "CONSTR_DEFAULT" -> ColumnConstraintNode.defaultExpr(name, exprOpt(c.path("raw_expr")));
            case "CONSTR_CHECK"   -> ColumnConstraintNode.check(name, exprOpt(c.path("raw_expr")));
            case "CONSTR_GENERATED", "CONSTR_IDENTITY" -> new ColumnConstraintNode(
                    "CONSTR_IDENTITY".equals(ct) ? ConstrType.IDENTITY : ConstrType.GENERATED,
                    name, exprOpt(c.path("raw_expr")), List.of(), null, List.of(), null, null,
                    c.path("generated_when").asText("a").equals("a"), false);
            case "CONSTR_FOREIGN" -> {
                RangeVar pktable = convertRangeVar(c.path("pktable"));
                var fkAttrs = new ArrayList<String>();
                for (JsonNode a : c.path("fk_attrs")) fkAttrs.add(getString(a));
                var pkAttrs = new ArrayList<String>();
                for (JsonNode a : c.path("pk_attrs")) pkAttrs.add(getString(a));
                FkAction del = fkAction(c.path("fk_del_action").asText("a"));
                FkAction upd = fkAction(c.path("fk_upd_action").asText("a"));
                yield new ColumnConstraintNode(ConstrType.FK, name, null, fkAttrs, pktable, pkAttrs, del, upd, false, false);
            }
            default -> new ColumnConstraintNode(ConstrType.CHECK, name, null, List.of(), null, List.of(), null, null, false, false);
        };
    }

    private static TableConstraintNode convertTableConstraint(JsonNode c) {
        String ct = c.path("contype").asText("");
        String name = c.path("conname").asText(null);
        var keys = new ArrayList<String>();
        for (JsonNode k : c.path("keys")) keys.add(getString(k));
        ConstrType type = switch (ct) {
            case "CONSTR_PRIMARY" -> ConstrType.PRIMARY;
            case "CONSTR_UNIQUE"  -> ConstrType.UNIQUE;
            case "CONSTR_CHECK"   -> ConstrType.CHECK;
            case "CONSTR_FOREIGN" -> ConstrType.FK;
            default -> ConstrType.CHECK;
        };
        // For FK constraints, keys is empty and pk_attrs holds the referenced parent columns
        if ("CONSTR_FOREIGN".equals(ct)) {
            for (JsonNode a : c.path("pk_attrs")) keys.add(getString(a));
        }
        RangeVar pktable = c.path("pktable").isMissingNode() ? null : convertRangeVar(c.path("pktable"));
        var fkAttrs = new ArrayList<String>();
        for (JsonNode a : c.path("fk_attrs")) fkAttrs.add(getString(a));
        FkAction del = fkAction(c.path("fk_del_action").asText("a"));
        FkAction upd = fkAction(c.path("fk_upd_action").asText("a"));
        Expr rawExpr = exprOpt(c.path("raw_expr"));
        return new TableConstraintNode(type, name, keys, pktable, fkAttrs, del, upd, rawExpr);
    }

    private static OnConflictClause convertOnConflict(JsonNode c) {
        OnConflictAction action = "ONCONFLICT_UPDATE".equals(c.path("action").asText())
                ? OnConflictAction.UPDATE : OnConflictAction.NOTHING;
        InferClause infer = null;
        JsonNode inf = c.path("infer");
        if (!inf.isMissingNode()) {
            var indexElems = new ArrayList<IndexElem>();
            for (JsonNode ie : inf.path("indexElems")) indexElems.add(convertIndexElem(ie.path("IndexElem")));
            Expr where = exprOpt(inf.path("whereClause"));
            String constr = inf.path("conname").asText(null);
            infer = new InferClause(indexElems, where, constr);
        }
        List<AssignTarget> targets = convertAssignTargets(c.path("targetList"));
        Expr where = exprOpt(c.path("whereClause"));
        return new OnConflictClause(action, infer, targets, where);
    }

    private static LockingClause convertLockingClause(JsonNode c) {
        LockClauseStrength str = switch (c.path("strength").asText("LCS_FORUPDATE")) {
            case "LCS_FORKEYSHARE"  -> LockClauseStrength.KEY_SHARE;
            case "LCS_FORSHARE"     -> LockClauseStrength.SHARE;
            case "LCS_FORNOKEYUPDATE" -> LockClauseStrength.NO_KEY_UPDATE;
            default                 -> LockClauseStrength.UPDATE;
        };
        LockWaitPolicy wait = switch (c.path("waitPolicy").asText("LockWaitBlock")) {
            case "LockWaitSkip"  -> LockWaitPolicy.SKIP;
            case "LockWaitError" -> LockWaitPolicy.ERROR;
            default              -> LockWaitPolicy.BLOCK;
        };
        var rels = new ArrayList<RangeVar>();
        for (JsonNode r : c.path("lockedRels")) rels.add(convertRangeVar(r));
        return new LockingClause(str, wait, rels);
    }

    private static List<DefElem> convertDefElems(JsonNode arr) {
        if (arr == null || arr.isMissingNode()) return List.of();
        var out = new ArrayList<DefElem>();
        for (JsonNode de : arr) {
            JsonNode d = de.path("DefElem");
            if (d.isMissingNode()) continue;
            String dname = d.path("defname").asText("");
            JsonNode argNode = d.path("arg");
            Node val;
            if (argNode.isMissingNode()) {
                val = null;
            } else if (argNode.has("List")) {
                // pg_query wraps some DefElem args (e.g. AS body) in a List node.
                // Extract the first String item as a StringLiteral.
                JsonNode items = argNode.path("List").path("items");
                if (items.isArray() && !items.isEmpty()
                        && items.get(0).has("String")) {
                    val = new StringLiteral(items.get(0).path("String").path("sval").asText());
                } else {
                    val = tryConvertExpr(argNode);
                }
            } else {
                val = tryConvertExpr(argNode);
            }
            out.add(new DefElem(dname, val));
        }
        return out;
    }

    // ─── DO ─────────────────────────────────────────────────────────────────

    private static DoStmt convertDoStmt(JsonNode c) {
        String body = null;
        String language = "plpgsql"; // default
        for (JsonNode elem : c.path("args")) {
            JsonNode de = elem.path("DefElem");
            if (de.isMissingNode()) continue;
            String defname = de.path("defname").asText("");
            if ("as".equals(defname)) {
                JsonNode arg = de.path("arg");
                if (arg.has("String")) {
                    body = arg.path("String").path("sval").asText();
                } else if (arg.has("List")) {
                    JsonNode items = arg.path("List").path("items");
                    if (items.isArray() && !items.isEmpty() && items.get(0).has("String")) {
                        body = items.get(0).path("String").path("sval").asText();
                    }
                }
            } else if ("language".equals(defname)) {
                JsonNode arg = de.path("arg");
                if (arg.has("String")) {
                    language = arg.path("String").path("sval").asText();
                }
            }
        }
        return new DoStmt(body != null ? body : "", language);
    }

    // ─── CREATE TRIGGER ──────────────────────────────────────────────────────

    private static CreateTriggerStmt convertCreateTrigger(JsonNode c) {
        String trigname = c.path("trigname").asText();
        RangeVar relation = convertRangeVar(c.path("relation"));
        var funcname = new ArrayList<String>();
        for (JsonNode n : c.path("funcname")) funcname.add(getString(n));
        boolean row = c.path("row").booleanValue();
        int timing = c.path("timing").asInt(0);
        int events = c.path("events").asInt(0);
        List<String> columns = null;
        JsonNode colsNode = c.path("columns");
        if (colsNode.isArray() && !colsNode.isEmpty()) {
            columns = new ArrayList<>();
            for (JsonNode col : colsNode) columns.add(getString(col));
        }
        List<String> args = new ArrayList<>();
        JsonNode argsNode = c.path("args");
        if (argsNode.isArray()) {
            for (JsonNode a : argsNode) args.add(getString(a));
        }
        Expr whenClause = c.has("whenClause") ? convertExpr(c.path("whenClause")) : null;
        boolean replace = c.path("replace").booleanValue();
        boolean isConstraint = c.path("isconstraint").booleanValue();
        boolean deferrable = c.path("deferrable").booleanValue();
        boolean initDeferred = c.path("initdeferred").booleanValue();
        return new CreateTriggerStmt(trigname, relation, funcname, args, row, timing, events,
                columns, whenClause, replace, isConstraint, deferrable, initDeferred);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Convert a SelectStmt that is wrapped: {@code {"SelectStmt":{…}}}. */
    private static SelectStmt convertSelectNode(JsonNode n) {
        if (n == null || n.isMissingNode()) return null;
        JsonNode inner = n.path("SelectStmt");
        return convertSelect(inner.isMissingNode() ? n : inner);
    }

    private static List<Expr> exprList(JsonNode arr) {
        if (arr == null || arr.isMissingNode()) return List.of();
        var out = new ArrayList<Expr>();
        for (JsonNode e : arr) {
            Expr expr = tryConvertExpr(e);
            if (expr != null) out.add(expr);
        }
        return out;
    }

    private static Expr exprOpt(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isEmpty()) return null;
        return tryConvertExpr(n);
    }

    private static Expr tryConvertExpr(JsonNode n) {
        try { return convertExpr(n); } catch (Exception e) { return null; }
    }

    private static List<FromItem> convertFromList(JsonNode arr) {
        if (arr == null || arr.isMissingNode()) return List.of();
        var out = new ArrayList<FromItem>();
        for (JsonNode fi : arr) out.add(convertFromItem(fi));
        return out;
    }

    /** Extract sval from {@code {"String":{"sval":"..."}}} (or bare text node). */
    private static String getString(JsonNode n) {
        if (n == null || n.isMissingNode()) return null;
        if (n.isTextual()) return n.asText();
        JsonNode s = n.path("String");
        if (!s.isMissingNode()) return s.path("sval").asText();
        // fallback: A_Const sval
        JsonNode ac = n.path("A_Const");
        if (!ac.isMissingNode()) return ac.path("sval").path("sval").asText();
        return n.asText(null);
    }

    private static List<String> stringList(JsonNode arr) {
        if (arr == null || arr.isMissingNode()) return List.of();
        var out = new ArrayList<String>();
        for (JsonNode n : arr) { String s = getString(n); if (s != null) out.add(s); }
        return out;
    }

    /** First operator name from a {@code name} array of String wrappers. */
    private static String firstOpName(JsonNode nameArr) {
        if (nameArr == null || nameArr.isMissingNode() || !nameArr.isArray() || nameArr.isEmpty())
            return "";
        return getString(nameArr.get(0));
    }

    /** Get the first (and usually only) key of an object node. */
    private static String firstKey(JsonNode n) {
        if (n == null || n.isMissingNode() || !n.isObject()) return "";
        Iterator<String> it = n.fieldNames();
        return it.hasNext() ? it.next() : "";
    }

    /** Build a RangeVar from the items array of a List node (for DROP TABLE etc.). */
    private static RangeVar rangeVarFromList(JsonNode items) {
        if (items == null || items.isMissingNode()) return new RangeVar(null, "", null, true);
        if (items.size() == 1) return new RangeVar(null, getString(items.get(0)), null, true);
        if (items.size() >= 2) return new RangeVar(getString(items.get(0)), getString(items.get(items.size() - 1)), null, true);
        return new RangeVar(null, "", null, true);
    }

    /** Extract a single unqualified name from a list-of-String-wrappers. */
    private static String stringFromItems(JsonNode items) {
        if (items == null || items.isMissingNode() || !items.isArray() || items.isEmpty()) return "";
        var parts = new ArrayList<String>();
        for (JsonNode item : items) { String s = getString(item); if (s != null) parts.add(s); }
        return String.join(".", parts);
    }

    private static FkAction fkAction(String code) {
        return switch (code) {
            case "r" -> FkAction.RESTRICT;
            case "c" -> FkAction.CASCADE;
            case "n" -> FkAction.SET_NULL;
            case "d" -> FkAction.SET_DEFAULT;
            default  -> FkAction.NO_ACTION;
        };
    }

    // ─── CREATE TYPE AS ENUM / CREATE DOMAIN / CALL / PREPARE / EXECUTE / DEALLOCATE

    private static CreateEnumStmt convertCreateEnum(JsonNode c) {
        List<String> typeName = new ArrayList<>();
        for (JsonNode n : c.path("typeName")) typeName.add(getString(n));
        List<String> labels = new ArrayList<>();
        for (JsonNode n : c.path("vals")) labels.add(getString(n));
        return new CreateEnumStmt(typeName, labels);
    }

    private static AlterEnumStmt convertAlterEnum(JsonNode c) {
        List<String> typeName = new ArrayList<>();
        for (JsonNode n : c.path("typeName")) typeName.add(getString(n));
        String newVal = c.path("newVal").asText();
        String neighbor = c.has("newValNeighbor") ? c.path("newValNeighbor").asText() : null;
        boolean isAfter = c.path("newValIsAfter").booleanValue();
        boolean skipIfExists = c.path("skipIfNewValExists").booleanValue();
        return new AlterEnumStmt(typeName, newVal, neighbor, isAfter, skipIfExists);
    }

    private static CreateDomainStmt convertCreateDomain(JsonNode c) {
        List<String> domainName = new ArrayList<>();
        for (JsonNode n : c.path("domainname")) domainName.add(getString(n));
        TypeName baseType = convertTypeName(c.path("typeName"));
        List<CreateDomainStmt.DomainConstraint> checkConstraints = new ArrayList<>();
        Expr defaultExpr = null;
        boolean notNull = false;
        for (JsonNode con : c.path("constraints")) {
            JsonNode ct = con.path("Constraint");
            if (ct.isMissingNode()) continue;
            String contype = ct.path("contype").asText("");
            if ("CONSTR_CHECK".equals(contype)) {
                String conname = ct.path("conname").asText(null);
                checkConstraints.add(new CreateDomainStmt.DomainConstraint(conname, convertExpr(ct.path("raw_expr"))));
            } else if ("CONSTR_DEFAULT".equals(contype)) {
                defaultExpr = convertExpr(ct.path("raw_expr"));
            } else if ("CONSTR_NOTNULL".equals(contype)) {
                notNull = true;
            }
        }
        return new CreateDomainStmt(domainName, baseType, checkConstraints, defaultExpr, notNull);
    }

    private static CallStmt convertCall(JsonNode c) {
        JsonNode fc = c.path("funccall");
        List<String> funcname = new ArrayList<>();
        for (JsonNode n : fc.path("funcname")) funcname.add(getString(n));
        List<Expr> args = exprList(fc.path("args"));
        return new CallStmt(funcname, args);
    }

    private static PrepareStmt convertPrepare(JsonNode c) {
        String name = c.path("name").asText();
        Stmt query = convertStmtNode(c.path("query"));
        List<TypeName> argTypes = new ArrayList<>();
        JsonNode argtypes = c.path("argtypes");
        if (argtypes.isArray()) {
            for (JsonNode tn : argtypes) argTypes.add(convertTypeName(tn));
        }
        return new PrepareStmt(name, argTypes, query);
    }

    private static ExecuteStmt convertExecute(JsonNode c) {
        String name = c.path("name").asText();
        List<Expr> params = exprList(c.path("params"));
        return new ExecuteStmt(name, params);
    }

    private static DeallocateStmt convertDeallocate(JsonNode c) {
        String name = c.path("name").asText(null);
        // "DEALLOCATE ALL" has no name field
        return new DeallocateStmt(name);
    }

    private static ParseException toParse(PgQueryException e, String sql) {
        int cursor = e.getCursorPosition();
        int line = 1, col = 1;
        if (cursor > 0 && sql != null) {
            // Convert byte offset to 1-based line/column
            for (int i = 0; i < Math.min(cursor, sql.length()); i++) {
                if (sql.charAt(i) == '\n') { line++; col = 1; } else { col++; }
            }
        }
        return new ParseException(e.getMessage(), line, col, null);
    }
}
