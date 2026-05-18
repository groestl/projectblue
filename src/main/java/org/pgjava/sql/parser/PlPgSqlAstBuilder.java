package org.pgjava.sql.parser;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.pgjava.sql.ast.plpgsql.*;
import org.pgjava.sql.parser.antlr.PlPgSqlParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts an ANTLR PlPgSqlParser parse tree into PL/pgSQL AST nodes.
 * SQL fragments are captured as raw text strings for later parsing by {@link ParserProvider}.
 */
public class PlPgSqlAstBuilder {

    // =====================================================================
    // Top-level body
    // =====================================================================

    public PlPgSqlBody buildBody(PlPgSqlParser.BodyContext ctx) {
        String label = extractLabel(ctx.opt_label());
        List<PlPgSqlDecl> decls = ctx.decl_section() != null
                ? visitDeclSection(ctx.decl_section()) : List.of();
        List<PlPgSqlStmt> stmts = visitStmtList(ctx.stmt_list());
        List<ExceptionHandler> handlers = ctx.exception_section() != null
                ? visitExceptionSection(ctx.exception_section()) : List.of();
        return new PlPgSqlBody(label, decls, stmts, handlers);
    }

    // =====================================================================
    // Labels
    // =====================================================================

    private String extractLabel(PlPgSqlParser.Opt_labelContext ctx) {
        if (ctx == null || ctx.IDENTIFIER() == null) return null;
        return ctx.IDENTIFIER().getText().toLowerCase();
    }

    // =====================================================================
    // DECLARE section
    // =====================================================================

    private List<PlPgSqlDecl> visitDeclSection(PlPgSqlParser.Decl_sectionContext ctx) {
        List<PlPgSqlDecl> result = new ArrayList<>();
        for (var decl : ctx.decl()) {
            result.add(visitDecl(decl));
        }
        return result;
    }

    private PlPgSqlDecl visitDecl(PlPgSqlParser.DeclContext ctx) {
        if (ctx.var_decl() != null) return visitVarDecl(ctx.var_decl());
        if (ctx.alias_decl() != null) return visitAliasDecl(ctx.alias_decl());
        if (ctx.cursor_decl() != null) return visitCursorDecl(ctx.cursor_decl());
        throw new IllegalStateException("Unknown declaration type: " + getText(ctx));
    }

    private PlPgSqlDecl visitVarDecl(PlPgSqlParser.Var_declContext ctx) {
        String name = ctx.IDENTIFIER().getText().toLowerCase();
        boolean constant = ctx.KW_CONSTANT() != null;

        var dataType = ctx.data_type();
        if (dataType.KW_RECORD() != null) {
            return new PlPgSqlDecl.RecordDecl(name);
        }

        String typeName = getText(dataType);
        String copyType = null;
        if (ctx.copy_type_suffix() != null) {
            if (ctx.copy_type_suffix().KW_ROWTYPE() != null) {
                copyType = "%ROWTYPE";
            } else {
                copyType = "%TYPE";
            }
        }

        boolean notNull = ctx.KW_NOT() != null;
        String defaultExpr = null;
        if (ctx.var_default() != null) {
            defaultExpr = getText(ctx.var_default().expr_until_semi());
        }

        return new PlPgSqlDecl.VarDecl(name, typeName, constant, notNull, defaultExpr, copyType);
    }

    private PlPgSqlDecl visitAliasDecl(PlPgSqlParser.Alias_declContext ctx) {
        String name = ctx.IDENTIFIER(0).getText().toLowerCase();
        String target;
        if (ctx.PARAM_REF() != null) {
            target = ctx.PARAM_REF().getText();
        } else {
            target = ctx.IDENTIFIER(1).getText().toLowerCase();
        }
        return new PlPgSqlDecl.AliasDecl(name, target);
    }

    private PlPgSqlDecl visitCursorDecl(PlPgSqlParser.Cursor_declContext ctx) {
        String name = ctx.IDENTIFIER().getText().toLowerCase();
        Boolean scroll = null;
        if (ctx.scroll_option() != null) {
            scroll = ctx.scroll_option().KW_NO() == null;
        }
        List<PlPgSqlDecl.CursorParam> params = List.of();
        if (ctx.cursor_arg_list() != null) {
            params = new ArrayList<>();
            for (var arg : ctx.cursor_arg_list().cursor_arg()) {
                params.add(new PlPgSqlDecl.CursorParam(
                        arg.IDENTIFIER().getText().toLowerCase(),
                        getText(arg.data_type())));
            }
        }
        String querySql = getText(ctx.sql_until_semi());
        return new PlPgSqlDecl.CursorDecl(name, params, querySql, scroll);
    }

    // =====================================================================
    // Statement list
    // =====================================================================

    private List<PlPgSqlStmt> visitStmtList(PlPgSqlParser.Stmt_listContext ctx) {
        List<PlPgSqlStmt> result = new ArrayList<>();
        // Iterate children in source order to preserve interleaving of
        // labeled_stmt and stmt alternatives (they share a single ( ... )* block).
        for (int i = 0; i < ctx.getChildCount(); i++) {
            var child = ctx.getChild(i);
            if (child instanceof PlPgSqlParser.Labeled_stmtContext labeled) {
                PlPgSqlStmt stmt = visitStmtNode(labeled.stmt());
                String label = labeled.IDENTIFIER().getText().toLowerCase();
                result.add(applyLabel(stmt, label));
            } else if (child instanceof PlPgSqlParser.StmtContext stmtCtx) {
                result.add(visitStmtNode(stmtCtx));
            }
            // Skip SEMI tokens and other terminals
        }
        return result;
    }

    private PlPgSqlStmt applyLabel(PlPgSqlStmt stmt, String label) {
        return switch (stmt) {
            case PlPgSqlStmt.LoopStmt s -> new PlPgSqlStmt.LoopStmt(label, s.body());
            case PlPgSqlStmt.WhileLoopStmt s -> new PlPgSqlStmt.WhileLoopStmt(label, s.condition(), s.body());
            case PlPgSqlStmt.ForIntLoopStmt s -> new PlPgSqlStmt.ForIntLoopStmt(label, s.varName(), s.lower(), s.upper(), s.step(), s.reverse(), s.body());
            case PlPgSqlStmt.ForQueryLoopStmt s -> new PlPgSqlStmt.ForQueryLoopStmt(label, s.targetVars(), s.sql(), s.body());
            case PlPgSqlStmt.ForCursorLoopStmt s -> new PlPgSqlStmt.ForCursorLoopStmt(label, s.recordVar(), s.cursorName(), s.cursorArgs(), s.body());
            case PlPgSqlStmt.ForeachArrayLoopStmt s -> new PlPgSqlStmt.ForeachArrayLoopStmt(label, s.varName(), s.slice(), s.arrayExpr(), s.body());
            case PlPgSqlStmt.BlockStmt s -> {
                var body = s.body();
                yield new PlPgSqlStmt.BlockStmt(new PlPgSqlBody(label, body.decls(), body.stmts(), body.handlers()));
            }
            default -> stmt;
        };
    }

    private PlPgSqlStmt visitStmtNode(PlPgSqlParser.StmtContext ctx) {
        if (ctx.block_stmt() != null) return visitBlockStmt(ctx.block_stmt());
        if (ctx.if_stmt() != null) return visitIfStmt(ctx.if_stmt());
        if (ctx.case_stmt() != null) return visitCaseStmt(ctx.case_stmt());
        if (ctx.loop_stmt() != null) return visitLoopStmt(ctx.loop_stmt());
        if (ctx.while_stmt() != null) return visitWhileStmt(ctx.while_stmt());
        if (ctx.for_stmt() != null) return visitForStmt(ctx.for_stmt());
        if (ctx.foreach_stmt() != null) return visitForeachStmt(ctx.foreach_stmt());
        if (ctx.return_stmt() != null) return visitReturnStmt(ctx.return_stmt());
        if (ctx.raise_stmt() != null) return visitRaiseStmt(ctx.raise_stmt());
        if (ctx.perform_stmt() != null) return visitPerformStmt(ctx.perform_stmt());
        if (ctx.execute_stmt() != null) return visitExecuteStmt(ctx.execute_stmt());
        if (ctx.get_diagnostics_stmt() != null) return visitGetDiagnosticsStmt(ctx.get_diagnostics_stmt());
        if (ctx.assert_stmt() != null) return visitAssertStmt(ctx.assert_stmt());
        if (ctx.null_stmt() != null) return new PlPgSqlStmt.NullStmt();
        if (ctx.exit_stmt() != null) return visitExitStmt(ctx.exit_stmt());
        if (ctx.continue_stmt() != null) return visitContinueStmt(ctx.continue_stmt());
        if (ctx.open_cursor_stmt() != null) return visitOpenCursorStmt(ctx.open_cursor_stmt());
        if (ctx.fetch_stmt() != null) return visitFetchStmt(ctx.fetch_stmt());
        if (ctx.move_stmt() != null) return visitMoveStmt(ctx.move_stmt());
        if (ctx.close_stmt() != null) return visitCloseStmt(ctx.close_stmt());
        if (ctx.sql_stmt() != null) return visitSqlStmt(ctx.sql_stmt());
        if (ctx.assign_stmt() != null) return visitAssignStmt(ctx.assign_stmt());
        throw new IllegalStateException("Unknown statement type: " + getText(ctx));
    }

    // =====================================================================
    // Individual statement visitors
    // =====================================================================

    private PlPgSqlStmt visitBlockStmt(PlPgSqlParser.Block_stmtContext ctx) {
        String label = extractLabel(ctx.opt_label());
        List<PlPgSqlDecl> decls = ctx.decl_section() != null
                ? visitDeclSection(ctx.decl_section()) : List.of();
        List<PlPgSqlStmt> stmts = visitStmtList(ctx.stmt_list());
        List<ExceptionHandler> handlers = ctx.exception_section() != null
                ? visitExceptionSection(ctx.exception_section()) : List.of();
        return new PlPgSqlStmt.BlockStmt(new PlPgSqlBody(label, decls, stmts, handlers));
    }

    private PlPgSqlStmt visitAssignStmt(PlPgSqlParser.Assign_stmtContext ctx) {
        String target = getText(ctx.assign_target());
        String expr = getText(ctx.expr_until_semi());
        return new PlPgSqlStmt.AssignStmt(target, expr);
    }

    private PlPgSqlStmt visitIfStmt(PlPgSqlParser.If_stmtContext ctx) {
        String condition = getText(ctx.expr_until_then());
        List<PlPgSqlStmt> thenBody = visitStmtList(ctx.stmt_list());

        List<ElsifClause> elsifs = new ArrayList<>();
        for (var elsif : ctx.elsif_clause()) {
            elsifs.add(new ElsifClause(
                    getText(elsif.expr_until_then()),
                    visitStmtList(elsif.stmt_list())));
        }

        List<PlPgSqlStmt> elseBody = List.of();
        if (ctx.else_clause() != null) {
            elseBody = visitStmtList(ctx.else_clause().stmt_list());
        }

        return new PlPgSqlStmt.IfStmt(condition, thenBody, elsifs, elseBody);
    }

    private PlPgSqlStmt visitCaseStmt(PlPgSqlParser.Case_stmtContext ctx) {
        String operand = ctx.expr_until_when() != null ? getText(ctx.expr_until_when()) : null;

        List<CaseWhenClause> whens = new ArrayList<>();
        for (var when : ctx.case_when_clause()) {
            List<String> exprs = new ArrayList<>();
            for (var expr : when.expr_list_until_then().expr_until_comma_or_then()) {
                exprs.add(getText(expr));
            }
            whens.add(new CaseWhenClause(exprs, visitStmtList(when.stmt_list())));
        }

        List<PlPgSqlStmt> elseBody = List.of();
        if (ctx.else_clause() != null) {
            elseBody = visitStmtList(ctx.else_clause().stmt_list());
        }

        return new PlPgSqlStmt.CaseStmt(operand, whens, elseBody);
    }

    private PlPgSqlStmt visitLoopStmt(PlPgSqlParser.Loop_stmtContext ctx) {
        return new PlPgSqlStmt.LoopStmt(null, visitStmtList(ctx.stmt_list()));
    }

    private PlPgSqlStmt visitWhileStmt(PlPgSqlParser.While_stmtContext ctx) {
        return new PlPgSqlStmt.WhileLoopStmt(null,
                getText(ctx.expr_until_loop()), visitStmtList(ctx.stmt_list()));
    }

    // Combined FOR statement with labeled alternatives
    private PlPgSqlStmt visitForStmt(PlPgSqlParser.For_stmtContext ctx) {
        if (ctx instanceof PlPgSqlParser.ForiStmtContext fori) {
            return visitForiStmt(fori);
        } else if (ctx instanceof PlPgSqlParser.ForqStmtContext forq) {
            return visitForqStmt(forq);
        } else if (ctx instanceof PlPgSqlParser.ForcStmtContext forc) {
            return visitForcStmt(forc);
        }
        throw new IllegalStateException("Unknown FOR statement variant: " + getText(ctx));
    }

    private PlPgSqlStmt visitForiStmt(PlPgSqlParser.ForiStmtContext ctx) {
        boolean reverse = ctx.KW_REVERSE() != null;
        String step = ctx.expr_until_loop() != null ? getText(ctx.expr_until_loop()) : null;
        return new PlPgSqlStmt.ForIntLoopStmt(null,
                ctx.IDENTIFIER().getText().toLowerCase(),
                getText(ctx.expr_until_dotdot()),
                getText(ctx.expr_until_loop_or_by()),
                step, reverse, visitStmtList(ctx.stmt_list()));
    }

    private PlPgSqlStmt visitForqStmt(PlPgSqlParser.ForqStmtContext ctx) {
        List<String> targets = new ArrayList<>();
        for (var id : ctx.for_target().IDENTIFIER()) {
            targets.add(id.getText().toLowerCase());
        }
        StringBuilder sql = new StringBuilder();
        if (ctx.KW_SELECT() != null) sql.append("SELECT ");
        else if (ctx.KW_WITH() != null) sql.append("WITH ");
        else if (ctx.KW_INSERT() != null) sql.append("INSERT ");
        else if (ctx.KW_UPDATE() != null) sql.append("UPDATE ");
        else if (ctx.KW_DELETE() != null) sql.append("DELETE ");
        else if (ctx.LPAREN() != null) sql.append("(");
        sql.append(getText(ctx.sql_until_loop()));
        return new PlPgSqlStmt.ForQueryLoopStmt(null, targets,
                sql.toString().trim(), visitStmtList(ctx.stmt_list()));
    }

    private PlPgSqlStmt visitForcStmt(PlPgSqlParser.ForcStmtContext ctx) {
        String recordVar = ctx.IDENTIFIER(0).getText().toLowerCase();
        String cursorName = ctx.IDENTIFIER(1).getText().toLowerCase();
        List<String> args = List.of();
        if (ctx.expr_list_until_rparen() != null) {
            args = new ArrayList<>();
            for (var expr : ctx.expr_list_until_rparen().expr_until_comma_rparen()) {
                args.add(getText(expr));
            }
        }
        return new PlPgSqlStmt.ForCursorLoopStmt(null, recordVar, cursorName,
                args, visitStmtList(ctx.stmt_list()));
    }

    private PlPgSqlStmt visitForeachStmt(PlPgSqlParser.Foreach_stmtContext ctx) {
        Integer slice = ctx.INTEGER_LITERAL() != null
                ? Integer.parseInt(ctx.INTEGER_LITERAL().getText()) : null;
        return new PlPgSqlStmt.ForeachArrayLoopStmt(null,
                ctx.IDENTIFIER().getText().toLowerCase(),
                slice, getText(ctx.expr_until_loop()),
                visitStmtList(ctx.stmt_list()));
    }

    // Combined RETURN statement with labeled alternatives
    private PlPgSqlStmt visitReturnStmt(PlPgSqlParser.Return_stmtContext ctx) {
        if (ctx instanceof PlPgSqlParser.ReturnNextStmtContext next) {
            return new PlPgSqlStmt.ReturnNextStmt(getText(next.expr_until_semi()));
        }
        if (ctx instanceof PlPgSqlParser.ReturnQueryExecuteStmtContext rqe) {
            String dynSql = getText(rqe.expr_until_semi_or_using());
            List<String> using = List.of();
            if (rqe.expr_list_until_semi() != null) {
                using = extractExprList(rqe.expr_list_until_semi());
            }
            return new PlPgSqlStmt.ReturnQueryStmt(dynSql, true, using);
        }
        if (ctx instanceof PlPgSqlParser.ReturnQuerySqlStmtContext rqs) {
            return new PlPgSqlStmt.ReturnQueryStmt(
                    getText(rqs.sql_until_semi()), false, List.of());
        }
        if (ctx instanceof PlPgSqlParser.ReturnPlainStmtContext plain) {
            String expr = plain.expr_until_semi() != null ? getText(plain.expr_until_semi()) : null;
            return new PlPgSqlStmt.ReturnStmt(expr);
        }
        throw new IllegalStateException("Unknown RETURN variant: " + getText(ctx));
    }

    private PlPgSqlStmt visitRaiseStmt(PlPgSqlParser.Raise_stmtContext ctx) {
        RaiseLevel level = RaiseLevel.EXCEPTION; // default
        if (ctx.raise_level() != null) {
            level = switch (ctx.raise_level().getStart().getType()) {
                case PlPgSqlParser.KW_DEBUG -> RaiseLevel.DEBUG;
                case PlPgSqlParser.KW_LOG -> RaiseLevel.LOG;
                case PlPgSqlParser.KW_INFO -> RaiseLevel.INFO;
                case PlPgSqlParser.KW_NOTICE -> RaiseLevel.NOTICE;
                case PlPgSqlParser.KW_WARNING -> RaiseLevel.WARNING;
                case PlPgSqlParser.KW_EXCEPTION -> RaiseLevel.EXCEPTION;
                default -> RaiseLevel.EXCEPTION;
            };
        }

        String format = null;
        String conditionName = null;
        String sqlstate = null;

        if (ctx.raise_format_or_condname() != null) {
            var fmtCtx = ctx.raise_format_or_condname();
            if (fmtCtx.STRING_LITERAL() != null && fmtCtx.KW_SQLSTATE() == null) {
                format = unquoteString(fmtCtx.STRING_LITERAL().getText());
            } else if (fmtCtx.IDENTIFIER() != null) {
                conditionName = fmtCtx.IDENTIFIER().getText().toLowerCase();
            } else if (fmtCtx.KW_SQLSTATE() != null) {
                sqlstate = unquoteString(fmtCtx.STRING_LITERAL().getText());
            }
        }

        List<String> formatArgs = new ArrayList<>();
        for (var arg : ctx.expr_until_comma_or_semi()) {
            formatArgs.add(getText(arg));
        }

        List<RaiseOption> options = List.of();
        if (ctx.raise_using() != null) {
            options = new ArrayList<>();
            for (var opt : ctx.raise_using().raise_option()) {
                options.add(new RaiseOption(
                        getText(opt.raise_option_name()).toUpperCase(),
                        getText(opt.expr_until_comma_or_semi())));
            }
        }

        return new PlPgSqlStmt.RaiseStmt(level, format, conditionName, sqlstate,
                formatArgs, options);
    }

    private PlPgSqlStmt visitPerformStmt(PlPgSqlParser.Perform_stmtContext ctx) {
        return new PlPgSqlStmt.PerformStmt(getText(ctx.sql_until_semi()));
    }

    private PlPgSqlStmt visitExecuteStmt(PlPgSqlParser.Execute_stmtContext ctx) {
        String dynSql = getText(ctx.expr_until_semi_into_using());

        List<String> intoVars = List.of();
        boolean strict = false;
        if (ctx.KW_INTO() != null) {
            strict = ctx.KW_STRICT() != null;
            intoVars = new ArrayList<>();
            for (var qn : ctx.into_target().qualified_name()) {
                intoVars.add(getText(qn));
            }
        }

        List<String> using = List.of();
        if (ctx.KW_USING() != null && ctx.expr_list_until_semi() != null) {
            using = extractExprList(ctx.expr_list_until_semi());
        }

        return new PlPgSqlStmt.ExecuteStmt(dynSql, intoVars, strict, using);
    }

    private PlPgSqlStmt visitSqlStmt(PlPgSqlParser.Sql_stmtContext ctx) {
        StringBuilder sql = new StringBuilder();
        if (ctx.KW_SELECT() != null) sql.append("SELECT ");
        else if (ctx.KW_WITH() != null) sql.append("WITH ");
        else if (ctx.KW_INSERT() != null) sql.append("INSERT ");
        else if (ctx.KW_UPDATE() != null) sql.append("UPDATE ");
        else if (ctx.KW_DELETE() != null) sql.append("DELETE ");
        sql.append(getText(ctx.sql_until_semi()));
        String rawSql = sql.toString().trim();

        // Extract INTO [STRICT] target_list from the SQL text for PL/pgSQL variable binding
        // Pattern: ... INTO [STRICT] var1, var2 ... (FROM|WHERE|GROUP|ORDER|LIMIT|HAVING|;|$)
        List<String> intoVars = List.of();
        boolean strict = false;
        // Match against a copy with string literals blanked out, so INTO inside quotes is not matched
        java.util.regex.Matcher m = INTO_PATTERN.matcher(blankStringLiterals(rawSql));
        if (m.find()) {
            strict = m.group(1) != null;
            String varsPart = m.group(2).trim();
            String[] vars = varsPart.split("\\s*,\\s*");
            intoVars = new ArrayList<>();
            for (String v : vars) {
                String trimmed = v.trim();
                if (!trimmed.isEmpty()) intoVars.add(trimmed);
            }
            // Remove INTO clause from the SQL so pg_query doesn't see it
            rawSql = rawSql.substring(0, m.start()) + " " + rawSql.substring(m.end());
            rawSql = rawSql.replaceAll("\\s+", " ").trim();
        }
        return new PlPgSqlStmt.SqlStmt(rawSql, intoVars, strict);
    }

    // Matches INTO [STRICT] vars FROM/WHERE/GROUP/ORDER/LIMIT/HAVING/UNION/EXCEPT/INTERSECT/end
    private static final java.util.regex.Pattern INTO_PATTERN = java.util.regex.Pattern.compile(
            "\\bINTO\\s+(STRICT\\s+)?([^;]+?)\\s+(?=FROM\\b|WHERE\\b|GROUP\\b|ORDER\\b|LIMIT\\b|HAVING\\b|UNION\\b|EXCEPT\\b|INTERSECT\\b|$)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Replace string literal contents with spaces so regex won't match inside quotes. */
    private static String blankStringLiterals(String sql) {
        char[] buf = sql.toCharArray();
        boolean inQuote = false;
        for (int i = 0; i < buf.length; i++) {
            if (buf[i] == '\'' && !inQuote) { inQuote = true; continue; }
            if (buf[i] == '\'' && inQuote) {
                if (i + 1 < buf.length && buf[i + 1] == '\'') { buf[i] = ' '; buf[++i] = ' '; continue; }
                inQuote = false; continue;
            }
            if (inQuote) buf[i] = ' ';
        }
        return new String(buf);
    }

    private PlPgSqlStmt visitGetDiagnosticsStmt(PlPgSqlParser.Get_diagnostics_stmtContext ctx) {
        boolean stacked = ctx.KW_STACKED() != null;
        List<PlPgSqlStmt.DiagnosticsItem> items = new ArrayList<>();
        for (var item : ctx.diagnostics_item()) {
            items.add(new PlPgSqlStmt.DiagnosticsItem(
                    item.IDENTIFIER().getText().toLowerCase(),
                    getText(item.diagnostics_tag())));
        }
        return new PlPgSqlStmt.GetDiagnosticsStmt(stacked, items);
    }

    private PlPgSqlStmt visitAssertStmt(PlPgSqlParser.Assert_stmtContext ctx) {
        String condition = getText(ctx.expr_until_comma_or_semi());
        String message = ctx.expr_until_semi() != null ? getText(ctx.expr_until_semi()) : null;
        return new PlPgSqlStmt.AssertStmt(condition, message);
    }

    private PlPgSqlStmt visitExitStmt(PlPgSqlParser.Exit_stmtContext ctx) {
        String label = ctx.IDENTIFIER() != null ? ctx.IDENTIFIER().getText().toLowerCase() : null;
        String when = ctx.expr_until_semi() != null ? getText(ctx.expr_until_semi()) : null;
        return new PlPgSqlStmt.ExitStmt(label, when);
    }

    private PlPgSqlStmt visitContinueStmt(PlPgSqlParser.Continue_stmtContext ctx) {
        String label = ctx.IDENTIFIER() != null ? ctx.IDENTIFIER().getText().toLowerCase() : null;
        String when = ctx.expr_until_semi() != null ? getText(ctx.expr_until_semi()) : null;
        return new PlPgSqlStmt.ContinueStmt(label, when);
    }

    // =====================================================================
    // Cursor statements
    // =====================================================================

    private PlPgSqlStmt visitOpenCursorStmt(PlPgSqlParser.Open_cursor_stmtContext ctx) {
        String name = ctx.IDENTIFIER().getText().toLowerCase();
        var tail = ctx.open_cursor_tail();

        if (tail.KW_EXECUTE() != null) {
            String dynSql = getText(tail.expr_until_semi_or_using());
            List<String> using = List.of();
            if (tail.expr_list_until_semi() != null) {
                using = extractExprList(tail.expr_list_until_semi());
            }
            return new PlPgSqlStmt.OpenCursorForExecuteStmt(name, dynSql, using);
        }

        if (tail.sql_until_semi() != null) {
            Boolean scroll = null;
            if (tail.KW_SCROLL() != null) {
                scroll = tail.KW_NO() == null;
            }
            return new PlPgSqlStmt.OpenCursorForQueryStmt(name,
                    getText(tail.sql_until_semi()), scroll);
        }

        if (tail.expr_list_until_rparen() != null) {
            List<String> args = new ArrayList<>();
            for (var expr : tail.expr_list_until_rparen().expr_until_comma_rparen()) {
                args.add(getText(expr));
            }
            return new PlPgSqlStmt.OpenCursorStmt(name, args);
        }

        return new PlPgSqlStmt.OpenCursorStmt(name, List.of());
    }

    private PlPgSqlStmt visitFetchStmt(PlPgSqlParser.Fetch_stmtContext ctx) {
        var dir = parseFetchDirection(ctx.fetch_direction());
        String cursorName = ctx.IDENTIFIER().getText().toLowerCase();
        List<String> targets = new ArrayList<>();
        for (var qn : ctx.into_target().qualified_name()) {
            targets.add(getText(qn));
        }
        return new PlPgSqlStmt.FetchStmt(dir.direction(), dir.expr(), cursorName, targets);
    }

    private PlPgSqlStmt visitMoveStmt(PlPgSqlParser.Move_stmtContext ctx) {
        var dir = parseFetchDirection(ctx.fetch_direction());
        String cursorName = ctx.IDENTIFIER().getText().toLowerCase();
        return new PlPgSqlStmt.MoveCursorStmt(dir.direction(), dir.expr(), cursorName);
    }

    private PlPgSqlStmt visitCloseStmt(PlPgSqlParser.Close_stmtContext ctx) {
        return new PlPgSqlStmt.CloseCursorStmt(ctx.IDENTIFIER().getText().toLowerCase());
    }

    private record DirResult(FetchDirection direction, String expr) {}

    private DirResult parseFetchDirection(PlPgSqlParser.Fetch_directionContext ctx) {
        if (ctx == null) return new DirResult(FetchDirection.NEXT, null);

        if (ctx.KW_NEXT() != null) return new DirResult(FetchDirection.NEXT, null);
        if (ctx.KW_PRIOR() != null) return new DirResult(FetchDirection.PRIOR, null);
        if (ctx.KW_FIRST() != null) return new DirResult(FetchDirection.FIRST, null);
        if (ctx.KW_LAST() != null) return new DirResult(FetchDirection.LAST, null);

        if (ctx.KW_ABSOLUTE() != null) {
            return new DirResult(FetchDirection.ABSOLUTE, getText(ctx.expr_until_from_or_in()));
        }
        if (ctx.KW_RELATIVE() != null) {
            return new DirResult(FetchDirection.RELATIVE, getText(ctx.expr_until_from_or_in()));
        }
        if (ctx.KW_FORWARD() != null) {
            if (ctx.KW_ALL() != null) return new DirResult(FetchDirection.FORWARD_ALL, null);
            return new DirResult(FetchDirection.FORWARD, getText(ctx.expr_until_from_or_in()));
        }
        if (ctx.KW_BACKWARD() != null) {
            if (ctx.KW_ALL() != null) return new DirResult(FetchDirection.BACKWARD_ALL, null);
            return new DirResult(FetchDirection.BACKWARD, getText(ctx.expr_until_from_or_in()));
        }

        return new DirResult(FetchDirection.NEXT, null);
    }

    // =====================================================================
    // EXCEPTION section
    // =====================================================================

    private List<ExceptionHandler> visitExceptionSection(PlPgSqlParser.Exception_sectionContext ctx) {
        List<ExceptionHandler> result = new ArrayList<>();
        for (var handler : ctx.exception_handler()) {
            List<String> conditions = new ArrayList<>();
            for (var cond : handler.exception_condition()) {
                if (cond.KW_OTHERS() != null) {
                    conditions.add("others");
                } else if (cond.KW_SQLSTATE() != null) {
                    conditions.add("SQLSTATE " + unquoteString(cond.STRING_LITERAL().getText()));
                } else {
                    conditions.add(getText(cond.qualified_name()).toLowerCase());
                }
            }
            result.add(new ExceptionHandler(conditions, visitStmtList(handler.stmt_list())));
        }
        return result;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private String getText(ParserRuleContext ctx) {
        if (ctx == null) return null;
        Token start = ctx.getStart();
        Token stop = ctx.getStop();
        if (start == null || stop == null) return ctx.getText();
        return start.getInputStream()
                .getText(org.antlr.v4.runtime.misc.Interval.of(
                        start.getStartIndex(), stop.getStopIndex()));
    }

    private List<String> extractExprList(PlPgSqlParser.Expr_list_until_semiContext ctx) {
        List<String> result = new ArrayList<>();
        for (var expr : ctx.expr_until_comma_or_semi()) {
            result.add(getText(expr));
        }
        return result;
    }

    private String unquoteString(String s) {
        if (s.startsWith("'") && s.endsWith("'")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("''", "'");
    }
}
