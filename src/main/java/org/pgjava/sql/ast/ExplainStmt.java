package org.pgjava.sql.ast;

/** EXPLAIN [ANALYZE] [VERBOSE] …. */
public record ExplainStmt(Stmt query, boolean analyze, boolean verbose, boolean buffers)
        implements Stmt {}
