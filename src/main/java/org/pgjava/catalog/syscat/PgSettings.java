package org.pgjava.catalog.syscat;

import org.pgjava.catalog.*;
import org.pgjava.types.PgOid;
import org.pgjava.types.PgTypeRegistry;

import java.util.List;

/**
 * {@code pg_catalog.pg_settings} — GUC parameter rows.
 * Shows the key session parameters that clients commonly query.
 */
public final class PgSettings implements VirtualTable {

    public static final PgSettings INSTANCE = new PgSettings();

    private static final PgTypeRegistry REG = PgTypeRegistry.INSTANCE;

    private static final List<ColumnDef> COLS = List.of(
            ColumnDef.notNull("name",          1,  REG.byOid(PgOid.TEXT)),
            ColumnDef.notNull("setting",        2,  REG.byOid(PgOid.TEXT)),
            ColumnDef.of     ("unit",           3,  REG.byOid(PgOid.TEXT)),
            ColumnDef.of     ("category",       4,  REG.byOid(PgOid.TEXT)),
            ColumnDef.of     ("short_desc",     5,  REG.byOid(PgOid.TEXT)),
            ColumnDef.of     ("extra_desc",     6,  REG.byOid(PgOid.TEXT)),
            ColumnDef.notNull("context",        7,  REG.byOid(PgOid.TEXT)),
            ColumnDef.notNull("vartype",        8,  REG.byOid(PgOid.TEXT)),
            ColumnDef.notNull("source",         9,  REG.byOid(PgOid.TEXT)),
            ColumnDef.of     ("min_val",        10, REG.byOid(PgOid.TEXT)),
            ColumnDef.of     ("max_val",        11, REG.byOid(PgOid.TEXT)),
            ColumnDef.of     ("enumvals",       12, REG.byOid(PgOid.TEXT)),
            ColumnDef.notNull("boot_val",       13, REG.byOid(PgOid.TEXT)),
            ColumnDef.notNull("reset_val",      14, REG.byOid(PgOid.TEXT)),
            ColumnDef.of     ("sourcefile",     15, REG.byOid(PgOid.TEXT)),
            ColumnDef.of     ("sourceline",     16, REG.byOid(PgOid.INT4)),
            ColumnDef.notNull("pending_restart",17, REG.byOid(PgOid.BOOL))
    );

    private PgSettings() {}

    @Override public String name()   { return "pg_settings"; }
    @Override public String schema() { return "pg_catalog"; }
    @Override public List<ColumnDef> columns() { return COLS; }

    private static Object[] setting(String name, String val, String type, String bootVal) {
        return new Object[]{name, val, null, "Client Connection Defaults", null, null,
                "user", type, "default", null, null, null, bootVal, bootVal, null, null, false};
    }

    @Override
    public Iterable<Object[]> scan(CatalogManager catalog) {
        return List.of(
                setting("server_version",              "15.0",              "string",  "15.0"),
                setting("server_version_num",          "150000",            "integer", "150000"),
                setting("client_encoding",             "UTF8",              "string",  "UTF8"),
                setting("server_encoding",             "UTF8",              "string",  "UTF8"),
                setting("DateStyle",                   "ISO, MDY",          "string",  "ISO, MDY"),
                setting("IntervalStyle",               "postgres",          "enum",    "postgres"),
                setting("TimeZone",                    "UTC",               "string",  "UTC"),
                setting("integer_datetimes",           "on",                "bool",    "on"),
                setting("standard_conforming_strings", "on",                "bool",    "on"),
                setting("default_transaction_isolation","read committed",   "enum",    "read committed"),
                setting("max_connections",             "100",               "integer", "100"),
                setting("search_path",                 "\"$user\", public", "string",  "\"$user\", public")
        );
    }
}
