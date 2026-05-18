package org.pgjava.catalog;

import org.pgjava.sql.ast.SelectStmt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named view (CREATE VIEW).
 *
 * <p>Views can have INSTEAD OF triggers attached to them, enabling DML
 * operations on views to be redirected through trigger functions.
 */
public final class ViewDef {

    private final long        oid;
    private final String      name;
    private final String      schemaName;
    private final String      definitionSql;
    private final SelectStmt  parsedDef;
    private final List<String> columnAliases;
    private final List<TriggerDef> triggers = new ArrayList<>();

    public ViewDef(long oid, String name, String schemaName,
                   String definitionSql, SelectStmt parsedDef,
                   List<String> columnAliases) {
        this.oid            = oid;
        this.name           = name;
        this.schemaName     = schemaName;
        this.definitionSql  = definitionSql;
        this.parsedDef      = parsedDef;
        this.columnAliases  = columnAliases != null ? List.copyOf(columnAliases) : List.of();
    }

    public long        oid()            { return oid; }
    public String      name()           { return name; }
    public String      schemaName()     { return schemaName; }
    public String      definitionSql()  { return definitionSql; }
    public SelectStmt  parsedDef()      { return parsedDef; }
    public List<String> columnAliases() { return columnAliases; }

    public List<TriggerDef> triggers()  { return Collections.unmodifiableList(triggers); }

    public void addTrigger(TriggerDef t) {
        triggers.add(t);
    }

    public void dropTrigger(String triggerName) {
        triggers.removeIf(t -> triggerName.equalsIgnoreCase(t.name()));
    }
}
