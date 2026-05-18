package org.pgjava.sql.parser;

import org.pgjava.sql.ast.Stmt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Singleton that selects between the primary parser (pg_query_java JNI) and the
 * fallback parser (ANTLR4).
 *
 * <p>Primary parser platforms: Linux x86_64, Linux aarch64 (glibc), macOS, Windows x86_64.
 * Android/Termux, Alpine/musl, and GraalVM native-image without config use the fallback.
 */
public final class ParserProvider {
    private static final Logger log = LoggerFactory.getLogger(ParserProvider.class);

    private static final boolean NATIVE_AVAILABLE;

    static {
        NATIVE_AVAILABLE = tryLoadNative();
    }

    private ParserProvider() {}

    /**
     * Returns true if the native pg_query_java parser loaded successfully.
     * False means the ANTLR4 fallback is in use.
     */
    public static boolean isNativeAvailable() {
        return NATIVE_AVAILABLE;
    }

    /**
     * Parse {@code sql} and return the list of statements.
     * Uses the native pg_query_java parser when available, ANTLR4 fallback otherwise.
     */
    public static List<Stmt> parse(String sql) throws ParseException {
        if (NATIVE_AVAILABLE) {
            return NativeParser.parse(sql);
        }
        return AntlrParser.parse(sql);
    }

    // -------------------------------------------------------------------------

    private static boolean tryLoadNative() {
        try {
            // Phase 3: extract bundled native lib from JAR to tmpdir and load it.
            // For now, just attempt a Class.forName to see if it's on the classpath.
            Class<?> cls = Class.forName("com.pganalyze.pg_query.PgQuery");
            // isAvailable() confirms the .so actually loaded (class present ≠ native loaded)
            boolean avail = (boolean) cls.getMethod("isAvailable").invoke(null);
            if (!avail) {
                log.info("pg_query_java class found but native library not loaded — using ANTLR4 fallback parser");
                return false;
            }
            log.info("pg_query_java native parser available");
            return true;
        } catch (ClassNotFoundException e) {
            log.info("pg_query_java not available — using ANTLR4 fallback parser");
            return false;
        } catch (Exception e) {
            log.warn("pg_query_java load failed: {} — using ANTLR4 fallback parser", e.getMessage());
            return false;
        }
    }
}
