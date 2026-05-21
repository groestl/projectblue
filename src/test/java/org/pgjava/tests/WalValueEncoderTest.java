package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.pgjava.types.PgInterval;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgRange;
import org.pgjava.wal.WalValueEncoder;

import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for {@link WalValueEncoder}.
 *
 * <p>Each test encodes a value and decodes it, asserting the decoded value equals
 * the original. Tests cover all type families including the previously broken
 * cases: arrays (List<Object>), PgRange, and BitSet.
 */
class WalValueEncoderTest {

    // -------------------------------------------------------------------------
    // Helpers

    private static Object roundTrip(int typeOid, Object value) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        WalValueEncoder.encode(new DataOutputStream(buf), typeOid, value);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(buf.toByteArray()));
        return WalValueEncoder.decode(in);
    }

    // -------------------------------------------------------------------------
    // Scalar types

    @Test void bool_true()  throws IOException { assertEquals(true,  roundTrip(PgOid.BOOL, true)); }
    @Test void bool_false() throws IOException { assertEquals(false, roundTrip(PgOid.BOOL, false)); }

    @Test void int2() throws IOException { assertEquals(42,   roundTrip(PgOid.INT2, (short) 42)); }
    @Test void int4() throws IOException { assertEquals(1234, roundTrip(PgOid.INT4, 1234)); }
    @Test void int8() throws IOException { assertEquals(Long.MAX_VALUE, roundTrip(PgOid.INT8, Long.MAX_VALUE)); }

    @Test void float4() throws IOException { assertEquals(3.14f, roundTrip(PgOid.FLOAT4, 3.14f)); }
    @Test void float8() throws IOException { assertEquals(Math.PI, roundTrip(PgOid.FLOAT8, Math.PI)); }

    @Test void numeric() throws IOException {
        BigDecimal bd = new BigDecimal("12345.6789");
        assertEquals(bd, roundTrip(PgOid.NUMERIC, bd));
    }

    @Test void date() throws IOException {
        LocalDate d = LocalDate.of(2024, 3, 15);
        assertEquals(d, roundTrip(PgOid.DATE, d));
    }

    @Test void time() throws IOException {
        LocalTime t = LocalTime.of(13, 45, 59, 123_000_000);
        assertEquals(t, roundTrip(PgOid.TIME, t));
    }

    @Test void timestamp() throws IOException {
        LocalDateTime ldt = LocalDateTime.of(2024, 6, 1, 12, 0, 0, 999_000_000);
        assertEquals(ldt, roundTrip(PgOid.TIMESTAMP, ldt));
    }

    @Test void timestamptz() throws IOException {
        OffsetDateTime odt = OffsetDateTime.of(2024, 6, 1, 12, 0, 0, 500_000_000, ZoneOffset.UTC);
        assertEquals(odt, roundTrip(PgOid.TIMESTAMPTZ, odt));
    }

    @Test void timetz() throws IOException {
        OffsetTime ot = OffsetTime.of(10, 30, 0, 0, ZoneOffset.ofHours(5));
        assertEquals(ot, roundTrip(PgOid.TIMETZ, ot));
    }

    @Test void interval() throws IOException {
        PgInterval iv = new PgInterval(14, 3, 7_200_000_000L);
        assertEquals(iv, roundTrip(PgOid.INTERVAL, iv));
    }

    @Test void uuid() throws IOException {
        UUID u = UUID.randomUUID();
        assertEquals(u, roundTrip(PgOid.UUID, u));
    }

    @Test void bytea() throws IOException {
        byte[] b = {1, 2, 3, (byte) 255};
        assertArrayEquals(b, (byte[]) roundTrip(PgOid.BYTEA, b));
    }

    @Test void text() throws IOException {
        assertEquals("hello world", roundTrip(PgOid.TEXT, "hello world"));
    }

    @Test void nullValue() throws IOException {
        assertNull(roundTrip(PgOid.INT4, null));
    }

    // -------------------------------------------------------------------------
    // BIT / VARBIT

    @Test void bit_nonEmpty() throws IOException {
        BitSet bs = new BitSet();
        bs.set(0);
        bs.set(3);
        bs.set(7);
        BitSet result = (BitSet) roundTrip(PgOid.BIT, bs);
        assertEquals(bs, result);
    }

    @Test void bit_empty() throws IOException {
        BitSet bs = new BitSet();
        BitSet result = (BitSet) roundTrip(PgOid.VARBIT, bs);
        assertEquals(bs, result);
    }

    // -------------------------------------------------------------------------
    // Arrays (previously broken: default → List.toString())

    @Test void intArray() throws IOException {
        List<Object> orig = List.of(1, 2, 3, 42);
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) roundTrip(PgOid.INT4_ARRAY, orig);
        assertEquals(orig, result);
    }

    @Test void textArray() throws IOException {
        List<Object> orig = List.of("foo", "bar", "baz");
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) roundTrip(PgOid.TEXT_ARRAY, orig);
        assertEquals(orig, result);
    }

    @Test void boolArray() throws IOException {
        List<Object> orig = List.of(true, false, true);
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) roundTrip(PgOid.BOOL_ARRAY, orig);
        assertEquals(orig, result);
    }

    @Test void numericArray() throws IOException {
        List<Object> orig = List.of(new BigDecimal("1.5"), new BigDecimal("2.75"));
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) roundTrip(PgOid.NUMERIC_ARRAY, orig);
        assertEquals(orig, result);
    }

    @Test void int8Array() throws IOException {
        List<Object> orig = List.of(Long.MIN_VALUE, 0L, Long.MAX_VALUE);
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) roundTrip(PgOid.INT8_ARRAY, orig);
        assertEquals(orig, result);
    }

    @Test void dateArray() throws IOException {
        List<Object> orig = List.of(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 12, 31));
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) roundTrip(PgOid.DATE_ARRAY, orig);
        assertEquals(orig, result);
    }

    @Test void arrayWithNullElement() throws IOException {
        // Null elements inside arrays must survive round-trip
        List<Object> orig = new java.util.ArrayList<>();
        orig.add(1);
        orig.add(null);
        orig.add(3);
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) roundTrip(PgOid.INT4_ARRAY, orig);
        assertEquals(orig, result);
    }

    @Test void emptyArray() throws IOException {
        List<Object> orig = List.of();
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) roundTrip(PgOid.TEXT_ARRAY, orig);
        assertEquals(orig, result);
    }

    // -------------------------------------------------------------------------
    // Ranges (previously broken: default → PgRange.toString() → decoded as String)

    @Test void int4Range_bounded() throws IOException {
        PgRange r = PgRange.of(1, 10, true, false); // [1,10)
        PgRange result = (PgRange) roundTrip(PgOid.INT4RANGE, r);
        assertEquals(r, result);
    }

    @Test void int4Range_lowerInf() throws IOException {
        PgRange r = PgRange.of(null, 5, false, true); // (,5]
        PgRange result = (PgRange) roundTrip(PgOid.INT4RANGE, r);
        assertEquals(r, result);
    }

    @Test void int4Range_upperInf() throws IOException {
        PgRange r = PgRange.of(3, null, true, false); // [3,)
        PgRange result = (PgRange) roundTrip(PgOid.INT4RANGE, r);
        assertEquals(r, result);
    }

    @Test void int4Range_empty() throws IOException {
        PgRange r = PgRange.EMPTY;
        PgRange result = (PgRange) roundTrip(PgOid.INT4RANGE, r);
        assertTrue(result.isEmpty());
    }

    @Test void int8Range() throws IOException {
        PgRange r = PgRange.of(100L, 200L, true, false);
        PgRange result = (PgRange) roundTrip(PgOid.INT8RANGE, r);
        assertEquals(r, result);
    }

    @Test void numRange() throws IOException {
        PgRange r = PgRange.of(new BigDecimal("1.5"), new BigDecimal("9.9"), true, true);
        PgRange result = (PgRange) roundTrip(PgOid.NUMRANGE, r);
        assertEquals(r, result);
    }

    @Test void tsRange() throws IOException {
        LocalDateTime lo = LocalDateTime.of(2024, 1, 1, 0, 0);
        LocalDateTime hi = LocalDateTime.of(2024, 12, 31, 23, 59);
        PgRange r = PgRange.of(lo, hi, true, false);
        PgRange result = (PgRange) roundTrip(PgOid.TSRANGE, r);
        assertEquals(r, result);
    }

    @Test void dateRange() throws IOException {
        PgRange r = PgRange.of(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 6, 30), true, false);
        PgRange result = (PgRange) roundTrip(PgOid.DATERANGE, r);
        assertEquals(r, result);
    }

    // -------------------------------------------------------------------------
    // User OIDs (enums) — encode as label string, decode as String

    @Test void userOid_encodesAsLabel() throws IOException {
        // EnumValue.toString() returns the label; user OID ≥ 16384
        int enumOid = 16400;
        // Simulate an EnumValue by using a plain String (same toString() behaviour)
        Object result = roundTrip(enumOid, "happy");
        assertEquals("happy", result.toString());
    }
}
