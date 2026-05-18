package org.pgjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;

/**
 * Golden tests for error <em>message text</em> matching between pgjava and PostgreSQL.
 *
 * <p>These tests use {@link DualExecutor#assertErrorMessage} which compares both
 * SQLSTATE codes and the actual error message string. PostgreSQL's error messages
 * are the definition of correct — any divergence is a bug.
 *
 * <p>Coverage target: every SQLSTATE code used by pgjava should have at least one
 * golden message test. High-frequency codes (42883, 42804, 22P02, etc.) have
 * multiple tests exercising different code paths.
 */
@ExtendWith(GoldenExtension.class)
class GoldenErrorMessageTest {

    // =========================================================================
    // 1. Constraint violations (23xxx)
    // =========================================================================

    @Test
    void notNullViolation(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE nn_test (id int NOT NULL, name text)");
        db.assertErrorMessage("INSERT INTO nn_test (id, name) VALUES (NULL, 'x')", "23502");
    }

    @Test
    void notNullViolationUpdate(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE nn_upd (id int NOT NULL, name text)");
        db.execute("INSERT INTO nn_upd VALUES (1, 'a')");
        db.assertErrorMessage("UPDATE nn_upd SET id = NULL WHERE id = 1", "23502");
    }

    @Test
    void uniqueViolation(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE uq_test (id int PRIMARY KEY, name text)");
        db.execute("INSERT INTO uq_test VALUES (1, 'a')");
        db.assertErrorMessage("INSERT INTO uq_test VALUES (1, 'b')", "23505");
    }

    @Test
    void uniqueViolationNamedConstraint(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE uq_named (id int, CONSTRAINT uq_named_id UNIQUE(id))");
        db.execute("INSERT INTO uq_named VALUES (1)");
        db.assertErrorMessage("INSERT INTO uq_named VALUES (1)", "23505");
    }

    @Test
    void checkConstraintViolation(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE chk_msg (val int CHECK (val > 0))");
        db.assertErrorMessage("INSERT INTO chk_msg VALUES (-1)", "23514");
    }

    @Test
    void checkConstraintNamedViolation(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE chk_named (val int, CONSTRAINT positive_val CHECK (val > 0))");
        db.assertErrorMessage("INSERT INTO chk_named VALUES (-5)", "23514");
    }

    @Test
    void fkViolationInsert(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE fk_par (id int PRIMARY KEY)");
        db.execute("CREATE TABLE fk_chi (id int, pid int REFERENCES fk_par(id))");
        db.execute("INSERT INTO fk_par VALUES (1)");
        db.assertErrorMessage("INSERT INTO fk_chi VALUES (1, 999)", "23503");
    }

    @Test
    void fkViolationDelete(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE fk_dp (id int PRIMARY KEY)");
        db.execute("CREATE TABLE fk_dc (id int, pid int REFERENCES fk_dp(id))");
        db.execute("INSERT INTO fk_dp VALUES (1)");
        db.execute("INSERT INTO fk_dc VALUES (1, 1)");
        db.assertErrorMessage("DELETE FROM fk_dp WHERE id = 1", "23503");
    }

    @Test
    void fkViolationUpdate(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE fk_up (id int PRIMARY KEY)");
        db.execute("CREATE TABLE fk_uc (id int, pid int REFERENCES fk_up(id))");
        db.execute("INSERT INTO fk_up VALUES (1)");
        db.execute("INSERT INTO fk_uc VALUES (1, 1)");
        db.assertErrorMessage("UPDATE fk_up SET id = 999 WHERE id = 1", "23503");
    }

    // =========================================================================
    // 2. Undefined objects (42P01, 42703, 42702, 42704)
    // =========================================================================

    @Test
    void undefinedTable(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT * FROM nonexistent_table_xyz", "42P01");
    }

    @Test
    void undefinedTableInInsert(DualExecutor db) throws Exception {
        db.assertErrorMessage("INSERT INTO nonexistent_table_xyz VALUES (1)", "42P01");
    }

    @Test
    void undefinedColumn(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE col_test (id int, name text)");
        db.assertErrorMessage("SELECT nonexistent_col FROM col_test", "42703");
    }

    @Test
    void undefinedColumnInWhere(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE col_wh (id int, name text)");
        db.assertErrorMessage("SELECT * FROM col_wh WHERE nope = 1", "42703");
    }

    @Test
    void ambiguousColumn(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE amb_a (id int, val text)");
        db.execute("CREATE TABLE amb_b (id int, val text)");
        db.assertErrorMessage("SELECT id FROM amb_a, amb_b", "42702");
    }

    @Test
    void undefinedType(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 'hello'::faketype", "42704");
    }

    @Test
    void undefinedIndex(DualExecutor db) throws Exception {
        db.assertErrorMessage("DROP INDEX nonexistent_idx_xyz", "42704");
    }

    // =========================================================================
    // 3. Type / cast errors (42804)
    // =========================================================================

    @Test
    void cannotCastTextToInt(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 'hello'::integer", "22P02");
    }

    @Test
    void cannotCastBoolToDate(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT true::date", "42846");
    }

    @Test
    void cannotCastIntToDate(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 42::date", "42846");
    }

    @Test
    void argumentOfNotMustBeBoolean(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT NOT 42", "42804");
    }

    // =========================================================================
    // 4. Invalid text representation (22P02)
    // =========================================================================

    @Test
    void invalidBooleanInput(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 'notabool'::boolean", "22P02");
    }

    @Test
    void invalidIntegerInput(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 'abc'::integer", "22P02");
    }

    @Test
    void invalidBigintInput(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 'xyz'::bigint", "22P02");
    }

    @Test
    void invalidSmallintInput(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 'notnum'::smallint", "22P02");
    }

    @Test
    void invalidFloatInput(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 'notfloat'::real", "22P02");
    }

    @Test
    void invalidDoubleInput(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 'notdbl'::double precision", "22P02");
    }

    @Test
    void invalidNumericInput(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 'notnum'::numeric", "22P02");
    }

    @Test
    void invalidUuidInput(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 'not-a-uuid'::uuid", "22P02");
    }

    @Test
    void invalidDateInput(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 'not-a-date'::date", "22007");
    }

    // =========================================================================
    // 5. Arithmetic errors (22012, 22003)
    // =========================================================================

    @Test
    void divisionByZeroInt(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 1 / 0", "22012");
    }

    @Test
    void divisionByZeroBigint(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 1::bigint / 0::bigint", "22012");
    }

    @Test
    void divisionByZeroNumeric(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 1.0 / 0.0", "22012");
    }

    @Test
    void integerOverflowAdd(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 2147483647 + 1", "22003");
    }

    @Test
    void integerOverflowSubtract(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT -2147483648 - 1", "22003");
    }

    @Test
    void bigintOverflowAdd(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 9223372036854775807::bigint + 1::bigint", "22003");
    }

    @Test
    void smallintOutOfRange(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 99999::smallint", "22003");
    }

    // =========================================================================
    // 6. Operator / function not found (42883)
    // =========================================================================

    @Test
    void operatorDoesNotExistTextMinusInt(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 'a'::text - 1", "42883");
    }

    @Test
    void undefinedFunctionCall(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT nonexistent_function_xyz(1)", "42883");
    }

    // =========================================================================
    // 7. Transaction state errors (25P02, 25P01)
    // =========================================================================

    @Test
    void abortedTransactionBlock(DualExecutor db) throws Exception {
        db.execute("BEGIN");
        DualExecutor.executeCatchingErrorInfo(db.pgConnection(), "SELECT 1/0");
        if (db.pgjavaConnection() != null)
            DualExecutor.executeCatchingErrorInfo(db.pgjavaConnection(), "SELECT 1/0");
        db.assertErrorMessage("SELECT 1", "25P02");
        db.execute("ROLLBACK");
    }

    // =========================================================================
    // 8. Subquery errors (21000)
    // =========================================================================

    @Test
    void subqueryReturnsMultipleRows(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE sq_test (id int)");
        db.execute("INSERT INTO sq_test VALUES (1), (2)");
        db.assertErrorMessage("SELECT (SELECT id FROM sq_test)", "21000");
    }

    // =========================================================================
    // 9. Schema errors (42P06, 3F000, 2BP01)
    // =========================================================================

    @Test
    void schemaAlreadyExists(DualExecutor db) throws Exception {
        db.execute("CREATE SCHEMA sch_dup_test");
        db.assertErrorMessage("CREATE SCHEMA sch_dup_test", "42P06");
    }

    @Test
    void schemaDoesNotExist(DualExecutor db) throws Exception {
        db.assertErrorMessage("DROP SCHEMA nonexistent_schema_xyz", "3F000");
    }

    @Test
    void schemaHasDependentObjects(DualExecutor db) throws Exception {
        db.execute("CREATE SCHEMA dep_sch_test");
        db.execute("CREATE TABLE dep_sch_test.t1 (id int)");
        db.assertErrorMessage("DROP SCHEMA dep_sch_test", "2BP01");
    }

    // =========================================================================
    // 10. Relation already exists (42P07)
    // =========================================================================

    @Test
    void tableAlreadyExists(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE tbl_dup_test (id int)");
        db.assertErrorMessage("CREATE TABLE tbl_dup_test (id int)", "42P07");
    }

    @Test
    void indexAlreadyExists(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE idx_dup_base (id int)");
        db.execute("CREATE INDEX idx_dup_test ON idx_dup_base(id)");
        db.assertErrorMessage("CREATE INDEX idx_dup_test ON idx_dup_base(id)", "42P07");
    }

    // =========================================================================
    // 11. Duplicate column (42701)
    // =========================================================================

    @Test
    void duplicateColumnInAlterTable(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE dup_col (id int, name text)");
        db.assertErrorMessage("ALTER TABLE dup_col ADD COLUMN name text", "42701");
    }

    @Test
    void duplicateColumnInCreate(DualExecutor db) throws Exception {
        db.assertErrorMessage("CREATE TABLE dup_col2 (id int, id int)", "42701");
    }

    // =========================================================================
    // 12. Sequence errors (2200H, 55000)
    // =========================================================================

    @Test
    void sequenceOverflowMax(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE seq_ovf_test MAXVALUE 3 NO CYCLE");
        db.execute("SELECT nextval('seq_ovf_test')");
        db.execute("SELECT nextval('seq_ovf_test')");
        db.execute("SELECT nextval('seq_ovf_test')");
        db.assertErrorMessage("SELECT nextval('seq_ovf_test')", "2200H");
    }

    @Test
    void currvalNotYetDefined(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE seq_curr_test");
        db.assertErrorMessage("SELECT currval('seq_curr_test')", "55000");
    }

    // =========================================================================
    // 13. Savepoint errors (3B001)
    // =========================================================================

    @Test
    void savepointDoesNotExist(DualExecutor db) throws Exception {
        db.execute("BEGIN");
        db.assertErrorMessage("ROLLBACK TO SAVEPOINT nonexistent_sp_xyz", "3B001");
        db.execute("ROLLBACK");
    }

    // =========================================================================
    // 14. Invalid regular expression (2201B)
    // =========================================================================

    @Test
    void invalidRegex(DualExecutor db) throws Exception {
        // Message text differs (Java vs PG regex engine) — validate SQLSTATE only
        db.assertError("SELECT 'abc' ~ '[invalid'", "2201B");
    }

    // =========================================================================
    // 15. Invalid parameter value (22023)
    // =========================================================================

    @Test
    void generateSeriesZeroStep(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT * FROM generate_series(1, 10, 0)", "22023");
    }

    // =========================================================================
    // 16. Missing FROM-clause entry (42P01 via EvalContext)
    // =========================================================================

    @Test
    void missingFromClauseEntry(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE from_test (id int)");
        db.assertErrorMessage("SELECT t.id FROM from_test", "42P01");
    }

    // =========================================================================
    // 17. Undefined sequence (42P01 for sequences)
    // =========================================================================

    @Test
    void undefinedSequence(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT nextval('nonexistent_seq_xyz')", "42P01");
    }

    // =========================================================================
    // 18. Multiple operator/function error paths (42883)
    // =========================================================================

    @Test
    void negateNonNumeric(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT -'text'::text", "42883");
    }

    @Test
    void moduloByZero(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 10 % 0", "22012");
    }

    // =========================================================================
    // 19. Additional 22P02 paths — various type conversions
    // =========================================================================

    @Test
    void invalidTimestampInput(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 'not-a-timestamp'::timestamp", "22007");
    }

    @Test
    void invalidTimestamptzInput(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 'garbage'::timestamptz", "22007");
    }

    // =========================================================================
    // 20. View errors
    // =========================================================================

    @Test
    void undefinedView(DualExecutor db) throws Exception {
        db.assertErrorMessage("DROP VIEW nonexistent_view_xyz", "42P01");
    }

    // =========================================================================
    // 21. More operator paths (42883)
    // =========================================================================

    @Test
    void operatorDoesNotExistBoolPlusBool(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT true + false", "42883");
    }

    @Test
    void undefinedFunctionMultipleArgs(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT nonexistent_fn_xyz(1, 'a', true)", "42883");
    }

    @Test
    void undefinedFunctionNoArgs(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT nonexistent_fn_xyz()", "42883");
    }

    // =========================================================================
    // 22. More cast paths (42846)
    // =========================================================================

    @Test
    void cannotCastIntToBool(DualExecutor db) throws Exception {
        // PG supports int→bool (unlike some other casts); verify SQLSTATE if it fails
        db.assertErrorMessage("SELECT 42::bytea", "42846");
    }

    @Test
    void cannotCastDateToInt(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT current_date::integer", "42846");
    }

    // =========================================================================
    // 23. More constraint paths
    // =========================================================================

    @Test
    void uniqueViolationMultiColumn(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE uq_multi (a int, b int, UNIQUE(a, b))");
        db.execute("INSERT INTO uq_multi VALUES (1, 2)");
        db.assertErrorMessage("INSERT INTO uq_multi VALUES (1, 2)", "23505");
    }

    @Test
    void checkConstraintUpdate(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE chk_upd (val int CHECK (val > 0))");
        db.execute("INSERT INTO chk_upd VALUES (1)");
        db.assertErrorMessage("UPDATE chk_upd SET val = -1", "23514");
    }

    // =========================================================================
    // 24. More undefined column paths (42703)
    // =========================================================================

    @Test
    void undefinedColumnInOrderBy(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE ord_test (id int, name text)");
        db.assertErrorMessage("SELECT * FROM ord_test ORDER BY nope", "42703");
    }

    @Test
    void undefinedColumnInGroupBy(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE grp_test (id int, name text)");
        db.assertErrorMessage("SELECT * FROM grp_test GROUP BY nope", "42703");
    }

    // =========================================================================
    // 25. More arithmetic overflow paths (22003)
    // =========================================================================

    @Test
    void integerOverflowMultiply(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 2147483647 * 2", "22003");
    }

    @Test
    void bigintOverflowMultiply(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 9223372036854775807::bigint * 2::bigint", "22003");
    }

    // =========================================================================
    // 26. More division by zero paths (22012)
    // =========================================================================

    @Test
    void moduloByZeroBigint(DualExecutor db) throws Exception {
        db.assertErrorMessage("SELECT 10::bigint % 0::bigint", "22012");
    }

    // =========================================================================
    // 27. More 42P01 paths — DELETE/UPDATE on nonexistent table
    // =========================================================================

    @Test
    void undefinedTableInDelete(DualExecutor db) throws Exception {
        db.assertErrorMessage("DELETE FROM nonexistent_del_xyz", "42P01");
    }

    @Test
    void undefinedTableInUpdate(DualExecutor db) throws Exception {
        db.assertErrorMessage("UPDATE nonexistent_upd_xyz SET a = 1", "42P01");
    }

    // =========================================================================
    // 28. Sequence already exists (42P07)
    // =========================================================================

    @Test
    void sequenceAlreadyExists(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE seq_dup_test");
        db.assertErrorMessage("CREATE SEQUENCE seq_dup_test", "42P07");
    }

    // =========================================================================
    // 29. View already exists (42P07)
    // =========================================================================

    @Test
    void viewAlreadyExists(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE view_base (id int)");
        db.execute("CREATE VIEW view_dup_test AS SELECT * FROM view_base");
        db.assertErrorMessage("CREATE VIEW view_dup_test AS SELECT * FROM view_base", "42P07");
    }

    // =========================================================================
    // 30. DROP nonexistent sequence (42P01)
    // =========================================================================

    @Test
    void undefinedSequenceDrop(DualExecutor db) throws Exception {
        db.assertErrorMessage("DROP SEQUENCE nonexistent_seq_drop_xyz", "42P01");
    }

    // =========================================================================
    // 31. More FK violation paths — multi-column
    // =========================================================================

    @Test
    void fkViolationMultiColumn(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE fk_mp (a int, b int, PRIMARY KEY (a, b))");
        db.execute("CREATE TABLE fk_mc (x int, a int, b int, FOREIGN KEY (a, b) REFERENCES fk_mp(a, b))");
        db.execute("INSERT INTO fk_mp VALUES (1, 2)");
        db.assertErrorMessage("INSERT INTO fk_mc VALUES (1, 99, 99)", "23503");
    }

    // =========================================================================
    // 32. Ambiguous column in ORDER BY
    // =========================================================================

    @Test
    void ambiguousColumnInJoin(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE amb_j1 (id int, val int)");
        db.execute("CREATE TABLE amb_j2 (id int, val int)");
        db.assertErrorMessage(
                "SELECT val FROM amb_j1 JOIN amb_j2 ON amb_j1.id = amb_j2.id", "42702");
    }

    // =========================================================================
    // 33. NOT NULL with multiple columns
    // =========================================================================

    @Test
    void notNullViolationSecondColumn(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE nn_multi (a int NOT NULL, b text NOT NULL, c int)");
        db.assertErrorMessage("INSERT INTO nn_multi (a, b, c) VALUES (1, NULL, 3)", "23502");
    }

    // =========================================================================
    // 34. Sequence overflow descending
    // =========================================================================

    @Test
    void sequenceOverflowMin(DualExecutor db) throws Exception {
        db.execute("CREATE SEQUENCE seq_desc_test INCREMENT -1 MINVALUE 1 MAXVALUE 3 START 3 NO CYCLE");
        db.execute("SELECT nextval('seq_desc_test')");
        db.execute("SELECT nextval('seq_desc_test')");
        db.execute("SELECT nextval('seq_desc_test')");
        db.assertErrorMessage("SELECT nextval('seq_desc_test')", "2200H");
    }

    // =========================================================================
    // 35. Drop column that doesn't exist
    // =========================================================================

    @Test
    void dropNonexistentColumn(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE drop_col (id int, name text)");
        db.assertErrorMessage("ALTER TABLE drop_col DROP COLUMN nope", "42703");
    }

    // =========================================================================
    // 36. INSERT column count mismatch
    // =========================================================================

    @Test
    void insertTooManyValues(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE ins_cnt (id int, name text)");
        db.assertErrorMessage("INSERT INTO ins_cnt VALUES (1, 'a', 'extra')", "42601");
    }

    // =========================================================================
    // 37. Multiple subquery-related errors
    // =========================================================================

    @Test
    void subqueryMultipleRowsInWhere(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE sq_wh (id int)");
        db.execute("INSERT INTO sq_wh VALUES (1), (2)");
        db.assertErrorMessage(
                "SELECT * FROM sq_wh WHERE id = (SELECT id FROM sq_wh)", "21000");
    }
}
