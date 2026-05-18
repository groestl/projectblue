package org.pgjava.executor;

import org.pgjava.catalog.ColumnDef;
import org.pgjava.catalog.TableDef;
import org.pgjava.engine.ColumnMeta;
import org.pgjava.sql.ast.*;
import org.pgjava.storage.Row;
import org.pgjava.storage.RowId;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper for evaluating RETURNING clause expressions against a DML-affected row.
 */
final class ReturningEval {

    private ReturningEval() {}

    /**
     * Build an EvalContext from a flat array of column values using the table's
     * column names.  The context uses the table name as the alias so both
     * qualified ({@code tbl.col}) and unqualified ({@code col}) references work.
     */
    static EvalContext buildContext(TableDef def, Object[] vals) {
        List<String> names = def.columns().stream()
                .map(c -> c.name().toLowerCase())
                .toList();
        EvalContext.ColumnMap cm = EvalContext.ColumnMap.of(names);
        Row row = new Row(new RowId(0, 0), vals);
        // Use both the table name alias AND the empty alias so unqualified refs work
        return EvalContext.of(row, cm);
    }

    /**
     * Evaluate one RETURNING row.  Handles {@code *} expansion.
     *
     * @param targets  the parsed RETURNING target list
     * @param def      table definition (for * expansion)
     * @param ctx      context built from the affected row
     * @param eval     expression evaluator
     * @return         the values of the RETURNING row
     */
    static Object[] evalRow(List<TargetEntry> targets, TableDef def,
                             EvalContext ctx, Evaluator eval) throws SQLException {
        List<Object> out = new ArrayList<>();
        for (TargetEntry te : targets) {
            if (te.val() instanceof ColumnRef cr && cr.isStar()) {
                // RETURNING * — expand to all table columns
                for (int i = 0; i < def.columnCount(); i++) {
                    out.add(eval.eval(ColumnRef.of(def.columns().get(i).name()), ctx));
                }
            } else {
                out.add(eval.eval(te.val(), ctx));
            }
        }
        return out.toArray();
    }

    /**
     * Derive the output column names for the RETURNING clause.
     *
     * @param targets  the parsed RETURNING target list
     * @param def      table definition (for * expansion)
     */
    static List<String> colNames(List<TargetEntry> targets, TableDef def) {
        List<String> names = new ArrayList<>();
        for (TargetEntry te : targets) {
            if (te.val() instanceof ColumnRef cr && cr.isStar()) {
                def.columns().forEach(c -> names.add(c.name().toLowerCase()));
            } else if (te.name() != null && !te.name().isBlank()) {
                names.add(te.name().toLowerCase());
            } else if (te.val() instanceof ColumnRef cr && !cr.fields().isEmpty()) {
                names.add(cr.fields().getLast().toLowerCase());
            } else {
                names.add("?column?");
            }
        }
        return names;
    }

    /**
     * Derive typed ColumnMeta for the RETURNING clause, preserving actual column types.
     */
    static List<ColumnMeta> colMetas(List<TargetEntry> targets, TableDef def) {
        List<ColumnMeta> metas = new ArrayList<>();
        for (TargetEntry te : targets) {
            if (te.val() instanceof ColumnRef cr && cr.isStar()) {
                for (ColumnDef c : def.columns()) {
                    metas.add(colDefToMeta(c, def));
                }
            } else if (te.val() instanceof ColumnRef cr && !cr.fields().isEmpty()) {
                String colName = cr.fields().getLast().toLowerCase();
                String label = (te.name() != null && !te.name().isBlank())
                        ? te.name().toLowerCase() : colName;
                ColumnDef cd = findColumn(def, colName);
                if (cd != null) {
                    metas.add(colDefToMeta(cd, label, def));
                } else {
                    metas.add(ColumnMeta.varchar(label));
                }
            } else {
                String label = (te.name() != null && !te.name().isBlank())
                        ? te.name().toLowerCase() : "?column?";
                metas.add(ColumnMeta.varchar(label));
            }
        }
        return metas;
    }

    private static ColumnMeta colDefToMeta(ColumnDef cd, TableDef def) {
        return colDefToMeta(cd, cd.name().toLowerCase(), def);
    }

    private static ColumnMeta colDefToMeta(ColumnDef cd, String label, TableDef def) {
        int jdbcType = ColumnMeta.pgOidToJdbcType(cd.type().oid());
        int nullable = cd.nullable()
                ? java.sql.ResultSetMetaData.columnNullable
                : java.sql.ResultSetMetaData.columnNoNulls;
        return new ColumnMeta(label, cd.name().toLowerCase(),
                def.name(), def.schemaName(),
                jdbcType, cd.type().name(), 0, 0, nullable);
    }

    private static ColumnDef findColumn(TableDef def, String name) {
        for (ColumnDef cd : def.columns()) {
            if (cd.name().equalsIgnoreCase(name)) return cd;
        }
        return null;
    }
}
