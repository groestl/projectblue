package org.pgjava.harness;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opt-in marker for tests that compare {@code pg_catalog} or {@code information_schema}
 * query results between pgjava and embedded-postgres.
 *
 * <p>By default {@link DualExecutor} skips cross-comparison for catalog queries because
 * OIDs, system object names, and catalog data differ between instances. Apply this
 * annotation (and design the query to exclude system-OID columns) to enable comparison.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface CatalogTest {
}
