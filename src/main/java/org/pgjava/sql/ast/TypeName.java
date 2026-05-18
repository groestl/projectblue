package org.pgjava.sql.ast;

import java.util.List;

/**
 * Represents a SQL type reference, e.g. {@code integer}, {@code varchar(255)},
 * {@code text[]}, {@code numeric(10,2)}.
 *
 * @param names      qualified type name, e.g. {@code ["pg_catalog","int4"]} or {@code ["text"]}
 * @param typmods    type modifiers, e.g. precision/scale for numeric; empty for types with no mods
 * @param arrayBounds number of array dimensions; 0 = not an array; each element is -1 (unbounded)
 *                    or a positive integer (bounded)
 * @param setOf      true when SETOF qualifier is present (used in function return types)
 */
public record TypeName(
        List<String> names,
        List<Node> typmods,
        int arrayBounds,
        boolean setOf
) implements Node {

    /** Convenience constructor for plain type with no mods or arrays. */
    public static TypeName of(String... nameParts) {
        return new TypeName(List.of(nameParts), List.of(), 0, false);
    }

    /** Returns the simple (unqualified) type name, lowercased. */
    public String simpleName() {
        return names.isEmpty() ? "" : names.getLast().toLowerCase();
    }
}
