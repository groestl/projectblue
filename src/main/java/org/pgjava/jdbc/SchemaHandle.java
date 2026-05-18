package org.pgjava.jdbc;

import org.pgjava.engine.Database;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * An isolated schema within a {@link org.pgjava.jdbc.PgJavaCluster} database.
 *
 * <p>Returned by {@link PgJavaCluster#allocateSchema(String)}. Provides:
 * <ul>
 *   <li>A {@link DataSource} whose connections automatically scope to this schema
 *       via {@code SET search_path}.</li>
 *   <li>A JDBC URL suitable for passing to HikariCP or Spring's datasource config.</li>
 *   <li>A {@link #drop()} method that issues {@code DROP SCHEMA ... CASCADE} and
 *       releases all objects — call in {@code @AfterAll} or {@code try}-with-resources.</li>
 * </ul>
 *
 * <p>The underlying connection pool (if any) is the caller's responsibility.
 * {@link #dataSource()} returns a non-pooling source; wrap it in HikariCP if needed.
 */
public final class SchemaHandle implements AutoCloseable {

    private final PgJavaCluster cluster;
    private final Database      db;
    private final String        dbName;
    private final String        schemaName;
    private final DataSource    dataSource;

    SchemaHandle(PgJavaCluster cluster, Database db, String dbName, String schemaName) {
        this.cluster    = cluster;
        this.db         = db;
        this.dbName     = dbName;
        this.schemaName = schemaName;
        String url      = "jdbc:pgjava://" + cluster.name() + "/" + dbName
                          + "?schema=" + schemaName;
        this.dataSource = new PgJavaDataSource(db, url, schemaName + ", public");
    }

    /** The generated schema name, e.g. {@code test_3f8a1c2b}. */
    public String schemaName() { return schemaName; }

    /**
     * Non-pooling {@link DataSource} whose connections are scoped to this schema.
     * Every {@code getConnection()} call executes {@code SET search_path = <schema>, public}.
     * Wrap in HikariCP if connection reuse is required.
     */
    public DataSource dataSource() { return dataSource; }

    /**
     * JDBC URL that encodes the cluster, database, and schema.
     * Suitable for {@code @DynamicPropertySource} or HikariCP {@code jdbcUrl}.
     *
     * <p>Format: {@code jdbc:pgjava://<cluster>/<db>?schema=<schema>}
     */
    public String jdbcUrl() {
        return "jdbc:pgjava://" + cluster.name() + "/" + dbName
               + "?schema=" + schemaName;
    }

    /**
     * Drop this schema and all its objects ({@code DROP SCHEMA ... CASCADE}).
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    public void drop() {
        try {
            db.catalog().dropSchema(schemaName, true, true);
        } catch (SQLException ignored) {
            // already dropped or never existed
        }
    }

    /** Alias for {@link #drop()} — supports try-with-resources. */
    @Override
    public void close() {
        drop();
    }
}
