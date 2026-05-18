package org.pgjava.sql.parser;

import java.sql.SQLException;

/**
 * Thrown when SQL text fails to parse. Carries position information (line, column, near-token)
 * for error reporting. SQLSTATE 42601 = syntax_error.
 */
public class ParseException extends SQLException {
    private final int line;
    private final int column;
    private final String nearToken;

    public ParseException(String message, int line, int column, String nearToken) {
        super(buildMessage(message, line, column, nearToken), "42601");
        this.line = line;
        this.column = column;
        this.nearToken = nearToken;
    }

    public ParseException(String message) {
        this(message, 0, 0, null);
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public String getNearToken() {
        return nearToken;
    }

    private static String buildMessage(String msg, int line, int col, String near) {
        var sb = new StringBuilder(msg);
        if (line > 0) {
            sb.append(" at line ").append(line).append(", column ").append(col);
        }
        if (near != null && !near.isBlank()) {
            sb.append(" near \"").append(near).append('"');
        }
        return sb.toString();
    }
}
