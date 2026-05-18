package org.pgjava.types;

/**
 * A scalar (non-array) PostgreSQL type.
 *
 * @param oid         PostgreSQL OID
 * @param name        canonical type name (e.g. {@code "int4"})
 * @param javaClass   Java class for in-memory values
 * @param category    PostgreSQL type category character (N=numeric, S=string, D=datetime, B=boolean, etc.)
 * @param preferred   true if this is the preferred type in its category (e.g. float8 preferred over float4)
 */
public record ScalarType(
        int     oid,
        String  name,
        Class<?> javaClass,
        char    category,
        boolean preferred
) implements PgType {}
