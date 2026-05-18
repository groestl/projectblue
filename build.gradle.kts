import java.net.URL

plugins {
    java
    antlr
    application
}

group = "org.pgjava"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") } // pg_query_java
}

val antlrVersion        = "4.13.2"
val junitVersion        = "5.12.2"
val nettyVersion        = "4.1.132.Final"
val slf4jVersion        = "2.0.16"
val logbackVersion      = "1.5.12"
val jacksonVersion      = "2.18.2"
val embeddedPgVersion   = "2.1.0"   // PINNED — do not float; behavior is defined against this version
val hikariVersion       = "5.1.0"
val flywayVersion       = "10.22.0"
val hibernateVersion    = "6.6.4.Final"
val liquibaseVersion    = "4.30.0"
val springVersion       = "6.2.1"
val springDataVersion   = "3.4.1"
val jooqVersion         = "3.19.15"

dependencies {
    // -------------------------------------------------------------------------
    // Parser — ANTLR4 (fallback parser, pure Java, works everywhere)
    // -------------------------------------------------------------------------
    antlr("org.antlr:antlr4:$antlrVersion")
    implementation("org.antlr:antlr4-runtime:$antlrVersion")

    // -------------------------------------------------------------------------
    // Parser — pg_query_java (primary parser, JNI binding to libpg_query)
    // Supported platforms: Linux x86_64, Linux aarch64 (glibc), macOS, Windows.
    // NOT available on Android/Termux or Alpine/musl — falls back to ANTLR4.
    // Uncomment when deploying on a supported platform:
    // -------------------------------------------------------------------------
    implementation("com.github.groestl:pg_query_java:v5.2.0")

    // -------------------------------------------------------------------------
    // Wire protocol server
    // -------------------------------------------------------------------------
    implementation("io.netty:netty-all:$nettyVersion")

    // -------------------------------------------------------------------------
    // Logging
    // -------------------------------------------------------------------------
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // -------------------------------------------------------------------------
    // JSON (for JSON/JSONB type support — used in later phases)
    // -------------------------------------------------------------------------
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")

    // -------------------------------------------------------------------------
    // Test
    // -------------------------------------------------------------------------
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Golden standard — real PostgreSQL for correctness comparison (PINNED version)
    testImplementation("io.zonky.test:embedded-postgres:$embeddedPgVersion")

    // PostgreSQL JDBC driver (used by embedded-postgres and test harness)
    testImplementation("org.postgresql:postgresql:42.7.4")

    // -------------------------------------------------------------------------
    // ORM compatibility smoke tests
    // -------------------------------------------------------------------------
    testImplementation("com.zaxxer:HikariCP:$hikariVersion")
    testImplementation("org.flywaydb:flyway-core:$flywayVersion")
    testImplementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")
    testImplementation("org.hibernate.orm:hibernate-core:$hibernateVersion")
    // Liquibase smoke test
    testImplementation("org.liquibase:liquibase-core:$liquibaseVersion")
    // Spring Data JPA smoke test
    testImplementation("org.springframework.data:spring-data-jpa:$springDataVersion")
    testImplementation("org.springframework:spring-orm:$springVersion")
    testImplementation("org.springframework:spring-context:$springVersion")
    testImplementation("org.springframework:spring-test:$springVersion")
    // jOOQ codegen smoke test
    testImplementation("org.jooq:jooq-meta:$jooqVersion")
    testImplementation("org.jooq:jooq-codegen:$jooqVersion")
}

// -------------------------------------------------------------------------
// ANTLR4 — generate parser. Grammar files must be present; download them with:
//   ./gradlew downloadGrammar
// -------------------------------------------------------------------------
// Grammar files live in the ANTLR plugin's standard source root (src/main/antlr).
// Subdirectory matches the generated package: org/pgjava/sql/parser/antlr/.
val grammarDir = file("src/main/antlr/org/pgjava/sql/parser/antlr")
val grammarPresent = file("$grammarDir/PostgreSQLLexer.g4").exists()

tasks.register("downloadGrammar") {
    description = "Download ANTLR4 PostgreSQL grammar files from grammars-v4 (run once)"
    group = "build setup"
    doLast {
        val base = "https://raw.githubusercontent.com/antlr/grammars-v4/master/sql/postgresql"
        grammarDir.mkdirs()
        listOf("PostgreSQLLexer.g4", "PostgreSQLParser.g4").forEach { name ->
            val dest = File(grammarDir, name)
            if (!dest.exists()) {
                logger.lifecycle("Downloading $name …")
                URL("$base/$name").openStream().use { src: java.io.InputStream ->
                    dest.outputStream().use { dst: java.io.OutputStream ->
                        src.copyTo(dst)
                    }
                }
                logger.lifecycle("  → $dest")
            } else {
                logger.lifecycle("$name already present — skipping")
            }
        }
    }
}

// Disable the Gradle ANTLR plugin's built-in task — it cannot handle split lexer/parser
// grammars reliably because it performs dependency resolution before any .tokens files are
// generated. We replace it with two sequential JavaExec tasks below.
tasks.generateGrammarSource {
    enabled = false
}

// Step 1: generate only the lexer — produces PostgreSQLLexer.tokens in outDir.
// Step 2: generate both lexer + parser with -lib pointing to outDir so the parser can
//         find PostgreSQLLexer.tokens when resolving tokenVocab = PostgreSQLLexer.
val antlrOutDir = layout.buildDirectory
    .dir("generated-src/antlr/main/org/pgjava/sql/parser/antlr")
    .get().asFile

val lexerFile  = file("$grammarDir/PostgreSQLLexer.g4")
val parserFile = file("$grammarDir/PostgreSQLParser.g4")

val generateLexer = tasks.register<JavaExec>("generateLexer") {
    enabled = grammarPresent
    group = "build"
    description = "Step 1: compile PostgreSQLLexer.g4 → produces .tokens file"
    if (grammarPresent) {
        doFirst { antlrOutDir.mkdirs() }
        classpath = configurations["antlr"]
        mainClass.set("org.antlr.v4.Tool")
        args("-package", "org.pgjava.sql.parser.antlr",
             "-o", antlrOutDir.absolutePath,
             "-no-listener", "-visitor",
             lexerFile.absolutePath)
        inputs.file(lexerFile)
        outputs.dir(antlrOutDir)
    }
}

tasks.register<JavaExec>("generateGrammarSourceCustom") {
    enabled = grammarPresent
    group = "build"
    description = "Step 2: compile PostgreSQLParser.g4 (tokens file must already exist)"
    if (grammarPresent) {
        dependsOn(generateLexer)
        classpath = configurations["antlr"]
        mainClass.set("org.antlr.v4.Tool")
        args("-package", "org.pgjava.sql.parser.antlr",
             "-o", antlrOutDir.absolutePath,
             "-lib", antlrOutDir.absolutePath,
             "-no-listener", "-visitor",
             lexerFile.absolutePath,
             parserFile.absolutePath)
        inputs.files(lexerFile, parserFile)
        outputs.dir(antlrOutDir)
    }
}

// -------------------------------------------------------------------------
// PL/pgSQL grammar — separate lexer/parser (like PostgreSQL's own pl_scanner/pl_gram)
// -------------------------------------------------------------------------
val plpgsqlLexerFile  = file("$grammarDir/PlPgSqlLexer.g4")
val plpgsqlParserFile = file("$grammarDir/PlPgSqlParser.g4")
val plpgsqlPresent    = plpgsqlLexerFile.exists()

val generatePlPgSqlLexer = tasks.register<JavaExec>("generatePlPgSqlLexer") {
    enabled = plpgsqlPresent
    group = "build"
    description = "Compile PlPgSqlLexer.g4 → produces .tokens file"
    if (plpgsqlPresent) {
        doFirst { antlrOutDir.mkdirs() }
        classpath = configurations["antlr"]
        mainClass.set("org.antlr.v4.Tool")
        args("-package", "org.pgjava.sql.parser.antlr",
             "-o", antlrOutDir.absolutePath,
             "-no-listener", "-visitor",
             plpgsqlLexerFile.absolutePath)
        inputs.file(plpgsqlLexerFile)
        outputs.dir(antlrOutDir)
    }
}

tasks.register<JavaExec>("generatePlPgSqlParser") {
    enabled = plpgsqlPresent
    group = "build"
    description = "Compile PlPgSqlParser.g4 (tokens file must already exist)"
    if (plpgsqlPresent) {
        dependsOn(generatePlPgSqlLexer)
        classpath = configurations["antlr"]
        mainClass.set("org.antlr.v4.Tool")
        args("-package", "org.pgjava.sql.parser.antlr",
             "-o", antlrOutDir.absolutePath,
             "-lib", antlrOutDir.absolutePath,
             "-no-listener", "-visitor",
             plpgsqlLexerFile.absolutePath,
             plpgsqlParserFile.absolutePath)
        inputs.files(plpgsqlLexerFile, plpgsqlParserFile)
        outputs.dir(antlrOutDir)
    }
}

tasks.compileJava {
    if (grammarPresent) dependsOn("generateGrammarSourceCustom")
    if (plpgsqlPresent) dependsOn("generatePlPgSqlParser")
}

sourceSets.main {
    if (grammarPresent) {
        // ANTLR-generated classes (PostgreSQLLexer, PostgreSQLParser)
        java.srcDir(layout.buildDirectory.dir("generated-src/antlr/main"))
        // Base classes that reference the generated types (can only compile after grammar gen)
        java.srcDir("src/main/antlr-support")
    }
}

// -------------------------------------------------------------------------
// Test configuration
// -------------------------------------------------------------------------
tasks.test {
    useJUnitPlatform()
    // Show standard output/error for failing tests
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Allow tests to be skipped via assumeSupported() without failing the build
    systemProperty("junit.jupiter.execution.timeout.default", "30s")
}

// -------------------------------------------------------------------------
// Application entry point (wire protocol server)
// -------------------------------------------------------------------------
application {
    mainClass = "org.pgjava.server.PgServer"
}
