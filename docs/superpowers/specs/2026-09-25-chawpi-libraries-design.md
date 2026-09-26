# Chawpi — sapgis refactored into reusable libraries (design)

## Context

`../sapgis` is a working metadata-driven platform (Kotlin 2.4.20 / Spring Boot 4.1.1 WebFlux + R2DBC,
PostgreSQL 18 + PostGIS, React 19.3 + Vite) built as ONE app: 15 backend packages in one Gradle
module, 16 frontend features in one Vite app. It cannot be reused: routes/nav hardcoded, 5 backend
package cycles (metadata↔data, metadata↔identity, metadata↔forms/views/pages, data→audit→metadata,
pages→forms→metadata), single required-bean ports (metadata can't boot without automation+gis),
all PostGIS SQL inside `data`, V1 migration mixes 8 modules, frontend cycles (history↔documents,
app↔layout, components→features), `DynamicForm` statically imports MapLibre, `/api` and `sapgis.*`
keys hardcoded.

Goal: **chawpi** = a general-purpose set of libraries (Spring Boot starter style + npm packages) so a
new App gets the core with minimal code and opts into modules (documents, gis, workflow…) by adding a
dependency. **No functional change**: same REST API, same behaviour, same UI; GIS support kept as-is
but optional. Everything is preserved (ADRs, HISTORY, specs/plans, docs, examples), rebranded.

Decisions taken with the user:
- Base package / groupId **`chawpi`** (`chawpi.core`, `chawpi.gis`…), npm scope **`@chawpi`**.
- **Polyglot monorepo** (this repo): `backend/` Gradle multi-project + `frontend/` yarn workspaces.
- **Clean start**: no sapgis DB to migrate → migrations rebaselined per module (same final schema).
- **GIS 100% optional**: core runs on plain PostgreSQL; `chawpi-gis` plugs geometry in via SPIs.
- Publishing to **GitHub Packages** (Maven + npm) from CI on tag.

Stack unchanged (Kotlin 2.4.20, Boot 4.1.1, JDK 25, Gradle 9.7.1, Flyway 12.4, Testcontainers 2.0.5,
Embabel 1.5.2, React 19.3, Vite 8, Tailwind 4, react-query 5, zod 4, i18next, MapLibre 6…).

## Repository layout

```
chawpi/
  backend/
    build-logic/                  convention plugins: chawpi.kotlin-library, chawpi.spring-module,
                                  chawpi.publishing, chawpi.integration-test
    chawpi-bom/                   java-platform: aligns every chawpi-* version
    chawpi-core/                  common, platform, identity, organization, metadata, data, audit
    chawpi-views/  chawpi-forms/  chawpi-pages/
    chawpi-workflow/  chawpi-automation/  chawpi-documents/
    chawpi-gis/  chawpi-agent/
    starters/chawpi-spring-boot-starter[-<module>]/   thin: module + runtime drivers
    chawpi-test/                  published test fixtures (IntegrationTest base, containers, auth helpers)
    chawpi-integration-tests/     ported API ITs (all 20) against assembled test apps
  frontend/                       yarn workspaces
    packages/ui  core  views  forms  pages  workflow  automation  documents  gis  agent  testing
  examples/
    simple-sample/   {server, web}   core only, plain Postgres (proves GIS optional)
    documents-sample/{server, web}   core + documents (+automation issuing)
    gis-sample/      {server, web}   core + gis; perene cadastre model.json + apply.py (from sapgis)
    full-sample/     {server, web}   every module
  infra/docker/                   compose (postgres+postgis+pgvector, geoserver), renamed chawpi
  docs/  adr/ architecture/ domain/ api/ gis/ security/ development/ modules/ superpowers/ HISTORY.md
  .github/workflows/ci.yml, publish.yml
```

Git history: fresh start (user decision). Files are copied from `../sapgis` (not merged); sapgis
stays the historical reference. Nothing from sapgis is lost.

## Backend design

### Module graph (acyclic, enforced)
```
core ← views, forms, workflow, documents, gis, agent
core ← forms ← pages
core ← automation   (documents ↔ automation only via optional port)
```
Each module: own package `chawpi.<module>`, own `@AutoConfiguration` registered in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, own
`@ConfigurationProperties("chawpi.<module>")` with `enabled` (default true), own Flyway migrations.
No component scanning of library code: beans declared explicitly in auto-configs
(`@ConditionalOnMissingBean` so apps can override any bean — Open/Closed).

### App developer experience
```kotlin
// build.gradle.kts
implementation(platform("chawpi:chawpi-bom:0.1.0"))
implementation("chawpi:chawpi-spring-boot-starter")          // core
implementation("chawpi:chawpi-spring-boot-starter-gis")       // opt-in
implementation("chawpi:chawpi-spring-boot-starter-documents") // opt-in

@SpringBootApplication class MyApp          // nothing else required
```
Optional `@ChawpiApplication` meta-annotation (= `@SpringBootApplication` + `@ConfigurationPropertiesScan`)
for app code convenience. Config in `application.yml` under `chawpi.*` (env `CHAWPI_*`).

### Extension SPIs in core (break cycles, make modules optional)
| SPI (chawpi.core…) | Pattern | Replaces | Provided by |
|---|---|---|---|
| `FieldTypeHandler` + `FieldTypeRegistry` | Strategy + Registry | PostGIS SQL hardcoded in `data` (DDL, GIST index, select `ST_AsGeoJSON`, bind `ST_GeomFromGeoJSON`, validation) | core (scalar types), gis (GEOMETRY) |
| `RecordQueryContributor` | Strategy | `bbox`/`geometry` query params in `RecordQueryParams`/`RecordStore` | gis |
| `SystemColumnContributor` | Registry | `workflow_state` in platform `SYSTEM_COLUMN_LIST` | workflow |
| `RecordChangeListener` (List) | Observer | single required bean | automation |
| `ObjectRemovalListener`, `FieldUsage` (List) | Observer / Chain | single required bean | gis, automation |
| `WorkflowStates` + `NoWorkflowStates` | Null Object via `@ConditionalOnMissingBean` | required bean | workflow |
| `PageComponentProvider` | Registry | hardcoded `ComponentType` switch in pages (MAP, WORKFLOW, HISTORY) | pages (base), gis (MAP), workflow (WORKFLOW), core (HISTORY) |
| `DocumentIssuer` (optional port in automation) | Adapter | `automation.Documents` required bean | documents |
| `ObjectMetadataContributor` | Composite | `ObjectMetadataController` injecting Page/View/FormService | views/forms/pages own their `/api/metadata/objects/{o}/…` routes (same URLs) |

Cross-module ports stay synchronous and inside the caller's transaction (current semantics); lists
iterate in `@Order`. Direct SQL on other modules' tables replaced by ports (e.g. `ObjectCatalog`,
`UserDirectory`); literal `"app_data"`/`"sapgis"` replaced by `ChawpiSchemas` (configurable
`chawpi.database.metadata-schema` default `chawpi`, `chawpi.database.data-schema` default `app_data`).

### Persistence / migrations
- `ChawpiMigrations`: core Flyway runner (JDBC, as ADR-008) executes every registered
  `ModuleMigration(name, location, order)` in dependency order, each with its own history table
  `flyway_history_<module>`. Locations `classpath:db/chawpi/<module>`.
- Rebaseline V1–V14 into per-module V1s producing the identical final schema. Cross-module FKs owned by
  the dependent module (e.g. `audit_log.document_id` FK added by documents' migration; geometry columns
  on `custom_fields` added by gis).
- Core: `CREATE EXTENSION pgcrypto` only; gis: `postgis`; `vector` stays optional as in sapgis.
- Dev seed (admin user) moved to opt-in location enabled by `chawpi.seed.dev=true`
  (seed user `admin@chawpi.local`).

### Formatting (mandatory)
sapgis `.editorconfig` copied verbatim to repo root and applied to ALL code (Kotlin 4 sp, TS/YAML/MD
2 sp, 160 cols, LF, final newline). Enforced: ktlint reads `.editorconfig` (`./gradlew ktlintCheck`
in build), prettier respects it (`.prettierrc.json` from sapgis, `editorconfig: true`), CI fails on
drift. Every subagent task ends with `ktlintFormat` / `prettier --write` + check.

### Clean-code rules carried from sapgis CLAUDE.md
Metadata-driven, no JPA, bound values only, `SqlIdentifier` validation, caveman comments, ktlint,
`.editorconfig`, no overengineering (every SPI above has a concrete second implementation or an optional
module that needs it). `SapgisException` → `ChawpiException`; problem type URI configurable
(`chawpi.web.problem-base-uri`).

## Frontend design

### Packages (Vite library mode + `tsc` declarations, ESM, peer deps react/react-dom/react-query/i18next/react-router)
| Package | Contents | Heavy deps isolated |
|---|---|---|
| `@chawpi/ui` | primitives, `cn`, `theme.css` (Tailwind 4 `@theme` tokens) | radix, cva |
| `@chawpi/core` | `ChawpiApp`, module registry, api client (configurable `baseUrl`, storage prefix), auth (`AuthProvider`, `useAuth`, permissions), i18n factory, types, metadata-to-zod, per-domain query hooks (split `lib/queries.ts`), AppShell, objects, relationships, records, dynamic-form, data-table, related, page-renderer, dashboard, admin, history/audit, login | react-hook-form, zod |
| `@chawpi/views`, `@chawpi/forms` | builders | – |
| `@chawpi/pages` | page builder + templates | dnd-kit |
| `@chawpi/workflow` | builder, canvas, WorkflowPanel, WORKFLOW page component | xyflow |
| `@chawpi/automation` | builder, runs | – |
| `@chawpi/documents` | types, TemplateEditor, DocumentView, print page, record documents + history link | tiptap |
| `@chawpi/gis` | MapView, GeometryField renderer, MAP page component, map page, layers page | maplibre, terra-draw |
| `@chawpi/agent` | assistant | – |
| `@chawpi/testing` | `renderWithProviders`, module-aware mocks | – |

### Module contract (Registry + Plugin; Open/Closed)
```ts
interface ChawpiModule {
  id: string
  routes?: RouteContribution[]          // relative paths, mounted under module base
  nav?: NavContribution[]               // group, label key, icon, permission
  fieldRenderers?: Record<FieldType, FieldRenderer>       // gis: GEOMETRY
  pageComponents?: Record<string, PageComponentRenderer>  // gis: MAP, workflow: WORKFLOW
  recordPanels?, historyRenderers?      // documents: issued-document link (breaks history↔documents)
  i18n?: Record<Lang, Resources>        // own namespace per module
}
// App code
<ChawpiApp config={{ apiBaseUrl: '/api', appName: 'My App' }}
           modules={[gisModule(), documentsModule(), workflowModule()]} />
```
- Hardcoded URLs → `useChawpiLinks()` (route builder from registry).
- Cycles removed: auth moves to core; page-renderer resolves components through registry;
  `DynamicForm` resolves field renderers through registry (no static MapLibre import); lazy routes.
- Unused deps (`@dnd-kit/sortable`, `@dnd-kit/modifiers`) dropped.
- Consumer Tailwind: `@import "@chawpi/ui/theme.css"` + `@source "../node_modules/@chawpi"`; documented.

## Commits, versioning and releases
- **Conventional Commits enforced**: local `commit-msg` hook (husky + commitlint
  `@commitlint/config-conventional`, scopes free); CI `commitlint` job on every PR commit range and a
  PR-title check (`amannn/action-semantic-pull-request`).
- **Versioning**: `release-please` (manifest mode, one lockstep version for every Maven and npm
  library, aligned by `chawpi-bom`). It keeps a release PR with bumped versions (`gradle.properties`
  `version=`, every `frontend/packages/*/package.json`) and `CHANGELOG.md`; merging it tags `vX.Y.Z`
  and creates the GitHub Release.
- **Publishing**: `publish.yml` runs on `release: published` and publishes every Maven artifact
  (`./gradlew publish -Pversion=<tag>`) and every `@chawpi/*` npm package to GitHub Packages.
  Examples, `chawpi-integration-tests` and `@chawpi/*` private tooling are never published.

## Tests
- Unit tests stay in each module (backend JUnit/Mockito, frontend vitest) — all sapgis tests ported.
- `chawpi-test`: `ChawpiIntegrationTest` base (Testcontainers `postgis/postgis:18-3.6` or plain
  `postgres:18` for core-only, external DB via `CHAWPI_TEST_DB_*`), login helpers — published so Apps test
  themselves.
- `chawpi-integration-tests`: the 20 sapgis API ITs against a full test app + a core-only app
  (asserts GIS routes absent, core works without PostGIS) + module-matrix smoke tests.
- ArchUnit-style check (Konsist or plain test) that `chawpi-core` never imports module packages.
- Frontend: vitest per package; `@chawpi/testing` shared; examples build in CI.

## Docs & preservation
- Copy ADR 0001–0023 verbatim + header note "Imported from sapgis; names updated to chawpi" and
  `sapgis`→`chawpi` identifier renames; mark superseded parts via new ADRs rather than editing decisions.
- New ADRs: 0024 libraries-and-starters, 0025 extension SPIs, 0026 per-module migrations,
  0027 GIS optional, 0028 frontend module registry, 0029 polyglot monorepo & publishing,
  0030 rebrand sapgis→chawpi.
- HISTORY.md, docs/superpowers/{specs,plans}, domain/api/gis/security/dev docs copied and updated
  (fix stale geometry section in metadata-model.md and auth doc); new `docs/modules/<module>.md` per
  library (what it adds, config, SPIs, frontend package) + "build your app" guide.
- CLAUDE.md rewritten for the library project (rules preserved).

## Execution (superpowers + subagents)
1. After approval: write spec `docs/superpowers/specs/2026-09-25-chawpi-libraries-design.md` (this
   design), commit, user reviews.
2. `superpowers:writing-plans` → one plan per phase; execute each with
   `superpowers:subagent-driven-development` (fresh subagent per task, review between tasks), in an
   isolated worktree branch.
   - P0 copy sapgis content (no history), scaffold monorepo, build-logic, BOM, rename, docs/ADRs copy
   - P1 backend core + SPIs + migrations rebaseline + chawpi-test
   - P2 backend modules (views, forms, pages, workflow, automation, documents, gis, agent) + starters
   - P3 chawpi-integration-tests (all ITs green, core-only + full)
   - P4 frontend ui/core/testing + registry
   - P5 frontend module packages
   - P6 examples (simple, documents, gis/perene, full)
   - P7 CI + release (GitHub Packages), docs/modules, new ADRs, CLAUDE.md
3. `superpowers:verification-before-completion` + `requesting-code-review` at the end of each phase.

## Verification
- `./gradlew build` (ktlint + unit) and `./gradlew integrationTest` green; same REST contract:
  compare route list (`/actuator/mappings`) of full-sample vs sapgis; same final DB schema (pg_dump
  `--schema-only` diff sapgis vs full-sample, modulo schema/table rename).
- simple-sample boots on plain `postgres:18` with no GIS routes.
- `yarn workspaces run lint|test|build` green; each example web builds; Playwright smoke on
  full-sample (login, create object with GEOMETRY field, record + map, issue document, workflow
  transition) mirroring sapgis manual flows.
- `publishToMavenLocal` + `npm pack` consumed by an example outside the workspace.
