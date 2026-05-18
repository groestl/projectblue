package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.List;

/**
 * {@code pg_catalog.pg_am} — access methods.
 *
 * <p>Two static rows: {@code heap} (table storage) and {@code btree} (index).
 * Queried by psql's {@code \d} command via a JOIN on {@code pg_class.relam}.
 */
public final class PgAm implements VirtualTable {

    public static final PgAm INSTANCE = new PgAm();

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    /** Real PostgreSQL OIDs for built-in access methods. */
    public static final long HEAP_OID   = 2;
    public static final long BTREE_OID  = 403;
    public static final long HASH_OID   = 405;
    public static final long GIST_OID   = 783;
    public static final long GIN_OID    = 2742;
    public static final long SPGIST_OID = 4000;
    public static final long BRIN_OID   = 3580;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("oid",       1, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("amname",    2, REG.byOid(PgOid.NAME)),
            ColumnDef.notNull("amhandler", 3, REG.byOid(PgOid.OID)),
            ColumnDef.notNull("amtype",    4, REG.byOid(PgOid.CHAR))
    );

    private PgAm() {}

    @Override public String name()   { return "pg_am"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        return List.of(
                new Object[]{ HEAP_OID,   "heap",   0L, "t" },   // t = table AM
                new Object[]{ BTREE_OID,  "btree",  0L, "i" },   // i = index AM
                new Object[]{ HASH_OID,   "hash",   0L, "i" },
                new Object[]{ GIST_OID,   "gist",   0L, "i" },
                new Object[]{ GIN_OID,    "gin",    0L, "i" },
                new Object[]{ SPGIST_OID, "spgist", 0L, "i" },
                new Object[]{ BRIN_OID,   "brin",   0L, "i" }
        );
    }
}
