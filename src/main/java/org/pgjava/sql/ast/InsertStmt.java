package org.pgjava.sql.ast;

import java.util.List;

/**
 * An INSERT statement.
 *
 * @param relation     target table
 * @param cols         explicit column list; empty = insert into all columns
 * @param source       the values/select source (a SelectStmt wrapping VALUES or a real SELECT)
 * @param defaultValues true for INSERT … DEFAULT VALUES
 * @param onConflict   ON CONFLICT clause; null if absent
 * @param returning    RETURNING clause; empty if absent
 * @param withClause   WITH clause; null if absent
 */
public record InsertStmt(
        RangeVar relation,
        List<String> cols,
        SelectStmt source,
        boolean defaultValues,
        OnConflictClause onConflict,
        List<TargetEntry> returning,
        WithClause withClause
) implements Stmt {}
