package org.pgjava.sql;

import org.pgjava.sql.ast.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconstructs SQL text from AST nodes, matching PostgreSQL's deparsing
 * conventions (as used by {@code pg_get_viewdef}, {@code pg_get_constraintdef},
 * etc.).
 *
 * <p>PG's deparser works on the <em>rewritten</em> query tree, not the parsed AST.
 * PG's parser converts BETWEEN→{@code >= AND <=}, LIKE→{@code ~~},
 * IN(list)→{@code = ANY(ARRAY[...])}, and adds explicit type casts.
 * When {@code rewrite=true}, this deparser applies the same transformations
 * to match PG's output.
 */
public final class SqlDeparser {

    private final StringBuilder sb = new StringBuilder();
    private final List<String> rangeTableNames = new ArrayList<>();
    /** Table names from outer query scopes (for subquery disambiguation). */
    private final Set<String> outerTableNames = new HashSet<>();
    /** Maps original table name → disambiguated alias when conflicts exist. */
    private final Map<String, String> tableAliases = new HashMap<>();
    private boolean qualifyColumns = false;
    private boolean prettyPrint = false;
    private boolean rewrite = false;
    /** Current indentation depth for nested pretty-print (subqueries). */
    private int indent = 0;

    private SqlDeparser() {}

    /**
     * Deparse a SELECT statement to SQL text, matching PG's pg_get_viewdef:
     * column qualification, pretty-printing, and semantic rewrites.
     */
    public static String deparse(SelectStmt stmt) {
        SqlDeparser d = new SqlDeparser();
        d.qualifyColumns = true;
        d.prettyPrint = true;
        d.rewrite = true;
        d.collectRangeTableNames(stmt);
        d.deparseSelect(stmt, false);
        return d.sb.toString().trim();
    }

    /** Deparse without column qualification, pretty-printing, or rewrites. */
    public static String deparseRaw(SelectStmt stmt) {
        SqlDeparser d = new SqlDeparser();
        d.deparseSelect(stmt, false);
        return d.sb.toString().trim();
    }

    /** Deparse any expression to SQL text. */
    public static String deparseExpr(Expr expr) {
        SqlDeparser d = new SqlDeparser();
        d.expr(expr);
        return d.sb.toString().trim();
    }

    // =========================================================================
    // Range table collection (for column qualification)
    // =========================================================================

    private void collectRangeTableNames(SelectStmt s) {
        if (s.fromClause() == null) return;
        for (FromItem fi : s.fromClause()) {
            collectFromItemName(fi);
        }
    }

    private void collectFromItemName(FromItem fi) {
        switch (fi) {
            case RangeVar rv -> {
                String name = rv.alias() != null ? rv.alias() : rv.relName();
                if (name != null) rangeTableNames.add(name);
            }
            case JoinExpr j -> {
                collectFromItemName(j.larg());
                collectFromItemName(j.rarg());
            }
            case RangeSubselect rs -> {
                if (rs.alias() != null) rangeTableNames.add(rs.alias());
            }
            case RangeFunction rf -> {
                if (rf.alias() != null) rangeTableNames.add(rf.alias());
            }
        }
    }

    // =========================================================================
    // SELECT
    // =========================================================================

    private void deparseSelect(SelectStmt s, boolean parens) {
        if (parens) sb.append('(');

        // Set operations (UNION / INTERSECT / EXCEPT)
        if (s.setOp() != null && s.left() != null && s.right() != null) {
            deparseSetOp(s);
            if (parens) sb.append(')');
            return;
        }

        // VALUES
        if (s.valuesLists() != null && !s.valuesLists().isEmpty()) {
            deparseValues(s.valuesLists());
            deparseOrderBy(s);
            deparseLimit(s);
            if (parens) sb.append(')');
            return;
        }

        // WITH
        if (s.withClause() != null) {
            deparseWith(s.withClause());
        }

        // SELECT [DISTINCT [ON (...)]]
        sb.append("SELECT ");
        if (s.distinct() || (s.distinctOn() != null && !s.distinctOn().isEmpty())) {
            sb.append("DISTINCT ");
            if (s.distinctOn() != null && !s.distinctOn().isEmpty()) {
                sb.append("ON (");
                exprList(s.distinctOn());
                sb.append(") ");
            }
        }

        // Target list
        if (s.targetList() == null || s.targetList().isEmpty()) {
            sb.append('*');
        } else {
            for (int i = 0; i < s.targetList().size(); i++) {
                if (i > 0) {
                    sb.append(',');
                    if (prettyPrint) nl(4);
                    else sb.append(' ');
                }
                TargetEntry te = s.targetList().get(i);
                expr(te.val());
                String alias = te.name();
                // PG's rewriter adds implicit aliases for function calls (AS funcname)
                if ((alias == null || alias.isEmpty()) && rewrite
                        && te.val() instanceof FunctionCall fc && fc.funcname() != null) {
                    alias = fc.funcname().getLast();
                }
                if (alias != null && !alias.isEmpty()) {
                    sb.append(" AS ").append(quoteIdent(alias));
                }
            }
        }

        // FROM
        if (s.fromClause() != null && !s.fromClause().isEmpty()) {
            if (prettyPrint) nl(3);
            sb.append("FROM ");
            for (int i = 0; i < s.fromClause().size(); i++) {
                if (i > 0) sb.append(", ");
                fromItem(s.fromClause().get(i));
            }
        }

        // WHERE
        if (s.whereClause() != null) {
            if (prettyPrint) nl(2);
            sb.append("WHERE ");
            parenExpr(s.whereClause());
        }

        // GROUP BY
        if (s.groupBy() != null && !s.groupBy().isEmpty()) {
            if (prettyPrint) nl(2);
            sb.append("GROUP BY ");
            exprList(s.groupBy());
        }

        // HAVING
        if (s.having() != null) {
            if (prettyPrint) nl(1);
            sb.append("HAVING ");
            parenExpr(s.having());
        }

        // WINDOW
        if (s.windows() != null && !s.windows().isEmpty()) {
            sb.append(" WINDOW ");
            for (int i = 0; i < s.windows().size(); i++) {
                if (i > 0) sb.append(", ");
                WindowDef w = s.windows().get(i);
                sb.append(quoteIdent(w.name())).append(" AS ");
                windowSpec(w);
            }
        }

        deparseOrderBy(s);
        deparseLimit(s);
        deparseLocking(s);

        if (parens) sb.append(')');
    }

    /** Newline + indent spaces, used for pretty-printing. */
    private void nl(int spaces) {
        sb.append('\n');
        sb.append(" ".repeat(indent + spaces));
    }

    // =========================================================================
    // Set operations (UNION / INTERSECT / EXCEPT)
    // =========================================================================

    private void deparseSetOp(SelectStmt s) {
        // PG qualifies columns per-branch in set operations
        List<String> savedNames = new ArrayList<>(rangeTableNames);

        rangeTableNames.clear();
        collectRangeTableNames(s.left());
        deparseSelect(s.left(), false);

        if (prettyPrint) nl(0);
        else sb.append(' ');
        sb.append(s.setOp().name());
        if (s.setAll()) sb.append(" ALL");
        if (prettyPrint) nl(1);
        else sb.append(' ');

        rangeTableNames.clear();
        collectRangeTableNames(s.right());
        deparseSelect(s.right(), false);

        rangeTableNames.clear();
        rangeTableNames.addAll(savedNames);
        deparseOrderBy(s);
        deparseLimit(s);
        deparseLocking(s);
    }

    // =========================================================================
    // ORDER BY / LIMIT / OFFSET
    // =========================================================================

    private void deparseOrderBy(SelectStmt s) {
        if (s.orderBy() != null && !s.orderBy().isEmpty()) {
            if (prettyPrint) nl(2);
            sb.append("ORDER BY ");
            for (int i = 0; i < s.orderBy().size(); i++) {
                if (i > 0) sb.append(", ");
                sortKey(s.orderBy().get(i));
            }
        }
    }

    private void deparseLimit(SelectStmt s) {
        // PG outputs OFFSET before LIMIT
        if (s.limitOffset() != null) {
            if (prettyPrint) nl(1);
            sb.append("OFFSET ");
            expr(s.limitOffset());
        }
        if (s.limitCount() != null) {
            if (prettyPrint) nl(1);
            sb.append("LIMIT ");
            expr(s.limitCount());
        }
    }

    private void deparseLocking(SelectStmt s) {
        if (s.locking() == null) return;
        for (LockingClause lc : s.locking()) {
            sb.append(" FOR ");
            sb.append(switch (lc.strength()) {
                case UPDATE        -> "UPDATE";
                case NO_KEY_UPDATE -> "NO KEY UPDATE";
                case SHARE         -> "SHARE";
                case KEY_SHARE     -> "KEY SHARE";
            });
            if (lc.lockedRels() != null && !lc.lockedRels().isEmpty()) {
                sb.append(" OF ");
                for (int i = 0; i < lc.lockedRels().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(lc.lockedRels().get(i).relName());
                }
            }
            if (lc.waitPolicy() == LockWaitPolicy.SKIP) sb.append(" SKIP LOCKED");
            else if (lc.waitPolicy() == LockWaitPolicy.ERROR) sb.append(" NOWAIT");
        }
    }

    private void deparseValues(List<List<Expr>> valuesLists) {
        sb.append("VALUES ");
        for (int r = 0; r < valuesLists.size(); r++) {
            if (r > 0) sb.append(", ");
            sb.append('(');
            exprList(valuesLists.get(r));
            sb.append(')');
        }
    }

    private void deparseWith(WithClause wc) {
        sb.append("WITH ");
        if (wc.recursive()) sb.append("RECURSIVE ");
        for (int i = 0; i < wc.ctes().size(); i++) {
            if (i > 0) sb.append(", ");
            CommonTableExpr cte = wc.ctes().get(i);
            sb.append(quoteIdent(cte.ctename()));
            if (cte.aliasColNames() != null && !cte.aliasColNames().isEmpty()) {
                sb.append('(');
                identList(cte.aliasColNames());
                sb.append(')');
            }
            sb.append(" AS ");
            if (cte.materialized() == CTEMaterialize.MATERIALIZED)
                sb.append("MATERIALIZED ");
            else if (cte.materialized() == CTEMaterialize.NOT_MATERIALIZED)
                sb.append("NOT MATERIALIZED ");
            sb.append('(');
            deparseSelect(cte.query(), false);
            sb.append(") ");
        }
    }

    // =========================================================================
    // FROM items
    // =========================================================================

    private void fromItem(FromItem item) {
        switch (item) {
            case RangeVar rv -> {
                if (rv.schemaName() != null && !rv.schemaName().isEmpty()) {
                    sb.append(quoteIdent(rv.schemaName())).append('.');
                }
                sb.append(quoteIdent(rv.relName()));
                String alias = rv.alias();
                // Add disambiguated alias for conflicting table names in subqueries
                if (alias == null && tableAliases.containsKey(rv.relName())) {
                    alias = tableAliases.get(rv.relName());
                }
                if (alias != null && !alias.isEmpty()) {
                    sb.append(' ').append(quoteIdent(alias));
                }
            }
            case JoinExpr j -> {
                // PG wraps the join tree in parens in the FROM clause
                sb.append('(');
                fromItem(j.larg());
                if (j.natural()) sb.append(" NATURAL");
                // PG puts JOIN keyword on new line with indent
                if (prettyPrint) nl(5);
                else sb.append(' ');
                sb.append(switch (j.joinType()) {
                    case INNER -> "JOIN";
                    case LEFT  -> "LEFT JOIN";
                    case RIGHT -> "RIGHT JOIN";
                    case FULL  -> "FULL JOIN";
                    case CROSS -> "CROSS JOIN";
                });
                sb.append(' ');
                fromItem(j.rarg());
                if (j.quals() != null) {
                    // PG double-wraps ON condition: ON ((expr))
                    sb.append(" ON (");
                    parenExpr(j.quals());
                    sb.append(')');
                }
                if (j.usingCols() != null && !j.usingCols().isEmpty()) {
                    sb.append(" USING (");
                    identList(j.usingCols());
                    sb.append(')');
                }
                sb.append(')');
            }
            case RangeSubselect rs -> {
                if (rs.lateral()) sb.append("LATERAL ");
                sb.append('(');
                deparseSelect(rs.subquery(), false);
                sb.append(')');
                if (rs.alias() != null && !rs.alias().isEmpty()) {
                    sb.append(' ').append(quoteIdent(rs.alias()));
                    if (rs.colAliases() != null && !rs.colAliases().isEmpty()) {
                        sb.append('(');
                        identList(rs.colAliases());
                        sb.append(')');
                    }
                }
            }
            case RangeFunction rf -> {
                if (rf.lateral()) sb.append("LATERAL ");
                expr(rf.function());
                if (rf.withOrdinality()) sb.append(" WITH ORDINALITY");
                if (rf.alias() != null && !rf.alias().isEmpty()) {
                    sb.append(" AS ").append(quoteIdent(rf.alias()));
                    if (rf.colAliases() != null && !rf.colAliases().isEmpty()) {
                        sb.append('(');
                        identList(rf.colAliases());
                        sb.append(')');
                    }
                }
            }
        }
    }

    // =========================================================================
    // Expressions
    // =========================================================================

    /**
     * Functions that PG deparses with uppercase names because they are
     * special syntax forms, not ordinary function calls.
     */
    private static final Set<String> UPPERCASE_FUNCTIONS = Set.of(
            "coalesce", "nullif", "greatest", "least"
    );

    /** PG canonical type names — maps parser short names to display names. */
    private static final Map<String, String> CANONICAL_TYPE_NAMES = Map.ofEntries(
            Map.entry("int2", "smallint"),
            Map.entry("int4", "integer"),
            Map.entry("int8", "bigint"),
            Map.entry("float4", "real"),
            Map.entry("float8", "double precision"),
            Map.entry("bool", "boolean"),
            Map.entry("varchar", "character varying"),
            Map.entry("char", "character"),
            Map.entry("timetz", "time with time zone"),
            Map.entry("timestamptz", "timestamp with time zone")
    );

    private void expr(Expr e) {
        switch (e) {
            case IntegerLiteral il   -> sb.append(il.value());
            case FloatLiteral fl     -> sb.append(fl.rawStr());
            case StringLiteral sl    -> {
                sb.append('\'');
                sb.append(sl.value().replace("'", "''"));
                sb.append('\'');
                // PG adds explicit ::text cast to string literals in views
                if (rewrite) sb.append("::text");
            }
            case BooleanLiteral bl   -> sb.append(bl.value() ? "true" : "false");
            case NullLiteral nl      -> sb.append("NULL");
            case SetToDefault sd     -> sb.append("DEFAULT");

            case TypedLiteral tl     -> {
                sb.append(typeName(tl.typeName()));
                sb.append(" '").append(tl.value()).append('\'');
            }
            case IntervalLiteral il  -> {
                sb.append("INTERVAL '").append(il.value()).append('\'');
                if (il.fields() != null && !il.fields().isEmpty()) {
                    sb.append(' ').append(il.fields());
                }
            }

            case ColumnRef cr        -> deparseColumnRef(cr);
            case ParamRef pr         -> sb.append('$').append(pr.number());

            case BinaryOp bo         -> deparseBinaryOp(bo);
            case UnaryOp uo          -> deparseUnaryOp(uo);

            case CastExpr ce         -> {
                // PG wraps non-literal cast args in parens: (col)::type
                boolean needsParens = !(ce.arg() instanceof IntegerLiteral
                        || ce.arg() instanceof FloatLiteral
                        || ce.arg() instanceof StringLiteral
                        || ce.arg() instanceof BooleanLiteral
                        || ce.arg() instanceof NullLiteral);
                if (needsParens) sb.append('(');
                expr(ce.arg());
                if (needsParens) sb.append(')');
                sb.append("::").append(typeName(ce.targetType()));
            }

            case FunctionCall fc     -> deparseFunctionCall(fc);
            case CaseExpr ce         -> deparseCase(ce);
            case SubLink sl          -> deparseSubLink(sl);

            case InExpr ie           -> deparseIn(ie);
            case InSubselect is      -> deparseInSubselect(is);
            case BetweenExpr be      -> deparseBetween(be);
            case LikeExpr le         -> deparseLike(le);

            case RowExpr re          -> {
                sb.append("ROW(");
                exprList(re.args());
                sb.append(')');
            }
            case ArrayExpr ae        -> {
                sb.append("ARRAY[");
                exprList(ae.elements());
                sb.append(']');
            }
            case ArraySubselect as   -> {
                sb.append("ARRAY(");
                deparseSelect(as.subselect(), false);
                sb.append(')');
            }
            case SubscriptExpr se    -> {
                expr(se.target());
                sb.append('[');
                expr(se.idx());
                if (se.isSlice() && se.idxUpper() != null) {
                    sb.append(':');
                    expr(se.idxUpper());
                }
                sb.append(']');
            }
            case FieldSelectExpr fse -> {
                sb.append('(');
                expr(fse.arg());
                sb.append(").").append(quoteIdent(fse.fieldName()));
            }
            case CollateExpr ce      -> {
                expr(ce.arg());
                sb.append(" COLLATE ");
                sb.append(String.join(".", ce.collationName()));
            }
            case MinMaxExpr mm       -> {
                sb.append(mm.op().name()).append('(');
                exprList(mm.args());
                sb.append(')');
            }
            case GroupingExpr ge     -> {
                sb.append("GROUPING(");
                exprList(ge.args());
                sb.append(')');
            }
            case NamedArgExpr na     -> {
                sb.append(na.name()).append(" => ");
                expr(na.arg());
            }
            case ArrayAnyAllExpr aa  -> {
                expr(aa.testExpr());
                sb.append(' ').append(aa.op()).append(' ');
                sb.append(aa.useAll() ? "ALL(" : "ANY(");
                expr(aa.arrayExpr());
                sb.append(')');
            }
        }
    }

    // =========================================================================
    // Column references
    // =========================================================================

    private void deparseColumnRef(ColumnRef cr) {
        // If qualifyColumns is on and this is a bare column name (no table prefix),
        // and there's exactly one table in the FROM clause, qualify it.
        if (qualifyColumns && cr.fields().size() == 1
                && rangeTableNames.size() == 1
                && !"*".equals(cr.fields().getFirst())) {
            sb.append(quoteIdent(rangeTableNames.getFirst()));
            sb.append('.');
            sb.append(quoteIdent(cr.fields().getFirst()));
        } else {
            for (int i = 0; i < cr.fields().size(); i++) {
                if (i > 0) sb.append('.');
                String f = cr.fields().get(i);
                if ("*".equals(f)) sb.append('*');
                else sb.append(quoteIdent(f));
            }
        }
    }

    // =========================================================================
    // PG semantic rewrites: IN → ANY(ARRAY[...]), BETWEEN → >= AND <=,
    // LIKE → ~~
    // =========================================================================

    private void deparseIn(InExpr ie) {
        if (rewrite) {
            // PG rewrites IN (v1, v2, ...) → = ANY (ARRAY[v1, v2, ...])
            sb.append('(');
            expr(ie.arg());
            if (ie.negated()) sb.append(" <> ALL");
            else sb.append(" = ANY");
            sb.append(" (ARRAY[");
            exprList(ie.list());
            sb.append("]))");
        } else {
            expr(ie.arg());
            if (ie.negated()) sb.append(" NOT");
            sb.append(" IN (");
            exprList(ie.list());
            sb.append(')');
        }
    }

    private void deparseInSubselect(InSubselect is) {
        expr(is.arg());
        if (is.negated()) sb.append(" NOT");
        sb.append(" IN ( ");
        int savedIndent = indent;
        if (prettyPrint) indent += 8;
        var scope = enterSubqueryScope(is.subselect());
        deparseSelect(is.subselect(), false);
        exitSubqueryScope(scope);
        indent = savedIndent;
        sb.append(')');
    }

    private void deparseBetween(BetweenExpr be) {
        if (rewrite) {
            // PG rewrites BETWEEN → AND(>=, <=). The result is an AND node,
            // so wrap it like PG does when AND appears as child of another AND.
            sb.append("((");
            expr(be.arg());
            sb.append(be.negated() ? " < " : " >= ");
            expr(be.low());
            sb.append(") AND (");
            expr(be.arg());
            sb.append(be.negated() ? " > " : " <= ");
            expr(be.high());
            sb.append("))");
        } else {
            expr(be.arg());
            if (be.negated()) sb.append(" NOT");
            sb.append(" BETWEEN ");
            if (be.symmetric()) sb.append("SYMMETRIC ");
            expr(be.low());
            sb.append(" AND ");
            expr(be.high());
        }
    }

    private void deparseLike(LikeExpr le) {
        if (rewrite) {
            // PG rewrites LIKE → ~~ operator, ILIKE → ~~*
            sb.append('(');
            expr(le.arg());
            String op = switch (le.type()) {
                case LIKE       -> le.negated() ? " !~~ "  : " ~~ ";
                case ILIKE      -> le.negated() ? " !~~* " : " ~~* ";
                case SIMILAR_TO -> le.negated() ? " !~ "   : " ~ ";
            };
            sb.append(op);
            expr(le.pattern());
            sb.append(')');
        } else {
            expr(le.arg());
            if (le.negated()) sb.append(" NOT");
            sb.append(switch (le.type()) {
                case LIKE       -> " LIKE ";
                case ILIKE      -> " ILIKE ";
                case SIMILAR_TO -> " SIMILAR TO ";
            });
            expr(le.pattern());
            if (le.escape() != null) {
                sb.append(" ESCAPE ");
                expr(le.escape());
            }
        }
    }

    // =========================================================================
    // Parenthesization
    // =========================================================================

    /** Wrap a top-level boolean expression in parens (PG style for WHERE/HAVING/ON). */
    private void parenExpr(Expr e) {
        boolean needsParens = needsOuterParens(e);
        if (needsParens) sb.append('(');
        expr(e);
        if (needsParens) sb.append(')');
    }

    private boolean needsOuterParens(Expr e) {
        if (e instanceof BinaryOp || e instanceof SubLink || e instanceof InSubselect
                || e instanceof UnaryOp) {
            return true;
        }
        // When rewrite=true, InExpr/LikeExpr/BetweenExpr produce their own parens
        if (!rewrite) {
            return e instanceof BetweenExpr || e instanceof InExpr || e instanceof LikeExpr;
        }
        return false;
    }

    // =========================================================================
    // Binary / unary operators
    // =========================================================================

    private void deparseBinaryOp(BinaryOp bo) {
        String op = bo.op().toUpperCase();
        boolean isLogical = op.equals("AND") || op.equals("OR");

        if (isLogical) {
            parenChild(bo.left(), bo);
            sb.append(' ').append(op).append(' ');
            parenChild(bo.right(), bo);
        } else {
            exprWithParens(bo.left(), bo);
            sb.append(' ').append(bo.op()).append(' ');
            exprWithParens(bo.right(), bo);
        }
    }

    /** Wrap child in parens if it's a lower-precedence binary op. */
    private void exprWithParens(Expr child, BinaryOp parent) {
        if (child instanceof BinaryOp childBo && precedence(childBo) < precedence(parent)) {
            sb.append('(');
            expr(child);
            sb.append(')');
        } else {
            expr(child);
        }
    }

    /** PG wraps each AND/OR child that is itself an AND/OR in parens. */
    private void parenChild(Expr e, BinaryOp parent) {
        if (e instanceof BinaryOp bo) {
            String childOp = bo.op().toUpperCase();
            String parentOp = parent.op().toUpperCase();
            // Wrap if child is different logical operator, or same for clarity
            if (childOp.equals("AND") || childOp.equals("OR")) {
                if (!childOp.equals(parentOp)) {
                    sb.append('(');
                    expr(e);
                    sb.append(')');
                    return;
                }
            }
        }
        // PG wraps comparison children of AND/OR in parens
        if (e instanceof BinaryOp bo) {
            String childOp = bo.op().toUpperCase();
            if (!childOp.equals("AND") && !childOp.equals("OR")) {
                sb.append('(');
                expr(e);
                sb.append(')');
                return;
            }
        }
        expr(e);
    }

    private static int precedence(BinaryOp bo) {
        return switch (bo.op().toUpperCase()) {
            case "OR" -> 1;
            case "AND" -> 2;
            case "=", "<>", "!=", "<", ">", "<=", ">=", "IS", "IS NOT",
                 "~~", "!~~", "~~*", "!~~*", "~", "!~" -> 3;
            case "+", "-" -> 5;
            case "*", "/", "%" -> 6;
            case "^" -> 7;
            case "||" -> 4;
            default -> 4;
        };
    }

    private void deparseUnaryOp(UnaryOp uo) {
        String op = uo.op().toUpperCase();
        switch (op) {
            case "NOT" -> {
                sb.append("NOT ");
                if (uo.operand() instanceof BinaryOp) {
                    sb.append('(');
                    expr(uo.operand());
                    sb.append(')');
                } else {
                    expr(uo.operand());
                }
            }
            case "IS NULL", "ISNULL" -> {
                expr(uo.operand());
                sb.append(" IS NULL");
            }
            case "IS NOT NULL", "NOTNULL" -> {
                expr(uo.operand());
                sb.append(" IS NOT NULL");
            }
            case "IS TRUE" -> {
                expr(uo.operand());
                sb.append(" IS TRUE");
            }
            case "IS NOT TRUE" -> {
                expr(uo.operand());
                sb.append(" IS NOT TRUE");
            }
            case "IS FALSE" -> {
                expr(uo.operand());
                sb.append(" IS FALSE");
            }
            case "IS NOT FALSE" -> {
                expr(uo.operand());
                sb.append(" IS NOT FALSE");
            }
            case "IS UNKNOWN" -> {
                expr(uo.operand());
                sb.append(" IS UNKNOWN");
            }
            case "IS NOT UNKNOWN" -> {
                expr(uo.operand());
                sb.append(" IS NOT UNKNOWN");
            }
            default -> {
                sb.append(uo.op());
                if (uo.operand() instanceof BinaryOp) {
                    sb.append('(');
                    expr(uo.operand());
                    sb.append(')');
                } else {
                    expr(uo.operand());
                }
            }
        }
    }

    // =========================================================================
    // Function calls
    // =========================================================================

    private void deparseFunctionCall(FunctionCall fc) {
        String fname = String.join(".", fc.funcname());
        // PG uppercases certain special expression functions
        if (UPPERCASE_FUNCTIONS.contains(fname.toLowerCase())) {
            fname = fname.toUpperCase();
        }
        sb.append(fname).append('(');

        if (fc.aggStar()) {
            sb.append('*');
        } else {
            if (fc.aggDistinct()) sb.append("DISTINCT ");
            if (fc.args() != null) {
                for (int i = 0; i < fc.args().size(); i++) {
                    if (i > 0) sb.append(", ");
                    expr(fc.args().get(i));
                }
            }
            if (fc.aggOrder() != null && !fc.aggOrder().isEmpty()) {
                sb.append(" ORDER BY ");
                for (int i = 0; i < fc.aggOrder().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sortKey(fc.aggOrder().get(i));
                }
            }
        }
        sb.append(')');

        if (fc.aggFilter() != null) {
            sb.append(" FILTER (WHERE ");
            expr(fc.aggFilter());
            sb.append(')');
        }

        if (fc.over() != null) {
            sb.append(" OVER ");
            if (fc.over().name() != null && !fc.over().name().isEmpty()
                    && fc.over().partitionClause() == null
                    && fc.over().orderClause() == null) {
                sb.append(quoteIdent(fc.over().name()));
            } else {
                windowSpec(fc.over());
            }
        }
    }

    // =========================================================================
    // CASE — PG pretty-prints with indented WHEN/ELSE
    // =========================================================================

    private void deparseCase(CaseExpr ce) {
        if (prettyPrint) {
            deparseCasePretty(ce);
        } else {
            sb.append("CASE");
            if (ce.arg() != null) { sb.append(' '); expr(ce.arg()); }
            for (CaseWhen cw : ce.whenClauses()) {
                sb.append(" WHEN "); expr(cw.condition());
                sb.append(" THEN "); expr(cw.result());
            }
            if (ce.defResult() != null) { sb.append(" ELSE "); expr(ce.defResult()); }
            sb.append(" END");
        }
    }

    private void deparseCasePretty(CaseExpr ce) {
        // PG's appendContextKeyword strips trailing whitespace before CASE
        // and uses indentLevel+8 for CASE, +12 for WHEN/ELSE, +8 for END
        stripTrailingWhitespace();
        sb.append('\n').append(" ".repeat(indent + 8));
        sb.append("CASE");
        if (ce.arg() != null) { sb.append(' '); expr(ce.arg()); }
        for (CaseWhen cw : ce.whenClauses()) {
            sb.append('\n').append(" ".repeat(indent + 12));
            sb.append("WHEN ");
            parenExpr(cw.condition());
            sb.append(" THEN ");
            expr(cw.result());
        }
        if (ce.defResult() != null) {
            sb.append('\n').append(" ".repeat(indent + 12));
            sb.append("ELSE ");
            expr(ce.defResult());
        }
        sb.append('\n').append(" ".repeat(indent + 8));
        sb.append("END");
    }

    /** Strip trailing whitespace and newlines from the buffer (matches PG's appendContextKeyword). */
    private void stripTrailingWhitespace() {
        int len = sb.length();
        while (len > 0 && (sb.charAt(len - 1) == ' ' || sb.charAt(len - 1) == '\n'))
            len--;
        sb.setLength(len);
    }

    // =========================================================================
    // Subquery scope management (for alias disambiguation)
    // =========================================================================

    private record SubqueryScope(List<String> rangeNames, Set<String> outerNames,
                                  Map<String, String> aliases) {}

    /** Enter a subquery scope: push outer names, collect subquery names, disambiguate. */
    private SubqueryScope enterSubqueryScope(SelectStmt subquery) {
        var saved = new SubqueryScope(
                new ArrayList<>(rangeTableNames),
                new HashSet<>(outerTableNames),
                new HashMap<>(tableAliases));
        outerTableNames.addAll(rangeTableNames);
        rangeTableNames.clear();
        collectRangeTableNames(subquery);
        // Disambiguate conflicting names
        for (int i = 0; i < rangeTableNames.size(); i++) {
            String name = rangeTableNames.get(i);
            if (outerTableNames.contains(name)) {
                String alias = name + "_1";
                tableAliases.put(name, alias);
                rangeTableNames.set(i, alias);
            }
        }
        return saved;
    }

    /** Restore scope after subquery. */
    private void exitSubqueryScope(SubqueryScope saved) {
        rangeTableNames.clear();
        rangeTableNames.addAll(saved.rangeNames());
        outerTableNames.clear();
        outerTableNames.addAll(saved.outerNames());
        tableAliases.clear();
        tableAliases.putAll(saved.aliases());
    }

    // =========================================================================
    // SubLink (EXISTS / ANY / ALL / scalar subquery)
    // =========================================================================

    private void deparseSubLink(SubLink sl) {
        switch (sl.type()) {
            case EXISTS -> {
                sb.append("EXISTS ( ");
                int savedIndent = indent;
                if (prettyPrint) indent += 8;
                var scope = enterSubqueryScope(sl.subselect());
                deparseSelect(sl.subselect(), false);
                exitSubqueryScope(scope);
                indent = savedIndent;
                sb.append(')');
            }
            case ANY -> {
                expr(sl.testExpr());
                sb.append(' ').append(sl.operName()).append(" ANY (");
                deparseSelect(sl.subselect(), false);
                sb.append(')');
            }
            case ALL -> {
                expr(sl.testExpr());
                sb.append(' ').append(sl.operName()).append(" ALL (");
                deparseSelect(sl.subselect(), false);
                sb.append(')');
            }
            case EXPR, ROWCOMPARE, MULTIEXPR -> {
                sb.append("( ");
                int savedIndent = indent;
                if (prettyPrint) indent += 8;
                var scope = enterSubqueryScope(sl.subselect());
                deparseSelect(sl.subselect(), false);
                exitSubqueryScope(scope);
                indent = savedIndent;
                sb.append(')');
            }
            case ARRAY -> {
                sb.append("ARRAY(");
                deparseSelect(sl.subselect(), false);
                sb.append(')');
            }
        }
    }

    // =========================================================================
    // WINDOW
    // =========================================================================

    private void windowSpec(WindowDef w) {
        sb.append('(');
        if (w.refname() != null && !w.refname().isEmpty()) {
            sb.append(quoteIdent(w.refname()));
        }
        boolean needsSpace = false;
        if (w.partitionClause() != null && !w.partitionClause().isEmpty()) {
            sb.append("PARTITION BY ");
            exprList(w.partitionClause());
            needsSpace = true;
        }
        if (w.orderClause() != null && !w.orderClause().isEmpty()) {
            if (needsSpace) sb.append(' ');
            sb.append("ORDER BY ");
            for (int i = 0; i < w.orderClause().size(); i++) {
                if (i > 0) sb.append(", ");
                sortKey(w.orderClause().get(i));
            }
        }
        sb.append(')');
    }

    // =========================================================================
    // Sort keys
    // =========================================================================

    private void sortKey(SortKey sk) {
        expr(sk.node());
        if (sk.dir() == SortByDir.DESC) sb.append(" DESC");
        if (sk.nulls() == SortByNulls.FIRST) sb.append(" NULLS FIRST");
        else if (sk.nulls() == SortByNulls.LAST) sb.append(" NULLS LAST");
    }

    // =========================================================================
    // Type names
    // =========================================================================

    private String typeName(TypeName tn) {
        StringBuilder t = new StringBuilder();
        if (tn.setOf()) t.append("SETOF ");
        String name = tn.simpleName();
        t.append(CANONICAL_TYPE_NAMES.getOrDefault(name.toLowerCase(), name));
        if (tn.typmods() != null && !tn.typmods().isEmpty()) {
            t.append('(');
            for (int i = 0; i < tn.typmods().size(); i++) {
                if (i > 0) t.append(", ");
                if (tn.typmods().get(i) instanceof Expr ex) {
                    SqlDeparser inner = new SqlDeparser();
                    inner.expr(ex);
                    t.append(inner.sb);
                } else {
                    t.append(tn.typmods().get(i));
                }
            }
            t.append(')');
        }
        if (tn.arrayBounds() > 0) {
            t.append("[]".repeat(tn.arrayBounds()));
        }
        return t.toString();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void exprList(List<Expr> exprs) {
        for (int i = 0; i < exprs.size(); i++) {
            if (i > 0) sb.append(", ");
            expr(exprs.get(i));
        }
    }

    private void identList(List<String> names) {
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quoteIdent(names.get(i)));
        }
    }

    private static String quoteIdent(String id) {
        if (id == null || id.isEmpty()) return id;
        if (id.equals(id.toLowerCase()) && id.matches("[a-z_][a-z0-9_]*") && !isReserved(id)) {
            return id;
        }
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    private static boolean isReserved(String id) {
        return switch (id) {
            case "all", "analyse", "analyze", "and", "any", "array", "as", "asc",
                 "between", "both", "case", "cast", "check", "collate", "column",
                 "constraint", "create", "cross", "current_date", "current_time",
                 "current_timestamp", "default", "desc", "distinct", "do", "else",
                 "end", "except", "false", "for", "foreign", "from", "full", "grant",
                 "group", "having", "in", "inner", "intersect", "into", "is", "join",
                 "lateral", "leading", "left", "like", "limit", "natural", "not",
                 "null", "offset", "on", "only", "or", "order", "outer", "primary",
                 "references", "right", "select", "some", "table", "then", "to",
                 "trailing", "true", "union", "unique", "using", "when", "where",
                 "window", "with" -> true;
            default -> false;
        };
    }
}
