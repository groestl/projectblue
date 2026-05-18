/*
 * PostgreSQL grammar. MIT License.
 * Copyright (c) 2021-2023, Oleksii Kovalov (Oleksii.Kovalov@outlook.com).
 */
package org.pgjava.sql.parser.antlr;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;

import java.util.Stack;

public abstract class PostgreSQLLexerBase extends Lexer {
    protected final Stack<String> tags = new Stack<>();

    protected PostgreSQLLexerBase(CharStream input) {
        super(input);
    }

    public void PushTag() {
        tags.push(getText());
    }

    public boolean IsTag() {
        return getText().equals(tags.peek());
    }

    public void PopTag() {
        tags.pop();
    }

    public void UnterminatedBlockCommentDebugAssert() {
        // no-op
    }

    public boolean CheckLaMinus() {
        return getInputStream().LA(1) != '-';
    }

    public boolean CheckLaStar() {
        return getInputStream().LA(1) != '*';
    }

    public boolean CharIsLetter() {
        return Character.isLetter(getInputStream().LA(-1));
    }

    public void HandleNumericFail() {
        getInputStream().seek(getInputStream().index() - 2);
        setType(PostgreSQLLexer.Integral);
    }

    public void HandleLessLessGreaterGreater() {
        if (getText().equals("<<")) setType(PostgreSQLLexer.LESS_LESS);
        if (getText().equals(">>")) setType(PostgreSQLLexer.GREATER_GREATER);
    }

    public boolean CheckIfUtf32Letter() {
        int codePoint = getInputStream().LA(-2) << 8 + getInputStream().LA(-1);
        char[] c;
        if (codePoint < 0x10000) {
            c = new char[]{(char) codePoint};
        } else {
            codePoint -= 0x10000;
            c = new char[]{
                (char) (codePoint / 0x400 + 0xd800),
                (char) (codePoint % 0x400 + 0xdc00)
            };
        }
        return Character.isLetter(c[0]);
    }

    public boolean IsSemiColon() {
        return ';' == (char) getInputStream().LA(1);
    }
}
