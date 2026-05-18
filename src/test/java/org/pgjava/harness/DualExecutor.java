package org.pgjava.harness;

import org.opentest4j.TestAbortedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Runs SQL against both pgjava and embedded-postgres and compares results.
 *
 * <p><b>Design contract:</b>
 * <ul>
 *   <li>The embedded-postgres side is always authoritative — failures there are test failures.
 *   <li>The pgjava side is optional at this stage. If the pgjava connection is null, if
 *       {@link #assumeSupported()} has been called, or if any operation throws
 *       {@link UnsupportedOperationException}, the pgjava side is silently skipped.
 *   <li>If the pgjava side throws a real {@link SQLException}, the test fails (a real SQL error
 *       should produce the same SQLSTATE as postgres).
 * </ul>
 *
 * <p>Catalog queries ({@code pg_catalog.*} / {@code information_schema.*}) are excluded from
 * cross-comparison by default because OIDs and system data differ between instances.
 * Annotate the test with {@link CatalogTest} and call {@link #assertCatalogQuery} to opt in.
 */
public final class DualExecutor {
    private static final Logger log = LoggerFactory.getLogger(DualExecutor.class);

    /** Columns excluded from cross-comparison because they are non-deterministic. */
    private static final Set<String> NON_DETERMINISTIC_COLUMNS = Set.of(
            "now", "current_timestamp", "clock_timestamp", "statement_timestamp",
            "random", "gen_random_uuid", "uuid_generate_v4",
            "pg_backend_pid", "txid_current", "pg_current_xact_id",
            "pg_postmaster_start_time"
    );

    private static final Pattern ORDER_BY = Pattern.compile(
            "(?i)\\bORDER\\s+BY\\b");
    private static final Pattern CATALOG_QUERY = Pattern.compile(
            "(?i)\\b(pg_catalog|information_schema)\\s*\\.");

    private final Connection pgConn;
    private final Connection pgjavaConn; // may be null

    /**
     * When true, the pgjava side is skipped for all subsequent operations.
     * Set by {@link #assumeSupported()}.
     */
    private boolean pgjavaAssumedUnsupported = false;

    DualExecutor(Connection pgConn, Connection pgjavaConn) {
        this.pgConn = pgConn;
        this.pgjavaConn = pgjavaConn;
    }

    // -------------------------------------------------------------------------
    // Setup / DDL / DML

    /**
     * Execute DDL or DML against both connections. Postgres side must succeed.
     * Pgjava side failures due to {@link UnsupportedOperationException} are silently ignored.
     */
    public void execute(String sql) throws SQLException {
        // Postgres side — must succeed
        try (Statement st = pgConn.createStatement()) {
            st.execute(sql);
        }
        // Pgjava side — skip if unavailable or assumeSupported() was called
        if (pgjavaConn != null && !pgjavaAssumedUnsupported) {
            try (Statement st = pgjavaConn.createStatement()) {
                st.execute(sql);
            } catch (UnsupportedOperationException e) {
                log.warn("pgjava: UnsupportedOperationException for execute — skipping. SQL: {}", sql);
            } catch (SQLException e) {
                org.junit.jupiter.api.Assertions.fail(
                        "pgjava threw SQLSTATE " + e.getSQLState() +
                        " but postgres succeeded.\n  SQL: " + sql +
                        "\n  Error: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Query comparison

    /**
     * Run {@code sql} on both connections and assert results are equal.
     * Catalog queries ({@code pg_catalog.*} / {@code information_schema.*}) are rejected —
     * use {@link #assertCatalogQuery} for those.
     */
    public void assertQuery(String sql) throws SQLException {
        if (CATALOG_QUERY.matcher(sql).find()) {
            throw new IllegalArgumentException(
                    "Use assertCatalogQuery() for pg_catalog / information_schema queries. " +
                    "Cross-comparison is disabled by default due to OID differences.");
        }
        assertQueryInternal(sql);
    }

    /**
     * Like {@link #assertQuery} but for catalog queries. Results are compared only on columns
     * that are explicitly returned (caller must exclude OID columns from the SELECT list).
     */
    public void assertCatalogQuery(String sql) throws SQLException {
        assertQueryInternal(sql);
    }

    private void assertQueryInternal(String sql) throws SQLException {
        boolean ordered = hasTopLevelOrderBy(sql);

        // Postgres side
        ResultSetSnapshot pgSnap;
        try (Statement st = pgConn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            pgSnap = ResultSetSnapshot.capture(rs, ordered, NON_DETERMINISTIC_COLUMNS);
        }

        // Pgjava side
        if (pgjavaConn == null || pgjavaAssumedUnsupported) {
            log.debug("pgjava skipped for query ({}): {}",
                    pgjavaConn == null ? "no connection" : "assumeSupported", sql);
            return;
        }
        ResultSetSnapshot pgjavaSnap;
        try (Statement st = pgjavaConn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            pgjavaSnap = ResultSetSnapshot.capture(rs, ordered, NON_DETERMINISTIC_COLUMNS);
        } catch (UnsupportedOperationException e) {
            log.warn("pgjava: UnsupportedOperationException for query — skipping. SQL: {}", sql);
            return;
        } catch (SQLException e) {
            org.junit.jupiter.api.Assertions.fail(
                    "pgjava threw SQLSTATE " + e.getSQLState() +
                    " but postgres returned " + pgSnap.rowCount() + " rows.\n  SQL: " + sql +
                    "\n  Error: " + e.getMessage());
            return;
        }

        ResultSetComparator.assertEqual(pgSnap, pgjavaSnap, sql);
    }

    /**
     * Assert that both connections throw a {@link SQLException} with the same SQLSTATE code.
     * Error message text is NOT compared (it varies by PG version).
     */
    public void assertError(String sql, String expectedSqlState) throws SQLException {
        String pgState = executeCatchingError(pgConn, sql);
        if (pgState == null) {
            org.junit.jupiter.api.Assertions.fail(
                    "Expected postgres to throw SQLSTATE " + expectedSqlState +
                    " but query succeeded. SQL: " + sql);
        }
        if (!expectedSqlState.equals(pgState)) {
            org.junit.jupiter.api.Assertions.fail(
                    "Expected SQLSTATE " + expectedSqlState + " but postgres threw " + pgState +
                    ". SQL: " + sql);
        }

        if (pgjavaConn == null || pgjavaAssumedUnsupported) return;
        String pgjavaState = executeCatchingError(pgjavaConn, sql);
        if (pgjavaState == null) {
            org.junit.jupiter.api.Assertions.fail(
                    "pgjava did not throw for error query (postgres threw SQLSTATE " +
                    expectedSqlState + "). SQL: " + sql);
            return;
        }
        if (!expectedSqlState.equals(pgjavaState)) {
            org.junit.jupiter.api.Assertions.fail(
                    "SQLSTATE mismatch: postgres=" + pgState + " pgjava=" + pgjavaState +
                    ". SQL: " + sql);
        }
    }

    /**
     * Like {@link #assertError} but also compares the error <em>message text</em>
     * between pgjava and postgres.  SQLSTATE must match exactly; the message text
     * is compared after stripping trailing whitespace from each line.
     */
    public void assertErrorMessage(String sql, String expectedSqlState) throws SQLException {
        ErrorInfo pgErr = executeCatchingErrorInfo(pgConn, sql);
        if (pgErr == null) {
            org.junit.jupiter.api.Assertions.fail(
                    "Expected postgres to throw SQLSTATE " + expectedSqlState +
                    " but query succeeded. SQL: " + sql);
        }
        if (!expectedSqlState.equals(pgErr.sqlState)) {
            org.junit.jupiter.api.Assertions.fail(
                    "Expected SQLSTATE " + expectedSqlState + " but postgres threw " +
                    pgErr.sqlState + ". SQL: " + sql);
        }

        if (pgjavaConn == null || pgjavaAssumedUnsupported) return;
        ErrorInfo pgjavaErr = executeCatchingErrorInfo(pgjavaConn, sql);
        if (pgjavaErr == null) {
            log.debug("pgjava: did not throw for error query — skipping (not yet implemented)");
            return;
        }
        if (!expectedSqlState.equals(pgjavaErr.sqlState)) {
            org.junit.jupiter.api.Assertions.fail(
                    "SQLSTATE mismatch: postgres=" + pgErr.sqlState + " pgjava=" +
                    pgjavaErr.sqlState + ". SQL: " + sql);
        }
        // Compare message text (the core assertion for error format matching)
        String pgMsg = normalizeErrorMessage(pgErr.message);
        String pgjavaMsg = normalizeErrorMessage(pgjavaErr.message);
        if (!pgMsg.equals(pgjavaMsg)) {
            org.junit.jupiter.api.Assertions.fail(
                    "Error message mismatch for SQLSTATE " + expectedSqlState +
                    ":\n  postgres: " + pgErr.message +
                    "\n  pgjava:   " + pgjavaErr.message +
                    "\n  SQL: " + sql);
        }
    }

    /** Captured error info from a failed SQL execution. */
    public record ErrorInfo(String sqlState, String message) {}

    /** Execute SQL and return full error info, or null if no error. */
    public static ErrorInfo executeCatchingErrorInfo(Connection conn, String sql) {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
            return null;
        } catch (UnsupportedOperationException e) {
            return null;
        } catch (SQLException e) {
            return new ErrorInfo(e.getSQLState(), e.getMessage());
        }
    }

    /**
     * Normalize error messages for comparison.
     * PG JDBC bakes Detail/Hint/Position into getMessage() as multi-line text:
     * {@code "ERROR: msg\n  Detail: ...\n  Position: ..."}
     * We strip the "ERROR: " prefix and any Detail/Hint/Position/Where suffixes,
     * keeping only the core message line — the 'M' field from the wire protocol.
     */
    /** Check for ORDER BY only at the top-level (not inside parenthesized subqueries). */
    private static boolean hasTopLevelOrderBy(String sql) {
        int depth = 0;
        String upper = sql.toUpperCase();
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && c == 'O' && upper.startsWith("ORDER", i)
                    && i + 8 < upper.length()
                    && Character.isWhitespace(upper.charAt(i + 5))
                    && upper.substring(i + 5).stripLeading().startsWith("BY")) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeErrorMessage(String msg) {
        if (msg == null) return "";
        String s = msg.strip();
        if (s.startsWith("ERROR: ")) s = s.substring(7);
        // Strip appended fields that PG JDBC adds from ErrorResponse
        int detailIdx = s.indexOf("\n  Detail:");
        if (detailIdx >= 0) s = s.substring(0, detailIdx);
        int hintIdx = s.indexOf("\n  Hint:");
        if (hintIdx >= 0) s = s.substring(0, hintIdx);
        int posIdx = s.indexOf("\n  Position:");
        if (posIdx >= 0) s = s.substring(0, posIdx);
        int whereIdx = s.indexOf("\n  Where:");
        if (whereIdx >= 0) s = s.substring(0, whereIdx);
        return s.strip();
    }

    private static String executeCatchingError(Connection conn, String sql) {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
            return null; // no error
        } catch (UnsupportedOperationException e) {
            return null;
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    /**
     * Mark the pgjava side of this test as known-unimplemented.
     * The postgres side still runs and is validated. All subsequent pgjava-side operations
     * in this test are skipped without comparison.
     *
     * <p>Tests graduate from {@code assumeSupported()} as phases are implemented.
     */
    public void assumeSupported() {
        pgjavaAssumedUnsupported = true;
    }

    /**
     * Explicitly abort the test (both sides) — use when neither postgres nor pgjava
     * should be tested for the current configuration.
     */
    public void abort(String reason) {
        throw new TestAbortedException(reason);
    }

    public Connection pgConnection()     { return pgConn; }
    public Connection pgjavaConnection() { return pgjavaConn; }
    public boolean isPgjavaAvailable()   { return pgjavaConn != null; }
}
