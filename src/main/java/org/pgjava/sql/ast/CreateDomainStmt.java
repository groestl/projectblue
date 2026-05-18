package org.pgjava.sql.ast;

import java.util.List;

/** {@code CREATE DOMAIN name AS type [NOT NULL] [DEFAULT expr] [CHECK (...)]} */
public record CreateDomainStmt(
        List<String> domainName,
        TypeName baseType,
        List<DomainConstraint> checkConstraints,
        Expr defaultExpr,
        boolean notNull
) implements Stmt {
    /** A named domain CHECK constraint. */
    public record DomainConstraint(String name, Expr expr) {
        public DomainConstraint(Expr expr) { this(null, expr); }
    }
}
