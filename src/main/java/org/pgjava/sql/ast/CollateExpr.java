package org.pgjava.sql.ast;

import java.util.List;

/** {@code expr COLLATE collation_name}. */
public record CollateExpr(Expr arg, List<String> collationName) implements Expr {}
