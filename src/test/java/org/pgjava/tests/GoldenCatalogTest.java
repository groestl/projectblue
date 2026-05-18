package org.pgjava.tests;

import org.junit.jupiter.api.extension.ExtendWith;
import org.pgjava.harness.CatalogTest;
import org.pgjava.harness.DualExecutor;
import org.pgjava.harness.GoldenExtension;

/**
 * Golden tests for pg_catalog virtual tables and system functions.
 * Compares pgjava results against real PostgreSQL.
 */
@ExtendWith(GoldenExtension.class)
@CatalogTest
class GoldenCatalogTest {

    // =========================================================================
    // pg_am — access methods
    // =========================================================================

    @org.junit.jupiter.api.Test
    void pgAmHeapAndBtree(DualExecutor db) throws Exception {
        // Only check heap and btree — PG has more AMs (hash, gist, gin, etc.)
        db.assertCatalogQuery(
                "SELECT amname, amtype FROM pg_catalog.pg_am WHERE amname IN ('heap', 'btree') ORDER BY amname");
    }

    @org.junit.jupiter.api.Test
    void pgAmJoinPgClass(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE am_test(id INT)");
        db.assertCatalogQuery("""
                SELECT c.relname, a.amname
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_am a ON a.oid = c.relam
                WHERE c.relname = 'am_test'
                """);
    }

    // =========================================================================
    // pg_attrdef — column defaults
    // =========================================================================

    @org.junit.jupiter.api.Test
    void pgAttrdefExists(DualExecutor db) throws Exception {
        db.assertCatalogQuery(
                "SELECT count(*) AS cnt FROM pg_catalog.pg_attrdef");
    }

    @org.junit.jupiter.api.Test
    void pgAttrdefShowsDefaults(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE def_test(id SERIAL, name TEXT DEFAULT 'hello')");
        db.assertCatalogQuery("""
                SELECT a.attname, d.adnum
                FROM pg_catalog.pg_attrdef d
                JOIN pg_catalog.pg_attribute a ON a.attrelid = d.adrelid AND a.attnum = d.adnum
                JOIN pg_catalog.pg_class c ON c.oid = d.adrelid
                WHERE c.relname = 'def_test'
                ORDER BY d.adnum
                """);
    }

    // =========================================================================
    // pg_description — object comments (empty stub)
    // =========================================================================

    @org.junit.jupiter.api.Test
    void pgDescriptionQueryable(DualExecutor db) throws Exception {
        // pg_description exists and is queryable; PG has system descriptions, we have none.
        // Just verify structure by querying with a filter that returns zero rows on both.
        db.assertCatalogQuery(
                "SELECT objoid, classoid, objsubid, description FROM pg_catalog.pg_description WHERE objoid = -1");
    }

    // =========================================================================
    // format_type()
    // =========================================================================

    @org.junit.jupiter.api.Test
    void formatTypeBasic(DualExecutor db) throws Exception {
        db.assertCatalogQuery("""
                SELECT format_type(23, -1) AS int4_type,
                       format_type(25, -1) AS text_type,
                       format_type(16, -1) AS bool_type,
                       format_type(20, -1) AS int8_type,
                       format_type(701, -1) AS float8_type
                """);
    }

    @org.junit.jupiter.api.Test
    void formatTypeWithTypmod(DualExecutor db) throws Exception {
        db.assertCatalogQuery("""
                SELECT format_type(1043, 104) AS varchar100,
                       format_type(1700, 655366) AS numeric10_2
                """);
    }

    @org.junit.jupiter.api.Test
    void formatTypeFromTable(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE ft_test(id INTEGER, name VARCHAR(50), val NUMERIC(8,2))");
        db.assertCatalogQuery("""
                SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS data_type
                FROM pg_catalog.pg_attribute a
                JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
                WHERE c.relname = 'ft_test' AND a.attnum > 0
                ORDER BY a.attnum
                """);
    }

    // =========================================================================
    // pg_get_expr()
    // =========================================================================

    @org.junit.jupiter.api.Test
    void pgGetExprNull(DualExecutor db) throws Exception {
        db.assertCatalogQuery("SELECT pg_get_expr(NULL, 0) AS result");
    }

    // =========================================================================
    // col_description()
    // =========================================================================

    @org.junit.jupiter.api.Test
    void colDescriptionNoComment(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE cd_test(id INT)");
        db.assertCatalogQuery("""
                SELECT col_description(c.oid, 1) AS col_desc
                FROM pg_catalog.pg_class c
                WHERE c.relname = 'cd_test'
                """);
    }

    // =========================================================================
    // pg_class.relam populated correctly
    // =========================================================================

    @org.junit.jupiter.api.Test
    void pgClassRelamForIndex(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE relam_test(id INT PRIMARY KEY)");
        db.assertCatalogQuery("""
                SELECT c.relname, a.amname
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_am a ON a.oid = c.relam
                WHERE c.relname LIKE 'relam_test%'
                ORDER BY c.relname
                """);
    }

    // =========================================================================
    // pg_get_userbyid()
    // =========================================================================

    @org.junit.jupiter.api.Test
    void pgGetUserById(DualExecutor db) throws Exception {
        db.assertCatalogQuery("SELECT pg_get_userbyid(10) AS owner_name");
    }

    // =========================================================================
    // pg_table_is_visible()
    // =========================================================================

    @org.junit.jupiter.api.Test
    void pgTableIsVisible(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE vis_test(id INT)");
        db.assertCatalogQuery("""
                SELECT c.relname, pg_table_is_visible(c.oid) AS visible
                FROM pg_catalog.pg_class c
                WHERE c.relname = 'vis_test'
                """);
    }

    // =========================================================================
    // pg_constraint.conkey populated
    // =========================================================================

    @org.junit.jupiter.api.Test
    void pgConstraintConkey(DualExecutor db) throws Exception {
        db.execute("""
                CREATE TABLE ck_parent(id INT PRIMARY KEY, name TEXT UNIQUE);
                CREATE TABLE ck_child(id INT, pid INT REFERENCES ck_parent(id));
                """);
        db.assertCatalogQuery("""
                SELECT c.relname, con.contype, con.conkey
                FROM pg_catalog.pg_constraint con
                JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
                WHERE c.relname IN ('ck_parent', 'ck_child')
                  AND con.contype IN ('p', 'u', 'f')
                ORDER BY c.relname, con.contype
                """);
    }

    // =========================================================================
    // array_agg returns NULL for empty input
    // =========================================================================

    @org.junit.jupiter.api.Test
    void arrayAggEmpty(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE agg_empty(id INT)");
        db.assertQuery("SELECT array_agg(id) AS result FROM agg_empty");
    }

    // =========================================================================
    // pg_get_indexdef — full definition and single column
    // =========================================================================

    @org.junit.jupiter.api.Test
    void pgGetIndexdefFull(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE idxdef_test(id INT PRIMARY KEY, name TEXT)");
        db.execute("CREATE INDEX idxdef_name ON idxdef_test(name)");
        db.assertCatalogQuery("""
                SELECT pg_get_indexdef(i.indexrelid) AS def
                FROM pg_catalog.pg_index i
                JOIN pg_catalog.pg_class c ON c.oid = i.indrelid
                JOIN pg_catalog.pg_class ic ON ic.oid = i.indexrelid
                WHERE c.relname = 'idxdef_test' AND ic.relname = 'idxdef_name'
                """);
    }

    @org.junit.jupiter.api.Test
    void pgGetIndexdefColumn(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE idxcol_test(a INT, b TEXT)");
        db.execute("CREATE INDEX idxcol_ab ON idxcol_test(a, b)");
        db.assertCatalogQuery("""
                SELECT pg_get_indexdef(i.indexrelid, 1, true) AS col1,
                       pg_get_indexdef(i.indexrelid, 2, true) AS col2
                FROM pg_catalog.pg_index i
                JOIN pg_catalog.pg_class c ON c.oid = i.indrelid
                JOIN pg_catalog.pg_class ic ON ic.oid = i.indexrelid
                WHERE c.relname = 'idxcol_test' AND ic.relname = 'idxcol_ab'
                """);
    }

    // =========================================================================
    // pg_get_constraintdef — PK, UNIQUE, FK, CHECK
    // =========================================================================

    @org.junit.jupiter.api.Test
    void pgGetConstraintdefPk(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE condef_pk(id INT PRIMARY KEY)");
        db.assertCatalogQuery("""
                SELECT pg_get_constraintdef(con.oid) AS def
                FROM pg_catalog.pg_constraint con
                JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
                WHERE c.relname = 'condef_pk' AND con.contype = 'p'
                """);
    }

    @org.junit.jupiter.api.Test
    void pgGetConstraintdefFk(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE condef_parent(id INT PRIMARY KEY)");
        db.execute("CREATE TABLE condef_child(id INT, pid INT REFERENCES condef_parent(id))");
        db.assertCatalogQuery("""
                SELECT pg_get_constraintdef(con.oid) AS def
                FROM pg_catalog.pg_constraint con
                JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
                WHERE c.relname = 'condef_child' AND con.contype = 'f'
                """);
    }

    // =========================================================================
    // pg_get_viewdef
    // =========================================================================

    @org.junit.jupiter.api.Test
    void pgGetViewdefSimple(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE vdef_test(id INT, name TEXT)");
        db.execute("CREATE VIEW vdef_view AS SELECT id, name FROM vdef_test WHERE id > 0");
        db.assertCatalogQuery("""
                SELECT pg_get_viewdef(c.oid) AS def
                FROM pg_catalog.pg_class c
                WHERE c.relname = 'vdef_view' AND c.relkind = 'v'
                """);
    }

    @org.junit.jupiter.api.Test
    void pgGetViewdefJoin(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE vj_a(id INT PRIMARY KEY, val TEXT)");
        db.execute("CREATE TABLE vj_b(id INT, a_id INT, note TEXT)");
        db.execute("""
                CREATE VIEW vj_view AS
                SELECT a.id, a.val, b.note
                FROM vj_a a
                JOIN vj_b b ON a.id = b.a_id
                """);
        db.assertCatalogQuery("""
                SELECT pg_get_viewdef(c.oid) AS def
                FROM pg_catalog.pg_class c
                WHERE c.relname = 'vj_view' AND c.relkind = 'v'
                """);
    }

    @org.junit.jupiter.api.Test
    void pgGetViewdefLeftJoin(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE vlj_a(id INT, name TEXT)");
        db.execute("CREATE TABLE vlj_b(a_id INT, extra TEXT)");
        db.execute("""
                CREATE VIEW vlj_view AS
                SELECT a.id, a.name, b.extra
                FROM vlj_a a
                LEFT JOIN vlj_b b ON a.id = b.a_id
                WHERE a.name IS NOT NULL
                """);
        db.assertCatalogQuery("""
                SELECT pg_get_viewdef(c.oid) AS def
                FROM pg_catalog.pg_class c
                WHERE c.relname = 'vlj_view' AND c.relkind = 'v'
                """);
    }

    @org.junit.jupiter.api.Test
    void pgGetViewdefSubquery(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE vsq_t(id INT, cat TEXT)");
        db.execute("""
                CREATE VIEW vsq_view AS
                SELECT id FROM vsq_t
                WHERE cat IN (SELECT cat FROM vsq_t WHERE id < 10)
                """);
        db.assertCatalogQuery("""
                SELECT pg_get_viewdef(c.oid) AS def
                FROM pg_catalog.pg_class c
                WHERE c.relname = 'vsq_view' AND c.relkind = 'v'
                """);
    }

    @org.junit.jupiter.api.Test
    void pgGetViewdefExists(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE vex_a(id INT)");
        db.execute("CREATE TABLE vex_b(a_id INT)");
        db.execute("""
                CREATE VIEW vex_view AS
                SELECT id FROM vex_a a
                WHERE EXISTS (SELECT 1 FROM vex_b b WHERE b.a_id = a.id)
                """);
        db.assertCatalogQuery("""
                SELECT pg_get_viewdef(c.oid) AS def
                FROM pg_catalog.pg_class c
                WHERE c.relname = 'vex_view' AND c.relkind = 'v'
                """);
    }

    @org.junit.jupiter.api.Test
    void pgGetViewdefAggGroupBy(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE vag_t(cat TEXT, amount INT)");
        db.execute("""
                CREATE VIEW vag_view AS
                SELECT cat, count(*) AS cnt, sum(amount) AS total
                FROM vag_t
                GROUP BY cat
                HAVING count(*) > 1
                """);
        db.assertCatalogQuery("""
                SELECT pg_get_viewdef(c.oid) AS def
                FROM pg_catalog.pg_class c
                WHERE c.relname = 'vag_view' AND c.relkind = 'v'
                """);
    }

    @org.junit.jupiter.api.Test
    void pgGetViewdefCase(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE vca_t(id INT, status INT)");
        db.execute("""
                CREATE VIEW vca_view AS
                SELECT id,
                       CASE WHEN status = 1 THEN 'active'
                            WHEN status = 2 THEN 'inactive'
                            ELSE 'unknown' END AS label
                FROM vca_t
                """);
        db.assertCatalogQuery("""
                SELECT pg_get_viewdef(c.oid) AS def
                FROM pg_catalog.pg_class c
                WHERE c.relname = 'vca_view' AND c.relkind = 'v'
                """);
    }

    @org.junit.jupiter.api.Test
    void pgGetViewdefCast(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE vct_t(id INT, val NUMERIC(10,2))");
        db.execute("""
                CREATE VIEW vct_view AS
                SELECT id, val::text AS val_text, CAST(id AS bigint) AS big_id
                FROM vct_t
                """);
        db.assertCatalogQuery("""
                SELECT pg_get_viewdef(c.oid) AS def
                FROM pg_catalog.pg_class c
                WHERE c.relname = 'vct_view' AND c.relkind = 'v'
                """);
    }

    @org.junit.jupiter.api.Test
    void pgGetViewdefLikeBetween(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE vlb_t(id INT, name TEXT)");
        db.execute("""
                CREATE VIEW vlb_view AS
                SELECT id, name FROM vlb_t
                WHERE name LIKE 'foo%' AND id BETWEEN 1 AND 100
                """);
        db.assertCatalogQuery("""
                SELECT pg_get_viewdef(c.oid) AS def
                FROM pg_catalog.pg_class c
                WHERE c.relname = 'vlb_view' AND c.relkind = 'v'
                """);
    }

    @org.junit.jupiter.api.Test
    void pgGetViewdefDistinctLimit(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE vdl_t(id INT, cat TEXT)");
        db.execute("""
                CREATE VIEW vdl_view AS
                SELECT DISTINCT cat FROM vdl_t
                ORDER BY cat
                LIMIT 10 OFFSET 5
                """);
        db.assertCatalogQuery("""
                SELECT pg_get_viewdef(c.oid) AS def
                FROM pg_catalog.pg_class c
                WHERE c.relname = 'vdl_view' AND c.relkind = 'v'
                """);
    }

    @org.junit.jupiter.api.Test
    void pgGetViewdefUnion(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE vun_a(id INT, name TEXT)");
        db.execute("CREATE TABLE vun_b(id INT, name TEXT)");
        db.execute("""
                CREATE VIEW vun_view AS
                SELECT id, name FROM vun_a
                UNION ALL
                SELECT id, name FROM vun_b
                """);
        db.assertCatalogQuery("""
                SELECT pg_get_viewdef(c.oid) AS def
                FROM pg_catalog.pg_class c
                WHERE c.relname = 'vun_view' AND c.relkind = 'v'
                """);
    }

    @org.junit.jupiter.api.Test
    void pgGetViewdefCoalesce(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE vcl_t(id INT, a TEXT, b TEXT)");
        db.execute("""
                CREATE VIEW vcl_view AS
                SELECT id, COALESCE(a, b, 'default') AS merged
                FROM vcl_t
                """);
        db.assertCatalogQuery("""
                SELECT pg_get_viewdef(c.oid) AS def
                FROM pg_catalog.pg_class c
                WHERE c.relname = 'vcl_view' AND c.relkind = 'v'
                """);
    }

    @org.junit.jupiter.api.Test
    void pgGetViewdefSubselect(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE vss_t(id INT, val INT)");
        db.execute("""
                CREATE VIEW vss_view AS
                SELECT id, (SELECT max(val) FROM vss_t) AS max_val
                FROM vss_t
                """);
        db.assertCatalogQuery("""
                SELECT pg_get_viewdef(c.oid) AS def
                FROM pg_catalog.pg_class c
                WHERE c.relname = 'vss_view' AND c.relkind = 'v'
                """);
    }

    @org.junit.jupiter.api.Test
    void pgGetViewdefInList(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE vin_t(id INT, cat TEXT)");
        db.execute("""
                CREATE VIEW vin_view AS
                SELECT id, cat FROM vin_t
                WHERE cat IN ('a', 'b', 'c')
                """);
        db.assertCatalogQuery("""
                SELECT pg_get_viewdef(c.oid) AS def
                FROM pg_catalog.pg_class c
                WHERE c.relname = 'vin_view' AND c.relkind = 'v'
                """);
    }

    // =========================================================================
    // psql \dt style query
    // =========================================================================

    @org.junit.jupiter.api.Test
    void psqlListTablesQuery(DualExecutor db) throws Exception {
        db.execute("CREATE TABLE psql_t1(id INT)");
        db.execute("CREATE TABLE psql_t2(name TEXT)");
        db.assertCatalogQuery("""
                SELECT n.nspname AS schema,
                       c.relname AS name,
                       CASE c.relkind WHEN 'r' THEN 'table' WHEN 'v' THEN 'view' END AS type,
                       pg_get_userbyid(c.relowner) AS owner
                FROM pg_catalog.pg_class c
                LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind IN ('r','v')
                  AND n.nspname = 'public'
                  AND pg_table_is_visible(c.oid)
                ORDER BY c.relname
                """);
    }
}
