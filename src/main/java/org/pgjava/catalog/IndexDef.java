package org.pgjava.catalog;

import java.util.List;

/** Definition of an index. */
public record IndexDef(
        long          oid,
        String        name,
        String        schemaName,
        String        tableName,
        long          tableOid,      // OID of the table this index belongs to
        List<IndexColumn> columns,
        boolean       unique,
        boolean       primary,       // true if this is the PK index
        String        accessMethod   // "btree", "hash", etc.
) {}
