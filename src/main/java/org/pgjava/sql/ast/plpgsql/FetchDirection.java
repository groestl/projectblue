package org.pgjava.sql.ast.plpgsql;

public enum FetchDirection {
    NEXT, PRIOR, FIRST, LAST,
    ABSOLUTE, RELATIVE,
    FORWARD, FORWARD_ALL,
    BACKWARD, BACKWARD_ALL
}
