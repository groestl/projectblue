package org.pgjava.executor;

import org.pgjava.catalog.CatalogManager;
import org.pgjava.catalog.VirtualTable;
import org.pgjava.storage.Row;
import org.pgjava.storage.RowId;

import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Scan operator for virtual catalog tables (pg_catalog, information_schema).
 */
public final class VirtualTableScan extends Operator {

    private static final RowId VIRTUAL_ROWID = new RowId(-1, -1);

    private final VirtualTable   vt;
    private final CatalogManager catalog;
    private Iterator<Object[]>   iter;

    public VirtualTableScan(String alias, VirtualTable vt, CatalogManager catalog) {
        super(OutputSchema.ofTable(alias, syntheticTable(alias, vt)));
        this.vt      = vt;
        this.catalog = catalog;
    }

    @Override
    public void open() throws SQLException {
        iter = vt.scan(catalog).iterator();
    }

    @Override
    public Row next() {
        if (iter == null || !iter.hasNext()) return null;
        Object[] values = iter.next();
        return new Row(VIRTUAL_ROWID, values);
    }

    @Override
    public void close() {
        iter = null;
    }

    /** Build a dummy TableDef-like structure for schema derivation. */
    private static org.pgjava.catalog.TableDef syntheticTable(
            String alias, VirtualTable vt) {
        var t = new org.pgjava.catalog.TableDef(0, vt.name(), vt.schema(), false);
        for (var c : vt.columns()) t.addColumn(c);
        return t;
    }
}
