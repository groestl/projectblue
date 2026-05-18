package org.pgjava.harness;

import org.pgjava.sql.parser.ParserProvider;

/** Condition methods for JUnit 5 {@code @DisabledIf} / {@code @EnabledIf}. */
public final class ParserConditions {
    private ParserConditions() {}

    /** Returns true when the native pg_query_java parser is NOT available. */
    public static boolean isFallbackParser() {
        return !ParserProvider.isNativeAvailable();
    }
}
