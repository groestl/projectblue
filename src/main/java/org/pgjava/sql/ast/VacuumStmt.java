package org.pgjava.sql.ast;

import java.util.List;

/** VACUUM / ANALYZE — accepted as no-op. */
public record VacuumStmt(List<RangeVar> relations, boolean analyze) implements Stmt {}
