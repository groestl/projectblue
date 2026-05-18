package org.pgjava.types;

/**
 * PostgreSQL coercion contexts, in increasing permissiveness.
 *
 * <ul>
 *   <li>{@code IMPLICIT} — used for automatic coercion in expressions; only
 *       implicit casts are permitted (e.g. int2 → int4, varchar → text).</li>
 *   <li>{@code ASSIGNMENT} — used for INSERT/UPDATE target column assignment;
 *       permits all implicit + assignment casts.</li>
 *   <li>{@code EXPLICIT} — used for CAST(x AS T) or x::T; all casts allowed.</li>
 * </ul>
 */
public enum CoercionContext {
    IMPLICIT,
    ASSIGNMENT,
    EXPLICIT
}
