package org.pgjava.sql.ast;

import java.util.List;

/**
 * A JOIN expression.
 *
 * @param quals      ON condition (null for USING or NATURAL)
 * @param usingCols  USING column list (null for ON or NATURAL)
 * @param natural    true for NATURAL JOIN
 * @param alias      alias for the join result, null if none
 * @param colAliases column aliases from {@code AS j(a,b,c)}; empty list if none
 */
public record JoinExpr(
        JoinType joinType,
        FromItem larg,
        FromItem rarg,
        Expr quals,
        List<String> usingCols,
        boolean natural,
        String alias,
        List<String> colAliases
) implements FromItem {
    /** Backwards-compatible constructor with no alias. */
    public JoinExpr(JoinType joinType, FromItem larg, FromItem rarg,
                    Expr quals, List<String> usingCols, boolean natural) {
        this(joinType, larg, rarg, quals, usingCols, natural, null, List.of());
    }
}
