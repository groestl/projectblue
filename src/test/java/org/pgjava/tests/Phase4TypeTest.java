package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.pgjava.types.*;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4: Type system unit tests.
 * Direct tests of PgTypeRegistry, CoercionEngine, TypeInput, TypeOutput,
 * TypeResolver, PgInterval — no SQL execution yet.
 */
class Phase4TypeTest {

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    // -------------------------------------------------------------------------
    // Registry lookups

    @Test
    void builtinTypesByOid() {
        assertEquals("int4",      REG.byOid(PgOid.INT4).name());
        assertEquals("text",      REG.byOid(PgOid.TEXT).name());
        assertEquals("bool",      REG.byOid(PgOid.BOOL).name());
        assertEquals("float8",    REG.byOid(PgOid.FLOAT8).name());
        assertEquals("timestamp", REG.byOid(PgOid.TIMESTAMP).name());
        assertEquals("uuid",      REG.byOid(PgOid.UUID).name());
        assertEquals("numeric",   REG.byOid(PgOid.NUMERIC).name());
        assertEquals("interval",  REG.byOid(PgOid.INTERVAL).name());
    }

    @Test
    void builtinTypesByName() {
        assertEquals(PgOid.INT4,      REG.byName("int4").oid());
        assertEquals(PgOid.TEXT,      REG.byName("text").oid());
        assertEquals(PgOid.TIMESTAMPTZ, REG.byName("timestamptz").oid());
    }

    @Test
    void aliasResolution() {
        assertEquals(PgOid.INT4,    REG.byTypeName("integer").oid());
        assertEquals(PgOid.INT4,    REG.byTypeName("int").oid());
        assertEquals(PgOid.INT2,    REG.byTypeName("smallint").oid());
        assertEquals(PgOid.INT8,    REG.byTypeName("bigint").oid());
        assertEquals(PgOid.FLOAT8,  REG.byTypeName("double precision").oid());
        assertEquals(PgOid.FLOAT4,  REG.byTypeName("real").oid());
        assertEquals(PgOid.NUMERIC, REG.byTypeName("decimal").oid());
        assertEquals(PgOid.NUMERIC, REG.byTypeName("numeric").oid());
        assertEquals(PgOid.BOOL,    REG.byTypeName("boolean").oid());
        assertEquals(PgOid.VARCHAR, REG.byTypeName("character varying").oid());
        assertEquals(PgOid.TIMESTAMP,   REG.byTypeName("timestamp without time zone").oid());
        assertEquals(PgOid.TIMESTAMPTZ, REG.byTypeName("timestamp with time zone").oid());
    }

    @Test
    void arrayTypes() {
        ArrayType int4Arr = REG.int4Array();
        assertNotNull(int4Arr);
        assertEquals(PgOid.INT4_ARRAY, int4Arr.oid());
        assertEquals(PgOid.INT4, int4Arr.elementType().oid());
        assertEquals("_int4", int4Arr.name());

        ArrayType textArr = REG.textArray();
        assertEquals(PgOid.TEXT_ARRAY, textArr.oid());
        assertEquals("_text", textArr.name());
    }

    @Test
    void arrayTypeOidLookup() {
        assertEquals(PgOid.BOOL_ARRAY,        REG.byOid(PgOid.BOOL_ARRAY).oid());
        assertEquals(PgOid.TIMESTAMPTZ_ARRAY, REG.byOid(PgOid.TIMESTAMPTZ_ARRAY).oid());
        assertEquals(PgOid.UUID_ARRAY,        REG.byOid(PgOid.UUID_ARRAY).oid());
    }

    @Test
    void preferredTypesInCategory() {
        ScalarType float8 = (ScalarType) REG.byOid(PgOid.FLOAT8);
        ScalarType float4 = (ScalarType) REG.byOid(PgOid.FLOAT4);
        assertTrue(float8.preferred(), "float8 should be preferred in numeric category");
        assertFalse(float4.preferred(), "float4 should not be preferred");

        ScalarType text = (ScalarType) REG.byOid(PgOid.TEXT);
        assertTrue(text.preferred(), "text is preferred in string category");

        ScalarType timestamptz = (ScalarType) REG.byOid(PgOid.TIMESTAMPTZ);
        assertTrue(timestamptz.preferred(), "timestamptz is preferred in datetime category");
    }

    // -------------------------------------------------------------------------
    // TypeInput

    @Test
    void parseBool() throws SQLException {
        assertEquals(Boolean.TRUE,  TypeInput.parse("true",  PgOid.BOOL));
        assertEquals(Boolean.TRUE,  TypeInput.parse("t",     PgOid.BOOL));
        assertEquals(Boolean.TRUE,  TypeInput.parse("yes",   PgOid.BOOL));
        assertEquals(Boolean.TRUE,  TypeInput.parse("on",    PgOid.BOOL));
        assertEquals(Boolean.TRUE,  TypeInput.parse("1",     PgOid.BOOL));
        assertEquals(Boolean.FALSE, TypeInput.parse("false", PgOid.BOOL));
        assertEquals(Boolean.FALSE, TypeInput.parse("f",     PgOid.BOOL));
        assertEquals(Boolean.FALSE, TypeInput.parse("no",    PgOid.BOOL));
        assertEquals(Boolean.FALSE, TypeInput.parse("off",   PgOid.BOOL));
        assertEquals(Boolean.FALSE, TypeInput.parse("0",     PgOid.BOOL));
    }

    @Test
    void parseBoolInvalid() {
        var ex = assertThrows(SQLException.class, () -> TypeInput.parse("maybe", PgOid.BOOL));
        assertEquals("22P02", ex.getSQLState());
    }

    @Test
    void parseIntegers() throws SQLException {
        assertEquals(42,    TypeInput.parse("42",  PgOid.INT4));
        assertEquals(42,    TypeInput.parse("42",  PgOid.INT2));
        assertEquals(42L,   TypeInput.parse("42",  PgOid.INT8));
        assertEquals(-1,    TypeInput.parse("-1",  PgOid.INT4));
    }

    @Test
    void parseInt2Overflow() {
        var ex = assertThrows(SQLException.class, () -> TypeInput.parse("40000", PgOid.INT2));
        assertEquals("22003", ex.getSQLState());
    }

    @Test
    void parseFloat() throws SQLException {
        assertEquals(3.14f,            TypeInput.parse("3.14",     PgOid.FLOAT4));
        assertEquals(3.14,             TypeInput.parse("3.14",     PgOid.FLOAT8));
        assertEquals(Float.POSITIVE_INFINITY,  TypeInput.parse("Infinity",  PgOid.FLOAT4));
        assertEquals(Double.NEGATIVE_INFINITY, TypeInput.parse("-Infinity", PgOid.FLOAT8));
        assertTrue(Double.isNaN((Double) TypeInput.parse("NaN", PgOid.FLOAT8)));
    }

    @Test
    void parseNumeric() throws SQLException {
        assertEquals(new BigDecimal("3.14159"), TypeInput.parse("3.14159", PgOid.NUMERIC));
    }

    @Test
    void parseDate() throws SQLException {
        assertEquals(LocalDate.of(2024, 1, 31), TypeInput.parse("2024-01-31", PgOid.DATE));
    }

    @Test
    void parseTimestamp() throws SQLException {
        LocalDateTime expected = LocalDateTime.of(2024, 1, 31, 12, 30, 0);
        assertEquals(expected, TypeInput.parse("2024-01-31 12:30:00", PgOid.TIMESTAMP));
    }

    @Test
    void parseTimestampTz() throws SQLException {
        Object result = TypeInput.parse("2024-01-31 12:30:00+00", PgOid.TIMESTAMPTZ);
        assertInstanceOf(OffsetDateTime.class, result);
    }

    @Test
    void parseUuid() throws SQLException {
        UUID expected = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        assertEquals(expected, TypeInput.parse("550e8400-e29b-41d4-a716-446655440000", PgOid.UUID));
    }

    @Test
    void parseBytea() throws SQLException {
        byte[] b = (byte[]) TypeInput.parse("\\x48656c6c6f", PgOid.BYTEA);
        assertArrayEquals(new byte[]{'H','e','l','l','o'}, b);
    }

    // -------------------------------------------------------------------------
    // TypeOutput

    @Test
    void formatBool() {
        assertEquals("t", TypeOutput.format(Boolean.TRUE,  PgOid.BOOL));
        assertEquals("f", TypeOutput.format(Boolean.FALSE, PgOid.BOOL));
    }

    @Test
    void formatIntegers() {
        assertEquals("42",  TypeOutput.format(42,  PgOid.INT4));
        assertEquals("-1",  TypeOutput.format(-1,  PgOid.INT4));
        assertEquals("100", TypeOutput.format(100L, PgOid.INT8));
    }

    @Test
    void formatFloat() {
        assertEquals("Infinity",  TypeOutput.format(Float.POSITIVE_INFINITY,  PgOid.FLOAT4));
        assertEquals("-Infinity", TypeOutput.format(Double.NEGATIVE_INFINITY, PgOid.FLOAT8));
        assertEquals("NaN",       TypeOutput.format(Double.NaN,               PgOid.FLOAT8));
    }

    @Test
    void formatDate() {
        assertEquals("2024-01-31", TypeOutput.format(LocalDate.of(2024, 1, 31), PgOid.DATE));
    }

    @Test
    void formatBytea() {
        String out = TypeOutput.format(new byte[]{'H','e','l','l','o'}, PgOid.BYTEA);
        assertEquals("\\x48656c6c6f", out);
    }

    // -------------------------------------------------------------------------
    // CoercionEngine

    @Test
    void implicitWideningAllowed() {
        assertTrue(CoercionEngine.canCoerce(PgOid.INT2, PgOid.INT4, CoercionContext.IMPLICIT));
        assertTrue(CoercionEngine.canCoerce(PgOid.INT4, PgOid.INT8, CoercionContext.IMPLICIT));
        assertTrue(CoercionEngine.canCoerce(PgOid.INT8, PgOid.NUMERIC, CoercionContext.IMPLICIT));
        assertTrue(CoercionEngine.canCoerce(PgOid.FLOAT4, PgOid.FLOAT8, CoercionContext.IMPLICIT));
        assertTrue(CoercionEngine.canCoerce(PgOid.DATE, PgOid.TIMESTAMP, CoercionContext.IMPLICIT));
        assertTrue(CoercionEngine.canCoerce(PgOid.VARCHAR, PgOid.TEXT, CoercionContext.IMPLICIT));
    }

    @Test
    void implicitNarrowingForbidden() {
        assertFalse(CoercionEngine.canCoerce(PgOid.INT8, PgOid.INT4, CoercionContext.IMPLICIT));
        assertFalse(CoercionEngine.canCoerce(PgOid.FLOAT8, PgOid.INT4, CoercionContext.IMPLICIT));
    }

    @Test
    void assignmentNarrowingAllowed() {
        assertTrue(CoercionEngine.canCoerce(PgOid.INT8, PgOid.INT4, CoercionContext.ASSIGNMENT));
        assertTrue(CoercionEngine.canCoerce(PgOid.INT4, PgOid.INT2, CoercionContext.ASSIGNMENT));
    }

    @Test
    void explicitCastAllowed() {
        assertTrue(CoercionEngine.canCoerce(PgOid.INT8, PgOid.INT4, CoercionContext.EXPLICIT));
        assertTrue(CoercionEngine.canCoerce(PgOid.FLOAT8, PgOid.INT4, CoercionContext.EXPLICIT));
        assertTrue(CoercionEngine.canCoerce(PgOid.TEXT, PgOid.INT4, CoercionContext.EXPLICIT));
        assertTrue(CoercionEngine.canCoerce(PgOid.INT4, PgOid.TEXT, CoercionContext.EXPLICIT));
    }

    @Test
    void unknownCoercesToAnything() {
        assertTrue(CoercionEngine.canCoerce(PgOid.UNKNOWN, PgOid.INT4, CoercionContext.IMPLICIT));
        assertTrue(CoercionEngine.canCoerce(PgOid.UNKNOWN, PgOid.DATE, CoercionContext.IMPLICIT));
        assertTrue(CoercionEngine.canCoerce(PgOid.UNKNOWN, PgOid.UUID, CoercionContext.IMPLICIT));
    }

    @Test
    void coerceInt2ToInt4() throws SQLException {
        Object result = CoercionEngine.coerce(42, PgOid.INT2, PgOid.INT4, CoercionContext.IMPLICIT);
        assertEquals(42, result);
    }

    @Test
    void coerceInt4ToInt8() throws SQLException {
        Object result = CoercionEngine.coerce(42, PgOid.INT4, PgOid.INT8, CoercionContext.IMPLICIT);
        assertEquals(42L, result);
    }

    @Test
    void coerceStringToInt() throws SQLException {
        Object result = CoercionEngine.coerce("42", PgOid.UNKNOWN, PgOid.INT4, CoercionContext.IMPLICIT);
        assertEquals(42, result);
    }

    @Test
    void coerceStringToDate() throws SQLException {
        Object result = CoercionEngine.coerce("2024-01-31", PgOid.UNKNOWN, PgOid.DATE, CoercionContext.IMPLICIT);
        assertEquals(LocalDate.of(2024, 1, 31), result);
    }

    @Test
    void coerceInt8NarrowingOverflow() {
        assertThrows(SQLException.class,
                () -> CoercionEngine.coerce(100000L, PgOid.INT8, PgOid.INT2, CoercionContext.ASSIGNMENT));
    }

    @Test
    void coerceNoPath() {
        var ex = assertThrows(SQLException.class,
                () -> CoercionEngine.coerce("hello", PgOid.TEXT, PgOid.DATE, CoercionContext.IMPLICIT));
        assertEquals("42804", ex.getSQLState());
    }

    @Test
    void coerceNullAlwaysNull() throws SQLException {
        assertNull(CoercionEngine.coerce(null, PgOid.INT4, PgOid.TEXT, CoercionContext.IMPLICIT));
    }

    @Test
    void resolveCommonType() throws SQLException {
        assertEquals(PgOid.INT8, CoercionEngine.resolveCommonType(new int[]{PgOid.INT4, PgOid.INT8}));
        assertEquals(PgOid.FLOAT8, CoercionEngine.resolveCommonType(new int[]{PgOid.INT4, PgOid.FLOAT8}));
        assertEquals(PgOid.TEXT, CoercionEngine.resolveCommonType(new int[]{PgOid.VARCHAR, PgOid.TEXT}));
    }

    // -------------------------------------------------------------------------
    // TypeResolver

    @Test
    void resolveUnknownOutputToText() {
        assertEquals(PgOid.TEXT, TypeResolver.resolveUnknownOutput(PgOid.UNKNOWN));
        assertEquals(PgOid.INT4, TypeResolver.resolveUnknownOutput(PgOid.INT4));
    }

    @Test
    void resolveAllUnknownToText() throws SQLException {
        int[] args = {PgOid.UNKNOWN, PgOid.UNKNOWN};
        int resolved = TypeResolver.resolveArgTypes(args);
        assertEquals(PgOid.TEXT, resolved);
        assertArrayEquals(new int[]{PgOid.TEXT, PgOid.TEXT}, args);
    }

    @Test
    void resolveUnknownWithKnown() throws SQLException {
        int[] args = {PgOid.UNKNOWN, PgOid.INT4};
        TypeResolver.resolveArgTypes(args);
        assertEquals(PgOid.INT4, args[0]);
        assertEquals(PgOid.INT4, args[1]);
    }

    @Test
    void resolveNumericWidening() throws SQLException {
        int[] args = {PgOid.INT4, PgOid.INT8};
        int result = TypeResolver.resolveArgTypes(args);
        assertEquals(PgOid.INT8, result);
    }

    // -------------------------------------------------------------------------
    // PgInterval

    @Test
    void intervalParsePGStyle() {
        PgInterval iv = PgInterval.parse("1 year 2 mons 3 days 04:05:06");
        assertEquals(14, iv.months());     // 1*12 + 2
        assertEquals(3,  iv.days());
        long expectedMicros = (4 * 3600L + 5 * 60L + 6L) * 1_000_000L;
        assertEquals(expectedMicros, iv.micros());
    }

    @Test
    void intervalParseISO() {
        PgInterval iv = PgInterval.parse("P1Y2M3DT4H5M6S");
        assertEquals(14, iv.months());
        assertEquals(3,  iv.days());
    }

    @Test
    void intervalToString() {
        PgInterval iv = new PgInterval(14, 3, (4 * 3600L + 5 * 60L + 6L) * 1_000_000L);
        String s = iv.toString();
        assertTrue(s.contains("year"), "should contain 'year': " + s);
        assertTrue(s.contains("04:05:06"), "should contain time: " + s);
    }

    @Test
    void intervalArithmetic() {
        PgInterval a = PgInterval.ofMonths(3);
        PgInterval b = PgInterval.ofDays(10);
        PgInterval sum = a.plus(b);
        assertEquals(3,  sum.months());
        assertEquals(10, sum.days());
        assertEquals(0L, sum.micros());
    }

    @Test
    void intervalNegate() {
        PgInterval iv = new PgInterval(1, 2, 3_000_000L);
        PgInterval neg = iv.negate();
        assertEquals(-1, neg.months());
        assertEquals(-2, neg.days());
        assertEquals(-3_000_000L, neg.micros());
    }

    @Test
    void intervalZeroFromString() {
        assertEquals(PgInterval.ZERO, PgInterval.parse(""));
        assertEquals(PgInterval.ZERO, PgInterval.parse(null));
    }
}
