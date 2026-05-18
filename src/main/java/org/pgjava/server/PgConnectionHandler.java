package org.pgjava.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.pgjava.engine.ColumnMeta;
import org.pgjava.engine.PgNotification;
import org.pgjava.engine.QueryResult;
import org.pgjava.engine.Session;
import org.pgjava.jdbc.PgJavaCluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the PostgreSQL wire protocol v3 for a single connection.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>SSL negotiation (respond 'N').</li>
 *   <li>Startup handshake: AuthenticationOk → ParameterStatus × N → BackendKeyData → ReadyForQuery.</li>
 *   <li>Simple Query loop: Q → execute → RowDescription + DataRow(s) + CommandComplete → ReadyForQuery.</li>
 *   <li>Extended Query: P/B/D/E messages → ParseComplete/BindComplete/RowDescription/DataRow/CommandComplete → Sync → ReadyForQuery.</li>
 *   <li>X (Terminate) → close channel.</li>
 * </ol>
 *
 * <p>All data is sent in text format (format code 0), which avoids binary encoding
 * complexity while being perfectly correct for all PostgreSQL client libraries.
 */
class PgConnectionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(PgConnectionHandler.class);

    /** Per-connection process ID sent in BackendKeyData. */
    private static final java.util.concurrent.atomic.AtomicInteger PID_GEN = new java.util.concurrent.atomic.AtomicInteger(1);
    private static final int FAKE_SECRET = 0;

    private final int connectionPid = PID_GEN.getAndIncrement();
    private final PgJavaCluster cluster;

    /** Session is null until after auth handshake. */
    private Session session;

    /** Extended Query state — named prepared statements (name → SQL + param OIDs). */
    private record PreparedStmt(String sql, int[] paramOids) {}
    private final Map<String, PreparedStmt> preparedStatements = new HashMap<>();

    /** Extended Query state — named portals (portal → bound SQL after param substitution). */
    private final Map<String, String> portals = new HashMap<>();

    /**
     * Per-portal pre-executed QueryResult cache.
     *
     * <p>When Describe 'P' is received for a SELECT portal, we execute the portal
     * immediately to obtain the RowDescription. The result is stored here so that
     * the subsequent Execute message can stream the rows without re-executing.
     * The cache entry is consumed (removed) when Execute runs.
     */
    private final Map<String, QueryResult> portalCache = new HashMap<>();

    /** True when we've seen at least one error since the last Sync. */
    private boolean extendedQueryError = false;

    PgConnectionHandler(PgJavaCluster cluster) {
        this.cluster = cluster;
    }

    // =========================================================================
    // Inbound message dispatch

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof PgMessages.FrontendMessage fm)) {
            log.warn("unexpected message type: {}", msg.getClass());
            return;
        }
        switch (fm) {
            case PgMessages.SslRequest ignored -> handleSslRequest(ctx);
            case PgMessages.StartupMessage sm  -> handleStartup(ctx, sm);
            case PgMessages.CancelRequest ignored -> { /* ignore — no cancellation */ }
            case PgMessages.Query q            -> handleSimpleQuery(ctx, q);
            case PgMessages.Parse p            -> handleParse(ctx, p);
            case PgMessages.Bind b             -> handleBind(ctx, b);
            case PgMessages.Describe d         -> handleDescribe(ctx, d);
            case PgMessages.Execute e          -> handleExecute(ctx, e);
            case PgMessages.Sync ignored       -> handleSync(ctx);
            case PgMessages.Flush ignored      -> ctx.flush(); // flush pending output without ReadyForQuery
            case PgMessages.Close c            -> handleClose(ctx, c);
            case PgMessages.Terminate ignored  -> ctx.close();
            case PgMessages.Unknown u          -> log.debug("unknown frontend message type: {}", (char)(u.type() & 0xFF));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("unhandled error on connection, closing", cause);
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    // =========================================================================
    // Startup

    private void handleSslRequest(ChannelHandlerContext ctx) {
        // Respond with 'N' — no SSL support. Single byte, no length prefix.
        ByteBuf buf = ctx.alloc().buffer(1);
        buf.writeByte('N');
        ctx.writeAndFlush(buf);
        log.debug("SSLRequest → N");
    }

    private void handleStartup(ChannelHandlerContext ctx, PgMessages.StartupMessage sm) {
        Map<String, String> params = sm.params();

        String user   = params.getOrDefault("user",     "postgres");
        String dbName = params.getOrDefault("database", user);  // default: user name
        String appName = params.getOrDefault("application_name", "");

        log.debug("startup: user={}, database={}, app={}", user, dbName, appName);

        try {
            var db = cluster.database(dbName);
            session = db.openSession();
        } catch (Exception e) {
            sendError(ctx, "3D000", "database \"" + dbName + "\" does not exist");
            ctx.close();
            return;
        }

        // Authentication OK
        ByteBuf buf = ctx.alloc().buffer(256);
        writeAuthOk(buf);

        // ParameterStatus — must match PostgreSQL wire protocol spec
        writeParamStatus(buf, "server_version",               "15.0");
        writeParamStatus(buf, "client_encoding",              "UTF8");
        writeParamStatus(buf, "server_encoding",              "UTF8");
        writeParamStatus(buf, "DateStyle",                    "ISO, MDY");
        writeParamStatus(buf, "IntervalStyle",                "postgres");
        writeParamStatus(buf, "TimeZone",                     "UTC");
        writeParamStatus(buf, "integer_datetimes",            "on");
        writeParamStatus(buf, "standard_conforming_strings",  "on");
        writeParamStatus(buf, "application_name",             appName);
        writeParamStatus(buf, "is_superuser",                 "on");
        writeParamStatus(buf, "session_authorization",        user);
        writeParamStatus(buf, "in_hot_standby",               "off");

        // BackendKeyData
        writeBackendKeyData(buf, connectionPid, FAKE_SECRET);

        // ReadyForQuery
        writeReadyForQuery(buf, 'I');

        ctx.writeAndFlush(buf);
    }

    // =========================================================================
    // Simple Query

    private void handleSimpleQuery(ChannelHandlerContext ctx, PgMessages.Query q) {
        String sql = q.sql().trim();
        if (sql.isEmpty()) {
            ByteBuf buf = ctx.alloc().buffer(32);
            writeEmptyQueryResponse(buf);
            flushNotifications(buf);
            writeReadyForQuery(buf, txStatus());
            ctx.writeAndFlush(buf);
            return;
        }

        ByteBuf buf = ctx.alloc().buffer(1024);
        try {
            QueryResult result = session.execute(sql);
            writeQueryResult(buf, result, sql);
        } catch (SQLException e) {
            buf.clear();
            sendErrorToBuf(buf, e);
        } catch (UnsupportedOperationException e) {
            buf.clear();
            sendErrorToBuf(buf, "0A000", "pgjava: " + e.getMessage());
        }
        flushNotifications(buf);
        writeReadyForQuery(buf, txStatus());
        ctx.writeAndFlush(buf);
    }

    // =========================================================================
    // Extended Query — Parse

    private void handleParse(ChannelHandlerContext ctx, PgMessages.Parse p) {
        if (extendedQueryError) return;  // skip until Sync
        preparedStatements.put(p.name(), new PreparedStmt(p.query(), p.paramOids()));
        ByteBuf buf = ctx.alloc().buffer(8);
        writeParseComplete(buf);
        ctx.writeAndFlush(buf);
    }

    // =========================================================================
    // Extended Query — Bind

    private void handleBind(ChannelHandlerContext ctx, PgMessages.Bind b) {
        if (extendedQueryError) return;

        PreparedStmt ps = preparedStatements.get(b.statement());
        if (ps == null) {
            if (b.statement().isEmpty()) {
                sendError(ctx, "26000", "unnamed prepared statement not found");
            } else {
                sendError(ctx, "26000", "prepared statement \"" + b.statement() + "\" does not exist");
            }
            extendedQueryError = true;
            return;
        }

        // Re-interpret binary params using known OIDs (e.g. float4/float8)
        String[] params = b.params().clone();
        for (int k = 0; k < params.length; k++) {
            if (b.paramFormats()[k] == 1 && b.rawBinaryParams()[k] != null
                    && k < ps.paramOids().length) {
                params[k] = reinterpretBinaryParam(
                        ps.paramOids()[k], b.rawBinaryParams()[k]);
            }
        }

        // Substitute $N parameters with escaped literals
        String bound = substituteParams(ps.sql(), params);
        portals.put(b.portal(), bound);

        ByteBuf buf = ctx.alloc().buffer(8);
        writeBindComplete(buf);
        ctx.writeAndFlush(buf);
    }

    // =========================================================================
    // Extended Query — Describe

    private void handleDescribe(ChannelHandlerContext ctx, PgMessages.Describe d) {
        if (extendedQueryError) return;

        if (d.type() == 'S') {
            // Describe statement — send ParameterDescription + RowDescription (for SELECT)
            // or NoData (for DML).
            //
            // pgjdbc's extended-query flow is two-phase:
            //   1. Parse + Describe('S') + Sync  → needs RowDescription here for SELECT
            //   2. Bind + Execute + Sync          → sends DataRows only (no 2nd RowDescription)
            //
            // To get the column types we execute the query with all $N replaced by NULL.
            // This is safe for SELECT (read-only, no side effects).
            PreparedStmt ps = preparedStatements.get(d.name());
            String sql = ps != null ? ps.sql() : null;
            ByteBuf buf = ctx.alloc().buffer(256);
            if (sql != null && looksLikeQuery(sql)) {
                String probeQuery = replaceParamsWithNull(sql);
                try {
                    QueryResult probe = session.execute(probeQuery);
                    writeParameterDescription(buf, new int[0]);
                    if (!probe.columns().isEmpty()) {
                        writeRowDescription(buf, probe.columns());
                    } else {
                        writeNoData(buf);
                    }
                } catch (SQLException | RuntimeException ex) {
                    // Can't probe schema — fall back to NoData; pgjdbc will use Describe 'P'
                    buf.clear();
                    writeParameterDescription(buf, new int[0]);
                    writeNoData(buf);
                }
            } else {
                writeParameterDescription(buf, new int[0]);
                writeNoData(buf);
            }
            ctx.writeAndFlush(buf);
        } else {
            // Describe portal — pgjdbc needs RowDescription before DataRows arrive.
            // Execute the portal now to get the schema; cache the full result for Execute.
            String sql = portals.get(d.name());
            if (sql == null) {
                sendError(ctx, "34000",
                        d.name().isEmpty() ? "portal \"\" not found"
                                           : "portal \"" + d.name() + "\" does not exist");
                extendedQueryError = true;
                return;
            }
            ByteBuf buf = ctx.alloc().buffer(256);
            if (looksLikeQuery(sql)) {
                try {
                    QueryResult result = session.execute(sql);
                    portalCache.put(d.name(), result);
                    if (!result.columns().isEmpty()) {
                        writeRowDescription(buf, result.columns());
                    } else {
                        writeNoData(buf);
                    }
                } catch (SQLException e) {
                    buf.clear();
                    sendErrorToBuf(buf, e);
                    extendedQueryError = true;
                }
            } else {
                writeNoData(buf);
            }
            ctx.writeAndFlush(buf);
        }
    }

    // =========================================================================
    // Extended Query — Execute

    private void handleExecute(ChannelHandlerContext ctx, PgMessages.Execute e) {
        if (extendedQueryError) return;

        String sql = portals.get(e.portal());
        if (sql == null) {
            if (e.portal().isEmpty()) {
                sendError(ctx, "34000", "portal \"\" not found");
            } else {
                sendError(ctx, "34000", "portal \"" + e.portal() + "\" does not exist");
            }
            extendedQueryError = true;
            return;
        }

        ByteBuf buf = ctx.alloc().buffer(1024);
        try {
            // Check if Describe 'P' already ran and cached the result.
            // If so, use the cache — RowDescription was already sent by Describe.
            QueryResult cached = portalCache.remove(e.portal());
            QueryResult result = cached != null ? cached : session.execute(sql);
            writeExecuteResult(buf, result, sql);
        } catch (SQLException ex) {
            buf.clear();
            sendErrorToBuf(buf, ex);
            extendedQueryError = true;
        } catch (UnsupportedOperationException ex) {
            buf.clear();
            sendErrorToBuf(buf, "0A000", "pgjava: " + ex.getMessage());
            extendedQueryError = true;
        }
        ctx.writeAndFlush(buf);
    }

    // =========================================================================
    // Extended Query — Sync

    private void handleSync(ChannelHandlerContext ctx) {
        extendedQueryError = false;
        ByteBuf buf = ctx.alloc().buffer(8);
        flushNotifications(buf);
        writeReadyForQuery(buf, txStatus());
        ctx.writeAndFlush(buf);
    }

    // =========================================================================
    // Extended Query — Close

    private void handleClose(ChannelHandlerContext ctx, PgMessages.Close c) {
        if (c.type() == 'S') {
            preparedStatements.remove(c.name());
        } else {
            portals.remove(c.name());
            portalCache.remove(c.name());
        }
        ByteBuf buf = ctx.alloc().buffer(8);
        writeCloseComplete(buf);
        ctx.writeAndFlush(buf);
    }

    // =========================================================================
    // Query result serialization

    /**
     * Simple Query: sends RowDescription + DataRow(s) + CommandComplete (full response).
     * RowDescription is included because Simple Query protocol doesn't have a separate Describe step.
     */
    private void writeQueryResult(ByteBuf buf, QueryResult result, String sql) {
        if (result.isQuery()) {
            if (!result.columns().isEmpty()) {
                writeRowDescription(buf, result.columns());
            }
            for (Object[] row : result.rows()) {
                writeDataRow(buf, row);
            }
            writeCommandComplete(buf, "SELECT " + result.rows().size());
        } else {
            writeCommandComplete(buf, commandTag(sql, result.updateCount()));
        }
    }

    /**
     * Extended Query Execute: sends DataRow(s) + CommandComplete only.
     * RowDescription was already sent in the Describe 'P' response — do NOT repeat it.
     * For DML (non-query), just sends CommandComplete.
     */
    private void writeExecuteResult(ByteBuf buf, QueryResult result, String sql) {
        if (result.isQuery()) {
            for (Object[] row : result.rows()) {
                writeDataRow(buf, row);
            }
            writeCommandComplete(buf, "SELECT " + result.rows().size());
        } else {
            writeCommandComplete(buf, commandTag(sql, result.updateCount()));
        }
    }

    // =========================================================================
    // Wire encoding helpers

    private static void writeAuthOk(ByteBuf buf) {
        buf.writeByte('R');
        buf.writeInt(8);   // length includes itself
        buf.writeInt(0);   // AuthenticationOk
    }

    private static void writeParamStatus(ByteBuf buf, String name, String value) {
        byte[] nameB  = name.getBytes(StandardCharsets.UTF_8);
        byte[] valueB = value.getBytes(StandardCharsets.UTF_8);
        int len = 4 + nameB.length + 1 + valueB.length + 1;
        buf.writeByte('S');
        buf.writeInt(len);
        buf.writeBytes(nameB);
        buf.writeByte(0);
        buf.writeBytes(valueB);
        buf.writeByte(0);
    }

    private static void writeBackendKeyData(ByteBuf buf, int pid, int secret) {
        buf.writeByte('K');
        buf.writeInt(12);
        buf.writeInt(pid);
        buf.writeInt(secret);
    }

    private static void writeReadyForQuery(ByteBuf buf, char status) {
        buf.writeByte('Z');
        buf.writeInt(5);
        buf.writeByte(status);
    }

    /**
     * Drain any pending LISTEN/NOTIFY notifications for this session and write
     * {@code NotificationResponse} ('A') messages into {@code buf}.
     * Must be called immediately before every {@code writeReadyForQuery}.
     */
    private void flushNotifications(ByteBuf buf) {
        if (session == null) return;
        for (PgNotification n : session.drainNotifications()) {
            writeNotificationResponse(buf, n.pid(), n.channel(), n.payload());
        }
    }

    /**
     * Write a single {@code NotificationResponse} ('A') message.
     * Format: {@code 'A'[int32 len][int32 pid][cstring channel][cstring payload]}
     */
    private static void writeNotificationResponse(ByteBuf buf, int pid, String channel, String payload) {
        byte[] chBytes  = channel.getBytes(StandardCharsets.UTF_8);
        byte[] payBytes = (payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8);
        int len = 4 + 4 + chBytes.length + 1 + payBytes.length + 1;
        buf.writeByte('A');
        buf.writeInt(len);
        buf.writeInt(pid);
        buf.writeBytes(chBytes); buf.writeByte(0);
        buf.writeBytes(payBytes); buf.writeByte(0);
    }

    private static void writeEmptyQueryResponse(ByteBuf buf) {
        buf.writeByte('I');
        buf.writeInt(4);
    }

    private static void writeRowDescription(ByteBuf buf, List<ColumnMeta> cols) {
        // Pre-calculate length
        int len = 4 + 2; // length field + numFields
        byte[][] nameBytes = new byte[cols.size()][];
        for (int i = 0; i < cols.size(); i++) {
            nameBytes[i] = cols.get(i).label().getBytes(StandardCharsets.UTF_8);
            len += nameBytes[i].length + 1 + 4 + 2 + 4 + 2 + 4 + 2; // name\0 + 6 fields
        }
        buf.writeByte('T');
        buf.writeInt(len);
        buf.writeShort(cols.size());
        for (int i = 0; i < cols.size(); i++) {
            buf.writeBytes(nameBytes[i]);
            buf.writeByte(0);
            int[] typeInfo = pgTypeInfo(cols.get(i));
            buf.writeInt(0);            // tableOid
            buf.writeShort(0);          // column attribute number
            buf.writeInt(typeInfo[0]);  // type OID
            buf.writeShort(typeInfo[1]);// type size
            buf.writeInt(-1);           // type modifier
            buf.writeShort(0);          // format code: 0 = text
        }
    }

    private static void writeDataRow(ByteBuf buf, Object[] values) {
        // Pre-encode values
        byte[][] encoded = new byte[values.length][];
        int len = 4 + 2; // length + numCols
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                len += 4; // -1 marker
            } else {
                encoded[i] = toPgText(values[i]).getBytes(StandardCharsets.UTF_8);
                len += 4 + encoded[i].length;
            }
        }
        buf.writeByte('D');
        buf.writeInt(len);
        buf.writeShort(values.length);
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                buf.writeInt(-1);
            } else {
                buf.writeInt(encoded[i].length);
                buf.writeBytes(encoded[i]);
            }
        }
    }

    private static void writeCommandComplete(ByteBuf buf, String tag) {
        byte[] tagBytes = tag.getBytes(StandardCharsets.UTF_8);
        buf.writeByte('C');
        buf.writeInt(4 + tagBytes.length + 1);
        buf.writeBytes(tagBytes);
        buf.writeByte(0);
    }

    private static void writeParseComplete(ByteBuf buf) {
        buf.writeByte('1');
        buf.writeInt(4);
    }

    private static void writeBindComplete(ByteBuf buf) {
        buf.writeByte('2');
        buf.writeInt(4);
    }

    private static void writeCloseComplete(ByteBuf buf) {
        buf.writeByte('3');
        buf.writeInt(4);
    }

    private static void writeNoData(ByteBuf buf) {
        buf.writeByte('n');
        buf.writeInt(4);
    }

    private static void writeParameterDescription(ByteBuf buf, int[] oids) {
        int len = 4 + 2 + oids.length * 4;
        buf.writeByte('t');
        buf.writeInt(len);
        buf.writeShort(oids.length);
        for (int oid : oids) buf.writeInt(oid);
    }

    // =========================================================================
    // Error responses

    private void sendError(ChannelHandlerContext ctx, String sqlState, String message) {
        ByteBuf buf = ctx.alloc().buffer(128);
        sendErrorToBuf(buf, sqlState, message);
        ctx.writeAndFlush(buf);
    }

    private static void sendErrorToBuf(ByteBuf buf, String sqlState, String message) {
        sendErrorToBuf(buf, sqlState, message, null, null, -1);
    }

    private static void sendErrorToBuf(ByteBuf buf, SQLException ex) {
        if (ex instanceof org.pgjava.engine.PgErrorException pe) {
            sendErrorToBuf(buf, pe.getSQLState(), pe.getMessage(), pe.detail(), pe.hint(), pe.position());
        } else {
            sendErrorToBuf(buf, ex.getSQLState(), ex.getMessage());
        }
    }

    private static void sendErrorToBuf(ByteBuf buf, String sqlState, String message,
                                       String detail, String hint, int position) {
        if (sqlState == null || sqlState.length() != 5) sqlState = "XX000";
        byte[] sev  = "ERROR".getBytes(StandardCharsets.UTF_8);
        byte[] code = sqlState.getBytes(StandardCharsets.UTF_8);
        byte[] msg  = (message != null ? message : "error").getBytes(StandardCharsets.UTF_8);
        byte[] det  = detail   != null ? detail.getBytes(StandardCharsets.UTF_8)              : null;
        byte[] hnt  = hint     != null ? hint.getBytes(StandardCharsets.UTF_8)                : null;
        byte[] pos  = position >= 0    ? String.valueOf(position).getBytes(StandardCharsets.UTF_8) : null;

        int len = 4
                + 1 + sev.length  + 1   // 'S' severity
                + 1 + sev.length  + 1   // 'V' severity (non-localized, PG 9.6+)
                + 1 + code.length + 1   // 'C' SQLSTATE
                + 1 + msg.length  + 1   // 'M' message
                + 1;                    // terminator \0
        if (det != null) len += 1 + det.length + 1;  // 'D' detail
        if (hnt != null) len += 1 + hnt.length + 1;  // 'H' hint
        if (pos != null) len += 1 + pos.length + 1;  // 'P' position

        buf.writeByte('E');
        buf.writeInt(len);
        buf.writeByte('S'); buf.writeBytes(sev);  buf.writeByte(0);
        buf.writeByte('V'); buf.writeBytes(sev);  buf.writeByte(0);
        buf.writeByte('C'); buf.writeBytes(code); buf.writeByte(0);
        buf.writeByte('M'); buf.writeBytes(msg);  buf.writeByte(0);
        if (det != null) { buf.writeByte('D'); buf.writeBytes(det); buf.writeByte(0); }
        if (hnt != null) { buf.writeByte('H'); buf.writeBytes(hnt); buf.writeByte(0); }
        if (pos != null) { buf.writeByte('P'); buf.writeBytes(pos); buf.writeByte(0); }
        buf.writeByte(0);
    }

    // =========================================================================
    // Helpers

    /** Return the ReadyForQuery status byte for the current session state. */
    private char txStatus() {
        if (session == null) return 'I';
        if (session.isTransactionFailed()) return 'E';
        return session.inTransaction() ? 'T' : 'I';
    }

    /**
     * Returns true if the SQL looks like a query that produces rows (SELECT, VALUES, WITH…SELECT).
     * Used by Describe 'P' to decide whether to pre-execute and return RowDescription.
     */
    private static boolean looksLikeQuery(String sql) {
        String upper = sql.stripLeading().toUpperCase();
        return upper.startsWith("SELECT")
            || upper.startsWith("VALUES")
            || upper.startsWith("WITH")
            || upper.startsWith("TABLE")
            || upper.startsWith("SHOW")
            || upper.startsWith("EXPLAIN");
    }

    /**
     * Build a CommandComplete tag from the SQL first keyword and row count.
     * INSERT 0 N, UPDATE N, DELETE N, SELECT N, CREATE TABLE, etc.
     */
    private static String commandTag(String sql, int rowCount) {
        String upper = sql.stripLeading().toUpperCase();
        if (upper.startsWith("INSERT"))   return "INSERT 0 " + rowCount;
        if (upper.startsWith("UPDATE"))   return "UPDATE " + rowCount;
        if (upper.startsWith("DELETE"))   return "DELETE " + rowCount;
        if (upper.startsWith("SELECT"))   return "SELECT " + rowCount;
        if (upper.startsWith("CREATE TABLE"))   return "CREATE TABLE";
        if (upper.startsWith("DROP TABLE"))     return "DROP TABLE";
        if (upper.startsWith("ALTER TABLE"))    return "ALTER TABLE";
        if (upper.startsWith("CREATE INDEX"))   return "CREATE INDEX";
        if (upper.startsWith("DROP INDEX"))     return "DROP INDEX";
        if (upper.startsWith("CREATE SCHEMA"))  return "CREATE SCHEMA";
        if (upper.startsWith("DROP SCHEMA"))    return "DROP SCHEMA";
        if (upper.startsWith("CREATE VIEW"))    return "CREATE VIEW";
        if (upper.startsWith("DROP VIEW"))      return "DROP VIEW";
        if (upper.startsWith("CREATE SEQUENCE")) return "CREATE SEQUENCE";
        if (upper.startsWith("DROP SEQUENCE"))   return "DROP SEQUENCE";
        if (upper.startsWith("TRUNCATE"))       return "TRUNCATE TABLE";
        if (upper.startsWith("BEGIN"))          return "BEGIN";
        if (upper.startsWith("COMMIT"))         return "COMMIT";
        if (upper.startsWith("ROLLBACK"))       return "ROLLBACK";
        if (upper.startsWith("SAVEPOINT"))      return "SAVEPOINT";
        if (upper.startsWith("SET"))            return "SET";
        if (upper.startsWith("SHOW"))           return "SHOW";
        // Generic fallback: first word
        String[] parts = upper.split("\\s+", 2);
        return parts[0];
    }

    /**
     * Replace all $N parameter placeholders with NULL.
     * Used in Describe 'S' to execute a schema-probe query without real parameters.
     */
    private static String replaceParamsWithNull(String sql) {
        // Replace highest-numbered params first to avoid $1 matching part of $10
        return sql.replaceAll("\\$\\d+", "NULL");
    }

    /**
     * Substitute $N parameter placeholders with properly-escaped literals.
     * Handles $1–$N in reverse order to avoid $1 matching $10, $11 etc.
     */
    private static String substituteParams(String sql, String[] params) {
        if (params == null || params.length == 0) return sql;
        for (int i = params.length; i >= 1; i--) {
            String placeholder = "$" + i;
            String replacement = params[i - 1] == null ? "NULL"
                    : "'" + params[i - 1].replace("'", "''") + "'";
            sql = sql.replace(placeholder, replacement);
        }
        return sql;
    }

    /**
     * Re-interpret a binary parameter value using the known type OID.
     * The decoder's default treats 4-byte as int4 and 8-byte as int8,
     * but float4 (OID 700) and float8 (OID 701) have the same sizes
     * and need IEEE 754 interpretation instead.
     */
    private static String reinterpretBinaryParam(int oid, byte[] raw) {
        if (oid == 700 && raw.length == 4) {
            // float4: 4-byte IEEE 754
            int bits = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16)
                     | ((raw[2] & 0xFF) << 8)  |  (raw[3] & 0xFF);
            return String.valueOf(Float.intBitsToFloat(bits));
        }
        if (oid == 701 && raw.length == 8) {
            // float8: 8-byte IEEE 754
            long bits = ((long)(raw[0] & 0xFF) << 56) | ((long)(raw[1] & 0xFF) << 48)
                      | ((long)(raw[2] & 0xFF) << 40) | ((long)(raw[3] & 0xFF) << 32)
                      | ((long)(raw[4] & 0xFF) << 24) | ((long)(raw[5] & 0xFF) << 16)
                      | ((long)(raw[6] & 0xFF) << 8)  |  (long)(raw[7] & 0xFF);
            return String.valueOf(Double.longBitsToDouble(bits));
        }
        // For other types, the decoder's default text conversion is fine
        if (raw.length == 1) return raw[0] == 0 ? "false" : "true";
        if (raw.length == 2) return String.valueOf(
                (short)(((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF)));
        if (raw.length == 4) return String.valueOf(
                ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16)
              | ((raw[2] & 0xFF) << 8)  |  (raw[3] & 0xFF));
        if (raw.length == 8) {
            long v = ((long)(raw[0] & 0xFF) << 56) | ((long)(raw[1] & 0xFF) << 48)
                   | ((long)(raw[2] & 0xFF) << 40) | ((long)(raw[3] & 0xFF) << 32)
                   | ((long)(raw[4] & 0xFF) << 24) | ((long)(raw[5] & 0xFF) << 16)
                   | ((long)(raw[6] & 0xFF) << 8)  |  (long)(raw[7] & 0xFF);
            return String.valueOf(v);
        }
        return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Replace 'T' with space and ensure seconds are present in timestamp strings. */
    private static String formatTimestamp(String iso) {
        String s = iso.replace('T', ' ');
        // "2024-03-15 10:30" → "2024-03-15 10:30:00" (pgjdbc requires seconds)
        // Detect: date part is 10 chars, then space, then time part
        int space = s.indexOf(' ');
        if (space >= 0) {
            String timePart = s.substring(space + 1);
            // Check if there's an offset at the end
            int offsetStart = -1;
            for (int i = timePart.length() - 1; i >= 0; i--) {
                char c = timePart.charAt(i);
                if (c == '+' || c == '-') { offsetStart = i; break; }
                if (c != ':' && !Character.isDigit(c)) break;
            }
            String time, offset;
            if (offsetStart > 0) {
                time = timePart.substring(0, offsetStart);
                offset = timePart.substring(offsetStart);
            } else {
                time = timePart;
                offset = "";
            }
            time = ensureSeconds(time);
            s = s.substring(0, space + 1) + time + offset;
        }
        return s;
    }

    /** Ensure HH:MM:SS format (add :00 seconds if missing). */
    private static String ensureSeconds(String time) {
        // Count colons to determine if seconds are present
        long colonCount = time.chars().filter(c -> c == ':').count();
        if (colonCount < 2) return time + ":00";
        return time;
    }

    /** Convert a Java value to PostgreSQL text wire format. */
    private static String toPgText(Object v) {
        if (v instanceof Boolean b) return b ? "t" : "f";
        if (v instanceof byte[] ba) {
            // bytea hex format: \x followed by hex pairs
            StringBuilder sb = new StringBuilder(2 + ba.length * 2);
            sb.append("\\x");
            for (byte b2 : ba) sb.append(String.format("%02x", b2 & 0xFF));
            return sb.toString();
        }
        if (v instanceof java.time.LocalDateTime ldt) {
            // PG format: "2024-01-15 10:30:00" (space not T, always include seconds)
            return formatTimestamp(ldt.toString());
        }
        if (v instanceof java.time.OffsetDateTime odt) {
            // PG format: "2024-01-15 10:30:00+00"
            String s = formatTimestamp(odt.toString());
            // Normalize "+00:00" → "+00", "-05:00" → "-05"
            if (s.endsWith(":00") && s.length() > 3) {
                int lastColon = s.lastIndexOf(':');
                if (lastColon > 0 && (s.charAt(lastColon - 3) == '+' || s.charAt(lastColon - 3) == '-'))
                    s = s.substring(0, lastColon);
            }
            return s;
        }
        if (v instanceof java.time.LocalTime lt) {
            return ensureSeconds(lt.toString());
        }
        if (v instanceof java.time.OffsetTime ot) {
            String s = ot.toString();
            // Separate time part from offset, ensure seconds
            int sign = Math.max(s.lastIndexOf('+'), s.lastIndexOf('-'));
            if (sign > 0) {
                String timePart = ensureSeconds(s.substring(0, sign));
                return timePart + s.substring(sign);
            }
            return ensureSeconds(s);
        }
        if (v instanceof java.util.BitSet bs) {
            // PG bit string format: "10110"
            if (bs.isEmpty()) return "0";
            int len = bs.length();
            StringBuilder sb = new StringBuilder(len);
            for (int i = 0; i < len; i++) sb.append(bs.get(i) ? '1' : '0');
            return sb.toString();
        }
        if (v instanceof java.util.List<?> list) {
            // PG array format: {elem1,elem2,...}
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                Object elem = list.get(i);
                if (elem == null) {
                    sb.append("NULL");
                } else if (elem instanceof String s) {
                    // Quote strings in arrays
                    sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
                } else {
                    sb.append(toPgText(elem));
                }
            }
            sb.append('}');
            return sb.toString();
        }
        return v.toString();
    }

    /** Return [pgTypeOid, typeLen] for a column. */
    private static int[] pgTypeInfo(ColumnMeta col) {
        // For Types.OTHER, disambiguate by typeName
        if (col.sqlType() == Types.OTHER && col.typeName() != null) {
            return switch (col.typeName().toLowerCase()) {
                case "uuid"     -> new int[]{2950, 16};
                case "interval" -> new int[]{1186, 16};
                case "json"     -> new int[]{114,  -1};
                case "jsonb"    -> new int[]{3802, -1};
                case "int4range"  -> new int[]{3904, -1};
                case "int8range"  -> new int[]{3926, -1};
                case "numrange"   -> new int[]{3906, -1};
                case "tsrange"    -> new int[]{3908, -1};
                case "tstzrange"  -> new int[]{3910, -1};
                case "daterange"  -> new int[]{3912, -1};
                default         -> new int[]{25, -1};
            };
        }
        return switch (col.sqlType()) {
            case Types.INTEGER   -> new int[]{23,   4};
            case Types.SMALLINT  -> new int[]{21,   2};
            case Types.BIGINT    -> new int[]{20,   8};
            case Types.REAL      -> new int[]{700,  4};
            case Types.FLOAT, Types.DOUBLE -> new int[]{701, 8};
            case Types.BOOLEAN   -> new int[]{16,   1};
            case Types.DATE      -> new int[]{1082, 4};
            case Types.TIME      -> new int[]{1083, 8};
            case Types.TIME_WITH_TIMEZONE -> new int[]{1266, 12};
            case Types.TIMESTAMP -> new int[]{1114, 8};
            case Types.TIMESTAMP_WITH_TIMEZONE -> new int[]{1184, 8};
            case Types.NUMERIC   -> new int[]{1700,-1};
            case Types.VARBINARY, Types.BINARY, Types.LONGVARBINARY -> new int[]{17, -1};
            case Types.BIT       -> new int[]{1560,-1};
            case Types.CHAR      -> new int[]{1042,-1}; // bpchar
            case Types.VARCHAR   -> new int[]{1043,-1};
            case Types.TINYINT   -> new int[]{21,   2}; // map to int2
            default              -> new int[]{25,  -1}; // text
        };
    }
}
