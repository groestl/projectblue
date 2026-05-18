package org.pgjava.engine;

import org.pgjava.catalog.*;
import org.pgjava.types.PgType;
import org.pgjava.types.PgTypeRegistry;
import org.pgjava.executor.*;
import org.pgjava.sql.ast.*;
import org.pgjava.sql.parser.ParseException;
import org.pgjava.sql.parser.ParserProvider;
import org.pgjava.storage.LockMode;
import org.pgjava.storage.Row;
import org.pgjava.wal.Transaction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * A single connection-scoped session against a {@link Database}.
 *
 * <p>Phase 2 stub: handles session-variable tracking (SET/SHOW), schema control
 * (CREATE/DROP SCHEMA), and transaction lifecycle as no-ops.  All other statements
 * throw {@link UnsupportedOperationException}, which the JDBC layer propagates up
 * to the test harness where it is caught as "not yet implemented".
 *
 * <p>Phase 9 will replace the throw-by-default path with real execution.
 */
public final class Session implements AutoCloseable, NotificationBus.Listener {

    /**
     * Thread-local reference to the currently executing session.
     * Set during {@link #execute(String)} so that PL/pgSQL interpreters
     * can access the session for savepoint operations in EXCEPTION blocks.
     */
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();

    /** Returns the Session executing on the current thread, or {@code null}. */
    public static Session current() { return CURRENT.get(); }

    private final Database    database;
    private final DdlExecutor ddl;
    private final Map<String, String> variables = new HashMap<>();
    private boolean closed = false;

    /** Active transaction; null when auto-commit mode (one tx per statement). */
    private Transaction activeTx = null;

    /**
     * True when the current explicit transaction has failed due to a statement error.
     * In this state all DML/DDL/SELECT is rejected with SQLSTATE 25P02 until ROLLBACK.
     */
    private boolean txFailed = false;

    /** The raw SQL text of the currently executing statement (for CREATE VIEW). */
    private String lastSql;

    /**
     * Snapshot of variables overridden by {@code SET LOCAL} in the current transaction.
     * Key = variable name, value = the pre-LOCAL value (null if the variable did not exist).
     * Restored on COMMIT or ROLLBACK; populated lazily only when SET LOCAL is used.
     */
    private final Map<String, String> localVarSave = new HashMap<>();

    // ── LISTEN/NOTIFY state ───────────────────────────────────────────────────

    /** Channels this session is currently LISTENing on. */
    private final Set<String> listeningChannels = new HashSet<>();

    /**
     * Notifications delivered to this session (by other sessions' NOTIFY or
     * by an immediate NOTIFY outside a transaction).  Drained by the JDBC layer.
     */
    private final ConcurrentLinkedQueue<PgNotification> deliveredNotifications =
            new ConcurrentLinkedQueue<>();

    /**
     * Notifications buffered for the current transaction.
     * Deduplication key is (channel, payload) — same pair is sent at most once per tx.
     * Flushed to the bus on COMMIT; discarded on ROLLBACK.
     */
    private final LinkedHashSet<PgNotification> txPendingNotifies = new LinkedHashSet<>();

    /**
     * A stable integer that acts as this session's "backend PID".
     * Used as the {@code pid} field in {@link PgNotification}.
     */
    private final int sessionPid = System.identityHashCode(this);

    /** Session-local function registry — holds pg_notify, pg_listening_channels. */
    private final FunctionRegistry sessionFunctions = new FunctionRegistry();

    Session(Database database) {
        this.ddl = new DdlExecutor(database);
        // Wire evaluator into DDL executor so ADD COLUMN DEFAULT can fill existing rows
        Evaluator ddlEval = new Evaluator(database.catalog().functions(), database.collation());
        this.ddl.setEvaluator(ddlEval);
        this.database = database;
        registerSessionFunctions();
        registerAdvisoryLockFunctions();
        // Default session variables matching PostgreSQL defaults
        variables.put("server_version", "15.0");
        variables.put("client_encoding", "UTF8");
        variables.put("server_encoding", "UTF8");
        variables.put("datestyle", "ISO, MDY");
        variables.put("intervalstyle", "postgres");
        variables.put("timezone", "UTC");
        variables.put("integer_datetimes", "on");
        variables.put("standard_conforming_strings", "on");
        variables.put("search_path", "public");
    }

    public Database database() { return database; }

    public CatalogManager catalog() { return database.catalog(); }

    /**
     * Current session search path as an ordered list of schema names.
     * Parsed from the {@code search_path} session variable.
     */
    public List<String> searchPath() {
        String raw = variables.getOrDefault("search_path", "public");
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean inTransaction() {
        return activeTx != null;
    }

    public boolean isTransactionFailed() {
        return activeTx != null && txFailed;
    }

    public void close() {
        closed = true;
        database.notifyBus().unsubscribeAll(this);
        listeningChannels.clear();
        // Release session-level advisory locks
        database.advisoryLocks().releaseAllSession(sessionPid);
    }

    // -------------------------------------------------------------------------
    // Execution entry point

    /**
     * Parse and execute one or more semicolon-separated SQL statements.
     * Returns the result of the last statement.
     *
     * <p>If the SQL contains a {@code COPY … FROM STDIN} block with inline data
     * (psql {@code \.} terminator), the data is stripped before parsing and
     * passed to {@link org.pgjava.executor.CopyExecutor} for bulk insert.
     */
    public QueryResult execute(String sql) throws SQLException {
        Session prev = CURRENT.get();
        CURRENT.set(this);
        try {
            return executeInner(sql);
        } finally {
            if (prev != null) CURRENT.set(prev); else CURRENT.remove();
        }
    }

    private QueryResult executeInner(String sql) throws SQLException {
        // ── Inline COPY data detection ─────────────────────────────────────────
        // psql sends: COPY tbl (cols) FROM stdin;\n<data lines>\n\.\n
        // We detect \. on its own line, split the string, parse only the SQL part,
        // and feed the data lines to CopyExecutor.
        String[] copyParts = splitCopyInline(sql);
        String parseable = copyParts != null ? copyParts[0] : sql;
        String inlineCopyData = copyParts != null ? copyParts[1] : null;

        List<Stmt> stmts;
        try {
            stmts = ParserProvider.parse(parseable);
        } catch (ParseException e) {
            var builder = PgErrorException.error("42601", e.getMessage())
                    .cause(e);
            if (e.getColumn() > 0) builder.position(e.getColumn());
            throw builder.build();
        }
        if (stmts.isEmpty()) return QueryResult.EMPTY_DML;
        this.lastSql = parseable;
        QueryResult last = QueryResult.EMPTY_DML;
        for (Stmt stmt : stmts) {
            try {
                last = (stmt instanceof CopyStmt cs)
                        ? executeCopy(cs, inlineCopyData)
                        : executeOne(stmt);
            } catch (SQLException e) {
                // In an explicit transaction, any error → FAILED state (per SQL standard)
                if (activeTx != null && !txFailed
                        && !(stmt instanceof RollbackStmt)
                        && !(stmt instanceof RollbackToSavepointStmt)) {
                    txFailed = true;
                }
                throw e;
            }
        }

        // Persist catalog + heap snapshot after every statement that leaves no
        // open transaction.  This covers DDL (no implicit tx) and COMMIT.
        // Mid-transaction statements are not persisted — only committed data survives.
        if (activeTx == null && database.isPersistent()) {
            try {
                database.persist();
            } catch (IOException e) {
                throw PgErrorException.error("XX000", "persist failed: " + e.getMessage()).cause(e).build();
            }
        }

        return last;
    }

    // -------------------------------------------------------------------------
    // Per-statement dispatch

    private QueryResult executeOne(Stmt stmt) throws SQLException {
        // ── Transaction control ────────────────────────────────────────────────
        // ROLLBACK is always allowed, even in FAILED state.
        if (stmt instanceof RollbackStmt) {
            rollbackTx();
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof RollbackToSavepointStmt rsp) {
            try {
                if (activeTx != null) {
                    database.txManager().rollbackToSavepoint(activeTx, rsp.name());
                    txFailed = false; // partial rollback also clears FAILED
                }
            } catch (IOException e) {
                throw PgErrorException.error("XX000", "rollback to savepoint failed: " + e.getMessage()).cause(e).build();
            }
            return QueryResult.EMPTY_DML;
        }

        // ── FAILED state gate ─────────────────────────────────────────────────
        // Any non-rollback statement in a failed explicit transaction is rejected.
        if (txFailed && activeTx != null) {
            throw PgErrorException.error("25P02",
                    "current transaction is aborted, commands ignored until end of transaction block").build();
        }

        if (stmt instanceof BeginStmt) {
            beginTx();
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof CommitStmt) {
            commitTx();
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof SavepointStmt sp) {
            try {
                if (activeTx == null) beginTx();
                database.txManager().savepoint(activeTx, sp.name());
            } catch (IOException e) {
                throw PgErrorException.error("XX000", "savepoint failed: " + e.getMessage()).cause(e).build();
            }
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof ReleaseSavepointStmt rs) {
            try {
                if (activeTx == null)
                    throw PgErrorException.error("3B001", "RELEASE SAVEPOINT can only be used in transaction blocks").build();
                database.txManager().releaseSavepoint(activeTx, rs.name());
            } catch (IOException e) {
                throw PgErrorException.error("XX000", "release savepoint failed: " + e.getMessage()).cause(e).build();
            }
            return QueryResult.EMPTY_DML;
        }

        // Schema management — delegate to real catalog
        if (stmt instanceof CreateSchemaStmt s) {
            database.catalog().createSchema(s.name(), s.ifNotExists());
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof DropSchemaStmt s) {
            boolean cascade = s.behavior() == org.pgjava.sql.ast.DropBehavior.CASCADE;
            for (String name : s.names()) {
                database.catalog().dropSchema(name, cascade, s.ifExists());
            }
            return QueryResult.EMPTY_DML;
        }

        // SET / SHOW
        if (stmt instanceof SetStmt s)  { handleSet(s);  return QueryResult.EMPTY_DML; }
        if (stmt instanceof ShowStmt s) { return handleShow(s); }

        // Utility — no-op stubs
        if (stmt instanceof VacuumStmt
                || stmt instanceof GrantStmt) {
            return QueryResult.EMPTY_DML;
        }

        // EXPLAIN
        if (stmt instanceof ExplainStmt es) {
            return handleExplain(es);
        }

        // ── LISTEN / UNLISTEN / NOTIFY ────────────────────────────────────────
        if (stmt instanceof ListenStmt ls) {
            handleListen(ls.channelName());
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof UnlistenStmt us) {
            handleUnlisten(us.channelName());
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof NotifyStmt ns) {
            handleNotify(ns.channelName(), ns.payload());
            return QueryResult.EMPTY_DML;
        }

        // LOCK TABLE
        if (stmt instanceof LockTableStmt lockStmt) {
            return executeLockTable(lockStmt);
        }

        // DDL — delegate to DdlExecutor
        List<String> sp = searchPath();
        if (stmt instanceof CreateTableStmt s) {
            ddl.executeCreateTable(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof DropTableStmt s) {
            for (RangeVar rv : s.relations()) {
                acquireDdlLock(rv, sp, LockMode.ACCESS_EXCLUSIVE);
            }
            ddl.executeDropTable(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof TruncateStmt s) {
            for (RangeVar rv : s.relations()) {
                acquireDdlLock(rv, sp, LockMode.ACCESS_EXCLUSIVE);
            }
            ddl.executeTruncate(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof CreateIndexStmt s) {
            acquireDdlLock(s.relation(), sp, LockMode.SHARE);
            ddl.executeCreateIndex(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof DropIndexStmt s) {
            ddl.executeDropIndex(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof CreateSequenceStmt s) {
            ddl.executeCreateSequence(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof DropSequenceStmt s) {
            ddl.executeDropSequence(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof AlterSequenceStmt s) {
            ddl.executeAlterSequence(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof CreateViewStmt s) {
            ddl.executeCreateView(s, sp, lastSql);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof DropViewStmt s) {
            ddl.executeDropView(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof AlterTableStmt s) {
            acquireDdlLock(s.relation(), sp, LockMode.ACCESS_EXCLUSIVE);
            ddl.executeAlterTable(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof CreateTableAsStmt s) {
            executeCreateTableAs(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof CreateFunctionStmt s) {
            ddl.executeCreateFunction(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof DropFunctionStmt s) {
            ddl.executeDropFunction(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof CreateTriggerStmt s) {
            ddl.executeCreateTrigger(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof DropTriggerStmt s) {
            ddl.executeDropTrigger(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof CreateEnumStmt s) {
            ddl.executeCreateEnum(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof CreateDomainStmt s) {
            ddl.executeCreateDomain(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof AlterEnumStmt s) {
            ddl.executeAlterEnum(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof DropTypeStmt s) {
            ddl.executeDropType(s, sp);
            return QueryResult.EMPTY_DML;
        }
        if (stmt instanceof CallStmt s) {
            return executeCall(s, sp);
        }
        if (stmt instanceof PrepareStmt s) {
            return executePrepare(s);
        }
        if (stmt instanceof ExecuteStmt s) {
            return executeExecuteStmt(s, sp);
        }
        if (stmt instanceof DeallocateStmt s) {
            return executeDeallocate(s);
        }

        // DO block — anonymous PL/pgSQL execution
        if (stmt instanceof DoStmt doStmt) {
            return executeDoBlock(doStmt);
        }

        // DML / query — Phase 9 execution engine
        if (stmt instanceof SelectStmt s) {
            return executeSelect(s);
        }
        if (stmt instanceof InsertStmt s) {
            return executeDml(() -> makePlanner().planInsert(s, requireTx()));
        }
        if (stmt instanceof UpdateStmt s) {
            return executeDml(() -> makePlanner().planUpdate(s, requireTx()));
        }
        if (stmt instanceof DeleteStmt s) {
            return executeDml(() -> makePlanner().planDelete(s, requireTx()));
        }

        // Explicitly unsupported raw SQL passthrough
        if (stmt instanceof UnsupportedStmt u) {
            if (isNoOpStmt(u.stmtType())) return QueryResult.EMPTY_DML;
            throw new UnsupportedOperationException(
                    "pgjava: unsupported SQL construct: " + u.stmtType()
                    + (u.rawText().isEmpty() ? "" : " — " + u.rawText()));
        }

        throw new UnsupportedOperationException(
                "pgjava execution not yet implemented: "
                        + stmt.getClass().getSimpleName());
    }

    private QueryResult executeDoBlock(DoStmt doStmt) throws SQLException {
        String language = doStmt.language() != null ? doStmt.language().toLowerCase() : "plpgsql";
        if (!"plpgsql".equals(language)) {
            throw PgErrorException.error("42704",
                    "language \"" + language + "\" does not exist").build();
        }
        var plBody = org.pgjava.sql.parser.PlPgSqlBodyParser.parse(doStmt.body());
        var interp = new org.pgjava.executor.plpgsql.PlPgSqlInterpreter(
                database, searchPath(), new Object[0], List.of(), null);
        interp.execute(plBody);
        return QueryResult.EMPTY_DML;
    }

    // ─── CALL ────────────────────────────────────────────────────────────────

    private QueryResult executeCall(CallStmt s, List<String> sp) throws SQLException {
        String name = s.funcname().getLast().toLowerCase();
        // Evaluate arguments
        var evaluator = new Evaluator(database.catalog().functions(), database.collation());
        evaluator.setSessionFunctions(sessionFunctions);
        Object[] args = new Object[s.args().size()];
        for (int i = 0; i < s.args().size(); i++) {
            args[i] = evaluator.eval(s.args().get(i), EvalContext.empty());
        }
        // Resolve function (procedures are stored as functions)
        FunctionDef fn = database.catalog().functions().findScalarForArgs(name, args);
        if (fn == null) fn = database.catalog().functions().findScalar(name, args.length);
        if (fn == null) {
            throw PgErrorException.error("42883",
                    "procedure " + name + " does not exist").build();
        }
        fn.impl().invoke(args);
        return QueryResult.EMPTY_DML;
    }

    // ─── PREPARE / EXECUTE / DEALLOCATE ──────────────────────────────────────

    private final Map<String, PreparedPlan> preparedPlans = new HashMap<>();
    /** Params bound via EXECUTE — injected into planner evaluator via makePlanner(). */
    private Object[] execParams;

    private record PreparedPlan(String name, Stmt query) {}

    private QueryResult executePrepare(PrepareStmt s) {
        String name = s.name().toLowerCase();
        preparedPlans.put(name, new PreparedPlan(name, s.query()));
        return QueryResult.EMPTY_DML;
    }

    private QueryResult executeExecuteStmt(ExecuteStmt s, List<String> sp) throws SQLException {
        String name = s.name().toLowerCase();
        PreparedPlan plan = preparedPlans.get(name);
        if (plan == null) {
            throw PgErrorException.error("26000",
                    "prepared statement \"" + s.name() + "\" does not exist").build();
        }
        // Evaluate parameters
        Object[] params = null;
        if (s.params() != null && !s.params().isEmpty()) {
            var evaluator = new Evaluator(database.catalog().functions(), database.collation());
            evaluator.setSessionFunctions(sessionFunctions);
            params = new Object[s.params().size()];
            for (int i = 0; i < s.params().size(); i++) {
                params[i] = evaluator.eval(s.params().get(i), EvalContext.empty());
            }
        }
        // Execute the prepared query with parameters bound
        Object[] prevParams = this.execParams;
        this.execParams = params;
        try {
            return executeOne(plan.query());
        } finally {
            this.execParams = prevParams;
        }
    }

    private QueryResult executeDeallocate(DeallocateStmt s) {
        if (s.name() == null) {
            preparedPlans.clear();
        } else {
            preparedPlans.remove(s.name().toLowerCase());
        }
        return QueryResult.EMPTY_DML;
    }

    /**
     * Returns true for statement types that are safe to silently ignore —
     * typically permission/ownership DDL and maintenance commands that have
     * no semantic effect in an embedded single-user database.
     */
    private static boolean isNoOpStmt(String type) {
        return switch (type) {
            case "CreateRoleStmt", "AlterRoleStmt", "AlterRoleSetStmt",
                 "CreateUserStmt", "AlterUserStmt",
                 "DropRoleStmt",
                 // LockStmt is now handled as LockTableStmt
                 "CommentStmt",
                 "CreateExtensionStmt", "AlterExtensionStmt", "AlterExtensionContentsStmt",
                 "AlterOwnerStmt",
                 "AlterSystemStmt",
                 "ClusterStmt",
                 "CheckPointStmt",
                 "ReindexStmt",
                 "RuleStmt",
                 "CreatedbStmt", "AlterDatabaseStmt", "AlterDatabaseSetStmt", "DropdbStmt",
                 "SecLabelStmt"
                    -> true;
            default -> type.startsWith("DropStmt/OBJECT_ROLE")
                    || type.startsWith("DropStmt/OBJECT_AGGREGATE")
                    || type.startsWith("DropStmt/OBJECT_EXTENSION")
                    || type.startsWith("DropStmt/OBJECT_RULE")
                    || type.startsWith("DropStmt/OBJECT_POLICY");
        };
    }

    // -------------------------------------------------------------------------
    // Phase 9: SELECT execution

    private QueryResult executeSelect(SelectStmt s) throws SQLException {
        // SELECT … FOR UPDATE requires an active transaction to hold the row locks.
        // Start one implicitly if not already in an explicit transaction.
        // The transaction stays open — caller commits/rolls back via BEGIN/COMMIT/ROLLBACK.
        if (!s.locking().isEmpty() && activeTx == null) {
            beginTx();
        }
        Planner planner = makePlanner();  // picks up activeTx via setCurrentTransaction
        Operator root = planner.planSelect(s);
        root.open();
        try {
            OutputSchema schema = root.schema();
            List<ColumnMeta> cols = new ArrayList<>(schema.width());
            for (int i = 0; i < schema.width(); i++) {
                cols.add(ColumnMeta.varchar(schema.name(i)));
            }
            List<Object[]> rows = new ArrayList<>();
            Row r;
            while ((r = root.next()) != null) {
                Object[] vals = r.values().clone();
                normalizeOutputValues(vals);
                rows.add(vals);
            }
            return new QueryResult(-1, cols, rows);
        } finally {
            root.close();
        }
    }

    // -------------------------------------------------------------------------
    // CREATE TABLE AS SELECT

    private void executeCreateTableAs(CreateTableAsStmt s, List<String> sp)
            throws SQLException {
        // 1. Run the SELECT to get schema + rows
        Planner planner = makePlanner();
        Operator root = planner.planSelect(s.query());
        root.open();
        List<Object[]> rows;
        OutputSchema schema;
        try {
            schema = root.schema();
            rows   = new ArrayList<>();
            Row r;
            while ((r = root.next()) != null) rows.add(r.values().clone());
        } finally {
            root.close();
        }

        // 2. Derive column defs from output schema (all text, nullable — no constraints)
        PgType textType = PgTypeRegistry.INSTANCE.byTypeName("text");
        List<ColumnDef> cols = new ArrayList<>(schema.width());
        for (int i = 0; i < schema.width(); i++) {
            cols.add(new ColumnDef(schema.name(i), i + 1, textType, -1, true, null,
                    GeneratedKind.NONE, null));
        }

        // 3. Resolve schema name for the new table
        String schemaName = sp.isEmpty() ? "public" : sp.get(0);
        RangeVar rv = s.relation();
        if (rv.schemaName() != null && !rv.schemaName().isEmpty()) {
            schemaName = rv.schemaName().toLowerCase();
        }
        String tableName = rv.relName().toLowerCase();

        // 4. Create table in catalog + storage
        TableDef t = database.catalog().createTable(schemaName, tableName,
                cols, List.of(), s.temp(), s.ifNotExists());
        database.storage().createTable(t);

        // 5. Bulk-insert rows using a transaction
        boolean autoCommit = (activeTx == null);
        if (autoCommit) beginTx();
        try {
            for (Object[] row : rows) {
                database.txManager().insert(activeTx, t.oid(), row,
                        (idx, vals) -> null); // no indexes on CTAS table
            }
            if (autoCommit) commitTx();
        } catch (Exception e) {
            if (autoCommit) { try { rollbackTx(); } catch (Exception ignored) {} }
            if (e instanceof SQLException se) throw se;
            throw PgErrorException.error("XX000", "CTAS insert failed: " + e.getMessage()).cause(e).build();
        }
    }

    // -------------------------------------------------------------------------
    // Phase 9: DML execution (auto-commit wrapper)

    @FunctionalInterface
    private interface DmlAction {
        org.pgjava.executor.DmlResult execute() throws SQLException;
    }

    private QueryResult executeDml(DmlAction action) throws SQLException {
        boolean autoCommit = (activeTx == null);
        if (autoCommit) beginTx();
        try {
            org.pgjava.executor.DmlResult result = action.execute();
            if (autoCommit) commitTx();
            if (result.hasReturning()) {
                // RETURNING — emit as a query result set
                for (Object[] row : result.returningRows()) normalizeOutputValues(row);
                return new QueryResult(-1, result.returningCols(), result.returningRows());
            }
            return new QueryResult(result.rowCount(), List.of(), List.of());
        } catch (SQLException e) {
            if (autoCommit) {
                try { rollbackTx(); } catch (Exception ignored) {}
            } else {
                txFailed = true; // explicit tx: enters FAILED state on error
            }
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // Transaction helpers

    private void beginTx() throws SQLException {
        if (activeTx != null) return; // already in a transaction
        try {
            activeTx = database.txManager().begin();
        } catch (IOException e) {
            throw PgErrorException.error("XX000", "BEGIN failed: " + e.getMessage()).cause(e).build();
        }
    }

    private void commitTx() throws SQLException {
        if (activeTx == null) return;
        long txid = activeTx.txid();
        // Flush transaction-buffered notifications before the commit record is written
        List<PgNotification> toFlush = new ArrayList<>(txPendingNotifies);
        txPendingNotifies.clear();
        try {
            database.txManager().commit(activeTx);
        } catch (IOException e) {
            throw PgErrorException.error("XX000", "COMMIT failed: " + e.getMessage()).cause(e).build();
        } finally {
            activeTx = null;
        }
        // Release transaction-level advisory locks
        database.advisoryLocks().releaseAllXact(txid);
        // SET LOCAL vars are also restored on COMMIT (they revert to pre-transaction values)
        restoreLocalVars();
        // Publish after commit so listeners see committed state
        for (PgNotification n : toFlush) {
            database.notifyBus().publish(n.channel(), n.payload(), n.pid());
        }
    }

    private void rollbackTx() throws SQLException {
        txFailed = false;
        txPendingNotifies.clear();  // discard — rolled-back transactions don't notify
        if (activeTx == null) return;
        long txid = activeTx.txid();
        try {
            database.txManager().rollback(activeTx);
        } catch (IOException | SQLException e) {
            throw PgErrorException.error("XX000", "ROLLBACK failed: " + e.getMessage()).cause(e).build();
        } finally {
            activeTx = null;
        }
        // Release transaction-level advisory locks
        database.advisoryLocks().releaseAllXact(txid);
        restoreLocalVars();
    }

    /** Restore any variables that were overridden via SET LOCAL, then clear the snapshot. */
    private void restoreLocalVars() {
        for (Map.Entry<String, String> e : localVarSave.entrySet()) {
            if (e.getValue() == null) {
                variables.remove(e.getKey());
            } else {
                variables.put(e.getKey(), e.getValue());
            }
        }
        localVarSave.clear();
    }

    /**
     * Parse the {@code lock_timeout} session variable.
     * Accepts integer milliseconds or PostgreSQL interval strings like {@code '5s'}, {@code '1000ms'}.
     * Returns 0 if not set (= default timeout).
     */
    private long parseLockTimeout() {
        String val = variables.get("lock_timeout");
        if (val == null || val.isEmpty() || "0".equals(val)) return 0;
        try {
            // Try plain integer (milliseconds)
            return Long.parseLong(val);
        } catch (NumberFormatException ignored) {}
        // Try PostgreSQL-style interval: "5s", "1000ms", "2min"
        val = val.trim().replace("'", "");
        if (val.endsWith("ms")) {
            try { return Long.parseLong(val.substring(0, val.length() - 2).trim()); }
            catch (NumberFormatException ignored) {}
        } else if (val.endsWith("s")) {
            try { return (long) (Double.parseDouble(val.substring(0, val.length() - 1).trim()) * 1000); }
            catch (NumberFormatException ignored) {}
        } else if (val.endsWith("min")) {
            try { return (long) (Double.parseDouble(val.substring(0, val.length() - 3).trim()) * 60_000); }
            catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    /** Returns active transaction, or throws if none (should not happen in normal flow). */
    private Transaction requireTx() throws SQLException {
        if (activeTx == null)
            throw PgErrorException.error("25P01", "there is no transaction in progress").build();
        return activeTx;
    }

    // ── PL/pgSQL savepoint helpers (used by PlPgSqlInterpreter) ──────────

    /**
     * Create a synthetic savepoint for a PL/pgSQL EXCEPTION block.
     * If no transaction is active, starts one (auto-commit mode).
     */
    public void plSavepoint(String name) throws SQLException {
        if (activeTx == null) beginTx();
        try {
            database.txManager().savepoint(activeTx, name);
        } catch (IOException e) {
            throw PgErrorException.error("XX000", "SAVEPOINT failed: " + e.getMessage()).cause(e).build();
        }
    }

    /** Roll back to a PL/pgSQL synthetic savepoint. */
    public void plRollbackToSavepoint(String name) throws SQLException {
        if (activeTx == null) return; // nothing to rollback
        try {
            database.txManager().rollbackToSavepoint(activeTx, name);
        } catch (IOException e) {
            throw PgErrorException.error("XX000",
                    "ROLLBACK TO SAVEPOINT failed: " + e.getMessage()).cause(e).build();
        }
    }

    /**
     * Execute a DML statement from PL/pgSQL context with a custom Evaluator
     * that has PL/pgSQL variable resolution.
     */
    public QueryResult plExecuteDml(Stmt stmt, org.pgjava.executor.Evaluator eval) throws SQLException {
        boolean autoCommit = (activeTx == null);
        if (autoCommit) beginTx();
        try {
            var planner = new org.pgjava.executor.Planner(
                    database.catalog(), database.storage(),
                    database.txManager(), eval, searchPath());
            planner.setSnapshot(database.txManager().snapshotFor(activeTx.txid()));
            planner.setCurrentTransaction(activeTx);

            org.pgjava.executor.DmlResult result;
            if (stmt instanceof InsertStmt ins) {
                result = planner.planInsert(ins, activeTx);
            } else if (stmt instanceof UpdateStmt upd) {
                result = planner.planUpdate(upd, activeTx);
            } else if (stmt instanceof DeleteStmt del) {
                result = planner.planDelete(del, activeTx);
            } else {
                // DDL — delegate to normal execution
                if (autoCommit) commitTx();
                return execute(stmt.toString());
            }
            if (autoCommit) commitTx();
            return new QueryResult(result.rowCount(), List.of(), List.of());
        } catch (SQLException e) {
            if (autoCommit) {
                try { rollbackTx(); } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    /** Release a PL/pgSQL synthetic savepoint (cleanup on success path). */
    public void plReleaseSavepoint(String name) throws SQLException {
        if (activeTx == null) return;
        // Just remove the savepoint marker — TransactionManager doesn't have a release
        // method, so we let it be cleaned up naturally.  The WAL records remain but
        // the savepoint mark is consumed.
    }

    /**
     * Acquire a table-level lock for DDL operations.  Only effective when an
     * explicit transaction is active; in auto-commit mode (activeTx == null)
     * the lock would be released immediately, so we skip it.
     */
    private void acquireDdlLock(RangeVar rv, List<String> searchPath, LockMode mode)
            throws SQLException {
        if (activeTx == null || rv == null) return;
        try {
            var def = database.catalog().resolveTable(rv.relName().toLowerCase(), searchPath);
            database.storage().tableLocks().acquire(def.oid(), activeTx.txid(), mode, 0);
        } catch (SQLException e) {
            // Table may not exist yet (e.g. CREATE TABLE IF NOT EXISTS then DROP) — ignore
            String state = e.getSQLState();
            if (state != null && state.equals("42P01")) return;
            throw e;
        }
    }

    /**
     * Execute a LOCK TABLE statement: acquire the specified table-level lock.
     * LOCK TABLE implicitly starts a transaction if not already in one.
     */
    private QueryResult executeLockTable(LockTableStmt s) throws SQLException {
        // LOCK TABLE requires a transaction — start one implicitly if needed
        beginTx();
        LockMode mode = LockMode.fromPgMode(s.mode());
        List<String> sp = searchPath();
        for (RangeVar rv : s.relations()) {
            var def = database.catalog().resolveTable(rv.relName().toLowerCase(), sp);
            if (s.nowait()) {
                boolean granted = database.storage().tableLocks()
                        .tryAcquire(def.oid(), activeTx.txid(), mode);
                if (!granted) {
                    throw PgErrorException.error("55P03",
                            "could not obtain lock on relation \"" + rv.relName() + "\"").build();
                }
            } else {
                database.storage().tableLocks()
                        .acquire(def.oid(), activeTx.txid(), mode, 0);
            }
        }
        return QueryResult.EMPTY_DML;
    }

    /** Convert internal types (e.g. EnumValue) to their external representations for query results. */
    private static void normalizeOutputValues(Object[] vals) {
        for (int i = 0; i < vals.length; i++) {
            Object v = vals[i];
            if (v instanceof org.pgjava.types.EnumValue ev) {
                vals[i] = ev.label();
            } else if (v instanceof Object[] nested) {
                normalizeOutputValues(nested);
            } else if (v instanceof java.util.List<?> list) {
                vals[i] = normalizeList(list);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<?> normalizeList(java.util.List<?> list) {
        boolean needsCopy = false;
        for (Object item : list) {
            if (item instanceof org.pgjava.types.EnumValue) { needsCopy = true; break; }
        }
        if (!needsCopy) return list;
        java.util.List<Object> result = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            result.add(item instanceof org.pgjava.types.EnumValue ev ? ev.label() : item);
        }
        return result;
    }

    private Planner makePlanner() {
        Evaluator eval = new Evaluator(database.catalog().functions(), database.collation());
        eval.setSessionFunctions(sessionFunctions);
        if (execParams != null) eval.setFunctionParams(execParams);
        // Wire schema-local type resolution for user-defined enums/domains
        final var catalog = database.catalog();
        final var sp = searchPath();
        eval.setSchemaTypeResolver(name -> {
            for (String schemaName : sp) {
                org.pgjava.catalog.Schema schema = catalog.allSchemas().get(schemaName);
                if (schema != null) {
                    org.pgjava.types.PgType t = schema.type(name);
                    if (t != null) return t;
                }
            }
            return null;
        });
        Planner planner = new Planner(
                database.catalog(),
                database.storage(),
                database.txManager(),
                eval,
                searchPath());
        // Wire subquery executor after planner is constructed (avoids circular dependency)
        eval.setSubqueryExecutor(planner::executeSubquery);
        // Pass the active transaction so FOR UPDATE can acquire row locks
        planner.setCurrentTransaction(activeTx);
        // Pass lock_timeout from session variable
        planner.setLockTimeout(parseLockTimeout());
        // Set READ COMMITTED snapshot for this statement — all SeqScans use this to
        // filter uncommitted rows from other sessions (no dirty reads)
        long currentTxid = activeTx != null ? activeTx.txid() : 0L;
        planner.setSnapshot(database.txManager().snapshotFor(currentTxid));
        planner.setTriggerDatabase(database);
        return planner;
    }

    // -------------------------------------------------------------------------
    // COPY

    private QueryResult executeCopy(CopyStmt cs, String inlineCopyData) throws SQLException {
        if (cs.isFrom()) {
            // COPY FROM behaves like DML: needs a transaction, commits in auto-commit mode
            return executeDml(() -> {
                Planner planner = makePlanner();
                org.pgjava.executor.CopyExecutor copyEx = new org.pgjava.executor.CopyExecutor(
                        database.catalog(), database.storage(), database.txManager(),
                        planner, searchPath());
                copyEx.setTriggerDatabase(database);
                QueryResult qr = copyEx.execute(cs, inlineCopyData, requireTx());
                return org.pgjava.executor.DmlResult.ofCount(qr.updateCount());
            });
        } else {
            // COPY TO is read-only — no transaction needed
            Planner planner = makePlanner();
            org.pgjava.executor.CopyExecutor copyEx = new org.pgjava.executor.CopyExecutor(
                    database.catalog(), database.storage(), database.txManager(),
                    planner, searchPath());
            return copyEx.execute(cs, null, null);
        }
    }

    /**
     * Detect and split a {@code COPY … FROM STDIN} block with inline psql data.
     *
     * <p>Inline data is terminated by {@code \.} on a line by itself.
     * Returns {@code [sqlPart, dataPart]} if found, or {@code null} if the SQL
     * does not contain inline COPY data.
     */
    public static String[] splitCopyInline(String sql) {
        // Fast check: does this string contain \. on its own line?
        int dotIdx = findBackslashDot(sql);
        if (dotIdx < 0) return null;

        // Find the last semicolon before the data terminator
        // (the COPY statement ends with ;)
        int semi = -1;
        for (int i = dotIdx - 1; i >= 0; i--) {
            char c = sql.charAt(i);
            if (c == ';') { semi = i; break; }
            // Only whitespace/newline allowed between ; and first data line
            if (c != '\n' && c != '\r' && c != ' ' && c != '\t') {
                // Non-whitespace between dot and potential semicolon — data already started
                // Keep scanning
            }
        }
        if (semi < 0) return null;

        // Verify the SQL portion contains COPY ... FROM (STDIN|stdin|'stdin'|'STDIN')
        String sqlPart = sql.substring(0, semi + 1);
        String upper = sqlPart.toUpperCase();
        if (!upper.contains("COPY") || !upper.contains("FROM")) return null;
        // Must mention STDIN (with or without quotes, case-insensitive)
        if (!upper.contains("STDIN")) return null;

        // Data starts on the line after the semicolon
        int dataStart = semi + 1;
        // Skip a single trailing newline after the semicolon
        if (dataStart < sql.length() && sql.charAt(dataStart) == '\r') dataStart++;
        if (dataStart < sql.length() && sql.charAt(dataStart) == '\n') dataStart++;

        String dataPart = sql.substring(dataStart, dotIdx);
        return new String[]{sqlPart, dataPart};
    }

    /**
     * Finds the index of {@code \.} that appears at the start of a line
     * and is followed only by optional whitespace/EOL.
     * Returns -1 if not found.
     */
    private static int findBackslashDot(String sql) {
        int i = 0;
        while (i < sql.length()) {
            int idx = sql.indexOf("\\.", i);
            if (idx < 0) break;
            boolean startOfLine = (idx == 0) || sql.charAt(idx - 1) == '\n';
            int after = idx + 2;
            boolean endOfLine = after >= sql.length()
                    || sql.charAt(after) == '\n'
                    || sql.charAt(after) == '\r'
                    || sql.charAt(after) == ' ';
            if (startOfLine && endOfLine) return idx;
            i = idx + 1;
        }
        return -1;
    }

    // -------------------------------------------------------------------------
    // SET

    private void handleSet(SetStmt s) {
        // RESET ALL — restore all LOCAL overrides, remove all session vars
        if (s.name() == null || s.name().isBlank()) {
            restoreLocalVars();
            return;
        }
        String key = s.name().toLowerCase();
        if (s.args().isEmpty()) {
            // SET … TO DEFAULT or RESET param — revert to no value
            // For LOCAL scope: also remove from save map so it won't be re-applied
            localVarSave.remove(key);
            variables.remove(key);
            return;
        }
        // If SET LOCAL, snapshot the current value before overwriting (once per tx per key)
        if (s.scope() == SetScope.LOCAL && activeTx != null) {
            localVarSave.putIfAbsent(key, variables.get(key));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.args().size(); i++) {
            if (i > 0) sb.append(", ");
            Node arg = s.args().get(i);
            if (arg instanceof StringLiteral sl) {
                sb.append(unquoteIdent(sl.value()));
            } else {
                sb.append(arg.toString());
            }
        }
        variables.put(key, sb.toString());
    }

    /** Strip surrounding double-quotes and unescape internal {@code ""} → {@code "}. */
    static String unquoteIdent(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s;
    }

    // -------------------------------------------------------------------------
    // SHOW

    private QueryResult handleShow(ShowStmt s) {
        String key = s.name().toLowerCase();
        String value = variables.getOrDefault(key, "");
        return QueryResult.singleValue(ColumnMeta.varchar(s.name()), value);
    }

    private QueryResult handleExplain(ExplainStmt es) throws SQLException {
        Stmt inner = es.query();
        ColumnMeta col = ColumnMeta.varchar("QUERY PLAN");

        if (inner instanceof SelectStmt s) {
            Planner planner = makePlanner();
            Operator root = planner.planSelect(s);
            java.util.List<String> lines = root.explain(0);

            if (es.analyze()) {
                // EXPLAIN ANALYZE: actually execute and count rows
                root.open();
                long rowCount = 0;
                try {
                    while (root.next() != null) rowCount++;
                } finally {
                    root.close();
                }
                lines.add("Planning Time: 0.000 ms");
                lines.add("Execution Time: 0.000 ms");
                lines.add("(actual rows=" + rowCount + ")");
            }

            java.util.List<Object[]> rows = new ArrayList<>();
            for (String line : lines) rows.add(new Object[]{line});
            return new QueryResult(-1, java.util.List.of(col), rows);
        }

        // For DML or other statements, just describe the statement type
        java.util.List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{inner.getClass().getSimpleName()});
        return new QueryResult(-1, java.util.List.of(col), rows);
    }

    // -------------------------------------------------------------------------
    // LISTEN / NOTIFY / UNLISTEN

    private void handleListen(String channel) {
        String norm = channel.toLowerCase();
        if (listeningChannels.add(norm)) {
            database.notifyBus().subscribe(norm, this);
        }
    }

    private void handleUnlisten(String channel) {
        if (channel == null || channel.isEmpty() || channel.equals("*")) {
            // UNLISTEN * — remove all subscriptions
            for (String ch : listeningChannels) {
                database.notifyBus().unsubscribe(ch, this);
            }
            listeningChannels.clear();
        } else {
            String norm = channel.toLowerCase();
            if (listeningChannels.remove(norm)) {
                database.notifyBus().unsubscribe(norm, this);
            }
        }
    }

    void handleNotify(String channel, String payload) {
        String norm = channel == null ? "" : channel.toLowerCase();
        String p    = payload == null ? "" : payload;
        PgNotification notif = new PgNotification(sessionPid, norm, p);
        if (activeTx != null) {
            // Inside a transaction — buffer for delivery on COMMIT
            txPendingNotifies.add(notif);
        } else {
            // Auto-commit — publish immediately
            database.notifyBus().publish(norm, p, sessionPid);
        }
    }

    /** Called by NotificationBus when another session publishes on a channel we LISTEN to. */
    @Override
    public void deliver(PgNotification notification) {
        deliveredNotifications.add(notification);
    }

    /**
     * Drain all pending notifications delivered to this session.
     *
     * <p>Called by the JDBC layer ({@link org.pgjava.jdbc.PgJavaConnection#getNotifications()}).
     * Returns an empty array if nothing has arrived yet.
     */
    public PgNotification[] drainNotifications() {
        List<PgNotification> list = new ArrayList<>();
        PgNotification n;
        while ((n = deliveredNotifications.poll()) != null) list.add(n);
        return list.toArray(PgNotification[]::new);
    }

    // -------------------------------------------------------------------------
    // Session-local functions (pg_notify, pg_notification_queue_usage)

    private void registerSessionFunctions() {
        PgType textType = PgTypeRegistry.INSTANCE.byTypeName("text");
        PgType voidType = PgTypeRegistry.INSTANCE.byOid(2278); // void
        PgType float8   = PgTypeRegistry.INSTANCE.byTypeName("float8");

        if (textType == null || voidType == null) return; // type registry not ready yet

        // pg_notify(channel text, payload text) → void
        sessionFunctions.register(new FunctionDef(
                0L, "pg_notify", "pg_catalog",
                List.of(textType, textType),
                voidType,
                false, false,
                args -> {
                    String ch  = args[0] == null ? "" : args[0].toString();
                    String pay = args[1] == null ? "" : args[1].toString();
                    handleNotify(ch, pay);
                    return null;
                }
        ));

        // pg_notification_queue_usage() → float8  — always returns 0.0 (no queue limit)
        if (float8 != null) {
            sessionFunctions.register(new FunctionDef(
                    0L, "pg_notification_queue_usage", "pg_catalog",
                    List.of(),
                    float8,
                    false, false,
                    args -> 0.0
            ));
        }

        // pg_listening_channels() → SETOF text
        // Returns one row per channel this session is currently LISTENing on.
        sessionFunctions.registerSrf(new org.pgjava.catalog.SrfDef(
                0L, "pg_listening_channels", "pg_catalog",
                List.of(),
                List.of("pg_listening_channels"),
                false,
                args -> {
                    // Snapshot current channels at call time (sorted for determinism)
                    List<String> channels = new ArrayList<>(listeningChannels);
                    channels.sort(String::compareTo);
                    return channels.stream()
                            .<Object[]>map(ch -> new Object[]{ch})
                            .toList();
                }
        ));
    }

    private void registerAdvisoryLockFunctions() {
        PgType boolType  = PgTypeRegistry.INSTANCE.byTypeName("bool");
        PgType int8Type  = PgTypeRegistry.INSTANCE.byTypeName("int8");
        PgType int4Type  = PgTypeRegistry.INSTANCE.byTypeName("int4");
        PgType voidType  = PgTypeRegistry.INSTANCE.byOid(2278);
        if (boolType == null || int8Type == null || voidType == null) return;

        var alm = database.advisoryLocks();

        // pg_try_advisory_xact_lock(int8) → bool
        sessionFunctions.register(new FunctionDef(
                3316L, "pg_try_advisory_xact_lock", "pg_catalog",
                List.of(int8Type), boolType, true, false,
                args -> {
                    long key = ((Number) args[0]).longValue();
                    long holderId = activeTx != null ? activeTx.txid() : sessionPid;
                    return alm.tryLockExclusive(key, holderId, false);
                }
        ));
        // pg_try_advisory_lock(int8) → bool
        sessionFunctions.register(new FunctionDef(
                3317L, "pg_try_advisory_lock", "pg_catalog",
                List.of(int8Type), boolType, true, false,
                args -> {
                    long key = ((Number) args[0]).longValue();
                    return alm.tryLockExclusive(key, sessionPid, true);
                }
        ));
        // pg_advisory_unlock(int8) → bool
        sessionFunctions.register(new FunctionDef(
                3318L, "pg_advisory_unlock", "pg_catalog",
                List.of(int8Type), boolType, true, false,
                args -> {
                    long key = ((Number) args[0]).longValue();
                    return alm.unlock(key, sessionPid);
                }
        ));
        // pg_advisory_unlock_all() → void
        sessionFunctions.register(new FunctionDef(
                3319L, "pg_advisory_unlock_all", "pg_catalog",
                List.of(), voidType, false, false,
                args -> { alm.releaseAllSession(sessionPid); return null; }
        ));
        // pg_advisory_lock(int8) → void  (blocking exclusive, session-level)
        sessionFunctions.register(new FunctionDef(
                0L, "pg_advisory_lock", "pg_catalog",
                List.of(int8Type), voidType, true, false,
                args -> {
                    long key = ((Number) args[0]).longValue();
                    alm.lock(key, sessionPid, true);
                    return null;
                }
        ));
        // pg_advisory_xact_lock(int8) → void  (blocking exclusive, transaction-level)
        sessionFunctions.register(new FunctionDef(
                0L, "pg_advisory_xact_lock", "pg_catalog",
                List.of(int8Type), voidType, true, false,
                args -> {
                    long key = ((Number) args[0]).longValue();
                    long holderId = activeTx != null ? activeTx.txid() : sessionPid;
                    alm.lock(key, holderId, false);
                    return null;
                }
        ));
        // pg_advisory_lock_shared(int8) → void  (blocking shared, session-level)
        sessionFunctions.register(new FunctionDef(
                0L, "pg_advisory_lock_shared", "pg_catalog",
                List.of(int8Type), voidType, true, false,
                args -> {
                    long key = ((Number) args[0]).longValue();
                    alm.lockShared(key, sessionPid, true);
                    return null;
                }
        ));
        // pg_advisory_xact_lock_shared(int8) → void  (blocking shared, transaction-level)
        sessionFunctions.register(new FunctionDef(
                0L, "pg_advisory_xact_lock_shared", "pg_catalog",
                List.of(int8Type), voidType, true, false,
                args -> {
                    long key = ((Number) args[0]).longValue();
                    long holderId = activeTx != null ? activeTx.txid() : sessionPid;
                    alm.lockShared(key, holderId, false);
                    return null;
                }
        ));
        // pg_try_advisory_xact_lock_shared(int8) → bool
        sessionFunctions.register(new FunctionDef(
                0L, "pg_try_advisory_xact_lock_shared", "pg_catalog",
                List.of(int8Type), boolType, true, false,
                args -> {
                    long key = ((Number) args[0]).longValue();
                    long holderId = activeTx != null ? activeTx.txid() : sessionPid;
                    return alm.tryLockShared(key, holderId, false);
                }
        ));
        // pg_try_advisory_lock_shared(int8) → bool
        sessionFunctions.register(new FunctionDef(
                0L, "pg_try_advisory_lock_shared", "pg_catalog",
                List.of(int8Type), boolType, true, false,
                args -> {
                    long key = ((Number) args[0]).longValue();
                    return alm.tryLockShared(key, sessionPid, true);
                }
        ));
        // pg_advisory_unlock_shared(int8) → bool
        sessionFunctions.register(new FunctionDef(
                0L, "pg_advisory_unlock_shared", "pg_catalog",
                List.of(int8Type), boolType, true, false,
                args -> {
                    long key = ((Number) args[0]).longValue();
                    return alm.unlockShared(key, sessionPid);
                }
        ));

        // Two-key overloads: (int4, int4) → compose as single long
        if (int4Type != null) {
            // pg_try_advisory_lock(int4, int4) → bool
            sessionFunctions.register(new FunctionDef(
                    0L, "pg_try_advisory_lock", "pg_catalog",
                    List.of(int4Type, int4Type), boolType, true, false,
                    args -> {
                        long key = composeTwoKey(((Number) args[0]).intValue(), ((Number) args[1]).intValue());
                        return alm.tryLockExclusive(key, sessionPid, true);
                    }
            ));
            // pg_try_advisory_xact_lock(int4, int4) → bool
            sessionFunctions.register(new FunctionDef(
                    0L, "pg_try_advisory_xact_lock", "pg_catalog",
                    List.of(int4Type, int4Type), boolType, true, false,
                    args -> {
                        long key = composeTwoKey(((Number) args[0]).intValue(), ((Number) args[1]).intValue());
                        long holderId = activeTx != null ? activeTx.txid() : sessionPid;
                        return alm.tryLockExclusive(key, holderId, false);
                    }
            ));
        }
    }

    private static long composeTwoKey(int k1, int k2) {
        return ((long) k1 << 32) | (k2 & 0xFFFFFFFFL);
    }

    // -------------------------------------------------------------------------
    // Variable access (used by Connection.getCatalog / getSchema etc.)

    public String getVariable(String name) {
        return variables.get(name.toLowerCase());
    }

    /**
     * Directly set a session variable, bypassing SQL parsing.
     *
     * <p>Used by the JDBC layer to apply connection-level configuration (e.g.
     * {@code search_path}) without building SQL strings from untrusted input.
     */
    public void setVariable(String name, String value) {
        variables.put(name.toLowerCase(), value);
    }
}
