package org.pgjava.harness;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Semantic marker for SQL correctness tests that use {@link DualExecutor}.
 * Equivalent to {@link Test} — the extension is registered at the <em>class</em> level via
 * {@code @ExtendWith(GoldenExtension.class)}, not here.
 *
 * <p>Usage:
 * <pre>{@code
 * @ExtendWith(GoldenExtension.class)
 * class MyTest {
 *     @GoldenTest
 *     void selectWorks(DualExecutor db) throws Exception {
 *         db.assertQuery("SELECT 1");
 *     }
 * }
 * }</pre>
 *
 * <p><b>Why not {@code @ExtendWith} here?</b> {@link org.junit.jupiter.api.extension.BeforeAllCallback}
 * is a container lifecycle callback invoked at the class level. Registering the extension via
 * a method-level annotation bypasses {@code beforeAll}, leaving the PostgreSQL connection
 * uninitialized.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Test
public @interface GoldenTest {
}
