package org.pgjava.types;

import org.pgjava.sql.ast.Expr;
import java.util.List;

/**
 * A user-defined domain type (CREATE DOMAIN ... AS base_type).
 *
 * @param oid              OID of this domain (≥ {@link PgOid#FIRST_USER_OID})
 * @param name             domain name
 * @param baseType         underlying base type
 * @param checkConstraints CHECK expressions (VALUE refers to the domain value)
 * @param defaultExpr      DEFAULT expression (nullable)
 * @param notNull          NOT NULL constraint
 */
public record DomainType(int oid, String name, PgType baseType,
                         List<Expr> checkConstraints, Expr defaultExpr,
                         boolean notNull) implements PgType {
    /** Backwards-compatible constructor (no constraints). */
    public DomainType(int oid, String name, PgType baseType) {
        this(oid, name, baseType, List.of(), null, false);
    }

    @Override public Class<?> javaClass() { return baseType.javaClass(); }
}
