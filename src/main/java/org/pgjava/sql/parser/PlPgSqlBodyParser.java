package org.pgjava.sql.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.pgjava.sql.ast.plpgsql.PlPgSqlBody;
import org.pgjava.sql.parser.antlr.PlPgSqlLexer;
import org.pgjava.sql.parser.antlr.PlPgSqlParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for parsing PL/pgSQL function bodies.
 *
 * <p>Takes the raw body string (extracted from between $$ delimiters or single quotes)
 * and produces a {@link PlPgSqlBody} AST. SQL fragments within the body are captured
 * as raw text strings, to be re-parsed via {@link ParserProvider} at execution time.
 */
public final class PlPgSqlBodyParser {

    private PlPgSqlBodyParser() {}

    /**
     * Parse a PL/pgSQL function body into an AST.
     *
     * @param body  the raw PL/pgSQL body text (content between $$ delimiters)
     * @return      parsed AST
     * @throws ParseException if the body contains syntax errors
     */
    public static PlPgSqlBody parse(String body) throws ParseException {
        var errors = new ErrorCollector();

        var charStream = CharStreams.fromString(body);
        var lexer = new PlPgSqlLexer(charStream);
        lexer.removeErrorListeners();
        lexer.addErrorListener(errors);

        var tokens = new CommonTokenStream(lexer);
        var parser = new PlPgSqlParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errors);

        var tree = parser.body();

        if (!errors.messages.isEmpty()) {
            var first = errors.messages.get(0);
            throw new ParseException(first.message(), first.line(), first.col(), first.token());
        }

        return new PlPgSqlAstBuilder().buildBody(tree);
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
