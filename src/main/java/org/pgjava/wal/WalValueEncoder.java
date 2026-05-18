package org.pgjava.wal;

import org.pgjava.types.PgInterval;
import org.pgjava.types.PgOid;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.BitSet;
import java.util.UUID;

/**
 * Encodes and decodes individual column values in WAL records.
 *
 * <p>Format per value: {@code [type_oid: 4][is_null: 1][value_length: 4][value_bytes: N]}
 *
 * <p>Each value is encoded with its type OID so the decoder can reconstruct
 * the correct Java type without knowing the schema at decode time.
 *
 * <p>All integers are big-endian.
 */
public final class WalValueEncoder {

    private WalValueEncoder() {}

    // -------------------------------------------------------------------------
    // Encode

    public static void encode(DataOutputStream out, int typeOid, Object value) throws IOException {
        out.writeInt(typeOid);
        if (value == null) {
            out.writeByte(1);   // is_null = true
            out.writeInt(0);    // length = 0
            return;
        }
        out.writeByte(0);       // is_null = false

        byte[] bytes = encodeValue(typeOid, value);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] encodeValue(int typeOid, Object value) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(buf);

        switch (typeOid) {
            case PgOid.BOOL -> dos.writeByte(Boolean.TRUE.equals(value) ? 1 : 0);
            case PgOid.INT2 -> dos.writeShort(toShort(value));
            case PgOid.INT4, PgOid.OID -> dos.writeInt(toInt(value));
            case PgOid.INT8 -> dos.writeLong(toLong(value));
            case PgOid.FLOAT4 -> dos.writeFloat(toFloat(value));
            case PgOid.FLOAT8 -> dos.writeDouble(toDouble(value));
            case PgOid.NUMERIC -> {
                byte[] s = value.toString().getBytes(StandardCharsets.UTF_8);
                dos.write(s);
            }
            case PgOid.DATE -> dos.writeLong(((LocalDate) value).toEpochDay());
            case PgOid.TIME -> dos.writeLong(((LocalTime) value).toNanoOfDay());
            case PgOid.TIMESTAMP -> {
                LocalDateTime ldt = (LocalDateTime) value;
                dos.writeLong(ldt.toEpochSecond(ZoneOffset.UTC));
                dos.writeInt(ldt.getNano());
            }
            case PgOid.TIMESTAMPTZ -> {
                OffsetDateTime odt = (OffsetDateTime) value;
                dos.writeLong(odt.toEpochSecond());
                dos.writeInt(odt.getNano());
            }
            case PgOid.TIMETZ -> {
                OffsetTime ot = (OffsetTime) value;
                dos.writeLong(ot.toLocalTime().toNanoOfDay());
                dos.writeInt(ot.getOffset().getTotalSeconds());
            }
            case PgOid.INTERVAL -> {
                PgInterval iv = (PgInterval) value;
                dos.writeInt(iv.months());
                dos.writeInt(iv.days());
                dos.writeLong(iv.micros());
            }
            case PgOid.UUID -> {
                UUID uuid = (UUID) value;
                dos.writeLong(uuid.getMostSignificantBits());
                dos.writeLong(uuid.getLeastSignificantBits());
            }
            case PgOid.BYTEA -> {
                byte[] b = (byte[]) value;
                dos.write(b);
            }
            default -> {
                // text, varchar, bpchar, name, json, jsonb, xml, char, unknown → UTF-8 string
                byte[] s = value.toString().getBytes(StandardCharsets.UTF_8);
                dos.write(s);
            }
        }

        dos.flush();
        return buf.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Decode

    public static Object decode(DataInputStream in) throws IOException {
        int  typeOid = in.readInt();
        boolean isNull = in.readByte() != 0;
        int  len     = in.readInt();

        if (isNull) {
            if (len > 0) in.skipBytes(len);
            return null;
        }

        byte[] bytes = in.readNBytes(len);
        return decodeValue(typeOid, bytes);
    }

    private static Object decodeValue(int typeOid, byte[] bytes) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));

        return switch (typeOid) {
            case PgOid.BOOL       -> dis.readByte() != 0;
            case PgOid.INT2       -> (int) dis.readShort();
            case PgOid.INT4, PgOid.OID -> dis.readInt();
            case PgOid.INT8       -> dis.readLong();
            case PgOid.FLOAT4     -> dis.readFloat();
            case PgOid.FLOAT8     -> dis.readDouble();
            case PgOid.NUMERIC    -> new BigDecimal(new String(bytes, StandardCharsets.UTF_8));
            case PgOid.DATE       -> LocalDate.ofEpochDay(dis.readLong());
            case PgOid.TIME       -> LocalTime.ofNanoOfDay(dis.readLong());
            case PgOid.TIMESTAMP  -> {
                long sec  = dis.readLong();
                int  nano = dis.readInt();
                yield LocalDateTime.ofEpochSecond(sec, nano, ZoneOffset.UTC);
            }
            case PgOid.TIMESTAMPTZ -> {
                long sec  = dis.readLong();
                int  nano = dis.readInt();
                yield OffsetDateTime.ofInstant(
                        java.time.Instant.ofEpochSecond(sec, nano), ZoneOffset.UTC);
            }
            case PgOid.TIMETZ -> {
                long nanos  = dis.readLong();
                int  offset = dis.readInt();
                yield OffsetTime.of(LocalTime.ofNanoOfDay(nanos),
                        ZoneOffset.ofTotalSeconds(offset));
            }
            case PgOid.INTERVAL -> {
                int months = dis.readInt();
                int days   = dis.readInt();
                long micros = dis.readLong();
                yield new PgInterval(months, days, micros);
            }
            case PgOid.UUID -> {
                long hi = dis.readLong();
                long lo = dis.readLong();
                yield new UUID(hi, lo);
            }
            case PgOid.BYTEA -> bytes.clone();
            default -> new String(bytes, StandardCharsets.UTF_8);
        };
    }

    // -------------------------------------------------------------------------
    // Helpers

    private static short  toShort(Object v) {
        if (v instanceof Short s) return s;
        if (v instanceof Number n) return n.shortValue();
        return Short.parseShort(v.toString());
    }
    private static int    toInt(Object v) {
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }
    private static long   toLong(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }
    private static float  toFloat(Object v) {
        if (v instanceof Float f) return f;
        if (v instanceof Number n) return n.floatValue();
        return Float.parseFloat(v.toString());
    }
    private static double toDouble(Object v) {
        if (v instanceof Double d) return d;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }
}
