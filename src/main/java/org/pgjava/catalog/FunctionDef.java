package org.pgjava.catalog;

import org.pgjava.sql.ast.Expr;
import org.pgjava.types.PgType;

import java.sql.SQLException;
import java.util.List;

/**
 * Metadata + implementation for a scalar or set-returning function.
 *
 * <p>Phase 5a: infrastructure only.  Built-in function implementations
 * are registered in Phase 5b.
 */
public record FunctionDef(
        long         oid,
        String       name,
        String       schemaName,
        List<PgType> argTypes,
        PgType       returnType,
        boolean      strict,       // true = return NULL if any arg is NULL (proisstrict)
        boolean      variadic,     // true = last arg is variadic
        ScalarImpl   impl,
        String       source,       // function body text (prosrc) — null for built-ins
        List<String> argNames,     // parameter names — null for built-ins
        List<Expr>   argDefaults   // default expressions — null/empty for no defaults
) {
    /** Backwards-compatible constructor for built-in functions (no source/argNames/defaults). */
    public FunctionDef(long oid, String name, String schemaName, List<PgType> argTypes,
                       PgType returnType, boolean strict, boolean variadic, ScalarImpl impl) {
        this(oid, name, schemaName, argTypes, returnType, strict, variadic, impl, null, null, null);
    }
    /** Callable implementation for a scalar function. */
    @FunctionalInterface
    public interface ScalarImpl {
        Object invoke(Object[] args) throws SQLException;
    }

    /** Convenience: look up by (name, argCount) for overload resolution in Phase 8. */
    public boolean matches(String name, int argCount) {
        if (!this.name().equalsIgnoreCase(name)) return false;
        if (variadic) return true;
        if (argTypes.size() == argCount) return true;
        // Allow fewer args if defaults cover the gap
        if (argDefaults != null && argCount < argTypes.size()) {
            int nDefaults = argDefaults.size();
            int minArgs = argTypes.size() - nDefaults;
            return argCount >= minArgs;
        }
        return false;
    }

    /** Number of required (non-defaulted) parameters. */
    public int minArgs() {
        if (argDefaults == null || argDefaults.isEmpty()) return argTypes.size();
        return argTypes.size() - argDefaults.size();
    }
}
