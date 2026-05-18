package org.pgjava.catalog;

import java.util.List;

/**
 * A read-only system catalog table (e.g. {@code pg_class}, {@code pg_attribute}).
 *
 * <p>Virtual tables live in the {@code pg_catalog} or {@code information_schema}
 * schemas.  Phase 9 will wire them into a {@code CatalogScan} operator so they
 * participate in normal query execution.
 *
 * <p>Rows are {@code Object[]} arrays whose element order matches
 * {@link #columns()}.  A {@code null} element means SQL NULL.
 */
public interface VirtualTable {

    /** Unqualified table name (e.g. {@code "pg_class"}). */
    String name();

    /** Schema this table belongs to (e.g. {@code "pg_catalog"}). */
    String schema();

    /** Column definitions — order matters, indexes are 0-based. */
    List<ColumnDef> columns();

    /**
     * Produce all rows visible in the given catalog.
     *
     * @param catalog  the live catalog to read from
     * @return iterable of {@code Object[]} rows; may be empty but never null
     */
    Iterable<Object[]> scan(CatalogManager catalog);
}
