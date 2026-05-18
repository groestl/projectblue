package org.pgjava.types;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A user-defined enum type (CREATE TYPE ... AS ENUM).
 *
 * <p>Labels are mutable so that ALTER TYPE ... ADD VALUE can update the type
 * in-place without creating a new object (existing ColumnDefs hold references
 * to this type and would become stale if replaced).
 */
public final class EnumType implements PgType {

    private final int oid;
    private final String name;
    private final List<String> labels;

    public EnumType(int oid, String name, List<String> labels) {
        this.oid = oid;
        this.name = name;
        this.labels = new ArrayList<>(labels);
    }

    public int oid()           { return oid; }
    @Override public String name() { return name; }
    public List<String> labels()   { return Collections.unmodifiableList(labels); }

    /** Add a label at the end. */
    public void addLabel(String label) {
        labels.add(label);
    }

    /** Add a label at a specific position. */
    public void addLabel(int index, String label) {
        labels.add(index, label);
    }

    @Override public Class<?> javaClass() { return String.class; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EnumType e)) return false;
        return oid == e.oid && name.equals(e.name) && labels.equals(e.labels);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * oid + name.hashCode()) + labels.hashCode();
    }

    @Override
    public String toString() {
        return "EnumType[oid=" + oid + ", name=" + name + ", labels=" + labels + "]";
    }
}
