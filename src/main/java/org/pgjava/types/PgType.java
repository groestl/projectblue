package org.pgjava.types;

/**
 * Sealed type hierarchy representing a PostgreSQL type.
 *
 * <p>Every {@code PgType} has an OID, a canonical name, and a Java class used
 * to hold values of that type in the execution engine.
 */
public sealed interface PgType
        permits ScalarType, ArrayType, DomainType, EnumType {

    /** PostgreSQL OID. */
    int oid();

    /** Canonical name (e.g. {@code "int4"}, {@code "text"}, {@code "_int4"}). */
    String name();

    /** Java class used to represent values of this type in memory. */
    Class<?> javaClass();

    /** True if this is an array type. */
    default boolean isArray() { return this instanceof ArrayType; }

    /** True if this is the {@code unknown} pseudo-type. */
    default boolean isUnknown() { return oid() == PgOid.UNKNOWN; }
}
