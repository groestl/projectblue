package org.pgjava.catalog;

import java.util.*;

/**
 * Definition of a table (or materialized view treated as a table).
 *
 * <p>Mutable so that ALTER TABLE ADD/DROP COLUMN and ADD/DROP CONSTRAINT work
 * without rebuilding the entire object.
 */
public final class TableDef {

    private final long   oid;
    private final String name;
    private final String schemaName;
    private final boolean isTemp;

    // Ordered by attnum; attnum is 1-based and assigned at creation / add column time
    private final List<ColumnDef>  columns     = new ArrayList<>();
    private final List<Constraint> constraints = new ArrayList<>();
    private final List<IndexDef>   indexes     = new ArrayList<>();
    private final List<TriggerDef> triggers    = new ArrayList<>();

    public TableDef(long oid, String name, String schemaName, boolean isTemp) {
        this.oid        = oid;
        this.name       = name;
        this.schemaName = schemaName;
        this.isTemp     = isTemp;
    }

    // -------------------------------------------------------------------------
    // Accessors

    public long   oid()        { return oid; }
    public String name()       { return name; }
    public String schemaName() { return schemaName; }
    public boolean isTemp()    { return isTemp; }

    public List<ColumnDef>  columns()     { return Collections.unmodifiableList(columns); }
    public List<Constraint> constraints() { return Collections.unmodifiableList(constraints); }
    public List<IndexDef>   indexes()     { return Collections.unmodifiableList(indexes); }
    public List<TriggerDef> triggers()    { return Collections.unmodifiableList(triggers); }

    /** Find column by name (case-insensitive). Returns null if not found. */
    public ColumnDef column(String name) {
        String lc = name.toLowerCase();
        return columns.stream()
                .filter(c -> c.name().equalsIgnoreCase(lc))
                .findFirst().orElse(null);
    }

    public int columnCount() { return columns.size(); }

    // -------------------------------------------------------------------------
    // Mutations (called by DDL executor)

    public void addColumn(ColumnDef col) {
        columns.add(col);
    }

    /**
     * Drop a column by name (case-insensitive).
     *
     * @throws IllegalArgumentException if the column does not exist
     */
    public void dropColumn(String name) {
        boolean removed = columns.removeIf(c -> c.name().equalsIgnoreCase(name));
        if (!removed) throw new IllegalArgumentException("column not found: " + name);
        // PostgreSQL never re-assigns attnums — dropped columns leave gaps.
        // Attnums are preserved so that indexes, pg_attribute, and constraints
        // that reference attnums remain valid.
    }

    /**
     * Replace an existing column definition in-place (same attnum, same name).
     * Used by ALTER TABLE ALTER COLUMN SET/DROP DEFAULT, SET/DROP NOT NULL.
     */
    public void updateColumn(ColumnDef updated) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(updated.name())) {
                columns.set(i, updated);
                return;
            }
        }
        throw new IllegalArgumentException("column not found: " + updated.name());
    }

    public void addConstraint(Constraint c) {
        constraints.add(c);
    }

    public void dropConstraint(String name) {
        constraints.removeIf(c -> name.equalsIgnoreCase(c.name()));
    }

    public void addIndex(IndexDef idx) {
        indexes.add(idx);
    }

    public void dropIndex(String name) {
        indexes.removeIf(i -> name.equalsIgnoreCase(i.name()));
    }

    public void addTrigger(TriggerDef t) {
        triggers.add(t);
    }

    public void dropTrigger(String name) {
        triggers.removeIf(t -> name.equalsIgnoreCase(t.name()));
    }

    // -------------------------------------------------------------------------
    // Convenience

    /** Returns the PRIMARY KEY constraint if any, else null. */
    public Constraint.PrimaryKey primaryKey() {
        return constraints.stream()
                .filter(c -> c instanceof Constraint.PrimaryKey)
                .map(c -> (Constraint.PrimaryKey) c)
                .findFirst().orElse(null);
    }

    @Override
    public String toString() {
        return schemaName + "." + name;
    }
}
