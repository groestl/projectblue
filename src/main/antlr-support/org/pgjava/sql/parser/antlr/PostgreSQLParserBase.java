/*
 * PostgreSQL grammar. MIT License.
 * Copyright (c) 2021-2023, Oleksii Kovalov (Oleksii.Kovalov@outlook.com).
 */
package org.pgjava.sql.parser.antlr;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.List;

public abstract class PostgreSQLParserBase extends Parser {

    protected PostgreSQLParserBase(TokenStream input) {
        super(input);
    }

    ParserRuleContext GetParsedSqlTree(String script, int line) {
        PostgreSQLParser ph = GetPostgreSQLParser(script);
        return ph.root();
    }

    public void ParseRoutineBody() {
        PostgreSQLParser.Createfunc_opt_listContext ctx =
                (PostgreSQLParser.Createfunc_opt_listContext) this.getContext();
        String lang = null;
        for (PostgreSQLParser.Createfunc_opt_itemContext coi : ctx.createfunc_opt_item()) {
            if (coi.LANGUAGE() != null) {
                if (coi.nonreservedword_or_sconst() != null
                        && coi.nonreservedword_or_sconst().nonreservedword() != null
                        && coi.nonreservedword_or_sconst().nonreservedword().identifier() != null
                        && coi.nonreservedword_or_sconst().nonreservedword().identifier().Identifier() != null) {
                    lang = coi.nonreservedword_or_sconst().nonreservedword()
                            .identifier().Identifier().getText();
                    break;
                }
            }
        }
        if (lang == null) return;
        PostgreSQLParser.Createfunc_opt_itemContext func_as = null;
        for (PostgreSQLParser.Createfunc_opt_itemContext a : ctx.createfunc_opt_item()) {
            if (a.func_as() != null) {
                func_as = a;
                break;
            }
        }
        if (func_as != null) {
            GetRoutineBodyString(func_as.func_as().sconst(0));
        }
    }

    private String TrimQuotes(String s) {
        return (s == null || s.isEmpty()) ? s : s.substring(1, s.length() - 1);
    }

    public String unquote(String s) {
        int slength = s.length();
        StringBuilder r = new StringBuilder(slength);
        int i = 0;
        while (i < slength) {
            char c = s.charAt(i);
            r.append(c);
            if (c == '\'' && i < slength - 1 && s.charAt(i + 1) == '\'') i++;
            i++;
        }
        return r.toString();
    }

    public String GetRoutineBodyString(PostgreSQLParser.SconstContext rule) {
        PostgreSQLParser.AnysconstContext anysconst = rule.anysconst();
        TerminalNode stringConst = anysconst.StringConstant();
        if (stringConst != null) return unquote(TrimQuotes(stringConst.getText()));
        TerminalNode unicodeConst = anysconst.UnicodeEscapeStringConstant();
        if (unicodeConst != null) return TrimQuotes(unicodeConst.getText());
        TerminalNode escapeConst = anysconst.EscapeStringConstant();
        if (escapeConst != null) return TrimQuotes(escapeConst.getText());
        StringBuilder result = new StringBuilder();
        List<TerminalNode> dollartext = anysconst.DollarText();
        for (TerminalNode s : dollartext) result.append(s.getText());
        return result.toString();
    }

    public PostgreSQLParser GetPostgreSQLParser(String script) {
        var charStream = CharStreams.fromString(script);
        Lexer lexer = new PostgreSQLLexer(charStream);
        var tokens = new CommonTokenStream(lexer);
        PostgreSQLParser parser = new PostgreSQLParser(tokens);
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        var lexerListener = new LexerDispatchingErrorListener(
                (Lexer) ((CommonTokenStream) this.getInputStream()).getTokenSource());
        var parserListener = new ParserDispatchingErrorListener(this);
        lexer.addErrorListener(lexerListener);
        parser.addErrorListener(parserListener);
        return parser;
    }

    public boolean OnlyAcceptableOps() {
        var c = ((CommonTokenStream) this.getInputStream()).LT(1);
        String text = c.getText();
        return text.equals("!") || text.equals("!!") || text.equals("!=-");
    }
}
