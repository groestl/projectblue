package org.pgjava.executor;

import org.pgjava.catalog.*;
import org.pgjava.engine.ColumnMeta;
import org.pgjava.engine.PgErrorException;
import org.pgjava.engine.QueryResult;
import org.pgjava.sql.ast.*;
import org.pgjava.storage.*;
import org.pgjava.storage.LockMode;
import org.pgjava.types.PgOid;
import org.pgjava.types.TypeInput;
import org.pgjava.types.TypeOutput;
import org.pgjava.wal.Transaction;
import org.pgjava.wal.TransactionManager;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * Executes {@code COPY table FROM STDIN} (bulk load) and
 * {@code COPY table TO STDOUT} / {@code COPY (query) TO STDOUT} (bulk export).
 *
 * <h3>Supported options</h3>
 * <ul>
 *   <li>{@code FORMAT TEXT} (default) — tab-delimited, {@code \N} = null,
 *       backslash escape sequences in values</li>
 *   <li>{@code FORMAT CSV} — comma-delimited (or custom), quoted fields,
 *       empty string = null unless {@code NULL ''} is overridden</li>
 *   <li>{@code DELIMITER 'char'} — overrides the column separator</li>
 *   <li>{@code NULL 'string'} — overrides the null marker</li>
 *   <li>{@code HEADER} — skip (FROM) or emit (TO) a header line with column
 *       names; silently accepted but the header line is ignored on input</li>
 *   <li>{@code ENCODING 'enc'} — accepted and ignored (always UTF-8)</li>
 * </ul>
 *
 * <h3>COPY TO result format</h3>
 * Returns a {@link QueryResult} with a single column {@code "data"} where
 * each row contains one formatted line (without the trailing newline).
 */
public final class CopyExecutor {

    private final CatalogManager     catalog;
    private final HeapStorage        storage;
    private final TransactionManager txMgr;
    private final Planner            planner;
    private final List<String>       searchPath;
    private org.pgjava.engine.Database triggerDb;

    public CopyExecutor(CatalogManager catalog, HeapStorage storage,
                        TransactionManager txMgr, Planner planner,
                        List<String> searchPath) {
        this.catalog    = catalog;
        this.storage    = storage;
        this.txMgr      = txMgr;
        this.planner    = planner;
        this.searchPath = searchPath;
    }

    public void setTriggerDatabase(org.pgjava.engine.Database db) { this.triggerDb = db; }

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Execute a COPY statement.
     *
     * @param stmt       parsed COPY AST node
     * @param inlineData data lines (COPY FROM only); null if none provided
     * @param tx         active transaction (auto-commit callers pass a fresh tx)
     */
    public QueryResult execute(CopyStmt stmt, String inlineData, Transaction tx)
            throws SQLException {
        CopyOptions opts = parseOptions(stmt.options());

        if (stmt.isFrom()) {
            return executeCopyFrom(stmt, opts, inlineData, tx);
        } else {
            return executeCopyTo(stmt, opts, tx);
        }
    }

    // =========================================================================
    // COPY FROM STDIN
    // =========================================================================

    private QueryResult executeCopyFrom(CopyStmt stmt, CopyOptions opts,
                                        String inlineData, Transaction tx)
            throws SQLException {
        if (inlineData == null || inlineData.isBlank()) {
            // No data provided — treat as a no-op (0 rows inserted).
            // This matches behaviour of clients that call Statement.execute("COPY … FROM STDIN")
            // without supplying any data (e.g. Flyway stripping the data block).
            return new QueryResult(0, List.of(), List.of());
        }

        TableDef def = catalog.resolveTable(stmt.relation().relName(), searchPath);

        // Determine which columns to populate, in what order
        List<ColumnDef> targetCols = resolveColumns(def, stmt.attlist());
        int[] colMapping = buildColMapping(def, targetCols);

        // Parse each data line
        String[] lines = inlineData.split("\n", -1);
        HeapTable ht = storage.table(def.oid());
        int rowCount = 0;
        // Acquire ROW_EXCLUSIVE table-level lock (held until COMMIT/ROLLBACK).
        storage.tableLocks().acquire(def.oid(), tx.txid(), LockMode.ROW_EXCLUSIVE, 0);
        // Narrow constraint lock for the mutation window.
        ht.constraintLock().lock();
        try {
        // BEFORE STATEMENT INSERT trigger
        if (triggerDb != null) {
            TriggerExecutor.fireStatement(def, org.pgjava.catalog.TriggerDef.INSERT,
                    org.pgjava.catalog.TriggerDef.BEFORE, triggerDb, searchPath);
        }

        boolean firstLine = true;
        for (String rawLine : lines) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.isEmpty()) continue;
            // HEADER: skip first line if requested
            if (firstLine && opts.header()) { firstLine = false; continue; }
            firstLine = false;

            Object[] parsed = parseLine(line, targetCols, opts);
            Object[] targetVals = new Object[def.columnCount()];
            if (colMapping.length == 0) {
                // All columns in order
                System.arraycopy(parsed, 0, targetVals, 0, Math.min(parsed.length, targetVals.length));
            } else {
                for (int i = 0; i < colMapping.length && i < parsed.length; i++) {
                    int idx = colMapping[i];
                    if (idx >= 0) targetVals[idx] = parsed[i];
                }
            }

            // Apply defaults for missing columns
            applyDefaults(def, targetVals, planner.evaluator());

            // BEFORE ROW INSERT trigger
            if (triggerDb != null) {
                targetVals = TriggerExecutor.fireBeforeRow(def, org.pgjava.catalog.TriggerDef.INSERT,
                        targetVals, null, triggerDb, searchPath);
                if (targetVals == null) continue; // trigger suppressed this row
            }

            ConstraintChecker.checkNotNull(def, targetVals);
            List<BTreeIndex> indexes = storage.indexesForTable(def.oid());
            ConstraintChecker.checkUnique(def, targetVals, indexes, null);
            ConstraintChecker.checkCheck(def, targetVals, planner.evaluator());
            ConstraintChecker.checkDomainAndEnum(def, targetVals, planner.evaluator());
            ConstraintChecker.checkForeignKey(def, targetVals, storage,
                    name -> catalog.resolveTable(name, searchPath),
                    txMgr.snapshotFor(tx.txid()));
            try {
                txMgr.insert(tx, def.oid(), targetVals,
                        TransactionManager.columnNameExtractor(storage.table(def.oid())));
            } catch (IOException e) {
                throw PgErrorException.error("XX000", "COPY insert WAL write failed: " + e.getMessage()).cause(e).build();
            }
            rowCount++;

            // AFTER ROW INSERT trigger
            if (triggerDb != null) {
                TriggerExecutor.fireAfterRow(def, org.pgjava.catalog.TriggerDef.INSERT,
                        targetVals, null, triggerDb, searchPath);
            }
        }

        // AFTER STATEMENT INSERT trigger
        if (triggerDb != null) {
            TriggerExecutor.fireStatement(def, org.pgjava.catalog.TriggerDef.INSERT,
                    org.pgjava.catalog.TriggerDef.AFTER, triggerDb, searchPath);
        }
        } finally {
            ht.constraintLock().unlock();
        }
        return new QueryResult(rowCount, List.of(), List.of());
    }

    // =========================================================================
    // COPY TO STDOUT
    // =========================================================================

    private QueryResult executeCopyTo(CopyStmt stmt, CopyOptions opts, Transaction tx)
            throws SQLException {
        // Collect rows from table or subquery
        List<String> colNames;
        List<Object[]> dataRows;

        if (stmt.query() != null) {
            QueryResult qr = planner.executeSelect(stmt.query());
            colNames = qr.columns().stream().map(ColumnMeta::name).toList();
            dataRows = qr.rows();
        } else {
            TableDef def = catalog.resolveTable(stmt.relation().relName(), searchPath);
            List<ColumnDef> targetCols = resolveColumns(def, stmt.attlist());
            colNames = targetCols.stream().map(ColumnDef::name).toList();

            int[] attNums = targetCols.stream()
                    .mapToInt(c -> c.attnum() - 1).toArray();
            HeapTable ht = storage.table(def.oid());
            dataRows = new ArrayList<>();
            long currentTxid = tx != null ? tx.txid() : 0L;
            var snap = txMgr.snapshotFor(currentTxid);
            var scan = ht.fullScan(snap);
            while (scan.hasNext()) {
                Row row = scan.next();
                Object[] src = row.values();
                Object[] out = new Object[targetCols.size()];
                for (int i = 0; i < attNums.length; i++) {
                    out[i] = (attNums[i] >= 0 && attNums[i] < src.length)
                            ? src[attNums[i]] : null;
                }
                dataRows.add(out);
            }
        }

        // Format rows
        List<Object[]> outputRows = new ArrayList<>();
        if (opts.header()) {
            outputRows.add(new Object[]{formatHeader(colNames, opts)});
        }
        for (Object[] row : dataRows) {
            outputRows.add(new Object[]{formatRow(row, opts)});
        }

        List<ColumnMeta> meta = List.of(ColumnMeta.varchar("data"));
        return new QueryResult(-1, meta, outputRows);
    }

    // =========================================================================
    // Text / CSV formatting
    // =========================================================================

    private Object[] parseLine(String line, List<ColumnDef> cols, CopyOptions opts)
            throws SQLException {
        List<String> fields = opts.isCsv()
                ? parseCsvLine(line, opts.delimiter())
                : parseTextLine(line, opts.delimiter());

        Object[] result = new Object[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            String field = i < fields.size() ? fields.get(i) : null;
            if (field == null || field.equals(opts.nullStr())) {
                result[i] = null;
            } else {
                ColumnDef col = cols.get(i);
                int oid = col.type().oid();
                // COPY uses the type's input function — equivalent to casting from unknown/text.
                // Skip CoercionEngine to avoid the implicit-cast allowlist check.
                result[i] = (oid == PgOid.TEXT || oid == PgOid.VARCHAR || oid == PgOid.BPCHAR)
                        ? field
                        : TypeInput.parse(field, oid);
            }
        }
        return result;
    }

    /** Split a TEXT-format COPY line by delimiter, unescaping backslash sequences. */
    private static List<String> parseTextLine(String line, char delim) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean curIsNull = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == delim) {
                out.add(curIsNull ? null : cur.toString());
                cur.setLength(0);
                curIsNull = false;
            } else if (c == '\\' && i + 1 < line.length()) {
                char next = line.charAt(++i);
                switch (next) {
                    case 'N' -> curIsNull = true;  // \N = null for this field
                    case 't' -> cur.append('\t');
                    case 'n' -> cur.append('\n');
                    case 'r' -> cur.append('\r');
                    case '\\' -> cur.append('\\');
                    default  -> { cur.append('\\'); cur.append(next); }
                }
            } else {
                cur.append(c);
            }
        }
        out.add(curIsNull ? null : cur.toString());
        return out;
    }

    /** Parse a CSV line respecting quoting. */
    private static List<String> parseCsvLine(String line, char delim) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i <= line.length()) {
            if (i == line.length()) { out.add(""); break; }
            char c = line.charAt(i);
            if (c == '"') {
                // Quoted field
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < line.length()) {
                    char cc = line.charAt(i);
                    if (cc == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                            sb.append('"'); i += 2;
                        } else {
                            i++; break;
                        }
                    } else {
                        sb.append(cc); i++;
                    }
                }
                out.add(sb.toString());
                if (i < line.length() && line.charAt(i) == delim) i++;
            } else {
                // Unquoted field
                int start = i;
                while (i < line.length() && line.charAt(i) != delim) i++;
                out.add(line.substring(start, i));
                if (i < line.length()) i++; // skip delimiter
            }
        }
        return out;
    }

    private static String formatRow(Object[] row, CopyOptions opts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.length; i++) {
            if (i > 0) sb.append(opts.delimiter());
            Object v = row[i];
            if (v == null) {
                sb.append(opts.nullStr());
            } else {
                String s = TypeOutput.format(v, PgOid.TEXT);
                if (opts.isCsv()) {
                    sb.append(csvQuote(s, opts.delimiter()));
                } else {
                    sb.append(textEscape(s));
                }
            }
        }
        return sb.toString();
    }

    private static String formatHeader(List<String> colNames, CopyOptions opts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < colNames.size(); i++) {
            if (i > 0) sb.append(opts.delimiter());
            if (opts.isCsv()) sb.append(csvQuote(colNames.get(i), opts.delimiter()));
            else               sb.append(colNames.get(i));
        }
        return sb.toString();
    }

    /** Escape a TEXT-format field value (backslash escaping). */
    private static String textEscape(String s) {
        if (!s.contains("\\") && !s.contains("\t") && !s.contains("\n") && !s.contains("\r")) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Quote a CSV field if it contains delimiter, quote, or newline. */
    private static String csvQuote(String s, char delim) {
        if (!s.contains(String.valueOf(delim)) && !s.contains("\"")
                && !s.contains("\n") && !s.contains("\r")) {
            return s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private List<ColumnDef> resolveColumns(TableDef def, List<String> attlist) {
        if (attlist == null || attlist.isEmpty()) return def.columns();
        List<ColumnDef> cols = new ArrayList<>(attlist.size());
        for (String name : attlist) {
            ColumnDef c = def.column(name.toLowerCase());
            if (c == null) throw new IllegalArgumentException(
                    "column \"" + name + "\" of relation \"" + def.name() + "\" does not exist");
            cols.add(c);
        }
        return cols;
    }

    private int[] buildColMapping(TableDef def, List<ColumnDef> targetCols) {
        // If targetCols == all columns in order, use the fast path (empty mapping)
        if (targetCols.equals(def.columns())) return new int[0];
        int[] m = new int[targetCols.size()];
        for (int i = 0; i < targetCols.size(); i++) {
            m[i] = targetCols.get(i).attnum() - 1;
        }
        return m;
    }

    private static void applyDefaults(TableDef def, Object[] vals, Evaluator eval) throws SQLException {
        for (int i = 0; i < def.columnCount(); i++) {
            if (vals[i] == null) {
                ColumnDef col = def.columns().get(i);
                if (col.defaultExpr() != null) {
                    try {
                        vals[i] = eval.eval(col.defaultExpr(), EvalContext.empty());
                    } catch (SQLException e) { throw e; }
                    catch (Exception e) { /* complex default — fall back to null */ }
                }
            }
        }
    }

    private static CopyOptions parseOptions(List<DefElem> elems) {
        boolean isCsv    = false;
        char    delim    = '\t';
        String  nullStr  = null;
        boolean header   = false;
        for (DefElem e : elems) {
            switch (e.name().toLowerCase()) {
                case "format" -> {
                    String fmt = strVal(e.value()).toLowerCase();
                    if (fmt.contains("csv")) { isCsv = true; delim = ','; }
                }
                case "delimiter" -> {
                    String d = strVal(e.value());
                    if (!d.isEmpty()) delim = d.charAt(0);
                }
                case "null" -> nullStr = strVal(e.value());
                case "header" -> header = true;
                // "encoding" → silently ignored
            }
        }
        if (nullStr == null) nullStr = isCsv ? "" : "\\N";
        return new CopyOptions(isCsv, delim, nullStr, header);
    }

    /** Extract string value from a DefElem value node. */
    private static String strVal(org.pgjava.sql.ast.Node v) {
        if (v == null) return "";
        if (v instanceof org.pgjava.sql.ast.StringLiteral sl) return sl.value();
        if (v instanceof org.pgjava.sql.ast.IntegerLiteral il) return String.valueOf(il.value());
        // Fallback: strip surrounding single-quotes from raw text
        String s = v.toString();
        if (s.startsWith("'") && s.endsWith("'") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** Immutable options parsed from COPY's WITH clause. */
    record CopyOptions(boolean isCsv, char delimiter, String nullStr, boolean header) {}
}
