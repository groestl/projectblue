package org.pgjava.executor;

import org.pgjava.catalog.FunctionDef;
import org.pgjava.catalog.FunctionRegistry;
import org.pgjava.engine.PgErrorException;
import org.pgjava.sql.ast.*;
import org.pgjava.storage.Row;
import org.pgjava.types.*;
import org.pgjava.types.CoercionContext;
import org.pgjava.types.JsonOps;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Scalar expression evaluator.
 *
 * <p>Evaluates any {@link Expr} AST node to a Java value given an
 * {@link EvalContext}.  NULL is represented as Java {@code null} throughout.
 *
 * <p>Three-valued logic: comparisons involving NULL produce NULL (not false),
 * matching PostgreSQL semantics exactly.
 *
 * <p>Arithmetic type promotion follows PG rules: int4 op int4 → int4,
 * int4 op int8 → int8, any int op float → float8, any int/float op numeric
 * → numeric.
 */
public final class Evaluator {

    /**
     * Executes a subquery and returns all rows it produces.
     * Used for EXISTS / scalar / ANY / ALL / IN-subquery / ARRAY(subquery).
     *
     * <p>Implemented by {@link org.pgjava.executor.Planner} and injected via
     * {@link #setSubqueryExecutor} to avoid a circular compile-time dependency.
     */
    @FunctionalInterface
    public interface SubqueryExecutor {
        List<Row> execute(org.pgjava.sql.ast.SelectStmt stmt, EvalContext outerCtx)
                throws SQLException;
    }

    private final FunctionRegistry functions;
    /** Optional per-session functions checked before the shared registry. */
    private FunctionRegistry       sessionFunctions;
    private final PgTypeRegistry   types;
    private final PgCollation      defaultCollation;

    /** Outer-query context threaded in for correlated subquery evaluation. */
    private EvalContext       outerCtx;
    /** Executes subqueries (set by Session after Planner is constructed). */
    private SubqueryExecutor  subqueryExecutor;
    /** Function params ($1, $2, ...) injected into every eval context. */
    private Object[]          functionParams;
    /** PL/pgSQL variable resolver — checked when ColumnRef resolution fails. */
    private VariableResolver  variableResolver;
    /** Resolves user-defined type names from schema (fallback after global registry). */
    private java.util.function.Function<String, PgType> schemaTypeResolver;

    /**
     * Resolves PL/pgSQL variable names to their current values.
     * Used as a fallback when ColumnRef resolution fails in EvalContext.
     */
    public interface VariableResolver {
        /** Sentinel returned by resolveField when the qualifier.field pair is not recognized. */
        Object UNRESOLVED = new Object();

        Object resolve(String name) throws SQLException;

        /**
         * Resolve a qualified field access (e.g. NEW.column_name).
         * Returns the field value, null (for NULL fields), or {@link #UNRESOLVED}
         * if the qualifier is not a known composite variable.
         */
        default Object resolveField(String qualifier, String field) throws SQLException {
            return UNRESOLVED;
        }
    }

    public Evaluator(FunctionRegistry functions, PgCollation defaultCollation) {
        this.functions        = functions;
        this.types            = PgTypeRegistry.INSTANCE;
        this.defaultCollation = defaultCollation;
    }

    public PgCollation defaultCollation() { return defaultCollation; }

    /** Register additional session-scoped functions (e.g. pg_notify). */
    public void setSessionFunctions(FunctionRegistry sf) {
        this.sessionFunctions = sf;
    }

    /** Set function params ($1...$N) that will be injected into every eval context. */
    public void setFunctionParams(Object[] params) {
        this.functionParams = params;
    }

    /** Set a resolver for user-defined types stored in schema (enums, domains). */
    public void setSchemaTypeResolver(java.util.function.Function<String, PgType> resolver) {
        this.schemaTypeResolver = resolver;
    }

    /** Set a PL/pgSQL variable resolver for ColumnRef fallback resolution. */
    public void setVariableResolver(VariableResolver resolver) {
        this.variableResolver = resolver;
    }

    /**
     * Find a set-returning function by name and argument count.
     * Checks session-local registry first, then the shared database registry.
     */
    public org.pgjava.catalog.SrfDef findSrf(String name, int argCount) {
        if (sessionFunctions != null) {
            var srf = sessionFunctions.findSrf(name, argCount);
            if (srf != null) return srf;
        }
        return functions.findSrf(name, argCount);
    }

    /** Set the subquery executor (called from Session after planner construction). */
    public void setSubqueryExecutor(SubqueryExecutor executor) {
        this.subqueryExecutor = executor;
    }

    /**
     * Return a child evaluator that attaches {@code outerCtx} to every context
     * before evaluation — enabling correlated subquery column resolution.
     */
    public Evaluator withOuterContext(EvalContext outerCtx) {
        Evaluator child = new Evaluator(functions, defaultCollation);
        child.outerCtx           = outerCtx;
        child.subqueryExecutor   = this.subqueryExecutor;
        child.sessionFunctions   = this.sessionFunctions;
        child.functionParams     = this.functionParams;
        child.variableResolver   = this.variableResolver;
        child.schemaTypeResolver = this.schemaTypeResolver;
        return child;
    }

    // -------------------------------------------------------------------------
    // Entry point

    /**
     * Evaluate an expression in the given context.
     *
     * @return the Java value, or {@code null} for SQL NULL
     * @throws SQLException on type errors, division by zero, invalid literals, etc.
     */
    public Object eval(Expr expr, EvalContext ctx) throws SQLException {
        // Thread outer context so correlated subquery conditions can resolve outer columns
        if (outerCtx != null) ctx = ctx.withOuter(outerCtx);
        // Inject function params ($1...$N) if set and context doesn't already have params
        if (functionParams != null && ctx.params().length == 0) ctx = ctx.withParams(functionParams);
        return switch (expr) {
            case NullLiteral ignored           -> null;
            case BooleanLiteral b             -> b.value();
            case IntegerLiteral i             -> {
                long v = i.value();
                if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE)
                    yield Integer.valueOf((int) v);
                yield Long.valueOf(v);
            }
            case FloatLiteral f               -> parseFloat(f.rawStr());
            case StringLiteral s              -> s.value();
            case TypedLiteral tl              -> evalTypedLiteral(tl);
            case IntervalLiteral il           -> evalIntervalLiteral(il);
            case ColumnRef cr                 -> resolveColumnOrVar(cr, ctx);
            case ParamRef pr                  -> ctx.resolveParam(pr.number());
            case BinaryOp bo                  -> evalBinary(bo, ctx);
            case UnaryOp uo                   -> evalUnary(uo, ctx);
            case CastExpr ce                  -> evalCast(ce, ctx);
            case FunctionCall fc              -> evalFunction(fc, ctx);
            case CaseExpr ca                  -> evalCase(ca, ctx);
            case InExpr ie                    -> evalIn(ie, ctx);
            case BetweenExpr be               -> evalBetween(be, ctx);
            case LikeExpr le                  -> evalLike(le, ctx);
            case MinMaxExpr mm                -> evalMinMax(mm, ctx);
            case RowExpr re                   -> evalRow(re, ctx);
            case ArrayExpr ae                 -> evalArray(ae, ctx);
            case SubscriptExpr se             -> evalSubscript(se, ctx);
            case FieldSelectExpr fs           -> evalFieldSelect(fs, ctx);
            case CollateExpr co               -> evalCollate(co, ctx);
            case NamedArgExpr na              -> eval(na.arg(), ctx);
            case ArrayAnyAllExpr aa           -> evalArrayAnyAll(aa, ctx);
            case GroupingExpr ignored         -> 0;   // GROUPING() → 0 outside GROUP BY
            case SetToDefault ignored         -> null; // resolved by DML layer
            case SubLink sl                   -> evalSubLink(sl, ctx);
            case InSubselect is               -> evalInSubselect(is, ctx);
            case ArraySubselect as            -> evalArraySubselect(as, ctx);
            default -> throw PgErrorException.error("0A000",
                    "unsupported expression type: " + expr.getClass().getSimpleName()).build();
        };
    }

    // -------------------------------------------------------------------------
    // ColumnRef with PL/pgSQL variable fallback

    private Object resolveColumnOrVar(ColumnRef cr, EvalContext ctx) throws SQLException {
        try {
            return ctx.resolveColumn(cr.fields());
        } catch (SQLException e) {
            if (variableResolver != null) {
                if (cr.fields().size() == 1) {
                    // Unqualified single-part name — try PL/pgSQL variable
                    String name = cr.fields().get(0);
                    try {
                        Object val = variableResolver.resolve(name);
                        // resolve() throws if the variable doesn't exist;
                        // if it returns (even null), the variable exists.
                        return val;
                    } catch (SQLException ignored) { /* fall through to original error */ }
                } else if (cr.fields().size() == 2) {
                    // Qualified two-part name (e.g. NEW.col) — try composite variable field access
                    String qualifier = cr.fields().get(0);
                    String field = cr.fields().get(1);
                    try {
                        Object val = variableResolver.resolveField(qualifier, field);
                        if (val != VariableResolver.UNRESOLVED) return val;
                    } catch (SQLException ignored) { /* fall through to original error */ }
                }
            }
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // COLLATE

    private Object evalCollate(CollateExpr co, EvalContext ctx) throws SQLException {
        String collName = co.collationName().getLast();
        PgCollation coll = PgCollation.resolveOrError(collName);
        Object val = eval(co.arg(), ctx);
        if (val == null) return null;
        return new CollatedValue(CollatedValue.unwrap(val), coll);
    }

    // -------------------------------------------------------------------------
    // Literals

    private Object parseFloat(String raw) throws SQLException {
        try {
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                return Double.parseDouble(raw);
            }
            long l = Long.parseLong(raw);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
            return l;
        } catch (NumberFormatException e) {
            throw PgErrorException.error("22P02", "invalid input syntax for type numeric: \"" + raw + "\"").build();
        }
    }

    private Object evalTypedLiteral(TypedLiteral tl) throws SQLException {
        String typName = tl.typeName().simpleName();
        PgType type = types.byTypeName(typName);
        if (type == null && schemaTypeResolver != null) type = schemaTypeResolver.apply(typName);
        if (type == null)
            throw PgErrorException.error("42704", "type \"" + typName + "\" does not exist").build();
        // For enums, validate and return the string value (ConstraintChecker wraps it later)
        if (type instanceof org.pgjava.types.EnumType enumType) {
            String val = tl.value();
            if (!enumType.labels().contains(val))
                throw PgErrorException.error("22P02",
                        "invalid input value for enum " + enumType.name() + ": \"" + val + "\"").build();
            return val;
        }
        return TypeInput.parse(tl.value(), type.oid());
    }

    private Object evalIntervalLiteral(IntervalLiteral il) throws SQLException {
        return PgInterval.parse(il.value());
    }

    // -------------------------------------------------------------------------
    // Binary operators

    private Object evalBinary(BinaryOp bo, EvalContext ctx) throws SQLException {
        String op = bo.op().toUpperCase();

        // Short-circuit AND / OR before evaluating right
        if ("AND".equals(op)) return evalAnd(bo, ctx);
        if ("OR".equals(op))  return evalOr(bo, ctx);

        Object left  = eval(bo.left(),  ctx);
        Object right = eval(bo.right(), ctx);

        return switch (op) {
            // Comparison
            case "="  -> sqlEquals(left, right);
            case "<>" , "!=" -> sqlNotEquals(left, right);
            case "<"  -> sqlCompare(left, right, op);
            case ">"  -> sqlCompare(left, right, op);
            case "<=" -> sqlCompare(left, right, op);
            case ">=" -> sqlCompare(left, right, op);
            case "IS DISTINCT FROM"     -> isDistinctFrom(left, right);
            case "IS NOT DISTINCT FROM" -> isNotDistinctFrom(left, right);
            // Arithmetic
            case "+"  -> arith(left, right, op);
            case "-"  -> arith(left, right, op);
            case "*"  -> arith(left, right, op);
            case "/"  -> arith(left, right, op);
            case "%"  -> arith(left, right, op);
            case "^"  -> arithPow(left, right);
            // String
            case "||" -> sqlConcat(left, right);
            // Range / array / JSON operators
            case "@>"  -> evalContains(left, right);
            case "<@"  -> evalContainedBy(left, right);
            case "&&"  -> evalOverlaps(left, right);
            // JSON operators
            case "->"  -> JsonOps.extractJson(asJsonString(left), right);
            case "->>" -> JsonOps.extractText(asJsonString(left), right);
            case "#>"  -> JsonOps.extractPath(asJsonString(left), right);
            case "#>>" -> JsonOps.extractPathText(asJsonString(left), right);
            case "?"   -> JsonOps.keyExists(asJsonString(left), asString(right));
            case "?|"  -> JsonOps.anyKeyExists(asJsonString(left), right);
            case "?&"  -> JsonOps.allKeysExist(asJsonString(left), right);
            // Regex
            case "~"  -> sqlRegex(left, right, false, false);
            case "~*" -> sqlRegex(left, right, false, true);
            case "!~" -> sqlRegex(left, right, true,  false);
            case "!~*"-> sqlRegex(left, right, true,  true);
            default   -> throw PgErrorException.error("42883", "operator does not exist: " + op).build();
        };
    }

    // Three-valued AND
    private Object evalAnd(BinaryOp bo, EvalContext ctx) throws SQLException {
        Object left = eval(bo.left(), ctx);
        if (Boolean.FALSE.equals(left)) return Boolean.FALSE;
        Object right = eval(bo.right(), ctx);
        if (Boolean.FALSE.equals(right)) return Boolean.FALSE;
        if (left == null || right == null) return null;
        return Boolean.TRUE;
    }

    // Three-valued OR
    private Object evalOr(BinaryOp bo, EvalContext ctx) throws SQLException {
        Object left = eval(bo.left(), ctx);
        if (Boolean.TRUE.equals(left)) return Boolean.TRUE;
        Object right = eval(bo.right(), ctx);
        if (Boolean.TRUE.equals(right)) return Boolean.TRUE;
        if (left == null || right == null) return null;
        return Boolean.FALSE;
    }

    private Object sqlEquals(Object l, Object r) throws SQLException {
        if (l == null || r == null) return null; // NULL comparison → NULL
        // Row comparison: (a, b) = (c, d) → element-wise
        if (l instanceof Object[] la && r instanceof Object[] ra) {
            if (la.length != ra.length) return false;
            for (int i = 0; i < la.length; i++) {
                Object eq = sqlEquals(la[i], ra[i]);
                if (eq == null) return null;
                if (!Boolean.TRUE.equals(eq)) return false;
            }
            return true;
        }
        return numericAware(l, r) == 0;
    }

    private Object sqlNotEquals(Object l, Object r) throws SQLException {
        if (l == null || r == null) return null;
        // Row comparison: element-wise
        if (l instanceof Object[] la && r instanceof Object[] ra) {
            Object eq = sqlEquals(l, r);
            if (eq == null) return null;
            return !Boolean.TRUE.equals(eq);
        }
        return numericAware(l, r) != 0;
    }

    private Object sqlCompare(Object l, Object r, String op) throws SQLException {
        if (l == null || r == null) return null;
        int cmp;
        if (l instanceof Object[] la && r instanceof Object[] ra) {
            cmp = rowCompare(la, ra);
        } else {
            cmp = numericAware(l, r);
        }
        return switch (op) {
            case "<"  -> cmp <  0;
            case ">"  -> cmp >  0;
            case "<=" -> cmp <= 0;
            case ">=" -> cmp >= 0;
            default   -> throw PgErrorException.error("42883", "operator does not exist: " + op).build();
        };
    }

    /** Lexicographic comparison for row expressions (element-wise, left to right). */
    private int rowCompare(Object[] la, Object[] ra) throws SQLException {
        int len = Math.min(la.length, ra.length);
        for (int i = 0; i < len; i++) {
            if (la[i] == null && ra[i] == null) continue;
            if (la[i] == null) return -1;
            if (ra[i] == null) return 1;
            int c = numericAware(la[i], ra[i]);
            if (c != 0) return c;
        }
        return Integer.compare(la.length, ra.length);
    }

    /** Compare two values, handling Object[] (row expressions) element-wise. */
    private int compareAware(Object l, Object r) throws SQLException {
        if (l instanceof Object[] la && r instanceof Object[] ra) return rowCompare(la, ra);
        return numericAware(l, r);
    }

    private Boolean isDistinctFrom(Object l, Object r) throws SQLException {
        if (l == null && r == null) return false;
        if (l == null || r == null) return true;
        if (l instanceof Object[] && r instanceof Object[]) {
            Object eq = sqlEquals(l, r);
            return !Boolean.TRUE.equals(eq);
        }
        return numericAware(l, r) != 0;
    }

    private Boolean isNotDistinctFrom(Object l, Object r) throws SQLException {
        return !isDistinctFrom(l, r);
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private int numericAware(Object l, Object r) throws SQLException {
        // Unwrap CollatedValue and resolve collation
        PgCollation lColl = CollatedValue.collationOf(l);
        PgCollation rColl = CollatedValue.collationOf(r);
        l = CollatedValue.unwrap(l);
        r = CollatedValue.unwrap(r);

        if (isNumericType(l) || isNumericType(r)) {
            // Handle Infinity/NaN before BigDecimal conversion (BigDecimal cannot represent them)
            if (l instanceof Double dl && (dl.isInfinite() || dl.isNaN())
                    || r instanceof Double dr && (dr.isInfinite() || dr.isNaN())
                    || l instanceof Float fl && (fl.isInfinite() || fl.isNaN())
                    || r instanceof Float fr && (fr.isInfinite() || fr.isNaN())) {
                return Double.compare(toDouble(l), toDouble(r));
            }
            return toBigDecimal(l).compareTo(toBigDecimal(r));
        }
        // Coerce string to temporal if comparing string vs temporal
        if (l instanceof String s && isTemporalType(r)) {
            l = coerceStringToTemporal(s, r);
        } else if (r instanceof String s && isTemporalType(l)) {
            r = coerceStringToTemporal(s, l);
        }
        // Normalize cross-temporal comparisons so Comparable.compareTo doesn't crash
        Object[] norm = normalizeTemporal(l, r);
        l = norm[0]; r = norm[1];
        // Enum value comparison — by ordinal, not alphabetically
        if (l instanceof org.pgjava.types.EnumValue el && r instanceof org.pgjava.types.EnumValue er) {
            return el.compareTo(er);
        }
        if (l instanceof org.pgjava.types.EnumValue el && r instanceof String sr) {
            try { return el.compareToLabel(sr); }
            catch (IllegalArgumentException e) {
                throw PgErrorException.error("22P02", e.getMessage()).build();
            }
        }
        if (l instanceof String sl && r instanceof org.pgjava.types.EnumValue er) {
            try { return -er.compareToLabel(sl); }
            catch (IllegalArgumentException e) {
                throw PgErrorException.error("22P02", e.getMessage()).build();
            }
        }
        if (l instanceof String sl && r instanceof String sr) {
            PgCollation coll = PgCollation.resolveConflict(lColl, rColl, defaultCollation);
            return coll.compare(sl, sr);
        }
        if (l instanceof Comparable c) return c.compareTo(r);
        PgCollation coll = PgCollation.resolveConflict(lColl, rColl, defaultCollation);
        return coll.compare(l.toString(), r.toString());
    }

    private boolean isNumericType(Object v) {
        return v instanceof Integer || v instanceof Long || v instanceof Short
                || v instanceof Float || v instanceof Double || v instanceof BigDecimal;
    }

    private static boolean isTemporalType(Object v) {
        return v instanceof java.time.temporal.Temporal || v instanceof LocalDate;
    }

    /**
     * If value looks like a JSON string (starts with { or [), return it.
     * Returns null for non-JSON strings and non-string values.
     */
    private static String asJsonString(Object v) {
        if (v == null) return null;
        if (!(v instanceof String s)) return null;
        if (s.isEmpty()) return null;
        char c = s.charAt(0);
        return (c == '{' || c == '[' || c == '"'
                || s.equals("true") || s.equals("false") || s.equals("null")
                || (c >= '0' && c <= '9') || c == '-')
                ? s : null;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    /** Coerce a string to a temporal type matching the target. */
    private static Object coerceStringToTemporal(String s, Object target) throws SQLException {
        if (target instanceof OffsetDateTime)
            return TypeInput.parse(s, PgOid.TIMESTAMPTZ);
        if (target instanceof LocalDateTime)
            return TypeInput.parse(s, PgOid.TIMESTAMP);
        if (target instanceof LocalDate)
            return TypeInput.parse(s, PgOid.DATE);
        if (target instanceof LocalTime)
            return TypeInput.parse(s, PgOid.TIME);
        if (target instanceof OffsetTime)
            return TypeInput.parse(s, PgOid.TIMETZ);
        return s; // unknown temporal type — pass through
    }

    /**
     * Normalize temporal types so they are mutually Comparable.
     * PostgreSQL implicitly casts between timestamp/timestamptz by assuming UTC.
     * LocalDate is promoted to LocalDateTime at midnight.
     * LocalTime/OffsetTime are normalized to OffsetTime (assuming UTC offset).
     */
    private static Object[] normalizeTemporal(Object l, Object r) {
        // timestamp vs timestamptz → both to OffsetDateTime (assume UTC for LocalDateTime)
        if (l instanceof java.time.LocalDateTime ldt && r instanceof OffsetDateTime) {
            return new Object[]{ldt.atOffset(java.time.ZoneOffset.UTC), r};
        }
        if (l instanceof OffsetDateTime && r instanceof java.time.LocalDateTime rdt) {
            return new Object[]{l, rdt.atOffset(java.time.ZoneOffset.UTC)};
        }
        // date vs timestamp → both to LocalDateTime
        if (l instanceof java.time.LocalDate ld && r instanceof java.time.LocalDateTime) {
            return new Object[]{ld.atStartOfDay(), r};
        }
        if (l instanceof java.time.LocalDateTime && r instanceof java.time.LocalDate rd) {
            return new Object[]{l, rd.atStartOfDay()};
        }
        // date vs timestamptz → both to OffsetDateTime
        if (l instanceof java.time.LocalDate ld && r instanceof OffsetDateTime) {
            return new Object[]{ld.atStartOfDay().atOffset(java.time.ZoneOffset.UTC), r};
        }
        if (l instanceof OffsetDateTime && r instanceof java.time.LocalDate rd) {
            return new Object[]{l, rd.atStartOfDay().atOffset(java.time.ZoneOffset.UTC)};
        }
        // time vs timetz → both to OffsetTime
        if (l instanceof java.time.LocalTime lt && r instanceof java.time.OffsetTime) {
            return new Object[]{lt.atOffset(java.time.ZoneOffset.UTC), r};
        }
        if (l instanceof java.time.OffsetTime && r instanceof java.time.LocalTime rt) {
            return new Object[]{l, rt.atOffset(java.time.ZoneOffset.UTC)};
        }
        return new Object[]{l, r};
    }

    // -------------------------------------------------------------------------
    // Arithmetic

    private Object arith(Object l, Object r, String op) throws SQLException {
        if (l == null || r == null) return null;

        // numeric × numeric → numeric
        if (l instanceof BigDecimal || r instanceof BigDecimal) {
            BigDecimal a = toBigDecimal(l), b = toBigDecimal(r);
            return switch (op) {
                case "+" -> a.add(b);
                case "-" -> a.subtract(b);
                case "*" -> a.multiply(b);
                case "/" -> {
                    if (b.compareTo(BigDecimal.ZERO) == 0)
                        throw PgErrorException.error("22012", "division by zero").build();
                    yield a.divide(b, 10, RoundingMode.HALF_UP).stripTrailingZeros();
                }
                case "%" -> {
                    if (b.compareTo(BigDecimal.ZERO) == 0)
                        throw PgErrorException.error("22012", "division by zero").build();
                    yield a.remainder(b);
                }
                default -> throw PgErrorException.error("42883", "operator does not exist: " + op).build();
            };
        }

        // float × float → float8
        if (l instanceof Double || r instanceof Double
                || l instanceof Float || r instanceof Float) {
            double a = toDouble(l), b = toDouble(r);
            return switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> {
                    if (b == 0.0) throw PgErrorException.error("22012", "division by zero").build();
                    yield a / b;
                }
                case "%" -> {
                    if (b == 0.0) throw PgErrorException.error("22012", "division by zero").build();
                    yield a % b;
                }
                default -> throw PgErrorException.error("42883", "operator does not exist: " + op).build();
            };
        }

        // int8 × int8 → int8
        if (l instanceof Long || r instanceof Long) {
            long a = toLong(l), b = toLong(r);
            try {
                return switch (op) {
                    case "+" -> Math.addExact(a, b);
                    case "-" -> Math.subtractExact(a, b);
                    case "*" -> Math.multiplyExact(a, b);
                    case "/" -> {
                        if (b == 0L) throw PgErrorException.error("22012", "division by zero").build();
                        if (a == Long.MIN_VALUE && b == -1L)
                            throw PgErrorException.error("22003", "bigint out of range").build();
                        yield a / b;
                    }
                    case "%" -> {
                        if (b == 0L) throw PgErrorException.error("22012", "division by zero").build();
                        yield a % b;
                    }
                    default -> throw PgErrorException.error("42883", "operator does not exist: " + op).build();
                };
            } catch (ArithmeticException e) {
                throw PgErrorException.error("22003", "bigint out of range").build();
            }
        }

        // Interval arithmetic: timestamp ± interval, interval ± interval, interval × number
        // Must come before date+int to prevent PgInterval being passed to toLong()
        if (l instanceof PgInterval li && r instanceof PgInterval ri) {
            return switch (op) {
                case "+" -> li.plus(ri);
                case "-" -> li.plus(ri.negate());
                default  -> throw PgErrorException.error("42883", "operator does not exist: " + op + " for type interval").build();
            };
        }
        if (r instanceof PgInterval ri) {
            return applyInterval(l, ri, op);
        }
        if (l instanceof PgInterval li) {
            if ("+".equals(op)) return applyInterval(r, li, "+");
            if ("*".equals(op)) return scaleInterval(li, toDouble(r));
            throw PgErrorException.error("42883", "operator does not exist: " + op + " for type interval").build();
        }

        // Timestamp arithmetic: timestamp - timestamp → interval
        if (l instanceof LocalDateTime ldt && r instanceof LocalDateTime rdt && "-".equals(op)) {
            Duration d = Duration.between(rdt, ldt);
            return new PgInterval(0, 0, d.getSeconds() * 1_000_000L + d.getNano() / 1000L);
        }
        if (l instanceof OffsetDateTime lodt && r instanceof OffsetDateTime rodt && "-".equals(op)) {
            Duration d = Duration.between(rodt, lodt);
            return new PgInterval(0, 0, d.getSeconds() * 1_000_000L + d.getNano() / 1000L);
        }

        // Time arithmetic: time - time → interval
        if (l instanceof LocalTime lt && r instanceof LocalTime rt && "-".equals(op)) {
            Duration d = Duration.between(rt, lt);
            return new PgInterval(0, 0, d.getSeconds() * 1_000_000L + d.getNano() / 1000L);
        }

        // Date arithmetic
        if (l instanceof LocalDate ld && r instanceof LocalDate rd) {
            if ("-".equals(op)) return (int) (ld.toEpochDay() - rd.toEpochDay());
            throw PgErrorException.error("42883", "operator does not exist: " + op + " for type date").build();
        }
        if (l instanceof LocalDate ld) {
            long n = toLong(r);
            return switch (op) {
                case "+" -> ld.plusDays(n);
                case "-" -> ld.minusDays(n);
                default  -> throw PgErrorException.error("42883", "operator does not exist: " + op + " for type date").build();
            };
        }
        if (r instanceof LocalDate rd && "+".equals(op)) {
            return rd.plusDays(toLong(l));
        }

        // Guard: if neither operand is numeric, the operator doesn't exist
        if (!(l instanceof Number) || !(r instanceof Number)) {
            throw PgErrorException.error("42883",
                    "operator does not exist: " + javaTypeToSqlName(l) + " " + op + " " + javaTypeToSqlName(r)).build();
        }

        // int4 × int4 → int4
        int a = toInt(l), b = toInt(r);
        try {
            return switch (op) {
                case "+" -> Math.addExact(a, b);
                case "-" -> Math.subtractExact(a, b);
                case "*" -> Math.multiplyExact(a, b);
                case "/" -> {
                    if (b == 0) throw PgErrorException.error("22012", "division by zero").build();
                    if (a == Integer.MIN_VALUE && b == -1)
                        throw PgErrorException.error("22003", "integer out of range").build();
                    yield a / b;
                }
                case "%" -> {
                    if (b == 0) throw PgErrorException.error("22012", "division by zero").build();
                    yield a % b;
                }
                default -> throw PgErrorException.error("42883", "operator does not exist: " + op).build();
            };
        } catch (ArithmeticException e) {
            throw PgErrorException.error("22003", "integer out of range").build();
        }
    }

    private Object arithPow(Object l, Object r) throws SQLException {
        if (l == null || r == null) return null;
        if (l instanceof java.math.BigDecimal || r instanceof java.math.BigDecimal) {
            java.math.BigDecimal base = (l instanceof java.math.BigDecimal bd) ? bd
                    : new java.math.BigDecimal(l.toString());
            try {
                int exp = Integer.parseInt(r.toString());
                return base.pow(exp, java.math.MathContext.DECIMAL128);
            } catch (NumberFormatException e) {
                return Math.pow(toDouble(l), toDouble(r));
            }
        }
        return Math.pow(toDouble(l), toDouble(r));
    }

    /** Apply an interval to a temporal value (timestamp ± interval, date ± interval, time ± interval). */
    private Object applyInterval(Object temporal, PgInterval iv, String op) throws SQLException {
        if (!"+".equals(op) && !"-".equals(op))
            throw PgErrorException.error("42883", "operator does not exist: " + op + " for type interval").build();
        PgInterval eff = "-".equals(op) ? iv.negate() : iv;
        if (temporal instanceof LocalDateTime ldt) {
            return ldt.plusMonths(eff.months()).plusDays(eff.days())
                    .plusNanos(eff.micros() * 1000L);
        }
        if (temporal instanceof OffsetDateTime odt) {
            return odt.plusMonths(eff.months()).plusDays(eff.days())
                    .plusNanos(eff.micros() * 1000L);
        }
        if (temporal instanceof LocalDate ld) {
            // PG promotes date + interval to timestamp
            return ld.plusMonths(eff.months()).plusDays(eff.days())
                    .atStartOfDay().plusNanos(eff.micros() * 1000L);
        }
        if (temporal instanceof LocalTime lt) {
            return lt.plusNanos(eff.micros() * 1000L);
        }
        if (temporal instanceof OffsetTime ot) {
            return ot.plusNanos(eff.micros() * 1000L);
        }
        // number * interval
        if (temporal instanceof Number) {
            return scaleInterval(eff, toDouble(temporal));
        }
        throw PgErrorException.error("42883", "operator does not exist for type " + temporal.getClass().getSimpleName()).build();
    }

    private PgInterval scaleInterval(PgInterval iv, double factor) {
        return new PgInterval(
                (int) Math.round(iv.months() * factor),
                (int) Math.round(iv.days() * factor),
                Math.round(iv.micros() * factor));
    }

    // -------------------------------------------------------------------------
    // Range operators

    private Object evalContains(Object l, Object r) throws SQLException {
        if (l == null || r == null) return null;
        if (l instanceof org.pgjava.types.PgRange lr) {
            if (r instanceof org.pgjava.types.PgRange rr) return lr.containsRange(rr);
            return lr.contains(r);
        }
        // JSONB @> JSONB
        String ls = asJsonString(l), rs = asJsonString(r);
        if (ls != null && rs != null) return JsonOps.jsonContains(ls, rs);
        return null;
    }

    private Object evalContainedBy(Object l, Object r) throws SQLException {
        if (l == null || r == null) return null;
        if (r instanceof org.pgjava.types.PgRange rr) {
            if (l instanceof org.pgjava.types.PgRange lr) return rr.containsRange(lr);
            return rr.contains(l);
        }
        // JSONB <@ JSONB
        String ls = asJsonString(l), rs = asJsonString(r);
        if (ls != null && rs != null) return JsonOps.jsonContainedBy(ls, rs);
        return null;
    }

    private Object evalOverlaps(Object l, Object r) {
        if (l == null || r == null) return null;
        if (l instanceof org.pgjava.types.PgRange lr && r instanceof org.pgjava.types.PgRange rr)
            return lr.overlaps(rr);
        return null;
    }

    private Object sqlConcat(Object l, Object r) throws SQLException {
        // PostgreSQL: NULL || anything = NULL (strict operator)
        if (l == null || r == null) return null;
        // JSONB || JSONB concatenation
        String lj = asJsonString(l), rj = asJsonString(r);
        if (lj != null && rj != null) return JsonOps.jsonConcat(lj, rj);
        // Array concatenation
        if (l instanceof List<?> la && r instanceof List<?> rb) {
            List<Object> result = new ArrayList<>(la);
            result.addAll(rb);
            return result;
        }
        if (l instanceof List<?> la) {
            List<Object> result = new ArrayList<>(la);
            result.add(r);
            return result;
        }
        if (r instanceof List<?> rb) {
            List<Object> result = new ArrayList<>();
            result.add(l);
            result.addAll(rb);
            return result;
        }
        return l.toString() + r.toString();
    }

    private Object sqlRegex(Object l, Object r, boolean negated, boolean caseInsensitive)
            throws SQLException {
        if (l == null || r == null) return null;
        String text    = l.toString();
        String pattern = r.toString();
        int flags = caseInsensitive ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        try {
            boolean matches = Pattern.compile(pattern, flags).matcher(text).find();
            return negated ? !matches : matches;
        } catch (PatternSyntaxException e) {
            throw PgErrorException.error("2201B", "invalid regular expression: " + e.getMessage()).cause(e).build();
        }
    }

    // -------------------------------------------------------------------------
    // Unary operators

    private Object evalUnary(UnaryOp uo, EvalContext ctx) throws SQLException {
        String op = uo.op().toUpperCase();

        // IS NULL / IS NOT NULL don't propagate NULL — evaluate before resolving operand
        Object val = eval(uo.operand(), ctx);

        return switch (op) {
            case "IS NULL",   "ISNULL"  -> val == null;
            case "IS NOT NULL","NOTNULL"-> val != null;
            case "NOT" -> {
                if (val == null) yield null;
                if (!(val instanceof Boolean b))
                    throw PgErrorException.error("42804",
                            "argument of NOT must be type boolean, not type " + javaTypeToSqlName(val)).build();
                yield !b;
            }
            case "-" -> {
                if (val == null) yield null;
                yield switch (val) {
                    case Integer i   -> -i;
                    case Long l      -> {
                        long neg = -l;
                        yield (neg >= Integer.MIN_VALUE && neg <= Integer.MAX_VALUE)
                                ? Integer.valueOf((int) neg) : Long.valueOf(neg);
                    }
                    case Double d    -> -d;
                    case Float f     -> -f;
                    case BigDecimal bd -> bd.negate();
                    default -> throw PgErrorException.error("42883", "operator does not exist: - " + javaTypeToSqlName(val)).build();
                };
            }
            case "+" -> val;
            case "IS TRUE"    -> val != null &&  (Boolean) val;
            case "IS FALSE"   -> val != null && !(Boolean) val;
            case "IS UNKNOWN" -> val == null;
            default -> throw PgErrorException.error("42883", "operator does not exist: " + op).build();
        };
    }

    // -------------------------------------------------------------------------
    // CAST

    private Object evalCast(CastExpr ce, EvalContext ctx) throws SQLException {
        Object val = eval(ce.arg(), ctx);
        if (val == null) return null;
        String typName = ce.targetType().simpleName();
        PgType target = types.byTypeName(typName);
        if (target == null && schemaTypeResolver != null) target = schemaTypeResolver.apply(typName);
        if (target == null)
            throw PgErrorException.error("42704", "type \"" + typName + "\" does not exist").build();
        // Enum cast: validate the label
        if (target instanceof org.pgjava.types.EnumType enumType) {
            String strVal = val instanceof org.pgjava.types.EnumValue ev ? ev.label() : val.toString();
            if (!enumType.labels().contains(strVal))
                throw PgErrorException.error("22P02",
                        "invalid input value for enum " + enumType.name() + ": \"" + strVal + "\"").build();
            return strVal;
        }
        // Cast FROM enum to text: just return label string
        if (val instanceof org.pgjava.types.EnumValue ev) {
            val = ev.label();
        }
        Object result;
        // Use TypeInput for string literals, CoercionEngine for typed values
        if (val instanceof String s) {
            result = TypeInput.parse(s, target.oid());
        } else {
            int srcOid = javaClassToOid(val);
            result = CoercionEngine.coerce(val, srcOid, target.oid(), CoercionContext.EXPLICIT);
        }
        // Apply typmod for numeric(precision, scale)
        if (result instanceof BigDecimal bd && !ce.targetType().typmods().isEmpty()) {
            List<Node> mods = ce.targetType().typmods();
            if (mods.size() >= 2) {
                int scale = evalTypmod(mods.get(1), ctx);
                result = bd.setScale(scale, RoundingMode.HALF_UP);
            }
        }
        return result;
    }

    private int evalTypmod(Node mod, EvalContext ctx) throws SQLException {
        if (mod instanceof Expr e) {
            Object v = eval(e, ctx);
            if (v instanceof Number n) return n.intValue();
        }
        return 0;
    }

    private int javaClassToOid(Object v) {
        if (v instanceof Integer)    return PgOid.INT4;
        if (v instanceof Long)       return PgOid.INT8;
        if (v instanceof Short)      return PgOid.INT2;
        if (v instanceof Double)     return PgOid.FLOAT8;
        if (v instanceof Float)      return PgOid.FLOAT4;
        if (v instanceof BigDecimal) return PgOid.NUMERIC;
        if (v instanceof Boolean)    return PgOid.BOOL;
        if (v instanceof String)     return PgOid.TEXT;
        if (v instanceof LocalDate)  return PgOid.DATE;
        if (v instanceof LocalTime)  return PgOid.TIME;
        if (v instanceof OffsetDateTime) return PgOid.TIMESTAMPTZ;
        if (v instanceof LocalDateTime)  return PgOid.TIMESTAMP;
        if (v instanceof java.util.UUID) return PgOid.UUID;
        if (v instanceof byte[])     return PgOid.BYTEA;
        if (v instanceof PgInterval) return PgOid.INTERVAL;
        return PgOid.TEXT;
    }

    // -------------------------------------------------------------------------
    // Function calls

    private Object evalFunction(FunctionCall fc, EvalContext ctx) throws SQLException {
        // Resolve function name
        String name = fc.funcname().getLast();

        // Special forms handled without evaluating args
        switch (name.toLowerCase()) {
            case "coalesce" -> {
                for (Expr arg : fc.args()) {
                    Object v = eval(arg, ctx);
                    if (v != null) return v;
                }
                return null;
            }
            case "nullif" -> {
                if (fc.args().size() < 2)
                    throw PgErrorException.error("42883", "function nullif must have two arguments").build();
                Object a = eval(fc.args().get(0), ctx);
                Object b = eval(fc.args().get(1), ctx);
                if (a == null) return null;
                return Boolean.TRUE.equals(sqlEquals(a, b)) ? null : a;
            }
        }

        // Evaluate arguments
        Object[] args = new Object[fc.args().size()];
        for (int i = 0; i < fc.args().size(); i++) {
            args[i] = eval(fc.args().get(i), ctx);
        }

        // Count(*) → pass empty args to count impl
        if (fc.aggStar()) args = new Object[0];

        // Special timestamp functions: return statement timestamp
        switch (name.toLowerCase()) {
            case "now", "current_timestamp", "transaction_timestamp" ->
                    { return ctx.statementTimestamp(); }
            case "clock_timestamp", "statement_timestamp" ->
                    { return OffsetDateTime.now(ZoneOffset.UTC); }
            case "current_date" ->
                    { return ctx.statementTimestamp().toLocalDate(); }
            case "current_time", "localtime" ->
                    { return ctx.statementTimestamp().toLocalTime(); }
            case "localtimestamp" ->
                    { return ctx.statementTimestamp().toLocalDateTime(); }
        }

        // Check session-local functions first (e.g. pg_notify, pg_listening_channels)
        FunctionDef fn = (sessionFunctions != null)
                ? sessionFunctions.findScalarForArgs(name, args) : null;
        // Type-aware overload resolution in main registry
        if (fn == null) fn = functions.findScalarForArgs(name, args);
        if (fn == null) {
            // Try variadic match (argCount=0 is how we register variadic)
            fn = (sessionFunctions != null) ? sessionFunctions.findScalar(name, 0) : null;
            if (fn == null) fn = functions.findScalar(name, 0);
        }
        // Try matching with default parameters (fewer args than declared)
        if (fn == null) {
            fn = (sessionFunctions != null) ? sessionFunctions.findScalar(name, args.length) : null;
            if (fn == null) fn = functions.findScalar(name, args.length);
        }
        if (fn == null)
            throw PgErrorException.error("42883",
                    "function " + name + "(" + argTypeList(args, fc.args()) + ") does not exist")
                    .hint("No function matches the given name and argument types. You might need to add explicit type casts.")
                    .build();

        // VARIADIC: collect trailing args into an array for user-defined variadic functions
        // Built-in variadic functions handle their own arg arrays directly
        if (fn.variadic() && fn.source() != null && fn.argTypes().size() > 0 && args.length >= fn.argTypes().size()) {
            int fixedCount = fn.argTypes().size() - 1; // all params except the variadic one
            if (args.length > fixedCount) {
                Object[] packed = new Object[fixedCount + 1];
                System.arraycopy(args, 0, packed, 0, fixedCount);
                // Collect trailing args into a List (our array representation)
                java.util.List<Object> variadicArgs = new java.util.ArrayList<>();
                for (int i = fixedCount; i < args.length; i++) {
                    variadicArgs.add(args[i]);
                }
                packed[fixedCount] = variadicArgs;
                args = packed;
            }
        }

        // Named argument reordering: if call uses name => value syntax, reorder to positional
        if (fn.argNames() != null && !fn.argNames().isEmpty() && fc.args().stream().anyMatch(a -> a instanceof NamedArgExpr)) {
            Object[] reordered = new Object[fn.argTypes().size()];
            boolean[] filled = new boolean[reordered.length];
            int positionalIdx = 0;
            for (int i = 0; i < fc.args().size(); i++) {
                if (fc.args().get(i) instanceof NamedArgExpr na) {
                    // Find the position of this named parameter
                    int pos = -1;
                    for (int j = 0; j < fn.argNames().size(); j++) {
                        if (fn.argNames().get(j) != null && fn.argNames().get(j).equalsIgnoreCase(na.name())) {
                            pos = j; break;
                        }
                    }
                    if (pos >= 0) { reordered[pos] = args[i]; filled[pos] = true; }
                    else { reordered[positionalIdx] = args[i]; filled[positionalIdx] = true; positionalIdx++; }
                } else {
                    reordered[positionalIdx] = args[i]; filled[positionalIdx] = true; positionalIdx++;
                }
            }
            args = reordered;
        }

        // Fill in default values for missing trailing arguments
        if (fn.argDefaults() != null && args.length < fn.argTypes().size()) {
            Object[] padded = new Object[fn.argTypes().size()];
            System.arraycopy(args, 0, padded, 0, args.length);
            int nDefaults = fn.argDefaults().size();
            int firstDefaultParam = fn.argTypes().size() - nDefaults;
            for (int i = args.length; i < padded.length; i++) {
                int defIdx = i - firstDefaultParam;
                if (defIdx >= 0 && defIdx < nDefaults) {
                    padded[i] = eval(fn.argDefaults().get(defIdx), ctx);
                }
            }
            args = padded;
        }

        // Strict: return NULL if any arg is NULL
        if (fn.strict()) {
            for (Object a : args) if (a == null) return null;
        }

        return fn.impl().invoke(args);
    }

    // -------------------------------------------------------------------------
    // CASE

    private Object evalCase(CaseExpr ca, EvalContext ctx) throws SQLException {
        Object caseArg = ca.arg() != null ? eval(ca.arg(), ctx) : null;

        for (CaseWhen when : ca.whenClauses()) {
            Object whenVal = eval(when.condition(), ctx);

            boolean matched;
            if (ca.arg() != null) {
                // Simple CASE: CASE x WHEN v THEN ...
                matched = Boolean.TRUE.equals(sqlEquals(caseArg, whenVal));
            } else {
                // Searched CASE: CASE WHEN condition THEN ...
                matched = Boolean.TRUE.equals(whenVal);
            }

            if (matched) return eval(when.result(), ctx);
        }

        return ca.defResult() != null ? eval(ca.defResult(), ctx) : null;
    }

    // -------------------------------------------------------------------------
    // IN / NOT IN

    private Object evalIn(InExpr ie, EvalContext ctx) throws SQLException {
        Object val = eval(ie.arg(), ctx);
        boolean hasNull = false;

        for (Expr e : ie.list()) {
            Object item = eval(e, ctx);
            if (item == null) { hasNull = true; continue; }
            if (Boolean.TRUE.equals(sqlEquals(val, item))) {
                return !ie.negated();
            }
        }

        // No match found
        if (val == null || hasNull) return null;
        return ie.negated();
    }

    // -------------------------------------------------------------------------
    // BETWEEN

    private Object evalBetween(BetweenExpr be, EvalContext ctx) throws SQLException {
        Object val  = eval(be.arg(),  ctx);
        Object low  = eval(be.low(),  ctx);
        Object high = eval(be.high(), ctx);

        if (val == null || low == null || high == null) return null;

        boolean result = compareAware(val, low) >= 0 && compareAware(val, high) <= 0;
        // BETWEEN SYMMETRIC: low ≤ val ≤ high OR high ≤ val ≤ low
        if (be.symmetric()) {
            result = result || (compareAware(val, high) >= 0 && compareAware(val, low) <= 0);
        }
        return be.negated() ? !result : result;
    }

    // -------------------------------------------------------------------------
    // LIKE / ILIKE / SIMILAR TO

    private Object evalLike(LikeExpr le, EvalContext ctx) throws SQLException {
        Object argVal = eval(le.arg(),     ctx);
        Object patVal = eval(le.pattern(), ctx);
        if (argVal == null || patVal == null) return null;

        String text    = argVal.toString();
        String pattern = patVal.toString();

        String escape = "\\";
        if (le.escape() != null) {
            Object escVal = eval(le.escape(), ctx);
            escape = escVal == null ? "" : escVal.toString();
        }

        boolean result = switch (le.type()) {
            case LIKE      -> likeMatch(text, pattern, escape, false);
            case ILIKE     -> likeMatch(text, pattern, escape, true);
            case SIMILAR_TO -> similarToMatch(text, pattern, escape);
        };
        return le.negated() ? !result : result;
    }

    /** Convert LIKE pattern to Java regex and match. */
    private boolean likeMatch(String text, String pattern, String escape, boolean ci) {
        String regex = likeToRegex(pattern, escape);
        int flags = Pattern.DOTALL | (ci ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
        return Pattern.compile(regex, flags).matcher(text).matches();
    }

    private String likeToRegex(String pattern, String escape) {
        StringBuilder sb = new StringBuilder("^");
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (!escape.isEmpty() && !escaped && String.valueOf(c).equals(escape)) {
                escaped = true;
                continue;
            }
            if (!escaped && c == '%') { sb.append(".*"); }
            else if (!escaped && c == '_') { sb.append('.'); }
            else { sb.append(Pattern.quote(String.valueOf(c))); }
            escaped = false;
        }
        sb.append("$");
        return sb.toString();
    }

    /** Convert SIMILAR TO pattern to Java regex. */
    private boolean similarToMatch(String text, String pattern, String escape) {
        // SIMILAR TO uses SQL-standard regex: % → .*, _ → .
        // These SQL regex operators pass through: | ( ) [ ] * + ? { }
        // Other Java regex metacharacters must be escaped: . ^ $ \
        // The escape character (if set) escapes %, _, and itself
        char esc = (escape != null && escape.length() == 1) ? escape.charAt(0) : 0;
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (esc != 0 && c == esc && i + 1 < pattern.length()) {
                // Escaped character — emit as literal
                char next = pattern.charAt(++i);
                if (".^$\\".indexOf(next) >= 0) sb.append('\\');
                sb.append(next);
            } else if (c == '%') {
                sb.append(".*");
            } else if (c == '_') {
                sb.append('.');
            } else if (c == '.' || c == '^' || c == '$' || c == '\\') {
                // Java regex metacharacters that are NOT SQL SIMILAR TO operators
                sb.append('\\').append(c);
            } else {
                // Pass through: includes SQL SIMILAR TO operators | ( ) [ ] * + ? { }
                // and all literal characters
                sb.append(c);
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString(), Pattern.DOTALL).matcher(text).matches();
    }

    // -------------------------------------------------------------------------
    // GREATEST / LEAST

    private Object evalMinMax(MinMaxExpr mm, EvalContext ctx) throws SQLException {
        Object result = null;
        for (Expr e : mm.args()) {
            Object v = eval(e, ctx);
            if (v == null) continue;
            if (result == null) { result = v; continue; }
            int cmp = compareAware(v, result);
            if (mm.op() == MinMaxOp.GREATEST ? cmp > 0 : cmp < 0) result = v;
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // ROW constructor

    private Object evalRow(RowExpr re, EvalContext ctx) throws SQLException {
        Object[] vals = new Object[re.args().size()];
        for (int i = 0; i < re.args().size(); i++) vals[i] = eval(re.args().get(i), ctx);
        return vals;
    }

    // -------------------------------------------------------------------------
    // ARRAY constructor

    private Object evalArray(ArrayExpr ae, EvalContext ctx) throws SQLException {
        List<Object> result = new ArrayList<>();
        for (Expr e : ae.elements()) result.add(eval(e, ctx));
        return result;
    }

    // -------------------------------------------------------------------------
    // Array ANY/ALL: expr op ANY(array) / expr op ALL(array)

    private Object evalArrayAnyAll(ArrayAnyAllExpr aa, EvalContext ctx) throws SQLException {
        Object lhs = eval(aa.testExpr(), ctx);
        if (lhs == null) return null;
        Object arrVal = eval(aa.arrayExpr(), ctx);
        if (arrVal == null) return null;

        List<?> arr;
        if (arrVal instanceof List<?> l) arr = l;
        else if (arrVal instanceof Object[] oa) arr = java.util.Arrays.asList(oa);
        else throw PgErrorException.error("42804", "op ANY/ALL (array) requires array on right side").build();

        boolean sawNull = false;
        for (Object elem : arr) {
            if (elem == null) { sawNull = true; continue; }
            Object cmp = applyOp(aa.op(), lhs, elem);
            if (aa.useAll()) {
                if (!Boolean.TRUE.equals(cmp)) return Boolean.FALSE;
            } else {
                if (Boolean.TRUE.equals(cmp)) return Boolean.TRUE;
            }
        }
        if (sawNull) return null;
        return aa.useAll() ? Boolean.TRUE : Boolean.FALSE;
    }

    // -------------------------------------------------------------------------
    // Array subscript: arr[n] (1-based)

    private Object evalSubscript(SubscriptExpr se, EvalContext ctx) throws SQLException {
        Object target = eval(se.target(), ctx);
        Object idx    = eval(se.idx(), ctx);
        if (target == null || idx == null) return null;
        int i = toInt(idx) - 1; // PG is 1-based
        if (target instanceof List<?> list) {
            if (i < 0 || i >= list.size()) return null;
            return list.get(i);
        }
        if (target instanceof Object[] arr) {
            if (i < 0 || i >= arr.length) return null;
            return arr[i];
        }
        throw PgErrorException.error("42804", "cannot subscript type that is not an array").build();
    }

    // -------------------------------------------------------------------------
    // Record field select: row.field

    private Object evalFieldSelect(FieldSelectExpr fs, EvalContext ctx) throws SQLException {
        Object arg = eval(fs.arg(), ctx);
        if (arg == null) return null;
        // Phase 9+: composite type field selection
        throw PgErrorException.error("0A000", "field selection on composite types not yet supported").build();
    }

    // -------------------------------------------------------------------------
    // Sublinks (EXISTS, scalar, ANY/ALL)

    private Object evalSubLink(SubLink sl, EvalContext ctx) throws SQLException {
        if (subqueryExecutor == null)
            throw PgErrorException.error("XX000", "subquery executor not wired").build();

        List<Row> rows = subqueryExecutor.execute(sl.subselect(), ctx);

        return switch (sl.type()) {
            case EXISTS -> !rows.isEmpty();

            case EXPR -> {
                if (rows.isEmpty()) yield null;
                if (rows.size() > 1)
                    throw PgErrorException.error("21000",
                            "more than one row returned by a subquery used as an expression").build();
                yield rows.get(0).get(0);
            }

            case ANY -> {
                // testExpr op ANY (subquery) — returns TRUE if any row satisfies the op
                Object lhs = eval(sl.testExpr(), ctx);
                if (lhs == null) yield null;
                boolean sawNull = false;
                for (Row r : rows) {
                    Object rhs = r.get(0);
                    if (rhs == null) { sawNull = true; continue; }
                    Object cmp = applyOp(sl.operName(), lhs, rhs);
                    if (Boolean.TRUE.equals(cmp)) yield Boolean.TRUE;
                }
                yield sawNull ? null : Boolean.FALSE;
            }

            case ALL -> {
                // testExpr op ALL (subquery) — returns TRUE if all rows satisfy the op
                Object lhs = eval(sl.testExpr(), ctx);
                if (lhs == null) yield null;
                boolean sawNull = false;
                for (Row r : rows) {
                    Object rhs = r.get(0);
                    if (rhs == null) { sawNull = true; continue; }
                    Object cmp = applyOp(sl.operName(), lhs, rhs);
                    if (!Boolean.TRUE.equals(cmp)) yield Boolean.FALSE;
                }
                yield sawNull ? null : Boolean.TRUE;
            }

            default -> throw PgErrorException.error("0A000",
                    "unsupported sublink type: " + sl.type()).build();
        };
    }

    private Object evalInSubselect(InSubselect is, EvalContext ctx) throws SQLException {
        if (subqueryExecutor == null)
            throw PgErrorException.error("XX000", "subquery executor not wired").build();

        Object val = eval(is.arg(), ctx);
        List<Row> rows = subqueryExecutor.execute(is.subselect(), ctx);

        boolean sawNull = false;
        for (Row r : rows) {
            Object item = r.get(0);
            if (item == null) { sawNull = true; continue; }
            if (Boolean.TRUE.equals(sqlEquals(val, item))) {
                return !is.negated();
            }
        }
        // No match
        if (val == null || sawNull) return null;
        return is.negated();
    }

    private Object evalArraySubselect(ArraySubselect as, EvalContext ctx) throws SQLException {
        if (subqueryExecutor == null)
            throw PgErrorException.error("XX000", "subquery executor not wired").build();

        List<Row> rows = subqueryExecutor.execute(as.subselect(), ctx);
        List<Object> result = new ArrayList<>(rows.size());
        for (Row r : rows) result.add(r.get(0));
        return result;
    }

    /** Apply a comparison operator by name for ANY/ALL. */
    private Object applyOp(String op, Object l, Object r) throws SQLException {
        if (l == null || r == null) return null;
        return switch (op == null ? "=" : op) {
            case "="  -> sqlEquals(l, r);
            case "<>" , "!=" -> sqlNotEquals(l, r);
            case "<"  -> sqlCompare(l, r, "<");
            case ">"  -> sqlCompare(l, r, ">");
            case "<=" -> sqlCompare(l, r, "<=");
            case ">=" -> sqlCompare(l, r, ">=");
            default   -> throw PgErrorException.error("42883", "operator does not exist: " + op).build();
        };
    }

    // -------------------------------------------------------------------------
    // Convenience type coercion helpers

    private int toInt(Object v) {
        if (v instanceof Integer i) return i;
        if (v instanceof Long l)    return l.intValue();
        if (v instanceof Short s)   return s;
        if (v instanceof Number n)  return n.intValue();
        return Integer.parseInt(v.toString());
    }

    private long toLong(Object v) {
        if (v instanceof Long l)    return l;
        if (v instanceof Integer i) return i;
        if (v instanceof Number n)  return n.longValue();
        return Long.parseLong(v.toString());
    }

    private double toDouble(Object v) {
        if (v instanceof Double d)  return d;
        if (v instanceof Float f)   return f;
        if (v instanceof Number n)  return n.doubleValue();
        return Double.parseDouble(v.toString());
    }

    private BigDecimal toBigDecimal(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Double d)  return BigDecimal.valueOf(d);
        if (v instanceof Float f)   return BigDecimal.valueOf(f);
        if (v instanceof Long l)    return BigDecimal.valueOf(l);
        if (v instanceof Integer i) return BigDecimal.valueOf(i);
        if (v instanceof Short s)   return BigDecimal.valueOf(s);
        return new BigDecimal(v.toString());
    }

    /** Map Java runtime type to PostgreSQL type name for error messages. */
    private static String javaTypeToSqlName(Object v) {
        if (v == null) return "unknown";
        return switch (v) {
            case Integer ignored  -> "integer";
            case Long ignored     -> "bigint";
            case Short ignored    -> "smallint";
            case Float ignored    -> "real";
            case Double ignored   -> "double precision";
            case BigDecimal ignored -> "numeric";
            case Boolean ignored  -> "boolean";
            case String ignored   -> "text";
            case byte[] ignored   -> "bytea";
            case java.util.UUID ignored -> "uuid";
            case java.time.LocalDate ignored -> "date";
            case java.time.LocalTime ignored -> "time without time zone";
            case java.time.OffsetTime ignored -> "time with time zone";
            case java.time.LocalDateTime ignored -> "timestamp without time zone";
            case java.time.OffsetDateTime ignored -> "timestamp with time zone";
            case PgInterval ignored -> "interval";
            default -> v.getClass().getSimpleName();
        };
    }

    /** Build comma-separated type list from argument values for error messages. */
    private static String argTypeList(Object[] args, java.util.List<? extends Node> astArgs) {
        if (args.length == 0) return "";
        var sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            // Untyped string literals are 'unknown' in PG, not 'text'
            if (astArgs != null && i < astArgs.size()
                    && astArgs.get(i) instanceof StringLiteral) {
                sb.append("unknown");
            } else {
                sb.append(javaTypeToSqlName(args[i]));
            }
        }
        return sb.toString();
    }
}
