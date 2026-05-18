package org.pgjava.sql.ast;

/** NOTIFY channel [, payload]. */
public record NotifyStmt(String channelName, String payload) implements Stmt {}
