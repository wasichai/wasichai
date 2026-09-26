# Development

## Requirements

Java 25; Node 26 and Yarn 1 for the commit hooks and prettier; a docker daemon for the Testcontainers-backed
integration tests (or an external database, see below). The Gradle wrapper pins Gradle 9.7.1.

## Layout

- repo root — `build-logic` (convention plugins), `wasichai-*` (core and module libraries), `starters/`
  (`wasichai-spring-boot-starter-*`, one per module plus the base starter), `wasichai-bom`, `wasichai-test`
  (shared test support), `wasichai-integration-tests` (the original app's API tests, run against assembled
  test apps).
- The npm packages live in [wasichai-ui](https://github.com/wasichai/wasichai-ui) (see its
  [docs/README.md](https://github.com/wasichai/wasichai-ui/blob/main/docs/README.md)); the sample apps and the
  local infrastructure in repositories of their own (next section).
- `docs/` — architecture, ADRs, module guides, this guide.

Wasichai is libraries, not an app: `wasichai-core` and the module starters ship no `main` class of their
own. See [../architecture/overview.md](../architecture/overview.md) for how the pieces fit together.

## Run a sample app

Wasichai has no app of its own to run. Each sample is a repository of its own, server and web together:
[simple-sample](https://github.com/wasichai/simple-sample) (core alone, on plain PostgreSQL),
[documents-sample](https://github.com/wasichai/documents-sample) (documents and automation),
[gis-sample](https://github.com/wasichai/gis-sample) (gis, with the Perené cadastre model) and
[full-sample](https://github.com/wasichai/full-sample) (every module, and the Playwright e2e). Cloned next to
this repository, a sample builds against this checkout instead of the published libraries (a Gradle composite
build; its README has the details), so a change here can be tried in an app before it is released.

What an app needs, however you provide it:

- PostgreSQL 18. With `wasichai-gis`, PostGIS 3.6 in the same database; pgvector is optional.
- GeoServer, optional: `wasichai-gis` publishes layers to it (`wasichai.gis.geoserver.*`, see
  [../modules/gis.md](../modules/gis.md)).

[wasichai-infrastructure](https://github.com/wasichai/wasichai-infrastructure) is one ready-made way to run them
locally; nothing in this repository depends on it.

An app boots without writing any YAML: `WASICHAI_DB_HOST`, `_PORT`, `_NAME`, `_USERNAME` and `_PASSWORD`
default to `localhost` / `5432` / `wasichai` / `wasichai` / `wasichai`. `WASICHAI_JWT_SECRET` has no default —
set it yourself, at least 32 bytes — because a usable out-of-the-box signing key would be a security
hole shipped by the library (see [Configuration](#configuration)).

To assemble the starters into an app of your own, rather than run a sample, see
[../guides/build-your-app.md](../guides/build-your-app.md).

## Configuration

Every app gets these defaults from `WasichaiEnvironmentPostProcessor`, without writing any YAML:

| Variable | Default | Meaning |
|---|---|---|
| `WASICHAI_DB_HOST` | `localhost` | Postgres host |
| `WASICHAI_DB_PORT` | `5432` | Postgres port |
| `WASICHAI_DB_NAME` | `wasichai` | Database name |
| `WASICHAI_DB_USERNAME` | `wasichai` | Database user |
| `WASICHAI_DB_PASSWORD` | `wasichai` | Database password |
| `WASICHAI_JWT_SECRET` | none (required) | JWT signing key, ≥ 32 bytes |

A module adds its own properties (`wasichai.<module>.*`) with its own defaults — see the module's page
under `../modules/*.md`, for example [../modules/gis.md](../modules/gis.md).

## Local secrets

API keys (`ANTHROPIC_API_KEY` for the assistant) come from the environment of the process that runs the app,
never from a file in this repository. wasichai-infrastructure keeps machine-local secrets in a git-ignored
`.local/` directory for that.

Without `ANTHROPIC_API_KEY` the assistant reports itself unavailable and every other part of Wasichai
works unchanged — that is deliberate, and `EmbabelGate` exists to keep it true (the agent framework
refuses to start without a model).

## Tests

```bash
./gradlew build   # ktlint + unit tests + architecture tests, no docker needed
```

## Integration tests

Integration tests are tagged `integration` and excluded from `build`, so a machine without a usable
docker daemon still gets a green build.

**1. Default: Testcontainers.** `./gradlew integrationTest` runs every suite. Core-only suites start a
`postgres:18` container; suites that need PostGIS start `postgis/postgis:18-3.6` instead (each suite
sets its own `wasichai.test.db.image` system property). This needs a local docker daemon. CI runs it on
GitHub-hosted runners, where this is the only mode.

**2. Remote docker daemon: external-database mode.** When `DOCKER_HOST` points at a remote daemon,
published container ports are not on `localhost`, so Testcontainers cannot reach them. Start the test
databases on that host yourself, tunnel their ports to your machine, and point the suites at the
tunnel instead of at Testcontainers:

```bash
nc -z localhost 5443 && echo plain db reachable
nc -z localhost 5442 && echo postgis db reachable
```

| Variable | Meaning |
|---|---|
| `WASICHAI_TEST_DB_HOST` | Tunnel host, e.g. `localhost`. Setting this switches every suite to external-database mode. |
| `WASICHAI_TEST_DB_PORT` | Tunnelled port of the plain Postgres database. |
| `WASICHAI_TEST_DB_NAME` | Database name. Must end in `_test` — the suite refuses to wipe anything else. |
| `WASICHAI_TEST_DB_USERNAME` | Database user. |
| `WASICHAI_TEST_DB_PASSWORD` | Database password. |
| `WASICHAI_TEST_GIS_DB_PORT` | Tunnelled port of the PostGIS database, for suites that need PostGIS. Name, user and password are shared. |

Once `WASICHAI_TEST_DB_HOST` is set, the other four `WASICHAI_TEST_DB_*` variables are required — there is
no silent default for a wipe target. Add `--rerun` when replaying a suite, because Gradle's build cache
could otherwise hand back a stale green result instead of hitting the external database again:

```bash
./gradlew :wasichai-integration-tests:coreOnly --rerun
```

**3. `it-env.sh`: your own local helper.** `wasichai-integration-tests/it-env.sh` is git-ignored
— it holds machine-specific tunnel coordinates and is never committed. Create your own copy next to it
(same filename, since `.gitignore` already excludes it) with the six variables above:

```bash
#!/usr/bin/env bash
# wasichai-integration-tests/it-env.sh — local only, never committed.
export WASICHAI_TEST_DB_HOST=localhost
export WASICHAI_TEST_DB_PORT=5443
export WASICHAI_TEST_DB_NAME=wasichai_test
export WASICHAI_TEST_DB_USERNAME=wasichai
export WASICHAI_TEST_DB_PASSWORD=changeme
export WASICHAI_TEST_GIS_DB_PORT=5442
```

Source it, never execute it, so the variables land in your current shell:

```bash
source wasichai-integration-tests/it-env.sh
```

**4. Suite lock.** Each JVM takes a session advisory lock on the external database before its one-time
wipe and holds it until it exits, so two suites against the same database run one after the other
instead of wiping each other mid-run. The plain database and the PostGIS database have separate locks,
so a plain suite and a PostGIS suite may still run at the same time. Still, start one suite at a time
per database when running by hand — parallel Gradle invocations against the same database just queue
on the lock rather than run concurrently.

**5. Leftover tables.** A persistent external database accumulates physical tables that the wipe never
sees mid-run — the wipe runs once per JVM, at the first suite that touches that database. If a run is
killed mid-wipe, the old manual recipe still applies:

```bash
psql "postgresql://$WASICHAI_TEST_DB_USERNAME:$WASICHAI_TEST_DB_PASSWORD@$WASICHAI_TEST_DB_HOST:$WASICHAI_TEST_DB_PORT/postgres" \
  -c "DROP DATABASE IF EXISTS $WASICHAI_TEST_DB_NAME;" -c "CREATE DATABASE $WASICHAI_TEST_DB_NAME OWNER $WASICHAI_TEST_DB_USERNAME;"
```

**6. Commands.**

```bash
./gradlew integrationTest                              # every suite, Testcontainers or external db
./gradlew :wasichai-integration-tests:coreOnly            # one module-slice suite, core only
./gradlew :wasichai-integration-tests:gisOnly             # one module-slice suite, needs PostGIS
./gradlew :wasichai-integration-tests:fullApp             # every starter assembled together
```

The full suite list: slices `coreOnly`, `viewsOnly`, `formsOnly`, `pagesOnly`, `workflowOnly`,
`automationOnly`, `documentsOnly`, `gisOnly`, `agentOnly`; and full-app suites `fullApp`, `workflowIt`,
`automationIt`, `documentsIt`, `pagesIt`, `viewsFormsIt`, `layersIt`, `agentIt`, `coreParityIt`,
`wireParityIt`, `schemaParityIt`.

## Conventions

- Code, identifiers and comments in English; comments are caveman style — short, say why.
- Formatting follows `.editorconfig` everywhere (Kotlin 4 spaces, TS/YAML/MD 2 spaces, max 160 columns),
  enforced by `./gradlew ktlintFormat` for Kotlin; `yarn format` (prettier) covers the YAML and JSON here and
  the frontend code in wasichai-ui.
- Commits follow Conventional Commits, enforced by a commitlint hook and in CI.
- Architectural decisions go in `docs/adr/` (see [../adr/README.md](../adr/README.md)); change history
  in `docs/HISTORY.md`.

## Adding a field type

1. A `FieldTypeHandler` bean, declared in the module's own auto-configuration: the column type,
   validation, and the select/bind SQL, plus an optional payload section. Core never adds a `SELECT`
   alias for a rewritten column — a handler that rewrites the select (gis's `ST_AsGeoJSON`) appends its
   own `AS <quoted read name>`.
2. Frontend: a `fieldRenderers` entry in the module's `WasichaiModule` — input, display and settings for
   the type (in wasichai-ui).
3. Tests: the handler's own unit test, plus an integration test that exercises it through the API.

No object-specific code anywhere. See [ADR-025](../adr/0025-extension-spis.md) for the SPI and
[ADR-028](../adr/0028-frontend-module-registry.md) for the frontend registry.

## Releasing

See [releasing.md](releasing.md) for the release flow and one-time repository setup.
