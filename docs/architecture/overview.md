# Architecture

## Shape

An app is its own Spring Boot main class plus the wasichai starters it chooses, and its own React entry point plus
the `@wasichai/*` packages behind those starters. Wasichai ships nothing runnable by itself.

```
                 React app (WasichaiApp + registered modules)
                              │  REST /api + GeoJSON
              Spring Boot 4.1 WebFlux app (wasichai starters: core + chosen modules)
                              │  R2DBC (reactive)
                    PostgreSQL 18 (+ PostGIS only with wasichai-gis)
                              │
                     GeoServer (only with wasichai-gis)  →  WMS / WFS / WMTS
```

One deployable per app: still a modular monolith ([ADR-001](../adr/0001-modular-monolith.md), amended by
[ADR-024](../adr/0024-libraries-and-starters.md)).

## Libraries and the module graph

The backend graph is acyclic:

```
core  <-  views, forms, workflow, automation, documents, gis, agent
core  <-  forms  <-  pages
automation  ->  documents, only through the optional DocumentIssuer port (documents implements it)
```

| Module | Maven artifact | Starter | npm package | Doc |
|---|---|---|---|---|
| core | `wasichai-core` | `wasichai-spring-boot-starter` | `@wasichai/core` (+ `@wasichai/ui`) | [core](../modules/core.md) |
| views | `wasichai-views` | `wasichai-spring-boot-starter-views` | `@wasichai/views` | [views](../modules/views.md) |
| forms | `wasichai-forms` | `wasichai-spring-boot-starter-forms` | `@wasichai/forms` | [forms](../modules/forms.md) |
| pages | `wasichai-pages` | `wasichai-spring-boot-starter-pages` | `@wasichai/pages` | [pages](../modules/pages.md) |
| workflow | `wasichai-workflow` | `wasichai-spring-boot-starter-workflow` | `@wasichai/workflow` | [workflow](../modules/workflow.md) |
| automation | `wasichai-automation` | `wasichai-spring-boot-starter-automation` | `@wasichai/automation` | [automation](../modules/automation.md) |
| documents | `wasichai-documents` | `wasichai-spring-boot-starter-documents` | `@wasichai/documents` | [documents](../modules/documents.md) |
| gis | `wasichai-gis` | `wasichai-spring-boot-starter-gis` | `@wasichai/gis` | [gis](../modules/gis.md) |
| agent | `wasichai-agent` | `wasichai-spring-boot-starter-agent` | `@wasichai/agent` | [agent](../modules/agent.md) |
| testing | `wasichai-test` | — | `@wasichai/testing` | [testing](../modules/testing.md) |

`pages` also depends on `forms`; `gis`, `workflow` and `agent` compile against `pages` only optionally
(`compileOnly`), to register a page component or record transitions when pages is present. `wasichai-core` depends on
no module: `CoreArchitectureTest` fails the build the moment core imports a module package or a module's Gradle
coordinate. See [ADR-024](../adr/0024-libraries-and-starters.md).

## Core packages

| Package (`wasichai.core.<area>`) | Responsibility |
|---|---|
| `common` | RFC 7807 errors, paging, health |
| `platform` | `WasichaiSchemas`, `SqlIdentifier`, Flyway runner (`WasichaiMigrations`), `ModuleMigration`, `SystemColumns`, database and JWT properties |
| `identity` | Users, roles, login, JWT issuing, tenant resolution from the token |
| `metadata` | Custom Objects, Custom Fields, relationships, `FieldTypeRegistry`, `ObjectSchemaManager` |
| `audit` | Append-only audit log and its query service |
| `data` | `RecordStore` port, `PhysicalTableRecordStore`, the dynamic record and relationship APIs |
| `admin` | User and role administration |
| `organization` | Organizations, the tenant |
| `autoconfigure` | `@AutoConfiguration` classes that wire every bean above |

`CoreArchitectureTest` enforces the layering as a DAG, `platform` at the bottom: `common` ← `platform` ← `identity`
← `metadata` ← `audit` ← `data`, with `admin` and `organization` standing on `metadata` and `autoconfigure` on top
of all of them. A package may only import the packages below it in this order; the test fails the build otherwise.

## Extension SPIs

| SPI | Lives in | Pattern | Implemented by |
|---|---|---|---|
| `FieldTypeHandler` + `FieldTypeRegistry` | `wasichai-core` (`metadata`) | Strategy + Registry | core's 12 scalar types; `GEOMETRY` from wasichai-gis |
| `RecordQueryContributor` + `RecordCriterion` | `wasichai-core` (`data`) | Strategy | modules that narrow the record query (list) |
| `SystemColumnContributor` → `SystemColumns` | `wasichai-core` (`platform`) | Registry | modules that add a reserved column name (list) |
| `RecordChangeListener` | `wasichai-core` (`data`) | Observer | modules that react to a record write (list) |
| `ObjectRemovalListener`, `FieldUsage` | `wasichai-core` (`metadata`) | Observer / Chain | modules that store something about an object or field (list) |
| `WorkflowStates` | `wasichai-core` (`data`) | Null Object | `NoWorkflowStates` (core default); wasichai-workflow's real implementation |
| `ModuleMigration` | `wasichai-core` (`platform`) | Registry | every module, one entry each, plus core's own and its dev seed |
| `PageComponentProvider` | `wasichai-pages` | Strategy | wasichai-pages itself (the HISTORY component); other modules that add a page component |
| `DocumentIssuer` | `wasichai-automation` | Port | `NoDocumentIssuer` (automation default); `DocumentIssuerAdapter` in wasichai-documents |

Listener and contributor lists run in `@Order`, synchronously, inside the caller's own call: `RecordService` opens
no transaction of its own, so a listener that needs atomicity opens one itself. An empty list means no module
installed, not a null pointer. See [ADR-025](../adr/0025-extension-spis.md). `RecordService`, its audit row and the
listeners join the caller's transaction, so an app wraps several calls in `TransactionalOperator.executeAndAwait { }`
and they commit or roll back together: [ADR-038](../adr/0038-record-service-joins-the-callers-transaction.md).

## Frontend packages

`@wasichai/ui` (Tailwind primitives), `@wasichai/core` (the app shell, `WasichaiApp`, the registry, everything that works
with no module installed), one package per backend module (`@wasichai/views`, `forms`, `pages`, `workflow`,
`automation`, `documents`, `gis`, `agent`) and `@wasichai/testing`, all in
[wasichai-ui](https://github.com/wasichai/wasichai-ui).

A module is a `WasichaiModule` value, usually built by a factory (`gisModule({ workerUrl })`), passed to
`<WasichaiApp modules={[...]} />`. `createRegistry` merges the list once, at mount, into routes, nav groups and
items, field renderers, page components and actions, record panels, history renderers and i18n resources — core
never imports a module, it asks the registry for a slot. Heavy libraries (MapLibre, xyflow, tiptap, dnd-kit) stay
out of the initial bundle behind lazy routes. See [ADR-028](../adr/0028-frontend-module-registry.md).

## Two schemas, two lifecycles

- **Metadata schema** (`wasichai.database.metadata-schema`, default `wasichai`) — platform metadata and identity, plus
  every installed module's own tables. Evolved by one Flyway run per module, each with its own history table
  `flyway_history_<module>`, so a module can join an app later without touching another module's history
  ([ADR-026](../adr/0026-per-module-migrations.md)).
- **Data schema** (`wasichai.database.data-schema`, default `app_data`) — business data. One physical table per
  Custom Object, built at runtime by `ObjectSchemaManager` from the metadata
  ([ADR-004](../adr/0004-physical-table-per-object.md)).

## Request path

```
HTTP  →  controller  →  service (permission + tenant)  →  RecordStore  →  SQL
                                     │
                                     └→ audit_log
```

Every query filters by `organization_id`, resolved from the JWT and never from the request body. A field type's
handler contributes its own SQL fragment for reads and writes; wasichai-gis's `GEOMETRY` handler wraps the column in
`ST_AsGeoJSON(...)` on the way out and `ST_GeomFromGeoJSON(...)` on the way in, so the API always sees GeoJSON.

## Reactive persistence

WebFlux is reactive, so JPA, Hibernate and Envers are out. There is no ORM-style data access layer at all: every
query, fixed schema or dynamic, is SQL issued through `DatabaseClient` with bound values, never interpolated.
Schema names come from `WasichaiSchemas` (validated once at construction), and identifiers are validated and quoted
by `SqlIdentifier`. Migrations run through Flyway over a short-lived JDBC connection at startup
([ADR-008](../adr/0008-flyway-over-jdbc.md)), one run per module ([ADR-026](../adr/0026-per-module-migrations.md)).
Geometry is converted in SQL by wasichai-gis's field type handler, because R2DBC has no PostGIS codec
([ADR-007](../adr/0007-geometry-over-r2dbc.md)).

## Security chain

Core contributes one `SecurityWebFilterChain` at `@Order(0)`, because Spring Boot's reactive resource-server
auto-configuration always adds a chain of its own regardless of `@ConditionalOnMissingBean`. A module or app that
needs its own chain (its own `securityMatcher`) declares one with `@Order` below `0`, so it runs first. See
[core module — security](../modules/core.md#security) and
[../security/authentication.md](../security/authentication.md).

## Decisions

Every decision is an ADR: [../adr/README.md](../adr/README.md). This page leans on:

- [ADR-001](../adr/0001-modular-monolith.md) — modular monolith
- [ADR-004](../adr/0004-physical-table-per-object.md) — one physical table per Custom Object
- [ADR-007](../adr/0007-geometry-over-r2dbc.md) — geometry over R2DBC as GeoJSON in SQL
- [ADR-008](../adr/0008-flyway-over-jdbc.md) — Flyway over a short-lived JDBC DataSource
- [ADR-024](../adr/0024-libraries-and-starters.md) — libraries, starters, a BOM and explicit auto-configuration
- [ADR-025](../adr/0025-extension-spis.md) — the core is extended through SPIs, never by knowing its modules
- [ADR-026](../adr/0026-per-module-migrations.md) — each module owns its migrations and its Flyway history
- [ADR-027](../adr/0027-gis-optional.md) — GIS is optional, and the API is unchanged when it is present
- [ADR-028](../adr/0028-frontend-module-registry.md) — frontend modules plug into a registry
- [ADR-029](../adr/0029-polyglot-monorepo-and-publishing.md) — polyglot monorepo and publishing
- [ADR-032](../adr/0032-rebrand-to-wasichai-and-split-repositories.md) — rebrand to wasichai, split into two repositories
