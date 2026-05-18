package org.pgjava.catalog;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.ArrayList;

/**
 * Registry of scalar functions and aggregate functions.
 *
 * <p>Phase 5a: infrastructure only — lookup methods are present but the built-in
 * function implementations are registered in Phase 5b.
 *
 * <p>Overload resolution is intentionally simple in Phase 5a: exact name + arity
 * match.  Full coercion-based overload resolution is implemented in Phase 8
 * (expression evaluator).
 */
public final class FunctionRegistry {

    private final List<FunctionDef>   scalars    = new CopyOnWriteArrayList<>();
    private final List<AggregateDef>  aggregates = new CopyOnWriteArrayList<>();
    private final List<SrfDef>        srfs       = new CopyOnWriteArrayList<>();

    // -------------------------------------------------------------------------
    // Registration

    public void register(FunctionDef fn) {
        scalars.add(fn);
    }

    public void registerAggregate(AggregateDef agg) {
        aggregates.add(agg);
    }

    public void registerSrf(SrfDef srf) {
        srfs.add(srf);
    }

    /** Remove a scalar function by name and exact argument type list. */
    public void unregister(String name, List<org.pgjava.types.PgType> argTypes) {
        String lc = name.toLowerCase();
        scalars.removeIf(fn ->
                fn.name().equalsIgnoreCase(lc) && fn.argTypes().equals(argTypes));
    }

    /**
     * Remove a scalar function by name and exact argument type list.
     * Returns true if a matching function was removed.
     */
    public boolean unregisterExact(String name, List<org.pgjava.types.PgType> argTypes) {
        String lc = name.toLowerCase();
        return scalars.removeIf(fn ->
                fn.name().equalsIgnoreCase(lc) && fn.argTypes().equals(argTypes));
    }

    /**
     * Remove all overloads of a scalar function by name.
     * Returns the number of overloads removed.
     */
    public int unregisterAll(String name) {
        String lc = name.toLowerCase();
        int before = scalars.size();
        scalars.removeIf(fn -> fn.name().equalsIgnoreCase(lc));
        return before - scalars.size();
    }

    // -------------------------------------------------------------------------
    // Lookup

    /**
     * Find a scalar function by name and exact argument count.
     * Returns the first match, or {@code null} if none found.
     */
    public FunctionDef findScalar(String name, int argCount) {
        String lc = name.toLowerCase();
        for (FunctionDef fn : scalars) {
            if (fn.matches(lc, argCount)) return fn;
        }
        return null;
    }

    /**
     * Type-aware overload resolution: finds the best scalar function for the
     * given name and actual argument values.  If only one overload matches by
     * arity, that is returned immediately.  When multiple overloads share the
     * same name+arity, the one whose declared argTypes best match the runtime
     * Java types of the arguments wins (one point per matched position).
     */
    public FunctionDef findScalarForArgs(String name, Object[] args) {
        String lc = name.toLowerCase();
        int argCount = args.length;
        List<FunctionDef> candidates = new ArrayList<>();
        for (FunctionDef fn : scalars) {
            if (fn.matches(lc, argCount)) candidates.add(fn);
        }
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        // Score each candidate by type compatibility
        FunctionDef best = null;
        int bestScore = -1;
        for (FunctionDef fn : candidates) {
            int score = 0;
            List<org.pgjava.types.PgType> declared = fn.argTypes();
            for (int i = 0; i < args.length && i < declared.size(); i++) {
                Class<?> javaClass = declared.get(i).javaClass();
                if (args[i] != null && javaClass != null && javaClass.isInstance(args[i])) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = fn;
            }
        }
        return best;
    }

    /**
     * Find all overloads of a scalar function by name.
     */
    public List<FunctionDef> findScalarOverloads(String name) {
        String lc = name.toLowerCase();
        List<FunctionDef> result = new ArrayList<>();
        for (FunctionDef fn : scalars) {
            if (fn.name().equalsIgnoreCase(lc)) result.add(fn);
        }
        return result;
    }

    /**
     * Find an aggregate function by name (first match by name, any arity).
     */
    public AggregateDef findAggregate(String name) {
        String lc = name.toLowerCase();
        for (AggregateDef agg : aggregates) {
            if (agg.name().equalsIgnoreCase(lc)) return agg;
        }
        return null;
    }

    /**
     * Find a set-returning function by name and exact argument count.
     * Returns the first match, or {@code null} if none found.
     */
    public SrfDef findSrf(String name, int argCount) {
        String lc = name.toLowerCase();
        for (SrfDef srf : srfs) {
            if (srf.matches(lc, argCount)) return srf;
        }
        return null;
    }

    public List<FunctionDef>  allScalars()    { return Collections.unmodifiableList(scalars); }
    public List<AggregateDef> allAggregates() { return Collections.unmodifiableList(aggregates); }
    public List<SrfDef>       allSrfs()       { return Collections.unmodifiableList(srfs); }
}
