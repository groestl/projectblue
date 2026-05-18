package org.pgjava.sql.ast;

import java.util.List;

/** ALTER SEQUENCE [IF EXISTS]. */
public record AlterSequenceStmt(RangeVar sequence, List<DefElem> options, boolean ifExists)
        implements Stmt {
    /** Backwards-compatible constructor with ifExists=false. */
    public AlterSequenceStmt(RangeVar sequence, List<DefElem> options) {
        this(sequence, options, false);
    }
}
