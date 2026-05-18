package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.pgjava.sql.ast.*;
import org.pgjava.sql.parser.AntlrParser;
import org.pgjava.sql.parser.ParseException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3: Parser correctness tests for the ANTLR4 fallback parser.
 * These are pure unit tests against AntlrParser — no database required.
 */
class Phase3ParserTest {

    private static List<Stmt> parse(String sql) throws ParseException {
        return AntlrParser.parse(sql);
    }

    private static Stmt parseOne(String sql) throws ParseException {
        var stmts = parse(sql);
        assertEquals(1, stmts.size(), "expected exactly one statement");
        return stmts.get(0);
    }

    // -------------------------------------------------------------------------
    // Literals

    @Test
    void selectIntegerLiteral() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT 1");
        assertEquals(1, stmt.targetList().size());
        var te = stmt.targetList().get(0);
        var lit = (IntegerLiteral) te.val();
        assertEquals(1L, lit.value());
    }

    @Test
    void selectFloatLiteral() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT 3.14");
        var lit = (FloatLiteral) stmt.targetList().get(0).val();
        assertEquals(3.14, Double.parseDouble(lit.rawStr()), 1e-10);
    }

    @Test
    void selectStringLiteral() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT 'hello'");
        var lit = (StringLiteral) stmt.targetList().get(0).val();
        assertEquals("hello", lit.value());
    }

    @Test
    void selectBooleanLiterals() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT true, false");
        var t = (BooleanLiteral) stmt.targetList().get(0).val();
        var f = (BooleanLiteral) stmt.targetList().get(1).val();
        assertTrue(t.value());
        assertFalse(f.value());
    }

    @Test
    void selectNull() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT NULL");
        assertInstanceOf(NullLiteral.class, stmt.targetList().get(0).val());
    }

    // -------------------------------------------------------------------------
    // Column refs

    @Test
    void selectColumnRef() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT a");
        var ref = (ColumnRef) stmt.targetList().get(0).val();
        assertEquals(List.of("a"), ref.fields());
    }

    @Test
    void selectQualifiedColumnRef() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT t.col");
        var ref = (ColumnRef) stmt.targetList().get(0).val();
        assertEquals(List.of("t", "col"), ref.fields());
    }

    // -------------------------------------------------------------------------
    // Aliases

    @Test
    void selectColumnAlias() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT 1 AS one");
        var te = stmt.targetList().get(0);
        assertEquals("one", te.name());
    }

    // -------------------------------------------------------------------------
    // Binary operators

    @Test
    void selectArithmetic() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT 1 + 2");
        var op = (BinaryOp) stmt.targetList().get(0).val();
        assertEquals("+", op.op());
        assertInstanceOf(IntegerLiteral.class, op.left());
        assertInstanceOf(IntegerLiteral.class, op.right());
    }

    @Test
    void selectComparisonOperators() throws ParseException {
        for (String op : List.of("=", "<>", "<", "<=", ">", ">=")) {
            var stmt = (SelectStmt) parseOne("SELECT 1 " + op + " 2");
            var binop = (BinaryOp) stmt.targetList().get(0).val();
            assertEquals(op, binop.op());
        }
    }

    // -------------------------------------------------------------------------
    // CAST

    @Test
    void castSyntaxDoubleColon() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT '42'::integer");
        var cast = (CastExpr) stmt.targetList().get(0).val();
        assertInstanceOf(StringLiteral.class, cast.arg());
        assertEquals("int4", cast.targetType().names().get(0));
    }

    @Test
    void castSyntaxFunction() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT CAST('42' AS integer)");
        var cast = (CastExpr) stmt.targetList().get(0).val();
        assertInstanceOf(StringLiteral.class, cast.arg());
        assertEquals("int4", cast.targetType().names().get(0));
    }

    // -------------------------------------------------------------------------
    // FROM clause

    @Test
    void selectFrom() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT a FROM t");
        assertEquals(1, stmt.fromClause().size());
        var rv = (RangeVar) stmt.fromClause().get(0);
        assertEquals("t", rv.relName());
    }

    @Test
    void selectFromSchemaQualified() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT * FROM public.users");
        var rv = (RangeVar) stmt.fromClause().get(0);
        assertEquals("public", rv.schemaName());
        assertEquals("users", rv.relName());
    }

    @Test
    void selectFromAlias() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT u.id FROM users u");
        var rv = (RangeVar) stmt.fromClause().get(0);
        assertEquals("u", rv.alias());
    }

    // -------------------------------------------------------------------------
    // WHERE clause

    @Test
    void selectWhere() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT id FROM t WHERE id = 1");
        assertNotNull(stmt.whereClause());
        assertInstanceOf(BinaryOp.class, stmt.whereClause());
    }

    // -------------------------------------------------------------------------
    // IS NULL / IS NOT NULL

    @Test
    void isNull() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT x IS NULL FROM t");
        var op = (UnaryOp) stmt.targetList().get(0).val();
        assertEquals("IS NULL", op.op());
    }

    @Test
    void isNotNull() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT x IS NOT NULL FROM t");
        var op = (UnaryOp) stmt.targetList().get(0).val();
        assertEquals("IS NOT NULL", op.op());
    }

    // -------------------------------------------------------------------------
    // BETWEEN, LIKE, IN

    @Test
    void betweenExpr() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT x BETWEEN 1 AND 10 FROM t");
        var b = (BetweenExpr) stmt.targetList().get(0).val();
        assertFalse(b.negated());
        assertInstanceOf(IntegerLiteral.class, b.low());
        assertInstanceOf(IntegerLiteral.class, b.high());
    }

    @Test
    void likeExpr() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT name LIKE '%foo%' FROM t");
        var like = (LikeExpr) stmt.targetList().get(0).val();
        assertFalse(like.negated());
        assertEquals(LikeType.LIKE, like.type());
    }

    @Test
    void inListExpr() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT x IN (1, 2, 3) FROM t");
        var in = (InExpr) stmt.targetList().get(0).val();
        assertFalse(in.negated());
        assertEquals(3, in.list().size());
    }

    // -------------------------------------------------------------------------
    // ORDER BY

    @Test
    void orderBy() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT id FROM t ORDER BY id DESC");
        assertEquals(1, stmt.orderBy().size());
        assertEquals(SortByDir.DESC, stmt.orderBy().get(0).dir());
    }

    // -------------------------------------------------------------------------
    // LIMIT / OFFSET

    @Test
    void limitOffset() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT id FROM t LIMIT 10 OFFSET 20");
        var lim = (IntegerLiteral) stmt.limitCount();
        var off = (IntegerLiteral) stmt.limitOffset();
        assertEquals(10L, lim.value());
        assertEquals(20L, off.value());
    }

    // -------------------------------------------------------------------------
    // Aggregates / HAVING

    @Test
    void aggregateAndGroupBy() throws ParseException {
        var stmt = (SelectStmt) parseOne(
                "SELECT dept, COUNT(*) FROM emp GROUP BY dept HAVING COUNT(*) > 5");
        assertEquals(1, stmt.groupBy().size());
        assertNotNull(stmt.having());
    }

    // -------------------------------------------------------------------------
    // JOIN

    @Test
    void innerJoin() throws ParseException {
        var stmt = (SelectStmt) parseOne(
                "SELECT * FROM a JOIN b ON a.id = b.id");
        var join = (JoinExpr) stmt.fromClause().get(0);
        assertEquals(JoinType.INNER, join.joinType());
    }

    @Test
    void leftJoin() throws ParseException {
        var stmt = (SelectStmt) parseOne(
                "SELECT * FROM a LEFT JOIN b ON a.id = b.id");
        var join = (JoinExpr) stmt.fromClause().get(0);
        assertEquals(JoinType.LEFT, join.joinType());
    }

    // -------------------------------------------------------------------------
    // Subqueries

    @Test
    void scalarSubquery() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT (SELECT 1)");
        var sub = (SubLink) stmt.targetList().get(0).val();
        assertEquals(SubLinkType.EXPR, sub.type());
    }

    @Test
    void existsSubquery() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT EXISTS (SELECT 1 FROM t WHERE id = 1)");
        var sub = (SubLink) stmt.targetList().get(0).val();
        assertEquals(SubLinkType.EXISTS, sub.type());
    }

    // -------------------------------------------------------------------------
    // Functions

    @Test
    void functionCall() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT lower(name) FROM t");
        var fn = (FunctionCall) stmt.targetList().get(0).val();
        assertEquals(List.of("lower"), fn.funcname());
        assertEquals(1, fn.args().size());
    }

    @Test
    void coalesceFunction() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT COALESCE(a, b, 0) FROM t");
        var fn = (FunctionCall) stmt.targetList().get(0).val();
        assertEquals(List.of("coalesce"), fn.funcname());
        assertEquals(3, fn.args().size());
    }

    // -------------------------------------------------------------------------
    // CASE

    @Test
    void caseExpr() throws ParseException {
        var stmt = (SelectStmt) parseOne(
                "SELECT CASE WHEN x = 1 THEN 'one' ELSE 'other' END FROM t");
        var c = (CaseExpr) stmt.targetList().get(0).val();
        assertEquals(1, c.whenClauses().size());
        assertNotNull(c.defResult());
    }

    // -------------------------------------------------------------------------
    // VALUES

    @Test
    void valuesClause() throws ParseException {
        var stmt = (SelectStmt) parseOne("VALUES (1, 'a'), (2, 'b')");
        assertEquals(2, stmt.valuesLists().size());
        assertEquals(2, stmt.valuesLists().get(0).size());
    }

    // -------------------------------------------------------------------------
    // SET operations

    @Test
    void unionAll() throws ParseException {
        var stmt = (SelectStmt) parseOne("SELECT 1 UNION ALL SELECT 2");
        assertEquals(SetOpType.UNION, stmt.setOp());
        assertTrue(stmt.setAll());
    }

    // -------------------------------------------------------------------------
    // CTE

    @Test
    void withClause() throws ParseException {
        var stmt = (SelectStmt) parseOne("""
                WITH cte AS (SELECT 1 AS n)
                SELECT n FROM cte""");
        assertNotNull(stmt.withClause());
        assertEquals(1, stmt.withClause().ctes().size());
        assertEquals("cte", stmt.withClause().ctes().get(0).ctename());
    }

    // -------------------------------------------------------------------------
    // DML

    @Test
    void insertStmt() throws ParseException {
        var stmt = (InsertStmt) parseOne("INSERT INTO t (a, b) VALUES (1, 2)");
        assertEquals("t", stmt.relation().relName());
        assertEquals(List.of("a", "b"), stmt.cols());
    }

    @Test
    void updateStmt() throws ParseException {
        var stmt = (UpdateStmt) parseOne("UPDATE t SET a = 1 WHERE id = 2");
        assertEquals("t", stmt.relation().relName());
        assertEquals(1, stmt.targets().size());
        assertNotNull(stmt.whereClause());
    }

    @Test
    void deleteStmt() throws ParseException {
        var stmt = (DeleteStmt) parseOne("DELETE FROM t WHERE id = 1");
        assertEquals("t", stmt.relation().relName());
        assertNotNull(stmt.whereClause());
    }

    // -------------------------------------------------------------------------
    // DDL

    @Test
    void createTable() throws ParseException {
        var stmt = (CreateTableStmt) parseOne("""
                CREATE TABLE foo (
                    id SERIAL PRIMARY KEY,
                    name TEXT NOT NULL
                )""");
        assertEquals("foo", stmt.relation().relName());
        assertEquals(2, stmt.columns().size());
        assertEquals("id", stmt.columns().get(0).colname());
        assertEquals("name", stmt.columns().get(1).colname());
    }

    @Test
    void dropTable() throws ParseException {
        var stmt = (DropTableStmt) parseOne("DROP TABLE IF EXISTS foo");
        assertTrue(stmt.ifExists());
        assertTrue(stmt.relations().stream().anyMatch(r -> "foo".equals(r.relName())));
    }

    @Test
    void createIndex() throws ParseException {
        var stmt = (CreateIndexStmt) parseOne("CREATE UNIQUE INDEX idx ON t (col)");
        assertTrue(stmt.unique());
        assertEquals("t", stmt.relation().relName());
        assertEquals(1, stmt.indexParams().size());
    }

    // -------------------------------------------------------------------------
    // Transactions

    @Test
    void beginCommitRollback() throws ParseException {
        assertInstanceOf(BeginStmt.class, parseOne("BEGIN"));
        assertInstanceOf(CommitStmt.class, parseOne("COMMIT"));
        assertInstanceOf(RollbackStmt.class, parseOne("ROLLBACK"));
    }

    @Test
    void savepoint() throws ParseException {
        assertInstanceOf(SavepointStmt.class, parseOne("SAVEPOINT sp1"));
        assertInstanceOf(RollbackToSavepointStmt.class, parseOne("ROLLBACK TO SAVEPOINT sp1"));
        assertInstanceOf(ReleaseSavepointStmt.class, parseOne("RELEASE SAVEPOINT sp1"));
    }

    // -------------------------------------------------------------------------
    // SET / SHOW

    @Test
    void setStmt() throws ParseException {
        var stmt = (SetStmt) parseOne("SET search_path TO public");
        assertEquals("search_path", stmt.name());
    }

    @Test
    void showStmt() throws ParseException {
        var stmt = (ShowStmt) parseOne("SHOW search_path");
        assertEquals("search_path", stmt.name());
    }

    // -------------------------------------------------------------------------
    // Multi-statement

    @Test
    void multipleStatements() throws ParseException {
        var stmts = parse("SELECT 1; SELECT 2; SELECT 3");
        assertEquals(3, stmts.size());
    }

    // -------------------------------------------------------------------------
    // Syntax errors

    @Test
    void syntaxErrorThrowsParseException() {
        var ex = assertThrows(ParseException.class, () -> parse("SELECT FROM FROM"));
        assertTrue(ex.getLine() > 0);
    }

    @Test
    void syntaxErrorIncludesPosition() {
        var ex = assertThrows(ParseException.class, () -> parse("SELECT 1 + + + FROM t"));
        assertTrue(ex.getLine() >= 1);
    }
}
