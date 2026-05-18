package org.pgjava.types;

/**
 * A PostgreSQL array type (e.g. {@code int4[]}, OID 1007).
 *
 * @param oid         PostgreSQL OID of the array type
 * @param elementType the element type
 */
public record ArrayType(int oid, PgType elementType) implements PgType {

    @Override public String   name()      { return "_" + elementType.name(); }
    @Override public Class<?> javaClass() { return Object[].class; }
}
