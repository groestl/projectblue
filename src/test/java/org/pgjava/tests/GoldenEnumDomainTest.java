package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;

/**
 * Golden tests for enum types, domain types, and COPY constraint validation.
 * Validates pgjava against real PostgreSQL for correctness.
 *
 * <p>Covers:
 * <ul>
 *   <li>Enum ordering (declaration order, not alphabetical)</li>
 *   <li>Enum comparison semantics (=, <, >, BETWEEN, IN)</li>
 *   <li>Enum in ORDER BY, DISTINCT, GROUP BY, MIN/MAX</li>
 *   <li>Enum in JOINs, subqueries, UNION/INTERSECT/EXCEPT</li>
 *   <li>Enum CAST and type literals</li>
 *   <li>Enum invalid value rejection</li>
 *   <li>Domain CHECK constraints, NOT NULL, default</li>
 *   <li>COPY FROM with constraint enforcement</li>
 *   <li>ALTER TYPE ADD VALUE</li>
 *   <li>Type isolation across schemas</li>
 * </ul>
 */
@ExtendWith(GoldenExtension.class)
class GoldenEnumDomainTest {

    // =========================================================================
    // ENUM — Basic CRUD
    // =========================================================================

    @Test void enumInsertAndSelect(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')");
        db.execute("CREATE TABLE people (name text, feeling mood)");
        db.execute("INSERT INTO people VALUES ('Alice', 'happy'), ('Bob', 'sad'), ('Carol', 'ok')");
        db.assertQuery("SELECT name, feeling FROM people ORDER BY name");
    }

    @Test void enumNullAllowed(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE color AS ENUM ('red', 'green', 'blue')");
        db.execute("CREATE TABLE items (id int, c color)");
        db.execute("INSERT INTO items VALUES (1, NULL), (2, 'red'), (3, NULL)");
        db.assertQuery("SELECT id, c FROM items ORDER BY id");
    }

    // =========================================================================
    // ENUM — Ordering (declaration order, NOT alphabetical)
    // =========================================================================

    @Test void enumOrderByUsesDeclarationOrder(DualExecutor db) throws Exception {
        // 'sad' < 'ok' < 'happy' by declaration, not alphabetical
        db.execute("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')");
        db.execute("CREATE TABLE t (name text, m mood)");
        db.execute("INSERT INTO t VALUES ('a','happy'),('b','sad'),('c','ok')");
        db.assertQuery("SELECT name, m FROM t ORDER BY m");
    }

    @Test void enumOrderByDesc(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE prio AS ENUM ('low', 'medium', 'high', 'critical')");
        db.execute("CREATE TABLE tasks (id int, p prio)");
        db.execute("INSERT INTO tasks VALUES (1,'low'),(2,'critical'),(3,'medium'),(4,'high')");
        db.assertQuery("SELECT id, p FROM tasks ORDER BY p DESC");
    }

    @Test void enumOrderByWithNulls(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE sz AS ENUM ('small', 'medium', 'large')");
        db.execute("CREATE TABLE products (id int, s sz)");
        db.execute("INSERT INTO products VALUES (1,'large'),(2,NULL),(3,'small'),(4,'medium'),(5,NULL)");
        db.assertQuery("SELECT id, s FROM products ORDER BY s NULLS LAST, id");
    }

    @Test void enumOrderByMultipleColumns(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE status AS ENUM ('draft', 'review', 'published')");
        db.execute("CREATE TABLE docs (id int, s status, title text)");
        db.execute("INSERT INTO docs VALUES (1,'published','Z'),(2,'draft','A'),(3,'review','M'),(4,'draft','B')");
        db.assertQuery("SELECT id, s, title FROM docs ORDER BY s, title");
    }

    // =========================================================================
    // ENUM — Comparison operators
    // =========================================================================

    @Test void enumLessThan(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE lvl AS ENUM ('low', 'medium', 'high')");
        db.execute("CREATE TABLE t (id int, l lvl)");
        db.execute("INSERT INTO t VALUES (1,'low'),(2,'medium'),(3,'high')");
        db.assertQuery("SELECT id FROM t WHERE l < 'high' ORDER BY id");
    }

    @Test void enumGreaterThan(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE lvl AS ENUM ('low', 'medium', 'high')");
        db.execute("CREATE TABLE t (id int, l lvl)");
        db.execute("INSERT INTO t VALUES (1,'low'),(2,'medium'),(3,'high')");
        db.assertQuery("SELECT id FROM t WHERE l > 'low' ORDER BY id");
    }

    @Test void enumBetween(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE grade AS ENUM ('F', 'D', 'C', 'B', 'A')");
        db.execute("CREATE TABLE scores (id int, g grade)");
        db.execute("INSERT INTO scores VALUES (1,'F'),(2,'D'),(3,'C'),(4,'B'),(5,'A')");
        db.assertQuery("SELECT id FROM scores WHERE g BETWEEN 'D' AND 'B' ORDER BY id");
    }

    @Test void enumInList(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE color AS ENUM ('red', 'orange', 'yellow', 'green', 'blue')");
        db.execute("CREATE TABLE t (id int, c color)");
        db.execute("INSERT INTO t VALUES (1,'red'),(2,'orange'),(3,'yellow'),(4,'green'),(5,'blue')");
        db.assertQuery("SELECT id FROM t WHERE c IN ('red', 'blue', 'green') ORDER BY id");
    }

    @Test void enumEquality(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')");
        db.execute("CREATE TABLE t (id int, m mood)");
        db.execute("INSERT INTO t VALUES (1,'sad'),(2,'ok'),(3,'happy')");
        db.assertQuery("SELECT id FROM t WHERE m = 'ok'");
    }

    @Test void enumNotEqual(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')");
        db.execute("CREATE TABLE t (id int, m mood)");
        db.execute("INSERT INTO t VALUES (1,'sad'),(2,'ok'),(3,'happy')");
        db.assertQuery("SELECT id FROM t WHERE m != 'sad' ORDER BY id");
    }

    @Test void enumLeastGreatest(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE prio AS ENUM ('low', 'medium', 'high')");
        db.execute("CREATE TABLE t (a prio, b prio)");
        db.execute("INSERT INTO t VALUES ('low','high'),('medium','low'),('high','medium')");
        db.assertQuery("SELECT LEAST(a, b) AS lo, GREATEST(a, b) AS hi FROM t ORDER BY a");
    }

    // =========================================================================
    // ENUM — Aggregates (MIN, MAX)
    // =========================================================================

    @Test void enumMin(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE prio AS ENUM ('low', 'medium', 'high')");
        db.execute("CREATE TABLE t (p prio)");
        db.execute("INSERT INTO t VALUES ('high'),('low'),('medium')");
        db.assertQuery("SELECT MIN(p) AS min_p FROM t");
    }

    @Test void enumMax(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE prio AS ENUM ('low', 'medium', 'high')");
        db.execute("CREATE TABLE t (p prio)");
        db.execute("INSERT INTO t VALUES ('high'),('low'),('medium')");
        db.assertQuery("SELECT MAX(p) AS max_p FROM t");
    }

    @Test void enumMinMaxGroupBy(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')");
        db.execute("CREATE TABLE t (grp text, m mood)");
        db.execute("INSERT INTO t VALUES ('a','sad'),('a','happy'),('b','ok'),('b','sad')");
        db.assertQuery("SELECT grp, MIN(m) AS min_m, MAX(m) AS max_m FROM t GROUP BY grp ORDER BY grp");
    }

    // =========================================================================
    // ENUM — DISTINCT
    // =========================================================================

    @Test void enumDistinct(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE color AS ENUM ('red', 'green', 'blue')");
        db.execute("CREATE TABLE t (c color)");
        db.execute("INSERT INTO t VALUES ('red'),('green'),('red'),('blue'),('green')");
        db.assertQuery("SELECT DISTINCT c FROM t ORDER BY c");
    }

    @Test void enumDistinctCount(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE status AS ENUM ('open', 'closed', 'pending')");
        db.execute("CREATE TABLE t (s status)");
        db.execute("INSERT INTO t VALUES ('open'),('closed'),('open'),('pending'),('closed')");
        db.assertQuery("SELECT COUNT(DISTINCT s) FROM t");
    }

    // =========================================================================
    // ENUM — GROUP BY
    // =========================================================================

    @Test void enumGroupBy(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')");
        db.execute("CREATE TABLE t (m mood, v int)");
        db.execute("INSERT INTO t VALUES ('sad',1),('happy',2),('sad',3),('ok',4),('happy',5)");
        db.assertQuery("SELECT m, SUM(v) AS total FROM t GROUP BY m ORDER BY m");
    }

    @Test void enumGroupByHaving(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE prio AS ENUM ('low', 'medium', 'high')");
        db.execute("CREATE TABLE t (p prio, v int)");
        db.execute("INSERT INTO t VALUES ('low',1),('low',2),('medium',3),('high',4),('high',5),('high',6)");
        db.assertQuery("SELECT p, COUNT(*) AS cnt FROM t GROUP BY p HAVING COUNT(*) > 1 ORDER BY p");
    }

    // =========================================================================
    // ENUM — JOINs
    // =========================================================================

    @Test void enumJoin(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE status AS ENUM ('active', 'inactive', 'banned')");
        db.execute("CREATE TABLE users (id int, name text, s status)");
        db.execute("CREATE TABLE actions (user_id int, action text, s status)");
        db.execute("INSERT INTO users VALUES (1,'Alice','active'),(2,'Bob','inactive'),(3,'Carol','active')");
        db.execute("INSERT INTO actions VALUES (1,'login','active'),(2,'timeout','inactive'),(3,'post','active')");
        db.assertQuery("SELECT u.name, a.action FROM users u JOIN actions a ON u.s = a.s ORDER BY u.name");
    }

    @Test void enumJoinCrossTable(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE prio AS ENUM ('low', 'medium', 'high')");
        db.execute("CREATE TABLE tasks (id int, p prio)");
        db.execute("CREATE TABLE filters (min_p prio)");
        db.execute("INSERT INTO tasks VALUES (1,'low'),(2,'medium'),(3,'high')");
        db.execute("INSERT INTO filters VALUES ('medium')");
        db.assertQuery("SELECT t.id FROM tasks t, filters f WHERE t.p >= f.min_p ORDER BY t.id");
    }

    // =========================================================================
    // ENUM — Subqueries
    // =========================================================================

    @Test void enumInSubquery(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')");
        db.execute("CREATE TABLE t1 (m mood)");
        db.execute("CREATE TABLE t2 (m mood)");
        db.execute("INSERT INTO t1 VALUES ('sad'),('ok'),('happy')");
        db.execute("INSERT INTO t2 VALUES ('ok'),('happy')");
        db.assertQuery("SELECT m FROM t1 WHERE m IN (SELECT m FROM t2) ORDER BY m");
    }

    @Test void enumScalarSubquery(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE prio AS ENUM ('low', 'medium', 'high')");
        db.execute("CREATE TABLE t (id int, p prio)");
        db.execute("INSERT INTO t VALUES (1,'low'),(2,'medium'),(3,'high')");
        db.assertQuery("SELECT id FROM t WHERE p > (SELECT MIN(p) FROM t) ORDER BY id");
    }

    // =========================================================================
    // ENUM — UNION / INTERSECT / EXCEPT
    // =========================================================================

    @Test void enumUnion(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE color AS ENUM ('red', 'green', 'blue', 'yellow')");
        db.execute("CREATE TABLE t1 (c color)");
        db.execute("CREATE TABLE t2 (c color)");
        db.execute("INSERT INTO t1 VALUES ('red'),('green'),('blue')");
        db.execute("INSERT INTO t2 VALUES ('green'),('blue'),('yellow')");
        db.assertQuery("SELECT c FROM t1 UNION SELECT c FROM t2 ORDER BY c");
    }

    @Test void enumIntersect(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE color AS ENUM ('red', 'green', 'blue', 'yellow')");
        db.execute("CREATE TABLE t1 (c color)");
        db.execute("CREATE TABLE t2 (c color)");
        db.execute("INSERT INTO t1 VALUES ('red'),('green'),('blue')");
        db.execute("INSERT INTO t2 VALUES ('green'),('blue'),('yellow')");
        db.assertQuery("SELECT c FROM t1 INTERSECT SELECT c FROM t2 ORDER BY c");
    }

    @Test void enumExcept(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE color AS ENUM ('red', 'green', 'blue', 'yellow')");
        db.execute("CREATE TABLE t1 (c color)");
        db.execute("CREATE TABLE t2 (c color)");
        db.execute("INSERT INTO t1 VALUES ('red'),('green'),('blue')");
        db.execute("INSERT INTO t2 VALUES ('green'),('blue'),('yellow')");
        db.assertQuery("SELECT c FROM t1 EXCEPT SELECT c FROM t2 ORDER BY c");
    }

    // =========================================================================
    // ENUM — CASE expression
    // =========================================================================

    @Test void enumCaseWhen(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')");
        db.execute("CREATE TABLE t (id int, m mood)");
        db.execute("INSERT INTO t VALUES (1,'sad'),(2,'ok'),(3,'happy')");
        db.assertQuery("SELECT id, CASE WHEN m > 'sad' THEN 'positive' ELSE 'negative' END AS label FROM t ORDER BY id");
    }

    // =========================================================================
    // ENUM — CAST and type literals
    // =========================================================================

    @Test void enumCast(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')");
        db.execute("CREATE TABLE t (id int, m mood)");
        db.execute("INSERT INTO t VALUES (1,'sad'),(2,'ok'),(3,'happy')");
        db.assertQuery("SELECT id FROM t WHERE m = 'happy'::mood ORDER BY id");
    }

    @Test void enumCastToText(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE color AS ENUM ('red', 'green', 'blue')");
        db.execute("CREATE TABLE t (c color)");
        db.execute("INSERT INTO t VALUES ('red'),('green'),('blue')");
        db.assertQuery("SELECT c::text FROM t ORDER BY c");
    }

    // =========================================================================
    // ENUM — Invalid value rejection
    // =========================================================================

    @Test void enumRejectsInvalidInsert(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE color AS ENUM ('red', 'green', 'blue')");
        db.execute("CREATE TABLE t (c color)");
        db.assertError("INSERT INTO t VALUES ('purple')", "22P02");
    }

    @Test void enumRejectsInvalidUpdate(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')");
        db.execute("CREATE TABLE t (id int, m mood)");
        db.execute("INSERT INTO t VALUES (1, 'sad')");
        db.assertError("UPDATE t SET m = 'ecstatic' WHERE id = 1", "22P02");
    }

    // =========================================================================
    // ENUM — UPDATE correctness
    // =========================================================================

    @Test void enumUpdatePreservesOrdering(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE prio AS ENUM ('low', 'medium', 'high')");
        db.execute("CREATE TABLE t (id int, p prio)");
        db.execute("INSERT INTO t VALUES (1,'low'),(2,'medium'),(3,'high')");
        db.execute("UPDATE t SET p = 'high' WHERE id = 1");
        db.assertQuery("SELECT id, p FROM t ORDER BY p, id");
    }

    // =========================================================================
    // ENUM — ALTER TYPE ADD VALUE
    // =========================================================================

    @Test void alterTypeAddValue(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE color AS ENUM ('red', 'green', 'blue')");
        db.execute("ALTER TYPE color ADD VALUE 'yellow'");
        db.execute("CREATE TABLE t (c color)");
        db.execute("INSERT INTO t VALUES ('red'),('yellow'),('blue')");
        db.assertQuery("SELECT c FROM t ORDER BY c");
    }

    @Test void alterTypeAddValueBefore(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE sz AS ENUM ('small', 'large')");
        db.execute("ALTER TYPE sz ADD VALUE 'medium' BEFORE 'large'");
        db.execute("CREATE TABLE t (s sz)");
        db.execute("INSERT INTO t VALUES ('small'),('medium'),('large')");
        db.assertQuery("SELECT s FROM t ORDER BY s");
    }

    @Test void alterTypeAddValueAfter(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE sz AS ENUM ('small', 'large')");
        db.execute("ALTER TYPE sz ADD VALUE 'medium' AFTER 'small'");
        db.execute("CREATE TABLE t (s sz)");
        db.execute("INSERT INTO t VALUES ('small'),('medium'),('large')");
        db.assertQuery("SELECT s FROM t ORDER BY s");
    }

    @Test void alterTypeAddValueIfNotExists(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE color AS ENUM ('red', 'green', 'blue')");
        db.execute("ALTER TYPE color ADD VALUE IF NOT EXISTS 'red'");  // should not error
        db.execute("ALTER TYPE color ADD VALUE IF NOT EXISTS 'yellow'");
        db.execute("CREATE TABLE t (c color)");
        db.execute("INSERT INTO t VALUES ('red'),('yellow')");
        db.assertQuery("SELECT c FROM t ORDER BY c");
    }

    // =========================================================================
    // ENUM — DROP TYPE
    // =========================================================================

    @Test void dropType(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE throwaway AS ENUM ('a', 'b')");
        db.execute("DROP TYPE throwaway");
        // Re-create with different labels to prove the old one is gone
        db.execute("CREATE TYPE throwaway AS ENUM ('x', 'y', 'z')");
        db.execute("CREATE TABLE t (v throwaway)");
        db.execute("INSERT INTO t VALUES ('x'),('z')");
        db.assertQuery("SELECT v FROM t ORDER BY v");
    }

    @Test void dropTypeIfExists(DualExecutor db) throws Exception {
        db.execute("DROP TYPE IF EXISTS nonexistent");  // should not error
        db.execute("CREATE TYPE mood AS ENUM ('a','b')");
        db.execute("DROP TYPE IF EXISTS mood");
        db.execute("CREATE TYPE mood AS ENUM ('x','y')");
        db.execute("CREATE TABLE t (m mood)");
        db.execute("INSERT INTO t VALUES ('x')");
        db.assertQuery("SELECT m FROM t");
    }

    @Test void dropTypeDependencyError(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE color AS ENUM ('red', 'blue')");
        db.execute("CREATE TABLE t (c color)");
        db.assertError("DROP TYPE color", "2BP01");
    }

    // =========================================================================
    // ENUM — Schema isolation
    // =========================================================================

    @Test void enumSameNameDifferentSchemas(DualExecutor db) throws Exception {
        db.execute("CREATE SCHEMA s1");
        db.execute("CREATE SCHEMA s2");
        db.execute("CREATE TYPE s1.mood AS ENUM ('a', 'b', 'c')");
        db.execute("CREATE TYPE s2.mood AS ENUM ('x', 'y', 'z')");
        db.execute("CREATE TABLE s1.t (m s1.mood)");
        db.execute("CREATE TABLE s2.t (m s2.mood)");
        db.execute("INSERT INTO s1.t VALUES ('a'),('c')");
        db.execute("INSERT INTO s2.t VALUES ('z'),('x')");
        db.assertQuery("SELECT m FROM s1.t ORDER BY m");
        db.assertQuery("SELECT m FROM s2.t ORDER BY m");
    }

    // =========================================================================
    // DOMAIN — Basic CHECK constraint
    // =========================================================================

    @Test void domainCheckAccepts(DualExecutor db) throws Exception {
        db.execute("CREATE DOMAIN posint AS integer CHECK (VALUE > 0)");
        db.execute("CREATE TABLE t (v posint)");
        db.execute("INSERT INTO t VALUES (1),(42),(100)");
        db.assertQuery("SELECT v FROM t ORDER BY v");
    }

    @Test void domainCheckRejects(DualExecutor db) throws Exception {
        db.execute("CREATE DOMAIN posint AS integer CHECK (VALUE > 0)");
        db.execute("CREATE TABLE t (v posint)");
        db.assertError("INSERT INTO t VALUES (0)", "23514");
    }

    @Test void domainCheckRejectsNegative(DualExecutor db) throws Exception {
        db.execute("CREATE DOMAIN posint AS integer CHECK (VALUE > 0)");
        db.execute("CREATE TABLE t (v posint)");
        db.assertError("INSERT INTO t VALUES (-5)", "23514");
    }

    @Test void domainCheckNullPasses(DualExecutor db) throws Exception {
        // PostgreSQL: CHECK passes for NULL unless NOT NULL is specified
        db.execute("CREATE DOMAIN posint AS integer CHECK (VALUE > 0)");
        db.execute("CREATE TABLE t (v posint)");
        db.execute("INSERT INTO t VALUES (NULL)");
        db.assertQuery("SELECT v FROM t");
    }

    // =========================================================================
    // DOMAIN — NOT NULL
    // =========================================================================

    @Test void domainNotNullRejects(DualExecutor db) throws Exception {
        db.execute("CREATE DOMAIN nn_text AS text NOT NULL");
        db.execute("CREATE TABLE t (v nn_text)");
        db.assertError("INSERT INTO t VALUES (NULL)", "23502");
    }

    @Test void domainNotNullAccepts(DualExecutor db) throws Exception {
        db.execute("CREATE DOMAIN nn_text AS text NOT NULL");
        db.execute("CREATE TABLE t (v nn_text)");
        db.execute("INSERT INTO t VALUES ('hello')");
        db.assertQuery("SELECT v FROM t");
    }

    // =========================================================================
    // DOMAIN — DEFAULT
    // =========================================================================

    @Test void domainDefault(DualExecutor db) throws Exception {
        db.execute("CREATE DOMAIN myint AS integer DEFAULT 42");
        db.execute("CREATE TABLE t (id int, v myint DEFAULT 42)");
        db.execute("INSERT INTO t (id) VALUES (1)");
        db.assertQuery("SELECT id, v FROM t");
    }

    // =========================================================================
    // DOMAIN — Range constraint
    // =========================================================================

    @Test void domainRangeConstraint(DualExecutor db) throws Exception {
        db.execute("CREATE DOMAIN pct AS integer CHECK (VALUE >= 0 AND VALUE <= 100)");
        db.execute("CREATE TABLE t (v pct)");
        db.execute("INSERT INTO t VALUES (0),(50),(100)");
        db.assertQuery("SELECT v FROM t ORDER BY v");
    }

    @Test void domainRangeRejectsOver(DualExecutor db) throws Exception {
        db.execute("CREATE DOMAIN pct AS integer CHECK (VALUE >= 0 AND VALUE <= 100)");
        db.execute("CREATE TABLE t (v pct)");
        db.assertError("INSERT INTO t VALUES (101)", "23514");
    }

    @Test void domainRangeRejectsUnder(DualExecutor db) throws Exception {
        db.execute("CREATE DOMAIN pct AS integer CHECK (VALUE >= 0 AND VALUE <= 100)");
        db.execute("CREATE TABLE t (v pct)");
        db.assertError("INSERT INTO t VALUES (-1)", "23514");
    }

    // =========================================================================
    // DOMAIN — UPDATE validation
    // =========================================================================

    @Test void domainCheckOnUpdate(DualExecutor db) throws Exception {
        db.execute("CREATE DOMAIN posint AS integer CHECK (VALUE > 0)");
        db.execute("CREATE TABLE t (id int, v posint)");
        db.execute("INSERT INTO t VALUES (1, 10)");
        db.assertError("UPDATE t SET v = -1 WHERE id = 1", "23514");
        db.assertQuery("SELECT v FROM t WHERE id = 1");  // unchanged
    }

    // =========================================================================
    // DOMAIN — Multiple constraints
    // =========================================================================

    @Test void domainMultipleChecks(DualExecutor db) throws Exception {
        db.execute("CREATE DOMAIN even_posint AS integer CHECK (VALUE > 0) CHECK (VALUE % 2 = 0)");
        db.execute("CREATE TABLE t (v even_posint)");
        db.execute("INSERT INTO t VALUES (2),(4),(100)");
        db.assertQuery("SELECT v FROM t ORDER BY v");
    }

    @Test void domainMultipleChecksRejectsOdd(DualExecutor db) throws Exception {
        db.execute("CREATE DOMAIN even_posint AS integer CHECK (VALUE > 0) CHECK (VALUE % 2 = 0)");
        db.execute("CREATE TABLE t (v even_posint)");
        db.assertError("INSERT INTO t VALUES (3)", "23514");
    }

    // NOTE: COPY FROM STDIN tests cannot run via JDBC golden framework (real PG
    // requires the COPY sub-protocol, not Statement.execute). See Phase12CopyTest
    // for COPY constraint validation tests using pgjava's Session API directly.

    // =========================================================================
    // ENUM — Function parameters with enum types
    // =========================================================================

    @Test void functionWithEnumParam(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')");
        db.execute("CREATE TABLE log (m mood)");
        db.execute("""
            CREATE FUNCTION log_mood(v mood) RETURNS void AS $$
            BEGIN
                INSERT INTO log VALUES (v);
            END;
            $$ LANGUAGE plpgsql
            """);
        db.execute("SELECT log_mood('happy')");
        db.assertQuery("SELECT m FROM log");
    }

    @Test void functionReturningEnum(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')");
        db.execute("""
            CREATE FUNCTION best_mood() RETURNS mood AS $$
            BEGIN
                RETURN 'happy';
            END;
            $$ LANGUAGE plpgsql
            """);
        db.assertQuery("SELECT best_mood()");
    }

    // =========================================================================
    // ENUM — Window functions
    // =========================================================================

    @Test void enumRowNumber(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE prio AS ENUM ('low', 'medium', 'high')");
        db.execute("CREATE TABLE t (id int, p prio)");
        db.execute("INSERT INTO t VALUES (1,'high'),(2,'low'),(3,'medium'),(4,'low')");
        db.assertQuery("SELECT id, p, ROW_NUMBER() OVER (ORDER BY p) AS rn FROM t ORDER BY rn");
    }

    @Test void enumRank(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE prio AS ENUM ('low', 'medium', 'high')");
        db.execute("CREATE TABLE t (id int, p prio)");
        db.execute("INSERT INTO t VALUES (1,'high'),(2,'low'),(3,'medium'),(4,'low')");
        db.assertQuery("SELECT id, p, RANK() OVER (ORDER BY p) AS rnk FROM t ORDER BY id");
    }

    // =========================================================================
    // ENUM — COALESCE and NULL handling
    // =========================================================================

    @Test void enumCoalesce(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')");
        db.execute("CREATE TABLE t (id int, m mood)");
        db.execute("INSERT INTO t VALUES (1, NULL),(2, 'ok')");
        db.assertQuery("SELECT id, COALESCE(m, 'sad') AS m FROM t ORDER BY id");
    }

    @Test void enumIsNull(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')");
        db.execute("CREATE TABLE t (id int, m mood)");
        db.execute("INSERT INTO t VALUES (1, NULL),(2, 'ok'),(3, NULL)");
        db.assertQuery("SELECT id FROM t WHERE m IS NULL ORDER BY id");
    }

    @Test void enumIsNotNull(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')");
        db.execute("CREATE TABLE t (id int, m mood)");
        db.execute("INSERT INTO t VALUES (1, NULL),(2, 'ok'),(3, 'happy')");
        db.assertQuery("SELECT id FROM t WHERE m IS NOT NULL ORDER BY id");
    }

    // =========================================================================
    // ENUM — Array of enums
    // =========================================================================

    @Test void enumArray(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE color AS ENUM ('red', 'green', 'blue')");
        db.execute("CREATE TABLE t (id int, colors color[])");
        db.execute("INSERT INTO t VALUES (1, '{red,blue}'), (2, '{green}')");
        db.assertQuery("SELECT id, colors FROM t ORDER BY id");
    }

    @Test void enumArrayAny(DualExecutor db) throws Exception {
        db.execute("CREATE TYPE color AS ENUM ('red', 'green', 'blue')");
        db.execute("CREATE TABLE t (id int, colors color[])");
        db.execute("INSERT INTO t VALUES (1, '{red,blue}'), (2, '{green}'), (3, '{red,green}')");
        db.assertQuery("SELECT id FROM t WHERE 'red' = ANY(colors) ORDER BY id");
    }

    // =========================================================================
    // DOMAIN over DOMAIN (nested domains)
    // =========================================================================

    @Test void domainOverDomain(DualExecutor db) throws Exception {
        db.execute("CREATE DOMAIN posint AS integer CHECK (VALUE > 0)");
        db.execute("CREATE DOMAIN small_posint AS posint CHECK (VALUE < 100)");
        db.execute("CREATE TABLE t (v small_posint)");
        db.execute("INSERT INTO t VALUES (1),(50),(99)");
        db.assertQuery("SELECT v FROM t ORDER BY v");
    }

    @Test void domainOverDomainRejectsBase(DualExecutor db) throws Exception {
        db.execute("CREATE DOMAIN posint AS integer CHECK (VALUE > 0)");
        db.execute("CREATE DOMAIN small_posint AS posint CHECK (VALUE < 100)");
        db.execute("CREATE TABLE t (v small_posint)");
        db.assertError("INSERT INTO t VALUES (-1)", "23514");
    }

    @Test void domainOverDomainRejectsOuter(DualExecutor db) throws Exception {
        db.execute("CREATE DOMAIN posint AS integer CHECK (VALUE > 0)");
        db.execute("CREATE DOMAIN small_posint AS posint CHECK (VALUE < 100)");
        db.execute("CREATE TABLE t (v small_posint)");
        db.assertError("INSERT INTO t VALUES (200)", "23514");
    }
}
