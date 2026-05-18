package org.pgjava.harness;

import org.junit.jupiter.api.condition.DisabledIf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Skips the test when running on the ANTLR4 fallback parser (i.e. when pg_query_java
 * JNI library is not available — Android, Alpine/musl, GraalVM native-image).
 *
 * <p>Use for tests that exercise obscure SQL syntax which the community ANTLR4 grammar
 * may not support but the real PostgreSQL Bison grammar does.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@DisabledIf("org.pgjava.harness.ParserConditions#isFallbackParser")
public @interface RequiresPrimaryParser {
}
