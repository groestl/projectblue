package org.pgjava.catalog;

import org.pgjava.sql.ast.Expr;
import org.pgjava.sql.ast.FkAction;

import java.util.List;

/**
 * Sealed hierarchy of table-level and column-level constraints.
 * All subtypes are records — immutable once created.
 */
public sealed interface Constraint
        permits Constraint.PrimaryKey,
                Constraint.Unique,
                Constraint.NotNull,
                Constraint.Check,
                Constraint.ForeignKey {

    /** Constraint name (may be null if unnamed). */
    String name();

    // -------------------------------------------------------------------------

    record PrimaryKey(String name, List<String> columns) implements Constraint {}

    record Unique(String name, List<String> columns) implements Constraint {}

    /** NOT NULL constraint on a single column (always column-level). */
    record NotNull(String name, String column) implements Constraint {}

    record Check(String name, String column, Expr expr, String exprSql) implements Constraint {}

    record ForeignKey(
            String      name,
            List<String> columns,
            String      refSchema,
            String      refTable,
            List<String> refColumns,
            FkAction    onDelete,
            FkAction    onUpdate
    ) implements Constraint {}
}
