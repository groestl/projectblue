package org.pgjava.sql.ast;

import java.util.List;

/**
 * SET [LOCAL|SESSION] name = value | TO value | TO DEFAULT.
 *
 * @param name   variable name (dot-separated for {@code search_path}, etc.)
 * @param args   value(s); empty = SET … TO DEFAULT / RESET
 * @param scope  LOCAL, SESSION, or DEFAULT (no scope keyword)
 */
public record SetStmt(String name, List<Node> args, SetScope scope) implements Stmt {}
