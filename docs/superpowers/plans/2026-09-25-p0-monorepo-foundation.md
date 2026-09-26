# P0 — Monorepo foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the empty `chawpi` repo into a buildable polyglot monorepo (Gradle + yarn workspaces) with convention plugins, BOM, infra, preserved sapgis docs/ADRs/examples, Conventional-Commit guardrails and release automation — before any library code moves.

**Architecture:** One Gradle build rooted at the repo root; convention plugins live in the included build `backend/build-logic`; backend modules are auto-discovered from `backend/*`, `backend/starters/*` and `examples/*/server`. One yarn (classic) workspace root at the repo root covering `frontend/packages/*` and `examples/*/web`. Everything from `../sapgis` is copied (no git history), with identifiers renamed `sapgis → chawpi`.

**Tech Stack:** Gradle 9.7.1 (Kotlin DSL, version catalog, precompiled script plugins, TestKit), Kotlin 2.4.20, JDK 25, ktlint-gradle 14.2.0 / ktlint 1.7.1, Spring Boot 4.1.1 BOM, Node 26, yarn 1.22, prettier 3.8.2, husky + commitlint, GitHub Actions, release-please.

**Spec:** `docs/superpowers/specs/2026-09-25-chawpi-libraries-design.md`

## Global Constraints

- **NEVER run `git commit` or `git push`.** Leave all changes in the working tree. `git add` is allowed only if a tool needs it; prefer not to.
- groupId and Kotlin base package: `chawpi` (NOT `io.chawpi`). npm scope: `@chawpi`. Initial version `0.1.0`.
- Source of truth for sapgis content: `/Users/jorge/IdeaProjects/sapgis` (read-only — never modify it).
- `.editorconfig` at repo root is already the sapgis one, verbatim. Do not edit it. All code formatted by it: Kotlin via ktlint (`./gradlew ktlintFormat`), TS/JSON/YAML via prettier (`yarn format`).
- Versions only in `gradle/libs.versions.toml` (backend) and `package.json` (frontend). Same versions as sapgis.
- JDK 25, Node 26, yarn 1.22 (classic), Gradle wrapper 9.7.1.
- Code, identifiers, comments in English. Comments caveman style: short, say why, never restate code.
- Rename rules for copied text: `com.sapgis`→`chawpi`, `com/sapgis`→`chawpi`, `SAPGIS_`→`CHAWPI_`, `SAPGIS`→`Chawpi`, `Sapgis`→`Chawpi`, `sapgis`→`chawpi`, `GEOFORGE_`→`CHAWPI_`, `GeoForge Core`→`Chawpi`, `GeoForge`→`Chawpi`. `examples/perene` path → `examples/gis-sample/perene`.
- Docker daemon may be down; nothing in P0 needs it running (`docker compose config` works offline).

## Review Focus

1. Fresh clone, `./gradlew build` from the repo root with nothing prebuilt → must succeed (build-logic compiles first). Covered in Task 2 Step 6.
2. Settings auto-discovery must ignore dirs without `build.gradle.kts` (`backend/build`, `backend/build-logic`, empty example folders). Covered in Task 1 Step 4.
3. `publishToMavenLocal` with no `GITHUB_ACTOR`/`GITHUB_TOKEN` in the environment must work; only `publish` to GitHub Packages needs them. Covered in Task 2 TestKit test and Task 3 Step 3.
4. `yarn format:check` must not scan docs, backend, build outputs or `.idea` (would reformat ADRs/HISTORY). Covered in Task 4 Step 5.
5. A badly formatted Kotlin file must fail the build (editorconfig actually enforced, not just present). Covered in Task 2 TestKit test.

---

### Task 1: Gradle root, wrapper, version catalog, gitignore

**Files:**
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` (copied from sapgis)
- Create: `gradle/libs.versions.toml`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`

**Interfaces:**
- Produces: version catalog alias `libs` (entries below, later tasks use them), Gradle property `version` in `gradle.properties` (release-please updates it), project auto-discovery rule.

- [ ] **Step 1: Copy the wrapper**

```bash
cd /Users/jorge/IdeaProjects/chawpi
cp ../sapgis/gradlew ../sapgis/gradlew.bat .
mkdir -p gradle/wrapper && cp ../sapgis/gradle/wrapper/gradle-wrapper.jar ../sapgis/gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
chmod +x gradlew
```

- [ ] **Step 2: Write `gradle/libs.versions.toml`** (sapgis catalog + build-logic plugin artifacts)

```toml
# Single source of truth for every backend dependency version.
[versions]
kotlin = "2.4.20"
springBoot = "4.1.1"
ktlint = "14.2.0"
ktlintTool = "1.7.1"
postgresqlJdbc = "42.7.13"
flyway = "12.4.0"
testcontainers = "2.0.5"
embabel = "1.5.2"

[libraries]
spring-boot-bom = { module = "org.springframework.boot:spring-boot-dependencies", version.ref = "springBoot" }
spring-boot-autoconfigure = { module = "org.springframework.boot:spring-boot-autoconfigure" }
spring-boot-starter-webflux = { module = "org.springframework.boot:spring-boot-starter-webflux" }
spring-boot-starter-data-r2dbc = { module = "org.springframework.boot:spring-boot-starter-data-r2dbc" }
spring-boot-starter-security = { module = "org.springframework.boot:spring-boot-starter-security" }
spring-boot-starter-oauth2-resource-server = { module = "org.springframework.boot:spring-boot-starter-oauth2-resource-server" }
spring-boot-starter-validation = { module = "org.springframework.boot:spring-boot-starter-validation" }
spring-boot-starter-actuator = { module = "org.springframework.boot:spring-boot-starter-actuator" }
spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test" }
spring-boot-starter-webflux-test = { module = "org.springframework.boot:spring-boot-starter-webflux-test" }
spring-boot-testcontainers = { module = "org.springframework.boot:spring-boot-testcontainers" }

jackson-module-kotlin = { module = "tools.jackson.module:jackson-module-kotlin" }
kotlin-reflect = { module = "org.jetbrains.kotlin:kotlin-reflect" }
kotlinx-coroutines-reactor = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-reactor" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test" }

r2dbc-postgresql = { module = "org.postgresql:r2dbc-postgresql" }
postgresql-jdbc = { module = "org.postgresql:postgresql", version.ref = "postgresqlJdbc" }
flyway-core = { module = "org.flywaydb:flyway-core", version.ref = "flyway" }
flyway-postgresql = { module = "org.flywaydb:flyway-database-postgresql", version.ref = "flyway" }

embabel-agent-starter = { module = "com.embabel.agent:embabel-agent-starter", version.ref = "embabel" }
embabel-agent-starter-anthropic = { module = "com.embabel.agent:embabel-agent-starter-anthropic", version.ref = "embabel" }

reactor-test = { module = "io.projectreactor:reactor-test" }
testcontainers-junit = { module = "org.testcontainers:testcontainers-junit-jupiter", version.ref = "testcontainers" }
testcontainers-postgresql = { module = "org.testcontainers:testcontainers-postgresql", version.ref = "testcontainers" }

# build-logic: convention plugins compile against these
kotlin-gradle-plugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
kotlin-allopen = { module = "org.jetbrains.kotlin:kotlin-allopen", version.ref = "kotlin" }
ktlint-gradle-plugin = { module = "org.jlleitschuh.gradle:ktlint-gradle", version.ref = "ktlint" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "springBoot" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-spring = { id = "org.jetbrains.kotlin.plugin.spring", version.ref = "kotlin" }
```

- [ ] **Step 3: Write `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    includeBuild("backend/build-logic")
}

rootProject.name = "chawpi"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}

// a folder is a module when it has a build file. no list to keep in sync.
fun includeModules(parent: File, nameOf: (File) -> String = { it.name }) {
    parent.listFiles()
        ?.filter { it.isDirectory && it.name != "build-logic" && File(it, "build.gradle.kts").isFile }
        ?.sortedBy { it.name }
        ?.forEach { dir ->
            val name = nameOf(dir)
            include(name)
            project(":$name").projectDir = dir
        }
}

includeModules(file("backend"))
includeModules(file("backend/starters"))
// examples/<sample>/server -> :<sample>-server
file("examples").listFiles()?.filter { it.isDirectory }?.forEach { sample ->
    includeModules(sample) { "${sample.name}-${it.name}" }
}
```
Note: `includeModules(sample)` scans every child of a sample; only `server` has a build file, so `web` is skipped.

`build.gradle.kts`:
```kotlin
allprojects {
    group = "chawpi"
    version = rootProject.property("version") as String
}
```

`gradle.properties`:
```properties
# x-release-please-start-version
version=0.1.0
# x-release-please-end
org.gradle.caching=true
org.gradle.parallel=true
kotlin.code.style=official
```

- [ ] **Step 4: Write `.gitignore`** (sapgis one adapted to the new layout)

```gitignore
# Gradle
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar

# IDE
.idea/
*.iml
*.ipr
*.iws
.vscode/

# Node
node_modules/
dist/
.vite/
*.tsbuildinfo
coverage/

# OS
.DS_Store
Thumbs.db

# Env / secrets
.env
.env.local
*.local.env
infra/.local/

# Logs
*.log
logs/

# Tooling scratch
.playwright-mcp/
shot-*.png
.superpowers/sdd/

# Kotlin build metadata
.kotlin/

# Python
__pycache__/
*.pyc
```

- [ ] **Step 5: Verify discovery ignores folders without a build file**

```bash
mkdir -p backend/build examples/demo/web
./gradlew projects -q
```
Expected: `Root project 'chawpi'` and "No sub-projects" (build-logic appears only as an included build). Then `rmdir examples/demo/web examples/demo; rm -rf backend/build`.

---

### Task 2: Convention plugins (`backend/build-logic`) with TestKit tests

**Files:**
- Create: `backend/build-logic/settings.gradle.kts`, `backend/build-logic/build.gradle.kts`
- Create: `backend/build-logic/src/main/kotlin/chawpi.kotlin-library.gradle.kts`
- Create: `backend/build-logic/src/main/kotlin/chawpi.spring-module.gradle.kts`
- Create: `backend/build-logic/src/main/kotlin/chawpi.publishing.gradle.kts`
- Create: `backend/build-logic/src/main/kotlin/chawpi.integration-test.gradle.kts`
- Test: `backend/build-logic/src/test/kotlin/chawpi/build/ConventionPluginsTest.kt`

**Interfaces:**
- Consumes: `gradle/libs.versions.toml` (Task 1).
- Produces plugin ids used by every later module:
  - `chawpi.kotlin-library`: kotlin-jvm + `java-library` + ktlint, JDK 25, `-Xjsr305=strict`, sources jar, `test` excludes JUnit tag `integration`.
  - `chawpi.spring-module`: `chawpi.kotlin-library` + kotlin-spring, `api(platform(spring-boot-bom))`, `api(spring-boot-autoconfigure)`, test deps (spring-boot-starter-test, reactor-test, kotlinx-coroutines-test).
  - `chawpi.publishing`: `maven-publish`, publication `maven` from `java` or `javaPlatform` component, resolved versions in the POM, repository `GitHubPackages` = `https://maven.pkg.github.com/${GITHUB_REPOSITORY ?: "hneyra/chawpi"}` with `GITHUB_ACTOR`/`GITHUB_TOKEN`.
  - `chawpi.integration-test`: task `integrationTest` (JUnit tag `integration`, group `verification`), passes `CHAWPI_TEST_DB_HOST|PORT|NAME|USERNAME|PASSWORD` through.

- [ ] **Step 1: Write the build files**

`backend/build-logic/settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
```

`backend/build-logic/build.gradle.kts`:
```kotlin
plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.ktlint.gradle.plugin)

    testImplementation(gradleTestKit())
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // testkit builds are slow. one jvm, no parallel forks fighting over the gradle user home.
    maxParallelForks = 1
}
```

- [ ] **Step 2: Write the failing TestKit test**

`backend/build-logic/src/test/kotlin/chawpi/build/ConventionPluginsTest.kt`:
```kotlin
package chawpi.build

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ConventionPluginsTest {
    @TempDir
    lateinit var dir: File

    // test jvm runs in backend/build-logic
    private val repoRoot = File("../..").canonicalFile

    private fun write(
        path: String,
        text: String,
    ) = File(dir, path).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    private fun runner(vararg args: String) =
        GradleRunner
            .create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace")
            // no github credentials, like a laptop
            .withEnvironment(mapOf("HOME" to System.getProperty("user.home")))

    @BeforeEach
    fun probeProject() {
        write(
            "settings.gradle.kts",
            """
            dependencyResolutionManagement {
                repositories { mavenCentral() }
                versionCatalogs { create("libs") { from(files("${File(repoRoot, "gradle/libs.versions.toml").invariantSeparatorsPath}")) } }
            }
            rootProject.name = "probe"
            """.trimIndent(),
        )
        File(repoRoot, ".editorconfig").copyTo(File(dir, ".editorconfig"))
    }

    @Test
    fun `kotlin library compiles and passes ktlint`() {
        write("build.gradle.kts", "plugins { id(\"chawpi.kotlin-library\") }\n")
        write("src/main/kotlin/probe/Probe.kt", "package probe\n\nfun probe(): String = \"ok\"\n")

        val result = runner("build").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
        assertTrue(result.tasks.any { it.path.startsWith(":runKtlintCheck") })
    }

    @Test
    fun `badly formatted kotlin fails the build`() {
        write("build.gradle.kts", "plugins { id(\"chawpi.kotlin-library\") }\n")
        write("src/main/kotlin/probe/Probe.kt", "package probe\nfun   probe( ) : String   =\"ok\"")

        val result = runner("build").buildAndFail()

        assertTrue(result.output.contains("ktlint", ignoreCase = true))
    }

    @Test
    fun `spring module compiles an auto configuration`() {
        write("build.gradle.kts", "plugins { id(\"chawpi.spring-module\") }\n")
        write(
            "src/main/kotlin/probe/ProbeAutoConfiguration.kt",
            """
            package probe

            import org.springframework.boot.autoconfigure.AutoConfiguration

            @AutoConfiguration
            class ProbeAutoConfiguration
            """.trimIndent() + "\n",
        )

        val result = runner("compileKotlin").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
    }

    @Test
    fun `publishing writes a chawpi pom and publishes locally without github credentials`() {
        write(
            "build.gradle.kts",
            "plugins {\n    id(\"chawpi.kotlin-library\")\n    id(\"chawpi.publishing\")\n}\nversion = \"9.9.9\"\n",
        )
        write("src/main/kotlin/probe/Probe.kt", "package probe\n\nfun probe(): String = \"ok\"\n")

        val result = runner("publishToMavenLocal", "tasks", "--all", "-Dmaven.repo.local=${File(dir, "m2").path}").build()

        assertTrue(result.output.contains("publishMavenPublicationToGitHubPackagesRepository"))
        val pom = File(dir, "build/publications/maven/pom-default.xml").readText()
        assertTrue(pom.contains("<groupId>chawpi</groupId>"))
        assertTrue(pom.contains("<version>9.9.9</version>"))
    }

    @Test
    fun `integration test task runs only integration tagged tests`() {
        write("build.gradle.kts", "plugins {\n    id(\"chawpi.kotlin-library\")\n    id(\"chawpi.integration-test\")\n}\n")

        val result = runner("tasks", "--group", "verification").build()

        assertTrue(result.output.contains("integrationTest"))
    }
}
```

- [ ] **Step 3: Run it to see it fail**

Run: `./gradlew -p backend/build-logic test`
Expected: FAIL — `Plugin [id: 'chawpi.kotlin-library'] was not found`.

- [ ] **Step 4: Write the convention plugins**

`backend/build-logic/src/main/kotlin/chawpi.kotlin-library.gradle.kts`:
```kotlin
plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "chawpi"

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

// unit tests always run; container-backed ones go through integrationTest
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
}
```

`backend/build-logic/src/main/kotlin/chawpi.spring-module.gradle.kts`:
```kotlin
plugins {
    id("chawpi.kotlin-library")
    id("org.jetbrains.kotlin.plugin.spring")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    api(platform(lib("spring-boot-bom")))
    api(lib("spring-boot-autoconfigure"))

    testImplementation(lib("spring-boot-starter-test"))
    testImplementation(lib("reactor-test"))
    testImplementation(lib("kotlinx-coroutines-test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

`backend/build-logic/src/main/kotlin/chawpi.publishing.gradle.kts`:
```kotlin
plugins {
    `maven-publish`
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY") ?: "hneyra/chawpi"}")
            // only read when publishing there; publishToMavenLocal never needs them
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

plugins.withId("java-library") {
    publishing.publications.create<MavenPublication>("maven") {
        from(components["java"])
        // consumers get real versions, not "managed by a bom you don't import"
        versionMapping {
            usage("java-api") { fromResolutionOf("runtimeClasspath") }
            usage("java-runtime") { fromResolutionResult() }
        }
    }
}

plugins.withId("java-platform") {
    publishing.publications.create<MavenPublication>("maven") {
        from(components["javaPlatform"])
    }
}
```

`backend/build-logic/src/main/kotlin/chawpi.integration-test.gradle.kts`:
```kotlin
plugins {
    java
}

val testDbVariables = listOf(
    "CHAWPI_TEST_DB_HOST",
    "CHAWPI_TEST_DB_PORT",
    "CHAWPI_TEST_DB_NAME",
    "CHAWPI_TEST_DB_USERNAME",
    "CHAWPI_TEST_DB_PASSWORD",
)

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs tests tagged 'integration' against a PostgreSQL container."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    // opt out of testcontainers and use a database that is already running
    testDbVariables.forEach { name -> System.getenv(name)?.let { environment(name, it) } }
    shouldRunAfter(tasks.named("test"))
}
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `./gradlew -p backend/build-logic test`
Expected: PASS, 5 tests. If `kotlin-dsl` warns about the embedded Kotlin version vs 2.4.20, that warning is fine; errors are not.

- [ ] **Step 6: Format and build from the root**

```bash
./gradlew build
```
Expected: `BUILD SUCCESSFUL` (root has no modules yet besides those of Task 3).

---

### Task 3: `chawpi-bom`

**Files:**
- Create: `backend/chawpi-bom/build.gradle.kts`

**Interfaces:**
- Consumes: `chawpi.publishing`.
- Produces: artifact `chawpi:chawpi-bom:<version>`; imports the Spring Boot BOM and constrains every published `chawpi-*` module (auto: every subproject named `chawpi-*` except `chawpi-bom`, `chawpi-integration-tests`, plus `chawpi-spring-boot-starter*`).

- [ ] **Step 1: Write the build file**

```kotlin
plugins {
    `java-platform`
    id("chawpi.publishing")
}

javaPlatform {
    allowDependencies()
}

// never published: the bom itself and the test suite
val unpublished = setOf("chawpi-bom", "chawpi-integration-tests")

dependencies {
    api(platform(libs.spring.boot.bom))
    constraints {
        rootProject.subprojects
            .filter { it.name.startsWith("chawpi-") && it.name !in unpublished }
            .forEach { api(project(it.path)) }
    }
}
```

- [ ] **Step 2: Verify the POM**

Run: `./gradlew :chawpi-bom:generatePomFileForMavenPublication && cat backend/chawpi-bom/build/publications/maven/pom-default.xml`
Expected: `<groupId>chawpi</groupId>`, `<artifactId>chawpi-bom</artifactId>`, `<version>0.1.0</version>`, a `dependencyManagement` import of `spring-boot-dependencies` 4.1.1.

- [ ] **Step 3: Publish locally without credentials**

Run: `env -u GITHUB_TOKEN -u GITHUB_ACTOR ./gradlew :chawpi-bom:publishToMavenLocal`
Expected: `BUILD SUCCESSFUL`, `~/.m2/repository/chawpi/chawpi-bom/0.1.0/chawpi-bom-0.1.0.pom` exists.

---

### Task 4: yarn workspace root, prettier, tsconfig base, commit guardrail

**Files:**
- Create: `package.json`, `.prettierrc.json`, `.prettierignore`, `.npmrc`, `frontend/tsconfig.base.json`, `frontend/packages/.gitkeep`, `commitlint.config.mjs`, `.husky/commit-msg`
- Generated: `yarn.lock`

**Interfaces:**
- Produces: workspaces globs `frontend/packages/*`, `examples/*/web`; root scripts `format`, `format:check`, `lint`, `test`, `build` (each runs the same script in every workspace); `frontend/tsconfig.base.json` that every package extends; commit-msg hook.

- [ ] **Step 1: Write `package.json`**

```json
{
    "name": "chawpi",
    "private": true,
    "version": "0.1.0",
    "workspaces": ["frontend/packages/*", "examples/*/web"],
    "engines": {
        "node": ">=26"
    },
    "scripts": {
        "prepare": "husky",
        "format": "prettier --write .",
        "format:check": "prettier --check .",
        "lint": "yarn workspaces run lint",
        "test": "yarn workspaces run test",
        "build": "yarn workspaces run build"
    },
    "devDependencies": {
        "@commitlint/cli": "19.8.1",
        "@commitlint/config-conventional": "19.8.1",
        "husky": "9.1.7",
        "prettier": "3.8.2",
        "typescript": "5.9.3"
    }
}
```
If a pinned devDependency version does not exist on npm, pin the latest existing release of that major and note it in the task report.

- [ ] **Step 2: Write prettier + npm config**

`.prettierrc.json` (sapgis, verbatim):
```json
{
    "semi": false,
    "singleQuote": true,
    "trailingComma": "none",
    "arrowParens": "always"
}
```

`.prettierignore`:
```
# prettier owns frontend code only. docs, adrs and history keep their hand formatting.
*.md
docs/
backend/
infra/
gradle/
.idea/
.github/
**/dist/
**/build/
**/coverage/
node_modules/
yarn.lock
CHANGELOG.md
.release-please-manifest.json
```

`.npmrc`:
```
@chawpi:registry=https://npm.pkg.github.com
```

- [ ] **Step 3: Write `frontend/tsconfig.base.json`** (sapgis compiler options; packages add their own `include`/`paths`)

```json
{
    "compilerOptions": {
        "target": "ES2022",
        "lib": ["ES2022", "DOM", "DOM.Iterable"],
        "module": "ESNext",
        "moduleResolution": "bundler",
        "jsx": "react-jsx",
        "strict": true,
        "noUnusedLocals": true,
        "noUnusedParameters": true,
        "noFallthroughCasesInSwitch": true,
        "skipLibCheck": true,
        "isolatedModules": true,
        "resolveJsonModule": true,
        "verbatimModuleSyntax": true
    }
}
```
And `touch frontend/packages/.gitkeep`.

- [ ] **Step 4: Commit-message guardrail**

`commitlint.config.mjs`:
```js
// conventional commits. release-please reads them to pick the next version.
export default {
  extends: ['@commitlint/config-conventional'],
  rules: {
    // sapgis history uses long, sentence-like subjects
    'header-max-length': [2, 'always', 120],
    'subject-case': [0]
  }
}
```

`.husky/commit-msg`:
```sh
yarn commitlint --edit "$1"
```

- [ ] **Step 5: Install and verify**

```bash
yarn install
chmod +x .husky/commit-msg
yarn format
yarn format:check
echo "feat(core): a record keeps its geometry" | yarn -s commitlint
echo "updated stuff" | yarn -s commitlint; echo "exit=$?"
```
Expected: install OK (creates `yarn.lock`, sets `core.hooksPath` to `.husky/_`), format:check passes and lists no `.md`/docs files, first commitlint exits 0, second prints `subject may not be empty` / `type may not be empty` and `exit=1`.

---

### Task 5: Local infrastructure (`infra/docker`)

**Files:**
- Create: `infra/docker/compose.yml`, `infra/docker/postgres/Dockerfile`, `infra/docker/postgres/init/01-extensions.sql`, `infra/.local/.gitkeep` is NOT created (ignored dir)

**Interfaces:**
- Produces: services `postgres` (image `chawpi/postgres:18-postgis-pgvector`, db/user/password `chawpi`, port 5432), `geoserver` (profile `gis`, port 8081), `postgres-plain` (profile `core`, `postgres:18`, port 5433, proves GIS optional).

- [ ] **Step 1: Copy and rename**

```bash
mkdir -p infra/docker/postgres/init
cp ../sapgis/infra/docker/postgres/Dockerfile infra/docker/postgres/
cp ../sapgis/infra/docker/postgres/init/01-extensions.sql infra/docker/postgres/init/
cp ../sapgis/infra/docker/compose.yml infra/docker/
sed -i '' -e 's/sapgis/chawpi/g' -e 's/SAPGIS/CHAWPI/g' infra/docker/compose.yml infra/docker/postgres/Dockerfile infra/docker/postgres/init/01-extensions.sql
```

- [ ] **Step 2: Put geoserver behind the `gis` profile and add the plain postgres**

In `infra/docker/compose.yml` add `profiles: ['gis']` to the `geoserver` service and this service:
```yaml
  # core only: plain postgres, no postgis. proves the core does not need it.
  postgres-plain:
    image: postgres:18
    container_name: chawpi-postgres-plain
    profiles: ['core']
    environment:
      POSTGRES_USER: chawpi
      POSTGRES_PASSWORD: chawpi
      POSTGRES_DB: chawpi
    ports:
      - '5433:5432'
    healthcheck:
      test: ['CMD-SHELL', 'pg_isready -U chawpi -d chawpi']
      interval: 5s
      timeout: 5s
      retries: 20
```
`01-extensions.sql` must still create `postgis`, `pgcrypto`, `vector` and schemas `chawpi`, `app_data` (only names change).

- [ ] **Step 3: Verify**

Run: `docker compose -f infra/docker/compose.yml config -q && docker compose -f infra/docker/compose.yml --profile gis --profile core config --services`
Expected: exit 0; services `postgres`, `geoserver`, `postgres-plain`. `grep -ri sapgis infra/` prints nothing.

---

### Task 6: Preserve sapgis docs, ADRs, history; new README and CLAUDE.md

**Files:**
- Create: `docs/adr/0001…0023-*.md`, `docs/architecture/overview.md`, `docs/domain/metadata-model.md`, `docs/api/rest.md`, `docs/gis/geometry.md`, `docs/security/authentication.md`, `docs/development/getting-started.md`, `docs/HISTORY.md`, `docs/superpowers/specs/*` and `docs/superpowers/plans/*` from sapgis (4 files), `docs/sapgis-origin.md`
- Modify: `README.md`, Create: `CLAUDE.md`

**Interfaces:**
- Produces: `docs/adr/` numbering continues at 0024 (new ADRs are written in P7). `docs/sapgis-origin.md` is the single place that explains what was copied and renamed.

- [ ] **Step 1: Copy**

```bash
mkdir -p docs
cp -R ../sapgis/docs/adr ../sapgis/docs/architecture ../sapgis/docs/domain ../sapgis/docs/api ../sapgis/docs/gis ../sapgis/docs/security ../sapgis/docs/development docs/
cp ../sapgis/docs/HISTORY.md docs/
cp ../sapgis/docs/superpowers/specs/* docs/superpowers/specs/
cp ../sapgis/docs/superpowers/plans/* docs/superpowers/plans/
```

- [ ] **Step 2: Rename identifiers in living docs** (ADRs + architecture/domain/api/gis/security/development). HISTORY and the sapgis superpowers specs/plans stay verbatim: they are the record of what happened in sapgis.

```bash
for f in docs/adr/*.md docs/architecture/*.md docs/domain/*.md docs/api/*.md docs/gis/*.md docs/security/*.md docs/development/*.md; do
  sed -i '' -e 's#com\.sapgis#chawpi#g' -e 's#com/sapgis#chawpi#g' -e 's/SAPGIS_/CHAWPI_/g' \
    -e 's/SAPGIS/Chawpi/g' -e 's/Sapgis/Chawpi/g' -e 's/sapgis/chawpi/g' \
    -e 's/GEOFORGE_/CHAWPI_/g' -e 's/GeoForge Core/Chawpi/g' -e 's/GeoForge/Chawpi/g' "$f"
done
```

- [ ] **Step 3: Add the provenance header to every ADR** (insert after the `**Status**` line)

```bash
for f in docs/adr/0*.md; do
  awk 'BEGIN{done=0} {print} /^\*\*Status\*\*/ && !done {print ""; print "> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md)."; done=1}' "$f" > "$f.tmp" && mv "$f.tmp" "$f"
done
grep -L "Imported from sapgis" docs/adr/*.md
```
Expected: last command prints nothing (every ADR has the header).

- [ ] **Step 4: Write `docs/sapgis-origin.md`**

```markdown
# Where chawpi comes from

Chawpi started on 2026-09-25 as a copy of **sapgis** (Spatial Application Platform for GIS), a
metadata-driven platform built as a single application. Chawpi keeps every decision and feature and
reshapes them into reusable libraries (see
[the design](superpowers/specs/2026-09-25-chawpi-libraries-design.md)).

The git history was not imported. What was copied:

| sapgis | chawpi | Changes |
|---|---|---|
| `docs/adr/0001–0023` | `docs/adr/0001–0023` | identifiers renamed, provenance header added |
| `docs/{architecture,domain,api,gis,security,development}` | same paths | identifiers renamed; updated to the module layout in P7 |
| `docs/HISTORY.md` | `docs/HISTORY.md` | verbatim below the chawpi entries |
| `docs/superpowers/{specs,plans}` | same paths | verbatim (historical, paths point at sapgis) |
| `examples/perene` | `examples/gis-sample/perene` | identifiers renamed |
| `infra/docker` | `infra/docker` | renamed; geoserver behind the `gis` profile |
| `.editorconfig`, `.prettierrc.json` | repo root | verbatim |

Renames: `com.sapgis` → `chawpi`, `sapgis.*` properties → `chawpi.*`, `SAPGIS_*` → `CHAWPI_*`,
schema `sapgis` → `chawpi`, `admin@sapgis.local` → `admin@chawpi.local`, `GEOFORGE_*` → `CHAWPI_*`.
```

- [ ] **Step 5: Prepend a chawpi entry to `docs/HISTORY.md`** (after the intro paragraph, before the first `## 2026-09-22` entry)

```markdown
## 2026-09-25 — Chawpi starts from sapgis

The whole of sapgis moves here to become a set of libraries: a Spring Boot starter per module and an
npm package per frontend module, so an app gets the platform by adding dependencies instead of
forking it. Nothing changes in behaviour. Entries below this one are sapgis history, kept as written.
```

- [ ] **Step 6: Write `README.md`**

```markdown
# Chawpi

**A metadata-driven application platform, as libraries.** An administrator defines Custom Objects,
Custom Fields, relationships, forms, views, pages, workflows, automations, documents and permissions;
chawpi turns that metadata into a working application at runtime: real PostgreSQL tables, a REST API,
dynamic forms and tables, detail pages. GIS (PostGIS geometry fields, maps, GeoServer) is one optional
module among others.

Chawpi comes from [sapgis](docs/sapgis-origin.md).

## Use it in your app

Backend (Kotlin/Spring Boot 4.1 WebFlux):

```kotlin
dependencies {
    implementation(platform("chawpi:chawpi-bom:0.1.0"))
    implementation("chawpi:chawpi-spring-boot-starter")          // core
    implementation("chawpi:chawpi-spring-boot-starter-documents") // opt-in module
}
```

Frontend (React 19):

```tsx
<ChawpiApp config={{ apiBaseUrl: '/api' }} modules={[documentsModule()]} />
```

Modules: views, forms, pages, workflow, automation, documents, gis, agent. See `examples/` from
`simple-sample` (core only) to `full-sample` (every module).

## Layout

```
backend/    Gradle libraries: chawpi-core, chawpi-<module>, starters, chawpi-bom, chawpi-test
frontend/   npm packages: @chawpi/ui, @chawpi/core, @chawpi/<module>, @chawpi/testing
examples/   runnable sample apps (server + web)
infra/      docker compose for local development
docs/       architecture, domain, api, gis, security, development, modules, adr
```

## Commands

```bash
docker compose -f infra/docker/compose.yml up -d          # PostGIS + pgvector (add --profile gis for GeoServer)
./gradlew build                                          # ktlint + unit tests
./gradlew integrationTest                                # API tests against a container
yarn install && yarn lint && yarn test && yarn build     # every frontend package
```

Commits follow [Conventional Commits](https://www.conventionalcommits.org) (checked by a git hook and
CI). Releases are cut by release-please; publishing a GitHub Release publishes every library to
GitHub Packages.
```

- [ ] **Step 7: Write `CLAUDE.md`** (sapgis rules, reshaped for libraries)

```markdown
# Chawpi — metadata-driven application platform, as libraries

Reusable libraries (Spring Boot starters + npm packages) extracted from sapgis. An app adds the core
and opts into modules; metadata drives schema, API and UI at runtime.

## Non-negotiable stack

- **Backend**: Kotlin 2.4.20, Spring Boot 4.1 **WebFlux** (reactive), Gradle (Kotlin DSL, version catalog, convention plugins in `backend/build-logic`)
- **Database**: PostgreSQL 18 (+ PostGIS only with `chawpi-gis`, + pgvector optional)
- **Frontend**: React 19.3 (yarn workspaces) + Vite + Tailwind CSS + shadcn-style components + i18next + react-router + zod
- **GIS module**: GeoServer (WMS/WFS/WMTS), MapLibre GL JS
- **Infra**: Docker Compose for local development

## Architectural rules

1. **Metadata-driven**: never generate code per Custom Object. Metadata drives behaviour at runtime.
2. **WebFlux is reactive** ⇒ JPA / Hibernate / Envers are forbidden. Spring Data R2DBC for fixed schema, `DatabaseClient` for dynamic records.
3. **Libraries, not an app**: `chawpi-core` never depends on a module. Modules extend the core only through its SPIs (field types, page components, listeners, contributors). Every module ships its own auto-configuration, properties (`chawpi.<module>.*`) and Flyway migrations.
4. **No component scanning of library code**: beans are declared in auto-configurations with `@ConditionalOnMissingBean` so apps can override them.
5. **Multi-tenancy** by `organization_id`; every query filters by the tenant resolved from the JWT.
6. **Dynamic DDL only through `ObjectSchemaManager`**. Identifiers validated and quoted by `SqlIdentifier`. Values always bound, never interpolated.
7. **Geometry is first-class when `chawpi-gis` is present**: PostGIS columns with their SRID, GeoJSON in EPSG:4326 over the API.
8. **Frontend modules register themselves** (`ChawpiModule`: routes, nav, field renderers, page components, i18n). No hardcoded routes or URLs outside the registry.
9. **No overengineering**: an abstraction needs a concrete second user.

## Code style

- Code, identifiers and comments in **English**. Comments **caveman style**: short, say why.
- Formatting follows `.editorconfig` everywhere (Kotlin 4 spaces, TS/YAML/MD 2, max 160 cols): ktlint (`./gradlew ktlintFormat`) and prettier (`yarn format`). Always format before finishing.
- Commits: Conventional Commits (`feat(core): …`, `fix(gis): …`). Enforced by commitlint.
- Change history lives in `docs/HISTORY.md`, decisions in `docs/adr/`.

## Commands

```bash
docker compose -f infra/docker/compose.yml up -d
./gradlew build                 # ktlint + unit tests
./gradlew integrationTest       # needs a docker daemon (or CHAWPI_TEST_DB_*)
yarn install && yarn lint && yarn test && yarn build
```

## Definition of Done

Works · has tests · handles errors · is documented · does not break existing features · build passes ·
tests pass · core stays module-agnostic · formatted with `.editorconfig`.
```

- [ ] **Step 8: Verify**

```bash
ls docs/adr | wc -l                        # 23
grep -rIl "sapgis" docs/adr docs/architecture docs/domain docs/api docs/gis docs/security docs/development
```
Expected: `23`; grep prints only files whose sole matches are the provenance header link `sapgis-origin.md` / the word "sapgis" in that header (acceptable). Any other identifier (`sapgis.database`, `SAPGIS_JWT_SECRET`, `com.sapgis`) is a failure.

---

### Task 7: Perené cadastre example (`examples/gis-sample/perene`)

**Files:**
- Create: `examples/gis-sample/perene/{README.md,model.json,apply.py,test_apply.py,test_model.py}`, `examples/README.md`

**Interfaces:**
- Produces: `apply.py` env vars `CHAWPI_CORE`, `CHAWPI_EMAIL`, `CHAWPI_PASSWORD`; default email `admin@chawpi.local`. P6 wires it into `gis-sample`.

- [ ] **Step 1: Copy and rename**

```bash
mkdir -p examples/gis-sample/perene
cp ../sapgis/examples/perene/{README.md,model.json,apply.py,test_apply.py,test_model.py} examples/gis-sample/perene/
cd examples/gis-sample/perene
sed -i '' -e 's#backend/src/main/kotlin/com/sapgis/platform/SqlIdentifier.kt#backend/chawpi-core/src/main/kotlin/chawpi/core/platform/SqlIdentifier.kt#g' \
  -e 's#examples/perene#examples/gis-sample/perene#g' -e 's/GEOFORGE_/CHAWPI_/g' -e 's/GeoForge Core/Chawpi/g' -e 's/GeoForge/Chawpi/g' \
  -e 's/admin@sapgis\.local/admin@chawpi.local/g' -e 's/sapgis/chawpi/g' *.py README.md
```
The README's run instructions (`./gradlew :backend:bootRun`) are updated in P6 when `gis-sample/server` exists; leave them.

- [ ] **Step 2: Run the example's tests**

Run: `cd examples/gis-sample/perene && python3 -m unittest -v`
Expected: all tests of `test_model.py` and `test_apply.py` PASS (same count as in sapgis: run `cd ../sapgis/examples/perene && python3 -m unittest 2>&1 | tail -3` to compare).

- [ ] **Step 3: Write `examples/README.md`**

```markdown
# Examples

Each sample is a runnable app made of a `server/` (Spring Boot, uses the chawpi starters) and a
`web/` (React, uses the `@chawpi/*` packages).

| Sample | Modules | Database |
|---|---|---|
| `simple-sample` | core | plain PostgreSQL (`--profile core`) |
| `documents-sample` | core, documents, automation | PostGIS image |
| `gis-sample` | core, gis (+ `perene/` cadastre model) | PostGIS image + GeoServer (`--profile gis`) |
| `full-sample` | every module | PostGIS image + GeoServer |

Samples are built in P6 of the [design](../docs/superpowers/specs/2026-09-25-chawpi-libraries-design.md);
`gis-sample/perene` is already here.
```

---

### Task 8: GitHub Actions — CI, commit checks, release-please, publish on release

**Files:**
- Create: `.github/workflows/ci.yml`, `.github/workflows/commits.yml`, `.github/workflows/release-please.yml`, `.github/workflows/publish.yml`
- Create: `release-please-config.json`, `.release-please-manifest.json`

**Interfaces:**
- Consumes: `gradle.properties` version markers (Task 1), root `package.json` (Task 4).
- Produces: on PR → build + commit/title checks; on push to `main` → release PR; on GitHub Release published → Maven + npm publish of version = tag without `v`. P4/P5 add each `frontend/packages/*/package.json` to `extra-files`.

- [ ] **Step 1: `.github/workflows/ci.yml`**

```yaml
name: CI

on:
  push:
    branches: ['**']
  pull_request:

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  backend:
    name: Backend
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v4
      - name: Build (ktlint + unit tests + build-logic tests)
        run: ./gradlew -p backend/build-logic test && ./gradlew build --no-daemon
      # ubuntu runners ship a docker daemon, so the PostGIS container is reachable here
      - name: Integration tests
        run: ./gradlew integrationTest --no-daemon
      - name: Upload test reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: backend-test-reports
          path: '**/build/reports/tests/'

  frontend:
    name: Frontend
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '26'
          cache: yarn
      - run: yarn install --frozen-lockfile
      - run: yarn format:check
      # no-op until P4 adds the first package
      - run: yarn lint && yarn test && yarn build
        if: hashFiles('frontend/packages/*/package.json') != ''

  examples:
    name: Examples
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.12'
      - name: Perené model
        working-directory: examples/gis-sample/perene
        run: python -m unittest -v
```

- [ ] **Step 2: `.github/workflows/commits.yml`** (semantic commit guardrail)

```yaml
name: Commits

on:
  pull_request:
    types: [opened, edited, synchronize, reopened]

permissions:
  contents: read
  pull-requests: read

jobs:
  commitlint:
    name: Conventional commits
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - uses: actions/setup-node@v4
        with:
          node-version: '26'
          cache: yarn
      - run: yarn install --frozen-lockfile
      - run: yarn commitlint --from ${{ github.event.pull_request.base.sha }} --to ${{ github.event.pull_request.head.sha }} --verbose

  pr-title:
    name: Semantic PR title
    runs-on: ubuntu-latest
    steps:
      # squash merges use the title as the commit, so it must be conventional too
      - uses: amannn/action-semantic-pull-request@v5
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

- [ ] **Step 3: release-please config + workflow**

`release-please-config.json`:
```json
{
    "$schema": "https://raw.githubusercontent.com/googleapis/release-please/main/schemas/config.json",
    "include-component-in-tag": false,
    "packages": {
        ".": {
            "release-type": "simple",
            "package-name": "chawpi",
            "changelog-path": "CHANGELOG.md",
            "bump-minor-pre-major": true,
            "extra-files": [
                "gradle.properties",
                { "type": "json", "path": "package.json", "jsonpath": "$.version" }
            ]
        }
    }
}
```

`.release-please-manifest.json`:
```json
{
    ".": "0.1.0"
}
```

`.github/workflows/release-please.yml`:
```yaml
name: Release Please

on:
  push:
    branches: [main]

permissions:
  contents: write
  pull-requests: write

jobs:
  release-please:
    runs-on: ubuntu-latest
    steps:
      # one lockstep version for every maven and npm library. merging its pr tags and creates the github release.
      - uses: googleapis/release-please-action@v4
        with:
          config-file: release-please-config.json
          manifest-file: .release-please-manifest.json
          # a release made with GITHUB_TOKEN does not trigger other workflows; a PAT lets publish.yml run
          token: ${{ secrets.RELEASE_PLEASE_TOKEN || secrets.GITHUB_TOKEN }}
```

- [ ] **Step 4: `.github/workflows/publish.yml`** (publish on GitHub Release)

```yaml
name: Publish

on:
  release:
    types: [published]

permissions:
  contents: read
  packages: write

jobs:
  maven:
    name: Maven libraries
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v4
      - name: Publish to GitHub Packages
        env:
          GITHUB_ACTOR: ${{ github.actor }}
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: ./gradlew publish --no-daemon -Pversion="${GITHUB_REF_NAME#v}"

  npm:
    name: npm packages
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      # hashFiles is not allowed in a job-level if; gate the steps instead
      - id: packages
        run: echo "present=$(ls frontend/packages/*/package.json >/dev/null 2>&1 && echo true || echo false)" >> "$GITHUB_OUTPUT"
      - if: steps.packages.outputs.present == 'true'
        uses: actions/setup-node@v4
        with:
          node-version: '26'
          cache: yarn
          registry-url: https://npm.pkg.github.com
          scope: '@chawpi'
      - if: steps.packages.outputs.present == 'true'
        run: yarn install --frozen-lockfile && yarn build
      - name: Publish every public @chawpi package
        if: steps.packages.outputs.present == 'true'
        env:
          NODE_AUTH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          version="${GITHUB_REF_NAME#v}"
          for dir in frontend/packages/*/; do
            if [ "$(node -p "require('./$dir/package.json').private === true")" = "true" ]; then continue; fi
            (cd "$dir" && npm version "$version" --no-git-tag-version --allow-same-version && npm publish)
          done
```

- [ ] **Step 5: Validate YAML/JSON syntax**

```bash
for f in .github/workflows/*.yml; do ruby -ryaml -e "YAML.load_file('$f')" && echo "ok $f"; done
node -e "JSON.parse(require('fs').readFileSync('release-please-config.json'));JSON.parse(require('fs').readFileSync('.release-please-manifest.json'));console.log('json ok')"
command -v actionlint >/dev/null && actionlint || echo "actionlint not installed, skipped"
```
Expected: `ok` for the 4 workflows, `json ok`.

---

### Task 9: Phase verification

- [ ] **Step 1: Full local verification**

```bash
./gradlew -p backend/build-logic test
./gradlew build
env -u GITHUB_TOKEN -u GITHUB_ACTOR ./gradlew publishToMavenLocal
yarn install --frozen-lockfile && yarn format:check
(cd examples/gis-sample/perene && python3 -m unittest)
docker compose -f infra/docker/compose.yml config -q
git status --short
```
Expected: everything green; `git status` shows only new/modified files, nothing committed. Report the output.
