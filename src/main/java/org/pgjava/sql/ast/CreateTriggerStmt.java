package org.pgjava.sql.ast;

import java.util.List;

/**
 * {@code CREATE TRIGGER name {BEFORE|AFTER|INSTEAD OF} {INSERT|UPDATE|DELETE} [OR ...]
 * ON table [FOR EACH {ROW|STATEMENT}] [WHEN (condition)] EXECUTE FUNCTION func()}
 */
public record CreateTriggerStmt(
        String triggerName,
        RangeVar relation,
        List<String> funcname,
        List<String> args,
        boolean row,
        int timing,
        int events,
        List<String> columns,
        Expr whenClause,
        boolean replace,
        boolean isConstraint,
        boolean deferrable,
        boolean initDeferred
) implements Stmt {}
