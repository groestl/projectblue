package org.pgjava.jdbc;

import org.pgjava.catalog.*;
import org.pgjava.engine.ColumnMeta;
import org.pgjava.engine.QueryResult;
import org.pgjava.sql.ast.FkAction;
import org.pgjava.types.PgOid;

import java.sql.*;
import java.util.*;

/**
 * DatabaseMetaData for pgjava — backed by the live catalog.
 *
 * <p>Phase 9b Priority 3: getColumns, getPrimaryKeys, getImportedKeys,
 * getExportedKeys, getIndexInfo, getTables, getSchemas are all populated
 * from the real catalog so Hibernate schema validation and jOOQ codegen work.
 */
final class PgJavaDatabaseMetaData implements DatabaseMetaData {

    private final PgJavaConnection conn;

    PgJavaDatabaseMetaData(PgJavaConnection conn) {
        this.conn = conn;
    }

    // -------------------------------------------------------------------------
    // Helpers

    private static PgJavaResultSet empty(ColumnMeta... cols) {
        return PgJavaResultSet.empty(List.of(cols));
    }

    private static ColumnMeta v(String n) { return ColumnMeta.varchar(n); }
    private static ColumnMeta i(String n) { return ColumnMeta.integer(n); }
    private static ColumnMeta s(String n) { return ColumnMeta.smallint(n); }
    private static ColumnMeta b(String n) {
        return new ColumnMeta(n, n, "", "", Types.BOOLEAN, "bool", 0, 0,
                ResultSetMetaData.columnNullableUnknown);
    }

    /** True if name matches a JDBC pattern (% = any, _ = one char, null = any). */
    private static boolean matches(String name, String pattern) {
        if (pattern == null) return true;
        // Convert JDBC pattern to regex
        StringBuilder regex = new StringBuilder("(?i)^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '%') regex.append(".*");
            else if (c == '_') regex.append('.');
            else regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        regex.append('$');
        return name.matches(regex.toString());
    }

    /** User-visible schemas: exclude pg_catalog, information_schema, pg_toast, pg_temp. */
    private static boolean isUserSchema(String name) {
        return !name.startsWith("pg_") && !name.equals("information_schema");
    }

    /** Map a PostgreSQL OID to a JDBC Types constant. */
    static int pgOidToJdbcType(int oid) {
        return ColumnMeta.pgOidToJdbcType(oid);
    }

    /** Column precision / display size for a type. */
    private static int columnSize(int oid, int typmod) {
        return switch (oid) {
            case PgOid.INT2    -> 5;
            case PgOid.INT4    -> 10;
            case PgOid.INT8    -> 19;
            case PgOid.FLOAT4  -> 8;
            case PgOid.FLOAT8  -> 17;
            case PgOid.NUMERIC -> typmod > 0 ? ((typmod - 4) >> 16) & 0xFFFF : 131072;
            case PgOid.BOOL    -> 1;
            case PgOid.DATE    -> 10;
            case PgOid.TIME, PgOid.TIMETZ -> 15;
            case PgOid.TIMESTAMP, PgOid.TIMESTAMPTZ -> 29;
            case PgOid.UUID    -> 36;
            case PgOid.BPCHAR, PgOid.VARCHAR ->
                    typmod > 0 ? typmod - 4 : 2147483647;
            default            -> 2147483647;
        };
    }

    /** Decimal digits (scale) for a type. */
    private static int decimalDigits(int oid, int typmod) {
        return switch (oid) {
            case PgOid.INT2, PgOid.INT4, PgOid.INT8 -> 0;
            case PgOid.FLOAT4  -> 8;
            case PgOid.FLOAT8  -> 17;
            case PgOid.NUMERIC -> typmod > 0 ? (typmod - 4) & 0xFFFF : 0;
            default            -> 0;
        };
    }

    private CatalogManager catalog() {
        return conn.session().database().catalog();
    }

    private String dbName() {
        return conn.session().database().name();
    }

    // -------------------------------------------------------------------------
    // Identity / capabilities

    @Override public String getDatabaseProductName()    { return "PostgreSQL"; }
    @Override public String getDatabaseProductVersion() { return "15.0"; }
    @Override public int    getDatabaseMajorVersion()   { return 15; }
    @Override public int    getDatabaseMinorVersion()   { return 0; }
    @Override public String getDriverName()             { return "pgjava-jdbc"; }
    @Override public String getDriverVersion()          { return "0.1.0"; }
    @Override public int    getDriverMajorVersion()     { return 0; }
    @Override public int    getDriverMinorVersion()     { return 1; }
    @Override public int    getJDBCMajorVersion()       { return 4; }
    @Override public int    getJDBCMinorVersion()       { return 3; }

    @Override public String getURL()      throws SQLException { return conn.getMetaUrl(); }
    @Override public String getUserName() throws SQLException { return conn.getUserNameValue(); }

    @Override public boolean isReadOnly() throws SQLException { return false; }

    // -------------------------------------------------------------------------
    // Feature flags

    @Override public boolean allProceduresAreCallable()          { return false; }
    @Override public boolean allTablesAreSelectable()            { return true; }
    @Override public boolean nullsAreSortedHigh()                { return true; }
    @Override public boolean nullsAreSortedLow()                 { return false; }
    @Override public boolean nullsAreSortedAtStart()             { return false; }
    @Override public boolean nullsAreSortedAtEnd()               { return false; }
    @Override public boolean usesLocalFiles()                    { return false; }
    @Override public boolean usesLocalFilePerTable()             { return false; }
    @Override public boolean supportsMixedCaseIdentifiers()      { return false; }
    @Override public boolean storesUpperCaseIdentifiers()        { return false; }
    @Override public boolean storesLowerCaseIdentifiers()        { return true; }
    @Override public boolean storesMixedCaseIdentifiers()        { return false; }
    @Override public boolean supportsMixedCaseQuotedIdentifiers() { return true; }
    @Override public boolean storesUpperCaseQuotedIdentifiers()  { return false; }
    @Override public boolean storesLowerCaseQuotedIdentifiers()  { return false; }
    @Override public boolean storesMixedCaseQuotedIdentifiers()  { return true; }
    @Override public String  getIdentifierQuoteString()          { return "\""; }
    @Override public String  getSQLKeywords()                    { return ""; }
    @Override public String  getNumericFunctions()               { return ""; }
    @Override public String  getStringFunctions()                { return ""; }
    @Override public String  getSystemFunctions()                { return ""; }
    @Override public String  getTimeDateFunctions()              { return ""; }
    @Override public String  getSearchStringEscape()             { return "\\"; }
    @Override public String  getExtraNameCharacters()            { return ""; }
    @Override public boolean supportsAlterTableWithAddColumn()   { return true; }
    @Override public boolean supportsAlterTableWithDropColumn()  { return true; }
    @Override public boolean supportsColumnAliasing()            { return true; }
    @Override public boolean nullPlusNonNullIsNull()             { return true; }
    @Override public boolean supportsConvert()                   { return false; }
    @Override public boolean supportsConvert(int from, int to)   { return false; }
    @Override public boolean supportsTableCorrelationNames()     { return true; }
    @Override public boolean supportsDifferentTableCorrelationNames() { return false; }
    @Override public boolean supportsExpressionsInOrderBy()      { return true; }
    @Override public boolean supportsOrderByUnrelated()          { return true; }
    @Override public boolean supportsGroupBy()                   { return true; }
    @Override public boolean supportsGroupByUnrelated()          { return true; }
    @Override public boolean supportsGroupByBeyondSelect()       { return true; }
    @Override public boolean supportsLikeEscapeClause()          { return true; }
    @Override public boolean supportsMultipleResultSets()        { return false; }
    @Override public boolean supportsMultipleTransactions()      { return true; }
    @Override public boolean supportsNonNullableColumns()        { return true; }
    @Override public boolean supportsMinimumSQLGrammar()         { return true; }
    @Override public boolean supportsCoreSQLGrammar()            { return false; }
    @Override public boolean supportsExtendedSQLGrammar()        { return false; }
    @Override public boolean supportsANSI92EntryLevelSQL()       { return true; }
    @Override public boolean supportsANSI92IntermediateSQL()     { return false; }
    @Override public boolean supportsANSI92FullSQL()             { return false; }
    @Override public boolean supportsIntegrityEnhancementFacility() { return false; }
    @Override public boolean supportsOuterJoins()                { return true; }
    @Override public boolean supportsFullOuterJoins()            { return true; }
    @Override public boolean supportsLimitedOuterJoins()         { return true; }
    @Override public String  getSchemaTerm()                     { return "schema"; }
    @Override public String  getProcedureTerm()                  { return "function"; }
    @Override public String  getCatalogTerm()                    { return "database"; }
    @Override public boolean isCatalogAtStart()                  { return true; }
    @Override public String  getCatalogSeparator()               { return "."; }
    @Override public boolean supportsSchemasInDataManipulation() { return true; }
    @Override public boolean supportsSchemasInProcedureCalls()   { return true; }
    @Override public boolean supportsSchemasInTableDefinitions() { return true; }
    @Override public boolean supportsSchemasInIndexDefinitions() { return true; }
    @Override public boolean supportsSchemasInPrivilegeDefinitions() { return true; }
    @Override public boolean supportsCatalogsInDataManipulation() { return false; }
    @Override public boolean supportsCatalogsInProcedureCalls()  { return false; }
    @Override public boolean supportsCatalogsInTableDefinitions() { return false; }
    @Override public boolean supportsCatalogsInIndexDefinitions() { return false; }
    @Override public boolean supportsCatalogsInPrivilegeDefinitions() { return false; }
    @Override public boolean supportsPositionedDelete()          { return false; }
    @Override public boolean supportsPositionedUpdate()          { return false; }
    @Override public boolean supportsSelectForUpdate()           { return false; }
    @Override public boolean supportsStoredProcedures()          { return false; }
    @Override public boolean supportsSubqueriesInComparisons()   { return true; }
    @Override public boolean supportsSubqueriesInExists()        { return true; }
    @Override public boolean supportsSubqueriesInIns()           { return true; }
    @Override public boolean supportsSubqueriesInQuantifieds()   { return true; }
    @Override public boolean supportsCorrelatedSubqueries()      { return true; }
    @Override public boolean supportsUnion()                     { return true; }
    @Override public boolean supportsUnionAll()                  { return true; }
    @Override public boolean supportsOpenCursorsAcrossCommit()   { return false; }
    @Override public boolean supportsOpenCursorsAcrossRollback() { return false; }
    @Override public boolean supportsOpenStatementsAcrossCommit() { return true; }
    @Override public boolean supportsOpenStatementsAcrossRollback() { return true; }
    @Override public int     getMaxBinaryLiteralLength()         { return 0; }
    @Override public int     getMaxCharLiteralLength()           { return 0; }
    @Override public int     getMaxColumnNameLength()            { return 63; }
    @Override public int     getMaxColumnsInGroupBy()            { return 0; }
    @Override public int     getMaxColumnsInIndex()              { return 32; }
    @Override public int     getMaxColumnsInOrderBy()            { return 0; }
    @Override public int     getMaxColumnsInSelect()             { return 0; }
    @Override public int     getMaxColumnsInTable()              { return 1600; }
    @Override public int     getMaxConnections()                 { return 0; }
    @Override public int     getMaxCursorNameLength()            { return 63; }
    @Override public int     getMaxIndexLength()                 { return 0; }
    @Override public int     getMaxSchemaNameLength()            { return 63; }
    @Override public int     getMaxProcedureNameLength()         { return 63; }
    @Override public int     getMaxCatalogNameLength()           { return 63; }
    @Override public int     getMaxRowSize()                     { return 0; }
    @Override public boolean doesMaxRowSizeIncludeBlobs()        { return false; }
    @Override public int     getMaxStatementLength()             { return 0; }
    @Override public int     getMaxStatements()                  { return 0; }
    @Override public int     getMaxTableNameLength()             { return 63; }
    @Override public int     getMaxTablesInSelect()              { return 0; }
    @Override public int     getMaxUserNameLength()              { return 63; }
    @Override public int     getDefaultTransactionIsolation()    { return Connection.TRANSACTION_READ_COMMITTED; }
    @Override public boolean supportsTransactions()              { return true; }
    @Override public boolean supportsTransactionIsolationLevel(int level) { return level == Connection.TRANSACTION_READ_COMMITTED; }
    @Override public boolean supportsDataDefinitionAndDataManipulationTransactions() { return true; }
    @Override public boolean supportsDataManipulationTransactionsOnly() { return false; }
    @Override public boolean dataDefinitionCausesTransactionCommit() { return false; }
    @Override public boolean dataDefinitionIgnoredInTransactions() { return false; }
    @Override public boolean supportsResultSetType(int type)     { return type == ResultSet.TYPE_FORWARD_ONLY; }
    @Override public boolean supportsResultSetConcurrency(int type, int concurrency) { return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY; }
    @Override public boolean ownUpdatesAreVisible(int type)      { return false; }
    @Override public boolean ownDeletesAreVisible(int type)      { return false; }
    @Override public boolean ownInsertsAreVisible(int type)      { return false; }
    @Override public boolean othersUpdatesAreVisible(int type)   { return false; }
    @Override public boolean othersDeletesAreVisible(int type)   { return false; }
    @Override public boolean othersInsertsAreVisible(int type)   { return false; }
    @Override public boolean updatesAreDetected(int type)        { return false; }
    @Override public boolean deletesAreDetected(int type)        { return false; }
    @Override public boolean insertsAreDetected(int type)        { return false; }
    @Override public boolean supportsBatchUpdates()              { return true; }
    @Override public boolean supportsSavepoints()                { return true; }
    @Override public boolean supportsNamedParameters()           { return false; }
    @Override public boolean supportsMultipleOpenResults()       { return false; }
    @Override public boolean supportsGetGeneratedKeys()          { return true; }
    @Override public boolean supportsResultSetHoldability(int h) { return h == ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public int     getResultSetHoldability()           { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public int     getSQLStateType()                   { return DatabaseMetaData.sqlStateSQL; }
    @Override public boolean locatorsUpdateCopy()                { return false; }
    @Override public boolean supportsStatementPooling()          { return false; }
    @Override public boolean supportsStoredFunctionsUsingCallSyntax() { return false; }
    @Override public boolean autoCommitFailureClosesAllResultSets() { return false; }
    @Override public boolean generatedKeyAlwaysReturned()        { return true; }
    @Override public RowIdLifetime getRowIdLifetime()            { return RowIdLifetime.ROWID_UNSUPPORTED; }

    // -------------------------------------------------------------------------
    // Catalog methods — populated from live catalog

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException {
        return empty(v("PROCEDURE_CAT"), v("PROCEDURE_SCHEM"), v("PROCEDURE_NAME"),
                v("reserved1"), v("reserved2"), v("reserved3"),
                v("REMARKS"), s("PROCEDURE_TYPE"), v("SPECIFIC_NAME"));
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) throws SQLException {
        return empty(v("PROCEDURE_CAT"), v("PROCEDURE_SCHEM"), v("PROCEDURE_NAME"),
                v("COLUMN_NAME"), s("COLUMN_TYPE"), i("DATA_TYPE"), v("TYPE_NAME"),
                i("PRECISION"), i("LENGTH"), s("SCALE"), s("RADIX"), s("NULLABLE"),
                v("REMARKS"), v("COLUMN_DEF"), i("SQL_DATA_TYPE"), i("SQL_DATETIME_SUB"),
                i("CHAR_OCTET_LENGTH"), i("ORDINAL_POSITION"), v("IS_NULLABLE"), v("SPECIFIC_NAME"));
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern,
                               String[] types) throws SQLException {
        // If types is non-null and doesn't include TABLE, return empty
        boolean wantTable = types == null || Arrays.asList(types).contains("TABLE");
        if (!wantTable) {
            return empty(v("TABLE_CAT"), v("TABLE_SCHEM"), v("TABLE_NAME"), v("TABLE_TYPE"),
                    v("REMARKS"), v("TYPE_CAT"), v("TYPE_SCHEM"), v("TYPE_NAME"),
                    v("SELF_REFERENCING_COL_NAME"), v("REF_GENERATION"));
        }

        List<ColumnMeta> cols = List.of(
                v("TABLE_CAT"), v("TABLE_SCHEM"), v("TABLE_NAME"), v("TABLE_TYPE"),
                v("REMARKS"), v("TYPE_CAT"), v("TYPE_SCHEM"), v("TYPE_NAME"),
                v("SELF_REFERENCING_COL_NAME"), v("REF_GENERATION"));
        List<Object[]> rows = new ArrayList<>();

        for (Map.Entry<String, Schema> se : catalog().allSchemas().entrySet()) {
            String schemaName = se.getKey();
            if (!isUserSchema(schemaName)) continue;
            if (!matches(schemaName, schemaPattern)) continue;

            for (String tableName : se.getValue().tables().keySet()) {
                if (!matches(tableName, tableNamePattern)) continue;
                rows.add(new Object[]{
                        dbName(),     // TABLE_CAT
                        schemaName,   // TABLE_SCHEM
                        tableName,    // TABLE_NAME
                        "TABLE",      // TABLE_TYPE
                        null,         // REMARKS
                        null, null, null, null, null
                });
            }
        }
        rows.sort(Comparator.<Object[], String>comparing(r -> (String) r[1])
                .thenComparing(r -> (String) r[2]));
        return new PgJavaResultSet(new QueryResult(-1, cols, rows));
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        return getSchemas(null, null);
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        List<ColumnMeta> cols = List.of(v("TABLE_SCHEM"), v("TABLE_CATALOG"));
        List<Object[]> rows = new ArrayList<>();
        for (String name : catalog().allSchemas().keySet()) {
            if (!isUserSchema(name)) continue;
            if (!matches(name, schemaPattern)) continue;
            rows.add(new Object[]{name, null});
        }
        rows.sort(Comparator.comparing(r -> (String) r[0]));
        return new PgJavaResultSet(new QueryResult(-1, cols, rows));
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        List<ColumnMeta> cols = List.of(v("TABLE_CAT"));
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{dbName()});
        return new PgJavaResultSet(new QueryResult(-1, cols, rows));
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        List<ColumnMeta> cols = List.of(v("TABLE_TYPE"));
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"TABLE"});
        return new PgJavaResultSet(new QueryResult(-1, cols, rows));
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern,
                                String tableNamePattern, String columnNamePattern)
            throws SQLException {
        List<ColumnMeta> cols = List.of(
                v("TABLE_CAT"), v("TABLE_SCHEM"), v("TABLE_NAME"), v("COLUMN_NAME"),
                i("DATA_TYPE"), v("TYPE_NAME"), i("COLUMN_SIZE"), i("BUFFER_LENGTH"),
                i("DECIMAL_DIGITS"), i("NUM_PREC_RADIX"), i("NULLABLE"), v("REMARKS"),
                v("COLUMN_DEF"), i("SQL_DATA_TYPE"), i("SQL_DATETIME_SUB"),
                i("CHAR_OCTET_LENGTH"), i("ORDINAL_POSITION"), v("IS_NULLABLE"),
                v("SCOPE_CATALOG"), v("SCOPE_SCHEMA"), v("SCOPE_TABLE"),
                s("SOURCE_DATA_TYPE"), v("IS_AUTOINCREMENT"), v("IS_GENERATEDCOLUMN"));

        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<String, Schema> se : catalog().allSchemas().entrySet()) {
            String schemaName = se.getKey();
            if (!isUserSchema(schemaName)) continue;
            if (!matches(schemaName, schemaPattern)) continue;

            for (Map.Entry<String, TableDef> te : se.getValue().tables().entrySet()) {
                String tableName = te.getKey();
                if (!matches(tableName, tableNamePattern)) continue;

                for (ColumnDef col : te.getValue().columns()) {
                    if (!matches(col.name(), columnNamePattern)) continue;
                    int oid    = col.type().oid();
                    int typmod = col.typmod();
                    int jdbcType  = pgOidToJdbcType(oid);
                    int colSize   = columnSize(oid, typmod);
                    int decDigits = decimalDigits(oid, typmod);
                    int nullable  = col.nullable()
                            ? ResultSetMetaData.columnNullable
                            : ResultSetMetaData.columnNoNulls;
                    boolean isGenerated = col.generated() != org.pgjava.catalog.GeneratedKind.NONE;
                    rows.add(new Object[]{
                            null,                       // TABLE_CAT
                            schemaName,                 // TABLE_SCHEM
                            tableName,                  // TABLE_NAME
                            col.name(),                 // COLUMN_NAME
                            jdbcType,                   // DATA_TYPE
                            col.type().name(),          // TYPE_NAME
                            colSize,                    // COLUMN_SIZE
                            null,                       // BUFFER_LENGTH
                            decDigits,                  // DECIMAL_DIGITS
                            10,                         // NUM_PREC_RADIX
                            nullable,                   // NULLABLE
                            null,                       // REMARKS
                            null,                       // COLUMN_DEF (not serialized yet)
                            0,                          // SQL_DATA_TYPE
                            0,                          // SQL_DATETIME_SUB
                            isCharType(oid) ? colSize : null, // CHAR_OCTET_LENGTH
                            col.attnum(),               // ORDINAL_POSITION
                            col.nullable() ? "YES" : "NO",  // IS_NULLABLE
                            null, null, null,            // SCOPE_*
                            null,                       // SOURCE_DATA_TYPE
                            isGenerated ? "YES" : "NO", // IS_AUTOINCREMENT
                            isGenerated ? "YES" : "NO"  // IS_GENERATEDCOLUMN
                    });
                }
            }
        }
        // Sort by TABLE_SCHEM, TABLE_NAME, ORDINAL_POSITION
        rows.sort(Comparator.<Object[], String>comparing(r -> (String) r[1])
                .thenComparing(r -> (String) r[2])
                .thenComparingInt(r -> (Integer) r[16]));
        return new PgJavaResultSet(new QueryResult(-1, cols, rows));
    }

    private static boolean isCharType(int oid) {
        return oid == PgOid.TEXT || oid == PgOid.VARCHAR || oid == PgOid.BPCHAR
                || oid == PgOid.NAME || oid == PgOid.BYTEA;
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table,
                                         String columnNamePattern) throws SQLException {
        return empty(v("TABLE_CAT"), v("TABLE_SCHEM"), v("TABLE_NAME"), v("COLUMN_NAME"),
                v("GRANTOR"), v("GRANTEE"), v("PRIVILEGE"), v("IS_GRANTABLE"));
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern,
                                        String tableNamePattern) throws SQLException {
        return empty(v("TABLE_CAT"), v("TABLE_SCHEM"), v("TABLE_NAME"),
                v("GRANTOR"), v("GRANTEE"), v("PRIVILEGE"), v("IS_GRANTABLE"));
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table,
                                          int scope, boolean nullable) throws SQLException {
        return empty(s("SCOPE"), v("COLUMN_NAME"), i("DATA_TYPE"), v("TYPE_NAME"),
                i("COLUMN_SIZE"), i("BUFFER_LENGTH"), s("DECIMAL_DIGITS"), s("PSEUDO_COLUMN"));
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table)
            throws SQLException {
        return empty(s("SCOPE"), v("COLUMN_NAME"), i("DATA_TYPE"), v("TYPE_NAME"),
                i("COLUMN_SIZE"), i("BUFFER_LENGTH"), s("DECIMAL_DIGITS"), s("PSEUDO_COLUMN"));
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table)
            throws SQLException {
        List<ColumnMeta> cols = List.of(
                v("TABLE_CAT"), v("TABLE_SCHEM"), v("TABLE_NAME"),
                v("COLUMN_NAME"), s("KEY_SEQ"), v("PK_NAME"));
        List<Object[]> rows = new ArrayList<>();

        for (Map.Entry<String, Schema> se : catalog().allSchemas().entrySet()) {
            String schemaName = se.getKey();
            if (!isUserSchema(schemaName)) continue;
            if (schema != null && !schemaName.equalsIgnoreCase(schema)) continue;

            for (Map.Entry<String, TableDef> te : se.getValue().tables().entrySet()) {
                String tableName = te.getKey();
                if (table != null && !tableName.equalsIgnoreCase(table)) continue;

                Constraint.PrimaryKey pk = te.getValue().primaryKey();
                if (pk == null) continue;
                for (int i = 0; i < pk.columns().size(); i++) {
                    rows.add(new Object[]{
                            null, schemaName, tableName,
                            pk.columns().get(i), (short)(i + 1), pk.name()
                    });
                }
            }
        }
        return new PgJavaResultSet(new QueryResult(-1, cols, rows));
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table)
            throws SQLException {
        return foreignKeys(schema, table, null, null);
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table)
            throws SQLException {
        return foreignKeys(null, null, schema, table);
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema,
                                       String parentTable, String foreignCatalog,
                                       String foreignSchema, String foreignTable)
            throws SQLException {
        return foreignKeys(foreignSchema, foreignTable, parentSchema, parentTable);
    }

    /**
     * Build the foreign-key result set.
     *
     * @param fkSchema  schema of the FK table (null = all)
     * @param fkTable   name of the FK table (null = all)
     * @param pkSchema  schema of the PK/referenced table (null = all)
     * @param pkTable   name of the PK/referenced table (null = all)
     */
    private ResultSet foreignKeys(String fkSchema, String fkTable,
                                   String pkSchema, String pkTable) {
        List<ColumnMeta> cols = List.of(
                v("PKTABLE_CAT"), v("PKTABLE_SCHEM"), v("PKTABLE_NAME"), v("PKCOLUMN_NAME"),
                v("FKTABLE_CAT"), v("FKTABLE_SCHEM"), v("FKTABLE_NAME"), v("FKCOLUMN_NAME"),
                s("KEY_SEQ"), s("UPDATE_RULE"), s("DELETE_RULE"),
                v("FK_NAME"), v("PK_NAME"), s("DEFERRABILITY"));
        List<Object[]> rows = new ArrayList<>();

        for (Map.Entry<String, Schema> se : catalog().allSchemas().entrySet()) {
            String schemaName = se.getKey();
            if (!isUserSchema(schemaName)) continue;
            if (fkSchema != null && !schemaName.equalsIgnoreCase(fkSchema)) continue;

            for (Map.Entry<String, TableDef> te : se.getValue().tables().entrySet()) {
                String tableName = te.getKey();
                if (fkTable != null && !tableName.equalsIgnoreCase(fkTable)) continue;

                for (Constraint c : te.getValue().constraints()) {
                    if (!(c instanceof Constraint.ForeignKey fk)) continue;
                    if (pkSchema != null && !fk.refSchema().equalsIgnoreCase(pkSchema)) continue;
                    if (pkTable != null && !fk.refTable().equalsIgnoreCase(pkTable)) continue;

                    // Find PK name for the referenced table
                    String pkName = null;
                    Schema refSchema = catalog().getSchemaOrNull(fk.refSchema());
                    if (refSchema != null) {
                        TableDef refDef = refSchema.table(fk.refTable());
                        if (refDef != null && refDef.primaryKey() != null) {
                            pkName = refDef.primaryKey().name();
                        }
                    }

                    for (int i = 0; i < fk.columns().size(); i++) {
                        rows.add(new Object[]{
                                null,              // PKTABLE_CAT
                                fk.refSchema(),    // PKTABLE_SCHEM
                                fk.refTable(),     // PKTABLE_NAME
                                i < fk.refColumns().size() ? fk.refColumns().get(i) : null, // PKCOLUMN_NAME
                                null,              // FKTABLE_CAT
                                schemaName,        // FKTABLE_SCHEM
                                tableName,         // FKTABLE_NAME
                                fk.columns().get(i), // FKCOLUMN_NAME
                                (short)(i + 1),    // KEY_SEQ
                                fkActionRule(fk.onUpdate()), // UPDATE_RULE
                                fkActionRule(fk.onDelete()), // DELETE_RULE
                                fk.name(),         // FK_NAME
                                pkName,            // PK_NAME
                                (short) DatabaseMetaData.importedKeyNotDeferrable // DEFERRABILITY
                        });
                    }
                }
            }
        }
        return new PgJavaResultSet(new QueryResult(-1, cols, rows));
    }

    private static short fkActionRule(FkAction action) {
        if (action == null) return (short) DatabaseMetaData.importedKeyNoAction;
        return (short) switch (action) {
            case CASCADE    -> DatabaseMetaData.importedKeyCascade;
            case SET_NULL   -> DatabaseMetaData.importedKeySetNull;
            case SET_DEFAULT-> DatabaseMetaData.importedKeySetDefault;
            case RESTRICT   -> DatabaseMetaData.importedKeyRestrict;
            default         -> DatabaseMetaData.importedKeyNoAction;
        };
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        List<ColumnMeta> cols = List.of(
                v("TYPE_NAME"), i("DATA_TYPE"), i("PRECISION"), v("LITERAL_PREFIX"),
                v("LITERAL_SUFFIX"), v("CREATE_PARAMS"), s("NULLABLE"), b("CASE_SENSITIVE"),
                s("SEARCHABLE"), b("UNSIGNED_ATTRIBUTE"), b("FIXED_PREC_SCALE"),
                b("AUTO_INCREMENT"), v("LOCAL_TYPE_NAME"), s("MINIMUM_SCALE"),
                s("MAXIMUM_SCALE"), i("SQL_DATA_TYPE"), i("SQL_DATETIME_SUB"),
                i("NUM_PREC_RADIX"));
        short nullable = (short) DatabaseMetaData.typeNullable;
        short searchable = (short) DatabaseMetaData.typeSearchable;
        List<Object[]> rows = new ArrayList<>();
        // boolean
        rows.add(typeRow("bool",        Types.BIT,       1,  null, null, nullable, false, searchable, false, false, false, "bool",    (short)0, (short)0, 0));
        // integers
        rows.add(typeRow("int2",        Types.SMALLINT,  5,  null, null, nullable, false, searchable, false, false, true,  "int2",    (short)0, (short)0, 10));
        rows.add(typeRow("int4",        Types.INTEGER,   10, null, null, nullable, false, searchable, false, false, true,  "int4",    (short)0, (short)0, 10));
        rows.add(typeRow("int8",        Types.BIGINT,    19, null, null, nullable, false, searchable, false, false, true,  "int8",    (short)0, (short)0, 10));
        // floats
        rows.add(typeRow("float4",      Types.REAL,      8,  null, null, nullable, false, searchable, false, false, false, "float4",  (short)0, (short)0, 10));
        rows.add(typeRow("float8",      Types.DOUBLE,    17, null, null, nullable, false, searchable, false, false, false, "float8",  (short)0, (short)0, 10));
        // numeric
        rows.add(typeRow("numeric",     Types.NUMERIC,   1000, null, null, nullable, false, searchable, false, true, false, "numeric", (short)0, (short)1000, 10));
        // strings
        rows.add(typeRow("text",        Types.VARCHAR,   Integer.MAX_VALUE, "'", "'", nullable, true, searchable, false, false, false, "text",    (short)0, (short)0, 0));
        rows.add(typeRow("varchar",     Types.VARCHAR,   Integer.MAX_VALUE, "'", "'", nullable, true, searchable, false, false, false, "varchar", (short)0, (short)0, 0));
        rows.add(typeRow("bpchar",      Types.CHAR,      Integer.MAX_VALUE, "'", "'", nullable, true, searchable, false, false, false, "bpchar",  (short)0, (short)0, 0));
        rows.add(typeRow("name",        Types.VARCHAR,   63,  "'", "'", nullable, true, searchable, false, false, false, "name",    (short)0, (short)0, 0));
        // bytea
        rows.add(typeRow("bytea",       Types.BINARY,    Integer.MAX_VALUE, "'", "'", nullable, false, searchable, false, false, false, "bytea",   (short)0, (short)0, 0));
        // date/time
        rows.add(typeRow("date",        Types.DATE,      13, "'", "'", nullable, false, searchable, false, false, false, "date",        (short)0, (short)0, 0));
        rows.add(typeRow("time",        Types.TIME,      15, "'", "'", nullable, false, searchable, false, false, false, "time",        (short)0, (short)0, 0));
        rows.add(typeRow("timetz",      Types.TIME_WITH_TIMEZONE, 21, "'", "'", nullable, false, searchable, false, false, false, "timetz", (short)0, (short)0, 0));
        rows.add(typeRow("timestamp",   Types.TIMESTAMP, 29, "'", "'", nullable, false, searchable, false, false, false, "timestamp",   (short)0, (short)0, 0));
        rows.add(typeRow("timestamptz", Types.TIMESTAMP_WITH_TIMEZONE, 35, "'", "'", nullable, false, searchable, false, false, false, "timestamptz", (short)0, (short)0, 0));
        rows.add(typeRow("interval",    Types.OTHER,     49, "'", "'", nullable, false, searchable, false, false, false, "interval",    (short)0, (short)0, 0));
        // uuid
        rows.add(typeRow("uuid",        Types.OTHER,     36, "'", "'", nullable, false, searchable, false, false, false, "uuid",    (short)0, (short)0, 0));
        // json
        rows.add(typeRow("json",        Types.OTHER,     Integer.MAX_VALUE, "'", "'", nullable, false, searchable, false, false, false, "json",  (short)0, (short)0, 0));
        rows.add(typeRow("jsonb",       Types.OTHER,     Integer.MAX_VALUE, "'", "'", nullable, false, searchable, false, false, false, "jsonb", (short)0, (short)0, 0));
        // oid
        rows.add(typeRow("oid",         Types.BIGINT,    10, null, null, nullable, false, searchable, true, false, false, "oid",     (short)0, (short)0, 10));
        return new PgJavaResultSet(new QueryResult(-1, cols, rows));
    }

    private static Object[] typeRow(String name, int dataType, int precision,
            String prefix, String suffix, short nullable, boolean caseSensitive,
            short searchable, boolean unsigned, boolean fixedPrec, boolean autoIncrement,
            String localName, short minScale, short maxScale, int radix) {
        return new Object[]{name, dataType, precision, prefix, suffix, null,
                nullable, caseSensitive, searchable, unsigned, fixedPrec,
                autoIncrement, localName, minScale, maxScale, 0, 0, radix};
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table,
                                  boolean unique, boolean approximate) throws SQLException {
        List<ColumnMeta> cols = List.of(
                v("TABLE_CAT"), v("TABLE_SCHEM"), v("TABLE_NAME"),
                b("NON_UNIQUE"), v("INDEX_QUALIFIER"), v("INDEX_NAME"),
                s("TYPE"), s("ORDINAL_POSITION"), v("COLUMN_NAME"),
                v("ASC_OR_DESC"), i("CARDINALITY"), i("PAGES"), v("FILTER_CONDITION"));

        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<String, Schema> se : catalog().allSchemas().entrySet()) {
            String schemaName = se.getKey();
            if (!isUserSchema(schemaName)) continue;
            if (schema != null && !schemaName.equalsIgnoreCase(schema)) continue;

            for (Map.Entry<String, IndexDef> ie : se.getValue().indexes().entrySet()) {
                IndexDef idx = ie.getValue();
                if (table != null && !idx.tableName().equalsIgnoreCase(table)) continue;
                if (unique && !idx.unique()) continue;

                for (int i = 0; i < idx.columns().size(); i++) {
                    IndexColumn ic = idx.columns().get(i);
                    rows.add(new Object[]{
                            null,                                    // TABLE_CAT
                            schemaName,                              // TABLE_SCHEM
                            idx.tableName(),                         // TABLE_NAME
                            !idx.unique(),                           // NON_UNIQUE
                            null,                                    // INDEX_QUALIFIER
                            idx.name(),                              // INDEX_NAME
                            (short) DatabaseMetaData.tableIndexOther, // TYPE
                            (short)(i + 1),                          // ORDINAL_POSITION
                            ic.column(),                             // COLUMN_NAME
                            ic.ascending() ? "A" : "D",             // ASC_OR_DESC
                            0,                                       // CARDINALITY
                            0,                                       // PAGES
                            null                                     // FILTER_CONDITION
                    });
                }
            }
        }
        // Sort by NON_UNIQUE, INDEX_NAME, ORDINAL_POSITION
        rows.sort(Comparator.<Object[], Boolean>comparing(r -> (Boolean) r[3])
                .thenComparing(r -> (String) r[5])
                .thenComparingInt(r -> (Short) r[7]));
        return new PgJavaResultSet(new QueryResult(-1, cols, rows));
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern,
                             int[] types) throws SQLException {
        return empty(v("TYPE_CAT"), v("TYPE_SCHEM"), v("TYPE_NAME"), v("CLASS_NAME"),
                i("DATA_TYPE"), v("REMARKS"), s("BASE_TYPE"));
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern,
                                   String typeNamePattern) throws SQLException {
        return empty(v("TYPE_CAT"), v("TYPE_SCHEM"), v("TYPE_NAME"),
                v("SUPERTYPE_CAT"), v("SUPERTYPE_SCHEM"), v("SUPERTYPE_NAME"));
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern,
                                    String tableNamePattern) throws SQLException {
        return empty(v("TABLE_CAT"), v("TABLE_SCHEM"), v("TABLE_NAME"), v("SUPERTABLE_NAME"));
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern,
                                   String typeNamePattern, String attributeNamePattern)
            throws SQLException {
        return empty(v("TYPE_CAT"), v("TYPE_SCHEM"), v("TYPE_NAME"), v("ATTR_NAME"),
                i("DATA_TYPE"), v("ATTR_TYPE_NAME"), i("ATTR_SIZE"), i("DECIMAL_DIGITS"),
                i("NUM_PREC_RADIX"), i("NULLABLE"), v("REMARKS"), v("ATTR_DEF"),
                i("SQL_DATA_TYPE"), i("SQL_DATETIME_SUB"), i("CHAR_OCTET_LENGTH"),
                i("ORDINAL_POSITION"), v("IS_NULLABLE"), v("SCOPE_CATALOG"),
                v("SCOPE_SCHEMA"), v("SCOPE_TABLE"), s("SOURCE_DATA_TYPE"));
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        return empty(v("NAME"), i("MAX_LEN"), v("DEFAULT_VALUE"), v("DESCRIPTION"));
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern,
                                  String functionNamePattern) throws SQLException {
        return empty(v("FUNCTION_CAT"), v("FUNCTION_SCHEM"), v("FUNCTION_NAME"),
                v("REMARKS"), s("FUNCTION_TYPE"), v("SPECIFIC_NAME"));
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern,
                                        String functionNamePattern, String columnNamePattern)
            throws SQLException {
        return empty(v("FUNCTION_CAT"), v("FUNCTION_SCHEM"), v("FUNCTION_NAME"),
                v("COLUMN_NAME"), s("COLUMN_TYPE"), i("DATA_TYPE"), v("TYPE_NAME"),
                i("PRECISION"), i("LENGTH"), s("SCALE"), s("RADIX"), s("NULLABLE"),
                v("REMARKS"), i("CHAR_OCTET_LENGTH"), i("ORDINAL_POSITION"),
                v("IS_NULLABLE"), v("SPECIFIC_NAME"));
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern,
                                      String tableNamePattern, String columnNamePattern)
            throws SQLException {
        return empty(v("TABLE_CAT"), v("TABLE_SCHEM"), v("TABLE_NAME"), v("COLUMN_NAME"),
                i("DATA_TYPE"), i("COLUMN_SIZE"), i("DECIMAL_DIGITS"), i("NUM_PREC_RADIX"),
                v("COLUMN_USAGE"), v("REMARKS"), i("CHAR_OCTET_LENGTH"), v("IS_NULLABLE"));
    }

    // -------------------------------------------------------------------------
    // Connection reference

    @Override public Connection getConnection() throws SQLException { return conn; }

    // -------------------------------------------------------------------------
    // Wrapper

    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface);
    }
    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}
