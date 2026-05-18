/*
 * PostgreSQL grammar. MIT License.
 * Copyright (c) 2021-2023, Oleksii Kovalov (Oleksii.Kovalov@outlook.com).
 */
package org.pgjava.sql.parser.antlr;

import org.antlr.v4.runtime.ANTLRErrorListener;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ProxyErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.dfa.DFA;

import java.util.BitSet;

public class ParserDispatchingErrorListener implements ANTLRErrorListener {
    private final Parser _parent;

    public ParserDispatchingErrorListener(Parser parent) {
        _parent = parent;
    }

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                            int line, int charPositionInLine, String msg, RecognitionException e) {
        new ProxyErrorListener(_parent.getErrorListeners())
                .syntaxError(recognizer, offendingSymbol, line, charPositionInLine, msg, e);
    }

    @Override
    public void reportAmbiguity(Parser recognizer, DFA dfa, int startIndex, int stopIndex,
                                boolean exact, BitSet ambigAlts, ATNConfigSet configs) {
        new ProxyErrorListener(_parent.getErrorListeners())
                .reportAmbiguity(recognizer, dfa, startIndex, stopIndex, exact, ambigAlts, configs);
    }

    @Override
    public void reportAttemptingFullContext(Parser recognizer, DFA dfa, int startIndex, int stopIndex,
                                            BitSet conflictingAlts, ATNConfigSet configs) {
        new ProxyErrorListener(_parent.getErrorListeners())
                .reportAttemptingFullContext(recognizer, dfa, startIndex, stopIndex, conflictingAlts, configs);
    }

    @Override
    public void reportContextSensitivity(Parser recognizer, DFA dfa, int startIndex, int stopIndex,
                                         int prediction, ATNConfigSet configs) {
        new ProxyErrorListener(_parent.getErrorListeners())
                .reportContextSensitivity(recognizer, dfa, startIndex, stopIndex, prediction, configs);
    }
}
