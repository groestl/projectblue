package org.pgjava.executor;

import org.pgjava.types.PgCollation;

/**
 * Wraps a value with an explicit collation from a {@code COLLATE} expression.
 *
 * <p>When the evaluator encounters {@code expr COLLATE "collation_name"}, it
 * wraps the result in a {@code CollatedValue}. Comparison sites unwrap the
 * value and use the attached collation for string ordering. If both operands
 * carry explicit collations and they differ, SQLSTATE 42P21 is raised.
 */
public record CollatedValue(Object value, PgCollation collation) {

    /** Unwrap a value: if it's a CollatedValue, return the inner value; otherwise return as-is. */
    public static Object unwrap(Object v) {
        return v instanceof CollatedValue cv ? cv.value : v;
    }

    /** Extract the explicit collation from a value, or null if none. */
    public static PgCollation collationOf(Object v) {
        return v instanceof CollatedValue cv ? cv.collation : null;
    }
}
