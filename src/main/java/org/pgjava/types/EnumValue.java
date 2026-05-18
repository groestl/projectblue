package org.pgjava.types;

/**
 * A typed enum value that preserves ordinal position for correct comparison.
 *
 * <p>PostgreSQL enums compare by declaration order, not alphabetically.
 * This wrapper carries the label string and its 0-based ordinal position
 * within the enum type's label list.
 */
public record EnumValue(String label, int ordinal, EnumType enumType) implements Comparable<EnumValue> {

    @Override
    public int compareTo(EnumValue other) {
        return Integer.compare(this.ordinal, other.ordinal);
    }

    @Override
    public String toString() {
        return label;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof EnumValue ev) return label.equals(ev.label);
        return false;
    }

    /** Compare label against a raw string (for WHERE col = 'literal' paths). */
    public boolean labelEquals(String s) {
        return label.equals(s);
    }

    @Override
    public int hashCode() {
        return label.hashCode();
    }

    /**
     * Compare this enum value against a string literal, resolving the string
     * to its ordinal within this enum type. Throws if the string is not a valid label.
     */
    public int compareToLabel(String other) {
        if (label.equals(other)) return 0;
        int otherOrd = enumType.labels().indexOf(other);
        if (otherOrd < 0) {
            throw new IllegalArgumentException(
                    "invalid input value for enum " + enumType.name() + ": \"" + other + "\"");
        }
        return Integer.compare(this.ordinal, otherOrd);
    }
}
