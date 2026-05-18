package org.pgjava.sql.ast;

import java.util.List;

/** A function call in a FROM clause: {@code func_name(...) [AS alias]}. */
public record RangeFunction(
        FunctionCall function,
        String alias,
        List<String> colAliases,
        boolean lateral,
        boolean withOrdinality
) implements FromItem {}
