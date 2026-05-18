package org.pgjava.storage;

/**
 * Immutable identifier for a single row in a heap table.
 *
 * <p>{@code tableOid} is the OID of the table; {@code position} is the
 * 0-based index of the row in the table's {@code ArrayList<Row>}.
 * After a DELETE (tombstone) or UPDATE (tombstone + insert), the old
 * position still exists in the list but is skipped by scans.
 */
public record RowId(long tableOid, int position) {

    @Override
    public String toString() {
        return "(" + tableOid + "," + position + ")";
    }
}
