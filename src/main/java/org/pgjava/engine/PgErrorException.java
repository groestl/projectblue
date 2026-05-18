package org.pgjava.engine;

import java.sql.SQLException;

/**
 * SQLException subclass carrying PostgreSQL-compatible error fields:
 * detail, hint, and position. These map to the 'D', 'H', and 'P'
 * fields in PG's ErrorResponse wire protocol message.
 *
 * <p>Message text matches PostgreSQL's wording so diagnostic tools
 * see identical output.
 */
public class PgErrorException extends SQLException {

    private final String detail;
    private final String hint;
    private final int position; // 1-based character offset in query, or -1 if absent

    private PgErrorException(String message, String sqlState, String detail, String hint, int position) {
        super(message, sqlState);
        this.detail = detail;
        this.hint = hint;
        this.position = position;
    }

    private PgErrorException(String message, String sqlState, String detail, String hint, int position, Throwable cause) {
        super(message, sqlState);
        if (cause != null) initCause(cause);
        this.detail = detail;
        this.hint = hint;
        this.position = position;
    }

    public String detail()   { return detail; }
    public String hint()     { return hint; }
    public int    position() { return position; }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder error(String sqlState, String message) {
        return new Builder(sqlState, message);
    }

    public static final class Builder {
        private final String sqlState;
        private final String message;
        private String detail;
        private String hint;
        private int position = -1;
        private Throwable cause;

        private Builder(String sqlState, String message) {
            this.sqlState = sqlState;
            this.message = message;
        }

        public Builder detail(String d)   { this.detail = d;   return this; }
        public Builder hint(String h)     { this.hint = h;     return this; }
        public Builder position(int p)    { this.position = p; return this; }
        public Builder cause(Throwable c) { this.cause = c;    return this; }

        public PgErrorException build() {
            return new PgErrorException(message, sqlState, detail, hint, position, cause);
        }
    }
}
