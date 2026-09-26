# P1 — Backend core (`chawpi-core` + `chawpi-test`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move sapgis's `common`, `platform`, `identity`, `organization`, `metadata`, `data` and `audit` packages into the published library `backend/chawpi-core` (package `chawpi.core.*`): cycle-free, module-agnostic, auto-configured, with configurable schemas, per-module Flyway migrations and the extension SPIs that the optional modules implement in P2. Also add the published test fixtures `backend/chawpi-test`.

**Architecture:** Code is copied from `/Users/jorge/IdeaProjects/sapgis/backend` with a scripted package rename. Then the few non-mechanical seams are rewritten by hand: field types become Strategy + Registry (`FieldTypeHandler`/`FieldTypeRegistry`); query parameters, system columns and listeners become SPIs; schema names come from a `ChawpiSchemas` bean; migrations run per module through `ChawpiMigrations`; beans are declared in `@AutoConfiguration` classes. Core never mentions geometry. Everything GIS does today can be plugged back in P2 through the SPIs, and the REST JSON stays identical when chawpi-gis is present.

**Tech Stack:** Kotlin 2.4.20, JDK 25, Spring Boot 4.1.1 WebFlux + R2DBC (`DatabaseClient`), Spring Security OAuth2 resource server (own HS256 JWT), Jackson 3 (`tools.jackson`), Flyway 12.4 over JDBC, PostgreSQL 18 (plain `postgres:18` image, no PostGIS), JUnit 5 + AssertJ, Testcontainers 2.0.5, ktlint 1.7.1 via ktlint-gradle 14.2.0.

**Spec:** `docs/superpowers/specs/2026-09-25-chawpi-libraries-design.md`

## Global Constraints

- **NEVER run `git commit`, `git push` or `git stash`.** Leave every change uncommitted in the working tree. Each task ends with `git status --short` to confirm nothing was committed.
- sapgis (`/Users/jorge/IdeaProjects/sapgis`) is READ-ONLY. Copy from it, never edit it.
- Base package `chawpi.core` (groupId `chawpi`, artifact `chawpi-core`). Package map: `com.sapgis.common`→`chawpi.core.common`, `com.sapgis.platform`→`chawpi.core.platform`, `com.sapgis.identity`→`chawpi.core.identity` (except `Admin*`→`chawpi.core.admin`), `com.sapgis.organization`→`chawpi.core.organization`, `com.sapgis.metadata`→`chawpi.core.metadata` (plus `ObjectSchemaManager`, `FieldValueCodec`), `com.sapgis.data`→`chawpi.core.data`, `com.sapgis.audit`→`chawpi.core.audit`. Auto-configs go in `chawpi.core.autoconfigure`.
- `chawpi.core` never imports, names in code, or depends on `chawpi.views|forms|pages|workflow|automation|documents|gis|agent`. It does not mention geometry, PostGIS, bbox or GeoJSON. (The column name `workflow_state` and the audit value `ISSUE` stay as data, see Rulings R8/R10.)
- Formatting: repo `.editorconfig` (Kotlin 4 spaces, 160 columns). Every task ends with `./gradlew :chawpi-core:ktlintFormat :chawpi-core:ktlintCheck` (and the same for `:chawpi-test` once it exists). Both must pass.
- Comments in English, caveman style: short, say why, never restate the code.
- No JPA/Hibernate, no Spring Data repositories. SQL goes through `DatabaseClient`. Values are always bound, never interpolated. Identifiers go through `SqlIdentifier`. Schema names go through `ChawpiSchemas` (validated at boot).
- REST routes and JSON of core endpoints stay the same as sapgis: same paths, verbs, status codes, property names and values. The only JSON change is the key order of the flattened extension properties (Ruling R5).
- Config keys live under `chawpi.*`; env vars are `CHAWPI_*`. Nothing named `sapgis`/`SAPGIS` survives in `backend/`.
- No component scanning of library code. Beans are declared in `@AutoConfiguration` classes listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, with `@ConditionalOnMissingBean` so apps can override them.
- Docker is a REMOTE daemon (ssh socket): Testcontainers-mapped ports are NOT reachable from this machine. Unit-test steps (`./gradlew :chawpi-core:test`) never need it. Integration tests ALWAYS run in external-DB mode against the tunnelled plain `postgres:18`: first `nc -z localhost 5443`; if it fails, report "IT step not run: tunnel down" and do not fake a pass. Command prefix (all five vars, always): `CHAWPI_TEST_DB_HOST=localhost CHAWPI_TEST_DB_PORT=5443 CHAWPI_TEST_DB_NAME=chawpi_test CHAWPI_TEST_DB_USERNAME=chawpi CHAWPI_TEST_DB_PASSWORD=chawpi`.
- Versions only from `gradle/libs.versions.toml`. No new libraries.

## Design rulings (binding for every task)

- **R1: package DAG inside core.** `common ← platform ← identity ← metadata ← audit ← data`, with `admin` and `organization` on top and `autoconfigure` above everything. Three moves break sapgis's cycles:
  - `ObjectSchemaManager` moves into `metadata`. It is the physical projection of metadata, and moving it breaks metadata↔data.
  - Record-level relationship walking (`relatedRecords`, `relatedRows`, `link`, `unlink`) moves from `metadata.RelationshipService` to a new `data.RelatedRecordService`.
  - `AdminController`/`AdminService`/`AdminDtos` move to `chawpi.core.admin`. This breaks identity↔metadata.

  Routes do not change. Task 12 enforces the DAG.
- **R2: `FieldType` is an open value.** It becomes `data class FieldType(val name: String)` with companion constants for the 12 core types (TEXT…RELATION). Parsing moves to `FieldTypeRegistry.parse`, which lists the core types first and then module types in bean order. With gis present, the error text `must be one of TEXT, …, RELATION, GEOMETRY` is identical to sapgis's.
- **R3: extra field attributes live in module-owned columns on `custom_fields`.** A handler declares `attributeColumns` (for gis: `geometry_type`, `srid`, `dimension`). Core reads and writes them generically into `CustomField.attributes`. The module's own migration adds the columns and their CHECKs, so the final schema is identical to sapgis's. This replaces `CustomField.geometryType/srid/dimension`.
- **R4: the `custom_fields_type_valid` CHECK.** Core's CHECK lists only the 12 core types. A module that adds a type drops and re-adds the constraint under the same name, with its type appended: `DROP CONSTRAINT custom_fields_type_valid; ADD CONSTRAINT custom_fields_type_valid CHECK (type IN (<12 core>, 'GEOMETRY'))`. With gis the final schema is identical to sapgis. Limit: only one type-adding module can own the list this way (gis is the only one). ADR-0027 records it.
- **R5: JSON extension points.**
  - `FieldResponse`, `ObjectResponse` and `ObjectDefinitionResponse` get a last constructor property `@get:JsonIgnore val extensions: Map<String, Any?> = emptyMap()`, written flat by a `@JsonAnyGetter fun flattened()`. It is filled by `FieldTypeRegistry.fieldProperties`/`objectProperties`. The ignore/any-getter split keeps a creator parameter and an any-getter from fighting over one logical property.
  - Every installed handler contributes to every field and object. That is how gis keeps answering `"geometry": null` on a TEXT field.
  - `FieldRequest` captures unknown properties (`geometryType`, `srid`, `dimension`) through `@JsonAnySetter` into `extensions`.
  - Records get the same treatment: `RecordRequest`/`RecordResponse` flatten `sections` (for example `geometries`) the same way.
  - With gis present, keys and values are identical to sapgis. Only key order differs (flattened keys come after the declared ones). JSON object order is not part of the contract (RFC 8259).
  - Without gis, `geometry` and `geometries` are absent.
- **R6: section semantics = sapgis geometry semantics.**
  - A field whose handler has `section != null` is written only when its key is present in that section. `null` clears it.
  - Sections list every field of that type, null included.
  - A section key that names no such field gets `handler.unknownSectionKey(...)` (gis: `Unknown geometry 'x'`).
  - Section fields are not audited. sapgis audits `attributes` only, and core keeps that.
- **R7: query parameters.** `RecordQuery.bbox/geometry` are replaced by `criteria: List<RecordCriterion>`, produced by `RecordQueryContributor` beans through the `RecordQueryParser` bean. Core reserves `page,size,sort,dir,q,limit`. Contributors reserve their own names (gis: `bbox`, `geometry`). Without gis, `?bbox=` is an equality filter on an unknown field: `400 Unknown field 'bbox'`.
- **R8: system columns.**
  - `SqlIdentifier.SYSTEM_COLUMN_LIST` becomes the `SystemColumns` bean. It lists the core ALWAYS columns, then the `SystemColumnContributor` columns, then the RESERVED `version`. `SystemColumn.scope` becomes a `String`, so workflow can say `"WORKFLOW"`.
  - Core keeps the record-state mechanics: the physical column `workflow_state` (as `ObjectSchemaManager.STATE_COLUMN`), `addStateColumn`, `RecordRow.state`, `RecordStore.transitionState`. `RecordResponse.state` is core JSON (ADR-013). Only the name reservation comes from the workflow module.
- **R9: ports become lists.**
  - `MetadataService` takes `List<FieldUsage>` and `List<ObjectRemovalListener>`. `RecordService` takes `List<RecordChangeListener>`. Iteration follows `@Order`, same transaction as before.
  - `WorkflowStates` gets the `NoWorkflowStates` null object through `@ConditionalOnMissingBean`. A module auto-config that provides one must declare `@AutoConfiguration(before = [ChawpiDataAutoConfiguration::class])`.
- **R10: audit keeps the documents hook as data.**
  - `audit_log.document_id uuid` is created by core without an FK. `AuditOperation.ISSUE` stays in the enum. `AuditEntry.documentId` stays in the JSON.
  - Core's CHECK allows `CREATE, UPDATE, DELETE`. The documents migration (P2) adds FK `audit_log_document_id_fkey … ON DELETE SET NULL` and re-adds the CHECK with `ISSUE`. Final schema is identical.
- **R11: no Spring Data repositories.** `UserRepository` and `OrganizationRepository` become `DatabaseClient` classes with the same method names. Reason: `@Table(schema="sapgis")` cannot follow a configurable schema, and repository scanning of library packages is forbidden.
- **R12: stereotypes stay on classes.** `@Service`, `@Component`, `@Repository` and `@RestController` remain:
  - kotlin-spring opens those classes, which the `@Transactional` proxies need.
  - `@RestController` is how WebFlux finds handlers.

  Library packages are never scanned. Every bean is declared once, in an auto-config.
- **R13: schemas.**
  - `ChawpiSchemas(metadata, data)` validates both names against `^[a-z][a-z0-9_]{0,62}$` and requires them to differ.
  - Kotlin SQL uses `${schemas.metadata}.table` (unquoted, like sapgis's literal) and `schemas.dataTable(name)` (quoted).
  - Migrations use Flyway placeholders `${metadataSchema}` and `${dataSchema}`.
- **R14: migrations.**
  - One Flyway run per `ModuleMigration(name, location, order)`, in `order`. History table `flyway_history_<name>`, `baselineOnMigrate(true)` at version `0`, placeholders as in R13.
  - Core's `V1__core.sql` is the final sapgis schema restricted to core tables, with the renamed schema, no geometry columns on `custom_fields`, and `postgis` removed (pgcrypto kept, vector still optional).
  - Dev seed: `ModuleMigration("core_seed", "classpath:db/chawpi/core-seed", 10)`, only when `chawpi.seed.dev=true`. User `admin@chawpi.local` / `admin`. It stores a precomputed bcrypt hash instead of calling `crypt()`: with several schema pairs in one database, pgcrypto lives in whichever schema created it first, and `crypt()` would not resolve from the others.
  - Order convention: core `0`, seed `10`, P2 modules `100+`.
- **R15: configuration.**
  - `ChawpiEnvironmentPostProcessor` adds lowest-precedence defaults:
    - `spring.r2dbc.*` built from `chawpi.database.*`;
    - `chawpi.database.*` from `CHAWPI_DB_*`;
    - `chawpi.security.jwt.secret` from `CHAWPI_JWT_SECRET` (empty when unset);
    - `spring.webflux.problemdetails.enabled=true`.
  - The library ships no `application.yml` and no usable JWT secret. Boot fails with `chawpi.security.jwt.secret must be at least 32 bytes` until one is set.
- **R16: SPIs not owned by core.**
  - `ObjectMetadataContributor`: core's `ObjectMetadataController` loses the 3 routes `GET /api/metadata/objects/{object}/views|forms|pages`. views/forms/pages re-add them in P2 under the same URLs. No Kotlin type is needed (ADR-0025).
  - `PageComponentProvider` belongs to chawpi-pages. pages registers the HISTORY component itself.
  - `DocumentIssuer` belongs to chawpi-automation.
- **R17: branding.** The problem `type` is `${chawpi.web.problem-base-uri}/<status>`, default `https://chawpi.dev/problems`. `/api/health` answers `application = ${spring.application.name:chawpi}`. `SapgisException` → `ChawpiException`.

## Core SPIs produced by this plan

| SPI | Package | Task | Default in core |
|---|---|---|---|
| `FieldTypeHandler`, `FieldTypeRegistry`, `FieldType` | `chawpi.core.metadata` | 4 | 12 scalar handlers (`ScalarFieldTypes.ALL`) |
| `ObjectRemovalListener`, `FieldUsage` (lists) | `chawpi.core.metadata` | 4 | none (empty lists) |
| `SystemColumnContributor`, `SystemColumns`, `SystemColumn` | `chawpi.core.platform` | 2 | none |
| `RecordQueryContributor`, `RecordCriterion`, `RecordQueryParser` | `chawpi.core.data` | 6 | none |
| `RecordChangeListener` (list) | `chawpi.core.data` | 6 | none |
| `WorkflowStates` + `NoWorkflowStates` | `chawpi.core.data` | 6 | `NoWorkflowStates` |
| `ModuleMigration`, `ChawpiMigrations` | `chawpi.core.platform` | 7 | core + optional seed |

## Out of scope (later phases)

- Module code (views, forms, pages, workflow, automation, documents, gis, agent), their migrations, and starters (`chawpi-spring-boot-starter*`, which bring the runtime drivers and actuator): P2. P1 only defines the SPIs they implement.
- `@ChawpiApplication` meta-annotation: P2, with the starters.
- The full 20-IT suite against a full app, plus the core-only app with "GIS routes absent": P3 (`chawpi-integration-tests`).
- Ports for SQL *across modules* (`ObjectCatalog`, `UserDirectory`): P2, added when a module needs them. Inside core, SQL across core tables (identity reading `custom_fields`, audit joining `users`, organization writing roles) stays. It is one library with one migration owner.

## Review Focus

1. **Wire shape with a module installed.** A TEXT field must still carry the module's key with `null` (gis: `"geometry": null`). A flat record must still carry an empty section (`"geometries": {}`). No `extensions` or `sections` key may leak into the JSON. Pinned by `MetadataJsonTest` and `MetadataMapperTest` (Tasks 4–5), `RecordJsonTest` (Task 6), and `MeasureFieldTypeApiTest` (`fields[0].measure` null, flat object `measures` empty; Task 11).
2. **Non-default schema names** (`chawpi.database.metadata-schema=acme_meta`, `data-schema=acme_data`). Migrations, DDL, record SQL, audit and login must follow them and never touch `chawpi`/`app_data`. Pinned by `ChawpiSchemasTest` (Task 2), `ObjectSchemaManagerTest` (relation FK into `acme_data`, Task 5), `ChawpiMigrationsTest` placeholders (Task 8), and `CustomSchemaApiTest` (Task 11).
3. **A stored field whose type's module was removed.** Metadata must still list it. Record operations must answer 409 `Field type 'X' is not installed`, not a 500 or a NullPointerException. Pinned by `FieldTypeRegistryTest.a stored field whose module is gone answers a conflict that names the type` (Task 4); `CustomFieldRepository.mapField` builds `FieldType(name)` without parsing (Task 5).
4. **Module-only input sent to a core-only app.** A `"geometries"` body section, a `geometryType` field property and a GEOMETRY type are handled like sapgis handles unknown input: the first two are ignored, the type is refused with 400 on `type`. `?bbox=` answers 400 `Unknown field 'bbox'` instead of silently matching everything. Pinned by `RecordQueryParserTest` (Task 6) and `CoreOnlyApiTest` (Task 11).
5. **Booting the library with no app configuration.** No JWT secret → boot fails naming `chawpi.security.jwt.secret`. Boot's generated in-memory user must not appear. An app's own `WorkflowStates`/handler/contributor beans must win or join. The dev seed must stay off unless `chawpi.seed.dev=true`. Pinned by `ChawpiAutoConfigurationTest` and `ChawpiEnvironmentPostProcessorTest` (Task 9).

---

## File structure (end state of P1)

```
backend/chawpi-core/
  build.gradle.kts
  src/main/kotlin/chawpi/core/
    common/        Actions, Errors (ChawpiException…), GlobalExceptionHandler, HealthController, PageResponse
    platform/      Rows, SqlIdentifier, SystemColumns (+Contributor), ChawpiSchemas, ChawpiDatabaseProperties,
                   JwtProperties, ChawpiWebProperties, ModuleMigration, ChawpiMigrations
    identity/      AccessPolicy (+FieldAccess), AuthController, AuthService, CurrentUser, JwtService,
                   RoleDirectory, RoleQueries, User (+UserRepository)
    metadata/      FieldType, FieldTypeHandler, FieldTypeRegistry, ScalarFieldTypes, FieldValueCodec,
                   CustomObject (+CustomField, ObjectDefinition), MetadataDtos, MetadataPorts, MetadataRepository,
                   MetadataMapper, MetadataService, MetadataController, ObjectSchemaManager, Relationship,
                   RelationshipService, RelationshipController, CallerPermissions{Controller,Service}
    audit/         AuditController, AuditDiff, AuditQueryService, AuditService
    data/          RecordStore, PhysicalTableRecordStore, RecordService, RecordController, RecordChanges,
                   RecordQueryContributor (+RecordCriterion, RecordQueryParser), WorkflowStates (+NoWorkflowStates),
                   RelatedRecordService, RelatedRecordController
    admin/         AdminController, AdminDtos, AdminService
    organization/  Organization (+OrganizationRepository), OrganizationController, OrganizationService
    autoconfigure/ ChawpiPlatformAutoConfiguration, ChawpiSecurityAutoConfiguration, ChawpiMetadataAutoConfiguration,
                   ChawpiDataAutoConfiguration, ChawpiAdminAutoConfiguration, ChawpiEnvironmentPostProcessor
  src/main/resources/
    META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    META-INF/spring.factories                       (EnvironmentPostProcessor)
    db/chawpi/core/V1__core.sql
    db/chawpi/core-seed/V1__seed_dev.sql
  src/test/kotlin/chawpi/core/
    ChawpiCoreTestApplication.kt                    (@SpringBootConfiguration @EnableAutoConfiguration, no scan)
    platform/ metadata/ data/ autoconfigure/        unit tests
    api/                                            ported ITs + MeasureFieldTypeApiTest + CustomSchemaApiTest
    fixtures/                                       MEASURE test field type (proves the SPI on plain postgres)
    architecture/CoreArchitectureTest.kt
  src/test/resources/db/chawpi/measure-test/V1__measure.sql
backend/chawpi-test/
  build.gradle.kts
  src/main/kotlin/chawpi/test/  ChawpiIntegrationTest, ChawpiTestDatabase, LoginBody
  src/test/kotlin/chawpi/test/  ChawpiTestDatabaseTest
docs/adr/0025-extension-spis.md  0026-per-module-migrations.md  0027-gis-optional.md
```

Shared shell variables used in the task steps (define them in each shell you open):

```bash
cd /Users/jorge/IdeaProjects/chawpi
SRC=/Users/jorge/IdeaProjects/sapgis/backend/src/main/kotlin/com/sapgis
TSRC=/Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis
DST=backend/chawpi-core/src/main/kotlin/chawpi/core
TDST=backend/chawpi-core/src/test/kotlin/chawpi/core
```

Two sed rules are reused across tasks:

- **Rename rule** (every copied `.kt`): `sed -i '' -e 's/com\.sapgis\./chawpi.core./g' -e 's/SapgisException/ChawpiException/g' <files>`
- **Schema rule** (every copied `.kt` that holds SQL; comment lines are skipped): `sed -i '' -E -e '/^[[:space:]]*\/\//!s/([^a-zA-Z_.])sapgis\.([a-z_]+)/\1${schemas.metadata}.\2/g' <files>`
  After it, every class whose SQL now says `${schemas.metadata}` needs the constructor parameter `private val schemas: ChawpiSchemas` and `import chawpi.core.platform.ChawpiSchemas`. The compiler lists the classes.

---

### Task 1: `chawpi-core` module skeleton

**Files:**
- Create: `backend/chawpi-core/build.gradle.kts`
- Create: `backend/chawpi-core/src/main/kotlin/chawpi/core/.gitkeep`, `backend/chawpi-core/src/test/kotlin/chawpi/core/.gitkeep`

**Interfaces:**
- Consumes: convention plugins `chawpi.spring-module`, `chawpi.publishing`, `chawpi.integration-test` (P0); catalog aliases `libs.spring.boot.starter.*`, `libs.jackson.module.kotlin`, `libs.kotlinx.coroutines.reactor`, `libs.flyway.core`, `libs.r2dbc.postgresql`, `libs.postgresql.jdbc`, `libs.flyway.postgresql`.
- Produces: Gradle project `:chawpi-core` (auto-discovered by `settings.gradle.kts`), constrained by `chawpi-bom` automatically. Task 10 adds `testImplementation(project(":chawpi-test"))`.

- [ ] **Step 1: Write `backend/chawpi-core/build.gradle.kts`**

```kotlin
plugins {
    id("chawpi.spring-module")
    id("chawpi.publishing")
    id("chawpi.integration-test")
}

description = "Chawpi core: metadata, records, identity, organizations, audit"

dependencies {
    // an app on chawpi-core is a webflux + r2dbc + jwt app. these are part of the api.
    api(libs.spring.boot.starter.webflux)
    api(libs.spring.boot.starter.data.r2dbc)
    api(libs.spring.boot.starter.security)
    api(libs.spring.boot.starter.oauth2.resource.server)
    api(libs.spring.boot.starter.validation)
    api(libs.jackson.module.kotlin)
    api(libs.kotlinx.coroutines.reactor)
    // flyway runs over jdbc at startup (ADR-008). drivers come with the starter (P2).
    implementation(libs.flyway.core)

    testImplementation(libs.spring.boot.starter.webflux.test)
    testRuntimeOnly(libs.r2dbc.postgresql)
    testRuntimeOnly(libs.postgresql.jdbc)
    testRuntimeOnly(libs.flyway.postgresql)
}
```

- [ ] **Step 2: Create the source folders**

```bash
mkdir -p backend/chawpi-core/src/main/kotlin/chawpi/core backend/chawpi-core/src/test/kotlin/chawpi/core
touch backend/chawpi-core/src/main/kotlin/chawpi/core/.gitkeep backend/chawpi-core/src/test/kotlin/chawpi/core/.gitkeep
```

- [ ] **Step 3: Verify the module is discovered, builds and is in the BOM**

```bash
./gradlew projects -q | grep chawpi-core
./gradlew :chawpi-core:build
./gradlew :chawpi-bom:generatePomFileForMavenPublication && grep -A2 "<artifactId>chawpi-core</artifactId>" backend/chawpi-bom/build/publications/maven/pom-default.xml
```
Expected: `Project ':chawpi-core'` is listed, `BUILD SUCCESSFUL` (NO-SOURCE compile is fine), and the BOM shows `chawpi-core` with `<version>0.1.0</version>`.

- [ ] **Step 4: Format check and leave uncommitted**

```bash
./gradlew :chawpi-core:ktlintFormat :chawpi-core:ktlintCheck
git status --short | grep chawpi-core
```
Expected: ktlint passes. `git status` shows `?? backend/chawpi-core/` (untracked), and nothing is committed.

---

### Task 2: `common` + `platform` (errors, SQL identifiers, system columns, schemas, properties)

**Files:**
- Create (copied + renamed): `$DST/common/{Actions,Errors,GlobalExceptionHandler,HealthController,PageResponse}.kt`, `$DST/platform/{Rows,SqlIdentifier}.kt`
- Create (new): `$DST/platform/SystemColumns.kt`, `$DST/platform/ChawpiSchemas.kt`, `$DST/platform/ChawpiDatabaseProperties.kt`, `$DST/platform/JwtProperties.kt`, `$DST/platform/ChawpiWebProperties.kt`
- Not copied: `platform/DatabaseProperties.kt` (replaced by `ChawpiDatabaseProperties`), `platform/FlywayConfiguration.kt` (replaced in Task 8), `SapgisApplication.kt`
- Test: `$TDST/platform/SqlIdentifierTest.kt` (ported), `$TDST/platform/SystemColumnsTest.kt`, `$TDST/platform/ChawpiSchemasTest.kt`

**Interfaces:**
- Produces:
  - `abstract class ChawpiException(val status: HttpStatus, override val message: String, val violations: List<FieldViolation> = emptyList())` + `NotFoundException`, `ConflictException`, `ForbiddenException`, `UnauthorizedException`, `ValidationException(message, violations)` / `ValidationException(message, field, reason)`, `FieldViolation(field, message)`
  - `class GlobalExceptionHandler(problemBaseUri: String)`, `class HealthController(applicationName: String)`
  - `PageResponse<T>`, `PageRequest.of(page: Int?, size: Int?)`, `object Actions`
  - `object Rows`
  - `object SqlIdentifier` with `requireValidName(name, kind, field = "name", maxLength = 49)`, `requireValidObjectName(name, field = "name")`, `requireValidFieldName(name: String, reserved: Set<String>, field: String = "name")`, `indexName(table, column, suffix)`, `quote(identifier)`, `literal(value)`, `qualify(schema, table)`, `MAX_OBJECT_NAME`
  - `object SystemColumnScope { ALWAYS, RESERVED }` (String consts), `data class SystemColumn(name: String, type: String?, scope: String)`, `fun interface SystemColumnContributor { fun systemColumns(): List<SystemColumn> }`, `class SystemColumns(contributors: List<SystemColumnContributor>)` with `all: List<SystemColumn>`, `names: Set<String>`, `requireValidFieldName(name, field = "name")`
  - `class ChawpiSchemas(metadata: String, data: String)` with `metadata`, `data`, `dataTable(table): String`, `companion fun of(properties: ChawpiDatabaseProperties)`
  - `@ConfigurationProperties("chawpi.database") data class ChawpiDatabaseProperties(host, port, name, username, password, metadataSchema = "chawpi", dataSchema = "app_data", migrate = true)` with `jdbcUrl`
  - `@ConfigurationProperties("chawpi.security.jwt") data class JwtProperties(secret: String, issuer = "chawpi", ttl = 8h)`
  - `@ConfigurationProperties("chawpi.web") data class ChawpiWebProperties(problemBaseUri = "https://chawpi.dev/problems", corsAllowedOriginPatterns = ["http://localhost:*"])`

- [ ] **Step 1: Copy and rename**

```bash
mkdir -p $DST/common $DST/platform $TDST/platform
cp $SRC/common/{Actions,Errors,GlobalExceptionHandler,HealthController,PageResponse}.kt $DST/common/
cp $SRC/platform/{Rows,SqlIdentifier}.kt $DST/platform/
cp $TSRC/platform/SqlIdentifierTest.kt $TDST/platform/
sed -i '' -e 's/com\.sapgis\./chawpi.core./g' -e 's/SapgisException/ChawpiException/g' $DST/common/*.kt $DST/platform/*.kt $TDST/platform/*.kt
rm -f $DST/.gitkeep
```

- [ ] **Step 2: Hand edits in `common`**

`Actions.kt`: change the comment to `// the actions permissions are granted on. mirrors the CHECK constraint on the permissions table.`

`Errors.kt`: change the comment above `ChawpiException` to:
```kotlin
// domain errors carry their http status. handler turns them into problem+json.
// not sealed: modules add their own.
```

Replace `GlobalExceptionHandler.kt` entirely:
```kotlin
package chawpi.core.common

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.ResponseStatusException
import java.net.URI

// every error leaves as RFC 7807 problem+json. the type uri is the app's (chawpi.web.problem-base-uri).
@RestControllerAdvice
class GlobalExceptionHandler(
    problemBaseUri: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val base = problemBaseUri.trimEnd('/')

    @ExceptionHandler(ChawpiException::class)
    fun handleChawpi(ex: ChawpiException): ProblemDetail = problem(ex.status, ex.message, ex.violations)

    @ExceptionHandler(WebExchangeBindException::class)
    fun handleBinding(ex: WebExchangeBindException): ProblemDetail =
        problem(
            HttpStatus.BAD_REQUEST,
            "Request validation failed",
            ex.fieldErrors.map { FieldViolation(it.field, it.defaultMessage ?: "is invalid") }
        )

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail =
        problem(HttpStatus.valueOf(ex.statusCode.value()), ex.reason ?: "Request failed", emptyList())

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ProblemDetail {
        log.error("Unhandled error", ex)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", emptyList())
    }

    private fun problem(
        status: HttpStatus,
        detail: String,
        violations: List<FieldViolation>
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            type = URI.create("$base/${status.value()}")
            title = status.reasonPhrase
            if (violations.isNotEmpty()) {
                setProperty("errors", violations)
            }
        }
}
```

Replace `HealthController.kt` entirely:
```kotlin
package chawpi.core.common

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// same shape as before; the name is the app's (spring.application.name)
@RestController
@RequestMapping("/api/health")
class HealthController(
    private val applicationName: String
) {
    @GetMapping
    fun health(): Map<String, String> = mapOf("status" to "UP", "application" to applicationName)
}
```

- [ ] **Step 3: Hand edits in `SqlIdentifier.kt`**

Delete `enum class SystemColumnScope`, `data class SystemColumn`, `SYSTEM_COLUMN_LIST` and `SYSTEM_COLUMNS` (they move to `SystemColumns.kt`). Replace `requireValidFieldName` with:
```kotlin
    // reserved = SystemColumns.names: core's plus whatever the installed modules own
    fun requireValidFieldName(
        name: String,
        reserved: Set<String>,
        field: String = "name"
    ): String {
        requireValidName(name, "field", field)
        if (name in reserved) {
            throw ValidationException("Invalid field name '$name'", field, "is reserved by the platform")
        }
        return name
    }
```

- [ ] **Step 4: Write the failing tests**

In the ported `$TDST/platform/SqlIdentifierTest.kt`, replace the three tests that use `SYSTEM_COLUMNS`/`SYSTEM_COLUMN_LIST`/`SystemColumnScope` (`rejects field names that collide with platform columns`, `the reserved set is exactly the declared list, with nothing said twice`, `only the reserved name nothing creates has no type`) with this one:
```kotlin
    @Test
    fun `rejects field names that collide with platform columns`() {
        val reserved = setOf("id", "created_at")
        reserved.forEach { column ->
            assertThatThrownBy { SqlIdentifier.requireValidFieldName(column, reserved) }
                .describedAs(column)
                .isInstanceOf(ValidationException::class.java)
        }
        assertThat(SqlIdentifier.requireValidFieldName("codigo", reserved)).isEqualTo("codigo")
    }
```

`$TDST/platform/SystemColumnsTest.kt`:
```kotlin
package chawpi.core.platform

import chawpi.core.common.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SystemColumnsTest {
    private val core = SystemColumns(emptyList())

    @Test
    fun `core alone reserves the six always-present columns and version`() {
        assertThat(core.all.map { it.name })
            .containsExactly("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "version")
        assertThat(core.names).doesNotContain("workflow_state")
    }

    @Test
    fun `a contributed column sits between the core ones and the reserved ones`() {
        val columns = SystemColumns(listOf(SystemColumnContributor { listOf(SystemColumn("workflow_state", "TEXT", "WORKFLOW")) }))
        assertThat(columns.all.map { it.name })
            .containsExactly("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "workflow_state", "version")
        assertThatThrownBy { columns.requireValidFieldName("workflow_state") }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `only the reserved name nothing creates has no type`() {
        assertThat(core.all.filter { it.type == null }).allMatch { it.scope == SystemColumnScope.RESERVED }
    }

    @Test
    fun `a column declared twice fails at boot`() {
        assertThatThrownBy { SystemColumns(listOf(SystemColumnContributor { listOf(SystemColumn("id", "UUID", "X")) })) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("id")
    }
}
```

`$TDST/platform/ChawpiSchemasTest.kt`:
```kotlin
package chawpi.core.platform

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ChawpiSchemasTest {
    @Test
    fun `defaults are chawpi and app_data`() {
        val schemas = ChawpiSchemas.of(ChawpiDatabaseProperties())
        assertThat(schemas.metadata).isEqualTo("chawpi")
        assertThat(schemas.data).isEqualTo("app_data")
        assertThat(schemas.dataTable("predio__1234abcd")).isEqualTo("\"app_data\".\"predio__1234abcd\"")
    }

    @Test
    fun `refuses a schema name that is not a plain identifier`() {
        listOf("App", "app-data", "x;drop schema y", "", "1abc").forEach { name ->
            assertThatThrownBy { ChawpiSchemas(name, "app_data") }.describedAs(name).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `refuses one schema for both metadata and data`() {
        assertThatThrownBy { ChawpiSchemas("same", "same") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

- [ ] **Step 5: Run the tests to see them fail**

Run: `./gradlew :chawpi-core:test`
Expected: compilation FAILS, with `Unresolved reference 'SystemColumns'`, `'ChawpiSchemas'`, `'ChawpiDatabaseProperties'`.

- [ ] **Step 6: Write the platform files**

`$DST/platform/SystemColumns.kt`:
```kotlin
package chawpi.core.platform

// when a system column exists on a record table. a module may name its own scope ("WORKFLOW").
object SystemColumnScope {
    const val ALWAYS = "ALWAYS"
    const val RESERVED = "RESERVED"
}

// a name the platform keeps for itself. type is null when no column is created for it.
data class SystemColumn(
    val name: String,
    val type: String?,
    val scope: String
)

// a module that owns a column on record tables names it here, so no user field can take it
fun interface SystemColumnContributor {
    fun systemColumns(): List<SystemColumn>
}

// every name user fields must not take: core's, then the modules', then the reserved ones
class SystemColumns(
    contributors: List<SystemColumnContributor>
) {
    val all: List<SystemColumn> = CORE + contributors.flatMap { it.systemColumns() } + RESERVED

    val names: Set<String> = all.map { it.name }.toSet()

    init {
        val twice = all.groupBy { it.name }.filterValues { it.size > 1 }.keys
        check(twice.isEmpty()) { "system column declared twice: ${twice.joinToString(", ")}" }
    }

    fun requireValidFieldName(
        name: String,
        field: String = "name"
    ): String = SqlIdentifier.requireValidFieldName(name, names, field)

    companion object {
        val CORE: List<SystemColumn> =
            listOf(
                SystemColumn("id", "UUID", SystemColumnScope.ALWAYS),
                SystemColumn("organization_id", "UUID", SystemColumnScope.ALWAYS),
                SystemColumn("created_at", "DATETIME", SystemColumnScope.ALWAYS),
                SystemColumn("updated_at", "DATETIME", SystemColumnScope.ALWAYS),
                SystemColumn("created_by", "UUID", SystemColumnScope.ALWAYS),
                SystemColumn("updated_by", "UUID", SystemColumnScope.ALWAYS)
            )

        // reserved, never created: optimistic locking does not exist yet
        val RESERVED: List<SystemColumn> = listOf(SystemColumn("version", null, SystemColumnScope.RESERVED))
    }
}
```

`$DST/platform/ChawpiSchemas.kt`:
```kotlin
package chawpi.core.platform

// the two schema names every sql string uses. checked once here, so interpolating them is safe.
class ChawpiSchemas(
    metadata: String,
    data: String
) {
    // metadata + identity, owned by flyway
    val metadata: String = safe(metadata, "metadata-schema")

    // one physical table per custom object, built at runtime. ADR-004.
    val data: String = safe(data, "data-schema")

    init {
        require(this.metadata != this.data) { "chawpi.database.metadata-schema and data-schema must differ" }
    }

    fun dataTable(table: String): String = SqlIdentifier.qualify(data, table)

    companion object {
        private val SAFE = Regex("^[a-z][a-z0-9_]{0,62}$")

        fun of(properties: ChawpiDatabaseProperties): ChawpiSchemas = ChawpiSchemas(properties.metadataSchema, properties.dataSchema)

        private fun safe(
            name: String,
            property: String
        ): String {
            require(SAFE.matches(name)) { "chawpi.database.$property '$name' must match ${SAFE.pattern}" }
            return name
        }
    }
}
```

`$DST/platform/ChawpiDatabaseProperties.kt`:
```kotlin
package chawpi.core.platform

import org.springframework.boot.context.properties.ConfigurationProperties

// one place for db coords. r2dbc at runtime, jdbc once for flyway.
@ConfigurationProperties(prefix = "chawpi.database")
data class ChawpiDatabaseProperties(
    val host: String = "localhost",
    val port: Int = 5432,
    val name: String = "chawpi",
    val username: String = "chawpi",
    val password: String = "chawpi",
    val metadataSchema: String = "chawpi",
    val dataSchema: String = "app_data",
    // false when the app runs the migrations some other way
    val migrate: Boolean = true
) {
    val jdbcUrl: String
        get() = "jdbc:postgresql://$host:$port/$name"
}
```

`$DST/platform/JwtProperties.kt`:
```kotlin
package chawpi.core.platform

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// no default secret on purpose: a library must not ship one that works
@ConfigurationProperties(prefix = "chawpi.security.jwt")
data class JwtProperties(
    val secret: String = "",
    val issuer: String = "chawpi",
    val ttl: Duration = Duration.ofHours(8)
)
```

`$DST/platform/ChawpiWebProperties.kt`:
```kotlin
package chawpi.core.platform

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "chawpi.web")
data class ChawpiWebProperties(
    // problem+json "type" is this plus "/<status>"
    val problemBaseUri: String = "https://chawpi.dev/problems",
    // browser origins the api answers. dev servers by default.
    val corsAllowedOriginPatterns: List<String> = listOf("http://localhost:*")
)
```

- [ ] **Step 7: Run the tests to see them pass**

Run: `./gradlew :chawpi-core:test`
Expected: PASS: `SqlIdentifierTest` (all its tests, with the replaced one), `SystemColumnsTest` (4), `ChawpiSchemasTest` (3).

- [ ] **Step 8: Format, check nothing named sapgis, leave uncommitted**

```bash
./gradlew :chawpi-core:ktlintFormat :chawpi-core:ktlintCheck :chawpi-core:build
grep -rn -i sapgis backend/chawpi-core || echo "clean"
git status --short | head
```
Expected: build passes, `clean`, nothing committed.

---

### Task 3: `identity` (auth, JWT, current user, access policy)

**Files:**
- Create (copied + renamed): `$DST/identity/{AccessPolicy,AuthController,AuthService,CurrentUser,JwtService,RoleDirectory,RoleQueries}.kt`
- Create (rewritten): `$DST/identity/User.kt`
- Not copied here: `SecurityConfig.kt` (becomes `ChawpiSecurityAutoConfiguration`, Task 9), `AdminController.kt`, `AdminDtos.kt`, `AdminService.kt` (Task 7)
- Test: `$TDST/identity/JwtServiceTest.kt`

**Interfaces:**
- Consumes: `ChawpiSchemas` (`metadata`), `JwtProperties`, `Rows`, `UnauthorizedException`, `ForbiddenException` (Task 2).
- Produces (same names/signatures as sapgis):
  - `CurrentUser(roleQueries: RoleQueries)` with `require(): AuthenticatedUser`, `requireWithPermission(action, objectId: UUID? = null)`, `permittedObjects(user, action): RoleQueries.PermittedObjects`, `requirePermission(user, action, objectId: UUID? = null)`
  - `AuthenticatedUser(userId, organizationId, email, roles)` with `isAdmin`
  - `AccessPolicy(db, schemas)` with `fieldAccess(user, objectId): FieldAccess`, `ownRecordsOnly(user)`, `ownerFilter(user): UUID?`
  - `FieldAccess` with `canRead`, `canWrite`, `unrestricted`, `FULL`
  - `RoleQueries(db, schemas)`, `RoleDirectory(db, schemas)`
  - `JwtService(properties: JwtProperties, secretKey: javax.crypto.SecretKey)` with `issue(user: User, roles: List<String>): IssuedToken`, claims `org`, `email`, `roles`
  - `AuthService(users: UserRepository, roleQueries, passwordEncoder, jwtService)`, `AuthController(authService: AuthService, currentUser: CurrentUser)`. Route `POST /api/auth/login` and `GET /api/auth/me` stay the same.
  - `data class User(id, organizationId, email, passwordHash, displayName, enabled = true, createdAt = null, updatedAt = null)`, `data class Role(id, organizationId, name, label, ownRecordsOnly = false, createdAt = null)`
  - `@Repository class UserRepository(db: DatabaseClient, schemas: ChawpiSchemas)` with `suspend fun findByEmail(email: String): User?`

- [ ] **Step 1: Write the failing test** `$TDST/identity/JwtServiceTest.kt`

```kotlin
package chawpi.core.identity

import chawpi.core.platform.JwtProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import java.time.Duration
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

class JwtServiceTest {
    private val properties = JwtProperties(secret = "0123456789abcdef0123456789abcdef", issuer = "chawpi", ttl = Duration.ofHours(1))
    private val key = SecretKeySpec(properties.secret.toByteArray(Charsets.UTF_8), "HmacSHA256")

    @Test
    fun `issues a token the resource server accepts, carrying tenant, email and roles`() {
        val user =
            User(
                id = UUID.randomUUID(),
                organizationId = UUID.randomUUID(),
                email = "ana@chawpi.local",
                passwordHash = "unused",
                displayName = "Ana"
            )

        val issued = JwtService(properties, key).issue(user, listOf("ADMIN"))

        val jwt =
            NimbusReactiveJwtDecoder
                .withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build()
                .decode(issued.token)
                .block()!!
        assertThat(jwt.subject).isEqualTo(user.id.toString())
        assertThat(jwt.getClaimAsString(JwtService.CLAIM_ORGANIZATION)).isEqualTo(user.organizationId.toString())
        assertThat(jwt.getClaimAsString(JwtService.CLAIM_EMAIL)).isEqualTo("ana@chawpi.local")
        assertThat(jwt.getClaimAsStringList(JwtService.CLAIM_ROLES)).containsExactly("ADMIN")
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("chawpi")
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :chawpi-core:test --tests chawpi.core.identity.JwtServiceTest`
Expected: compilation FAILS with `Unresolved reference 'JwtService'` / `'User'`.

- [ ] **Step 3: Copy, rename, and point SQL at the configurable schema**

```bash
mkdir -p $DST/identity $TDST/identity
cp $SRC/identity/{AccessPolicy,AuthController,AuthService,CurrentUser,JwtService,RoleDirectory,RoleQueries}.kt $DST/identity/
sed -i '' -e 's/com\.sapgis\./chawpi.core./g' -e 's/SapgisException/ChawpiException/g' $DST/identity/*.kt
sed -i '' -E -e '/^[[:space:]]*\/\//!s/([^a-zA-Z_.])sapgis\.([a-z_]+)/\1${schemas.metadata}.\2/g' $DST/identity/*.kt
grep -ln 'schemas.metadata' $DST/identity/*.kt
```
Expected: the grep lists `AccessPolicy.kt`, `RoleDirectory.kt` and `RoleQueries.kt`. In each of those classes add the constructor parameter `private val schemas: ChawpiSchemas` right after `private val db: DatabaseClient`, and add `import chawpi.core.platform.ChawpiSchemas`.

In `RoleDirectory.kt` reword the class comment (core does not name modules):
```kotlin
// role names for features that reference a role without administering one (a module's transition
// rules, say). administering roles still goes through AdminService and MANAGE_ORGANIZATION.
```

- [ ] **Step 4: Rewrite `$DST/identity/User.kt`** (Ruling R11: no Spring Data)

```kotlin
package chawpi.core.identity

import chawpi.core.platform.ChawpiSchemas
import chawpi.core.platform.Rows
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

data class User(
    val id: UUID,
    val organizationId: UUID,
    val email: String,
    val passwordHash: String,
    val displayName: String,
    val enabled: Boolean = true,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
)

data class Role(
    val id: UUID,
    val organizationId: UUID,
    val name: String,
    val label: String,
    val ownRecordsOnly: Boolean = false,
    val createdAt: Instant? = null
)

// what login needs. plain sql: a @Table annotation cannot follow a configurable schema.
@Repository
class UserRepository(
    private val db: DatabaseClient,
    private val schemas: ChawpiSchemas
) {
    suspend fun findByEmail(email: String): User? =
        db
            .sql(
                "SELECT id, organization_id, email, password_hash, display_name, enabled, created_at, updated_at " +
                    "FROM ${schemas.metadata}.users WHERE email = :email"
            ).bind("email", email)
            .map { row, _ ->
                User(
                    id = Rows.uuid(row, "id"),
                    organizationId = Rows.uuid(row, "organization_id"),
                    email = Rows.string(row, "email"),
                    passwordHash = Rows.string(row, "password_hash"),
                    displayName = Rows.string(row, "display_name"),
                    enabled = Rows.bool(row, "enabled"),
                    createdAt = Rows.instantOrNull(row, "created_at"),
                    updatedAt = Rows.instantOrNull(row, "updated_at")
                )
            }.one()
            .awaitFirstOrNull()
}
```

`AuthService.kt` already calls `users.findByEmail(...)`, so it compiles unchanged against the new class.

- [ ] **Step 5: Run the test to see it pass**

Run: `./gradlew :chawpi-core:test`
Expected: PASS: `JwtServiceTest` plus everything from Task 2.

- [ ] **Step 6: Format, check, leave uncommitted**

```bash
./gradlew :chawpi-core:ktlintFormat :chawpi-core:ktlintCheck :chawpi-core:build
grep -rn -i "sapgis\|app_data" $DST/identity || echo "clean"
git status --short | head
```
Expected: build passes, `clean`, nothing committed.

---

### Task 4: `metadata` model + the field-type SPI (Strategy + Registry)

This is the heart of "GIS 100% optional": core knows 12 scalar types and nothing else. A module adds a type by declaring one `FieldTypeHandler` bean. The handler contributes DDL, indexes, select/bind SQL, validation, stored attributes, JSON properties and a record-payload section.

**Files:**
- Create (new): `$DST/metadata/FieldType.kt`, `$DST/metadata/FieldTypeHandler.kt`, `$DST/metadata/FieldTypeRegistry.kt`, `$DST/metadata/ScalarFieldTypes.kt`
- Create (rewritten from sapgis): `$DST/metadata/FieldValueCodec.kt` (was `data/FieldValueCodec.kt`), `$DST/metadata/CustomObject.kt`, `$DST/metadata/MetadataDtos.kt`, `$DST/metadata/MetadataPorts.kt`
- Test: `$TDST/metadata/FieldValueCodecTest.kt` (ported from `data/FieldValueCodecTest.kt`), `$TDST/metadata/FieldTypeRegistryTest.kt`, `$TDST/metadata/MetadataJsonTest.kt`

**Interfaces:**
- Consumes: `ValidationException`, `ConflictException` (Task 2), `FieldAccess` (Task 3), `SqlIdentifier`.
- Produces:
  - `data class FieldType(val name: String)` with companions `TEXT, LONG_TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, ENUM, EMAIL, URL, UUID, RELATION`
  - `interface FieldTypeHandler`, exactly as written in Step 3. Every later task and every P2 module relies on these member names.
  - `class FieldTypeRegistry(extra: List<FieldTypeHandler>)` with `handlers`, `types`, `sections: List<String>`, `attributeColumns: Map<String, Class<*>>`, `parse(raw, field = "type"): FieldType`, `handler(type): FieldTypeHandler` (throws `ConflictException` when not installed), `isInstalled(type)`, `sectionOwner(section): FieldTypeHandler`, `fieldProperties(field): Map<String, Any?>`, `objectProperties(definition): Map<String, Any?>`
  - `class ScalarFieldType(type, column, textLike = false) : FieldTypeHandler`, `object ScalarFieldTypes { val ALL: List<FieldTypeHandler> }`
  - `object FieldValueCodec { javaType(type), toDatabase(field, value), fromDatabase(value) }`
  - `data class CustomObject(...)` (unchanged fields), `data class CustomField(id, objectId, name, label, type: FieldType, columnName, required, unique, defaultValue, description, position, enumOptions, relationTargetObjectId, attributes: Map<String, Any?> = emptyMap(), visible, editable)`, `data class ObjectDefinition(obj, fields)` + `readableBy(access)`, `writableBy(access)`, `readableNames(access)`
  - DTOs: `FieldRequest` (+ `extensions: MutableMap<String, Any?>` filled by `@JsonAnySetter`), `CreateObjectRequest`, `UpdateFieldRequest`, `UpdateObjectRequest`, `FieldResponse(..., extensions: Map<String, Any?> = emptyMap())`, `ObjectResponse(..., extensions)`, `ObjectDefinitionResponse(..., fields, extensions)`, `SystemFieldResponse(name, type, scope)`
  - Ports: `interface ObjectRemovalListener { suspend fun objectRemoved(obj: CustomObject) }`, `interface FieldUsage { suspend fun whoUses(obj: CustomObject, fieldName: String): List<String> }`

- [ ] **Step 1: Write the failing tests**

Port the codec test:
```bash
mkdir -p $DST/metadata $TDST/metadata
cp $TSRC/data/FieldValueCodecTest.kt $TDST/metadata/
sed -i '' -e 's/com\.sapgis\./chawpi.core./g' -e 's/^package chawpi\.core\.data$/package chawpi.core.metadata/' $TDST/metadata/FieldValueCodecTest.kt
```
Then in `$TDST/metadata/FieldValueCodecTest.kt`:
- delete the imports `chawpi.core.metadata.CustomField` and `chawpi.core.metadata.FieldType`, since they are the same package now;
- in the `field(...)` helper, delete the three lines `geometryType = null,`, `srid = null,`, `dimension = null,`;
- replace the last test (`geometry is not an attribute value` and its comment) with:
```kotlin
    // a type core does not know never reaches the codec. if it does, it is refused, not guessed.
    @Test
    fun `a type core does not know is refused`() {
        assertThatThrownBy { FieldValueCodec.toDatabase(field(FieldType("SHAPE")), "{}") }
            .isInstanceOf(ValidationException::class.java)
    }
```

`$TDST/metadata/FieldTypeRegistryTest.kt`:
```kotlin
package chawpi.core.metadata

import chawpi.core.common.ConflictException
import chawpi.core.common.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class FieldTypeRegistryTest {
    private val shape = FieldType("SHAPE")

    // a stand-in for a module type: own section, own attribute column, a property on every field
    private val shapeHandler =
        object : FieldTypeHandler {
            override val type = shape
            override val attributeColumns = mapOf("shape_kind" to String::class.java)
            override val section = "shapes"

            override fun columnType(field: CustomField) = "text"

            override fun toDatabase(
                field: CustomField,
                value: Any?
            ) = value

            override fun javaType(field: CustomField) = String::class.java

            override fun fromDatabase(
                field: CustomField,
                value: Any?
            ) = value

            override fun fieldProperties(field: CustomField) = mapOf("shape" to if (field.type == shape) field.attributes["shape_kind"] else null)

            override fun objectProperties(definition: ObjectDefinition) = mapOf("shape" to definition.fields.firstOrNull { it.type == shape }?.name)
        }

    private fun field(
        type: FieldType,
        attributes: Map<String, Any?> = emptyMap()
    ) = CustomField(
        id = UUID.randomUUID(),
        objectId = UUID.randomUUID(),
        name = "campo",
        label = "Campo",
        type = type,
        columnName = "campo",
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

    @Test
    fun `core alone parses its twelve types, any case, and lists them in order when it cannot`() {
        val registry = FieldTypeRegistry(emptyList())

        assertThat(registry.parse("text")).isEqualTo(FieldType.TEXT)
        assertThatThrownBy { registry.parse("GEOMETRY") }
            .isInstanceOfSatisfying(ValidationException::class.java) { ex ->
                assertThat(ex.violations.single().message)
                    .isEqualTo("must be one of TEXT, LONG_TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, ENUM, EMAIL, URL, UUID, RELATION")
            }
    }

    @Test
    fun `a module type is parsed and listed after the core ones`() {
        val registry = FieldTypeRegistry(listOf(shapeHandler))

        assertThat(registry.parse("shape")).isEqualTo(shape)
        assertThat(registry.types.last()).isEqualTo(shape)
        assertThat(registry.types).hasSize(13)
    }

    @Test
    fun `a type declared twice fails at boot`() {
        assertThatThrownBy { FieldTypeRegistry(listOf(shapeHandler, shapeHandler)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("SHAPE")
    }

    @Test
    fun `a stored field whose module is gone answers a conflict that names the type`() {
        val registry = FieldTypeRegistry(emptyList())

        assertThat(registry.isInstalled(FieldType("GEOMETRY"))).isFalse()
        assertThatThrownBy { registry.handler(FieldType("GEOMETRY")) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("GEOMETRY")
    }

    @Test
    fun `sections, attribute columns and json properties come from every installed handler`() {
        val registry = FieldTypeRegistry(listOf(shapeHandler))

        assertThat(registry.sections).containsExactly("shapes")
        assertThat(registry.sectionOwner("shapes")).isSameAs(shapeHandler)
        assertThat(registry.attributeColumns).containsOnlyKeys("shape_kind")
        // a text field still gets the module's key, with null: this is how gis keeps "geometry": null
        assertThat(registry.fieldProperties(field(FieldType.TEXT))).containsExactly(org.assertj.core.api.Assertions.entry("shape", null))
        assertThat(registry.fieldProperties(field(shape, mapOf("shape_kind" to "round")))).containsEntry("shape", "round")
    }

    @Test
    fun `core alone adds no section, no attribute column and no json property`() {
        val registry = FieldTypeRegistry(emptyList())

        assertThat(registry.sections).isEmpty()
        assertThat(registry.attributeColumns).isEmpty()
        assertThat(registry.fieldProperties(field(FieldType.TEXT))).isEmpty()
        assertThat(registry.handler(FieldType.TEXT).textLike).isTrue()
        assertThat(registry.handler(FieldType.INTEGER).columnType(field(FieldType.INTEGER))).isEqualTo("bigint")
    }
}
```

`$TDST/metadata/MetadataJsonTest.kt`:
```kotlin
package chawpi.core.metadata

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class MetadataJsonTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private fun response(extensions: Map<String, Any?>) =
        FieldResponse(
            id = "1",
            name = "lote",
            label = "Lote",
            type = "TEXT",
            required = false,
            unique = false,
            defaultValue = null,
            description = null,
            position = 0,
            enumOptions = null,
            relationTarget = null,
            visible = true,
            editable = true,
            extensions = extensions
        )

    @Test
    fun `with no module the field json has exactly the core keys`() {
        val tree = mapper.readTree(mapper.writeValueAsString(response(emptyMap())))

        assertThat(tree.propertyNames().asSequence().toList()).containsExactlyInAnyOrder(
            "id",
            "name",
            "label",
            "type",
            "required",
            "unique",
            "defaultValue",
            "description",
            "position",
            "enumOptions",
            "relationTarget",
            "visible",
            "editable"
        )
    }

    @Test
    fun `module properties are flattened, and a null one is still written`() {
        val tree = mapper.readTree(mapper.writeValueAsString(response(mapOf("geometry" to null, "other" to mapOf("srid" to 32718)))))

        assertThat(tree.has("extensions")).isFalse()
        assertThat(tree.has("geometry")).isTrue()
        assertThat(tree.get("geometry").isNull).isTrue()
        assertThat(tree.get("other").get("srid").asInt()).isEqualTo(32718)
    }

    @Test
    fun `request properties core does not know are kept for the field type`() {
        val request =
            mapper.readValue(
                """{"name":"lote","type":"GEOMETRY","geometryType":"POLYGON","srid":32718}""",
                FieldRequest::class.java
            )

        assertThat(request.name).isEqualTo("lote")
        assertThat(request.extensions).containsEntry("geometryType", "POLYGON").containsEntry("srid", 32718)
    }
}
```
If `tree.propertyNames()` does not exist in the Jackson 3 `JsonNode` on the classpath, use `tree.fieldNames()` (Jackson 2 name). Keep the assertion.

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :chawpi-core:test`
Expected: compilation FAILS with `Unresolved reference 'FieldType'`, `'FieldTypeRegistry'`, `'FieldResponse'`.

- [ ] **Step 3: Write `FieldType.kt`, `FieldTypeHandler.kt`, `ScalarFieldTypes.kt`, `FieldTypeRegistry.kt`**

`$DST/metadata/FieldType.kt`:
```kotlin
package chawpi.core.metadata

// a field type is a name. core knows the twelve below; a module adds more with a FieldTypeHandler.
// parsing has to know what is installed, so it lives in FieldTypeRegistry.
data class FieldType(
    val name: String
) {
    override fun toString(): String = name

    companion object {
        val TEXT = FieldType("TEXT")
        val LONG_TEXT = FieldType("LONG_TEXT")
        val INTEGER = FieldType("INTEGER")
        val DECIMAL = FieldType("DECIMAL")
        val BOOLEAN = FieldType("BOOLEAN")
        val DATE = FieldType("DATE")
        val DATETIME = FieldType("DATETIME")
        val ENUM = FieldType("ENUM")
        val EMAIL = FieldType("EMAIL")
        val URL = FieldType("URL")
        val UUID = FieldType("UUID")
        val RELATION = FieldType("RELATION")
    }
}
```

`$DST/metadata/FieldTypeHandler.kt`:
```kotlin
package chawpi.core.metadata

import chawpi.core.common.ValidationException

// how one field type lives in postgres and in the api. core ships the scalar ones; a module adds
// a type by declaring a bean of this. every hook has the core behaviour as its default. ADR-0025.
interface FieldTypeHandler {
    val type: FieldType

    // ---- metadata ----

    // custom_fields columns this type owns (its module's migration creates them) and the java type
    // a null is bound as. every field row carries them, null when the field is of another type.
    val attributeColumns: Map<String, Class<*>> get() = emptyMap()

    // checks the type-specific part of a new field. returns what goes in attributeColumns.
    fun attributesOf(
        fieldName: String,
        request: FieldRequest
    ): Map<String, Any?> = emptyMap()

    // refuses a change that means nothing for this type
    fun checkUpdate(
        field: CustomField,
        request: UpdateFieldRequest
    ) = Unit

    // json properties added to EVERY field response, not only to this type's fields
    fun fieldProperties(field: CustomField): Map<String, Any?> = emptyMap()

    // json properties added to every object response
    fun objectProperties(definition: ObjectDefinition): Map<String, Any?> = emptyMap()

    // ---- ddl ----

    // postgres column type. may read the field's attributes.
    fun columnType(field: CustomField): String

    // statements run once the column exists. `table` is already qualified and quoted.
    fun indexes(
        obj: CustomObject,
        table: String,
        field: CustomField
    ): List<String> = emptyList()

    // ---- records ----

    // null: the value travels in "attributes". otherwise the payload section it travels in. a field
    // in a section is written only when its key is sent; null clears it.
    val section: String? get() = null

    // what a section key naming no field of this type answers
    fun unknownSectionKey(
        key: String,
        definition: ObjectDefinition
    ): ValidationException = ValidationException("Unknown field '$key'", key, "is not a field of '${definition.obj.name}'")

    // true: ?q= searches this column with ILIKE
    val textLike: Boolean get() = false

    // json value -> bound value, validated. the only place raw input becomes a parameter.
    fun toDatabase(
        field: CustomField,
        value: Any?
    ): Any?

    // the class a null of this type is bound as
    fun javaType(field: CustomField): Class<*>

    // sql for the bound value. `parameter` comes without the colon.
    fun bindExpression(
        field: CustomField,
        parameter: String
    ): String = ":$parameter"

    // select-list item. `column` is already quoted. read back under readName(field).
    fun select(
        field: CustomField,
        column: String
    ): String = column

    fun readName(field: CustomField): String = field.columnName

    // db value -> json friendly
    fun fromDatabase(
        field: CustomField,
        value: Any?
    ): Any?

    // non-null: the type can be neither filtered on nor sorted by, and this is what the caller hears
    fun rejectFilterOrSort(field: CustomField): ValidationException? = null
}
```

`$DST/metadata/ScalarFieldTypes.kt`:
```kotlin
package chawpi.core.metadata

// a type that is one plain postgres column. storage rules are FieldValueCodec's.
class ScalarFieldType(
    override val type: FieldType,
    private val column: String,
    override val textLike: Boolean = false
) : FieldTypeHandler {
    override fun columnType(field: CustomField): String = column

    override fun toDatabase(
        field: CustomField,
        value: Any?
    ): Any? = FieldValueCodec.toDatabase(field, value)

    override fun javaType(field: CustomField): Class<*> = FieldValueCodec.javaType(field.type)

    override fun fromDatabase(
        field: CustomField,
        value: Any?
    ): Any? = FieldValueCodec.fromDatabase(value)
}

// the types core knows, in the order the api lists them
object ScalarFieldTypes {
    val ALL: List<FieldTypeHandler> =
        listOf(
            ScalarFieldType(FieldType.TEXT, "text", textLike = true),
            ScalarFieldType(FieldType.LONG_TEXT, "text", textLike = true),
            ScalarFieldType(FieldType.INTEGER, "bigint"),
            ScalarFieldType(FieldType.DECIMAL, "numeric"),
            ScalarFieldType(FieldType.BOOLEAN, "boolean"),
            ScalarFieldType(FieldType.DATE, "date"),
            ScalarFieldType(FieldType.DATETIME, "timestamptz"),
            ScalarFieldType(FieldType.ENUM, "text", textLike = true),
            ScalarFieldType(FieldType.EMAIL, "text", textLike = true),
            ScalarFieldType(FieldType.URL, "text", textLike = true),
            ScalarFieldType(FieldType.UUID, "uuid"),
            ScalarFieldType(FieldType.RELATION, "uuid")
        )
}
```

`$DST/metadata/FieldTypeRegistry.kt`:
```kotlin
package chawpi.core.metadata

import chawpi.core.common.ConflictException
import chawpi.core.common.ValidationException

// every installed field type: core's first, then the modules' in bean order
class FieldTypeRegistry(
    extra: List<FieldTypeHandler>
) {
    val handlers: List<FieldTypeHandler> = ScalarFieldTypes.ALL + extra

    private val byName: Map<String, FieldTypeHandler>

    init {
        val twice = handlers.groupBy { it.type.name }.filterValues { it.size > 1 }.keys
        check(twice.isEmpty()) { "field type declared twice: ${twice.joinToString(", ")}" }
        byName = handlers.associateBy { it.type.name }
    }

    val types: List<FieldType> = handlers.map { it.type }

    // payload sections in declaration order, each once
    val sections: List<String> = handlers.mapNotNull { it.section }.distinct()

    // custom_fields columns owned by installed modules
    val attributeColumns: Map<String, Class<*>> =
        handlers.fold(linkedMapOf()) { acc, handler ->
            acc.putAll(handler.attributeColumns)
            acc
        }

    fun isInstalled(type: FieldType): Boolean = type.name in byName

    fun parse(
        raw: String,
        field: String = "type"
    ): FieldType =
        byName[raw.trim().uppercase()]?.type
            ?: throw ValidationException(
                "Unknown field type '$raw'",
                field,
                "must be one of ${types.joinToString(", ") { it.name }}"
            )

    // a stored field whose module was removed: its records stay unreadable until the module is back
    fun handler(type: FieldType): FieldTypeHandler =
        byName[type.name]
            ?: throw ConflictException("Field type '${type.name}' is not installed. Add the module that provides it.")

    fun sectionOwner(section: String): FieldTypeHandler = handlers.first { it.section == section }

    fun fieldProperties(field: CustomField): Map<String, Any?> =
        handlers.fold(linkedMapOf()) { acc, handler ->
            acc.putAll(handler.fieldProperties(field))
            acc
        }

    fun objectProperties(definition: ObjectDefinition): Map<String, Any?> =
        handlers.fold(linkedMapOf()) { acc, handler ->
            acc.putAll(handler.objectProperties(definition))
            acc
        }
}
```

- [ ] **Step 4: Write `FieldValueCodec.kt`** (moved from `data`; `GEOMETRY` branch gone; `when` needs `else` now that `FieldType` is not an enum)

```kotlin
package chawpi.core.metadata

import chawpi.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// json value -> db value for the core types, with validation
object FieldValueCodec {
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun javaType(type: FieldType): Class<*> =
        when (type) {
            FieldType.INTEGER -> java.lang.Long::class.java
            FieldType.DECIMAL -> BigDecimal::class.java
            FieldType.BOOLEAN -> java.lang.Boolean::class.java
            FieldType.DATE -> LocalDate::class.java
            FieldType.DATETIME -> OffsetDateTime::class.java
            FieldType.UUID, FieldType.RELATION -> UUID::class.java
            else -> String::class.java
        }

    fun toDatabase(
        field: CustomField,
        value: Any?
    ): Any? {
        if (value == null) {
            if (field.required) throw ValidationException("Missing value", field.name, "is required")
            return null
        }
        return when (field.type) {
            FieldType.TEXT, FieldType.LONG_TEXT -> text(field, value)
            FieldType.EMAIL ->
                text(field, value).also {
                    if (!EMAIL.matches(it)) throw ValidationException("Invalid email", field.name, "is not an email")
                }
            FieldType.URL ->
                text(field, value).also {
                    if (!it.startsWith("http://") && !it.startsWith("https://")) {
                        throw ValidationException("Invalid URL", field.name, "must start with http:// or https://")
                    }
                }
            FieldType.ENUM ->
                text(field, value).also {
                    val options = field.enumOptions.orEmpty()
                    if (it !in options) {
                        throw ValidationException("Invalid option", field.name, "must be one of ${options.joinToString(", ")}")
                    }
                }
            FieldType.INTEGER ->
                when (value) {
                    is Number -> value.toLong()
                    is String -> value.toLongOrNull() ?: reject(field, "is not an integer")
                    else -> reject(field, "is not an integer")
                }
            FieldType.DECIMAL ->
                when (value) {
                    is BigDecimal -> value
                    is Number -> BigDecimal(value.toString())
                    is String -> value.toBigDecimalOrNull() ?: reject(field, "is not a number")
                    else -> reject(field, "is not a number")
                }
            FieldType.BOOLEAN ->
                when (value) {
                    is Boolean -> value
                    is String -> value.toBooleanStrictOrNull() ?: reject(field, "is not a boolean")
                    else -> reject(field, "is not a boolean")
                }
            FieldType.DATE -> runCatching { LocalDate.parse(text(field, value)) }.getOrElse { reject(field, "is not an ISO date") }
            FieldType.DATETIME ->
                runCatching { OffsetDateTime.parse(text(field, value)) }
                    .getOrElse { reject(field, "is not an ISO date-time") }
            FieldType.UUID, FieldType.RELATION ->
                runCatching { UUID.fromString(text(field, value)) }.getOrElse { reject(field, "is not a UUID") }
            // a module type goes through its own handler, never here
            else -> reject(field, "is not a core field type")
        }
    }

    // db value -> json friendly. dates as iso strings so output does not depend on mapper config.
    fun fromDatabase(value: Any?): Any? =
        when (value) {
            null -> null
            is LocalDate -> value.toString()
            is OffsetDateTime -> value.toInstant().toString()
            is UUID -> value.toString()
            else -> value
        }

    private fun text(
        field: CustomField,
        value: Any?
    ): String = value as? String ?: reject(field, "must be a string")

    private fun reject(
        field: CustomField,
        reason: String
    ): Nothing = throw ValidationException("Invalid value for '${field.name}'", field.name, reason)
}
```

- [ ] **Step 5: Write `CustomObject.kt`, `MetadataPorts.kt`, `MetadataDtos.kt`**

`$DST/metadata/CustomObject.kt`:
```kotlin
package chawpi.core.metadata

import chawpi.core.identity.FieldAccess
import java.time.Instant
import java.util.UUID

data class CustomObject(
    val id: UUID,
    val organizationId: UUID,
    val name: String,
    val label: String,
    val pluralLabel: String,
    val description: String?,
    val enabled: Boolean,
    val physicalTable: String,
    val createdAt: Instant?,
    val updatedAt: Instant?
)

data class CustomField(
    val id: UUID,
    val objectId: UUID,
    val name: String,
    val label: String,
    val type: FieldType,
    val columnName: String,
    val required: Boolean,
    val unique: Boolean,
    val defaultValue: String?,
    val description: String?,
    val position: Int,
    val enumOptions: List<String>?,
    val relationTargetObjectId: UUID?,
    // what a module's field type keeps in its own custom_fields columns, by column name
    val attributes: Map<String, Any?> = emptyMap(),
    val visible: Boolean,
    val editable: Boolean
)

// object plus its fields. what the UI needs to render anything.
data class ObjectDefinition(
    val obj: CustomObject,
    val fields: List<CustomField>
)

// what the caller may see: unreadable fields gone, unwritable ones locked.
fun ObjectDefinition.readableBy(access: FieldAccess): ObjectDefinition {
    if (access.unrestricted) return this
    return copy(
        fields =
            fields
                .filter { access.canRead(it.id) }
                .map { if (access.canWrite(it.id)) it else it.copy(editable = false) }
    )
}

// every field stays (the row still has to be read back), but the locked ones stop being written.
fun ObjectDefinition.writableBy(access: FieldAccess): ObjectDefinition {
    if (access.unrestricted) return this
    return copy(fields = fields.map { if (access.canWrite(it.id)) it else it.copy(editable = false) })
}

// field names the caller may read back
fun ObjectDefinition.readableNames(access: FieldAccess): Set<String> = fields.filter { access.canRead(it.id) }.map { it.name }.toSet()
```

`$DST/metadata/MetadataPorts.kt`:
```kotlin
package chawpi.core.metadata

// told before an object's table is dropped, inside the same transaction. a module that published
// something about the object (a map layer) cleans it up here. every listener is called, in @Order.
interface ObjectRemovalListener {
    suspend fun objectRemoved(obj: CustomObject)
}

// asked before a field is dropped. a rule that reads a deleted field fails only when it next fires,
// far from the admin who deleted it, so ask first. answers from every bean are joined.
interface FieldUsage {
    suspend fun whoUses(
        obj: CustomObject,
        fieldName: String
    ): List<String>
}
```

`$DST/metadata/MetadataDtos.kt`:
```kotlin
package chawpi.core.metadata

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.constraints.NotBlank
import java.time.Instant

data class FieldRequest(
    @field:NotBlank val name: String,
    val label: String? = null,
    @field:NotBlank val type: String,
    val required: Boolean = false,
    val unique: Boolean = false,
    val defaultValue: String? = null,
    val description: String? = null,
    val enumOptions: List<String>? = null,
    // target object technical name, for RELATION fields
    val relationTarget: String? = null,
    val visible: Boolean = true,
    val editable: Boolean = true
) {
    // properties only a module's field type reads. core keeps them for it.
    @get:JsonIgnore
    val extensions: MutableMap<String, Any?> = linkedMapOf()

    @JsonAnySetter
    fun extension(
        name: String,
        value: Any?
    ) {
        extensions[name] = value
    }
}

data class CreateObjectRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val label: String,
    val pluralLabel: String? = null,
    val description: String? = null,
    val fields: List<FieldRequest> = emptyList()
)

// null means "leave as it is". name and type are accepted only to be refused: see MetadataService.
data class UpdateFieldRequest(
    val name: String? = null,
    val type: String? = null,
    val label: String? = null,
    val required: Boolean? = null,
    val unique: Boolean? = null,
    val description: String? = null,
    val enumOptions: List<String>? = null,
    val visible: Boolean? = null,
    val editable: Boolean? = null,
    val position: Int? = null
)

data class UpdateObjectRequest(
    // accepted only to be refused: renaming would move the table and the API path
    val name: String? = null,
    @field:NotBlank val label: String,
    val pluralLabel: String? = null,
    val description: String? = null,
    val enabled: Boolean = true
)

// installed field types add their own keys (FieldTypeRegistry.fieldProperties), flattened in
data class FieldResponse(
    val id: String,
    val name: String,
    val label: String,
    val type: String,
    val required: Boolean,
    val unique: Boolean,
    val defaultValue: String?,
    val description: String?,
    val position: Int,
    val enumOptions: List<String>?,
    val relationTarget: String?,
    val visible: Boolean,
    val editable: Boolean,
    // what installed field types add (R5). kept out of the json as itself, written flat below.
    @get:JsonIgnore val extensions: Map<String, Any?> = emptyMap()
) {
    @JsonAnyGetter
    fun flattened(): Map<String, Any?> = extensions
}

data class ObjectResponse(
    val id: String,
    val name: String,
    val label: String,
    val pluralLabel: String,
    val description: String?,
    val enabled: Boolean,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    // what installed field types add (R5). kept out of the json as itself, written flat below.
    @get:JsonIgnore val extensions: Map<String, Any?> = emptyMap()
) {
    @JsonAnyGetter
    fun flattened(): Map<String, Any?> = extensions
}

// what the dynamic UI renders from: object + fields, flat so the client needs no unwrapping.
data class ObjectDefinitionResponse(
    val id: String,
    val name: String,
    val label: String,
    val pluralLabel: String,
    val description: String?,
    val enabled: Boolean,
    val fields: List<FieldResponse>,
    // what installed field types add (R5). kept out of the json as itself, written flat below.
    @get:JsonIgnore val extensions: Map<String, Any?> = emptyMap()
) {
    @JsonAnyGetter
    fun flattened(): Map<String, Any?> = extensions
}

// the names the platform keeps for itself. published so the field editor can show them
// instead of letting the admin find out through a 400.
data class SystemFieldResponse(
    val name: String,
    // FieldType vocabulary, not postgres. null when no column is created for the name.
    val type: String?,
    val scope: String
)
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `./gradlew :chawpi-core:test`
Expected: PASS: `FieldValueCodecTest` (all ported tests + `a type core does not know is refused`), `FieldTypeRegistryTest` (6), `MetadataJsonTest` (3), plus the tests from Tasks 2–3.
If `module properties are flattened, and a null one is still written` fails only on the null check, add `@JsonInclude(content = JsonInclude.Include.ALWAYS)` (import `com.fasterxml.jackson.annotation.JsonInclude`) next to each `@JsonAnyGetter` and rerun. Do not change the test.

- [ ] **Step 7: Format, check, leave uncommitted**

```bash
./gradlew :chawpi-core:ktlintFormat :chawpi-core:ktlintCheck :chawpi-core:build
grep -rn -i "sapgis\|geometr\|postgis" $DST || echo "clean"
git status --short | head
```
Expected: build passes, `clean`, nothing committed.

---

### Task 5: `metadata` repositories, DDL, services and controllers

**Files:**
- Create (copied + renamed + edited): `$DST/metadata/{MetadataRepository,Relationship,RelationshipService,RelationshipController,MetadataService,MetadataController,CallerPermissionsController,CallerPermissionsService}.kt`
- Create (rewritten): `$DST/metadata/ObjectSchemaManager.kt` (was `data/ObjectSchemaManager.kt`), `$DST/metadata/MetadataMapper.kt`
- Test: `$TDST/metadata/ObjectSchemaManagerTest.kt`, `$TDST/metadata/MetadataMapperTest.kt`

**Interfaces:**
- Consumes: Task 4 types (`FieldTypeRegistry`, `FieldTypeHandler`, DTOs, ports); `ChawpiSchemas`, `SystemColumns` (Task 2); `CurrentUser`, `AccessPolicy` (Task 3).
- Produces:
  - `ObjectSchemaManager(db: DatabaseClient, schemas: ChawpiSchemas, types: FieldTypeRegistry)` with `createTable(obj, fields, relationTables: Map<UUID, String> = emptyMap())`, `addColumn(obj, field, relationTables = emptyMap())`, `addStateColumn(obj)`, `dropTable(obj)`, `dropColumn(obj, field)`, `setRequired(obj, field, required)`, `setUnique(obj, field, unique)`, `replaceEnumCheck(obj, field, options)`, `createJoinTable(joinTable, source, target)`, `dropJoinTable(joinTable)`, `internal fun columnDefinition(field, relationTables): String`, `internal fun indexStatements(obj, field): List<String>`, `companion const val STATE_COLUMN = "workflow_state"`
  - `CustomObjectRepository(db, schemas)`: same methods as sapgis (`insert`, `findByName`, `findById`, `findAll`, `update`, `delete`)
  - `CustomFieldRepository(db, objectMapper, schemas, types)`: same methods as sapgis (`insert`, `findByObject`, `findByObjects`, `findByName`, `findById`, `update`, `delete`, `findByRelationTarget`, `maxPosition`)
  - `RelationshipRepository(db, schemas)`: same methods; `tableExists` checks `schemas.data`
  - `MetadataService(objects, fields, relationships, schema, currentUser, access, types, systemColumns, usages: List<FieldUsage>, removals: List<ObjectRemovalListener>)`: same public methods as sapgis (`listObjects`, `listDefinitions`, `definitionOf`, `loadDefinition(organizationId, name)`, `loadDefinitionById`, `createObject`, `addField`, `updateField`, `deleteField`, `updateObject`, `deleteObject`)
  - `RelationshipService(relationships, objects, fields, metadata, schema, currentUser)` with `list()`, `forObject(objectName)`, `create(request)`, `update(name, request)`, `delete(name)` and the now-public `suspend fun side(relationship: Relationship, obj: CustomObject): RelatedSide`. Record-level methods move to `data.RelatedRecordService` (Task 6).
  - `MetadataMapper(objects: CustomObjectRepository, types: FieldTypeRegistry)` with `toObjectResponse(definition): ObjectResponse`, `suspend toResponse(definition, organizationId): ObjectDefinitionResponse`, `suspend toFieldResponses(fields, organizationId): List<FieldResponse>`, `toFieldResponse(field, relationTargetName: String?): FieldResponse`
  - Controllers (routes unchanged): `ObjectController` `/api/objects`, `ObjectMetadataController` `/api/metadata/objects` (**without** the `/views`, `/forms`, `/pages` routes, Ruling R16), `SystemFieldController(systemColumns: SystemColumns)` `/api/metadata/system-fields`, `RelationshipController` `/api/relationships`, `CallerPermissionsController` `/api/auth/me/permissions`. `RelationshipMapper`, `RelationshipResponse`, `RelatedSideResponse` stay in `RelationshipController.kt`.

- [ ] **Step 1: Write the failing tests**

`$TDST/metadata/ObjectSchemaManagerTest.kt`:
```kotlin
package chawpi.core.metadata

import chawpi.core.platform.ChawpiSchemas
import chawpi.core.platform.SqlIdentifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import java.util.UUID

class ObjectSchemaManagerTest {
    private val measure = FieldType("MEASURE")

    // a module type: its column type reads an attribute, and it asks for its own index
    private val measureHandler =
        object : FieldTypeHandler {
            override val type = measure

            override fun columnType(field: CustomField) = "numeric(12, ${field.attributes["scale"]})"

            override fun indexes(
                obj: CustomObject,
                table: String,
                field: CustomField
            ) = listOf(
                "CREATE INDEX ${SqlIdentifier.quote(SqlIdentifier.indexName(obj.physicalTable, field.columnName, "mix"))} " +
                    "ON $table (${SqlIdentifier.quote(field.columnName)})"
            )

            override fun toDatabase(
                field: CustomField,
                value: Any?
            ) = value

            override fun javaType(field: CustomField) = String::class.java

            override fun fromDatabase(
                field: CustomField,
                value: Any?
            ) = value
        }

    private fun manager(schemas: ChawpiSchemas = ChawpiSchemas("chawpi", "app_data")) =
        ObjectSchemaManager(mock(DatabaseClient::class.java), schemas, FieldTypeRegistry(listOf(measureHandler)))

    private val obj =
        CustomObject(UUID.randomUUID(), UUID.randomUUID(), "predio", "Predio", "Predios", null, true, "predio__1234abcd", null, null)

    private fun field(
        name: String,
        type: FieldType,
        required: Boolean = false,
        unique: Boolean = false,
        enumOptions: List<String>? = null,
        relationTarget: UUID? = null,
        attributes: Map<String, Any?> = emptyMap()
    ) = CustomField(
        id = UUID.randomUUID(),
        objectId = obj.id,
        name = name,
        label = name,
        type = type,
        columnName = name,
        required = required,
        unique = unique,
        defaultValue = null,
        description = null,
        position = 0,
        enumOptions = enumOptions,
        relationTargetObjectId = relationTarget,
        attributes = attributes,
        visible = true,
        editable = true
    )

    @Test
    fun `a core field gets its postgres type and its constraints`() {
        assertThat(manager().columnDefinition(field("codigo", FieldType.TEXT, required = true, unique = true), emptyMap()))
            .isEqualTo("\"codigo\" text NOT NULL UNIQUE")
        assertThat(manager().columnDefinition(field("uso", FieldType.ENUM, enumOptions = listOf("A", "B'C")), emptyMap()))
            .isEqualTo("\"uso\" text CHECK (\"uso\" IN ('A', 'B''C'))")
    }

    @Test
    fun `a relation points at the target table in the configured data schema`() {
        val target = UUID.randomUUID()
        val ddl = manager(ChawpiSchemas("acme_meta", "acme_data")).columnDefinition(field("dueno", FieldType.RELATION, relationTarget = target), mapOf(target to "persona__1234abcd"))

        assertThat(ddl).isEqualTo("\"dueno\" uuid REFERENCES \"acme_data\".\"persona__1234abcd\" (id) ON DELETE SET NULL")
    }

    @Test
    fun `a module type brings its own column type and its own index`() {
        val area = field("area", measure, attributes = mapOf("scale" to 2))

        assertThat(manager().columnDefinition(area, emptyMap())).isEqualTo("\"area\" numeric(12, 2)")
        assertThat(manager().indexStatements(obj, area))
            .containsExactly("CREATE INDEX \"predio__1234abcd_area_mix\" ON \"app_data\".\"predio__1234abcd\" (\"area\")")
        assertThat(manager().indexStatements(obj, field("codigo", FieldType.TEXT))).isEmpty()
    }
}
```

`$TDST/metadata/MetadataMapperTest.kt`:
```kotlin
package chawpi.core.metadata

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.util.UUID

class MetadataMapperTest {
    private val tag = FieldType("TAG")

    private val tagHandler =
        object : FieldTypeHandler {
            override val type = tag

            override fun columnType(field: CustomField) = "text"

            override fun toDatabase(
                field: CustomField,
                value: Any?
            ) = value

            override fun javaType(field: CustomField) = String::class.java

            override fun fromDatabase(
                field: CustomField,
                value: Any?
            ) = value

            override fun fieldProperties(field: CustomField) = mapOf("tag" to if (field.type == tag) field.attributes["color"] else null)

            override fun objectProperties(definition: ObjectDefinition) = mapOf("tag" to definition.fields.firstOrNull { it.type == tag }?.name)
        }

    private val obj = CustomObject(UUID.randomUUID(), UUID.randomUUID(), "predio", "Predio", "Predios", null, true, "predio__1", null, null)

    private fun field(
        name: String,
        type: FieldType,
        attributes: Map<String, Any?> = emptyMap()
    ) = CustomField(UUID.randomUUID(), obj.id, name, name, type, name, false, false, null, null, 0, null, null, attributes, true, true)

    @Test
    fun `without modules the responses carry no extension`() {
        val mapper = MetadataMapper(mock(CustomObjectRepository::class.java), FieldTypeRegistry(emptyList()))
        val definition = ObjectDefinition(obj, listOf(field("codigo", FieldType.TEXT)))

        assertThat(mapper.toObjectResponse(definition).extensions).isEmpty()
        assertThat(mapper.toFieldResponse(definition.fields.single(), null).extensions).isEmpty()
    }

    @Test
    fun `an installed type adds its keys to every field and to the object`() {
        val mapper = MetadataMapper(mock(CustomObjectRepository::class.java), FieldTypeRegistry(listOf(tagHandler)))
        val codigo = field("codigo", FieldType.TEXT)
        val marca = field("marca", tag, mapOf("color" to "red"))
        val definition = ObjectDefinition(obj, listOf(codigo, marca))

        assertThat(mapper.toObjectResponse(definition).extensions).containsEntry("tag", "marca")
        assertThat(mapper.toFieldResponse(codigo, null).extensions).containsEntry("tag", null)
        assertThat(mapper.toFieldResponse(marca, null).extensions).containsEntry("tag", "red")
        assertThat(mapper.toFieldResponse(marca, null).type).isEqualTo("TAG")
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :chawpi-core:test`
Expected: compilation FAILS with `Unresolved reference 'ObjectSchemaManager'` / `'MetadataMapper'`.

- [ ] **Step 3: Copy, rename, point SQL at the schema**

```bash
cp $SRC/metadata/{MetadataRepository,Relationship,RelationshipService,RelationshipController,MetadataService,MetadataController,CallerPermissionsController,CallerPermissionsService}.kt $DST/metadata/
sed -i '' -e 's/com\.sapgis\./chawpi.core./g' -e 's/SapgisException/ChawpiException/g' $DST/metadata/*.kt
sed -i '' -E -e '/^[[:space:]]*\/\//!s/([^a-zA-Z_.])sapgis\.([a-z_]+)/\1${schemas.metadata}.\2/g' $DST/metadata/*.kt
grep -n 'import chawpi.core.data\.\|import chawpi.core.forms\|import chawpi.core.pages\|import chawpi.core.views' $DST/metadata/*.kt
```
The grep lists imports to delete in Step 4 and Step 5.

- [ ] **Step 4: Write `ObjectSchemaManager.kt`** (moved from `data`; the geometry code is gone and every type goes through its handler)

`$DST/metadata/ObjectSchemaManager.kt`:
```kotlin
package chawpi.core.metadata

import chawpi.core.common.ValidationException
import chawpi.core.platform.ChawpiSchemas
import chawpi.core.platform.SqlIdentifier
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.util.UUID

// the only place that runs DDL. metadata in, physical table out. ADR-004.
@Component
class ObjectSchemaManager(
    private val db: DatabaseClient,
    private val schemas: ChawpiSchemas,
    private val types: FieldTypeRegistry
) {
    suspend fun createTable(
        obj: CustomObject,
        fields: List<CustomField>,
        relationTables: Map<UUID, String> = emptyMap()
    ) {
        val table = schemas.dataTable(obj.physicalTable)
        val columns =
            buildList {
                add("id uuid PRIMARY KEY DEFAULT gen_random_uuid()")
                add("organization_id uuid NOT NULL")
                add("created_at timestamptz NOT NULL DEFAULT now()")
                add("updated_at timestamptz NOT NULL DEFAULT now()")
                add("created_by uuid")
                add("updated_by uuid")
                fields.forEach { add(columnDefinition(it, relationTables)) }
            }

        execute("CREATE TABLE $table (\n    ${columns.joinToString(",\n    ")}\n)")
        execute("CREATE INDEX ${SqlIdentifier.quote(obj.physicalTable + "_org_idx")} ON $table (organization_id)")
        fields.forEach { field -> indexStatements(obj, field).forEach { execute(it) } }
    }

    suspend fun addColumn(
        obj: CustomObject,
        field: CustomField,
        relationTables: Map<UUID, String> = emptyMap()
    ) {
        val table = schemas.dataTable(obj.physicalTable)
        execute("ALTER TABLE $table ADD COLUMN ${columnDefinition(field, relationTables)}")
        indexStatements(obj, field).forEach { execute(it) }
    }

    // a module that gives an object's records a state calls this (ADR-013). nullable on purpose:
    // records that predate it keep no state. never dropped: the state history must survive.
    suspend fun addStateColumn(obj: CustomObject) {
        val table = schemas.dataTable(obj.physicalTable)
        val column = SqlIdentifier.quote(STATE_COLUMN)
        execute("ALTER TABLE $table ADD COLUMN IF NOT EXISTS $column text")
        // the state is filtered and sorted on, so it is worth an index from day one
        execute("CREATE INDEX IF NOT EXISTS ${SqlIdentifier.quote(obj.physicalTable + "_state_idx")} ON $table ($column)")
    }

    suspend fun dropTable(obj: CustomObject) {
        execute("DROP TABLE IF EXISTS ${schemas.dataTable(obj.physicalTable)} CASCADE")
    }

    suspend fun dropColumn(
        obj: CustomObject,
        field: CustomField
    ) {
        execute("ALTER TABLE ${schemas.dataTable(obj.physicalTable)} DROP COLUMN IF EXISTS ${SqlIdentifier.quote(field.columnName)}")
    }

    suspend fun setRequired(
        obj: CustomObject,
        field: CustomField,
        required: Boolean
    ) {
        val action = if (required) "SET NOT NULL" else "DROP NOT NULL"
        execute("ALTER TABLE ${schemas.dataTable(obj.physicalTable)} ALTER COLUMN ${SqlIdentifier.quote(field.columnName)} $action")
    }

    suspend fun setUnique(
        obj: CustomObject,
        field: CustomField,
        unique: Boolean
    ) {
        dropConstraints(obj, field, UNIQUE_CONSTRAINT)
        if (unique) execute("ALTER TABLE ${schemas.dataTable(obj.physicalTable)} ADD UNIQUE (${SqlIdentifier.quote(field.columnName)})")
    }

    suspend fun replaceEnumCheck(
        obj: CustomObject,
        field: CustomField,
        options: List<String>
    ) {
        val column = SqlIdentifier.quote(field.columnName)
        dropConstraints(obj, field, CHECK_CONSTRAINT)
        if (options.isNotEmpty()) {
            execute(
                "ALTER TABLE ${schemas.dataTable(obj.physicalTable)} ADD CHECK ($column IN (${options.joinToString(", ") { SqlIdentifier.literal(it) }}))"
            )
        }
    }

    suspend fun createJoinTable(
        joinTable: String,
        source: CustomObject,
        target: CustomObject
    ) {
        val table = schemas.dataTable(joinTable)
        execute(
            """
            CREATE TABLE $table (
                organization_id uuid NOT NULL,
                source_id uuid NOT NULL REFERENCES ${schemas.dataTable(source.physicalTable)} (id) ON DELETE CASCADE,
                target_id uuid NOT NULL REFERENCES ${schemas.dataTable(target.physicalTable)} (id) ON DELETE CASCADE,
                created_at timestamptz NOT NULL DEFAULT now(),
                PRIMARY KEY (source_id, target_id)
            )
            """.trimIndent()
        )
        execute("CREATE INDEX ${SqlIdentifier.quote(joinTable + "_target_idx")} ON $table (target_id)")
    }

    suspend fun dropJoinTable(joinTable: String) {
        execute("DROP TABLE IF EXISTS ${schemas.dataTable(joinTable)}")
    }

    // the column as DDL. the type comes from its handler; enum and relation keep their core constraints.
    internal fun columnDefinition(
        field: CustomField,
        relationTables: Map<UUID, String>
    ): String {
        val column = SqlIdentifier.quote(field.columnName)
        val definition = StringBuilder("$column ${types.handler(field.type).columnType(field)}")
        if (field.required) definition.append(" NOT NULL")
        if (field.unique) definition.append(" UNIQUE")
        when (field.type) {
            FieldType.ENUM -> {
                val options = field.enumOptions.orEmpty()
                if (options.isEmpty()) {
                    throw ValidationException("Enum field '${field.name}' has no options", field.name, "requires enumOptions")
                }
                definition.append(" CHECK ($column IN (${options.joinToString(", ") { SqlIdentifier.literal(it) }}))")
            }
            FieldType.RELATION -> {
                val target =
                    field.relationTargetObjectId?.let { relationTables[it] }
                        ?: throw ValidationException(
                            "Relation field '${field.name}' has no resolvable target",
                            field.name,
                            "target object does not exist"
                        )
                definition.append(" REFERENCES ${schemas.dataTable(target)} (id) ON DELETE SET NULL")
            }
            else -> Unit
        }
        return definition.toString()
    }

    // whatever the type asks for once its column exists (a spatial index, say)
    internal fun indexStatements(
        obj: CustomObject,
        field: CustomField
    ): List<String> = types.handler(field.type).indexes(obj, schemas.dataTable(obj.physicalTable), field)

    // constraint names are generated by postgres, so drop what the catalog actually has
    private suspend fun dropConstraints(
        obj: CustomObject,
        field: CustomField,
        type: String
    ) {
        val table = schemas.dataTable(obj.physicalTable)
        constraintNames(obj.physicalTable, field.columnName, type).forEach { name ->
            execute("ALTER TABLE $table DROP CONSTRAINT ${SqlIdentifier.quote(name)}")
        }
    }

    private suspend fun constraintNames(
        table: String,
        column: String,
        type: String
    ): List<String> =
        db
            .sql(
                """
                SELECT c.conname
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (c.conkey)
                WHERE n.nspname = :schema AND t.relname = :table AND a.attname = :column AND c.contype = :type
                """.trimIndent()
            ).bind("schema", schemas.data)
            .bind("table", table)
            .bind("column", column)
            .bind("type", type)
            .map { row, _ -> row.get("conname", String::class.java)!! }
            .all()
            .asFlow()
            .toList()

    private suspend fun execute(sql: String) {
        db
            .sql(sql)
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
    }

    companion object {
        // the record-state column (ADR-013). the name is kept so existing tables need no rename.
        const val STATE_COLUMN = "workflow_state"
        private const val UNIQUE_CONSTRAINT = "u"
        private const val CHECK_CONSTRAINT = "c"
    }
}
```

- [ ] **Step 5: Edit `MetadataRepository.kt`** (Ruling R3)

1. Add `private val schemas: ChawpiSchemas` as the second constructor parameter of `CustomObjectRepository`. The schema rule already rewrote its SQL.
2. Replace the `FIELD_COLUMNS` constant and the whole `CustomFieldRepository` class with the code below. Keep `OBJECT_COLUMNS`, `CustomObjectRepository` and the `bindNullable` helper at the bottom of the file.
3. Imports: add `chawpi.core.platform.ChawpiSchemas`, `chawpi.core.platform.SqlIdentifier`, `io.r2dbc.spi.RowMetadata`.

```kotlin
private const val FIELD_COLUMNS =
    "id, object_id, name, label, type, column_name, required, is_unique, default_value, " +
        "description, position, enum_options::text AS enum_options, relation_target_object_id, visible, editable"

@Repository
class CustomFieldRepository(
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val schemas: ChawpiSchemas,
    private val types: FieldTypeRegistry
) {
    // core columns, then the ones installed field types own (R3). read into CustomField.attributes.
    private val attributeColumns: List<String> = types.attributeColumns.keys.toList()
    private val selectColumns: String = FIELD_COLUMNS + attributeColumns.joinToString("") { ", " + SqlIdentifier.quote(it) }
    private val table: String = "${schemas.metadata}.custom_fields"

    suspend fun insert(field: CustomField): CustomField {
        val extraColumns = attributeColumns.joinToString("") { ", " + SqlIdentifier.quote(it) }
        val extraValues = attributeColumns.indices.joinToString("") { ", :a$it" }
        var spec =
            db
                .sql(
                    """
                    INSERT INTO $table
                        (id, object_id, name, label, type, column_name, required, is_unique, default_value,
                         description, position, enum_options, relation_target_object_id, visible, editable$extraColumns)
                    VALUES (:id, :objectId, :name, :label, :type, :columnName, :required, :unique, :defaultValue,
                            :description, :position, CAST(:enumOptions AS jsonb), :relationTarget, :visible, :editable$extraValues)
                    RETURNING $selectColumns
                    """.trimIndent()
                ).bind("id", field.id)
                .bind("objectId", field.objectId)
                .bind("name", field.name)
                .bind("label", field.label)
                .bind("type", field.type.name)
                .bind("columnName", field.columnName)
                .bind("required", field.required)
                .bind("unique", field.unique)
                .bindNullable("defaultValue", field.defaultValue)
                .bindNullable("description", field.description)
                .bind("position", field.position)
                .bindNullable("enumOptions", field.enumOptions?.let { objectMapper.writeValueAsString(it) })
                .bindNullable("relationTarget", field.relationTargetObjectId)
                .bind("visible", field.visible)
                .bind("editable", field.editable)
        attributeColumns.forEachIndexed { index, column ->
            val value = field.attributes[column]
            spec = if (value == null) spec.bindNull("a$index", types.attributeColumns.getValue(column)) else spec.bind("a$index", value)
        }
        return spec.map(::mapField).one().awaitSingle()
    }

    suspend fun findByObject(objectId: UUID): List<CustomField> =
        db
            .sql("SELECT $selectColumns FROM $table WHERE object_id = :objectId ORDER BY position, name")
            .bind("objectId", objectId)
            .map(::mapField)
            .all()
            .asFlow()
            .toList()

    // one query for a whole listing
    suspend fun findByObjects(objectIds: List<UUID>): Map<UUID, List<CustomField>> {
        if (objectIds.isEmpty()) return emptyMap()
        return db
            .sql("SELECT $selectColumns FROM $table WHERE object_id IN (:objectIds) ORDER BY position, name")
            .bind("objectIds", objectIds)
            .map(::mapField)
            .all()
            .asFlow()
            .toList()
            .groupBy { it.objectId }
    }

    suspend fun findByName(
        objectId: UUID,
        name: String
    ): CustomField? =
        db
            .sql("SELECT $selectColumns FROM $table WHERE object_id = :objectId AND name = :name")
            .bind("objectId", objectId)
            .bind("name", name)
            .map(::mapField)
            .one()
            .awaitFirstOrNull()

    suspend fun findById(id: UUID): CustomField? =
        db
            .sql("SELECT $selectColumns FROM $table WHERE id = :id")
            .bind("id", id)
            .map(::mapField)
            .one()
            .awaitFirstOrNull()

    // attributes are fixed at creation, like the type: an update never touches them
    suspend fun update(field: CustomField): CustomField =
        db
            .sql(
                """
                UPDATE $table
                SET label = :label, required = :required, is_unique = :unique, description = :description,
                    position = :position, enum_options = CAST(:enumOptions AS jsonb), visible = :visible,
                    editable = :editable, updated_at = now()
                WHERE id = :id
                RETURNING $selectColumns
                """.trimIndent()
            ).bind("id", field.id)
            .bind("label", field.label)
            .bind("required", field.required)
            .bind("unique", field.unique)
            .bindNullable("description", field.description)
            .bind("position", field.position)
            .bindNullable("enumOptions", field.enumOptions?.let { objectMapper.writeValueAsString(it) })
            .bind("visible", field.visible)
            .bind("editable", field.editable)
            .map(::mapField)
            .one()
            .awaitSingle()

    suspend fun delete(id: UUID) {
        db
            .sql("DELETE FROM $table WHERE id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // who points at this object. deleting it would break their FK, so we refuse instead.
    suspend fun findByRelationTarget(targetObjectId: UUID): List<CustomField> =
        db
            .sql("SELECT $selectColumns FROM $table WHERE relation_target_object_id = :targetId")
            .bind("targetId", targetObjectId)
            .map(::mapField)
            .all()
            .asFlow()
            .toList()

    suspend fun maxPosition(objectId: UUID): Int =
        db
            .sql("SELECT COALESCE(MAX(position), -1) AS max_position FROM $table WHERE object_id = :objectId")
            .bind("objectId", objectId)
            .map { row, _ -> Rows.int(row, "max_position") }
            .one()
            .awaitSingle()

    private fun mapField(
        row: Row,
        metadata: RowMetadata
    ): CustomField =
        CustomField(
            id = Rows.uuid(row, "id"),
            objectId = Rows.uuid(row, "object_id"),
            name = Rows.string(row, "name"),
            label = Rows.string(row, "label"),
            // not parsed: a stored type whose module is gone must still list (FieldTypeRegistry.handler says 409)
            type = FieldType(Rows.string(row, "type")),
            columnName = Rows.string(row, "column_name"),
            required = Rows.bool(row, "required"),
            unique = Rows.bool(row, "is_unique"),
            defaultValue = Rows.stringOrNull(row, "default_value"),
            description = Rows.stringOrNull(row, "description"),
            position = Rows.int(row, "position"),
            enumOptions =
                Rows.stringOrNull(row, "enum_options")?.let {
                    objectMapper.readValue(it, object : TypeReference<List<String>>() {})
                },
            relationTargetObjectId = Rows.uuidOrNull(row, "relation_target_object_id"),
            attributes = attributeColumns.associateWith { row.get(it) },
            visible = Rows.bool(row, "visible"),
            editable = Rows.bool(row, "editable")
        )
}
```

- [ ] **Step 6: Edit `Relationship.kt`, `RelationshipService.kt`, `MetadataService.kt`, `MetadataController.kt`**

`Relationship.kt`: add `private val schemas: ChawpiSchemas` after `db` in `RelationshipRepository` (+ import). Replace `tableExists`:
```kotlin
    suspend fun tableExists(table: String): Boolean =
        db
            .sql("SELECT 1 FROM information_schema.tables WHERE table_schema = :schema AND table_name = :table")
            .bind("schema", schemas.data)
            .bind("table", table)
            .map { _, _ -> true }
            .one()
            .awaitFirstOrNull() ?: false
```

`RelationshipService.kt`:
- delete the imports of `chawpi.core.data.*`, `chawpi.core.identity.AccessPolicy`, `chawpi.core.common.PageResponse`, `kotlinx.coroutines.flow.toList`, `kotlinx.coroutines.reactive.asFlow`, `kotlinx.coroutines.reactive.awaitSingle`, `org.springframework.r2dbc.core.DatabaseClient`;
- constructor becomes:
```kotlin
class RelationshipService(
    private val relationships: RelationshipRepository,
    private val objects: CustomObjectRepository,
    private val fields: CustomFieldRepository,
    private val metadata: MetadataService,
    private val schema: ObjectSchemaManager,
    private val currentUser: CurrentUser
)
```
- delete these members entirely: `relatedRecords`, `relatedRows`, `link`, `unlink`, `manyToManyOrFail`, `linkedIds`, `relationFieldOrFail`, `usesJoinTableFor`, `fkIsOn`. Task 6 re-creates them in `data.RelatedRecordService`, with full code there.
- change `private suspend fun side(` to `suspend fun side(` and put this comment above it: `// one relationship seen from one object. data walks records with it.`

`MetadataService.kt`:
- delete `import chawpi.core.data.ObjectSchemaManager` (same package now) and add `import chawpi.core.platform.SystemColumns`. Keep `import chawpi.core.platform.SqlIdentifier`, because `createObject` still calls `SqlIdentifier.requireValidObjectName`;
- constructor becomes:
```kotlin
class MetadataService(
    private val objects: CustomObjectRepository,
    private val fields: CustomFieldRepository,
    private val relationships: RelationshipRepository,
    private val schema: ObjectSchemaManager,
    private val currentUser: CurrentUser,
    private val access: AccessPolicy,
    private val types: FieldTypeRegistry,
    private val systemColumns: SystemColumns,
    private val usages: List<FieldUsage>,
    private val removals: List<ObjectRemovalListener>
)
```
- in `updateField` replace the geometry block
```kotlin
        // a unique constraint on a geometry means nothing, and buildField already refuses it
        if (request.unique == true && existing.type == FieldType.GEOMETRY) {
            throw ValidationException("Geometry field '$fieldName' cannot be unique", "unique", "has no meaning on a geometry")
        }
```
with
```kotlin
        // the type's own rules (a module type may refuse unique)
        types.handler(existing.type).checkUpdate(existing, request)
```
- in `deleteField` replace `val users = usage.whoUses(obj, field.name)` with `val users = usages.flatMap { it.whoUses(obj, field.name) }`;
- in `deleteObject` replace `removal.objectRemoved(obj)` with `removals.forEach { it.objectRemoved(obj) }`;
- in `buildField` replace the first three lines
```kotlin
        val name = SqlIdentifier.requireValidFieldName(request.name.trim().lowercase())
        val type = FieldType.parse(request.type)
        val geometry = if (type == FieldType.GEOMETRY) geometryOf(name, request) else null
```
with
```kotlin
        val name = systemColumns.requireValidFieldName(request.name.trim().lowercase())
        val type = types.parse(request.type)
        // the type's own rules and stored attributes, checked before anything generic, as before
        val attributes = types.handler(type).attributesOf(name, request)
```
and in the returned `CustomField(...)` replace the three lines `geometryType = geometry?.type,`, `srid = geometry?.srid,`, `dimension = geometry?.dimension,` with `attributes = attributes,`;
- delete `private data class GeometrySpec`, `private fun geometryOf(...)`, and the companion constants `DEFAULT_SRID` and `MAX_SRID`. Keep `ENUM_OPTION`.
- reword two comments. Above `listDefinitions`: `// the same list with its fields attached, in one extra query. installed field types answer from the fields.` In `updateField`, `// views, forms and automations store field names, so a rename would break them in silence` becomes `// layouts and rules store field names, so a rename would break them in silence`.

`MetadataController.kt`:
- delete the imports of `chawpi.core.forms.*`, `chawpi.core.pages.*`, `chawpi.core.views.*` and `chawpi.core.platform.SqlIdentifier`, and add `import chawpi.core.platform.SystemColumns`;
- in `ObjectController`: `list()` becomes `metadata.listDefinitions().map(mapper::toObjectResponse)` and `update(...)` becomes `mapper.toObjectResponse(metadata.updateObject(name, request))`;
- in `ObjectMetadataController`: remove the constructor parameters `pageService`, `viewService`, `formService` and delete the three methods `views`, `forms`, `pages` together with their comments (Ruling R16);
- replace `SystemFieldController` with:
```kotlin
// static and tenant-free: core's names plus the ones installed modules own. saying whether *this*
// object has a given column would make metadata ask the module, so the scope names the condition.
@RestController
@RequestMapping("/api/metadata/system-fields")
class SystemFieldController(
    private val systemColumns: SystemColumns
) {
    @GetMapping
    fun systemFields(): List<SystemFieldResponse> = systemColumns.all.map { SystemFieldResponse(it.name, it.type, it.scope) }
}
```

- [ ] **Step 7: Rewrite `MetadataMapper.kt`**

```kotlin
package chawpi.core.metadata

import org.springframework.stereotype.Component
import java.util.UUID

// metadata -> api. relation fields store a target id and the api speaks object names; installed
// field types add their own keys (R5).
@Component
class MetadataMapper(
    private val objects: CustomObjectRepository,
    private val types: FieldTypeRegistry
) {
    fun toObjectResponse(definition: ObjectDefinition): ObjectResponse =
        ObjectResponse(
            id = definition.obj.id.toString(),
            name = definition.obj.name,
            label = definition.obj.label,
            pluralLabel = definition.obj.pluralLabel,
            description = definition.obj.description,
            enabled = definition.obj.enabled,
            createdAt = definition.obj.createdAt,
            updatedAt = definition.obj.updatedAt,
            extensions = types.objectProperties(definition)
        )

    suspend fun toResponse(
        definition: ObjectDefinition,
        organizationId: UUID
    ): ObjectDefinitionResponse =
        ObjectDefinitionResponse(
            id = definition.obj.id.toString(),
            name = definition.obj.name,
            label = definition.obj.label,
            pluralLabel = definition.obj.pluralLabel,
            description = definition.obj.description,
            enabled = definition.obj.enabled,
            fields = toFieldResponses(definition.fields, organizationId),
            extensions = types.objectProperties(definition)
        )

    suspend fun toFieldResponses(
        fields: List<CustomField>,
        organizationId: UUID
    ): List<FieldResponse> {
        val targetNames =
            fields
                .mapNotNull { it.relationTargetObjectId }
                .distinct()
                .mapNotNull { id -> objects.findById(organizationId, id)?.let { id to it.name } }
                .toMap()
        return fields.map { toFieldResponse(it, it.relationTargetObjectId?.let(targetNames::get)) }
    }

    fun toFieldResponse(
        field: CustomField,
        relationTargetName: String?
    ): FieldResponse =
        FieldResponse(
            id = field.id.toString(),
            name = field.name,
            label = field.label,
            type = field.type.name,
            required = field.required,
            unique = field.unique,
            defaultValue = field.defaultValue,
            description = field.description,
            position = field.position,
            enumOptions = field.enumOptions,
            relationTarget = relationTargetName,
            visible = field.visible,
            editable = field.editable,
            extensions = types.fieldProperties(field)
        )
}
```

- [ ] **Step 8: Run the tests to see them pass**

Run: `./gradlew :chawpi-core:test`
Expected: compilation succeeds. PASS: `ObjectSchemaManagerTest` (3), `MetadataMapperTest` (2), plus all earlier tests.

- [ ] **Step 9: Format, check, leave uncommitted**

```bash
./gradlew :chawpi-core:ktlintFormat :chawpi-core:ktlintCheck :chawpi-core:build
grep -rn -i -w "sapgis\|app_data\|geometry\|geometries\|postgis\|forms\|pages\|views\|workflow\|automation\|automations\|gis" $DST/metadata $DST/identity || echo "clean"
git status --short | head
```
Expected: build passes, `clean`, nothing committed.

---

### Task 6: `audit` + `data` (records, sections, query contributors, related records)

**Files:**
- Create (copied + renamed): `$DST/audit/{AuditController,AuditDiff,AuditQueryService,AuditService}.kt`
- Create (rewritten): `$DST/data/{RecordStore,PhysicalTableRecordStore,RecordService,RecordController,RecordChanges,WorkflowStates,RelatedRecordController}.kt`
- Create (new): `$DST/data/RecordQueryContributor.kt`, `$DST/data/RelatedRecordService.kt`
- Not copied: `data/RecordQueryParams.kt` (replaced by `RecordQueryParser`), `data/FieldValueCodec.kt` (moved in Task 4), `data/ObjectSchemaManager.kt` (moved in Task 5)
- Test: `$TDST/data/RecordQueryParserTest.kt`, `$TDST/data/RecordJsonTest.kt`, `$TDST/data/PhysicalTableRecordStoreTest.kt`

**Interfaces:**
- Consumes: `FieldTypeRegistry`, `FieldTypeHandler`, `ObjectSchemaManager.STATE_COLUMN`, `MetadataService`, `RelationshipService.side`, `RelationshipRepository`, `CustomObjectRepository`, `CustomFieldRepository`, `RelationshipMapper` (Tasks 4–5); `CurrentUser`, `AccessPolicy`, `FieldAccess` (Task 3); `ChawpiSchemas`, `PageRequest`, `PageResponse`.
- Produces:
  - `AuditService(db, objectMapper, schemas)` with `record(organizationId, userId, objectName, recordId, operation: AuditOperation, before = null, after = null, documentId: UUID? = null)`, `enum class AuditOperation { CREATE, UPDATE, DELETE, ISSUE }`, `AuditQueryService(db, objectMapper, currentUser, metadata, access, schemas)`. Routes unchanged.
  - `data class RecordRow(id, createdAt, updatedAt, attributes: Map<String, Any?>, sections: Map<String, Map<String, Any?>> = emptyMap(), state: String? = null)`
  - `data class RecordQuery(page, sort = null, descending = false, search = null, filters = emptyMap(), criteria: List<RecordCriterion> = emptyList(), ids: List<UUID>? = null, createdBy: UUID? = null, withState = false)`
  - `interface RecordStore` with `insert(definition, organizationId, userId, attributes, sections: Map<String, Map<String, Any?>>, workflow = ObjectWorkflowState.NONE)`, `update(definition, organizationId, userId, id, attributes, sections, withState = false)`, `transitionState(definition, organizationId, userId, id, from, to): RecordRow?`, `delete(definition, organizationId, id): Boolean`, `findById(definition, organizationId, id, createdBy = null, withState = false): RecordRow?`, `query(definition, organizationId, query): PageResponse<RecordRow>`
  - `PhysicalTableRecordStore(db, schemas, types)` with `internal fun selectList(definition, withState): String`
  - `fun interface RecordCriterion { fun condition(definition: ObjectDefinition, bind: (Any) -> String): String }`, `interface RecordQueryContributor { val parameters: Set<String>; fun parse(params: Map<String, String>): RecordCriterion? }`, `class RecordQueryParser(contributors: List<RecordQueryContributor>)` with `parse(params): RecordQuery` and `CORE_PARAMETERS = setOf("page","size","sort","dir","q","limit")`
  - `data class RecordRequest(attributes)` + `sections: MutableMap<String, Map<String, Any?>>` (any-setter); `data class RecordResponse(id, createdAt, updatedAt, attributes, state = null, sections = emptyMap())` (sections flattened by any-getter); `fun RecordRow.toResponse(): RecordResponse`
  - `RecordService(metadata, store, audit, currentUser, access, workflows, types, changes: List<RecordChangeListener>)` with `list`, `get`, `create`, `update`, `delete`, `rows(objectName, query): Pair<ObjectDefinition, List<RecordRow>>` (was `featureRows`)
  - `RecordChange(...)` (same fields as sapgis), `enum RecordChangeKind`, `interface RecordChangeListener { suspend fun recordChanged(change: RecordChange) }`
  - `data class ObjectWorkflowState(attached, initialState)` + `NONE`, `interface WorkflowStates { stateOf(organizationId, objectId); transitionNames(organizationId, objectId): Set<String> }`, `class NoWorkflowStates : WorkflowStates`
  - `RelatedRecordService(relationships, relationshipService, objects, fields, metadata, store, currentUser, access, db, schemas)` with `relatedRecords(objectName, recordId, relationshipName, query)`, `relatedRows(organizationId, objectName, recordId, relationshipName, query, narrow = { it })`, `link(...)`, `unlink(...)`
  - Controllers (routes unchanged): `RecordController(records, queries)` `/api/objects/{object}/records`, `RelatedRecordController(relationships, related, mapper, queries)` `/api/objects/{object}/relationships` and `/records/{id}/related/{relationship}`

- [ ] **Step 1: Write the failing tests**

`$TDST/data/RecordQueryParserTest.kt`:
```kotlin
package chawpi.core.data

import chawpi.core.common.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RecordQueryParserTest {
    // a module parameter, like gis's bbox: reserved, and turned into a criterion only when sent
    private val near =
        object : RecordQueryContributor {
            override val parameters = setOf("near")

            override fun parse(params: Map<String, String>): RecordCriterion? {
                val raw = params["near"] ?: return null
                val value = raw.toIntOrNull() ?: throw ValidationException("Invalid near", "near", "must be a number")
                return RecordCriterion { _, bind -> "distance < ${bind(value)}" }
            }
        }

    @Test
    fun `core parses paging, sort, search and treats the rest as field filters`() {
        val query = RecordQueryParser(emptyList()).parse(mapOf("page" to "2", "size" to "10", "sort" to "codigo", "dir" to "DESC", "q" to "x", "limit" to "5", "uso" to "COMERCIAL"))

        assertThat(query.page.page).isEqualTo(2)
        assertThat(query.page.size).isEqualTo(10)
        assertThat(query.descending).isTrue()
        assertThat(query.search).isEqualTo("x")
        assertThat(query.filters).containsExactly(org.assertj.core.api.Assertions.entry("uso", "COMERCIAL"))
        assertThat(query.criteria).isEmpty()
    }

    @Test
    fun `without the module that owns it, bbox is just an unknown field filter`() {
        assertThat(RecordQueryParser(emptyList()).parse(mapOf("bbox" to "1,2,3,4")).filters).containsKey("bbox")
    }

    @Test
    fun `a contributor owns its parameters and turns them into a criterion`() {
        val parser = RecordQueryParser(listOf(near))

        val sent = parser.parse(mapOf("near" to "7", "uso" to "A"))
        assertThat(sent.filters).containsOnlyKeys("uso")
        val bound = mutableListOf<Any>()
        assertThat(sent.criteria.single().condition(ObjectDefinitionFixtures.empty()) { bound += it; ":c0" }).isEqualTo("distance < :c0")
        assertThat(bound).containsExactly(7)

        assertThat(parser.parse(mapOf("uso" to "A")).criteria).isEmpty()
        assertThatThrownBy { parser.parse(mapOf("near" to "lejos")) }.isInstanceOf(ValidationException::class.java)
    }
}
```

`$TDST/data/ObjectDefinitionFixtures.kt` (test helper shared by the data tests):
```kotlin
package chawpi.core.data

import chawpi.core.metadata.CustomField
import chawpi.core.metadata.CustomObject
import chawpi.core.metadata.FieldType
import chawpi.core.metadata.ObjectDefinition
import java.util.UUID

object ObjectDefinitionFixtures {
    val obj = CustomObject(UUID.randomUUID(), UUID.randomUUID(), "predio", "Predio", "Predios", null, true, "predio__1234abcd", null, null)

    fun empty(): ObjectDefinition = ObjectDefinition(obj, emptyList())

    fun field(
        name: String,
        type: FieldType
    ) = CustomField(UUID.randomUUID(), obj.id, name, name, type, name, false, false, null, null, 0, null, null, emptyMap(), true, true)
}
```

`$TDST/data/RecordJsonTest.kt`:
```kotlin
package chawpi.core.data

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class RecordJsonTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `sections are flattened next to attributes, empty and null values included`() {
        val response = RecordResponse("1", null, null, mapOf("codigo" to "A"), null, mapOf("geometries" to mapOf("lote" to null), "extra" to emptyMap()))
        val tree = mapper.readTree(mapper.writeValueAsString(response))

        assertThat(tree.has("sections")).isFalse()
        assertThat(tree.get("geometries").has("lote")).isTrue()
        assertThat(tree.get("geometries").get("lote").isNull).isTrue()
        assertThat(tree.get("extra").size()).isEqualTo(0)
        assertThat(tree.has("state")).isTrue()
    }

    @Test
    fun `with no module a record has only the core keys`() {
        val tree = mapper.readTree(mapper.writeValueAsString(RecordResponse("1", null, null, emptyMap())))

        assertThat(tree.propertyNames().asSequence().toList()).containsExactlyInAnyOrder("id", "createdAt", "updatedAt", "attributes", "state")
    }

    @Test
    fun `a request keeps object-valued unknown properties as sections and drops the rest`() {
        val request = mapper.readValue("""{"attributes":{"codigo":"A"},"geometries":{"lote":null},"id":"x"}""", RecordRequest::class.java)

        assertThat(request.attributes).containsEntry("codigo", "A")
        assertThat(request.sections).containsOnlyKeys("geometries")
        assertThat(request.sections.getValue("geometries")).containsEntry("lote", null)
    }
}
```
(Use `fieldNames()` instead of `propertyNames()` if that is the Jackson 3 name on the classpath. See Task 4.)

`$TDST/data/PhysicalTableRecordStoreTest.kt`:
```kotlin
package chawpi.core.data

import chawpi.core.metadata.CustomField
import chawpi.core.metadata.FieldType
import chawpi.core.metadata.FieldTypeHandler
import chawpi.core.metadata.FieldTypeRegistry
import chawpi.core.metadata.ObjectDefinition
import chawpi.core.platform.ChawpiSchemas
import chawpi.core.platform.SqlIdentifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient

class PhysicalTableRecordStoreTest {
    private val measure = FieldType("MEASURE")

    private val measureHandler =
        object : FieldTypeHandler {
            override val type = measure
            override val section = "measures"

            override fun columnType(field: CustomField) = "numeric"

            override fun select(
                field: CustomField,
                column: String
            ) = "CAST($column AS text) AS ${SqlIdentifier.quote(readName(field))}"

            override fun readName(field: CustomField) = field.columnName + "__txt"

            override fun toDatabase(
                field: CustomField,
                value: Any?
            ) = value

            override fun javaType(field: CustomField) = String::class.java

            override fun fromDatabase(
                field: CustomField,
                value: Any?
            ) = value
        }

    private fun store(vararg extra: FieldTypeHandler) =
        PhysicalTableRecordStore(mock(DatabaseClient::class.java), ChawpiSchemas("chawpi", "app_data"), FieldTypeRegistry(extra.toList()))

    private val codigo = ObjectDefinitionFixtures.field("codigo", FieldType.TEXT)

    @Test
    fun `core columns are selected plainly and the state only when asked`() {
        val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo))

        assertThat(store().selectList(definition, false)).isEqualTo("id, created_at, updated_at, \"codigo\"")
        assertThat(store().selectList(definition, true)).isEqualTo("id, created_at, updated_at, \"codigo\", \"workflow_state\"")
    }

    @Test
    fun `a module type is selected through its own expression`() {
        val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo, ObjectDefinitionFixtures.field("area", measure)))

        assertThat(store(measureHandler).selectList(definition, false))
            .isEqualTo("id, created_at, updated_at, \"codigo\", CAST(\"area\" AS text) AS \"area__txt\"")
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :chawpi-core:test`
Expected: compilation FAILS with `Unresolved reference 'RecordQueryParser'` / `'RecordResponse'` / `'PhysicalTableRecordStore'`.

- [ ] **Step 3: Copy and rename `audit`**

```bash
mkdir -p $DST/audit $DST/data $TDST/data
cp $SRC/audit/{AuditController,AuditDiff,AuditQueryService,AuditService}.kt $DST/audit/
sed -i '' -e 's/com\.sapgis\./chawpi.core./g' -e 's/SapgisException/ChawpiException/g' $DST/audit/*.kt
sed -i '' -E -e '/^[[:space:]]*\/\//!s/([^a-zA-Z_.])sapgis\.([a-z_]+)/\1${schemas.metadata}.\2/g' $DST/audit/*.kt
```
Add `private val schemas: ChawpiSchemas` (last constructor parameter) plus the import to `AuditService` and `AuditQueryService`. In `AuditService.kt`, put this comment above `enum class AuditOperation`:
```kotlin
// ISSUE is written by a module; the audit_log CHECK accepts it once that module's migration ran (R10)
```

- [ ] **Step 4: Write `RecordChanges.kt`, `WorkflowStates.kt`, `RecordQueryContributor.kt`, `RecordStore.kt`**

`$DST/data/RecordChanges.kt`:
```kotlin
package chawpi.core.data

import java.util.UUID

enum class RecordChangeKind { CREATED, UPDATED, DELETED, TRANSITIONED }

// what happened to one record, with the values as they were. listeners judge this snapshot,
// never a fresh read: by the time they act the row may have moved on.
data class RecordChange(
    val organizationId: UUID,
    val userId: UUID?,
    val objectId: UUID,
    val objectName: String,
    val recordId: UUID,
    val kind: RecordChangeKind,
    val before: Map<String, Any?>? = null,
    val after: Map<String, Any?>? = null,
    val state: String? = null,
    val transition: String? = null,
    // how deep a chain of listener-caused changes already is, and what caused it. both stop loops.
    val depth: Int = 0,
    val causedBy: UUID? = null
)

// told of every record change, inside the same transaction, in @Order. data never reaches into
// a module's tables itself.
interface RecordChangeListener {
    suspend fun recordChanged(change: RecordChange)
}
```

`$DST/data/WorkflowStates.kt`:
```kotlin
package chawpi.core.data

import java.util.UUID

// what the record path needs to know about an object's record state (ADR-013). nothing more.
data class ObjectWorkflowState(
    // the physical table carries the state column, so it can be selected
    val attached: Boolean,
    // where a brand new record starts. null when no state machine is enabled.
    val initialState: String?
) {
    companion object {
        val NONE = ObjectWorkflowState(false, null)
    }
}

// port. a module that gives records a state implements it; data never reads that module's tables.
interface WorkflowStates {
    suspend fun stateOf(
        organizationId: UUID,
        objectId: UUID
    ): ObjectWorkflowState

    // other modules validate actions against these. the record path never asks.
    suspend fun transitionNames(
        organizationId: UUID,
        objectId: UUID
    ): Set<String>
}

// no module installed: no object has a state
class NoWorkflowStates : WorkflowStates {
    override suspend fun stateOf(
        organizationId: UUID,
        objectId: UUID
    ): ObjectWorkflowState = ObjectWorkflowState.NONE

    override suspend fun transitionNames(
        organizationId: UUID,
        objectId: UUID
    ): Set<String> = emptySet()
}
```

`$DST/data/RecordQueryContributor.kt`:
```kotlin
package chawpi.core.data

import chawpi.core.common.PageRequest
import chawpi.core.metadata.ObjectDefinition
import org.springframework.stereotype.Component

// a condition a module adds to a record query. values go through `bind`, which returns the
// placeholder to write: nothing from the request is ever interpolated.
fun interface RecordCriterion {
    fun condition(
        definition: ObjectDefinition,
        bind: (Any) -> String
    ): String
}

// a module that owns query-string parameters turns them into criteria (R7)
interface RecordQueryContributor {
    // never read as field filters, whether sent or not
    val parameters: Set<String>

    // null when none of its parameters was sent. a bad value throws ValidationException.
    fun parse(params: Map<String, String>): RecordCriterion?
}

// query string -> RecordQuery. anything not reserved is an equality filter on a field.
@Component
class RecordQueryParser(
    private val contributors: List<RecordQueryContributor>
) {
    private val reserved: Set<String> = CORE_PARAMETERS + contributors.flatMap { it.parameters }

    fun parse(params: Map<String, String>): RecordQuery =
        RecordQuery(
            page = PageRequest.of(params["page"]?.toIntOrNull(), params["size"]?.toIntOrNull()),
            sort = params["sort"],
            descending = params["dir"].equals("desc", ignoreCase = true),
            search = params["q"],
            filters = params.filterKeys { it !in reserved },
            criteria = contributors.mapNotNull { it.parse(params) }
        )

    companion object {
        val CORE_PARAMETERS = setOf("page", "size", "sort", "dir", "q", "limit")
    }
}
```

`$DST/data/RecordStore.kt`:
```kotlin
package chawpi.core.data

import chawpi.core.common.PageRequest
import chawpi.core.common.PageResponse
import chawpi.core.metadata.ObjectDefinition
import java.time.Instant
import java.util.UUID

data class RecordRow(
    val id: UUID,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val attributes: Map<String, Any?>,
    // one map per installed section, every field of that section listed, null included
    val sections: Map<String, Map<String, Any?>> = emptyMap(),
    // null when the object has no state, or when the record predates it
    val state: String? = null
)

data class RecordQuery(
    val page: PageRequest,
    val sort: String? = null,
    val descending: Boolean = false,
    val search: String? = null,
    val filters: Map<String, String> = emptyMap(),
    // conditions installed modules add (R7)
    val criteria: List<RecordCriterion> = emptyList(),
    val ids: List<UUID>? = null,
    // record-level security. non-null = only rows this user created.
    val createdBy: UUID? = null,
    // selecting the state column on a table that has none would blow up
    val withState: Boolean = false
)

// port. physical tables today, could be jsonb tomorrow without touching callers. ADR-004.
interface RecordStore {
    suspend fun insert(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        attributes: Map<String, Any?>,
        sections: Map<String, Map<String, Any?>>,
        workflow: ObjectWorkflowState = ObjectWorkflowState.NONE
    ): RecordRow

    suspend fun update(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        id: UUID,
        attributes: Map<String, Any?>,
        sections: Map<String, Map<String, Any?>>,
        withState: Boolean = false
    ): RecordRow

    // moves the state only while the record still sits in `from`. null = it moved on without us.
    suspend fun transitionState(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        id: UUID,
        from: String?,
        to: String
    ): RecordRow?

    suspend fun delete(
        definition: ObjectDefinition,
        organizationId: UUID,
        id: UUID
    ): Boolean

    suspend fun findById(
        definition: ObjectDefinition,
        organizationId: UUID,
        id: UUID,
        createdBy: UUID? = null,
        withState: Boolean = false
    ): RecordRow?

    suspend fun query(
        definition: ObjectDefinition,
        organizationId: UUID,
        query: RecordQuery
    ): PageResponse<RecordRow>
}
```

- [ ] **Step 5: Write `PhysicalTableRecordStore.kt`**

```kotlin
package chawpi.core.data

import chawpi.core.common.NotFoundException
import chawpi.core.common.PageResponse
import chawpi.core.common.ValidationException
import chawpi.core.metadata.CustomField
import chawpi.core.metadata.FieldTypeRegistry
import chawpi.core.metadata.ObjectDefinition
import chawpi.core.metadata.ObjectSchemaManager
import chawpi.core.platform.ChawpiSchemas
import chawpi.core.platform.Rows
import chawpi.core.platform.SqlIdentifier
import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.util.UUID

// one physical table per object (ADR-004). every column goes through its type's handler, so a
// module type brings its own select and bind sql and core never learns what it stores.
@Component
class PhysicalTableRecordStore(
    private val db: DatabaseClient,
    private val schemas: ChawpiSchemas,
    private val types: FieldTypeRegistry
) : RecordStore {
    override suspend fun insert(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        attributes: Map<String, Any?>,
        sections: Map<String, Map<String, Any?>>,
        workflow: ObjectWorkflowState
    ): RecordRow {
        val writable = attributeFields(definition).filter { it.editable }
        val values = writable.associateWith { types.handler(it.type).toDatabase(it, attributes[it.name]) }
        val sectionValues = sectionValues(definition, sections)

        val columns = mutableListOf("organization_id", "created_by", "updated_by")
        val placeholders = mutableListOf(":organizationId", ":userId", ":userId")
        values.keys.forEachIndexed { index, field ->
            columns += SqlIdentifier.quote(field.columnName)
            placeholders += types.handler(field.type).bindExpression(field, "p$index")
        }
        sectionValues.keys.forEachIndexed { index, field ->
            columns += SqlIdentifier.quote(field.columnName)
            placeholders += types.handler(field.type).bindExpression(field, "s$index")
        }
        // a state machine that is enabled means a new record is born in its initial state
        if (workflow.initialState != null) {
            columns += SqlIdentifier.quote(ObjectSchemaManager.STATE_COLUMN)
            placeholders += ":recordState"
        }

        var spec =
            db
                .sql(
                    """
                    INSERT INTO ${tableOf(definition)} (${columns.joinToString(", ")})
                    VALUES (${placeholders.joinToString(", ")})
                    RETURNING ${selectList(definition, workflow.attached)}
                    """.trimIndent()
                ).bind("organizationId", organizationId)
                .bind("userId", userId)
        spec = bindValues(spec, "p", values)
        spec = bindValues(spec, "s", sectionValues)
        if (workflow.initialState != null) spec = spec.bind("recordState", workflow.initialState)

        return spec.map { row, _ -> mapRow(definition, row, workflow.attached) }.one().awaitSingle()
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
        val writable = attributeFields(definition).filter { it.editable }
        val values = writable.associateWith { types.handler(it.type).toDatabase(it, attributes[it.name]) }
        val sectionValues = sectionValues(definition, sections)

        val assignments = mutableListOf("updated_at = now()", "updated_by = :userId")
        values.keys.forEachIndexed { index, field ->
            assignments += "${SqlIdentifier.quote(field.columnName)} = ${types.handler(field.type).bindExpression(field, "p$index")}"
        }
        sectionValues.keys.forEachIndexed { index, field ->
            assignments += "${SqlIdentifier.quote(field.columnName)} = ${types.handler(field.type).bindExpression(field, "s$index")}"
        }

        var spec =
            db
                .sql(
                    """
                    UPDATE ${tableOf(definition)}
                    SET ${assignments.joinToString(", ")}
                    WHERE id = :id AND organization_id = :organizationId
                    RETURNING ${selectList(definition, withState)}
                    """.trimIndent()
                ).bind("id", id)
                .bind("organizationId", organizationId)
                .bind("userId", userId)
        spec = bindValues(spec, "p", values)
        spec = bindValues(spec, "s", sectionValues)

        return spec.map { row, _ -> mapRow(definition, row, withState) }.one().awaitFirstOrNull()
            ?: throw NotFoundException("Record $id does not exist")
    }

    // guarded by the current state in the WHERE, so a racing caller loses instead of overwriting
    override suspend fun transitionState(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        id: UUID,
        from: String?,
        to: String
    ): RecordRow? {
        val column = SqlIdentifier.quote(ObjectSchemaManager.STATE_COLUMN)
        val guard = if (from == null) "$column IS NULL" else "$column = :from"
        var spec =
            db
                .sql(
                    """
                    UPDATE ${tableOf(definition)}
                    SET $column = :to, updated_at = now(), updated_by = :userId
                    WHERE id = :id AND organization_id = :organizationId AND $guard
                    RETURNING ${selectList(definition, true)}
                    """.trimIndent()
                ).bind("id", id)
                .bind("organizationId", organizationId)
                .bind("userId", userId)
                .bind("to", to)
        if (from != null) spec = spec.bind("from", from)
        return spec.map { row, _ -> mapRow(definition, row, true) }.one().awaitFirstOrNull()
    }

    override suspend fun delete(
        definition: ObjectDefinition,
        organizationId: UUID,
        id: UUID
    ): Boolean =
        db
            .sql("DELETE FROM ${tableOf(definition)} WHERE id = :id AND organization_id = :organizationId")
            .bind("id", id)
            .bind("organizationId", organizationId)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    // a record the caller may not see must look missing, not forbidden: no existence leak
    override suspend fun findById(
        definition: ObjectDefinition,
        organizationId: UUID,
        id: UUID,
        createdBy: UUID?,
        withState: Boolean
    ): RecordRow? {
        val owner = if (createdBy == null) "" else " AND created_by = :createdBy"
        var spec =
            db
                .sql(
                    "SELECT ${selectList(definition, withState)} FROM ${tableOf(definition)} " +
                        "WHERE id = :id AND organization_id = :organizationId$owner"
                ).bind("id", id)
                .bind("organizationId", organizationId)
        if (createdBy != null) spec = spec.bind("createdBy", createdBy)
        return spec
            .map { row, _ -> mapRow(definition, row, withState) }
            .one()
            .awaitFirstOrNull()
    }

    override suspend fun query(
        definition: ObjectDefinition,
        organizationId: UUID,
        query: RecordQuery
    ): PageResponse<RecordRow> {
        val table = tableOf(definition)
        val conditions = mutableListOf("organization_id = :organizationId")
        val bindings = mutableMapOf<String, Any>("organizationId" to organizationId)

        query.filters.entries.forEachIndexed { index, (name, raw) ->
            val field = fieldOrFail(definition, name)
            val value =
                types.handler(field.type).toDatabase(field.copy(required = false), raw)
                    ?: return@forEachIndexed
            conditions += "${SqlIdentifier.quote(field.columnName)} = :f$index"
            bindings["f$index"] = value
        }

        query.search?.takeIf { it.isNotBlank() }?.let { term ->
            val textColumns = definition.fields.filter { types.handler(it.type).textLike }
            if (textColumns.isNotEmpty()) {
                conditions +=
                    "(" +
                    textColumns.joinToString(" OR ") { "${SqlIdentifier.quote(it.columnName)} ILIKE :search" } +
                    ")"
                bindings["search"] = "%$term%"
            }
        }

        query.createdBy?.let { owner ->
            conditions += "created_by = :createdBy"
            bindings["createdBy"] = owner
        }

        query.ids?.let { ids ->
            if (ids.isEmpty()) return PageResponse.of(emptyList(), query.page.page, query.page.size, 0)
            conditions += "id = ANY(:ids)"
            bindings["ids"] = ids.toTypedArray()
        }

        // module conditions. every value they need is bound here, under a name of ours.
        query.criteria.forEach { criterion ->
            conditions +=
                criterion.condition(definition) { value ->
                    val name = "c${bindings.size}"
                    bindings[name] = value
                    ":$name"
                }
        }

        val where = conditions.joinToString(" AND ")
        val order = orderBy(definition, query)

        var countSpec = db.sql("SELECT COUNT(*) AS total FROM $table WHERE $where")
        bindings.forEach { (name, value) -> countSpec = countSpec.bind(name, value) }
        val total = countSpec.map { row, _ -> Rows.long(row, "total") }.one().awaitSingle()

        var rowsSpec =
            db.sql(
                "SELECT ${selectList(definition, query.withState)} FROM $table WHERE $where $order " +
                    "LIMIT :limit OFFSET :offset"
            )
        bindings.forEach { (name, value) -> rowsSpec = rowsSpec.bind(name, value) }
        val rows =
            rowsSpec
                .bind("limit", query.page.size)
                .bind("offset", query.page.offset)
                .map { row, _ -> mapRow(definition, row, query.withState) }
                .all()
                .asFlow()
                .toList()

        return PageResponse.of(rows, query.page.page, query.page.size, total)
    }

    internal fun selectList(
        definition: ObjectDefinition,
        withState: Boolean
    ): String {
        val columns = mutableListOf("id", "created_at", "updated_at")
        definition.fields.forEach { field ->
            columns += types.handler(field.type).select(field, SqlIdentifier.quote(field.columnName))
        }
        if (withState) columns += SqlIdentifier.quote(ObjectSchemaManager.STATE_COLUMN)
        return columns.joinToString(", ")
    }

    private fun tableOf(definition: ObjectDefinition) = schemas.dataTable(definition.obj.physicalTable)

    private fun sectionOf(field: CustomField): String? = types.handler(field.type).section

    // fields whose value travels in "attributes"
    private fun attributeFields(definition: ObjectDefinition) = definition.fields.filter { sectionOf(it) == null }

    // only the section entries the caller sent. one left out is left alone; one sent as null is cleared.
    private fun sectionValues(
        definition: ObjectDefinition,
        sections: Map<String, Map<String, Any?>>
    ): Map<CustomField, Any?> {
        val values = linkedMapOf<CustomField, Any?>()
        sections.forEach { (section, entries) ->
            if (section !in types.sections) return@forEach
            entries.forEach { (name, value) ->
                val field =
                    definition.fields.firstOrNull { it.name == name && sectionOf(it) == section }
                        ?: throw types.sectionOwner(section).unknownSectionKey(name, definition)
                values[field] = value?.let { types.handler(field.type).toDatabase(field, it) }
            }
        }
        return values
    }

    private fun orderBy(
        definition: ObjectDefinition,
        query: RecordQuery
    ): String {
        val direction = if (query.descending) "DESC" else "ASC"
        val requested = query.sort?.trim()?.lowercase()
        val column =
            when {
                requested.isNullOrBlank() -> "created_at"
                requested in setOf("id", "created_at", "updated_at") -> requested
                else -> SqlIdentifier.quote(fieldOrFail(definition, requested).columnName)
            }
        return "ORDER BY $column $direction"
    }

    private fun fieldOrFail(
        definition: ObjectDefinition,
        name: String
    ): CustomField {
        val field =
            definition.fields.firstOrNull { it.name == name }
                ?: throw ValidationException("Unknown field '$name'", name, "is not a field of '${definition.obj.name}'")
        types.handler(field.type).rejectFilterOrSort(field)?.let { throw it }
        return field
    }

    private fun bindValues(
        spec: DatabaseClient.GenericExecuteSpec,
        prefix: String,
        values: Map<CustomField, Any?>
    ): DatabaseClient.GenericExecuteSpec {
        var current = spec
        values.entries.forEachIndexed { index, (field, value) ->
            val name = "$prefix$index"
            current = if (value == null) current.bindNull(name, types.handler(field.type).javaType(field)) else current.bind(name, value)
        }
        return current
    }

    private fun read(
        field: CustomField,
        row: Row
    ): Any? {
        val handler = types.handler(field.type)
        return handler.fromDatabase(field, row.get(handler.readName(field)))
    }

    private fun mapRow(
        definition: ObjectDefinition,
        row: Row,
        withState: Boolean
    ): RecordRow =
        RecordRow(
            id = Rows.uuid(row, "id"),
            createdAt = Rows.instantOrNull(row, "created_at"),
            updatedAt = Rows.instantOrNull(row, "updated_at"),
            attributes = attributeFields(definition).associate { it.name to read(it, row) },
            // every field of every installed section is listed, null included: the caller should not
            // have to guess whether a missing key means "empty" or "not a field of this object"
            sections =
                types.sections.associateWith { section ->
                    definition.fields.filter { sectionOf(it) == section }.associate { it.name to read(it, row) }
                },
            state = if (withState) Rows.stringOrNull(row, ObjectSchemaManager.STATE_COLUMN) else null
        )
}
```

- [ ] **Step 6: Write `RecordService.kt`**

```kotlin
package chawpi.core.data

import chawpi.core.audit.AuditOperation
import chawpi.core.audit.AuditService
import chawpi.core.common.Actions
import chawpi.core.common.ConflictException
import chawpi.core.common.ForbiddenException
import chawpi.core.common.NotFoundException
import chawpi.core.common.PageResponse
import chawpi.core.common.ValidationException
import chawpi.core.identity.AccessPolicy
import chawpi.core.identity.CurrentUser
import chawpi.core.identity.FieldAccess
import chawpi.core.metadata.FieldTypeRegistry
import chawpi.core.metadata.MetadataService
import chawpi.core.metadata.ObjectDefinition
import chawpi.core.metadata.readableBy
import chawpi.core.metadata.readableNames
import chawpi.core.metadata.writableBy
import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

data class RecordRequest(
    val attributes: Map<String, Any?> = emptyMap()
) {
    // payload sections of installed field types, by section then field. left out is left alone, null clears.
    @get:JsonIgnore
    val sections: MutableMap<String, Map<String, Any?>> = linkedMapOf()

    // anything that is not an object cannot be a section, so it is ignored like any unknown property
    @JsonAnySetter
    fun section(
        name: String,
        value: Any?
    ) {
        if (value is Map<*, *>) sections[name] = value.entries.associate { (key, item) -> key.toString() to item }
    }
}

data class RecordResponse(
    val id: String,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val attributes: Map<String, Any?>,
    // the record's state. null when nothing gives the object one.
    val state: String? = null,
    // one key per installed section (R5). kept out of the json as itself, written flat below.
    @get:JsonIgnore val sections: Map<String, Map<String, Any?>> = emptyMap()
) {
    @JsonAnyGetter
    fun flattened(): Map<String, Map<String, Any?>> = sections
}

@Service
class RecordService(
    private val metadata: MetadataService,
    private val store: RecordStore,
    private val audit: AuditService,
    private val currentUser: CurrentUser,
    private val access: AccessPolicy,
    private val workflows: WorkflowStates,
    private val types: FieldTypeRegistry,
    private val changes: List<RecordChangeListener>
) {
    suspend fun list(
        objectName: String,
        query: RecordQuery
    ): PageResponse<RecordResponse> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        val fieldAccess = access.fieldAccess(user, definition.obj.id)
        // unreadable columns are never selected, so they cannot leak by accident
        val visible = definition.readableBy(fieldAccess)
        val workflow = workflows.stateOf(user.organizationId, definition.obj.id)
        val page =
            store.query(
                visible,
                user.organizationId,
                query.copy(createdBy = access.ownerFilter(user), withState = workflow.attached)
            )
        return PageResponse(
            content = page.content.map { it.toResponse() },
            page = page.page,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages
        )
    }

    suspend fun get(
        objectName: String,
        id: UUID
    ): RecordResponse {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        val visible = definition.readableBy(access.fieldAccess(user, definition.obj.id))
        val workflow = workflows.stateOf(user.organizationId, definition.obj.id)
        return store
            .findById(visible, user.organizationId, id, access.ownerFilter(user), workflow.attached)
            ?.toResponse()
            ?: throw NotFoundException("Record $id does not exist")
    }

    suspend fun create(
        objectName: String,
        request: RecordRequest
    ): RecordResponse {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.CREATE, definition.obj.id)
        rejectDisabled(definition)
        val sections = installed(request.sections)
        val fieldAccess = access.fieldAccess(user, definition.obj.id)
        rejectUnwritable(definition, fieldAccess, request.attributes, sections)
        rejectUnwritableRequired(definition, fieldAccess)
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
        // the whole row, not the caller's projection: a listener must not judge a record by the
        // fields this user happens to be allowed to see.
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
        return created.onlyReadable(definition, fieldAccess).toResponse()
    }

    suspend fun update(
        objectName: String,
        id: UUID,
        request: RecordRequest
    ): RecordResponse {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.UPDATE, definition.obj.id)
        rejectDisabled(definition)
        val sections = installed(request.sections)
        val fieldAccess = access.fieldAccess(user, definition.obj.id)
        rejectUnwritable(definition, fieldAccess, request.attributes, sections)
        val workflow = workflows.stateOf(user.organizationId, definition.obj.id)
        val before =
            store.findById(definition, user.organizationId, id, access.ownerFilter(user), workflow.attached)
                ?: throw NotFoundException("Record $id does not exist")
        // locked fields keep their stored value: a full-replace PUT must not blank them.
        // the state is untouched here: it only moves through a transition.
        val updated =
            store.update(
                definition.writableBy(fieldAccess),
                user.organizationId,
                user.userId,
                id,
                request.attributes,
                sections,
                workflow.attached
            )
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
        return updated.onlyReadable(definition, fieldAccess).toResponse()
    }

    suspend fun delete(
        objectName: String,
        id: UUID
    ) {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.DELETE, definition.obj.id)
        rejectDisabled(definition)
        val before =
            store.findById(definition, user.organizationId, id, access.ownerFilter(user))
                ?: throw NotFoundException("Record $id does not exist")
        store.delete(definition, user.organizationId, id)
        audit.record(
            organizationId = user.organizationId,
            userId = user.userId,
            objectName = objectName,
            recordId = id,
            operation = AuditOperation.DELETE,
            before = before.attributes
        )
        notify(
            RecordChange(
                organizationId = user.organizationId,
                userId = user.userId,
                objectId = definition.obj.id,
                objectName = definition.obj.name,
                recordId = id,
                kind = RecordChangeKind.DELETED,
                before = before.attributes,
                state = before.state
            )
        )
    }

    // rows rather than pages, for modules that render records their own way
    suspend fun rows(
        objectName: String,
        query: RecordQuery
    ): Pair<ObjectDefinition, List<RecordRow>> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        val visible = definition.readableBy(access.fieldAccess(user, definition.obj.id))
        return visible to
            store
                .query(visible, user.organizationId, query.copy(createdBy = access.ownerFilter(user)))
                .content
    }

    private suspend fun notify(change: RecordChange) {
        changes.forEach { it.recordChanged(change) }
    }

    // sections no installed type owns are ignored, like any unknown property
    private fun installed(sections: Map<String, Map<String, Any?>>) = sections.filterKeys { it in types.sections }

    // a disabled object is retired, not gone: its data stays readable, nothing new lands on it
    private fun rejectDisabled(definition: ObjectDefinition) {
        if (!definition.obj.enabled) {
            throw ConflictException("Object '${definition.obj.name}' is disabled and accepts no changes")
        }
    }

    // dropping the value silently would let the caller believe the edit landed. a section field is
    // a field, so its write permission is the field's: checking only `attributes` would let a locked
    // one in through the other door.
    private fun rejectUnwritable(
        definition: ObjectDefinition,
        fieldAccess: FieldAccess,
        attributes: Map<String, Any?>,
        sections: Map<String, Map<String, Any?>>
    ) {
        if (fieldAccess.unrestricted) return
        val denied =
            definition.fields.firstOrNull { field ->
                (attributes.containsKey(field.name) || sections.values.any { it.containsKey(field.name) }) && !fieldAccess.canWrite(field.id)
            }
        if (denied != null) {
            throw ValidationException(
                "Field '${denied.name}' is not writable for you",
                denied.name,
                "your roles may not write this field"
            )
        }
    }

    // a required field nobody may write would fail on NOT NULL: say so instead of a 500
    private fun rejectUnwritableRequired(
        definition: ObjectDefinition,
        fieldAccess: FieldAccess
    ) {
        if (fieldAccess.unrestricted) return
        val blocked =
            definition.fields.firstOrNull { it.required && it.defaultValue == null && !fieldAccess.canWrite(it.id) }
                ?: return
        throw ForbiddenException(
            "Field '${blocked.name}' is required but your roles may not write it, so you cannot create ${definition.obj.name}"
        )
    }

    private fun RecordRow.onlyReadable(
        definition: ObjectDefinition,
        fieldAccess: FieldAccess
    ): RecordRow {
        if (fieldAccess.unrestricted) return this
        val allowed = definition.readableNames(fieldAccess)
        return copy(
            attributes = attributes.filterKeys { it in allowed },
            sections = sections.mapValues { (_, entries) -> entries.filterKeys { it in allowed } }
        )
    }
}

fun RecordRow.toResponse(): RecordResponse =
    RecordResponse(
        id = id.toString(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        attributes = attributes,
        state = state,
        sections = sections
    )
```

- [ ] **Step 7: Write `RelatedRecordService.kt`** (moved out of `metadata.RelationshipService`, Ruling R1)

```kotlin
package chawpi.core.data

import chawpi.core.common.Actions
import chawpi.core.common.NotFoundException
import chawpi.core.common.PageResponse
import chawpi.core.common.ValidationException
import chawpi.core.identity.AccessPolicy
import chawpi.core.identity.CurrentUser
import chawpi.core.metadata.CustomField
import chawpi.core.metadata.CustomFieldRepository
import chawpi.core.metadata.CustomObject
import chawpi.core.metadata.CustomObjectRepository
import chawpi.core.metadata.MetadataService
import chawpi.core.metadata.ObjectDefinition
import chawpi.core.metadata.Relationship
import chawpi.core.metadata.RelationshipRepository
import chawpi.core.metadata.RelationshipService
import chawpi.core.metadata.readableBy
import chawpi.core.platform.ChawpiSchemas
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// records on the other side of a relationship. metadata owns the relationship; walking its
// records is a record matter, so it lives here.
@Service
class RelatedRecordService(
    private val relationships: RelationshipRepository,
    private val relationshipService: RelationshipService,
    private val objects: CustomObjectRepository,
    private val fields: CustomFieldRepository,
    private val metadata: MetadataService,
    private val store: RecordStore,
    private val currentUser: CurrentUser,
    private val access: AccessPolicy,
    private val db: DatabaseClient,
    private val schemas: ChawpiSchemas
) {
    // records on the other side of a relationship, from one record
    suspend fun relatedRecords(
        objectName: String,
        recordId: UUID,
        relationshipName: String,
        query: RecordQuery
    ): Pair<ObjectDefinition, PageResponse<RecordRow>> {
        val user = currentUser.require()
        val obj =
            objects.findByName(user.organizationId, objectName)
                ?: throw NotFoundException("Object '$objectName' does not exist")
        currentUser.requirePermission(user, Actions.READ, obj.id)
        return relatedRows(
            user.organizationId,
            objectName,
            recordId,
            relationshipName,
            query.copy(createdBy = access.ownerFilter(user)),
            narrow = { definition -> definition.readableBy(access.fieldAccess(user, definition.obj.id)) }
        )
    }

    // the same walk with no caller behind it: a module acting as the platform (ADR-016) has no user
    // to check. callers that DO have a user pass `narrow` to put their field rules back.
    suspend fun relatedRows(
        organizationId: UUID,
        objectName: String,
        recordId: UUID,
        relationshipName: String,
        query: RecordQuery,
        narrow: suspend (ObjectDefinition) -> ObjectDefinition = { it }
    ): Pair<ObjectDefinition, PageResponse<RecordRow>> {
        val obj =
            objects.findByName(organizationId, objectName)
                ?: throw NotFoundException("Object '$objectName' does not exist")
        val relationship =
            relationships.findByName(organizationId, relationshipName)
                ?: throw NotFoundException("Relationship '$relationshipName' does not exist")
        val view = relationshipService.side(relationship, obj)
        val otherDefinition = narrow(metadata.loadDefinition(organizationId, view.otherObject.name))

        val resolved =
            when {
                relationship.usesJoinTableFor() -> {
                    val ids = linkedIds(relationship, obj, recordId)
                    store.query(otherDefinition, organizationId, query.copy(ids = ids))
                }
                fkIsOn(relationship, obj) -> {
                    // this record carries the foreign key: follow it to a single record
                    val definition = metadata.loadDefinition(organizationId, obj.name)
                    val field = relationFieldOrFail(relationship)
                    val value = store.findById(definition, organizationId, recordId)?.attributes?.get(field.name)
                    val targetId = (value as? String)?.let(UUID::fromString)
                    store.query(otherDefinition, organizationId, query.copy(ids = listOfNotNull(targetId)))
                }
                else -> {
                    // the other side points back at this record
                    val field = relationFieldOrFail(relationship)
                    store.query(otherDefinition, organizationId, query.copy(filters = query.filters + (field.name to recordId.toString())))
                }
            }
        return otherDefinition to resolved
    }

    @Transactional
    suspend fun link(
        objectName: String,
        recordId: UUID,
        relationshipName: String,
        otherId: UUID
    ) {
        val (relationship, obj) = manyToManyOrFail(objectName, relationshipName)
        val sourceId = if (obj.id == relationship.sourceObjectId) recordId else otherId
        val targetId = if (obj.id == relationship.sourceObjectId) otherId else recordId
        db
            .sql(
                """
                INSERT INTO ${schemas.dataTable(relationship.joinTable!!)}
                    (organization_id, source_id, target_id)
                VALUES (:organizationId, :sourceId, :targetId)
                ON CONFLICT DO NOTHING
                """.trimIndent()
            ).bind("organizationId", relationship.organizationId)
            .bind("sourceId", sourceId)
            .bind("targetId", targetId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    @Transactional
    suspend fun unlink(
        objectName: String,
        recordId: UUID,
        relationshipName: String,
        otherId: UUID
    ) {
        val (relationship, obj) = manyToManyOrFail(objectName, relationshipName)
        val sourceId = if (obj.id == relationship.sourceObjectId) recordId else otherId
        val targetId = if (obj.id == relationship.sourceObjectId) otherId else recordId
        db
            .sql("DELETE FROM ${schemas.dataTable(relationship.joinTable!!)} WHERE source_id = :sourceId AND target_id = :targetId")
            .bind("sourceId", sourceId)
            .bind("targetId", targetId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun manyToManyOrFail(
        objectName: String,
        relationshipName: String
    ): Pair<Relationship, CustomObject> {
        val user = currentUser.require()
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

    private suspend fun linkedIds(
        relationship: Relationship,
        obj: CustomObject,
        recordId: UUID
    ): List<UUID> {
        val fromSource = obj.id == relationship.sourceObjectId
        val selected = if (fromSource) "target_id" else "source_id"
        val matched = if (fromSource) "source_id" else "target_id"
        return db
            .sql("SELECT $selected AS other_id FROM ${schemas.dataTable(relationship.joinTable!!)} WHERE $matched = :recordId")
            .bind("recordId", recordId)
            .map { row, _ -> row.get("other_id", UUID::class.java)!! }
            .all()
            .asFlow()
            .toList()
    }

    private suspend fun relationFieldOrFail(relationship: Relationship): CustomField =
        relationship.relationFieldId?.let { fields.findById(it) }
            ?: throw NotFoundException("Relationship '${relationship.name}' has no field")

    private fun Relationship.usesJoinTableFor(): Boolean = type.usesJoinTable && joinTable != null

    // true when the record we start from owns the foreign key column
    private fun fkIsOn(
        relationship: Relationship,
        obj: CustomObject
    ): Boolean =
        (relationship.type.fkOnSource && obj.id == relationship.sourceObjectId) ||
            (relationship.type.fkOnTarget && obj.id == relationship.targetObjectId)
}
```

- [ ] **Step 8: Write the two controllers**

`$DST/data/RecordController.kt`:
```kotlin
package chawpi.core.data

import chawpi.core.common.PageResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/objects/{object}/records")
class RecordController(
    private val records: RecordService,
    private val queries: RecordQueryParser
) {
    @GetMapping
    suspend fun list(
        @PathVariable("object") objectName: String,
        @RequestParam params: Map<String, String>
    ): PageResponse<RecordResponse> = records.list(objectName, queries.parse(params))

    @GetMapping("/{id}")
    suspend fun get(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID
    ): RecordResponse = records.get(objectName, id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @PathVariable("object") objectName: String,
        @RequestBody request: RecordRequest
    ): RecordResponse = records.create(objectName, request)

    @PutMapping("/{id}")
    suspend fun update(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @RequestBody request: RecordRequest
    ): RecordResponse = records.update(objectName, id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID
    ) = records.delete(objectName, id)
}
```

`$DST/data/RelatedRecordController.kt`:
```kotlin
package chawpi.core.data

import chawpi.core.common.PageResponse
import chawpi.core.metadata.RelatedSideResponse
import chawpi.core.metadata.RelationshipMapper
import chawpi.core.metadata.RelationshipService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class LinkRequest(
    val otherId: String
)

@RestController
@RequestMapping("/api/objects/{object}")
class RelatedRecordController(
    private val relationships: RelationshipService,
    private val related: RelatedRecordService,
    private val mapper: RelationshipMapper,
    private val queries: RecordQueryParser
) {
    @GetMapping("/relationships")
    suspend fun relationshipsOf(
        @PathVariable("object") objectName: String
    ): List<RelatedSideResponse> = relationships.forObject(objectName).map(mapper::toResponse)

    @GetMapping("/records/{id}/related/{relationship}")
    suspend fun related(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @PathVariable relationship: String,
        @RequestParam params: Map<String, String>
    ): PageResponse<RecordResponse> {
        val (_, page) = related.relatedRecords(objectName, id, relationship, queries.parse(params))
        return PageResponse(
            content = page.content.map { it.toResponse() },
            page = page.page,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages
        )
    }

    @PostMapping("/records/{id}/related/{relationship}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun link(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @PathVariable relationship: String,
        @RequestBody request: LinkRequest
    ) = related.link(objectName, id, relationship, UUID.fromString(request.otherId))

    @DeleteMapping("/records/{id}/related/{relationship}/{otherId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun unlink(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @PathVariable relationship: String,
        @PathVariable otherId: UUID
    ) = related.unlink(objectName, id, relationship, otherId)
}
```

- [ ] **Step 9: Run the tests to see them pass**

Run: `./gradlew :chawpi-core:test`
Expected: PASS: `RecordQueryParserTest` (3), `RecordJsonTest` (3), `PhysicalTableRecordStoreTest` (2), plus all earlier tests.

- [ ] **Step 10: Format, check, leave uncommitted**

```bash
./gradlew :chawpi-core:ktlintFormat :chawpi-core:ktlintCheck :chawpi-core:build
grep -rn -i -w "sapgis\|app_data\|geometry\|geometries\|postgis\|bbox\|geojson\|automation\|automations\|documents\|gis" $DST/data $DST/audit || echo "clean"
git status --short | head
```
Expected: build passes, `clean`, nothing committed.

---

### Task 7: `admin` (users, roles, permissions) + `organization`

A mechanical move. Moving `Admin*` into its own package is what breaks the identity↔metadata cycle (R1). No unit test is added here: `AdminApiTest`, `OrganizationApiTest` and `PermissionEnforcementTest` (Task 11) pin the behaviour. This task's gate is a green compile plus the existing unit tests.

**Files:**
- Create (copied + renamed + package moved): `$DST/admin/{AdminController,AdminDtos,AdminService}.kt` (from `identity/`)
- Create (copied + renamed): `$DST/organization/{OrganizationController,OrganizationService}.kt`
- Create (rewritten): `$DST/organization/Organization.kt`

**Interfaces:**
- Consumes: `CurrentUser`, `AuthenticatedUser` (Task 3); `MetadataService.loadDefinition`, `ObjectDefinition`, `CustomObjectRepository.findAll`, `ObjectSchemaManager.dropTable/dropJoinTable` (Task 5); `ChawpiSchemas`.
- Produces:
  - `chawpi.core.admin.AdminService(db, metadata, passwordEncoder, currentUser, schemas)`; `UserAdminController(admin)` (`/api/users`) and `RoleAdminController(admin)` (`/api/roles`), both in `AdminController.kt`, same routes; DTOs unchanged
  - `data class Organization(id, name, slug, createdAt = null, updatedAt = null)`, `@Repository class OrganizationRepository(db, schemas)` with `suspend fun findById(id: UUID): Organization?`, `suspend fun findBySlug(slug: String): Organization?`
  - `OrganizationService(organizations, objects, schema, passwordEncoder, currentUser, db, schemas)`, `OrganizationController` (`/api/organizations`, `/current`; same routes), `fun Organization.toResponse()`

- [ ] **Step 1: Copy, rename, move the package, point SQL at the schema**

```bash
mkdir -p $DST/admin $DST/organization
cp $SRC/identity/{AdminController,AdminDtos,AdminService}.kt $DST/admin/
cp $SRC/organization/{OrganizationController,OrganizationService}.kt $DST/organization/
sed -i '' -e 's/com\.sapgis\./chawpi.core./g' -e 's/SapgisException/ChawpiException/g' $DST/admin/*.kt $DST/organization/*.kt
sed -i '' -e 's/^package chawpi\.core\.identity$/package chawpi.core.admin/' $DST/admin/*.kt
sed -i '' -E -e '/^[[:space:]]*\/\//!s/([^a-zA-Z_.])sapgis\.([a-z_]+)/\1${schemas.metadata}.\2/g' $DST/admin/*.kt $DST/organization/*.kt
sed -i '' -e 's/import chawpi\.core\.data\.ObjectSchemaManager/import chawpi.core.metadata.ObjectSchemaManager/' $DST/organization/OrganizationService.kt
```

- [ ] **Step 2: Hand edits**

`AdminService.kt`:
- add the imports `chawpi.core.identity.AuthenticatedUser`, `chawpi.core.identity.CurrentUser` and `chawpi.core.platform.ChawpiSchemas`;
- add `private val schemas: ChawpiSchemas` as the last constructor parameter;
- if the compiler reports more unresolved `identity` symbols, import them from `chawpi.core.identity`.

`OrganizationService.kt`: add `private val schemas: ChawpiSchemas` as the last constructor parameter, plus the import.

Replace `$DST/organization/Organization.kt` (Ruling R11):
```kotlin
package chawpi.core.organization

import chawpi.core.platform.ChawpiSchemas
import chawpi.core.platform.Rows
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

data class Organization(
    val id: UUID,
    val name: String,
    val slug: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
)

// plain sql: a @Table annotation cannot follow a configurable schema
@Repository
class OrganizationRepository(
    private val db: DatabaseClient,
    private val schemas: ChawpiSchemas
) {
    suspend fun findById(id: UUID): Organization? =
        db
            .sql("SELECT id, name, slug, created_at, updated_at FROM ${schemas.metadata}.organizations WHERE id = :id")
            .bind("id", id)
            .map { row, _ -> map(row) }
            .one()
            .awaitFirstOrNull()

    suspend fun findBySlug(slug: String): Organization? =
        db
            .sql("SELECT id, name, slug, created_at, updated_at FROM ${schemas.metadata}.organizations WHERE slug = :slug")
            .bind("slug", slug)
            .map { row, _ -> map(row) }
            .one()
            .awaitFirstOrNull()

    private fun map(row: Row): Organization =
        Organization(
            id = Rows.uuid(row, "id"),
            name = Rows.string(row, "name"),
            slug = Rows.string(row, "slug"),
            createdAt = Rows.instantOrNull(row, "created_at"),
            updatedAt = Rows.instantOrNull(row, "updated_at")
        )
}
```

- [ ] **Step 3: Compile and run the unit tests**

Run: `./gradlew :chawpi-core:test`
Expected: compilation succeeds and every unit test from Tasks 2–6 still PASSES.

- [ ] **Step 4: Nothing in `main` still references sapgis or a module**

```bash
grep -rn -i "sapgis\|'app_data'\|\"app_data\"" $DST || echo "clean"
grep -rn "import chawpi\.\(views\|forms\|pages\|workflow\|automation\|documents\|gis\|agent\)" $DST || echo "clean"
```
Expected: `clean` twice.

- [ ] **Step 5: Format, check, leave uncommitted**

```bash
./gradlew :chawpi-core:ktlintFormat :chawpi-core:ktlintCheck :chawpi-core:build
git status --short | head
```
Expected: build passes, nothing committed.

---

### Task 8: Per-module migrations (`ModuleMigration`, `ChawpiMigrations`) + rebaselined core SQL

**Files:**
- Create: `$DST/platform/ModuleMigration.kt`, `$DST/platform/ChawpiMigrations.kt`
- Create: `backend/chawpi-core/src/main/resources/db/chawpi/core/V1__core.sql`
- Create: `backend/chawpi-core/src/main/resources/db/chawpi/core-seed/V1__seed_dev.sql`
- Test: `$TDST/platform/ChawpiMigrationsTest.kt`, `$TDST/platform/CoreMigrationSqlTest.kt`

**Interfaces:**
- Consumes: `ChawpiDatabaseProperties` (`jdbcUrl`, `username`, `password`), `ChawpiSchemas` (Task 2).
- Produces:
  - `data class ModuleMigration(name: String, location: String, order: Int)` with `historyTable = "flyway_history_<name>"`, companions `CORE_ORDER = 0`, `CORE_SEED_ORDER = 10`, `MODULE_ORDER = 100`, `CORE`, `CORE_SEED`
  - `class ChawpiMigrations(database: ChawpiDatabaseProperties, schemas: ChawpiSchemas, migrations: List<ModuleMigration>)` with `plan: List<ModuleMigration>`, `fun migrate()` (the auto-config calls it as `initMethod`), `internal fun flyway(module): Flyway`
  - SQL placeholders every module migration may use: `${metadataSchema}`, `${dataSchema}`
  - Core tables in `${metadataSchema}`: `organizations`, `users`, `roles`, `user_roles`, `custom_objects`, `custom_fields`, `relationships`, `permissions`, `field_permissions`, `audit_log`

- [ ] **Step 1: Write the failing tests**

`$TDST/platform/ChawpiMigrationsTest.kt`:
```kotlin
package chawpi.core.platform

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ChawpiMigrationsTest {
    private val database = ChawpiDatabaseProperties()
    private val schemas = ChawpiSchemas("acme_meta", "acme_data")

    @Test
    fun `modules run in order, core first, ties broken by name`() {
        val gis = ModuleMigration("gis", "classpath:db/chawpi/gis", ModuleMigration.MODULE_ORDER)
        val forms = ModuleMigration("forms", "classpath:db/chawpi/forms", ModuleMigration.MODULE_ORDER)

        val plan = ChawpiMigrations(database, schemas, listOf(gis, ModuleMigration.CORE_SEED, forms, ModuleMigration.CORE)).plan

        assertThat(plan.map { it.name }).containsExactly("core", "core_seed", "forms", "gis")
    }

    @Test
    fun `each module keeps its own history table and gets the schema placeholders`() {
        val flyway = ChawpiMigrations(database, schemas, listOf(ModuleMigration.CORE)).flyway(ModuleMigration.CORE)
        val configuration = flyway.configuration

        assertThat(configuration.table).isEqualTo("flyway_history_core")
        assertThat(configuration.defaultSchema).isEqualTo("acme_meta")
        assertThat(configuration.placeholders).containsEntry("metadataSchema", "acme_meta").containsEntry("dataSchema", "acme_data")
        assertThat(configuration.isBaselineOnMigrate).isTrue()
        assertThat(configuration.baselineVersion.version).isEqualTo("0")
        assertThat(configuration.locations.map { it.descriptor }).containsExactly("classpath:db/chawpi/core")
    }

    @Test
    fun `a module registered twice, or with an unsafe name, fails at boot`() {
        assertThatThrownBy { ChawpiMigrations(database, schemas, listOf(ModuleMigration.CORE, ModuleMigration.CORE)) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { ModuleMigration("core-seed", "classpath:x", 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
```
If Flyway 12 renamed `Location.descriptor`, use `it.toString()`. The assertion stays the same.

`$TDST/platform/CoreMigrationSqlTest.kt`:
```kotlin
package chawpi.core.platform

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// the rebaseline must stay core-only and schema-agnostic. the real schema check is the IT suite.
class CoreMigrationSqlTest {
    private fun sql(path: String): String = javaClass.getResource(path)!!.readText()

    private val core = sql("/db/chawpi/core/V1__core.sql")
    private val seed = sql("/db/chawpi/core-seed/V1__seed_dev.sql")

    @Test
    fun `every table is created in the placeholder schema, never a literal one`() {
        assertThat(core).doesNotContainIgnoringCase("sapgis")
        Regex("CREATE TABLE ([^ ]+)").findAll(core).forEach { match ->
            assertThat(match.groupValues[1]).startsWith("\${metadataSchema}.")
        }
        assertThat(core).contains("CREATE SCHEMA IF NOT EXISTS \${dataSchema};")
    }

    @Test
    fun `core creates exactly the core tables and nothing a module owns`() {
        val tables = Regex("CREATE TABLE \\$\\{metadataSchema}\\.([a-z_]+)").findAll(core).map { it.groupValues[1] }.toList()

        assertThat(tables).containsExactly(
            "organizations",
            "users",
            "roles",
            "user_roles",
            "custom_objects",
            "custom_fields",
            "relationships",
            "permissions",
            "field_permissions",
            "audit_log"
        )
        assertThat(core).doesNotContainIgnoringCase("postgis")
        assertThat(core).doesNotContain("GEOMETRY", "geometry_type", "'ISSUE'")
    }

    @Test
    fun `the dev seed names the chawpi admin and every admin action`() {
        assertThat(seed).contains("admin@chawpi.local").contains("MANAGE_ORGANIZATION").doesNotContainIgnoringCase("sapgis")
    }

    @Test
    fun `the seeded hash really is the password admin`() {
        val hash = Regex("'(\\$2[aby]\\$10\\$[./A-Za-z0-9]{53})'").find(seed)!!.groupValues[1]

        assertThat(org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches("admin", hash)).isTrue()
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :chawpi-core:test --tests 'chawpi.core.platform.*Migration*'`
Expected: compilation FAILS (`Unresolved reference 'ModuleMigration'`). After Step 3 the SQL test fails with a `NullPointerException` because the resources do not exist yet.

- [ ] **Step 3: Write `ModuleMigration.kt` and `ChawpiMigrations.kt`**

`$DST/platform/ModuleMigration.kt`:
```kotlin
package chawpi.core.platform

// one module's flyway scripts. each module keeps its own history table, so a module can join an
// app later without touching anyone else's history. ADR-0026.
data class ModuleMigration(
    val name: String,
    val location: String,
    val order: Int
) {
    init {
        require(NAME.matches(name)) { "module migration name '$name' must match ${NAME.pattern}" }
    }

    val historyTable: String get() = "flyway_history_$name"

    companion object {
        private val NAME = Regex("^[a-z][a-z0-9_]{0,40}$")

        const val CORE_ORDER = 0
        const val CORE_SEED_ORDER = 10

        // modules start here and space themselves out in dependency order
        const val MODULE_ORDER = 100

        val CORE = ModuleMigration("core", "classpath:db/chawpi/core", CORE_ORDER)

        // opt-in: chawpi.seed.dev=true
        val CORE_SEED = ModuleMigration("core_seed", "classpath:db/chawpi/core-seed", CORE_SEED_ORDER)
    }
}
```

`$DST/platform/ChawpiMigrations.kt`:
```kotlin
package chawpi.core.platform

import org.flywaydb.core.Flyway

// runs every module's migrations at startup, before traffic, in order. flyway is jdbc only,
// so it gets its own short-lived connection. ADR-008, ADR-0026.
class ChawpiMigrations(
    private val database: ChawpiDatabaseProperties,
    private val schemas: ChawpiSchemas,
    migrations: List<ModuleMigration>
) {
    val plan: List<ModuleMigration> = migrations.sortedWith(compareBy({ it.order }, { it.name }))

    init {
        val twice = plan.groupBy { it.name }.filterValues { it.size > 1 }.keys
        check(twice.isEmpty()) { "module migration registered twice: ${twice.joinToString(", ")}" }
    }

    fun migrate() {
        plan.forEach { flyway(it).migrate() }
    }

    internal fun flyway(module: ModuleMigration): Flyway =
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .schemas(schemas.metadata)
            .defaultSchema(schemas.metadata)
            .table(module.historyTable)
            .locations(module.location)
            // every module after the first finds the schema in use; baseline 0 still runs its V1
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .placeholders(mapOf("metadataSchema" to schemas.metadata, "dataSchema" to schemas.data))
            .load()
}
```

- [ ] **Step 4: Write `db/chawpi/core/V1__core.sql`**

This is the final sapgis schema (V1–V14) restricted to core tables. Take V1's core tables and apply the later changes:
- V3: the `MANAGE_ORGANIZATION` action; `relationships.source_field_id` renamed to `relation_field_id`; `inverse_label` added; the relationship indexes.
- V6: `roles.own_records_only`; `field_permissions` and its index.
- V9: `custom_objects` loses its geometry columns. `custom_fields` gets no geometry columns (R3).
- V14: `audit_log.document_id` without an FK (R10).

Column order and constraint names match sapgis, so a gis/documents install ends identical.

```sql
-- chawpi core schema: the original V1..V14 rebaselined, core tables only (ADR-0026).
-- ${metadataSchema} = metadata + identity, fixed, flyway owns it.
-- ${dataSchema}     = business data, one table per custom object, built at runtime. ADR-004.
-- modules add their own tables, columns and constraints in their own migrations.

CREATE SCHEMA IF NOT EXISTS ${metadataSchema};
CREATE SCHEMA IF NOT EXISTS ${dataSchema};

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- pgvector only when the server ships it. nothing in core depends on it.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'vector') THEN
        CREATE EXTENSION IF NOT EXISTS vector;
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- orgs + identity
-- ---------------------------------------------------------------------------

CREATE TABLE ${metadataSchema}.organizations (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text NOT NULL,
    slug       text NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ${metadataSchema}.users (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    email           text NOT NULL,
    password_hash   text NOT NULL,
    display_name    text NOT NULL,
    enabled         boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT users_email_unique_per_org UNIQUE (organization_id, email)
);

CREATE INDEX users_email_idx ON ${metadataSchema}.users (lower(email));

-- own_records_only: a role that only sees what its users created (field- and record-level security)
CREATE TABLE ${metadataSchema}.roles (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    name             text NOT NULL,
    label            text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    own_records_only boolean NOT NULL DEFAULT false,
    CONSTRAINT roles_name_unique_per_org UNIQUE (organization_id, name)
);

CREATE TABLE ${metadataSchema}.user_roles (
    user_id uuid NOT NULL REFERENCES ${metadataSchema}.users (id) ON DELETE CASCADE,
    role_id uuid NOT NULL REFERENCES ${metadataSchema}.roles (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- ---------------------------------------------------------------------------
-- metadata: custom objects + fields
-- ---------------------------------------------------------------------------

CREATE TABLE ${metadataSchema}.custom_objects (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    name            text NOT NULL,
    label           text NOT NULL,
    plural_label    text NOT NULL,
    description     text,
    enabled         boolean NOT NULL DEFAULT true,
    physical_table  text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custom_objects_name_unique_per_org UNIQUE (organization_id, name),
    CONSTRAINT custom_objects_physical_table_unique UNIQUE (physical_table)
);

-- a module that adds a field type re-adds custom_fields_type_valid with its type appended (R4),
-- and adds its own attribute columns after updated_at (R3)
CREATE TABLE ${metadataSchema}.custom_fields (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    object_id                 uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    name                      text NOT NULL,
    label                     text NOT NULL,
    type                      text NOT NULL,
    column_name               text NOT NULL,
    required                  boolean NOT NULL DEFAULT false,
    is_unique                 boolean NOT NULL DEFAULT false,
    default_value             text,
    description               text,
    position                  integer NOT NULL DEFAULT 0,
    validation                jsonb,
    enum_options              jsonb,
    relation_target_object_id uuid REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE RESTRICT,
    visible                   boolean NOT NULL DEFAULT true,
    editable                  boolean NOT NULL DEFAULT true,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custom_fields_name_unique_per_object UNIQUE (object_id, name),
    CONSTRAINT custom_fields_type_valid CHECK (type IN (
        'TEXT', 'LONG_TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATETIME',
        'ENUM', 'EMAIL', 'URL', 'UUID', 'RELATION'
    )),
    CONSTRAINT custom_fields_enum_has_options CHECK (type <> 'ENUM' OR enum_options IS NOT NULL),
    CONSTRAINT custom_fields_relation_has_target CHECK (
        type <> 'RELATION' OR relation_target_object_id IS NOT NULL
    )
);

CREATE INDEX custom_fields_object_idx ON ${metadataSchema}.custom_fields (object_id, position);

-- ---------------------------------------------------------------------------
-- relationships. the FK lives on the source for MANY_TO_ONE and on the target for ONE_TO_MANY,
-- so the field is named relation_field_id. its FK keeps the name postgres gave it before the
-- column was renamed, so the schema stays identical to the original.
-- ---------------------------------------------------------------------------

CREATE TABLE ${metadataSchema}.relationships (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    name              text NOT NULL,
    label             text NOT NULL,
    type              text NOT NULL,
    source_object_id  uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    target_object_id  uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    relation_field_id uuid,
    join_table        text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    inverse_label     text,
    CONSTRAINT relationships_source_field_id_fkey FOREIGN KEY (relation_field_id)
        REFERENCES ${metadataSchema}.custom_fields (id) ON DELETE SET NULL,
    CONSTRAINT relationships_name_unique_per_org UNIQUE (organization_id, name),
    CONSTRAINT relationships_type_valid CHECK (type IN (
        'ONE_TO_ONE', 'ONE_TO_MANY', 'MANY_TO_ONE', 'MANY_TO_MANY'
    ))
);

CREATE INDEX relationships_source_idx ON ${metadataSchema}.relationships (source_object_id);
CREATE INDEX relationships_target_idx ON ${metadataSchema}.relationships (target_object_id);

-- ---------------------------------------------------------------------------
-- permissions. object/action, plus field-level rules.
-- restriction model: no field rule = full access. a role narrows access, it never widens it.
-- ---------------------------------------------------------------------------

CREATE TABLE ${metadataSchema}.permissions (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    role_id   uuid NOT NULL REFERENCES ${metadataSchema}.roles (id) ON DELETE CASCADE,
    object_id uuid REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    action    text NOT NULL,
    allowed   boolean NOT NULL DEFAULT true,
    CONSTRAINT permissions_action_valid CHECK (action IN (
        'READ', 'CREATE', 'UPDATE', 'DELETE', 'MANAGE_METADATA', 'MANAGE_ORGANIZATION'
    )),
    CONSTRAINT permissions_unique UNIQUE NULLS NOT DISTINCT (role_id, object_id, action)
);

CREATE TABLE ${metadataSchema}.field_permissions (
    role_id   uuid NOT NULL REFERENCES ${metadataSchema}.roles (id) ON DELETE CASCADE,
    field_id  uuid NOT NULL REFERENCES ${metadataSchema}.custom_fields (id) ON DELETE CASCADE,
    can_read  boolean NOT NULL DEFAULT true,
    can_write boolean NOT NULL DEFAULT true,
    PRIMARY KEY (role_id, field_id)
);

-- dropping a field wipes its rules; the index keeps that cascade cheap
CREATE INDEX field_permissions_field_idx ON ${metadataSchema}.field_permissions (field_id);

-- ---------------------------------------------------------------------------
-- audit log. document_id has no FK here: the module that issues documents adds it, and the
-- ISSUE operation to the CHECK, in its own migration (R10).
-- ---------------------------------------------------------------------------

CREATE TABLE ${metadataSchema}.audit_log (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    user_id         uuid,
    object_name     text NOT NULL,
    record_id       uuid,
    operation       text NOT NULL,
    before_state    jsonb,
    after_state     jsonb,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    document_id     uuid,
    CONSTRAINT audit_log_operation_valid CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE'))
);

CREATE INDEX audit_log_org_time_idx ON ${metadataSchema}.audit_log (organization_id, occurred_at DESC);
CREATE INDEX audit_log_record_idx ON ${metadataSchema}.audit_log (object_name, record_id);
```

- [ ] **Step 5: Write `db/chawpi/core-seed/V1__seed_dev.sql`** (sapgis V2 + the V3 grant, renamed)

```sql
-- dev seed. opt-in: chawpi.seed.dev=true. password "admin".
-- the bcrypt hash is written out instead of calling pgcrypto's crypt(): with several schemas in one
-- database the extension lives in whichever schema created it first, and crypt() would not resolve.
INSERT INTO ${metadataSchema}.organizations (id, name, slug)
VALUES ('00000000-0000-0000-0000-000000000001', 'Demo', 'demo')
ON CONFLICT (slug) DO NOTHING;

INSERT INTO ${metadataSchema}.roles (id, organization_id, name, label)
VALUES ('00000000-0000-0000-0000-000000000010',
        '00000000-0000-0000-0000-000000000001', 'ADMIN', 'Administrator')
ON CONFLICT (organization_id, name) DO NOTHING;

INSERT INTO ${metadataSchema}.users (id, organization_id, email, password_hash, display_name)
VALUES ('00000000-0000-0000-0000-000000000100',
        '00000000-0000-0000-0000-000000000001',
        'admin@chawpi.local',
        '$2y$10$ayidBy/nTHxx5PEkqTJaHeYsLi8EjHm9nNUMLydmcBFbqftPDiILy',
        'Platform Administrator')
ON CONFLICT (organization_id, email) DO NOTHING;

INSERT INTO ${metadataSchema}.user_roles (user_id, role_id)
VALUES ('00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000010')
ON CONFLICT DO NOTHING;

-- object_id NULL = applies to every object in the org
INSERT INTO ${metadataSchema}.permissions (role_id, object_id, action)
SELECT '00000000-0000-0000-0000-000000000010', NULL, action
FROM unnest(ARRAY['READ', 'CREATE', 'UPDATE', 'DELETE', 'MANAGE_METADATA', 'MANAGE_ORGANIZATION']) AS action
ON CONFLICT DO NOTHING;
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `./gradlew :chawpi-core:test`
Expected: PASS: `ChawpiMigrationsTest` (3), `CoreMigrationSqlTest` (4), plus all earlier tests. The real run against PostgreSQL happens in Task 11.

- [ ] **Step 7: Format, check, leave uncommitted**

```bash
./gradlew :chawpi-core:ktlintFormat :chawpi-core:ktlintCheck :chawpi-core:build
git status --short | head
```
Expected: build passes, nothing committed.

---

### Task 9: Auto-configuration, environment defaults, imports file

**Files:**
- Create: `$DST/autoconfigure/ChawpiPlatformAutoConfiguration.kt`, `ChawpiSecurityAutoConfiguration.kt`, `ChawpiMetadataAutoConfiguration.kt`, `ChawpiDataAutoConfiguration.kt`, `ChawpiAdminAutoConfiguration.kt`, `ChawpiEnvironmentPostProcessor.kt`
- Create: `backend/chawpi-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `backend/chawpi-core/src/main/resources/META-INF/spring.factories`
- Test: `$TDST/autoconfigure/ChawpiAutoConfigurationTest.kt`, `$TDST/autoconfigure/ChawpiEnvironmentPostProcessorTest.kt`

**Interfaces:**
- Consumes: every class from Tasks 2–8, with the constructor orders listed in their Interfaces blocks.
- Produces:
  - Auto-config classes, in this order: `ChawpiPlatformAutoConfiguration` → `ChawpiSecurityAutoConfiguration` → `ChawpiMetadataAutoConfiguration` → `ChawpiDataAutoConfiguration` → `ChawpiAdminAutoConfiguration`. A P2 module that replaces a core default declares `@AutoConfiguration(before = [ChawpiDataAutoConfiguration::class])` (for `WorkflowStates`, `RecordStore`) or `before = [ChawpiMetadataAutoConfiguration::class]` for metadata beans.
  - Extension points collected as ordered lists: `FieldTypeHandler`, `SystemColumnContributor`, `RecordQueryContributor`, `RecordChangeListener`, `FieldUsage`, `ObjectRemovalListener`, `ModuleMigration`.
  - Overridable single beans (`@ConditionalOnMissingBean`): every service, repository and controller, plus `WorkflowStates` (default `NoWorkflowStates`), `RecordStore` (default `PhysicalTableRecordStore`), `SecurityWebFilterChain`, `ReactiveJwtDecoder`, `PasswordEncoder`, `CorsConfigurationSource`, `ChawpiSchemas`, `ChawpiMigrations`.
  - Properties: `chawpi.database.*`, `chawpi.security.jwt.*`, `chawpi.web.*`, `chawpi.seed.dev` (default false).
  - Env vars (via the defaults): `CHAWPI_DB_HOST|PORT|NAME|USERNAME|PASSWORD`, `CHAWPI_JWT_SECRET`.

- [ ] **Step 1: Write the failing tests**

`$TDST/autoconfigure/ChawpiAutoConfigurationTest.kt`:
```kotlin
package chawpi.core.autoconfigure

import chawpi.core.data.NoWorkflowStates
import chawpi.core.data.ObjectWorkflowState
import chawpi.core.data.RecordService
import chawpi.core.data.WorkflowStates
import chawpi.core.metadata.CustomField
import chawpi.core.metadata.FieldType
import chawpi.core.metadata.FieldTypeHandler
import chawpi.core.metadata.FieldTypeRegistry
import chawpi.core.platform.ChawpiMigrations
import chawpi.core.platform.ModuleMigration
import chawpi.core.platform.SystemColumn
import chawpi.core.platform.SystemColumnContributor
import chawpi.core.platform.SystemColumns
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.core.userdetails.ReactiveUserDetailsService
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

class ChawpiAutoConfigurationTest {
    private val runner =
        ReactiveWebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    ReactiveUserDetailsServiceAutoConfiguration::class.java,
                    ChawpiPlatformAutoConfiguration::class.java,
                    ChawpiSecurityAutoConfiguration::class.java,
                    ChawpiMetadataAutoConfiguration::class.java,
                    ChawpiDataAutoConfiguration::class.java,
                    ChawpiAdminAutoConfiguration::class.java
                )
            ).withBean(DatabaseClient::class.java, { mock(DatabaseClient::class.java) })
            .withBean(ObjectMapper::class.java, { JsonMapper.builder().build() })
            .withPropertyValues("chawpi.database.migrate=false", "chawpi.security.jwt.secret=0123456789abcdef0123456789abcdef")

    private val shape =
        object : FieldTypeHandler {
            override val type = FieldType("SHAPE")

            override fun columnType(field: CustomField) = "text"

            override fun toDatabase(
                field: CustomField,
                value: Any?
            ) = value

            override fun javaType(field: CustomField) = String::class.java

            override fun fromDatabase(
                field: CustomField,
                value: Any?
            ) = value
        }

    @Test
    fun `core wires on its own, with no module and no in-memory user`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(RecordService::class.java)
            assertThat(context.getBean(WorkflowStates::class.java)).isInstanceOf(NoWorkflowStates::class.java)
            assertThat(context.getBean(FieldTypeRegistry::class.java).types).hasSize(12)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values).containsExactly(ModuleMigration.CORE)
            assertThat(context).doesNotHaveBean(ChawpiMigrations::class.java)
            assertThat(context).doesNotHaveBean(ReactiveUserDetailsService::class.java)
        }
    }

    @Test
    fun `the dev seed is opt in`() {
        runner.withPropertyValues("chawpi.seed.dev=true").run { context ->
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values)
                .containsExactlyInAnyOrder(ModuleMigration.CORE, ModuleMigration.CORE_SEED)
        }
    }

    @Test
    fun `a missing jwt secret fails at boot and names the property`() {
        runner.withPropertyValues("chawpi.security.jwt.secret=").run { context ->
            assertThat(context).hasFailed()
            assertThat(generateSequence(context.startupFailure) { it.cause }.map { it.message.orEmpty() }.joinToString(" | "))
                .contains("chawpi.security.jwt.secret must be at least 32 bytes")
        }
    }

    @Test
    fun `modules plug in through beans, and an app bean replaces the core default`() {
        val states =
            object : WorkflowStates {
                override suspend fun stateOf(
                    organizationId: UUID,
                    objectId: UUID
                ) = ObjectWorkflowState.NONE

                override suspend fun transitionNames(
                    organizationId: UUID,
                    objectId: UUID
                ) = setOf("approve")
            }
        runner
            .withBean(FieldTypeHandler::class.java, { shape })
            .withBean(WorkflowStates::class.java, { states })
            .withBean(SystemColumnContributor::class.java, { SystemColumnContributor { listOf(SystemColumn("workflow_state", "TEXT", "WORKFLOW")) } })
            .withBean("gisMigration", ModuleMigration::class.java, { ModuleMigration("gis", "classpath:db/chawpi/gis", ModuleMigration.MODULE_ORDER) })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(FieldTypeRegistry::class.java).types.last()).isEqualTo(FieldType("SHAPE"))
                assertThat(context.getBean(WorkflowStates::class.java)).isSameAs(states)
                assertThat(context.getBean(SystemColumns::class.java).names).contains("workflow_state")
                assertThat(context.getBeansOfType(ModuleMigration::class.java)).hasSize(2)
            }
    }
}
```
If the context fails only because WebFlux infrastructure is missing (for example no `ServerCodecConfigurer` bean for `@EnableWebFluxSecurity`), add Boot's WebFlux auto-config to `AutoConfigurations.of(...)`. Find its exact name with `unzip -p $(find ~/.gradle -name 'spring-boot-webflux-4.1.1.jar' | head -1) META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Do not weaken the assertions.

`$TDST/autoconfigure/ChawpiEnvironmentPostProcessorTest.kt`:
```kotlin
package chawpi.core.autoconfigure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class ChawpiEnvironmentPostProcessorTest {
    private fun environment(app: Map<String, Any>): StandardEnvironment =
        StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("app", app))
            ChawpiEnvironmentPostProcessor().postProcessEnvironment(this, SpringApplication())
        }

    @Test
    fun `r2dbc follows chawpi database settings`() {
        val env = environment(mapOf("chawpi.database.host" to "db.local", "chawpi.database.port" to "6543", "chawpi.database.name" to "acme"))

        assertThat(env.getProperty("spring.r2dbc.url")).isEqualTo("r2dbc:postgresql://db.local:6543/acme")
        assertThat(env.getProperty("spring.webflux.problemdetails.enabled")).isEqualTo("true")
    }

    @Test
    fun `whatever the app sets wins over the defaults`() {
        val env = environment(mapOf("spring.r2dbc.url" to "r2dbc:postgresql://elsewhere/x"))

        assertThat(env.getProperty("spring.r2dbc.url")).isEqualTo("r2dbc:postgresql://elsewhere/x")
    }

    @Test
    fun `no jwt secret unless the app gives one`() {
        assertThat(environment(emptyMap()).getProperty("chawpi.security.jwt.secret")).isEmpty()
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :chawpi-core:test --tests 'chawpi.core.autoconfigure.*'`
Expected: compilation FAILS with `Unresolved reference 'ChawpiPlatformAutoConfiguration'`.

- [ ] **Step 3: Write the auto-configurations**

`$DST/autoconfigure/ChawpiPlatformAutoConfiguration.kt`:
```kotlin
package chawpi.core.autoconfigure

import chawpi.core.common.GlobalExceptionHandler
import chawpi.core.common.HealthController
import chawpi.core.platform.ChawpiDatabaseProperties
import chawpi.core.platform.ChawpiMigrations
import chawpi.core.platform.ChawpiSchemas
import chawpi.core.platform.ChawpiWebProperties
import chawpi.core.platform.JwtProperties
import chawpi.core.platform.ModuleMigration
import chawpi.core.platform.SystemColumnContributor
import chawpi.core.platform.SystemColumns
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

// properties, schema names, system columns, migrations, errors, health. no scanning: every bean here.
@AutoConfiguration
@EnableConfigurationProperties(ChawpiDatabaseProperties::class, JwtProperties::class, ChawpiWebProperties::class)
class ChawpiPlatformAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun chawpiSchemas(database: ChawpiDatabaseProperties): ChawpiSchemas = ChawpiSchemas.of(database)

    @Bean
    @ConditionalOnMissingBean
    fun systemColumns(contributors: ObjectProvider<SystemColumnContributor>): SystemColumns = SystemColumns(contributors.orderedStream().toList())

    @Bean
    fun chawpiCoreMigration(): ModuleMigration = ModuleMigration.CORE

    @Bean
    @ConditionalOnProperty(prefix = "chawpi.seed", name = ["dev"], havingValue = "true")
    fun chawpiCoreSeedMigration(): ModuleMigration = ModuleMigration.CORE_SEED

    // runs at startup, before traffic. every module migration bean is in the list.
    @Bean(initMethod = "migrate")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chawpi.database", name = ["migrate"], havingValue = "true", matchIfMissing = true)
    fun chawpiMigrations(
        database: ChawpiDatabaseProperties,
        schemas: ChawpiSchemas,
        migrations: ObjectProvider<ModuleMigration>
    ): ChawpiMigrations = ChawpiMigrations(database, schemas, migrations.orderedStream().toList())

    @Bean
    @ConditionalOnMissingBean
    fun globalExceptionHandler(web: ChawpiWebProperties): GlobalExceptionHandler = GlobalExceptionHandler(web.problemBaseUri)

    @Bean
    @ConditionalOnMissingBean
    fun healthController(
        @Value("\${spring.application.name:chawpi}") applicationName: String
    ): HealthController = HealthController(applicationName)
}
```

`$DST/autoconfigure/ChawpiSecurityAutoConfiguration.kt` (was `identity/SecurityConfig.kt`):
```kotlin
package chawpi.core.autoconfigure

import chawpi.core.identity.AccessPolicy
import chawpi.core.identity.AuthController
import chawpi.core.identity.AuthService
import chawpi.core.identity.CurrentUser
import chawpi.core.identity.JwtService
import chawpi.core.identity.RoleDirectory
import chawpi.core.identity.RoleQueries
import chawpi.core.identity.UserRepository
import chawpi.core.platform.ChawpiSchemas
import chawpi.core.platform.ChawpiWebProperties
import chawpi.core.platform.JwtProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpMethod
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

// own HS256 jwt (ADR-010) and the identity beans. runs before boot's security defaults, so they
// see our decoder and back off instead of generating an in-memory user.
@AutoConfiguration(
    after = [ChawpiPlatformAutoConfiguration::class],
    beforeName = [
        "org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.ReactiveOAuth2ResourceServerAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.reactive.ReactiveOAuth2ResourceServerWebSecurityAutoConfiguration"
    ]
)
@EnableWebFluxSecurity
class ChawpiSecurityAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun chawpiJwtSecretKey(properties: JwtProperties): SecretKey {
        val bytes = properties.secret.toByteArray(Charsets.UTF_8)
        // HS256 needs >= 256 bits. fail at boot, not at first login.
        require(bytes.size >= 32) { "chawpi.security.jwt.secret must be at least 32 bytes (env CHAWPI_JWT_SECRET)" }
        return SecretKeySpec(bytes, "HmacSHA256")
    }

    @Bean
    @ConditionalOnMissingBean
    fun jwtDecoder(secretKey: SecretKey): ReactiveJwtDecoder = NimbusReactiveJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build()

    @Bean
    @ConditionalOnMissingBean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    @ConditionalOnMissingBean
    fun securityFilterChain(
        http: ServerHttpSecurity,
        jwtDecoder: ReactiveJwtDecoder
    ): SecurityWebFilterChain =
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .cors { }
            .authorizeExchange {
                it.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                it.pathMatchers("/api/auth/login", "/api/health", "/actuator/health/**").permitAll()
                it.anyExchange().authenticated()
            }.oauth2ResourceServer { server -> server.jwt { it.jwtDecoder(jwtDecoder) } }
            .build()

    @Bean
    @ConditionalOnMissingBean
    fun corsConfigurationSource(web: ChawpiWebProperties): CorsConfigurationSource {
        val config =
            CorsConfiguration().apply {
                allowedOriginPatterns = web.corsAllowedOriginPatterns
                allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                allowedHeaders = listOf("*")
                allowCredentials = true
            }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }

    @Bean
    @ConditionalOnMissingBean
    fun roleQueries(
        db: DatabaseClient,
        schemas: ChawpiSchemas
    ): RoleQueries = RoleQueries(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun roleDirectory(
        db: DatabaseClient,
        schemas: ChawpiSchemas
    ): RoleDirectory = RoleDirectory(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun currentUser(roleQueries: RoleQueries): CurrentUser = CurrentUser(roleQueries)

    @Bean
    @ConditionalOnMissingBean
    fun accessPolicy(
        db: DatabaseClient,
        schemas: ChawpiSchemas
    ): AccessPolicy = AccessPolicy(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun userRepository(
        db: DatabaseClient,
        schemas: ChawpiSchemas
    ): UserRepository = UserRepository(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun jwtService(
        properties: JwtProperties,
        secretKey: SecretKey
    ): JwtService = JwtService(properties, secretKey)

    @Bean
    @ConditionalOnMissingBean
    fun authService(
        users: UserRepository,
        roleQueries: RoleQueries,
        passwordEncoder: PasswordEncoder,
        jwtService: JwtService
    ): AuthService = AuthService(users, roleQueries, passwordEncoder, jwtService)

    @Bean
    @ConditionalOnMissingBean
    fun authController(
        authService: AuthService,
        currentUser: CurrentUser
    ): AuthController = AuthController(authService, currentUser)
}
```

`$DST/autoconfigure/ChawpiMetadataAutoConfiguration.kt`:
```kotlin
package chawpi.core.autoconfigure

import chawpi.core.identity.AccessPolicy
import chawpi.core.identity.CurrentUser
import chawpi.core.metadata.CallerPermissionsController
import chawpi.core.metadata.CallerPermissionsService
import chawpi.core.metadata.CustomFieldRepository
import chawpi.core.metadata.CustomObjectRepository
import chawpi.core.metadata.FieldTypeHandler
import chawpi.core.metadata.FieldTypeRegistry
import chawpi.core.metadata.FieldUsage
import chawpi.core.metadata.MetadataMapper
import chawpi.core.metadata.MetadataService
import chawpi.core.metadata.ObjectController
import chawpi.core.metadata.ObjectMetadataController
import chawpi.core.metadata.ObjectRemovalListener
import chawpi.core.metadata.ObjectSchemaManager
import chawpi.core.metadata.RelationshipController
import chawpi.core.metadata.RelationshipMapper
import chawpi.core.metadata.RelationshipRepository
import chawpi.core.metadata.RelationshipService
import chawpi.core.metadata.SystemFieldController
import chawpi.core.platform.ChawpiSchemas
import chawpi.core.platform.SystemColumns
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.ObjectMapper

// custom objects, fields, relationships and the field-type registry modules extend
@AutoConfiguration(after = [ChawpiSecurityAutoConfiguration::class])
class ChawpiMetadataAutoConfiguration {
    // core's twelve types first, then every FieldTypeHandler bean in @Order
    @Bean
    @ConditionalOnMissingBean
    fun fieldTypeRegistry(handlers: ObjectProvider<FieldTypeHandler>): FieldTypeRegistry = FieldTypeRegistry(handlers.orderedStream().toList())

    @Bean
    @ConditionalOnMissingBean
    fun customObjectRepository(
        db: DatabaseClient,
        schemas: ChawpiSchemas
    ): CustomObjectRepository = CustomObjectRepository(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun customFieldRepository(
        db: DatabaseClient,
        objectMapper: ObjectMapper,
        schemas: ChawpiSchemas,
        types: FieldTypeRegistry
    ): CustomFieldRepository = CustomFieldRepository(db, objectMapper, schemas, types)

    @Bean
    @ConditionalOnMissingBean
    fun relationshipRepository(
        db: DatabaseClient,
        schemas: ChawpiSchemas
    ): RelationshipRepository = RelationshipRepository(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun objectSchemaManager(
        db: DatabaseClient,
        schemas: ChawpiSchemas,
        types: FieldTypeRegistry
    ): ObjectSchemaManager = ObjectSchemaManager(db, schemas, types)

    @Bean
    @ConditionalOnMissingBean
    fun metadataService(
        objects: CustomObjectRepository,
        fields: CustomFieldRepository,
        relationships: RelationshipRepository,
        schema: ObjectSchemaManager,
        currentUser: CurrentUser,
        access: AccessPolicy,
        types: FieldTypeRegistry,
        systemColumns: SystemColumns,
        usages: ObjectProvider<FieldUsage>,
        removals: ObjectProvider<ObjectRemovalListener>
    ): MetadataService =
        MetadataService(
            objects,
            fields,
            relationships,
            schema,
            currentUser,
            access,
            types,
            systemColumns,
            usages.orderedStream().toList(),
            removals.orderedStream().toList()
        )

    @Bean
    @ConditionalOnMissingBean
    fun relationshipService(
        relationships: RelationshipRepository,
        objects: CustomObjectRepository,
        fields: CustomFieldRepository,
        metadata: MetadataService,
        schema: ObjectSchemaManager,
        currentUser: CurrentUser
    ): RelationshipService = RelationshipService(relationships, objects, fields, metadata, schema, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun metadataMapper(
        objects: CustomObjectRepository,
        types: FieldTypeRegistry
    ): MetadataMapper = MetadataMapper(objects, types)

    @Bean
    @ConditionalOnMissingBean
    fun relationshipMapper(
        objects: CustomObjectRepository,
        fields: CustomFieldRepository
    ): RelationshipMapper = RelationshipMapper(objects, fields)

    @Bean
    @ConditionalOnMissingBean
    fun callerPermissionsService(
        objects: CustomObjectRepository,
        currentUser: CurrentUser
    ): CallerPermissionsService = CallerPermissionsService(objects, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun objectController(
        metadata: MetadataService,
        mapper: MetadataMapper,
        currentUser: CurrentUser
    ): ObjectController = ObjectController(metadata, mapper, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun objectMetadataController(
        metadata: MetadataService,
        mapper: MetadataMapper,
        currentUser: CurrentUser
    ): ObjectMetadataController = ObjectMetadataController(metadata, mapper, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun systemFieldController(systemColumns: SystemColumns): SystemFieldController = SystemFieldController(systemColumns)

    @Bean
    @ConditionalOnMissingBean
    fun relationshipController(
        relationships: RelationshipService,
        mapper: RelationshipMapper,
        currentUser: CurrentUser
    ): RelationshipController = RelationshipController(relationships, mapper, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun callerPermissionsController(permissions: CallerPermissionsService): CallerPermissionsController = CallerPermissionsController(permissions)
}
```

`$DST/autoconfigure/ChawpiDataAutoConfiguration.kt`:
```kotlin
package chawpi.core.autoconfigure

import chawpi.core.audit.AuditController
import chawpi.core.audit.AuditQueryService
import chawpi.core.audit.AuditService
import chawpi.core.data.NoWorkflowStates
import chawpi.core.data.PhysicalTableRecordStore
import chawpi.core.data.RecordChangeListener
import chawpi.core.data.RecordController
import chawpi.core.data.RecordQueryContributor
import chawpi.core.data.RecordQueryParser
import chawpi.core.data.RecordService
import chawpi.core.data.RecordStore
import chawpi.core.data.RelatedRecordController
import chawpi.core.data.RelatedRecordService
import chawpi.core.data.WorkflowStates
import chawpi.core.identity.AccessPolicy
import chawpi.core.identity.CurrentUser
import chawpi.core.metadata.CustomFieldRepository
import chawpi.core.metadata.CustomObjectRepository
import chawpi.core.metadata.FieldTypeRegistry
import chawpi.core.metadata.MetadataService
import chawpi.core.metadata.RelationshipMapper
import chawpi.core.metadata.RelationshipRepository
import chawpi.core.metadata.RelationshipService
import chawpi.core.platform.ChawpiSchemas
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.ObjectMapper

// records, audit and related records. a module that gives records a state, or stores them another
// way, declares its bean in an auto-config that runs before this one.
@AutoConfiguration(after = [ChawpiMetadataAutoConfiguration::class])
class ChawpiDataAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun auditService(
        db: DatabaseClient,
        objectMapper: ObjectMapper,
        schemas: ChawpiSchemas
    ): AuditService = AuditService(db, objectMapper, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun auditQueryService(
        db: DatabaseClient,
        objectMapper: ObjectMapper,
        currentUser: CurrentUser,
        metadata: MetadataService,
        access: AccessPolicy,
        schemas: ChawpiSchemas
    ): AuditQueryService = AuditQueryService(db, objectMapper, currentUser, metadata, access, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun auditController(audit: AuditQueryService): AuditController = AuditController(audit)

    // null object: no module installed, no object has a state (R9)
    @Bean
    @ConditionalOnMissingBean
    fun workflowStates(): WorkflowStates = NoWorkflowStates()

    @Bean
    @ConditionalOnMissingBean
    fun recordStore(
        db: DatabaseClient,
        schemas: ChawpiSchemas,
        types: FieldTypeRegistry
    ): RecordStore = PhysicalTableRecordStore(db, schemas, types)

    @Bean
    @ConditionalOnMissingBean
    fun recordQueryParser(contributors: ObjectProvider<RecordQueryContributor>): RecordQueryParser = RecordQueryParser(contributors.orderedStream().toList())

    @Bean
    @ConditionalOnMissingBean
    fun recordService(
        metadata: MetadataService,
        store: RecordStore,
        audit: AuditService,
        currentUser: CurrentUser,
        access: AccessPolicy,
        workflows: WorkflowStates,
        types: FieldTypeRegistry,
        changes: ObjectProvider<RecordChangeListener>
    ): RecordService = RecordService(metadata, store, audit, currentUser, access, workflows, types, changes.orderedStream().toList())

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
        schemas: ChawpiSchemas
    ): RelatedRecordService =
        RelatedRecordService(relationships, relationshipService, objects, fields, metadata, store, currentUser, access, db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun recordController(
        records: RecordService,
        queries: RecordQueryParser
    ): RecordController = RecordController(records, queries)

    @Bean
    @ConditionalOnMissingBean
    fun relatedRecordController(
        relationships: RelationshipService,
        related: RelatedRecordService,
        mapper: RelationshipMapper,
        queries: RecordQueryParser
    ): RelatedRecordController = RelatedRecordController(relationships, related, mapper, queries)
}
```

`$DST/autoconfigure/ChawpiAdminAutoConfiguration.kt`:
```kotlin
package chawpi.core.autoconfigure

import chawpi.core.admin.AdminService
import chawpi.core.admin.RoleAdminController
import chawpi.core.admin.UserAdminController
import chawpi.core.identity.CurrentUser
import chawpi.core.metadata.CustomObjectRepository
import chawpi.core.metadata.MetadataService
import chawpi.core.metadata.ObjectSchemaManager
import chawpi.core.organization.OrganizationController
import chawpi.core.organization.OrganizationRepository
import chawpi.core.organization.OrganizationService
import chawpi.core.platform.ChawpiSchemas
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.crypto.password.PasswordEncoder

// users, roles, permissions and the tenant itself
@AutoConfiguration(after = [ChawpiDataAutoConfiguration::class])
class ChawpiAdminAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun adminService(
        db: DatabaseClient,
        metadata: MetadataService,
        passwordEncoder: PasswordEncoder,
        currentUser: CurrentUser,
        schemas: ChawpiSchemas
    ): AdminService = AdminService(db, metadata, passwordEncoder, currentUser, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun userAdminController(admin: AdminService): UserAdminController = UserAdminController(admin)

    @Bean
    @ConditionalOnMissingBean
    fun roleAdminController(admin: AdminService): RoleAdminController = RoleAdminController(admin)

    @Bean
    @ConditionalOnMissingBean
    fun organizationRepository(
        db: DatabaseClient,
        schemas: ChawpiSchemas
    ): OrganizationRepository = OrganizationRepository(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun organizationService(
        organizations: OrganizationRepository,
        objects: CustomObjectRepository,
        schema: ObjectSchemaManager,
        passwordEncoder: PasswordEncoder,
        currentUser: CurrentUser,
        db: DatabaseClient,
        schemas: ChawpiSchemas
    ): OrganizationService = OrganizationService(organizations, objects, schema, passwordEncoder, currentUser, db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun organizationController(organizations: OrganizationService): OrganizationController = OrganizationController(organizations)
}
```

`$DST/autoconfigure/ChawpiEnvironmentPostProcessor.kt`:
```kotlin
package chawpi.core.autoconfigure

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

// what an app gets without writing any yaml. added last: anything the app sets wins.
// no usable jwt secret on purpose (R15).
class ChawpiEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication
    ) {
        environment.propertySources.addLast(MapPropertySource(SOURCE, DEFAULTS))
    }

    companion object {
        const val SOURCE = "chawpiDefaults"

        val DEFAULTS: Map<String, Any> =
            mapOf(
                "chawpi.database.host" to "\${CHAWPI_DB_HOST:localhost}",
                "chawpi.database.port" to "\${CHAWPI_DB_PORT:5432}",
                "chawpi.database.name" to "\${CHAWPI_DB_NAME:chawpi}",
                "chawpi.database.username" to "\${CHAWPI_DB_USERNAME:chawpi}",
                "chawpi.database.password" to "\${CHAWPI_DB_PASSWORD:chawpi}",
                "chawpi.security.jwt.secret" to "\${CHAWPI_JWT_SECRET:}",
                "spring.r2dbc.url" to "r2dbc:postgresql://\${chawpi.database.host}:\${chawpi.database.port}/\${chawpi.database.name}",
                "spring.r2dbc.username" to "\${chawpi.database.username}",
                "spring.r2dbc.password" to "\${chawpi.database.password}",
                "spring.r2dbc.pool.enabled" to "true",
                "spring.r2dbc.pool.initial-size" to "5",
                "spring.r2dbc.pool.max-size" to "20",
                "spring.webflux.problemdetails.enabled" to "true"
            )
    }
}
```

- [ ] **Step 4: Register them**

`backend/chawpi-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
chawpi.core.autoconfigure.ChawpiPlatformAutoConfiguration
chawpi.core.autoconfigure.ChawpiSecurityAutoConfiguration
chawpi.core.autoconfigure.ChawpiMetadataAutoConfiguration
chawpi.core.autoconfigure.ChawpiDataAutoConfiguration
chawpi.core.autoconfigure.ChawpiAdminAutoConfiguration
```

`backend/chawpi-core/src/main/resources/META-INF/spring.factories`:
```
org.springframework.boot.EnvironmentPostProcessor=\
  chawpi.core.autoconfigure.ChawpiEnvironmentPostProcessor
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `./gradlew :chawpi-core:test`
Expected: PASS: `ChawpiAutoConfigurationTest` (4), `ChawpiEnvironmentPostProcessorTest` (3), plus all earlier tests.

- [ ] **Step 6: Format, check, leave uncommitted**

```bash
./gradlew :chawpi-core:ktlintFormat :chawpi-core:ktlintCheck :chawpi-core:build
git status --short | head
```
Expected: build passes, nothing committed.

---

### Task 10: `chawpi-test` (published integration-test fixtures)

**Files:**
- Create: `backend/chawpi-test/build.gradle.kts`
- Create: `backend/chawpi-test/src/main/kotlin/chawpi/test/ChawpiTestDatabase.kt`, `ChawpiIntegrationTest.kt`
- Test: `backend/chawpi-test/src/test/kotlin/chawpi/test/ChawpiTestDatabaseTest.kt`
- Modify: `backend/chawpi-core/build.gradle.kts` (add `testImplementation(project(":chawpi-test"))`)
- Create: `backend/chawpi-core/src/test/kotlin/chawpi/core/ChawpiCoreTestApplication.kt`
- Delete: `backend/chawpi-core/src/test/kotlin/chawpi/core/.gitkeep`, `backend/chawpi-core/src/main/kotlin/chawpi/core/.gitkeep` if still there

**Interfaces:**
- Consumes: nothing from `chawpi-core` at compile time. It talks to the app over HTTP (`POST /api/auth/login`) and sets `chawpi.*` properties.
- Produces:
  - `abstract class ChawpiIntegrationTest`: `@Tag("integration")`, `@SpringBootTest(RANDOM_PORT)`, `@AutoConfigureWebTestClient(timeout = "30s")`, `@TestPropertySource(chawpi.seed.dev=true, chawpi.security.jwt.secret=<test secret>)`; members `client: WebTestClient`, `uniqueName(prefix = "obj")`, `bearer()`, `bearer(email, password)`; constants `ADMIN_EMAIL = "admin@chawpi.local"`, `ADMIN_PASSWORD = "admin"`, `TEST_JWT_SECRET`
  - `object ChawpiTestDatabase`: `DEFAULT_IMAGE = "postgres:18"`, `POSTGIS_IMAGE = "postgis/postgis:18-3.6"`, `image` (from `-Dchawpi.test.db.image` or `CHAWPI_TEST_DB_IMAGE`), `properties(): Map<String, String>` (`chawpi.database.host|port|name|username|password`), `requireTestDatabaseName(name)`, `quoteIdentifier(name)`
  - `data class LoginBody(val token: String)`
  - Test-app pattern: a `@SpringBootConfiguration @EnableAutoConfiguration` class (no component scan) at the root package of the module's tests, e.g. `chawpi.core.ChawpiCoreTestApplication`

- [ ] **Step 1: Write the failing test** `backend/chawpi-test/src/test/kotlin/chawpi/test/ChawpiTestDatabaseTest.kt`

```kotlin
package chawpi.test

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ChawpiTestDatabaseTest {
    @Test
    fun `refuses to wipe a database whose name does not end in _test`() {
        assertThatThrownBy { ChawpiTestDatabase.requireTestDatabaseName("chawpi") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("refusing to wipe 'chawpi'")
        assertThat(ChawpiTestDatabase.requireTestDatabaseName("chawpi_test")).isEqualTo("chawpi_test")
    }

    @Test
    fun `quotes identifiers it drops, doubling embedded quotes`() {
        assertThat(ChawpiTestDatabase.quoteIdentifier("app_data")).isEqualTo("\"app_data\"")
        assertThat(ChawpiTestDatabase.quoteIdentifier("we\"ird")).isEqualTo("\"we\"\"ird\"")
    }

    @Test
    fun `core tests run on plain postgres unless told otherwise`() {
        if (System.getProperty("chawpi.test.db.image") == null && System.getenv("CHAWPI_TEST_DB_IMAGE") == null) {
            assertThat(ChawpiTestDatabase.image).isEqualTo(ChawpiTestDatabase.DEFAULT_IMAGE)
        }
    }
}
```

- [ ] **Step 2: Write `backend/chawpi-test/build.gradle.kts` and run the test to see it fail**

```kotlin
plugins {
    id("chawpi.spring-module")
    id("chawpi.publishing")
}

description = "Chawpi test fixtures: an integration-test base on a real PostgreSQL"

dependencies {
    api(libs.spring.boot.starter.test)
    api(libs.spring.boot.starter.webflux.test)
    api(libs.spring.boot.testcontainers)
    api(libs.testcontainers.junit)
    api(libs.testcontainers.postgresql)
    // the external-database wipe talks plain jdbc
    implementation(libs.postgresql.jdbc)
}
```
Run: `./gradlew :chawpi-test:test`
Expected: compilation FAILS with `Unresolved reference 'ChawpiTestDatabase'`.

- [ ] **Step 3: Write `ChawpiTestDatabase.kt`** (sapgis `IntegrationTest` companion, generalised)

```kotlin
package chawpi.test

import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

// the one database every integration test in a jvm shares. testcontainers by default. when
// CHAWPI_TEST_DB_HOST is set, an already running database instead: a remote docker daemon publishes
// container ports on its own host, out of reach here.
object ChawpiTestDatabase {
    const val DEFAULT_IMAGE = "postgres:18"
    const val POSTGIS_IMAGE = "postgis/postgis:18-3.6"

    // core runs on plain postgres. a module that needs postgis sets the image for its test task:
    // tasks.integrationTest { systemProperty("chawpi.test.db.image", "postgis/postgis:18-3.6") }
    val image: String = System.getProperty("chawpi.test.db.image") ?: System.getenv("CHAWPI_TEST_DB_IMAGE") ?: DEFAULT_IMAGE

    private val externalHost: String? = System.getenv("CHAWPI_TEST_DB_HOST")

    private val container: PostgreSQLContainer? by lazy {
        if (externalHost != null) {
            null
        } else {
            PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("chawpi")
                .withUsername("chawpi")
                .withPassword("chawpi")
                .also { it.start() }
        }
    }

    // an external database outlives the suite: wipe it once per jvm, before the first context
    private val ready: Boolean by lazy {
        if (externalHost != null) wipeExternalDatabase()
        true
    }

    fun properties(): Map<String, String> {
        check(ready)
        val running = container
        return if (running == null) {
            mapOf(
                "chawpi.database.host" to externalHost!!,
                "chawpi.database.port" to (System.getenv("CHAWPI_TEST_DB_PORT") ?: "5432"),
                "chawpi.database.name" to (System.getenv("CHAWPI_TEST_DB_NAME") ?: "chawpi_test"),
                "chawpi.database.username" to (System.getenv("CHAWPI_TEST_DB_USERNAME") ?: "chawpi"),
                "chawpi.database.password" to (System.getenv("CHAWPI_TEST_DB_PASSWORD") ?: "chawpi")
            )
        } else {
            mapOf(
                "chawpi.database.host" to running.host,
                "chawpi.database.port" to running.firstMappedPort.toString(),
                "chawpi.database.name" to running.databaseName,
                "chawpi.database.username" to running.username,
                "chawpi.database.password" to running.password
            )
        }
    }

    // the one thing that must never be wrong here
    fun requireTestDatabaseName(name: String): String {
        require(name.endsWith("_test")) { "refusing to wipe '$name': a test database's name has to end in _test" }
        return name
    }

    fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    // one DROP per statement, never one big transaction: postgres takes a lock per table and dies with
    // "out of shared memory" long before a thousand of them. autocommit gives each drop its own.
    // every user schema goes (a test may use its own schema names); tables an extension owns stay,
    // and so does any schema an extension lives in. flyway history goes too, so migrations replay.
    // ONE SUITE AT A TIME against an external database: the advisory lock makes a second run wait
    // instead of deadlock, but it still starts on a wiped database.
    private fun wipeExternalDatabase() {
        val name = requireTestDatabaseName(System.getenv("CHAWPI_TEST_DB_NAME") ?: "chawpi_test")
        val port = System.getenv("CHAWPI_TEST_DB_PORT") ?: "5432"
        val user = System.getenv("CHAWPI_TEST_DB_USERNAME") ?: "chawpi"
        val password = System.getenv("CHAWPI_TEST_DB_PASSWORD") ?: "chawpi"
        DriverManager.getConnection("jdbc:postgresql://$externalHost:$port/$name", user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SELECT pg_advisory_lock(hashtext('chawpi-test-wipe'))")
                val userSchemas = "n.nspname NOT LIKE 'pg\\_%' AND n.nspname NOT IN ('information_schema', 'public')"
                val tables = mutableListOf<Pair<String, String>>()
                statement
                    .executeQuery(
                        """
                        SELECT n.nspname, c.relname
                        FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE $userSchemas
                          AND c.relkind IN ('r', 'p')
                          AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = c.oid AND d.deptype = 'e')
                        """.trimIndent()
                    ).use { rows -> while (rows.next()) tables.add(rows.getString(1) to rows.getString(2)) }
                tables.forEach { (schema, table) ->
                    statement.execute("DROP TABLE IF EXISTS ${quoteIdentifier(schema)}.${quoteIdentifier(table)} CASCADE")
                }
                val schemas = mutableListOf<String>()
                statement
                    .executeQuery(
                        "SELECT n.nspname FROM pg_namespace n WHERE $userSchemas " +
                            "AND NOT EXISTS (SELECT 1 FROM pg_extension e WHERE e.extnamespace = n.oid)"
                    ).use { rows -> while (rows.next()) schemas.add(rows.getString(1)) }
                schemas.forEach { statement.execute("DROP SCHEMA IF EXISTS ${quoteIdentifier(it)} CASCADE") }
                statement.execute("SELECT pg_advisory_unlock(hashtext('chawpi-test-wipe'))")
            }
        }
    }
}
```

- [ ] **Step 4: Write `ChawpiIntegrationTest.kt`**

```kotlin
package chawpi.test

import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

data class LoginBody(
    val token: String
)

// base for api tests against a real postgres. the app under test is the @SpringBootConfiguration
// found above the test's package: give your tests one with @EnableAutoConfiguration and no component
// scan, so the app boots the way a real one gets chawpi. a subclass may override any property with
// its own @TestPropertySource (say, other schema names).
// do not add @ActiveProfiles("test"): a profile called exactly "test" can switch beans off in some
// libraries (embabel's agents, for one) without a word.
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@TestPropertySource(properties = ["chawpi.seed.dev=true", "chawpi.security.jwt.secret=${ChawpiIntegrationTest.TEST_JWT_SECRET}"])
abstract class ChawpiIntegrationTest {
    @Autowired
    protected lateinit var client: WebTestClient

    // the database is shared, so every test names its own object
    protected fun uniqueName(prefix: String = "obj"): String =
        prefix +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)

    protected fun bearer(): String = bearer(ADMIN_EMAIL, ADMIN_PASSWORD)

    // tests that exercise permissions need a token that is not the seeded administrator's
    protected fun bearer(
        email: String,
        password: String
    ): String {
        val body =
            client
                .post()
                .uri("/api/auth/login")
                .bodyValue(mapOf("email" to email, "password" to password))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(LoginBody::class.java)
                .returnResult()
                .responseBody!!
        return "Bearer ${body.token}"
    }

    companion object {
        const val ADMIN_EMAIL = "admin@chawpi.local"
        const val ADMIN_PASSWORD = "admin"
        const val TEST_JWT_SECRET = "chawpi-integration-test-secret-0123456789"

        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            ChawpiTestDatabase.properties().forEach { (key, value) -> registry.add(key) { value } }
        }
    }
}
```

- [ ] **Step 5: Run the chawpi-test unit test to see it pass**

Run: `./gradlew :chawpi-test:test`
Expected: PASS (3 tests). No Docker is needed: nothing touches `properties()`.

- [ ] **Step 6: Wire it into core and add the core test app**

In `backend/chawpi-core/build.gradle.kts` add inside `dependencies { }`:
```kotlin
    testImplementation(project(":chawpi-test"))
```

`backend/chawpi-core/src/test/kotlin/chawpi/core/ChawpiCoreTestApplication.kt`:
```kotlin
package chawpi.core

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// the app the api tests boot: auto-configuration and nothing else, the way a real app gets chawpi.
// no component scan, so the library's classes on the test classpath are never picked up twice.
@SpringBootConfiguration
@EnableAutoConfiguration
class ChawpiCoreTestApplication
```

```bash
rm -f backend/chawpi-core/src/test/kotlin/chawpi/core/.gitkeep backend/chawpi-core/src/main/kotlin/chawpi/core/.gitkeep
./gradlew :chawpi-core:test :chawpi-test:test
```
Expected: PASS. Unit tests do not boot the test app.

- [ ] **Step 7: Format, check the BOM, leave uncommitted**

```bash
./gradlew :chawpi-core:ktlintFormat :chawpi-core:ktlintCheck :chawpi-test:ktlintFormat :chawpi-test:ktlintCheck build
./gradlew :chawpi-bom:generatePomFileForMavenPublication && grep -c "<artifactId>chawpi-test</artifactId>" backend/chawpi-bom/build/publications/maven/pom-default.xml
git status --short | head
```
Expected: build passes. The BOM lists `chawpi-test` (count `1`). Nothing committed.

---

### Task 11: API integration tests (ported core ITs + SPI proof + custom schemas) **(needs Docker, or `CHAWPI_TEST_DB_*`)**

Core ITs run on plain `postgres:18`, which proves core needs no PostGIS. Tests about optional modules are removed here and come back in P3 against the full app:
- MetadataApiTest: 3 geometry tests.
- ObjectCrudApiTest: 1 automation test.
- RecordApiTest: 8 geometry/bbox tests.
- PermissionEnforcementTest: 1 locked-geometry test.

The same behaviours are proven module-agnostically by `MeasureFieldTypeApiTest`. It uses a made-up field type that exercises every hook gis needs.

**Files:**
- Create (ported): `$TDST/api/{MetadataApiTest,ObjectCrudApiTest,FieldApiTest,RelationshipApiTest,RecordApiTest,AdminApiTest,OrganizationApiTest,AuditApiTest,PermissionEnforcementTest}.kt`
- Create (new): `$TDST/api/CoreOnlyApiTest.kt`, `$TDST/api/MeasureFieldTypeApiTest.kt`, `$TDST/api/CustomSchemaApiTest.kt`
- Create (fixtures): `$TDST/fixtures/MeasureFieldType.kt`, `backend/chawpi-core/src/test/resources/db/chawpi/measure-test/V1__measure.sql`

**Interfaces:**
- Consumes: `ChawpiIntegrationTest` (`client`, `uniqueName`, `bearer()`, `bearer(email, password)`), `ChawpiCoreTestApplication` (Task 10); every core route (Tasks 2–9); `FieldTypeHandler`, `RecordQueryContributor`, `RecordCriterion`, `ModuleMigration` (Tasks 4, 6, 8).
- Produces: `chawpi.core.fixtures.MEASURE`, `MeasureFieldType`, `MinMeasureQuery`, `MeasureTestConfiguration`. These are test-only; P2's gis mirrors them.

- [ ] **Step 1: Copy and rename the nine ITs**

```bash
mkdir -p $TDST/api
for t in MetadataApiTest ObjectCrudApiTest FieldApiTest RelationshipApiTest RecordApiTest AdminApiTest OrganizationApiTest AuditApiTest PermissionEnforcementTest; do cp $TSRC/api/$t.kt $TDST/api/; done
sed -i '' -e 's/^package com\.sapgis\.api$/package chawpi.core.api/' -e 's/com\.sapgis\./chawpi.core./g' \
  -e 's/: IntegrationTest()/: ChawpiIntegrationTest()/' -e 's/@sapgis\.local/@chawpi.local/g' $TDST/api/*.kt
sed -i '' -E -e 's/([^a-zA-Z_.])sapgis\.([a-z_]+)/\1chawpi.\2/g' $TDST/api/*.kt
sed -i '' -e '/^import chawpi\.core\.data\.DATA_SCHEMA$/d' -e "s/'\$DATA_SCHEMA'/'app_data'/g" $TDST/api/MetadataApiTest.kt
perl -0pi -e 's/^package chawpi\.core\.api\n/package chawpi.core.api\n\nimport chawpi.test.ChawpiIntegrationTest\n/m' $TDST/api/*.kt
grep -rn -i "sapgis\|DATA_SCHEMA" $TDST/api || echo "clean"
```
Expected: `clean`.

- [ ] **Step 2: Drop the tests that belong to optional modules**

Each test starts at `    @Test` and ends at the first line that is exactly `    }`. This script deletes the named ones, plus the comment lines directly above them:
```bash
python3 - "$TDST/api" <<'PY'
import re, sys, os
root = sys.argv[1]
drop = {
    "MetadataApiTest.kt": ["an object can carry more than one geometry", "the object reports its first geometry as the object geometry", "a geometry field cannot be unique"],
    "ObjectCrudApiTest.kt": ["a field an automation depends on cannot be deleted, and says which rule"],
    "RecordApiTest.kt": ["stores a polygon in the declared CRS and returns it in WGS84", "rejects a geometry of the wrong type",
                         "serves records as a GeoJSON FeatureCollection", "filters features by bounding box",
                         "a record carries every geometry the object declares, each on its own", "a geometry sent as null is cleared",
                         "refuses a geometry the object does not declare", "a bbox filters the geometry it names"],
    "PermissionEnforcementTest.kt": ["a locked geometry can be neither written nor read back"],
}
for name, tests in drop.items():
    path = os.path.join(root, name)
    lines = open(path).read().split("\n")
    out, i, left = [], 0, set(tests)
    while i < len(lines):
        m = re.match(r"    fun `(.+)`\(", lines[i])
        if m and m.group(1) in left:
            while out and (out[-1].strip().startswith("@") or out[-1].strip().startswith("//")):
                out.pop()
            while lines[i] != "    }":
                i += 1
            i += 1
            if i < len(lines) and lines[i].strip() == "" and out and out[-1].strip() == "":
                i += 1
            left.discard(m.group(1))
            continue
        out.append(lines[i])
        i += 1
    assert not left, (name, left)
    open(path, "w").write("\n".join(out))
print("dropped")
PY
```
Expected: `dropped`.

- [ ] **Step 3: Hand edits in the ported ITs**

`MetadataApiTest.kt`:
- delete the private helpers `geometrySrid(...)` and `geometryType(...)`;
- in the first test, rename it to `` `creates an object with fields and a physical table` `` and delete the field line `mapOf("name" to "lote", "type" to "GEOMETRY", "geometryType" to "POLYGON", "srid" to 32718)` (remove the trailing comma on the line before it);
- replace the response assertions after `.jsonPath("$.name").isEqualTo(name)` with only `.jsonPath("$.fields.length()").isEqualTo(3)`;
- replace the last three assertions (`geometrySrid`, `geometryType`, `indexNames(...).contains("${table}_lote_gix", ...)`) with `assertThat(indexNames(table)).contains("${table}_org_idx")`.

`RecordApiTest.kt`:
- in `@BeforeEach createObject()` delete the two `GEOMETRY` field lines (`lote`, `acceso`) and fix the trailing comma;
- in `updates and deletes a record and audits every operation` and in `createRecord(...)`, delete the line `"geometries" to mapOf("lote" to POLYGON)` and fix the trailing comma;
- delete the top-level `private val POLYGON = ...` and the private helpers `storedSrid()` and `storedArea()`.

`PermissionEnforcementTest.kt`: in `@BeforeEach`, change `mapOf("name" to "zona", "type" to "GEOMETRY", "geometryType" to "POINT", "srid" to 4326)` to `mapOf("name" to "zona", "type" to "TEXT")`. The field count assertions stay the same (`codigo` and `zona` are still readable).

`FieldApiTest.kt`: replace the test `publishes the names it keeps for itself` with:
```kotlin
    @Test
    fun `publishes the names it keeps for itself`() {
        val published = systemFields()
        // core alone: the six columns every record table has, then the reserved version.
        // a module adds its own (workflow_state) in P2; this app has none.
        assertThat(published.map { it.name })
            .containsExactly("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "version")
        assertThat(published.first { it.name == "created_at" }).isEqualTo(SystemFieldResponse("created_at", "DATETIME", "ALWAYS"))
        // reserved but never created: no type, because there is no column
        assertThat(published.first { it.name == "version" }).isEqualTo(SystemFieldResponse("version", null, "RESERVED"))
    }
```

Check that nothing optional is left:
```bash
grep -n -i "GEOMETRY\|geometr\|bbox\|automation\|/api/gis\|workflow\|/views\|/forms\|/pages" $TDST/api/*.kt || echo "clean"
```
Expected: `clean`.

- [ ] **Step 4: Write the MEASURE fixture**

`backend/chawpi-core/src/test/resources/db/chawpi/measure-test/V1__measure.sql`:
```sql
-- a test-only module adding a field type the way chawpi-gis does: its own custom_fields column (R3)
-- and the type check re-added with its type appended (R4).
ALTER TABLE ${metadataSchema}.custom_fields ADD COLUMN unit text;

ALTER TABLE ${metadataSchema}.custom_fields DROP CONSTRAINT custom_fields_type_valid;
ALTER TABLE ${metadataSchema}.custom_fields ADD CONSTRAINT custom_fields_type_valid CHECK (type IN (
    'TEXT', 'LONG_TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATETIME',
    'ENUM', 'EMAIL', 'URL', 'UUID', 'RELATION', 'MEASURE'
));

ALTER TABLE ${metadataSchema}.custom_fields
    ADD CONSTRAINT custom_fields_measure_has_unit CHECK (type <> 'MEASURE' OR unit IS NOT NULL);
```

`$TDST/fixtures/MeasureFieldType.kt`:
```kotlin
package chawpi.core.fixtures

import chawpi.core.common.ValidationException
import chawpi.core.data.RecordCriterion
import chawpi.core.data.RecordQueryContributor
import chawpi.core.metadata.CustomField
import chawpi.core.metadata.CustomObject
import chawpi.core.metadata.FieldRequest
import chawpi.core.metadata.FieldType
import chawpi.core.metadata.FieldTypeHandler
import chawpi.core.metadata.ObjectDefinition
import chawpi.core.metadata.UpdateFieldRequest
import chawpi.core.platform.ModuleMigration
import chawpi.core.platform.SqlIdentifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

// a made-up module type that uses every hook chawpi-gis needs, on plain postgres: its own
// custom_fields column, a payload section, select/bind sql, its own rules, json on every field and
// object, its own index and its own query parameter. if this works, the SPI is enough for GEOMETRY.
val MEASURE = FieldType("MEASURE")

class MeasureFieldType : FieldTypeHandler {
    override val type = MEASURE
    override val attributeColumns: Map<String, Class<*>> = mapOf("unit" to String::class.java)
    override val section = "measures"

    override fun attributesOf(
        fieldName: String,
        request: FieldRequest
    ): Map<String, Any?> {
        if (request.unique) throw ValidationException("Measure field '$fieldName' cannot be unique", "unique", "has no meaning on a measure")
        val unit = request.extensions["unit"] as? String ?: throw ValidationException("Measure field '$fieldName' has no unit", "unit", "is required")
        return mapOf("unit" to unit)
    }

    override fun checkUpdate(
        field: CustomField,
        request: UpdateFieldRequest
    ) {
        if (request.unique == true) throw ValidationException("Measure field '${field.name}' cannot be unique", "unique", "has no meaning on a measure")
    }

    override fun fieldProperties(field: CustomField): Map<String, Any?> =
        mapOf("measure" to if (field.type == MEASURE) mapOf("unit" to field.attributes["unit"]) else null)

    override fun objectProperties(definition: ObjectDefinition): Map<String, Any?> =
        mapOf("measure" to definition.fields.firstOrNull { it.type == MEASURE }?.let { mapOf("unit" to it.attributes["unit"]) })

    override fun columnType(field: CustomField) = "numeric"

    override fun indexes(
        obj: CustomObject,
        table: String,
        field: CustomField
    ): List<String> =
        listOf(
            "CREATE INDEX ${SqlIdentifier.quote(SqlIdentifier.indexName(obj.physicalTable, field.columnName, "mix"))} " +
                "ON $table (${SqlIdentifier.quote(field.columnName)})"
        )

    override fun unknownSectionKey(
        key: String,
        definition: ObjectDefinition
    ) = ValidationException("Unknown measure '$key'", key, "is not a measure of '${definition.obj.name}'")

    override fun toDatabase(
        field: CustomField,
        value: Any?
    ): Any? =
        when (value) {
            null -> null
            is Number -> value.toString()
            else -> throw ValidationException("Invalid measure", field.name, "must be a number")
        }

    override fun javaType(field: CustomField) = String::class.java

    // bound as text, cast in sql: the trick gis plays with geojson
    override fun bindExpression(
        field: CustomField,
        parameter: String
    ) = "CAST(:$parameter AS numeric)"

    override fun select(
        field: CustomField,
        column: String
    ) = "CAST($column AS text) AS ${SqlIdentifier.quote(readName(field))}"

    override fun readName(field: CustomField) = "${field.columnName}__txt"

    override fun fromDatabase(
        field: CustomField,
        value: Any?
    ): Any? = (value as String?)?.toBigDecimal()

    override fun rejectFilterOrSort(field: CustomField) =
        ValidationException("Cannot filter or sort by measure '${field.name}'", field.name, "use min_measure instead")
}

// ?min_measure=10: records whose first measure is at least 10. the gis bbox, in miniature.
class MinMeasureQuery : RecordQueryContributor {
    override val parameters = setOf("min_measure")

    override fun parse(params: Map<String, String>): RecordCriterion? {
        val raw = params["min_measure"] ?: return null
        val min = raw.toBigDecimalOrNull() ?: throw ValidationException("Invalid min_measure", "min_measure", "must be a number")
        return RecordCriterion { definition, bind ->
            val field =
                definition.fields.firstOrNull { it.type == MEASURE }
                    ?: throw ValidationException("Object '${definition.obj.name}' has no measure", "min_measure", "the object has no measure")
            "${SqlIdentifier.quote(field.columnName)} >= ${bind(min)}"
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class MeasureTestConfiguration {
    @Bean
    fun measureFieldType(): FieldTypeHandler = MeasureFieldType()

    @Bean
    fun minMeasureQuery(): RecordQueryContributor = MinMeasureQuery()

    @Bean
    fun measureMigration(): ModuleMigration = ModuleMigration("measure_test", "classpath:db/chawpi/measure-test", ModuleMigration.MODULE_ORDER)
}
```

- [ ] **Step 5: Write the three new IT classes**

`$TDST/api/CoreOnlyApiTest.kt`:
```kotlin
package chawpi.core.api

import chawpi.test.ChawpiIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

// what an app with chawpi-core and no module sees. proves every module is optional.
class CoreOnlyApiTest : ChawpiIntegrationTest() {
    private val json = JsonMapper.builder().build()
    private lateinit var token: String
    private lateinit var objectName: String

    @BeforeEach
    fun createObject() {
        token = bearer()
        objectName = uniqueName("flat")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to objectName, "label" to "Flat", "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
    }

    private fun body(
        method: String,
        uri: String,
        payload: Any? = null
    ): JsonNode {
        val spec =
            when (method) {
                "POST" -> client.post().uri(uri).header(HttpHeaders.AUTHORIZATION, token).bodyValue(payload!!)
                else -> client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, token)
            }
        val raw =
            spec
                .exchange()
                .expectStatus()
                .is2xxSuccessful
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        return json.readTree(raw)
    }

    private fun keys(node: JsonNode): List<String> = node.propertyNames().asSequence().toList()

    @Test
    fun `a record carries attributes and state, and nothing a module would add`() {
        val record = body("POST", "/api/objects/$objectName/records", mapOf("attributes" to mapOf("codigo" to "A-1")))

        assertThat(keys(record)).containsExactlyInAnyOrder("id", "createdAt", "updatedAt", "attributes", "state")
    }

    @Test
    fun `object and field json have no module keys`() {
        val definition = body("GET", "/api/objects/$objectName")

        assertThat(keys(definition)).containsExactlyInAnyOrder("id", "name", "label", "pluralLabel", "description", "enabled", "fields")
        assertThat(keys(definition.get("fields").get(0))).containsExactlyInAnyOrder(
            "id",
            "name",
            "label",
            "type",
            "required",
            "unique",
            "defaultValue",
            "description",
            "position",
            "enumOptions",
            "relationTarget",
            "visible",
            "editable"
        )
    }

    @Test
    fun `module properties sent without the module are ignored, like any unknown property`() {
        body("POST", "/api/objects/$objectName/records", mapOf("attributes" to mapOf("codigo" to "A-2"), "geometries" to mapOf("lote" to null)))
        body("POST", "/api/metadata/objects/$objectName/fields", mapOf("name" to "nota", "type" to "TEXT", "geometryType" to "POINT"))
    }

    @Test
    fun `a type no installed module provides is refused`() {
        client
            .post()
            .uri("/api/metadata/objects/$objectName/fields")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to "lote", "type" to "GEOMETRY", "geometryType" to "POINT"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("type")
    }

    @Test
    fun `bbox is an unknown field without the module that owns it`() {
        client
            .get()
            .uri("/api/objects/$objectName/records?bbox=1,2,3,4")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("bbox")
    }

    @Test
    fun `health names the app and errors carry the chawpi problem type`() {
        client
            .get()
            .uri("/api/health")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.application")
            .isEqualTo("chawpi")

        client
            .get()
            .uri("/api/objects/${uniqueName("missing")}")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNotFound
            .expectBody()
            .jsonPath("$.type")
            .isEqualTo("https://chawpi.dev/problems/404")
    }

    @Test
    fun `the views, forms and pages lists belong to their modules`() {
        listOf("views", "forms", "pages").forEach { part ->
            client
                .get()
                .uri("/api/metadata/objects/$objectName/$part")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isNotFound
        }
    }
}
```
(Use `fieldNames()` if `propertyNames()` is not on the Jackson 3 `JsonNode`.)

`$TDST/api/MeasureFieldTypeApiTest.kt`:
```kotlin
package chawpi.core.api

import chawpi.core.fixtures.MeasureTestConfiguration
import chawpi.test.ChawpiIntegrationTest
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource

// a module field type, end to end, the way chawpi-gis will plug GEOMETRY in. own schemas so the
// extra column and check never reach the other contexts sharing this database.
@Import(MeasureTestConfiguration::class)
@TestPropertySource(properties = ["chawpi.database.metadata-schema=measure_meta", "chawpi.database.data-schema=measure_data"])
class MeasureFieldTypeApiTest : ChawpiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    private lateinit var admin: String
    private lateinit var objectName: String

    @BeforeEach
    fun createObject() {
        admin = bearer()
        objectName = uniqueName("lote")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to objectName,
                    "label" to "Lote",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "area", "type" to "MEASURE", "unit" to "m2")
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.measure.unit")
            .isEqualTo("m2")
            .jsonPath("$.fields[0].measure")
            .isEmpty()
            .jsonPath("$.fields[1].type")
            .isEqualTo("MEASURE")
            .jsonPath("$.fields[1].measure.unit")
            .isEqualTo("m2")
    }

    private fun createRecord(
        codigo: String,
        measures: Map<String, Any?>? = null,
        token: String = admin
    ): String {
        val body = mutableMapOf<String, Any>("attributes" to mapOf("codigo" to codigo))
        if (measures != null) body["measures"] = measures
        val raw =
            client
                .post()
                .uri("/api/objects/$objectName/records")
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(raw)!!.groupValues[1]
    }

    private fun scalar(sql: String): Any? =
        runBlocking {
            db
                .sql(sql)
                .bind("name", objectName)
                .map { row, _ -> row.get(0) }
                .one()
                .awaitFirstOrNull()
        }

    @Test
    fun `the attribute lands in the module's column and the index in the data schema`() {
        assertThat(
            scalar(
                "SELECT f.unit FROM measure_meta.custom_fields f JOIN measure_meta.custom_objects o ON o.id = f.object_id " +
                    "WHERE o.name = :name AND f.name = 'area'"
            )
        ).isEqualTo("m2")
        assertThat(
            scalar(
                "SELECT count(*) FROM pg_indexes i JOIN measure_meta.custom_objects o ON i.tablename = o.physical_table " +
                    "WHERE o.name = :name AND i.schemaname = 'measure_data' AND i.indexname LIKE '%_area_mix'"
            ).toString()
        ).isEqualTo("1")
    }

    @Test
    fun `the type's own rules refuse what it does not support`() {
        client
            .post()
            .uri("/api/metadata/objects/$objectName/fields")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "alto", "type" to "MEASURE"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("unit")

        client
            .put()
            .uri("/api/metadata/objects/$objectName/fields/area")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("unique" to true))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("unique")
    }

    @Test
    fun `a section is written when sent, left alone when not, cleared by null`() {
        val id = createRecord("A-1", mapOf("area" to 12.5))
        val record = "/api/objects/$objectName/records/$id"

        client.get().uri(record).header(HttpHeaders.AUTHORIZATION, admin).exchange().expectBody().jsonPath("$.measures.area").isEqualTo(12.5)

        client
            .put()
            .uri(record)
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "A-1b")))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.measures.area")
            .isEqualTo(12.5)

        client
            .put()
            .uri(record)
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "A-1b"), "measures" to mapOf("area" to null)))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.measures.area")
            .isEmpty()
    }

    @Test
    fun `a record with no measure still lists the section, and a flat object gets an empty one`() {
        val id = createRecord("A-2")
        client
            .get()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectBody()
            .jsonPath("$.measures")
            .exists()
            .jsonPath("$.measures.area")
            .isEmpty()

        val flat = uniqueName("flat")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to flat, "label" to "Flat", "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .post()
            .uri("/api/objects/$flat/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "F-1")))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.measures")
            .isEmpty()
    }

    @Test
    fun `a section key naming no such field is refused in the module's words`() {
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "A-3"), "measures" to mapOf("codigo" to 1)))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Unknown measure 'codigo'")
    }

    @Test
    fun `the module's query parameter filters, and its type cannot be sorted on`() {
        createRecord("small", mapOf("area" to 5))
        createRecord("big", mapOf("area" to 20))

        client
            .get()
            .uri("/api/objects/$objectName/records?min_measure=10")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)
            .jsonPath("$.content[0].attributes.codigo")
            .isEqualTo("big")

        client
            .get()
            .uri("/api/objects/$objectName/records?sort=area")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isBadRequest
        client
            .get()
            .uri("/api/objects/$objectName/records?min_measure=lots")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    // a section field is a field: its permissions are the field's, through either door
    @Test
    fun `a locked section field can be neither written nor read back`() {
        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to role, "label" to "Blind", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to listOf("READ", "CREATE", "UPDATE").map { mapOf("objectName" to null, "action" to it, "allowed" to true) }))
            .exchange()
            .expectStatus()
            .isOk
        client
            .put()
            .uri("/api/roles/$role/field-permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("fields" to listOf(mapOf("objectName" to objectName, "fieldName" to "area", "read" to false, "write" to false))))
            .exchange()
            .expectStatus()
            .isOk
        val email = "${uniqueName("member")}@chawpi.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Member", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        val member = bearer(email, "supersecret")

        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, member)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "L-1"), "measures" to mapOf("area" to 3)))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("area")

        createRecord("L-2", mapOf("area" to 3))
        client
            .get()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, member)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.content[0].measures.area")
            .doesNotExist()
    }
}
```

`$TDST/api/CustomSchemaApiTest.kt`:
```kotlin
package chawpi.core.api

import chawpi.test.ChawpiIntegrationTest
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource

// an app may pick its own schema names: migrations, ddl and every query follow them, and the
// default schemas are never touched by this context
@TestPropertySource(properties = ["chawpi.database.metadata-schema=acme_meta", "chawpi.database.data-schema=acme_data"])
class CustomSchemaApiTest : ChawpiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    private fun count(
        sql: String,
        vararg binds: Pair<String, Any>
    ): Long =
        runBlocking {
            var spec = db.sql(sql)
            binds.forEach { (name, value) -> spec = spec.bind(name, value) }
            (spec.map { row, _ -> row.get(0) as Number }.one().awaitFirstOrNull() ?: 0).toLong()
        }

    @Test
    fun `objects, records and history live in the configured schemas`() {
        val token = bearer()
        val name = uniqueName("predio")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to name, "label" to "Predio", "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
        val raw =
            client
                .post()
                .uri("/api/objects/$name/records")
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "C-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(raw)!!.groupValues[1]
        client
            .get()
            .uri("/api/objects/$name/records/$id/history")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)

        val table =
            runBlocking {
                db
                    .sql("SELECT physical_table FROM acme_meta.custom_objects WHERE name = :name")
                    .bind("name", name)
                    .map { row, _ -> row.get("physical_table", String::class.java)!! }
                    .one()
                    .awaitFirstOrNull()!!
            }
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'acme_data' AND table_name = :t", "t" to table)).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'app_data' AND table_name = :t", "t" to table)).isEqualTo(0)
        assertThat(count("SELECT count(*) FROM acme_meta.audit_log WHERE object_name = :n", "n" to name)).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'acme_meta' AND table_name = 'flyway_history_core'")).isEqualTo(1)
    }
}
```

- [ ] **Step 6: Compile the tests without Docker**

Run: `./gradlew :chawpi-core:compileTestKotlin :chawpi-core:test`
Expected: compiles. Unit tests PASS. The ITs are tagged `integration`, so `test` skips them.

- [ ] **Step 7: Run the ITs (needs Docker, or `CHAWPI_TEST_DB_*` pointing at a database named `*_test`)**

Run: `nc -z localhost 5443 && CHAWPI_TEST_DB_HOST=localhost CHAWPI_TEST_DB_PORT=5443 CHAWPI_TEST_DB_NAME=chawpi_test CHAWPI_TEST_DB_USERNAME=chawpi CHAWPI_TEST_DB_PASSWORD=chawpi ./gradlew :chawpi-core:integrationTest --rerun`
Expected: PASS. Counts:
- ported: Metadata 5, ObjectCrud 9, Field 7, Relationship 11, Record 4, Admin 7, Organization 3, Audit 6, PermissionEnforcement 12;
- new: CoreOnly 7, MeasureFieldType 7, CustomSchema 1.

The database is the tunnelled plain `postgres:18` (no PostGIS installed — proves core needs none). If `nc -z localhost 5443` fails, report "IT step not run: tunnel down" and continue. Do not mark it passed.
If a ported assertion fails only because of the JSON key order (Ruling R5), fix the assertion to be order-independent. If it fails for any other reason, it is a real regression: fix the code, not the test.

- [ ] **Step 8: Format, check, leave uncommitted**

```bash
./gradlew :chawpi-core:ktlintFormat :chawpi-core:ktlintCheck :chawpi-core:build
git status --short | head
```
Expected: build passes, nothing committed.

---

### Task 12: Architecture test: core is module-agnostic and cycle-free

Plain Kotlin that scans the source text. No Konsist, no ArchUnit. It enforces two things: the package DAG from Ruling R1, and the rule that `chawpi.core` never names an optional module or geometry.

**Files:**
- Test: `$TDST/architecture/CoreArchitectureTest.kt`

**Interfaces:**
- Consumes: the source tree `backend/chawpi-core/src/main/kotlin/chawpi/core/**` and `backend/chawpi-core/build.gradle.kts`. The Gradle test working directory is the module directory.
- Produces: a failing unit test whenever someone adds a forbidden import, a package cycle, or a dependency on a module project.

- [ ] **Step 1: Write the test**

```kotlin
package chawpi.core.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

// core is a library other modules build on. it must never reach up into them, and its own
// packages must stay a DAG (the old package cycles are gone for good). ADR-0025.
class CoreArchitectureTest {
    private val root = File("src/main/kotlin/chawpi/core")

    // package -> the core packages it may use
    private val allowed: Map<String, Set<String>> =
        mapOf(
            "common" to emptySet(),
            "platform" to setOf("common"),
            "identity" to setOf("common", "platform"),
            "metadata" to setOf("common", "platform", "identity"),
            "audit" to setOf("common", "platform", "identity", "metadata"),
            "data" to setOf("common", "platform", "identity", "metadata", "audit"),
            "admin" to setOf("common", "platform", "identity", "metadata"),
            "organization" to setOf("common", "platform", "identity", "metadata"),
            "autoconfigure" to setOf("common", "platform", "identity", "metadata", "audit", "data", "admin", "organization")
        )

    private val coreReference = Regex("""\bchawpi\.core\.([a-z]+)\b""")
    private val moduleReference = Regex("""\bchawpi[.:-](views|forms|pages|workflow|automation|documents|gis|agent)\b""")
    private val geometryWords = Regex("""(?i)\b(postgis|geojson|geometry|geometries|bbox|wgs84)\b|\bST_[A-Za-z]+""")

    private fun sources(): List<File> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    private fun packageOf(file: File): String = file.relativeTo(root).path.substringBefore(File.separator)

    private fun offenders(pattern: Regex): List<String> =
        sources().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line -> if (pattern.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}: ${line.trim()}" else null }
        }

    @Test
    fun `the scan finds the sources`() {
        assertThat(sources()).hasSizeGreaterThan(40)
    }

    @Test
    fun `core never names an optional module`() {
        assertThat(offenders(moduleReference)).isEmpty()
        assertThat(File("build.gradle.kts").readText()).doesNotContainPattern(""":chawpi-(views|forms|pages|workflow|automation|documents|gis|agent)""")
    }

    @Test
    fun `core does not speak geometry`() {
        assertThat(offenders(geometryWords)).isEmpty()
    }

    @Test
    fun `every package sits in the dag and uses only the packages below it`() {
        val violations =
            sources().flatMap { file ->
                val own = packageOf(file)
                val permitted = allowed[own] ?: return@flatMap listOf("${file.relativeTo(root)}: package '$own' is not in the dag")
                file
                    .readLines()
                    .filterNot { it.startsWith("package ") }
                    .flatMap { line -> coreReference.findAll(line).map { it.groupValues[1] }.toList() }
                    .filter { it != own && it !in permitted }
                    .distinct()
                    .map { "${file.relativeTo(root)}: $own may not use chawpi.core.$it" }
            }
        assertThat(violations).isEmpty()
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew :chawpi-core:test --tests chawpi.core.architecture.CoreArchitectureTest`
Expected: PASS (4 tests). If a test fails, it prints the file and line. Fix the source by rewording the comment, or by moving the dependency behind an SPI or down the DAG. Never add an exception to the test.

- [ ] **Step 3: Prove the test bites, then revert the probe**

```bash
printf 'package chawpi.core.common\n\n// uses chawpi.gis on purpose\nval probe = "chawpi.core.data"\n' > backend/chawpi-core/src/main/kotlin/chawpi/core/common/Probe.kt
./gradlew :chawpi-core:test --tests chawpi.core.architecture.CoreArchitectureTest; echo "exit=$?"
rm backend/chawpi-core/src/main/kotlin/chawpi/core/common/Probe.kt
./gradlew :chawpi-core:test --tests chawpi.core.architecture.CoreArchitectureTest
```
Expected: the first run FAILS (`exit=1`), naming `common/Probe.kt:3` (module) and `common may not use chawpi.core.data`. After `rm`, it PASSES.

- [ ] **Step 4: Format, check, leave uncommitted**

```bash
./gradlew :chawpi-core:ktlintFormat :chawpi-core:ktlintCheck :chawpi-core:build
git status --short | head
```
Expected: build passes, `Probe.kt` is gone, nothing committed.

---

### Task 13: ADRs 0025–0027

**Files:**
- Create: `docs/adr/0025-extension-spis.md`, `docs/adr/0026-per-module-migrations.md`, `docs/adr/0027-gis-optional.md`

**Interfaces:**
- Consumes: the rulings above and the code from Tasks 2–12 (names must match the code exactly).
- Produces: the decision record P2 modules follow. ADR 0024 (libraries and starters) is written in P7; these three do not depend on it.

- [ ] **Step 1: Write `docs/adr/0025-extension-spis.md`**

```markdown
# ADR-025: The core is extended through SPIs, never by knowing its modules

**Status**: accepted · 2026-09-25 · amends ADR-001, ADR-013, ADR-016, ADR-017

## Context

In sapgis the core packages knew the optional ones. `metadata` injected `PageService`, `ViewService`
and `FormService`; `MetadataService` could not start without a `FieldUsage` (automation) and an
`ObjectRemovalListener` (gis); `RecordService` needed exactly one `RecordChangeListener` and one
`WorkflowStates`; `platform.SqlIdentifier` listed `workflow_state`; and there were five package cycles
(metadata↔data, metadata↔identity, metadata↔forms/views/pages, data→audit→metadata, pages→forms→metadata).
A library cannot work like that: an app that leaves a module out must still boot.

## Decision

**`chawpi-core` (`chawpi.core.*`) depends on no module.** Modules plug in by declaring beans of
core-owned interfaces. Core collects every bean of a kind as an ordered list and falls back to a null
object when a single answer is needed.

| SPI | Package | Pattern | Core default |
|---|---|---|---|
| `FieldTypeHandler` + `FieldTypeRegistry` | `metadata` | Strategy + Registry | the 12 scalar types |
| `RecordQueryContributor` + `RecordCriterion` | `data` | Strategy | none |
| `SystemColumnContributor` → `SystemColumns` | `platform` | Registry | none |
| `RecordChangeListener` | `data` | Observer (list) | none |
| `ObjectRemovalListener`, `FieldUsage` | `metadata` | Observer / Chain (lists) | none |
| `WorkflowStates` | `data` | Null Object | `NoWorkflowStates` |
| `ModuleMigration` | `platform` | Registry | core, optional dev seed (ADR-026) |

Listeners are called synchronously, inside the caller's transaction, in `@Order`, as before.

**Inside core the packages form a DAG**: `common ← platform ← identity ← metadata ← audit ← data`,
with `admin` and `organization` on top and `autoconfigure` above all. Three moves made it one:
`ObjectSchemaManager` moved into `metadata`, record-level relationship walking moved into
`data.RelatedRecordService`, and user/role administration moved out of `identity` into `admin`.
`CoreArchitectureTest` enforces the DAG and the ban on module names.

**No component scanning of library code.** Every bean is declared in an `@AutoConfiguration`
(`ChawpiPlatform…`, `ChawpiSecurity…`, `ChawpiMetadata…`, `ChawpiData…`, `ChawpiAdminAutoConfiguration`)
with `@ConditionalOnMissingBean`, so an app replaces any of them by declaring its own. A module that
replaces a core default (`WorkflowStates`, `RecordStore`) orders its auto-configuration
`before = [ChawpiDataAutoConfiguration::class]`. Stereotype annotations stay on the classes: the
kotlin-spring plugin opens those classes for `@Transactional` proxies, and `@RestController` is how
WebFlux finds handlers.

**What core does not define.** Three rows of the design's SPI table live elsewhere:
- `ObjectMetadataContributor`: no interface. views, forms and pages each map their own
  `GET /api/metadata/objects/{object}/views|forms|pages` (same URLs); core's controller simply lost
  the three routes.
- `PageComponentProvider` belongs to chawpi-pages. pages registers the HISTORY component itself.
- `DocumentIssuer` belongs to chawpi-automation.

**Kept as data, not as knowledge.** The record-state column is still called `workflow_state`
(`ObjectSchemaManager.STATE_COLUMN`) and `RecordResponse.state` is still core JSON (ADR-013); only
the reservation of the name comes from the workflow module. `audit_log.document_id` and
`AuditOperation.ISSUE` stay in core, and chawpi-documents adds the FK and the CHECK value.

## Consequences

- An app with only `chawpi-core` boots on plain PostgreSQL; `CoreOnlyApiTest` proves it.
- A module that stores something about an object (a layer, a rule) must implement the matching
  listener, or deleting the object leaves it dangling. Core cannot check this for it.
- Without the workflow module, nothing stops a user field called `workflow_state`. Installing workflow
  later then finds the column taken. Accepted: the name is unusual and the conflict is loud.
```

- [ ] **Step 2: Write `docs/adr/0026-per-module-migrations.md`**

```markdown
# ADR-026: Each module owns its migrations and its Flyway history

**Status**: accepted · 2026-09-25 · amends ADR-008

## Context

sapgis ran one Flyway over `classpath:db/migration`, V1..V14. V1 alone created tables for eight
packages and the PostGIS extension; V14 put a foreign key from `audit_log` to `documents`. The schema
name `sapgis` was a literal in about 120 SQL strings. With modules optional, an app must get exactly
the tables of the modules it has, and may add a module later.

## Decision

**`ChawpiMigrations` runs one Flyway per `ModuleMigration(name, location, order)` bean**, in `order`
(ties by name). Each has its own history table `flyway_history_<name>` in the metadata schema, and
`baselineOnMigrate(true)` at version `0`, so a module added to an existing database still runs its V1.
Order convention: core `0`, dev seed `10`, modules from `100` in dependency order. It stays JDBC-only
and runs at startup before traffic (ADR-008); `chawpi.database.migrate=false` turns it off.

**Schemas are configurable.** `chawpi.database.metadata-schema` (default `chawpi`) and
`chawpi.database.data-schema` (default `app_data`) become the `ChawpiSchemas` bean, validated as plain
identifiers at boot. Kotlin SQL interpolates them; migration SQL uses the Flyway placeholders
`${metadataSchema}` and `${dataSchema}`.

**The history was rebaselined, not replayed** (no chawpi database exists yet). Core's
`db/chawpi/core/V1__core.sql` is sapgis's final schema restricted to core tables: organizations, users,
roles, user_roles, custom_objects, custom_fields, relationships, permissions, field_permissions,
audit_log. Column order and constraint names are sapgis's, including the FK
`relationships_source_field_id_fkey` that kept its name when its column became `relation_field_id`.
Cross-module pieces move to the module that needs them:
- gis adds `custom_fields.geometry_type|srid|dimension`, their three CHECKs, re-adds
  `custom_fields_type_valid` with `GEOMETRY` appended, and creates `postgis`;
- documents adds `audit_log_document_id_fkey` and re-adds `audit_log_operation_valid` with `ISSUE`;
- views, forms, pages, workflow and automation create their own tables.
Installed together, the final schema equals sapgis's (modulo the schema rename), which P6 checks
with a `pg_dump --schema-only` diff.

Core creates `pgcrypto` and, when the server has it, `vector`. It no longer creates `postgis`.

**The dev seed is opt-in**: `ModuleMigration("core_seed", "classpath:db/chawpi/core-seed", 10)` is
registered only with `chawpi.seed.dev=true`. It creates the Demo organization and
`admin@chawpi.local` / `admin` with every admin action, storing a precomputed bcrypt hash rather than
calling `crypt()`, because with several schema pairs in one database pgcrypto lives wherever it was
created first.

## Consequences

- Adding a module to a running app is adding a dependency: its migrations run on the next boot.
- Removing a module leaves its tables and columns behind. Core ignores columns it did not ask for.
- A second module that adds a field type cannot simply re-add `custom_fields_type_valid` with its own
  list, because it would erase the first one's type. Today only gis adds a type; a second one needs this
  ADR revisited (for example, core dropping the CHECK in favour of `FieldTypeRegistry`).
- Beans that touch the database at startup must `@DependsOn("chawpiMigrations")`.
```

- [ ] **Step 3: Write `docs/adr/0027-gis-optional.md`** (with the illustrative gis sketch; it is not a task in this plan)

````markdown
# ADR-027: GIS is optional, and the API is unchanged when it is present

**Status**: accepted · 2026-09-25 · amends ADR-002, ADR-007, ADR-019

## Context

In sapgis, geometry lived in the core. `metadata` had `FieldType.GEOMETRY`, `GeometryType` and
`CustomField.geometryType/srid/dimension`. `data` emitted the geometry DDL and GIST index, selected
`ST_AsGeoJSON(ST_Transform(…, 4326))`, bound `ST_GeomFromGeoJSON`, filtered by `bbox`, and carried
`RecordRow.geometries`. The REST contract exposes all of it: `"geometry": {type, srid, dimension}` on
every field and object (null when flat), `"geometries": {…}` on every record, `?bbox=&geometry=` on
queries. Core must run on plain PostgreSQL, and with chawpi-gis present every one of those bytes must
come back unchanged.

## Decision

**A field type is a Strategy, looked up in a Registry.** `FieldType` is an open value
(`data class FieldType(val name: String)`). Core registers the 12 scalar types. chawpi-gis registers
one `FieldTypeHandler` for `GEOMETRY`, and that handler contributes everything geometry needs:

| Concern | Hook |
|---|---|
| column type `geometry(PolygonZ, 32718)` | `columnType(field)` |
| GIST index | `indexes(obj, table, field)` |
| read `ST_AsGeoJSON(ST_Transform(col, 4326)) AS "col__geojson"` | `select(field, column)` + `readName(field)` + `fromDatabase` |
| write `ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(CAST(:p AS text)), 4326), srid)` | `bindExpression(field, parameter)` + `toDatabase` + `javaType` |
| shape check (`must be a Polygon`) | `toDatabase` |
| `geometryType`/`srid`/`dimension` on the request; no unique, no default | `attributesOf(fieldName, request)` via `FieldRequest.extensions`, `checkUpdate` |
| stored in `custom_fields.geometry_type/srid/dimension` | `attributeColumns` → `CustomField.attributes` |
| `"geometry": {…}` or `null` on every field and object | `fieldProperties`, `objectProperties` |
| `"geometries": {…}` beside `"attributes"` | `section = "geometries"`, `unknownSectionKey` |
| no sort/filter on a geometry | `rejectFilterOrSort` |
| `?bbox=&geometry=` | a `RecordQueryContributor` returning a `RecordCriterion` |
| drop its layer when the object goes | `ObjectRemovalListener` |

JSON extension properties are flattened with `@JsonAnyGetter` and captured with `@JsonAnySetter`.
With gis present, keys and values are identical to sapgis. Only key order changes (flattened keys
come last), and JSON object order carries no meaning. Without gis, `geometry` and `geometries` are
simply absent, `GEOMETRY` is an unknown type (400), and `bbox` is an unknown field (400).

**Schema identity over core purity.** The three attribute columns stay on `custom_fields` (sapgis's
final schema), but core neither creates nor names them: gis's migration adds them, and core reads and
writes whatever columns installed handlers declare. The `custom_fields_type_valid` CHECK is re-added by
gis with `GEOMETRY` appended, under the same name (ADR-026).

A stored field whose type is no longer installed still lists in metadata (its type is just a name);
record reads and writes on its object answer 409 `Field type 'GEOMETRY' is not installed`.

`MeasureFieldTypeApiTest` proves the contract on plain PostgreSQL with a made-up `MEASURE` type that
uses every hook above (own column, section, cast-based select/bind, rules, index, query parameter,
field permissions through both doors).

### Illustrative sketch of chawpi-gis (P2, not built here)

```kotlin
val GEOMETRY = FieldType("GEOMETRY")

class GeometryFieldType(private val json: ObjectMapper) : FieldTypeHandler {
    override val type = GEOMETRY
    override val section = "geometries"
    override val attributeColumns = mapOf("geometry_type" to String::class.java, "srid" to Integer::class.java, "dimension" to Integer::class.java)

    override fun attributesOf(fieldName: String, request: FieldRequest): Map<String, Any?> {
        if (request.unique) throw ValidationException("Geometry field '$fieldName' cannot be unique", "unique", "has no meaning on a geometry")
        if (request.defaultValue != null) throw ValidationException("Geometry field '$fieldName' cannot have a default", "defaultValue", "has no meaning on a geometry")
        val dimension = (request.extensions["dimension"] as Number?)?.toInt() ?: 2
        val srid = (request.extensions["srid"] as Number?)?.toInt() ?: 4326
        // same checks and messages as sapgis MetadataService.geometryOf
        return mapOf("geometry_type" to GeometryType.parse(request.extensions["geometryType"] as String?).name, "srid" to srid, "dimension" to dimension)
    }

    override fun columnType(field: CustomField) = "geometry(${shape(field).columnType(dimension(field))}, ${srid(field)})"
    override fun indexes(obj: CustomObject, table: String, field: CustomField) =
        listOf("CREATE INDEX ${SqlIdentifier.quote(SqlIdentifier.indexName(obj.physicalTable, field.columnName, "gix"))} ON $table USING GIST (${SqlIdentifier.quote(field.columnName)})")
    override fun select(field: CustomField, column: String) = "ST_AsGeoJSON(ST_Transform($column, 4326)) AS ${SqlIdentifier.quote(readName(field))}"
    override fun readName(field: CustomField) = "${field.columnName}__geojson"
    override fun bindExpression(field: CustomField, parameter: String) = "ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(CAST(:$parameter AS text)), 4326), ${srid(field)})"
    override fun toDatabase(field: CustomField, value: Any?) = json.writeValueAsString(checkShape(field, value))
    override fun javaType(field: CustomField) = String::class.java
    override fun fromDatabase(field: CustomField, value: Any?) = (value as String?)?.let { json.readValue(it, Map::class.java) }
    override fun fieldProperties(field: CustomField) = mapOf("geometry" to field.geometryResponse())
    override fun objectProperties(definition: ObjectDefinition) = mapOf("geometry" to definition.fields.firstOrNull { it.type == GEOMETRY }?.geometryResponse())
    override fun unknownSectionKey(key: String, definition: ObjectDefinition) = ValidationException("Unknown geometry '$key'", key, "is not a geometry of '${definition.obj.name}'")
    override fun rejectFilterOrSort(field: CustomField) = ValidationException("Cannot filter or sort by geometry '${field.name}'", field.name, "use bbox instead")
}

class BboxQuery : RecordQueryContributor {
    override val parameters = setOf("bbox", "geometry")
    override fun parse(params: Map<String, String>): RecordCriterion? {
        val box = params["bbox"]?.let(::parseBbox) ?: return null   // "Invalid bbox" as in sapgis
        val named = params["geometry"]?.trim()?.ifBlank { null }
        return RecordCriterion { definition, bind ->
            val field = geometryOrFail(definition, named)           // sapgis messages
            "ST_Intersects(${SqlIdentifier.quote(field.columnName)}, ST_Transform(ST_MakeEnvelope(${bind(box.minX)}, ${bind(box.minY)}, ${bind(box.maxX)}, ${bind(box.maxY)}, 4326), ${srid(field)}))"
        }
    }
}
```

gis's migration (`db/chawpi/gis/V1__gis.sql`, order ≥ 100): `CREATE EXTENSION IF NOT EXISTS postgis`,
`ALTER TABLE ${metadataSchema}.custom_fields ADD COLUMN geometry_type text, ADD COLUMN srid integer,
ADD COLUMN dimension integer`, sapgis V9's three CHECKs, and `custom_fields_type_valid` re-added with
`'GEOMETRY'` appended. Its integration tests set `systemProperty("chawpi.test.db.image", "postgis/postgis:18-3.6")`.

## Consequences

- Core's record path costs one registry lookup per field. It never branches on a type name, except
  the ENUM check and the RELATION foreign key, which are core's own.
- The only visible difference with gis present is key order.
- Anything else that wants a new column type (money with currency, say) takes the same road.
````

- [ ] **Step 4: Check the names in the ADRs against the code**

```bash
for n in FieldTypeHandler FieldTypeRegistry RecordQueryContributor RecordCriterion SystemColumnContributor SystemColumns RecordChangeListener ObjectRemovalListener FieldUsage WorkflowStates NoWorkflowStates ModuleMigration ChawpiMigrations ChawpiSchemas RelatedRecordService ChawpiDataAutoConfiguration; do
  grep -rq "\b$n\b" backend/chawpi-core/src/main/kotlin && echo "ok $n" || echo "MISSING $n"
done
grep -c "" docs/adr/0025-extension-spis.md docs/adr/0026-per-module-migrations.md docs/adr/0027-gis-optional.md
```
Expected: every line says `ok`, and all three files are non-empty.

- [ ] **Step 5: Leave uncommitted**

Run: `git status --short docs/adr`
Expected: three `??` entries, nothing committed.

---

### Task 14: Phase verification

- [ ] **Step 1: Full build, unit tests, publishing**

```bash
./gradlew -p backend/build-logic test
./gradlew build
env -u GITHUB_TOKEN -u GITHUB_ACTOR ./gradlew :chawpi-core:publishToMavenLocal :chawpi-test:publishToMavenLocal :chawpi-bom:publishToMavenLocal
ls ~/.m2/repository/chawpi/chawpi-core/0.1.0/ ~/.m2/repository/chawpi/chawpi-test/0.1.0/
```
Expected: all green. The local repo has `chawpi-core-0.1.0.jar`, `-sources.jar`, `.pom` (and the same for `chawpi-test`). The core jar contains the imports file and the migrations:
```bash
unzip -l ~/.m2/repository/chawpi/chawpi-core/0.1.0/chawpi-core-0.1.0.jar | grep -E "AutoConfiguration.imports|spring.factories|db/chawpi/core/V1__core.sql|core-seed"
```

- [ ] **Step 2: Integration tests (needs Docker, or `CHAWPI_TEST_DB_*`)**

Run: `nc -z localhost 5443 && CHAWPI_TEST_DB_HOST=localhost CHAWPI_TEST_DB_PORT=5443 CHAWPI_TEST_DB_NAME=chawpi_test CHAWPI_TEST_DB_USERNAME=chawpi CHAWPI_TEST_DB_PASSWORD=chawpi ./gradlew integrationTest --rerun`
Expected: all ITs from Task 11 PASS against the tunnelled `postgres:18`. If the tunnel is down, report it as not run.

- [ ] **Step 3: Nothing sapgis, nothing optional, nothing committed**

```bash
grep -rn -i sapgis backend/ --include='*.kt' --include='*.kts' --include='*.sql' --include='*.imports' --include='*.factories' || echo "no sapgis"
git status --short
git log --oneline -1
```
Expected: `no sapgis`. `git status` shows only new/modified files. The last commit is still P0's `d94065c` (or whatever HEAD was before P1). Report the output.
