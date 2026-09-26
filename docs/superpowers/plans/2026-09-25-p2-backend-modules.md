# P2 — Backend optional modules + starters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the original's `views`, `forms`, `pages`, `workflow`, `automation`, `documents`, `gis` and `agent` packages into eight published, auto-configured, individually optional libraries `backend/chawpi-<m>` (package `chawpi.<m>`), each with its own rebaselined Flyway migration, plugged into chawpi-core only through the P1 SPIs, plus thin starters `chawpi-spring-boot-starter[-<m>]`.

**Architecture:** Each module is copied from `/Users/jorge/IdeaProjects/sapgis/backend` with one scripted rename (`port`), then the few seams that touched another module are rewritten by hand: GIS SQL becomes a `FieldTypeHandler` + `RecordQueryContributor`; page components become an open registry (`PageComponentProvider`, owned by chawpi-pages); automation's document port becomes `DocumentIssuer` (owned by chawpi-automation, with a null object); cross-module adapters are compiled `compileOnly` and switched on by `@ConditionalOnClass`. Every bean is declared in the module's `@AutoConfiguration`. Tasks are cut so that one directory = one task; waves say what can run at the same time. A last task (Wave 5) closes the two ADR-0025 known gaps in core: the plan's one deliberate behaviour change against the original.

**Tech Stack:** Kotlin 2.4.20, JDK 25, Spring Boot 4.1.1 WebFlux + R2DBC (`DatabaseClient`), Jackson 3 (`tools.jackson`, annotations still `com.fasterxml.jackson.annotation`), Flyway 12.4 (JDBC, per-module history), PostgreSQL 18 (+ PostGIS 3.6 for chawpi-gis), Embabel 1.5.2, JUnit 5 + AssertJ + Mockito (from `spring-boot-starter-test`), `ReactiveWebApplicationContextRunner`, ktlint 1.7.1.

**Spec:** `docs/superpowers/specs/2026-09-25-chawpi-libraries-design.md`. Also binding: the P1 plan `docs/superpowers/plans/2026-09-25-p1-backend-core.md` (Rulings R1–R17) and the P1 ledger `.superpowers/sdd/2026-09-25-p1-backend-core/progress.md` (its notes override P1 plan text).

## Global Constraints

- **NEVER run `git commit`, `git push` or `git stash`.** Leave every change uncommitted in the working tree. Each task ends with `git status --short` to confirm nothing was committed.
- sapgis (`/Users/jorge/IdeaProjects/sapgis`) is READ-ONLY. Copy from it, never edit it.
- Module `backend/chawpi-<m>`, artifact `chawpi-<m>`, group `chawpi`, base package `chawpi.<m>`, auto-configs in `chawpi.<m>.autoconfigure`. Module names: `views`, `forms`, `pages`, `workflow`, `automation`, `documents`, `gis`, `agent`.
- **Module graph.** Every module has `api(project(":chawpi-core"))`. The only module→module `api`/`implementation` dependency is `chawpi-pages → chawpi-forms`. Every other link is optional: `compileOnly(project(...))` plus an auto-config guarded by `@ConditionalOnClass(name = [...])`, or an `ObjectProvider` lookup (Ruling M2).
- **chawpi-core is not edited** except by Task 1 (the `@ChawpiApplication` annotation) and Task 16 (core hardening: the ADR-0025 known gaps, the one deliberate behaviour change against the original in P2). If a task finds it needs a core change, it stops and reports `BLOCKED: core change needed: <what>` instead of editing core.
- Formatting: repo `.editorconfig` (Kotlin 4 spaces, 160 columns, LF, final newline). Every task ends with two separate `./gradlew` calls per module it touched — `:chawpi-<m>:ktlintFormat` then `:chawpi-<m>:ktlintCheck` — never combined in one invocation (they can race under `org.gradle.parallel=true`). Both must pass.
- Comments in English, caveman style: short, say why, never restate the code. Keep the original's comments when they still hold; reword any that name it directly.
- REST routes and JSON stay exactly the original's: same paths, verbs, status codes, property names and values. The flattened-extension key order is the only allowed difference (P1 R5).
- Config keys live under `chawpi.*`; env vars `CHAWPI_*` (relaxed binding). Nothing named after the original survives in `backend/`.
- No component scanning of library code. Beans are declared in `@AutoConfiguration` classes listed in the module's own `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, each bean `@ConditionalOnMissingBean` (except `ModuleMigration` beans, Ruling M9). Stereotypes (`@Service`, `@Component`, `@Repository`, `@RestController`) stay on the classes (P1 R12).
- No JPA, no Spring Data repositories. SQL goes through `DatabaseClient`, values are bound, identifiers go through `SqlIdentifier`, schema names through `ChawpiSchemas` (`${schemas.metadata}` in Kotlin SQL, `${metadataSchema}` in migrations).
- Where the original injected `ObjectMapper`, the auto-config passes the `tools.jackson.databind.json.JsonMapper` bean (ask for `JsonMapper`, the class keeps its `ObjectMapper` parameter).
- **Parallel safety.** A task edits only the files in its own **Files** list. Run Gradle only for your own projects (`:chawpi-<m>:…`), never `./gradlew build` at the root (that is Task 15). If Gradle says `Timeout waiting to lock`, another task is building core: wait 30 s and run the same command again.
- **Tests.** P2 tasks need only unit tests and `ApplicationContextRunner` tests: `./gradlew :chawpi-<m>:test`. No Docker, no database. Integration tests are P3.
- Versions only from `gradle/libs.versions.toml`. No new libraries.

## Design rulings (binding for every task)

- **M1: where each SPI lives.**

  | Contract | Package (artifact) | Implemented by |
  |---|---|---|
  | `FieldTypeHandler`, `ObjectRemovalListener`, `FieldUsage` | `chawpi.core.metadata` (core, exists) | gis `GeometryFieldType`, gis `LayerCleanup`, automation `AutomationFieldUsage` |
  | `RecordQueryContributor`, `RecordCriterion`, `RecordChangeListener`, `WorkflowStates` | `chawpi.core.data` (core, exists) | gis `BboxQuery`, automation `AutomationDispatcher`, workflow `WorkflowStatesAdapter` |
  | `SystemColumnContributor` | `chawpi.core.platform` (core, exists) | workflow `WorkflowSystemColumns` |
  | `PageComponentProvider`, `GeneratedComponent`, `PageComponentTypes`, `ComponentType` | `chawpi.pages` (chawpi-pages, new) | gis `MapPageComponent`, workflow `WorkflowPageComponent` |
  | `DocumentIssuer` + `NoDocumentIssuer` | `chawpi.automation` (chawpi-automation, new; was the original's `automation.Documents`) | documents `DocumentIssuerAdapter` |
  | `RecordTransitions` + `NoRecordTransitions`, `AgentTransition` | `chawpi.agent` (chawpi-agent, new) | agent `WorkflowRecordTransitions` (wraps `WorkflowService`) |

  `ObjectMetadataContributor` does not exist (P1 R16): views, forms and pages each own a `*MetadataController` mapped at `/api/metadata/objects/{object}/views|forms|pages`. The HISTORY page component is a built-in type of chawpi-pages.
- **M2: optional cross-module adapters.** The adapter lives in the module that implements the foreign contract, is compiled against it with `compileOnly(project(":chawpi-x"))`, and sits in its own auto-config class annotated `@ConditionalOnClass(name = ["<fqcn of the contract>"])` (a string, so the class is never loaded when absent). If it needs a bean of the other module, it also has `@ConditionalOnBean(<that bean>::class)` and `afterName = ["<fqcn of the other module's auto-config>"]`. The consumer asks with `ObjectProvider<Contract>.getIfAvailable { NullObject() }`, so it never depends on auto-config order. Adapters: documents→automation (Task 10), agent→workflow (Task 11), gis→pages (Task 12), workflow→pages (Task 13).
- **M3: CHECK/FK redefinitions owned by modules (P1 R4, R10).** gis drops and re-adds `custom_fields_type_valid` with `'GEOMETRY'` appended and adds the three geometry CHECKs. documents drops and re-adds `audit_log_operation_valid` with `'ISSUE'` and adds `audit_log_document_id_fkey … ON DELETE SET NULL`. Plain `DROP CONSTRAINT` (no `IF EXISTS`): if core's constraint is missing the migration must fail loudly. Only gis may own the field-type list (ADR-0027 limit).
- **M4: migration order.** Every module migration is `ModuleMigration("<m>", "classpath:db/chawpi/<m>", ModuleMigration.MODULE_ORDER)` (100, strictly above core 0 and seed 10). No module migration reads another module's tables, so equal orders are safe. One file per module: `db/chawpi/<m>/V1__<m>.sql`. It produces the original's final (V1–V14) schema for the module's tables: same column order, types, defaults, constraint and index names. Data-only statements (V5 back-fills, V10 tree rewrite, V11 `DELETE FROM pages`) are dropped: clean start.
- **M5: GEOMETRY handler shape.** `GeometryFieldType(objectMapper)`: `type = GEOMETRY`, `attributeColumns = geometry_type text / srid Integer / dimension Integer`, `section = "geometries"`. `attributesOf` checks in the original's order (unique → default → dimension → srid → geometryType) and validates srid; `columnType` re-validates srid and type (P1 ledger) → `geometry(<Type>[Z], <srid>)`; `indexes` → one GIST index `…_gix`; `bindExpression` → `ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(CAST(:p AS text)), 4326), <srid>)`; `select` → `ST_AsGeoJSON(ST_Transform(<col>, 4326)) AS "<col>__geojson"` (the handler adds its own alias, P1 ledger); `readName` → `<col>__geojson`; `fieldProperties` → `"geometry"` on every field (null when not GEOMETRY); `objectProperties` → `"geometry"` = first geometry field; `rejectFilterOrSort` → `Cannot filter or sort by geometry 'x'` / `use bbox instead`. `BboxQuery` reserves `bbox` and `geometry` and returns one `ST_Intersects(...)` expression (core parenthesises every criterion, P1 ledger). With chawpi-gis removed later, stored GEOMETRY fields still list, record calls answer 409 `Field type 'GEOMETRY' is not installed`, and even a label-only edit of such a field is a 409 (core behaviour, P1 ledger).
- **M6: security chains.** No module registers a `SecurityWebFilterChain`. Every module route is under `/api/**` and is covered by core's chain (`@Order(0)`, authenticated except login/health). A future module chain must carry a `securityMatcher` and an `@Order` below 0, or it will never be reached (P1 ledger). No module reads the signing key; one that must, asks for the `ChawpiJwtKey` bean, never a bare `SecretKey`.
- **M7: enabled flags.** Each auto-config class carries `@ConditionalOnProperty(prefix = "chawpi.<m>", name = ["enabled"], havingValue = "true", matchIfMissing = true)` and enables a `@ConfigurationProperties("chawpi.<m>")` class that has `val enabled: Boolean = true`. Off = no beans, no routes, no migration.
- **M8: property renames.** `sapgis.geoserver.*` → `chawpi.gis.geoserver.*` (class `GeoServerProperties`), `sapgis.agent.*` → `chawpi.agent.*` (`apiKey` → `api-key`), `sapgis.automation.*` → `chawpi.automation.*`. The library ships no `application.yml`; the original's `${SAPGIS_*}` placeholders become plain relaxed binding (`CHAWPI_GIS_GEOSERVER_URL`, `CHAWPI_AUTOMATION_POLL_INTERVAL`, `CHAWPI_AGENT_MODEL`, …). The two defaults the original only had in YAML are added by the agent's `EmbabelGate` (M11).
- **M9: bean rules.** `ModuleMigration` beans are never `@ConditionalOnMissingBean` (core already has one of that type, the condition would always skip) and are named `chawpi<M>Migration`. No module bean touches the database while the context starts (automation's drain polls from `SmartLifecycle.start()`, after every singleton including `chawpiMigrations`), so no `@DependsOn("chawpiMigrations")` is needed; a bean that ever reads the database in its constructor or `@PostConstruct` must add it. Repositories whose SQL names `${schemas.metadata}` take `private val schemas: ChawpiSchemas` as their LAST constructor parameter.
- **M10: page component types are an open value.** `ComponentType(name)` (JSON: the bare name) with built-ins PAGE, REGION, TABS, TAB, SECTION, FORM, DYNAMIC_FORM, FIELD, RELATED_LIST, TEXT, HISTORY, ACTION. `PageComponentTypes` parses built-ins then provider types in bean order. A stored page naming a type whose module is gone still reads back (the value is just a name); saving it again is refused like any unknown component. Deviation: with gis and workflow installed, the `Unknown component` error lists MAP and WORKFLOW at the end instead of between FIELD/RELATED_LIST and HISTORY/ACTION.
- **M11: agent gating.** `EmbabelGate` (`org.springframework.boot.EnvironmentPostProcessor`, Boot 4.1 key) excludes Embabel's auto-configs when no Anthropic key is configured OR `chawpi.agent.enabled=false`, merging with any `spring.autoconfigure.exclude` the app set. It also adds lowest-precedence defaults `chawpi.agent.api-key=${ANTHROPIC_API_KEY:}` and `embabel.models.default-llm=${chawpi.agent.model:claude-haiku-4-5}`. Deviation: the original kept `/api/agent/*` routes with `enabled=false`; chawpi removes them (M7).
- **M12: workflow.** `WorkflowService` takes `List<RecordChangeListener>` instead of one bean and calls each in order (P1 R9). The workflow auto-config runs `before = [ChawpiDataAutoConfiguration::class]` so its `WorkflowStatesAdapter` replaces `NoWorkflowStates`, and contributes `SystemColumn("workflow_state", "TEXT", "WORKFLOW")`.
- **M13: GeoServer schema.** `GeoServerPayloads.virtualTableSql` and `featureType` take the schema as their first parameter, read from `chawpi.gis.geoserver.datastore.schema` (default `app_data`, same as `chawpi.database.data-schema`'s default). An app that changes the data schema sets both.

## Review Focus

1. **Wire shape with gis installed.** A TEXT field answers `"geometry": null`; an object answers `"geometry"` = its first GEOMETRY field or null; a 3D POINT field answers `{"type":"POINT","srid":…,"dimension":3}`; a flat record answers `"geometries": {}` (core fills every installed section; core's MEASURE tests pin that, P3 re-checks it with gis). Pinned by `GeometryFieldTypeTest` and `ChawpiGisAutoConfigurationTest` (`sections == [geometries]`) (Task 7).
2. **Bad geometry input never becomes a 500.** `srid` sent as `"abc"`, `1.5`, `-1` or `1000000`; `geometryType` missing or `"circle"`; `dimension` 4; a geometry value that is a string or has the wrong `type` → 400 with the original's messages. Pinned by `GeometryFieldTypeTest` (Task 7).
3. **bbox edge cases.** `bbox=1,2,3`, `bbox=a,b,c,d`, a bbox on a flat object, `geometry=` naming no geometry → 400 with the original's messages; values are bound, only the validated srid is interpolated. Pinned by `BboxQueryTest` (Task 7).
4. **A module that is absent.** GENERATE_DOCUMENT saved without chawpi-documents → 400 `Unknown document type`, never a missing-bean boot failure; `available_transitions` asked without chawpi-workflow → empty list; a stored page with a `MAP` component read without chawpi-gis → still deserializes. Pinned by `NoDocumentIssuerTest` + `ChawpiAutomationAutoConfigurationTest` (Task 5), `ChawpiAgentAutoConfigurationTest` (Task 8), `PageComponentJsonTest` (Task 9).
5. **A module switched off or overridden.** `chawpi.<m>.enabled=false` leaves core booting with none of the module's beans or migration; an app bean of the same type wins. Pinned by each module's `Chawpi<M>AutoConfigurationTest` (Tasks 2–13).

---

## Waves (what may run at the same time)

| Wave | Tasks (parallel inside a wave) | Needs |
|---|---|---|
| 0 | Task 1 prep (core annotation, shared test runner, warm build) | P1 done |
| 1 | Task 2 views · Task 3 forms · Task 4 workflow · Task 5 automation · Task 6 documents · Task 7 gis · Task 8 agent | Wave 0 |
| 2 | Task 9 pages (needs forms) · Task 10 documents→automation adapter · Task 11 agent→workflow adapter | Wave 1 |
| 3 | Task 12 gis MAP component (needs pages) · Task 13 workflow WORKFLOW component (needs pages) · Task 14 starters | Wave 2 |
| 4 | Task 15 integration verification (full build, BOM, all-modules wiring test) | Wave 3 |
| 5 | Task 16 core hardening: link/unlink own_records_only + audit, full-row audit/`RecordChange.after` (ITs against the tunnel) | Wave 4 |

Files touched by two tasks of the same wave: none. Task 10 and Task 6 both touch `backend/chawpi-documents` but in different waves; same for 11/8, 12/7, 13/4.

Settings auto-includes any directory with a build file, so every concurrent Gradle invocation configures every other in-progress module too: one executor's half-written or broken `build.gradle.kts` can fail configuration for the whole wave. Write each `build.gradle.kts` in a single write; if configuration fails on a *foreign* module's script, wait 30 s and retry — never edit a script owned by another task. Task 14 reads the gis and workflow build scripts while Tasks 12/13 edit them in the same wave; the same rule applies there. Each Wave 1 and Wave 2 task's last step also builds its own module jar (`./gradlew :chawpi-<m>:jar`, Task 9 also builds `:chawpi-forms:jar`), so no later wave's `Jar` task races against it on the same output file.

## File structure (end state of P2)

```
backend/chawpi-core/src/main/kotlin/chawpi/core/autoconfigure/ChawpiApplication.kt   (Task 1)
backend/chawpi-test/src/main/kotlin/chawpi/test/ChawpiContextRunner.kt                (Task 1)
backend/chawpi-<m>/                                                                   (Tasks 2–13)
  build.gradle.kts
  src/main/kotlin/chawpi/<m>/…                     ported classes from the original + new seams
  src/main/kotlin/chawpi/<m>/autoconfigure/Chawpi<M>AutoConfiguration.kt, Chawpi<M>Properties.kt
  src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  src/main/resources/db/chawpi/<m>/V1__<m>.sql      (not agent)
  src/test/kotlin/chawpi/<m>/…                      ported unit tests + new tests
backend/chawpi-agent/src/main/resources/META-INF/spring.factories                     (EmbabelGate)
backend/starters/chawpi-spring-boot-starter/build.gradle.kts                          (Task 14)
backend/starters/chawpi-spring-boot-starter-<m>/build.gradle.kts   × 8                (Task 14)
backend/chawpi-integration-tests/build.gradle.kts + src/test/kotlin/chawpi/it/AllModulesWiringTest.kt (Task 15; P3 extends)
backend/chawpi-core/…/data/{RelatedRecordService,RecordService}.kt, CoreHardeningApiTest.kt   (Task 16)
```

## Shared conventions (every task repeats what it needs)

Shell variables (define in each shell):

```bash
cd /Users/jorge/IdeaProjects/chawpi
SRC=/Users/jorge/IdeaProjects/sapgis/backend/src/main/kotlin/com/sapgis
TSRC=/Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis
MIG=/Users/jorge/IdeaProjects/sapgis/backend/src/main/resources/db/migration
```

The `port` function (paste into the shell before copying files). Order matters: property prefixes before the schema rule, the schema rule before the blanket brand rename.

```bash
port() {
  sed -i '' -E \
    -e 's/^package com\.sapgis\.([a-z]+)$/package chawpi.\1/' \
    -e 's/com\.sapgis\.(views|forms|pages|workflow|automation|documents|gis|agent)\./chawpi.\1./g' \
    -e 's/com\.sapgis\.data\.ObjectSchemaManager/chawpi.core.metadata.ObjectSchemaManager/g' \
    -e 's/com\.sapgis\./chawpi.core./g' \
    -e 's/SapgisException/ChawpiException/g' \
    -e 's/sapgis\.geoserver/chawpi.gis.geoserver/g' \
    -e 's/sapgis\.(agent|automation)/chawpi.\1/g' \
    "$@"
  sed -i '' -E -e '/^[[:space:]]*\/\//!s/([^a-zA-Z_.])sapgis\.([a-z_]+)/\1${schemas.metadata}.\2/g' "$@"
  sed -i '' -e 's/SAPGIS/Chawpi/g' -e 's/Sapgis/Chawpi/g' -e 's/sapgis/chawpi/g' "$@"
}
```

After `port`, every class whose SQL now says `${schemas.metadata}` needs `private val schemas: ChawpiSchemas` as its last constructor parameter and `import chawpi.core.platform.ChawpiSchemas` (M9).

---

### Task 1: Prep — `@ChawpiApplication`, shared context runner, warm build (Wave 0)

The only shared edits of P2. Everything later builds on an already-compiled core, so parallel tasks do not fight over core's build directory.

**Files:**
- Create: `backend/chawpi-core/src/main/kotlin/chawpi/core/autoconfigure/ChawpiApplication.kt`
- Create: `backend/chawpi-core/src/test/kotlin/chawpi/core/autoconfigure/ChawpiApplicationTest.kt`
- Modify: `backend/chawpi-test/build.gradle.kts` (add two dependency lines)
- Create: `backend/chawpi-test/src/main/kotlin/chawpi/test/ChawpiContextRunner.kt`
- Create: `backend/chawpi-test/src/test/kotlin/chawpi/test/ChawpiContextRunnerTest.kt`

**Interfaces:**
- Consumes: core auto-configs `chawpi.core.autoconfigure.ChawpiPlatformAutoConfiguration`, `ChawpiSecurityAutoConfiguration`, `ChawpiMetadataAutoConfiguration`, `ChawpiDataAutoConfiguration`, `ChawpiAdminAutoConfiguration`; properties `chawpi.database.migrate`, `chawpi.security.jwt.secret`.
- Produces:
  - `annotation class chawpi.core.autoconfigure.ChawpiApplication` (= `@SpringBootApplication` + `@ConfigurationPropertiesScan`).
  - `object chawpi.test.ChawpiContextRunner { fun core(): ReactiveWebApplicationContextRunner }` — every core auto-config (plus Boot's security ones), a Mockito `DatabaseClient`, a `JsonMapper`, `chawpi.database.migrate=false` and a 32-byte test JWT secret. No database is touched. Every module's `Chawpi<M>AutoConfigurationTest` starts from it.

- [ ] **Step 1: Write the failing test for the annotation**

`backend/chawpi-core/src/test/kotlin/chawpi/core/autoconfigure/ChawpiApplicationTest.kt`:

```kotlin
package chawpi.core.autoconfigure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.core.annotation.MergedAnnotations

// no sample class annotated with it here: @SpringBootTest's configuration search is recursive and
// would find a second @SpringBootConfiguration in core's test tree.
class ChawpiApplicationTest {
    @Test
    fun `the annotation is a boot app that scans the app's own properties`() {
        val meta = MergedAnnotations.from(ChawpiApplication::class.java)
        assertThat(meta.isPresent(SpringBootApplication::class.java)).isTrue()
        assertThat(meta.isPresent(ConfigurationPropertiesScan::class.java)).isTrue()
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :chawpi-core:test --tests 'chawpi.core.autoconfigure.ChawpiApplicationTest'`
Expected: FAIL, compilation error `Unresolved reference 'ChawpiApplication'`.

- [ ] **Step 3: Write the annotation**

`backend/chawpi-core/src/main/kotlin/chawpi/core/autoconfigure/ChawpiApplication.kt`:

```kotlin
package chawpi.core.autoconfigure

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import java.lang.annotation.Inherited

// an app's main class in one word: boot's app plus scanning of the app's own @ConfigurationProperties.
// scanning starts at the app's package, so library code is still never scanned: auto-config wires it.
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Inherited
@SpringBootApplication
@ConfigurationPropertiesScan
annotation class ChawpiApplication
```

- [ ] **Step 4: Run it to see it pass, and core's arch test still green**

Run: `./gradlew :chawpi-core:test --tests 'chawpi.core.autoconfigure.ChawpiApplicationTest' --tests 'chawpi.core.architecture.CoreArchitectureTest'`
Expected: PASS (both).

- [ ] **Step 5: Let chawpi-test see core**

In `backend/chawpi-test/build.gradle.kts`, add these two lines at the end of the `dependencies { }` block (after `implementation(libs.postgresql.jdbc)`):

```kotlin
    // ChawpiContextRunner names core's auto-configs. compileOnly: every user already has core.
    compileOnly(project(":chawpi-core"))
    testImplementation(project(":chawpi-core"))
```

- [ ] **Step 6: Write the failing runner test**

`backend/chawpi-test/src/test/kotlin/chawpi/test/ChawpiContextRunnerTest.kt`:

```kotlin
package chawpi.test

import chawpi.core.data.RecordService
import chawpi.core.platform.ChawpiMigrations
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChawpiContextRunnerTest {
    @Test
    fun `core wires with no database and runs no migration`() {
        ChawpiContextRunner.core().run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(RecordService::class.java)
            assertThat(context).doesNotHaveBean(ChawpiMigrations::class.java)
        }
    }
}
```

Run: `./gradlew :chawpi-test:test --tests 'chawpi.test.ChawpiContextRunnerTest'`
Expected: FAIL, `Unresolved reference 'ChawpiContextRunner'`.

- [ ] **Step 7: Write the runner**

`backend/chawpi-test/src/main/kotlin/chawpi/test/ChawpiContextRunner.kt`:

```kotlin
package chawpi.test

import chawpi.core.autoconfigure.ChawpiAdminAutoConfiguration
import chawpi.core.autoconfigure.ChawpiDataAutoConfiguration
import chawpi.core.autoconfigure.ChawpiMetadataAutoConfiguration
import chawpi.core.autoconfigure.ChawpiPlatformAutoConfiguration
import chawpi.core.autoconfigure.ChawpiSecurityAutoConfiguration
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.ReactiveOAuth2ResourceServerAutoConfiguration
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.reactive.ReactiveOAuth2ResourceServerWebSecurityAutoConfiguration
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper

// core's whole bean graph on a mocked database: enough to prove a module's auto-config wires,
// backs off and joins core's SPIs, without docker. migrations are off, so nothing connects.
object ChawpiContextRunner {
    const val TEST_SECRET = "0123456789abcdef0123456789abcdef"

    fun core(): ReactiveWebApplicationContextRunner =
        ReactiveWebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    ReactiveUserDetailsServiceAutoConfiguration::class.java,
                    ReactiveWebSecurityAutoConfiguration::class.java,
                    ReactiveOAuth2ResourceServerAutoConfiguration::class.java,
                    ReactiveOAuth2ResourceServerWebSecurityAutoConfiguration::class.java,
                    ChawpiPlatformAutoConfiguration::class.java,
                    ChawpiSecurityAutoConfiguration::class.java,
                    ChawpiMetadataAutoConfiguration::class.java,
                    ChawpiDataAutoConfiguration::class.java,
                    ChawpiAdminAutoConfiguration::class.java
                )
            ).withBean(DatabaseClient::class.java, { mock(DatabaseClient::class.java) })
            .withBean(JsonMapper::class.java, { JsonMapper.builder().build() })
            .withPropertyValues("chawpi.database.migrate=false", "chawpi.security.jwt.secret=$TEST_SECRET")
}
```

- [ ] **Step 8: Run it to see it pass**

Run: `./gradlew :chawpi-test:test`
Expected: PASS (`ChawpiContextRunnerTest` and the existing `ChawpiTestDatabaseTest`).

- [ ] **Step 9: Warm the build for the parallel waves, format, leave uncommitted**

```bash
./gradlew :chawpi-core:ktlintFormat :chawpi-test:ktlintFormat
./gradlew :chawpi-core:ktlintCheck :chawpi-test:ktlintCheck
./gradlew :chawpi-core:test :chawpi-core:jar :chawpi-test:jar
git status --short backend/chawpi-core backend/chawpi-test
```
Expected: `BUILD SUCCESSFUL` both times (core unit tests unchanged plus the new one). `git status` lists the new/modified files as uncommitted.

### Task 2: `chawpi-views` (Wave 1)

**Files:**
- Create: `backend/chawpi-views/build.gradle.kts`
- Create (ported from `$SRC/views/`): `backend/chawpi-views/src/main/kotlin/chawpi/views/{View,ViewController,ViewRepository,ViewService}.kt`
- Create: `backend/chawpi-views/src/main/kotlin/chawpi/views/ViewMetadataController.kt`
- Create: `backend/chawpi-views/src/main/kotlin/chawpi/views/autoconfigure/{ChawpiViewsProperties,ChawpiViewsAutoConfiguration}.kt`
- Create: `backend/chawpi-views/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `backend/chawpi-views/src/main/resources/db/chawpi/views/V1__views.sql`
- Test: `backend/chawpi-views/src/test/kotlin/chawpi/views/{ViewsMigrationSqlTest,ChawpiViewsAutoConfigurationTest}.kt`

**Interfaces:**
- Consumes (core): `MetadataService`, `CurrentUser`, `ChawpiSchemas`, `ModuleMigration`, `DatabaseClient`, `JsonMapper`; test helper `chawpi.test.ChawpiContextRunner.core()` (Task 1).
- Produces: `chawpi.views.ViewService` (same public methods as the original, incl. `suspend fun forObject(objectName: String): List<ResolvedView>`), `ViewController` (`/api/objects/{object}/views…`), `ViewMetadataController` (`GET /api/metadata/objects/{object}/views`), `chawpi.views.autoconfigure.ChawpiViewsAutoConfiguration`, migration `ModuleMigration("views", "classpath:db/chawpi/views", 100)`, property `chawpi.views.enabled`.

- [ ] **Step 1: Module build file and folders**

```bash
cd /Users/jorge/IdeaProjects/chawpi
mkdir -p backend/chawpi-views/src/main/kotlin/chawpi/views/autoconfigure backend/chawpi-views/src/main/resources/META-INF/spring \
  backend/chawpi-views/src/main/resources/db/chawpi/views backend/chawpi-views/src/test/kotlin/chawpi/views
```

`backend/chawpi-views/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.spring-module")
    id("chawpi.publishing")
    id("chawpi.integration-test")
}

description = "Chawpi views: named list views per object"

dependencies {
    api(project(":chawpi-core"))

    testImplementation(project(":chawpi-test"))
}
```

- [ ] **Step 2: Copy the original's classes and run the port rules**

```bash
SRC=/Users/jorge/IdeaProjects/sapgis/backend/src/main/kotlin/com/sapgis
DST=backend/chawpi-views/src/main/kotlin/chawpi/views
cp $SRC/views/View.kt $SRC/views/ViewController.kt $SRC/views/ViewRepository.kt $SRC/views/ViewService.kt $DST/
port() {
  sed -i '' -E \
    -e 's/^package com\.sapgis\.([a-z]+)$/package chawpi.\1/' \
    -e 's/com\.sapgis\.(views|forms|pages|workflow|automation|documents|gis|agent)\./chawpi.\1./g' \
    -e 's/com\.sapgis\.data\.ObjectSchemaManager/chawpi.core.metadata.ObjectSchemaManager/g' \
    -e 's/com\.sapgis\./chawpi.core./g' \
    -e 's/SapgisException/ChawpiException/g' \
    -e 's/sapgis\.geoserver/chawpi.gis.geoserver/g' \
    -e 's/sapgis\.(agent|automation)/chawpi.\1/g' \
    "$@"
  sed -i '' -E -e '/^[[:space:]]*\/\//!s/([^a-zA-Z_.])sapgis\.([a-z_]+)/\1${schemas.metadata}.\2/g' "$@"
  sed -i '' -e 's/SAPGIS/Chawpi/g' -e 's/Sapgis/Chawpi/g' -e 's/sapgis/chawpi/g' "$@"
}
port $DST/*.kt
grep -n 'schemas.metadata' $DST/*.kt | cut -c1-120
```
Expected: only `ViewRepository.kt` lines mention `${schemas.metadata}.views`.

- [ ] **Step 3: Give the repository its schema**

In `$DST/ViewRepository.kt` replace

```kotlin
    private val objectMapper: ObjectMapper
) {
```
with
```kotlin
    private val objectMapper: ObjectMapper,
    private val schemas: ChawpiSchemas
) {
```
and add `import chawpi.core.platform.ChawpiSchemas` to the imports (sorted).

- [ ] **Step 4: Add the metadata route (the original had it in `ObjectMetadataController`)**

`backend/chawpi-views/src/main/kotlin/chawpi/views/ViewMetadataController.kt`:

```kotlin
package chawpi.views

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// same url as before the split: core no longer knows views exist, so views answers it itself.
// only stored views: the generated default lives behind /api/objects/{object}/views
@RestController
@RequestMapping("/api/metadata/objects")
class ViewMetadataController(
    private val views: ViewService
) {
    @GetMapping("/{object}/views")
    suspend fun views(
        @PathVariable("object") name: String
    ): List<ViewResponse> = views.forObject(name).map { it.toResponse() }
}
```

Run: `./gradlew :chawpi-views:compileKotlin`
Expected: `BUILD SUCCESSFUL`. If an import still fails, it is a core rename: check the symbol with `grep -rn "fun <name>\|class <name>" backend/chawpi-core/src/main/kotlin` and fix the import only.

- [ ] **Step 5: Write the failing migration test**

`backend/chawpi-views/src/test/kotlin/chawpi/views/ViewsMigrationSqlTest.kt`:

```kotlin
package chawpi.views

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ViewsMigrationSqlTest {
    private val sql = javaClass.getResource("/db/chawpi/views/V1__views.sql")!!.readText()

    @Test
    fun `views are tenant scoped with at most one default per object`() {
        assertThat(sql)
            .contains("CREATE TABLE \${metadataSchema}.views (")
            .contains("organization_id uuid NOT NULL REFERENCES \${metadataSchema}.organizations (id) ON DELETE CASCADE")
            .contains("is_default      boolean NOT NULL DEFAULT false")
            .contains("CONSTRAINT views_name_unique_per_object UNIQUE (object_id, name)")
            .contains("CREATE UNIQUE INDEX views_one_default_per_object ON \${metadataSchema}.views (object_id) WHERE is_default;")
            .doesNotContainIgnoringCase("sapgis")
            .doesNotContain("app_data")
    }
}
```

Run: `./gradlew :chawpi-views:test --tests 'chawpi.views.ViewsMigrationSqlTest'`
Expected: FAIL with `NullPointerException` (resource missing).

- [ ] **Step 6: Write the migration (the original's V1 `views` + V5, final shape)**

`backend/chawpi-views/src/main/resources/db/chawpi/views/V1__views.sql`:

```sql
-- chawpi-views: named list views of an object. the original's V1 + V5 rebaselined (ADR-0026): the
-- final shape only, no back-fill, clean start. column order matches the original.
-- scoped to the organization so two tenants can use the same names.

CREATE TABLE ${metadataSchema}.views (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    object_id       uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    name            text NOT NULL,
    label           text NOT NULL,
    definition      jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    is_default      boolean NOT NULL DEFAULT false,
    CONSTRAINT views_name_unique_per_object UNIQUE (object_id, name)
);

-- at most one view per object opens by default
CREATE UNIQUE INDEX views_one_default_per_object ON ${metadataSchema}.views (object_id) WHERE is_default;
```

Run: `./gradlew :chawpi-views:test --tests 'chawpi.views.ViewsMigrationSqlTest'`
Expected: PASS.

- [ ] **Step 7: Write the failing auto-config test**

`backend/chawpi-views/src/test/kotlin/chawpi/views/ChawpiViewsAutoConfigurationTest.kt`:

```kotlin
package chawpi.views

import chawpi.core.platform.ModuleMigration
import chawpi.test.ChawpiContextRunner
import chawpi.views.autoconfigure.ChawpiViewsAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates

class ChawpiViewsAutoConfigurationTest {
    private val runner = ChawpiContextRunner.core().withConfiguration(AutoConfigurations.of(ChawpiViewsAutoConfiguration::class.java))

    @Test
    fun `views wires on core and brings its migration`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(ViewService::class.java)
            assertThat(context).hasSingleBean(ViewController::class.java)
            assertThat(context).hasSingleBean(ViewMetadataController::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactlyInAnyOrder("core", "views")
        }
    }

    @Test
    fun `switched off, core boots without any views bean`() {
        runner.withPropertyValues("chawpi.views.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(ViewService::class.java)
            assertThat(context).doesNotHaveBean(ViewMetadataController::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactly("core")
        }
    }

    @Test
    fun `an app's own service wins`() {
        val mine = mock(ViewService::class.java)
        runner.withBean(ViewService::class.java, { mine }).run { context ->
            assertThat(context.getBean(ViewService::class.java)).isSameAs(mine)
        }
    }

    @Test
    fun `the imports file registers the auto-config`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("chawpi.views.autoconfigure.ChawpiViewsAutoConfiguration")
    }
}
```

Run: `./gradlew :chawpi-views:test --tests 'chawpi.views.ChawpiViewsAutoConfigurationTest'`
Expected: FAIL, `Unresolved reference 'ChawpiViewsAutoConfiguration'`.

- [ ] **Step 8: Properties, auto-config and imports file**

`backend/chawpi-views/src/main/kotlin/chawpi/views/autoconfigure/ChawpiViewsProperties.kt`:

```kotlin
package chawpi.views.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("chawpi.views")
data class ChawpiViewsProperties(
    // false: no views beans, routes or migration
    val enabled: Boolean = true
)
```

`backend/chawpi-views/src/main/kotlin/chawpi/views/autoconfigure/ChawpiViewsAutoConfiguration.kt`:

```kotlin
package chawpi.views.autoconfigure

import chawpi.core.autoconfigure.ChawpiDataAutoConfiguration
import chawpi.core.identity.CurrentUser
import chawpi.core.metadata.MetadataService
import chawpi.core.platform.ChawpiSchemas
import chawpi.core.platform.ModuleMigration
import chawpi.views.ViewController
import chawpi.views.ViewMetadataController
import chawpi.views.ViewRepository
import chawpi.views.ViewService
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper

// named list views. no scanning: every bean here, each one replaceable by the app.
@AutoConfiguration(after = [ChawpiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "chawpi.views", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ChawpiViewsProperties::class)
class ChawpiViewsAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would always make it back off
    @Bean
    fun chawpiViewsMigration(): ModuleMigration = ModuleMigration("views", "classpath:db/chawpi/views", ModuleMigration.MODULE_ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun viewRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: ChawpiSchemas
    ): ViewRepository = ViewRepository(db, objectMapper, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun viewService(
        views: ViewRepository,
        metadata: MetadataService,
        currentUser: CurrentUser
    ): ViewService = ViewService(views, metadata, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun viewController(views: ViewService): ViewController = ViewController(views)

    @Bean
    @ConditionalOnMissingBean
    fun viewMetadataController(views: ViewService): ViewMetadataController = ViewMetadataController(views)
}
```

`backend/chawpi-views/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
chawpi.views.autoconfigure.ChawpiViewsAutoConfiguration
```

Run: `./gradlew :chawpi-views:test`
Expected: PASS (5 tests).

- [ ] **Step 9: Format, nothing named after the original, leave uncommitted**

```bash
./gradlew :chawpi-views:ktlintFormat
./gradlew :chawpi-views:ktlintCheck
./gradlew :chawpi-views:jar
grep -rn -i "sapgis" backend/chawpi-views || echo "clean"
git status --short backend/chawpi-views
```
Expected: ktlint passes, `clean`, `?? backend/chawpi-views/`.

### Task 3: `chawpi-forms` (Wave 1)

**Files:**
- Create: `backend/chawpi-forms/build.gradle.kts`
- Create (ported from `$SRC/forms/`): `backend/chawpi-forms/src/main/kotlin/chawpi/forms/{Form,FormController,FormRepository,FormService}.kt`
- Create: `backend/chawpi-forms/src/main/kotlin/chawpi/forms/FormMetadataController.kt`
- Create: `backend/chawpi-forms/src/main/kotlin/chawpi/forms/autoconfigure/{ChawpiFormsProperties,ChawpiFormsAutoConfiguration}.kt`
- Create: `backend/chawpi-forms/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `backend/chawpi-forms/src/main/resources/db/chawpi/forms/V1__forms.sql`
- Test: `backend/chawpi-forms/src/test/kotlin/chawpi/forms/{FormsMigrationSqlTest,ChawpiFormsAutoConfigurationTest}.kt`

**Interfaces:**
- Consumes (core): `MetadataService`, `CurrentUser`, `ChawpiSchemas`, `ModuleMigration`, `DatabaseClient`, `JsonMapper`; `chawpi.test.ChawpiContextRunner.core()` (Task 1).
- Produces: `chawpi.forms.FormService` with, among the original's methods, `suspend fun forObject(objectName: String): List<ResolvedForm>` and `suspend fun storedNames(objectId: UUID): Set<String>` (Task 9 uses it); `FormController` (`/api/objects/{object}/forms…`); `FormMetadataController` (`GET /api/metadata/objects/{object}/forms`); `chawpi.forms.autoconfigure.ChawpiFormsAutoConfiguration` (Task 9 runs `after` it); migration `ModuleMigration("forms", "classpath:db/chawpi/forms", 100)`; property `chawpi.forms.enabled`.

- [ ] **Step 1: Module build file and folders**

```bash
cd /Users/jorge/IdeaProjects/chawpi
mkdir -p backend/chawpi-forms/src/main/kotlin/chawpi/forms/autoconfigure backend/chawpi-forms/src/main/resources/META-INF/spring \
  backend/chawpi-forms/src/main/resources/db/chawpi/forms backend/chawpi-forms/src/test/kotlin/chawpi/forms
```

`backend/chawpi-forms/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.spring-module")
    id("chawpi.publishing")
    id("chawpi.integration-test")
}

description = "Chawpi forms: named, sectioned forms per object"

dependencies {
    api(project(":chawpi-core"))

    testImplementation(project(":chawpi-test"))
}
```

- [ ] **Step 2: Copy the original's classes and run the port rules**

```bash
SRC=/Users/jorge/IdeaProjects/sapgis/backend/src/main/kotlin/com/sapgis
DST=backend/chawpi-forms/src/main/kotlin/chawpi/forms
cp $SRC/forms/Form.kt $SRC/forms/FormController.kt $SRC/forms/FormRepository.kt $SRC/forms/FormService.kt $DST/
port() {
  sed -i '' -E \
    -e 's/^package com\.sapgis\.([a-z]+)$/package chawpi.\1/' \
    -e 's/com\.sapgis\.(views|forms|pages|workflow|automation|documents|gis|agent)\./chawpi.\1./g' \
    -e 's/com\.sapgis\.data\.ObjectSchemaManager/chawpi.core.metadata.ObjectSchemaManager/g' \
    -e 's/com\.sapgis\./chawpi.core./g' \
    -e 's/SapgisException/ChawpiException/g' \
    -e 's/sapgis\.geoserver/chawpi.gis.geoserver/g' \
    -e 's/sapgis\.(agent|automation)/chawpi.\1/g' \
    "$@"
  sed -i '' -E -e '/^[[:space:]]*\/\//!s/([^a-zA-Z_.])sapgis\.([a-z_]+)/\1${schemas.metadata}.\2/g' "$@"
  sed -i '' -e 's/SAPGIS/Chawpi/g' -e 's/Sapgis/Chawpi/g' -e 's/sapgis/chawpi/g' "$@"
}
port $DST/*.kt
grep -n 'schemas.metadata' $DST/*.kt | cut -c1-120
```
Expected: only `FormRepository.kt` lines mention `${schemas.metadata}.forms`.

- [ ] **Step 3: Give the repository its schema**

In `$DST/FormRepository.kt` replace

```kotlin
    private val objectMapper: ObjectMapper
) {
```
with
```kotlin
    private val objectMapper: ObjectMapper,
    private val schemas: ChawpiSchemas
) {
```
and add `import chawpi.core.platform.ChawpiSchemas` to the imports (sorted).

- [ ] **Step 4: Add the metadata route (the original had it in `ObjectMetadataController`)**

`backend/chawpi-forms/src/main/kotlin/chawpi/forms/FormMetadataController.kt`:

```kotlin
package chawpi.forms

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// same url as before the split: core no longer knows forms exist, so forms answers it itself.
// only stored forms: the generated default lives behind /api/objects/{object}/forms
@RestController
@RequestMapping("/api/metadata/objects")
class FormMetadataController(
    private val forms: FormService
) {
    @GetMapping("/{object}/forms")
    suspend fun forms(
        @PathVariable("object") name: String
    ): List<FormResponse> = forms.forObject(name).map { it.toResponse() }
}
```

Run: `./gradlew :chawpi-forms:compileKotlin`
Expected: `BUILD SUCCESSFUL`. If an import still fails, it is a core rename: check the symbol with `grep -rn "fun <name>\|class <name>" backend/chawpi-core/src/main/kotlin` and fix the import only.

- [ ] **Step 5: Write the failing migration test**

`backend/chawpi-forms/src/test/kotlin/chawpi/forms/FormsMigrationSqlTest.kt`:

```kotlin
package chawpi.forms

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FormsMigrationSqlTest {
    private val sql = javaClass.getResource("/db/chawpi/forms/V1__forms.sql")!!.readText()

    @Test
    fun `forms are tenant scoped and unique by name per object`() {
        assertThat(sql)
            .contains("CREATE TABLE \${metadataSchema}.forms (")
            .contains("organization_id uuid NOT NULL REFERENCES \${metadataSchema}.organizations (id) ON DELETE CASCADE")
            .contains("CONSTRAINT forms_name_unique_per_object UNIQUE (object_id, name)")
            .doesNotContainIgnoringCase("sapgis")
            .doesNotContain("app_data")
    }
}
```

Run: `./gradlew :chawpi-forms:test --tests 'chawpi.forms.FormsMigrationSqlTest'`
Expected: FAIL with `NullPointerException` (resource missing).

- [ ] **Step 6: Write the migration (the original's V1 `forms` + V5, final shape)**

`backend/chawpi-forms/src/main/resources/db/chawpi/forms/V1__forms.sql`:

```sql
-- chawpi-forms: named forms of an object. the original's V1 + V5 rebaselined (ADR-0026): the final shape
-- only, no back-fill, clean start. column order matches the original.

CREATE TABLE ${metadataSchema}.forms (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    object_id       uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    name            text NOT NULL,
    label           text NOT NULL,
    definition      jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    CONSTRAINT forms_name_unique_per_object UNIQUE (object_id, name)
);
```

Run: `./gradlew :chawpi-forms:test --tests 'chawpi.forms.FormsMigrationSqlTest'`
Expected: PASS.

- [ ] **Step 7: Write the failing auto-config test**

`backend/chawpi-forms/src/test/kotlin/chawpi/forms/ChawpiFormsAutoConfigurationTest.kt`:

```kotlin
package chawpi.forms

import chawpi.core.platform.ModuleMigration
import chawpi.forms.autoconfigure.ChawpiFormsAutoConfiguration
import chawpi.test.ChawpiContextRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates

class ChawpiFormsAutoConfigurationTest {
    private val runner = ChawpiContextRunner.core().withConfiguration(AutoConfigurations.of(ChawpiFormsAutoConfiguration::class.java))

    @Test
    fun `forms wires on core and brings its migration`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(FormService::class.java)
            assertThat(context).hasSingleBean(FormController::class.java)
            assertThat(context).hasSingleBean(FormMetadataController::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactlyInAnyOrder("core", "forms")
        }
    }

    @Test
    fun `switched off, core boots without any forms bean`() {
        runner.withPropertyValues("chawpi.forms.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(FormService::class.java)
            assertThat(context).doesNotHaveBean(FormMetadataController::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactly("core")
        }
    }

    @Test
    fun `an app's own service wins`() {
        val mine = mock(FormService::class.java)
        runner.withBean(FormService::class.java, { mine }).run { context ->
            assertThat(context.getBean(FormService::class.java)).isSameAs(mine)
        }
    }

    @Test
    fun `the imports file registers the auto-config`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("chawpi.forms.autoconfigure.ChawpiFormsAutoConfiguration")
    }
}
```

Run: `./gradlew :chawpi-forms:test --tests 'chawpi.forms.ChawpiFormsAutoConfigurationTest'`
Expected: FAIL, `Unresolved reference 'ChawpiFormsAutoConfiguration'`.

- [ ] **Step 8: Properties, auto-config and imports file**

`backend/chawpi-forms/src/main/kotlin/chawpi/forms/autoconfigure/ChawpiFormsProperties.kt`:

```kotlin
package chawpi.forms.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("chawpi.forms")
data class ChawpiFormsProperties(
    // false: no forms beans, routes or migration (and chawpi-pages backs off with it)
    val enabled: Boolean = true
)
```

`backend/chawpi-forms/src/main/kotlin/chawpi/forms/autoconfigure/ChawpiFormsAutoConfiguration.kt`:

```kotlin
package chawpi.forms.autoconfigure

import chawpi.core.autoconfigure.ChawpiDataAutoConfiguration
import chawpi.core.identity.CurrentUser
import chawpi.core.metadata.MetadataService
import chawpi.core.platform.ChawpiSchemas
import chawpi.core.platform.ModuleMigration
import chawpi.forms.FormController
import chawpi.forms.FormMetadataController
import chawpi.forms.FormRepository
import chawpi.forms.FormService
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper

// named forms. no scanning: every bean here, each one replaceable by the app.
@AutoConfiguration(after = [ChawpiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "chawpi.forms", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ChawpiFormsProperties::class)
class ChawpiFormsAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would always make it back off
    @Bean
    fun chawpiFormsMigration(): ModuleMigration = ModuleMigration("forms", "classpath:db/chawpi/forms", ModuleMigration.MODULE_ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun formRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: ChawpiSchemas
    ): FormRepository = FormRepository(db, objectMapper, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun formService(
        forms: FormRepository,
        metadata: MetadataService,
        currentUser: CurrentUser
    ): FormService = FormService(forms, metadata, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun formController(forms: FormService): FormController = FormController(forms)

    @Bean
    @ConditionalOnMissingBean
    fun formMetadataController(forms: FormService): FormMetadataController = FormMetadataController(forms)
}
```

`backend/chawpi-forms/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
chawpi.forms.autoconfigure.ChawpiFormsAutoConfiguration
```

Run: `./gradlew :chawpi-forms:test`
Expected: PASS (5 tests).

- [ ] **Step 9: Format, nothing named after the original, leave uncommitted**

```bash
./gradlew :chawpi-forms:ktlintFormat
./gradlew :chawpi-forms:ktlintCheck
./gradlew :chawpi-forms:jar
grep -rn -i "sapgis" backend/chawpi-forms || echo "clean"
git status --short backend/chawpi-forms
```
Expected: ktlint passes, `clean`, `?? backend/chawpi-forms/`.

### Task 4: `chawpi-workflow` (Wave 1)

The WORKFLOW page component is Task 13 (it needs chawpi-pages).

**Files:**
- Create: `backend/chawpi-workflow/build.gradle.kts`
- Create (ported from `$SRC/workflow/`): `backend/chawpi-workflow/src/main/kotlin/chawpi/workflow/{Workflow,WorkflowController,WorkflowRepository,WorkflowService,WorkflowStatesAdapter}.kt`
- Create: `backend/chawpi-workflow/src/main/kotlin/chawpi/workflow/WorkflowSystemColumns.kt`
- Create: `backend/chawpi-workflow/src/main/kotlin/chawpi/workflow/autoconfigure/{ChawpiWorkflowProperties,ChawpiWorkflowAutoConfiguration}.kt`
- Create: `backend/chawpi-workflow/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `backend/chawpi-workflow/src/main/resources/db/chawpi/workflow/V1__workflow.sql`
- Test: `backend/chawpi-workflow/src/test/kotlin/chawpi/workflow/{WorkflowMigrationSqlTest,WorkflowStatesAdapterTest,ChawpiWorkflowAutoConfigurationTest}.kt`

**Interfaces:**
- Consumes (core): `RoleDirectory`, `MetadataService`, `ObjectSchemaManager` (`chawpi.core.metadata`, `addStateColumn`, `STATE_COLUMN = "workflow_state"`), `RecordStore`, `AuditService`, `CurrentUser`, `AccessPolicy`, `RecordChangeListener`/`RecordChange`/`RecordChangeKind`, `WorkflowStates`/`ObjectWorkflowState`, `SystemColumnContributor`/`SystemColumn`, `ChawpiDataAutoConfiguration`; `ChawpiContextRunner.core()` (Task 1).
- Produces:
  - `chawpi.workflow.WorkflowService(roles, workflows, metadata, schema, store, audit, currentUser, access, changes: List<RecordChangeListener>)` with the original's public methods, incl. `suspend fun transitionsOf(objectName: String, id: UUID): List<AvailableTransition>` (Task 11 wraps it).
  - `data class AvailableTransition(name, label, to, toLabel, allowed, reason)` (unchanged from the original).
  - `chawpi.workflow.WorkflowStatesAdapter(workflows: WorkflowRepository) : WorkflowStates` (Task 13 uses the `WorkflowStates` bean).
  - `chawpi.workflow.WorkflowSystemColumns : SystemColumnContributor` → `SystemColumn("workflow_state", "TEXT", "WORKFLOW")`.
  - `chawpi.workflow.autoconfigure.ChawpiWorkflowAutoConfiguration` (`before = ChawpiDataAutoConfiguration`), migration `ModuleMigration("workflow", "classpath:db/chawpi/workflow", 100)`, property `chawpi.workflow.enabled`.

- [ ] **Step 1: Module build file and folders**

```bash
cd /Users/jorge/IdeaProjects/chawpi
mkdir -p backend/chawpi-workflow/src/main/kotlin/chawpi/workflow/autoconfigure backend/chawpi-workflow/src/main/resources/META-INF/spring \
  backend/chawpi-workflow/src/main/resources/db/chawpi/workflow backend/chawpi-workflow/src/test/kotlin/chawpi/workflow
```

`backend/chawpi-workflow/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.spring-module")
    id("chawpi.publishing")
    id("chawpi.integration-test")
}

description = "Chawpi workflow: record states and transitions per object"

dependencies {
    api(project(":chawpi-core"))

    testImplementation(project(":chawpi-test"))
}
```

- [ ] **Step 2: Copy the original's classes and run the port rules**

```bash
SRC=/Users/jorge/IdeaProjects/sapgis/backend/src/main/kotlin/com/sapgis
DST=backend/chawpi-workflow/src/main/kotlin/chawpi/workflow
cp $SRC/workflow/Workflow.kt $SRC/workflow/WorkflowController.kt $SRC/workflow/WorkflowRepository.kt \
   $SRC/workflow/WorkflowService.kt $SRC/workflow/WorkflowStatesAdapter.kt $DST/
port() {
  sed -i '' -E \
    -e 's/^package com\.sapgis\.([a-z]+)$/package chawpi.\1/' \
    -e 's/com\.sapgis\.(views|forms|pages|workflow|automation|documents|gis|agent)\./chawpi.\1./g' \
    -e 's/com\.sapgis\.data\.ObjectSchemaManager/chawpi.core.metadata.ObjectSchemaManager/g' \
    -e 's/com\.sapgis\./chawpi.core./g' \
    -e 's/SapgisException/ChawpiException/g' \
    -e 's/sapgis\.geoserver/chawpi.gis.geoserver/g' \
    -e 's/sapgis\.(agent|automation)/chawpi.\1/g' \
    "$@"
  sed -i '' -E -e '/^[[:space:]]*\/\//!s/([^a-zA-Z_.])sapgis\.([a-z_]+)/\1${schemas.metadata}.\2/g' "$@"
  sed -i '' -e 's/SAPGIS/Chawpi/g' -e 's/Sapgis/Chawpi/g' -e 's/sapgis/chawpi/g' "$@"
}
port $DST/*.kt
grep -n 'schemas.metadata' $DST/*.kt | cut -c1-120
```
Expected: only `WorkflowRepository.kt` lines mention `${schemas.metadata}.workflows`. `WorkflowStatesAdapter.kt` has a comment "reading chawpi.workflows itself": reword it to `// the data module asks here instead of reading the workflows table itself.`

- [ ] **Step 3: Give the repository its schema**

In `$DST/WorkflowRepository.kt` replace

```kotlin
    private val objectMapper: ObjectMapper
) {
```
with
```kotlin
    private val objectMapper: ObjectMapper,
    private val schemas: ChawpiSchemas
) {
```
and add `import chawpi.core.platform.ChawpiSchemas` to the imports (sorted).

- [ ] **Step 4: Every change listener, not one (M12, P1 R9)**

In `$DST/WorkflowService.kt`:

1. Replace the constructor line `    private val changes: RecordChangeListener` with `    private val changes: List<RecordChangeListener>`.
2. In `apply(...)` (the only caller of `changes`) replace

```kotlin
        changes.recordChanged(
            RecordChange(
```
with
```kotlin
        val change =
            RecordChange(
```
3. and replace

```kotlin
                transition = transition.name
            )
        )
        return moved.toResponse()
```
with
```kotlin
                transition = transition.name
            )
        // every listener, in @Order, inside this transaction (P1 R9)
        changes.forEach { it.recordChanged(change) }
        return moved.toResponse()
```

- [ ] **Step 5: The reserved state column**

`backend/chawpi-workflow/src/main/kotlin/chawpi/workflow/WorkflowSystemColumns.kt`:

```kotlin
package chawpi.workflow

import chawpi.core.metadata.ObjectSchemaManager
import chawpi.core.platform.SystemColumn
import chawpi.core.platform.SystemColumnContributor

// a workflow adds the state column to an object's table (ADR-013), so no user field may take its
// name. core keeps the column mechanics; the reservation is ours.
class WorkflowSystemColumns : SystemColumnContributor {
    override fun systemColumns(): List<SystemColumn> = listOf(SystemColumn(ObjectSchemaManager.STATE_COLUMN, "TEXT", SCOPE))

    companion object {
        // shown to the field editor: "exists only when a workflow is attached"
        const val SCOPE = "WORKFLOW"
    }
}
```

Run: `./gradlew :chawpi-workflow:compileKotlin`
Expected: `BUILD SUCCESSFUL`. If an import still fails, check the symbol with `grep -rn "fun <name>\|class <name>" backend/chawpi-core/src/main/kotlin` and fix the import only.

- [ ] **Step 6: Write the failing adapter and migration tests**

`backend/chawpi-workflow/src/test/kotlin/chawpi/workflow/WorkflowStatesAdapterTest.kt`:

```kotlin
package chawpi.workflow

import chawpi.core.data.ObjectWorkflowState
import chawpi.core.platform.ChawpiSchemas
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

class WorkflowStatesAdapterTest {
    private val org = UUID.randomUUID()
    private val obj = UUID.randomUUID()

    private fun adapterWith(workflow: Workflow?) =
        WorkflowStatesAdapter(
            object : WorkflowRepository(mock(DatabaseClient::class.java), JsonMapper.builder().build(), ChawpiSchemas("chawpi", "app_data")) {
                override suspend fun findByObject(
                    organizationId: UUID,
                    objectId: UUID
                ): Workflow? = workflow
            }
        )

    private fun workflow(enabled: Boolean) =
        Workflow(
            id = UUID.randomUUID(),
            organizationId = org,
            objectId = obj,
            name = "tramite",
            label = "Trámite",
            enabled = enabled,
            definition =
                WorkflowDefinition(
                    states = listOf(WorkflowState("draft", "Draft", StateType.INITIAL), WorkflowState("done", "Done", StateType.FINAL)),
                    transitions = listOf(WorkflowTransition("finish", "Finish", "draft", "done"))
                )
        )

    @Test
    fun `no workflow, no state and nothing to fire`() =
        runTest {
            val adapter = adapterWith(null)
            assertThat(adapter.stateOf(org, obj)).isEqualTo(ObjectWorkflowState.NONE)
            assertThat(adapter.transitionNames(org, obj)).isEmpty()
        }

    @Test
    fun `an enabled workflow starts records in its initial state`() =
        runTest {
            val adapter = adapterWith(workflow(enabled = true))
            assertThat(adapter.stateOf(org, obj)).isEqualTo(ObjectWorkflowState(attached = true, initialState = "draft"))
            assertThat(adapter.transitionNames(org, obj)).containsExactly("finish")
        }

    @Test
    fun `a disabled workflow keeps the column but starts nothing and fires nothing`() =
        runTest {
            val adapter = adapterWith(workflow(enabled = false))
            assertThat(adapter.stateOf(org, obj)).isEqualTo(ObjectWorkflowState(attached = true, initialState = null))
            assertThat(adapter.transitionNames(org, obj)).isEmpty()
        }
}
```

`backend/chawpi-workflow/src/test/kotlin/chawpi/workflow/WorkflowMigrationSqlTest.kt`:

```kotlin
package chawpi.workflow

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkflowMigrationSqlTest {
    private val sql = javaClass.getResource("/db/chawpi/workflow/V1__workflow.sql")!!.readText()

    @Test
    fun `one workflow per object, names unique per organization`() {
        assertThat(sql)
            .contains("CREATE TABLE \${metadataSchema}.workflows (")
            .contains("CONSTRAINT workflows_one_per_object UNIQUE (object_id)")
            .contains("CONSTRAINT workflows_name_unique_per_org UNIQUE (organization_id, name)")
            .contains("CREATE INDEX workflows_org_idx ON \${metadataSchema}.workflows (organization_id);")
            .doesNotContainIgnoringCase("sapgis")
    }
}
```

Run: `./gradlew :chawpi-workflow:test --tests 'chawpi.workflow.WorkflowStatesAdapterTest' --tests 'chawpi.workflow.WorkflowMigrationSqlTest'`
Expected: `WorkflowStatesAdapterTest` PASS (ported behaviour), `WorkflowMigrationSqlTest` FAIL with `NullPointerException`.

- [ ] **Step 7: Write the migration (the original's V7)**

`backend/chawpi-workflow/src/main/resources/db/chawpi/workflow/V1__workflow.sql`:

```sql
-- chawpi-workflow: states and transitions, no BPM engine. the original's V7 rebaselined (ADR-0026).
-- the definition lives here; the record's current state lives in a real column on the physical
-- table (workflow_state), added by core's ObjectSchemaManager when a workflow is attached.

CREATE TABLE ${metadataSchema}.workflows (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    object_id uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    name text NOT NULL,
    label text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    definition jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT workflows_one_per_object UNIQUE (object_id),
    CONSTRAINT workflows_name_unique_per_org UNIQUE (organization_id, name)
);

CREATE INDEX workflows_org_idx ON ${metadataSchema}.workflows (organization_id);
```

Run: `./gradlew :chawpi-workflow:test --tests 'chawpi.workflow.WorkflowMigrationSqlTest'`
Expected: PASS.

- [ ] **Step 8: Write the failing auto-config test**

`backend/chawpi-workflow/src/test/kotlin/chawpi/workflow/ChawpiWorkflowAutoConfigurationTest.kt`:

```kotlin
package chawpi.workflow

import chawpi.core.data.NoWorkflowStates
import chawpi.core.data.RecordChange
import chawpi.core.data.RecordChangeListener
import chawpi.core.data.WorkflowStates
import chawpi.core.platform.ModuleMigration
import chawpi.core.platform.SystemColumns
import chawpi.test.ChawpiContextRunner
import chawpi.workflow.autoconfigure.ChawpiWorkflowAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates

class ChawpiWorkflowAutoConfigurationTest {
    private val runner = ChawpiContextRunner.core().withConfiguration(AutoConfigurations.of(ChawpiWorkflowAutoConfiguration::class.java))

    @Test
    fun `workflow replaces the null object and reserves the state column`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(WorkflowService::class.java)
            assertThat(context).hasSingleBean(WorkflowController::class.java)
            assertThat(context.getBean(WorkflowStates::class.java)).isInstanceOf(WorkflowStatesAdapter::class.java)
            assertThat(context.getBean(SystemColumns::class.java).all.map { it.name })
                .containsExactly("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "workflow_state", "version")
            assertThat(context.getBean(SystemColumns::class.java).all.first { it.name == "workflow_state" }.scope).isEqualTo("WORKFLOW")
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactlyInAnyOrder("core", "workflow")
        }
    }

    @Test
    fun `a neighbour's change listener is handed to the service too`() {
        val neighbour = object : RecordChangeListener {
            override suspend fun recordChanged(change: RecordChange) = Unit
        }
        runner.withBean("neighbourListener", RecordChangeListener::class.java, { neighbour }).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(WorkflowService::class.java)
        }
    }

    @Test
    fun `switched off, records have no state again`() {
        runner.withPropertyValues("chawpi.workflow.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(WorkflowService::class.java)
            assertThat(context.getBean(WorkflowStates::class.java)).isInstanceOf(NoWorkflowStates::class.java)
            assertThat(context.getBean(SystemColumns::class.java).names).doesNotContain("workflow_state")
        }
    }

    @Test
    fun `the imports file registers the auto-config`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("chawpi.workflow.autoconfigure.ChawpiWorkflowAutoConfiguration")
    }
}
```

Run: `./gradlew :chawpi-workflow:test --tests 'chawpi.workflow.ChawpiWorkflowAutoConfigurationTest'`
Expected: FAIL, `Unresolved reference 'ChawpiWorkflowAutoConfiguration'`.

- [ ] **Step 9: Properties, auto-config and imports file**

`backend/chawpi-workflow/src/main/kotlin/chawpi/workflow/autoconfigure/ChawpiWorkflowProperties.kt`:

```kotlin
package chawpi.workflow.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("chawpi.workflow")
data class ChawpiWorkflowProperties(
    // false: no workflow beans, routes or migration; records have no state
    val enabled: Boolean = true
)
```

`backend/chawpi-workflow/src/main/kotlin/chawpi/workflow/autoconfigure/ChawpiWorkflowAutoConfiguration.kt`:

```kotlin
package chawpi.workflow.autoconfigure

import chawpi.core.audit.AuditService
import chawpi.core.autoconfigure.ChawpiDataAutoConfiguration
import chawpi.core.data.RecordChangeListener
import chawpi.core.data.RecordStore
import chawpi.core.identity.AccessPolicy
import chawpi.core.identity.CurrentUser
import chawpi.core.identity.RoleDirectory
import chawpi.core.metadata.MetadataService
import chawpi.core.metadata.ObjectSchemaManager
import chawpi.core.platform.ChawpiSchemas
import chawpi.core.platform.ModuleMigration
import chawpi.workflow.WorkflowController
import chawpi.workflow.WorkflowRepository
import chawpi.workflow.WorkflowService
import chawpi.workflow.WorkflowStatesAdapter
import chawpi.workflow.WorkflowSystemColumns
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper

// record states. before core's data config: our WorkflowStates must exist when core decides
// whether it still needs its NoWorkflowStates null object (P1 R9).
@AutoConfiguration(before = [ChawpiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "chawpi.workflow", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ChawpiWorkflowProperties::class)
class ChawpiWorkflowAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would always make it back off
    @Bean
    fun chawpiWorkflowMigration(): ModuleMigration = ModuleMigration("workflow", "classpath:db/chawpi/workflow", ModuleMigration.MODULE_ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun workflowSystemColumns(): WorkflowSystemColumns = WorkflowSystemColumns()

    @Bean
    @ConditionalOnMissingBean
    fun workflowRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: ChawpiSchemas
    ): WorkflowRepository = WorkflowRepository(db, objectMapper, schemas)

    // named apart from core's "workflowStates" bean: same name would be a bean override, not a replacement
    @Bean
    @ConditionalOnMissingBean
    fun workflowStatesAdapter(workflows: WorkflowRepository): WorkflowStatesAdapter = WorkflowStatesAdapter(workflows)

    @Bean
    @ConditionalOnMissingBean
    fun workflowService(
        roles: RoleDirectory,
        workflows: WorkflowRepository,
        metadata: MetadataService,
        schema: ObjectSchemaManager,
        store: RecordStore,
        audit: AuditService,
        currentUser: CurrentUser,
        access: AccessPolicy,
        changes: ObjectProvider<RecordChangeListener>
    ): WorkflowService = WorkflowService(roles, workflows, metadata, schema, store, audit, currentUser, access, changes.orderedStream().toList())

    @Bean
    @ConditionalOnMissingBean
    fun workflowController(workflows: WorkflowService): WorkflowController = WorkflowController(workflows)
}
```

`backend/chawpi-workflow/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
chawpi.workflow.autoconfigure.ChawpiWorkflowAutoConfiguration
```

Run: `./gradlew :chawpi-workflow:test`
Expected: PASS (8 tests).

- [ ] **Step 10: Format, nothing named after the original, leave uncommitted**

```bash
./gradlew :chawpi-workflow:ktlintFormat
./gradlew :chawpi-workflow:ktlintCheck
./gradlew :chawpi-workflow:jar
grep -rn -i "sapgis" backend/chawpi-workflow || echo "clean"
git status --short backend/chawpi-workflow
```
Expected: ktlint passes, `clean`, `?? backend/chawpi-workflow/`.

### Task 5: `chawpi-automation` (Wave 1)

The documents side of `DocumentIssuer` is Task 10.

**Files:**
- Create: `backend/chawpi-automation/build.gradle.kts`
- Create (ported from `$SRC/automation/`, every file except `Documents.kt`): `backend/chawpi-automation/src/main/kotlin/chawpi/automation/{Automation,AutomationController,AutomationDispatcher,AutomationDrain,AutomationFieldUsage,AutomationProperties,AutomationRepository,AutomationRules,AutomationRun,AutomationRunRepository,AutomationRunner,AutomationService,WebhookSender}.kt`
- Create: `backend/chawpi-automation/src/main/kotlin/chawpi/automation/DocumentIssuer.kt` (replaces the original's `Documents.kt`)
- Create: `backend/chawpi-automation/src/main/kotlin/chawpi/automation/autoconfigure/ChawpiAutomationAutoConfiguration.kt`
- Create: `backend/chawpi-automation/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `backend/chawpi-automation/src/main/resources/db/chawpi/automation/V1__automation.sql`
- Test (ported from `$TSRC/automation/`): `backend/chawpi-automation/src/test/kotlin/chawpi/automation/{AutomationDrainTest,AutomationRulesTest}.kt`
- Test (new): `backend/chawpi-automation/src/test/kotlin/chawpi/automation/{NoDocumentIssuerTest,AutomationMigrationSqlTest,ChawpiAutomationAutoConfigurationTest}.kt`

**Interfaces:**
- Consumes (core): `RecordChangeListener`, `RecordChange`, `RecordChangeKind`, `FieldUsage`, `CustomObject`, `MetadataService`, `RecordStore`, `WorkflowStates`, `AuditService`, `CurrentUser`, `ChawpiSchemas`, `ModuleMigration`; `ChawpiContextRunner.core()` (Task 1).
- Produces:
  - `interface chawpi.automation.DocumentIssuer { suspend fun typeExists(objectId: UUID, name: String): Boolean; suspend fun issue(organizationId: UUID, objectName: String, recordId: UUID, typeName: String): String }` — Task 10 implements it.
  - `class chawpi.automation.NoDocumentIssuer : DocumentIssuer` (null object: no types, issuing throws `ValidationException`).
  - `AutomationProperties` bound at `chawpi.automation` (`enabled`, `poll-interval`, `batch-size`, `max-depth`, `webhook-timeout`, `allow-private-webhooks`).
  - `AutomationDispatcher : RecordChangeListener`, `AutomationFieldUsage : FieldUsage`, `AutomationService`, `AutomationRunner`, `AutomationDrain`, `AutomationController` (`/api/objects/{object}/automations…`, `/api/automation-runs`).
  - `chawpi.automation.autoconfigure.ChawpiAutomationAutoConfiguration` (Task 10 looks the port up lazily; no ordering needed), migration `ModuleMigration("automation", "classpath:db/chawpi/automation", 100)`.

- [ ] **Step 1: Module build file and folders**

```bash
cd /Users/jorge/IdeaProjects/chawpi
mkdir -p backend/chawpi-automation/src/main/kotlin/chawpi/automation/autoconfigure backend/chawpi-automation/src/main/resources/META-INF/spring \
  backend/chawpi-automation/src/main/resources/db/chawpi/automation backend/chawpi-automation/src/test/kotlin/chawpi/automation
```

`backend/chawpi-automation/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.spring-module")
    id("chawpi.publishing")
    id("chawpi.integration-test")
}

description = "Chawpi automation: trigger -> conditions -> actions on record changes"

dependencies {
    api(project(":chawpi-core"))

    testImplementation(project(":chawpi-test"))
}
```

- [ ] **Step 2: Copy the original's classes and tests, run the port rules**

```bash
SRC=/Users/jorge/IdeaProjects/sapgis/backend/src/main/kotlin/com/sapgis
TSRC=/Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis
DST=backend/chawpi-automation/src/main/kotlin/chawpi/automation
TDST=backend/chawpi-automation/src/test/kotlin/chawpi/automation
for f in Automation AutomationController AutomationDispatcher AutomationDrain AutomationFieldUsage AutomationProperties \
         AutomationRepository AutomationRules AutomationRun AutomationRunRepository AutomationRunner AutomationService WebhookSender; do
  cp $SRC/automation/$f.kt $DST/
done
cp $TSRC/automation/AutomationDrainTest.kt $TSRC/automation/AutomationRulesTest.kt $TDST/
port() {
  sed -i '' -E \
    -e 's/^package com\.sapgis\.([a-z]+)$/package chawpi.\1/' \
    -e 's/com\.sapgis\.(views|forms|pages|workflow|automation|documents|gis|agent)\./chawpi.\1./g' \
    -e 's/com\.sapgis\.data\.ObjectSchemaManager/chawpi.core.metadata.ObjectSchemaManager/g' \
    -e 's/com\.sapgis\./chawpi.core./g' \
    -e 's/SapgisException/ChawpiException/g' \
    -e 's/sapgis\.geoserver/chawpi.gis.geoserver/g' \
    -e 's/sapgis\.(agent|automation)/chawpi.\1/g' \
    "$@"
  sed -i '' -E -e '/^[[:space:]]*\/\//!s/([^a-zA-Z_.])sapgis\.([a-z_]+)/\1${schemas.metadata}.\2/g' "$@"
  sed -i '' -e 's/SAPGIS/Chawpi/g' -e 's/Sapgis/Chawpi/g' -e 's/sapgis/chawpi/g' "$@"
}
port $DST/*.kt $TDST/*.kt
# the document port has a new name (M1)
sed -i '' -e 's/private val documents: Documents$/private val documents: DocumentIssuer/' \
          -e 's/private val documents: Documents,$/private val documents: DocumentIssuer,/' $DST/AutomationRunner.kt $DST/AutomationService.kt
grep -n 'schemas.metadata' $DST/*.kt | cut -c1-120
grep -n '@ConfigurationProperties' $DST/AutomationProperties.kt
```
Expected: `${schemas.metadata}` only in `AutomationRepository.kt` and `AutomationRunRepository.kt`; `@ConfigurationProperties(prefix = "chawpi.automation")`.

- [ ] **Step 3: Give both repositories their schema**

In `$DST/AutomationRepository.kt` and in `$DST/AutomationRunRepository.kt` replace

```kotlin
    private val objectMapper: ObjectMapper
) {
```
with
```kotlin
    private val objectMapper: ObjectMapper,
    private val schemas: ChawpiSchemas
) {
```
and add `import chawpi.core.platform.ChawpiSchemas` to each file's imports (sorted).

- [ ] **Step 4: The module switch on the properties (M7)**

In `$DST/AutomationProperties.kt` replace

```kotlin
data class AutomationProperties(
```
with
```kotlin
data class AutomationProperties(
    // false: no automation beans, routes, drain or migration
    val enabled: Boolean = true,
```

- [ ] **Step 5: Write the failing null-object test**

`backend/chawpi-automation/src/test/kotlin/chawpi/automation/NoDocumentIssuerTest.kt`:

```kotlin
package chawpi.automation

import chawpi.core.common.ValidationException
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class NoDocumentIssuerTest {
    @Test
    fun `without a documents module no document type exists, so saving the action is refused`() =
        runTest {
            assertThat(NoDocumentIssuer().typeExists(UUID.randomUUID(), "permiso")).isFalse()
        }

    @Test
    fun `issuing without the module fails the run with a 400, not a crash`() =
        runTest {
            val error = runCatching { NoDocumentIssuer().issue(UUID.randomUUID(), "predio", UUID.randomUUID(), "permiso") }.exceptionOrNull()
            assertThat(error).isInstanceOf(ValidationException::class.java).hasMessage("Unknown document type 'permiso'")
        }
}
```

Run: `./gradlew :chawpi-automation:test --tests 'chawpi.automation.NoDocumentIssuerTest'`
Expected: FAIL, compilation error `Unresolved reference 'DocumentIssuer'` (in `AutomationRunner.kt`/`AutomationService.kt`) and `'NoDocumentIssuer'`.

- [ ] **Step 6: Write the port and its null object**

`backend/chawpi-automation/src/main/kotlin/chawpi/automation/DocumentIssuer.kt`:

```kotlin
package chawpi.automation

import chawpi.core.common.ValidationException
import java.util.UUID

// what an automation needs from a documents module, and nothing more. chawpi-documents implements
// it when both are installed; automation never reaches into its repositories or its service --
// the same shape as core's WorkflowStates, for the same reason.
interface DocumentIssuer {
    suspend fun typeExists(
        objectId: UUID,
        name: String
    ): Boolean

    // issues one and answers with the correlative it got, which is all a run step has to print.
    // nothing is passed for the issuer: no user sits behind a queued run (ADR-016), so the
    // platform issues it.
    suspend fun issue(
        organizationId: UUID,
        objectName: String,
        recordId: UUID,
        typeName: String
    ): String
}

// no documents module: no type exists, so a GENERATE_DOCUMENT action is refused when it is saved,
// exactly like a type nobody created. a stored one fails its run instead of the boot.
class NoDocumentIssuer : DocumentIssuer {
    override suspend fun typeExists(
        objectId: UUID,
        name: String
    ): Boolean = false

    override suspend fun issue(
        organizationId: UUID,
        objectName: String,
        recordId: UUID,
        typeName: String
    ): String = throw ValidationException("Unknown document type '$typeName'", "documentType", "the documents module is not installed")
}
```

Run: `./gradlew :chawpi-automation:test --tests 'chawpi.automation.NoDocumentIssuerTest' --tests 'chawpi.automation.AutomationDrainTest' --tests 'chawpi.automation.AutomationRulesTest'`
Expected: PASS (the two ported suites unchanged). If compilation fails on a core symbol, check it with `grep -rn "fun <name>\|class <name>" backend/chawpi-core/src/main/kotlin` and fix the import only.

- [ ] **Step 7: Write the failing migration test, then the migration (the original's V8)**

`backend/chawpi-automation/src/test/kotlin/chawpi/automation/AutomationMigrationSqlTest.kt`:

```kotlin
package chawpi.automation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AutomationMigrationSqlTest {
    private val sql = javaClass.getResource("/db/chawpi/automation/V1__automation.sql")!!.readText()

    @Test
    fun `automations and their runs, with the drain's partial index`() {
        assertThat(sql)
            .contains("CREATE TABLE \${metadataSchema}.automations (")
            .contains("CONSTRAINT automations_name_unique_per_org UNIQUE (organization_id, name)")
            .contains("CREATE TABLE \${metadataSchema}.automation_runs (")
            .contains("user_id uuid REFERENCES \${metadataSchema}.users (id) ON DELETE SET NULL")
            .contains("CREATE INDEX automation_runs_pending_idx ON \${metadataSchema}.automation_runs (created_at) WHERE status = 'PENDING';")
            .doesNotContainIgnoringCase("sapgis")
    }
}
```

Run: `./gradlew :chawpi-automation:test --tests 'chawpi.automation.AutomationMigrationSqlTest'` → FAIL (`NullPointerException`).

`backend/chawpi-automation/src/main/resources/db/chawpi/automation/V1__automation.sql`:

```sql
-- chawpi-automation: trigger -> conditions -> actions, defined as metadata like everything else.
-- the original's V8 rebaselined (ADR-0026). a run row is the audit trail of one automation meeting one
-- record change. matching happens when the change lands (against the snapshot); the actions run
-- later, off the request.

CREATE TABLE ${metadataSchema}.automations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    object_id uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    name text NOT NULL,
    label text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    definition jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT automations_name_unique_per_org UNIQUE (organization_id, name)
);

CREATE INDEX automations_object_idx ON ${metadataSchema}.automations (organization_id, object_id);

CREATE TABLE ${metadataSchema}.automation_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    automation_id uuid NOT NULL REFERENCES ${metadataSchema}.automations (id) ON DELETE CASCADE,
    object_name text NOT NULL,
    record_id uuid,
    trigger_type text NOT NULL,
    -- PENDING -> RUNNING -> SUCCEEDED | FAILED, or SKIPPED before it ever runs
    status text NOT NULL,
    -- how many automations deep this chain already is. the loop guard reads it.
    depth integer NOT NULL DEFAULT 0,
    -- the record as it was when the change landed. conditions and templates read this, not the
    -- live row: re-reading later would judge a change that may have moved on since.
    payload jsonb NOT NULL,
    steps jsonb,
    error text,
    attempts integer NOT NULL DEFAULT 0,
    -- who caused the change. the actions themselves run as the platform, not as this user.
    user_id uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz
);

-- the drain query. partial index: only pending rows are ever claimed.
CREATE INDEX automation_runs_pending_idx ON ${metadataSchema}.automation_runs (created_at) WHERE status = 'PENDING';
CREATE INDEX automation_runs_log_idx ON ${metadataSchema}.automation_runs (organization_id, created_at DESC);
CREATE INDEX automation_runs_automation_idx ON ${metadataSchema}.automation_runs (automation_id, created_at DESC);
```

Run the test again → PASS.

- [ ] **Step 8: Write the failing auto-config test**

`backend/chawpi-automation/src/test/kotlin/chawpi/automation/ChawpiAutomationAutoConfigurationTest.kt`:

```kotlin
package chawpi.automation

import chawpi.automation.autoconfigure.ChawpiAutomationAutoConfiguration
import chawpi.core.data.RecordChangeListener
import chawpi.core.metadata.FieldUsage
import chawpi.core.platform.ModuleMigration
import chawpi.test.ChawpiContextRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.test.util.ReflectionTestUtils
import java.util.UUID

class ChawpiAutomationAutoConfigurationTest {
    // poll-interval 0: no background drain against the mocked database
    private val runner =
        ChawpiContextRunner
            .core()
            .withConfiguration(AutoConfigurations.of(ChawpiAutomationAutoConfiguration::class.java))
            .withPropertyValues("chawpi.automation.poll-interval=0s")

    private val neighbour =
        object : DocumentIssuer {
            override suspend fun typeExists(
                objectId: UUID,
                name: String
            ) = true

            override suspend fun issue(
                organizationId: UUID,
                objectName: String,
                recordId: UUID,
                typeName: String
            ) = "SGTM-2026-001"
        }

    @Test
    fun `automation joins core's listeners and field usages`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(AutomationService::class.java)
            assertThat(context).hasSingleBean(AutomationController::class.java)
            assertThat(context.getBeansOfType(RecordChangeListener::class.java).values).hasAtLeastOneElementOfType(AutomationDispatcher::class.java)
            assertThat(context.getBeansOfType(FieldUsage::class.java).values).hasAtLeastOneElementOfType(AutomationFieldUsage::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactlyInAnyOrder("core", "automation")
        }
    }

    @Test
    fun `without a documents module it falls back to the null issuer`() {
        runner.run { context ->
            assertThat(ReflectionTestUtils.getField(context.getBean(AutomationService::class.java), "documents")).isInstanceOf(NoDocumentIssuer::class.java)
            assertThat(ReflectionTestUtils.getField(context.getBean(AutomationRunner::class.java), "documents")).isInstanceOf(NoDocumentIssuer::class.java)
        }
    }

    @Test
    fun `with a document issuer next to it, that one is used`() {
        runner.withBean(DocumentIssuer::class.java, { neighbour }).run { context ->
            assertThat(ReflectionTestUtils.getField(context.getBean(AutomationService::class.java), "documents")).isSameAs(neighbour)
            assertThat(ReflectionTestUtils.getField(context.getBean(AutomationRunner::class.java), "documents")).isSameAs(neighbour)
        }
    }

    @Test
    fun `settings bind under chawpi automation`() {
        runner.withPropertyValues("chawpi.automation.allow-private-webhooks=true", "chawpi.automation.max-depth=5").run { context ->
            val properties = context.getBean(AutomationProperties::class.java)
            assertThat(properties.polling).isFalse()
            assertThat(properties.allowPrivateWebhooks).isTrue()
            assertThat(properties.depthCap).isEqualTo(5)
        }
    }

    @Test
    fun `switched off, nothing listens and nothing drains`() {
        runner.withPropertyValues("chawpi.automation.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(AutomationService::class.java)
            assertThat(context).doesNotHaveBean(AutomationDrain::class.java)
            assertThat(context.getBeansOfType(RecordChangeListener::class.java)).isEmpty()
        }
    }

    @Test
    fun `the imports file registers the auto-config`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("chawpi.automation.autoconfigure.ChawpiAutomationAutoConfiguration")
    }
}
```

Run: `./gradlew :chawpi-automation:test --tests 'chawpi.automation.ChawpiAutomationAutoConfigurationTest'`
Expected: FAIL, `Unresolved reference 'ChawpiAutomationAutoConfiguration'`.

- [ ] **Step 9: Auto-config and imports file**

`backend/chawpi-automation/src/main/kotlin/chawpi/automation/autoconfigure/ChawpiAutomationAutoConfiguration.kt`:

```kotlin
package chawpi.automation.autoconfigure

import chawpi.automation.AutomationController
import chawpi.automation.AutomationDispatcher
import chawpi.automation.AutomationDrain
import chawpi.automation.AutomationFieldUsage
import chawpi.automation.AutomationProperties
import chawpi.automation.AutomationRepository
import chawpi.automation.AutomationRunRepository
import chawpi.automation.AutomationRunner
import chawpi.automation.AutomationService
import chawpi.automation.DocumentIssuer
import chawpi.automation.NoDocumentIssuer
import chawpi.automation.WebhookSender
import chawpi.core.audit.AuditService
import chawpi.core.autoconfigure.ChawpiDataAutoConfiguration
import chawpi.core.data.RecordStore
import chawpi.core.data.WorkflowStates
import chawpi.core.identity.CurrentUser
import chawpi.core.metadata.MetadataService
import chawpi.core.platform.ChawpiSchemas
import chawpi.core.platform.ModuleMigration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper

// automations. the document port is looked up, not required: with no documents module the null
// issuer answers, and no auto-config order has to be right for that (M2).
@AutoConfiguration(after = [ChawpiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "chawpi.automation", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AutomationProperties::class)
class ChawpiAutomationAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would always make it back off
    @Bean
    fun chawpiAutomationMigration(): ModuleMigration = ModuleMigration("automation", "classpath:db/chawpi/automation", ModuleMigration.MODULE_ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun automationRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: ChawpiSchemas
    ): AutomationRepository = AutomationRepository(db, objectMapper, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun automationRunRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: ChawpiSchemas
    ): AutomationRunRepository = AutomationRunRepository(db, objectMapper, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun automationDispatcher(
        automations: AutomationRepository,
        runs: AutomationRunRepository,
        properties: AutomationProperties
    ): AutomationDispatcher = AutomationDispatcher(automations, runs, properties)

    @Bean
    @ConditionalOnMissingBean
    fun automationFieldUsage(automations: AutomationRepository): AutomationFieldUsage = AutomationFieldUsage(automations)

    @Bean
    @ConditionalOnMissingBean
    fun webhookSender(properties: AutomationProperties): WebhookSender = WebhookSender(properties)

    @Bean
    @ConditionalOnMissingBean
    fun automationRunner(
        automations: AutomationRepository,
        runs: AutomationRunRepository,
        metadata: MetadataService,
        store: RecordStore,
        workflows: WorkflowStates,
        audit: AuditService,
        dispatcher: AutomationDispatcher,
        webhooks: WebhookSender,
        documents: ObjectProvider<DocumentIssuer>
    ): AutomationRunner =
        AutomationRunner(automations, runs, metadata, store, workflows, audit, dispatcher, webhooks, documents.getIfAvailable { NoDocumentIssuer() })

    @Bean
    @ConditionalOnMissingBean
    fun automationService(
        automations: AutomationRepository,
        runs: AutomationRunRepository,
        metadata: MetadataService,
        webhooks: WebhookSender,
        documents: ObjectProvider<DocumentIssuer>,
        currentUser: CurrentUser
    ): AutomationService = AutomationService(automations, runs, metadata, webhooks, documents.getIfAvailable { NoDocumentIssuer() }, currentUser)

    // polls from SmartLifecycle.start(), after every singleton (migrations included): no @DependsOn (M9)
    @Bean
    @ConditionalOnMissingBean
    fun automationDrain(
        runner: AutomationRunner,
        properties: AutomationProperties
    ): AutomationDrain = AutomationDrain(runner, properties)

    @Bean
    @ConditionalOnMissingBean
    fun automationController(automations: AutomationService): AutomationController = AutomationController(automations)
}
```

`backend/chawpi-automation/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
chawpi.automation.autoconfigure.ChawpiAutomationAutoConfiguration
```

Run: `./gradlew :chawpi-automation:test`
Expected: PASS (every suite: the two ported ones, `NoDocumentIssuerTest`, `AutomationMigrationSqlTest`, `ChawpiAutomationAutoConfigurationTest`). If `AutomationController`'s constructor parameter is not `automations`, match the original's name.

- [ ] **Step 10: Format, nothing named after the original, leave uncommitted**

```bash
./gradlew :chawpi-automation:ktlintFormat
./gradlew :chawpi-automation:ktlintCheck
./gradlew :chawpi-automation:jar
grep -rn -i "sapgis" backend/chawpi-automation || echo "clean"
git status --short backend/chawpi-automation
```
Expected: ktlint passes, `clean`, `?? backend/chawpi-automation/`.

### Task 6: `chawpi-documents` (Wave 1)

The automation adapter (the original's `DocumentsAdapter.kt`) is Task 10.

**Files:**
- Create: `backend/chawpi-documents/build.gradle.kts`
- Create (ported from `$SRC/documents/`, every file except `DocumentsAdapter.kt`): `backend/chawpi-documents/src/main/kotlin/chawpi/documents/{Document,DocumentController,DocumentCounterRepository,DocumentRepository,DocumentService,DocumentType,DocumentTypeController,DocumentTypeRepository,DocumentTypeService}.kt`
- Create: `backend/chawpi-documents/src/main/kotlin/chawpi/documents/autoconfigure/{ChawpiDocumentsProperties,ChawpiDocumentsAutoConfiguration}.kt`
- Create: `backend/chawpi-documents/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `backend/chawpi-documents/src/main/resources/db/chawpi/documents/V1__documents.sql`
- Test: `backend/chawpi-documents/src/test/kotlin/chawpi/documents/{DocumentTemplateValuesTest,DocumentsMigrationSqlTest,ChawpiDocumentsAutoConfigurationTest}.kt`

**Interfaces:**
- Consumes (core): `MetadataService`, `RelationshipService` (`forObject`), `RelatedRecordService` (`chawpi.core.data`, `suspend fun relatedRows(organizationId, objectName, recordId, relationshipName, query, narrow = { it }): Pair<ObjectDefinition, PageResponse<RecordRow>>`), `RecordStore`, `RecordRow` (`attributes`, `sections`), `AuditService.record(..., operation = AuditOperation.ISSUE, documentId = …)`, `CurrentUser`, `ChawpiSchemas`, `ModuleMigration`; `ChawpiContextRunner.core()` (Task 1).
- Produces:
  - `chawpi.documents.DocumentService(documents, counters, types, metadata, related: RelatedRecordService, store, currentUser, audit)` with the original's `suspend fun issue(organizationId: UUID, objectName: String, recordId: UUID, typeName: String, issuedBy: UUID?, issuedByEmail: String?): Document` (Task 10 calls it with `null, null`).
  - `chawpi.documents.DocumentTypeRepository` with `suspend fun findByName(objectId: UUID, name: String): DocumentType?` (Task 10).
  - `internal fun RecordRow.templateValues(): Map<String, Any?>` in `DocumentService.kt`.
  - `chawpi.documents.autoconfigure.ChawpiDocumentsAutoConfiguration`, migration `ModuleMigration("documents", "classpath:db/chawpi/documents", 100)`, property `chawpi.documents.enabled`.

- [ ] **Step 1: Module build file and folders**

```bash
cd /Users/jorge/IdeaProjects/chawpi
mkdir -p backend/chawpi-documents/src/main/kotlin/chawpi/documents/autoconfigure backend/chawpi-documents/src/main/resources/META-INF/spring \
  backend/chawpi-documents/src/main/resources/db/chawpi/documents backend/chawpi-documents/src/test/kotlin/chawpi/documents
```

`backend/chawpi-documents/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.spring-module")
    id("chawpi.publishing")
    id("chawpi.integration-test")
}

description = "Chawpi documents: document types, templates and issued, numbered documents"

dependencies {
    api(project(":chawpi-core"))

    testImplementation(project(":chawpi-test"))
}
```

- [ ] **Step 2: Copy the original's classes and run the port rules**

```bash
SRC=/Users/jorge/IdeaProjects/sapgis/backend/src/main/kotlin/com/sapgis
DST=backend/chawpi-documents/src/main/kotlin/chawpi/documents
for f in Document DocumentController DocumentCounterRepository DocumentRepository DocumentService DocumentType \
         DocumentTypeController DocumentTypeRepository DocumentTypeService; do
  cp $SRC/documents/$f.kt $DST/
done
port() {
  sed -i '' -E \
    -e 's/^package com\.sapgis\.([a-z]+)$/package chawpi.\1/' \
    -e 's/com\.sapgis\.(views|forms|pages|workflow|automation|documents|gis|agent)\./chawpi.\1./g' \
    -e 's/com\.sapgis\.data\.ObjectSchemaManager/chawpi.core.metadata.ObjectSchemaManager/g' \
    -e 's/com\.sapgis\./chawpi.core./g' \
    -e 's/SapgisException/ChawpiException/g' \
    -e 's/sapgis\.geoserver/chawpi.gis.geoserver/g' \
    -e 's/sapgis\.(agent|automation)/chawpi.\1/g' \
    "$@"
  sed -i '' -E -e '/^[[:space:]]*\/\//!s/([^a-zA-Z_.])sapgis\.([a-z_]+)/\1${schemas.metadata}.\2/g' "$@"
  sed -i '' -e 's/SAPGIS/Chawpi/g' -e 's/Sapgis/Chawpi/g' -e 's/sapgis/chawpi/g' "$@"
}
port $DST/*.kt
grep -ln 'schemas.metadata' $DST/*.kt
```
Expected: `DocumentCounterRepository.kt`, `DocumentRepository.kt`, `DocumentTypeRepository.kt`.

- [ ] **Step 3: Give the three repositories their schema**

In `$DST/DocumentRepository.kt` and `$DST/DocumentTypeRepository.kt` replace

```kotlin
    private val objectMapper: ObjectMapper
) {
```
with
```kotlin
    private val objectMapper: ObjectMapper,
    private val schemas: ChawpiSchemas
) {
```
In `$DST/DocumentCounterRepository.kt` replace

```kotlin
    private val db: DatabaseClient
) {
```
with
```kotlin
    private val db: DatabaseClient,
    private val schemas: ChawpiSchemas
) {
```
Add `import chawpi.core.platform.ChawpiSchemas` to each of the three files (sorted).

- [ ] **Step 4: Related rows come from core's `RelatedRecordService` now (P1 R1)**

In `$DST/DocumentService.kt`:

1. Replace the import `import chawpi.core.metadata.RelationshipService` with `import chawpi.core.data.RelatedRecordService`.
2. Replace the constructor line `    private val relationships: RelationshipService,` with `    private val related: RelatedRecordService,`.
3. Replace `relationships.relatedRows(` with `related.relatedRows(` (one place, in `relatedOf`).

`DocumentTypeService.kt` keeps `RelationshipService` (it only calls `forObject`).

- [ ] **Step 5: Write the failing template-values test**

A snapshot freezes every value a template may name. In the original that was `record.attributes + record.geometries`; a row now carries one map per installed section (gis: `geometries`), none without gis.

`backend/chawpi-documents/src/test/kotlin/chawpi/documents/DocumentTemplateValuesTest.kt`:

```kotlin
package chawpi.documents

import chawpi.core.data.RecordRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class DocumentTemplateValuesTest {
    private fun row(sections: Map<String, Map<String, Any?>>) =
        RecordRow(id = UUID.randomUUID(), createdAt = null, updatedAt = null, attributes = mapOf("codigo" to "A-1"), sections = sections)

    @Test
    fun `a flat record freezes its attributes`() {
        assertThat(row(emptyMap()).templateValues()).containsExactly(entry("codigo", "A-1"))
    }

    @Test
    fun `section fields sit next to the attributes, by field name, null included`() {
        val point = mapOf("type" to "Point", "coordinates" to listOf(1.0, 2.0))
        val values = row(mapOf("geometries" to mapOf("lote" to point, "acceso" to null))).templateValues()
        assertThat(values).containsEntry("codigo", "A-1").containsEntry("lote", point).containsEntry("acceso", null).hasSize(3)
    }

    private fun entry(
        key: String,
        value: Any?
    ) = org.assertj.core.api.Assertions.entry(key, value)
}
```

Run: `./gradlew :chawpi-documents:test --tests 'chawpi.documents.DocumentTemplateValuesTest'`
Expected: FAIL, compilation error `Unresolved reference 'templateValues'` (and `record.geometries` in `DocumentService.kt`).

- [ ] **Step 6: Flatten the sections**

In `$DST/DocumentService.kt` replace

```kotlin
                values = record.attributes + record.geometries,
```
with
```kotlin
                values = record.templateValues(),
```
and append at the end of the file:

```kotlin

// what a template can name: the record's fields, then every section's fields (gis: its geometries)
// by field name, the way the original froze attributes + geometries
internal fun RecordRow.templateValues(): Map<String, Any?> = sections.values.fold(attributes) { all, section -> all + section }
```
Add `import chawpi.core.data.RecordRow` if the file does not import it yet.

Run: `./gradlew :chawpi-documents:test --tests 'chawpi.documents.DocumentTemplateValuesTest'`
Expected: PASS. If another compile error names a core symbol, check it with `grep -rn "fun <name>\|class <name>" backend/chawpi-core/src/main/kotlin` and fix the import only.

- [ ] **Step 7: Write the failing migration test, then the migration (the original's V12 + V13 + V14)**

`backend/chawpi-documents/src/test/kotlin/chawpi/documents/DocumentsMigrationSqlTest.kt`:

```kotlin
package chawpi.documents

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DocumentsMigrationSqlTest {
    private val sql = javaClass.getResource("/db/chawpi/documents/V1__documents.sql")!!.readText()

    @Test
    fun `types, counters and issued documents`() {
        assertThat(sql)
            .contains("CREATE TABLE \${metadataSchema}.document_types (")
            .contains("CONSTRAINT document_types_prefix_unique_per_org UNIQUE (organization_id, prefix)")
            .contains("CREATE TABLE \${metadataSchema}.document_counters (")
            .contains("CREATE TABLE \${metadataSchema}.documents (")
            .contains("document_type_id uuid NOT NULL REFERENCES \${metadataSchema}.document_types (id) ON DELETE RESTRICT")
            .contains("CREATE UNIQUE INDEX documents_one_valid_per_record")
            .doesNotContainIgnoringCase("sapgis")
    }

    // core created audit_log.document_id without its FK and without ISSUE (P1 R10): this module owns both
    @Test
    fun `the audit log learns ISSUE and points at the document`() {
        assertThat(sql)
            .contains("ALTER TABLE \${metadataSchema}.audit_log DROP CONSTRAINT audit_log_operation_valid;")
            .contains("CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE', 'ISSUE'))")
            .contains(
                "ADD CONSTRAINT audit_log_document_id_fkey FOREIGN KEY (document_id) REFERENCES \${metadataSchema}.documents (id) ON DELETE SET NULL"
            )
        // loud failure if core's constraint or column is not what we expect
        assertThat(sql).doesNotContain("IF EXISTS").doesNotContain("ADD COLUMN")
    }
}
```

Run: `./gradlew :chawpi-documents:test --tests 'chawpi.documents.DocumentsMigrationSqlTest'` → FAIL (`NullPointerException`).

`backend/chawpi-documents/src/main/resources/db/chawpi/documents/V1__documents.sql`:

```sql
-- chawpi-documents: the original's V12 + V13 + V14 rebaselined (ADR-0026). final shape only, clean start.
--
-- a document type is a template an admin writes once and a record issues many times. it keys to an
-- object the way a form does -- several per object, named -- plus a prefix, the SGTM in SGTM-2026-001.
-- the prefix is unique per organization: the correlative counts per type, so two types sharing a
-- prefix would each issue their own SGTM-2026-001.

CREATE TABLE ${metadataSchema}.document_types (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    object_id uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    name text NOT NULL,
    label text NOT NULL,
    prefix text NOT NULL,
    template jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_types_name_unique_per_object UNIQUE (object_id, name),
    CONSTRAINT document_types_prefix_unique_per_org UNIQUE (organization_id, prefix)
);

CREATE INDEX document_types_object_idx ON ${metadataSchema}.document_types (organization_id, object_id);

-- the counter is a table, not a sequence: a sequence is not transactional, so a failed issue would
-- burn a number. this one moves with the transaction -- roll back and the number comes back.
CREATE TABLE ${metadataSchema}.document_counters (
    document_type_id uuid NOT NULL REFERENCES ${metadataSchema}.document_types (id) ON DELETE CASCADE,
    year integer NOT NULL,
    next integer NOT NULL DEFAULT 1,
    PRIMARY KEY (document_type_id, year)
);

-- issuing freezes a document: the row keeps the values AND the template as they were.
CREATE TABLE ${metadataSchema}.documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    -- RESTRICT: an issued document is a fact. a type that issued anything cannot be deleted under it.
    document_type_id uuid NOT NULL REFERENCES ${metadataSchema}.document_types (id) ON DELETE RESTRICT,
    object_id uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    record_id uuid NOT NULL,
    number text NOT NULL,
    year integer NOT NULL,
    sequence integer NOT NULL,
    status text NOT NULL DEFAULT 'VALID',
    snapshot jsonb NOT NULL,
    issued_at timestamptz NOT NULL DEFAULT now(),
    issued_by uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    CONSTRAINT documents_number_unique_per_org UNIQUE (organization_id, number),
    CONSTRAINT documents_status_valid CHECK (status IN ('VALID', 'ARCHIVED'))
);

-- "the last one issued prevails" is this index, not a rule in a service
CREATE UNIQUE INDEX documents_one_valid_per_record
    ON ${metadataSchema}.documents (document_type_id, record_id) WHERE status = 'VALID';

CREATE INDEX documents_record_idx ON ${metadataSchema}.documents (organization_id, object_id, record_id, issued_at DESC);

-- issuing is history too. core's audit_log already has document_id (no FK) and a CHECK without
-- ISSUE (P1 R10); this module owns both halves. same constraint name, so the final schema is the
-- original one. ON DELETE SET NULL: an audit entry must not vanish because its document did.
ALTER TABLE ${metadataSchema}.audit_log DROP CONSTRAINT audit_log_operation_valid;
ALTER TABLE ${metadataSchema}.audit_log ADD CONSTRAINT audit_log_operation_valid
    CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE', 'ISSUE'));

ALTER TABLE ${metadataSchema}.audit_log
    ADD CONSTRAINT audit_log_document_id_fkey FOREIGN KEY (document_id) REFERENCES ${metadataSchema}.documents (id) ON DELETE SET NULL;
```

Run the test again → PASS.

- [ ] **Step 8: Write the failing auto-config test**

`backend/chawpi-documents/src/test/kotlin/chawpi/documents/ChawpiDocumentsAutoConfigurationTest.kt`:

```kotlin
package chawpi.documents

import chawpi.core.platform.ModuleMigration
import chawpi.documents.autoconfigure.ChawpiDocumentsAutoConfiguration
import chawpi.test.ChawpiContextRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates

class ChawpiDocumentsAutoConfigurationTest {
    private val runner = ChawpiContextRunner.core().withConfiguration(AutoConfigurations.of(ChawpiDocumentsAutoConfiguration::class.java))

    @Test
    fun `documents wires on core alone, no automation needed`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(DocumentService::class.java)
            assertThat(context).hasSingleBean(DocumentTypeService::class.java)
            assertThat(context).hasSingleBean(DocumentController::class.java)
            assertThat(context).hasSingleBean(DocumentTypeController::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactlyInAnyOrder("core", "documents")
        }
    }

    @Test
    fun `switched off, core boots without any documents bean`() {
        runner.withPropertyValues("chawpi.documents.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(DocumentService::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactly("core")
        }
    }

    @Test
    fun `an app's own service wins`() {
        val mine = mock(DocumentService::class.java)
        runner.withBean(DocumentService::class.java, { mine }).run { context ->
            assertThat(context.getBean(DocumentService::class.java)).isSameAs(mine)
        }
    }

    @Test
    fun `the imports file registers the auto-config`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("chawpi.documents.autoconfigure.ChawpiDocumentsAutoConfiguration")
    }
}
```

Run: `./gradlew :chawpi-documents:test --tests 'chawpi.documents.ChawpiDocumentsAutoConfigurationTest'`
Expected: FAIL, `Unresolved reference 'ChawpiDocumentsAutoConfiguration'`.

- [ ] **Step 9: Properties, auto-config and imports file**

`backend/chawpi-documents/src/main/kotlin/chawpi/documents/autoconfigure/ChawpiDocumentsProperties.kt`:

```kotlin
package chawpi.documents.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("chawpi.documents")
data class ChawpiDocumentsProperties(
    // false: no documents beans, routes or migration
    val enabled: Boolean = true
)
```

`backend/chawpi-documents/src/main/kotlin/chawpi/documents/autoconfigure/ChawpiDocumentsAutoConfiguration.kt`:

```kotlin
package chawpi.documents.autoconfigure

import chawpi.core.audit.AuditService
import chawpi.core.autoconfigure.ChawpiDataAutoConfiguration
import chawpi.core.data.RecordStore
import chawpi.core.data.RelatedRecordService
import chawpi.core.identity.CurrentUser
import chawpi.core.metadata.MetadataService
import chawpi.core.metadata.RelationshipService
import chawpi.core.platform.ChawpiSchemas
import chawpi.core.platform.ModuleMigration
import chawpi.documents.DocumentController
import chawpi.documents.DocumentCounterRepository
import chawpi.documents.DocumentRepository
import chawpi.documents.DocumentService
import chawpi.documents.DocumentTypeController
import chawpi.documents.DocumentTypeRepository
import chawpi.documents.DocumentTypeService
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper

// document types and issued documents. the automation port adapter is a separate auto-config
// (ChawpiDocumentsAutomationAutoConfiguration) that only exists when chawpi-automation does.
@AutoConfiguration(after = [ChawpiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "chawpi.documents", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ChawpiDocumentsProperties::class)
class ChawpiDocumentsAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would always make it back off
    @Bean
    fun chawpiDocumentsMigration(): ModuleMigration = ModuleMigration("documents", "classpath:db/chawpi/documents", ModuleMigration.MODULE_ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun documentRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: ChawpiSchemas
    ): DocumentRepository = DocumentRepository(db, objectMapper, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun documentCounterRepository(
        db: DatabaseClient,
        schemas: ChawpiSchemas
    ): DocumentCounterRepository = DocumentCounterRepository(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun documentTypeRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: ChawpiSchemas
    ): DocumentTypeRepository = DocumentTypeRepository(db, objectMapper, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun documentService(
        documents: DocumentRepository,
        counters: DocumentCounterRepository,
        types: DocumentTypeRepository,
        metadata: MetadataService,
        related: RelatedRecordService,
        store: RecordStore,
        currentUser: CurrentUser,
        audit: AuditService
    ): DocumentService = DocumentService(documents, counters, types, metadata, related, store, currentUser, audit)

    @Bean
    @ConditionalOnMissingBean
    fun documentTypeService(
        types: DocumentTypeRepository,
        documents: DocumentRepository,
        metadata: MetadataService,
        relationships: RelationshipService,
        currentUser: CurrentUser
    ): DocumentTypeService = DocumentTypeService(types, documents, metadata, relationships, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun documentController(documents: DocumentService): DocumentController = DocumentController(documents)

    @Bean
    @ConditionalOnMissingBean
    fun documentTypeController(types: DocumentTypeService): DocumentTypeController = DocumentTypeController(types)
}
```

`backend/chawpi-documents/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
chawpi.documents.autoconfigure.ChawpiDocumentsAutoConfiguration
```

Run: `./gradlew :chawpi-documents:test`
Expected: PASS (8 tests).

- [ ] **Step 10: Format, nothing named after the original, leave uncommitted**

```bash
./gradlew :chawpi-documents:ktlintFormat
./gradlew :chawpi-documents:ktlintCheck
./gradlew :chawpi-documents:jar
grep -rn -i "sapgis" backend/chawpi-documents || echo "clean"
git status --short backend/chawpi-documents
```
Expected: ktlint passes, `clean`, `?? backend/chawpi-documents/`.

### Task 7: `chawpi-gis` (Wave 1)

GEOMETRY comes back as a field type through core's SPIs (P1 R2–R7, M5). The MAP page component is Task 12.

**Files:**
- Create: `backend/chawpi-gis/build.gradle.kts`
- Create (ported from `$SRC/gis/`): `backend/chawpi-gis/src/main/kotlin/chawpi/gis/{GeoJson,GeoServerClient,GeoServerPayloads,GeoServerProperties,LayerCleanup,LayerController,LayerService}.kt`
- Create (rewritten): `backend/chawpi-gis/src/main/kotlin/chawpi/gis/FeatureController.kt`
- Create (new): `backend/chawpi-gis/src/main/kotlin/chawpi/gis/{GeometryType,GeometryFields,GeometryFieldType,BboxQuery}.kt`
- Create: `backend/chawpi-gis/src/main/kotlin/chawpi/gis/autoconfigure/{ChawpiGisProperties,ChawpiGisAutoConfiguration}.kt`
- Create: `backend/chawpi-gis/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `backend/chawpi-gis/src/main/resources/db/chawpi/gis/V1__gis.sql`
- Test (ported from `$TSRC/gis/`): `backend/chawpi-gis/src/test/kotlin/chawpi/gis/{GeoServerPayloadsTest,GeoServerUrlsTest}.kt`
- Test (new): `backend/chawpi-gis/src/test/kotlin/chawpi/gis/{GisFixtures,GeometryFieldTypeTest,BboxQueryTest,GisMigrationSqlTest,ChawpiGisAutoConfigurationTest}.kt`

**Interfaces:**
- Consumes (core): `FieldTypeHandler` (all hooks, see `backend/chawpi-core/src/main/kotlin/chawpi/core/metadata/FieldTypeHandler.kt`), `FieldType`, `FieldTypeRegistry` (`isInstalled`, `handler`, `types`, `sections`, `parse`), `FieldRequest.extensions`, `UpdateFieldRequest.unique`, `CustomField.attributes`, `ObjectDefinition`, `CustomObject`, `RecordQueryContributor`/`RecordCriterion`/`RecordQueryParser`, `RecordService.rows(objectName, query): Pair<ObjectDefinition, List<RecordRow>>`, `RecordService.get(objectName, id): RecordResponse`, `RecordRow.sections`, `RecordResponse.sections`, `ObjectRemovalListener`, `SqlIdentifier.quote/indexName`, `PageRequest(page, size)`, `ValidationException(message, field, reason)` (`violations[0].field`), `NotFoundException`, `MetadataService`, `CurrentUser`, `ModuleMigration`; `ChawpiContextRunner.core()` (Task 1).
- Produces (Task 12 and the agent rely on these names):
  - `val GEOMETRY: FieldType` (= `FieldType("GEOMETRY")`), `const val GEOMETRIES = "geometries"`, `enum class GeometryType(postgisType)`, `const val DEFAULT_SRID = 4326`, `data class GeometryResponse(type: String, srid: Int, dimension: Int)`.
  - Extension properties `CustomField.geometryType: GeometryType?`, `CustomField.srid: Int?`, `CustomField.dimension: Int?`, `ObjectDefinition.geometryFields: List<CustomField>`, and `fun CustomField.toGeometryResponse(): GeometryResponse?`.
  - `class GeometryFieldType(objectMapper: ObjectMapper) : FieldTypeHandler`, `class BboxQuery : RecordQueryContributor` (`parameters = {"bbox", "geometry"}`), `data class BoundingBox`.
  - `GeoServerProperties` at `chawpi.gis.geoserver`, `ChawpiGisProperties` at `chawpi.gis`, `chawpi.gis.autoconfigure.ChawpiGisAutoConfiguration`, migration `ModuleMigration("gis", "classpath:db/chawpi/gis", 100)`.
  - Routes (unchanged): `/api/gis/objects/{object}/features[/{id}]`, `/api/gis/layers…`, `/api/gis/services`.

- [ ] **Step 1: Module build file and folders**

```bash
cd /Users/jorge/IdeaProjects/chawpi
mkdir -p backend/chawpi-gis/src/main/kotlin/chawpi/gis/autoconfigure backend/chawpi-gis/src/main/resources/META-INF/spring \
  backend/chawpi-gis/src/main/resources/db/chawpi/gis backend/chawpi-gis/src/test/kotlin/chawpi/gis
```

`backend/chawpi-gis/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.spring-module")
    id("chawpi.publishing")
    id("chawpi.integration-test")
}

description = "Chawpi GIS: the GEOMETRY field type on PostGIS, bbox queries, features and GeoServer layers"

dependencies {
    api(project(":chawpi-core"))

    testImplementation(project(":chawpi-test"))
}
```

- [ ] **Step 2: Copy the original's classes and tests, run the port rules**

`FeatureController.kt` is NOT copied (Step 9 writes it).

```bash
SRC=/Users/jorge/IdeaProjects/sapgis/backend/src/main/kotlin/com/sapgis
TSRC=/Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis
DST=backend/chawpi-gis/src/main/kotlin/chawpi/gis
TDST=backend/chawpi-gis/src/test/kotlin/chawpi/gis
for f in GeoJson GeoServerClient GeoServerPayloads GeoServerProperties LayerCleanup LayerController LayerService; do cp $SRC/gis/$f.kt $DST/; done
cp $TSRC/gis/GeoServerPayloadsTest.kt $TSRC/gis/GeoServerUrlsTest.kt $TDST/
port() {
  sed -i '' -E \
    -e 's/^package com\.sapgis\.([a-z]+)$/package chawpi.\1/' \
    -e 's/com\.sapgis\.(views|forms|pages|workflow|automation|documents|gis|agent)\./chawpi.\1./g' \
    -e 's/com\.sapgis\.data\.ObjectSchemaManager/chawpi.core.metadata.ObjectSchemaManager/g' \
    -e 's/com\.sapgis\./chawpi.core./g' \
    -e 's/SapgisException/ChawpiException/g' \
    -e 's/sapgis\.geoserver/chawpi.gis.geoserver/g' \
    -e 's/sapgis\.(agent|automation)/chawpi.\1/g' \
    "$@"
  sed -i '' -E -e '/^[[:space:]]*\/\//!s/([^a-zA-Z_.])sapgis\.([a-z_]+)/\1${schemas.metadata}.\2/g' "$@"
  sed -i '' -e 's/SAPGIS/Chawpi/g' -e 's/Sapgis/Chawpi/g' -e 's/sapgis/chawpi/g' "$@"
}
port $DST/*.kt $TDST/*.kt
# geometry helpers now live in this package (Step 4), GEOMETRY is ours
sed -i '' -e '/^import chawpi\.core\.metadata\.geometryFields$/d' -e '/^import chawpi\.core\.metadata\.FieldType$/d' \
          -e 's/FieldType\.GEOMETRY/GEOMETRY/g' $DST/LayerService.kt
grep -n '@ConfigurationProperties' $DST/GeoServerProperties.kt
grep -n 'schemas.metadata' $DST/*.kt || echo "no metadata sql"
```
Expected: `@ConfigurationProperties(prefix = "chawpi.gis.geoserver")`, `no metadata sql`.

- [ ] **Step 3: GeoServer's virtual table follows the configured data schema (M13)**

In `$DST/GeoServerPayloads.kt`:

1. In `fun featureType(`, add a first parameter: replace
```kotlin
    fun featureType(
        table: String,
```
with
```kotlin
    fun featureType(
        schema: String,
        table: String,
```
2. Replace `"sql" to virtualTableSql(table, geometryColumn, attributeColumns),` with `"sql" to virtualTableSql(schema, table, geometryColumn, attributeColumns),`.
3. Replace
```kotlin
    fun virtualTableSql(
        table: String,
```
with
```kotlin
    fun virtualTableSql(
        schema: String,
        table: String,
```
4. Replace `return "SELECT $columns FROM \"app_data\".\"$table\""` with `return "SELECT $columns FROM \"$schema\".\"$table\""`.

In `$DST/GeoServerClient.kt` replace `GeoServerPayloads.featureType(table, layerName,` with `GeoServerPayloads.featureType(properties.datastore.schema, table, layerName,`.

In `$TDST/GeoServerPayloadsTest.kt`:
1. Replace `GeoServerPayloads.featureType(table, layerName,` with `GeoServerPayloads.featureType("app_data", table, layerName,`.
2. Replace `GeoServerPayloads.virtualTableSql("predio__00000000", "lote", listOf("codigo", "area"))` with `GeoServerPayloads.virtualTableSql("app_data", "predio__00000000", "lote", listOf("codigo", "area"))`.
3. Add this test after `the virtual table selects one geometry and the plain columns`:

```kotlin
    // chawpi.database.data-schema is configurable; the layer must read the tables where they are
    @Test
    fun `the virtual table reads from the configured schema`() {
        assertThat(GeoServerPayloads.virtualTableSql("acme_data", "t", "g", emptyList()))
            .isEqualTo("""SELECT "id", "created_at", "updated_at", "g" FROM "acme_data"."t"""")
    }
```

- [ ] **Step 4: Geometry type and field helpers**

`backend/chawpi-gis/src/main/kotlin/chawpi/gis/GeometryType.kt`:

```kotlin
package chawpi.gis

import chawpi.core.common.ValidationException

// what a GEOMETRY field holds. "none" is an object with no geometry field, not a value in here.
enum class GeometryType(
    val postgisType: String
) {
    POINT("Point"),
    LINESTRING("LineString"),
    POLYGON("Polygon"),
    MULTIPOINT("MultiPoint"),
    MULTILINESTRING("MultiLineString"),
    MULTIPOLYGON("MultiPolygon");

    // 3d columns are PointZ, PolygonZ and so on
    fun columnType(dimension: Int): String = if (dimension == 3) postgisType + "Z" else postgisType

    companion object {
        fun parse(
            raw: String?,
            field: String = "geometryType"
        ): GeometryType =
            entries.firstOrNull { it.name == raw?.uppercase() }
                ?: throw ValidationException(
                    "Unknown geometry type '${raw ?: ""}'",
                    field,
                    "must be one of ${entries.joinToString(", ") { it.name }}"
                )
    }
}

// geojson crosses the api in EPSG:4326 whatever crs the column stores. ADR-007.
const val WGS84 = 4326
const val DEFAULT_SRID = 4326
private const val MAX_SRID = 999_999

// the srid is interpolated into DDL and SQL, so it must be a plain positive int
fun validSrid(srid: Int): Int {
    if (srid <= 0 || srid > MAX_SRID) {
        throw ValidationException("Invalid SRID $srid", "srid", "must be a positive EPSG code")
    }
    return srid
}
```

`backend/chawpi-gis/src/main/kotlin/chawpi/gis/GeometryFields.kt`:

```kotlin
package chawpi.gis

import chawpi.core.metadata.CustomField
import chawpi.core.metadata.FieldType
import chawpi.core.metadata.ObjectDefinition

val GEOMETRY = FieldType("GEOMETRY")

// the record payload section geometries travel in, keyed by field name (P1 R6)
const val GEOMETRIES = "geometries"

// custom_fields columns this module owns (P1 R3); its migration creates them
const val GEOMETRY_TYPE_COLUMN = "geometry_type"
const val SRID_COLUMN = "srid"
const val DIMENSION_COLUMN = "dimension"

// what "geometry" says in the json of a field, and of an object (its first geometry field)
data class GeometryResponse(
    val type: String,
    val srid: Int,
    val dimension: Int
)

// null on every field that is not a GEOMETRY. a stored value the enum does not know reads as null
// rather than breaking every response that lists the field.
val CustomField.geometryType: GeometryType?
    get() = (attributes[GEOMETRY_TYPE_COLUMN] as String?)?.let { raw -> GeometryType.entries.firstOrNull { it.name == raw } }

val CustomField.srid: Int? get() = (attributes[SRID_COLUMN] as Number?)?.toInt()

val CustomField.dimension: Int? get() = (attributes[DIMENSION_COLUMN] as Number?)?.toInt()

// the geometry columns of this object, in field order. empty means a flat object.
val ObjectDefinition.geometryFields: List<CustomField>
    get() = fields.filter { it.type == GEOMETRY }

fun CustomField.toGeometryResponse(): GeometryResponse? = geometryType?.let { GeometryResponse(it.name, srid ?: 0, dimension ?: 2) }
```

Run: `./gradlew :chawpi-gis:compileKotlin`
Expected: `BUILD SUCCESSFUL` (`LayerService` now compiles against these helpers; `FeatureController` comes in Step 9). If a core import fails, check the symbol with `grep -rn "fun <name>\|class <name>" backend/chawpi-core/src/main/kotlin` and fix the import only.

- [ ] **Step 5: Shared test fixtures**

`backend/chawpi-gis/src/test/kotlin/chawpi/gis/GisFixtures.kt`:

```kotlin
package chawpi.gis

import chawpi.core.common.ValidationException
import chawpi.core.metadata.CustomField
import chawpi.core.metadata.CustomObject
import chawpi.core.metadata.FieldType
import chawpi.core.metadata.ObjectDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import java.util.UUID

object GisFixtures {
    val obj =
        CustomObject(
            id = UUID.randomUUID(),
            organizationId = UUID.randomUUID(),
            name = "predio",
            label = "Predio",
            pluralLabel = "Predios",
            description = null,
            enabled = true,
            physicalTable = "predio__00000000",
            createdAt = null,
            updatedAt = null
        )

    fun geometry(
        name: String,
        type: String? = "POLYGON",
        srid: Int? = 32718,
        dimension: Int? = 2
    ): CustomField = field(name, GEOMETRY, mapOf(GEOMETRY_TYPE_COLUMN to type, SRID_COLUMN to srid, DIMENSION_COLUMN to dimension))

    fun text(name: String): CustomField = field(name, FieldType.TEXT, mapOf(GEOMETRY_TYPE_COLUMN to null, SRID_COLUMN to null, DIMENSION_COLUMN to null))

    fun definition(vararg fields: CustomField) = ObjectDefinition(obj, fields.toList())

    private fun field(
        name: String,
        type: FieldType,
        attributes: Map<String, Any?>
    ) = CustomField(
        id = UUID.randomUUID(),
        objectId = obj.id,
        name = name,
        label = name,
        type = type,
        columnName = name,
        required = false,
        unique = false,
        defaultValue = null,
        description = null,
        position = 0,
        enumOptions = null,
        relationTargetObjectId = null,
        attributes = attributes,
        visible = true,
        editable = true
    )

    // a 400 naming the field and the message, the way the original answered
    fun refused(
        message: String,
        field: String,
        block: () -> Unit
    ) {
        val error = assertThrows<ValidationException> { block() }
        assertThat(error.message).isEqualTo(message)
        assertThat(error.violations.single().field).isEqualTo(field)
    }
}
```

- [ ] **Step 6: Write the failing handler test**

`backend/chawpi-gis/src/test/kotlin/chawpi/gis/GeometryFieldTypeTest.kt`:

```kotlin
package chawpi.gis

import chawpi.core.metadata.FieldRequest
import chawpi.core.metadata.FieldType
import chawpi.core.metadata.FieldTypeRegistry
import chawpi.core.metadata.UpdateFieldRequest
import chawpi.gis.GisFixtures.definition
import chawpi.gis.GisFixtures.geometry
import chawpi.gis.GisFixtures.obj
import chawpi.gis.GisFixtures.refused
import chawpi.gis.GisFixtures.text
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class GeometryFieldTypeTest {
    private val handler = GeometryFieldType(JsonMapper.builder().build())

    private fun request(
        vararg extensions: Pair<String, Any?>,
        unique: Boolean = false,
        defaultValue: String? = null
    ) = FieldRequest(name = "lote", type = "GEOMETRY", unique = unique, defaultValue = defaultValue, extensions = mapOf(*extensions))

    // ---- metadata ----

    @Test
    fun `a new geometry defaults to 2d in 4326, whatever case its type is written in`() {
        assertThat(handler.attributesOf("lote", request("geometryType" to "point")))
            .isEqualTo(mapOf("geometry_type" to "POINT", "srid" to 4326, "dimension" to 2))
    }

    @Test
    fun `srid and dimension are taken as given, a numeric string included`() {
        assertThat(handler.attributesOf("lote", request("geometryType" to "POLYGON", "srid" to "32718", "dimension" to 3)))
            .isEqualTo(mapOf("geometry_type" to "POLYGON", "srid" to 32718, "dimension" to 3))
    }

    @Test
    fun `unique and a default mean nothing on a geometry`() {
        refused("Geometry field 'lote' cannot be unique", "unique") { handler.attributesOf("lote", request("geometryType" to "POINT", unique = true)) }
        refused("Geometry field 'lote' cannot have a default", "defaultValue") {
            handler.attributesOf("lote", request("geometryType" to "POINT", defaultValue = "x"))
        }
    }

    @Test
    fun `bad dimension, srid or type are a 400, never a 500`() {
        refused("Invalid dimension", "dimension") { handler.attributesOf("lote", request("geometryType" to "POINT", "dimension" to 4)) }
        refused("Invalid SRID -1", "srid") { handler.attributesOf("lote", request("geometryType" to "POINT", "srid" to -1)) }
        refused("Invalid SRID 1000000", "srid") { handler.attributesOf("lote", request("geometryType" to "POINT", "srid" to 1_000_000)) }
        refused("Invalid SRID abc", "srid") { handler.attributesOf("lote", request("geometryType" to "POINT", "srid" to "abc")) }
        refused("Invalid SRID 1.5", "srid") { handler.attributesOf("lote", request("geometryType" to "POINT", "srid" to 1.5)) }
        refused("Unknown geometry type ''", "geometryType") { handler.attributesOf("lote", request()) }
        refused("Unknown geometry type 'circle'", "geometryType") { handler.attributesOf("lote", request("geometryType" to "circle")) }
    }

    @Test
    fun `an existing geometry cannot be made unique`() {
        refused("Geometry field 'lote' cannot be unique", "unique") { handler.checkUpdate(geometry("lote"), UpdateFieldRequest(unique = true)) }
        handler.checkUpdate(geometry("lote"), UpdateFieldRequest(label = "Lote"))
    }

    // ---- json ----

    @Test
    fun `every field says geometry, null unless it is one`() {
        assertThat(handler.fieldProperties(text("codigo"))).containsExactly(org.assertj.core.api.Assertions.entry("geometry", null))
        assertThat(handler.fieldProperties(geometry("acceso", "POINT", 4326, 3)))
            .containsExactly(org.assertj.core.api.Assertions.entry("geometry", GeometryResponse("POINT", 4326, 3)))
    }

    @Test
    fun `an object's geometry is its first geometry field, null when flat`() {
        val spatial = definition(text("codigo"), geometry("lote", "POLYGON"), geometry("acceso", "POINT"))
        assertThat(handler.objectProperties(spatial)["geometry"]).isEqualTo(GeometryResponse("POLYGON", 32718, 2))
        assertThat(handler.objectProperties(definition(text("codigo")))).containsEntry("geometry", null)
    }

    // ---- ddl ----

    @Test
    fun `the column is typed by shape, dimension and crs`() {
        assertThat(handler.columnType(geometry("lote", "POLYGON", 32718, 2))).isEqualTo("geometry(Polygon, 32718)")
        assertThat(handler.columnType(geometry("acceso", "POINT", 4326, 3))).isEqualTo("geometry(PointZ, 4326)")
    }

    // attributes come from the database: checked again before they reach DDL (P1 ledger)
    @Test
    fun `the column refuses a stored field with no type or a bad srid`() {
        refused("Geometry field 'lote' has no type", "lote") { handler.columnType(geometry("lote", type = null)) }
        refused("Invalid SRID 0", "srid") { handler.columnType(geometry("lote", srid = 0)) }
    }

    @Test
    fun `a gist index per geometry column`() {
        assertThat(handler.indexes(obj, "\"app_data\".\"predio__00000000\"", geometry("lote")))
            .containsExactly("CREATE INDEX \"predio__00000000_lote_gix\" ON \"app_data\".\"predio__00000000\" USING GIST (\"lote\")")
    }

    // ---- records ----

    @Test
    fun `geojson in 4326 on the wire, the column's own crs on disk`() {
        val lote = geometry("lote", srid = 32718)
        assertThat(handler.bindExpression(lote, "s0")).isEqualTo("ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(CAST(:s0 AS text)), 4326), 32718)")
        assertThat(handler.select(lote, "\"lote\"")).isEqualTo("ST_AsGeoJSON(ST_Transform(\"lote\", 4326)) AS \"lote__geojson\"")
        assertThat(handler.readName(lote)).isEqualTo("lote__geojson")
        assertThat(handler.javaType(lote)).isEqualTo(String::class.java)
        assertThat(handler.section).isEqualTo("geometries")
    }

    @Test
    fun `a geometry value must be geojson of the field's own shape`() {
        val lote = geometry("lote", "POLYGON")
        val polygon = mapOf("type" to "polygon", "coordinates" to listOf(listOf(listOf(0.0, 0.0), listOf(1.0, 0.0), listOf(0.0, 1.0), listOf(0.0, 0.0))))
        assertThat(handler.toDatabase(lote, polygon) as String).contains("\"type\":\"polygon\"").contains("\"coordinates\"")
        assertThat(handler.toDatabase(lote, null)).isNull()
        refused("Invalid geometry", "lote") { handler.toDatabase(lote, mapOf("type" to "Point", "coordinates" to listOf(1.0, 2.0))) }
        refused("Invalid geometry", "lote") { handler.toDatabase(lote, mapOf("coordinates" to listOf(1.0, 2.0))) }
        refused("Invalid geometry", "lote") { handler.toDatabase(lote, "POINT(1 2)") }
    }

    @Test
    fun `geojson text reads back as a map`() {
        assertThat(handler.fromDatabase(geometry("lote"), """{"type":"Point","coordinates":[1.0,2.0]}"""))
            .isEqualTo(mapOf("type" to "Point", "coordinates" to listOf(1.0, 2.0)))
        assertThat(handler.fromDatabase(geometry("lote"), null)).isNull()
    }

    @Test
    fun `a geometry is filtered with bbox, never by equality or sort, and unknown keys say geometry`() {
        refused("Cannot filter or sort by geometry 'lote'", "lote") { throw handler.rejectFilterOrSort(geometry("lote")) }
        refused("Unknown geometry 'x'", "x") { throw handler.unknownSectionKey("x", definition(geometry("lote"))) }
    }

    // ---- registry ----

    @Test
    fun `installed next to core, GEOMETRY parses and is listed last, as before`() {
        val registry = FieldTypeRegistry(listOf(handler))
        assertThat(registry.parse("geometry")).isEqualTo(GEOMETRY)
        assertThat(registry.types.last()).isEqualTo(FieldType("GEOMETRY"))
        assertThat(registry.sections).containsExactly("geometries")
        assertThat(registry.attributeColumns.keys).containsExactly("geometry_type", "srid", "dimension")
        refused(
            "Unknown field type 'circle'",
            "type"
        ) { registry.parse("circle") }
    }
}
```

Run: `./gradlew :chawpi-gis:test --tests 'chawpi.gis.GeometryFieldTypeTest'`
Expected: FAIL, `Unresolved reference 'GeometryFieldType'`.

- [ ] **Step 7: Write the handler**

`backend/chawpi-gis/src/main/kotlin/chawpi/gis/GeometryFieldType.kt`:

```kotlin
package chawpi.gis

import chawpi.core.common.ValidationException
import chawpi.core.metadata.CustomField
import chawpi.core.metadata.CustomObject
import chawpi.core.metadata.FieldRequest
import chawpi.core.metadata.FieldTypeHandler
import chawpi.core.metadata.ObjectDefinition
import chawpi.core.metadata.UpdateFieldRequest
import chawpi.core.platform.SqlIdentifier
import tools.jackson.databind.ObjectMapper

// GEOMETRY: a typed postgis column. geojson crosses r2dbc as text: ST_AsGeoJSON out,
// ST_GeomFromGeoJSON in, always in 4326 on the wire and the field's own crs on disk. ADR-007, ADR-019.
class GeometryFieldType(
    private val objectMapper: ObjectMapper
) : FieldTypeHandler {
    override val type = GEOMETRY

    override val attributeColumns: Map<String, Class<*>> =
        linkedMapOf(
            GEOMETRY_TYPE_COLUMN to String::class.java,
            SRID_COLUMN to Int::class.javaObjectType,
            DIMENSION_COLUMN to Int::class.javaObjectType
        )

    override val section = GEOMETRIES

    // ---- metadata ----

    // a geometry is a column with a shape and a crs. unique and a default mean nothing on one.
    // checked in the original's order, so the first complaint is the same one.
    override fun attributesOf(
        fieldName: String,
        request: FieldRequest
    ): Map<String, Any?> {
        if (request.unique) {
            throw ValidationException("Geometry field '$fieldName' cannot be unique", "unique", "has no meaning on a geometry")
        }
        if (request.defaultValue != null) {
            throw ValidationException("Geometry field '$fieldName' cannot have a default", "defaultValue", "has no meaning on a geometry")
        }
        val dimension = wholeNumber(request.extensions["dimension"]) { invalidDimension() } ?: 2
        if (dimension !in 2..3) throw invalidDimension()
        val rawSrid = request.extensions["srid"]
        val srid =
            validSrid(
                wholeNumber(rawSrid) { ValidationException("Invalid SRID $rawSrid", "srid", "must be a positive EPSG code") } ?: DEFAULT_SRID
            )
        val geometryType = GeometryType.parse(request.extensions["geometryType"]?.toString())
        return mapOf(GEOMETRY_TYPE_COLUMN to geometryType.name, SRID_COLUMN to srid, DIMENSION_COLUMN to dimension)
    }

    override fun checkUpdate(
        field: CustomField,
        request: UpdateFieldRequest
    ) {
        if (request.unique == true) {
            throw ValidationException("Geometry field '${field.name}' cannot be unique", "unique", "has no meaning on a geometry")
        }
    }

    // every field carries the key, null unless it is a geometry: the json the original always answered
    override fun fieldProperties(field: CustomField): Map<String, Any?> =
        mapOf("geometry" to if (field.type == GEOMETRY) field.toGeometryResponse() else null)

    // the first geometry field, kept so callers that only ask "is this object spatial" still work
    override fun objectProperties(definition: ObjectDefinition): Map<String, Any?> =
        mapOf("geometry" to definition.geometryFields.firstOrNull()?.toGeometryResponse())

    // ---- ddl ----

    // a typed geometry column, not a bare "geometry": the type and the srid are the constraint.
    // the attributes come back from the database, so they are checked again before DDL sees them.
    override fun columnType(field: CustomField): String {
        val geometryType =
            field.geometryType
                ?: throw ValidationException("Geometry field '${field.name}' has no type", field.name, "requires geometryType")
        return "geometry(${geometryType.columnType(field.dimension ?: 2)}, ${validSrid(field.srid ?: 0)})"
    }

    // a geometry column without a GIST index is a table scan per bbox
    override fun indexes(
        obj: CustomObject,
        table: String,
        field: CustomField
    ): List<String> {
        val name = SqlIdentifier.indexName(obj.physicalTable, field.columnName, "gix")
        return listOf("CREATE INDEX ${SqlIdentifier.quote(name)} ON $table USING GIST (${SqlIdentifier.quote(field.columnName)})")
    }

    // ---- records ----

    override fun unknownSectionKey(
        key: String,
        definition: ObjectDefinition
    ): ValidationException = ValidationException("Unknown geometry '$key'", key, "is not a geometry of '${definition.obj.name}'")

    override fun toDatabase(
        field: CustomField,
        value: Any?
    ): Any? {
        if (value == null) return null
        val geometry = value as? Map<*, *> ?: throw ValidationException("Invalid geometry", field.name, "must be a GeoJSON object")
        val expected = field.geometryType?.postgisType
        val type = geometry["type"] as? String ?: throw ValidationException("Invalid geometry", field.name, "missing 'type'")
        if (!type.equals(expected, ignoreCase = true)) {
            throw ValidationException("Invalid geometry", field.name, "must be a $expected")
        }
        return objectMapper.writeValueAsString(geometry)
    }

    override fun javaType(field: CustomField): Class<*> = String::class.java

    // stored in the field's srid, exchanged in 4326
    override fun bindExpression(
        field: CustomField,
        parameter: String
    ): String = "ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(CAST(:$parameter AS text)), $WGS84), ${validSrid(field.srid ?: 0)})"

    // core never adds an alias (P1 ledger): a rewritten expression must name itself
    override fun select(
        field: CustomField,
        column: String
    ): String = "ST_AsGeoJSON(ST_Transform($column, $WGS84)) AS ${SqlIdentifier.quote(readName(field))}"

    // one alias per geometry column, so two of them never share a slot
    override fun readName(field: CustomField): String = "${field.columnName}__geojson"

    @Suppress("UNCHECKED_CAST")
    override fun fromDatabase(
        field: CustomField,
        value: Any?
    ): Any? = (value as String?)?.let { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> }

    // sorting or matching a geometry for equality is not a thing. bbox is how you filter one.
    override fun rejectFilterOrSort(field: CustomField): ValidationException =
        ValidationException("Cannot filter or sort by geometry '${field.name}'", field.name, "use bbox instead")

    private fun invalidDimension() = ValidationException("Invalid dimension", "dimension", "must be 2 or 3")

    // json numbers arrive as Int, Long or Double, and an admin may send "32718". a fraction is refused.
    private fun wholeNumber(
        raw: Any?,
        invalid: () -> ValidationException
    ): Int? =
        when (raw) {
            null -> null
            is Int -> raw
            is Number -> {
                val value = raw.toDouble()
                val whole = value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE
                if (whole) value.toInt() else throw invalid()
            }
            is String -> raw.trim().toIntOrNull() ?: throw invalid()
            else -> throw invalid()
        }
}
```

Run: `./gradlew :chawpi-gis:test --tests 'chawpi.gis.GeometryFieldTypeTest'`
Expected: PASS (15 tests).

- [ ] **Step 8: bbox — write the failing test, then the contributor**

`backend/chawpi-gis/src/test/kotlin/chawpi/gis/BboxQueryTest.kt`:

```kotlin
package chawpi.gis

import chawpi.core.metadata.ObjectDefinition
import chawpi.gis.GisFixtures.definition
import chawpi.gis.GisFixtures.geometry
import chawpi.gis.GisFixtures.refused
import chawpi.gis.GisFixtures.text
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BboxQueryTest {
    private val query = BboxQuery()
    private val spatial = definition(text("codigo"), geometry("lote", "POLYGON", 32718), geometry("acceso", "POINT", 4326))

    // what core's RecordQueryParser hands a criterion: every value becomes a named placeholder
    private fun sqlOf(
        params: Map<String, String>,
        on: ObjectDefinition = spatial
    ): Pair<String, List<Any>> {
        val bound = mutableListOf<Any>()
        val sql =
            query.parse(params)!!.condition(on) { value ->
                bound += value
                ":c${bound.size - 1}"
            }
        return sql to bound
    }

    @Test
    fun `bbox and geometry are ours, never field filters`() {
        assertThat(query.parameters).containsExactlyInAnyOrder("bbox", "geometry")
        assertThat(query.parse(mapOf("geometry" to "lote"))).isNull()
        assertThat(query.parse(emptyMap())).isNull()
    }

    @Test
    fun `a bbox intersects the first geometry, in its own crs, with every number bound`() {
        val (sql, bound) = sqlOf(mapOf("bbox" to "-77.1, -12.2, -77.0, -12.0"))
        assertThat(sql).isEqualTo("ST_Intersects(\"lote\", ST_Transform(ST_MakeEnvelope(:c0, :c1, :c2, :c3, 4326), 32718))")
        assertThat(bound).containsExactly(-77.1, -12.2, -77.0, -12.0)
    }

    @Test
    fun `geometry names which one`() {
        val (sql, _) = sqlOf(mapOf("bbox" to "1,2,3,4", "geometry" to " acceso "))
        assertThat(sql).startsWith("ST_Intersects(\"acceso\",").endsWith(", 4326))")
    }

    @Test
    fun `a broken bbox is refused before any object is read`() {
        refused("Invalid bbox", "bbox") { query.parse(mapOf("bbox" to "1,2,3")) }
        refused("Invalid bbox", "bbox") { query.parse(mapOf("bbox" to "a,b,c,d")) }
    }

    // it used to be dropped in silence on a flat object, which answered 200 to a question nobody answered
    @Test
    fun `a bbox on a flat object or a geometry it does not have is a 400`() {
        refused("Object 'predio' has no geometry", "bbox") { sqlOf(mapOf("bbox" to "1,2,3,4"), definition(text("codigo"))) }
        refused("Unknown geometry 'x'", "geometry") { sqlOf(mapOf("bbox" to "1,2,3,4", "geometry" to "x")) }
    }
}
```

Run: `./gradlew :chawpi-gis:test --tests 'chawpi.gis.BboxQueryTest'` → FAIL (`Unresolved reference 'BboxQuery'`).

`backend/chawpi-gis/src/main/kotlin/chawpi/gis/BboxQuery.kt`:

```kotlin
package chawpi.gis

import chawpi.core.common.ValidationException
import chawpi.core.data.RecordCriterion
import chawpi.core.data.RecordQueryContributor
import chawpi.core.metadata.CustomField
import chawpi.core.metadata.ObjectDefinition
import chawpi.core.platform.SqlIdentifier

data class BoundingBox(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double
)

// ?bbox=minX,minY,maxX,maxY (EPSG:4326) and ?geometry=<field>: records whose geometry meets the box.
// numbers are bound; only the srid, validated, is written into the sql.
class BboxQuery : RecordQueryContributor {
    override val parameters: Set<String> = setOf("bbox", "geometry")

    override fun parse(params: Map<String, String>): RecordCriterion? {
        // geometry alone filters nothing: it only says which column a bbox means
        val box = params["bbox"]?.let(::parseBbox) ?: return null
        val named = params["geometry"]?.trim()?.ifBlank { null }
        return RecordCriterion { definition, bind ->
            val field = geometryOrFail(definition, named)
            val column = SqlIdentifier.quote(field.columnName)
            "ST_Intersects($column, ST_Transform(ST_MakeEnvelope(${bind(box.minX)}, ${bind(box.minY)}, ${bind(box.maxX)}, ${bind(box.maxY)}, $WGS84), " +
                "${validSrid(field.srid ?: 0)}))"
        }
    }

    companion object {
        fun parseBbox(raw: String): BoundingBox {
            val parts = raw.split(",").mapNotNull { it.trim().toDoubleOrNull() }
            if (parts.size != 4) {
                throw ValidationException("Invalid bbox", "bbox", "must be minX,minY,maxX,maxY in EPSG:4326")
            }
            return BoundingBox(parts[0], parts[1], parts[2], parts[3])
        }

        // the geometry a spatial query means: the one it named, or the first one the object declares
        fun geometryOrFail(
            definition: ObjectDefinition,
            name: String?
        ): CustomField {
            if (name == null) {
                return definition.geometryFields.firstOrNull()
                    ?: throw ValidationException("Object '${definition.obj.name}' has no geometry", "bbox", "the object is not spatial")
            }
            return definition.geometryFields.firstOrNull { it.name == name }
                ?: throw ValidationException("Unknown geometry '$name'", "geometry", "is not a geometry of '${definition.obj.name}'")
        }
    }
}
```

Run the test again → PASS (5 tests).

- [ ] **Step 9: Rewrite the feature endpoints on core's record API**

`backend/chawpi-gis/src/main/kotlin/chawpi/gis/FeatureController.kt` (the original's, with `RecordQueryParams` → the `RecordQueryParser` bean, `featureRows` → `rows`, `row.geometries` → the `geometries` section, `type.isTextLike` → the handler's `textLike`):

```kotlin
package chawpi.gis

import chawpi.core.common.NotFoundException
import chawpi.core.common.PageRequest
import chawpi.core.data.RecordQueryParser
import chawpi.core.data.RecordService
import chawpi.core.metadata.FieldTypeRegistry
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

private const val MAX_FEATURES = 5000

// an object's records as geojson, for the map. same permissions and bbox as the record list.
@RestController
@RequestMapping("/api/gis/objects/{object}/features")
class FeatureController(
    private val records: RecordService,
    private val queries: RecordQueryParser,
    private val types: FieldTypeRegistry
) {
    @GetMapping
    suspend fun collection(
        @PathVariable("object") objectName: String,
        @RequestParam params: Map<String, String>
    ): FeatureCollection {
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, MAX_FEATURES) ?: 1000
        val query = queries.parse(params).copy(page = PageRequest(0, limit))
        val (definition, rows) = records.rows(objectName, query)
        // a field whose module is gone has no handler: it cannot be the label
        val labelField = definition.fields.firstOrNull { types.isInstalled(it.type) && types.handler(it.type).textLike }?.name
        // a geojson Feature holds one geometry, so a request carries one. named, or the first.
        val geometry =
            params["geometry"]?.trim()?.ifBlank { null }
                ?: definition.geometryFields.firstOrNull()?.name
                ?: throw NotFoundException("Object '$objectName' has no geometry")
        return FeatureCollection(
            rows.map { row ->
                Feature(
                    id = "${row.id}:$geometry",
                    geometry = shape(row.sections[GEOMETRIES]?.get(geometry)),
                    properties =
                        row.attributes +
                            mapOf("__label" to labelField?.let { row.attributes[it] }, "__id" to row.id.toString())
                )
            }
        )
    }

    @GetMapping("/{id}")
    suspend fun feature(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @RequestParam(required = false) geometry: String?
    ): Feature {
        val record = records.get(objectName, id)
        val geometries = record.sections[GEOMETRIES].orEmpty()
        val name = geometry ?: geometries.keys.firstOrNull() ?: throw NotFoundException("Record $id has no geometry")
        val shape = shape(geometries[name]) ?: throw NotFoundException("Record $id has no geometry '$name'")
        return Feature(id = "${record.id}:$name", geometry = shape, properties = record.attributes)
    }

    @Suppress("UNCHECKED_CAST")
    private fun shape(value: Any?): Map<String, Any?>? = value as Map<String, Any?>?
}
```

Run: `./gradlew :chawpi-gis:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Write the failing migration test, then the migration (the original's V1 postgis + V9, final shape)**

`backend/chawpi-gis/src/test/kotlin/chawpi/gis/GisMigrationSqlTest.kt`:

```kotlin
package chawpi.gis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GisMigrationSqlTest {
    private val sql = javaClass.getResource("/db/chawpi/gis/V1__gis.sql")!!.readText()

    @Test
    fun `postgis lives in public, shared by every app in the database`() {
        assertThat(sql).contains("CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;")
    }

    @Test
    fun `the geometry columns and checks the original had on custom_fields`() {
        assertThat(sql)
            .contains("ADD COLUMN geometry_type text,")
            .contains("ADD COLUMN srid          integer,")
            .contains("ADD COLUMN dimension     integer;")
            .contains("ADD CONSTRAINT custom_fields_geometry_has_type CHECK (")
            .contains("ADD CONSTRAINT custom_fields_geometry_type_valid CHECK (geometry_type IS NULL OR geometry_type IN (")
            .contains("ADD CONSTRAINT custom_fields_dimension_valid CHECK (dimension IS NULL OR dimension IN (2, 3));")
    }

    // P1 R4: the type list is re-added under the same name with GEOMETRY appended
    @Test
    fun `GEOMETRY joins the type check, loud if core's check is missing`() {
        assertThat(sql)
            .contains("ALTER TABLE \${metadataSchema}.custom_fields DROP CONSTRAINT custom_fields_type_valid;")
            .contains("'ENUM', 'EMAIL', 'URL', 'UUID', 'RELATION', 'GEOMETRY'")
            .doesNotContain("IF EXISTS")
            .doesNotContainIgnoringCase("sapgis")
    }
}
```

Run: `./gradlew :chawpi-gis:test --tests 'chawpi.gis.GisMigrationSqlTest'` → FAIL (`NullPointerException`).

`backend/chawpi-gis/src/main/resources/db/chawpi/gis/V1__gis.sql`:

```sql
-- chawpi-gis: postgis and the GEOMETRY field type (ADR-0027). the original's V1 (extension) + V9 (geometry
-- is a field) rebaselined (ADR-0026): final shape only. the attribute columns are added last, as V9
-- did, so custom_fields ends with the original column order.

-- schema-qualified: flyway's search_path puts ${metadataSchema} first, and an unqualified CREATE
-- EXTENSION would land there. public is shared by every app in the database.
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;

ALTER TABLE ${metadataSchema}.custom_fields
    ADD COLUMN geometry_type text,
    ADD COLUMN srid          integer,
    ADD COLUMN dimension     integer;

-- core's check lists its twelve types. GEOMETRY is appended under the same name (P1 R4). no IF
-- EXISTS: if core's constraint is not there, something is wrong and this must fail.
ALTER TABLE ${metadataSchema}.custom_fields DROP CONSTRAINT custom_fields_type_valid;
ALTER TABLE ${metadataSchema}.custom_fields ADD CONSTRAINT custom_fields_type_valid CHECK (type IN (
    'TEXT', 'LONG_TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATETIME',
    'ENUM', 'EMAIL', 'URL', 'UUID', 'RELATION', 'GEOMETRY'
));

-- same shape as the enum and relation checks: metadata a type needs, and only that type.
ALTER TABLE ${metadataSchema}.custom_fields
    ADD CONSTRAINT custom_fields_geometry_has_type CHECK (
        type <> 'GEOMETRY' OR (geometry_type IS NOT NULL AND srid IS NOT NULL AND dimension IS NOT NULL)
    ),
    ADD CONSTRAINT custom_fields_geometry_type_valid CHECK (geometry_type IS NULL OR geometry_type IN (
        'POINT', 'LINESTRING', 'POLYGON', 'MULTIPOINT', 'MULTILINESTRING', 'MULTIPOLYGON'
    )),
    ADD CONSTRAINT custom_fields_dimension_valid CHECK (dimension IS NULL OR dimension IN (2, 3));
```

Run the test again → PASS.

- [ ] **Step 11: Write the failing auto-config test**

`backend/chawpi-gis/src/test/kotlin/chawpi/gis/ChawpiGisAutoConfigurationTest.kt`:

```kotlin
package chawpi.gis

import chawpi.core.data.RecordQueryParser
import chawpi.core.metadata.FieldTypeRegistry
import chawpi.core.metadata.ObjectRemovalListener
import chawpi.core.platform.ModuleMigration
import chawpi.gis.autoconfigure.ChawpiGisAutoConfiguration
import chawpi.test.ChawpiContextRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates

class ChawpiGisAutoConfigurationTest {
    private val runner = ChawpiContextRunner.core().withConfiguration(AutoConfigurations.of(ChawpiGisAutoConfiguration::class.java))

    @Test
    fun `gis plugs GEOMETRY, bbox and layer cleanup into core`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val registry = context.getBean(FieldTypeRegistry::class.java)
            assertThat(registry.types.last()).isEqualTo(GEOMETRY)
            assertThat(registry.sections).containsExactly("geometries")
            val query = context.getBean(RecordQueryParser::class.java).parse(mapOf("bbox" to "1,2,3,4", "codigo" to "A-1"))
            assertThat(query.filters).containsOnlyKeys("codigo")
            assertThat(query.criteria).hasSize(1)
            assertThat(context.getBeansOfType(ObjectRemovalListener::class.java).values).hasAtLeastOneElementOfType(LayerCleanup::class.java)
            assertThat(context).hasSingleBean(FeatureController::class.java)
            assertThat(context).hasSingleBean(LayerController::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactlyInAnyOrder("core", "gis")
        }
    }

    @Test
    fun `geoserver settings bind under chawpi gis geoserver`() {
        runner.withPropertyValues("chawpi.gis.geoserver.url=http://gs:8080/geoserver/", "chawpi.gis.geoserver.enabled=false").run { context ->
            val properties = context.getBean(GeoServerProperties::class.java)
            assertThat(properties.baseUrl).isEqualTo("http://gs:8080/geoserver")
            assertThat(properties.enabled).isFalse()
            assertThat(properties.datastore.schema).isEqualTo("app_data")
            assertThat(properties.workspace).isEqualTo("chawpi")
        }
    }

    // the core-only answer: no GEOMETRY, and ?bbox= is just an unknown field filter (P1 R7)
    @Test
    fun `switched off, core knows nothing of geometry`() {
        runner.withPropertyValues("chawpi.gis.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(FieldTypeRegistry::class.java).isInstalled(GEOMETRY)).isFalse()
            assertThat(context.getBean(RecordQueryParser::class.java).parse(mapOf("bbox" to "1,2,3,4")).filters).containsOnlyKeys("bbox")
            assertThat(context).doesNotHaveBean(FeatureController::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactly("core")
        }
    }

    @Test
    fun `the imports file registers the auto-config`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("chawpi.gis.autoconfigure.ChawpiGisAutoConfiguration")
    }
}
```

Run: `./gradlew :chawpi-gis:test --tests 'chawpi.gis.ChawpiGisAutoConfigurationTest'`
Expected: FAIL, `Unresolved reference 'ChawpiGisAutoConfiguration'`.

- [ ] **Step 12: Properties, auto-config and imports file**

`backend/chawpi-gis/src/main/kotlin/chawpi/gis/autoconfigure/ChawpiGisProperties.kt`:

```kotlin
package chawpi.gis.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

// the module switch. geoserver publishing has its own switch: chawpi.gis.geoserver.enabled.
@ConfigurationProperties("chawpi.gis")
data class ChawpiGisProperties(
    // false: no GEOMETRY type, no bbox, no gis routes, no postgis migration
    val enabled: Boolean = true
)
```

`backend/chawpi-gis/src/main/kotlin/chawpi/gis/autoconfigure/ChawpiGisAutoConfiguration.kt`:

```kotlin
package chawpi.gis.autoconfigure

import chawpi.core.autoconfigure.ChawpiDataAutoConfiguration
import chawpi.core.data.RecordQueryParser
import chawpi.core.data.RecordService
import chawpi.core.identity.CurrentUser
import chawpi.core.metadata.FieldTypeRegistry
import chawpi.core.metadata.MetadataService
import chawpi.core.platform.ModuleMigration
import chawpi.gis.BboxQuery
import chawpi.gis.FeatureController
import chawpi.gis.GeoServerClient
import chawpi.gis.GeoServerProperties
import chawpi.gis.GeometryFieldType
import chawpi.gis.LayerCleanup
import chawpi.gis.LayerController
import chawpi.gis.LayerService
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import tools.jackson.databind.json.JsonMapper

// GIS as a plug-in: a field type, a query parameter, an object-removal listener and its own routes.
// core finds the first three through ObjectProviders, so no ordering against core is needed.
@AutoConfiguration(after = [ChawpiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "chawpi.gis", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ChawpiGisProperties::class, GeoServerProperties::class)
class ChawpiGisAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would always make it back off
    @Bean
    fun chawpiGisMigration(): ModuleMigration = ModuleMigration("gis", "classpath:db/chawpi/gis", ModuleMigration.MODULE_ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun geometryFieldType(objectMapper: JsonMapper): GeometryFieldType = GeometryFieldType(objectMapper)

    @Bean
    @ConditionalOnMissingBean
    fun bboxQuery(): BboxQuery = BboxQuery()

    @Bean
    @ConditionalOnMissingBean
    fun geoServerClient(properties: GeoServerProperties): GeoServerClient = GeoServerClient(properties)

    @Bean
    @ConditionalOnMissingBean
    fun layerCleanup(
        client: GeoServerClient,
        properties: GeoServerProperties
    ): LayerCleanup = LayerCleanup(client, properties)

    @Bean
    @ConditionalOnMissingBean
    fun layerService(
        metadata: MetadataService,
        client: GeoServerClient,
        properties: GeoServerProperties,
        currentUser: CurrentUser
    ): LayerService = LayerService(metadata, client, properties, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun layerController(layers: LayerService): LayerController = LayerController(layers)

    @Bean
    @ConditionalOnMissingBean
    fun featureController(
        records: RecordService,
        queries: RecordQueryParser,
        types: FieldTypeRegistry
    ): FeatureController = FeatureController(records, queries, types)
}
```

`backend/chawpi-gis/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
chawpi.gis.autoconfigure.ChawpiGisAutoConfiguration
```

Run: `./gradlew :chawpi-gis:test`
Expected: PASS (every suite: `GeoServerPayloadsTest`, `GeoServerUrlsTest`, `GeometryFieldTypeTest`, `BboxQueryTest`, `GisMigrationSqlTest`, `ChawpiGisAutoConfigurationTest`).

- [ ] **Step 13: Format, nothing named after the original, leave uncommitted**

```bash
./gradlew :chawpi-gis:ktlintFormat
./gradlew :chawpi-gis:ktlintCheck
./gradlew :chawpi-gis:jar
grep -rn -i "sapgis" backend/chawpi-gis || echo "clean"
grep -rn "app_data" backend/chawpi-gis/src/main || true
git status --short backend/chawpi-gis
```
Expected: ktlint passes, `clean`; `app_data` appears only as the `GeoServerDataStoreProperties.schema` default; `?? backend/chawpi-gis/`.

### Task 8: `chawpi-agent` (Wave 1)

The workflow adapter for `available_transitions` is Task 11.

**Files:**
- Create: `backend/chawpi-agent/build.gradle.kts`
- Create (ported from `$SRC/agent/`): `backend/chawpi-agent/src/main/kotlin/chawpi/agent/{AgentController,AgentProperties,AgentRun,AgentService,AgentToolCatalog,AgentToolInput,AgentToolbox,AgentTools}.kt` and `ChawpiAgent.kt` (from `SapgisAgent.kt`)
- Create (new): `backend/chawpi-agent/src/main/kotlin/chawpi/agent/RecordTransitions.kt`
- Create (rewritten from `EmbabelGate.kt`): `backend/chawpi-agent/src/main/kotlin/chawpi/agent/autoconfigure/EmbabelGate.kt`
- Create: `backend/chawpi-agent/src/main/kotlin/chawpi/agent/autoconfigure/ChawpiAgentAutoConfiguration.kt`
- Create: `backend/chawpi-agent/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `backend/chawpi-agent/src/main/resources/META-INF/spring.factories`
- Test (ported from `$TSRC/agent/`): `backend/chawpi-agent/src/test/kotlin/chawpi/agent/{AgentToolCatalogTest,AgentToolInputTest}.kt`
- Test (new): `backend/chawpi-agent/src/test/kotlin/chawpi/agent/{EmbabelGateTest,ChawpiAgentAutoConfigurationTest}.kt`

**Interfaces:**
- Consumes (core): `MetadataService` (`listDefinitions`, `definitionOf`), `MetadataMapper` (`toObjectResponse(definition)`, `toResponse(definition, organizationId)`), `RecordService` (`list`, `get`), `RecordQueryParser.parse(params): RecordQuery` (`filters`, `criteria`), `RecordQuery(page, sort, descending, search, filters, criteria)`, `RelationshipService.forObject`, `RelatedRecordService.relatedRecords(objectName, recordId, relationshipName, query)`, `AuditQueryService.history`, `CurrentUser`, `ChawpiException`; Embabel `AgentPlatform`; `ChawpiContextRunner.core()` (Task 1).
- Produces:
  - `data class chawpi.agent.AgentTransition(name: String, label: String, to: String, toLabel: String, allowed: Boolean, reason: String? = null)` (same JSON as workflow's `AvailableTransition`).
  - `fun interface chawpi.agent.RecordTransitions { suspend fun transitionsOf(objectName: String, id: UUID): List<AgentTransition> }` and `class NoRecordTransitions : RecordTransitions` (empty list) — Task 11 implements the port over `WorkflowService`.
  - `AgentTools(metadata, mapper, records, relationships, related, transitions, queries, audit, currentUser, json)`, `ChawpiAgent` (`@Agent(name = "chawpi-assistant")`), `AgentService`, `AgentController` (`/api/agent/ask`, `/api/agent/status`), `AgentProperties` at `chawpi.agent`.
  - `chawpi.agent.autoconfigure.EmbabelGate` (EnvironmentPostProcessor), `chawpi.agent.autoconfigure.ChawpiAgentAutoConfiguration`.

- [ ] **Step 1: Module build file and folders**

```bash
cd /Users/jorge/IdeaProjects/chawpi
mkdir -p backend/chawpi-agent/src/main/kotlin/chawpi/agent/autoconfigure backend/chawpi-agent/src/main/resources/META-INF/spring \
  backend/chawpi-agent/src/test/kotlin/chawpi/agent
```

`backend/chawpi-agent/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.spring-module")
    id("chawpi.publishing")
    id("chawpi.integration-test")
}

description = "Chawpi agent: an AI assistant over the caller's own data, on Embabel"

dependencies {
    api(project(":chawpi-core"))
    // AgentService takes embabel's AgentPlatform: part of the api
    api(libs.embabel.agent.starter)
    api(libs.embabel.agent.starter.anthropic)

    testImplementation(project(":chawpi-test"))
}
```

- [ ] **Step 2: Copy the original's classes and tests, run the port rules**

`EmbabelGate.kt` is NOT copied (Step 6 rewrites it). `SapgisAgent.kt` becomes `ChawpiAgent.kt`.

```bash
SRC=/Users/jorge/IdeaProjects/sapgis/backend/src/main/kotlin/com/sapgis
TSRC=/Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis
DST=backend/chawpi-agent/src/main/kotlin/chawpi/agent
TDST=backend/chawpi-agent/src/test/kotlin/chawpi/agent
for f in AgentController AgentProperties AgentRun AgentService AgentToolCatalog AgentToolInput AgentToolbox AgentTools; do cp $SRC/agent/$f.kt $DST/; done
cp $SRC/agent/SapgisAgent.kt $DST/ChawpiAgent.kt
cp $TSRC/agent/AgentToolCatalogTest.kt $TSRC/agent/AgentToolInputTest.kt $TDST/
port() {
  sed -i '' -E \
    -e 's/^package com\.sapgis\.([a-z]+)$/package chawpi.\1/' \
    -e 's/com\.sapgis\.(views|forms|pages|workflow|automation|documents|gis|agent)\./chawpi.\1./g' \
    -e 's/com\.sapgis\.data\.ObjectSchemaManager/chawpi.core.metadata.ObjectSchemaManager/g' \
    -e 's/com\.sapgis\./chawpi.core./g' \
    -e 's/SapgisException/ChawpiException/g' \
    -e 's/sapgis\.geoserver/chawpi.gis.geoserver/g' \
    -e 's/sapgis\.(agent|automation)/chawpi.\1/g' \
    "$@"
  sed -i '' -E -e '/^[[:space:]]*\/\//!s/([^a-zA-Z_.])sapgis\.([a-z_]+)/\1${schemas.metadata}.\2/g' "$@"
  sed -i '' -e 's/SAPGIS/Chawpi/g' -e 's/Sapgis/Chawpi/g' -e 's/sapgis/chawpi/g' "$@"
}
port $DST/*.kt $TDST/*.kt
grep -n '@ConfigurationProperties\|name = "chawpi-assistant"\|class ChawpiAgent' $DST/*.kt
```
Expected: `@ConfigurationProperties(prefix = "chawpi.agent")`, `name = "chawpi-assistant"`, `class ChawpiAgent(`. The prompt text in `ChawpiAgent.kt` now says "the assistant built into Chawpi"; leave the rest of the prompt as it is.

- [ ] **Step 3: The transitions port**

`backend/chawpi-agent/src/main/kotlin/chawpi/agent/RecordTransitions.kt`:

```kotlin
package chawpi.agent

import java.util.UUID

// one transition leaving a record's state, as the assistant reports it. same json as
// chawpi-workflow's AvailableTransition, so the tool answer did not change with the split.
data class AgentTransition(
    val name: String,
    val label: String,
    val to: String,
    val toLabel: String,
    val allowed: Boolean,
    val reason: String? = null
)

// what the assistant may ask about transitions. chawpi-workflow answers when it is installed;
// the agent never depends on it (M2).
fun interface RecordTransitions {
    suspend fun transitionsOf(
        objectName: String,
        id: UUID
    ): List<AgentTransition>
}

// no workflow module: no object has a workflow, and "no workflow" has always answered an empty list
class NoRecordTransitions : RecordTransitions {
    override suspend fun transitionsOf(
        objectName: String,
        id: UUID
    ): List<AgentTransition> = emptyList()
}
```

- [ ] **Step 4: Tools call core's current API and the optional ports**

In `$DST/AgentToolInput.kt`:
1. Delete the lines `import chawpi.core.data.BoundingBox` and `import chawpi.core.data.RecordQueryParams`.
2. Replace
```kotlin
    fun bbox(input: Map<String, Any?>): BoundingBox? = optionalString(input, "bbox")?.let(RecordQueryParams::parseBbox)
```
with
```kotlin
    // passed along as sent: the gis module's query contributor parses it, and refuses a broken one
    fun bbox(input: Map<String, Any?>): String? = optionalString(input, "bbox")
```

In `$TDST/AgentToolInputTest.kt` replace the whole test `` `bbox is parsed, and a broken one is rejected` `` (the `@Test` line through its closing `}`) with:

```kotlin
    // BboxQuery (chawpi-gis) parses it; a core-only app answers the record api's 400 instead
    @Test
    fun `bbox is passed along as sent, trimmed`() {
        assertThat(AgentToolInput.bbox(mapOf("bbox" to " -1,-2,3,4 "))).isEqualTo("-1,-2,3,4")
        assertThat(AgentToolInput.bbox(emptyMap())).isNull()
    }
```

In `$DST/AgentTools.kt`:
1. Delete the lines `import chawpi.core.metadata.toResponse` and `import chawpi.workflow.WorkflowService`; add `import chawpi.core.data.RecordQueryParser` and `import chawpi.core.data.RelatedRecordService` (sorted).
2. Replace the constructor lines
```kotlin
    private val relationships: RelationshipService,
    private val workflows: WorkflowService,
```
with
```kotlin
    private val relationships: RelationshipService,
    private val related: RelatedRecordService,
    private val transitions: RecordTransitions,
    private val queries: RecordQueryParser,
```
3. In `listObjects()` replace `metadata.listDefinitions().map { it.toResponse() }` with `metadata.listDefinitions().map { mapper.toObjectResponse(it) }`.
4. Replace
```kotlin
    private suspend fun queryRecords(input: Map<String, Any?>): Pair<Any, String> {
        val name = AgentToolInput.string(input, "object")
        val limit = AgentToolInput.limit(input)
```
with
```kotlin
    private suspend fun queryRecords(input: Map<String, Any?>): Pair<Any, String> {
        val name = AgentToolInput.string(input, "object")
        val limit = AgentToolInput.limit(input)
        // bbox goes through the installed query contributors (gis), like on the record api. without
        // gis it stays a field filter and gets that api's 400.
        val spatial = queries.parse(AgentToolInput.bbox(input)?.let { mapOf("bbox" to it) } ?: emptyMap())
```
and, in the same function, replace
```kotlin
                    filters = AgentToolInput.filters(input),
                    bbox = AgentToolInput.bbox(input)
```
with
```kotlin
                    filters = AgentToolInput.filters(input) + spatial.filters,
                    criteria = spatial.criteria
```
5. Replace `relationships.relatedRecords(` with `related.relatedRecords(`.
6. Replace `workflows.transitionsOf(name, id)` with `transitions.transitionsOf(name, id)`.
7. Replace `"hasGeometry" to geometries.values.any { it != null }` with `"hasGeometry" to sections[GEOMETRIES].orEmpty().values.any { it != null }` and add at the top level of the file (after the imports):

```kotlin
// the payload section chawpi-gis fills. absent without gis, so a record simply has no geometry.
private const val GEOMETRIES = "geometries"
```

Run: `./gradlew :chawpi-agent:test --tests 'chawpi.agent.AgentToolInputTest' --tests 'chawpi.agent.AgentToolCatalogTest'`
Expected: PASS. If compilation fails on a core symbol, check it with `grep -rn "fun <name>\|class <name>" backend/chawpi-core/src/main/kotlin` and fix the import only.

- [ ] **Step 5: Write the failing gate test**

`backend/chawpi-agent/src/test/kotlin/chawpi/agent/EmbabelGateTest.kt`:

```kotlin
package chawpi.agent

import chawpi.agent.autoconfigure.EmbabelGate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment

class EmbabelGateTest {
    private fun gated(vararg properties: Pair<String, String>): MockEnvironment {
        val environment = MockEnvironment()
        properties.forEach { (key, value) -> environment.setProperty(key, value) }
        EmbabelGate().postProcessEnvironment(environment, SpringApplication())
        return environment
    }

    private fun MockEnvironment.excluded(): List<String> = getProperty("spring.autoconfigure.exclude").orEmpty().split(",").filter { it.isNotBlank() }

    @Test
    fun `no key keeps embabel out, so the platform still boots`() {
        assertThat(gated().excluded()).containsAll(EmbabelGate.EXCLUDED)
    }

    @Test
    fun `a key lets embabel in, whichever way it came`() {
        assertThat(gated("ANTHROPIC_API_KEY" to "k").excluded()).isEmpty()
        assertThat(gated("chawpi.agent.api-key" to "k").excluded()).isEmpty()
        assertThat(gated("embabel.agent.platform.models.anthropic.api-key" to "k").excluded()).isEmpty()
    }

    @Test
    fun `switched off, embabel stays out even with a key`() {
        assertThat(gated("ANTHROPIC_API_KEY" to "k", "chawpi.agent.enabled" to "false").excluded()).containsAll(EmbabelGate.EXCLUDED)
    }

    @Test
    fun `the app's own exclusions survive`() {
        assertThat(gated("spring.autoconfigure.exclude" to "com.acme.FooAutoConfiguration").excluded())
            .contains("com.acme.FooAutoConfiguration")
            .containsAll(EmbabelGate.EXCLUDED)
    }

    // the original had these in application.yml; a library ships none (M8)
    @Test
    fun `the key and the model get defaults the app can override`() {
        assertThat(gated("ANTHROPIC_API_KEY" to "k").getProperty("chawpi.agent.api-key")).isEqualTo("k")
        assertThat(gated().getProperty("embabel.models.default-llm")).isEqualTo("claude-haiku-4-5")
        assertThat(gated("chawpi.agent.model" to "claude-opus-4-8").getProperty("embabel.models.default-llm")).isEqualTo("claude-opus-4-8")
        assertThat(gated("embabel.models.default-llm" to "mine").getProperty("embabel.models.default-llm")).isEqualTo("mine")
    }
}
```

Run: `./gradlew :chawpi-agent:test --tests 'chawpi.agent.EmbabelGateTest'`
Expected: FAIL, `Unresolved reference 'EmbabelGate'`.

- [ ] **Step 6: Write the gate and register it (M11)**

`backend/chawpi-agent/src/main/kotlin/chawpi/agent/autoconfigure/EmbabelGate.kt`:

```kotlin
package chawpi.agent.autoconfigure

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment
import org.springframework.core.env.MapPropertySource

/**
 * Embabel's platform refuses to start without a model: no key, no context, no app. The app has to
 * boot without one — CI runs with no key, and an app that does not want the assistant is still an
 * app. So with no key (or with the module switched off) Embabel's auto-configuration is kept out
 * of the context, and the assistant reports itself unavailable.
 *
 * It also adds the two defaults the original kept in its application.yml, last, so the app wins.
 */
class EmbabelGate : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication
    ) {
        environment.propertySources.addLast(MapPropertySource(DEFAULTS_SOURCE, DEFAULTS))
        val enabled = environment.getProperty("chawpi.agent.enabled", Boolean::class.java, true)
        if (enabled && anthropicKeyOf(environment).isNotBlank()) return
        // merged, not replaced: the app may exclude auto-configs of its own
        val excluded =
            (environment.getProperty(EXCLUDE).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() } + EXCLUDED).distinct()
        environment.propertySources.addFirst(MapPropertySource(SOURCE_NAME, mapOf(EXCLUDE to excluded.joinToString(","))))
    }

    companion object {
        const val SOURCE_NAME = "chawpi-embabel-gate"
        const val DEFAULTS_SOURCE = "chawpi-agent-defaults"
        private const val EXCLUDE = "spring.autoconfigure.exclude"

        val DEFAULTS: Map<String, Any> =
            mapOf(
                "chawpi.agent.api-key" to "\${ANTHROPIC_API_KEY:}",
                // without it embabel looks for gpt-4.1-mini and the context fails to start
                "embabel.models.default-llm" to "\${chawpi.agent.model:claude-haiku-4-5}"
            )

        // every embabel autoconfiguration. excluding only the model one moves the failure, it does
        // not avoid it: the platform itself asserts that a model exists.
        val EXCLUDED =
            listOf(
                "com.embabel.agent.autoconfigure.models.anthropic.AgentAnthropicAutoConfiguration",
                "com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration",
                "com.embabel.agent.autoconfigure.platform.cache.AgentCacheProviderAutoConfiguration",
                "com.embabel.agent.autoconfigure.platform.cache.CacheSnapshotStoreAutoConfiguration"
            )

        /** The key can arrive as the env var Embabel documents, its own property, or ours. */
        fun anthropicKeyOf(environment: Environment): String =
            sequenceOf(
                "ANTHROPIC_API_KEY",
                "embabel.agent.platform.models.anthropic.api-key",
                "chawpi.agent.api-key"
            ).mapNotNull { environment.getProperty(it) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
    }
}
```

`backend/chawpi-agent/src/main/resources/META-INF/spring.factories` (Boot 4.1 key):

```
org.springframework.boot.EnvironmentPostProcessor=\
  chawpi.agent.autoconfigure.EmbabelGate
```

Run: `./gradlew :chawpi-agent:test --tests 'chawpi.agent.EmbabelGateTest'`
Expected: PASS (5 tests).

- [ ] **Step 7: Write the failing auto-config test**

`backend/chawpi-agent/src/test/kotlin/chawpi/agent/ChawpiAgentAutoConfigurationTest.kt`:

```kotlin
package chawpi.agent

import chawpi.agent.autoconfigure.ChawpiAgentAutoConfiguration
import chawpi.test.ChawpiContextRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.test.util.ReflectionTestUtils
import java.util.UUID

class ChawpiAgentAutoConfigurationTest {
    // no embabel auto-config here: exactly the no-key case, where the gate keeps it out
    private val runner = ChawpiContextRunner.core().withConfiguration(AutoConfigurations.of(ChawpiAgentAutoConfiguration::class.java))

    @Test
    fun `the agent wires without embabel's platform and says it is unavailable`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(AgentController::class.java)
            assertThat(context).hasSingleBean(ChawpiAgent::class.java)
            assertThat(context.getBean(AgentService::class.java).status().enabled).isFalse()
        }
    }

    @Test
    fun `a key makes it available`() {
        runner.withPropertyValues("chawpi.agent.api-key=k").run { context ->
            assertThat(context.getBean(AgentService::class.java).status().enabled).isTrue()
        }
    }

    @Test
    fun `without a workflow module, transitions come from the null port`() {
        runner.run { context ->
            assertThat(ReflectionTestUtils.getField(context.getBean(AgentTools::class.java), "transitions")).isInstanceOf(NoRecordTransitions::class.java)
        }
    }

    @Test
    fun `a transitions port next to it is used`() {
        val port = RecordTransitions { _: String, _: UUID -> listOf(AgentTransition("finish", "Finish", "done", "Done", true)) }
        runner.withBean(RecordTransitions::class.java, { port }).run { context ->
            assertThat(ReflectionTestUtils.getField(context.getBean(AgentTools::class.java), "transitions")).isSameAs(port)
        }
    }

    @Test
    fun `switched off, no agent route exists`() {
        runner.withPropertyValues("chawpi.agent.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(AgentService::class.java)
            assertThat(context).doesNotHaveBean(AgentController::class.java)
        }
    }

    @Test
    fun `the auto-config and the gate are registered`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("chawpi.agent.autoconfigure.ChawpiAgentAutoConfiguration")
        // name check only: instantiating every EPP on the classpath throws on Boot's own ones,
        // which need constructor args a plain loader can't supply.
        val registered = javaClass.classLoader.getResources("META-INF/spring.factories").toList().map { it.readText() }
        assertThat(registered).anyMatch {
            it.contains("org.springframework.boot.EnvironmentPostProcessor") && it.contains("chawpi.agent.autoconfigure.EmbabelGate")
        }
    }
}
```

Run: `./gradlew :chawpi-agent:test --tests 'chawpi.agent.ChawpiAgentAutoConfigurationTest'`
Expected: FAIL, `Unresolved reference 'ChawpiAgentAutoConfiguration'`.

- [ ] **Step 8: Auto-config and imports file**

`backend/chawpi-agent/src/main/kotlin/chawpi/agent/autoconfigure/ChawpiAgentAutoConfiguration.kt`:

```kotlin
package chawpi.agent.autoconfigure

import chawpi.agent.AgentController
import chawpi.agent.AgentProperties
import chawpi.agent.AgentService
import chawpi.agent.AgentTools
import chawpi.agent.ChawpiAgent
import chawpi.agent.NoRecordTransitions
import chawpi.agent.RecordTransitions
import chawpi.core.audit.AuditQueryService
import chawpi.core.autoconfigure.ChawpiDataAutoConfiguration
import chawpi.core.data.RecordQueryParser
import chawpi.core.data.RecordService
import chawpi.core.data.RelatedRecordService
import chawpi.core.identity.CurrentUser
import chawpi.core.metadata.MetadataMapper
import chawpi.core.metadata.MetadataService
import chawpi.core.metadata.RelationshipService
import com.embabel.agent.core.AgentPlatform
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import tools.jackson.databind.json.JsonMapper

// the assistant. the tools only ever call services, so tenancy and permissions are the caller's.
// embabel finds ChawpiAgent by its @Agent annotation through a bean post-processor: a @Bean is enough.
@AutoConfiguration(after = [ChawpiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "chawpi.agent", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AgentProperties::class)
class ChawpiAgentAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun agentTools(
        metadata: MetadataService,
        mapper: MetadataMapper,
        records: RecordService,
        relationships: RelationshipService,
        related: RelatedRecordService,
        transitions: ObjectProvider<RecordTransitions>,
        queries: RecordQueryParser,
        audit: AuditQueryService,
        currentUser: CurrentUser,
        json: JsonMapper
    ): AgentTools =
        AgentTools(
            metadata,
            mapper,
            records,
            relationships,
            related,
            transitions.getIfAvailable { NoRecordTransitions() },
            queries,
            audit,
            currentUser,
            json
        )

    @Bean
    @ConditionalOnMissingBean
    fun chawpiAgent(
        properties: AgentProperties,
        tools: AgentTools
    ): ChawpiAgent = ChawpiAgent(properties, tools)

    // the platform is absent whenever EmbabelGate kept embabel out (no key, or switched off)
    @Bean
    @ConditionalOnMissingBean
    fun agentService(
        properties: AgentProperties,
        currentUser: CurrentUser,
        platform: ObjectProvider<AgentPlatform>
    ): AgentService = AgentService(properties, currentUser, platform)

    @Bean
    @ConditionalOnMissingBean
    fun agentController(agent: AgentService): AgentController = AgentController(agent)
}
```

`backend/chawpi-agent/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
chawpi.agent.autoconfigure.ChawpiAgentAutoConfiguration
```

Run: `./gradlew :chawpi-agent:test`
Expected: PASS (every suite: `AgentToolCatalogTest`, `AgentToolInputTest`, `EmbabelGateTest`, `ChawpiAgentAutoConfigurationTest`).

- [ ] **Step 9: Format, nothing named after the original, leave uncommitted**

```bash
./gradlew :chawpi-agent:ktlintFormat
./gradlew :chawpi-agent:ktlintCheck
./gradlew :chawpi-agent:jar
grep -rn -i "sapgis" backend/chawpi-agent || echo "clean"
git status --short backend/chawpi-agent
```
Expected: ktlint passes, `clean`, `?? backend/chawpi-agent/`.

### Task 9: `chawpi-pages` (Wave 2 — needs Task 3)

Pages owns the page-component registry (M1, M10). MAP and WORKFLOW leave this module and come back as providers in Tasks 12 and 13; HISTORY stays here as a built-in.

**Files:**
- Create: `backend/chawpi-pages/build.gradle.kts`
- Create (ported from `$SRC/pages/`): `backend/chawpi-pages/src/main/kotlin/chawpi/pages/{Page,PageController,PageRepository,PageService,PageTemplate}.kt`
- Create (new): `backend/chawpi-pages/src/main/kotlin/chawpi/pages/{PageComponentProvider,PageGeneration,PageMetadataController}.kt`
- Create: `backend/chawpi-pages/src/main/kotlin/chawpi/pages/autoconfigure/{ChawpiPagesProperties,ChawpiPagesAutoConfiguration}.kt`
- Create: `backend/chawpi-pages/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `backend/chawpi-pages/src/main/resources/db/chawpi/pages/V1__pages.sql`
- Test (ported): `backend/chawpi-pages/src/test/kotlin/chawpi/pages/PageTemplateTest.kt`
- Test (new): `backend/chawpi-pages/src/test/kotlin/chawpi/pages/{PageComponentTypesTest,PageComponentJsonTest,PageGenerationTest,PagesMigrationSqlTest,ChawpiPagesAutoConfigurationTest}.kt`

**Interfaces:**
- Consumes: core `WorkflowStates` (`transitionNames`, for ACTION), `MetadataService`, `RelationshipService.forObject`, `CurrentUser`, `ObjectDefinition`, `ValidationException`, `ChawpiSchemas`, `ModuleMigration`; chawpi-forms (Task 3) `FormService.storedNames(objectId: UUID): Set<String>`, `chawpi.forms.autoconfigure.ChawpiFormsAutoConfiguration`; `ChawpiContextRunner.core()` (Task 1).
- Produces (Tasks 12 and 13 implement these):
  - `class chawpi.pages.ComponentType(name: String)` with `container`, `placeable`, companion constants `PAGE, REGION, TABS, TAB, SECTION, FORM, DYNAMIC_FORM, FIELD, RELATED_LIST, TEXT, HISTORY, ACTION`, `BUILT_IN: List<ComponentType>`, `fun of(name: String): ComponentType` (JSON creator).
  - `interface chawpi.pages.PageComponentProvider { val type: ComponentType; fun check(component: PageComponentRequest, definition: ObjectDefinition) = Unit; suspend fun generated(definition: ObjectDefinition): GeneratedComponent? = null }`.
  - `data class chawpi.pages.GeneratedComponent(component: PageComponent, tab: String? = null)` — `tab == null` joins the DETAILS tab after the form; otherwise its own tab titled `tab`, after DETAILS and before RELATED/HISTORY.
  - `class chawpi.pages.PageComponentTypes(providers: List<PageComponentProvider>)` with `types`, `parse(raw)`, `provider(type)`.
  - `data class PageComponent(type: ComponentType, column, title, layout, children, region, relationship, fields, form, field, visible, editable, geometry, content, action, transition, target, url, style)` and `data class PageComponentRequest(type: String, …, geometry: String?, …)` (unchanged from the original apart from `type`).
  - `suspend fun chawpi.pages.generatedTabs(definition, providers, related): List<PageComponent>`.
  - `PageService(workflows, pages, metadata, relationships, forms, currentUser, componentTypes)`, controllers (`/api/pages…`, `/api/objects/{object}/pages/{kind}`, `/api/metadata/page-templates`, `/api/metadata/objects/{object}/pages`), `chawpi.pages.autoconfigure.ChawpiPagesAutoConfiguration`, migration `ModuleMigration("pages", "classpath:db/chawpi/pages", 100)`.

- [ ] **Step 1: Module build file and folders**

```bash
cd /Users/jorge/IdeaProjects/chawpi
mkdir -p backend/chawpi-pages/src/main/kotlin/chawpi/pages/autoconfigure backend/chawpi-pages/src/main/resources/META-INF/spring \
  backend/chawpi-pages/src/main/resources/db/chawpi/pages backend/chawpi-pages/src/test/kotlin/chawpi/pages
```

`backend/chawpi-pages/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.spring-module")
    id("chawpi.publishing")
    id("chawpi.integration-test")
}

description = "Chawpi pages: record pages as a component tree on a template"

dependencies {
    api(project(":chawpi-core"))
    // a FORM component names a stored form: the one hard module-to-module edge (spec module graph)
    api(project(":chawpi-forms"))

    testImplementation(project(":chawpi-test"))
}
```

- [ ] **Step 2: Copy the original's classes and test, run the port rules**

```bash
SRC=/Users/jorge/IdeaProjects/sapgis/backend/src/main/kotlin/com/sapgis
TSRC=/Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis
DST=backend/chawpi-pages/src/main/kotlin/chawpi/pages
TDST=backend/chawpi-pages/src/test/kotlin/chawpi/pages
for f in Page PageController PageRepository PageService PageTemplate; do cp $SRC/pages/$f.kt $DST/; done
cp $TSRC/pages/PageTemplateTest.kt $TDST/
port() {
  sed -i '' -E \
    -e 's/^package com\.sapgis\.([a-z]+)$/package chawpi.\1/' \
    -e 's/com\.sapgis\.(views|forms|pages|workflow|automation|documents|gis|agent)\./chawpi.\1./g' \
    -e 's/com\.sapgis\.data\.ObjectSchemaManager/chawpi.core.metadata.ObjectSchemaManager/g' \
    -e 's/com\.sapgis\./chawpi.core./g' \
    -e 's/SapgisException/ChawpiException/g' \
    -e 's/sapgis\.geoserver/chawpi.gis.geoserver/g' \
    -e 's/sapgis\.(agent|automation)/chawpi.\1/g' \
    "$@"
  sed -i '' -E -e '/^[[:space:]]*\/\//!s/([^a-zA-Z_.])sapgis\.([a-z_]+)/\1${schemas.metadata}.\2/g' "$@"
  sed -i '' -e 's/SAPGIS/Chawpi/g' -e 's/Sapgis/Chawpi/g' -e 's/sapgis/chawpi/g' "$@"
}
port $DST/*.kt $TDST/*.kt
# geometry is gis's business now; parsing needs the installed providers
sed -i '' -e '/^import chawpi\.core\.metadata\.geometryFields$/d' -e 's/ComponentType\.parse(/componentTypes.parse(/g' $DST/PageService.kt
sed -i '' -e '/^    const val MAP = "MAP"$/d' $DST/Page.kt
grep -n 'schemas.metadata' $DST/*.kt | cut -c1-100
```
Expected: `${schemas.metadata}.pages` only in `PageRepository.kt`.

- [ ] **Step 3: Repository — schema, and its own nullable bind (core's is internal)**

In `$DST/PageRepository.kt`:
1. Delete the line `import chawpi.core.metadata.bindNullable`; add `import chawpi.core.platform.ChawpiSchemas` (sorted).
2. Replace
```kotlin
    private val objectMapper: ObjectMapper
) {
```
with
```kotlin
    private val objectMapper: ObjectMapper,
    private val schemas: ChawpiSchemas
) {
```
3. Append at the end of the file:
```kotlin

// r2dbc rejects bind(null); a nullable bind must name its type. core has the same helper, internal.
private inline fun <reified T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: T?
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, T::class.java) else bind(name, value)
```

- [ ] **Step 4: Write the failing registry and JSON tests**

`backend/chawpi-pages/src/test/kotlin/chawpi/pages/PageComponentTypesTest.kt`:

```kotlin
package chawpi.pages

import chawpi.core.common.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PageComponentTypesTest {
    private val map = object : PageComponentProvider {
        override val type = ComponentType("MAP")
    }

    @Test
    fun `built-ins parse in any case`() {
        assertThat(PageComponentTypes(emptyList()).parse("history")).isEqualTo(ComponentType.HISTORY)
    }

    @Test
    fun `a module type is unknown until its module is installed`() {
        val error = assertThrows<ValidationException> { PageComponentTypes(emptyList()).parse("MAP") }
        assertThat(error.message).isEqualTo("Unknown component 'MAP'")
        assertThat(error.violations.single().field).isEqualTo("components")
        assertThat(error.violations.single().message)
            .isEqualTo("must be one of PAGE, REGION, TABS, TAB, SECTION, FORM, DYNAMIC_FORM, FIELD, RELATED_LIST, TEXT, HISTORY, ACTION")
    }

    @Test
    fun `an installed provider's type parses and finds its provider`() {
        val types = PageComponentTypes(listOf(map))
        assertThat(types.parse("map")).isEqualTo(ComponentType("MAP"))
        assertThat(types.provider(ComponentType("MAP"))).isSameAs(map)
        assertThat(types.provider(ComponentType.FORM)).isNull()
        assertThat(types.types.last()).isEqualTo(ComponentType("MAP"))
    }

    @Test
    fun `a provider cannot take a built-in's name`() {
        val history = object : PageComponentProvider {
            override val type = ComponentType.HISTORY
        }
        assertThatThrownBy { PageComponentTypes(listOf(history)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("page component type declared twice: HISTORY")
    }

    @Test
    fun `module components are placeable leaves`() {
        assertThat(ComponentType("MAP").container).isFalse()
        assertThat(ComponentType("MAP").placeable).isTrue()
        assertThat(ComponentType.DYNAMIC_FORM.container).isTrue()
        assertThat(ComponentType.REGION.placeable).isFalse()
    }
}
```

`backend/chawpi-pages/src/test/kotlin/chawpi/pages/PageComponentJsonTest.kt`:

```kotlin
package chawpi.pages

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class PageComponentJsonTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    // the wire and jsonb shape the enum had: a bare name
    @Test
    fun `a component type is written as its bare name`() {
        val json = mapper.writeValueAsString(PageComponent(type = ComponentType.TABS, children = listOf(PageComponent(type = ComponentType("MAP")))))
        assertThat(json).contains("\"type\":\"TABS\"").contains("\"type\":\"MAP\"")
    }

    // a page stored while gis/workflow were installed must still load after they are removed
    @Test
    fun `a stored page naming a module type still reads back`() {
        val stored = """{"page":{"type":"PAGE","children":[{"type":"WORKFLOW"},{"type":"map"}]}}"""
        val definition = mapper.readValue(stored, PageDefinition::class.java)
        assertThat(definition.page.type).isEqualTo(ComponentType.PAGE)
        assertThat(definition.page.children.map { it.type }).containsExactly(ComponentType("WORKFLOW"), ComponentType("MAP"))
    }
}
```

Run: `./gradlew :chawpi-pages:test --tests 'chawpi.pages.PageComponentTypesTest' --tests 'chawpi.pages.PageComponentJsonTest'`
Expected: FAIL, compilation errors (`Unresolved reference 'PageComponentProvider'`, `'componentTypes'`, enum constants `MAP`/`WORKFLOW`).

- [ ] **Step 5: `ComponentType` becomes an open value (M10)**

In `$DST/Page.kt` replace the whole `enum class ComponentType { … }` block — from the line `enum class ComponentType {` through its closing `}` just before `enum class ActionKind {` — with:

```kotlin
// a component's type is a name. pages knows the built-ins below; a module adds one through a
// PageComponentProvider (gis: MAP, workflow: WORKFLOW). parsing has to know what is installed, so
// it lives in PageComponentTypes. json: the bare name, exactly as the enum was written.
class ComponentType(
    @get:JsonValue val name: String
) {
    // DYNAMIC_FORM is one: its fields are children, so without this the generic leaf rule would
    // refuse the very thing it exists to hold. module types are always leaves.
    val container: Boolean
        get() = this == TABS || this == TAB || this == SECTION || this == PAGE || this == REGION || this == DYNAMIC_FORM

    // what an admin may place. the template owns the rest.
    val placeable: Boolean get() = this != PAGE && this != REGION

    override fun equals(other: Any?): Boolean = other is ComponentType && other.name == name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name

    companion object {
        // the root, and the slots the template puts in it. scaffolding: the palette never offers them.
        val PAGE = ComponentType("PAGE")
        val REGION = ComponentType("REGION")

        // containers. they hold children and draw nothing of their own.
        val TABS = ComponentType("TABS")
        val TAB = ComponentType("TAB")
        val SECTION = ComponentType("SECTION")

        val FORM = ComponentType("FORM")

        // a form the admin builds field by field, and one field placed in it. same pair shape as
        // TABS/TAB: one holds only the other, and the other lives nowhere else.
        val DYNAMIC_FORM = ComponentType("DYNAMIC_FORM")
        val FIELD = ComponentType("FIELD")

        val RELATED_LIST = ComponentType("RELATED_LIST")
        val TEXT = ComponentType("TEXT")

        // the record's audit trail
        val HISTORY = ComponentType("HISTORY")

        // a button: fires a transition, or goes somewhere
        val ACTION = ComponentType("ACTION")

        // the original's order, minus the types modules bring
        val BUILT_IN: List<ComponentType> =
            listOf(PAGE, REGION, TABS, TAB, SECTION, FORM, DYNAMIC_FORM, FIELD, RELATED_LIST, TEXT, HISTORY, ACTION)

        // stored json is read without judging it: a page may name a module that is gone. saving
        // it again goes through PageComponentTypes.parse, which does judge.
        @JvmStatic
        @JsonCreator
        fun of(name: String): ComponentType = ComponentType(name.trim().uppercase())
    }
}
```

Add `import com.fasterxml.jackson.annotation.JsonCreator` next to the existing `JsonValue` import.

- [ ] **Step 6: The provider SPI and the registry**

`backend/chawpi-pages/src/main/kotlin/chawpi/pages/PageComponentProvider.kt`:

```kotlin
package chawpi.pages

import chawpi.core.common.ValidationException
import chawpi.core.metadata.ObjectDefinition

// a component type a module brings to pages (gis: MAP, workflow: WORKFLOW). pages keeps the tree
// rules -- where it may sit, no children, which column; the provider owns what the component
// means for an object. ADR-0025.
interface PageComponentProvider {
    val type: ComponentType

    // refuses a placed component that cannot work on this object. runs after pages' tree rules.
    fun check(
        component: PageComponentRequest,
        definition: ObjectDefinition
    ) = Unit

    // what a page generated from metadata gets from this type. null: nothing for this object.
    suspend fun generated(definition: ObjectDefinition): GeneratedComponent? = null
}

// tab == null: it joins the details tab, after the form. otherwise it gets a tab of its own titled
// `tab` (a key the client translates), after details and before related and history.
data class GeneratedComponent(
    val component: PageComponent,
    val tab: String? = null
)

// every type an admin may name: pages' own first, then the modules' in bean order
class PageComponentTypes(
    val providers: List<PageComponentProvider>
) {
    val types: List<ComponentType> = ComponentType.BUILT_IN + providers.map { it.type }

    private val byType: Map<ComponentType, PageComponentProvider> = providers.associateBy { it.type }

    init {
        val twice = types.groupBy { it }.filterValues { it.size > 1 }.keys
        check(twice.isEmpty()) { "page component type declared twice: ${twice.joinToString(", ")}" }
    }

    fun parse(raw: String): ComponentType =
        types.firstOrNull { it.name == raw.uppercase() }
            ?: throw ValidationException(
                "Unknown component '$raw'",
                "components",
                "must be one of ${types.joinToString(", ") { it.name }}"
            )

    fun provider(type: ComponentType): PageComponentProvider? = byType[type]
}
```

Run: `./gradlew :chawpi-pages:test --tests 'chawpi.pages.PageComponentTypesTest' --tests 'chawpi.pages.PageComponentJsonTest'`
Expected: still FAIL to compile, now only in `PageService.kt` (`componentTypes`, `ComponentType.MAP`, `ComponentType.WORKFLOW`, `geometryFields`). Steps 7–8 fix it.

- [ ] **Step 7: Generation goes through the providers**

`backend/chawpi-pages/src/main/kotlin/chawpi/pages/PageGeneration.kt`:

```kotlin
package chawpi.pages

import chawpi.core.metadata.ObjectDefinition

// the tab strip of a page generated from metadata. one tab per thing you go looking for, each
// holding one column: details (the form, plus what modules put there), the modules' own tabs,
// related lists, and history last.
suspend fun generatedTabs(
    definition: ObjectDefinition,
    providers: List<PageComponentProvider>,
    related: List<PageComponent>
): List<PageComponent> {
    val details = mutableListOf(PageComponent(type = ComponentType.FORM))
    val moduleTabs = mutableListOf<PageComponent>()
    for (provider in providers) {
        val generated = provider.generated(definition) ?: continue
        if (generated.tab == null) {
            details += generated.component
        } else {
            moduleTabs += PageComponent(type = ComponentType.TAB, title = generated.tab, children = listOf(generated.component))
        }
    }

    val tabs = mutableListOf(PageComponent(type = ComponentType.TAB, title = GeneratedTab.DETAILS, children = details))
    tabs += moduleTabs
    if (related.isNotEmpty()) {
        tabs += PageComponent(type = ComponentType.TAB, title = GeneratedTab.RELATED, children = related)
    }
    tabs += PageComponent(type = ComponentType.TAB, title = GeneratedTab.HISTORY, children = listOf(PageComponent(type = ComponentType.HISTORY)))
    return tabs
}
```

In `$DST/PageService.kt`:

1. Replace the constructor end
```kotlin
    private val currentUser: CurrentUser
) {
```
with
```kotlin
    private val currentUser: CurrentUser,
    private val componentTypes: PageComponentTypes
) {
```
2. In `generate(...)`, replace everything from the line `        val details = mutableListOf(PageComponent(type = ComponentType.FORM))` down to and including
```kotlin
        tabs +=
            PageComponent(
                type = ComponentType.TAB,
                title = GeneratedTab.HISTORY,
                children = listOf(PageComponent(type = ComponentType.HISTORY))
            )
```
(the block that builds details, the MAP tab, related and history) with
```kotlin
        val related =
            relationships.forObject(definition.obj.name).map { side ->
                PageComponent(type = ComponentType.RELATED_LIST, title = side.label, relationship = side.relationship.name)
            }
        val tabs = generatedTabs(definition, componentTypes.providers, related)
```
The lines after it (`// one region, because a derived page …`, `val template = PageTemplate.ONE_REGION`, `val contents = … children = tabs …`) stay as they are.

- [ ] **Step 8: Validation asks the provider for module components**

In `$DST/PageService.kt`, in `check(...)`:

1. Replace
```kotlin
        val formName = component.form?.trim()?.ifBlank { null }
        when (type) {
```
with
```kotlin
        // a module's component: the tree rules above were pages', the rest is the module's
        componentTypes.provider(type)?.check(component, definition)
        val formName = component.form?.trim()?.ifBlank { null }
        when (type) {
```
2. Delete the whole `ComponentType.MAP -> { … }` branch (from `            ComponentType.MAP -> {` through its closing `            }`, the one that throws `MAP on a non-spatial object` and `Unknown geometry`), and replace
```kotlin
            // both read what the record already has; nothing to configure, nothing to check
            ComponentType.HISTORY, ComponentType.WORKFLOW -> Unit
```
with
```kotlin
            // reads what the record already has; nothing to configure, nothing to check
            ComponentType.HISTORY -> Unit
```
The `when (type)` is a statement over a non-enum now, so it needs no `else`: a module type simply matches no branch.

Run: `./gradlew :chawpi-pages:test --tests 'chawpi.pages.PageComponentTypesTest' --tests 'chawpi.pages.PageComponentJsonTest' --tests 'chawpi.pages.PageTemplateTest'`
Expected: PASS. If `PageService.kt` still names `ComponentType.MAP`, `ComponentType.WORKFLOW` or `geometryFields`, that line belongs to the MAP branch or the generation block above: remove it. If another import fails, check the core symbol with `grep -rn "fun <name>\|class <name>" backend/chawpi-core/src/main/kotlin`.

- [ ] **Step 9: Write the generation test**

`backend/chawpi-pages/src/test/kotlin/chawpi/pages/PageGenerationTest.kt`:

```kotlin
package chawpi.pages

import chawpi.core.metadata.CustomObject
import chawpi.core.metadata.ObjectDefinition
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class PageGenerationTest {
    private val definition =
        ObjectDefinition(
            CustomObject(UUID.randomUUID(), UUID.randomUUID(), "predio", "Predio", "Predios", null, true, "predio__00000000", null, null),
            emptyList()
        )

    private fun provider(
        name: String,
        generated: GeneratedComponent?
    ) = object : PageComponentProvider {
        override val type = ComponentType(name)

        override suspend fun generated(definition: ObjectDefinition) = generated
    }

    private val related = listOf(PageComponent(type = ComponentType.RELATED_LIST, title = "Lotes", relationship = "predio_lotes"))

    @Test
    fun `core only - details, related, history`() =
        runTest {
            val tabs = generatedTabs(definition, emptyList(), related)
            assertThat(tabs.map { it.title }).containsExactly("DETAILS", "RELATED", "HISTORY")
            assertThat(tabs[0].children.map { it.type }).containsExactly(ComponentType.FORM)
            assertThat(tabs[2].children.map { it.type }).containsExactly(ComponentType.HISTORY)
        }

    // the original's layout with gis and workflow: WORKFLOW beside the form, MAP in its own tab before related
    @Test
    fun `module components land where they always did`() =
        runTest {
            val workflow = provider("WORKFLOW", GeneratedComponent(PageComponent(type = ComponentType("WORKFLOW"))))
            val map = provider("MAP", GeneratedComponent(PageComponent(type = ComponentType("MAP"), title = "Predio"), tab = "MAP"))
            val tabs = generatedTabs(definition, listOf(workflow, map), related)
            assertThat(tabs.map { it.title }).containsExactly("DETAILS", "MAP", "RELATED", "HISTORY")
            assertThat(tabs[0].children.map { it.type }).containsExactly(ComponentType.FORM, ComponentType("WORKFLOW"))
            assertThat(tabs[1].children.single().title).isEqualTo("Predio")
        }

    @Test
    fun `a provider with nothing for this object adds nothing, and no relationship means no related tab`() =
        runTest {
            val tabs = generatedTabs(definition, listOf(provider("MAP", null)), emptyList())
            assertThat(tabs.map { it.title }).containsExactly("DETAILS", "HISTORY")
        }
}
```

Run: `./gradlew :chawpi-pages:test --tests 'chawpi.pages.PageGenerationTest'`
Expected: PASS (3 tests).

- [ ] **Step 10: The metadata route (the original had it in `ObjectMetadataController`)**

`backend/chawpi-pages/src/main/kotlin/chawpi/pages/PageMetadataController.kt`:

```kotlin
package chawpi.pages

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// same url as before the split: core no longer knows pages exist, so pages answers it itself.
// only stored pages: the generated default lives behind /api/objects/{object}/pages/{kind}
@RestController
@RequestMapping("/api/metadata/objects")
class PageMetadataController(
    private val pages: PageService
) {
    @GetMapping("/{object}/pages")
    suspend fun pages(
        @PathVariable("object") name: String
    ): List<PageResponse> = pages.forObject(name).map { it.toResponse() }
}
```

- [ ] **Step 11: Write the failing migration test, then the migration (the original's V1 `pages` + V4 + V11, final shape)**

`backend/chawpi-pages/src/test/kotlin/chawpi/pages/PagesMigrationSqlTest.kt`:

```kotlin
package chawpi.pages

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PagesMigrationSqlTest {
    private val sql = javaClass.getResource("/db/chawpi/pages/V1__pages.sql")!!.readText()

    @Test
    fun `pages are tenant scoped, one per object and kind, on a template`() {
        assertThat(sql)
            .contains("CREATE TABLE \${metadataSchema}.pages (")
            .contains("object_id       uuid REFERENCES \${metadataSchema}.custom_objects (id) ON DELETE CASCADE,")
            .contains("template        text NOT NULL DEFAULT 'one-region',")
            .contains("CONSTRAINT pages_name_unique_per_org UNIQUE (organization_id, name)")
            .contains("CONSTRAINT pages_kind_valid CHECK (kind IN ('RECORD_DETAIL'))")
            .contains("CONSTRAINT pages_kind_unique_per_object UNIQUE (object_id, kind)")
            .doesNotContain("layout")
            .doesNotContainIgnoringCase("sapgis")
    }
}
```

Run: `./gradlew :chawpi-pages:test --tests 'chawpi.pages.PagesMigrationSqlTest'` → FAIL (`NullPointerException`).

`backend/chawpi-pages/src/main/resources/db/chawpi/pages/V1__pages.sql`:

```sql
-- chawpi-pages: record pages. the original's V1 + V4 + V11 rebaselined (ADR-0026): final shape
-- only. a page's arrangement is its template column; the V10 tree rewrite and V11 delete were
-- data steps with nothing to do on a clean start.

CREATE TABLE ${metadataSchema}.pages (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    object_id       uuid REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    name            text NOT NULL,
    label           text NOT NULL,
    definition      jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    kind            text NOT NULL DEFAULT 'RECORD_DETAIL',
    -- no CHECK on template: the catalogue is code, and a CHECK would make adding one a migration
    template        text NOT NULL DEFAULT 'one-region',
    CONSTRAINT pages_name_unique_per_org UNIQUE (organization_id, name),
    CONSTRAINT pages_kind_valid CHECK (kind IN ('RECORD_DETAIL')),
    CONSTRAINT pages_kind_unique_per_object UNIQUE (object_id, kind)
);
```

Run the test again → PASS.

- [ ] **Step 12: Write the failing auto-config test**

`backend/chawpi-pages/src/test/kotlin/chawpi/pages/ChawpiPagesAutoConfigurationTest.kt`:

```kotlin
package chawpi.pages

import chawpi.core.platform.ModuleMigration
import chawpi.forms.autoconfigure.ChawpiFormsAutoConfiguration
import chawpi.pages.autoconfigure.ChawpiPagesAutoConfiguration
import chawpi.test.ChawpiContextRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates

class ChawpiPagesAutoConfigurationTest {
    private val runner =
        ChawpiContextRunner
            .core()
            .withConfiguration(AutoConfigurations.of(ChawpiFormsAutoConfiguration::class.java, ChawpiPagesAutoConfiguration::class.java))

    private val map = object : PageComponentProvider {
        override val type = ComponentType("MAP")
    }

    @Test
    fun `pages wires on core and forms, with only its built-in components`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(PageService::class.java)
            assertThat(context).hasSingleBean(PageController::class.java)
            assertThat(context).hasSingleBean(ObjectPageController::class.java)
            assertThat(context).hasSingleBean(PageTemplateController::class.java)
            assertThat(context).hasSingleBean(PageMetadataController::class.java)
            assertThat(context.getBean(PageComponentTypes::class.java).types).isEqualTo(ComponentType.BUILT_IN)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactlyInAnyOrder("core", "forms", "pages")
        }
    }

    @Test
    fun `a module's provider joins the registry`() {
        runner.withBean("mapComponent", PageComponentProvider::class.java, { map }).run { context ->
            assertThat(context.getBean(PageComponentTypes::class.java).provider(ComponentType("MAP"))).isSameAs(map)
        }
    }

    // pages cannot work without forms: it backs off instead of failing the boot
    @Test
    fun `with forms switched off, pages backs off too`() {
        runner.withPropertyValues("chawpi.forms.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(PageService::class.java)
        }
    }

    @Test
    fun `switched off, core and forms boot without any pages bean`() {
        runner.withPropertyValues("chawpi.pages.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(PageService::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactlyInAnyOrder("core", "forms")
        }
    }

    @Test
    fun `the imports file registers the auto-config`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("chawpi.pages.autoconfigure.ChawpiPagesAutoConfiguration")
    }
}
```

Run: `./gradlew :chawpi-pages:test --tests 'chawpi.pages.ChawpiPagesAutoConfigurationTest'`
Expected: FAIL, `Unresolved reference 'ChawpiPagesAutoConfiguration'`.

- [ ] **Step 13: Properties, auto-config and imports file**

`backend/chawpi-pages/src/main/kotlin/chawpi/pages/autoconfigure/ChawpiPagesProperties.kt`:

```kotlin
package chawpi.pages.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("chawpi.pages")
data class ChawpiPagesProperties(
    // false: no pages beans, routes or migration
    val enabled: Boolean = true
)
```

`backend/chawpi-pages/src/main/kotlin/chawpi/pages/autoconfigure/ChawpiPagesAutoConfiguration.kt`:

```kotlin
package chawpi.pages.autoconfigure

import chawpi.core.autoconfigure.ChawpiDataAutoConfiguration
import chawpi.core.data.WorkflowStates
import chawpi.core.identity.CurrentUser
import chawpi.core.metadata.MetadataService
import chawpi.core.metadata.RelationshipService
import chawpi.core.platform.ChawpiSchemas
import chawpi.core.platform.ModuleMigration
import chawpi.forms.FormService
import chawpi.forms.autoconfigure.ChawpiFormsAutoConfiguration
import chawpi.pages.ObjectPageController
import chawpi.pages.PageComponentProvider
import chawpi.pages.PageComponentTypes
import chawpi.pages.PageController
import chawpi.pages.PageMetadataController
import chawpi.pages.PageRepository
import chawpi.pages.PageService
import chawpi.pages.PageTemplateController
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper

// record pages. after forms, and only with its FormService: a FORM component names a stored form.
// module components (MAP, WORKFLOW) arrive as PageComponentProvider beans, found through a provider.
@AutoConfiguration(after = [ChawpiDataAutoConfiguration::class, ChawpiFormsAutoConfiguration::class])
@ConditionalOnProperty(prefix = "chawpi.pages", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(FormService::class)
@EnableConfigurationProperties(ChawpiPagesProperties::class)
class ChawpiPagesAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would always make it back off
    @Bean
    fun chawpiPagesMigration(): ModuleMigration = ModuleMigration("pages", "classpath:db/chawpi/pages", ModuleMigration.MODULE_ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun pageComponentTypes(providers: ObjectProvider<PageComponentProvider>): PageComponentTypes = PageComponentTypes(providers.orderedStream().toList())

    @Bean
    @ConditionalOnMissingBean
    fun pageRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: ChawpiSchemas
    ): PageRepository = PageRepository(db, objectMapper, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun pageService(
        workflows: WorkflowStates,
        pages: PageRepository,
        metadata: MetadataService,
        relationships: RelationshipService,
        forms: FormService,
        currentUser: CurrentUser,
        componentTypes: PageComponentTypes
    ): PageService = PageService(workflows, pages, metadata, relationships, forms, currentUser, componentTypes)

    @Bean
    @ConditionalOnMissingBean
    fun pageController(pages: PageService): PageController = PageController(pages)

    @Bean
    @ConditionalOnMissingBean
    fun objectPageController(pages: PageService): ObjectPageController = ObjectPageController(pages)

    @Bean
    @ConditionalOnMissingBean
    fun pageTemplateController(): PageTemplateController = PageTemplateController()

    @Bean
    @ConditionalOnMissingBean
    fun pageMetadataController(pages: PageService): PageMetadataController = PageMetadataController(pages)
}
```

`backend/chawpi-pages/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
chawpi.pages.autoconfigure.ChawpiPagesAutoConfiguration
```

Run: `./gradlew :chawpi-pages:test`
Expected: PASS (every suite: `PageTemplateTest`, `PageComponentTypesTest`, `PageComponentJsonTest`, `PageGenerationTest`, `PagesMigrationSqlTest`, `ChawpiPagesAutoConfigurationTest`).

- [ ] **Step 14: Format, nothing named after the original or geometry-aware, leave uncommitted**

```bash
./gradlew :chawpi-pages:ktlintFormat
./gradlew :chawpi-pages:ktlintCheck
./gradlew :chawpi-pages:jar :chawpi-forms:jar
grep -rn -i "sapgis" backend/chawpi-pages || echo "clean"
grep -rn "geometryFields\|ComponentType\.MAP\|ComponentType\.WORKFLOW" backend/chawpi-pages/src/main || echo "no module types"
git status --short backend/chawpi-pages
```
Expected: ktlint passes, `clean`, `no module types` (the `geometry` property of `PageComponent`/`PageComponentRequest` stays: it is part of the page JSON), `?? backend/chawpi-pages/`.

### Task 10: documents → automation `DocumentIssuer` adapter (Wave 2 — needs Tasks 5 and 6)

**Files:**
- Modify: `backend/chawpi-documents/build.gradle.kts` (two dependency lines)
- Create (from the original's `$SRC/documents/DocumentsAdapter.kt`): `backend/chawpi-documents/src/main/kotlin/chawpi/documents/DocumentIssuerAdapter.kt`
- Create: `backend/chawpi-documents/src/main/kotlin/chawpi/documents/autoconfigure/ChawpiDocumentsAutomationAutoConfiguration.kt`
- Modify: `backend/chawpi-documents/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (append one line)
- Test: `backend/chawpi-documents/src/test/kotlin/chawpi/documents/{DocumentIssuerAdapterTest,ChawpiDocumentsAutomationAutoConfigurationTest}.kt`

**Interfaces:**
- Consumes: Task 5 `chawpi.automation.DocumentIssuer`, `chawpi.automation.AutomationService`/`AutomationRunner` (private field `documents`), `chawpi.automation.autoconfigure.ChawpiAutomationAutoConfiguration`; Task 6 `DocumentTypeRepository.findByName(objectId, name): DocumentType?`, `DocumentService.issue(organizationId, objectName, recordId, typeName, issuedBy: UUID?, issuedByEmail: String?): Document`, `chawpi.documents.autoconfigure.ChawpiDocumentsAutoConfiguration`; `ChawpiContextRunner.core()`.
- Produces: `class chawpi.documents.DocumentIssuerAdapter(types: DocumentTypeRepository, documents: DocumentService) : DocumentIssuer`; `chawpi.documents.autoconfigure.ChawpiDocumentsAutomationAutoConfiguration` (`@ConditionalOnClass(name = ["chawpi.automation.DocumentIssuer"])`).

- [ ] **Step 1: Compile against automation without requiring it (M2)**

In `backend/chawpi-documents/build.gradle.kts`, replace the `dependencies { }` block with:

```kotlin
dependencies {
    api(project(":chawpi-core"))
    // the automation port is implemented here, but an app without automation must not get it:
    // compileOnly, and the adapter's auto-config checks the class is there
    compileOnly(project(":chawpi-automation"))

    testImplementation(project(":chawpi-test"))
    testImplementation(project(":chawpi-automation"))
}
```

- [ ] **Step 2: Write the failing adapter test**

`backend/chawpi-documents/src/test/kotlin/chawpi/documents/DocumentIssuerAdapterTest.kt`:

```kotlin
package chawpi.documents

import chawpi.core.platform.ChawpiSchemas
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

class DocumentIssuerAdapterTest {
    private val asked = mutableListOf<String>()

    private val types =
        object : DocumentTypeRepository(mock(DatabaseClient::class.java), JsonMapper.builder().build(), ChawpiSchemas("chawpi", "app_data")) {
            override suspend fun findByName(
                objectId: UUID,
                name: String
            ): DocumentType? {
                asked += name
                return if (name == "permiso") mock(DocumentType::class.java) else null
            }
        }

    private val adapter = DocumentIssuerAdapter(types, mock(DocumentService::class.java))

    // automation stores the name as the admin typed it; types are stored trimmed and lowercase
    @Test
    fun `a type is looked up the way it is stored`() =
        runTest {
            assertThat(adapter.typeExists(UUID.randomUUID(), " Permiso ")).isTrue()
            assertThat(adapter.typeExists(UUID.randomUUID(), "licencia")).isFalse()
            assertThat(asked).containsExactly("permiso", "licencia")
        }
}
```

Run: `./gradlew :chawpi-documents:test --tests 'chawpi.documents.DocumentIssuerAdapterTest'`
Expected: FAIL, `Unresolved reference 'DocumentIssuerAdapter'`.

- [ ] **Step 3: Write the adapter**

`backend/chawpi-documents/src/main/kotlin/chawpi/documents/DocumentIssuerAdapter.kt`:

```kotlin
package chawpi.documents

import chawpi.automation.DocumentIssuer
import org.springframework.stereotype.Service
import java.util.UUID

// the documents side of automation's port. compiled against chawpi-automation, loaded only when an
// app has it (ChawpiDocumentsAutomationAutoConfiguration).
@Service
class DocumentIssuerAdapter(
    private val types: DocumentTypeRepository,
    private val documents: DocumentService
) : DocumentIssuer {
    override suspend fun typeExists(
        objectId: UUID,
        name: String
    ): Boolean = types.findByName(objectId, name.trim().lowercase()) != null

    // no issuer: a queued run has no user behind it (ADR-016), so the platform issues it
    override suspend fun issue(
        organizationId: UUID,
        objectName: String,
        recordId: UUID,
        typeName: String
    ): String = documents.issue(organizationId, objectName, recordId, typeName, null, null).number
}
```

Run: `./gradlew :chawpi-documents:test --tests 'chawpi.documents.DocumentIssuerAdapterTest'`
Expected: PASS.

- [ ] **Step 4: Write the failing wiring test**

`backend/chawpi-documents/src/test/kotlin/chawpi/documents/ChawpiDocumentsAutomationAutoConfigurationTest.kt`:

```kotlin
package chawpi.documents

import chawpi.automation.AutomationRunner
import chawpi.automation.AutomationService
import chawpi.automation.autoconfigure.ChawpiAutomationAutoConfiguration
import chawpi.documents.autoconfigure.ChawpiDocumentsAutoConfiguration
import chawpi.documents.autoconfigure.ChawpiDocumentsAutomationAutoConfiguration
import chawpi.test.ChawpiContextRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.test.util.ReflectionTestUtils

class ChawpiDocumentsAutomationAutoConfigurationTest {
    @Test
    fun `with automation installed, a GENERATE_DOCUMENT action issues through documents`() {
        ChawpiContextRunner
            .core()
            .withConfiguration(
                AutoConfigurations.of(
                    ChawpiAutomationAutoConfiguration::class.java,
                    ChawpiDocumentsAutoConfiguration::class.java,
                    ChawpiDocumentsAutomationAutoConfiguration::class.java
                )
            ).withPropertyValues("chawpi.automation.poll-interval=0s")
            .run { context ->
                assertThat(context).hasNotFailed()
                val adapter = context.getBean(DocumentIssuerAdapter::class.java)
                assertThat(ReflectionTestUtils.getField(context.getBean(AutomationService::class.java), "documents")).isSameAs(adapter)
                assertThat(ReflectionTestUtils.getField(context.getBean(AutomationRunner::class.java), "documents")).isSameAs(adapter)
            }
    }

    // an app with documents and no automation: the adapter's class would not even load
    @Test
    fun `without automation on the classpath, documents boots alone`() {
        ChawpiContextRunner
            .core()
            .withClassLoader(FilteredClassLoader("chawpi.automation"))
            .withConfiguration(AutoConfigurations.of(ChawpiDocumentsAutoConfiguration::class.java, ChawpiDocumentsAutomationAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(DocumentService::class.java)
                assertThat(context).doesNotHaveBean("documentIssuerAdapter")
            }
    }

    @Test
    fun `with documents switched off, no adapter is left behind`() {
        ChawpiContextRunner
            .core()
            .withConfiguration(AutoConfigurations.of(ChawpiDocumentsAutoConfiguration::class.java, ChawpiDocumentsAutomationAutoConfiguration::class.java))
            .withPropertyValues("chawpi.documents.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean("documentIssuerAdapter")
            }
    }

    @Test
    fun `the imports file registers both documents auto-configs`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains(
                "chawpi.documents.autoconfigure.ChawpiDocumentsAutoConfiguration",
                "chawpi.documents.autoconfigure.ChawpiDocumentsAutomationAutoConfiguration"
            )
    }
}
```

Run: `./gradlew :chawpi-documents:test --tests 'chawpi.documents.ChawpiDocumentsAutomationAutoConfigurationTest'`
Expected: FAIL, `Unresolved reference 'ChawpiDocumentsAutomationAutoConfiguration'`.

- [ ] **Step 5: The adapter's own auto-config**

`backend/chawpi-documents/src/main/kotlin/chawpi/documents/autoconfigure/ChawpiDocumentsAutomationAutoConfiguration.kt`:

```kotlin
package chawpi.documents.autoconfigure

import chawpi.automation.DocumentIssuer
import chawpi.documents.DocumentIssuerAdapter
import chawpi.documents.DocumentService
import chawpi.documents.DocumentTypeRepository
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

// documents behind automation's port, only when chawpi-automation is on the classpath. the class
// is named as a string, so this config is skipped before anything tries to load it (M2).
// automation looks the port up lazily, so no order against its auto-config is needed.
@AutoConfiguration(after = [ChawpiDocumentsAutoConfiguration::class])
@ConditionalOnClass(name = ["chawpi.automation.DocumentIssuer"])
@ConditionalOnBean(DocumentService::class)
class ChawpiDocumentsAutomationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DocumentIssuer::class)
    fun documentIssuerAdapter(
        types: DocumentTypeRepository,
        documents: DocumentService
    ): DocumentIssuerAdapter = DocumentIssuerAdapter(types, documents)
}
```

Append this line to `backend/chawpi-documents/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (the file then has two lines):

```
chawpi.documents.autoconfigure.ChawpiDocumentsAutomationAutoConfiguration
```

Run: `./gradlew :chawpi-documents:test`
Expected: PASS (all documents suites, including Task 6's).

- [ ] **Step 6: Format, leave uncommitted**

```bash
./gradlew :chawpi-documents:ktlintFormat
./gradlew :chawpi-documents:ktlintCheck
./gradlew :chawpi-documents:jar
grep -rn -i "sapgis" backend/chawpi-documents || echo "clean"
git status --short backend/chawpi-documents
```
Expected: ktlint passes, `clean`.

### Task 11: agent → workflow transitions adapter (Wave 2 — needs Tasks 4 and 8)

**Files:**
- Modify: `backend/chawpi-agent/build.gradle.kts` (two dependency lines)
- Create: `backend/chawpi-agent/src/main/kotlin/chawpi/agent/WorkflowRecordTransitions.kt`
- Create: `backend/chawpi-agent/src/main/kotlin/chawpi/agent/autoconfigure/ChawpiAgentWorkflowAutoConfiguration.kt`
- Modify: `backend/chawpi-agent/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (append one line)
- Test: `backend/chawpi-agent/src/test/kotlin/chawpi/agent/{WorkflowRecordTransitionsTest,ChawpiAgentWorkflowAutoConfigurationTest}.kt`

**Interfaces:**
- Consumes: Task 8 `RecordTransitions`, `AgentTransition`, `NoRecordTransitions`, `AgentTools` (private field `transitions`), `chawpi.agent.autoconfigure.ChawpiAgentAutoConfiguration`; Task 4 `chawpi.workflow.WorkflowService.transitionsOf(objectName: String, id: UUID): List<AvailableTransition>`, `AvailableTransition(name, label, to, toLabel, allowed, reason)`, `WorkflowRepository`, `chawpi.workflow.autoconfigure.ChawpiWorkflowAutoConfiguration`; `ChawpiContextRunner.core()`.
- Produces: `class chawpi.agent.WorkflowRecordTransitions(workflows: WorkflowService) : RecordTransitions`; `chawpi.agent.autoconfigure.ChawpiAgentWorkflowAutoConfiguration`.

- [ ] **Step 1: Compile against workflow without requiring it (M2)**

In `backend/chawpi-agent/build.gradle.kts`, replace the `dependencies { }` block with:

```kotlin
dependencies {
    api(project(":chawpi-core"))
    // AgentService takes embabel's AgentPlatform: part of the api
    api(libs.embabel.agent.starter)
    api(libs.embabel.agent.starter.anthropic)
    // available_transitions asks workflow when an app has it: compileOnly, guarded by @ConditionalOnClass
    compileOnly(project(":chawpi-workflow"))

    testImplementation(project(":chawpi-test"))
    testImplementation(project(":chawpi-workflow"))
}
```

- [ ] **Step 2: Write the failing adapter test**

`backend/chawpi-agent/src/test/kotlin/chawpi/agent/WorkflowRecordTransitionsTest.kt`:

```kotlin
package chawpi.agent

import chawpi.core.audit.AuditService
import chawpi.core.data.RecordStore
import chawpi.core.identity.AccessPolicy
import chawpi.core.identity.CurrentUser
import chawpi.core.identity.RoleDirectory
import chawpi.core.metadata.MetadataService
import chawpi.core.metadata.ObjectSchemaManager
import chawpi.workflow.AvailableTransition
import chawpi.workflow.WorkflowRepository
import chawpi.workflow.WorkflowService
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

class WorkflowRecordTransitionsTest {
    private val available =
        listOf(
            AvailableTransition("finish", "Finish", "done", "Done", true),
            AvailableTransition("reject", "Reject", "rejected", "Rejected", false, "requires role 'reviewer'")
        )

    private val workflows =
        object : WorkflowService(
            mock(RoleDirectory::class.java),
            mock(WorkflowRepository::class.java),
            mock(MetadataService::class.java),
            mock(ObjectSchemaManager::class.java),
            mock(RecordStore::class.java),
            mock(AuditService::class.java),
            mock(CurrentUser::class.java),
            mock(AccessPolicy::class.java),
            emptyList()
        ) {
            override suspend fun transitionsOf(
                objectName: String,
                id: UUID
            ): List<AvailableTransition> = available
        }

    // the tool answer must read exactly as it did when the agent called WorkflowService itself
    @Test
    fun `transitions reach the assistant with the same json`() =
        runTest {
            val mapper = JsonMapper.builder().build()
            val answered = WorkflowRecordTransitions(workflows).transitionsOf("predio", UUID.randomUUID())
            assertThat(answered.count { it.allowed }).isEqualTo(1)
            assertThat(mapper.writeValueAsString(answered)).isEqualTo(mapper.writeValueAsString(available))
        }
}
```

Run: `./gradlew :chawpi-agent:test --tests 'chawpi.agent.WorkflowRecordTransitionsTest'`
Expected: FAIL, `Unresolved reference 'WorkflowRecordTransitions'`.

- [ ] **Step 3: Write the adapter**

`backend/chawpi-agent/src/main/kotlin/chawpi/agent/WorkflowRecordTransitions.kt`:

```kotlin
package chawpi.agent

import chawpi.workflow.WorkflowService
import java.util.UUID

// the assistant's transitions port over chawpi-workflow. compiled against it, loaded only when an
// app has it (ChawpiAgentWorkflowAutoConfiguration). permissions and tenancy stay the service's.
class WorkflowRecordTransitions(
    private val workflows: WorkflowService
) : RecordTransitions {
    override suspend fun transitionsOf(
        objectName: String,
        id: UUID
    ): List<AgentTransition> =
        workflows.transitionsOf(objectName, id).map {
            AgentTransition(it.name, it.label, it.to, it.toLabel, it.allowed, it.reason)
        }
}
```

Run: `./gradlew :chawpi-agent:test --tests 'chawpi.agent.WorkflowRecordTransitionsTest'`
Expected: PASS.

- [ ] **Step 4: Write the failing wiring test**

`backend/chawpi-agent/src/test/kotlin/chawpi/agent/ChawpiAgentWorkflowAutoConfigurationTest.kt`:

```kotlin
package chawpi.agent

import chawpi.agent.autoconfigure.ChawpiAgentAutoConfiguration
import chawpi.agent.autoconfigure.ChawpiAgentWorkflowAutoConfiguration
import chawpi.test.ChawpiContextRunner
import chawpi.workflow.autoconfigure.ChawpiWorkflowAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.test.util.ReflectionTestUtils

class ChawpiAgentWorkflowAutoConfigurationTest {
    private val all =
        AutoConfigurations.of(
            ChawpiWorkflowAutoConfiguration::class.java,
            ChawpiAgentAutoConfiguration::class.java,
            ChawpiAgentWorkflowAutoConfiguration::class.java
        )

    @Test
    fun `with workflow installed, the assistant asks it`() {
        ChawpiContextRunner.core().withConfiguration(all).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(ReflectionTestUtils.getField(context.getBean(AgentTools::class.java), "transitions"))
                .isInstanceOf(WorkflowRecordTransitions::class.java)
        }
    }

    @Test
    fun `with workflow switched off, the assistant says there are none`() {
        ChawpiContextRunner.core().withConfiguration(all).withPropertyValues("chawpi.workflow.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(ReflectionTestUtils.getField(context.getBean(AgentTools::class.java), "transitions"))
                .isInstanceOf(NoRecordTransitions::class.java)
        }
    }

    @Test
    fun `without workflow on the classpath, the agent boots alone`() {
        ChawpiContextRunner
            .core()
            .withClassLoader(FilteredClassLoader("chawpi.workflow"))
            .withConfiguration(AutoConfigurations.of(ChawpiAgentAutoConfiguration::class.java, ChawpiAgentWorkflowAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean("workflowRecordTransitions")
                assertThat(ReflectionTestUtils.getField(context.getBean(AgentTools::class.java), "transitions"))
                    .isInstanceOf(NoRecordTransitions::class.java)
            }
    }

    @Test
    fun `the imports file registers both agent auto-configs`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("chawpi.agent.autoconfigure.ChawpiAgentAutoConfiguration", "chawpi.agent.autoconfigure.ChawpiAgentWorkflowAutoConfiguration")
    }
}
```

Run: `./gradlew :chawpi-agent:test --tests 'chawpi.agent.ChawpiAgentWorkflowAutoConfigurationTest'`
Expected: FAIL, `Unresolved reference 'ChawpiAgentWorkflowAutoConfiguration'`.

- [ ] **Step 5: The adapter's own auto-config**

`backend/chawpi-agent/src/main/kotlin/chawpi/agent/autoconfigure/ChawpiAgentWorkflowAutoConfiguration.kt`:

```kotlin
package chawpi.agent.autoconfigure

import chawpi.agent.RecordTransitions
import chawpi.agent.WorkflowRecordTransitions
import chawpi.workflow.WorkflowService
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

// workflow behind the assistant's transitions port, only when chawpi-workflow is on the classpath
// and switched on. names as strings: this config is skipped before anything loads a workflow class.
// the agent looks the port up lazily, so no order against the agent's own auto-config is needed.
@AutoConfiguration(afterName = ["chawpi.workflow.autoconfigure.ChawpiWorkflowAutoConfiguration"])
@ConditionalOnClass(name = ["chawpi.workflow.WorkflowService"])
@ConditionalOnBean(type = ["chawpi.workflow.WorkflowService"])
class ChawpiAgentWorkflowAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(RecordTransitions::class)
    fun workflowRecordTransitions(workflows: WorkflowService): WorkflowRecordTransitions = WorkflowRecordTransitions(workflows)
}
```

Append this line to `backend/chawpi-agent/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (the file then has two lines):

```
chawpi.agent.autoconfigure.ChawpiAgentWorkflowAutoConfiguration
```

Run: `./gradlew :chawpi-agent:test`
Expected: PASS (all agent suites, including Task 8's).

- [ ] **Step 6: Format, leave uncommitted**

```bash
./gradlew :chawpi-agent:ktlintFormat
./gradlew :chawpi-agent:ktlintCheck
./gradlew :chawpi-agent:jar
grep -rn -i "sapgis" backend/chawpi-agent || echo "clean"
git status --short backend/chawpi-agent
```
Expected: ktlint passes, `clean`.

### Task 12: gis → pages MAP component (Wave 3 — needs Tasks 7 and 9)

**Files:**
- Modify: `backend/chawpi-gis/build.gradle.kts` (two dependency lines)
- Create: `backend/chawpi-gis/src/main/kotlin/chawpi/gis/MapPageComponent.kt`
- Create: `backend/chawpi-gis/src/main/kotlin/chawpi/gis/autoconfigure/ChawpiGisPagesAutoConfiguration.kt`
- Modify: `backend/chawpi-gis/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (append one line)
- Test: `backend/chawpi-gis/src/test/kotlin/chawpi/gis/{MapPageComponentTest,ChawpiGisPagesAutoConfigurationTest}.kt`

**Interfaces:**
- Consumes: Task 9 `PageComponentProvider`, `ComponentType`, `GeneratedComponent`, `PageComponent`, `PageComponentRequest(type, …, geometry)`, `PageComponentTypes.provider`, `chawpi.pages.autoconfigure.ChawpiPagesAutoConfiguration`, `chawpi.forms.autoconfigure.ChawpiFormsAutoConfiguration`; Task 7 `ObjectDefinition.geometryFields`, `GisFixtures` (`definition`, `geometry`, `text`, `refused`), `chawpi.gis.autoconfigure.ChawpiGisAutoConfiguration`; `ChawpiContextRunner.core()`.
- Produces: `class chawpi.gis.MapPageComponent : PageComponentProvider` (`type = ComponentType("MAP")`, generated in its own tab `"MAP"`); `chawpi.gis.autoconfigure.ChawpiGisPagesAutoConfiguration`.

- [ ] **Step 1: Compile against pages without requiring it (M2)**

In `backend/chawpi-gis/build.gradle.kts`, replace the `dependencies { }` block with:

```kotlin
dependencies {
    api(project(":chawpi-core"))
    // the MAP page component, only when an app has chawpi-pages: compileOnly + @ConditionalOnClass
    compileOnly(project(":chawpi-pages"))

    testImplementation(project(":chawpi-test"))
    testImplementation(project(":chawpi-pages"))
}
```

- [ ] **Step 2: Write the failing component test**

`backend/chawpi-gis/src/test/kotlin/chawpi/gis/MapPageComponentTest.kt`:

```kotlin
package chawpi.gis

import chawpi.gis.GisFixtures.definition
import chawpi.gis.GisFixtures.geometry
import chawpi.gis.GisFixtures.refused
import chawpi.gis.GisFixtures.text
import chawpi.pages.ComponentType
import chawpi.pages.PageComponentRequest
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MapPageComponentTest {
    private val map = MapPageComponent()
    private val spatial = definition(text("codigo"), geometry("lote"), geometry("acceso", "POINT"))

    @Test
    fun `a map needs a geometry to draw`() {
        refused("MAP on a non-spatial object", "components") { map.check(PageComponentRequest(type = "MAP"), definition(text("codigo"))) }
    }

    // naming none draws them all, which is what a one-shape object wants
    @Test
    fun `a map may name one geometry, and only one the object has`() {
        map.check(PageComponentRequest(type = "MAP"), spatial)
        map.check(PageComponentRequest(type = "MAP", geometry = " acceso "), spatial)
        refused("Unknown geometry 'x'", "components") { map.check(PageComponentRequest(type = "MAP", geometry = "x"), spatial) }
    }

    @Test
    fun `a generated page gets a map tab only when the object is spatial`() =
        runTest {
            assertThat(map.generated(definition(text("codigo")))).isNull()
            val generated = map.generated(spatial)!!
            assertThat(generated.tab).isEqualTo("MAP")
            assertThat(generated.component.type).isEqualTo(ComponentType("MAP"))
            assertThat(generated.component.title).isEqualTo("Predio")
        }
}
```

Run: `./gradlew :chawpi-gis:test --tests 'chawpi.gis.MapPageComponentTest'`
Expected: FAIL, `Unresolved reference 'MapPageComponent'`.

- [ ] **Step 3: Write the component (the original's MAP branch and MAP tab, moved)**

`backend/chawpi-gis/src/main/kotlin/chawpi/gis/MapPageComponent.kt`:

```kotlin
package chawpi.gis

import chawpi.core.common.ValidationException
import chawpi.core.metadata.ObjectDefinition
import chawpi.pages.ComponentType
import chawpi.pages.GeneratedComponent
import chawpi.pages.PageComponent
import chawpi.pages.PageComponentProvider
import chawpi.pages.PageComponentRequest

// the MAP page component. pages placed it; what it means for an object is gis's to judge.
class MapPageComponent : PageComponentProvider {
    override val type = MAP

    override fun check(
        component: PageComponentRequest,
        definition: ObjectDefinition
    ) {
        if (definition.geometryFields.isEmpty()) {
            throw ValidationException("MAP on a non-spatial object", "components", "'${definition.obj.name}' has no geometry")
        }
        // naming none draws them all, which is what a one-shape object wants
        val geometry = component.geometry?.trim()?.ifBlank { null }
        if (geometry != null && definition.geometryFields.none { it.name == geometry }) {
            throw ValidationException("Unknown geometry '$geometry'", "components", "'${definition.obj.name}' has no geometry field '$geometry'")
        }
    }

    // a spatial object's generated page gets a map tab between details and related, as before
    override suspend fun generated(definition: ObjectDefinition): GeneratedComponent? =
        if (definition.geometryFields.isEmpty()) {
            null
        } else {
            GeneratedComponent(PageComponent(type = MAP, title = definition.obj.label), tab = MAP_TAB)
        }

    companion object {
        val MAP = ComponentType("MAP")

        // a key, not a word: the client translates it
        const val MAP_TAB = "MAP"
    }
}
```

Run: `./gradlew :chawpi-gis:test --tests 'chawpi.gis.MapPageComponentTest'`
Expected: PASS (3 tests).

- [ ] **Step 4: Write the failing wiring test**

`backend/chawpi-gis/src/test/kotlin/chawpi/gis/ChawpiGisPagesAutoConfigurationTest.kt`:

```kotlin
package chawpi.gis

import chawpi.forms.autoconfigure.ChawpiFormsAutoConfiguration
import chawpi.gis.autoconfigure.ChawpiGisAutoConfiguration
import chawpi.gis.autoconfigure.ChawpiGisPagesAutoConfiguration
import chawpi.pages.ComponentType
import chawpi.pages.PageComponentTypes
import chawpi.pages.autoconfigure.ChawpiPagesAutoConfiguration
import chawpi.test.ChawpiContextRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader

class ChawpiGisPagesAutoConfigurationTest {
    private val all =
        AutoConfigurations.of(
            ChawpiFormsAutoConfiguration::class.java,
            ChawpiPagesAutoConfiguration::class.java,
            ChawpiGisAutoConfiguration::class.java,
            ChawpiGisPagesAutoConfiguration::class.java
        )

    @Test
    fun `with pages installed, MAP is a page component`() {
        ChawpiContextRunner.core().withConfiguration(all).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(PageComponentTypes::class.java).provider(ComponentType("MAP"))).isInstanceOf(MapPageComponent::class.java)
        }
    }

    @Test
    fun `with gis switched off, pages has no MAP`() {
        ChawpiContextRunner.core().withConfiguration(all).withPropertyValues("chawpi.gis.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(PageComponentTypes::class.java).provider(ComponentType("MAP"))).isNull()
        }
    }

    @Test
    fun `without pages on the classpath, gis boots alone`() {
        ChawpiContextRunner
            .core()
            .withClassLoader(FilteredClassLoader("chawpi.pages"))
            .withConfiguration(AutoConfigurations.of(ChawpiGisAutoConfiguration::class.java, ChawpiGisPagesAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(FeatureController::class.java)
                assertThat(context).doesNotHaveBean("mapPageComponent")
            }
    }

    @Test
    fun `the imports file registers both gis auto-configs`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("chawpi.gis.autoconfigure.ChawpiGisAutoConfiguration", "chawpi.gis.autoconfigure.ChawpiGisPagesAutoConfiguration")
    }
}
```

Run: `./gradlew :chawpi-gis:test --tests 'chawpi.gis.ChawpiGisPagesAutoConfigurationTest'`
Expected: FAIL, `Unresolved reference 'ChawpiGisPagesAutoConfiguration'`.

- [ ] **Step 5: The component's own auto-config**

`backend/chawpi-gis/src/main/kotlin/chawpi/gis/autoconfigure/ChawpiGisPagesAutoConfiguration.kt`:

```kotlin
package chawpi.gis.autoconfigure

import chawpi.gis.MapPageComponent
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

// the MAP component, only when chawpi-pages is on the classpath (named as a string, so nothing
// loads a pages class otherwise) and gis itself is switched on. pages collects providers lazily,
// so no order against its auto-config is needed.
@AutoConfiguration
@ConditionalOnClass(name = ["chawpi.pages.PageComponentProvider"])
@ConditionalOnProperty(prefix = "chawpi.gis", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ChawpiGisPagesAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun mapPageComponent(): MapPageComponent = MapPageComponent()
}
```

Append this line to `backend/chawpi-gis/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (the file then has two lines):

```
chawpi.gis.autoconfigure.ChawpiGisPagesAutoConfiguration
```

Run: `./gradlew :chawpi-gis:test`
Expected: PASS (all gis suites, including Task 7's).

- [ ] **Step 6: Format, leave uncommitted**

```bash
./gradlew :chawpi-gis:ktlintFormat
./gradlew :chawpi-gis:ktlintCheck
git status --short backend/chawpi-gis
```
Expected: ktlint passes; changes uncommitted.

### Task 13: workflow → pages WORKFLOW component (Wave 3 — needs Tasks 4 and 9)

**Files:**
- Modify: `backend/chawpi-workflow/build.gradle.kts` (two dependency lines)
- Create: `backend/chawpi-workflow/src/main/kotlin/chawpi/workflow/WorkflowPageComponent.kt`
- Create: `backend/chawpi-workflow/src/main/kotlin/chawpi/workflow/autoconfigure/ChawpiWorkflowPagesAutoConfiguration.kt`
- Modify: `backend/chawpi-workflow/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (append one line)
- Test: `backend/chawpi-workflow/src/test/kotlin/chawpi/workflow/{WorkflowPageComponentTest,ChawpiWorkflowPagesAutoConfigurationTest}.kt`

**Interfaces:**
- Consumes: Task 9 `PageComponentProvider`, `ComponentType`, `GeneratedComponent`, `PageComponent`, `PageComponentTypes.provider`, `chawpi.pages.autoconfigure.ChawpiPagesAutoConfiguration`, `chawpi.forms.autoconfigure.ChawpiFormsAutoConfiguration`; core `WorkflowStates.stateOf(organizationId, objectId): ObjectWorkflowState`, `ObjectDefinition`, `CustomObject`; Task 4 `chawpi.workflow.autoconfigure.ChawpiWorkflowAutoConfiguration`; `ChawpiContextRunner.core()`.
- Produces: `class chawpi.workflow.WorkflowPageComponent(workflows: WorkflowStates) : PageComponentProvider` (`type = ComponentType("WORKFLOW")`, generated into the DETAILS tab when a workflow is attached); `chawpi.workflow.autoconfigure.ChawpiWorkflowPagesAutoConfiguration`.

- [ ] **Step 1: Compile against pages without requiring it (M2)**

In `backend/chawpi-workflow/build.gradle.kts`, replace the `dependencies { }` block with:

```kotlin
dependencies {
    api(project(":chawpi-core"))
    // the WORKFLOW page component, only when an app has chawpi-pages: compileOnly + @ConditionalOnClass
    compileOnly(project(":chawpi-pages"))

    testImplementation(project(":chawpi-test"))
    testImplementation(project(":chawpi-pages"))
}
```

- [ ] **Step 2: Write the failing component test**

`backend/chawpi-workflow/src/test/kotlin/chawpi/workflow/WorkflowPageComponentTest.kt`:

```kotlin
package chawpi.workflow

import chawpi.core.data.ObjectWorkflowState
import chawpi.core.data.WorkflowStates
import chawpi.core.metadata.CustomObject
import chawpi.core.metadata.ObjectDefinition
import chawpi.pages.ComponentType
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WorkflowPageComponentTest {
    private val definition =
        ObjectDefinition(
            CustomObject(UUID.randomUUID(), UUID.randomUUID(), "tramite", "Trámite", "Trámites", null, true, "tramite__00000000", null, null),
            emptyList()
        )

    private fun states(attached: Boolean) =
        object : WorkflowStates {
            override suspend fun stateOf(
                organizationId: UUID,
                objectId: UUID
            ) = if (attached) ObjectWorkflowState(true, "draft") else ObjectWorkflowState.NONE

            override suspend fun transitionNames(
                organizationId: UUID,
                objectId: UUID
            ) = emptySet<String>()
        }

    // acting on the record's state is something you do while looking at it, not at its trail
    @Test
    fun `an attached workflow sits in the details tab, next to the form`() =
        runTest {
            val generated = WorkflowPageComponent(states(attached = true)).generated(definition)!!
            assertThat(generated.tab).isNull()
            assertThat(generated.component.type).isEqualTo(ComponentType("WORKFLOW"))
        }

    @Test
    fun `no workflow, no component`() =
        runTest {
            assertThat(WorkflowPageComponent(states(attached = false)).generated(definition)).isNull()
        }
}
```

Run: `./gradlew :chawpi-workflow:test --tests 'chawpi.workflow.WorkflowPageComponentTest'`
Expected: FAIL, `Unresolved reference 'WorkflowPageComponent'`.

- [ ] **Step 3: Write the component (the original's WORKFLOW generation, moved)**

`backend/chawpi-workflow/src/main/kotlin/chawpi/workflow/WorkflowPageComponent.kt`:

```kotlin
package chawpi.workflow

import chawpi.core.data.WorkflowStates
import chawpi.core.metadata.ObjectDefinition
import chawpi.pages.ComponentType
import chawpi.pages.GeneratedComponent
import chawpi.pages.PageComponent
import chawpi.pages.PageComponentProvider

// the WORKFLOW page component: the record's state and the transitions open to the caller. it reads
// what the record already has, so there is nothing to configure and nothing to check.
class WorkflowPageComponent(
    private val workflows: WorkflowStates
) : PageComponentProvider {
    override val type = WORKFLOW

    // acting on the record's state is something you do while looking at it, not at its trail:
    // it joins the details tab
    override suspend fun generated(definition: ObjectDefinition): GeneratedComponent? =
        if (workflows.stateOf(definition.obj.organizationId, definition.obj.id).attached) {
            GeneratedComponent(PageComponent(type = WORKFLOW))
        } else {
            null
        }

    companion object {
        val WORKFLOW = ComponentType("WORKFLOW")
    }
}
```

Run: `./gradlew :chawpi-workflow:test --tests 'chawpi.workflow.WorkflowPageComponentTest'`
Expected: PASS (2 tests).

- [ ] **Step 4: Write the failing wiring test**

`backend/chawpi-workflow/src/test/kotlin/chawpi/workflow/ChawpiWorkflowPagesAutoConfigurationTest.kt`:

```kotlin
package chawpi.workflow

import chawpi.forms.autoconfigure.ChawpiFormsAutoConfiguration
import chawpi.pages.ComponentType
import chawpi.pages.PageComponentTypes
import chawpi.pages.autoconfigure.ChawpiPagesAutoConfiguration
import chawpi.test.ChawpiContextRunner
import chawpi.workflow.autoconfigure.ChawpiWorkflowAutoConfiguration
import chawpi.workflow.autoconfigure.ChawpiWorkflowPagesAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader

class ChawpiWorkflowPagesAutoConfigurationTest {
    private val all =
        AutoConfigurations.of(
            ChawpiFormsAutoConfiguration::class.java,
            ChawpiPagesAutoConfiguration::class.java,
            ChawpiWorkflowAutoConfiguration::class.java,
            ChawpiWorkflowPagesAutoConfiguration::class.java
        )

    @Test
    fun `with pages installed, WORKFLOW is a page component`() {
        ChawpiContextRunner.core().withConfiguration(all).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(PageComponentTypes::class.java).provider(ComponentType("WORKFLOW")))
                .isInstanceOf(WorkflowPageComponent::class.java)
        }
    }

    @Test
    fun `with workflow switched off, pages has no WORKFLOW`() {
        ChawpiContextRunner.core().withConfiguration(all).withPropertyValues("chawpi.workflow.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(PageComponentTypes::class.java).provider(ComponentType("WORKFLOW"))).isNull()
        }
    }

    @Test
    fun `without pages on the classpath, workflow boots alone`() {
        ChawpiContextRunner
            .core()
            .withClassLoader(FilteredClassLoader("chawpi.pages"))
            .withConfiguration(AutoConfigurations.of(ChawpiWorkflowAutoConfiguration::class.java, ChawpiWorkflowPagesAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(WorkflowService::class.java)
                assertThat(context).doesNotHaveBean("workflowPageComponent")
            }
    }

    @Test
    fun `the imports file registers both workflow auto-configs`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("chawpi.workflow.autoconfigure.ChawpiWorkflowAutoConfiguration", "chawpi.workflow.autoconfigure.ChawpiWorkflowPagesAutoConfiguration")
    }
}
```

Run: `./gradlew :chawpi-workflow:test --tests 'chawpi.workflow.ChawpiWorkflowPagesAutoConfigurationTest'`
Expected: FAIL, `Unresolved reference 'ChawpiWorkflowPagesAutoConfiguration'`.

- [ ] **Step 5: The component's own auto-config**

`backend/chawpi-workflow/src/main/kotlin/chawpi/workflow/autoconfigure/ChawpiWorkflowPagesAutoConfiguration.kt`:

```kotlin
package chawpi.workflow.autoconfigure

import chawpi.core.data.WorkflowStates
import chawpi.workflow.WorkflowPageComponent
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

// the WORKFLOW component, only when chawpi-pages is on the classpath (named as a string, so nothing
// loads a pages class otherwise) and workflow itself is switched on. pages collects providers
// lazily, so no order against its auto-config is needed.
@AutoConfiguration
@ConditionalOnClass(name = ["chawpi.pages.PageComponentProvider"])
@ConditionalOnProperty(prefix = "chawpi.workflow", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ChawpiWorkflowPagesAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun workflowPageComponent(workflows: WorkflowStates): WorkflowPageComponent = WorkflowPageComponent(workflows)
}
```

Append this line to `backend/chawpi-workflow/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (the file then has two lines):

```
chawpi.workflow.autoconfigure.ChawpiWorkflowPagesAutoConfiguration
```

Run: `./gradlew :chawpi-workflow:test`
Expected: PASS (all workflow suites, including Task 4's).

- [ ] **Step 6: Format, leave uncommitted**

```bash
./gradlew :chawpi-workflow:ktlintFormat
./gradlew :chawpi-workflow:ktlintCheck
git status --short backend/chawpi-workflow
```
Expected: ktlint passes; changes uncommitted.

### Task 14: Starters (Wave 3 — needs Tasks 2–9)

Thin starters: no code, only dependencies (spec "App developer experience"). The BOM and `settings.gradle.kts` pick them up by themselves (folder with a build file under `backend/starters`, name starting `chawpi-`), so no shared file changes.

**Files:**
- Create: `backend/starters/chawpi-spring-boot-starter/build.gradle.kts`
- Create: `backend/starters/chawpi-spring-boot-starter-{views,forms,pages,workflow,automation,documents,gis,agent}/build.gradle.kts` (8 files)

**Interfaces:**
- Consumes: projects `:chawpi-core` and `:chawpi-<m>` (Tasks 2–9); catalog aliases `libs.spring.boot.bom`, `libs.r2dbc.postgresql`, `libs.postgresql.jdbc`, `libs.flyway.postgresql`, `libs.spring.boot.starter.actuator`; convention plugins `chawpi.kotlin-library`, `chawpi.publishing`.
- Produces: published artifacts `chawpi:chawpi-spring-boot-starter` (core + runtime drivers + actuator) and `chawpi:chawpi-spring-boot-starter-<m>` (the core starter + one module), all listed in `chawpi-bom`.

- [ ] **Step 1: The core starter**

`backend/starters/chawpi-spring-boot-starter/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.kotlin-library")
    id("chawpi.publishing")
}

description = "Chawpi starter: chawpi-core plus the PostgreSQL drivers, Flyway support and actuator an app runs on"

dependencies {
    api(platform(libs.spring.boot.bom))
    api(project(":chawpi-core"))
    // runtime only: an app never codes against the drivers. r2dbc for records, jdbc + flyway for migrations (ADR-008)
    runtimeOnly(libs.r2dbc.postgresql)
    runtimeOnly(libs.postgresql.jdbc)
    runtimeOnly(libs.flyway.postgresql)
    // /actuator/health is already public in core's security chain
    implementation(libs.spring.boot.starter.actuator)
}
```

- [ ] **Step 2: One starter per module (8 files, same shape)**

```bash
cd /Users/jorge/IdeaProjects/chawpi
for m in views forms pages workflow automation documents gis agent; do
  mkdir -p backend/starters/chawpi-spring-boot-starter-$m
done
```

`backend/starters/chawpi-spring-boot-starter-views/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.kotlin-library")
    id("chawpi.publishing")
}

description = "Chawpi views starter: the Chawpi starter plus chawpi-views"

dependencies {
    api(project(":chawpi-spring-boot-starter"))
    api(project(":chawpi-views"))
}
```

`backend/starters/chawpi-spring-boot-starter-forms/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.kotlin-library")
    id("chawpi.publishing")
}

description = "Chawpi forms starter: the Chawpi starter plus chawpi-forms"

dependencies {
    api(project(":chawpi-spring-boot-starter"))
    api(project(":chawpi-forms"))
}
```

`backend/starters/chawpi-spring-boot-starter-pages/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.kotlin-library")
    id("chawpi.publishing")
}

// chawpi-pages brings chawpi-forms with it
description = "Chawpi pages starter: the Chawpi starter plus chawpi-pages (and chawpi-forms)"

dependencies {
    api(project(":chawpi-spring-boot-starter"))
    api(project(":chawpi-pages"))
}
```

`backend/starters/chawpi-spring-boot-starter-workflow/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.kotlin-library")
    id("chawpi.publishing")
}

description = "Chawpi workflow starter: the Chawpi starter plus chawpi-workflow"

dependencies {
    api(project(":chawpi-spring-boot-starter"))
    api(project(":chawpi-workflow"))
}
```

`backend/starters/chawpi-spring-boot-starter-automation/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.kotlin-library")
    id("chawpi.publishing")
}

description = "Chawpi automation starter: the Chawpi starter plus chawpi-automation"

dependencies {
    api(project(":chawpi-spring-boot-starter"))
    api(project(":chawpi-automation"))
}
```

`backend/starters/chawpi-spring-boot-starter-documents/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.kotlin-library")
    id("chawpi.publishing")
}

// automation can issue documents when an app adds chawpi-spring-boot-starter-automation too
description = "Chawpi documents starter: the Chawpi starter plus chawpi-documents"

dependencies {
    api(project(":chawpi-spring-boot-starter"))
    api(project(":chawpi-documents"))
}
```

`backend/starters/chawpi-spring-boot-starter-gis/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.kotlin-library")
    id("chawpi.publishing")
}

// needs a PostgreSQL server with the postgis extension available
description = "Chawpi GIS starter: the Chawpi starter plus chawpi-gis"

dependencies {
    api(project(":chawpi-spring-boot-starter"))
    api(project(":chawpi-gis"))
}
```

`backend/starters/chawpi-spring-boot-starter-agent/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.kotlin-library")
    id("chawpi.publishing")
}

// no ANTHROPIC_API_KEY, no assistant: the app still boots (EmbabelGate)
description = "Chawpi agent starter: the Chawpi starter plus chawpi-agent"

dependencies {
    api(project(":chawpi-spring-boot-starter"))
    api(project(":chawpi-agent"))
}
```

- [ ] **Step 3: Verify discovery, runtime classpath, POMs and the BOM**

```bash
./gradlew projects -q | grep -c "chawpi-spring-boot-starter"
./gradlew :chawpi-spring-boot-starter-gis:dependencies --configuration runtimeClasspath -q \
  | grep -E "project :chawpi-(core|gis|spring-boot-starter)$|r2dbc-postgresql|org.postgresql:postgresql|flyway-database-postgresql|spring-boot-starter-actuator" | sort -u
./gradlew :chawpi-spring-boot-starter:generatePomFileForMavenPublication :chawpi-bom:generatePomFileForMavenPublication -q
grep -B1 -A3 "<artifactId>r2dbc-postgresql</artifactId>" backend/starters/chawpi-spring-boot-starter/build/publications/maven/pom-default.xml
grep -o "<artifactId>chawpi-[a-z-]*</artifactId>" backend/chawpi-bom/build/publications/maven/pom-default.xml | sort
```
Expected:
- `9` starter projects.
- The gis starter's runtime classpath shows `project :chawpi-spring-boot-starter`, `project :chawpi-core`, `project :chawpi-gis`, `org.postgresql:r2dbc-postgresql`, `org.postgresql:postgresql:42.7.13`, `org.flywaydb:flyway-database-postgresql:12.4.0`, `spring-boot-starter-actuator`.
- The core starter's POM has `r2dbc-postgresql` with `<scope>runtime</scope>` and a real `<version>`.
- The BOM lists `chawpi-agent, chawpi-automation, chawpi-core, chawpi-documents, chawpi-forms, chawpi-gis, chawpi-pages, chawpi-spring-boot-starter, chawpi-spring-boot-starter-agent, chawpi-spring-boot-starter-automation, chawpi-spring-boot-starter-documents, chawpi-spring-boot-starter-forms, chawpi-spring-boot-starter-gis, chawpi-spring-boot-starter-pages, chawpi-spring-boot-starter-views, chawpi-spring-boot-starter-workflow, chawpi-test, chawpi-views, chawpi-workflow` (19 lines) and never `chawpi-bom` or `chawpi-integration-tests`.

- [ ] **Step 4: Format, leave uncommitted**

```bash
./gradlew $(for s in "" -views -forms -pages -workflow -automation -documents -gis -agent; do printf ":chawpi-spring-boot-starter%s:ktlintFormat " "$s"; done)
./gradlew $(for s in "" -views -forms -pages -workflow -automation -documents -gis -agent; do printf ":chawpi-spring-boot-starter%s:ktlintCheck " "$s"; done)
git status --short backend/starters
```
Expected: ktlint passes — no Kotlin sources, but `ktlintKotlinScriptCheck` still lints each starter's `build.gradle.kts`; `?? backend/starters/`.

### Task 15: Integration verification — all modules together (Wave 4)

Creates the skeleton of `backend/chawpi-integration-tests` (never published; P3 adds the API ITs to it) with two plain tests: every module wired in one context, and the module boundaries of the source tree. Then the whole build.

**Files:**
- Create: `backend/chawpi-integration-tests/build.gradle.kts`
- Create: `backend/chawpi-integration-tests/src/test/kotlin/chawpi/it/AllModulesWiringTest.kt`
- Create: `backend/chawpi-integration-tests/src/test/kotlin/chawpi/it/ModuleBoundariesTest.kt`

**Interfaces:**
- Consumes: every auto-config of Tasks 2–13 (`chawpi.<m>.autoconfigure.Chawpi<M>AutoConfiguration`, `ChawpiDocumentsAutomationAutoConfiguration`, `ChawpiAgentWorkflowAutoConfiguration`, `ChawpiGisPagesAutoConfiguration`, `ChawpiWorkflowPagesAutoConfiguration`), `ChawpiContextRunner.core()` (Task 1), `DocumentIssuerAdapter`, `WorkflowRecordTransitions`, `MapPageComponent`, `WorkflowPageComponent`, `WorkflowStatesAdapter`, `GEOMETRY`.
- Produces: Gradle project `:chawpi-integration-tests` (test-only, excluded from the BOM by name) that P3 extends.

- [ ] **Step 1: The test project**

`backend/chawpi-integration-tests/build.gradle.kts`:

```kotlin
plugins {
    id("chawpi.spring-module")
    id("chawpi.integration-test")
}

// never published (chawpi-bom skips it by name). P3 adds the ported API ITs here.
description = "Chawpi integration tests: every module assembled in one app"

dependencies {
    testImplementation(project(":chawpi-test"))
    listOf("views", "forms", "pages", "workflow", "automation", "documents", "gis", "agent").forEach {
        testImplementation(project(":chawpi-$it"))
    }
}
```

- [ ] **Step 2: Write the all-modules wiring test**

`backend/chawpi-integration-tests/src/test/kotlin/chawpi/it/AllModulesWiringTest.kt`:

```kotlin
package chawpi.it

import chawpi.agent.AgentTools
import chawpi.agent.WorkflowRecordTransitions
import chawpi.agent.autoconfigure.ChawpiAgentAutoConfiguration
import chawpi.agent.autoconfigure.ChawpiAgentWorkflowAutoConfiguration
import chawpi.automation.AutomationService
import chawpi.automation.autoconfigure.ChawpiAutomationAutoConfiguration
import chawpi.core.data.WorkflowStates
import chawpi.core.metadata.FieldTypeRegistry
import chawpi.core.platform.ModuleMigration
import chawpi.core.platform.SystemColumns
import chawpi.documents.DocumentIssuerAdapter
import chawpi.documents.autoconfigure.ChawpiDocumentsAutoConfiguration
import chawpi.documents.autoconfigure.ChawpiDocumentsAutomationAutoConfiguration
import chawpi.forms.autoconfigure.ChawpiFormsAutoConfiguration
import chawpi.gis.GEOMETRY
import chawpi.gis.MapPageComponent
import chawpi.gis.autoconfigure.ChawpiGisAutoConfiguration
import chawpi.gis.autoconfigure.ChawpiGisPagesAutoConfiguration
import chawpi.pages.ComponentType
import chawpi.pages.PageComponentTypes
import chawpi.pages.autoconfigure.ChawpiPagesAutoConfiguration
import chawpi.test.ChawpiContextRunner
import chawpi.views.autoconfigure.ChawpiViewsAutoConfiguration
import chawpi.workflow.WorkflowPageComponent
import chawpi.workflow.WorkflowStatesAdapter
import chawpi.workflow.autoconfigure.ChawpiWorkflowAutoConfiguration
import chawpi.workflow.autoconfigure.ChawpiWorkflowPagesAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping

class AllModulesWiringTest {
    // a handler mapping, so every controller's routes are registered and an ambiguous one fails the
    // context. no @Configuration on purpose: P3's scanned test apps in this package must not pick it up.
    @EnableWebFlux
    class WebFlux

    private val runner =
        ChawpiContextRunner
            .core()
            .withUserConfiguration(WebFlux::class.java)
            .withConfiguration(
                AutoConfigurations.of(
                    ChawpiViewsAutoConfiguration::class.java,
                    ChawpiFormsAutoConfiguration::class.java,
                    ChawpiPagesAutoConfiguration::class.java,
                    ChawpiWorkflowAutoConfiguration::class.java,
                    ChawpiWorkflowPagesAutoConfiguration::class.java,
                    ChawpiAutomationAutoConfiguration::class.java,
                    ChawpiDocumentsAutoConfiguration::class.java,
                    ChawpiDocumentsAutomationAutoConfiguration::class.java,
                    ChawpiGisAutoConfiguration::class.java,
                    ChawpiGisPagesAutoConfiguration::class.java,
                    ChawpiAgentAutoConfiguration::class.java,
                    ChawpiAgentWorkflowAutoConfiguration::class.java
                )
            ).withPropertyValues("chawpi.automation.poll-interval=0s")

    @Test
    fun `every module wires with every optional link made`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name })
                .containsExactlyInAnyOrder("core", "views", "forms", "pages", "workflow", "automation", "documents", "gis")
            assertThat(context.getBean(FieldTypeRegistry::class.java).types.last()).isEqualTo(GEOMETRY)
            assertThat(context.getBean(SystemColumns::class.java).names).contains("workflow_state")
            assertThat(context.getBean(WorkflowStates::class.java)).isInstanceOf(WorkflowStatesAdapter::class.java)
            val components = context.getBean(PageComponentTypes::class.java)
            assertThat(components.provider(ComponentType("MAP"))).isInstanceOf(MapPageComponent::class.java)
            assertThat(components.provider(ComponentType("WORKFLOW"))).isInstanceOf(WorkflowPageComponent::class.java)
            assertThat(ReflectionTestUtils.getField(context.getBean(AutomationService::class.java), "documents"))
                .isInstanceOf(DocumentIssuerAdapter::class.java)
            assertThat(ReflectionTestUtils.getField(context.getBean(AgentTools::class.java), "transitions"))
                .isInstanceOf(WorkflowRecordTransitions::class.java)
        }
    }

    // the original's module routes, verb by verb, plus the three metadata routes that left core (P1 R16)
    @Test
    fun `the module routes are the original's, with no collision`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val routes =
                context.getBean(RequestMappingHandlerMapping::class.java).handlerMethods.keys.flatMap { info ->
                    info.methodsCondition.methods.flatMap { verb -> info.patternsCondition.patterns.map { "${verb.name} ${it.patternString}" } }
                }
            assertThat(routes).doesNotHaveDuplicates().containsAll(LEGACY_MODULE_ROUTES)
        }
    }

    companion object {
        val LEGACY_MODULE_ROUTES =
            listOf(
                "GET /api/metadata/objects/{object}/views",
                "GET /api/metadata/objects/{object}/forms",
                "GET /api/metadata/objects/{object}/pages",
                "DELETE /api/gis/layers/{object}",
                "DELETE /api/gis/layers/{object}/{geometry}",
                "DELETE /api/objects/{object}/automations/{name}",
                "DELETE /api/objects/{object}/document-types/{name}",
                "DELETE /api/objects/{object}/forms/{name}",
                "DELETE /api/objects/{object}/views/{name}",
                "DELETE /api/objects/{object}/workflow",
                "DELETE /api/pages/{name}",
                "GET /api/agent/status",
                "GET /api/automation-runs",
                "GET /api/documents/{id}",
                "GET /api/gis/layers",
                "GET /api/gis/objects/{object}/features",
                "GET /api/gis/objects/{object}/features/{id}",
                "GET /api/gis/services",
                "GET /api/metadata/page-templates",
                "GET /api/objects/{object}/automations",
                "GET /api/objects/{object}/automations/{name}",
                "GET /api/objects/{object}/automations/{name}/runs",
                "GET /api/objects/{object}/document-types",
                "GET /api/objects/{object}/document-types/{name}",
                "GET /api/objects/{object}/forms",
                "GET /api/objects/{object}/forms/{name}",
                "GET /api/objects/{object}/pages/{kind}",
                "GET /api/objects/{object}/records/{id}/documents",
                "GET /api/objects/{object}/records/{id}/transitions",
                "GET /api/objects/{object}/views",
                "GET /api/objects/{object}/views/{name}",
                "GET /api/objects/{object}/workflow",
                "GET /api/pages",
                "GET /api/pages/{name}",
                "POST /api/agent/ask",
                "POST /api/gis/layers/{object}",
                "POST /api/gis/layers/{object}/{geometry}",
                "POST /api/objects/{object}/automations",
                "POST /api/objects/{object}/document-types",
                "POST /api/objects/{object}/forms",
                "POST /api/objects/{object}/records/{id}/documents/{type}",
                "POST /api/objects/{object}/records/{id}/transitions/{name}",
                "POST /api/objects/{object}/views",
                "POST /api/pages",
                "PUT /api/objects/{object}/automations/{name}",
                "PUT /api/objects/{object}/document-types/{name}",
                "PUT /api/objects/{object}/forms/{name}",
                "PUT /api/objects/{object}/views/{name}",
                "PUT /api/objects/{object}/workflow",
                "PUT /api/pages/{name}"
            )
    }
}
```

- [ ] **Step 3: Write the module-boundaries test**

`backend/chawpi-integration-tests/src/test/kotlin/chawpi/it/ModuleBoundariesTest.kt`:

```kotlin
package chawpi.it

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

// the spec's module graph, checked on the sources: pages may use forms; every other link between
// modules is an optional adapter, allowed only in the files that exist for it (M2).
class ModuleBoundariesTest {
    private val modules = listOf("views", "forms", "pages", "workflow", "automation", "documents", "gis", "agent")
    private val reference = Regex("""\bchawpi\.(views|forms|pages|workflow|automation|documents|gis|agent)\.""")

    // module -> (other module -> files allowed to name it)
    private val allowed: Map<String, Map<String, Set<String>>> =
        mapOf(
            "pages" to mapOf("forms" to setOf("*")),
            "documents" to mapOf("automation" to setOf("DocumentIssuerAdapter.kt", "ChawpiDocumentsAutomationAutoConfiguration.kt")),
            "agent" to mapOf("workflow" to setOf("WorkflowRecordTransitions.kt", "ChawpiAgentWorkflowAutoConfiguration.kt")),
            "gis" to mapOf("pages" to setOf("MapPageComponent.kt", "ChawpiGisPagesAutoConfiguration.kt")),
            "workflow" to mapOf("pages" to setOf("WorkflowPageComponent.kt", "ChawpiWorkflowPagesAutoConfiguration.kt"))
        )

    @Test
    fun `no module reaches into another outside its declared adapters`() {
        val offenders =
            modules.flatMap { module ->
                File("../chawpi-$module/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                    reference.findAll(file.readText()).map { it.groupValues[1] }.filter { it != module }.distinct().mapNotNull { other ->
                        val files = allowed[module]?.get(other).orEmpty()
                        if ("*" in files || file.name in files) null else "chawpi-$module/${file.name} -> chawpi.$other"
                    }
                }
            }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `core never names a module`() {
        val core = File("../chawpi-core/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
        assertThat(core.filter { reference.containsMatchIn(it.readText()) }.map { it.name }.toList()).isEmpty()
    }
}
```

Run: `./gradlew :chawpi-integration-tests:test`
Expected: PASS (4 tests). A failure names the module/file/route to fix; fix it in the owning module (the task that created that file), not here.

- [ ] **Step 4: The whole build**

```bash
cd /Users/jorge/IdeaProjects/chawpi
./gradlew build
```
Expected: `BUILD SUCCESSFUL`: every project compiles, `ktlintCheck` passes everywhere, every unit test passes (core's included, `CoreArchitectureTest` still green). `integrationTest` is not part of `build`.

- [ ] **Step 5: Publishing works for every library**

```bash
./gradlew publishToMavenLocal -q
ls ~/.m2/repository/chawpi/ | sort
```
Expected: contains these 20 folders — `chawpi-agent chawpi-automation chawpi-bom chawpi-core chawpi-documents chawpi-forms chawpi-gis chawpi-pages chawpi-spring-boot-starter chawpi-spring-boot-starter-agent chawpi-spring-boot-starter-automation chawpi-spring-boot-starter-documents chawpi-spring-boot-starter-forms chawpi-spring-boot-starter-gis chawpi-spring-boot-starter-pages chawpi-spring-boot-starter-views chawpi-spring-boot-starter-workflow chawpi-test chawpi-views chawpi-workflow` — not necessarily *only* these (stale P0/P1 artifacts in `~/.m2` could add noise); the check is that `chawpi-integration-tests` is absent.

- [ ] **Step 6: Nothing named after the original, nothing committed**

```bash
grep -rn -i "sapgis" backend --include=*.kt --include=*.kts --include=*.sql --include=*.imports --include=*.factories || echo "clean"
git status --short | head -40
git log --oneline -1
```
Expected: `clean`; every P2 file is uncommitted (`??`/` M`); the last commit is the same one as before P2 started.

### Task 16: Core hardening — links and audit snapshots (Wave 5 — after Task 15)

**This is P2's one deliberate behaviour change against the original.** The differences named in M10 and M11 are side effects of splitting the modules. This one is a choice: it closes the two "Known gaps" that ADR-0025 lists, both inherited from the original.
1. `link`/`unlink` now enforce `own_records_only` on both records, and 404 when a record is missing, belongs to another tenant or is hidden from the caller. In the original they answered 204 on someone else's record, 500 (an FK violation) on a missing one for link, and 204 for unlink. Every effective link or unlink now writes audit rows.
2. The audit `after` snapshot and `RecordChange.after` are re-read in full after the write.

Every other P2 task keeps the original's behaviour.

**Decisions (binding for this task):**
- **Audit operation = `UPDATE`, no new value.** The audit CHECK allows `CREATE, UPDATE, DELETE` (core V1) plus `ISSUE` (documents, M3). A link changes what a record is related to, so it is an update of that record. It is written as a one-key diff: key = the relationship name, value = the other record's id (`null` → id on link, id → `null` on unlink). One row goes on each record's history. Core V1's CHECK, `AuditOperation` and documents' CHECK redefinition therefore stay exactly as they are. **Do not add `LINK`/`UNLINK`.** If a reviewer wants them, stop and ask the user first. Adding them would mean changing core V1's `audit_log_operation_valid`, the `AuditOperation` enum, AND the documents V1 re-add (which must list every core value plus `ISSUE`) in the same change.
- Only an effective change is audited. A link that already existed (`ON CONFLICT DO NOTHING`) or an unlink that removed nothing writes no row.
- The relationship key is not a field. So a caller with field-level restrictions (`FieldAccess` not unrestricted) sees the UPDATE entry without its change, because `AuditQueryService.filter` keeps only readable field names. Unrestricted callers and admins see it. This is accepted, not a gap.
- **Listeners get the full row, not the caller's projection.** Check it: `grep -n "change.after\|change.before\|payload.after" backend/chawpi-automation/src/main/kotlin/chawpi/automation/*.kt` shows:
  - `AutomationRules` evaluates conditions on `change.after ?: change.before`;
  - the `CHANGED` operator compares `before` (already a full, unprojected read) with `after`, so a projected `after` would report every locked field as changed to null;
  - `AutomationRunner` posts `run.payload.after` as the webhook's `record`;
  - runs act as the platform, not as the caller (ADR-016);
  - workflow already hands listeners the unprojected row ("read unprojected: an automation must judge the whole record", `WorkflowService.apply`).

  Listeners are server code and never answer the caller, and the audit read path already filters by the viewer's readable fields. So the full row leaks nothing.
- The re-read is `store.findById(definition, org, id, createdBy = null, withState)` with the full definition, right after the write in the same transaction. Rationale: the `RecordStore` port promises nothing about what `insert`/`update` return (an app's own store may return only what it wrote). One extra SELECT per create/update is the cost. The HTTP response is unchanged: it still comes from the write's own row, filtered by `onlyReadable`.
- Today's `PhysicalTableRecordStore` already selects every field in `RETURNING` (`writableBy` keeps locked fields, only flipping `editable`). So the unit test below, whose fake store returns only what it wrote, is what fails before this task. The history IT is a regression pin and may already pass on the current code. Report that honestly rather than faking a red run.

**Files:**
- Modify: `backend/chawpi-core/src/main/kotlin/chawpi/core/data/RelatedRecordService.kt` (constructor, `link`, `unlink`, `manyToManyOrFail`, new private helpers, new top-level `linkChange`)
- Modify: `backend/chawpi-core/src/main/kotlin/chawpi/core/data/RecordService.kt` (`create`, `update`)
- Modify: `backend/chawpi-core/src/main/kotlin/chawpi/core/autoconfigure/ChawpiDataAutoConfiguration.kt` (`relatedRecordService` bean gets `audit`)
- Modify: `docs/adr/0025-extension-spis.md` (the "Known gaps" paragraph)
- Modify: `docs/HISTORY.md` (new newest-first section)
- Test (new): `backend/chawpi-core/src/test/kotlin/chawpi/core/data/{RecordAuditSnapshotTest,LinkChangeTest}.kt`
- Test (new IT): `backend/chawpi-core/src/test/kotlin/chawpi/core/api/CoreHardeningApiTest.kt`

**Interfaces:**
- Consumes (core, existing):
  - `AuditService.record(organizationId, userId, objectName, recordId, operation, before, after, documentId)` and `AuditOperation.UPDATE`;
  - `AccessPolicy.ownerFilter(user): UUID?`, `CurrentUser.require()`, `AuthenticatedUser(userId, organizationId, …)`;
  - `MetadataService.loadDefinitionById(organizationId, id)`;
  - `RecordStore.findById(definition, organizationId, id, createdBy, withState)`;
  - `Relationship(sourceObjectId, targetObjectId, name, joinTable, organizationId, type)`;
  - `AuditDiff.changes(before, after): List<FieldChange(field, before, after)>`;
  - `chawpi.test.ChawpiIntegrationTest` (`client`, `bearer()`, `bearer(email, password)`, `uniqueName`).
- Produces:
  - `RelatedRecordService(relationships, relationshipService, objects, fields, metadata, store, currentUser, access, db, schemas, audit: AuditService)`: new LAST constructor parameter.
  - `internal fun linkChange(relationship: String, otherId: UUID, linked: Boolean): Pair<Map<String, Any?>, Map<String, Any?>>`.
  - Routes and JSON unchanged. New 404s on link/unlink as described above.

- [ ] **Step 1: Write the failing snapshot unit test**

`backend/chawpi-core/src/test/kotlin/chawpi/core/data/RecordAuditSnapshotTest.kt`:

```kotlin
package chawpi.core.data

import chawpi.core.audit.AuditOperation
import chawpi.core.audit.AuditService
import chawpi.core.common.PageResponse
import chawpi.core.identity.AccessPolicy
import chawpi.core.identity.AuthenticatedUser
import chawpi.core.identity.CurrentUser
import chawpi.core.identity.FieldAccess
import chawpi.core.metadata.FieldType
import chawpi.core.metadata.FieldTypeRegistry
import chawpi.core.metadata.MetadataService
import chawpi.core.metadata.ObjectDefinition
import chawpi.core.platform.ChawpiSchemas
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// ADR-0025 known gap: the audit and the listeners must see the record as stored, not what one
// caller was allowed to write. the fake store answers a write with only what it wrote, which the
// RecordStore port allows; the read-back after it is what makes the snapshot whole.
class RecordAuditSnapshotTest {
    private val codigo = ObjectDefinitionFixtures.field("codigo", FieldType.TEXT)
    private val valor = ObjectDefinitionFixtures.field("valor", FieldType.DECIMAL)
    private val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo, valor))
    private val user = AuthenticatedUser(UUID.randomUUID(), ObjectDefinitionFixtures.obj.organizationId, "user@example.com", listOf("EDITOR"))

    // valor is readable but locked for this caller
    private val fieldAccess = FieldAccess(read = emptyMap(), write = mapOf(valor.id to false))

    private val rows = linkedMapOf<UUID, Map<String, Any?>>()

    private val store =
        object : RecordStore {
            private fun written(definition: ObjectDefinition) = definition.fields.filter { it.editable }.map { it.name }.toSet()

            override suspend fun insert(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID,
                attributes: Map<String, Any?>,
                sections: Map<String, Map<String, Any?>>,
                workflow: ObjectWorkflowState
            ): RecordRow {
                val id = UUID.randomUUID()
                rows[id] = definition.fields.associate { it.name to attributes[it.name] }
                return RecordRow(id, Instant.now(), Instant.now(), attributes.filterKeys { it in written(definition) })
            }

            override suspend fun update(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID,
                id: UUID,
                attributes: Map<String, Any?>,
                sections: Map<String, Map<String, Any?>>,
                withState: Boolean
            ): RecordRow {
                val kept = written(definition)
                rows[id] = rows.getValue(id) + attributes.filterKeys { it in kept }
                return RecordRow(id, Instant.now(), Instant.now(), rows.getValue(id).filterKeys { it in kept })
            }

            override suspend fun transitionState(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID,
                id: UUID,
                from: String?,
                to: String
            ): RecordRow? = null

            override suspend fun delete(
                definition: ObjectDefinition,
                organizationId: UUID,
                id: UUID
            ) = true

            override suspend fun findById(
                definition: ObjectDefinition,
                organizationId: UUID,
                id: UUID,
                createdBy: UUID?,
                withState: Boolean
            ): RecordRow? = rows[id]?.let { RecordRow(id, Instant.now(), Instant.now(), it) }

            override suspend fun query(
                definition: ObjectDefinition,
                organizationId: UUID,
                query: RecordQuery
            ) = PageResponse.of(emptyList<RecordRow>(), 0, 25, 0)
        }

    private data class Recorded(
        val operation: AuditOperation,
        val before: Any?,
        val after: Any?
    )

    private val recorded = mutableListOf<Recorded>()
    private val changes = mutableListOf<RecordChange>()

    private val audit =
        object : AuditService(mock(DatabaseClient::class.java), JsonMapper.builder().build(), ChawpiSchemas("chawpi", "app_data")) {
            override suspend fun record(
                organizationId: UUID,
                userId: UUID?,
                objectName: String,
                recordId: UUID?,
                operation: AuditOperation,
                before: Any?,
                after: Any?,
                documentId: UUID?
            ) {
                recorded += Recorded(operation, before, after)
            }
        }

    private val listener =
        object : RecordChangeListener {
            override suspend fun recordChanged(change: RecordChange) {
                changes += change
            }
        }

    private suspend fun service(): RecordService {
        val currentUser = mock(CurrentUser::class.java)
        val metadata = mock(MetadataService::class.java)
        val access = mock(AccessPolicy::class.java)
        doReturn(user).`when`(currentUser).require()
        doReturn(definition).`when`(metadata).loadDefinition(user.organizationId, "predio")
        doReturn(fieldAccess).`when`(access).fieldAccess(user, definition.obj.id)
        return RecordService(metadata, store, audit, currentUser, access, NoWorkflowStates(), FieldTypeRegistry(emptyList()), listOf(listener))
    }

    @Test
    fun `an update the caller could not fully write is audited as the whole stored row`() =
        runTest {
            val id = UUID.randomUUID()
            rows[id] = mapOf("codigo" to "S-1", "valor" to BigDecimal("5"))

            service().update("predio", id, RecordRequest(attributes = mapOf("codigo" to "S-2")))

            val entry = recorded.single()
            assertThat(entry.operation).isEqualTo(AuditOperation.UPDATE)
            assertThat(entry.before).isEqualTo(mapOf("codigo" to "S-1", "valor" to BigDecimal("5")))
            // the locked field is still there, unchanged: the history must not read "valor: 5 -> (cleared)"
            assertThat(entry.after).isEqualTo(mapOf("codigo" to "S-2", "valor" to BigDecimal("5")))
            assertThat(changes.single().after).isEqualTo(mapOf("codigo" to "S-2", "valor" to BigDecimal("5")))
        }

    @Test
    fun `a create is audited and announced with every stored field`() =
        runTest {
            service().create("predio", RecordRequest(attributes = mapOf("codigo" to "S-1")))

            assertThat(recorded.single().after as Map<*, *>).containsKeys("codigo", "valor")
            assertThat(changes.single().after).containsKeys("codigo", "valor")
        }
}
```

Run: `./gradlew :chawpi-core:test --tests 'chawpi.core.data.RecordAuditSnapshotTest'`
Expected: FAIL. Both tests fail: `after` has no `valor` key.

- [ ] **Step 2: Re-read the stored row after the write**

In `backend/chawpi-core/src/main/kotlin/chawpi/core/data/RecordService.kt`, in `create(...)`, replace the block from `        val created =` through the closing `        )` of its `notify(...)` call:

```kotlin
        val created =
            store.insert(
                definition.writableBy(fieldAccess),
                user.organizationId,
                user.userId,
                request.attributes,
                sections,
                workflows.stateOf(user.organizationId, definition.obj.id)
            )
        audit.record(
            organizationId = user.organizationId,
            userId = user.userId,
            objectName = objectName,
            recordId = created.id,
            operation = AuditOperation.CREATE,
            after = created.attributes
        )
        // only the fields this user may write, per `writableBy` above: same projection RETURNING
        // gave back, so a listener sees exactly what the write actually stored.
        notify(
            RecordChange(
                organizationId = user.organizationId,
                userId = user.userId,
                objectId = definition.obj.id,
                objectName = definition.obj.name,
                recordId = created.id,
                kind = RecordChangeKind.CREATED,
                after = created.attributes,
                state = created.state
            )
        )
```
with
```kotlin
        val workflow = workflows.stateOf(user.organizationId, definition.obj.id)
        val created =
            store.insert(
                definition.writableBy(fieldAccess),
                user.organizationId,
                user.userId,
                request.attributes,
                sections,
                workflow
            )
        // audit and listeners judge the record as stored, every field (ADR-0025): the port promises
        // nothing about what insert hands back, and a locked field left out would read as cleared
        val stored = storedRow(definition, user, created, workflow.attached)
        audit.record(
            organizationId = user.organizationId,
            userId = user.userId,
            objectName = objectName,
            recordId = created.id,
            operation = AuditOperation.CREATE,
            after = stored.attributes
        )
        notify(
            RecordChange(
                organizationId = user.organizationId,
                userId = user.userId,
                objectId = definition.obj.id,
                objectName = definition.obj.name,
                recordId = created.id,
                kind = RecordChangeKind.CREATED,
                after = stored.attributes,
                state = stored.state
            )
        )
```

In `update(...)`, replace

```kotlin
        audit.record(
            organizationId = user.organizationId,
            userId = user.userId,
            objectName = objectName,
            recordId = id,
            operation = AuditOperation.UPDATE,
            before = before.attributes,
            after = updated.attributes
        )
        notify(
            RecordChange(
                organizationId = user.organizationId,
                userId = user.userId,
                objectId = definition.obj.id,
                objectName = definition.obj.name,
                recordId = id,
                kind = RecordChangeKind.UPDATED,
                before = before.attributes,
                after = updated.attributes,
                state = updated.state
            )
        )
```
with
```kotlin
        // before is a full read; after must be one too, or every locked field reads as cleared (ADR-0025)
        val stored = storedRow(definition, user, updated, workflow.attached)
        audit.record(
            organizationId = user.organizationId,
            userId = user.userId,
            objectName = objectName,
            recordId = id,
            operation = AuditOperation.UPDATE,
            before = before.attributes,
            after = stored.attributes
        )
        notify(
            RecordChange(
                organizationId = user.organizationId,
                userId = user.userId,
                objectId = definition.obj.id,
                objectName = definition.obj.name,
                recordId = id,
                kind = RecordChangeKind.UPDATED,
                before = before.attributes,
                after = stored.attributes,
                state = stored.state
            )
        )
```

and add this private function right before `private suspend fun notify(`:

```kotlin
    // the row as stored, read with the full definition and no owner filter: we just wrote it.
    // listeners get this, not the caller's projection: automations judge the whole record and act
    // as the platform (ADR-016), and nothing here is ever sent back to the caller.
    private suspend fun storedRow(
        definition: ObjectDefinition,
        user: AuthenticatedUser,
        written: RecordRow,
        withState: Boolean
    ): RecordRow = store.findById(definition, user.organizationId, written.id, null, withState) ?: written
```
Add `import chawpi.core.identity.AuthenticatedUser` if the file lacks it. The responses (`created.onlyReadable(...)`, `updated.onlyReadable(...)`) stay as they are.

Run: `./gradlew :chawpi-core:test --tests 'chawpi.core.data.RecordAuditSnapshotTest' --tests 'chawpi.core.data.RecordServiceTest'`
Expected: PASS. `RecordServiceTest`'s fake returns `null` from `findById`, so `storedRow` falls back to the written row and its assertions still hold.

- [ ] **Step 3: Write the failing link-change unit test**

`backend/chawpi-core/src/test/kotlin/chawpi/core/data/LinkChangeTest.kt`:

```kotlin
package chawpi.core.data

import chawpi.core.audit.AuditDiff
import chawpi.core.audit.FieldChange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

// a link is an UPDATE of what the record is related to, shown as one change keyed by the relationship
class LinkChangeTest {
    private val other = UUID.randomUUID()

    @Test
    fun `linking reads as the other record appearing`() {
        val (before, after) = linkChange("predio_titulares", other, linked = true)
        assertThat(AuditDiff.changes(before, after)).containsExactly(FieldChange("predio_titulares", null, other.toString()))
    }

    @Test
    fun `unlinking reads as it going away`() {
        val (before, after) = linkChange("predio_titulares", other, linked = false)
        assertThat(AuditDiff.changes(before, after)).containsExactly(FieldChange("predio_titulares", other.toString(), null))
    }
}
```

Run: `./gradlew :chawpi-core:test --tests 'chawpi.core.data.LinkChangeTest'`
Expected: FAIL, `Unresolved reference 'linkChange'`.

- [ ] **Step 4: Check both ends, audit both histories**

In `backend/chawpi-core/src/main/kotlin/chawpi/core/data/RelatedRecordService.kt`:

1. Add the imports `chawpi.core.audit.AuditOperation`, `chawpi.core.audit.AuditService` and `chawpi.core.identity.AuthenticatedUser` (sorted). `data` may use `audit` under P1 R1.
2. Replace the constructor end `    private val schemas: ChawpiSchemas\n) {` with:
```kotlin
    private val schemas: ChawpiSchemas,
    private val audit: AuditService
) {
```
3. Replace the whole `link(...)` function, the whole `unlink(...)` function and the whole `manyToManyOrFail(...)` function with:

```kotlin
    @Transactional
    suspend fun link(
        objectName: String,
        recordId: UUID,
        relationshipName: String,
        otherId: UUID
    ) {
        val ends = checkedEnds(objectName, recordId, relationshipName, otherId)
        val inserted =
            db
                .sql(
                    """
                    INSERT INTO ${schemas.dataTable(ends.relationship.joinTable!!)}
                        (organization_id, source_id, target_id)
                    VALUES (:organizationId, :sourceId, :targetId)
                    ON CONFLICT DO NOTHING
                    """.trimIndent()
                ).bind("organizationId", ends.relationship.organizationId)
                .bind("sourceId", ends.sourceId)
                .bind("targetId", ends.targetId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        // already linked: nothing changed, nothing to record
        if (inserted > 0) auditLink(ends, linked = true)
    }

    @Transactional
    suspend fun unlink(
        objectName: String,
        recordId: UUID,
        relationshipName: String,
        otherId: UUID
    ) {
        val ends = checkedEnds(objectName, recordId, relationshipName, otherId)
        val deleted =
            db
                .sql(
                    "DELETE FROM ${schemas.dataTable(ends.relationship.joinTable!!)} " +
                        "WHERE source_id = :sourceId AND target_id = :targetId AND organization_id = :organizationId"
                ).bind("sourceId", ends.sourceId)
                .bind("targetId", ends.targetId)
                .bind("organizationId", ends.relationship.organizationId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        if (deleted > 0) auditLink(ends, linked = false)
    }

    // both records of one link, as the caller may see them
    private data class LinkEnds(
        val user: AuthenticatedUser,
        val relationship: Relationship,
        val definition: ObjectDefinition,
        val recordId: UUID,
        val otherDefinition: ObjectDefinition,
        val otherId: UUID
    ) {
        private val fromSource get() = definition.obj.id == relationship.sourceObjectId
        val sourceId: UUID get() = if (fromSource) recordId else otherId
        val targetId: UUID get() = if (fromSource) otherId else recordId
    }

    // a link writes to both records, so both are held to what the record api holds them to (ADR-0025):
    // same tenant, and only the caller's own when own_records_only. missing, foreign and not-yours all
    // look the same -- a 404 -- exactly like GET/PUT/DELETE on a record.
    private suspend fun checkedEnds(
        objectName: String,
        recordId: UUID,
        relationshipName: String,
        otherId: UUID
    ): LinkEnds {
        val user = currentUser.require()
        val (relationship, obj) = manyToManyOrFail(user, objectName, relationshipName)
        val otherObjectId = if (obj.id == relationship.sourceObjectId) relationship.targetObjectId else relationship.sourceObjectId
        val definition = metadata.loadDefinitionById(user.organizationId, obj.id)
        val otherDefinition = metadata.loadDefinitionById(user.organizationId, otherObjectId)
        val owner = access.ownerFilter(user)
        store.findById(definition, user.organizationId, recordId, owner)
            ?: throw NotFoundException("Record $recordId does not exist")
        store.findById(otherDefinition, user.organizationId, otherId, owner)
            ?: throw NotFoundException("Record $otherId does not exist")
        return LinkEnds(user, relationship, definition, recordId, otherDefinition, otherId)
    }

    // one UPDATE on each record's history. UPDATE, not a new operation: the audit CHECK stays the one
    // core and documents define (ADR-0025).
    private suspend fun auditLink(
        ends: LinkEnds,
        linked: Boolean
    ) {
        listOf(
            Triple(ends.definition, ends.recordId, ends.otherId),
            Triple(ends.otherDefinition, ends.otherId, ends.recordId)
        ).forEach { (definition, id, other) ->
            val (before, after) = linkChange(ends.relationship.name, other, linked)
            audit.record(
                organizationId = ends.user.organizationId,
                userId = ends.user.userId,
                objectName = definition.obj.name,
                recordId = id,
                operation = AuditOperation.UPDATE,
                before = before,
                after = after
            )
        }
    }

    private suspend fun manyToManyOrFail(
        user: AuthenticatedUser,
        objectName: String,
        relationshipName: String
    ): Pair<Relationship, CustomObject> {
        val obj =
            objects.findByName(user.organizationId, objectName)
                ?: throw NotFoundException("Object '$objectName' does not exist")
        currentUser.requirePermission(user, Actions.UPDATE, obj.id)
        val relationship =
            relationships.findByName(user.organizationId, relationshipName)
                ?: throw NotFoundException("Relationship '$relationshipName' does not exist")
        if (!relationship.type.usesJoinTable) {
            throw ValidationException(
                "Not a many-to-many relationship",
                "relationship",
                "link and unlink only apply to MANY_TO_MANY; update the field instead"
            )
        }
        return relationship to obj
    }
```

4. Append at the end of the file (top level):

```kotlin

// what a link or unlink looks like in a record's history: the relationship as the "field", the other
// record's id appearing or going away
internal fun linkChange(
    relationship: String,
    otherId: UUID,
    linked: Boolean
): Pair<Map<String, Any?>, Map<String, Any?>> {
    val absent = mapOf<String, Any?>(relationship to null)
    val present = mapOf<String, Any?>(relationship to otherId.toString())
    return if (linked) absent to present else present to absent
}
```

In `backend/chawpi-core/src/main/kotlin/chawpi/core/autoconfigure/ChawpiDataAutoConfiguration.kt`, change the `relatedRecordService` bean:

```kotlin
    @Bean
    @ConditionalOnMissingBean
    fun relatedRecordService(
        relationships: RelationshipRepository,
        relationshipService: RelationshipService,
        objects: CustomObjectRepository,
        fields: CustomFieldRepository,
        metadata: MetadataService,
        store: RecordStore,
        currentUser: CurrentUser,
        access: AccessPolicy,
        db: DatabaseClient,
        schemas: ChawpiSchemas,
        audit: AuditService
    ): RelatedRecordService =
        RelatedRecordService(relationships, relationshipService, objects, fields, metadata, store, currentUser, access, db, schemas, audit)
```

Run: `./gradlew :chawpi-core:test`
Expected: PASS. That covers every core unit test, including `LinkChangeTest`, `ChawpiAutoConfigurationTest` and `CoreArchitectureTest`, since `data → audit` is allowed.

- [ ] **Step 5: Write the ITs (own_records_only on links, link audit, locked fields in history)**

`backend/chawpi-core/src/test/kotlin/chawpi/core/api/CoreHardeningApiTest.kt`:

```kotlin
package chawpi.core.api

import chawpi.test.ChawpiIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import java.util.UUID

// ADR-0025 known gaps, closed: links obey own_records_only and leave history, and history never
// reads a locked field as cleared. the one deliberate behaviour change against the original in P2.
class CoreHardeningApiTest : ChawpiIntegrationTest() {
    private lateinit var admin: String
    private lateinit var predio: String
    private lateinit var titular: String
    private lateinit var relationship: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        predio = uniqueName("predio")
        titular = uniqueName("titular")
        createObject(predio, listOf(mapOf("name" to "codigo", "type" to "TEXT"), mapOf("name" to "valor", "type" to "DECIMAL")))
        createObject(titular, listOf(mapOf("name" to "nombre", "type" to "TEXT")))
        relationship = uniqueName("rel").take(30)
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to relationship,
                    "label" to "Titulares",
                    "inverseLabel" to "Predios",
                    "type" to "MANY_TO_MANY",
                    "source" to predio,
                    "target" to titular
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `an own-records-only user cannot link or unlink someone else's record`() {
        val token = newUserToken(ownRecordsRole())
        val mine = createRecord(token, predio, mapOf("codigo" to "MINE"))
        val myOwner = createRecord(token, titular, mapOf("nombre" to "Yo"))
        val theirs = createRecord(admin, predio, mapOf("codigo" to "THEIRS"))
        val theirOwner = createRecord(admin, titular, mapOf("nombre" to "Otro"))

        // someone else's record on either end looks missing, as on GET/PUT/DELETE
        link(token, predio, mine, theirOwner).expectStatus().isNotFound
        link(token, predio, theirs, myOwner).expectStatus().isNotFound
        link(admin, predio, theirs, theirOwner).expectStatus().isNoContent
        unlink(token, predio, theirs, theirOwner).expectStatus().isNotFound
        related(admin, predio, theirs).jsonPath("$.totalElements").isEqualTo(1)

        // their own pair still works
        link(token, predio, mine, myOwner).expectStatus().isNoContent
        related(token, predio, mine).jsonPath("$.totalElements").isEqualTo(1)
    }

    @Test
    fun `a record that does not exist in this organization cannot be linked`() {
        val plot = createRecord(admin, predio, mapOf("codigo" to "P-1"))
        val nobody = UUID.randomUUID().toString()

        link(admin, predio, plot, nobody)
            .expectStatus()
            .isNotFound
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Record $nobody does not exist")
        unlink(admin, predio, plot, nobody).expectStatus().isNotFound
    }

    @Test
    fun `link and unlink leave an UPDATE on both histories, and a repeat leaves nothing`() {
        val plot = createRecord(admin, predio, mapOf("codigo" to "P-1"))
        val owner = createRecord(admin, titular, mapOf("nombre" to "Marta"))

        link(admin, predio, plot, owner).expectStatus().isNoContent
        link(admin, predio, plot, owner).expectStatus().isNoContent
        unlink(admin, predio, plot, owner).expectStatus().isNoContent

        history(admin, predio, plot)
            // newest first: unlink, link, create. the repeated link wrote nothing.
            .jsonPath("$.length()")
            .isEqualTo(3)
            .jsonPath("$[0].operation")
            .isEqualTo("UPDATE")
            .jsonPath("$[0].changes[0].field")
            .isEqualTo(relationship)
            .jsonPath("$[0].changes[0].before")
            .isEqualTo(owner)
            .jsonPath("$[1].changes[0].after")
            .isEqualTo(owner)
        history(admin, titular, owner)
            .jsonPath("$[1].operation")
            .isEqualTo("UPDATE")
            .jsonPath("$[1].changes[0].after")
            .isEqualTo(plot)
    }

    @Test
    fun `a field the caller could not write is not reported as cleared`() {
        val role = newRole()
        grant(role, listOf("READ", "CREATE", "UPDATE"))
        lockField(role, predio, "valor")
        val token = newUserToken(role)
        val plot = createRecord(admin, predio, mapOf("codigo" to "S-1", "valor" to 5))

        client
            .put()
            .uri("/api/objects/$predio/records/$plot")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "S-2")))
            .exchange()
            .expectStatus()
            .isOk

        val body =
            history(admin, predio, plot)
                .jsonPath("$[0].changes.length()")
                .isEqualTo(1)
                .jsonPath("$[0].changes[0].field")
                .isEqualTo("codigo")
                .returnResult()
                .responseBody!!
                .decodeToString()
        assertThat(body).doesNotContain("\"valor\"")
    }

    // ---- helpers ----

    private fun createObject(
        name: String,
        fields: List<Map<String, Any>>
    ) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to name, "fields" to fields))
            .exchange()
            .expectStatus()
            .isCreated
    }

    private fun createRecord(
        token: String,
        target: String,
        attributes: Map<String, Any>
    ): String =
        client
            .post()
            .uri("/api/objects/$target/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .substringAfter("\"id\":\"")
            .substringBefore("\"")

    private fun link(
        token: String,
        target: String,
        id: String,
        otherId: String
    ) = client
        .post()
        .uri("/api/objects/$target/records/$id/related/$relationship")
        .header(HttpHeaders.AUTHORIZATION, token)
        .bodyValue(mapOf("otherId" to otherId))
        .exchange()

    private fun unlink(
        token: String,
        target: String,
        id: String,
        otherId: String
    ) = client
        .delete()
        .uri("/api/objects/$target/records/$id/related/$relationship/$otherId")
        .header(HttpHeaders.AUTHORIZATION, token)
        .exchange()

    private fun related(
        token: String,
        target: String,
        id: String
    ) = client
        .get()
        .uri("/api/objects/$target/records/$id/related/$relationship")
        .header(HttpHeaders.AUTHORIZATION, token)
        .exchange()
        .expectStatus()
        .isOk
        .expectBody()

    private fun history(
        token: String,
        target: String,
        id: String
    ) = client
        .get()
        .uri("/api/objects/$target/records/$id/history")
        .header(HttpHeaders.AUTHORIZATION, token)
        .exchange()
        .expectStatus()
        .isOk
        .expectBody()

    private fun newRole(): String {
        val name = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to "Hardening", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun ownRecordsRole(): String {
        val role = newRole()
        grant(role, listOf("READ", "CREATE", "UPDATE"))
        client
            .put()
            .uri("/api/roles/$role")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("ownRecordsOnly" to true))
            .exchange()
            .expectStatus()
            .isOk
        return role
    }

    private fun grant(
        role: String,
        actions: List<String>
    ) {
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to actions.map { mapOf("objectName" to null, "action" to it, "allowed" to true) }))
            .exchange()
            .expectStatus()
            .isOk
    }

    // readable, not writable
    private fun lockField(
        role: String,
        target: String,
        fieldName: String
    ) {
        client
            .put()
            .uri("/api/roles/$role/field-permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("fields" to listOf(mapOf("objectName" to target, "fieldName" to fieldName, "read" to true, "write" to false))))
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun newUserToken(role: String): String {
        val email = "${uniqueName("member")}@chawpi.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Member", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }
}
```

- [ ] **Step 6: Run the ITs against the tunnel (external-DB mode)**

```bash
cd /Users/jorge/IdeaProjects/chawpi
nc -z localhost 5443 && echo "tunnel up" || echo "IT step not run: tunnel down"
CHAWPI_TEST_DB_HOST=localhost CHAWPI_TEST_DB_PORT=5443 CHAWPI_TEST_DB_NAME=chawpi_test CHAWPI_TEST_DB_USERNAME=chawpi CHAWPI_TEST_DB_PASSWORD=chawpi \
  ./gradlew :chawpi-core:integrationTest --rerun \
  --tests 'chawpi.core.api.CoreHardeningApiTest' --tests 'chawpi.core.api.RelationshipApiTest' \
  --tests 'chawpi.core.api.AuditApiTest' --tests 'chawpi.core.api.PermissionEnforcementTest' --tests 'chawpi.core.api.RecordApiTest'
```
Expected: `tunnel up`, then all selected suites PASS. `CoreHardeningApiTest` must pass 4/4; the other suites prove nothing else moved. The first three `CoreHardeningApiTest` tests fail on the pre-task code: the link returns 204 instead of 404, the link returns 500 instead of 404, and the history has 1 entry instead of 3. The fourth may already pass (see Decisions). If the tunnel is down, report "IT step not run: tunnel down" and do not claim a pass. Only one external-DB suite may run at a time against `chawpi_test`.

- [ ] **Step 7: ADR-0025 and HISTORY**

In `docs/adr/0025-extension-spis.md`, replace the whole "Known gaps" paragraph and its two bullets (from the line `**Known gaps, inherited from sapgis.** Both are scheduled for a hardening task after P2:` through the line ending `fact just left alone.`) with:

```markdown
**Known gaps, inherited from the original — both fixed (P2 Task 16, the one deliberate behaviour change
against the original in P2).**
- `RelatedRecordService.link`/`unlink` now hold both records to the record api's rules: same
  organization, and only the caller's own under `own_records_only`. A missing, foreign or not-yours
  record answers `404`, as on GET/PUT/DELETE (the original answered `204`, or `500` for a missing one on
  link). Every effective link or unlink writes one `UPDATE` row on each record's history, keyed by the
  relationship name. No new audit operation: the `audit_log_operation_valid` CHECK stays core's
  `CREATE, UPDATE, DELETE`, with `ISSUE` added by chawpi-documents.
- The audit `after` snapshot and `RecordChange.after` are the row re-read with the full definition
  after the write, not what the write returned. A locked field no longer reads as cleared, whatever
  a `RecordStore` hands back. Listeners get the full row, not the caller's projection: automations
  judge and act on the whole record as the platform (ADR-016). Cost: one SELECT per create/update.
```

In `docs/HISTORY.md`, insert directly under the line `Newest first. Architectural reasoning lives in \`docs/adr/\`; this file records what shipped.` (and its blank line) a new section:

```markdown
## 2026-09-25 — A link answers to the same rules as the record

The one deliberate behaviour change against the original in the library split. Linking or unlinking two
records now checks both the way the record api does -- same organization, and only your own under
`own_records_only` -- and answers `404` otherwise instead of quietly succeeding. Each link or unlink
shows up as an `UPDATE` in both records' histories. The history's "after" is now the record as stored,
so a field the editor could not write no longer looks cleared. ADR-0025 records both.

```

- [ ] **Step 8: Format, whole core green, leave uncommitted**

```bash
./gradlew :chawpi-core:ktlintFormat :chawpi-core:ktlintCheck :chawpi-core:test
git status --short backend/chawpi-core docs/adr/0025-extension-spis.md docs/HISTORY.md
```
Expected: ktlint and unit tests pass; changes are uncommitted.

## Out of scope (later phases)

- **P3 (`chawpi-integration-tests`):** porting the original's API ITs that exercise modules — `WorkflowApiTest`, `AutomationApiTest`, `DocumentApiTest`, `DocumentTypeApiTest`, `PageApiTest`, `ViewApiTest`, `FormApiTest`, `LayerApiTest`, `AgentApiTest`, `AgentEmbabelTest`, `AgentToolsTest` — plus the geometry assertions removed from core ITs (`MetadataApiTest` geometry cases, `PermissionEnforcementTest` locked geometry, `FieldApiTest` system fields with `workflow_state`), the core-only app asserting GIS routes absent, and the schema diff against the original. `PageApiTest` replays the original's V10/V11 by hand; P3 decides what replaces that (M4 dropped those data statements).
- **P7:** `docs/modules/<m>.md`, the new ADRs (0024, 0028–0030), CLAUDE.md, CI.
- Examples (P6) and frontend (P4/P5).

## Self-review

- **Spec coverage.** Covered: each of the eight modules (Tasks 2–9); the auto-config, `enabled` flag, properties and migration of each; the SPI implementations (gis handler/bbox/LayerCleanup/MAP, workflow adapter/system column/WORKFLOW, automation listener/FieldUsage, documents `DocumentIssuer`, pages registry + HISTORY); the three metadata routes (Tasks 2, 3, 9); the ported unit tests (all 7 of the original's module unit tests); the starters + BOM (Task 14); `EmbabelGate` (Task 8); `@ChawpiApplication` (Task 1); cross-module wiring and route parity (Task 15); and the ADR-0025 known gaps (Task 16). The module ITs are P3 (Out of scope).
- **Placeholders.** None. Every non-mechanical file is written out in full. The ellipses that remain only name existing code blocks to replace.
- **Type consistency.** Checked:
  - `ComponentType`, `PageComponentProvider`, `GeneratedComponent`, `PageComponentTypes` (Tasks 9/12/13/15);
  - `DocumentIssuer`/`NoDocumentIssuer`/`DocumentIssuerAdapter` (Tasks 5/10/15);
  - `RecordTransitions`/`AgentTransition`/`WorkflowRecordTransitions` (Tasks 8/11/15);
  - `GEOMETRY`/`GEOMETRIES`/geometry extension properties (Tasks 7/12/15);
  - `RelatedRecordService`'s new last constructor parameter `audit` (Task 16) matches the `ChawpiDataAutoConfiguration` bean. P2 modules call only its `relatedRows`/`relatedRecords` methods and never construct it, so Tasks 6/8 are unaffected.
- **Review Focus.** Each of the five lines names the test that pins it. Task 16 pins its own inputs:
  - an own-records-only user linking someone else's record, on either end;
  - a missing `otherId`;
  - a repeated link;
  - a locked field in the history.
- **Behaviour changes.** Task 16 is the deliberate one (link/unlink 404s and audit rows; full-row audit snapshot). The side effects of the split are M10 (the order of the component list in one error text) and M11 (`chawpi.agent.enabled=false` removes the routes).

