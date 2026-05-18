package org.pgjava.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes PostgreSQL wire protocol v3 byte stream into {@link PgMessages.FrontendMessage} objects.
 *
 * <p>State machine:
 * <ul>
 *   <li>STARTUP — reads the startup message (no type byte; 4-byte length + 4-byte version).</li>
 *   <li>REGULAR — reads regular messages (1-byte type + 4-byte length + payload).</li>
 * </ul>
 *
 * <p>After a {@link PgMessages.SslRequest} is emitted we stay in STARTUP state so the
 * real startup message that immediately follows can be decoded.
 *
 * <p>Not {@code @Sharable} — each connection must have its own instance (state machine per connection).
 */
class PgWireDecoder extends ByteToMessageDecoder {

    private enum State { STARTUP, REGULAR }

    // Not actually sharable per-connection — each connection gets its own instance
    private State state = State.STARTUP;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (state == State.STARTUP) {
            decodeStartup(ctx, in, out);
        } else {
            decodeRegular(ctx, in, out);
        }
    }

    // -------------------------------------------------------------------------
    // Startup-phase decoding

    private void decodeStartup(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 8) return;   // need at least length(4) + version(4)
        in.markReaderIndex();
        int totalLen = in.readInt();           // includes itself
        int payloadLen = totalLen - 4;
        if (payloadLen < 4) { ctx.close(); return; }
        if (in.readableBytes() < payloadLen) {
            in.resetReaderIndex();
            return;
        }
        int version = in.readInt();

        if (version == 80877103) {
            // SSLRequest — no extra payload
            out.add(new PgMessages.SslRequest());
            // Stay in STARTUP so the real startup message follows
        } else if (version == 80877102) {
            // CancelRequest — 8 bytes of payload (pid + secret)
            int pid    = in.readInt();
            int secret = in.readInt();
            out.add(new PgMessages.CancelRequest(pid, secret));
            state = State.REGULAR;
        } else if ((version >>> 16) == 3) {
            // Protocol 3.x startup — key=value\0 ... \0
            byte[] paramBytes = new byte[payloadLen - 4];
            in.readBytes(paramBytes);
            Map<String, String> params = parseStartupParams(paramBytes);
            out.add(new PgMessages.StartupMessage(params));
            state = State.REGULAR;
        } else {
            // Unknown protocol version
            ctx.close();
        }
    }

    private static Map<String, String> parseStartupParams(byte[] data) {
        Map<String, String> params = new LinkedHashMap<>();
        int i = 0;
        while (i < data.length && data[i] != 0) {
            int keyEnd = indexOf(data, i, (byte) 0);
            String key = new String(data, i, keyEnd - i, StandardCharsets.UTF_8);
            i = keyEnd + 1;
            int valEnd = indexOf(data, i, (byte) 0);
            String val = new String(data, i, valEnd - i, StandardCharsets.UTF_8);
            i = valEnd + 1;
            if (!key.isEmpty()) params.put(key, val);
        }
        return params;
    }

    // -------------------------------------------------------------------------
    // Regular-phase decoding

    private void decodeRegular(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (true) {
            if (in.readableBytes() < 5) return;  // type(1) + length(4)
            in.markReaderIndex();
            byte type       = in.readByte();
            int  len        = in.readInt();       // includes itself (4), not the type byte
            int  payloadLen = len - 4;
            if (payloadLen < 0) { ctx.close(); return; }
            if (in.readableBytes() < payloadLen) {
                in.resetReaderIndex();
                return;
            }
            byte[] payload = new byte[payloadLen];
            in.readBytes(payload);
            out.add(parseRegularMessage(type, payload));
        }
    }

    private PgMessages.FrontendMessage parseRegularMessage(byte type, byte[] payload) {
        return switch ((char) (type & 0xFF)) {
            case 'Q' -> parseQuery(payload);
            case 'P' -> parseParse(payload);
            case 'B' -> parseBind(payload);
            case 'E' -> parseExecute(payload);
            case 'D' -> parseDescribe(payload);
            case 'S' -> new PgMessages.Sync();
            case 'H' -> new PgMessages.Flush();
            case 'C' -> parseClose(payload);
            case 'X' -> new PgMessages.Terminate();
            default  -> new PgMessages.Unknown(type);
        };
    }

    // ---- Message parsers ----

    private PgMessages.Query parseQuery(byte[] p) {
        return new PgMessages.Query(readCString(p, 0));
    }

    private PgMessages.Parse parseParse(byte[] p) {
        int i = 0;
        String name = readCString(p, i); i += name.length() + 1;
        String query = readCString(p, i); i += query.length() + 1;
        int nParams = readShort(p, i); i += 2;
        int[] oids = new int[nParams];
        for (int k = 0; k < nParams; k++) {
            oids[k] = readInt(p, i); i += 4;
        }
        return new PgMessages.Parse(name, query, oids);
    }

    private PgMessages.Bind parseBind(byte[] p) {
        int i = 0;
        String portal    = readCString(p, i); i += portal.length() + 1;
        String statement = readCString(p, i); i += statement.length() + 1;

        // Parameter format codes: 0 = text, 1 = binary.
        // If nFmts == 1, that single code applies to all parameters.
        int nFmts = readShort(p, i); i += 2;
        int[] fmtCodes = new int[nFmts];
        for (int k = 0; k < nFmts; k++) {
            fmtCodes[k] = readShort(p, i); i += 2;
        }

        int nParams = readShort(p, i); i += 2;
        String[] params = new String[nParams];
        int[] paramFormats = new int[nParams];
        byte[][] rawBinary = new byte[nParams][];
        for (int k = 0; k < nParams; k++) {
            int len = readInt(p, i); i += 4;
            if (len == -1) {
                params[k] = null;
                continue;
            }
            // Determine format: single code applies to all, else per-param
            int fmt = (nFmts == 0) ? 0
                    : (nFmts == 1) ? fmtCodes[0]
                    : (k < nFmts)  ? fmtCodes[k]
                    : 0;
            paramFormats[k] = fmt;
            if (fmt == 1) {
                // Binary format — store raw bytes for type-aware conversion later
                rawBinary[k] = java.util.Arrays.copyOfRange(p, i, i + len);
                // Also do best-effort text conversion (may be refined by handler)
                params[k] = binaryParamToText(p, i, len);
            } else {
                params[k] = new String(p, i, len, StandardCharsets.UTF_8);
            }
            i += len;
        }
        // Skip result format codes
        return new PgMessages.Bind(portal, statement, params, paramFormats, rawBinary);
    }

    /**
     * Convert a binary-format parameter to its text representation.
     * Handles the common fixed-size PG types by size:
     *   1 byte  → bool (false/true) or int1
     *   2 bytes → int2
     *   4 bytes → int4 (big-endian signed int)
     *   8 bytes → int8 (big-endian signed long)
     * Arbitrary lengths fall back to UTF-8 text decoding (handles text in binary frame).
     */
    private static String binaryParamToText(byte[] data, int offset, int len) {
        switch (len) {
            case 1 -> { return data[offset] == 0 ? "false" : "true"; }
            case 2 -> { return String.valueOf(readShort(data, offset)); }
            case 4 -> { return String.valueOf(readInt(data, offset)); }
            case 8 -> { return String.valueOf(readLong(data, offset)); }
            default -> { return new String(data, offset, len, StandardCharsets.UTF_8); }
        }
    }

    private static long readLong(byte[] data, int offset) {
        return ((long)(data[offset]     & 0xFF) << 56)
             | ((long)(data[offset + 1] & 0xFF) << 48)
             | ((long)(data[offset + 2] & 0xFF) << 40)
             | ((long)(data[offset + 3] & 0xFF) << 32)
             | ((long)(data[offset + 4] & 0xFF) << 24)
             | ((long)(data[offset + 5] & 0xFF) << 16)
             | ((long)(data[offset + 6] & 0xFF) <<  8)
             |  (long)(data[offset + 7] & 0xFF);
    }

    private PgMessages.Execute parseExecute(byte[] p) {
        String portal = readCString(p, 0);
        int maxRows = readInt(p, portal.length() + 1);
        return new PgMessages.Execute(portal, maxRows);
    }

    private PgMessages.Describe parseDescribe(byte[] p) {
        char type = (char) (p[0] & 0xFF);
        String name = readCString(p, 1);
        return new PgMessages.Describe(type, name);
    }

    private PgMessages.Close parseClose(byte[] p) {
        char type = (char) (p[0] & 0xFF);
        String name = readCString(p, 1);
        return new PgMessages.Close(type, name);
    }

    // ---- Helpers ----

    private static String readCString(byte[] data, int offset) {
        if (offset >= data.length) return "";
        int end = indexOf(data, offset, (byte) 0);
        return new String(data, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static int indexOf(byte[] data, int from, byte target) {
        for (int i = from; i < data.length; i++) {
            if (data[i] == target) return i;
        }
        return data.length;
    }

    private static int readShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset]     & 0xFF) << 24)
             | ((data[offset + 1] & 0xFF) << 16)
             | ((data[offset + 2] & 0xFF) <<  8)
             |  (data[offset + 3] & 0xFF);
    }
}
