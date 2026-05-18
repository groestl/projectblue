package org.pgjava.sql.ast;

/** One element of a CREATE INDEX column list. */
public record IndexElem(
        String colname,
        Expr expr,
        String opclass,
        SortByDir ordering,
        SortByNulls nullsOrdering
) implements Node {}
