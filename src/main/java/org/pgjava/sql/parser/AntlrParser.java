package org.pgjava.sql.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.pgjava.sql.ast.Stmt;
import org.pgjava.sql.parser.antlr.PostgreSQLLexer;
import org.pgjava.sql.parser.antlr.PostgreSQLParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps the ANTLR4 PostgreSQL grammar and drives the {@link AntlrAstBuilder} visitor.
 * Used as the fallback parser on platforms where pg_query_java JNI is unavailable.
 */
public final class AntlrParser {

    /** Parse {@code sql} and return the list of statements. Never returns null. */
    public static List<Stmt> parse(String sql) throws ParseException {
        var errors = new ErrorCollector();

        var charStream = CharStreams.fromString(sql);
        var lexer = new PostgreSQLLexer(charStream);
        lexer.removeErrorListeners();
        lexer.addErrorListener(errors);

        var tokens = new CommonTokenStream(lexer);
        var parser = new PostgreSQLParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errors);

        var tree = parser.root();

        if (!errors.messages.isEmpty()) {
            var first = errors.messages.get(0);
            throw new ParseException(first.message(), first.line(), first.col(), first.token());
        }

        return new AntlrAstBuilder().buildScript(tree);
    }

    // -------------------------------------------------------------------------

    record SyntaxError(String message, int line, int col, String token) {}

    static final class ErrorCollector extends BaseErrorListener {
        final List<SyntaxError> messages = new ArrayList<>();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine,
                                String msg, RecognitionException e) {
            String token = offendingSymbol == null ? "<EOF>" : offendingSymbol.toString();
            messages.add(new SyntaxError(msg, line, charPositionInLine, token));
        }
    }
}
