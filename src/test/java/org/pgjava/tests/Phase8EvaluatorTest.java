package org.pgjava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pgjava.catalog.*;
import org.pgjava.executor.EvalContext;
import org.pgjava.executor.Evaluator;
import org.pgjava.sql.ast.*;
import org.pgjava.storage.Row;
import org.pgjava.storage.RowId;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 8: Expression Evaluator unit tests.
 * Constructs AST nodes directly (no parser round-trip needed) to verify
 * every evaluation path in {@link Evaluator}.
 */
class Phase8EvaluatorTest {

    private Evaluator   eval;
    private FunctionRegistry reg;

    // A simple 3-column row: id=1, name="Alice", active=true
    private EvalContext rowCtx;

    @BeforeEach
    void setUp() {
        reg = new FunctionRegistry();
        BuiltinFunctions.registerAll(reg);
        eval = new Evaluator(reg, org.pgjava.types.PgCollation.DEFAULT);

        // Build a ColumnMap + Row for "id INTEGER, name TEXT, active BOOLEAN"
        var cols = EvalContext.ColumnMap.of(List.of("id", "name", "active"));
        var row  = new Row(new RowId(1L, 0), new Object[]{1, "Alice", true});
        rowCtx   = EvalContext.of(row, cols);
    }

    // =========================================================================
    // Literals

    @Test void nullLiteral()    throws Exception { assertNull(eval.eval(new NullLiteral(), EvalContext.empty())); }
    @Test void boolTrue()       throws Exception { assertEquals(true,  eval.eval(new BooleanLiteral(true),  EvalContext.empty())); }
    @Test void boolFalse()      throws Exception { assertEquals(false, eval.eval(new BooleanLiteral(false), EvalContext.empty())); }
    @Test void intLiteral()     throws Exception { assertEquals(42,    eval.eval(new IntegerLiteral(42),    EvalContext.empty())); }
    @Test void longLiteral()    throws Exception {
        // Values > Integer.MAX_VALUE become long
        assertEquals(3000000000L, eval.eval(new IntegerLiteral(3_000_000_000L), EvalContext.empty()));
    }
    @Test void floatLiteral()   throws Exception { assertEquals(3.14,  eval.eval(new FloatLiteral("3.14"),  EvalContext.empty())); }
    @Test void stringLiteral()  throws Exception { assertEquals("hi",  eval.eval(new StringLiteral("hi"),   EvalContext.empty())); }

    @Test void typedLiteralDate() throws Exception {
        var tl = new TypedLiteral(TypeName.of("date"), "2024-06-15");
        assertEquals(LocalDate.of(2024, 6, 15), eval.eval(tl, EvalContext.empty()));
    }

    // =========================================================================
    // Column references

    @Test void columnRefUnqualified() throws Exception {
        assertEquals(1,       eval.eval(ColumnRef.of("id"),     rowCtx));
        assertEquals("Alice", eval.eval(ColumnRef.of("name"),   rowCtx));
        assertEquals(true,    eval.eval(ColumnRef.of("active"), rowCtx));
    }

    @Test void columnRefCaseInsensitive() throws Exception {
        assertEquals(1, eval.eval(ColumnRef.of("ID"), rowCtx));
    }

    @Test void columnRefMissingThrows() {
        assertThrows(Exception.class, () -> eval.eval(ColumnRef.of("missing"), rowCtx));
    }

    // =========================================================================
    // Arithmetic

    @Test void addInts()    throws Exception { assertEquals(5,   arith(2, "+", 3)); }
    @Test void subInts()    throws Exception { assertEquals(1,   arith(4, "-", 3)); }
    @Test void mulInts()    throws Exception { assertEquals(12,  arith(3, "*", 4)); }
    @Test void divInts()    throws Exception { assertEquals(3,   arith(7, "/", 2)); }
    @Test void modInts()    throws Exception { assertEquals(1,   arith(7, "%", 2)); }
    @Test void addLongs()   throws Exception { assertEquals(4_294_967_298L, arith(2_147_483_648L, "+", 2_147_483_650L)); }
    @Test void addDoubles() throws Exception { assertEquals(1.5, arith(1.0, "+", 0.5)); }
    @Test void addMixed()   throws Exception {
        // int + double → double
        Object r = arithObj(new IntegerLiteral(1), "+", new FloatLiteral("0.5"));
        assertEquals(1.5, r);
    }
    @Test void divByZeroThrows() {
        assertThrowsSQLState("22012", () -> arith(1, "/", 0));
    }
    @Test void negativeUnary() throws Exception {
        assertEquals(-5, eval.eval(new UnaryOp("-", new IntegerLiteral(5)), EvalContext.empty()));
    }
    @Test void nullPropagatesArith() throws Exception {
        assertNull(eval.eval(new BinaryOp("+", new NullLiteral(), new IntegerLiteral(1)), EvalContext.empty()));
    }

    // =========================================================================
    // String concat

    @Test void stringConcat() throws Exception {
        var expr = new BinaryOp("||", new StringLiteral("foo"), new StringLiteral("bar"));
        assertEquals("foobar", eval.eval(expr, EvalContext.empty()));
    }
    @Test void concatWithNull() throws Exception {
        // In PG, text || NULL = NULL for the || operator (SQL standard strict semantics)
        // concat() function skips nulls, but || does not
        var expr = new BinaryOp("||", new StringLiteral("foo"), new NullLiteral());
        assertNull(eval.eval(expr, EvalContext.empty()));
    }

    // =========================================================================
    // Comparison operators

    @Test void equalInts() throws Exception {
        assertEquals(true,  cmp(1, "=", 1));
        assertEquals(false, cmp(1, "=", 2));
    }
    @Test void notEqual() throws Exception {
        assertEquals(true, cmp(1, "<>", 2));
    }
    @Test void lessThan() throws Exception {
        assertEquals(true,  cmp(1, "<", 2));
        assertEquals(false, cmp(2, "<", 1));
    }
    @Test void greaterThan() throws Exception {
        assertEquals(true, cmp(2, ">", 1));
    }
    @Test void nullComparisonReturnsNull() throws Exception {
        Object r = eval.eval(new BinaryOp("=", new NullLiteral(), new IntegerLiteral(1)),
                EvalContext.empty());
        assertNull(r, "NULL = 1 must be NULL, not false");
    }
    @Test void numericMixedComparison() throws Exception {
        // 1 (int) = 1.0 (double) → true via numeric promotion
        assertEquals(true, eval.eval(
                new BinaryOp("=", new IntegerLiteral(1), new FloatLiteral("1.0")),
                EvalContext.empty()));
    }

    // =========================================================================
    // Boolean operators (three-valued logic)

    @Test void andTrueTrue()   throws Exception { assertEquals(true,  and(true,  true)); }
    @Test void andTrueFalse()  throws Exception { assertEquals(false, and(true,  false)); }
    @Test void andNullFalse()  throws Exception { assertEquals(false, andWithNull(false)); }
    @Test void andNullTrue()   throws Exception { assertNull(andWithNull(true)); }
    @Test void orFalseTrue()   throws Exception { assertEquals(true,  or(false,  true)); }
    @Test void orNullTrue()    throws Exception { assertEquals(true,  orWithNull(true)); }
    @Test void orNullFalse()   throws Exception { assertNull(orWithNull(false)); }
    @Test void notTrue()       throws Exception { assertEquals(false, not(true)); }
    @Test void notNull()       throws Exception { assertNull(eval.eval(new UnaryOp("NOT", new NullLiteral()), EvalContext.empty())); }

    // =========================================================================
    // IS NULL / IS NOT NULL

    @Test void isNull()       throws Exception { assertEquals(true,  eval.eval(new UnaryOp("IS NULL",     new NullLiteral()), EvalContext.empty())); }
    @Test void isNotNull()    throws Exception { assertEquals(false, eval.eval(new UnaryOp("IS NOT NULL", new NullLiteral()), EvalContext.empty())); }
    @Test void isNullFalse()  throws Exception { assertEquals(false, eval.eval(new UnaryOp("IS NULL",     new IntegerLiteral(1)), EvalContext.empty())); }

    // =========================================================================
    // IS DISTINCT FROM

    @Test void isDistinctFromNulls() throws Exception {
        var expr = new BinaryOp("IS DISTINCT FROM", new NullLiteral(), new NullLiteral());
        assertEquals(false, eval.eval(expr, EvalContext.empty()));
    }
    @Test void isDistinctFromNullValue() throws Exception {
        var expr = new BinaryOp("IS DISTINCT FROM", new NullLiteral(), new IntegerLiteral(1));
        assertEquals(true, eval.eval(expr, EvalContext.empty()));
    }
    @Test void isNotDistinctFrom() throws Exception {
        var expr = new BinaryOp("IS NOT DISTINCT FROM", new IntegerLiteral(5), new IntegerLiteral(5));
        assertEquals(true, eval.eval(expr, EvalContext.empty()));
    }

    // =========================================================================
    // CAST

    @Test void castIntToText() throws Exception {
        var expr = new CastExpr(new IntegerLiteral(42), TypeName.of("text"));
        assertEquals("42", eval.eval(expr, EvalContext.empty()));
    }
    @Test void castStringToInt() throws Exception {
        var expr = new CastExpr(new StringLiteral("99"), TypeName.of("integer"));
        assertEquals(99, eval.eval(expr, EvalContext.empty()));
    }
    @Test void castStringToDate() throws Exception {
        var expr = new CastExpr(new StringLiteral("2024-01-15"), TypeName.of("date"));
        assertEquals(LocalDate.of(2024, 1, 15), eval.eval(expr, EvalContext.empty()));
    }
    @Test void castNullReturnsNull() throws Exception {
        var expr = new CastExpr(new NullLiteral(), TypeName.of("integer"));
        assertNull(eval.eval(expr, EvalContext.empty()));
    }

    // =========================================================================
    // CASE expressions

    @Test void caseSearched() throws Exception {
        // CASE WHEN id = 1 THEN 'one' WHEN id = 2 THEN 'two' ELSE 'other' END
        var expr = new CaseExpr(null, List.of(
                new CaseWhen(new BinaryOp("=", ColumnRef.of("id"), new IntegerLiteral(1)), new StringLiteral("one")),
                new CaseWhen(new BinaryOp("=", ColumnRef.of("id"), new IntegerLiteral(2)), new StringLiteral("two"))
        ), new StringLiteral("other"));
        assertEquals("one", eval.eval(expr, rowCtx));
    }

    @Test void caseSimple() throws Exception {
        // CASE id WHEN 1 THEN 'one' ELSE 'other' END
        var expr = new CaseExpr(ColumnRef.of("id"), List.of(
                new CaseWhen(new IntegerLiteral(1), new StringLiteral("one"))
        ), new StringLiteral("other"));
        assertEquals("one", eval.eval(expr, rowCtx));
    }

    @Test void caseElse() throws Exception {
        var expr = new CaseExpr(null, List.of(
                new CaseWhen(new BinaryOp("=", ColumnRef.of("id"), new IntegerLiteral(99)), new StringLiteral("no"))
        ), new StringLiteral("yes"));
        assertEquals("yes", eval.eval(expr, rowCtx));
    }

    @Test void caseNoElseReturnsNull() throws Exception {
        var expr = new CaseExpr(null, List.of(
                new CaseWhen(new BooleanLiteral(false), new StringLiteral("no"))
        ), null);
        assertNull(eval.eval(expr, rowCtx));
    }

    // =========================================================================
    // IN / NOT IN

    @Test void inList() throws Exception {
        var expr = new InExpr(ColumnRef.of("id"),
                List.of(new IntegerLiteral(1), new IntegerLiteral(2), new IntegerLiteral(3)),
                false);
        assertEquals(true, eval.eval(expr, rowCtx));
    }

    @Test void inListMiss() throws Exception {
        var expr = new InExpr(ColumnRef.of("id"),
                List.of(new IntegerLiteral(5), new IntegerLiteral(6)),
                false);
        assertEquals(false, eval.eval(expr, rowCtx));
    }

    @Test void notInList() throws Exception {
        var expr = new InExpr(ColumnRef.of("id"),
                List.of(new IntegerLiteral(5)),
                true);
        assertEquals(true, eval.eval(expr, rowCtx));
    }

    @Test void inListWithNull() throws Exception {
        // value NOT IN (1, NULL) → NULL (because list has null and value ≠ 1)
        var expr = new InExpr(new IntegerLiteral(2),
                List.of(new IntegerLiteral(1), new NullLiteral()),
                false);
        assertNull(eval.eval(expr, EvalContext.empty()));
    }

    // =========================================================================
    // BETWEEN

    @Test void betweenTrue() throws Exception {
        var expr = new BetweenExpr(new IntegerLiteral(5),
                new IntegerLiteral(1), new IntegerLiteral(10), false, false);
        assertEquals(true, eval.eval(expr, EvalContext.empty()));
    }

    @Test void betweenFalse() throws Exception {
        var expr = new BetweenExpr(new IntegerLiteral(0),
                new IntegerLiteral(1), new IntegerLiteral(10), false, false);
        assertEquals(false, eval.eval(expr, EvalContext.empty()));
    }

    @Test void notBetween() throws Exception {
        var expr = new BetweenExpr(new IntegerLiteral(0),
                new IntegerLiteral(1), new IntegerLiteral(10), true, false);
        assertEquals(true, eval.eval(expr, EvalContext.empty()));
    }

    @Test void betweenNullReturnsNull() throws Exception {
        var expr = new BetweenExpr(new NullLiteral(),
                new IntegerLiteral(1), new IntegerLiteral(10), false, false);
        assertNull(eval.eval(expr, EvalContext.empty()));
    }

    // =========================================================================
    // LIKE / ILIKE

    @Test void likeMatch() throws Exception {
        var expr = new LikeExpr(new StringLiteral("hello world"), new StringLiteral("hello%"),
                null, LikeType.LIKE, false);
        assertEquals(true, eval.eval(expr, EvalContext.empty()));
    }

    @Test void likeMiss() throws Exception {
        var expr = new LikeExpr(new StringLiteral("hello"), new StringLiteral("world%"),
                null, LikeType.LIKE, false);
        assertEquals(false, eval.eval(expr, EvalContext.empty()));
    }

    @Test void likeUnderscore() throws Exception {
        var expr = new LikeExpr(new StringLiteral("abc"), new StringLiteral("a_c"),
                null, LikeType.LIKE, false);
        assertEquals(true, eval.eval(expr, EvalContext.empty()));
    }

    @Test void notLike() throws Exception {
        var expr = new LikeExpr(new StringLiteral("hello"), new StringLiteral("world%"),
                null, LikeType.LIKE, true);
        assertEquals(true, eval.eval(expr, EvalContext.empty()));
    }

    @Test void iLikeCaseInsensitive() throws Exception {
        var expr = new LikeExpr(new StringLiteral("Hello"), new StringLiteral("hello%"),
                null, LikeType.ILIKE, false);
        assertEquals(true, eval.eval(expr, EvalContext.empty()));
    }

    @Test void likeNull() throws Exception {
        var expr = new LikeExpr(new NullLiteral(), new StringLiteral("a%"),
                null, LikeType.LIKE, false);
        assertNull(eval.eval(expr, EvalContext.empty()));
    }

    // =========================================================================
    // GREATEST / LEAST

    @Test void greatest() throws Exception {
        var expr = new MinMaxExpr(MinMaxOp.GREATEST, List.of(
                new IntegerLiteral(3), new IntegerLiteral(1), new IntegerLiteral(4),
                new IntegerLiteral(1), new IntegerLiteral(5)));
        assertEquals(5, eval.eval(expr, EvalContext.empty()));
    }

    @Test void least() throws Exception {
        var expr = new MinMaxExpr(MinMaxOp.LEAST, List.of(
                new IntegerLiteral(3), new IntegerLiteral(1), new IntegerLiteral(4)));
        assertEquals(1, eval.eval(expr, EvalContext.empty()));
    }

    @Test void greatestSkipsNull() throws Exception {
        var expr = new MinMaxExpr(MinMaxOp.GREATEST, List.of(
                new NullLiteral(), new IntegerLiteral(7)));
        assertEquals(7, eval.eval(expr, EvalContext.empty()));
    }

    @Test void greatestAllNull() throws Exception {
        var expr = new MinMaxExpr(MinMaxOp.GREATEST, List.of(
                new NullLiteral(), new NullLiteral()));
        assertNull(eval.eval(expr, EvalContext.empty()));
    }

    // =========================================================================
    // ARRAY constructor and subscript

    @Test void arrayConstructor() throws Exception {
        var expr = new ArrayExpr(
                List.of(new IntegerLiteral(1), new IntegerLiteral(2), new IntegerLiteral(3)));
        var result = eval.eval(expr, EvalContext.empty());
        assertInstanceOf(List.class, result);
        assertEquals(List.of(1, 2, 3), result);
    }

    @Test void arraySubscript() throws Exception {
        // ARRAY[10,20,30][2] → 20
        var arr = new ArrayExpr(
                List.of(new IntegerLiteral(10), new IntegerLiteral(20), new IntegerLiteral(30)));
        var sub = new SubscriptExpr(arr, new IntegerLiteral(2), null, false);
        assertEquals(20, eval.eval(sub, EvalContext.empty()));
    }

    @Test void arraySubscriptOutOfBounds() throws Exception {
        var arr = new ArrayExpr(List.of(new IntegerLiteral(1)));
        var sub = new SubscriptExpr(arr, new IntegerLiteral(99), null, false);
        assertNull(eval.eval(sub, EvalContext.empty())); // out of bounds → NULL
    }

    // =========================================================================
    // Function calls (dispatched via FunctionRegistry)

    @Test void functionLower() throws Exception {
        var expr = FunctionCall.simple("lower", List.of(new StringLiteral("HELLO")));
        assertEquals("hello", eval.eval(expr, EvalContext.empty()));
    }

    @Test void functionUpper() throws Exception {
        var expr = FunctionCall.simple("upper", List.of(new StringLiteral("world")));
        assertEquals("WORLD", eval.eval(expr, EvalContext.empty()));
    }

    @Test void functionLength() throws Exception {
        var expr = FunctionCall.simple("length", List.of(new StringLiteral("hello")));
        assertEquals(5, eval.eval(expr, EvalContext.empty()));
    }

    @Test void functionAbs() throws Exception {
        var expr = FunctionCall.simple("abs", List.of(new IntegerLiteral(-7)));
        assertEquals(7, eval.eval(expr, EvalContext.empty()));
    }

    @Test void functionNow() throws Exception {
        var expr = FunctionCall.simple("now", List.of());
        var result = eval.eval(expr, rowCtx);
        assertInstanceOf(OffsetDateTime.class, result);
    }

    @Test void functionCoalesce() throws Exception {
        var expr = FunctionCall.simple("coalesce",
                List.of(new NullLiteral(), new NullLiteral(), new StringLiteral("found")));
        assertEquals("found", eval.eval(expr, EvalContext.empty()));
    }

    @Test void functionNullif() throws Exception {
        var expr = FunctionCall.simple("nullif",
                List.of(new IntegerLiteral(5), new IntegerLiteral(5)));
        assertNull(eval.eval(expr, EvalContext.empty()));
    }

    @Test void functionNullifNotEqual() throws Exception {
        var expr = FunctionCall.simple("nullif",
                List.of(new IntegerLiteral(5), new IntegerLiteral(6)));
        assertEquals(5, eval.eval(expr, EvalContext.empty()));
    }

    @Test void functionStrictReturnsNullOnNullArg() throws Exception {
        // lower(NULL) → NULL (strict function)
        var expr = FunctionCall.simple("lower", List.of(new NullLiteral()));
        assertNull(eval.eval(expr, EvalContext.empty()));
    }

    @Test void functionSubstr() throws Exception {
        var expr = FunctionCall.simple("substr",
                List.of(new StringLiteral("hello"), new IntegerLiteral(2), new IntegerLiteral(3)));
        assertEquals("ell", eval.eval(expr, EvalContext.empty()));
    }

    @Test void functionConcat() throws Exception {
        var expr = FunctionCall.simple("concat",
                List.of(new StringLiteral("a"), new StringLiteral("b"), new StringLiteral("c")));
        assertEquals("abc", eval.eval(expr, EvalContext.empty()));
    }

    @Test void functionReplace() throws Exception {
        var expr = FunctionCall.simple("replace",
                List.of(new StringLiteral("hello"), new StringLiteral("l"), new StringLiteral("r")));
        assertEquals("herro", eval.eval(expr, EvalContext.empty()));
    }

    // =========================================================================
    // Regex operators

    @Test void regexMatch() throws Exception {
        var expr = new BinaryOp("~", new StringLiteral("hello"), new StringLiteral("hel+o"));
        assertEquals(true, eval.eval(expr, EvalContext.empty()));
    }

    @Test void regexNoMatch() throws Exception {
        var expr = new BinaryOp("~", new StringLiteral("hello"), new StringLiteral("^world"));
        assertEquals(false, eval.eval(expr, EvalContext.empty()));
    }

    @Test void regexCaseInsensitive() throws Exception {
        var expr = new BinaryOp("~*", new StringLiteral("Hello"), new StringLiteral("hello"));
        assertEquals(true, eval.eval(expr, EvalContext.empty()));
    }

    @Test void regexNegated() throws Exception {
        var expr = new BinaryOp("!~", new StringLiteral("hello"), new StringLiteral("^world"));
        assertEquals(true, eval.eval(expr, EvalContext.empty()));
    }

    // =========================================================================
    // EvalContext: params, multi-alias, outer

    @Test void paramRef() throws Exception {
        var cols = EvalContext.ColumnMap.of(List.of());
        var row  = new Row(new RowId(1L, 0), new Object[0]);
        var ctx  = EvalContext.of(row, cols).withParams(new Object[]{"p1val", 42});
        assertEquals("p1val", eval.eval(new ParamRef(1), ctx));
        assertEquals(42,      eval.eval(new ParamRef(2), ctx));
    }

    @Test void statementTimestampIsFixed() throws Exception {
        OffsetDateTime ts = OffsetDateTime.parse("2024-06-15T10:00:00Z");
        var ctx = rowCtx.withTimestamp(ts);
        // now() returns the statement timestamp, not wall clock
        var expr = FunctionCall.simple("now", List.of());
        assertEquals(ts, eval.eval(expr, ctx));
    }

    @Test void unsupportedExprThrows() {
        // Sublinks require Phase 9
        var sl = new SubLink(SubLinkType.EXISTS, null, null,
                new SelectStmt(List.of(), List.of(), null, List.of(), null, List.of(),
                        null, null, List.of(), false, List.of(), null, null, null,
                        false, List.of(), null, List.of()));
        assertThrows(Exception.class, () -> eval.eval(sl, EvalContext.empty()));
    }

    // =========================================================================
    // BigDecimal arithmetic

    @Test void bigDecimalArith() throws Exception {
        // 1.5 + 2.5 = 4.0 (BigDecimal path via FloatLiteral → Double, but explicit numeric)
        var expr = new BinaryOp("+",
                new CastExpr(new StringLiteral("1.5"), TypeName.of("numeric")),
                new CastExpr(new StringLiteral("2.5"), TypeName.of("numeric")));
        Object result = eval.eval(expr, EvalContext.empty());
        assertEquals(0, new BigDecimal("4.0").compareTo((BigDecimal) result));
    }

    @Test void bigDecimalDivision() throws Exception {
        var expr = new BinaryOp("/",
                new CastExpr(new StringLiteral("10"), TypeName.of("numeric")),
                new CastExpr(new StringLiteral("3"),  TypeName.of("numeric")));
        Object result = eval.eval(expr, EvalContext.empty());
        assertInstanceOf(BigDecimal.class, result);
        // 10/3 ≈ 3.333...
        assertTrue(((BigDecimal) result).compareTo(new BigDecimal("3.3")) > 0);
    }

    // =========================================================================
    // Helpers

    private Object arith(Object l, String op, Object r) throws Exception {
        return arithObj(toLit(l), op, toLit(r));
    }

    private Object arithObj(Expr l, String op, Expr r) throws Exception {
        return eval.eval(new BinaryOp(op, l, r), EvalContext.empty());
    }

    private Object cmp(Object l, String op, Object r) throws Exception {
        return eval.eval(new BinaryOp(op, toLit(l), toLit(r)), EvalContext.empty());
    }

    private Object and(boolean l, boolean r) throws Exception {
        return eval.eval(new BinaryOp("AND", new BooleanLiteral(l), new BooleanLiteral(r)),
                EvalContext.empty());
    }
    private Object andWithNull(boolean r) throws Exception {
        return eval.eval(new BinaryOp("AND", new NullLiteral(), new BooleanLiteral(r)),
                EvalContext.empty());
    }
    private Object or(boolean l, boolean r) throws Exception {
        return eval.eval(new BinaryOp("OR", new BooleanLiteral(l), new BooleanLiteral(r)),
                EvalContext.empty());
    }
    private Object orWithNull(boolean r) throws Exception {
        return eval.eval(new BinaryOp("OR", new NullLiteral(), new BooleanLiteral(r)),
                EvalContext.empty());
    }
    private Object not(boolean v) throws Exception {
        return eval.eval(new UnaryOp("NOT", new BooleanLiteral(v)), EvalContext.empty());
    }

    private Expr toLit(Object v) {
        if (v instanceof Integer i) return new IntegerLiteral(i);
        if (v instanceof Long l)    return new IntegerLiteral(l);
        if (v instanceof Double d)  return new FloatLiteral(String.valueOf(d));
        if (v instanceof String s)  return new StringLiteral(s);
        if (v instanceof Boolean b) return new BooleanLiteral(b);
        return new NullLiteral();
    }

    @FunctionalInterface interface CheckedRunnable { void run() throws Exception; }

    private void assertThrowsSQLState(String state, CheckedRunnable r) {
        try {
            r.run();
            fail("expected SQLException with SQLSTATE " + state);
        } catch (java.sql.SQLException e) {
            assertEquals(state, e.getSQLState());
        } catch (Exception e) {
            fail("expected SQLException, got " + e);
        }
    }
}
