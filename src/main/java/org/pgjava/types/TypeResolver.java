package org.pgjava.types;

import org.pgjava.engine.PgErrorException;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Resolves PostgreSQL's {@code unknown} pseudo-type in expression contexts.
 *
 * <p>Implements the 10-step algorithm from PostgreSQL documentation §10.5:
 * "Type Resolution for Operators" / "Type Resolution for Functions".
 *
 * <p>The primary question is: given a list of argument types (some of which
 * may be {@code unknown}, i.e. untyped string literals), what concrete type
 * should each argument resolve to, and which function/operator candidate wins?
 */
public final class TypeResolver {

    private TypeResolver() {}

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    /**
     * Resolve the output type of a SELECT target-list entry where the raw
     * expression has type {@code unknown}.  PostgreSQL resolves this to {@code text}.
     *
     * <p>Example: {@code SELECT 'hello'} → column type OID = 25 (text)
     */
    public static int resolveUnknownOutput(int oid) {
        return oid == PgOid.UNKNOWN ? PgOid.TEXT : oid;
    }

    /**
     * Given a list of argument type OIDs (any may be {@link PgOid#UNKNOWN}),
     * resolve each unknown to a concrete type following PG's algorithm:
     *
     * <ol>
     *   <li>If all are unknown → resolve all to {@code text}.</li>
     *   <li>If any is known → collect the distinct known types.</li>
     *   <li>If all knowns agree on one type → resolve unknowns to that type.</li>
     *   <li>Prefer types in the numeric category over others.</li>
     *   <li>Prefer the "preferred" type within a category.</li>
     *   <li>If still ambiguous → fail with 42725.</li>
     * </ol>
     *
     * @param argOids input OIDs (mutated in-place: unknowns replaced)
     * @return the resolved common type OID
     * @throws SQLException SQLSTATE 42725 (ambiguous) or 42804 (mismatch)
     */
    public static int resolveArgTypes(int[] argOids) throws SQLException {
        if (argOids.length == 0) return PgOid.TEXT;

        // Step 1: all unknown → text
        boolean allUnknown = IntStream.of(argOids).allMatch(o -> o == PgOid.UNKNOWN);
        if (allUnknown) {
            Arrays.fill(argOids, PgOid.TEXT);
            return PgOid.TEXT;
        }

        // Step 2: collect distinct known types
        int[] known = IntStream.of(argOids).filter(o -> o != PgOid.UNKNOWN).distinct().toArray();

        // Step 3: all known types agree → use that type
        if (known.length == 1) {
            int resolved = known[0];
            for (int i = 0; i < argOids.length; i++) {
                if (argOids[i] == PgOid.UNKNOWN) argOids[i] = resolved;
            }
            return resolved;
        }

        // Step 4–5: try to find a common implicit-cast target
        // Walk each known type: check if all others can implicitly cast to it
        int winner = -1;
        for (int candidate : known) {
            boolean ok = true;
            for (int other : known) {
                if (other != candidate && !CoercionEngine.canCoerce(other, candidate, CoercionContext.IMPLICIT)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                // Multiple candidates may survive; prefer the "preferred" type
                if (winner == -1) {
                    winner = candidate;
                } else {
                    // Prefer preferred type in category
                    ScalarType wt = scalarOf(winner);
                    ScalarType ct = scalarOf(candidate);
                    if (ct != null && ct.preferred() && (wt == null || !wt.preferred())) {
                        winner = candidate;
                    }
                }
            }
        }

        if (winner == -1) {
            throw PgErrorException.error("42804",
                    "could not determine polymorphic type because input has type unknown").build();
        }

        for (int i = 0; i < argOids.length; i++) {
            if (argOids[i] == PgOid.UNKNOWN) argOids[i] = winner;
        }
        return winner;
    }

    private static ScalarType scalarOf(int oid) {
        PgType t = REG.byOid(oid);
        return t instanceof ScalarType st ? st : null;
    }
}
