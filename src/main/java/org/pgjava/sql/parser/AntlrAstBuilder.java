package org.pgjava.sql.parser;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.pgjava.sql.ast.*;
import org.pgjava.sql.parser.antlr.PostgreSQLParser;
import org.pgjava.sql.parser.antlr.PostgreSQLParser.*;
import org.pgjava.sql.parser.antlr.PostgreSQLParserBaseVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Visits the ANTLR4 parse tree produced by {@link org.pgjava.sql.parser.antlr.PostgreSQLParser}
 * and builds our typed {@code ast.*} node hierarchy.
 *
 * <p>Extends {@link PostgreSQLParserBaseVisitor}{@literal <}{@link Node}{@literal >} so
 * unhandled rules fall through to {@code visitChildren} which returns the last non-null child.
 * This handles single-child pass-through rules (e.g. {@code a_expr → a_expr_qual}) without
 * explicit overrides.
 */
final class AntlrAstBuilder extends PostgreSQLParserBaseVisitor<Node> {

    // -------------------------------------------------------------------------
    // Entry point

    List<Stmt> buildScript(RootContext root) {
        var stmts = new ArrayList<Stmt>();
        var multi = root.stmtblock().stmtmulti();
        for (var stmtCtx : multi.stmt()) {
            Node node = visit(stmtCtx);
            if (node instanceof Stmt s) stmts.add(s);
        }
        return stmts;
    }

    // -------------------------------------------------------------------------
    // Typed helpers

    private Expr expr(ParseTree ctx) {
        if (ctx == null) return null;
        Node n = visit(ctx);
        return (Expr) n;
    }

    private Stmt stmt(ParseTree ctx) {
        if (ctx == null) return null;
        Node n = visit(ctx);
        return (Stmt) n;
    }

    private FromItem fromItem(ParseTree ctx) {
        if (ctx == null) return null;
        Node n = visit(ctx);
        return (FromItem) n;
    }

    // -------------------------------------------------------------------------
    // Statements

    @Override
    public Node visitSelectstmt(SelectstmtContext ctx) {
        return visitChildren(ctx); // delegate to select_no_parens or select_with_parens
    }

    @Override
    public Node visitSelect_with_parens(Select_with_parensContext ctx) {
        // select_with_parens: '(' select_no_parens ')' | '(' select_with_parens ')'
        // visitChildren() returns null because the last child ')' terminal returns null.
        // Explicitly visit the non-terminal child to get the SelectStmt.
        if (ctx.select_no_parens() != null) return visit(ctx.select_no_parens());
        if (ctx.select_with_parens() != null) return visit(ctx.select_with_parens());
        return visitChildren(ctx);
    }

    @Override
    public Node visitSelect_no_parens(Select_no_parensContext ctx) {
        // Build: [with_clause] select_clause [sort_clause] [limit] [for_locking]
        WithClause withClause = ctx.with_clause() != null ? buildWithClause(ctx.with_clause()) : null;

        // Visit the select_clause which contains the core query
        SelectStmt core = (SelectStmt) visit(ctx.select_clause());

        // Apply ORDER BY
        List<SortKey> orderBy = List.of();
        if (ctx.sort_clause_() != null) {
            orderBy = buildSortClause(ctx.sort_clause_().sort_clause());
        }

        // Apply LIMIT / OFFSET
        Expr limitCount = null;
        Expr limitOffset = null;
        if (ctx.select_limit() != null) {
            var lim = ctx.select_limit();
            if (lim.limit_clause() != null) limitCount = buildLimitCount(lim.limit_clause());
            if (lim.offset_clause() != null) limitOffset = expr(lim.offset_clause().select_offset_value().a_expr());
        }

        // Apply FOR UPDATE / SHARE
        List<LockingClause> locking = List.of();
        if (ctx.for_locking_clause() != null) {
            locking = buildLockingClause(ctx.for_locking_clause());
        }

        // Patch the core SelectStmt with outer clauses
        return new SelectStmt(
                core.targetList(), core.fromClause(), core.whereClause(),
                core.groupBy(), core.having(),
                orderBy.isEmpty() ? core.orderBy() : orderBy,
                limitCount != null ? limitCount : core.limitCount(),
                limitOffset != null ? limitOffset : core.limitOffset(),
                core.windows(), core.distinct(), core.distinctOn(),
                core.setOp(), core.left(), core.right(), core.setAll(),
                locking.isEmpty() ? core.locking() : locking,
                withClause != null ? withClause : core.withClause(),
                core.valuesLists());
    }

    @Override
    public Node visitSelect_clause(Select_clauseContext ctx) {
        // select_clause: simple_select_intersect ((UNION | EXCEPT) all_or_distinct? simple_select_intersect)*
        var intersects = ctx.simple_select_intersect();
        SelectStmt result = (SelectStmt) visit(intersects.get(0));
        int i = 1;
        for (var tok : ctx.children) {
            if (tok instanceof TerminalNode tn) {
                int type = tn.getSymbol().getType();
                if (type == PostgreSQLParser.UNION || type == PostgreSQLParser.EXCEPT) {
                    SetOpType op = type == PostgreSQLParser.UNION ? SetOpType.UNION : SetOpType.EXCEPT;
                    boolean all = isAll(ctx, tok);
                    SelectStmt right = (SelectStmt) visit(intersects.get(i++));
                    result = setOp(result, right, op, all);
                }
            }
        }
        return result;
    }

    @Override
    public Node visitSimple_select_intersect(Simple_select_intersectContext ctx) {
        var primaries = ctx.simple_select_pramary();
        SelectStmt result = (SelectStmt) visit(primaries.get(0));
        int i = 1;
        for (var tok : ctx.children) {
            if (tok instanceof TerminalNode tn &&
                    tn.getSymbol().getType() == PostgreSQLParser.INTERSECT) {
                boolean all = isAll(ctx, tok);
                SelectStmt right = (SelectStmt) visit(primaries.get(i++));
                result = setOp(result, right, SetOpType.INTERSECT, all);
            }
        }
        return result;
    }

    @Override
    public Node visitSimple_select_pramary(Simple_select_pramaryContext ctx) {
        // VALUES clause
        if (ctx.values_clause() != null) {
            return buildValuesSelect(ctx.values_clause());
        }
        // TABLE relation_expr
        if (ctx.TABLE() != null) {
            var rv = buildRangeVar(ctx.relation_expr());
            return emptySelect(List.of(new TargetEntry(ColumnRef.of("*"), null)),
                    List.of(rv));
        }
        // Parenthesized: select_with_parens
        if (ctx.select_with_parens() != null) {
            return visit(ctx.select_with_parens());
        }
        // SELECT [DISTINCT] target_list [FROM ...] [WHERE ...] ...
        boolean distinct = ctx.distinct_clause() != null;
        List<Expr> distinctOn = List.of();
        if (ctx.distinct_clause() != null && ctx.distinct_clause().expr_list() != null) {
            distinctOn = buildExprList(ctx.distinct_clause().expr_list().a_expr());
        }

        List<TargetEntry> targets = List.of();
        if (ctx.target_list_() != null && ctx.target_list_().target_list() != null) {
            targets = buildTargetList(ctx.target_list_().target_list());
        } else if (ctx.target_list() != null) {
            targets = buildTargetList(ctx.target_list());
        }

        List<FromItem> from = List.of();
        if (ctx.from_clause() != null) from = buildFromClause(ctx.from_clause());

        Expr where = ctx.where_clause() != null ? expr(ctx.where_clause().a_expr()) : null;

        List<Expr> groupBy = List.of();
        if (ctx.group_clause() != null) groupBy = buildGroupBy(ctx.group_clause());

        Expr having = ctx.having_clause() != null ? expr(ctx.having_clause().a_expr()) : null;

        List<WindowDef> windows = List.of();
        if (ctx.window_clause() != null) windows = buildWindowClause(ctx.window_clause());

        return new SelectStmt(targets, from, where, groupBy, having,
                List.of(), null, null, windows, distinct, distinctOn,
                null, null, null, false, List.of(), null, List.of());
    }

    // -------------------------------------------------------------------------
    // INSERT

    @Override
    public Node visitInsertstmt(InsertstmtContext ctx) {
        WithClause with = ctx.with_clause_() != null ? buildWithClause(ctx.with_clause_().with_clause()) : null;
        RangeVar relation = buildRangeVar(ctx.insert_target().qualified_name());
        String alias = ctx.insert_target().colid() != null ? ctx.insert_target().colid().getText() : null;
        if (alias != null) relation = new RangeVar(relation.schemaName(), relation.relName(), alias, true);

        List<String> cols = List.of();
        SelectStmt source = null;
        boolean defaultValues = false;

        var rest = ctx.insert_rest();
        if (rest.DEFAULT() != null && rest.VALUES() != null) {
            defaultValues = true;
        } else {
            if (rest.insert_column_list() != null) {
                cols = rest.insert_column_list().insert_column_item().stream()
                        .map(ic -> ic.colid().getText())
                        .toList();
            }
            source = (SelectStmt) visit(rest.selectstmt());
        }

        OnConflictClause onConflict = ctx.on_conflict_() != null
                ? buildOnConflict(ctx.on_conflict_()) : null;
        List<TargetEntry> returning = ctx.returning_clause() != null
                ? buildTargetList(ctx.returning_clause().target_list()) : List.of();

        return new InsertStmt(relation, cols, source, defaultValues, onConflict, returning, with);
    }

    // -------------------------------------------------------------------------
    // UPDATE

    @Override
    public Node visitUpdatestmt(UpdatestmtContext ctx) {
        WithClause with = ctx.with_clause_() != null ? buildWithClause(ctx.with_clause_().with_clause()) : null;
        RangeVar relation = buildRelationExprOptAlias(ctx.relation_expr_opt_alias());

        List<AssignTarget> targets = new ArrayList<>();
        for (var sc : ctx.set_clause_list().set_clause()) {
            targets.add(buildSetClause(sc));
        }

        List<FromItem> from = ctx.from_clause() != null ? buildFromClause(ctx.from_clause()) : List.of();
        Expr where = ctx.where_or_current_clause() != null
                ? expr(ctx.where_or_current_clause().a_expr()) : null;
        List<TargetEntry> returning = ctx.returning_clause() != null
                ? buildTargetList(ctx.returning_clause().target_list()) : List.of();

        return new UpdateStmt(relation, targets, from, where, returning, with);
    }

    // -------------------------------------------------------------------------
    // DELETE

    @Override
    public Node visitDeletestmt(DeletestmtContext ctx) {
        WithClause with = ctx.with_clause_() != null ? buildWithClause(ctx.with_clause_().with_clause()) : null;
        RangeVar relation = buildRelationExprOptAlias(ctx.relation_expr_opt_alias());

        List<FromItem> using = ctx.using_clause() != null
                ? ctx.using_clause().from_list().table_ref().stream()
                    .map(tr -> (FromItem) visit(tr)).toList()
                : List.of();
        Expr where = ctx.where_or_current_clause() != null
                ? expr(ctx.where_or_current_clause().a_expr()) : null;
        List<TargetEntry> returning = ctx.returning_clause() != null
                ? buildTargetList(ctx.returning_clause().target_list()) : List.of();

        return new DeleteStmt(relation, using, where, returning, with);
    }

    // -------------------------------------------------------------------------
    // CREATE TABLE

    @Override
    public Node visitCreatestmt(CreatestmtContext ctx) {
        boolean temp = ctx.opttemp() != null;
        boolean ifNotExists = false;
        for (var child : ctx.children) {
            if (child instanceof TerminalNode tn && tn.getSymbol().getType() == PostgreSQLParser.EXISTS)
                ifNotExists = true;
        }
        RangeVar rel = buildRangeVar(ctx.qualified_name(0));
        List<ColumnDefNode> cols = new ArrayList<>();
        List<TableConstraintNode> constraints = new ArrayList<>();

        if (ctx.opttableelementlist() != null) {
            for (var elem : ctx.opttableelementlist().tableelementlist().tableelement()) {
                if (elem.columnDef() != null) cols.add(buildColumnDef(elem.columnDef()));
                if (elem.tableconstraint() != null) constraints.add(buildTableConstraint(elem.tableconstraint()));
            }
        }
        return new CreateTableStmt(rel, cols, constraints, ifNotExists, temp);
    }

    // -------------------------------------------------------------------------
    // DROP (multi-purpose)

    @Override
    public Node visitDropstmt(DropstmtContext ctx) {
        // Use grammar tokens to detect IF EXISTS
        boolean ifExists = ctx.IF_P() != null && ctx.EXISTS() != null;

        // Check for DROP TABLE
        if (ctx.object_type_any_name() != null) {
            String type = ctx.object_type_any_name().getText().toUpperCase();
            if (type.startsWith("TABLE")) {
                List<RangeVar> rels = ctx.any_name_list_().any_name().stream()
                        .map(n -> buildAnyNameToRangeVar(n))
                        .toList();
                DropBehavior beh = buildDropBehavior(ctx.drop_behavior_());
                return new DropTableStmt(rels, ifExists, beh);
            }
            if (type.startsWith("INDEX")) {
                List<String> names = ctx.any_name_list_().any_name().stream()
                        .map(n -> n.getText()).toList();
                return new DropIndexStmt(names, ifExists, buildDropBehavior(ctx.drop_behavior_()));
            }
            if (type.startsWith("VIEW")) {
                List<RangeVar> rels = ctx.any_name_list_().any_name().stream()
                        .map(n -> buildAnyNameToRangeVar(n)).toList();
                return new DropViewStmt(rels, ifExists, buildDropBehavior(ctx.drop_behavior_()));
            }
            if (type.startsWith("SEQUENCE")) {
                List<RangeVar> seqs = ctx.any_name_list_().any_name().stream()
                        .map(n -> buildAnyNameToRangeVar(n)).toList();
                return new DropSequenceStmt(seqs, ifExists, buildDropBehavior(ctx.drop_behavior_()));
            }
            if (type.startsWith("TYPE") || type.startsWith("DOMAIN")) {
                List<List<String>> typeNames = ctx.any_name_list_().any_name().stream()
                        .map(n -> n.colid() != null
                                ? (n.attrs() != null
                                        ? java.util.stream.Stream.concat(
                                                java.util.stream.Stream.of(n.colid().getText()),
                                                n.attrs().attr_name().stream().map(a -> a.getText()))
                                              .toList()
                                        : List.of(n.colid().getText()))
                                : List.of(n.getText()))
                        .toList();
                DropBehavior beh = buildDropBehavior(ctx.drop_behavior_());
                return new DropTypeStmt(typeNames, ifExists, beh);
            }
        }
        if (ctx.drop_type_name() != null) {
            String type = ctx.drop_type_name().getText().toUpperCase();
            if (type.equals("SCHEMA")) {
                List<String> names = ctx.name_list() != null
                        ? ctx.name_list().name().stream().map(n -> n.getText()).toList()
                        : List.of();
                return new DropSchemaStmt(names, ifExists, buildDropBehavior(ctx.drop_behavior_()));
            }
        }
        return new UnsupportedStmt("DROP", ctx.getText());
    }

    // -------------------------------------------------------------------------
    // ALTER TABLE

    @Override
    public Node visitAltertablestmt(AltertablestmtContext ctx) {
        // Simplified: extract relation, map commands to AlterTableCmd
        RangeVar rel = null;
        if (ctx.relation_expr() != null) rel = buildRangeVar(ctx.relation_expr());
        List<AlterTableCmd> cmds = new ArrayList<>();
        if (ctx.alter_table_cmds() != null) {
            for (var c : ctx.alter_table_cmds().alter_table_cmd()) {
                cmds.add(buildAlterTableCmd(c));
            }
        }
        return new AlterTableStmt(rel, cmds);
    }

    // -------------------------------------------------------------------------
    // CREATE SCHEMA

    @Override
    public Node visitCreateschemastmt(CreateschemastmtContext ctx) {
        boolean ifNotExists = ctx.IF_P() != null;
        // Grammar: (optschemaname? AUTHORIZATION rolespec | colid)
        // Simple CREATE SCHEMA myname uses colid; AUTHORIZATION form uses optschemaname
        String name = null;
        if (ctx.colid() != null) {
            name = ctx.colid().getText();
        } else if (ctx.optschemaname() != null && !ctx.optschemaname().isEmpty()) {
            name = ctx.optschemaname().getText();
        }
        return new CreateSchemaStmt(name, ifNotExists);
    }

    // -------------------------------------------------------------------------
    // CREATE VIEW

    @Override
    public Node visitViewstmt(ViewstmtContext ctx) {
        boolean replace = ctx.REPLACE() != null;
        boolean temp = ctx.opttemp() != null;
        RangeVar view = buildRangeVar(ctx.qualified_name());
        List<String> aliases = ctx.column_list_() != null
                ? ctx.column_list_().columnlist().columnElem().stream().map(e -> e.colid().getText()).toList()
                : List.of();
        SelectStmt query = (SelectStmt) visit(ctx.selectstmt());
        return new CreateViewStmt(view, aliases, query, replace, temp);
    }

    // -------------------------------------------------------------------------
    // CREATE INDEX

    @Override
    public Node visitIndexstmt(IndexstmtContext ctx) {
        boolean unique = ctx.unique_() != null;
        boolean ifNotExists = ctx.if_not_exists_() != null;
        String name = ctx.index_name_() != null ? ctx.index_name_().getText() : null;
        RangeVar rel = buildRangeVar(ctx.relation_expr());
        String accessMethod = ctx.access_method_clause() != null
                ? ctx.access_method_clause().name().getText() : null;
        List<IndexElem> params = ctx.index_params().index_elem().stream()
                .map(this::buildIndexElem).toList();
        Expr where = ctx.where_clause() != null ? expr(ctx.where_clause().a_expr()) : null;
        return new CreateIndexStmt(name, rel, params, where, unique, ifNotExists, accessMethod);
    }

    // -------------------------------------------------------------------------
    // CREATE TABLE AS

    @Override
    public Node visitCreateasstmt(CreateasstmtContext ctx) {
        boolean temp = ctx.opttemp() != null;
        boolean ifNotExists = ctx.IF_P() != null;
        RangeVar rel = buildRangeVar(ctx.create_as_target().qualified_name());
        SelectStmt query = (SelectStmt) visit(ctx.selectstmt());
        return new CreateTableAsStmt(rel, query, ifNotExists, temp);
    }

    // -------------------------------------------------------------------------
    // CREATE SEQUENCE / ALTER SEQUENCE

    @Override
    public Node visitCreateseqstmt(CreateseqstmtContext ctx) {
        boolean ifNotExists = ctx.IF_P() != null;
        RangeVar seq = buildRangeVar(ctx.qualified_name());
        List<DefElem> opts = ctx.optseqoptlist() != null
                ? buildSeqOptList(ctx.optseqoptlist().seqoptlist()) : List.of();
        return new CreateSequenceStmt(seq, opts, ifNotExists);
    }

    @Override
    public Node visitAlterseqstmt(AlterseqstmtContext ctx) {
        RangeVar seq = buildRangeVar(ctx.qualified_name());
        List<DefElem> opts = ctx.seqoptlist() != null
                ? buildSeqOptList(ctx.seqoptlist()) : List.of();
        return new AlterSequenceStmt(seq, opts);
    }

    private List<DefElem> buildSeqOptList(
            org.pgjava.sql.parser.antlr.PostgreSQLParser.SeqoptlistContext ctx) {
        if (ctx == null) return List.of();
        List<DefElem> opts = new ArrayList<>();
        for (var elem : ctx.seqoptelem()) {
            if (elem.START() != null && elem.numericonly() != null)
                opts.add(new DefElem("start", buildNumericOnly(elem.numericonly())));
            else if (elem.INCREMENT() != null && elem.numericonly() != null)
                opts.add(new DefElem("increment", buildNumericOnly(elem.numericonly())));
            else if (elem.MAXVALUE() != null && elem.NO() == null)
                opts.add(new DefElem("maxvalue", buildNumericOnly(elem.numericonly())));
            else if (elem.MINVALUE() != null && elem.NO() == null)
                opts.add(new DefElem("minvalue", buildNumericOnly(elem.numericonly())));
            else if (elem.CACHE() != null)
                opts.add(new DefElem("cache", buildNumericOnly(elem.numericonly())));
            else if (elem.CYCLE() != null && elem.NO() == null)
                opts.add(new DefElem("cycle", new BooleanLiteral(true)));
            else if (elem.NO() != null && elem.CYCLE() != null)
                opts.add(new DefElem("cycle", new BooleanLiteral(false)));
            else if (elem.RESTART() != null)
                opts.add(new DefElem("restart",
                        elem.numericonly() != null ? buildNumericOnly(elem.numericonly()) : null));
        }
        return opts;
    }

    private Node buildNumericOnly(
            org.pgjava.sql.parser.antlr.PostgreSQLParser.NumericonlyContext ctx) {
        String text = ctx.getText().replace("+", "");
        try {
            return new IntegerLiteral(Long.parseLong(text));
        } catch (NumberFormatException e) {
            return new FloatLiteral(text);
        }
    }

    // -------------------------------------------------------------------------
    // CREATE FUNCTION

    @Override
    public Node visitCreatefunctionstmt(CreatefunctionstmtContext ctx) {
        boolean replace = ctx.or_replace_() != null;
        boolean proc = ctx.PROCEDURE() != null;
        List<String> name = buildFuncName(ctx.func_name());

        // -- Parameters --
        List<FunctionParameter> params = new ArrayList<>();
        var argsWithDefaults = ctx.func_args_with_defaults();
        if (argsWithDefaults != null && argsWithDefaults.func_args_with_defaults_list() != null) {
            for (var fad : argsWithDefaults.func_args_with_defaults_list().func_arg_with_default()) {
                var fa = fad.func_arg();
                // Determine parameter mode
                FunctionParameterMode mode = FunctionParameterMode.IN;
                if (fa.arg_class() != null) {
                    var ac = fa.arg_class();
                    if (ac.INOUT() != null) mode = FunctionParameterMode.INOUT;
                    else if (ac.OUT_P() != null && ac.IN_P() == null) mode = FunctionParameterMode.OUT;
                    else if (ac.IN_P() != null && ac.OUT_P() != null) mode = FunctionParameterMode.INOUT;
                    else if (ac.VARIADIC() != null) mode = FunctionParameterMode.VARIADIC;
                }
                // Parameter name (may be null)
                String paramName = fa.param_name() != null
                        ? fa.param_name().getText().toLowerCase() : null;
                // Parameter type
                TypeName argType = buildTypeName(fa.func_type().typename());
                // Default expression
                Expr defexpr = null;
                if (fad.a_expr() != null) {
                    defexpr = (Expr) visit(fad.a_expr());
                }
                params.add(new FunctionParameter(paramName, argType, mode, defexpr));
            }
        }

        // -- Return type --
        TypeName returnType = null;
        if (ctx.RETURNS() != null) {
            if (ctx.func_return() != null) {
                returnType = buildTypeName(ctx.func_return().func_type().typename());
            } else if (ctx.TABLE() != null && ctx.table_func_column_list() != null) {
                // RETURNS TABLE(...) — build a RECORD-like type;
                // For now, represent as a simple "record" type.
                // The individual columns are available via table_func_column_list
                // but the executor resolves them from the function body.
                returnType = new TypeName(List.of("record"), List.of(), 0, false);
            }
        }

        // -- Options (language, body, volatility, strictness, security, etc.) --
        List<DefElem> options = new ArrayList<>();
        if (ctx.createfunc_opt_list() != null) {
            for (var item : ctx.createfunc_opt_list().createfunc_opt_item()) {
                // AS func_as  (function body)
                if (item.func_as() != null) {
                    var funcAs = item.func_as();
                    // func_as : def=sconst | sconst COMMA sconst
                    String body = extractSconst(funcAs.sconst(0));
                    options.add(new DefElem("as", new StringLiteral(body)));
                    // If there's a second sconst (obj_file, link_symbol), add it too
                    if (funcAs.sconst().size() > 1) {
                        options.add(new DefElem("as_secondary", new StringLiteral(
                                extractSconst(funcAs.sconst(1)))));
                    }
                }
                // LANGUAGE
                else if (item.LANGUAGE() != null && item.nonreservedword_or_sconst() != null) {
                    var langCtx = item.nonreservedword_or_sconst();
                    String lang;
                    if (langCtx.sconst() != null) {
                        lang = extractSconst(langCtx.sconst());
                    } else {
                        lang = langCtx.getText().toLowerCase();
                    }
                    options.add(new DefElem("language", new StringLiteral(lang)));
                }
                // WINDOW
                else if (item.WINDOW() != null) {
                    options.add(new DefElem("window", null));
                }
                // TRANSFORM — skip for now
                // common_func_opt_item
                else if (item.common_func_opt_item() != null) {
                    var cfo = item.common_func_opt_item();
                    if (cfo.STRICT_P() != null) {
                        options.add(new DefElem("strict", null));
                    } else if (cfo.CALLED() != null) {
                        // CALLED ON NULL INPUT  (opposite of STRICT)
                        options.add(new DefElem("strict", new StringLiteral("false")));
                    } else if (cfo.RETURNS() != null) {
                        // RETURNS NULL ON NULL INPUT (same as STRICT)
                        options.add(new DefElem("strict", null));
                    } else if (cfo.IMMUTABLE() != null) {
                        options.add(new DefElem("volatility", new StringLiteral("immutable")));
                    } else if (cfo.STABLE() != null) {
                        options.add(new DefElem("volatility", new StringLiteral("stable")));
                    } else if (cfo.VOLATILE() != null) {
                        options.add(new DefElem("volatility", new StringLiteral("volatile")));
                    } else if (cfo.SECURITY() != null || cfo.EXTERNAL() != null) {
                        // SECURITY DEFINER / SECURITY INVOKER / EXTERNAL SECURITY ...
                        if (cfo.DEFINER() != null) {
                            options.add(new DefElem("security", new StringLiteral("definer")));
                        } else {
                            options.add(new DefElem("security", new StringLiteral("invoker")));
                        }
                    } else if (cfo.LEAKPROOF() != null) {
                        boolean notLeakproof = cfo.NOT() != null;
                        options.add(new DefElem("leakproof",
                                new StringLiteral(notLeakproof ? "false" : "true")));
                    } else if (cfo.COST() != null) {
                        options.add(new DefElem("cost",
                                new StringLiteral(cfo.numericonly().getText())));
                    } else if (cfo.ROWS() != null) {
                        options.add(new DefElem("rows",
                                new StringLiteral(cfo.numericonly().getText())));
                    } else if (cfo.PARALLEL() != null) {
                        options.add(new DefElem("parallel",
                                new StringLiteral(cfo.colid().getText().toLowerCase())));
                    }
                }
            }
        }

        return new CreateFunctionStmt(name, params, returnType, options, replace, proc);
    }

    // -------------------------------------------------------------------------
    // DROP FUNCTION / PROCEDURE / ROUTINE

    @Override
    public Node visitRemovefuncstmt(RemovefuncstmtContext ctx) {
        boolean ifExists = ctx.IF_P() != null;
        var targets = new ArrayList<DropFunctionStmt.Target>();
        for (var fwa : ctx.function_with_argtypes_list().function_with_argtypes()) {
            List<String> name;
            List<TypeName> argTypes = null;
            if (fwa.func_name() != null) {
                name = buildFuncName(fwa.func_name());
                // func_args present → extract arg types
                if (fwa.func_args() != null && fwa.func_args().func_args_list() != null) {
                    argTypes = new ArrayList<>();
                    for (var fa : fwa.func_args().func_args_list().func_arg()) {
                        argTypes.add(buildTypeName(fa.func_type().typename()));
                    }
                } else if (fwa.func_args() != null) {
                    // Empty parens: DROP FUNCTION foo() — zero-arg signature
                    argTypes = List.of();
                }
                // else: no parens → argTypes stays null (unspecified)
            } else if (fwa.colid() != null) {
                var parts = new ArrayList<String>();
                parts.add(fwa.colid().getText().toLowerCase());
                if (fwa.indirection() != null) {
                    for (var el : fwa.indirection().indirection_el()) {
                        if (el.attr_name() != null) parts.add(el.attr_name().getText().toLowerCase());
                    }
                }
                name = parts;
            } else {
                name = List.of(fwa.getText().toLowerCase());
            }
            targets.add(new DropFunctionStmt.Target(name, argTypes));
        }
        return new DropFunctionStmt(targets, ifExists);
    }

    // -------------------------------------------------------------------------
    // TRUNCATE

    @Override
    public Node visitTruncatestmt(TruncatestmtContext ctx) {
        List<RangeVar> rels = ctx.relation_expr_list().relation_expr().stream()
                .map(this::buildRangeVar).toList();
        boolean restart = ctx.restart_seqs_() != null;
        DropBehavior beh = buildDropBehavior(ctx.drop_behavior_());
        return new TruncateStmt(rels, restart, beh);
    }

    // -------------------------------------------------------------------------
    // Transactions

    @Override
    public Node visitTransactionstmt(TransactionstmtContext ctx) {
        // BEGIN / START TRANSACTION
        if (ctx.BEGIN_P() != null || ctx.START() != null) return new BeginStmt();
        // COMMIT / END
        if (ctx.COMMIT() != null || ctx.END_P() != null) return new CommitStmt();
        // ROLLBACK / ABORT
        if ((ctx.ROLLBACK() != null || ctx.ABORT_P() != null) && ctx.TO() == null)
            return new RollbackStmt();
        // SAVEPOINT
        if (ctx.SAVEPOINT() != null && ctx.ROLLBACK() == null && ctx.RELEASE() == null)
            return new SavepointStmt(ctx.colid().getText());
        // RELEASE SAVEPOINT / RELEASE name
        if (ctx.RELEASE() != null)
            return new ReleaseSavepointStmt(ctx.colid().getText());
        // ROLLBACK TO SAVEPOINT / ROLLBACK TO
        if (ctx.ROLLBACK() != null && ctx.TO() != null)
            return new RollbackToSavepointStmt(ctx.colid().getText());
        return new UnsupportedStmt("TRANSACTION", ctx.getText());
    }

    // -------------------------------------------------------------------------
    // SET / SHOW

    @Override
    public Node visitVariablesetstmt(VariablesetstmtContext ctx) {
        SetScope scope = SetScope.DEFAULT;
        if (ctx.LOCAL() != null) scope = SetScope.LOCAL;
        if (ctx.SESSION() != null) scope = SetScope.SESSION;

        var rest = ctx.set_rest();
        if (rest.set_rest_more() != null) {
            var more = rest.set_rest_more();
            if (more.generic_set() != null) {
                var gs = more.generic_set();
                String name = gs.var_name().getText();
                List<Node> args = new ArrayList<>();
                if (gs.var_list() != null) {
                    for (var v : gs.var_list().var_value()) {
                        args.add(new StringLiteral(v.getText()));
                    }
                }
                return new SetStmt(name, args, scope);
            }
            if (more.TIME() != null) return new SetStmt("TimeZone",
                    List.of(new StringLiteral(more.zone_value().getText())), scope);
            if (more.NAMES() != null) return new SetStmt("client_encoding", List.of(), scope);
        }
        return new SetStmt("", List.of(), scope);
    }

    @Override
    public Node visitVariableshowstmt(VariableshowstmtContext ctx) {
        if (ctx.ALL() != null) return new ShowStmt("ALL");
        if (ctx.TIME() != null) return new ShowStmt("TimeZone");
        return new ShowStmt(ctx.var_name() != null ? ctx.var_name().getText() : "");
    }

    // -------------------------------------------------------------------------
    // NOTIFY / LISTEN / UNLISTEN

    @Override
    public Node visitNotifystmt(NotifystmtContext ctx) {
        String channel = ctx.colid().getText();
        String payload = ctx.notify_payload() != null
                ? extractSconst(ctx.notify_payload().sconst()) : null;
        return new NotifyStmt(channel, payload);
    }

    @Override
    public Node visitListenstmt(ListenstmtContext ctx) {
        return new ListenStmt(ctx.colid().getText());
    }

    @Override
    public Node visitUnlistenstmt(UnlistenstmtContext ctx) {
        if (ctx.STAR() != null) return new UnlistenStmt("*");
        return new UnlistenStmt(ctx.colid().getText());
    }

    // -------------------------------------------------------------------------
    // VACUUM / ANALYZE

    @Override
    public Node visitVacuumstmt(VacuumstmtContext ctx) {
        boolean analyze = ctx.analyze_() != null;
        return new VacuumStmt(List.of(), analyze);
    }

    @Override
    public Node visitAnalyzestmt(AnalyzestmtContext ctx) {
        return new VacuumStmt(List.of(), true);
    }

    // -------------------------------------------------------------------------
    // GRANT / REVOKE (no-op)

    @Override
    public Node visitGrantstmt(GrantstmtContext ctx) {
        return new GrantStmt(true, ctx.getText());
    }

    @Override
    public Node visitRevokestmt(RevokestmtContext ctx) {
        return new GrantStmt(false, ctx.getText());
    }

    // -------------------------------------------------------------------------
    // FROM clause / table_ref

    private List<FromItem> buildFromClause(From_clauseContext ctx) {
        if (ctx == null || ctx.from_list() == null) return List.of();
        return ctx.from_list().table_ref().stream()
                .map(tr -> (FromItem) visit(tr))
                .toList();
    }

    @Override
    public Node visitTable_ref(Table_refContext ctx) {
        // Build the base FROM item (relation_expr, subselect, or func_table)
        FromItem base = buildBaseTableRef(ctx);

        // Grammar: (CROSS JOIN table_ref | NATURAL join_type? JOIN table_ref | join_type? JOIN table_ref join_qual)*
        // Scan children sequentially. join_type is a sub-rule (Join_typeContext), not raw tokens.
        List<ParseTree> children = ctx.children;
        int i = 0;
        // Skip base part (everything up to first JOIN-related child)
        while (i < children.size()) {
            ParseTree child = children.get(i);
            boolean isJoinStart = (child instanceof Join_typeContext)
                    || (child instanceof TerminalNode tn2 &&
                       (tn2.getSymbol().getType() == PostgreSQLParser.JOIN
                        || tn2.getSymbol().getType() == PostgreSQLParser.CROSS
                        || tn2.getSymbol().getType() == PostgreSQLParser.NATURAL));
            if (isJoinStart) break;
            i++;
        }

        // Build the JOIN chain
        while (i < children.size()) {
            ParseTree child = children.get(i);

            if (child instanceof Join_typeContext jtCtx) {
                // join_type JOIN table_ref join_qual
                JoinType jt = JoinType.INNER;
                if (jtCtx.LEFT() != null) jt = JoinType.LEFT;
                else if (jtCtx.RIGHT() != null) jt = JoinType.RIGHT;
                else if (jtCtx.FULL() != null) jt = JoinType.FULL;
                // skip to JOIN
                i++;
                while (i < children.size() && !(children.get(i) instanceof TerminalNode tn3
                        && tn3.getSymbol().getType() == PostgreSQLParser.JOIN)) i++;
                i++; // skip JOIN
                while (i < children.size() && !(children.get(i) instanceof Table_refContext)) i++;
                if (i < children.size()) {
                    FromItem rhs = (FromItem) visit(children.get(i));
                    Expr quals = null;
                    List<String> using = List.of();
                    if (i + 1 < children.size() && children.get(i + 1) instanceof Join_qualContext jq) {
                        if (jq.ON() != null) quals = expr(jq.a_expr());
                        else if (jq.name_list() != null)
                            using = jq.name_list().name().stream().map(ParseTree::getText).toList();
                        i++; // consume join_qual
                    }
                    base = new JoinExpr(jt, base, rhs, quals, using, false);
                }
            } else if (child instanceof TerminalNode tn) {
                int type = tn.getSymbol().getType();
                if (type == PostgreSQLParser.JOIN) {
                    // Bare JOIN = INNER JOIN
                    i++;
                    while (i < children.size() && !(children.get(i) instanceof Table_refContext)) i++;
                    if (i < children.size()) {
                        FromItem rhs = (FromItem) visit(children.get(i));
                        Expr quals = null;
                        List<String> using = List.of();
                        if (i + 1 < children.size() && children.get(i + 1) instanceof Join_qualContext jq) {
                            if (jq.ON() != null) quals = expr(jq.a_expr());
                            else if (jq.name_list() != null)
                                using = jq.name_list().name().stream().map(ParseTree::getText).toList();
                            i++;
                        }
                        base = new JoinExpr(JoinType.INNER, base, rhs, quals, using, false);
                    }
                } else if (type == PostgreSQLParser.CROSS) {
                    // CROSS JOIN rhs
                    i++; // skip JOIN
                    while (i < children.size() && !(children.get(i) instanceof Table_refContext)) i++;
                    if (i < children.size()) {
                        FromItem rhs = (FromItem) visit(children.get(i));
                        base = new JoinExpr(JoinType.CROSS, base, rhs, null, List.of(), false);
                    }
                } else if (type == PostgreSQLParser.NATURAL) {
                    // NATURAL [join_type] JOIN rhs
                    i++;
                    JoinType njType = JoinType.INNER;
                    if (i < children.size() && children.get(i) instanceof Join_typeContext njt) {
                        // NATURAL LEFT/RIGHT/FULL JOIN
                        if (njt.LEFT() != null) njType = JoinType.LEFT;
                        else if (njt.RIGHT() != null) njType = JoinType.RIGHT;
                        else if (njt.FULL() != null) njType = JoinType.FULL;
                        i++;
                    }
                    // skip JOIN
                    while (i < children.size() && !(children.get(i) instanceof Table_refContext)) i++;
                    if (i < children.size()) {
                        FromItem rhs = (FromItem) visit(children.get(i));
                        base = new JoinExpr(njType, base, rhs, null, List.of(), true);
                    }
                }
            }
            i++;
        }
        return base;
    }

    private FromItem buildBaseTableRef(Table_refContext ctx) {
        // relation_expr [alias_clause]
        if (ctx.relation_expr() != null) {
            RangeVar rv = buildRangeVar(ctx.relation_expr());
            String alias = ctx.alias_clause() != null ? ctx.alias_clause().colid().getText() : null;
            if (alias != null) rv = new RangeVar(rv.schemaName(), rv.relName(), alias, rv.inh());
            return rv;
        }
        // select_with_parens alias_clause
        if (ctx.select_with_parens() != null) {
            SelectStmt sub = (SelectStmt) visit(ctx.select_with_parens());
            String alias = ctx.alias_clause() != null ? ctx.alias_clause().colid().getText() : null;
            List<String> colAliases = ctx.alias_clause() != null && ctx.alias_clause().name_list() != null
                    ? ctx.alias_clause().name_list().name().stream().map(ParseTree::getText).toList()
                    : List.of();
            boolean lateral = ctx.LATERAL_P() != null;
            return new RangeSubselect(sub, alias, colAliases, lateral);
        }
        // func_table func_alias_clause?
        if (ctx.func_table() != null) {
            var ft = ctx.func_table();
            boolean lateral = ctx.LATERAL_P() != null;
            boolean withOrdinality = ft.ordinality_() != null;

            // Build the function call from func_expr_windowless
            FunctionCall funcCall;
            var few = ft.func_expr_windowless();
            if (few != null) {
                if (few.func_application() != null) {
                    funcCall = buildFuncApplication(few.func_application(), null, null, null);
                } else {
                    // func_expr_common_subexpr (e.g. CURRENT_TIMESTAMP, COALESCE, etc.)
                    Node n = visit(few.func_expr_common_subexpr());
                    if (n instanceof FunctionCall fc) {
                        funcCall = fc;
                    } else {
                        // Wrap non-FunctionCall expressions (e.g. CastExpr) as a synthetic call
                        funcCall = new FunctionCall(List.of("__func_expr__"), List.of(), false,
                                List.of(), null, false, false, null);
                    }
                }
            } else {
                // ROWS FROM (...) form — not yet fully supported, use placeholder
                funcCall = new FunctionCall(List.of("__rows_from__"), List.of(), false,
                        List.of(), null, false, false, null);
            }

            // Extract alias and column aliases from func_alias_clause
            String alias = null;
            List<String> colAliases = List.of();
            var fac = ctx.func_alias_clause();
            if (fac != null) {
                if (fac.alias_clause() != null) {
                    alias = fac.alias_clause().colid().getText();
                    if (fac.alias_clause().name_list() != null) {
                        colAliases = fac.alias_clause().name_list().name().stream()
                                .map(ParseTree::getText).toList();
                    }
                } else if (fac.colid() != null) {
                    alias = fac.colid().getText();
                }
            }

            return new RangeFunction(funcCall, alias, colAliases, lateral, withOrdinality);
        }
        // Parenthesized table_ref (for grouped joins) — first table_ref in children
        var inner = ctx.table_ref();
        if (!inner.isEmpty()) {
            return (FromItem) visit(inner.get(0));
        }
        return RangeVar.of("__unknown__");
    }

    // -------------------------------------------------------------------------
    // Expressions — binary operator chains

    @Override
    public Node visitA_expr_or(A_expr_orContext ctx) {
        var ands = ctx.a_expr_and();
        if (ands.size() == 1) return visit(ands.get(0));
        Expr result = (Expr) visit(ands.get(0));
        for (int i = 1; i < ands.size(); i++) {
            result = new BinaryOp("OR", result, (Expr) visit(ands.get(i)));
        }
        return result;
    }

    @Override
    public Node visitA_expr_and(A_expr_andContext ctx) {
        var betweens = ctx.a_expr_between();
        if (betweens.size() == 1) return visit(betweens.get(0));
        Expr result = (Expr) visit(betweens.get(0));
        for (int i = 1; i < betweens.size(); i++) {
            result = new BinaryOp("AND", result, (Expr) visit(betweens.get(i)));
        }
        return result;
    }

    @Override
    public Node visitA_expr_between(A_expr_betweenContext ctx) {
        var ins = ctx.a_expr_in();
        if (ins.size() == 1) return visit(ins.get(0));
        boolean negated = ctx.NOT() != null;
        boolean symmetric = ctx.SYMMETRIC() != null;
        Expr arg = (Expr) visit(ins.get(0));
        Expr low = (Expr) visit(ins.get(1));
        Expr high = (Expr) visit(ins.get(2));
        return new BetweenExpr(arg, low, high, negated, symmetric);
    }

    @Override
    public Node visitA_expr_in(A_expr_inContext ctx) {
        Expr arg = (Expr) visit(ctx.a_expr_unary_not());
        if (ctx.IN_P() == null) return arg;
        boolean negated = ctx.NOT() != null;
        var inExpr = ctx.in_expr();
        if (inExpr instanceof In_expr_selectContext sel) {
            SelectStmt sub = (SelectStmt) visit(sel.select_with_parens());
            return new InSubselect(arg, sub, negated);
        }
        // expr_list
        List<Expr> list = inExpr instanceof In_expr_listContext lst && lst.expr_list() != null
                ? buildExprList(lst.expr_list().a_expr())
                : List.of();
        return new InExpr(arg, list, negated);
    }

    @Override
    public Node visitA_expr_unary_not(A_expr_unary_notContext ctx) {
        Expr inner = (Expr) visit(ctx.a_expr_isnull());
        if (ctx.NOT() == null) return inner;
        return new UnaryOp("NOT", inner);
    }

    @Override
    public Node visitA_expr_isnull(A_expr_isnullContext ctx) {
        Expr inner = (Expr) visit(ctx.a_expr_is_not());
        if (ctx.ISNULL() != null) return new UnaryOp("IS NULL", inner);
        if (ctx.NOTNULL() != null) return new UnaryOp("IS NOT NULL", inner);
        return inner;
    }

    @Override
    public Node visitA_expr_is_not(A_expr_is_notContext ctx) {
        Expr inner = (Expr) visit(ctx.a_expr_compare());
        if (ctx.IS() == null) return inner;
        boolean not = ctx.NOT() != null;
        if (ctx.NULL_P() != null) return new UnaryOp(not ? "IS NOT NULL" : "IS NULL", inner);
        if (ctx.TRUE_P() != null) return new UnaryOp(not ? "IS NOT TRUE" : "IS TRUE", inner);
        if (ctx.FALSE_P() != null) return new UnaryOp(not ? "IS NOT FALSE" : "IS FALSE", inner);
        if (ctx.UNKNOWN() != null) return new UnaryOp(not ? "IS NOT UNKNOWN" : "IS UNKNOWN", inner);
        if (ctx.DISTINCT() != null) {
            Expr right = (Expr) visit(ctx.a_expr());
            return new BinaryOp(not ? "IS NOT DISTINCT FROM" : "IS DISTINCT FROM", inner, right);
        }
        return inner;
    }

    @Override
    public Node visitA_expr_compare(A_expr_compareContext ctx) {
        var likes = ctx.a_expr_like();

        // Handle: a_expr subquery_Op sub_type (subquery)  → SubLink ANY/ALL
        if (ctx.sub_type() != null) {
            Expr left = (Expr) visit(likes.get(0));
            String op = ctx.subquery_Op().getText(); // e.g. "=", "<"
            boolean isAll = ctx.sub_type().ALL() != null;
            SubLinkType type = isAll ? SubLinkType.ALL : SubLinkType.ANY;
            SelectStmt subselect;
            if (ctx.select_with_parens() != null) {
                subselect = (SelectStmt) visit(ctx.select_with_parens());
            } else {
                // (a_expr) — not a subquery, unsupported in this path
                throw new IllegalStateException(
                        "ANY/ALL with scalar expression not supported here");
            }
            return new SubLink(type, left, op, subselect);
        }

        if (likes.size() == 1) return visit(likes.get(0));
        Expr left = (Expr) visit(likes.get(0));
        Expr right = (Expr) visit(likes.get(1));
        String op = "=";
        for (var child : ctx.children) {
            if (child instanceof TerminalNode tn) {
                op = switch (tn.getSymbol().getType()) {
                    case PostgreSQLParser.EQUAL         -> "=";
                    case PostgreSQLParser.NOT_EQUALS    -> "<>";
                    case PostgreSQLParser.LT             -> "<";
                    case PostgreSQLParser.GT             -> ">";
                    case PostgreSQLParser.LESS_EQUALS   -> "<=";
                    case PostgreSQLParser.GREATER_EQUALS -> ">=";
                    default -> tn.getText();
                };
                if (tn.getSymbol().getType() == PostgreSQLParser.EQUAL ||
                    tn.getSymbol().getType() == PostgreSQLParser.NOT_EQUALS ||
                    tn.getSymbol().getType() == PostgreSQLParser.LT ||
                    tn.getSymbol().getType() == PostgreSQLParser.GT ||
                    tn.getSymbol().getType() == PostgreSQLParser.LESS_EQUALS ||
                    tn.getSymbol().getType() == PostgreSQLParser.GREATER_EQUALS) break;
            }
        }
        return new BinaryOp(op, left, right);
    }

    @Override
    public Node visitA_expr_like(A_expr_likeContext ctx) {
        var ops = ctx.a_expr_qual_op();
        if (ops.size() == 1) return visit(ops.get(0));
        Expr arg = (Expr) visit(ops.get(0));
        Expr pattern = (Expr) visit(ops.get(1));
        boolean negated = ctx.NOT() != null;
        Expr escape = ctx.escape_() != null ? expr(ctx.escape_().a_expr()) : null;
        LikeType type = ctx.LIKE() != null ? LikeType.LIKE
                : ctx.ILIKE() != null ? LikeType.ILIKE : LikeType.SIMILAR_TO;
        return new LikeExpr(arg, pattern, escape, type, negated);
    }

    @Override
    public Node visitA_expr_qual_op(A_expr_qual_opContext ctx) {
        var unquals = ctx.a_expr_unary_qualop();
        if (unquals.size() == 1) return visit(unquals.get(0));
        Expr result = (Expr) visit(unquals.get(0));
        for (int i = 0; i < ctx.qual_op().size(); i++) {
            String op = ctx.qual_op(i).getText();
            result = new BinaryOp(op, result, (Expr) visit(unquals.get(i + 1)));
        }
        return result;
    }

    @Override
    public Node visitA_expr_unary_qualop(A_expr_unary_qualopContext ctx) {
        if (ctx.qual_op() != null) {
            return new UnaryOp(ctx.qual_op().getText(), (Expr) visit(ctx.a_expr_add()));
        }
        return visit(ctx.a_expr_add());
    }

    @Override
    public Node visitA_expr_add(A_expr_addContext ctx) {
        var muls = ctx.a_expr_mul();
        if (muls.size() == 1) return visit(muls.get(0));
        Expr result = (Expr) visit(muls.get(0));
        int opIdx = 0;
        for (var child : ctx.children) {
            if (child instanceof TerminalNode tn) {
                int type = tn.getSymbol().getType();
                if (type == PostgreSQLParser.PLUS || type == PostgreSQLParser.MINUS) {
                    result = new BinaryOp(tn.getText(), result, (Expr) visit(muls.get(++opIdx)));
                }
            }
        }
        return result;
    }

    @Override
    public Node visitA_expr_mul(A_expr_mulContext ctx) {
        var carets = ctx.a_expr_caret();
        if (carets.size() == 1) return visit(carets.get(0));
        Expr result = (Expr) visit(carets.get(0));
        int opIdx = 0;
        for (var child : ctx.children) {
            if (child instanceof TerminalNode tn) {
                int type = tn.getSymbol().getType();
                if (type == PostgreSQLParser.STAR || type == PostgreSQLParser.SLASH
                        || type == PostgreSQLParser.PERCENT) {
                    result = new BinaryOp(tn.getText(), result, (Expr) visit(carets.get(++opIdx)));
                }
            }
        }
        return result;
    }

    @Override
    public Node visitA_expr_caret(A_expr_caretContext ctx) {
        var signs = ctx.a_expr_unary_sign();
        if (signs.size() == 1) return visit(signs.get(0));
        return new BinaryOp("^", (Expr) visit(signs.get(0)), (Expr) visit(signs.get(1)));
    }

    @Override
    public Node visitA_expr_unary_sign(A_expr_unary_signContext ctx) {
        Expr inner = (Expr) visit(ctx.a_expr_at_time_zone());
        if (ctx.MINUS() != null) return new UnaryOp("-", inner);
        if (ctx.PLUS() != null) return inner; // unary plus is identity
        return inner;
    }

    @Override
    public Node visitA_expr_at_time_zone(A_expr_at_time_zoneContext ctx) {
        Expr inner = (Expr) visit(ctx.a_expr_collate());
        if (ctx.AT() != null) {
            Expr tz = expr(ctx.a_expr());
            return new BinaryOp("AT TIME ZONE", inner, tz);
        }
        return inner;
    }

    @Override
    public Node visitA_expr_collate(A_expr_collateContext ctx) {
        Expr inner = (Expr) visit(ctx.a_expr_typecast());
        if (ctx.COLLATE() != null) {
            List<String> coll = ctx.any_name().colid() != null
                    ? List.of(ctx.any_name().getText()) : List.of(ctx.any_name().getText());
            return new CollateExpr(inner, coll);
        }
        return inner;
    }

    @Override
    public Node visitA_expr_typecast(A_expr_typecastContext ctx) {
        // c_expr (TYPECAST typename)*
        Expr inner = (Expr) visit(ctx.c_expr());
        for (var tn : ctx.typename()) {
            TypeName type = buildTypeName(tn);
            inner = new CastExpr(inner, type);
        }
        return inner;
    }

    // -------------------------------------------------------------------------
    // Primary expressions (c_expr)

    @Override
    public Node visitC_expr_exists(C_expr_existsContext ctx) {
        SelectStmt sub = (SelectStmt) visit(ctx.select_with_parens());
        return new SubLink(SubLinkType.EXISTS, null, null, sub);
    }

    @Override
    public Node visitC_expr_case(C_expr_caseContext ctx) {
        return visit(ctx.case_expr());
    }

    @Override
    public Node visitC_expr_expr(C_expr_exprContext ctx) {
        // columnref
        if (ctx.columnref() != null) return visit(ctx.columnref());
        // aexprconst
        if (ctx.aexprconst() != null) return visit(ctx.aexprconst());
        // PARAM opt_indirection
        if (ctx.PARAM() != null) {
            String text = ctx.PARAM().getText(); // $1, $2, etc.
            int num = Integer.parseInt(text.substring(1));
            return applyOptIndirection(new ParamRef(num), ctx.opt_indirection());
        }
        // GROUPING (...)
        if (ctx.GROUPING() != null) {
            List<Expr> args = buildExprList(ctx.expr_list().a_expr());
            return new GroupingExpr(args);
        }
        // ARRAY select_with_parens
        if (ctx.ARRAY() != null && ctx.select_with_parens() != null) {
            SelectStmt sub = (SelectStmt) visit(ctx.select_with_parens());
            return new ArraySubselect(sub);
        }
        // ARRAY array_expr
        if (ctx.ARRAY() != null && ctx.array_expr() != null) {
            return visit(ctx.array_expr());
        }
        // parenthesized a_expr
        if (ctx.a_expr_in_parens != null) {
            Expr inner = expr(ctx.a_expr_in_parens);
            return applyOptIndirection(inner, ctx.opt_indirection());
        }
        // func_expr
        if (ctx.func_expr() != null) return visit(ctx.func_expr());
        // select_with_parens (scalar subquery)
        if (ctx.select_with_parens() != null) {
            SelectStmt sub = (SelectStmt) visit(ctx.select_with_parens());
            return new SubLink(SubLinkType.EXPR, null, null, sub);
        }
        // explicit_row
        if (ctx.explicit_row() != null) {
            List<Expr> args = buildExprList(ctx.explicit_row().expr_list() != null
                    ? ctx.explicit_row().expr_list().a_expr() : List.of());
            return new RowExpr(args);
        }
        // implicit_row — treated as RowExpr
        if (ctx.implicit_row() != null) {
            // implicit_row: (a_expr, a_expr [, ...]) — the first element is stored differently
            List<Expr> args = new ArrayList<>();
            args.add(expr(ctx.implicit_row().a_expr()));
            if (ctx.implicit_row().expr_list() != null)
                args.addAll(buildExprList(ctx.implicit_row().expr_list().a_expr()));
            return new RowExpr(args);
        }
        // DEFAULT
        if (ctx.DEFAULT() != null) return new SetToDefault();
        return new StringLiteral(ctx.getText()); // fallback
    }

    // -------------------------------------------------------------------------
    // Literals

    @Override
    public Node visitAexprconst(AexprconstContext ctx) {
        if (ctx.iconst() != null) return buildIconst(ctx.iconst());
        if (ctx.fconst() != null) return new FloatLiteral(ctx.fconst().getText());
        if (ctx.sconst() != null) return new StringLiteral(extractSconst(ctx.sconst()));
        if (ctx.TRUE_P() != null) return new BooleanLiteral(true);
        if (ctx.FALSE_P() != null) return new BooleanLiteral(false);
        if (ctx.NULL_P() != null) return new NullLiteral();
        // consttypename sconst
        if (ctx.consttypename() != null) {
            String raw = extractSconst(ctx.sconst());
            // constinterval
            if (ctx.constinterval() != null) {
                String fields = ctx.interval_() != null ? ctx.interval_().getText() : null;
                return new IntervalLiteral(raw, fields);
            }
            TypeName tn = buildConstTypeName(ctx.consttypename());
            return new TypedLiteral(tn, raw);
        }
        // func_name (sconst | ...) — e.g. DATE '2024-01-01'
        if (ctx.func_name() != null && ctx.sconst() != null) {
            String typeName = ctx.func_name().getText();
            String raw = extractSconst(ctx.sconst());
            return new TypedLiteral(TypeName.of(typeName), raw);
        }
        return new StringLiteral(ctx.getText());
    }

    // -------------------------------------------------------------------------
    // Column reference

    @Override
    public Node visitColumnref(ColumnrefContext ctx) {
        List<String> fields = new ArrayList<>();
        fields.add(ctx.colid().getText());
        if (ctx.indirection() != null) {
            for (var el : ctx.indirection().indirection_el()) {
                if (el.STAR() != null) fields.add("*");
                else if (el.attr_name() != null) fields.add(el.attr_name().getText());
                else if (el.a_expr() != null) {
                    // subscript — won't be a ColumnRef; rebuild as SubscriptExpr later
                    // For now just append the bracket text
                }
            }
        }
        return new ColumnRef(fields);
    }

    // -------------------------------------------------------------------------
    // Function calls

    @Override
    public Node visitFunc_expr(Func_exprContext ctx) {
        if (ctx.func_application() != null) {
            return buildFuncApplication(ctx.func_application(),
                    ctx.within_group_clause(), ctx.filter_clause(), ctx.over_clause());
        }
        if (ctx.func_expr_common_subexpr() != null) {
            return visit(ctx.func_expr_common_subexpr());
        }
        return new FunctionCall(List.of("__unknown__"), List.of(), false, List.of(), null, false, false, null);
    }

    @Override
    public Node visitFunc_expr_common_subexpr(Func_expr_common_subexprContext ctx) {
        // CAST(x AS type) — highest priority
        if (ctx.CAST() != null) {
            Expr arg = expr(ctx.a_expr(0));
            TypeName tn = buildTypeName(ctx.typename());
            return new CastExpr(arg, tn);
        }
        // COALESCE / NULLIF / GREATEST / LEAST
        if (ctx.COALESCE() != null)
            return FunctionCall.simple("coalesce", buildExprList(ctx.expr_list().a_expr()));
        if (ctx.NULLIF() != null) {
            var args = ctx.a_expr();
            return FunctionCall.simple("nullif", List.of(expr(args.get(0)), expr(args.get(1))));
        }
        if (ctx.GREATEST() != null)
            return new MinMaxExpr(MinMaxOp.GREATEST, buildExprList(ctx.expr_list().a_expr()));
        if (ctx.LEAST() != null)
            return new MinMaxExpr(MinMaxOp.LEAST, buildExprList(ctx.expr_list().a_expr()));
        // CURRENT_DATE, CURRENT_TIMESTAMP, etc.
        if (ctx.CURRENT_DATE() != null) return FunctionCall.simple("current_date", List.of());
        if (ctx.CURRENT_TIMESTAMP() != null) return FunctionCall.simple("current_timestamp", List.of());
        if (ctx.LOCALTIMESTAMP() != null) return FunctionCall.simple("localtimestamp", List.of());
        if (ctx.CURRENT_TIME() != null) return FunctionCall.simple("current_time", List.of());
        if (ctx.LOCALTIME() != null) return FunctionCall.simple("localtime", List.of());
        if (ctx.CURRENT_USER() != null) return FunctionCall.simple("current_user", List.of());
        if (ctx.SESSION_USER() != null) return FunctionCall.simple("session_user", List.of());
        if (ctx.CURRENT_SCHEMA() != null) return FunctionCall.simple("current_schema", List.of());
        if (ctx.CURRENT_CATALOG() != null) return FunctionCall.simple("current_catalog", List.of());
        // EXTRACT
        if (ctx.EXTRACT() != null) {
            List<Expr> args = new ArrayList<>();
            if (ctx.extract_list() != null) {
                args.add(new StringLiteral(ctx.extract_list().extract_arg().getText()));
                args.add(expr(ctx.extract_list().a_expr()));
            }
            return FunctionCall.simple("extract", args);
        }
        // SUBSTRING, OVERLAY, TRIM — map to function calls
        if (ctx.SUBSTRING() != null)
            return FunctionCall.simple("substring", buildSubstrArgs(ctx));
        if (ctx.TRIM() != null) {
            // Determine function: LEADING→ltrim, TRAILING→rtrim, BOTH/default→btrim
            String fn = "btrim";
            if (ctx.LEADING() != null) fn = "ltrim";
            else if (ctx.TRAILING() != null) fn = "rtrim";
            List<Expr> args = new ArrayList<>();
            var tl = ctx.trim_list();
            if (tl != null) {
                if (tl.FROM() != null && tl.a_expr() != null) {
                    // TRIM(chars FROM expr_list) → fn(expr, chars)
                    for (var e : tl.expr_list().a_expr()) args.add(expr(e));
                    args.add(expr(tl.a_expr()));
                } else if (tl.FROM() != null) {
                    // TRIM(FROM expr_list) → fn(expr)
                    for (var e : tl.expr_list().a_expr()) args.add(expr(e));
                } else if (tl.expr_list() != null) {
                    // TRIM(expr_list) → fn(expr)
                    for (var e : tl.expr_list().a_expr()) args.add(expr(e));
                }
            }
            return FunctionCall.simple(fn, args);
        }
        // Fallback
        return FunctionCall.simple(ctx.getStart().getText().toLowerCase(), List.of());
    }

    // -------------------------------------------------------------------------
    // CASE expression

    @Override
    public Node visitCase_expr(Case_exprContext ctx) {
        Expr arg = ctx.case_arg() != null && ctx.case_arg().a_expr() != null
                ? expr(ctx.case_arg().a_expr()) : null;
        List<CaseWhen> whens = new ArrayList<>();
        for (var w : ctx.when_clause_list().when_clause()) {
            Expr cond = expr(w.a_expr(0));
            Expr result = expr(w.a_expr(1));
            whens.add(new CaseWhen(cond, result));
        }
        Expr defResult = ctx.case_default() != null ? expr(ctx.case_default().a_expr()) : null;
        return new CaseExpr(arg, whens, defResult);
    }

    // -------------------------------------------------------------------------
    // ARRAY expression

    @Override
    public Node visitArray_expr(Array_exprContext ctx) {
        if (ctx.expr_list() != null) {
            return new ArrayExpr(buildExprList(ctx.expr_list().a_expr()));
        }
        return new ArrayExpr(List.of());
    }

    // -------------------------------------------------------------------------
    // Type name

    @Override
    public Node visitTypename(TypenameContext ctx) {
        return buildTypeName(ctx);
    }

    // -------------------------------------------------------------------------
    // Target list

    private List<TargetEntry> buildTargetList(Target_listContext ctx) {
        if (ctx == null) return List.of();
        List<TargetEntry> result = new ArrayList<>();
        for (var el : ctx.target_el()) {
            result.add(buildTargetEl(el));
        }
        return result;
    }

    private TargetEntry buildTargetEl(Target_elContext el) {
        if (el instanceof Target_labelContext tl) {
            Expr val = expr(tl.a_expr());
            String name = null;
            if (tl.colLabel() != null) name = tl.colLabel().getText();
            else if (tl.bareColLabel() != null) name = tl.bareColLabel().getText();
            return new TargetEntry(val, name);
        }
        if (el instanceof Target_starContext) {
            return new TargetEntry(ColumnRef.of("*"), null);
        }
        return new TargetEntry(new StringLiteral(el.getText()), null);
    }

    // -------------------------------------------------------------------------
    // Helpers

    private WithClause buildWithClause(With_clauseContext ctx) {
        boolean recursive = ctx.RECURSIVE() != null;
        List<CommonTableExpr> ctes = new ArrayList<>();
        for (var cte : ctx.cte_list().common_table_expr()) {
            String name = cte.name().getText();
            List<String> aliases = cte.name_list_() != null
                    ? cte.name_list_().name_list().name().stream().map(ParseTree::getText).toList()
                    : List.of();
            SelectStmt query = (SelectStmt) visit(cte.preparablestmt());
            CTEMaterialize mat = cte.materialized_() != null
                    ? (cte.materialized_().NOT() != null
                            ? CTEMaterialize.NOT_MATERIALIZED : CTEMaterialize.MATERIALIZED)
                    : CTEMaterialize.DEFAULT;
            ctes.add(new CommonTableExpr(name, aliases, query, mat));
        }
        return new WithClause(ctes, recursive);
    }

    private RangeVar buildRangeVar(Qualified_nameContext ctx) {
        String schema = null;
        String rel = ctx.colid().getText();
        if (ctx.indirection() != null) {
            for (var el : ctx.indirection().indirection_el()) {
                if (el.attr_name() != null) {
                    schema = rel;
                    rel = el.attr_name().getText();
                }
            }
        }
        return new RangeVar(schema, rel, null, true);
    }

    private RangeVar buildRangeVar(Relation_exprContext ctx) {
        return buildRangeVar(ctx.qualified_name());
    }

    private RangeVar buildRelationExprOptAlias(Relation_expr_opt_aliasContext ctx) {
        RangeVar rv = buildRangeVar(ctx.relation_expr());
        if (ctx.colid() != null) {
            rv = new RangeVar(rv.schemaName(), rv.relName(), ctx.colid().getText(), rv.inh());
        }
        return rv;
    }

    private RangeVar buildAnyNameToRangeVar(Any_nameContext ctx) {
        String rel = ctx.colid().getText();
        String schema = null;
        if (ctx.attrs() != null && !ctx.attrs().attr_name().isEmpty()) {
            schema = rel;
            rel = ctx.attrs().attr_name().get(ctx.attrs().attr_name().size() - 1).getText();
        }
        return new RangeVar(schema, rel, null, true);
    }

    private List<SortKey> buildSortClause(Sort_clauseContext ctx) {
        List<SortKey> keys = new ArrayList<>();
        for (var sb : ctx.sortby_list().sortby()) {
            Expr e = expr(sb.a_expr());
            SortByDir dir = SortByDir.DEFAULT;
            SortByNulls nulls = SortByNulls.DEFAULT;
            if (sb.asc_desc_() != null) {
                dir = sb.asc_desc_().DESC() != null ? SortByDir.DESC : SortByDir.ASC;
            }
            if (sb.nulls_order_() != null) {
                nulls = sb.nulls_order_().FIRST_P() != null ? SortByNulls.FIRST : SortByNulls.LAST;
            }
            keys.add(new SortKey(e, dir, nulls));
        }
        return keys;
    }

    private Expr buildLimitCount(Limit_clauseContext ctx) {
        if (ctx.select_limit_value() != null) {
            if (ctx.select_limit_value().ALL() != null) return new IntegerLiteral(0);
            return expr(ctx.select_limit_value().a_expr());
        }
        return null;
    }

    private List<LockingClause> buildLockingClause(For_locking_clauseContext ctx) {
        List<LockingClause> result = new ArrayList<>();
        if (ctx.for_locking_items() != null) {
            for (var item : ctx.for_locking_items().for_locking_item()) {
                LockClauseStrength strength = LockClauseStrength.UPDATE;
                if (item.for_locking_strength() != null) {
                    String text = item.for_locking_strength().getText().toUpperCase();
                    if (text.contains("SHARE")) strength = LockClauseStrength.SHARE;
                    else if (text.contains("NOKEYUPDATE")) strength = LockClauseStrength.NO_KEY_UPDATE;
                }
                result.add(new LockingClause(strength, LockWaitPolicy.BLOCK, List.of()));
            }
        }
        return result;
    }

    private TypeName buildTypeName(TypenameContext ctx) {
        boolean setOf = ctx.SETOF() != null;
        TypeName base = buildSimpleTypeName(ctx.simpletypename());
        int arrayDims = 0;
        if (ctx.ARRAY() != null) arrayDims = 1;
        for (var ob : ctx.opt_array_bounds().OPEN_BRACKET()) arrayDims++;
        return new TypeName(base.names(), base.typmods(), arrayDims, setOf);
    }

    private TypeName buildSimpleTypeName(SimpletypenameContext ctx) {
        if (ctx.generictype() != null) {
            String name = ctx.generictype().type_function_name().getText();
            // Resolve aliases
            List<String> names = List.of(name.toLowerCase());
            List<Node> typmods = List.of();
            if (ctx.generictype().type_modifiers_() != null) {
                typmods = buildExprList(ctx.generictype().type_modifiers_()
                        .expr_list().a_expr())
                        .stream().map(e -> (Node) e).toList();
            }
            return new TypeName(names, typmods, 0, false);
        }
        if (ctx.numeric() != null) {
            var nc = ctx.numeric();
            String typName = mapNumericTypeName(nc);
            List<Node> typmods = List.of();
            if (nc.type_modifiers_() != null) {
                typmods = buildExprList(nc.type_modifiers_().expr_list().a_expr())
                        .stream().map(e -> (Node) e).toList();
            }
            return new TypeName(List.of(typName), typmods, 0, false);
        }
        if (ctx.character() != null) {
            var cc = ctx.character().character_c();
            // VARCHAR or CHARACTER VARYING → varchar; CHAR/CHARACTER → bpchar
            boolean isVarying = cc.VARCHAR() != null || cc.varying_() != null;
            String typName = isVarying ? "varchar" : "bpchar";
            List<Node> typmods = List.of();
            if (ctx.character().iconst() != null) {
                typmods = List.of(buildIconst(ctx.character().iconst()));
            }
            return new TypeName(List.of(typName), typmods, 0, false);
        }
        if (ctx.constdatetime() != null) {
            String name = ctx.constdatetime().TIMESTAMP() != null ? "timestamp"
                    : ctx.constdatetime().TIME() != null ? "time" : "timestamp";
            var tz = ctx.constdatetime().timezone_();
            if (tz != null && tz.WITH() != null) name += "tz";
            return TypeName.of(name);
        }
        if (ctx.constinterval() != null) return TypeName.of("interval");
        if (ctx.bit() != null) return TypeName.of("bit");
        return TypeName.of("text");
    }

    private TypeName buildConstTypeName(ConsttypenameContext ctx) {
        if (ctx.numeric() != null) return TypeName.of(mapNumericTypeName(ctx.numeric()));
        if (ctx.constcharacter() != null) {
            var cc = ctx.constcharacter().character_c();
            boolean isVarying = cc.VARCHAR() != null || cc.varying_() != null;
            String typName = isVarying ? "varchar" : "bpchar";
            List<Node> typmods = List.of();
            if (ctx.constcharacter().iconst() != null) {
                typmods = List.of(buildIconst(ctx.constcharacter().iconst()));
            }
            return new TypeName(List.of(typName), typmods, 0, false);
        }
        if (ctx.constdatetime() != null) {
            String name = ctx.constdatetime().TIMESTAMP() != null ? "timestamp"
                    : ctx.constdatetime().TIME() != null ? "time" : "timestamp";
            var tz = ctx.constdatetime().timezone_();
            if (tz != null && tz.WITH() != null) name += "tz";
            return TypeName.of(name);
        }
        return TypeName.of("text");
    }

    private String mapNumericTypeName(NumericContext ctx) {
        if (ctx.INT_P() != null || ctx.INTEGER() != null) return "int4";
        if (ctx.BIGINT() != null) return "int8";
        if (ctx.SMALLINT() != null) return "int2";
        if (ctx.REAL() != null) return "float4";
        if (ctx.DOUBLE_P() != null) return "float8";
        if (ctx.FLOAT_P() != null) return "float8";
        if (ctx.NUMERIC() != null || ctx.DECIMAL_P() != null || ctx.DEC() != null) return "numeric";
        if (ctx.BOOLEAN_P() != null) return "bool";
        return "numeric";
    }

    private ColumnDefNode buildColumnDef(ColumnDefContext ctx) {
        String colname = ctx.colid().getText();
        TypeName typeName = buildTypeName(ctx.typename());
        List<ColumnConstraintNode> constraints = new ArrayList<>();
        String collation = null;
        for (var cc : ctx.colquallist().colconstraint()) {
            if (cc.colconstraintelem() != null) {
                var c = buildColConstraint(cc.colconstraintelem(), cc.name() != null ? cc.name().getText() : null);
                if (c != null) constraints.add(c);
            }
            if (cc.COLLATE() != null && cc.any_name() != null) collation = cc.any_name().getText();
        }
        return new ColumnDefNode(colname, typeName, constraints, collation);
    }

    private ColumnConstraintNode buildColConstraint(ColconstraintelemContext ctx, String name) {
        if (ctx.NOT() != null && ctx.NULL_P() != null) return ColumnConstraintNode.notNull(name);
        if (ctx.NULL_P() != null && ctx.NOT() == null)
            return new ColumnConstraintNode(ConstrType.NULL, name, null, List.of(), null, List.of(), null, null, false, false);
        if (ctx.PRIMARY() != null) return ColumnConstraintNode.primaryKey(name);
        if (ctx.UNIQUE() != null) return ColumnConstraintNode.unique(name);
        if (ctx.DEFAULT() != null) return ColumnConstraintNode.defaultExpr(name, expr(ctx.b_expr() != null ? ctx.b_expr() : null));
        if (ctx.CHECK() != null) return ColumnConstraintNode.check(name, expr(ctx.a_expr()));
        if (ctx.REFERENCES() != null) {
            RangeVar pk = buildRangeVar(ctx.qualified_name());
            List<String> pkAttrs = ctx.column_list_() != null
                    ? ctx.column_list_().columnlist().columnElem().stream().map(e -> e.colid().getText()).toList()
                    : List.of();
            return new ColumnConstraintNode(ConstrType.FK, name, null, List.of(), pk, pkAttrs,
                    FkAction.NO_ACTION, FkAction.NO_ACTION, false, false);
        }
        return null;
    }

    private TableConstraintNode buildTableConstraint(TableconstraintContext ctx) {
        String name = ctx.name() != null ? ctx.name().getText() : null;
        var elem = ctx.constraintelem();
        if (elem.PRIMARY() != null) {
            List<String> keys = elem.columnlist().columnElem().stream().map(e -> e.colid().getText()).toList();
            return new TableConstraintNode(ConstrType.PRIMARY, name, keys, null, List.of(),
                    null, null, null);
        }
        if (elem.UNIQUE() != null) {
            List<String> keys = elem.columnlist().columnElem().stream().map(e -> e.colid().getText()).toList();
            return new TableConstraintNode(ConstrType.UNIQUE, name, keys, null, List.of(),
                    null, null, null);
        }
        if (elem.CHECK() != null) {
            return new TableConstraintNode(ConstrType.CHECK, name, List.of(), null, List.of(),
                    null, null, expr(elem.a_expr()));
        }
        if (elem.FOREIGN() != null) {
            List<String> keys = elem.columnlist().columnElem().stream().map(e -> e.colid().getText()).toList();
            RangeVar pk = buildRangeVar(elem.qualified_name());
            List<String> fkAttrs = elem.column_list_() != null
                    ? elem.column_list_().columnlist().columnElem().stream().map(e -> e.colid().getText()).toList()
                    : List.of();
            return new TableConstraintNode(ConstrType.FK, name, keys, pk, fkAttrs,
                    FkAction.NO_ACTION, FkAction.NO_ACTION, null);
        }
        return new TableConstraintNode(ConstrType.CHECK, name, List.of(), null, List.of(), null, null, null);
    }

    private AlterTableCmd buildAlterTableCmd(Alter_table_cmdContext ctx) {
        AlterTableType type = AlterTableType.OTHER;
        String colName = null;
        ColumnDefNode def = null;
        DropBehavior beh = ctx.drop_behavior_() != null
                && ctx.drop_behavior_().CASCADE() != null
                ? DropBehavior.CASCADE : DropBehavior.RESTRICT;

        if (ctx.ADD_P() != null && ctx.tableconstraint() != null) {
            // ADD CONSTRAINT — build a fake ColumnDefNode carrying the constraint
            type = AlterTableType.ADD_CONSTRAINT;
            var tc = ctx.tableconstraint();
            colName = tc.name() != null ? tc.name().getText() : null;
            def = buildConstraintAsColumnDef(tc);
        } else if (ctx.ADD_P() != null && ctx.columnDef() != null) {
            type = AlterTableType.ADD_COLUMN;
            def = buildColumnDef(ctx.columnDef());
        } else if (ctx.ALTER() != null && ctx.TYPE_P() != null) {
            // ALTER COLUMN x TYPE typename
            type = AlterTableType.ALTER_COLUMN_TYPE;
            colName = !ctx.colid().isEmpty() ? ctx.colid(0).getText() : null;
            if (ctx.typename() != null) {
                TypeName tn = buildTypeName(ctx.typename());
                def = new ColumnDefNode(colName, tn, List.of(), null);
            }
        } else if (ctx.ALTER() != null && ctx.SET() != null && ctx.NOT() != null) {
            // ALTER COLUMN x SET NOT NULL
            type = AlterTableType.SET_NOT_NULL;
            colName = !ctx.colid().isEmpty() ? ctx.colid(0).getText() : null;
        } else if (ctx.ALTER() != null && ctx.DROP() != null && ctx.NOT() != null) {
            // ALTER COLUMN x DROP NOT NULL
            type = AlterTableType.DROP_NOT_NULL;
            colName = !ctx.colid().isEmpty() ? ctx.colid(0).getText() : null;
        } else if (ctx.ALTER() != null && ctx.alter_column_default() != null) {
            var acd = ctx.alter_column_default();
            colName = !ctx.colid().isEmpty() ? ctx.colid(0).getText() : null;
            if (acd.SET() != null) {
                type = AlterTableType.SET_DEFAULT;
                Expr defaultExpr = acd.a_expr() != null ? expr(acd.a_expr()) : null;
                def = new ColumnDefNode(colName,
                        TypeName.of("text"), // placeholder type
                        defaultExpr != null
                                ? List.of(ColumnConstraintNode.defaultExpr(null, defaultExpr))
                                : List.of(),
                        null);
            } else {
                type = AlterTableType.DROP_DEFAULT;
            }
        } else if (ctx.ALTER() != null) {
            // Generic ALTER COLUMN — treat as no-op
            type = AlterTableType.OTHER;
            colName = !ctx.colid().isEmpty() ? ctx.colid(0).getText() : null;
        } else if (ctx.DROP() != null && ctx.CONSTRAINT() != null) {
            // DROP CONSTRAINT name
            type = AlterTableType.DROP_CONSTRAINT;
            colName = ctx.name() != null ? ctx.name().getText() : null;
        } else if (ctx.DROP() != null) {
            // DROP COLUMN x
            type = AlterTableType.DROP_COLUMN;
            colName = !ctx.colid().isEmpty() ? ctx.colid(0).getText() : null;
        }

        return new AlterTableCmd(type, colName, def, beh);
    }

    private ColumnDefNode buildConstraintAsColumnDef(TableconstraintContext tc) {
        if (tc == null) return null;
        String constraintName = tc.name() != null ? tc.name().getText() : null;
        ColumnConstraintNode ccn = buildConstraintelemAsColConstraint(
                tc.constraintelem(), constraintName);
        if (ccn == null) return null;
        return new ColumnDefNode("", TypeName.of("text"), List.of(ccn), null);
    }

    private ColumnConstraintNode buildConstraintelemAsColConstraint(
            org.pgjava.sql.parser.antlr.PostgreSQLParser.ConstraintelemContext ce,
            String constraintName) {
        if (ce == null) return null;
        if (ce.PRIMARY() != null) {
            List<String> cols = ce.columnlist() != null
                    ? ce.columnlist().columnElem().stream()
                            .map(c -> c.colid().getText().toLowerCase()).toList()
                    : List.of();
            return new ColumnConstraintNode(ConstrType.PRIMARY, constraintName,
                    null, List.of(), null, cols, null, null, false, false);
        }
        if (ce.UNIQUE() != null) {
            List<String> cols = ce.columnlist() != null
                    ? ce.columnlist().columnElem().stream()
                            .map(c -> c.colid().getText().toLowerCase()).toList()
                    : List.of();
            return new ColumnConstraintNode(ConstrType.UNIQUE, constraintName,
                    null, List.of(), null, cols, null, null, false, false);
        }
        if (ce.CHECK() != null && ce.a_expr() != null) {
            Expr checkExpr = expr(ce.a_expr());
            return ColumnConstraintNode.check(constraintName, checkExpr);
        }
        if (ce.FOREIGN() != null) {
            // FOREIGN KEY (fk_cols) REFERENCES reftable (pk_cols)
            List<String> fkCols = List.of();
            List<String> pkCols = List.of();
            RangeVar refTable = null;
            // columnlist = the FK column list
            if (ce.columnlist() != null) {
                fkCols = ce.columnlist().columnElem().stream()
                        .map(c -> c.colid().getText().toLowerCase()).toList();
            }
            // qualified_name = reference table
            if (ce.qualified_name() != null) {
                refTable = buildRangeVar(ce.qualified_name());
            }
            // column_list_ = optional (pk_cols)
            if (ce.column_list_() != null && ce.column_list_().columnlist() != null) {
                pkCols = ce.column_list_().columnlist().columnElem().stream()
                        .map(c -> c.colid().getText().toLowerCase()).toList();
            }
            return new ColumnConstraintNode(ConstrType.FK, constraintName,
                    null, fkCols, refTable, pkCols, null, null, false, false);
        }
        return null;
    }

    private OnConflictClause buildOnConflict(On_conflict_Context ctx) {
        OnConflictAction action = ctx.NOTHING() != null
                ? OnConflictAction.NOTHING : OnConflictAction.UPDATE;
        List<AssignTarget> targets = new ArrayList<>();
        if (ctx.set_clause_list() != null) {
            for (var sc : ctx.set_clause_list().set_clause()) {
                targets.add(buildSetClause(sc));
            }
        }
        Expr where = ctx.where_clause() != null ? expr(ctx.where_clause().a_expr()) : null;
        InferClause infer = null;
        if (ctx.conf_expr_() != null) {
            var conf = ctx.conf_expr_();
            if (conf.CONSTRAINT() != null) {
                infer = new InferClause(List.of(), null, conf.name().getText());
            } else if (conf.index_params() != null) {
                List<IndexElem> elems = conf.index_params().index_elem().stream()
                        .map(this::buildIndexElem).toList();
                Expr confWhere = conf.where_clause() != null ? expr(conf.where_clause().a_expr()) : null;
                infer = new InferClause(elems, confWhere, null);
            }
        }
        return new OnConflictClause(action, infer, targets, where);
    }

    private AssignTarget buildSetClause(Set_clauseContext ctx) {
        List<String> names = new ArrayList<>();
        Expr val = null;
        if (ctx.set_target() != null) {
            names.add(ctx.set_target().colid().getText());
            val = expr(ctx.a_expr());
        } else if (ctx.set_target_list() != null) {
            for (var st : ctx.set_target_list().set_target()) names.add(st.colid().getText());
            val = expr(ctx.a_expr());
        }
        return new AssignTarget(names, val, null);
    }

    private IndexElem buildIndexElem(Index_elemContext ctx) {
        String colname = ctx.colid() != null ? ctx.colid().getText() : null;
        Expr expr = ctx.a_expr() != null ? expr(ctx.a_expr()) : null;
        var opts = ctx.index_elem_options();
        String opclass = (opts != null && opts.class_() != null) ? opts.class_().getText() : null;
        SortByDir dir = SortByDir.DEFAULT;
        SortByNulls nulls = SortByNulls.DEFAULT;
        return new IndexElem(colname, expr, opclass, dir, nulls);
    }

    private DropBehavior buildDropBehavior(Drop_behavior_Context ctx) {
        if (ctx == null) return DropBehavior.RESTRICT;
        return ctx.CASCADE() != null ? DropBehavior.CASCADE : DropBehavior.RESTRICT;
    }

    private List<WindowDef> buildWindowClause(Window_clauseContext ctx) {
        List<WindowDef> result = new ArrayList<>();
        for (var wd : ctx.window_definition_list().window_definition()) {
            String name = wd.colid().getText();
            result.add(buildWindowDef(name, wd.window_specification()));
        }
        return result;
    }

    private WindowDef buildWindowDef(String name, Window_specificationContext ctx) {
        if (ctx == null) return new WindowDef(name, null, List.of(), List.of(), null);
        String refname = ctx.existing_window_name_() != null ? ctx.existing_window_name_().colid().getText() : null;
        List<Expr> partition = ctx.partition_clause_() != null
                ? buildExprList(ctx.partition_clause_().expr_list().a_expr()) : List.of();
        List<SortKey> order = ctx.sort_clause_() != null
                ? buildSortClause(ctx.sort_clause_().sort_clause()) : List.of();
        return new WindowDef(name, refname, partition, order, null);
    }

    private List<Expr> buildGroupBy(Group_clauseContext ctx) {
        List<Expr> result = new ArrayList<>();
        for (var item : ctx.group_by_list().group_by_item()) {
            if (item.a_expr() != null) result.add(expr(item.a_expr()));
            else if (item.empty_grouping_set() != null) {/* skip */}
        }
        return result;
    }

    private SelectStmt buildValuesSelect(Values_clauseContext ctx) {
        List<List<Expr>> rows = new ArrayList<>();
        for (var exprList : ctx.expr_list()) {
            rows.add(buildExprList(exprList.a_expr()));
        }
        return new SelectStmt(List.of(), List.of(), null, List.of(), null,
                List.of(), null, null, List.of(), false, List.of(),
                null, null, null, false, List.of(), null, rows);
    }

    private List<Expr> buildExprList(List<? extends A_exprContext> exprs) {
        return exprs.stream().map(this::expr).toList();
    }

    private FunctionCall buildFuncApplication(Func_applicationContext app,
                                               Within_group_clauseContext within,
                                               Filter_clauseContext filter,
                                               Over_clauseContext over) {
        List<String> name = buildFuncName(app.func_name());
        boolean aggStar = app.STAR() != null;
        boolean aggDistinct = app.DISTINCT() != null;
        List<Expr> args = new ArrayList<>();
        List<SortKey> aggOrder = List.of();
        if (app.func_arg_list() != null) {
            for (var fa : app.func_arg_list().func_arg_expr()) {
                if (fa.a_expr() != null) {
                    if (fa.param_name() != null) {
                        args.add(new NamedArgExpr(fa.param_name().getText(), expr(fa.a_expr())));
                    } else {
                        args.add(expr(fa.a_expr()));
                    }
                }
            }
        }
        if (app.sort_clause_() != null) {
            aggOrder = buildSortClause(app.sort_clause_().sort_clause());
        }
        Expr aggFilter = filter != null ? expr(filter.a_expr()) : null;
        boolean withinGroup = within != null;
        WindowDef overDef = null;
        if (over != null && over.window_specification() != null) {
            overDef = buildWindowDef(null, over.window_specification());
        } else if (over != null && over.colid() != null) {
            overDef = new WindowDef(null, over.colid().getText(), List.of(), List.of(), null);
        }
        return new FunctionCall(name, args, aggDistinct, aggOrder, aggFilter, aggStar, withinGroup, overDef);
    }

    private List<String> buildFuncName(Func_nameContext ctx) {
        if (ctx.type_function_name() != null) return List.of(ctx.type_function_name().getText().toLowerCase());
        if (ctx.colid() != null) {
            List<String> parts = new ArrayList<>();
            parts.add(ctx.colid().getText().toLowerCase());
            if (ctx.indirection() != null) {
                for (var el : ctx.indirection().indirection_el()) {
                    if (el.attr_name() != null) parts.add(el.attr_name().getText().toLowerCase());
                }
            }
            return parts;
        }
        return List.of(ctx.getText().toLowerCase());
    }

    private Expr applyOptIndirection(Expr base, Opt_indirectionContext ctx) {
        if (ctx == null || ctx.indirection_el().isEmpty()) return base;
        Expr result = base;
        for (var el : ctx.indirection_el()) {
            if (el.DOT() != null) {
                if (el.STAR() != null) {
                    result = new FieldSelectExpr(result, "*");
                } else if (el.attr_name() != null) {
                    result = new FieldSelectExpr(result, el.attr_name().getText());
                }
            } else if (el.OPEN_BRACKET() != null) {
                if (!el.slice_bound_().isEmpty()) {
                    // a[i:j]
                    Expr low = el.a_expr() != null ? expr(el.a_expr()) : null;
                    var sb = el.slice_bound_(0);
                    Expr high = sb != null && sb.a_expr() != null ? expr(sb.a_expr()) : null;
                    result = new SubscriptExpr(result, low, high, true);
                } else {
                    result = new SubscriptExpr(result, expr(el.a_expr()), null, false);
                }
            }
        }
        return result;
    }

    private List<Expr> buildSubstrArgs(Func_expr_common_subexprContext ctx) {
        List<Expr> args = new ArrayList<>();
        if (ctx.substr_list() != null) {
            for (var a : ctx.substr_list().a_expr()) args.add(expr(a));
        } else if (ctx.func_arg_list() != null) {
            for (var fa : ctx.func_arg_list().func_arg_expr()) {
                if (fa.a_expr() != null) args.add(expr(fa.a_expr()));
            }
        }
        return args;
    }

    private IntegerLiteral buildIconst(IconstContext ctx) {
        String text = ctx.getText();
        try {
            if (text.startsWith("0b") || text.startsWith("0B"))
                return new IntegerLiteral(Long.parseLong(text.substring(2), 2));
            if (text.startsWith("0o") || text.startsWith("0O"))
                return new IntegerLiteral(Long.parseLong(text.substring(2), 8));
            if (text.startsWith("0x") || text.startsWith("0X"))
                return new IntegerLiteral(Long.parseLong(text.substring(2), 16));
            return new IntegerLiteral(Long.parseLong(text));
        } catch (NumberFormatException e) {
            return new IntegerLiteral(0);
        }
    }

    /** Extract the string value from sconst, stripping quotes and handling escapes. */
    private String extractSconst(SconstContext ctx) {
        if (ctx == null) return "";
        var anysconst = ctx.anysconst();
        if (anysconst.StringConstant() != null) {
            String raw = anysconst.StringConstant().getText();
            // Strip surrounding quotes
            if (raw.startsWith("'") && raw.endsWith("'")) {
                raw = raw.substring(1, raw.length() - 1);
            }
            // Handle doubled single quotes
            return raw.replace("''", "'");
        }
        if (anysconst.EscapeStringConstant() != null) {
            String raw = anysconst.EscapeStringConstant().getText();
            if (raw.startsWith("E'") || raw.startsWith("e'")) raw = raw.substring(2);
            else if (raw.startsWith("'")) raw = raw.substring(1);
            if (raw.endsWith("'")) raw = raw.substring(0, raw.length() - 1);
            return raw.replace("''", "'");
        }
        if (anysconst.UnicodeEscapeStringConstant() != null) {
            String raw = anysconst.UnicodeEscapeStringConstant().getText();
            if (raw.startsWith("U&'") || raw.startsWith("u&'")) raw = raw.substring(3);
            if (raw.endsWith("'")) raw = raw.substring(0, raw.length() - 1);
            return raw;
        }
        // DollarText (dollar-quoted strings)
        var dollarParts = anysconst.DollarText();
        if (!dollarParts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (var part : dollarParts) sb.append(part.getText());
            return sb.toString();
        }
        return ctx.getText();
    }

    // -------------------------------------------------------------------------
    // Set operation helper

    private SelectStmt setOp(SelectStmt left, SelectStmt right, SetOpType op, boolean all) {
        return new SelectStmt(List.of(), List.of(), null, List.of(), null,
                List.of(), null, null, List.of(), false, List.of(),
                op, left, right, all, List.of(), null, List.of());
    }

    private SelectStmt emptySelect(List<TargetEntry> targets, List<FromItem> from) {
        return new SelectStmt(targets, from, null, List.of(), null,
                List.of(), null, null, List.of(), false, List.of(),
                null, null, null, false, List.of(), null, List.of());
    }

    private boolean isAll(ParserRuleContext ctx, ParseTree afterTok) {
        boolean found = false;
        for (var child : ctx.children) {
            if (child == afterTok) { found = true; continue; }
            if (found) {
                // ALL may be a direct terminal or inside all_or_distinct sub-rule
                if (child instanceof TerminalNode tn
                        && tn.getSymbol().getType() == PostgreSQLParser.ALL) return true;
                if (child instanceof All_or_distinctContext aod && aod.ALL() != null) return true;
                if (child instanceof Simple_select_intersectContext
                        || child instanceof Simple_select_pramaryContext) break;
            }
        }
        return false;
    }
}
