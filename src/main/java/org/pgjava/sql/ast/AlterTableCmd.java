package org.pgjava.sql.ast;

/** One sub-command of ALTER TABLE. */
public record AlterTableCmd(
        AlterTableType subtype,
        String name,
        ColumnDefNode def,
        DropBehavior behavior
) implements Node {}
