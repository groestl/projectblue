package org.pgjava.sql.ast;

/** LISTEN channel. */
public record ListenStmt(String channelName) implements Stmt {}
