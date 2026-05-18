package org.pgjava.sql.ast;

/** Sealed base for all FROM-clause items. */
public sealed interface FromItem extends Node
        permits RangeVar, RangeSubselect, JoinExpr, RangeFunction {}
