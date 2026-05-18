package org.pgjava.sql.ast;

import java.util.List;

/** CREATE [TEMPORARY] SEQUENCE. */
public record CreateSequenceStmt(RangeVar sequence, List<DefElem> options, boolean ifNotExists)
        implements Stmt {}
