package org.pgjava.catalog;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.pgjava.types.PgType;

/** A named schema containing tables, indexes, sequences, views, and functions. */
public final class Schema {

    private final long   oid;
    private final String name;

    private final Map<String, TableDef>   tables    = new ConcurrentHashMap<>();
    private final Map<String, IndexDef>   indexes   = new ConcurrentHashMap<>();
    private final Map<String, SequenceDef> sequences = new ConcurrentHashMap<>();
    private final Map<String, ViewDef>    views     = new ConcurrentHashMap<>();
    private final Map<String, PgType>     types     = new ConcurrentHashMap<>();

    public Schema(long oid, String name) {
        this.oid  = oid;
        this.name = name;
    }

    // -------------------------------------------------------------------------

    public long   oid()  { return oid; }
    public String name() { return name; }

    // Tables
    public Map<String, TableDef> tables()     { return Collections.unmodifiableMap(tables); }
    public TableDef  table(String name)       { return tables.get(name.toLowerCase()); }
    public void      addTable(TableDef t)     { tables.put(t.name().toLowerCase(), t); }
    public TableDef  removeTable(String name) { return tables.remove(name.toLowerCase()); }
    public boolean   hasTable(String name)    { return tables.containsKey(name.toLowerCase()); }

    // Indexes
    public Map<String, IndexDef> indexes()    { return Collections.unmodifiableMap(indexes); }
    public IndexDef  index(String name)       { return indexes.get(name.toLowerCase()); }
    public void      addIndex(IndexDef i)     { indexes.put(i.name().toLowerCase(), i); }
    public IndexDef  removeIndex(String name) { return indexes.remove(name.toLowerCase()); }

    // Sequences
    public Map<String, SequenceDef> sequences()      { return Collections.unmodifiableMap(sequences); }
    public SequenceDef  sequence(String name)         { return sequences.get(name.toLowerCase()); }
    public void         addSequence(SequenceDef s)    { sequences.put(s.name().toLowerCase(), s); }
    public SequenceDef  removeSequence(String name)   { return sequences.remove(name.toLowerCase()); }

    // Views
    public Map<String, ViewDef> views()       { return Collections.unmodifiableMap(views); }
    public ViewDef   view(String name)        { return views.get(name.toLowerCase()); }
    public void      addView(ViewDef v)       { views.put(v.name().toLowerCase(), v); }
    public ViewDef   removeView(String name)  { return views.remove(name.toLowerCase()); }

    // Types (user-defined: ENUM, DOMAIN)
    public Map<String, PgType> types()        { return Collections.unmodifiableMap(types); }
    public PgType    type(String name)        { return types.get(name.toLowerCase()); }
    public void      addType(PgType t)        { types.put(t.name().toLowerCase(), t); }
    public PgType    removeType(String name)  { return types.remove(name.toLowerCase()); }

    // -------------------------------------------------------------------------
    // Deep copy

    /**
     * Create a deep copy of this schema.  All mutable catalog objects (tables,
     * sequences, views) are cloned; immutable records (indexes, types) are shared.
     */
    public Schema deepCopy() {
        Schema copy = new Schema(this.oid, this.name);

        // Tables — mutable, need deep copy
        for (TableDef t : tables.values()) {
            TableDef tc = new TableDef(t.oid(), t.name(), t.schemaName(), t.isTemp());
            for (ColumnDef col : t.columns())      tc.addColumn(col);
            for (Constraint c : t.constraints())   tc.addConstraint(c);
            for (IndexDef idx : t.indexes())       tc.addIndex(idx);
            for (TriggerDef tr : t.triggers())     tc.addTrigger(tr);
            copy.addTable(tc);
        }

        // Indexes — immutable records, shallow copy
        for (IndexDef idx : indexes.values()) copy.addIndex(idx);

        // Sequences — mutable (AtomicLong current), need deep copy
        for (SequenceDef seq : sequences.values()) {
            SequenceDef sc = new SequenceDef(seq.oid(), seq.name(), seq.schemaName(),
                    seq.start(), seq.increment(), seq.minVal(), seq.maxVal(), seq.cycle());
            sc.setval(seq.current());
            copy.addSequence(sc);
        }

        // Views — mutable triggers list, need deep copy
        for (ViewDef v : views.values()) {
            ViewDef vc = new ViewDef(v.oid(), v.name(), v.schemaName(),
                    v.definitionSql(), v.parsedDef(), v.columnAliases());
            for (TriggerDef tr : v.triggers()) vc.addTrigger(tr);
            copy.addView(vc);
        }

        // Types — effectively immutable, shallow copy
        for (PgType t : types.values()) copy.addType(t);

        return copy;
    }
}
