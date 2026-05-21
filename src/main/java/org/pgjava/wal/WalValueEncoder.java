package org.pgjava.wal;

import org.pgjava.types.PgInterval;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgRange;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
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
 *
 * <p>Special formats:
 * <ul>
 *   <li><b>Arrays</b> ({@code *_ARRAY} OIDs, value type {@code List<Object>}):
 *       {@code [elemOid: 4][count: 4] (for each: [isNull: 1][?len: 4][?bytes: N])}</li>
 *   <li><b>Ranges</b> ({@code INT4RANGE} etc., value type {@code PgRange}):
 *       {@code [flags: 1][?lowerLen: 4][?lowerBytes][?upperLen: 4][?upperBytes]}</li>
 *   <li><b>BIT / VARBIT</b> (value type {@code BitSet}):
 *       {@code [bitCount: 4][bytes: ceil(bitCount/8)]}</li>
 *   <li><b>Enum / user OIDs</b> (OID ≥ {@code FIRST_USER_OID}):
 *       UTF-8 label string; decoded as {@code String} (caller converts to {@code EnumValue})</li>
 * </ul>
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
            case PgOid.BIT, PgOid.VARBIT -> {
                BitSet bs = (BitSet) value;
                int bitCount = bs.length();
                dos.writeInt(bitCount);
                byte[] raw = bs.toByteArray();
                dos.write(raw);
            }
            case PgOid.INT4RANGE, PgOid.INT8RANGE, PgOid.NUMRANGE,
                 PgOid.TSRANGE, PgOid.TSTZRANGE, PgOid.DATERANGE -> {
                PgRange range = (PgRange) value;
                encodeRange(dos, range, elementOidForRange(typeOid));
            }
            default -> {
                if (isArrayOid(typeOid)) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) value;
                    int elemOid = elementOidForArray(typeOid);
                    dos.writeInt(elemOid);
                    dos.writeInt(list.size());
                    for (Object elem : list) {
                        if (elem == null) {
                            dos.writeByte(1); // is_null
                        } else {
                            dos.writeByte(0);
                            byte[] eb = encodeValue(elemOid, elem);
                            dos.writeInt(eb.length);
                            dos.write(eb);
                        }
                    }
                } else {
                    // text, varchar, bpchar, name, json, jsonb, xml, char, unknown,
                    // user OIDs (enum labels, domain values) → UTF-8 string
                    byte[] s = value.toString().getBytes(StandardCharsets.UTF_8);
                    dos.write(s);
                }
            }
        }

        dos.flush();
        return buf.toByteArray();
    }

    private static void encodeRange(DataOutputStream dos, PgRange range, int elemOid)
            throws IOException {
        byte flags = 0;
        if (range.isEmpty())  flags |= 0x01;
        if (range.lowerInf()) flags |= 0x02;
        if (range.upperInf()) flags |= 0x04;
        if (range.lowerInc()) flags |= 0x08;
        if (range.upperInc()) flags |= 0x10;
        dos.writeByte(flags);
        if (!range.isEmpty()) {
            if (!range.lowerInf()) {
                byte[] lb = encodeValue(elemOid, range.lower());
                dos.writeInt(lb.length);
                dos.write(lb);
            }
            if (!range.upperInf()) {
                byte[] ub = encodeValue(elemOid, range.upper());
                dos.writeInt(ub.length);
                dos.write(ub);
            }
        }
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
            case PgOid.BIT, PgOid.VARBIT -> {
                int bitCount = dis.readInt();
                int byteCount = (bitCount + 7) / 8;
                byte[] raw = byteCount > 0 ? dis.readNBytes(byteCount) : new byte[0];
                yield BitSet.valueOf(raw);
            }
            case PgOid.INT4RANGE, PgOid.INT8RANGE, PgOid.NUMRANGE,
                 PgOid.TSRANGE, PgOid.TSTZRANGE, PgOid.DATERANGE ->
                    decodeRange(dis, elementOidForRange(typeOid));
            default -> {
                if (isArrayOid(typeOid)) {
                    int elemOid = dis.readInt();
                    int count   = dis.readInt();
                    List<Object> list = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        boolean isNull = dis.readByte() != 0;
                        if (isNull) {
                            list.add(null);
                        } else {
                            int elemLen   = dis.readInt();
                            byte[] elemBytes = dis.readNBytes(elemLen);
                            list.add(decodeValue(elemOid, elemBytes));
                        }
                    }
                    yield list;
                } else {
                    yield new String(bytes, StandardCharsets.UTF_8);
                }
            }
        };
    }

    private static PgRange decodeRange(DataInputStream dis, int elemOid) throws IOException {
        byte flags    = dis.readByte();
        boolean empty    = (flags & 0x01) != 0;
        boolean lowerInf = (flags & 0x02) != 0;
        boolean upperInf = (flags & 0x04) != 0;
        boolean lowerInc = (flags & 0x08) != 0;
        boolean upperInc = (flags & 0x10) != 0;
        if (empty) return PgRange.EMPTY;
        Object lower = null;
        Object upper = null;
        if (!lowerInf) {
            int len = dis.readInt();
            byte[] b = dis.readNBytes(len);
            lower = decodeValue(elemOid, b);
        }
        if (!upperInf) {
            int len = dis.readInt();
            byte[] b = dis.readNBytes(len);
            upper = decodeValue(elemOid, b);
        }
        return PgRange.of(lower, upper, lowerInc, upperInc);
    }

    // -------------------------------------------------------------------------
    // Array OID helpers

    private static boolean isArrayOid(int oid) {
        return switch (oid) {
            case PgOid.BOOL_ARRAY, PgOid.BYTEA_ARRAY, PgOid.CHAR_ARRAY, PgOid.NAME_ARRAY,
                 PgOid.INT2_ARRAY, PgOid.INT4_ARRAY, PgOid.TEXT_ARRAY, PgOid.OID_ARRAY,
                 PgOid.FLOAT4_ARRAY, PgOid.FLOAT8_ARRAY, PgOid.MONEY_ARRAY,
                 PgOid.BPCHAR_ARRAY, PgOid.VARCHAR_ARRAY, PgOid.INT8_ARRAY,
                 PgOid.DATE_ARRAY, PgOid.TIME_ARRAY, PgOid.TIMESTAMP_ARRAY,
                 PgOid.TIMESTAMPTZ_ARRAY, PgOid.INTERVAL_ARRAY, PgOid.NUMERIC_ARRAY,
                 PgOid.UUID_ARRAY, PgOid.JSONB_ARRAY, PgOid.JSON_ARRAY, PgOid.XML_ARRAY,
                 PgOid.TIMETZ_ARRAY, PgOid.MACADDR_ARRAY, PgOid.INET_ARRAY, PgOid.CIDR_ARRAY,
                 PgOid.BIT_ARRAY, PgOid.VARBIT_ARRAY -> true;
            default -> false;
        };
    }

    private static int elementOidForArray(int arrayOid) {
        return switch (arrayOid) {
            case PgOid.BOOL_ARRAY        -> PgOid.BOOL;
            case PgOid.BYTEA_ARRAY       -> PgOid.BYTEA;
            case PgOid.CHAR_ARRAY        -> PgOid.CHAR;
            case PgOid.NAME_ARRAY        -> PgOid.NAME;
            case PgOid.INT2_ARRAY        -> PgOid.INT2;
            case PgOid.INT4_ARRAY        -> PgOid.INT4;
            case PgOid.TEXT_ARRAY        -> PgOid.TEXT;
            case PgOid.OID_ARRAY         -> PgOid.OID;
            case PgOid.FLOAT4_ARRAY      -> PgOid.FLOAT4;
            case PgOid.FLOAT8_ARRAY      -> PgOid.FLOAT8;
            case PgOid.BPCHAR_ARRAY      -> PgOid.BPCHAR;
            case PgOid.VARCHAR_ARRAY     -> PgOid.VARCHAR;
            case PgOid.INT8_ARRAY        -> PgOid.INT8;
            case PgOid.DATE_ARRAY        -> PgOid.DATE;
            case PgOid.TIME_ARRAY        -> PgOid.TIME;
            case PgOid.TIMESTAMP_ARRAY   -> PgOid.TIMESTAMP;
            case PgOid.TIMESTAMPTZ_ARRAY -> PgOid.TIMESTAMPTZ;
            case PgOid.INTERVAL_ARRAY    -> PgOid.INTERVAL;
            case PgOid.NUMERIC_ARRAY     -> PgOid.NUMERIC;
            case PgOid.UUID_ARRAY        -> PgOid.UUID;
            case PgOid.TIMETZ_ARRAY      -> PgOid.TIMETZ;
            case PgOid.BIT_ARRAY         -> PgOid.BIT;
            case PgOid.VARBIT_ARRAY      -> PgOid.VARBIT;
            case PgOid.JSONB_ARRAY       -> PgOid.JSONB;
            case PgOid.JSON_ARRAY        -> PgOid.JSON;
            // MONEY, MACADDR, INET, CIDR, XML → treat element as TEXT (no typed encoder)
            default                      -> PgOid.TEXT;
        };
    }

    private static int elementOidForRange(int rangeOid) {
        return switch (rangeOid) {
            case PgOid.INT4RANGE  -> PgOid.INT4;
            case PgOid.INT8RANGE  -> PgOid.INT8;
            case PgOid.NUMRANGE   -> PgOid.NUMERIC;
            case PgOid.TSRANGE    -> PgOid.TIMESTAMP;
            case PgOid.TSTZRANGE  -> PgOid.TIMESTAMPTZ;
            case PgOid.DATERANGE  -> PgOid.DATE;
            default -> throw new IllegalArgumentException("Unknown range OID: " + rangeOid);
        };
    }

    // -------------------------------------------------------------------------
    // Scalar helpers

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
