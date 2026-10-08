plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

group = "wasichai"

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

java {
    withSourcesJar()
}

// ktlint reads the repo .editorconfig
ktlint {
    version.set(libs.findVersion("ktlintTool").get().requiredVersion)
}

// a plain library (no spring-module) still needs a test runner
dependencies {
    // security floors over the boot bom: highest version wins, and they ship in the published metadata
    api(platform(lib("jackson-bom")))
    api(platform(lib("jackson2-bom")))
    constraints {
        api(lib("scram-client"))
        api(lib("scram-common"))
        // ktlint's own classpath, not the library's: never published
        add("ktlint", lib("ktlint-logback"))
    }

    testImplementation(platform(lib("spring-boot-bom")))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// unit tests always run; container-backed ones go through integrationTest
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// format rewrites sources in place: a cache hit would skip the rewrite and leave them unformatted
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask>().configureEach {
    outputs.cacheIf("formats sources in place") { false }
}

// `ktlintCheck ktlintFormat` in one invocation: check the formatted sources, not the old ones
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask>().configureEach {
    mustRunAfter(tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask>())
}

// the per-source-set "ktlint<SourceSet>Format" report task (ktlint-gradle's GenerateReportsTask) sits
// downstream of the rewrite above and is @CacheableTask by default: same reasoning, same fix
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.GenerateReportsTask>().configureEach {
    if (name.endsWith("Format")) {
        outputs.cacheIf("part of the format task group, not just the report") { false }
    }
}
