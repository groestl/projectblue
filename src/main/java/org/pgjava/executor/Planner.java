package org.pgjava.executor;

import org.pgjava.catalog.*;
import org.pgjava.engine.PgErrorException;
import org.pgjava.sql.ast.*;
import org.pgjava.storage.HeapStorage;
import org.pgjava.storage.Row;
import org.pgjava.storage.TxSnapshot;
import org.pgjava.wal.Transaction;
import org.pgjava.wal.TransactionManager;

import java.sql.SQLException;
import java.util.*;

/**
 * Converts an AST statement into a physical Volcano-model operator tree.
 *
 * <p>Supports:
 * <ul>
 *   <li>SELECT (with FROM, WHERE, GROUP BY, HAVING, ORDER BY, LIMIT, OFFSET, DISTINCT,
 *       UNION/INTERSECT/EXCEPT, VALUES, subqueries in FROM, CTEs)</li>
 *   <li>INSERT ... VALUES / INSERT ... SELECT</li>
 *   <li>UPDATE ... SET ... WHERE</li>
 *   <li>DELETE ... WHERE</li>
 * </ul>
 *
 * <p>No query optimizer — plans are built naively left-to-right with nested-loop joins.
 */
public final class Planner {

    private final CatalogManager     catalog;
    private final HeapStorage        storage;
    private final TransactionManager txMgr;
    private final Evaluator          eval;
    private final List<String>       searchPath;

    /**
     * Pre-materialized recursive CTE results.
     * Populated before planning begins for {@code WITH RECURSIVE} queries.
     * Checked first in {@link #planRangeVar} before the SelectStmt-based CTE map.
     */
    private Map<String, List<Object[]>> materializedCtes = new HashMap<>();

    /** Active CTE map from the most recent planSelect call — used by subquery execution. */
    private Map<String, SelectStmt> activeCteMap = Map.of();

    /**
     * Current transaction — set by Session so that SELECT … FOR UPDATE can
     * acquire row locks under the correct txid.  Null in read-only sessions.
     */
    private Transaction currentTx = null;

    /**
     * READ COMMITTED snapshot for this statement — set by Session at statement start.
     * All SeqScans created by this planner use this snapshot to filter uncommitted rows.
     */
    private TxSnapshot txSnapshot = null;

    /**
     * Lock timeout for row-level locking (FOR UPDATE with BLOCK policy).
     * Set from the session's {@code lock_timeout} variable.  0 = default timeout.
     */
    private long lockTimeoutMs = 0;

    /** Database reference for trigger execution. */
    private org.pgjava.engine.Database triggerDatabase;

    public void setCurrentTransaction(Transaction tx) { this.currentTx = tx; }
    public void setSnapshot(TxSnapshot snap)          { this.txSnapshot = snap; }
    public void setLockTimeout(long ms)               { this.lockTimeoutMs = ms; }
    public void setTriggerDatabase(org.pgjava.engine.Database db) { this.triggerDatabase = db; }

    public Planner(CatalogManager catalog, HeapStorage storage,
                   TransactionManager txMgr, Evaluator eval,
                   List<String> searchPath) {
        this.catalog    = catalog;
        this.storage    = storage;
        this.txMgr      = txMgr;
        this.eval       = eval;
        this.searchPath = searchPath;
    }

    // =========================================================================
    // SELECT
    // =========================================================================

    /** Returns the evaluator used by this planner — used by CopyExecutor. */
    public Evaluator evaluator() { return eval; }

    /**
     * Plan a SELECT statement and return the root operator.
     * Caller is responsible for open/close.
     */
    public Operator planSelect(SelectStmt s) throws SQLException {
        return planSelect(s, Map.of());
    }

    /**
     * Execute a SELECT statement and return the full result — used by COPY TO STDOUT.
     */
    public org.pgjava.engine.QueryResult executeSelect(SelectStmt s) throws SQLException {
        Operator root = planSelect(s);
        root.open();
        try {
            OutputSchema schema = root.schema();
            List<org.pgjava.engine.ColumnMeta> cols = new java.util.ArrayList<>(schema.width());
            for (int i = 0; i < schema.width(); i++) {
                cols.add(org.pgjava.engine.ColumnMeta.varchar(schema.name(i)));
            }
            List<Object[]> rows = new java.util.ArrayList<>();
            org.pgjava.storage.Row r;
            while ((r = root.next()) != null) rows.add(r.values().clone());
            return new org.pgjava.engine.QueryResult(-1, cols, rows);
        } finally {
            root.close();
        }
    }

    /**
     * Execute a subquery in the context of an outer query row.
     *
     * <p>Used by {@link Evaluator.SubqueryExecutor} for correlated subqueries,
     * EXISTS, scalar subqueries, ANY/ALL, IN (subquery), and ARRAY(subquery).
     *
     * <p>A child Planner is created with an Evaluator that threads {@code outerCtx}
     * into every expression evaluation, enabling correlated column references.
     */
    public List<org.pgjava.storage.Row> executeSubquery(
            SelectStmt stmt, EvalContext outerCtx) throws SQLException {
        return executeLateralSubquery(stmt, outerCtx).rows();
    }

    /**
     * Execute a lateral subquery and return both the column names and rows.
     * Used by {@link LateralJoin} so it can build the combined output schema.
     */
    public LateralJoin.SubqueryResult executeLateralSubquery(
            SelectStmt stmt, EvalContext outerCtx) throws SQLException {
        // Child evaluator threads outer context into all inner expression evaluations
        Evaluator innerEval = eval.withOuterContext(outerCtx);
        Planner innerPlanner = new Planner(catalog, storage, txMgr, innerEval, searchPath);
        innerPlanner.setSnapshot(txSnapshot);
        innerPlanner.materializedCtes = this.materializedCtes;
        innerPlanner.activeCteMap = this.activeCteMap;
        // Wire subquery executor recursively for nested correlated subqueries
        innerEval.setSubqueryExecutor(innerPlanner::executeSubquery);

        Operator op = innerPlanner.planSelect(stmt, this.activeCteMap);
        List<String> colNames = new ArrayList<>(op.schema().width());
        for (int i = 0; i < op.schema().width(); i++) colNames.add(op.schema().name(i));

        List<org.pgjava.storage.Row> result = new ArrayList<>();
        op.open();
        try {
            org.pgjava.storage.Row row;
            while ((row = op.next()) != null) result.add(row);
        } finally {
            op.close();
        }
        return new LateralJoin.SubqueryResult(colNames, result);
    }

    /**
     * Derive the output schema of a lateral subquery at plan time (no execution).
     * Plans the subquery (using the same evaluator; outer context is not needed
     * to determine column names) and wraps it with the given table alias.
     */
    private OutputSchema lateralSchema(SelectStmt stmt, String alias,
                                       List<String> colAliases) throws SQLException {
        Planner inner = new Planner(catalog, storage, txMgr, eval, searchPath);
        inner.setSnapshot(txSnapshot);
        inner.materializedCtes = this.materializedCtes;
        Operator op = inner.planSelect(stmt);
        if (!colAliases.isEmpty()) {
            return OutputSchema.ofNamesWithAlias(alias, colAliases);
        }
        List<String> names = new ArrayList<>(op.schema().width());
        for (int i = 0; i < op.schema().width(); i++) names.add(op.schema().name(i));
        return OutputSchema.ofNamesWithAlias(alias, names);
    }

    private Operator planSelect(SelectStmt s, Map<String, SelectStmt> cteMap)
            throws SQLException {
        // Collect CTEs defined in this statement FIRST so that set-op branches
        // (UNION ALL etc.) and VALUES can reference them.
        Map<String, SelectStmt> localCteMap = new LinkedHashMap<>(cteMap);
        if (s.withClause() != null) {
            boolean recursive = s.withClause().recursive();
            for (CommonTableExpr cte : s.withClause().ctes()) {
                if (!(cte.query() instanceof SelectStmt sq)) continue;
                String cteName = cte.ctename().toLowerCase();
                if (recursive && sq.setOp() != null && sq.left() != null && sq.right() != null) {
                    // Recursive CTE: materialize iteratively
                    materializeRecursiveCte(cteName, cte.aliasColNames(), sq, localCteMap);
                    // planRangeVar will find it in materializedCtes
                } else {
                    localCteMap.put(cteName, sq);
                }
            }
        }

        // Expose to subquery executor so correlated subqueries can see CTEs
        this.activeCteMap = localCteMap;

        // ── Set operations ────────────────────────────────────────────────────
        if (s.setOp() != null) {
            Operator left  = planSelect(s.left(),  localCteMap);
            Operator right = planSelect(s.right(), localCteMap);
            Operator op = new SetOpOperator(left, right, s.setOp(), s.setAll());
            // ORDER BY / LIMIT / OFFSET on the set-op result (e.g. UNION ALL … ORDER BY …)
            if (!s.orderBy().isEmpty()) {
                List<SortKey> keys = new ArrayList<>();
                for (SortKey sk : s.orderBy()) {
                    Expr e = resolveOrderByExpr(sk.node(), s.targetList());
                    keys.add(new SortKey(e, sk.dir(), sk.nulls()));
                }
                op = new Sort(op, keys, eval);
            }
            long limit  = -1;
            long offset = 0;
            if (s.limitCount() != null) {
                Object lv = evalConstant(s.limitCount());
                if (lv instanceof Number n) limit = n.longValue();
            }
            if (s.limitOffset() != null) {
                Object ov = evalConstant(s.limitOffset());
                if (ov instanceof Number n) offset = n.longValue();
            }
            if (limit >= 0 || offset > 0) op = new Limit(op, limit, offset);
            return op;
        }

        // ── VALUES ───────────────────────────────────────────────────────────
        if (!s.valuesLists().isEmpty()) {
            Operator op = planValues(s);
            if (!s.orderBy().isEmpty()) {
                // Build synthetic target entries: column1, column2, ...
                int width = s.valuesLists().get(0).size();
                List<TargetEntry> syntheticTargets = new ArrayList<>(width);
                for (int i = 1; i <= width; i++) {
                    syntheticTargets.add(new TargetEntry(
                            ColumnRef.of("column" + i), "column" + i));
                }
                List<SortKey> keys = new ArrayList<>();
                for (SortKey sk : s.orderBy()) {
                    Expr e = resolveOrderByExpr(sk.node(), syntheticTargets);
                    keys.add(new SortKey(e, sk.dir(), sk.nulls()));
                }
                op = new Sort(op, keys, eval);
            }
            long limit  = -1;
            long offset = 0;
            if (s.limitCount() != null) {
                Object lv = evalConstant(s.limitCount());
                if (lv instanceof Number n) limit = n.longValue();
            }
            if (s.limitOffset() != null) {
                Object ov = evalConstant(s.limitOffset());
                if (ov instanceof Number n) offset = n.longValue();
            }
            if (limit >= 0 || offset > 0) op = new Limit(op, limit, offset);
            return op;
        }

        // ── FROM clause ──────────────────────────────────────────────────────
        Operator current = null;
        if (s.fromClause().isEmpty()) {
            // SELECT with no FROM — single synthetic row
            current = new ValuesScan(List.of(), List.<Object[]>of(new Object[0]));
        } else {
            for (FromItem fi : s.fromClause()) {
                // LATERAL subquery: re-execute right side per left row with outer context
                if (fi instanceof RangeSubselect rs && rs.lateral() && current != null) {
                    final Operator leftOp = current;
                    OutputSchema rightSchema = lateralSchema(rs.subquery(), rs.alias(), rs.colAliases());
                    current = new LateralJoin(leftOp, rightSchema, rs.subquery(),
                            rs.alias(), rs.colAliases(), this::executeLateralSubquery,
                            null, eval);
                    continue;
                }
                Operator op = planFromItem(fi, localCteMap);
                if (current == null) {
                    current = op;
                } else {
                    // Cross join (no condition)
                    current = new NestedLoopJoin(current, op, null, JoinType.CROSS, eval);
                }
            }
        }

        // ── WHERE ─────────────────────────────────────────────────────────────
        if (s.whereClause() != null) {
            current = new Filter(current, s.whereClause(), eval);
        }

        // ── Detect aggregates in target list / HAVING ────────────────────────
        List<FunctionCall> aggCalls = collectAggregates(s.targetList(), s.having());
        boolean hasAgg = !aggCalls.isEmpty() || !s.groupBy().isEmpty();

        if (hasAgg) {
            List<AggAccumulator> accTemplates = new ArrayList<>();
            for (FunctionCall fc : aggCalls) accTemplates.add(new AggAccumulator(fc, eval));
            current = new HashAgg(current, s.groupBy(), accTemplates, eval);
            // HAVING: rewrite agg and group-key refs before filtering
            if (s.having() != null) {
                Expr havingRewritten = rewriteAggRefs(
                        rewriteGroupKeyRefs(s.having(), s.groupBy()), aggCalls);
                current = new Filter(current, havingRewritten, eval);
            }
        }

        // ── Window functions ──────────────────────────────────────────────────
        WinFuncCollection winCol = collectWindowFuncs(s.targetList(), s.windows());
        List<TargetEntry> effectiveTargets = s.targetList();
        if (!winCol.funcs().isEmpty()) {
            current = new WindowAgg(current, winCol.funcs(), eval);
            effectiveTargets = rewriteTargetsForWindow(s.targetList(), winCol.keyToOut());
        }

        // ── ORDER BY / DISTINCT ON sort ───────────────────────────────────────
        // DISTINCT ON requires the input to be sorted by the DISTINCT ON keys first.
        // ORDER BY keys follow.  Alias/positional references are resolved against
        // effectiveTargets; agg/group-key placeholders are rewritten as needed.
        {
            final List<TargetEntry> targets = effectiveTargets;
            List<SortKey> sortKeys = new ArrayList<>();

            // DISTINCT ON keys become leading sort keys (ASC NULLS LAST)
            for (Expr dk : s.distinctOn()) {
                Expr e = resolveOrderByExpr(dk, targets);
                if (hasAgg) e = rewriteAggRefs(rewriteGroupKeyRefs(e, s.groupBy()), aggCalls);
                sortKeys.add(new SortKey(e, null, null));
            }

            // Regular ORDER BY keys
            for (SortKey sk : s.orderBy()) {
                Expr e = resolveOrderByExpr(sk.node(), targets);
                if (hasAgg) e = rewriteAggRefs(rewriteGroupKeyRefs(e, s.groupBy()), aggCalls);
                sortKeys.add(new SortKey(e, sk.dir(), sk.nulls()));
            }

            if (!sortKeys.isEmpty()) current = new Sort(current, sortKeys, eval);
        }

        // ── Projection ────────────────────────────────────────────────────────
        if (!effectiveTargets.isEmpty() && !isStarOnly(effectiveTargets)) {
            current = buildProjection(current, effectiveTargets, aggCalls, s.groupBy());
        }
        // SELECT * — identity projection, keep all columns

        // ── DISTINCT / DISTINCT ON ────────────────────────────────────────────
        if (!s.distinctOn().isEmpty()) {
            // Resolve DISTINCT ON keys the same way as above (must match sort keys)
            final List<TargetEntry> targets = effectiveTargets;
            List<Expr> distinctOnKeys = s.distinctOn().stream()
                    .map(dk -> resolveOrderByExpr(dk, targets))
                    .toList();
            current = new DistinctOn(current, distinctOnKeys, eval);
        } else if (s.distinct()) {
            current = new Distinct(current);
        }

        // ── FOR UPDATE / FOR SHARE / SKIP LOCKED / NOWAIT ────────────────────
        // Applied BEFORE LIMIT so that SKIP LOCKED filters from the full ordered
        // result set and LIMIT then applies to what remains after locking.
        // (ORDER BY → ForUpdate → Limit is the correct PostgreSQL evaluation order.)
        if (!s.locking().isEmpty() && currentTx != null) {
            // Upgrade table-level lock to ROW_SHARE for FOR UPDATE/SHARE
            // (ACCESS_SHARE was already acquired during scan planning)
            for (LockingClause lc : s.locking()) {
                // If specific tables are listed, lock those; otherwise lock all FROM tables.
                // For simplicity, upgrade the lock on all scanned tables by re-acquiring ROW_SHARE.
                // TableLockManager handles upgrades — if ROW_EXCLUSIVE is already held, this is a no-op.
                for (FromItem fi : s.fromClause()) {
                    if (fi instanceof RangeVar rv) {
                        try {
                            TableDef td = catalog.resolveTable(rv.relName().toLowerCase(), searchPath);
                            storage.tableLocks().acquire(td.oid(), currentTx.txid(),
                                    org.pgjava.storage.LockMode.ROW_SHARE, lockTimeoutMs);
                        } catch (SQLException ignored) {
                            // Table might be a CTE/view/virtual — skip
                        }
                    }
                }
            }

            // Merge all locking clauses — take the strictest wait policy
            LockWaitPolicy policy = LockWaitPolicy.BLOCK;
            for (LockingClause lc : s.locking()) {
                if (lc.waitPolicy() == LockWaitPolicy.SKIP)  { policy = LockWaitPolicy.SKIP;  break; }
                if (lc.waitPolicy() == LockWaitPolicy.ERROR) { policy = LockWaitPolicy.ERROR; }
            }
            current = new ForUpdateOperator(current, storage.rowLocks(), policy, currentTx.txid(), lockTimeoutMs);
        }

        // ── LIMIT / OFFSET ────────────────────────────────────────────────────
        long limit  = -1;
        long offset = 0;
        if (s.limitCount() != null) {
            Object lv = evalConstant(s.limitCount());
            if (lv instanceof Number n) limit = n.longValue();
        }
        if (s.limitOffset() != null) {
            Object ov = evalConstant(s.limitOffset());
            if (ov instanceof Number n) offset = n.longValue();
        }
        if (limit >= 0 || offset > 0) {
            current = new Limit(current, limit, offset);
        }

        return current;
    }

    // ── FROM item dispatch ────────────────────────────────────────────────────

    private Operator planFromItem(FromItem fi, Map<String, SelectStmt> cteMap)
            throws SQLException {
        if (fi instanceof RangeVar rv) {
            return planRangeVar(rv, cteMap);
        }
        if (fi instanceof JoinExpr je) {
            return planJoin(je, cteMap);
        }
        if (fi instanceof RangeSubselect rs) {
            Operator sub = planSelect(rs.subquery(), cteMap);
            // Wrap with alias renaming if col aliases provided
            if (!rs.colAliases().isEmpty()) {
                sub = realiasProjection(sub, rs.alias(), rs.colAliases());
            } else {
                sub = realiasAll(sub, rs.alias());
            }
            return sub;
        }
        if (fi instanceof RangeFunction rf) {
            return planRangeFunction(rf);
        }
        throw PgErrorException.error("0A000",
                "unsupported FROM item: " + fi.getClass().getSimpleName()).build();
    }

    private Operator planRangeVar(RangeVar rv, Map<String, SelectStmt> cteMap)
            throws SQLException {
        String name = rv.relName().toLowerCase();
        String alias = rv.alias() != null ? rv.alias().toLowerCase() : name;

        // Check materialized recursive CTEs first
        if (materializedCtes.containsKey(name)) {
            List<Object[]> rows = materializedCtes.get(name);
            // Derive column names from the first row's width (names stored as _col_0, …)
            List<String> colNames = materializedCtes.containsKey(name + ".__cols__")
                    ? List.of()  // shouldn't happen
                    : colNamesForMaterialized(name);
            Operator scan = new ValuesScan(colNames, rows);
            return realiasAll(scan, alias);
        }

        // Check non-recursive CTEs
        if (cteMap.containsKey(name)) {
            Operator sub = planSelect(cteMap.get(name), cteMap);
            return realiasAll(sub, alias);
        }

        // Check virtual tables (pg_catalog / information_schema)
        String schemaHint = rv.schemaName() != null ? rv.schemaName().toLowerCase() : null;
        VirtualTable vt = resolveVirtualTable(schemaHint, name);
        if (vt != null) {
            return new VirtualTableScan(alias, vt, catalog);
        }

        // Check views
        ViewDef view = resolveView(schemaHint, name);
        if (view != null) {
            if (view.parsedDef() == null)
                throw PgErrorException.error("0A000", "view \"" + name + "\" has no parsed definition").build();
            Operator sub = planSelect(view.parsedDef(), cteMap);
            return realiasAll(sub, alias);
        }

        // Regular heap table
        TableDef def;
        if (schemaHint != null) {
            Schema s = catalog.getSchemaOrNull(schemaHint);
            if (s == null || s.table(name) == null)
                throw PgErrorException.error("42P01", "relation \"" + rv.qualifiedName() + "\" does not exist").build();
            def = s.table(name);
        } else {
            def = catalog.resolveTable(name, searchPath);
        }
        // Acquire ACCESS_SHARE table-level lock (held until COMMIT/ROLLBACK).
        if (currentTx != null) {
            storage.tableLocks().acquire(def.oid(), currentTx.txid(),
                    org.pgjava.storage.LockMode.ACCESS_SHARE, lockTimeoutMs);
        }
        return new SeqScan(alias, def, storage.table(def.oid()), txSnapshot);
    }

    private Operator planRangeFunction(RangeFunction rf) throws SQLException {
        if (rf.function() == null)
            throw PgErrorException.error("0A000", "null function in FROM clause").build();

        // Resolve function name (last element of qualified name list)
        var nameParts = rf.function().funcname();
        String fnName = nameParts.get(nameParts.size() - 1).toLowerCase();
        int argCount  = rf.function().args().size();

        // Look up SRF in session-local registry first, then database registry
        var srf = eval.findSrf(fnName, argCount);
        if (srf == null)
            throw PgErrorException.error("42883",
                    "function " + fnName + "() does not exist")
                    .hint("Only set-returning functions are supported in the FROM clause.").build();

        // Determine table alias and column names
        String tableAlias = rf.alias() != null ? rf.alias().toLowerCase() : fnName;
        List<String> colNames = rf.colAliases().isEmpty()
                ? srf.outColumnNames()
                : rf.colAliases();

        OutputSchema schema = OutputSchema.ofNamesWithAlias(tableAlias, colNames);
        return new FunctionScanOperator(srf, rf.function().args(), eval, schema);
    }

    private Operator planJoin(JoinExpr je, Map<String, SelectStmt> cteMap)
            throws SQLException {
        Operator left = planFromItem(je.larg(), cteMap);

        // JOIN LATERAL (subquery) — right side is re-executed per left row
        if (je.rarg() instanceof RangeSubselect rs && rs.lateral()) {
            OutputSchema rightSchema = lateralSchema(rs.subquery(), rs.alias(), rs.colAliases());
            return new LateralJoin(left, rightSchema, rs.subquery(),
                    rs.alias(), rs.colAliases(), this::executeLateralSubquery,
                    je.quals(), eval);
        }

        Operator right = planFromItem(je.rarg(), cteMap);

        Expr condition = je.quals();

        // USING → synthesize equality condition
        if (je.usingCols() != null && !je.usingCols().isEmpty()) {
            condition = buildUsingCondition(je.usingCols(), left.schema(), right.schema());
        }

        return new NestedLoopJoin(left, right, condition, je.joinType(), eval);
    }

    // ── Recursive CTE helpers ─────────────────────────────────────────────────

    /**
     * Materialize a {@code WITH RECURSIVE cte AS (seed UNION ALL recursive)} CTE
     * by iterating until the working set is empty.
     *
     * <p>The result is stored in {@link #materializedCtes} under {@code cteName}.
     * Column names (derived from the seed query's output schema) are stored under
     * the key {@code cteName + "\u0000cols"} as a singleton List<Object[]> whose
     * first element is a String[].
     *
     * <p>If the seed result is empty or the body is not a UNION, the CTE is
     * treated as non-recursive and stored via {@link #planSelect}.
     */
    private void materializeRecursiveCte(String cteName, List<String> declaredColNames,
                                          SelectStmt body,
                                          Map<String, SelectStmt> cteMap) throws SQLException {
        // body is guaranteed to have setOp + left + right (checked by caller)
        boolean unionAll = body.setAll();

        // 1. Execute seed (left side) — no reference to cteName yet
        List<Object[]> accumulated = new ArrayList<>();
        List<String>   colNames;
        {
            Operator seedOp = planSelect(body.left(), cteMap);
            seedOp.open();
            try {
                // Prefer declared column aliases (e.g. cnt(n)) over inferred schema names
                if (declaredColNames != null && !declaredColNames.isEmpty()) {
                    colNames = new ArrayList<>(declaredColNames);
                } else {
                    colNames = new ArrayList<>(seedOp.schema().width());
                    for (int i = 0; i < seedOp.schema().width(); i++) colNames.add(seedOp.schema().name(i));
                }
                Row r;
                while ((r = seedOp.next()) != null) accumulated.add(r.values().clone());
            } finally {
                seedOp.close();
            }
        }

        // 2. Store column names for later schema reconstruction
        Object[] colArr = colNames.toArray(new Object[0]);
        List<Object[]> colsMeta = new ArrayList<>();
        colsMeta.add(colArr);
        materializedCtes.put(cteName + "\u0000cols", colsMeta);

        // 3. Iterate recursive part
        List<Object[]> working = new ArrayList<>(accumulated);
        Set<String> seen = unionAll ? null : rowSet(accumulated);

        int maxRecursion = 10_000;
        for (int guard = 0; !working.isEmpty(); guard++) {
            if (guard >= maxRecursion) {
                throw new java.sql.SQLException(
                        "recursive query exceeded maximum number of iterations (" + maxRecursion + ")",
                        "54001");
            }
            // Expose current working set as the CTE (override for this iteration)
            materializedCtes.put(cteName, working);

            Operator recOp = planSelect(body.right(), cteMap);
            recOp.open();
            List<Object[]> next = new ArrayList<>();
            try {
                Row r;
                while ((r = recOp.next()) != null) {
                    Object[] vals = r.values().clone();
                    String key = rowKey(vals);
                    if (unionAll || seen.add(key)) {
                        next.add(vals);
                        accumulated.add(vals);
                    }
                }
            } finally {
                recOp.close();
            }
            working = next;
        }

        // 4. Final materialized result
        materializedCtes.put(cteName, accumulated);
    }

    /** Build column name list for a materialized CTE (stored at cteName+"\u0000cols"). */
    private List<String> colNamesForMaterialized(String cteName) {
        List<Object[]> meta = materializedCtes.get(cteName + "\u0000cols");
        if (meta == null || meta.isEmpty()) return List.of();
        Object[] arr = meta.get(0);
        List<String> names = new ArrayList<>(arr.length);
        for (Object o : arr) names.add(o == null ? "?column?" : o.toString());
        return names;
    }

    private static Set<String> rowSet(List<Object[]> rows) {
        Set<String> s = new HashSet<>();
        for (Object[] row : rows) s.add(rowKey(row));
        return s;
    }

    private static String rowKey(Object[] row) {
        return Arrays.toString(row);
    }

    // ── Aggregate helpers ─────────────────────────────────────────────────────

    // ── Window function helpers ───────────────────────────────────────────────

    /**
     * Walk the target list and collect every FunctionCall that has an OVER clause
     * (inline or referencing a named window).
     *
     * <p>Returns a map from the <em>original</em> fc.toString() key to outCol name
     * (for rewriting targets), alongside the WinFunc list (with resolved WindowDefs)
     * for the WindowAgg operator.
     */
    private record WinFuncCollection(
            List<WindowAgg.WinFunc> funcs,    // resolved, for WindowAgg
            Map<String, String>    keyToOut   // original fc.toString() → _win_N
    ) {}

    private WinFuncCollection collectWindowFuncs(
            List<TargetEntry> targets, List<WindowDef> namedWindows) {
        List<WindowAgg.WinFunc> funcs = new ArrayList<>();
        Map<String, String> keyToOut = new LinkedHashMap<>();
        for (TargetEntry te : targets) {
            collectWinFuncs(te.val(), namedWindows, funcs, keyToOut);
        }
        return new WinFuncCollection(funcs, keyToOut);
    }

    private void collectWinFuncs(Expr e, List<WindowDef> namedWindows,
                                  List<WindowAgg.WinFunc> funcs,
                                  Map<String, String> keyToOut) {
        if (e == null) return;
        if (e instanceof FunctionCall fc && fc.over() != null) {
            String originalKey = fc.toString();
            if (!keyToOut.containsKey(originalKey)) {
                String outCol = "_win_" + funcs.size();
                keyToOut.put(originalKey, outCol);
                // Resolve named window reference (OVER w → full WindowDef) before storing
                FunctionCall resolved = resolveNamedWindow(fc, namedWindows);
                funcs.add(new WindowAgg.WinFunc(resolved, outCol));
            }
            return; // do not recurse into window function args for more window funcs
        }
        // Recurse into compound expressions
        switch (e) {
            case BinaryOp bo   -> { collectWinFuncs(bo.left(), namedWindows, funcs, keyToOut);
                                    collectWinFuncs(bo.right(), namedWindows, funcs, keyToOut); }
            case UnaryOp uo    -> collectWinFuncs(uo.operand(), namedWindows, funcs, keyToOut);
            case CastExpr ce   -> collectWinFuncs(ce.arg(), namedWindows, funcs, keyToOut);
            case FunctionCall fc -> { for (Expr a : fc.args()) collectWinFuncs(a, namedWindows, funcs, keyToOut); }
            case CaseExpr ca   -> {
                for (CaseWhen cw : ca.whenClauses()) {
                    collectWinFuncs(cw.condition(), namedWindows, funcs, keyToOut);
                    collectWinFuncs(cw.result(), namedWindows, funcs, keyToOut);
                }
                collectWinFuncs(ca.defResult(), namedWindows, funcs, keyToOut);
            }
            default -> {}
        }
    }

    /**
     * If fc.over() is a bare named-window reference (name set, no partition/order),
     * replace over with the matching named WindowDef from the WINDOW clause.
     *
     * <p>PostgreSQL encodes {@code OVER w} as a WindowDef where {@code name="w"} and
     * partition/order are empty.  The corresponding WINDOW definition also has
     * {@code name="w"} with the actual partition/order filled in.
     */
    private FunctionCall resolveNamedWindow(FunctionCall fc, List<WindowDef> namedWindows) {
        WindowDef over = fc.over();
        if (over == null || namedWindows == null || namedWindows.isEmpty()) return fc;
        // A bare "OVER w" reference has a name but no partition/order of its own
        String ref = over.name();
        if (ref == null || ref.isEmpty()) return fc;
        boolean hasInlineSpec = (over.partitionClause() != null && !over.partitionClause().isEmpty())
                             || (over.orderClause()     != null && !over.orderClause().isEmpty())
                             || over.frame() != null;
        if (hasInlineSpec) return fc; // fully inline OVER clause — nothing to resolve
        for (WindowDef wd : namedWindows) {
            if (ref.equalsIgnoreCase(wd.name())) {
                WindowDef merged = new WindowDef(null, null,
                        wd.partitionClause(), wd.orderClause(), wd.frame());
                return new FunctionCall(fc.funcname(), fc.args(), fc.aggDistinct(),
                        fc.aggOrder(), fc.aggFilter(), fc.aggStar(), fc.withinGroup(), merged);
            }
        }
        return fc; // named window not found — leave as-is
    }

    /**
     * Rewrite each TargetEntry's expression, replacing window FunctionCall nodes
     * (matched by original toString key) with ColumnRef("_win_N").
     */
    private List<TargetEntry> rewriteTargetsForWindow(
            List<TargetEntry> targets, Map<String, String> keyToOut) {
        List<TargetEntry> result = new ArrayList<>(targets.size());
        for (TargetEntry te : targets) {
            Expr rewritten = rewriteWinRefs(te.val(), keyToOut);
            result.add(new TargetEntry(rewritten, te.name()));
        }
        return result;
    }

    private Expr rewriteWinRefs(Expr e, Map<String, String> keyToOut) {
        if (e == null) return null;
        if (e instanceof FunctionCall fc && fc.over() != null) {
            String outCol = keyToOut.get(fc.toString());
            if (outCol != null) return ColumnRef.of(outCol);
        }
        return switch (e) {
            case BinaryOp bo -> new BinaryOp(bo.op(),
                    rewriteWinRefs(bo.left(), keyToOut), rewriteWinRefs(bo.right(), keyToOut));
            case UnaryOp uo -> new UnaryOp(uo.op(), rewriteWinRefs(uo.operand(), keyToOut));
            case CastExpr ce -> new CastExpr(rewriteWinRefs(ce.arg(), keyToOut), ce.targetType());
            case FunctionCall fc -> {
                List<Expr> newArgs = new ArrayList<>();
                for (Expr a : fc.args()) newArgs.add(rewriteWinRefs(a, keyToOut));
                yield new FunctionCall(fc.funcname(), newArgs, fc.aggDistinct(),
                        fc.aggOrder(), fc.aggFilter(), fc.aggStar(), fc.withinGroup(), fc.over());
            }
            default -> e;
        };
    }

    private List<FunctionCall> collectAggregates(List<TargetEntry> targets, Expr having) {
        List<FunctionCall> aggs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TargetEntry te : targets) collectAggs(te.val(), aggs, seen);
        if (having != null) collectAggs(having, aggs, seen);
        return aggs;
    }

    private void collectAggs(Expr e, List<FunctionCall> out, Set<String> seen) {
        if (e == null) return;
        if (e instanceof FunctionCall fc && isAggFunc(fc.funcname()) && fc.over() == null) {
            String key = fc.toString();
            if (seen.add(key)) out.add(fc);
            return;
        }
        // Window functions (over != null) are handled by WindowAgg, not HashAgg — skip them
        if (e instanceof FunctionCall fc && fc.over() != null) return;
        // Recurse into sub-expressions
        switch (e) {
            case BinaryOp bo   -> { collectAggs(bo.left(), out, seen); collectAggs(bo.right(), out, seen); }
            case UnaryOp uo    -> collectAggs(uo.operand(), out, seen);
            case CastExpr ce   -> collectAggs(ce.arg(), out, seen);
            case FunctionCall fc -> { for (Expr a : fc.args()) collectAggs(a, out, seen); }
            case CaseExpr ca   -> {
                for (CaseWhen cw : ca.whenClauses()) {
                    collectAggs(cw.condition(), out, seen);
                    collectAggs(cw.result(), out, seen);
                }
                collectAggs(ca.defResult(), out, seen);
            }
            default -> {}
        }
    }

    private static final Set<String> AGG_NAMES = Set.of(
            "count", "sum", "avg", "min", "max",
            "bool_and", "bool_or", "every", "string_agg", "array_agg");

    private boolean isAggFunc(List<String> funcname) {
        if (funcname.isEmpty()) return false;
        String last = funcname.getLast().toLowerCase();
        return AGG_NAMES.contains(last);
    }

    // ── Projection builder ────────────────────────────────────────────────────

    private Operator buildProjection(Operator source, List<TargetEntry> targets,
                                     List<FunctionCall> aggCalls,
                                     List<Expr> groupKeys) throws SQLException {
        OutputSchema srcSchema = source.schema();
        List<Project.Projection> projections = new ArrayList<>();

        for (TargetEntry te : targets) {
            if (te.val() instanceof ColumnRef cr && cr.isStar()) {
                // Expand star: add all columns from source schema
                if (cr.fields().size() == 1) {
                    // plain *
                    for (int i = 0; i < srcSchema.width(); i++) {
                        projections.add(new Project.Projection(
                                ColumnRef.of(srcSchema.alias(i), srcSchema.name(i)),
                                srcSchema.name(i)));
                    }
                } else {
                    // alias.*
                    String targetAlias = cr.fields().getFirst().toLowerCase();
                    for (int i = 0; i < srcSchema.width(); i++) {
                        if (srcSchema.alias(i).equals(targetAlias)) {
                            projections.add(new Project.Projection(
                                    ColumnRef.of(srcSchema.alias(i), srcSchema.name(i)),
                                    srcSchema.name(i)));
                        }
                    }
                }
            } else {
                // Rewrite agg calls → _agg_N and group-key refs → _key_N
                Expr expr = rewriteAggRefs(rewriteGroupKeyRefs(te.val(), groupKeys), aggCalls);
                String name = te.name() != null ? te.name()
                        : deriveColumnName(te.val());
                projections.add(new Project.Projection(expr, name));
            }
        }

        return new Project(source, projections, eval);
    }

    /**
     * Rewrite expressions that match a GROUP BY key to the corresponding _key_N placeholder.
     * This is needed because HashAgg renames grouping columns to _key_0, _key_1, etc.
     */
    private Expr rewriteGroupKeyRefs(Expr e, List<Expr> groupKeys) {
        if (e == null || groupKeys.isEmpty()) return e;
        for (int i = 0; i < groupKeys.size(); i++) {
            if (exprEquals(e, groupKeys.get(i))) {
                return ColumnRef.of("_key_" + i);
            }
        }
        // Recurse into compound expressions
        return switch (e) {
            case BinaryOp bo -> new BinaryOp(bo.op(),
                    rewriteGroupKeyRefs(bo.left(), groupKeys),
                    rewriteGroupKeyRefs(bo.right(), groupKeys));
            case UnaryOp uo -> new UnaryOp(uo.op(),
                    rewriteGroupKeyRefs(uo.operand(), groupKeys));
            case CastExpr ce -> new CastExpr(
                    rewriteGroupKeyRefs(ce.arg(), groupKeys), ce.targetType());
            case FunctionCall fc -> {
                List<Expr> newArgs = new ArrayList<>();
                for (Expr a : fc.args()) newArgs.add(rewriteGroupKeyRefs(a, groupKeys));
                yield new FunctionCall(fc.funcname(), newArgs, fc.aggDistinct(),
                        fc.aggOrder(), fc.aggFilter(), fc.aggStar(), fc.withinGroup(), fc.over());
            }
            default -> e;
        };
    }

    /**
     * Resolve ORDER BY expression for alias and positional references.
     *
     * <ul>
     *   <li>Integer literal N → Nth target list expression (1-based)</li>
     *   <li>Simple ColumnRef whose name matches a SELECT alias → that target's expression</li>
     *   <li>Everything else → unchanged (evaluated against source schema as usual)</li>
     * </ul>
     */
    private Expr resolveOrderByExpr(Expr e, List<TargetEntry> targets) {
        // Positional: ORDER BY 1
        if (e instanceof IntegerLiteral il) {
            int pos = (int) il.value() - 1;
            if (pos >= 0 && pos < targets.size()) return targets.get(pos).val();
        }
        // Alias or inferred-name: ORDER BY alias_name / output_col_name
        // PostgreSQL resolves ORDER BY against the SELECT output list first.
        // An explicit AS alias takes priority; if absent, the inferred column name
        // (e.g. "c" for the expression c::text) is used for matching.
        if (e instanceof ColumnRef cr && cr.fields().size() == 1) {
            String name = cr.fields().get(0).toLowerCase();
            for (TargetEntry te : targets) {
                if (te.name() != null && name.equals(te.name().toLowerCase())) {
                    return te.val();
                }
            }
            // Fall back to inferred column name (e.g. CastExpr inherits its arg's name).
            for (TargetEntry te : targets) {
                if (te.name() == null && name.equals(deriveColumnName(te.val()).toLowerCase())) {
                    return te.val();
                }
            }
        }
        return e;
    }

    /** Structural equality check for expression rewriting. */
    private boolean exprEquals(Expr a, Expr b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.toString().equals(b.toString());
    }

    /** Rewrite FunctionCall nodes that are aggregates to ColumnRef(_agg_N). */
    private Expr rewriteAggRefs(Expr e, List<FunctionCall> aggCalls) {
        if (e == null) return null;
        if (e instanceof FunctionCall fc && isAggFunc(fc.funcname())) {
            int idx = indexOfAgg(fc, aggCalls);
            if (idx >= 0) return ColumnRef.of("_agg_" + idx);
        }
        // Recurse
        return switch (e) {
            case BinaryOp bo -> new BinaryOp(bo.op(),
                    rewriteAggRefs(bo.left(), aggCalls),
                    rewriteAggRefs(bo.right(), aggCalls));
            case UnaryOp uo -> new UnaryOp(uo.op(), rewriteAggRefs(uo.operand(), aggCalls));
            case CastExpr ce -> new CastExpr(rewriteAggRefs(ce.arg(), aggCalls), ce.targetType());
            case FunctionCall fc -> {
                List<Expr> newArgs = new ArrayList<>();
                for (Expr a : fc.args()) newArgs.add(rewriteAggRefs(a, aggCalls));
                yield new FunctionCall(fc.funcname(), newArgs, fc.aggDistinct(),
                        fc.aggOrder(), fc.aggFilter(), fc.aggStar(), fc.withinGroup(), fc.over());
            }
            case CaseExpr ca -> {
                List<CaseWhen> newWhen = new ArrayList<>();
                for (CaseWhen cw : ca.whenClauses())
                    newWhen.add(new CaseWhen(rewriteAggRefs(cw.condition(), aggCalls),
                                             rewriteAggRefs(cw.result(), aggCalls)));
                yield new CaseExpr(rewriteAggRefs(ca.arg(), aggCalls), newWhen,
                                   rewriteAggRefs(ca.defResult(), aggCalls));
            }
            default -> e;
        };
    }

    private int indexOfAgg(FunctionCall fc, List<FunctionCall> aggCalls) {
        String target = fc.toString();
        for (int i = 0; i < aggCalls.size(); i++) {
            if (aggCalls.get(i).toString().equals(target)) return i;
        }
        return -1;
    }

    private String deriveColumnName(Expr e) {
        if (e instanceof ColumnRef cr) {
            return cr.fields().getLast();
        }
        if (e instanceof FunctionCall fc) {
            return fc.funcname().getLast().toLowerCase();
        }
        if (e instanceof CastExpr ce) {
            return deriveColumnName(ce.arg());
        }
        return "?column?";
    }

    // ── Alias helpers ─────────────────────────────────────────────────────────

    /** Rebuild the operator's schema so all columns carry the given alias. */
    private Operator realiasAll(Operator op, String alias) {
        OutputSchema src = op.schema();
        List<Project.Projection> projs = new ArrayList<>(src.width());
        for (int i = 0; i < src.width(); i++) {
            ColumnRef ref = src.alias(i).isEmpty()
                    ? ColumnRef.of(src.name(i))
                    : ColumnRef.of(src.alias(i), src.name(i));
            projs.add(new Project.Projection(ref, src.name(i)));
        }
        return new Project(op, projs, eval, alias);
    }

    /** Rebuild the operator's schema with explicit column name aliases. */
    private Operator realiasProjection(Operator op, String tableAlias,
                                       List<String> colAliases) {
        OutputSchema src = op.schema();
        List<Project.Projection> projs = new ArrayList<>();
        int limit = Math.min(src.width(), colAliases.size());
        for (int i = 0; i < limit; i++) {
            ColumnRef ref = src.alias(i).isEmpty()
                    ? ColumnRef.of(src.name(i))
                    : ColumnRef.of(src.alias(i), src.name(i));
            projs.add(new Project.Projection(ref, colAliases.get(i).toLowerCase()));
        }
        return new Project(op, projs, eval, tableAlias);
    }

    // ── VALUES ────────────────────────────────────────────────────────────────

    private Operator planValues(SelectStmt s) throws SQLException {
        List<List<Expr>> lists = s.valuesLists();
        int width = lists.get(0).size();
        List<String> colNames = new ArrayList<>(width);
        for (int i = 1; i <= width; i++) colNames.add("column" + i);

        List<Object[]> rows = new ArrayList<>(lists.size());
        for (List<Expr> row : lists) {
            Object[] vals = new Object[row.size()];
            for (int i = 0; i < row.size(); i++) {
                vals[i] = evalConstant(row.get(i));
            }
            rows.add(vals);
        }
        return new ValuesScan(colNames, rows);
    }

    // ── USING condition builder ───────────────────────────────────────────────

    private Expr buildUsingCondition(List<String> cols,
                                     OutputSchema left, OutputSchema right) {
        Expr condition = null;
        for (String col : cols) {
            // Find which alias the column comes from in left and right
            String leftAlias  = findAlias(col, left);
            String rightAlias = findAlias(col, right);
            ColumnRef lRef = leftAlias != null
                    ? ColumnRef.of(leftAlias, col) : ColumnRef.of(col);
            ColumnRef rRef = rightAlias != null
                    ? ColumnRef.of(rightAlias, col) : ColumnRef.of(col);
            Expr eq = new BinaryOp("=", lRef, rRef);
            condition = condition == null ? eq : new BinaryOp("AND", condition, eq);
        }
        return condition;
    }

    private String findAlias(String col, OutputSchema schema) {
        for (int i = 0; i < schema.width(); i++) {
            if (schema.name(i).equalsIgnoreCase(col)) return schema.alias(i);
        }
        return null;
    }

    // ── Virtual / view lookup helpers ─────────────────────────────────────────

    private VirtualTable resolveVirtualTable(String schemaHint, String name) {
        if (schemaHint != null) {
            VirtualTable vt = catalog.getVirtualTable(schemaHint, name);
            if (vt != null) return vt;
        }
        // Check pg_catalog and information_schema explicitly
        for (String sysSchema : List.of("pg_catalog", "information_schema")) {
            VirtualTable vt = catalog.getVirtualTable(sysSchema, name);
            if (vt != null) return vt;
        }
        return null;
    }

    private ViewDef resolveView(String schemaHint, String name) {
        if (schemaHint != null) {
            Schema s = catalog.getSchemaOrNull(schemaHint);
            return s != null ? s.view(name) : null;
        }
        for (String schName : searchPath) {
            Schema s = catalog.getSchemaOrNull(schName);
            if (s != null) {
                ViewDef v = s.view(name);
                if (v != null) return v;
            }
        }
        return null;
    }

    /**
     * Resolve a view by qualified name (e.g. "schema.view" or just "view").
     */
    private ViewDef resolveViewByQualifiedName(String qualifiedName) {
        String lc = qualifiedName.toLowerCase();
        int dot = lc.indexOf('.');
        if (dot > 0) {
            return resolveView(lc.substring(0, dot), lc.substring(dot + 1));
        }
        return resolveView(null, lc);
    }

    // ── Star-only target list check ───────────────────────────────────────────

    private static boolean isStarOnly(List<TargetEntry> targets) {
        if (targets.size() != 1) return false;
        Expr v = targets.get(0).val();
        return v instanceof ColumnRef cr && cr.isStar();
    }

    // ── Constant eval helper ──────────────────────────────────────────────────

    private Object evalConstant(Expr e) throws SQLException {
        return eval.eval(e, EvalContext.empty());
    }

    // =========================================================================
    // INSERT
    // =========================================================================

    /**
     * Plan and execute an INSERT statement.
     *
     * @return DmlResult with row count (and RETURNING rows if applicable)
     */
    public DmlResult planInsert(InsertStmt s, Transaction tx) throws SQLException {
        // Handle WITH clause on INSERT
        if (s.withClause() != null) {
            for (CommonTableExpr cte : s.withClause().ctes()) {
                if (cte.query() instanceof SelectStmt sq) {
                    activeCteMap = new LinkedHashMap<>(activeCteMap);
                    activeCteMap.put(cte.ctename().toLowerCase(), sq);
                }
            }
        }

        // Try table first; if not found, check for a view with INSTEAD OF triggers
        TableDef def;
        try {
            def = catalog.resolveTable(s.relation().qualifiedName(), searchPath);
        } catch (SQLException e) {
            ViewDef view = resolveViewByQualifiedName(s.relation().qualifiedName());
            if (view != null) {
                return planInsertOnView(s, tx, view);
            }
            throw e;
        }

        // Build source operator
        Operator source;
        if (s.defaultValues()) {
            // INSERT ... DEFAULT VALUES — one row of all nulls (DEFAULTs applied by InsertOp)
            int ncols = def.columnCount();
            source = new ValuesScan(
                    def.columns().stream().map(ColumnDef::name).toList(),
                    List.<Object[]>of(new Object[ncols]));
        } else if (s.source() != null && !s.source().valuesLists().isEmpty()) {
            source = planValues(s.source());
        } else if (s.source() != null) {
            source = planSelect(s.source(), activeCteMap);
        } else {
            source = new ValuesScan(List.of(), List.<Object[]>of(new Object[0]));
        }

        // Build column mapping: source position → target attnum-1
        int[] colMapping;
        if (s.cols().isEmpty()) {
            colMapping = new int[0]; // positional
        } else {
            colMapping = new int[s.cols().size()];
            for (int i = 0; i < s.cols().size(); i++) {
                ColumnDef col = def.column(s.cols().get(i));
                if (col == null)
                    throw PgErrorException.error("42703",
                            "column \"" + s.cols().get(i) + "\" of relation \""
                                    + def.name() + "\" does not exist").build();
                colMapping[i] = col.attnum() - 1;
            }
        }

        InsertOp op = new InsertOp(source, def, storage, txMgr, tx, colMapping, eval,
                s.returning(), s.onConflict());
        op.setSnapshot(txSnapshot);
        op.setTableResolver(name -> catalog.resolveTable(name, searchPath));
        if (triggerDatabase != null) op.setTriggerContext(triggerDatabase, searchPath);
        op.open();
        int count = op.execute();
        if (!s.returning().isEmpty()) {
            return DmlResult.ofReturning(count,
                    ReturningEval.colMetas(s.returning(), def),
                    op.returningRows());
        }
        return DmlResult.ofCount(count);
    }

    // =========================================================================
    // UPDATE
    // =========================================================================

    /**
     * Plan and execute an UPDATE statement.
     *
     * @return DmlResult with row count (and RETURNING rows if applicable)
     */
    public DmlResult planUpdate(UpdateStmt s, Transaction tx) throws SQLException {
        // Handle WITH clause on UPDATE
        if (s.withClause() != null) {
            for (CommonTableExpr cte : s.withClause().ctes()) {
                if (cte.query() instanceof SelectStmt sq) {
                    activeCteMap = new LinkedHashMap<>(activeCteMap);
                    activeCteMap.put(cte.ctename().toLowerCase(), sq);
                }
            }
        }

        // Try table first; if not found, check for a view with INSTEAD OF triggers
        TableDef def;
        try {
            def = catalog.resolveTable(s.relation().qualifiedName(), searchPath);
        } catch (SQLException e) {
            ViewDef view = resolveViewByQualifiedName(s.relation().qualifiedName());
            if (view != null) {
                return planUpdateOnView(s, tx, view);
            }
            throw e;
        }
        String alias = s.relation().alias() != null
                ? s.relation().alias().toLowerCase()
                : def.name().toLowerCase();

        Operator scan = new SeqScan(alias, def, storage.table(def.oid()), txSnapshot);

        // FROM clause joins (UPDATE ... FROM ...)
        Operator from = scan;
        if (!s.fromClause().isEmpty()) {
            for (FromItem fi : s.fromClause()) {
                Operator right = planFromItem(fi, activeCteMap);
                from = new NestedLoopJoin(from, right, null, JoinType.CROSS, eval);
            }
        }

        Operator source = from;
        if (s.whereClause() != null) {
            source = new Filter(source, s.whereClause(), eval);
        }

        List<String> colNames = new ArrayList<>();
        List<Expr>   colExprs = new ArrayList<>();
        for (AssignTarget at : s.targets()) {
            if (at.names().size() == 1 && at.val() != null) {
                colNames.add(at.names().get(0));
                colExprs.add(at.val());
            }
            // subselect assignments not yet supported
        }

        UpdateOp op = new UpdateOp(source, def, storage, txMgr, tx,
                colNames, colExprs, eval, s.returning());
        op.setTableResolver(name -> catalog.resolveTable(name, searchPath));
        if (triggerDatabase != null) op.setTriggerContext(triggerDatabase, searchPath);
        op.setAllTablesSupplier(() -> {
            List<org.pgjava.catalog.TableDef> all = new ArrayList<>();
            for (org.pgjava.catalog.Schema s2 : catalog.allSchemas().values())
                all.addAll(s2.tables().values());
            return all;
        });
        op.open();
        int count = op.execute();
        if (!s.returning().isEmpty()) {
            return DmlResult.ofReturning(count,
                    ReturningEval.colMetas(s.returning(), def),
                    op.returningRows());
        }
        return DmlResult.ofCount(count);
    }

    // =========================================================================
    // DELETE
    // =========================================================================

    /**
     * Plan and execute a DELETE statement.
     *
     * @return DmlResult with row count (and RETURNING rows if applicable)
     */
    public DmlResult planDelete(DeleteStmt s, Transaction tx) throws SQLException {
        // Handle WITH clause on DELETE
        if (s.withClause() != null) {
            for (CommonTableExpr cte : s.withClause().ctes()) {
                if (cte.query() instanceof SelectStmt sq) {
                    activeCteMap = new LinkedHashMap<>(activeCteMap);
                    activeCteMap.put(cte.ctename().toLowerCase(), sq);
                }
            }
        }

        // Try table first; if not found, check for a view with INSTEAD OF triggers
        TableDef def;
        try {
            def = catalog.resolveTable(s.relation().qualifiedName(), searchPath);
        } catch (SQLException e) {
            ViewDef view = resolveViewByQualifiedName(s.relation().qualifiedName());
            if (view != null) {
                return planDeleteOnView(s, tx, view);
            }
            throw e;
        }
        String alias = s.relation().alias() != null
                ? s.relation().alias().toLowerCase()
                : def.name().toLowerCase();

        Operator scan = new SeqScan(alias, def, storage.table(def.oid()), txSnapshot);

        // USING clause joins
        Operator source = scan;
        if (!s.usingClause().isEmpty()) {
            for (FromItem fi : s.usingClause()) {
                Operator right = planFromItem(fi, activeCteMap);
                source = new NestedLoopJoin(source, right, null, JoinType.CROSS, eval);
            }
        }

        if (s.whereClause() != null) {
            source = new Filter(source, s.whereClause(), eval);
        }

        DeleteOp op = new DeleteOp(source, def, storage, txMgr, tx, eval, s.returning());
        if (triggerDatabase != null) op.setTriggerContext(triggerDatabase, searchPath);
        op.setAllTablesSupplier(() -> {
            List<org.pgjava.catalog.TableDef> all = new ArrayList<>();
            for (org.pgjava.catalog.Schema s2 : catalog.allSchemas().values())
                all.addAll(s2.tables().values());
            return all;
        });
        op.open();
        int count = op.execute();
        if (!s.returning().isEmpty()) {
            return DmlResult.ofReturning(count,
                    ReturningEval.colMetas(s.returning(), def),
                    op.returningRows());
        }
        return DmlResult.ofCount(count);
    }

    // =========================================================================
    // INSTEAD OF trigger support for DML on views
    // =========================================================================

    /**
     * Handle INSERT on a view by firing INSTEAD OF INSERT triggers.
     */
    private DmlResult planInsertOnView(InsertStmt s, Transaction tx, ViewDef view)
            throws SQLException {
        List<TriggerDef> ioTriggers = view.triggers().stream()
                .filter(t -> t.isInsteadOf() && t.firesOn(TriggerDef.INSERT))
                .toList();
        if (ioTriggers.isEmpty()) {
            throw PgErrorException.error("42809",
                    "cannot insert into view \"" + view.name()
                    + "\" without an INSTEAD OF INSERT trigger").build();
        }
        if (triggerDatabase == null) {
            throw PgErrorException.error("XX000",
                    "trigger database context not available").build();
        }

        // Build source operator to get the rows to insert
        Operator source;
        if (s.defaultValues()) {
            source = new ValuesScan(List.of(), List.<Object[]>of(new Object[0]));
        } else if (s.source() != null && !s.source().valuesLists().isEmpty()) {
            source = planValues(s.source());
        } else if (s.source() != null) {
            source = planSelect(s.source(), activeCteMap);
        } else {
            source = new ValuesScan(List.of(), List.<Object[]>of(new Object[0]));
        }

        source.open();
        int rowCount = 0;
        Row srcRow;
        while ((srcRow = source.next()) != null) {
            Object[] newVals = srcRow.values();
            boolean fired = TriggerExecutor.fireInsteadOf(
                    view.triggers(), TriggerDef.INSERT,
                    newVals, null,
                    view.name(), view.schemaName(), null,
                    triggerDatabase, searchPath);
            if (fired) rowCount++;
        }
        source.close();
        return DmlResult.ofCount(rowCount);
    }

    /**
     * Handle UPDATE on a view by firing INSTEAD OF UPDATE triggers.
     */
    private DmlResult planUpdateOnView(UpdateStmt s, Transaction tx, ViewDef view)
            throws SQLException {
        List<TriggerDef> ioTriggers = view.triggers().stream()
                .filter(t -> t.isInsteadOf() && t.firesOn(TriggerDef.UPDATE))
                .toList();
        if (ioTriggers.isEmpty()) {
            throw PgErrorException.error("42809",
                    "cannot update view \"" + view.name()
                    + "\" without an INSTEAD OF UPDATE trigger").build();
        }
        if (triggerDatabase == null) {
            throw PgErrorException.error("XX000",
                    "trigger database context not available").build();
        }

        // Expand the view to get its rows, then fire INSTEAD OF for each
        if (view.parsedDef() == null) {
            throw PgErrorException.error("0A000",
                    "view \"" + view.name() + "\" has no parsed definition").build();
        }
        Operator scan = planSelect(view.parsedDef(), activeCteMap);
        if (s.whereClause() != null) {
            scan = new Filter(scan, s.whereClause(), eval);
        }

        // Materialize rows first
        scan.open();
        OutputSchema srcSchema = scan.schema();
        List<Row> rows = new ArrayList<>();
        Row r;
        while ((r = scan.next()) != null) rows.add(r);
        scan.close();

        int rowCount = 0;
        for (Row row : rows) {
            Object[] oldVals = row.values();
            Object[] newVals = oldVals.clone();

            // Apply SET assignments
            EvalContext ctx = srcSchema.buildContext(row);
            for (int i = 0; i < s.targets().size(); i++) {
                AssignTarget at = s.targets().get(i);
                if (at.names().size() == 1 && at.val() != null) {
                    String colName = at.names().get(0).toLowerCase();
                    // Find column index in the view's output
                    int idx = -1;
                    for (int j = 0; j < srcSchema.width(); j++) {
                        if (colName.equalsIgnoreCase(srcSchema.name(j))) {
                            idx = j;
                            break;
                        }
                    }
                    if (idx >= 0 && idx < newVals.length) {
                        newVals[idx] = CollatedValue.unwrap(eval.eval(at.val(), ctx));
                    }
                }
            }

            boolean fired = TriggerExecutor.fireInsteadOf(
                    view.triggers(), TriggerDef.UPDATE,
                    newVals, oldVals,
                    view.name(), view.schemaName(), null,
                    triggerDatabase, searchPath);
            if (fired) rowCount++;
        }
        return DmlResult.ofCount(rowCount);
    }

    /**
     * Handle DELETE on a view by firing INSTEAD OF DELETE triggers.
     */
    private DmlResult planDeleteOnView(DeleteStmt s, Transaction tx, ViewDef view)
            throws SQLException {
        List<TriggerDef> ioTriggers = view.triggers().stream()
                .filter(t -> t.isInsteadOf() && t.firesOn(TriggerDef.DELETE))
                .toList();
        if (ioTriggers.isEmpty()) {
            throw PgErrorException.error("42809",
                    "cannot delete from view \"" + view.name()
                    + "\" without an INSTEAD OF DELETE trigger").build();
        }
        if (triggerDatabase == null) {
            throw PgErrorException.error("XX000",
                    "trigger database context not available").build();
        }

        // Expand the view to get its rows, then fire INSTEAD OF for each
        if (view.parsedDef() == null) {
            throw PgErrorException.error("0A000",
                    "view \"" + view.name() + "\" has no parsed definition").build();
        }
        Operator scan = planSelect(view.parsedDef(), activeCteMap);
        if (s.whereClause() != null) {
            scan = new Filter(scan, s.whereClause(), eval);
        }

        scan.open();
        int rowCount = 0;
        Row r;
        while ((r = scan.next()) != null) {
            Object[] oldVals = r.values();
            boolean fired = TriggerExecutor.fireInsteadOf(
                    view.triggers(), TriggerDef.DELETE,
                    null, oldVals,
                    view.name(), view.schemaName(), null,
                    triggerDatabase, searchPath);
            if (fired) rowCount++;
        }
        scan.close();
        return DmlResult.ofCount(rowCount);
    }
}
