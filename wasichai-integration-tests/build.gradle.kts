plugins {
    id("wasichai.spring-module")
    id("wasichai.integration-test")
    `java-test-fixtures`
}

// never published (wasichai-bom skips it by name). every test group is a suite of its own: its own
// source set, compile, ktlint task and jvm. groups are written in parallel, and a slice needs a
// classpath that really lacks the other modules (an optional adapter switches on by class presence).
description = "Wasichai integration tests: the original's api tests against assembled test apps"

val wasichaiModules = listOf("views", "forms", "pages", "workflow", "automation", "documents", "gis", "agent", "notifications", "files")

dependencies {
    // P2's wiring tests: every module in one context runner, no database
    testImplementation(project(":wasichai-test"))
    wasichaiModules.forEach { testImplementation(project(":wasichai-$it")) }

    // shared by every suite: the full test app, its base class, the module route table, the slice checks
    testFixturesApi(project(":wasichai-test"))
    testFixturesApi(project(":wasichai-spring-boot-starter"))
}

// every starter, on postgis
val fullAppSuites =
    listOf(
        "fullApp",
        "coreApiIt",
        "workflowIt",
        "automationIt",
        "documentsIt",
        "pagesIt",
        "viewsFormsIt",
        "layersIt",
        "agentIt",
        "notificationsIt",
        "coreParityIt",
        "wireParityIt",
        "schemaParityIt"
    )

// one module alone with core: its starter and nothing else. pages brings forms, its one hard dependency.
val sliceSuites: Map<String, List<String>> =
    mapOf(
        "coreOnly" to emptyList(),
        "viewsOnly" to listOf("views"),
        "formsOnly" to listOf("forms"),
        "pagesOnly" to listOf("pages"),
        "workflowOnly" to listOf("workflow"),
        "automationOnly" to listOf("automation"),
        "documentsOnly" to listOf("documents"),
        "gisOnly" to listOf("gis"),
        "agentOnly" to listOf("agent"),
        "notificationsOnly" to listOf("notifications"),
        "filesOnly" to listOf("files")
    )

val postgisSuites = fullAppSuites.toSet() + "gisOnly"
val allSuites = fullAppSuites + sliceSuites.keys

// external mode: both servers share name, user and password; only the postgis port differs
// blank counts as unset, same as WasichaiTestDatabase: otherwise gradle and the jvm disagree on the mode
val externalDb = providers.environmentVariable("WASICHAI_TEST_DB_HOST").map { it.isNotBlank() }.getOrElse(false)
val gisDbPort: String? = providers.environmentVariable("WASICHAI_TEST_GIS_DB_PORT").orNull

testing {
    suites {
        (fullAppSuites.associateWith { wasichaiModules } + sliceSuites).forEach { (suite, starters) ->
            register<JvmTestSuite>(suite) {
                useJUnitJupiter() // jupiter's version comes from the boot bom below, not from gradle's default
                dependencies {
                    implementation(testFixtures(project()))
                    implementation(platform(libs.spring.boot.bom))
                    starters.forEach { implementation(project(":wasichai-spring-boot-starter-$it")) }
                    runtimeOnly("org.junit.platform:junit-platform-launcher")
                }
                targets.all {
                    testTask.configure {
                        group = "verification"
                        description = "Integration suite '$suite' against a real database."
                        shouldRunAfter(tasks.named("test"))
                        // the database is no task input: a cache hit or up-to-date skip would pass a run
                        // that never touched the database it names
                        outputs.cacheIf { false }
                        outputs.upToDateWhen { false }
                        // the suites decide when the assistant exists, not the developer's shell. every env
                        // spelling that can reach the key (EmbabelGate, relaxed binding) is blanked.
                        listOf(
                            "ANTHROPIC_API_KEY",
                            "WASICHAI_AGENT_API_KEY",
                            "WASICHAI_AGENT_APIKEY",
                            "EMBABEL_AGENT_PLATFORM_MODELS_ANTHROPIC_API_KEY",
                            "SPRING_AI_ANTHROPIC_API_KEY"
                        ).forEach { environment(it, "") }
                        if (suite in postgisSuites) {
                            systemProperty("wasichai.test.db.image", "postgis/postgis:18-3.6")
                            if (gisDbPort != null) environment("WASICHAI_TEST_DB_PORT", gisDbPort)
                            val missingGisPort = externalDb && gisDbPort == null
                            doFirst {
                                if (missingGisPort) {
                                    throw GradleException(
                                        "$name needs PostGIS: with WASICHAI_TEST_DB_HOST set, also set WASICHAI_TEST_GIS_DB_PORT " +
                                            "(the PostGIS server; database name, user and password are shared)"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ./gradlew integrationTest runs every suite; ./gradlew build at least compiles them
tasks.named("integrationTest") { dependsOn(allSuites) }
tasks.named("check") { dependsOn(allSuites.map { "${it}Classes" }) }
