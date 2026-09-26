# Wasichai migration, Waves 2 and 3 (rebrand, docs, CI, final verification) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the move of chawpi into the two `wasichai` repositories: rename every chawpi identifier to wasichai
(backend and frontend), rewrite the docs for two repositories, give each repository its own CI and release
pipeline, and prove both repositories green end to end, including the cross-repo Playwright e2e.

**Architecture:** Wave 1 (T1, T2) already copied the code with the old names: the Gradle build sits at the root of
`/Users/jorge/IdeaProjects/wasichai` (projects still `chawpi-*`), the yarn workspace at the root of
`/Users/jorge/IdeaProjects/wasichai-ui` (scope still `@hneyra`). Wave 2 runs four tasks in parallel with disjoint
file ownership: T3 renames the backend with one ordered, idempotent script; T4 renames the frontend the same way;
T5 owns every Markdown doc that is not a package or project README; T6 owns `.github/**` and the release-please
files. Wave 3 (T7) verifies both repositories together and routes each defect back to the owning task.

**Tech Stack:** Kotlin 2.4.20, Spring Boot 4.1.1, Gradle 9.7.1 (build-logic convention plugins, TestKit), JDK 25,
PostgreSQL 18 / PostGIS 3.6 (test containers behind an ssh tunnel on 5442/5443), React 19.3, Vite 8, vitest, yarn
1.22 workspaces, Node 26 (`node --test` for tooling), Playwright, Python 3 (perene), GitHub Actions, release-please,
GitHub Packages (Maven + npm), bash + perl for the rename scripts.

**Spec:** `/Users/jorge/.claude/plans/eres-un-experto-arquitecto-adaptive-lovelace.md` (the approved plan: naming map,
allow-list, target layouts, Waves, Verification). Source of truth for the code: `/Users/jorge/IdeaProjects/chawpi`
(read-only). Ledger: `/Users/jorge/IdeaProjects/wasichai/.superpowers/sdd/2026-09-26-wasichai-migration/progress.md`
(git-ignored by `.superpowers/`).

## Global Constraints

Every task's requirements include this section.

- **No git commits, in any repository.** No `git add`, `git commit`, `git stash`, `git checkout -- <file>`, no push.
  "Done" means the files are on disk and the task's checks pass. Tasks have no commit step; they end with a report
  (per phase) instead. The user commits.
- **chawpi is read-only.** Never create, edit, delete or build anything under `/Users/jorge/IdeaProjects/chawpi`
  (no `./gradlew`, no `yarn` there: both write `build/`, `node_modules/`). Read with `cat`, `grep`, `sed -n`.
  `/Users/jorge/IdeaProjects/sapgis` is read-only too.
- **Port 5432 is never used.** No test, sample, psql or Playwright run connects to 5432, and no task starts
  `infra/docker/compose.yml` (its `postgres` service publishes 5432). Databases: the tunnelled test containers only,
  `5443` (plain PostgreSQL) and `5442` (PostGIS). Before any DB step run the tunnel script
  `bash /private/tmp/claude-502/-Users-jorge-IdeaProjects-chawpi/73f54845-6d0f-493f-961b-10662da123e9/scratchpad/tunnel.sh`
  (idempotent: prints `tunnel up` or `tunnel started`; `tunnel FAILED` = stop and report).
- **Gradle in parallel.** Several agents may run Gradle at the same time (different repos or builds). Never run
  `./gradlew --stop`, never kill a Gradle daemon or `java` process you did not start. Use `--no-daemon` only where a
  step says so.
- **Stay inside your ownership column** (section "File ownership"). A defect found in a file you do not own goes
  into your report as `route to T<n>: <path>: <problem>`, never into a fix.
- **Formatting follows `.editorconfig`** (both repos carry the same one): Kotlin 4 spaces, TS/JSON/YAML/MD 2 spaces
  (JSON files in the repos are 4-space, keep what the file has), max 160 columns, LF, final newline, no trailing
  whitespace. Kotlin: `./gradlew ktlintFormat` then `./gradlew ktlintCheck`. TS/JS/JSON/YAML/CSS: `yarn prettier --write
  <files>` then `yarn prettier --check <files>` (prettier config `.prettierrc.json`). Markdown is prettier-ignored and
  checked with the MD check below. Wrap doc prose at about 115 columns, like the existing docs.
- **MD check** (on every Markdown file a task creates or edits; prints nothing when clean; run from the repo root
  that holds the files):

  ```bash
  FILES="<the task's .md files>"
  awk 'length > 160 { print FILENAME ":" FNR ": over 160 columns" }' $FILES
  command grep -n ' $' $FILES | sed 's/$/  <- trailing space/'
  for f in $FILES; do [ -z "$(tail -c1 "$f")" ] || echo "$f: no final newline"; done
  node -e '
  const fs = require("fs"), path = require("path"); let bad = 0
  for (const f of process.argv.slice(1)) {
    const text = fs.readFileSync(f, "utf8").replace(/```[\s\S]*?```/g, "")
    for (const m of text.matchAll(/\]\(([^)\s#]+)(#[^)]*)?\)/g)) {
      const u = m[1]
      if (/^(https?:|mailto:)/.test(u)) continue
      if (!fs.existsSync(path.resolve(path.dirname(f), u))) { console.log(f + ": broken link " + u); bad++ }
    }
  }
  process.exit(bad ? 1 : 0)' $FILES
  ```

- **Links.** Inside one repository: relative links. Across repositories: absolute GitHub URLs, exactly
  `https://github.com/wasichai/wasichai-ui/tree/main/packages/<pkg>` (a frontend package),
  `https://github.com/wasichai/wasichai-ui/tree/main/<path>` (other UI paths),
  `https://github.com/wasichai/wasichai/blob/main/docs/<path>.md` (a backend-repo doc) and
  `https://github.com/wasichai/wasichai/tree/main/<path>` (a backend-repo folder).
- **Secrets never printed.** The tunnel password lives only in the git-ignored `it-env.sh`. Scripts read it into a
  variable; no step echoes it, and reports show `***`.
- **Tests counts are the bar.** wasichai: `./gradlew build` green, `integrationTest` 410 tests (94 core + 300 IT
  suites + 16 samples), 0 failures, schema parity 464 facts, route parity 95 = 95, perene 36 tests. wasichai-ui:
  677 package/web tests, 22 tooling tests, 11 public packages, 20 Maven artifacts on the backend side. A lower count
  is a failure even when everything that ran is green.

## Naming map

Single source of truth, copied from the spec and extended with what the inventory found. Left column as it is
after Wave 1, right column after Wave 2.

| After Wave 1 (chawpi names) | After Wave 2 (wasichai) | Owner |
|---|---|---|
| Kotlin root package `chawpi.*`, dirs `src/<set>/kotlin/chawpi/…` (every source set: main, test, testFixtures, the 21 IT suites, examples, build-logic test `chawpi.buildlogic`) | `wasichai.*`, `src/<set>/kotlin/wasichai/…`, `wasichai.buildlogic` | T3 |
| Gradle group `chawpi`, root project `chawpi`, projects `chawpi-{agent,automation,bom,core,documents,forms,gis,integration-tests,pages,test,views,workflow}` | `wasichai`, `wasichai`, `wasichai-{…}` | T3 |
| `starters/chawpi-spring-boot-starter[-agent,-automation,-documents,-forms,-gis,-pages,-views,-workflow]` | `starters/wasichai-spring-boot-starter[-…]` | T3 |
| plugin ids and files `build-logic/src/main/kotlin/chawpi.{kotlin-library,spring-module,publishing,integration-test,sample-app}.gradle.kts` | `wasichai.{…}.gradle.kts` | T3 |
| types `Chawpi*` (`@ChawpiApplication`, `ChawpiException`, `ChawpiSchemas`, `ChawpiJwtKey`, `ChawpiIntegrationTest`, `ChawpiContextRunner`, `ChawpiTestDatabase`, `Chawpi*AutoConfiguration`, `Chawpi*Properties`, `ChawpiMigrations`, `ChawpiEnvironmentPostProcessor`, `ChawpiAgent`), their files, `handleChawpi`, `chawpiJwtKey`, `chawpi*Migration` beans, `chawpiModules` | `Wasichai*`, `handleWasichai`, `wasichaiJwtKey`, `wasichai*Migration`, `wasichaiModules` | T3 |
| properties `chawpi.*` (e.g. `chawpi.database.*`, `chawpi.seed.dev`, `chawpi.web.problem-base-uri`, `chawpi.<module>.enabled`, `chawpi.test.db.image`) | `wasichai.*` | T3 |
| env `CHAWPI_*` (`CHAWPI_DB_{HOST,PORT,NAME,USERNAME,PASSWORD}`, `CHAWPI_SEED_DEV`, `CHAWPI_JWT_SECRET`, `CHAWPI_AUTOMATION_POLL`, `CHAWPI_GEOSERVER_{ENABLED,URL}`, `CHAWPI_AGENT_API_KEY`, `CHAWPI_AGENT_APIKEY`, `CHAWPI_PG_PORT`, `CHAWPI_TEST_DB_{HOST,PORT,NAME,USERNAME,PASSWORD,IMAGE}`, `CHAWPI_TEST_GIS_DB_PORT`, perene `CHAWPI_{CORE,EMAIL,PASSWORD}`) | `WASICHAI_*` (same suffixes) | T3 (backend), T6 (workflows) |
| migrations `src/main/resources/db/chawpi/<module>`, `classpath:db/chawpi/<module>`, core test `db/chawpi/measure-test` | `db/wasichai/<module>` | T3 |
| metadata schema default `chawpi` (`ChawpiDatabaseProperties.metadataSchema`, SQL `chawpi.<table>` in tests, `infra/docker/postgres/init/01-extensions.sql`) | `wasichai` | T3 |
| DB defaults: name/user/password `chawpi` (`ChawpiDatabaseProperties`, `GeoServerProperties`, samples' `${CHAWPI_DB_USERNAME:chawpi}`), Testcontainers db/user/password `chawpi` | `wasichai` | T3 |
| demo DB names `chawpi` (simple default), `chawpi_documents`, `chawpi_gis`, `chawpi_full` | `wasichai`, `wasichai_documents`, `wasichai_gis`, `wasichai_full`; on the tunnel also `wasichai_simple` (5443) | T3 |
| seed `admin@chawpi.local`, test users `*@chawpi.local`, `*@chawpi.test` | `admin@wasichai.local`, `*@wasichai.local`, `*@wasichai.test` | T3, T4 |
| JWT issuer default `chawpi`, `spring.application.name` fallback `chawpi`, problem base URI `https://chawpi.dev/problems` | `wasichai`, `wasichai`, `https://wasichai.dev/problems` | T3 |
| GeoServer workspace `chawpi`, datastore `chawpi-postgis`; compose project/containers/image `chawpi`, `chawpi-postgres`, `chawpi-postgres-plain`, `chawpi-geoserver`, `chawpi/postgres:18-postgis-pgvector` | `wasichai`, `wasichai-postgis`, `wasichai`, `wasichai-postgres`, `wasichai-postgres-plain`, `wasichai-geoserver`, `wasichai/postgres:18-postgis-pgvector` | T3 |
| advisory locks `chawpi-test-suite`, `chawpi-test-wipe`; log prefix `chawpi-test:` | `wasichai-test-suite`, `wasichai-test-wipe`, `wasichai-test:` | T3 |
| Maven coordinates `chawpi:chawpi-*`; Maven repo fallback `hneyra/chawpi` in `chawpi.publishing` | `wasichai:wasichai-*`; `wasichai/wasichai` (`maven.pkg.github.com/wasichai/wasichai`) | T3 |
| npm scope `@hneyra/*` (and any stray `@chawpi/*`), `.npmrc` `@hneyra:registry=…`, Tailwind `@source '…/node_modules/@hneyra'` | `@wasichai/*`, `@wasichai:registry=https://npm.pkg.github.com`, `…/node_modules/@wasichai` | T4 |
| TS types/functions `ChawpiApp`, `ChawpiAppProps`, `ChawpiProviders[Props]`, `ChawpiRoutes`, `ChawpiModule`, `ChawpiRegistry`, `ChawpiRouteMap`, `ChawpiLinks`, `ChawpiConfig`, `ChawpiContext[Value]`, `ChawpiRender{Options,Result}`, `ChawpiI18nOptions`, `createChawpiI18n`, `useChawpi`, `useChawpiConfig`, `useChawpiLinks`; files `packages/core/src/app/Chawpi*.tsx` | `Wasichai*`, `createWasichaiI18n`, `useWasichai*`; files `Wasichai*.tsx` | T4 |
| `storagePrefix: 'chawpi'`, errors `chawpi: …`, `chawpi links: …`, map ids `chawpi-features`, `chawpi-wms-`, pack tmp `chawpi-pack-`, UI brand "Chawpi" (`app.name` in `packages/core/src/i18n/locales/{en,es}/common.json`, package descriptions) | `'wasichai'`, `wasichai: …`, `wasichai links: …`, `wasichai-features`, `wasichai-wms-`, `wasichai-pack-`, "Wasichai" | T4 |
| web env `CHAWPI_API_URL`, Playwright comment `CHAWPI_DB_PORT` / `CHAWPI_DB_NAME` | `WASICHAI_API_URL`, `WASICHAI_DB_PORT` / `WASICHAI_DB_NAME` | T4 |
| release-please `package-name: chawpi`, one lockstep config for both ecosystems | wasichai: `simple`, `package-name: wasichai`, bumps `gradle.properties`; wasichai-ui: `node`, `package-name: wasichai-ui`, bumps root + 11 public `packages/*/package.json` | T6 |

**Kept as-is (never renamed):** Flyway history tables `flyway_history_<module>`; the tunnel test DB itself (db
`chawpi_test`, user `chawpi`, password, containers `chawpi-test-postgis` / `chawpi-test-plain` on the VPS) — only the
env var NAMES change, the values stay in the git-ignored `it-env.sh`; every `sapgis` / `SAPGIS_*` reference (the
parity generators' `SAPGIS_MIGRATIONS`, `SAPGIS_SRC`, `search_path TO sapgis`, the `port-from-sapgis` tool, the
`TSRC` path in `it-env.sh`); the file names `docs/adr/0030-rebrand-sapgis-to-chawpi.md`,
`docs/sapgis-origin.md` and `docs/superpowers/specs/2026-09-25-chawpi-libraries-design.md`.

## Allow-list (where "chawpi" / "Chawpi" / "CHAWPI" may remain)

A hit of `chawpi|Chawpi|CHAWPI` outside this list, or ANY hit of `hneyra` (case-insensitive) in either repository
outside `docs/superpowers/**`, `docs/HISTORY.md` past entries and the three history docs below, is a leftover.

wasichai:

1. `docs/superpowers/**` (verbatim history, including this plan).
2. `docs/HISTORY.md`: every entry below the new top entry stays verbatim; the new top entry names chawpi on purpose.
3. `docs/chawpi-origin.md` (new, T5) and `docs/adr/0032-rebrand-to-wasichai-and-split-repositories.md` (new, T5).
4. ADR provenance lines: `> Imported from sapgis … sapgis → chawpi …` (ADRs 0001–0023, unchanged) and the new
   `> Moved from chawpi …` line (T5 adds it to every ADR it changes).
5. **Extension of the spec's list, recorded in ADR-032** (history documents, renaming them would rewrite what
   happened): `docs/adr/0030-rebrand-sapgis-to-chawpi.md` (body verbatim), the `## Addendum (2026-09-25)` section of
   `docs/adr/0029-polyglot-monorepo-and-publishing.md` (verbatim, it is the `@hneyra` episode), and
   `docs/sapgis-origin.md` (verbatim below a new pointer line).
6. File-name references that must keep working: `rebrand-sapgis-to-chawpi`, `chawpi-libraries-design`,
   `chawpi-origin` (inside a link target or a file name).
7. The one `README.md` line that links `docs/chawpi-origin.md` (`Origin: [wasichai was chawpi until 2026-09-26](…)`).
8. Parity generator scripts
   (`wasichai-integration-tests/src/schemaParityIt/resources/schema-parity/generate-expected.sh`,
   `examples/full-sample/server/src/test/resources/route-parity/generate-expected.sh`): only their `sapgis`
   references are protected; after T3 they contain no `chawpi` token.
9. Git-ignored files are out of the grep by construction (the grep lists files with
   `git ls-files -co --exclude-standard`): `wasichai-integration-tests/it-env.sh` keeps `chawpi_test` / `chawpi`.

wasichai-ui:

1. `README.md`: only the `Origin:` line that links `chawpi-origin.md` (absolute URL into wasichai).
2. Nothing else. `tooling/port-from-sapgis*.mjs` keeps `sapgis`, not `chawpi`.

## File ownership (Wave 2 runs T3–T6 in parallel)

Every path in both repositories has exactly one Wave 2 owner. Read access is free everywhere.

| Path | Owner |
|---|---|
| **wasichai** `build-logic/**`, `chawpi-*/**` → `wasichai-*/**` (incl. `starters/**` project READMEs, e.g. `starters/*-agent/README.md`), `starters/**` | T3 |
| **wasichai** `examples/*/server/**`, `examples/gis-sample/perene/**` (incl. its `README.md`), `infra/**`, `gradle/**` | T3 |
| **wasichai** root `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`, `.prettierignore`, `wasichai-integration-tests/it-env.sh` (git-ignored) | T3 |
| **wasichai** tunnel demo databases `wasichai_simple` (5443), `wasichai_documents`, `wasichai_gis`, `wasichai_full` (5442) | T3 |
| **wasichai-ui** `packages/**` (incl. every `packages/*/README.md`), `tooling/**`, `examples/*/web/**` | T4 |
| **wasichai-ui** root `package.json`, `.npmrc`, `.gitignore`, `.prettierignore`, `yarn.lock`, `tsconfig.base.json`, `node_modules/` | T4 |
| **wasichai** `docs/**` (all living docs, ADRs, HISTORY, new ADR-032, `chawpi-origin.md`, `development/releasing.md` incl. its secrets section), `README.md`, `CLAUDE.md`, `examples/README.md`, `examples/*/README.md` | T5 |
| **wasichai-ui** `README.md`, `CLAUDE.md`, `docs/**` (new `docs/README.md` incl. its secrets section), `examples/README.md` (new) | T5 |
| **wasichai** `.github/**` (`workflows/{ci,commits,release-please,publish}.yml`, `scripts/check-maven-publications.sh`), `release-please-config.json`, `.release-please-manifest.json` | T6 |
| **wasichai-ui** `.github/**` (new), `release-please-config.json` (new), `.release-please-manifest.json` (new) | T6 |
| **both** `package.json`/`yarn.lock` of wasichai, `.husky/**`, `commitlint.config.mjs`, `.editorconfig`, `.prettierrc.json` | nobody (no change needed; a needed change is reported to the controller) |
| **both** `.superpowers/**` (ledger) | controller only |

Interfaces between the Wave 2 tasks (the names each task relies on from the others):

- T6's workflows call T3's names: projects `:full-sample-server`, `:<sample>-server`, env `WASICHAI_DB_*`,
  artifacts `wasichai-*` under Maven group dir `wasichai/`, `./gradlew -p build-logic test`.
- T6's UI workflows call T4's names: `tooling/check-release.mjs`, `tooling/set-version.mjs`, workspace
  `full-sample-web` script `e2e`, `WASICHAI_BACKEND_DIR` (already read by
  `examples/full-sample/web/playwright.config.ts`, default `../../../../wasichai`), npm scope `@wasichai`.
- T4's `tooling/check-release.mjs` reads T6's `wasichai-ui/release-please-config.json` (`packages["."]["extra-files"]`
  must list exactly the 11 public `packages/<pkg>/package.json`). T6 writes that file in its Step 1, first thing, so
  T4's last step can run against it.
- T5's docs describe T3/T4/T6 names exactly as in the naming map above, the secrets `RELEASE_PLEASE_TOKEN` and
  `WASICHAI_REPO_TOKEN`, and the workflow job names T6 defines (T6 Step 2/5 list them).

## Inventory (read from chawpi, classified)

Grep `chawpi|Chawpi|CHAWPI|hneyra` over the chawpi working tree (tracked + untracked, not ignored:
`git ls-files -co --exclude-standard`), 2026-09-26. Paths are chawpi's; the "Target" column is the Wave 1 path.

| chawpi path | Target | Files / hits | Classes found | Owner |
|---|---|---|---|---|
| `backend/**` | wasichai root (`wasichai-*/`, `starters/`, `build-logic/`) | 343 / 2295 | Kotlin package (`package chawpi.core…`, imports; ~1,500), types `Chawpi*` (40 files named `Chawpi*.kt`), property keys (`chawpi.database.*`, `@ConfigurationProperties("chawpi.…")`, `.imports` / `spring.factories`), env (`CHAWPI_TEST_DB_*` 42, `CHAWPI_DB_*`, `CHAWPI_JWT_SECRET`, `CHAWPI_AGENT_API_KEY`/`APIKEY`), SQL (`chawpi.custom_objects` etc. in tests, `db/chawpi/<module>` 11 migration dirs, schema/DB defaults), artifacts / project paths (`:chawpi-core`, `chawpi:chawpi-bom`), plugin ids (`chawpi.kotlin-library`…, 5 precompiled scripts), brand strings (JWT issuer, problem URI `chawpi.dev`, lock names, log prefixes), regexes in architecture tests (`\bchawpi[.:-](…)`) | T3 |
| `examples/*/server/**` | `examples/*/server/` | 17 / 152 | package `chawpi.examples.*`, `@ChawpiApplication`, `application.yml` keys + env + DB defaults (`chawpi_documents`, `chawpi_gis`, `chawpi_full`, simple `chawpi`), seed login in comments | T3 |
| `examples/gis-sample/perene/**` | same | 3 / 19 | `apply.py` env `CHAWPI_{CORE,EMAIL,PASSWORD}`, brand "Chawpi", `admin@chawpi.local`, path comment `chawpi-core/src/main/kotlin/chawpi/…`; `test_apply.py`; `README.md` (Spanish) | T3 |
| `infra/**` | `infra/` | 2 / 19 | compose project/containers/image/DB/user, `CHAWPI_PG_PORT`; init SQL `CREATE SCHEMA chawpi` | T3 |
| `frontend/packages/**`, `frontend/tooling/**` | `packages/`, `tooling/` | 250 / 1153 | npm scope `@hneyra/*` (~740), TS types/hooks `Chawpi*` (6 files named `Chawpi*.tsx`), `storagePrefix`, brand "Chawpi" in i18n JSON + package descriptions, test emails `@chawpi.test`, error prefixes, map source ids, `EXPECTED_PUBLIC`, relative links `../../../docs/modules/*.md` in 11 package READMEs | T4 |
| `examples/*/web/**` | `examples/*/web/` | 24 / 113 | `@hneyra/*`, Tailwind `@source '../../../../node_modules/@hneyra'`, `CHAWPI_API_URL`, seed login `admin@chawpi.local`, Playwright comment `CHAWPI_DB_*` | T4 |
| `docs/{architecture,api,development,domain,gis,guides,modules,security}/**` | wasichai `docs/` | 19 / 576 | doc prose + identifiers of every class above, relative links `../../frontend/packages/<pkg>/README.md` (10), monorepo layout (`backend/`, `frontend/`, `examples/*/web`) | T5 |
| `docs/adr/**` | wasichai `docs/adr/` | 32 / 182 | provenance lines (0001–0023), decision text (0024–0031), ADR-029 addendum (`@hneyra`), ADR-030 (the chawpi rename itself), index | T5 |
| `docs/HISTORY.md`, `docs/sapgis-origin.md` | same | 2 / 38 | history: verbatim | T5 (append only) |
| `docs/superpowers/**` | same | 9 / 7147 | history: verbatim, never edited | nobody |
| `README.md`, `CLAUDE.md`, `examples/README.md`, `examples/*/README.md` | wasichai (server side) + wasichai-ui (web side) | 13 / 202 | brand, artifacts, commands, layout | T5 |
| `.github/**` | both repos | 3 / 43 | `CHAWPI_DB_*`, `chawpi_full`, `@hneyra` scope, `frontend/tooling`, `backend/build-logic` | T6 |
| `release-please-config.json`, `.npmrc`, root `package.json`, `settings.gradle.kts`, `build.gradle.kts`, `.gitignore` | both repos | 8 / 34 | package-name, registry scope, root project name, group, it-env path | T6 / T4 / T3 (per ownership) |
| `backend/chawpi-integration-tests/it-env.sh` (git-ignored) | `wasichai-integration-tests/it-env.sh` | 1 | tunnel env NAMES (rename) and VALUES `chawpi_test` / `chawpi` (keep); `port_it` porting rules (target renamed, `sapgis` kept) | T3 (recreates) |

Parity fixtures (checked, answer for T3's "schema-name-bound?" question):

- **Schema parity** (`schemaParityIt/resources/schema-parity/{catalog.sql,legacy-final.catalog,generate-expected.sh}`):
  NOT bound to `chawpi`. `catalog.sql` takes `__SCHEMA__`; `SchemaParityTest.catalogOf("chawpi")` substitutes it and
  prints every name as `META.`; `legacy-final.catalog` is generated from sapgis' own schema `sapgis` and holds 0
  `chawpi` tokens. After the rename the test calls `catalogOf("wasichai")`; no regeneration.
- **Route parity** (`examples/full-sample/server/src/test/resources/route-parity/legacy-routes.txt`, 95 lines): paths
  only (`GET /api/…`), 0 `chawpi` tokens. No regeneration.
- **Wire parity** (`wireParityIt/kotlin/chawpi/it/full/{WireJson,GeometryWireParityTest}.kt`): goldens are inline
  JSON, 0 `chawpi` tokens besides the `package` line. No regeneration. Problem-detail `type` URIs
  (`https://chawpi.dev/problems/<status>`) are asserted only in core unit tests (`CoreOnlyApiTest`,
  `CoreHardeningApiTest`), which the rename script updates together with the default.

## Review Focus

A rename compiles even when a string the runtime reads by name was missed. These five are the failures most likely
to reach a person using the libraries; each has its pinning check in the owning task.

1. **A configuration key or auto-configuration class name that no longer matches.** An app sets
   `wasichai.database.port` (or `WASICHAI_DB_PORT`) and expects it to be honoured; a `@ConfigurationProperties`
   prefix, a YAML top-level key or an `AutoConfiguration.imports` line still saying `chawpi` would silently fall back
   to defaults or drop a bean. Pinned by T3 Step 6 (static cross-check of prefixes, YAML roots and `.imports` entries)
   and by the integration suites.
2. **Schema `wasichai` under DB user `chawpi`.** On the tunnel the user stays `chawpi`, so PostgreSQL's default
   `search_path` (`"$user", public`) no longer lands in the metadata schema by coincidence. Any unqualified table
   reference that only worked because user and schema had the same name now fails with "relation does not exist".
   Expected: every suite green with user `chawpi` and schema `wasichai`, and no `chawpi` schema recreated. Pinned by T3
   Step 9 (IT run + catalog query).
3. **A BOM with no constraints.** A consumer imports `platform("wasichai:wasichai-bom:X")` and expects versions for
   every starter; `wasichai-bom` builds its constraints from projects whose name starts with the prefix, so a missed
   prefix yields an empty BOM that still publishes. Pinned by T3 Step 10 (19 constraints in the BOM POM) and T7 Step 5
   (consumer compile).
4. **A published npm package pointing at `*` or at `@hneyra`.** `set-version.mjs` pins only dependencies whose name
   starts with the scope it knows; a stale prefix leaves `"@wasichai/core": "*"` in a tarball and a consumer gets
   whatever is latest. Pinned by T4 Step 7 (`check-release --pack`) and T7 Step 5 (install from tarballs).
5. **The e2e silently testing the wrong server.** Locally Playwright reuses whatever answers on 8093/5174
   (`reuseExistingServer: !CI`); an old chawpi full-sample server still running there would make the cross-repo e2e
   pass against chawpi. Expected: the run fails fast when a port is taken. Pinned by T7 Step 4 (ports must be free
   before the run, jar path must be the wasichai one).

## Execution order and open risks

Wave 2: dispatch T3, T4, T5 and T6 at once (disjoint files). The only ordering inside the wave: T6 Step 1 (wasichai-ui
release-please files) before T4 Step 7; T4 waits on the file, nothing else waits. Each task reports; the controller
records it in the ledger. Wave 3: T7 alone, then the final review and one fix round (T7 Step 9).

Open risks (the controller watches these; none blocks starting):

1. **Allow-list extension.** The spec's list does not name ADR-030, ADR-029's `@hneyra` addendum or
   `docs/sapgis-origin.md`; this plan keeps them verbatim as history (recorded in ADR-032). If the user wants them
   renamed instead, only T5 Step 2/3 and T7 Step 6 change.
2. **Tunnel user `chawpi` vs schema `wasichai`** (Review Focus 2): a latent unqualified table name may surface as a
   real failure in T3 Step 9. The fix is in code (qualify it), never on the tunnel.
3. **Suite lock renamed.** `wasichai-test-suite` does not serialize against `chawpi-test-suite`: a chawpi IT run on the
   same tunnel during T3 Step 9 or T7 Step 2 would wipe the wasichai run's schema. Nobody runs chawpi's suites meanwhile.
4. **CI needs the user.** The UI `e2e` job cannot pass until `wasichai/wasichai` is pushed with T3's rename and the
   secret `WASICHAI_REPO_TOKEN` exists; publishing needs `RELEASE_PLEASE_TOKEN` per repo and the organization's
   package settings to let workflow tokens create `@wasichai` npm and `wasichai` Maven packages. Local T7 covers the
   code; the first CI run on GitHub is the proof of the workflows.
5. **Per-sample READMEs.** T1 copied only `examples/README.md`; T5 creates the four `examples/<s>-sample/README.md`
   from chawpi's. If T1 later adds them, T5's version wins (T5 owns them).
6. **Path-length-sensitive TestKit test.** `ConventionPluginsTest` writes the absolute repository path into a probe
   `settings.gradle.kts`; from a very long checkout path (seen from the scratch dry-run copy) ktlint fails that probe.
   Not an issue at `/Users/jorge/IdeaProjects/wasichai` or on GitHub runners.
7. **Existing chawpi databases and browsers.** An old database keeps schema `chawpi` (set
   `wasichai.database.metadata-schema=chawpi`, ADR-032); old browser sessions under the `chawpi.` storage prefix are
   simply logged out. The tunnel's old `chawpi_*` demo databases stay untouched.
8. **release-please first run.** Both manifests say `0.1.0` with `release-as: 0.1.0`, copied from chawpi's setup;
   the first release PR on GitHub is the first real test of it.

---

### Task 3 (T3): wasichai backend rebrand

**Files:**
- Rename (dirs): `chawpi-{agent,automation,bom,core,documents,forms,gis,integration-tests,pages,test,views,workflow}/` →
  `wasichai-…/`; `starters/chawpi-spring-boot-starter*/` → `starters/wasichai-spring-boot-starter*/`; every
  `src/<set>/kotlin/chawpi/` → `src/<set>/kotlin/wasichai/` (main, test, testFixtures, the 21 IT source sets
  `agentIt … workflowOnly`, `examples/*/server/src/{main,test}`, `build-logic/src/test`); every
  `src/<set>/resources/db/chawpi/` → `db/wasichai/`.
- Rename (files): `build-logic/src/main/kotlin/chawpi.*.gradle.kts` → `wasichai.*.gradle.kts`; 55 `Chawpi*.kt` →
  `Wasichai*.kt` (list: `find . -name 'Chawpi*.kt' -not -path '*/build/*'`).
- Modify: every text file under the renamed trees plus `examples/*/server/**`, `examples/gis-sample/perene/**`,
  `infra/**`, `gradle/**`, root `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`,
  `.prettierignore`.
- Rewrite (git-ignored): `wasichai-integration-tests/it-env.sh`.
- Create (tunnel, not files): databases `wasichai_simple` on 5443; `wasichai_documents`, `wasichai_gis`,
  `wasichai_full` on 5442.
- Scratch: `$SCRATCH/t3/` with `SCRATCH=/private/tmp/claude-502/-Users-jorge-IdeaProjects-chawpi/73f54845-6d0f-493f-961b-10662da123e9/scratchpad`.

**Interfaces:**
- Consumes: T1's layout (Gradle at the wasichai root, projects `chawpi-*`, auto-discovered by `settings.gradle.kts`
  via `startsWith("chawpi-")`, `starters/*`, `examples/*/server`), T1's `chawpi-integration-tests/it-env.sh`.
- Produces (T4–T7 rely on these exact names): Gradle projects `:wasichai-*`, `:wasichai-spring-boot-starter*`,
  `:<sample>-server` (unchanged: `:simple-sample-server`, `:documents-sample-server`, `:gis-sample-server`,
  `:full-sample-server`); group `wasichai`; Maven dir `<repo>/wasichai/<artifactId>`; 20 artifacts; jar
  `examples/<sample>/server/build/libs/app.jar`; env `WASICHAI_DB_{HOST,PORT,NAME,USERNAME,PASSWORD}`,
  `WASICHAI_SEED_DEV`, `WASICHAI_JWT_SECRET`, `WASICHAI_TEST_DB_{HOST,PORT,NAME,USERNAME,PASSWORD,IMAGE}`,
  `WASICHAI_TEST_GIS_DB_PORT`; sample DB defaults `wasichai`, `wasichai_documents`, `wasichai_gis`, `wasichai_full`,
  user/password `wasichai`; seed `admin@wasichai.local` / `admin`; `it-env.sh` exporting the `WASICHAI_TEST_*` vars.

- [ ] **Step 1: Preconditions**

```bash
cd /Users/jorge/IdeaProjects/wasichai
test -d chawpi-core -o -d wasichai-core && test -f settings.gradle.kts && test -d build-logic && echo "layout ok"
test -f chawpi-integration-tests/it-env.sh -o -f wasichai-integration-tests/it-env.sh && echo "it-env ok"
mkdir -p "$SCRATCH/t3"
```

Expected: `layout ok`, `it-env ok`. If T1's report is not green (its `./gradlew build` failed), stop: the rename must
start from a green tree, or a failure later cannot be told apart from a Wave 1 defect.

- [ ] **Step 2: Write the rename script** to `$SCRATCH/t3/rename-backend.sh` (then `chmod +x`). Exactly:

```bash
#!/usr/bin/env bash
# T3: chawpi -> wasichai in the wasichai backend repo. ordered (most specific first), idempotent: a 2nd run is a no-op.
# never touches docs/, .github/, README.md, CLAUDE.md, examples/**/README.md outside perene, release-please files,
# nor the git-ignored it-env.sh (its tunnel values stay chawpi; T3 Step 5 rewrites it by hand).
set -euo pipefail
shopt -s nullglob
ROOT="${1:-/Users/jorge/IdeaProjects/wasichai}"
cd "$ROOT"
[ -f settings.gradle.kts ] && [ -d build-logic ] && [ -d docs ] || { echo "not the wasichai root: $ROOT" >&2; exit 1; }
case "$ROOT" in */chawpi|*/chawpi/*) echo "refusing: chawpi is read-only" >&2; exit 1 ;; esac

TOKENS='chawpi|Chawpi|CHAWPI|hneyra'
# the trees T3 owns. examples/*/README.md and examples/README.md are T5's: only server/ and perene/ are listed.
owned_roots() {
  local r
  for r in build-logic chawpi-* wasichai-* starters examples/*/server examples/gis-sample/perene infra gradle; do
    [ -e "$r" ] && printf '%s\n' "$r"
  done
}
ROOT_FILES="settings.gradle.kts build.gradle.kts gradle.properties .gitignore .prettierignore"

# text files T3 owns, minus build output, caches and the git-ignored tunnel env
owned_files() {
  { owned_roots | while read -r r; do
      find "$r" \( -type d \( -name build -o -name .gradle -o -name .kotlin -o -name node_modules -o -name __pycache__ \) -not -path '*/src/*' -prune \) \
        -o \( -type f -not -name it-env.sh -print \)
    done
    for f in $ROOT_FILES; do [ -f "$f" ] && printf '%s\n' "$f"; done
  } | LC_ALL=C sort -u
}

# invariants recorded before, compared after (step 7)
PARITY_GEN="$(ls */src/schemaParityIt/resources/schema-parity/generate-expected.sh examples/full-sample/server/src/test/resources/route-parity/generate-expected.sh)"
sapgis_before="$(cat $PARITY_GEN | grep -o -i sapgis | wc -l | tr -d ' ')"
flyway_before="$(owned_files | tr '\n' '\0' | xargs -0 grep -ohI 'flyway_history_[a-z_$]*' | LC_ALL=C sort -u | tr '\n' ' ')"

# 1. stale build output keeps old class and dir names: drop it, first run only (a source package named build is
#    never touched). a rerun keeps the build output of the renamed tree.
set -- chawpi-* starters/chawpi-*
if [ $# -gt 0 ]; then
  owned_roots | while read -r r; do
    find "$r" -type d \( -name build -o -name .gradle -o -name .kotlin \) -not -path '*/src/*' -prune -exec rm -rf {} +
  done
  rm -rf build .gradle .kotlin
fi

# 2. gradle project dirs: chawpi-* -> wasichai-*, starters too
for d in chawpi-* starters/chawpi-*; do
  target="${d/chawpi-/wasichai-}"
  [ -e "$target" ] && { echo "both $d and $target exist: resolve by hand" >&2; exit 1; }
  mv "$d" "$target"
done

# 3. kotlin package dirs (every source set: main, test, testFixtures, the IT suites, examples, build-logic) and
#    flyway resource dirs db/chawpi -> db/wasichai
owned_roots | while read -r r; do
  find "$r" -type d \( -path '*/src/*/kotlin/chawpi' -o -path '*/src/*/resources/db/chawpi' \) -prune -print
done | while read -r d; do
  target="${d%/chawpi}/wasichai"
  [ -e "$target" ] && { echo "both $d and $target exist: resolve by hand" >&2; exit 1; }
  mv "$d" "$target"
done

# 4. file names: chawpi.<plugin>.gradle.kts, Chawpi*.kt (deepest first, so a renamed dir never hides a child)
owned_roots | while read -r r; do
  find "$r" -depth -name '*[Cc]hawpi*' -not -path '*/build/*'
done | while read -r p; do
  base="$(basename "$p")"
  new="${base//Chawpi/Wasichai}"
  new="${new//chawpi/wasichai}"
  mv "$p" "$(dirname "$p")/$new"
done

# 5. contents, most specific first. plain substrings, no \b: `\bchawpi` inside a kotlin regex literal must change too.
hits="$(owned_files | tr '\n' '\0' | xargs -0 grep -lIE "$TOKENS" 2>/dev/null || true)"
[ -z "$hits" ] || printf '%s\n' "$hits" | tr '\n' '\0' | xargs -0 perl -pi -e '
  s#hneyra/chawpi#wasichai/wasichai#g;   # maven repo fallback in wasichai.publishing
  s/CHAWPI_/WASICHAI_/g;                 # env vars
  s/CHAWPI/WASICHAI/g;                   # remaining upper case
  s/Chawpi/Wasichai/g;                   # types, brand text
  s/chawpi:chawpi-/wasichai:wasichai-/g; # maven coordinates
  s#db/chawpi/#db/wasichai/#g;           # flyway locations
  s/chawpi\./wasichai./g;                # packages, property keys, plugin ids, schema-qualified sql, @chawpi.local, chawpi.dev
  s/chawpi-/wasichai-/g;                 # artifacts, project paths, locks, containers
  s/chawpi_/wasichai_/g;                 # demo db names
  s/chawpi/wasichai/g;                   # group, schema/db/user defaults, issuer, workspace, yaml root key, the rest
'

# 6. no leftover in what T3 owns (it-env.sh excluded on purpose)
left="$(owned_files | tr '\n' '\0' | xargs -0 grep -nIE "$TOKENS" 2>/dev/null || true)"
[ -z "$left" ] || { echo "leftovers:"; echo "$left"; exit 1; }
dirs="$(owned_roots | while read -r r; do find "$r" -name '*[Cc]hawpi*' -not -path '*/build/*'; done)"
[ -z "$dirs" ] || { echo "names left:"; echo "$dirs"; exit 1; }

# 7. invariants: sapgis references in the parity generators and every flyway history table name are untouched
PARITY_GEN="$(ls */src/schemaParityIt/resources/schema-parity/generate-expected.sh examples/full-sample/server/src/test/resources/route-parity/generate-expected.sh)"
[ "$(cat $PARITY_GEN | grep -o -i sapgis | wc -l | tr -d ' ')" = "$sapgis_before" ] || { echo "sapgis references changed" >&2; exit 1; }
flyway_after="$(owned_files | tr '\n' '\0' | xargs -0 grep -ohI 'flyway_history_[a-z_$]*' | LC_ALL=C sort -u | tr '\n' ' ')"
[ "$flyway_after" = "$flyway_before" ] || { echo "flyway history names changed: $flyway_before -> $flyway_after" >&2; exit 1; }
echo "rename-backend: ok ($(owned_files | wc -l | tr -d ' ') owned files, sapgis refs $sapgis_before, flyway: $flyway_after)"
```

Why this order: `CHAWPI_` before `CHAWPI`, `Chawpi` before any lower-case rule, and the four lower-case rules with a
following character (`:chawpi-`, `db/chawpi/`, `.`, `-`, `_`) before the bare one, so a reader of a diff can tell
which class each change belongs to. The substitutions are plain substrings on purpose: `\bchawpi` in
`CoreArchitectureTest` / `ModuleBoundariesTest` regex literals has no word boundary before `chawpi` (the `b` of `\b`
is a word character) and must still change. Exclusions are by path, not by pattern, and each is named: `docs/**`
(T5), `.github/**` (T6), `README.md` / `CLAUDE.md` / `examples/README.md` / `examples/*/README.md` (T5),
`release-please-config.json` / `.release-please-manifest.json` (T6), `it-env.sh` (tunnel values stay; Step 5).
The `sapgis` references in the two parity generators and every `flyway_history_*` name are checked unchanged by
the script itself (step 7). This script was dry-run on a copy of the Wave 1 tree on 2026-09-26: first run `ok`
(382 owned files, sapgis refs 7), second run `ok` with no change.

- [ ] **Step 3: Run it, then prove idempotency**

```bash
"$SCRATCH/t3/rename-backend.sh" /Users/jorge/IdeaProjects/wasichai
touch "$SCRATCH/t3/after-first-run"
"$SCRATCH/t3/rename-backend.sh" /Users/jorge/IdeaProjects/wasichai
cd /Users/jorge/IdeaProjects/wasichai
find build-logic wasichai-* starters examples/*/server examples/gis-sample/perene infra gradle settings.gradle.kts build.gradle.kts \
  .gitignore .prettierignore -type f -newer "$SCRATCH/t3/after-first-run" -not -path '*/build/*' | head
ls -d chawpi-* starters/chawpi-* 2>/dev/null; grep -n 'wasichai' settings.gradle.kts | head -3
```

Expected: both runs print `rename-backend: ok (… owned files, sapgis refs 7, flyway: flyway_history_$it
flyway_history_$name flyway_history_core flyway_history_core_seed )`; the `find` prints nothing (second run changed no
file); no `chawpi-*` dir is listed; `settings.gradle.kts` shows `rootProject.name = "wasichai"` and
`startsWith("wasichai-")`. A `leftovers:` or `names left:` output means a class the script does not know: add a rule
in the right position (most specific first), rerun, and record the new rule in the report.

- [ ] **Step 4: Format** (the rename reorders imports: `wasichai.*` sorts after `org.*`, where `chawpi.*` sorted
  before; and longer names push a few argument lists over 160 columns)

```bash
cd /Users/jorge/IdeaProjects/wasichai
./gradlew ktlintFormat -q      # build-logic is an included build without ktlint: nothing to format there
./gradlew ktlintCheck
```

Expected: `ktlintCheck` BUILD SUCCESSFUL. The dry run showed 242 `import-ordering` and 6 `argument-list-wrapping`
findings before `ktlintFormat`, all auto-fixable. A `max-line-length` finding is not auto-fixable: wrap that line by
hand (Kotlin 4-space continuation) and rerun.

- [ ] **Step 5: Recreate `it-env.sh`** with `WASICHAI_TEST_DB_*` names and the unchanged tunnel values

```bash
cd /Users/jorge/IdeaProjects/wasichai
ENV=wasichai-integration-tests/it-env.sh
PW="$(sed -nE 's/.*(CHAWPI|WASICHAI)_TEST_DB_PASSWORD=([^ \\]+).*/\2/p' "$ENV" | head -1)"
[ -n "$PW" ] || { echo "no tunnel password in $ENV: take it from chawpi's backend/chawpi-integration-tests/it-env.sh" >&2; exit 1; }
cat > "$ENV" <<'SH'
# sourced (never executed) at the start of every Bash call that needs $IT, $TSRC, the
# WASICHAI_TEST_DB_* vars or port_it: source wasichai-integration-tests/it-env.sh
# git-ignored. the tunnel db keeps its own names (db chawpi_test, user chawpi, containers
# chawpi-test-postgis on 5442 / chawpi-test-plain on 5443): only the variable names are wasichai.
cd /Users/jorge/IdeaProjects/wasichai

TSRC=/Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis/api
IT=wasichai-integration-tests
export WASICHAI_TEST_DB_HOST=localhost WASICHAI_TEST_DB_PORT=5443 WASICHAI_TEST_DB_NAME=chawpi_test \
       WASICHAI_TEST_DB_USERNAME=chawpi WASICHAI_TEST_DB_PASSWORD=__PW__ WASICHAI_TEST_GIS_DB_PORT=5442

# porting rules for an original (sapgis) api test: order matters, the specific renames before the blanket one
port_it() {
  sed -i '' -E \
    -e 's/^package com\.sapgis\.api$/package wasichai.it.full/' \
    -e 's/^import com\.sapgis\.(agent|automation|pages|documents|gis|workflow|views|forms)\./import wasichai.\1./' \
    -e 's/^import com\.sapgis\.metadata\./import wasichai.core.metadata./' \
    -e '/^import com\.sapgis\.data\.DATA_SCHEMA$/d' \
    -e "s/'\\\$DATA_SCHEMA'/'app_data'/g" \
    -e 's/: IntegrationTest\(\)/: FullAppIntegrationTest()/' \
    -e 's/sapgis\.agent\.apiKey/wasichai.agent.api-key/g' \
    -e 's/sapgis\.geoserver\./wasichai.gis.geoserver./g' \
    "$@"
  sed -i '' -e 's/SAPGIS/WASICHAI/g' -e 's/Sapgis/Wasichai/g' -e 's/sapgis/wasichai/g' "$@"
}
SH
PW="$PW" perl -pi -e 's/__PW__/$ENV{PW}/' "$ENV"
chmod +x "$ENV"
git check-ignore -q "$ENV" && echo "ignored"
grep -c 'CHAWPI' "$ENV"; grep -o 'WASICHAI_TEST_[A-Z_]*' "$ENV" | sort -u | tr '\n' ' '
```

Expected: `ignored`, `0` (no `CHAWPI` left), and `WASICHAI_TEST_DB_HOST WASICHAI_TEST_DB_NAME
WASICHAI_TEST_DB_PASSWORD WASICHAI_TEST_DB_PORT WASICHAI_TEST_DB_USERNAME WASICHAI_TEST_GIS_DB_PORT`. Never print
`$PW`. `git check-ignore` works because the rename script turned the `.gitignore` line into
`wasichai-integration-tests/it-env.sh`.

- [ ] **Step 6: Static cross-checks for names the runtime reads by string** (Review Focus 1)

```bash
cd /Users/jorge/IdeaProjects/wasichai
# every @ConfigurationProperties prefix is wasichai.*
grep -rhoE '@ConfigurationProperties\((prefix = )?"[^"]+"' --include='*.kt' . | grep -v '"wasichai' ; echo "prefixes checked"
# every sample yaml root key is server/spring/management/wasichai
grep -hE '^[a-z]' examples/*/server/src/main/resources/application.yml | sort -u
# every class named in .imports / spring.factories exists as a kotlin file
for f in $(find . -path '*/src/main/resources/META-INF/*' \( -name '*.imports' -o -name 'spring.factories' \) -not -path '*/build/*'); do
  mod="${f%%/src/main/*}"
  grep -oE 'wasichai(\.[A-Za-z0-9_]+)+' "$f" | while read -r cls; do
    [ -f "$mod/src/main/kotlin/$(echo "$cls" | tr . /).kt" ] || echo "$f: no source for $cls"
  done
done; echo "imports checked"
```

Expected: `prefixes checked` preceded only by `@ConfigurationProperties("live"` (a test app's own prefix in
`wasichai-core/src/test/kotlin/testapp/live`, not a library key); the yaml roots are exactly `server:`, `spring:`,
`wasichai:`; `imports checked` with no `no source for` line. (All three verified on the dry-run copy.)

- [ ] **Step 7: Build**

```bash
cd /Users/jorge/IdeaProjects/wasichai
./gradlew -p build-logic test
./gradlew build
```

Expected: both BUILD SUCCESSFUL (build-logic TestKit tests, ktlint, every unit and architecture test, every sample
server's unit tests). `CoreArchitectureTest` and `ModuleBoundariesTest` now match `\bwasichai…`; if one of them
passes with zero matched references, the regex rename failed: its assertions must still see core/module references.

- [ ] **Step 8: Create the demo databases on the tunnel** (never 5432)

```bash
bash /private/tmp/claude-502/-Users-jorge-IdeaProjects-chawpi/73f54845-6d0f-493f-961b-10662da123e9/scratchpad/tunnel.sh
source /Users/jorge/IdeaProjects/wasichai/wasichai-integration-tests/it-env.sh
for spec in 5443:wasichai_simple 5442:wasichai_documents 5442:wasichai_gis 5442:wasichai_full; do
  port="${spec%%:*}" db="${spec#*:}"
  case "$port" in 5442|5443) ;; *) echo "refusing port $port" >&2; exit 1 ;; esac
  q() { PGPASSWORD="$WASICHAI_TEST_DB_PASSWORD" psql -X -q -At -v ON_ERROR_STOP=1 -h localhost -p "$port" \
          -U "$WASICHAI_TEST_DB_USERNAME" -d "$WASICHAI_TEST_DB_NAME" -c "$1"; }
  [ "$(q "SELECT 1 FROM pg_database WHERE datname = '$db'")" = 1 ] || q "CREATE DATABASE $db"
  echo "$port $(q "SELECT datname FROM pg_database WHERE datname = '$db'")"
done
```

Expected: `5443 wasichai_simple`, `5442 wasichai_documents`, `5442 wasichai_gis`, `5442 wasichai_full`. A rerun
prints the same and creates nothing. The old `chawpi_*` demo databases stay (the user's; not ours to drop). PostGIS
and pgcrypto are created by the migrations on first boot (the tunnel user is a superuser).

- [ ] **Step 9: All integration tests on the tunnel** (Review Focus 2)

```bash
bash /private/tmp/claude-502/-Users-jorge-IdeaProjects-chawpi/73f54845-6d0f-493f-961b-10662da123e9/scratchpad/tunnel.sh
source /Users/jorge/IdeaProjects/wasichai/wasichai-integration-tests/it-env.sh
./gradlew integrationTest --rerun-tasks --continue 2>&1 | tee "$SCRATCH/t3/it.log" | grep -E 'route parity|BUILD|FAILED' 
find . -path '*/build/test-results/*' -name 'TEST-*.xml' -not -path './build-logic/*' -newer "$SCRATCH/t3/after-first-run" \
  | grep -iE 'integrationTest|It/|Only/|fullApp|layersIt' \
  | xargs perl -ne 'if (/<testsuite [^>]*tests="(\d+)"[^>]*skipped="(\d+)"[^>]*failures="(\d+)"[^>]*errors="(\d+)"/) { $t+=$1; $s+=$2; $f+=$3; $e+=$4 } END { print "tests=$t skipped=$s failures=$f errors=$e\n" }'
for port in 5442 5443; do
  PGPASSWORD="$WASICHAI_TEST_DB_PASSWORD" psql -X -At -h localhost -p $port -U "$WASICHAI_TEST_DB_USERNAME" -d "$WASICHAI_TEST_DB_NAME" \
    -c "SELECT string_agg(nspname, ',') FROM pg_namespace WHERE nspname IN ('chawpi', 'wasichai')"
done
```

Expected: `BUILD SUCCESSFUL`; `route parity: 95 live routes compared …`; `tests=410 skipped=0 failures=0 errors=0`
(94 core + 300 IT suites + 16 samples; if the XML filter above counts a different set, count per task with
`grep -h '<testsuite' <dir>/*.xml` and report the breakdown); schema parity green against the 464-line fixture with
`catalogOf("wasichai")`; the catalog query prints `wasichai` (or nothing) on each port and never `chawpi`. A
"relation … does not exist" failure means an unqualified table name that used to resolve through
`search_path = "$user"` (user and schema were both `chawpi`): qualify it with `${schemas.metadata}.` in the code
(ADR rule 2), never rename the tunnel user. The suites hold the advisory lock `wasichai-test-suite`, which no longer
serializes against a chawpi run: do not run chawpi's suites against the tunnel while this step runs.

- [ ] **Step 10: The published set and the BOM** (Review Focus 3)

```bash
cd /Users/jorge/IdeaProjects/wasichai
M="$SCRATCH/t3/m2"; rm -rf "$M"
./gradlew publishToMavenLocal -Pversion=0.0.0-verify -Dmaven.repo.local="$M"
ls "$M/wasichai" | wc -l | tr -d ' '
# the guard as T6 ships it, applied to a scratch copy so T3 never edits .github/**
perl -pe 's/chawpi/wasichai/g' .github/scripts/check-maven-publications.sh > "$SCRATCH/t3/guard.sh"
bash "$SCRATCH/t3/guard.sh" "$M"
grep -c '<artifactId>wasichai-' "$M/wasichai/wasichai-bom/0.0.0-verify/wasichai-bom-0.0.0-verify.pom"
grep -A2 '<artifactId>wasichai-core</artifactId>' "$M/wasichai/wasichai-test/0.0.0-verify/wasichai-test-0.0.0-verify.pom" | head -3
```

Expected: `20`; `maven publications: ok (20)`; `20` (the BOM's own artifactId plus 19 constraints: 10 libraries and 9
starters); the `wasichai-test` POM names `wasichai-core`. Never publish to the default `~/.m2` (a consumer check in
T7 must not pick up a stray local copy).

- [ ] **Step 11: Perené against gis-sample** (36 unit tests + the apply.py cycle on `wasichai_gis`)

```bash
bash /private/tmp/claude-502/-Users-jorge-IdeaProjects-chawpi/73f54845-6d0f-493f-961b-10662da123e9/scratchpad/tunnel.sh
source /Users/jorge/IdeaProjects/wasichai/wasichai-integration-tests/it-env.sh
(cd examples/gis-sample/perene && python3 -m unittest -q 2>&1 | tail -3)
lsof -nP -iTCP:8090 -sTCP:LISTEN && { echo "8090 busy: stop and report" >&2; exit 1; }
./gradlew :gis-sample-server:bootJar -q
WASICHAI_DB_PORT=5442 WASICHAI_DB_NAME=wasichai_gis WASICHAI_DB_USERNAME="$WASICHAI_TEST_DB_USERNAME" \
  WASICHAI_DB_PASSWORD="$WASICHAI_TEST_DB_PASSWORD" java -jar examples/gis-sample/server/build/libs/app.jar \
  > "$SCRATCH/t3/gis-sample.log" 2>&1 &
GIS_PID=$!
until curl -sf http://localhost:8090/actuator/health >/dev/null; do kill -0 $GIS_PID || { tail -40 "$SCRATCH/t3/gis-sample.log"; exit 1; }; sleep 2; done
cd examples/gis-sample/perene
python3 apply.py --validate-only && python3 apply.py | tail -1 && python3 apply.py | tail -1 && python3 apply.py --drop | tail -1
kill $GIS_PID
```

Expected: `Ran 36 tests … OK`; health answers; the three apply runs end with `done: 30 created, 0 skipped` (or
`done: 0 created, 30 skipped` if the database already held the model), `done: 0 created, 30 skipped`,
`done: 30 deleted, 0 skipped`. The `until` loop is the only wait (no bare `sleep`); if the jar exits, its log tail is
the report.

- [ ] **Step 12: Report** (no commit): the two script outputs, Step 4–11 results with the counts, every hand edit
  (file:line, why), and every `route to T<n>` item. Also list any file T1 left that the script did not own but that
  still names chawpi (`git ls-files -co --exclude-standard | xargs grep -lIE 'chawpi|CHAWPI|Chawpi'` minus the
  allow-list and minus T5/T6 paths).

### Task 4 (T4): wasichai-ui frontend rebrand

**Files:**
- Rename: `packages/core/src/app/Chawpi{App,App.smoke.test,Providers,Providers.test,Routes,Routes.test}.tsx` →
  `Wasichai*.tsx`.
- Modify: every text file under `packages/**` (sources, tests, i18n JSON, `package.json`, `README.md`), `tooling/**`,
  `examples/*/web/**` (sources, tests, `index.css`, `vite.config.ts`, `playwright.config.ts`, `e2e/*.spec.ts`,
  `package.json`), root `package.json`, `.npmrc`, `.gitignore`, `.prettierignore`, `tsconfig.base.json`.
- Regenerate: `node_modules/@wasichai/*` workspace links (`yarn install`); `yarn.lock` must stay byte-identical.
- Scratch: `$SCRATCH/t4/`.

**Interfaces:**
- Consumes: T2's layout (workspaces `packages/*`, `examples/*/web`; `tooling/*.mjs`; `playwright.config.ts` reading
  `WASICHAI_BACKEND_DIR`, default `../../../../wasichai`); T6's `release-please-config.json` in the wasichai-ui root
  (Step 7 only); T3's env names `WASICHAI_DB_*`, seed `admin@wasichai.local` (sample tests and e2e text only).
- Produces: npm scope `@wasichai` (11 public packages `@wasichai/{agent,automation,core,documents,forms,gis,pages,
  testing,ui,views,workflow}`, private `@wasichai/smoke`); exports `WasichaiApp`, `WasichaiAppProps`,
  `WasichaiProviders`, `WasichaiRoutes`, `WasichaiModule`, `WasichaiRegistry`, `WasichaiRouteMap`, `WasichaiLinks`,
  `WasichaiConfig`, `createWasichaiI18n`, `useWasichai`, `useWasichaiConfig`, `useWasichaiLinks`, `renderWasichai…`
  helpers from `@wasichai/testing` (whatever `Chawpi*` became, name for name); default `storagePrefix: 'wasichai'`;
  `tooling/check-release.mjs` `EXPECTED_PUBLIC` = the 11 names; `tooling/set-version.mjs` pins `@wasichai/*`;
  `.npmrc` `@wasichai:registry=https://npm.pkg.github.com`; web env `WASICHAI_API_URL`.

- [ ] **Step 1: Preconditions**

```bash
cd /Users/jorge/IdeaProjects/wasichai-ui
test -d packages/core -a -d tooling -a -f examples/full-sample/web/playwright.config.ts && echo "layout ok"
grep -q WASICHAI_BACKEND_DIR examples/full-sample/web/playwright.config.ts && echo "backend dir ok"
mkdir -p "$SCRATCH/t4"; cp yarn.lock "$SCRATCH/t4/yarn.lock.before"
```

Expected: `layout ok`, `backend dir ok`. Otherwise stop: T2 is not done.

- [ ] **Step 2: Write the rename script** to `$SCRATCH/t4/rename-frontend.sh` (then `chmod +x`). Exactly:

```bash
#!/usr/bin/env bash
# T4: @hneyra / chawpi -> @wasichai / wasichai in wasichai-ui. ordered (most specific first), idempotent.
# never touches README.md, CLAUDE.md, docs/, examples/README.md (T5) nor .github/, release-please files (T6).
set -euo pipefail
shopt -s nullglob
ROOT="${1:-/Users/jorge/IdeaProjects/wasichai-ui}"
cd "$ROOT"
[ -d packages/core ] && [ -d tooling ] && [ -f package.json ] || { echo "not the wasichai-ui root: $ROOT" >&2; exit 1; }
case "$ROOT" in */chawpi|*/chawpi/*) echo "refusing: chawpi is read-only" >&2; exit 1 ;; esac

TOKENS='chawpi|Chawpi|CHAWPI|hneyra'
ROOT_FILES="package.json .npmrc .gitignore .prettierignore tsconfig.base.json"
owned_roots() { local r; for r in packages tooling examples/*/web; do [ -e "$r" ] && printf '%s\n' "$r"; done; }
owned_files() {
  { owned_roots | while read -r r; do
      find "$r" \( -type d \( -name node_modules -o -name dist -o -name coverage -o -name test-results -o -name playwright-report -o -name .vite \) -prune \) \
        -o \( -type f -print \)
    done
    for f in $ROOT_FILES; do [ -f "$f" ] && printf '%s\n' "$f"; done
  } | LC_ALL=C sort -u
}

# 1. file names: packages/core/src/app/Chawpi*.tsx -> Wasichai*.tsx (imports follow in step 2)
owned_roots | while read -r r; do
  find "$r" -depth -name '*[Cc]hawpi*' -not -path '*/node_modules/*' -not -path '*/dist/*'
done | while read -r p; do
  base="$(basename "$p")"; new="${base//Chawpi/Wasichai}"; new="${new//chawpi/wasichai}"
  [ -e "$(dirname "$p")/$new" ] && { echo "both $p and $new exist: resolve by hand" >&2; exit 1; }
  mv "$p" "$(dirname "$p")/$new"
done

# 2. contents, most specific first
hits="$(owned_files | tr '\n' '\0' | xargs -0 grep -lIE "$TOKENS|\]\((\.\./)+docs/" 2>/dev/null || true)"
[ -z "$hits" ] || printf '%s\n' "$hits" | tr '\n' '\0' | xargs -0 perl -pi -e '
  s#\]\((?:\.\./)+docs/#](https://github.com/wasichai/wasichai/blob/main/docs/#g; # package READMEs: docs live in wasichai
  s#\@hneyra:registry#\@wasichai:registry#g;  # .npmrc
  s#\@hneyra/#\@wasichai/#g;                  # imports, deps, aliases, EXPECTED_PUBLIC
  s#\@hneyra\b#\@wasichai#g;                  # bare scope: tailwind @source, comments
  s#\@chawpi/#\@wasichai/#g;                  # stray old scope
  s/CHAWPI_/WASICHAI_/g;                      # WASICHAI_API_URL, WASICHAI_DB_* in comments
  s/CHAWPI/WASICHAI/g;
  s/Chawpi/Wasichai/g;                        # WasichaiApp, WasichaiModule, useWasichaiLinks, brand text
  s/chawpi/wasichai/g;                        # storagePrefix, emails, error prefixes, map ids, tmp prefix
'

# 3. nothing left
left="$(owned_files | tr '\n' '\0' | xargs -0 grep -nIE "$TOKENS" 2>/dev/null || true)"
[ -z "$left" ] || { echo "leftovers:"; echo "$left"; exit 1; }
names="$(owned_roots | while read -r r; do find "$r" -name '*[Cc]hawpi*' -not -path '*/node_modules/*' -not -path '*/dist/*'; done)"
[ -z "$names" ] || { echo "names left:"; echo "$names"; exit 1; }
echo "rename-frontend: ok ($(owned_files | wc -l | tr -d ' ') owned files)"
```

What each rule covers (inventory classes): the README rule turns the 11 `Module guide: [docs/modules/<m>.md](../../../docs/modules/<m>.md)`
links into `https://github.com/wasichai/wasichai/blob/main/docs/modules/<m>.md` (the docs live in wasichai now);
`@hneyra:registry` is the `.npmrc` line; `@hneyra/` covers ~740 imports, dependency keys, vite aliases,
`EXPECTED_PUBLIC`, `set-version.mjs`'s `name.startsWith('@hneyra/')`, `scaffold-module.mjs`; bare `@hneyra` covers the
Tailwind `@source '../../../../node_modules/@hneyra'` lines and comments; `CHAWPI_` covers `WASICHAI_API_URL` in the
four `vite.config.ts` and the Playwright comment; `Chawpi` covers every exported type/hook (naming map), the six file
imports and the brand "Chawpi" (`app.name` in `packages/core/src/i18n/locales/{en,es}/common.json`, package
`description`s); bare `chawpi` covers `storagePrefix: 'chawpi'`, the error prefixes `chawpi:` / `chawpi links:`,
`chawpi-features` / `chawpi-wms-`, `chawpi-pack-`, the seed login `admin@chawpi.local` → `admin@wasichai.local` in the
four sample `App.tsx` (`defaultLoginEmail`), `App.test.tsx` and `e2e/smoke.spec.ts`, and test emails `@chawpi.test`.
`port-from-sapgis.mjs` keeps every `sapgis` pattern; only its replacement targets become `wasichai` / `Wasichai`.
This script was dry-run on a copy of the Wave 2 input on 2026-09-26: `ok (421 owned files)` twice, then 677 tests
and 22 tooling tests green, `check-release: ok`, all four sample webs built with `.flex{`, and exactly two files
needing `prettier --write` (Step 4).

- [ ] **Step 3: Run it twice**

```bash
"$SCRATCH/t4/rename-frontend.sh" /Users/jorge/IdeaProjects/wasichai-ui
touch "$SCRATCH/t4/after-first-run"
"$SCRATCH/t4/rename-frontend.sh" /Users/jorge/IdeaProjects/wasichai-ui
cd /Users/jorge/IdeaProjects/wasichai-ui
find packages tooling examples/*/web -type f -newer "$SCRATCH/t4/after-first-run" -not -path '*/node_modules/*' -not -path '*/dist/*' | head
cat .npmrc; grep -n "storagePrefix: '" packages/core/src/app/config.ts; grep -n '"name": "Wasichai"' packages/core/src/i18n/locales/*/common.json
grep -h '"registry"' packages/*/package.json | sort | uniq -c
```

Expected: both runs `rename-frontend: ok (… owned files)`; `find` prints nothing; `.npmrc` is
`@wasichai:registry=https://npm.pkg.github.com`; `storagePrefix: 'wasichai'`; both locales show `"name": "Wasichai"`;
every `publishConfig.registry` is `https://npm.pkg.github.com` (11 lines, one value; the registry is the same for
any scope, only the scope in `.npmrc` and in the package names changed).

- [ ] **Step 4: Relink the workspace and format**

```bash
cd /Users/jorge/IdeaProjects/wasichai-ui
rm -rf node_modules/@hneyra
yarn install --frozen-lockfile
ls node_modules/@wasichai | tr '\n' ' '
cmp yarn.lock "$SCRATCH/t4/yarn.lock.before" && echo "lockfile unchanged"
# longer names re-wrap a few lines (dry run: WasichaiRoutes.test.tsx, RecordListPage.test.tsx). scoped to T4's
# paths: a repo-wide `yarn format` would also write T6's .github files and release-please config
yarn -s prettier --write packages tooling examples/*/web package.json tsconfig.base.json > /dev/null
yarn format:check
```

Expected: `agent automation core documents forms gis pages smoke testing ui views workflow`; `lockfile unchanged`
(workspace packages are not in yarn v1's lockfile); `format:check` prints `All matched files use Prettier code
style!`. `format:check` is repo-wide: if it flags a file under `.github/` or `release-please-config.json`, leave it
and route it to T6.

- [ ] **Step 5: Tooling, lint, tests, build**

```bash
cd /Users/jorge/IdeaProjects/wasichai-ui
yarn test:tooling 2>&1 | grep -E '^ℹ (tests|pass|fail)'
yarn lint
yarn test 2>&1 | grep -E 'Tests +[0-9]+' | perl -ne '$t += $1 if /(\d+) passed/; $f += $1 if /(\d+) failed/; END { print "passed=$t failed=${\($f//0)}\n" }'
yarn build
```

Expected: `ℹ tests 22`, `ℹ pass 22`, `ℹ fail 0`; lint clean; `passed=677 failed=0`; build green (every package,
then every sample web against the packages' `dist`).

- [ ] **Step 6: The four sample webs** (test, typecheck, build, Tailwind scanned the `@wasichai` packages)

```bash
cd /Users/jorge/IdeaProjects/wasichai-ui
for s in simple documents gis full; do
  yarn -s workspace $s-sample-web test >/dev/null && yarn -s workspace $s-sample-web typecheck && yarn -s workspace $s-sample-web build >/dev/null \
    && grep -l '\.flex{' examples/$s-sample/web/dist/assets/*.css >/dev/null && echo "$s ok"
done
grep -rn "admin@wasichai.local" examples/*/web/src/App.tsx | wc -l | tr -d ' '
```

Expected: `simple ok`, `documents ok`, `gis ok`, `full ok`; `4` (the seed login text in every sample). A missing
`.flex{` means the `@source` path no longer reaches `node_modules/@wasichai`.

- [ ] **Step 7: Release guard with packing** (Review Focus 4). Needs T6's `release-please-config.json`; T6 writes it
  first. If it is not there yet, wait for it (no bare `sleep`; use Monitor with
  `until [ -f /Users/jorge/IdeaProjects/wasichai-ui/release-please-config.json ]; do sleep 10; done`).

```bash
cd /Users/jorge/IdeaProjects/wasichai-ui
node tooling/check-release.mjs --pack 0.1.0
node --input-type=module -e 'import { EXPECTED_PUBLIC } from "./tooling/check-release.mjs"; console.log(EXPECTED_PUBLIC.length, EXPECTED_PUBLIC.every((n) => n.startsWith("@wasichai/")))'
node -p "require('./packages/core/package.json').version"
```

Expected: `check-release: ok` (11 public `@wasichai/*`, every one bumped by release-please, every internal range
pinned to `0.1.0` in the packed copy, entry points in every tarball); `11 true`; the repository version (`0.1.0`)
unchanged (the pack step works on a temp copy).

- [ ] **Step 8: Report** (no commit): script outputs, Steps 4–7 results with counts, any hand edit (file:line, why),
  and `route to T<n>` items.

### Task 5 (T5): docs in both repositories

**Files (wasichai, `/Users/jorge/IdeaProjects/wasichai`):**
- Create: `docs/adr/0032-rebrand-to-wasichai-and-split-repositories.md`, `docs/chawpi-origin.md`.
- Modify (scripted rename + listed hand edits): every `docs/**/*.md` except `docs/superpowers/**`,
  `docs/HISTORY.md` (one new top entry only), `docs/sapgis-origin.md` (one pointer line only),
  `docs/adr/0030-rebrand-sapgis-to-chawpi.md` (status line + one note only), and the `## Addendum (2026-09-25)`
  section of `docs/adr/0029-polyglot-monorepo-and-publishing.md` (verbatim).
- Rewrite: `docs/development/releasing.md` (full text below), `README.md`, `CLAUDE.md`, `examples/README.md` (full
  texts below); create or rewrite `examples/{simple,documents,gis,full}-sample/README.md` (from chawpi's, server side).

**Files (wasichai-ui, `/Users/jorge/IdeaProjects/wasichai-ui`):**
- Create or rewrite: `README.md`, `CLAUDE.md`, `docs/README.md`, `examples/README.md` (full texts below).

**Interfaces:**
- Consumes: the naming map; T6's job names and secrets (wasichai CI jobs `backend`, `integration`,
  `examples-servers`, `perene`, `format`, `publish-dry-run`; wasichai-ui CI jobs `frontend`, `sample-webs`,
  `release-guard`, `e2e`; secrets `RELEASE_PLEASE_TOKEN` in both repos, `WASICHAI_REPO_TOKEN` in wasichai-ui); T4's
  package names and exports; T3's properties and env.
- Produces: `docs/adr/0032-rebrand-to-wasichai-and-split-repositories.md` (T7 links it), `docs/chawpi-origin.md`, the
  URLs other docs use: `https://github.com/wasichai/wasichai/blob/main/docs/<path>.md` and
  `https://github.com/wasichai/wasichai-ui/tree/main/packages/<pkg>`.

- [ ] **Step 1: Snapshot and preconditions**

```bash
cd /Users/jorge/IdeaProjects/wasichai
test -f docs/HISTORY.md -a -f docs/adr/0031-deliberate-deviations-from-sapgis.md -a -d /Users/jorge/IdeaProjects/wasichai-ui/packages && echo ok
mkdir -p "$SCRATCH/t5" && rm -rf "$SCRATCH/t5/docs.before" && cp -R docs "$SCRATCH/t5/docs.before"
```

Expected: `ok` (T1 has copied `docs/`; if not, wait for the T1 report).

- [ ] **Step 2: Scripted rename of the living docs.** Write `$SCRATCH/t5/rename-docs.sh` exactly as below and run it
  twice (the second run must change nothing: `diff -r` of two snapshots prints nothing).

```bash
#!/usr/bin/env bash
# T5: chawpi -> wasichai in the wasichai living docs. idempotent. history, provenance and file names stay.
set -euo pipefail
cd "${1:-/Users/jorge/IdeaProjects/wasichai}"
FILES="$(find docs -name '*.md' -not -path 'docs/superpowers/*' -not -name HISTORY.md -not -name sapgis-origin.md \
  -not -name chawpi-origin.md -not -name '0030-*' -not -name '0032-*' | LC_ALL=C sort)"
perl -pi -e '
  $skip = 0 if $. == 1;
  # the @hneyra addendum of ADR-029 is history: verbatim to the end of the file
  $skip = 1 if $ARGV =~ m{/0029-} && /^## Addendum \(2026-09-25\)/;
  # provenance lines and the ADR-030 index entry keep the old name
  unless ($skip || /^> (Imported from sapgis|Moved from chawpi)/ || /^- \[ADR-030:/) {
    # file names that must keep resolving
    s/(rebrand-sapgis-to-chawpi|chawpi-libraries-design|chawpi-origin)/"\x00K" . unpack("H*", $1) . "\x00"/ge;
    # frontend package links: the packages live in wasichai-ui now
    s#\]\((?:\.\./)+frontend/packages/([a-z]+)/README\.md\)#](https://github.com/wasichai/wasichai-ui/tree/main/packages/$1)#g;
    s#\[(?:\.\./)+frontend/packages/([a-z]+)/README\.md\]#[`packages/$1` in wasichai-ui]#g;
    s#hneyra/chawpi#wasichai/wasichai#g;    # maven repo url
    s#\@hneyra#\@wasichai#g;                # npm scope and registry line
    s#\@chawpi/#\@wasichai/#g;              # the scope ADR text used before the addendum
    s/CHAWPI_/WASICHAI_/g;
    s/CHAWPI/WASICHAI/g;
    s/Chawpi/Wasichai/g;
    s/chawpi/wasichai/g;
    s/\x00K([0-9a-f]+)\x00/pack("H*", $1)/ge;
  }
  close ARGV if eof;   # resets $. per file
' $FILES
echo "rename-docs: ok ($(echo "$FILES" | wc -l | tr -d ' ') files)"
```

```bash
bash "$SCRATCH/t5/rename-docs.sh"; rm -rf "$SCRATCH/t5/docs.run1"; cp -R docs "$SCRATCH/t5/docs.run1"
bash "$SCRATCH/t5/rename-docs.sh"; diff -r "$SCRATCH/t5/docs.run1" docs && echo "idempotent"
diff -r "$SCRATCH/t5/docs.before/superpowers" docs/superpowers && cmp "$SCRATCH/t5/docs.before/HISTORY.md" docs/HISTORY.md && echo "history untouched"
```

Expected: `rename-docs: ok (…)` twice, `idempotent`, `history untouched`.

- [ ] **Step 3: Provenance lines, ADR-029/030 status, ADR index.** For every ADR the script changed, add one
  provenance line (idempotent: only when missing). ADRs 0001–0023 get it as a second paragraph of their existing
  `> Imported from sapgis …` quote; ADRs 0024–0031 get it as a quote after the `**Status**` line.

```bash
cd /Users/jorge/IdeaProjects/wasichai
# two lines: one would pass 160 columns. the 2nd line names the origin page by file only (leftover grep masks it)
LINE=$'> Moved from chawpi on 2026-09-26: identifiers renamed chawpi → wasichai; the decision is unchanged. See\n> [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md) and [the origin page](../chawpi-origin.md).'
for f in docs/adr/00[0-2][0-9]-*.md docs/adr/0031-*.md; do
  cmp -s "$f" "$SCRATCH/t5/docs.before/adr/$(basename "$f")" && continue      # unchanged: no line
  grep -q '^> Moved from chawpi' "$f" && continue                              # already there
  if grep -q '^> Imported from sapgis' "$f"; then
    L="$LINE" perl -pi -e 's/^(> Imported from sapgis.*)$/$1\n>\n$ENV{L}/' "$f"
  else
    L="$LINE" perl -0pi -e 's/^(\*\*Status\*\*:[^\n]*\n)/$1\n$ENV{L}\n/m' "$f"
  fi
done
grep -L '^> Moved from chawpi' $(for f in docs/adr/00[0-2][0-9]-*.md docs/adr/0031-*.md; do cmp -s "$f" "$SCRATCH/t5/docs.before/adr/$(basename "$f")" || echo "$f"; done)
```

Expected: the final `grep -L` prints nothing (every changed ADR carries the line). Then three hand edits:

1. `docs/adr/0029-polyglot-monorepo-and-publishing.md`, status line becomes
   `**Status**: accepted · 2026-09-25 · amended by [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md)`.
2. `docs/adr/0030-rebrand-sapgis-to-chawpi.md` (body stays verbatim): status line becomes
   `**Status**: accepted · 2026-09-25 · names superseded by [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md)`,
   and one line after it (blank line before and after):
   `> Kept verbatim when chawpi became wasichai (2026-09-26): this ADR records the sapgis → chawpi rename. The current names are in ADR-032.`
3. `docs/adr/README.md`: append after the ADR-031 entry
   `- [ADR-032: Rebrand to wasichai and split into two repositories](0032-rebrand-to-wasichai-and-split-repositories.md)`.

- [ ] **Step 4: ADR-032.** Create `docs/adr/0032-rebrand-to-wasichai-and-split-repositories.md` with exactly:

````markdown
# ADR-032: Rebrand to wasichai and split into two repositories

**Status**: accepted · 2026-09-26 · amends [ADR-029](0029-polyglot-monorepo-and-publishing.md), supersedes the names in
[ADR-030](0030-rebrand-sapgis-to-chawpi.md)

## Context

The libraries were published from one repository, `hneyra/chawpi`, as the Maven group `chawpi` and the npm scope
`@chawpi`. GitHub Packages accepts an npm package only when its scope equals the owner of the repository that
publishes it, and the `chawpi` organization name is taken, so the npm packages had moved to `@hneyra/*` (ADR-029,
addendum): a product scope tied to a person's account. The organization **wasichai** is free and now exists, with two
empty repositories.

The monorepo also mixed two toolchains in every job: a Kotlin change waited for a yarn install, a frontend change
for Gradle, and one release version moved every package in both ecosystems even when only one side changed.

## Decision

**One name.** Everything called chawpi is called wasichai, and the old names are no longer read:

| chawpi | wasichai |
|---|---|
| Kotlin packages `chawpi.*`, types `Chawpi*` (`@ChawpiApplication`, `ChawpiException`, …) | `wasichai.*`, `Wasichai*` |
| Maven group `chawpi`, artifacts `chawpi-*` (`chawpi-bom`, `chawpi-spring-boot-starter[-x]`) | `wasichai`, `wasichai-*` |
| Gradle plugin ids `chawpi.*` | `wasichai.*` |
| properties `chawpi.*`, environment `CHAWPI_*` (incl. `CHAWPI_TEST_DB_*`) | `wasichai.*`, `WASICHAI_*` |
| migrations `db/chawpi/<module>` | `db/wasichai/<module>` |
| default metadata schema, database and user `chawpi` | `wasichai` |
| seed user `admin@chawpi.local`, JWT issuer `chawpi`, GeoServer workspace `chawpi` | `admin@wasichai.local`, `wasichai`, `wasichai` |
| problem base URI `https://chawpi.dev/problems` | `https://wasichai.dev/problems` |
| npm `@hneyra/*` (earlier `@chawpi/*`) | `@wasichai/*` |
| `ChawpiApp`, `ChawpiModule`, `useChawpiLinks`, …, `storagePrefix` `chawpi` | `WasichaiApp`, `WasichaiModule`, `useWasichaiLinks`, …, `wasichai` |
| Maven repository `maven.pkg.github.com/hneyra/chawpi` | `maven.pkg.github.com/wasichai/wasichai` |
| npm registry line `@hneyra:registry=…` | `@wasichai:registry=https://npm.pkg.github.com` |

Unchanged: the Flyway history tables (`flyway_history_<module>`), every table, column and route, the REST contract and
all behaviour. The npm scope equals the organization, so GitHub Packages accepts it.

**Two repositories.** [`wasichai/wasichai`](https://github.com/wasichai/wasichai) holds the Gradle build at its root
(`build-logic/`, `wasichai-*`, `starters/`), the sample servers (`examples/*/server`, `examples/gis-sample/perene`),
`infra/` and **all** documentation: ADRs, HISTORY, module docs, guides, API and security. Decisions stay in one place.
[`wasichai/wasichai-ui`](https://github.com/wasichai/wasichai-ui) holds the yarn workspace at its root (`packages/*`,
`tooling/`) and the sample webs (`examples/*/web`, the Playwright e2e in `full-sample`); its `docs/README.md` is a
frontend guide that links here. A sample is split along the same line: its server here, its web there.

**Independent versions.** This amends ADR-029's "one lockstep version": a single release cannot span two
repositories. Each repository runs its own release-please (wasichai: `simple`, bumps `gradle.properties`;
wasichai-ui: `node`, bumps the root and every public package) and publishes its own GitHub Release. Both start at
0.1.0 (`release-as`). Inside a repository the lockstep rule of ADR-029 still holds: every Maven artifact shares one
version (`wasichai-bom` aligns them), and every `@wasichai/*` package shares one version with internal ranges pinned
at publish time. Across repositories the REST API is the contract; a release note says when a UI release needs a
newer backend.

**Cross-repository e2e.** The full-sample Playwright smoke lives in wasichai-ui and starts the real server jar from
the backend checkout named by `WASICHAI_BACKEND_DIR` (default: the sibling directory `../wasichai`). In CI the e2e job
checks out `wasichai/wasichai` into a subdirectory with the repository secret `WASICHAI_REPO_TOKEN` (read access to
contents), builds `:full-sample-server:bootJar` there, and runs against a PostGIS service database `wasichai_full` on
port 5433.

**Guards and publishing per repository.** wasichai: `check-maven-publications.sh` (20 artifacts) before
`./gradlew publish` to `maven.pkg.github.com/wasichai/wasichai`. wasichai-ui: `tooling/check-release.mjs --pack`
(11 packages) before `npm publish` to `npm.pkg.github.com`. Each repository needs its own `RELEASE_PLEASE_TOKEN`.

**History.** Both repositories start with a fresh git history, copied from chawpi's working tree on 2026-09-26.
[`docs/chawpi-origin.md`](../chawpi-origin.md) says what moved where. ADRs keep their numbers; each ADR whose text
changed carries a "Moved from chawpi" line. The old name remains only where it is history: `docs/superpowers/**`,
past HISTORY entries, `docs/chawpi-origin.md`, `docs/sapgis-origin.md`, ADR-030 and ADR-029's addendum (kept
verbatim, they record earlier renames), provenance lines, this ADR, and the local test database, whose containers
and credentials on the test host keep their names (only the variable names changed).

## Consequences

- An app built on chawpi renames its dependencies, imports, `chawpi.*` keys and `CHAWPI_*` variables; nothing reads
  the old names. An existing database keeps working by setting `wasichai.database.metadata-schema=chawpi`: the Flyway
  history tables did not change names, so no migration runs again.
- A change that spans the REST contract is two pull requests, backend first. The UI's e2e job, which builds the
  backend's `main`, is where a mismatch shows up.
- Two sets of secrets, workflows and release PRs to keep. The guards stay per ecosystem, as before.
- Suites against the shared test database lock on `wasichai-test-suite`, so they no longer queue behind chawpi runs
  against the same database; run one project's suites at a time there.
````

- [ ] **Step 5: `docs/chawpi-origin.md`** (create) and the pointer in `docs/sapgis-origin.md`.

````markdown
# Where wasichai comes from

Wasichai started on 2026-09-26 as a copy of **chawpi**, the metadata-driven platform extracted from sapgis as
libraries (see [sapgis origin](sapgis-origin.md) for that earlier step). Nothing changed but the names and the
repository layout: [ADR-032](adr/0032-rebrand-to-wasichai-and-split-repositories.md) has the full naming table and the
reasons.

The git history was not imported. The source was chawpi's working tree, including its last uncommitted change (the
`@hneyra` npm scope, superseded here by `@wasichai`). What went where:

| chawpi | wasichai | wasichai-ui |
|---|---|---|
| `backend/build-logic`, `backend/chawpi-*`, `backend/starters` | `build-logic`, `wasichai-*`, `starters` (repo root) | — |
| `frontend/packages`, `frontend/tooling` | — | `packages`, `tooling` (repo root) |
| `examples/*/server`, `examples/gis-sample/perene` | same paths | — |
| `examples/*/web` (incl. the Playwright e2e) | — | same paths |
| `infra/docker` | same path | — |
| `docs/**` (ADRs, HISTORY, modules, guides, api, security, specs, plans) | same paths | `docs/README.md` links here |
| `.github/workflows`, `.github/scripts` | backend jobs, Maven publish | frontend jobs, e2e, npm publish |

Renames: `chawpi` → `wasichai`, `Chawpi` → `Wasichai`, `CHAWPI_` → `WASICHAI_`, `@hneyra/*` and `@chawpi/*` →
`@wasichai/*`, `maven.pkg.github.com/hneyra/chawpi` → `maven.pkg.github.com/wasichai/wasichai`. `docs/superpowers/**`
and the HISTORY entries before 2026-09-26 are verbatim, so they still say chawpi, `backend/` and `frontend/`.
````

In `docs/sapgis-origin.md` insert, after its `# Where chawpi comes from` title and a blank line, exactly one line
(skip if present), then a blank line:
`> Chawpi is now wasichai (2026-09-26). This page is kept as written; see [chawpi origin](chawpi-origin.md).`

- [ ] **Step 6: HISTORY entry.** Insert at the top of `docs/HISTORY.md`, right below the intro line
  (`Newest first. …`), exactly (skip if a `## 2026-09-26 — ` entry already exists):

```markdown
## 2026-09-26 — Chawpi becomes wasichai, in two repositories

The platform is renamed wasichai and lives in the `wasichai` GitHub organization: the Gradle libraries, the sample
servers and every doc in `wasichai/wasichai`, the npm packages and the sample webs in `wasichai/wasichai-ui`. Kotlin
packages, types, Maven coordinates, configuration keys, environment variables, the npm scope (`@wasichai/*`, equal to
the organization, as GitHub Packages requires), the React API (`WasichaiApp`, `WasichaiModule`, …) and the defaults
(schema, database, seed user, issuer) all say wasichai; tables, routes and behaviour are unchanged. Each repository
now has its own CI, its own release-please and its own version, both starting at 0.1.0, and the full-sample e2e runs
the web against a server built from the backend repository. ADR-032 records the decision and amends ADR-029; entries
below this one keep the old names.
```

- [ ] **Step 7: Hand edits in the living docs** (after the script; monorepo paths that the script cannot know).

1. `docs/development/getting-started.md`, section `## Layout`: replace the `frontend/` bullet and the `examples/`
   bullet with:
   `- \`examples/\` — the sample servers (\`examples/<sample>/server\`) and the Perené model (\`examples/gis-sample/perene\`). Their webs, and every npm package, live in [wasichai-ui](https://github.com/wasichai/wasichai-ui) (see its [docs/README.md](https://github.com/wasichai/wasichai-ui/blob/main/docs/README.md)).`
   (wrap at ~115 columns). In `## Run a sample app`, "Then run a sample's `server/` and `web/`" becomes "Then run a
   sample's `server/` (its web is in wasichai-ui)". In `## Tests`, drop the two `yarn` lines. In `## Conventions`,
   the formatting bullet ends "…enforced by `./gradlew ktlintFormat` for Kotlin; `yarn format` (prettier) covers the
   YAML and JSON here and the frontend code in wasichai-ui." In `## Adding a field type` step 2 append " (in
   wasichai-ui)".
2. `docs/modules/core.md` line with `` `frontend/packages/core/src/app/coreModule.ts` `` → ``(`packages/core/src/app/coreModule.ts` in
   [wasichai-ui](https://github.com/wasichai/wasichai-ui/tree/main/packages/core))``; `docs/modules/testing.md` line with
   `` `frontend/packages/testing/src/render.test.tsx` `` → `` `packages/testing/src/render.test.tsx` in wasichai-ui ``.
3. `docs/guides/build-your-app.md`: the `.npmrc` / registry lines now say `@wasichai:registry=https://npm.pkg.github.com`
   (the script did this); check that every package link points at `https://github.com/wasichai/wasichai-ui/tree/main/packages/<pkg>`
   and every Maven URL at `https://maven.pkg.github.com/wasichai/wasichai`.
4. `docs/architecture/overview.md`: any sentence that places the frontend in this repository (`frontend/`, "one
   repository") gets "in wasichai-ui"; the rest stays.
5. `docs/development/releasing.md`: replace the whole file with:

````markdown
# Releasing

This repository releases the Maven libraries. The npm packages are released from
[wasichai-ui](https://github.com/wasichai/wasichai-ui) on their own version (see its
[docs/README.md](https://github.com/wasichai/wasichai-ui/blob/main/docs/README.md#releasing));
[ADR-032](../adr/0032-rebrand-to-wasichai-and-split-repositories.md) explains why the two are independent.

## Flow

1. Merge conventional commits (`feat:`, `fix:`, ...) to `main`.
2. `release-please.yml` runs on every push to `main` and opens or updates a release PR that bumps `version=` in
   `gradle.properties` and writes `CHANGELOG.md`.
3. Merging that PR makes release-please tag `vX.Y.Z` and publish a GitHub Release.
4. The release triggers `publish.yml`: the `guards` job publishes to a throwaway local repository and runs
   `.github/scripts/check-maven-publications.sh`; only then the `maven` job runs `./gradlew publish` to
   `https://maven.pkg.github.com/wasichai/wasichai`, version = the tag without `v`.

## One-time setup (repository secrets)

| Secret | Repository | Needed for | Fine-grained PAT |
|---|---|---|---|
| `RELEASE_PLEASE_TOKEN` | `wasichai/wasichai` | a release that runs `publish.yml` | on this repo: Contents and Pull requests read/write |
| `RELEASE_PLEASE_TOKEN` | `wasichai/wasichai-ui` | the same, for the npm release | on wasichai-ui: the same |
| `WASICHAI_REPO_TOKEN` | `wasichai/wasichai-ui` | the UI `e2e` job's checkout of this repository | on `wasichai/wasichai`: Contents read |

A release made with the workflow's `GITHUB_TOKEN` triggers no other workflow, hence the PAT.

Settings → Secrets and variables → Actions → New repository secret. `release-please.yml` falls back to
`GITHUB_TOKEN` when `RELEASE_PLEASE_TOKEN` is absent: releases still happen, but nothing is published until the
secret exists. Publishing itself uses the workflow's `GITHUB_TOKEN` (`packages: write`).

## First release is pinned

`release-please-config.json` carries `"release-as": "0.1.0"`. Delete that key right after v0.1.0 ships.

## Versions

One version for every Maven artifact of this repository (`gradle.properties`); `wasichai-bom` aligns them. The npm
packages have their own version line.

## Guards

`.github/scripts/check-maven-publications.sh <dir>` compares what `./gradlew publishToMavenLocal
-Dmaven.repo.local=<dir>` produced with the twenty expected artifacts. CI runs it on every pull request
(`publish-dry-run` job) and `publish.yml` right before uploading. A new library fails it until it is added to the
script's list on purpose.

## Consuming a published library

Gradle (`settings.gradle.kts` or `~/.gradle/init.d/github-packages.init.gradle.kts`):

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/wasichai/wasichai")
        credentials {
            username = "<github-username>"
            password = "<PAT with read:packages>"
        }
    }
}
```

npm (`.npmrc`), for the frontend packages:

```
@wasichai:registry=https://npm.pkg.github.com
//npm.pkg.github.com/:_authToken=<PAT with read:packages>
```

GitHub Packages requires a token even for public packages; `read:packages` is enough to install.
````

- [ ] **Step 8: wasichai `README.md`, `CLAUDE.md`, `examples/README.md`** (replace whole files).

`README.md`:

````markdown
# Wasichai

**A metadata-driven application platform, as libraries.** An administrator defines Custom Objects, Custom Fields,
relationships, forms, views, pages, workflows, automations, documents and permissions; wasichai turns that metadata
into a working application at runtime: real PostgreSQL tables, a REST API, dynamic forms and tables, detail pages.
GIS (PostGIS geometry fields, maps, GeoServer) is one optional module among others.

This repository holds the backend (Kotlin, Spring Boot 4.1 WebFlux) and all documentation. The React packages live in
[wasichai-ui](https://github.com/wasichai/wasichai-ui).
Origin: [wasichai was chawpi until 2026-09-26](docs/chawpi-origin.md).

## Use it in your app

```kotlin
dependencies {
    implementation(platform("wasichai:wasichai-bom:0.1.0"))
    implementation("wasichai:wasichai-spring-boot-starter")          // core
    implementation("wasichai:wasichai-spring-boot-starter-documents") // opt-in module
}
```

Frontend: `<WasichaiApp config={{ apiBaseUrl: '/api' }} modules={[documentsModule()]} />` from `@wasichai/core`, see
[wasichai-ui](https://github.com/wasichai/wasichai-ui). Step by step, minimal to full:
[docs/guides/build-your-app.md](docs/guides/build-your-app.md). Modules: views, forms, pages, workflow, automation,
documents, gis, agent — one page each in [docs/modules](docs/modules/README.md).

## Layout

```
build-logic/   Gradle convention plugins (wasichai.kotlin-library, .spring-module, .publishing, .integration-test, .sample-app)
wasichai-*/    libraries: wasichai-core, wasichai-<module>, wasichai-bom, wasichai-test, wasichai-integration-tests
starters/      wasichai-spring-boot-starter and one wasichai-spring-boot-starter-<module> per module
examples/      sample servers (examples/<sample>/server) and the Perené model; their webs are in wasichai-ui
infra/         docker compose for local development
docs/          architecture, modules, guides, domain, api, gis, security, development, adr, HISTORY.md
```

## Commands

```bash
docker compose -f infra/docker/compose.yml up -d   # PostGIS + pgvector (add --profile gis for GeoServer)
./gradlew build                                   # ktlint + unit + architecture tests
./gradlew integrationTest                         # API tests against Testcontainers (or WASICHAI_TEST_DB_*)
```

Commits follow [Conventional Commits](https://www.conventionalcommits.org). Releases are cut by release-please and
published to GitHub Packages: [docs/development/releasing.md](docs/development/releasing.md).

## Decisions

Every architectural decision is an ADR in [docs/adr](docs/adr/README.md), frontend ones included; what shipped and
when is in [docs/HISTORY.md](docs/HISTORY.md).
````

`CLAUDE.md` (backend adaptation of the current one: same rules, no history section):

````markdown
# Wasichai — metadata-driven application platform, as libraries (backend + docs)

Reusable Spring Boot starters; an app adds the core and opts into modules, and metadata drives schema, API and UI at
runtime. The React packages are in the sibling repository `wasichai-ui` (`../wasichai-ui`); its decisions live here too.

## Non-negotiable stack

- **Backend**: Kotlin 2.4.20, Spring Boot 4.1 **WebFlux** (reactive), Gradle 9.7.1 (Kotlin DSL, version catalog,
  convention plugins in `build-logic`), JDK 25
- **Database**: PostgreSQL 18 (+ PostGIS only with `wasichai-gis`, + pgvector optional)
- **GIS module**: GeoServer (WMS/WFS/WMTS)
- **Infra**: Docker Compose for local development
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
   UI's e2e job builds this repository's `main`.
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
- Port 5432 is never used by a test or a sample default; integration tests use Testcontainers or `WASICHAI_TEST_DB_*`.

## Commands

```bash
docker compose -f infra/docker/compose.yml up -d
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
````

`examples/README.md`:

````markdown
# Examples

Each sample is a runnable app. Its `server/` (Spring Boot, built only from the wasichai starters and `wasichai-bom`)
is here; its `web/` (Vite + React, built only from the `@wasichai/*` packages it needs) is in
[wasichai-ui](https://github.com/wasichai/wasichai-ui/tree/main/examples). Samples are never published.

| Sample | Modules | Database | Ports: server, web |
|---|---|---|---|
| [`simple-sample`](simple-sample/README.md) | core | plain PostgreSQL, compose `--profile core` (5433), `wasichai` | 8091, 5171 |
| [`documents-sample`](documents-sample/README.md) | core, documents, automation | PostGIS (`postgres`), `wasichai_documents` | 8092, 5172 |
| [`gis-sample`](gis-sample/README.md) | core, gis (+ `perene/` model) | PostGIS, `wasichai_gis`; GeoServer optional | 8090, 5173 |
| [`full-sample`](full-sample/README.md) | every module | PostGIS, `wasichai_full`; GeoServer optional | 8093, 5174 |

Every sample:

```bash
./gradlew :<sample>-server:bootRun   # WASICHAI_DB_HOST/PORT/NAME/USERNAME/PASSWORD pick the database
```

Then, in a wasichai-ui checkout: `yarn install && yarn build` once, and `yarn workspace <sample>-web dev` (it proxies
`/api` to the server; see wasichai-ui's `examples/README.md`).

`WASICHAI_DB_PORT` has no default and is required for the documents, gis and full samples; only simple-sample
defaults it (to compose's plain PostgreSQL, 5433).

Port 5432 is never used on its own: no sample defaults to it and no test connects to it, so a PostgreSQL already
running there (another app's) is never touched by accident. compose's `postgres` service is published on 5432, so
pointing a sample at it is always an explicit `WASICHAI_DB_PORT=5432`.

Give each sample its own database: they install different modules, and one sample cannot read the field types
another left in a shared database. With compose, create the PostGIS ones once:

```bash
for db in wasichai_documents wasichai_gis wasichai_full; do docker exec wasichai-postgres createdb -U wasichai "$db"; done
```

Log in as `admin@wasichai.local` / `admin` (the dev seed, `wasichai.seed.dev`, on by default in the samples).

Tests: `./gradlew :<sample>-server:integrationTest` (Testcontainers, or an external database through
`WASICHAI_TEST_DB_*`). The web tests and the full-sample Playwright e2e run in wasichai-ui.
````

Per-sample READMEs, for each `s` in `simple documents gis full`:

```bash
cd /Users/jorge/IdeaProjects/wasichai
for s in simple documents gis full; do
  f="examples/$s-sample/README.md"
  cp "/Users/jorge/IdeaProjects/chawpi/examples/$s-sample/README.md" "$f"
  perl -pi -e 's#hneyra/chawpi#wasichai/wasichai#g; s#\@hneyra#\@wasichai#g; s#\@chawpi/#\@wasichai/#g;
               s/CHAWPI_/WASICHAI_/g; s/CHAWPI/WASICHAI/g; s/Chawpi/Wasichai/g; s/chawpi/wasichai/g' "$f"
done
```

Then, by hand in each of the four files: the `| Web |` table row becomes
`| Web | in [wasichai-ui](https://github.com/wasichai/wasichai-ui/tree/main/examples), workspace \`<s>-sample-web\`, port <port> |`
(keep the row under 160 columns);
the two lines `# 3. the web …` and `yarn workspace <s>-sample-web dev …` in the Run block become one comment line
`# 3. the web: in wasichai-ui, yarn workspace <s>-sample-web dev (see its examples/README.md)`; the
`WASICHAI_API_URL (web)` table row is removed; in gis-sample, the `web/src/main.tsx` / `web/src/App.tsx` bullet gets
"(in wasichai-ui)" and the `yarn workspace gis-sample-web test` line is removed; in full-sample, any e2e / Playwright
paragraph is replaced with "The Playwright smoke runs in wasichai-ui (`yarn workspace full-sample-web e2e`, with
`WASICHAI_BACKEND_DIR` pointing at this repository)."

- [ ] **Step 9: wasichai-ui `README.md`, `CLAUDE.md`, `docs/README.md`, `examples/README.md`** (create or replace).

`README.md`:

````markdown
# wasichai-ui

The React packages of [wasichai](https://github.com/wasichai/wasichai), a metadata-driven application platform built
as libraries: the app shell, one package per backend module, UI primitives and test helpers, published as
`@wasichai/*` to GitHub Packages. The backend, every ADR and the module docs live in
[wasichai/wasichai](https://github.com/wasichai/wasichai).

```tsx
import { WasichaiApp } from '@wasichai/core'
import { documentsModule } from '@wasichai/documents'

<WasichaiApp config={{ apiBaseUrl: '/api' }} modules={[documentsModule()]} />
```

| Package | What |
|---|---|
| `@wasichai/ui` | primitives and the Tailwind theme |
| `@wasichai/core` | the app shell, module registry, api client, auth, i18n and every screen that needs no module |
| `@wasichai/{views,forms,pages,workflow,automation,documents,gis,agent}` | one package per backend module |
| `@wasichai/testing` | render helpers and fetch mocks for tests |

Install (`.npmrc`: `@wasichai:registry=https://npm.pkg.github.com` plus a token with `read:packages`):
`yarn add @wasichai/core @wasichai/ui`. Step by step:
[build your app](https://github.com/wasichai/wasichai/blob/main/docs/guides/build-your-app.md).

## Layout

```
packages/   @wasichai/* (ui, core, testing, one per module; smoke is private)
tooling/    release and scaffolding scripts (check-release, set-version, run-ordered, scaffold-module)
examples/   the sample webs (examples/<sample>/web); their servers are in wasichai
docs/       frontend development guide
```

## Commands

```bash
yarn install && yarn lint && yarn test && yarn build
yarn test:tooling
```

Development, the sample webs, the cross-repository e2e and releasing: [docs/README.md](docs/README.md).
Origin: [wasichai was chawpi until 2026-09-26](https://github.com/wasichai/wasichai/blob/main/docs/chawpi-origin.md).
````

`CLAUDE.md`:

````markdown
# wasichai-ui — the React packages of wasichai

The frontend of a metadata-driven platform built as libraries. The backend, the docs and every ADR (frontend ones
included) are in the sibling repository `wasichai` (`../wasichai`): read its `docs/` before changing behaviour.

## Non-negotiable stack

- React 19.3 (yarn 1 workspaces) + Vite 8 + Tailwind CSS 4 + shadcn-style components + i18next + react-router +
  react-query + zod; MapLibre GL JS in `@wasichai/gis`
- Node 26; vitest + testing-library; Playwright for the full-sample e2e
- Published as `@wasichai/*` to `npm.pkg.github.com`

## Architectural rules

1. **Metadata-driven**: never generate code per Custom Object. Screens read metadata from the API at runtime.
2. **Frontend modules register themselves** (`WasichaiModule`: routes, nav, field renderers, page components, slots,
   i18n) (ADR-028). No hardcoded routes or URLs outside the registry; links come from `useWasichaiLinks()`.
3. **A module package stays installable on its own**: it imports `@wasichai/core`, `@wasichai/ui` and its own
   libraries, never another module package or another module's heavy library (each package's `boundaries.test.ts`).
   Heavy libraries stay behind lazy routes.
4. **The REST API of wasichai is the contract** (ADR-032). A change that needs a new endpoint lands in wasichai first.
5. **Same behaviour as the original app**, except the entries of ADR-031.
6. **No overengineering**: an abstraction needs a concrete second user.

## Working rules

- **`.editorconfig` is law** (TS/JSON/YAML/MD 2 spaces where the file says so, max 160 columns, LF, final newline).
  Format before finishing: `yarn format`. Markdown is hand-formatted to the same rules.
- **Conventional Commits** for every commit and PR title (`feat(gis): …`, `fix(core): …`), enforced by commitlint.
- Code, identifiers and comments in **English**. Comments **caveman style**: short, say why.
- Decisions are ADRs in wasichai's `docs/adr/`; change history in wasichai's `docs/HISTORY.md`.

## Commands

```bash
yarn install && yarn lint && yarn test && yarn build
yarn test:tooling                                   # tooling/*.test.mjs
node tooling/check-release.mjs --pack 0.0.0-local   # what a release would publish
yarn workspace full-sample-web e2e                  # needs the server jar built in ../wasichai (docs/README.md)
```

## Definition of Done

Works · has tests · handles errors · is documented · does not break existing features · lint, tests and build pass ·
modules stay independent · behaviour matches the original app or ADR-031 · formatted with `.editorconfig`.
````

`docs/README.md`:

````markdown
# Frontend development

The React side of [wasichai](https://github.com/wasichai/wasichai). Architecture, modules, API and decisions are
documented there; this page covers working in this repository.

## Read first (in wasichai)

- [Architecture overview](https://github.com/wasichai/wasichai/blob/main/docs/architecture/overview.md)
- [Modules](https://github.com/wasichai/wasichai/blob/main/docs/modules/README.md) (one page each, frontend part included) ·
  [Build your app](https://github.com/wasichai/wasichai/blob/main/docs/guides/build-your-app.md) ·
  [REST API](https://github.com/wasichai/wasichai/blob/main/docs/api/rest.md)
- ADRs: [ADR-028 Frontend modules plug into a registry](https://github.com/wasichai/wasichai/blob/main/docs/adr/0028-frontend-module-registry.md),
  [ADR-029 publishing](https://github.com/wasichai/wasichai/blob/main/docs/adr/0029-polyglot-monorepo-and-publishing.md),
  [ADR-031 deviations](https://github.com/wasichai/wasichai/blob/main/docs/adr/0031-deliberate-deviations-from-sapgis.md),
  [ADR-032 rebrand and repository split](https://github.com/wasichai/wasichai/blob/main/docs/adr/0032-rebrand-to-wasichai-and-split-repositories.md),
  [all ADRs](https://github.com/wasichai/wasichai/blob/main/docs/adr/README.md)

## Requirements and layout

Node 26, Yarn 1. `packages/*` and `examples/*/web` are the yarn workspaces. `tooling/` holds `run-ordered.mjs`
(builds packages in dependency order), `check-release.mjs`, `set-version.mjs` and `scaffold-module.mjs`.

## Commands

```bash
yarn install
yarn format:check && yarn lint   # prettier + tsc per workspace
yarn test                        # vitest in every package and sample web
yarn test:tooling                # node --test tooling/*.test.mjs
yarn build                       # every package, then every sample web against the packages' dist
```

## Sample webs and the backend

Each sample web proxies `/api` to its server from wasichai (`WASICHAI_API_URL`, default the sample's server port).
Start the server there (`./gradlew :<sample>-server:bootRun`, see wasichai's
[examples/README.md](https://github.com/wasichai/wasichai/blob/main/examples/README.md)), then
`yarn workspace <sample>-web dev`. Log in as `admin@wasichai.local` / `admin`.

## The cross-repository e2e

`examples/full-sample/web` runs a Playwright smoke against the real server jar. It expects the backend checkout at
`WASICHAI_BACKEND_DIR` (default `../wasichai`, a sibling of this repository) and a PostGIS database for the server:

```bash
(cd ../wasichai && ./gradlew :full-sample-server:bootJar)
WASICHAI_DB_PORT=<postgis port> WASICHAI_DB_NAME=wasichai_full yarn workspace full-sample-web e2e
```

Locally Playwright reuses a server already listening on 8093 or 5174; make sure nothing else is. CI (`e2e` job)
checks out `wasichai/wasichai` into a subdirectory and runs against a PostGIS service on port 5433.

## Releasing

release-please (`node`) bumps the root `package.json` and every public `packages/*/package.json` together and writes
`CHANGELOG.md`; merging its PR tags `vX.Y.Z`, and the release runs `publish.yml`: `check-release.mjs --pack` (exactly
the eleven public `@wasichai/*` packages, internal ranges pinned, entry points in every tarball), then
`set-version.mjs` and `npm publish` to `https://npm.pkg.github.com`. The version is independent of the Maven
libraries' (ADR-032). The first release is pinned with `"release-as": "0.1.0"`; delete that key after v0.1.0.

One-time repository secrets (Settings → Secrets and variables → Actions):

| Secret | Needed for | Fine-grained PAT |
|---|---|---|
| `RELEASE_PLEASE_TOKEN` | a release that runs `publish.yml` (one made with `GITHUB_TOKEN` runs nothing) | on this repo: Contents and Pull requests read/write |
| `WASICHAI_REPO_TOKEN` | the `e2e` job's checkout of `wasichai/wasichai` | on `wasichai/wasichai`: Contents read |

Consumers: `.npmrc` with `@wasichai:registry=https://npm.pkg.github.com` and `//npm.pkg.github.com/:_authToken=<PAT
with read:packages>`.
````

`examples/README.md` (wasichai-ui):

````markdown
# Sample webs

The web half of each wasichai sample: Vite + React, built only from the `@wasichai/*` packages it needs, never
published. The servers, databases and logins are described in wasichai's
[examples/README.md](https://github.com/wasichai/wasichai/blob/main/examples/README.md).

| Web | Workspace | Port | Proxies `/api` to (`WASICHAI_API_URL`) | Packages |
|---|---|---|---|---|
| `simple-sample/web` | `simple-sample-web` | 5171 | `http://localhost:8091` | core, ui |
| `documents-sample/web` | `documents-sample-web` | 5172 | `http://localhost:8092` | core, ui, documents, automation |
| `gis-sample/web` | `gis-sample-web` | 5173 | `http://localhost:8090` | core, ui, gis (+ `maplibre-gl`) |
| `full-sample/web` | `full-sample-web` | 5174 | `http://localhost:8093` | core, ui and all eight modules; Playwright e2e |

```bash
yarn install && yarn build            # once: builds the @wasichai/* packages the webs import
yarn workspace <sample>-web dev
yarn workspace <sample>-web test
```

The full-sample e2e is described in [docs/README.md](../docs/README.md#the-cross-repository-e2e).
````

- [ ] **Step 10: Checks** (MD check from Global Constraints on every file T5 touched, in both repos; leftover grep on
  T5's files)

```bash
cd /Users/jorge/IdeaProjects/wasichai
T5W="README.md CLAUDE.md examples/README.md examples/*-sample/README.md $(find docs -name '*.md' -not -path 'docs/superpowers/*')"
# MD check: paste the Global Constraints block with FILES="$T5W"; then the same in wasichai-ui with
# FILES="README.md CLAUDE.md docs/README.md examples/README.md"
command grep -nE 'chawpi|Chawpi|CHAWPI|hneyra' $T5W \
  | command grep -vE '^docs/(HISTORY\.md|chawpi-origin\.md|sapgis-origin\.md|adr/0030-|adr/0032-)' \
  | command grep -vE '^docs/adr/00[0-9]{2}-[a-z0-9-]+\.md:[0-9]+:> (Imported from sapgis|Moved from chawpi)' \
  | command grep -vE '^docs/adr/0029-' \
  | command grep -vE '^(README|CLAUDE)\.md:[0-9]+:.*chawpi-origin' \
  | sed -E 's/(rebrand-sapgis-to-chawpi|chawpi-libraries-design|chawpi-origin)//g' | command grep -E 'chawpi|Chawpi|CHAWPI|hneyra'
sed -n '/^## Addendum (2026-09-25)/,$d; p' docs/adr/0029-polyglot-monorepo-and-publishing.md | command grep -nE 'chawpi|Chawpi|hneyra' | command grep -v 'Moved from chawpi\|0032-\|rebrand-sapgis-to-chawpi'
```

Expected: MD check prints nothing in both repos; both greps print nothing. The longer name pushes exactly one line
over 160 columns in the dry run (`docs/guides/build-your-app.md`, the `gis` row of the module table, 161): shorten
that row's last prose cell by hand. The HISTORY and origin docs are allowed
whole; ADR-029 is checked only above its addendum. A broken link that points at a file T3/T4/T6 still has to create
is reported as "pending T<n>", not fixed.

- [ ] **Step 11: Report** (no commit): files created/changed per repo, the two script outputs, Step 10 results, any
  sentence changed by hand beyond Step 7 (file:line, why), and every `route to T<n>` item.

### Task 6 (T6): CI and release, one set per repository

**Files (wasichai):**
- Replace: `.github/workflows/ci.yml`, `.github/workflows/publish.yml`, `.github/workflows/release-please.yml`,
  `.github/scripts/check-maven-publications.sh`, `release-please-config.json`, `.release-please-manifest.json`.
- Keep (verify byte-identical to chawpi's): `.github/workflows/commits.yml`.

**Files (wasichai-ui):**
- Create: `release-please-config.json`, `.release-please-manifest.json` (Step 1, first),
  `.github/workflows/{ci,commits,release-please,publish}.yml`.

**Interfaces:**
- Consumes: T3's Gradle names (`./gradlew -p build-logic test`, `:<sample>-server:bootJar`, `app.jar`,
  `integrationTest`, group dir `wasichai/`), T3's env (`WASICHAI_DB_*`), T4's scripts (`tooling/check-release.mjs`,
  `tooling/set-version.mjs`), workspaces (`<sample>-web` with `typecheck`, `test`, `e2e`), scope `@wasichai`,
  `WASICHAI_BACKEND_DIR` in `playwright.config.ts`. Both repos' root `package.json` have `format:check` and
  `commitlint` (wasichai: `wasichai-build`, commitlint/husky/prettier only).
- Produces: job ids (T5 documents them) — wasichai `ci.yml`: `backend`, `integration`, `examples-servers`, `perene`,
  `format`, `publish-dry-run`; `publish.yml`: `guards`, `maven`. wasichai-ui `ci.yml`: `frontend`, `sample-webs`,
  `release-guard`, `e2e`; `publish.yml`: `guards`, `npm`. Secrets: `RELEASE_PLEASE_TOKEN` (both), `WASICHAI_REPO_TOKEN`
  (wasichai-ui). wasichai-ui `release-please-config.json` whose `packages["."]["extra-files"]` lists exactly the 11
  public `packages/<pkg>/package.json` (T4's `check-release.mjs` reads it).

- [ ] **Step 1: wasichai-ui release-please files first** (T4 Step 7 waits on them). Create
  `/Users/jorge/IdeaProjects/wasichai-ui/release-please-config.json`:

```json
{
    "$schema": "https://raw.githubusercontent.com/googleapis/release-please/main/schemas/config.json",
    "include-component-in-tag": false,
    "packages": {
        ".": {
            "release-type": "node",
            "package-name": "wasichai-ui",
            "changelog-path": "CHANGELOG.md",
            "bump-minor-pre-major": true,
            "release-as": "0.1.0",
            "extra-files": [
                { "type": "json", "path": "packages/agent/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "packages/automation/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "packages/core/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "packages/documents/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "packages/forms/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "packages/gis/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "packages/pages/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "packages/testing/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "packages/ui/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "packages/views/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "packages/workflow/package.json", "jsonpath": "$.version" }
            ]
        }
    }
}
```

`node` bumps the root `package.json` itself; `extra-files` bumps the eleven public packages (not `packages/smoke`,
which is private: `check-release.mjs` rejects a bumped non-public package). And
`/Users/jorge/IdeaProjects/wasichai-ui/.release-please-manifest.json`:

```json
{
    ".": "0.1.0"
}
```

- [ ] **Step 2: wasichai `ci.yml`** — replace `/Users/jorge/IdeaProjects/wasichai/.github/workflows/ci.yml` with:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  backend:
    name: Backend build
    runs-on: ubuntu-latest
    # a hung gradle or test jvm must not burn the default 6 h
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v4
      - name: build-logic tests
        run: ./gradlew -p build-logic test --no-daemon
      # ktlint + unit + architecture tests of every library, starter and sample server
      - name: Build
        run: ./gradlew build --no-daemon
      - name: Upload test reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: backend-test-reports
          path: '**/build/reports/tests/'

  integration:
    name: Integration tests
    runs-on: ubuntu-latest
    # a hung testcontainers pull or wait must not burn the default 6 h
    timeout-minutes: 120
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v4
      # github runners have a local docker daemon: testcontainers starts postgres:18 and
      # postgis/postgis:18-3.6 per suite. no WASICHAI_TEST_DB_* here, on purpose.
      # pull once up front: two suites must not race the same pull into their startup timeout
      - name: Pull database images
        run: docker pull -q postgres:18 && docker pull -q postgis/postgis:18-3.6
      # every library, wasichai-integration-tests and every sample server apply wasichai.integration-test,
      # so the one task name reaches all of them. one container per test jvm: two workers, two databases.
      - name: Integration tests
        run: ./gradlew integrationTest --no-daemon --max-workers=2
      - name: Upload test reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: integration-test-reports
          path: '**/build/reports/tests/'

  examples-servers:
    name: Examples (servers)
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v4
      # the runnable jars people start (and wasichai-ui's e2e builds): each one must assemble
      - name: Boot jars
        run: ./gradlew :simple-sample-server:bootJar :documents-sample-server:bootJar :gis-sample-server:bootJar :full-sample-server:bootJar --no-daemon
      - name: One app.jar per sample
        run: ls examples/{simple,documents,gis,full}-sample/server/build/libs/app.jar

  perene:
    name: Perené model
    runs-on: ubuntu-latest
    # a hung unittest run must not burn the default 6 h
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.12'
      - name: Unit tests
        working-directory: examples/gis-sample/perene
        run: python -m unittest -v

  format:
    name: Format (prettier)
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '26'
          cache: yarn
      - run: yarn install --frozen-lockfile
      # yaml and json here; kotlin is ktlint's (backend job), markdown is hand-formatted
      - run: yarn format:check

  publish-dry-run:
    name: Publish dry run (Maven)
    runs-on: ubuntu-latest
    # a hung gradle publish must not burn the default 6 h
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v4
      - name: Publish to a throwaway local repository
        run: ./gradlew publishToMavenLocal --no-daemon -Pversion=0.0.0-ci -Dmaven.repo.local="$RUNNER_TEMP/m2"
      - name: Release guard (Maven)
        run: .github/scripts/check-maven-publications.sh "$RUNNER_TEMP/m2"
```

What moved out: the `frontend` and `e2e` jobs (now in wasichai-ui). What is new: `examples-servers` (the four boot
jars, which the UI e2e depends on) and `format` (prettier used to run in the frontend job). The integration job keeps
its 120-minute bound and `--max-workers=2`.

- [ ] **Step 3: wasichai `release-please.yml`, `publish.yml`, `commits.yml`, guard script, release-please files.**

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
      # one version for every maven library of this repo. merging its pr tags and creates the github release.
      - uses: googleapis/release-please-action@v4
        with:
          config-file: release-please-config.json
          manifest-file: .release-please-manifest.json
          # a release made with GITHUB_TOKEN does not trigger other workflows; a PAT lets publish.yml run
          token: ${{ secrets.RELEASE_PLEASE_TOKEN || secrets.GITHUB_TOKEN }}
```

`.github/workflows/publish.yml`:

```yaml
name: Publish

on:
  release:
    types: [published]

permissions:
  contents: read
  packages: write

jobs:
  # the guard runs before the upload: a failed guard blocks it, so a release never publishes the wrong set
  guards:
    name: Release guard (Maven)
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v4
      - name: Release guard (Maven)
        run: |
          ./gradlew publishToMavenLocal --no-daemon -Pversion="${GITHUB_REF_NAME#v}" -Dmaven.repo.local="$RUNNER_TEMP/m2"
          .github/scripts/check-maven-publications.sh "$RUNNER_TEMP/m2"

  maven:
    name: Maven libraries
    needs: guards
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v4
      # wasichai.publishing targets maven.pkg.github.com/$GITHUB_REPOSITORY = wasichai/wasichai
      - name: Publish to GitHub Packages
        env:
          GITHUB_ACTOR: ${{ github.actor }}
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: ./gradlew publish --no-daemon -Pversion="${GITHUB_REF_NAME#v}"
```

`.github/workflows/commits.yml`: unchanged from chawpi (T1 copied it); verify with
`cmp /Users/jorge/IdeaProjects/chawpi/.github/workflows/commits.yml .github/workflows/commits.yml`. It runs
`yarn commitlint` from the root `package.json` and the semantic PR title check.

`.github/scripts/check-maven-publications.sh` (keep the executable bit):

```bash
#!/usr/bin/env bash
# what `./gradlew publish` would upload, checked before it does: publish to a throwaway local
# repository first, then compare the artifact ids with the list below. a new library fails here
# until someone adds it on purpose; a sample or the integration tests leaking in fails here too.
# usage: check-maven-publications.sh <local-repo-dir>   (the dir given to -Dmaven.repo.local)
set -euo pipefail

repo="${1:?usage: check-maven-publications.sh <local-repo-dir>}"

expected="wasichai-agent
wasichai-automation
wasichai-bom
wasichai-core
wasichai-documents
wasichai-forms
wasichai-gis
wasichai-pages
wasichai-spring-boot-starter
wasichai-spring-boot-starter-agent
wasichai-spring-boot-starter-automation
wasichai-spring-boot-starter-documents
wasichai-spring-boot-starter-forms
wasichai-spring-boot-starter-gis
wasichai-spring-boot-starter-pages
wasichai-spring-boot-starter-views
wasichai-spring-boot-starter-workflow
wasichai-test
wasichai-views
wasichai-workflow"

if [ ! -d "$repo/wasichai" ]; then
  echo "maven publications: nothing under $repo/wasichai" >&2
  exit 1
fi

actual="$(ls "$repo/wasichai" | LC_ALL=C sort)"
if [ "$actual" != "$(printf '%s\n' "$expected" | LC_ALL=C sort)" ]; then
  echo "maven publications differ from the expected set (< expected, > actual):" >&2
  diff <(printf '%s\n' "$expected" | LC_ALL=C sort) <(printf '%s\n' "$actual") >&2 || true
  exit 1
fi
echo "maven publications: ok ($(printf '%s\n' "$actual" | wc -l | tr -d ' '))"
```

(This is chawpi's script with `chawpi` → `wasichai`; `perl -pe 's/chawpi/wasichai/g'` of the chawpi file produces it
byte for byte.)

`release-please-config.json`:

```json
{
    "$schema": "https://raw.githubusercontent.com/googleapis/release-please/main/schemas/config.json",
    "include-component-in-tag": false,
    "packages": {
        ".": {
            "release-type": "simple",
            "package-name": "wasichai",
            "changelog-path": "CHANGELOG.md",
            "bump-minor-pre-major": true,
            "release-as": "0.1.0",
            "extra-files": ["gradle.properties"]
        }
    }
}
```

`.release-please-manifest.json`: `{ ".": "0.1.0" }` written as in Step 1 (4-space JSON). `gradle.properties` already
carries the `# x-release-please-start-version` / `# x-release-please-end` markers around `version=`; leave them.

- [ ] **Step 4: wasichai-ui `ci.yml`** — create `/Users/jorge/IdeaProjects/wasichai-ui/.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  frontend:
    name: Frontend
    runs-on: ubuntu-latest
    # a hung yarn install or vite build must not burn the default 6 h
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '26'
          cache: yarn
      - run: yarn install --frozen-lockfile
      - run: yarn format:check
      - run: yarn test:tooling
      - run: yarn lint
      - run: yarn test
      # every package, then every sample web against the packages' dist
      - run: yarn build

  sample-webs:
    name: Sample web (${{ matrix.sample }})
    runs-on: ubuntu-latest
    timeout-minutes: 20
    strategy:
      fail-fast: false
      matrix:
        sample: [simple-sample, documents-sample, gis-sample, full-sample]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '26'
          cache: yarn
      - run: yarn install --frozen-lockfile
      # the web imports the packages' dist: build them (and the webs) in dependency order first
      - run: yarn build
      - run: yarn workspace ${{ matrix.sample }}-web typecheck
      - run: yarn workspace ${{ matrix.sample }}-web test
      # tailwind scanned the @wasichai packages: their utility classes are in the bundle
      - name: Tailwind scanned the packages
        run: grep -l '\.flex{' examples/${{ matrix.sample }}/web/dist/assets/*.css

  release-guard:
    name: Release guard (npm)
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '26'
          cache: yarn
      - run: yarn install --frozen-lockfile
      - run: yarn build
      - run: node tooling/check-release.mjs --pack 0.0.0-ci

  e2e:
    name: End-to-end (full-sample, against wasichai)
    runs-on: ubuntu-latest
    timeout-minutes: 30
    services:
      # the sample's own database, created by the image. published on 5433, never 5432: the
      # sample has no default port, so the one below is the only one it can reach.
      postgres:
        image: postgis/postgis:18-3.6
        env:
          POSTGRES_DB: wasichai_full
          POSTGRES_USER: wasichai
          POSTGRES_PASSWORD: wasichai
        ports:
          - 5433:5432
        options: >-
          --health-cmd "pg_isready -U wasichai -d wasichai_full"
          --health-interval 5s
          --health-timeout 5s
          --health-retries 20
    env:
      # playwright starts the jar and the dev server itself, never reuses running ones
      CI: '1'
      WASICHAI_DB_HOST: localhost
      WASICHAI_DB_PORT: '5433'
      WASICHAI_DB_NAME: wasichai_full
      WASICHAI_DB_USERNAME: wasichai
      WASICHAI_DB_PASSWORD: wasichai
      # playwright.config.ts starts <this>/examples/full-sample/server/build/libs/app.jar
      WASICHAI_BACKEND_DIR: ${{ github.workspace }}/wasichai
    steps:
      - uses: actions/checkout@v4
      # the server lives in the backend repo. a fine-grained token with contents:read on it; the
      # workflow token only reaches this repository
      - uses: actions/checkout@v4
        with:
          repository: wasichai/wasichai
          path: wasichai
          token: ${{ secrets.WASICHAI_REPO_TOKEN }}
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v4
      - name: Server jar
        working-directory: wasichai
        run: ./gradlew :full-sample-server:bootJar --no-daemon
      - uses: actions/setup-node@v4
        with:
          node-version: '26'
          cache: yarn
      - run: yarn install --frozen-lockfile
      # the dev server resolves @wasichai/* through their dist
      - run: yarn build
      - name: Headless browser
        working-directory: examples/full-sample/web
        run: npx playwright install --with-deps chromium
      - name: Playwright smoke
        run: yarn workspace full-sample-web e2e
      - name: Upload playwright traces
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: e2e-test-results
          path: examples/full-sample/web/test-results/
```

The backend checkout lands in `wasichai/` inside the workspace: yarn ignores it (not a workspace pattern), and no job
of this workflow runs prettier over it.

- [ ] **Step 5: wasichai-ui `commits.yml`, `release-please.yml`, `publish.yml`.**

`commits.yml`: byte-identical copy of `/Users/jorge/IdeaProjects/chawpi/.github/workflows/commits.yml` (the root
`package.json` of wasichai-ui has `@commitlint/cli` and `yarn.lock`).

`release-please.yml`: the wasichai one from Step 3 with the comment line reading
`# one version for every public npm package of this repo. merging its pr tags and creates the github release.`

`publish.yml`:

```yaml
name: Publish

on:
  release:
    types: [published]

permissions:
  contents: read
  packages: write

jobs:
  # the guard runs before the upload: a failed guard blocks it, so a release never half-publishes
  guards:
    name: Release guard (npm)
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '26'
          cache: yarn
      - run: yarn install --frozen-lockfile && yarn build
      - name: Release guard (npm)
        run: node tooling/check-release.mjs --pack "${GITHUB_REF_NAME#v}"

  npm:
    name: npm packages
    needs: guards
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '26'
          cache: yarn
          registry-url: https://npm.pkg.github.com
          # the scope must equal the organization: github packages refuses any other
          scope: '@wasichai'
      - run: yarn install --frozen-lockfile && yarn build
      - name: Publish every public @wasichai package
        env:
          NODE_AUTH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          version="${GITHUB_REF_NAME#v}"
          # bumps every package's own version AND internal @wasichai/* dep ranges; plain
          # `npm version` per package would leave internal deps pointing at the old version.
          node tooling/set-version.mjs "$version"
          for dir in packages/*/; do
            if [ "$(node -p "require('./$dir/package.json').private === true")" = "true" ]; then continue; fi
            (cd "$dir" && npm publish)
          done
```

- [ ] **Step 6: Lint, format, cross-check against T3/T4 names**

```bash
for r in /Users/jorge/IdeaProjects/wasichai /Users/jorge/IdeaProjects/wasichai-ui; do
  (cd "$r" && actionlint && echo "$r actionlint ok")
  (cd "$r" && yarn -s prettier --check .github release-please-config.json)
done
cd /Users/jorge/IdeaProjects/wasichai
bash -n .github/scripts/check-maven-publications.sh && test -x .github/scripts/check-maven-publications.sh && echo "guard ok"
cmp /Users/jorge/IdeaProjects/chawpi/.github/workflows/commits.yml .github/workflows/commits.yml && echo "commits same"
cmp /Users/jorge/IdeaProjects/chawpi/.github/workflows/commits.yml /Users/jorge/IdeaProjects/wasichai-ui/.github/workflows/commits.yml && echo "ui commits same"
command grep -rnE 'chawpi|Chawpi|CHAWPI|hneyra|frontend/|backend/' .github release-please-config.json \
  /Users/jorge/IdeaProjects/wasichai-ui/.github /Users/jorge/IdeaProjects/wasichai-ui/release-please-config.json
node -e 'const c = require("/Users/jorge/IdeaProjects/wasichai-ui/release-please-config.json").packages["."]["extra-files"]; console.log(c.length, c.every((f) => require("fs").existsSync("/Users/jorge/IdeaProjects/wasichai-ui/" + f.path)))'
```

Expected: `actionlint ok` for both repos; prettier `All matched files use Prettier code style!` twice; `guard ok`;
`commits same`, `ui commits same`; the grep prints nothing; `11 true`. These drafts were checked on 2026-09-26 with
actionlint and the repositories' prettier config: clean. If T3 or T4 has not finished, the grep and the `existsSync`
check can still run (they read T6's own files and `packages/*`); the end-to-end proof of the workflows is T7.

- [ ] **Step 7: Report** (no commit): files written per repo, Step 6 output, and the user actions the workflows need:
  create `RELEASE_PLEASE_TOKEN` in both repositories and `WASICHAI_REPO_TOKEN` in wasichai-ui (scopes in
  `docs/development/releasing.md`, written by T5).

### Task 7 (T7, Wave 3): final verification of both repositories

**Files:** none created or modified in either repository. Scratch only: `$SCRATCH/t7/`. A defect is routed to its
owner task (ownership table) for one fix round, then the affected steps rerun.

**Interfaces:**
- Consumes: everything T3–T6 produced (names in their Interfaces blocks), the tunnel, the demo DB `wasichai_full` on
  5442 (T3 Step 8).
- Produces: the verification record in the report, one line per step with the exact numbers.

- [ ] **Step 1: Preconditions**

```bash
test -d /Users/jorge/IdeaProjects/wasichai/wasichai-core -a -f /Users/jorge/IdeaProjects/wasichai-ui/release-please-config.json \
  -a -f /Users/jorge/IdeaProjects/wasichai/docs/adr/0032-rebrand-to-wasichai-and-split-repositories.md \
  -a -f /Users/jorge/IdeaProjects/wasichai-ui/.github/workflows/ci.yml && echo "wave 2 landed"
mkdir -p "$SCRATCH/t7"
```

Expected: `wave 2 landed`, and all four T3–T6 reports green. No other agent runs Gradle or yarn in either repository
during T7 (T7 is alone in Wave 3); still, never `./gradlew --stop`.

- [ ] **Step 2: wasichai, full**

```bash
bash /private/tmp/claude-502/-Users-jorge-IdeaProjects-chawpi/73f54845-6d0f-493f-961b-10662da123e9/scratchpad/tunnel.sh
source /Users/jorge/IdeaProjects/wasichai/wasichai-integration-tests/it-env.sh
touch "$SCRATCH/t7/start"
./gradlew -p build-logic test && ./gradlew build
./gradlew integrationTest --rerun-tasks --continue 2>&1 | tee "$SCRATCH/t7/it.log" | grep -E 'route parity|BUILD'
find . -path '*/build/test-results/*' -name 'TEST-*.xml' -newer "$SCRATCH/t7/start" -not -path './build-logic/*' \
  | grep -iE 'integrationTest|It/|Only/|fullApp|layersIt' \
  | xargs perl -ne 'if (/<testsuite [^>]*tests="(\d+)"[^>]*skipped="(\d+)"[^>]*failures="(\d+)"[^>]*errors="(\d+)"/) { $t+=$1; $s+=$2; $f+=$3; $e+=$4 } END { print "tests=$t skipped=$s failures=$f errors=$e\n" }'
(cd examples/gis-sample/perene && python3 -m unittest -q 2>&1 | tail -2)
M="$SCRATCH/t7/m2"; rm -rf "$M"
./gradlew publishToMavenLocal -Pversion=0.0.0-verify -Dmaven.repo.local="$M" -q
.github/scripts/check-maven-publications.sh "$M"
yarn -s format:check
```

Expected: both builds `BUILD SUCCESSFUL`; `route parity: 95 live routes compared …`; `tests=410 skipped=0 failures=0
errors=0` (94 core + 300 IT suites + 16 samples, schema parity included); `Ran 36 tests … OK`;
`maven publications: ok (20)`; prettier clean.

- [ ] **Step 3: wasichai-ui, full**

```bash
cd /Users/jorge/IdeaProjects/wasichai-ui
yarn install --frozen-lockfile
yarn format:check && yarn lint
yarn test:tooling 2>&1 | grep -E '^ℹ (tests|pass|fail)'
yarn test 2>&1 | grep -E 'Tests +[0-9]+' | perl -ne '$t += $1 if /(\d+) passed/; $f += $1 if /(\d+) failed/; END { print "passed=$t failed=${\($f//0)}\n" }'
yarn build
for s in simple documents gis full; do
  yarn -s workspace $s-sample-web typecheck && grep -l '\.flex{' examples/$s-sample/web/dist/assets/*.css >/dev/null && echo "$s web ok"
done
node tooling/check-release.mjs --pack 0.1.0
```

Expected: lint clean; `ℹ tests 22`, `ℹ fail 0`; `passed=677 failed=0`; build green; four `web ok`;
`check-release: ok` (11 `@wasichai/*`).

- [ ] **Step 4: Cross-repo Playwright e2e, locally** (Review Focus 5): server jar from `../wasichai`, database
  `wasichai_full` on 5442

```bash
bash /private/tmp/claude-502/-Users-jorge-IdeaProjects-chawpi/73f54845-6d0f-493f-961b-10662da123e9/scratchpad/tunnel.sh
source /Users/jorge/IdeaProjects/wasichai/wasichai-integration-tests/it-env.sh
for p in 8093 5174; do lsof -nP -iTCP:$p -sTCP:LISTEN && { echo "port $p busy: Playwright would reuse it. stop and report" >&2; exit 1; }; done
./gradlew :full-sample-server:bootJar -q
ls -l /Users/jorge/IdeaProjects/wasichai/examples/full-sample/server/build/libs/app.jar
cd /Users/jorge/IdeaProjects/wasichai-ui
(cd examples/full-sample/web && npx playwright install chromium)
WASICHAI_BACKEND_DIR=/Users/jorge/IdeaProjects/wasichai \
WASICHAI_DB_HOST=localhost WASICHAI_DB_PORT=5442 WASICHAI_DB_NAME=wasichai_full \
WASICHAI_DB_USERNAME="$WASICHAI_TEST_DB_USERNAME" WASICHAI_DB_PASSWORD="$WASICHAI_TEST_DB_PASSWORD" \
  yarn workspace full-sample-web e2e
for p in 8093 5174; do lsof -nP -iTCP:$p -sTCP:LISTEN >/dev/null && echo "port $p still held"; done
```

Expected: both ports free before; the jar exists (fresh timestamp); every Playwright test passes (the smoke logs in as
`admin@wasichai.local`); both ports free again after (Playwright stopped what it started). The run uses port 5442
only; `WASICHAI_DB_PORT` is never 5432.

- [ ] **Step 5: Consumer check outside both repositories** (Review Focus 3 and 4; the guide's minimal app)

```bash
C="$SCRATCH/t7/consumer"; rm -rf "$C"; mkdir -p "$C"; V=0.0.0-verify
cd /Users/jorge/IdeaProjects/wasichai
./gradlew publishToMavenLocal -q -Pversion=$V -Dmaven.repo.local="$C/m2"
mkdir -p "$C/app/src/main/kotlin/com/example/myapp"
cat > "$C/app/settings.gradle.kts" <<'KTS'
rootProject.name = "myapp"
KTS
cat > "$C/app/build.gradle.kts" <<KTS
plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    id("org.springframework.boot") version "4.1.1"
}
repositories {
    mavenCentral()
    maven { url = uri("file://$C/m2") }
}
dependencies {
    implementation(platform("wasichai:wasichai-bom:$V"))
    implementation("wasichai:wasichai-spring-boot-starter")
    implementation("wasichai:wasichai-spring-boot-starter-gis")
}
kotlin { jvmToolchain(25) }
KTS
cat > "$C/app/src/main/kotlin/com/example/myapp/MyApp.kt" <<'KT'
package com.example.myapp

import org.springframework.boot.runApplication
import wasichai.core.autoconfigure.WasichaiApplication

@WasichaiApplication
class MyApp

fun main(args: Array<String>) {
    runApplication<MyApp>(*args)
}
KT
./gradlew -p "$C/app" -q compileKotlin && echo "maven consumer ok"
cd /Users/jorge/IdeaProjects/wasichai-ui
mkdir -p "$C/web/pkgs" && cp -R packages/ui packages/core "$C/web/pkgs/" && rm -rf "$C/web/pkgs/"*/node_modules
node tooling/set-version.mjs $V "$C/web/pkgs"
(cd "$C/web/pkgs/ui" && npm pack -q --ignore-scripts --pack-destination "$C/web") && (cd "$C/web/pkgs/core" && npm pack -q --ignore-scripts --pack-destination "$C/web")
node -e '
const [dir, v] = process.argv.slice(1)
const peers = require("./packages/core/package.json").peerDependencies
const deps = { "@wasichai/ui": `file:./wasichai-ui-${v}.tgz`, "@wasichai/core": `file:./wasichai-core-${v}.tgz` }
for (const [n, r] of Object.entries(peers)) if (!n.startsWith("@wasichai/")) deps[n] = r
require("fs").writeFileSync(dir + "/package.json", JSON.stringify({ name: "consumer", private: true, type: "module", dependencies: deps, devDependencies: { typescript: "5.9.3", "@types/react": "19.3.0", "@types/react-dom": "19.3.0" } }, null, 2))' "$C/web" $V
cat > "$C/web/main.tsx" <<'TSX'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { WasichaiApp } from '@wasichai/core'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <WasichaiApp config={{ apiBaseUrl: '/api', appName: 'My App' }} modules={[]} />
  </StrictMode>
)
TSX
(cd "$C/web" && npm install --no-audit --no-fund -q && npx tsc --noEmit --jsx react-jsx --module esnext --moduleResolution bundler --target es2022 --strict --lib dom,es2022 main.tsx) && echo "npm consumer ok"
node -p "require('$C/web/node_modules/@wasichai/core/package.json').dependencies['@wasichai/ui']"
node -p "require('./packages/core/package.json').version"
```

Expected: `maven consumer ok` (BOM constraints resolve every starter version, no project references in the POMs);
`npm consumer ok`; the installed core depends on `@wasichai/ui` `0.0.0-verify` (pinned, not `*`); the repository's
core version is unchanged (`0.1.0`). The tarball names (`wasichai-ui-…tgz`, `wasichai-core-…tgz`) are npm's scoped
naming for `@wasichai/ui` and `@wasichai/core`. Nothing is published to `~/.m2` or a registry.

- [ ] **Step 6: Leftover grep with the allow-list** (both repositories; the lists come from git, so git-ignored files
  such as `it-env.sh`, `node_modules`, `dist`, `build` and `.superpowers` are out)

```bash
cd /Users/jorge/IdeaProjects/wasichai
A=$(grep -n '^## Addendum (2026-09-25)' docs/adr/0029-polyglot-monorepo-and-publishing.md | cut -d: -f1)
git ls-files -co --exclude-standard -z | xargs -0 grep -nIE 'chawpi|Chawpi|CHAWPI|hneyra' 2>/dev/null \
  | grep -vE '^docs/superpowers/' \
  | grep -vE '^docs/(HISTORY|chawpi-origin|sapgis-origin)\.md:' \
  | grep -vE '^docs/adr/00(30|32)-' \
  | grep -vE '^docs/adr/00[0-9]{2}-[a-z0-9-]+\.md:[0-9]+:> (Imported from sapgis|Moved from chawpi)' \
  | grep -vE '^README\.md:[0-9]+:Origin: ' \
  | awk -F: -v a="$A" '!($1 ~ /^docs\/adr\/0029-/ && $2 >= a)' \
  | sed -E 's/(rebrand-sapgis-to-chawpi|chawpi-libraries-design|chawpi-origin)//g' \
  | grep -E 'chawpi|Chawpi|CHAWPI|hneyra'
echo "wasichai grep done"
cd /Users/jorge/IdeaProjects/wasichai-ui
git ls-files -co --exclude-standard -z | xargs -0 grep -nIE 'chawpi|Chawpi|CHAWPI|hneyra' 2>/dev/null \
  | grep -vE '^README\.md:[0-9]+:Origin: ' \
  | sed -E 's/chawpi-origin//g' | grep -E 'chawpi|Chawpi|CHAWPI|hneyra'
echo "wasichai-ui grep done"
```

Expected: only the two `grep done` lines. Every other line is a leftover: route it by path (ownership table).

- [ ] **Step 7: Link check and name check over both repositories.** Relative links must resolve; cross-repo GitHub
  URLs must resolve against the sibling checkout.

```bash
cd /Users/jorge/IdeaProjects
node -e '
const fs = require("fs"), path = require("path"), cp = require("child_process"); let bad = 0, n = 0
const repos = { wasichai: "/Users/jorge/IdeaProjects/wasichai", "wasichai-ui": "/Users/jorge/IdeaProjects/wasichai-ui" }
for (const [name, root] of Object.entries(repos)) {
  const files = cp.execSync("git ls-files -co --exclude-standard", { cwd: root, encoding: "utf8" }).split("\n")
    .filter((f) => f.endsWith(".md") && !f.startsWith("docs/superpowers/") && f !== "CHANGELOG.md")
  for (const rel of files) {
    const f = path.join(root, rel), text = fs.readFileSync(f, "utf8").replace(/```[\s\S]*?```/g, "")
    for (const m of text.matchAll(/\]\(([^)\s#]+)(#[^)]*)?\)/g)) {
      const u = m[1]; n++
      const gh = u.match(/^https:\/\/github\.com\/wasichai\/(wasichai|wasichai-ui)\/(?:blob|tree)\/main\/(.+)$/)
      let target = null
      if (gh) target = path.join(repos[gh[1]], gh[2])
      else if (/^(https?:|mailto:)/.test(u)) continue
      else target = path.resolve(path.dirname(f), u)
      if (!fs.existsSync(target)) { console.log(name + "/" + rel + ": broken link " + u); bad++ }
    }
  }
}
console.log("links checked: " + n); process.exit(bad ? 1 : 0)' && echo "links ok"
cd /Users/jorge/IdeaProjects/wasichai
node -e '
const fs = require("fs"), path = require("path")
const artifacts = new Set([...fs.readdirSync("."), ...fs.readdirSync("starters")].filter((d) => d.startsWith("wasichai-")))
const ui = "/Users/jorge/IdeaProjects/wasichai-ui/packages"
const pkgs = new Set(fs.readdirSync(ui).map((d) => { try { return require(path.join(ui, d, "package.json")).name } catch { return null } }))
const props = new Set(["wasichai.seed.dev", "wasichai.test.db.image"]), kotlinPackages = new Set()
const walk = (d) => { for (const e of fs.readdirSync(d, { withFileTypes: true })) {
  const p = path.join(d, e.name)
  if (e.isDirectory()) { if (!["build", "node_modules", ".gradle", ".git", "docs"].includes(e.name)) walk(p); continue }
  if (!e.name.endsWith(".kt")) continue
  const t = fs.readFileSync(p, "utf8")
  const pkg = t.match(/^package ([\w.]+)/m); if (pkg) kotlinPackages.add(pkg[1])
  const m = t.match(/@ConfigurationProperties\((?:prefix = )?"([^"]+)"\)/); if (!m) continue
  for (const v of t.matchAll(/^\s+val (\w+)\s*:/gm)) props.add(m[1] + "." + v[1].replace(/[A-Z]/g, (c) => "-" + c.toLowerCase()))
} }
walk(".")
const plugins = new Set(fs.readdirSync("build-logic/src/main/kotlin").map((f) => f.replace(/\.gradle\.kts$/, "")))
let bad = 0
for (const f of process.argv.slice(1)) {
  const t = fs.readFileSync(f, "utf8")
  for (const m of t.matchAll(/wasichai:(wasichai-[a-z-]+)/g)) if (!artifacts.has(m[1])) { console.log(f + ": unknown artifact " + m[1]); bad++ }
  for (const m of t.matchAll(/@wasichai\/([a-z-]+)/g)) if (!pkgs.has("@wasichai/" + m[1])) { console.log(f + ": unknown package @wasichai/" + m[1]); bad++ }
  for (const m of t.matchAll(/`(wasichai\.[a-z][a-z0-9.-]*[a-z0-9])`/g)) {
    const k = m[1]
    if (props.has(k) || kotlinPackages.has(k) || plugins.has(k) || [...props].some((p) => p.startsWith(k + "."))) continue
    console.log(f + ": unknown property/package " + k); bad++
  }
}
process.exit(bad ? 1 : 0)' README.md CLAUDE.md examples/README.md docs/architecture/overview.md docs/modules/*.md docs/guides/*.md \
  docs/development/*.md docs/security/*.md docs/gis/*.md /Users/jorge/IdeaProjects/wasichai-ui/README.md \
  /Users/jorge/IdeaProjects/wasichai-ui/docs/README.md && echo "names ok"
```

Expected: `links checked: <n>` then `links ok`; `names ok`. A `wasichai.<x>` in backticks that is a Kotlin type path
(`wasichai.core.autoconfigure.WasichaiApplication`) is covered by the package set only up to the package; if the check
flags such a fully qualified type name, it is not a defect: note it and move on.

- [ ] **Step 8: MD check and actionlint**

```bash
cd /Users/jorge/IdeaProjects/wasichai
FILES="README.md CLAUDE.md examples/README.md examples/*-sample/README.md examples/gis-sample/perene/README.md starters/*/README.md $(find docs -name '*.md' -not -path 'docs/superpowers/*')"
# paste the MD check block (Global Constraints) here
actionlint && echo "wasichai actionlint ok"
cd /Users/jorge/IdeaProjects/wasichai-ui
FILES="README.md CLAUDE.md docs/README.md examples/README.md packages/*/README.md"
# paste the MD check block (Global Constraints) here
actionlint && echo "wasichai-ui actionlint ok"
```

Expected: the MD check prints nothing in either repository; both `actionlint ok`.

- [ ] **Step 9: Final review and one fix round.** The controller dispatches one reviewer on the most capable model
  over both repositories (the diff is the whole tree: compare against chawpi with
  `diff -r --exclude=node_modules --exclude=build --exclude=dist --exclude=.gradle` per mapped directory), with this
  plan and the spec. Findings are routed to T3/T4/T5/T6 by path, fixed in one round, and the T7 steps that cover the
  touched files rerun (a Kotlin change reruns Steps 2, 4, 5; a UI change Steps 3, 4, 5; a doc change Steps 6–8; a
  workflow change Step 8).

- [ ] **Step 10: Report** (no commit): one line per step with its numbers (410 / 36 / 20 / 677 / 22 / 11 / e2e
  pass / consumer ok / 0 leftovers / links n ok / MD clean / actionlint ok ×2), the routed defects and their fixes,
  and the user actions still open: commit both repositories, push, create the secrets `RELEASE_PLEASE_TOKEN` (both
  repos) and `WASICHAI_REPO_TOKEN` (wasichai-ui).
