package org.pgjava.catalog;

import org.pgjava.sql.ast.Expr;

import java.util.List;

/**
 * Definition of a trigger attached to a table.
 *
 * <p>Stores the metadata needed to decide whether a trigger fires for a given
 * DML event and, if so, which function to invoke.
 */
public record TriggerDef(
        long oid,
        String name,
        long tableOid,
        String tableName,
        String tableSchema,
        long functionOid,
        String functionName,
        String functionSchema,
        boolean row,
        int timing,
        int events,
        List<String> columns,
        Expr whenClause,
        List<String> args
) {
    // Timing constants (match pg_query output)
    public static final int AFTER = 0;
    public static final int BEFORE = 2;
    public static final int INSTEAD_OF = 64;

    // Event bitmask constants (match pg_query output)
    public static final int INSERT = 4;
    public static final int DELETE = 8;
    public static final int UPDATE = 16;
    public static final int TRUNCATE = 32;

    public boolean firesOn(int event) { return (events & event) != 0; }
    public boolean isBefore()    { return timing == BEFORE; }
    public boolean isAfter()     { return timing == AFTER; }
    public boolean isInsteadOf() { return timing == INSTEAD_OF; }

    /**
     * Encode timing + events into the int16 tgtype bitmask used by pg_catalog.pg_trigger.
     * PostgreSQL tgtype: bit 0 = row, bit 1 = before, bit 6 = instead of,
     * bits 2-5 = INSERT/DELETE/UPDATE/TRUNCATE.
     */
    public short tgtype() {
        short t = 0;
        if (row) t |= 1;
        if (timing == BEFORE) t |= 2;
        if (timing == INSTEAD_OF) t |= 64;
        t |= (short) events;
        return t;
    }
}
