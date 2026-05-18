package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.List;
import java.util.function.Supplier;

/**
 * {@code pg_catalog.pg_locks} — shows all active locks in the database.
 *
 * <p>Unlike other virtual tables, this one needs access to runtime lock state
 * (TableLockManager, RowLockTable, AdvisoryLockManager) which live outside the
 * catalog.  The row supplier is injected by {@code Database} after construction.
 */
public final class PgLocks implements VirtualTable {

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.of("locktype",           1,  REG.byOid(PgOid.TEXT)),
            ColumnDef.of("database",           2,  REG.byOid(PgOid.OID)),
            ColumnDef.of("relation",           3,  REG.byOid(PgOid.OID)),
            ColumnDef.of("page",               4,  REG.byOid(PgOid.INT4)),
            ColumnDef.of("tuple",              5,  REG.byOid(PgOid.INT2)),
            ColumnDef.of("virtualxid",         6,  REG.byOid(PgOid.TEXT)),
            ColumnDef.of("transactionid",      7,  REG.byOid(PgOid.INT8)),
            ColumnDef.of("classid",            8,  REG.byOid(PgOid.OID)),
            ColumnDef.of("objid",              9,  REG.byOid(PgOid.OID)),
            ColumnDef.of("objsubid",           10, REG.byOid(PgOid.INT2)),
            ColumnDef.of("virtualtransaction", 11, REG.byOid(PgOid.TEXT)),
            ColumnDef.of("pid",                12, REG.byOid(PgOid.INT4)),
            ColumnDef.of("mode",               13, REG.byOid(PgOid.TEXT)),
            ColumnDef.of("granted",            14, REG.byOid(PgOid.BOOL)),
            ColumnDef.of("fastpath",           15, REG.byOid(PgOid.BOOL)),
            ColumnDef.of("waitstart",          16, REG.byOid(PgOid.TIMESTAMPTZ))
    );

    private final Supplier<List<Object[]>> rowSupplier;

    public PgLocks(Supplier<List<Object[]>> rowSupplier) {
        this.rowSupplier = rowSupplier;
    }

    @Override public String name()   { return "pg_locks"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        return rowSupplier.get();
    }
}
