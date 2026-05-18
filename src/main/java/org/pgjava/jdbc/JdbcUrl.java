package org.pgjava.jdbc;

/**
 * Parses {@code jdbc:pgjava:...} URLs.
 *
 * <p>Supported forms:
 * <ul>
 *   <li>{@code jdbc:pgjava:mem:<dbname>} — legacy in-memory, routes to default cluster</li>
 *   <li>{@code jdbc:pgjava:file:<path>}  — legacy file-backed (not yet wired)</li>
 *   <li>{@code jdbc:pgjava://<cluster>/<dbname>} — named cluster</li>
 *   <li>{@code jdbc:pgjava://<cluster>/<dbname>?schema=<schema>} — cluster + schema override</li>
 * </ul>
 *
 * <p>The {@code clusterName} field is null for legacy URLs; non-null for cluster-scoped URLs.
 * The {@code schema} field is null unless a {@code ?schema=} parameter is present.
 */
public record JdbcUrl(Mode mode, String clusterName, String dbName, String schema) {

    public enum Mode { MEM, FILE, CLUSTER }

    static final String PREFIX = "jdbc:pgjava:";

    /** Returns true if {@code url} is accepted by this driver. */
    public static boolean accepts(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    /** True if this URL references a named cluster (vs the legacy default cluster). */
    public boolean isClusterScoped() { return clusterName != null; }

    /**
     * Parse a pgjava JDBC URL.
     *
     * @throws IllegalArgumentException if the URL is malformed
     */
    public static JdbcUrl parse(String url) {
        if (!accepts(url)) throw new IllegalArgumentException("Not a pgjava URL: " + url);

        String rest = url.substring(PREFIX.length()); // after "jdbc:pgjava:"

        // ── Cluster-scoped: jdbc:pgjava://<cluster>/<db>[?schema=...] ────────
        if (rest.startsWith("//")) {
            String authority = rest.substring(2); // "<cluster>/<db>[?...]"
            String schema    = null;
            int q = authority.indexOf('?');
            if (q >= 0) {
                schema    = parseParam(authority.substring(q + 1), "schema");
                authority = authority.substring(0, q);
            }
            int slash = authority.indexOf('/');
            if (slash < 0)
                throw new IllegalArgumentException("Missing database in pgjava URL: " + url);
            String cluster = authority.substring(0, slash);
            String db      = authority.substring(slash + 1);
            if (cluster.isEmpty()) throw new IllegalArgumentException("Empty cluster name: " + url);
            if (db.isEmpty())      db = "default";
            requireSafeIdentifier(cluster, "cluster name");
            requireSafeIdentifier(db,      "database name");
            if (schema != null) requireSafeIdentifier(schema, "schema");
            return new JdbcUrl(Mode.CLUSTER, cluster, db, schema);
        }

        // ── Legacy: jdbc:pgjava:mem:<db> or jdbc:pgjava:file:<path> ──────────
        // Query params parsed but ignored (wal=true was never wired; kept for compat)
        int q = rest.indexOf('?');
        if (q >= 0) rest = rest.substring(0, q);

        if (rest.startsWith("mem:")) {
            String db = rest.substring("mem:".length());
            if (db.isEmpty()) db = "default";
            return new JdbcUrl(Mode.MEM, null, db, null);
        } else if (rest.startsWith("file:")) {
            String path = rest.substring("file:".length());
            return new JdbcUrl(Mode.FILE, null, path, null);
        } else {
            throw new IllegalArgumentException("Unknown pgjava URL scheme: " + url);
        }
    }

    private static String parseParam(String query, String key) {
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equalsIgnoreCase(key))
                return kv.substring(eq + 1);
        }
        return null;
    }

    /**
     * Validate that {@code name} is a safe PostgreSQL identifier: starts with a letter
     * or underscore, contains only letters, digits, underscores, or dollar signs.
     *
     * <p>Called on any identifier extracted from a JDBC URL before it is used in
     * session-variable assignments, preventing injection via crafted URLs.
     *
     * @throws IllegalArgumentException if {@code name} contains unsafe characters
     */
    static String requireSafeIdentifier(String name, String label) {
        if (name == null || name.isEmpty())
            throw new IllegalArgumentException(label + " must not be empty");
        if (!name.matches("[A-Za-z_][A-Za-z0-9_$]*"))
            throw new IllegalArgumentException(
                    label + " \"" + name + "\" is not a valid identifier");
        return name;
    }
}
