# Wasichai — metadata-driven application platform, as libraries (backend + docs)

Reusable Spring Boot starters; an app adds the core and opts into modules, and metadata drives schema, API and UI at
runtime. The React packages are in the sibling repository `wasichai-ui` (`../wasichai-ui`); its decisions live here too.
The sample apps are sibling repositories (`../simple-sample`, `../documents-sample`, `../gis-sample`,
`../full-sample`); cloned next to this one they build against it (a Gradle composite build).

## Non-negotiable stack

- **Backend**: Kotlin 2.4.20, Spring Boot 4.1 **WebFlux** (reactive), Gradle 9.7.1 (Kotlin DSL, version catalog,
  convention plugins in `build-logic`), JDK 25
- **Database**: PostgreSQL 18 (+ PostGIS only with `wasichai-gis`, + pgvector optional)
- **GIS module**: GeoServer (WMS/WFS/WMTS)
- **Infra**: none in this repository (ADR-033); local databases live in the sibling `wasichai-infrastructure`
- Frontend (in wasichai-ui): React 19.3, Vite 8, Tailwind CSS 4, MapLibre GL JS

## Architectural rules

1. **Metadata-driven**: never generate code per Custom Object. Metadata drives behaviour at runtime.
2. **WebFlux is reactive** ⇒ JPA / Hibernate / Envers are forbidden, and so are Spring Data repositories. Every
   query, fixed schema or dynamic, is SQL through `DatabaseClient` with bound values. Schema names come from
   `WasichaiSchemas` (`${schemas.metadata}.<table>`, `schemas.dataTable(<table>)`), never a literal.
3. **Libraries, not an app**: `wasichai-core` never depends on a module (`CoreArchitectureTest`). Modules extend the
   core only through its SPIs (field types, query contributors, listeners, page components, ports). Every module
   ships its own auto-configuration, `wasichai.<module>.enabled` switch, properties and Flyway migrations (ADR-024,
   ADR-025, ADR-026).
4. **No component scanning of library code**: beans are declared in auto-configurations, with
   `@ConditionalOnMissingBean` where an app may override them. Stereotypes stay on library classes: kotlin-spring
   opens only annotated classes, so `@Transactional` proxies need them (ADR-024).
5. **Multi-tenancy** by `organization_id`; every query filters by the tenant resolved from the JWT.
6. **Dynamic DDL only through `ObjectSchemaManager`**. Identifiers validated and quoted by `SqlIdentifier`. Values
   always bound, never interpolated.
7. **Geometry is a field, and only with `wasichai-gis`**: PostGIS columns with their SRID, GeoJSON in EPSG:4326 over
   the API. Core runs on plain PostgreSQL.
8. **The REST API is the contract with wasichai-ui** (ADR-032). A change to it is backend first, then the UI; the
   full-sample e2e (repository `full-sample`) is where a mismatch shows.
9. **Same behaviour as the original app**, except the entries of ADR-031. A new difference needs a new entry.
10. **No overengineering**: an abstraction needs a concrete second user.

## Working rules

- **`.editorconfig` is law** for every file (Kotlin 4 spaces, YAML/MD 2, max 160 columns, LF, final newline).
  Format before finishing: `./gradlew ktlintFormat` for Kotlin, `yarn format` for YAML/JSON. Markdown is
  hand-formatted to the same rules.
- **Conventional Commits** for every commit message and PR title (`feat(core): …`, `fix(gis): …`, `docs: …`).
  Enforced by the commitlint hook and CI. Scopes are free; use the module name.
- Code, identifiers and comments in **English**. Comments **caveman style**: short, say why.
- Change history lives in `docs/HISTORY.md` (newest first), decisions in `docs/adr/` ([index](docs/adr/README.md)),
  for both repositories. A decision is changed by a new ADR, never by editing an old one.
- Port 5432 is never used by a test; integration tests use Testcontainers or `WASICHAI_TEST_DB_*`. Nothing here
  assumes how a database or GeoServer is run (ADR-033).

## Commands

```bash
./gradlew build                 # ktlint + unit tests + architecture tests
./gradlew -p build-logic test   # convention plugin tests (TestKit)
./gradlew integrationTest       # Testcontainers, or an external DB via WASICHAI_TEST_DB_* (see below)
```

Integration tests against a remote docker daemon or an external database: read
[docs/development/getting-started.md](docs/development/getting-started.md#integration-tests) first (six variables,
suite lock, `it-env.sh`).

## Where things are

- Architecture: [docs/architecture/overview.md](docs/architecture/overview.md)
- Modules: [docs/modules/](docs/modules/README.md) · Build an app: [docs/guides/build-your-app.md](docs/guides/build-your-app.md)
- REST API: [docs/api/rest.md](docs/api/rest.md) · Security: [docs/security/authentication.md](docs/security/authentication.md)
- Releasing: [docs/development/releasing.md](docs/development/releasing.md)

## Definition of Done

Works · has tests · handles errors · is documented · does not break existing features · build passes · tests pass
· core stays module-agnostic · behaviour matches the original app or ADR-031 · formatted with `.editorconfig`.
