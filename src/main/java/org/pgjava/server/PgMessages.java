package org.pgjava.server;

import java.util.Map;

/**
 * Frontend (client→server) message types decoded from the PostgreSQL wire protocol.
 *
 * <p>Startup-phase messages have no type byte; regular messages have a 1-byte type prefix.
 * Both are decoded by {@link PgWireDecoder} and dispatched by {@link PgConnectionHandler}.
 */
final class PgMessages {

    private PgMessages() {}

    sealed interface FrontendMessage
            permits SslRequest, StartupMessage, CancelRequest,
                    Query, Parse, Bind, Execute, Describe, Sync, Flush, Close, Terminate, Unknown {}

    // ---- Startup-phase ----

    /** Client is asking for SSL; we always respond 'N'. */
    record SslRequest() implements FrontendMessage {}

    /** Protocol 3.0 startup message with connection parameters (user, database, etc.). */
    record StartupMessage(Map<String, String> params) implements FrontendMessage {}

    /** Cancel request for a running query. We ignore these for now. */
    record CancelRequest(int pid, int secret) implements FrontendMessage {}

    // ---- Regular frontend messages ----

    /** Simple Query: execute SQL text, return full results, then ReadyForQuery. */
    record Query(String sql) implements FrontendMessage {}

    /**
     * Extended Query — Parse: prepare a named (or unnamed) statement.
     * paramOids may be empty; server infers types.
     */
    record Parse(String name, String query, int[] paramOids) implements FrontendMessage {}

    /**
     * Extended Query — Bind: bind parameters to a statement, creating a portal.
     * All parameters are received as text strings (format=0) for simplicity.
     */
    record Bind(String portal, String statement, String[] params,
                int[] paramFormats, byte[][] rawBinaryParams) implements FrontendMessage {}

    /** Extended Query — Execute: run a portal, optionally limiting rows. */
    record Execute(String portal, int maxRows) implements FrontendMessage {}

    /** Extended Query — Describe: ask for RowDescription/ParameterDescription. */
    record Describe(char type, String name) implements FrontendMessage {}  // type='S' or 'P'

    /** Extended Query — Sync: flush pipeline, send ReadyForQuery. */
    record Sync() implements FrontendMessage {}

    /** Extended Query — Flush: flush pending output without ReadyForQuery. */
    record Flush() implements FrontendMessage {}

    /** Extended Query — Close: close a named statement or portal. */
    record Close(char type, String name) implements FrontendMessage {}  // type='S' or 'P'

    /** Connection termination. */
    record Terminate() implements FrontendMessage {}

    /** Unknown message type — logged and ignored. */
    record Unknown(byte type) implements FrontendMessage {}
}
