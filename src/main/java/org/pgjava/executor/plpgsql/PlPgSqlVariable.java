package org.pgjava.executor.plpgsql;

import org.pgjava.engine.PgErrorException;

import java.sql.SQLException;
import java.util.List;

/**
 * A PL/pgSQL variable: name, type, value, and constraints.
 */
public final class PlPgSqlVariable {

    private final String  name;
    private final String  typeName;
    private final boolean constant;
    private final boolean notNull;
    private Object        value;
    private final PlPgSqlVariable delegate; // non-null for ALIAS FOR — shares storage with target
    private List<String>  columnNames;      // for record/composite types: ordered column names

    public PlPgSqlVariable(String name, String typeName, boolean constant, boolean notNull, Object value) {
        this.name     = name;
        this.typeName = typeName;
        this.constant = constant;
        this.notNull  = notNull;
        this.value    = value;
        this.delegate = null;
    }

    /** Create an alias that delegates to another variable. */
    public PlPgSqlVariable(String aliasName, PlPgSqlVariable target) {
        this.name     = aliasName;
        this.typeName = target.typeName;
        this.constant = target.constant;
        this.notNull  = target.notNull;
        this.value    = null; // unused — delegates
        this.delegate = target;
    }

    public String  name()     { return name; }
    public String  typeName() { return delegate != null ? delegate.typeName : typeName; }
    public boolean isConstant() { return delegate != null ? delegate.constant : constant; }
    public Object  value()    { return delegate != null ? delegate.value() : value; }
    public List<String> columnNames() { return delegate != null ? delegate.columnNames : columnNames; }
    public void setColumnNames(List<String> names) {
        if (delegate != null) delegate.setColumnNames(names);
        else this.columnNames = names;
    }

    public void setValue(Object v) throws SQLException {
        if (delegate != null) { delegate.setValue(v); return; }
        if (constant) {
            throw PgErrorException.error("22005",
                    "variable \"" + name + "\" is declared CONSTANT").build();
        }
        if (notNull && v == null) {
            throw PgErrorException.error("22004",
                    "null value cannot be assigned to variable \"" + name + "\" declared NOT NULL").build();
        }
        this.value = v;
    }
}
