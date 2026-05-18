package org.pgjava.sql.ast;

/** UNLISTEN {channel | *}. */
public record UnlistenStmt(String channelName) implements Stmt {}
