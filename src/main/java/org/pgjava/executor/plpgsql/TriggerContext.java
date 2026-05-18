package org.pgjava.executor.plpgsql;

import org.pgjava.catalog.TableDef;

/**
 * Trigger execution context passed to PL/pgSQL trigger functions.
 *
 * <p>Carries the NEW/OLD row data and TG_* special variables that
 * PostgreSQL injects into trigger function scope.
 */
public record TriggerContext(
        String tgName,
        String tgWhen,       // "BEFORE", "AFTER", "INSTEAD OF"
        String tgLevel,      // "ROW" or "STATEMENT"
        String tgOp,         // "INSERT", "UPDATE", "DELETE", "TRUNCATE"
        String tgTableName,
        String tgTableSchema,
        String[] tgArgv,
        TableDef tableDef,
        Object[] newRow,     // null for DELETE / STATEMENT
        Object[] oldRow      // null for INSERT / STATEMENT
) {}
