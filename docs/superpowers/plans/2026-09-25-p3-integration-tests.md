# P3 — Backend integration tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove, against a real PostgreSQL/PostGIS, that the chawpi libraries behave exactly like the original app: every module API test of the original ported and green against a full test app, the geometry assertions that left core restored against the gis app, each module booting alone with core (the module matrix), the wire JSON and the final schema equal to the original's.

**Architecture:** Everything lives in `backend/chawpi-integration-tests`. Every test group is its own Gradle JVM test suite (own source set, own compile, own ktlint task, own JVM), so the tasks that write them can run at the same time without breaking each other's compile. Full-app suites put every starter on the classpath and run on PostGIS; slice suites put one starter on the classpath (real classpath absence, not property switches) and run on plain PostgreSQL, except gis. A new suite lock in `ChawpiTestDatabase` (a session advisory lock held for the JVM's lifetime) makes any two suites against the same database queue instead of wiping each other's data, so ITs can be *launched* in parallel and still *run* one at a time per database.

**Tech Stack:** Kotlin 2.4.20, JDK 25, Spring Boot 4.1.1 WebFlux + R2DBC, `WebTestClient`, JUnit 6 (Boot BOM) + AssertJ, Jackson 3 (`tools.jackson`), Gradle 9.7.1 `jvm-test-suite` + `java-test-fixtures`, Testcontainers 2.0.5 (CI only), PostgreSQL 18 / PostGIS 3.6, psql (fixture generation only), ktlint 1.7.1.

**Spec:** `docs/superpowers/specs/2026-09-25-chawpi-libraries-design.md` (section "Tests" and "Verification"). Also binding: the P2 plan `docs/superpowers/plans/2026-09-25-p2-backend-modules.md` (Rulings M1–M13, out-of-scope P3 hand-off) and the ledgers `.superpowers/sdd/2026-09-25-p1-backend-core/progress.md` and `.superpowers/sdd/2026-09-25-p2-backend-modules/progress.md` (their notes override plan text).

## Global Constraints

- **NEVER run `git commit`, `git push` or `git stash`.** Leave every change uncommitted in the working tree. Each task ends with `git status --short` to confirm nothing was committed.
- sapgis (`/Users/jorge/IdeaProjects/sapgis`) is READ-ONLY. Copy from it, never edit it, never run Gradle or any build inside it.
- **Test-only phase.** P3 edits only `backend/chawpi-integration-tests/**`, `backend/chawpi-test/src/main/kotlin/chawpi/test/ChawpiTestDatabase.kt` and `backend/build-logic/src/main/kotlin/chawpi.integration-test.gradle.kts` (Task 1), and `.superpowers/` notes. If a ported test fails because library code behaves differently from the original, **do not edit the library and do not weaken the test**: stop and report `BLOCKED: behaviour differs: <test name> — expected <x>, got <y>` with the failing assertion. The controller decides where the fix goes.
- **Databases (external mode is the only mode here: the Docker daemon is remote, Testcontainers ports are unreachable).** Plain PostgreSQL 18 on `localhost:5443`, PostGIS on `localhost:5442`; both databases are named `chawpi_test`, user `chawpi`, password `chawpi`. **Never use port 5432.** Every IT command runs with all five `CHAWPI_TEST_DB_*` vars pointing at 5443 plus `CHAWPI_TEST_GIS_DB_PORT=5442` (the env block in Shared conventions).
- **Tunnel check first.** Before any IT run: `nc -z localhost 5443 && nc -z localhost 5442`. If a port is closed, do not start Gradle; report `not run: tunnel down (<port> closed)`. The user may need to add `-L 5442:localhost:5442` to the SSH tunnel.
- **If the tunnel is down**, run `/private/tmp/claude-502/-Users-jorge-IdeaProjects-chawpi/73f54845-6d0f-493f-961b-10662da123e9/scratchpad/tunnel.sh` (it restarts only missing ports 5442/5443).
- **Sequencing with the concurrent P2 fix wave.** Wave 0 (Task 1) starts only after the P2 fix wave is closed: its edits to `backend/` are done and its ITs have finished. From then until Task 21, nothing under `backend/` outside `backend/chawpi-integration-tests/src/<suite>/` is edited by anyone. The controller dispatches Wave 1 only after Task 1 Step 10 (`:chawpi-integration-tests:check`) has run green after the last such edit — that single build is what makes every shared upstream output (chawpi-core, chawpi-agent, and every jar Wave 1 depends on) up to date. Without it, the ~19 parallel Wave 1 builds race to rebuild the same upstream jars and Kotlin incremental caches.
- **One suite at a time per database.** Never start two IT runs yourself. Runs launched by different executors at the same time are safe only because of the suite lock (Task 1): the second JVM prints `chawpi-test: another suite holds …; waiting` and waits. A queued run can take many minutes: launch IT runs with the Bash tool's `run_in_background: true`, redirect output to the suite's log file, and read the log when it finishes. Never kill a run that is waiting.
- Formatting: repo `.editorconfig` (Kotlin 4 spaces, 160 columns, LF, final newline). Every task ends with two separate `./gradlew` calls for its own source set — `ktlint<Suite>SourceSetFormat` then `ktlint<Suite>SourceSetCheck` — never combined in one invocation. Both must pass.
- **Parallel safety.** A task edits only the files in its own **Files** list and runs Gradle only for its own suite (`:chawpi-integration-tests:<suite>`, `compile<Suite>Kotlin`, its ktlint tasks), never `./gradlew build` at the root (that is Task 21). If Gradle says `Timeout waiting to lock`, wait 30 s and run the same command again. If compilation fails in a file you do not own, wait 60 s and retry (up to 10 times), then report `BLOCKED: foreign file <path>`; never edit it.
- Comments in English, caveman style: short, say why, never restate the code. Ported tests keep the original's comments; reword any that name the original app.
- REST routes, status codes and JSON are the original's. A ported assertion is changed only where a rule below says so (renames, the dropped V11 replay).
- Nothing named after the original survives in `backend/` (`grep -rni sapgis backend --include=*.kt --include=*.kts --include=*.sql` → nothing), except the generator script `generate-expected.sh` (Task 11), which must name the original's migrations path and schema and is allowed to say `sapgis`.
- Versions only from `gradle/libs.versions.toml`. No new libraries.

## Rulings (binding for every task)

- **T1: where the ITs live.** All in `backend/chawpi-integration-tests` (the spec says so, and every module IT needs neighbours: `WorkflowApiTest` reads pages, `AutomationApiTest` issues documents, `PageApiTest` draws maps). Module test source sets keep their unit tests only.
- **T2: one JVM test suite per test group.** Each task below owns one `jvm-test-suite` suite (`src/<suite>/kotlin`). Reasons: (a) the tasks run concurrently and a shared source set means one executor's half-written file breaks everyone's compile, and `ktlintTestSourceSetFormat` rewrites everyone's files; (b) a slice must have a *real* classpath without the other modules, or the `@ConditionalOnClass` adapters (documents→automation, agent→workflow, gis→pages, workflow→pages) would switch on and the matrix would test a fiction; (c) each suite starts on a freshly wiped database, so no suite sees another's leftovers. Cost: ~20 JVM starts per full run (~20–30 s each), accepted.
- **T3: DB isolation = suite lock + sequential runs, not schema pairs.** Rejected: one schema pair per test app. `ChawpiTestDatabase` wipes *every* user schema once per JVM (it has to: a test may choose its own schema names), so a second JVM starting against the same database destroys the first one's schemas whatever they are called. Chosen: `ChawpiTestDatabase` takes a session-level advisory lock `pg_advisory_lock(hashtext('chawpi-test-suite'))` on its own JDBC connection before the wipe and holds it until the JVM exits (shutdown hook; PostgreSQL also drops it if the JVM dies). Result: any number of suites may be *launched* at once (by Gradle, by parallel executors, by two checkouts); per database they *run* one after the other, each on a fresh wipe. The two servers (5443 plain, 5442 PostGIS) are different databases, so a core/slice suite and a gis suite still run side by side. The lock is also what the published fixture should have done since P1 ("ONE SUITE AT A TIME" becomes enforced instead of documented).
- **T4: which database each suite uses.** Full-app suites and `gisOnly` → PostGIS (`systemProperty("chawpi.test.db.image", "postgis/postgis:18-3.6")` for Testcontainers; in external mode the build points `CHAWPI_TEST_DB_PORT` at `CHAWPI_TEST_GIS_DB_PORT`). Every other slice → plain PostgreSQL 18 (proves core and the non-gis modules need no PostGIS). In external mode a PostGIS suite without `CHAWPI_TEST_GIS_DB_PORT` fails before its JVM starts with a message naming the variable (never falls back to the plain server: gis migrations would fail half-way).
- **T5: test apps.** Every app is a `@SpringBootConfiguration @EnableAutoConfiguration class` with no component scan (the P1 pattern). Full app: `chawpi.it.full.FullTestApplication` in `src/testFixtures` (shared by all full-app suites); tests in package `chawpi.it.full`. Slice apps: `chawpi.it.slice.<m>.<M>OnlyApplication` inside the slice's own suite; tests in the same package. `ChawpiIntegrationTest` finds the first `@SpringBootConfiguration` walking up from the test's package, so a test package must never contain two apps.
- **T6: full-app properties.** Base class `FullAppIntegrationTest` (testFixtures) adds `chawpi.automation.poll-interval=0s` (the original's tests drove the drain by hand), `chawpi.gis.geoserver.enabled=false` and `chawpi.gis.geoserver.url=http://geoserver.invalid:8081/geoserver` (GeoServer stays out of the suite, as the original's `LayerApiTest` did). Every Gradle IT task sets `ANTHROPIC_API_KEY=""` so a developer's shell key never switches the assistant on; the agent tests set their own keys by property, as the original did.
- **T7: port rules.** Ported test files are the original's, renamed by the `port_it` sed function (Shared conventions), nothing else, except where a task says so. Package `com.sapgis.api` → `chawpi.it.full`; base `IntegrationTest()` → `FullAppIntegrationTest()`; imports of module classes → `chawpi.<m>.`; `com.sapgis.metadata.` → `chawpi.core.metadata.`; `DATA_SCHEMA` → literal `'app_data'`; `sapgis.agent.apiKey` → `chawpi.agent.api-key` (the key `EmbabelGate` reads); `sapgis.geoserver.` → `chawpi.gis.geoserver.`; then every `sapgis` → `chawpi` (SQL `sapgis.<table>` becomes `chawpi.<table>`, the default metadata schema; `admin@sapgis.local` becomes the seeded `admin@chawpi.local`; the GeoServer workspace becomes `chawpi` on both sides of `LayerApiTest`).
- **T8: geometry restoration = port the five originals whole.** `MetadataApiTest`, `RecordApiTest`, `PermissionEnforcementTest`, `FieldApiTest` and `ObjectCrudApiTest` are ported *unmodified* (only T7 renames) into the full app (Task 9). This restores exactly what P1 cut from core — 4 metadata geometry tests, 8 record geometry/bbox/feature tests, the locked-geometry permission test, the `workflow_state` system-field assertion, and the automation field-usage test — with the original's own assertions, and re-proves the rest of the original's core contract with every module installed. Core's own trimmed copies stay in chawpi-core (core-only proof).
- **T9: wire JSON parity = golden JSON.** Task 10 compares whole response bodies (object definition, object list entry, record, feature collection, single feature) with JSON literals derived line by line from the original's DTOs (`MetadataDtos.kt`, `RecordService.kt`, `GeoJson.kt`, `FeatureController.kt`, `PhysicalTableRecordStore.mapRow`). Comparison is Jackson tree equality (object key order ignored, array order and number types kept) after normalising UUIDs to `<uuid>`, non-null `createdAt`/`updatedAt` to `<ts>` and the object's unique name to `<name>`. Geometries use SRID 4326 so coordinates round-trip exactly.
- **T10: schema parity = catalog snapshot.** One SQL file (`catalog.sql`) prints one sorted line per catalog fact of a schema — tables, columns with ordinal/type/NOT NULL/default, constraints (`pg_get_constraintdef`), indexes (`pg_get_indexdef`), non-internal triggers, functions (identity args, result, md5 of body), views, sequences — skipping extension-owned objects and `flyway_%` tables, with the schema name normalised to `META.` and `search_path` set to `public` so every name prints qualified. The expected file is generated once from the original's V1–V14 replayed with psql in ONE transaction that is rolled back (the test database is left untouched) and checked in; the IT runs the same SQL against `chawpi` after the full app's migrations and diffs line sets. Differences are either a regression (report BLOCKED) or listed in `KNOWN_DEVIATIONS` with a reason; the task starts with that set empty.
- **T11: PageApiTest's V10/V11 replay.** Dropped (clean start: no stored pages from the original to migrate, P2 M4). Replaced by one test pinning V11's end state and the behaviour it relied on: `chawpi.pages.template` is `text NOT NULL DEFAULT 'one-region'`, there is no `layout` column, and a deleted stored page falls back to the generated one-region page. `PageApiTest` therefore has 60 tests (61 − 2 + 1).
- **T11b: DocumentApiTest's V14 replay.** Dropped for the same reason as T11. It is replaced by one test pinning V14's end state as chawpi-documents' V1 builds it. DocumentApiTest keeps 10 tests.
- **T12: module matrix.** Each slice suite (core alone, and each module alone with core; pages brings forms, its one hard dependency) extends `SliceSmokeTest`, which asserts: every route of every module that is not on the classpath answers **404** to the administrator and to a member with no grants (never 403, never 401 with a valid token); only `flyway_history_core`, `flyway_history_core_seed` and the installed modules' history tables exist. Each slice then adds its own positive tests and the behaviour of its optional neighbour's absence (documents without automation, automation without documents, agent without workflow, gis without pages, workflow without pages, pages without gis/workflow).
- **T13: link/unlink "other end" permission (P2 ledger).** Implemented and tested in chawpi-core by the P2 fix wave (`CoreHardeningApiTest`); none of the P3 ported tests calls link/unlink, so P3 neither pins nor contradicts it. No P3 test changes.
- **T14: Gradle.** `./gradlew integrationTest` runs everything: core's ITs and, in `:chawpi-integration-tests`, `integrationTest` depends on every suite task. `./gradlew build` compiles every suite (`check` depends on each `<suite>Classes`) and runs the two unit tests plus `ModuleRoutesTest`, but no IT. The convention's `integrationTest` gets `failOnNoDiscoveredTests = false` (modules without ITs apply it). CI (GitHub runners, local Docker) needs no change: without `CHAWPI_TEST_DB_HOST` each suite JVM starts its own container from the image its task names.

## Review Focus

1. **Two suites started against the same database at once.** Expected: the second waits, then runs on a fresh wipe; neither sees a table vanish mid-run. Pinned by `FullAppBootTest` `the suite lock is held for as long as this jvm runs` (Task 1) and exercised for real by Wave 1's parallel executors.
2. **A PostGIS suite launched with only the five plain-DB vars.** Expected: the task fails before any JVM starts, naming `CHAWPI_TEST_GIS_DB_PORT`; it never wipes or migrates the plain server. Pinned by Task 1 Step 9 (run with the variable unset, expect the message).
3. **A developer shell that exports `ANTHROPIC_API_KEY`.** Expected: the suites behave as in CI (assistant off unless a test configures it). Pinned by Task 8 Step 6 (run `agentIt` with a fake key exported; `AgentApiTest` still sees `enabled=false`).
4. **A non-administrator calling a route of a module the app does not have.** Expected: 404, never 403 (P2 ledger). Pinned by `SliceSmokeTest` `a member with no grants gets 404 from an absent module too, never 403` (Task 1), run by every slice (Tasks 12–20).
5. **A geometry stored in a projected CRS and read back.** Expected: stored in the field's SRID (e.g. 32718), answered as WGS84 GeoJSON within 1e-6 degrees, area in square metres. Pinned by the original's `stores a polygon in the declared CRS and returns it in WGS84` (Task 9) and `GisOnlyApiTest` `a polygon goes into postgis in the field's srid and comes back as wgs84` (Task 19).

---

## Waves (what may run at the same time)

| Wave | Tasks (parallel inside a wave) | Needs |
|---|---|---|
| 0 | Task 1: tunnel/DB preflight, suite lock, Gradle suites, shared fixtures, full-app boot test | P2 done; see below |
| 1 | **Full app:** Task 2 workflow · Task 3 automation · Task 4 documents + document types · Task 5 pages · Task 6 views + forms · Task 7 layers · Task 8 agent · Task 9 core originals with geometry · Task 10 wire JSON parity · Task 11 schema parity. **Slices:** Task 12 core only · Task 13 views only · Task 14 forms only · Task 15 pages only · Task 16 workflow only · Task 17 automation only · Task 18 documents only · Task 19 gis only · Task 20 agent only | Wave 0; see below |
| 2 | Task 21: whole build + every IT, sequential, counts, final greps | Wave 1 |

- Wave 0 starts only after the P2 fix wave is closed: its edits are done and its ITs have finished.
- From then until Task 21, nothing under `backend/` outside `backend/chawpi-integration-tests/src/<suite>/` is edited by anyone.
- The controller dispatches Wave 1 only after Task 1 Step 10 (`:chawpi-integration-tests:check`) has run green after the last such edit. That single build is what makes every shared upstream output up to date. Without it, parallel builds race to rebuild the same jars.

Files touched by two tasks of the same wave: none (each Wave 1 task owns one `src/<suite>/` directory; Task 1 created the suite in the build file already). Each Wave 1 executor *writes and compiles* in parallel; its IT runs queue on the suite lock per database (5443: Tasks 12–18, 20; 5442: Tasks 2–11, 19). The controller may cap concurrent executors (memory: each Gradle build is a daemon of ~1.5 GB); order inside the wave does not matter. Every Wave 1 task first runs `./gradlew :chawpi-integration-tests:testFixturesJar -q`, expecting it to be up to date; if it reports work, stop and report `BLOCKED: upstream not warm`.

## File structure (end state of P3)

```
backend/build-logic/src/main/kotlin/chawpi.integration-test.gradle.kts           (Task 1: failOnNoDiscoveredTests = false)
backend/chawpi-test/src/main/kotlin/chawpi/test/ChawpiTestDatabase.kt             (Task 1: suite lock, extension-member schemas kept)
backend/chawpi-integration-tests/
  build.gradle.kts                                                               (Task 1: 20 suites, gis port, integrationTest aggregate)
  src/test/kotlin/chawpi/it/AllModulesWiringTest.kt, ModuleBoundariesTest.kt     (P2, untouched)
  src/test/kotlin/chawpi/it/ModuleRoutesTest.kt                                  (Task 1)
  src/testFixtures/kotlin/chawpi/it/full/FullTestApplication.kt                  (Task 1)
  src/testFixtures/kotlin/chawpi/it/full/FullAppIntegrationTest.kt               (Task 1)
  src/testFixtures/kotlin/chawpi/it/support/ModuleRoutes.kt                      (Task 1)
  src/testFixtures/kotlin/chawpi/it/support/SliceSmokeTest.kt                    (Task 1)
  src/fullApp/kotlin/chawpi/it/full/FullAppBootTest.kt                           (Task 1)
  src/workflowIt/kotlin/chawpi/it/full/WorkflowApiTest.kt                        (Task 2)
  src/automationIt/kotlin/chawpi/it/full/AutomationApiTest.kt                    (Task 3)
  src/documentsIt/kotlin/chawpi/it/full/{DocumentApiTest,DocumentTypeApiTest}.kt (Task 4)
  src/pagesIt/kotlin/chawpi/it/full/PageApiTest.kt                               (Task 5)
  src/viewsFormsIt/kotlin/chawpi/it/full/{ViewApiTest,FormApiTest}.kt            (Task 6)
  src/layersIt/kotlin/chawpi/it/full/LayerApiTest.kt                             (Task 7)
  src/agentIt/kotlin/chawpi/it/full/{AgentApiTest,AgentEmbabelTest,AgentToolsTest}.kt (Task 8)
  src/coreParityIt/kotlin/chawpi/it/full/{MetadataApiTest,RecordApiTest,PermissionEnforcementTest,FieldApiTest,ObjectCrudApiTest}.kt (Task 9)
  src/wireParityIt/kotlin/chawpi/it/full/{WireJson,GeometryWireParityTest}.kt    (Task 10)
  src/schemaParityIt/kotlin/chawpi/it/full/SchemaParityTest.kt                   (Task 11)
  src/schemaParityIt/resources/schema-parity/{catalog.sql,generate-expected.sh,legacy-final.catalog} (Task 11)
  src/coreOnly/kotlin/chawpi/it/slice/core/{CoreOnlyApplication,CoreOnlyApiTest}.kt             (Task 12)
  src/viewsOnly/kotlin/chawpi/it/slice/views/{ViewsOnlyApplication,ViewsOnlyApiTest}.kt         (Task 13)
  src/formsOnly/kotlin/chawpi/it/slice/forms/{FormsOnlyApplication,FormsOnlyApiTest}.kt         (Task 14)
  src/pagesOnly/kotlin/chawpi/it/slice/pages/{PagesOnlyApplication,PagesOnlyApiTest}.kt         (Task 15)
  src/workflowOnly/kotlin/chawpi/it/slice/workflow/{WorkflowOnlyApplication,WorkflowOnlyApiTest}.kt (Task 16)
  src/automationOnly/kotlin/chawpi/it/slice/automation/{AutomationOnlyApplication,AutomationOnlyApiTest}.kt (Task 17)
  src/documentsOnly/kotlin/chawpi/it/slice/documents/{DocumentsOnlyApplication,DocumentsOnlyApiTest}.kt (Task 18)
  src/gisOnly/kotlin/chawpi/it/slice/gis/{GisOnlyApplication,GisOnlyApiTest}.kt                 (Task 19)
  src/agentOnly/kotlin/chawpi/it/slice/agent/{AgentOnlyApplication,AgentOnlyApiTest}.kt         (Task 20)
```

Suite → Gradle names (source set = suite name; tasks derive from it):

| Suite | Test task | Compile task | ktlint format / check | DB |
|---|---|---|---|---|
| `fullApp` | `:chawpi-integration-tests:fullApp` | `compileFullAppKotlin` | `ktlintFullAppSourceSetFormat` / `…Check` | PostGIS |
| `workflowIt` … `schemaParityIt` | `:chawpi-integration-tests:<suite>` | `compile<Suite>Kotlin` | `ktlint<Suite>SourceSetFormat` / `…Check` | PostGIS |
| `gisOnly` | `:chawpi-integration-tests:gisOnly` | `compileGisOnlyKotlin` | `ktlintGisOnlySourceSetFormat` / `…Check` | PostGIS |
| `coreOnly`, `viewsOnly`, `formsOnly`, `pagesOnly`, `workflowOnly`, `automationOnly`, `documentsOnly`, `agentOnly` | `:chawpi-integration-tests:<suite>` | `compile<Suite>Kotlin` | `ktlint<Suite>SourceSetFormat` / `…Check` | plain |

`<Suite>` is the suite name with its first letter upper-cased (`workflowIt` → `WorkflowIt`).

## Shared conventions (every task repeats what it needs)

**The Bash tool keeps only the working directory between calls.** Exported vars, `$IT`, `$TSRC`, `$D`, `$F`, `$R` and the `port_it` function do not survive from one Bash call to the next. Start every Bash call that uses any of them, or `./gradlew`, with:

```bash
source backend/chawpi-integration-tests/it-env.sh
```

(absolute path also works: `source /Users/jorge/IdeaProjects/chawpi/backend/chawpi-integration-tests/it-env.sh`, needed if the working directory has drifted). Task 1 Step 1 creates that file; from then on, every later step in every task sources it instead of re-defining the vars or the function inline. Run "Copy and port" as one call, starting with that `source` line.

`it-env.sh` sets the working directory, `$TSRC`, `$IT`, the five `CHAWPI_TEST_DB_*` vars plus `CHAWPI_TEST_GIS_DB_PORT`, and defines `port_it` (rules T7; order matters — the specific renames before the blanket one):

```bash
cd /Users/jorge/IdeaProjects/chawpi

TSRC=/Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis/api
IT=backend/chawpi-integration-tests
export CHAWPI_TEST_DB_HOST=localhost CHAWPI_TEST_DB_PORT=5443 CHAWPI_TEST_DB_NAME=chawpi_test \
       CHAWPI_TEST_DB_USERNAME=chawpi CHAWPI_TEST_DB_PASSWORD=chawpi CHAWPI_TEST_GIS_DB_PORT=5442

port_it() {
  sed -i '' -E \
    -e 's/^package com\.sapgis\.api$/package chawpi.it.full/' \
    -e 's/^import com\.sapgis\.(agent|automation|pages|documents|gis|workflow|views|forms)\./import chawpi.\1./' \
    -e 's/^import com\.sapgis\.metadata\./import chawpi.core.metadata./' \
    -e '/^import com\.sapgis\.data\.DATA_SCHEMA$/d' \
    -e "s/'\\\$DATA_SCHEMA'/'app_data'/g" \
    -e 's/: IntegrationTest\(\)/: FullAppIntegrationTest()/' \
    -e 's/sapgis\.agent\.apiKey/chawpi.agent.api-key/g' \
    -e 's/sapgis\.geoserver\./chawpi.gis.geoserver./g' \
    "$@"
  sed -i '' -e 's/SAPGIS/CHAWPI/g' -e 's/Sapgis/Chawpi/g' -e 's/sapgis/chawpi/g' "$@"
}
```

After `port_it`, every ported file must pass: `grep -n "sapgis\|com\.chawpi\|IntegrationTest()" <file>` prints only `FullAppIntegrationTest()` lines.

Running your suite (source the env file, tunnel check, then the run in the background because it may queue on the suite lock):

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
./gradlew :chawpi-integration-tests:<suite> --rerun > $IT/build/<suite>-run.log 2>&1; echo "exit $?"; grep -E "tests completed|FAILED|BUILD" $IT/build/<suite>-run.log | tail -20
```

(`mkdir -p $IT/build` first.) Launch that command with `run_in_background: true`; when it finishes read the tail of the log. The per-test results are in `$IT/build/test-results/<suite>/*.xml`; count them with
`grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/<suite>/*.xml`.
If `TUNNEL DOWN`, do not run: finish the task with the compile + ktlint steps and report `not run: tunnel down`.

---

### Task 1: Foundation — preflight, suite lock, suites, shared fixtures, full-app boot (Wave 0)

Everything Wave 1 stands on: the databases are reachable and right, two runs can no longer wipe each other, every suite exists in Gradle, and the full app boots on PostGIS with every module.

**Files:**
- Modify: `backend/build-logic/src/main/kotlin/chawpi.integration-test.gradle.kts`
- Modify: `backend/chawpi-test/src/main/kotlin/chawpi/test/ChawpiTestDatabase.kt`
- Modify: `backend/chawpi-integration-tests/build.gradle.kts` (full rewrite)
- Create: `backend/chawpi-integration-tests/src/testFixtures/kotlin/chawpi/it/full/FullTestApplication.kt`
- Create: `backend/chawpi-integration-tests/src/testFixtures/kotlin/chawpi/it/full/FullAppIntegrationTest.kt`
- Create: `backend/chawpi-integration-tests/src/testFixtures/kotlin/chawpi/it/support/ModuleRoutes.kt`
- Create: `backend/chawpi-integration-tests/src/testFixtures/kotlin/chawpi/it/support/SliceSmokeTest.kt`
- Create: `backend/chawpi-integration-tests/src/test/kotlin/chawpi/it/ModuleRoutesTest.kt`
- Create: `backend/chawpi-integration-tests/src/fullApp/kotlin/chawpi/it/full/FullAppBootTest.kt`
- Create: `backend/chawpi-integration-tests/it-env.sh` (uncommitted; sourced by every later Bash call that needs `$IT`, `$TSRC`, the `CHAWPI_TEST_DB_*` vars or `port_it` — the Bash tool keeps only the working directory between calls)

**Interfaces:**
- Consumes: `chawpi.test.ChawpiIntegrationTest` (`client`, `uniqueName(prefix)`, `bearer()`, `bearer(email, password)`, `ADMIN_EMAIL`), `chawpi.test.ChawpiTestDatabase`, `chawpi.it.AllModulesWiringTest.LEGACY_MODULE_ROUTES` (P2), the nine starters `:chawpi-spring-boot-starter[-<m>]`.
- Produces (every Wave 1 task relies on these exact names):
  - Gradle suites `fullApp`, `workflowIt`, `automationIt`, `documentsIt`, `pagesIt`, `viewsFormsIt`, `layersIt`, `agentIt`, `coreParityIt`, `wireParityIt`, `schemaParityIt` (every starter, PostGIS) and `coreOnly`, `viewsOnly`, `formsOnly`, `pagesOnly`, `workflowOnly`, `automationOnly`, `documentsOnly`, `agentOnly` (plain PostgreSQL) and `gisOnly` (PostGIS), each with core plus its own starter only. Source dir `backend/chawpi-integration-tests/src/<suite>/kotlin` (and `…/resources`).
  - `chawpi.it.full.FullTestApplication` — the full app.
  - `abstract class chawpi.it.full.FullAppIntegrationTest : ChawpiIntegrationTest()` — base of every ported test (poll-interval 0s, GeoServer off).
  - `object chawpi.it.support.ModuleRoutes { const val PROBE_ID: String; val byModule: Map<String, List<String>>; val all: List<String>; fun absentFrom(installed: Set<String>): List<String>; fun probe(route: String, objectName: String): Pair<HttpMethod, String> }`.
  - `abstract class chawpi.it.support.SliceSmokeTest : ChawpiIntegrationTest()` with `protected lateinit var db: DatabaseClient`, `protected abstract val installed: Set<String>`, `protected lateinit var admin: String` (admin bearer), `protected lateinit var objectName: String` (a flat object with one TEXT field `codigo`, created before each test), `protected fun statusOf(route: String, token: String): Int`, `protected fun memberWithoutGrants(): String` (bearer), and three inherited tests.
  - `ChawpiTestDatabase.SUITE_LOCK = "chawpi-test-suite"`.

- [ ] **Step 1: Preflight — tunnel and databases**

```bash
cd /Users/jorge/IdeaProjects/chawpi
nc -z localhost 5443 && echo "5443 open" || echo "5443 CLOSED"
nc -z localhost 5442 && echo "5442 open" || echo "5442 CLOSED"
```

If 5442 is closed, STOP the whole task and report exactly: `BLOCKED: PostGIS port 5442 is closed. Add -L 5442:localhost:5442 to the SSH tunnel (next to the existing -L 5443:…) and re-dispatch Task 1.` If 5443 is closed, report the same for 5443. Do not continue: every later step needs both.

With both open:

```bash
export PGPASSWORD=chawpi
psql -X -At -h localhost -p 5443 -U chawpi -d chawpi_test -c "SELECT current_database(), (SELECT count(*) FROM pg_available_extensions WHERE name = 'postgis')"
psql -X -At -h localhost -p 5442 -U chawpi -d chawpi_test -c "SELECT current_database(), (SELECT count(*) FROM pg_available_extensions WHERE name = 'postgis')"
psql -X -At -h localhost -p 5442 -U chawpi -d chawpi_test -c "SELECT e.extname || ' in ' || n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace ORDER BY 1"
```

Expected: 5443 → `chawpi_test|0` or `chawpi_test|1` (plain; either is fine). 5442 → `chawpi_test|1` (PostGIS available). If 5442 says `|0`, the port is not the PostGIS server: STOP, report `BLOCKED: 5442 is not PostGIS`. Note in the report the extension list of 5442 (the PostGIS image usually installs `postgis`, `postgis_topology`, `fuzzystrmatch`, `postgis_tiger_geocoder`; the tiger one creates schema `tiger` (and, in some images, `tiger_data`), which Step 3 teaches the wipe to keep).

**The Bash tool keeps only the working directory between calls.** Nothing else (exported vars, shell functions) survives from one Bash call to the next. Every later Bash call in this plan that uses `$IT`, `$TSRC`, `$D`, `$F`, `$R`, `port_it` or `./gradlew` starts by sourcing the env file this step now creates:

`backend/chawpi-integration-tests/it-env.sh`:

```bash
# sourced (never executed) at the start of every Bash call that needs $IT, $TSRC, the
# CHAWPI_TEST_DB_* vars or port_it: source backend/chawpi-integration-tests/it-env.sh
cd /Users/jorge/IdeaProjects/chawpi

TSRC=/Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis/api
IT=backend/chawpi-integration-tests
export CHAWPI_TEST_DB_HOST=localhost CHAWPI_TEST_DB_PORT=5443 CHAWPI_TEST_DB_NAME=chawpi_test \
       CHAWPI_TEST_DB_USERNAME=chawpi CHAWPI_TEST_DB_PASSWORD=chawpi CHAWPI_TEST_GIS_DB_PORT=5442

# rules T7: order matters, the specific renames before the blanket one
port_it() {
  sed -i '' -E \
    -e 's/^package com\.sapgis\.api$/package chawpi.it.full/' \
    -e 's/^import com\.sapgis\.(agent|automation|pages|documents|gis|workflow|views|forms)\./import chawpi.\1./' \
    -e 's/^import com\.sapgis\.metadata\./import chawpi.core.metadata./' \
    -e '/^import com\.sapgis\.data\.DATA_SCHEMA$/d' \
    -e "s/'\\\$DATA_SCHEMA'/'app_data'/g" \
    -e 's/: IntegrationTest\(\)/: FullAppIntegrationTest()/' \
    -e 's/sapgis\.agent\.apiKey/chawpi.agent.api-key/g' \
    -e 's/sapgis\.geoserver\./chawpi.gis.geoserver./g' \
    "$@"
  sed -i '' -e 's/SAPGIS/CHAWPI/g' -e 's/Sapgis/Chawpi/g' -e 's/sapgis/chawpi/g' "$@"
}
```

Run: `mkdir -p backend/chawpi-integration-tests && chmod +x backend/chawpi-integration-tests/it-env.sh && source backend/chawpi-integration-tests/it-env.sh && echo "$IT / $TSRC / $CHAWPI_TEST_DB_HOST:$CHAWPI_TEST_DB_PORT"`
Expected: prints `backend/chawpi-integration-tests / /Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis/api / localhost:5443`. This file is never committed (Global Constraints); every task that sources it must first `source backend/chawpi-integration-tests/it-env.sh` in the same Bash call that needs it.

- [ ] **Step 2: `integrationTest` tolerates a module with no ITs**

In `backend/build-logic/src/main/kotlin/chawpi.integration-test.gradle.kts`, inside the `integrationTest` registration, after `shouldRunAfter(tasks.named("test"))`, add:

```kotlin
    // every module applies this plugin, most have no integration tests: zero tests is not an error
    failOnNoDiscoveredTests.set(false)
```

Run:

```bash
./gradlew -p backend/build-logic test -q && ./gradlew :chawpi-views:integrationTest --rerun
```

Expected: `BUILD SUCCESSFUL` (the testkit suite passes; views runs zero ITs). If the script fails with `Unresolved reference: failOnNoDiscoveredTests`, this Gradle has no such property and never failed on zero tests: delete the two lines, re-run, and note it in the report.

- [ ] **Step 3: The suite lock, and a wipe that keeps extension-created schemas**

Edit `backend/chawpi-test/src/main/kotlin/chawpi/test/ChawpiTestDatabase.kt`:

(a) Add the import `import java.sql.Connection` next to `import java.sql.DriverManager`.

(b) Replace the `ready` property:

```kotlin
    // an external database outlives the suite: wipe it once per jvm, before the first context
    private val ready: Boolean by lazy {
        externalConfig?.let { wipeExternalDatabase(it) }
        true
    }
```

with:

```kotlin
    // an external database outlives the suite: take the suite lock, then wipe it once per jvm,
    // before the first context
    private val ready: Boolean by lazy {
        externalConfig?.let {
            lockSuite(it)
            wipeExternalDatabase(it)
        }
        true
    }

    /** The advisory lock a jvm holds on an external test database from its wipe until it exits. */
    const val SUITE_LOCK = "chawpi-test-suite"

    // never closed by us: closing it is what releases the lock, and that happens when the jvm exits
    @Volatile
    private var suiteLockConnection: Connection? = null

    /**
     * Two suites against one external database used to wipe each other mid-run. The second one now
     * waits here, on a session advisory lock taken on a connection of its own and held until this
     * jvm exits. Postgres drops the lock with the connection, so a killed run never leaves it stuck.
     * Another database (another port) has its own lock: a plain and a postgis suite still overlap.
     */
    private fun lockSuite(config: Map<String, String>) {
        val connection = connect(config)
        connection.createStatement().use { statement ->
            val free =
                statement.executeQuery("SELECT pg_try_advisory_lock(hashtext('$SUITE_LOCK'))").use { rows -> rows.next() && rows.getBoolean(1) }
            if (!free) {
                System.err.println(
                    "chawpi-test: another suite holds ${config.getValue(ENV_NAME)} on ${config.getValue(ENV_HOST)}:${config.getValue(ENV_PORT)}; " +
                        "waiting for it to finish"
                )
                statement.execute("SELECT pg_advisory_lock(hashtext('$SUITE_LOCK'))")
            }
        }
        suiteLockConnection = connection
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { connection.close() } })
    }

    private fun connect(config: Map<String, String>): Connection {
        val name = requireTestDatabaseName(config.getValue(ENV_NAME))
        return DriverManager.getConnection(
            "jdbc:postgresql://${config.getValue(ENV_HOST)}:${config.getValue(ENV_PORT)}/$name",
            config.getValue(ENV_USERNAME),
            config.getValue(ENV_PASSWORD)
        )
    }
```

(c) In `wipeExternalDatabase`, replace the schema query

```kotlin
                        "SELECT n.nspname FROM pg_namespace n WHERE $userSchemas " +
                            "AND NOT EXISTS (SELECT 1 FROM pg_extension e WHERE e.extnamespace = n.oid)"
```

with

```kotlin
                        "SELECT n.nspname FROM pg_namespace n WHERE $userSchemas " +
                            "AND NOT EXISTS (SELECT 1 FROM pg_extension e WHERE e.extnamespace = n.oid) " +
                            // a schema an extension created (postgis_tiger_geocoder's tiger_data) belongs to it
                            "AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_namespace'::regclass AND d.objid = n.oid AND d.deptype = 'e')"
```

(d) In the KDoc of `wipeExternalDatabase`, replace the sentence
`ONE SUITE AT A TIME against an external database: the advisory lock makes a second run wait instead of deadlock, but it still starts on a wiped database.`
with
`Two suites against the same external database no longer collide: the suite lock ([lockSuite]) makes the second jvm wait until the first exits, then it wipes and runs.`
and replace `and so does any schema an extension lives in.` with `and so does any schema an extension lives in or created.`

Run:

```bash
./gradlew :chawpi-test:ktlintFormat
./gradlew :chawpi-test:ktlintCheck :chawpi-test:test
```

Expected: `BUILD SUCCESSFUL` (the existing unit tests still pass).

- [ ] **Step 4: The suites — `backend/chawpi-integration-tests/build.gradle.kts`**

Replace the whole file with:

```kotlin
plugins {
    id("chawpi.spring-module")
    id("chawpi.integration-test")
    `java-test-fixtures`
}

// never published (chawpi-bom skips it by name). every test group is a suite of its own: its own
// source set, compile, ktlint task and jvm. groups are written in parallel, and a slice needs a
// classpath that really lacks the other modules (an optional adapter switches on by class presence).
description = "Chawpi integration tests: the original's api tests against assembled test apps"

val chawpiModules = listOf("views", "forms", "pages", "workflow", "automation", "documents", "gis", "agent")

dependencies {
    // P2's wiring tests: every module in one context runner, no database
    testImplementation(project(":chawpi-test"))
    chawpiModules.forEach { testImplementation(project(":chawpi-$it")) }

    // shared by every suite: the full test app, its base class, the module route table, the slice checks
    testFixturesApi(project(":chawpi-test"))
    testFixturesApi(project(":chawpi-spring-boot-starter"))
}

// every starter, on postgis
val fullAppSuites =
    listOf(
        "fullApp",
        "workflowIt",
        "automationIt",
        "documentsIt",
        "pagesIt",
        "viewsFormsIt",
        "layersIt",
        "agentIt",
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
        "agentOnly" to listOf("agent")
    )

val postgisSuites = fullAppSuites.toSet() + "gisOnly"
val allSuites = fullAppSuites + sliceSuites.keys

// external mode: both servers share name, user and password; only the postgis port differs
val externalDb = providers.environmentVariable("CHAWPI_TEST_DB_HOST").isPresent
val gisDbPort: String? = providers.environmentVariable("CHAWPI_TEST_GIS_DB_PORT").orNull

testing {
    suites {
        (fullAppSuites.associateWith { chawpiModules } + sliceSuites).forEach { (suite, starters) ->
            register<JvmTestSuite>(suite) {
                useJUnitJupiter() // jupiter's version comes from the boot bom below, not from gradle's default
                dependencies {
                    implementation(testFixtures(project()))
                    implementation(platform(libs.spring.boot.bom))
                    starters.forEach { implementation(project(":chawpi-spring-boot-starter-$it")) }
                    runtimeOnly("org.junit.platform:junit-platform-launcher")
                }
                targets.all {
                    testTask.configure {
                        group = "verification"
                        description = "Integration suite '$suite' against a real database."
                        shouldRunAfter(tasks.named("test"))
                        // the suites decide when the assistant exists, not the developer's shell
                        environment("ANTHROPIC_API_KEY", "")
                        if (suite in postgisSuites) {
                            systemProperty("chawpi.test.db.image", "postgis/postgis:18-3.6")
                            if (gisDbPort != null) environment("CHAWPI_TEST_DB_PORT", gisDbPort)
                            val missingGisPort = externalDb && gisDbPort == null
                            doFirst {
                                if (missingGisPort) {
                                    throw GradleException(
                                        "$name needs PostGIS: with CHAWPI_TEST_DB_HOST set, also set CHAWPI_TEST_GIS_DB_PORT " +
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
```

Run: `./gradlew :chawpi-integration-tests:tasks --all -q | grep -E "^(fullApp|coreOnly|gisOnly|schemaParityIt|ktlintGisOnlySourceSetFormat) "`
Expected: those five task names listed. If Gradle rejects the script, fix it inside this file only, keeping every suite name, the PostGIS mapping and the two `tasks.named` lines.

- [ ] **Step 5: Shared fixtures**

`backend/chawpi-integration-tests/src/testFixtures/kotlin/chawpi/it/full/FullTestApplication.kt`:

```kotlin
package chawpi.it.full

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// the app every full-app suite boots: every starter on the classpath, auto-configuration and nothing
// else, the way a real app gets chawpi. no component scan, so no library class is picked up twice.
@SpringBootConfiguration
@EnableAutoConfiguration
class FullTestApplication
```

`backend/chawpi-integration-tests/src/testFixtures/kotlin/chawpi/it/full/FullAppIntegrationTest.kt`:

```kotlin
package chawpi.it.full

import chawpi.test.ChawpiIntegrationTest
import org.springframework.test.context.TestPropertySource

// base of every ported api test. no background drain: the tests drive the automation runner by
// hand, as the original's did. geoserver stays out of the suite; a real publish is checked by hand.
@TestPropertySource(
    properties = [
        "chawpi.automation.poll-interval=0s",
        "chawpi.gis.geoserver.enabled=false",
        "chawpi.gis.geoserver.url=http://geoserver.invalid:8081/geoserver"
    ]
)
abstract class FullAppIntegrationTest : ChawpiIntegrationTest()
```

`backend/chawpi-integration-tests/src/testFixtures/kotlin/chawpi/it/support/ModuleRoutes.kt`:

```kotlin
package chawpi.it.support

import org.springframework.http.HttpMethod

// every route a module adds, by module: the same 50 as AllModulesWiringTest.LEGACY_MODULE_ROUTES
// (ModuleRoutesTest keeps the two equal). the module matrix probes the ones an app has not got.
object ModuleRoutes {
    const val PROBE_ID = "00000000-0000-0000-0000-00000000abcd"

    val byModule: Map<String, List<String>> =
        mapOf(
            "views" to
                listOf(
                    "GET /api/metadata/objects/{object}/views",
                    "GET /api/objects/{object}/views",
                    "POST /api/objects/{object}/views",
                    "GET /api/objects/{object}/views/{name}",
                    "PUT /api/objects/{object}/views/{name}",
                    "DELETE /api/objects/{object}/views/{name}"
                ),
            "forms" to
                listOf(
                    "GET /api/metadata/objects/{object}/forms",
                    "GET /api/objects/{object}/forms",
                    "POST /api/objects/{object}/forms",
                    "GET /api/objects/{object}/forms/{name}",
                    "PUT /api/objects/{object}/forms/{name}",
                    "DELETE /api/objects/{object}/forms/{name}"
                ),
            "pages" to
                listOf(
                    "GET /api/metadata/objects/{object}/pages",
                    "GET /api/metadata/page-templates",
                    "GET /api/objects/{object}/pages/{kind}",
                    "GET /api/pages",
                    "POST /api/pages",
                    "GET /api/pages/{name}",
                    "PUT /api/pages/{name}",
                    "DELETE /api/pages/{name}"
                ),
            "workflow" to
                listOf(
                    "GET /api/objects/{object}/workflow",
                    "PUT /api/objects/{object}/workflow",
                    "DELETE /api/objects/{object}/workflow",
                    "GET /api/objects/{object}/records/{id}/transitions",
                    "POST /api/objects/{object}/records/{id}/transitions/{name}"
                ),
            "automation" to
                listOf(
                    "GET /api/automation-runs",
                    "GET /api/objects/{object}/automations",
                    "POST /api/objects/{object}/automations",
                    "GET /api/objects/{object}/automations/{name}",
                    "PUT /api/objects/{object}/automations/{name}",
                    "DELETE /api/objects/{object}/automations/{name}",
                    "GET /api/objects/{object}/automations/{name}/runs"
                ),
            "documents" to
                listOf(
                    "GET /api/documents/{id}",
                    "GET /api/objects/{object}/document-types",
                    "POST /api/objects/{object}/document-types",
                    "GET /api/objects/{object}/document-types/{name}",
                    "PUT /api/objects/{object}/document-types/{name}",
                    "DELETE /api/objects/{object}/document-types/{name}",
                    "GET /api/objects/{object}/records/{id}/documents",
                    "POST /api/objects/{object}/records/{id}/documents/{type}"
                ),
            "gis" to
                listOf(
                    "GET /api/gis/layers",
                    "POST /api/gis/layers/{object}",
                    "DELETE /api/gis/layers/{object}",
                    "POST /api/gis/layers/{object}/{geometry}",
                    "DELETE /api/gis/layers/{object}/{geometry}",
                    "GET /api/gis/services",
                    "GET /api/gis/objects/{object}/features",
                    "GET /api/gis/objects/{object}/features/{id}"
                ),
            "agent" to
                listOf(
                    "GET /api/agent/status",
                    "POST /api/agent/ask"
                )
        )

    val all: List<String> get() = byModule.values.flatten()

    fun absentFrom(installed: Set<String>): List<String> = byModule.filterKeys { it !in installed }.values.flatten()

    // a concrete request for a route pattern: the caller's existing object, made-up ids and names.
    // an absent route is a 404 whatever the values; a present one must not be judged by these.
    fun probe(
        route: String,
        objectName: String
    ): Pair<HttpMethod, String> {
        val (verb, pattern) = route.split(" ", limit = 2)
        val uri =
            pattern
                .replace("{object}", objectName)
                .replace("{id}", PROBE_ID)
                .replace("{name}", "probe")
                .replace("{kind}", "record-detail")
                .replace("{type}", "probe")
                .replace("{geometry}", "geom")
        return HttpMethod.valueOf(verb) to uri
    }
}
```

`backend/chawpi-integration-tests/src/testFixtures/kotlin/chawpi/it/support/SliceSmokeTest.kt`:

```kotlin
package chawpi.it.support

import chawpi.test.ChawpiIntegrationTest
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient

/**
 * The module matrix: an app with core and [installed] on its classpath, nothing else. Every route of
 * every other module is absent -- a 404 for anyone holding a valid token, never a 403 -- and only the
 * installed modules' migrations ran. Each slice adds its own positive tests.
 */
abstract class SliceSmokeTest : ChawpiIntegrationTest() {
    @Autowired
    protected lateinit var db: DatabaseClient

    /** The modules on this app's classpath besides core. Pages brings forms. */
    protected abstract val installed: Set<String>

    protected lateinit var admin: String
    protected lateinit var objectName: String

    @BeforeEach
    fun createFlatObject() {
        admin = bearer()
        objectName = uniqueName("slice")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to objectName, "label" to "Slice", "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `every route of a module that is not installed answers 404 to an administrator`() {
        assertThat(notFoundMisses(admin)).isEmpty()
    }

    @Test
    fun `a member with no grants gets 404 from an absent module too, never 403`() {
        assertThat(notFoundMisses(memberWithoutGrants())).isEmpty()
    }

    @Test
    fun `only core's and the installed modules' migrations ran`() {
        val histories =
            runBlocking {
                db
                    .sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'chawpi' AND table_name LIKE 'flyway\\_history\\_%'")
                    .map { row, _ -> row.get("table_name", String::class.java)!! }
                    .all()
                    .collectList()
                    .awaitFirstOrNull()
                    .orEmpty()
            }
        // agent has no tables
        val expected = listOf("flyway_history_core", "flyway_history_core_seed") + (installed - "agent").map { "flyway_history_$it" }
        assertThat(histories).containsExactlyInAnyOrderElementsOf(expected)
    }

    // "<route> -> <status>" for every absent route that did not answer 404
    private fun notFoundMisses(token: String): List<String> =
        ModuleRoutes.absentFrom(installed).mapNotNull { route ->
            val status = statusOf(route, token)
            if (status == 404) null else "$route -> $status"
        }

    protected fun statusOf(
        route: String,
        token: String
    ): Int {
        val (method, uri) = ModuleRoutes.probe(route, objectName)
        return client
            .method(method)
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectBody()
            .returnResult()
            .status
            .value()
    }

    // a signed-in user holding a role with no grant at all
    protected fun memberWithoutGrants(): String {
        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to role, "label" to "Nadie", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
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

- [ ] **Step 6: The route table test (no database)**

`backend/chawpi-integration-tests/src/test/kotlin/chawpi/it/ModuleRoutesTest.kt`:

```kotlin
package chawpi.it

import chawpi.it.support.ModuleRoutes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ModuleRoutesTest {
    @Test
    fun `the matrix probes exactly the module routes the wiring test pins`() {
        assertThat(ModuleRoutes.all).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(AllModulesWiringTest.LEGACY_MODULE_ROUTES)
    }

    @Test
    fun `a probe fills every path variable`() {
        ModuleRoutes.all.forEach { route -> assertThat(ModuleRoutes.probe(route, "predio").second).describedAs(route).doesNotContain("{", "}") }
    }
}
```

Run: `./gradlew :chawpi-integration-tests:test --rerun`
Expected: PASS, 6 tests (P2's 4 + these 2). If the first fails, the table in `ModuleRoutes` is wrong: fix `ModuleRoutes`, never `LEGACY_MODULE_ROUTES`.

- [ ] **Step 7: The full-app boot test**

`backend/chawpi-integration-tests/src/fullApp/kotlin/chawpi/it/full/FullAppBootTest.kt`:

```kotlin
package chawpi.it.full

import chawpi.it.support.SliceSmokeTest
import chawpi.test.ChawpiTestDatabase
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.test.context.TestPropertySource
import java.sql.DriverManager

// the full app on postgis: every module installed (so the matrix has nothing absent and every
// migration ran), every module answers, and this jvm holds the suite lock. same properties as
// FullAppIntegrationTest: this one reuses the slice checks, so it cannot extend that class.
@TestPropertySource(
    properties = [
        "chawpi.automation.poll-interval=0s",
        "chawpi.gis.geoserver.enabled=false",
        "chawpi.gis.geoserver.url=http://geoserver.invalid:8081/geoserver"
    ]
)
class FullAppBootTest : SliceSmokeTest() {
    override val installed = setOf("views", "forms", "pages", "workflow", "automation", "documents", "gis", "agent")

    @Autowired
    private lateinit var environment: Environment

    @Test
    fun `postgis and pgcrypto live in public`() {
        val schemas =
            runBlocking {
                db
                    .sql(
                        "SELECT e.extname, n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace " +
                            "WHERE e.extname IN ('postgis', 'pgcrypto')"
                    ).map { row, _ -> row.get("extname", String::class.java)!! to row.get("nspname", String::class.java)!! }
                    .all()
                    .collectList()
                    .awaitFirstOrNull()
                    .orEmpty()
                    .toMap()
            }
        assertThat(schemas).containsEntry("postgis", "public").containsEntry("pgcrypto", "public")
    }

    @Test
    fun `every module answers its list route`() {
        listOf(
            "/api/objects/$objectName/views",
            "/api/objects/$objectName/forms",
            "/api/pages",
            "/api/metadata/page-templates",
            "/api/objects/$objectName/automations",
            "/api/automation-runs",
            "/api/objects/$objectName/document-types",
            "/api/gis/layers",
            "/api/gis/services",
            "/api/agent/status"
        ).forEach { uri ->
            client
                .get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isOk
        }
        // workflow has no list route: its system column is how it shows
        client
            .get()
            .uri("/api/metadata/system-fields")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[?(@.name == 'workflow_state')].scope")
            .isEqualTo("WORKFLOW")
    }

    @Test
    fun `the suite lock is held for as long as this jvm runs`() {
        assumeTrue(System.getenv("CHAWPI_TEST_DB_HOST") != null, "testcontainers: every jvm has a database of its own, no lock")
        val url =
            "jdbc:postgresql://${environment.getProperty("chawpi.database.host")}:${environment.getProperty("chawpi.database.port")}/" +
                environment.getProperty("chawpi.database.name")
        DriverManager
            .getConnection(url, environment.getProperty("chawpi.database.username"), environment.getProperty("chawpi.database.password"))
            .use { other ->
            other.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_try_advisory_lock(hashtext('${ChawpiTestDatabase.SUITE_LOCK}'))").use { rows ->
                    rows.next()
                    assertThat(rows.getBoolean(1)).describedAs("a second session took the suite lock").isFalse()
                }
            }
        }
    }
}
```

- [ ] **Step 8: Run it on PostGIS**

```bash
source backend/chawpi-integration-tests/it-env.sh
mkdir -p backend/chawpi-integration-tests/build
./gradlew :chawpi-integration-tests:fullApp --rerun > backend/chawpi-integration-tests/build/fullApp-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/chawpi-integration-tests/build/test-results/fullApp/*.xml
```

Expected: exit 0, `tests="6" skipped="0" failures="0" errors="0"`. This is the first time the wipe runs against the PostGIS server. If a module migration fails, report `BLOCKED: behaviour differs: <module> migration on PostGIS — <error>`.

- [ ] **Step 9: The PostGIS port is required, and core still passes with the lock**

```bash
source backend/chawpi-integration-tests/it-env.sh
env -u CHAWPI_TEST_GIS_DB_PORT ./gradlew :chawpi-integration-tests:fullApp --rerun 2>&1 | grep -m1 "needs PostGIS"
./gradlew :chawpi-core:integrationTest --rerun > backend/chawpi-integration-tests/build/core-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/chawpi-core/build/test-results/integrationTest/*.xml | awk -F'"' '{t+=$2; f+=$6+$8} END {print t" tests, "f" failed"}'
```

Expected: the first prints `fullApp needs PostGIS: with CHAWPI_TEST_DB_HOST set, also set CHAWPI_TEST_GIS_DB_PORT …`; the second exits 0 with `<N> tests, 0 failed`, where N is the current core IT count (read it from the report of the last run; ≥94) — do not hard-code a number (core's ITs on 5443, now under the suite lock).

- [ ] **Step 10: Format, compile every suite, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintFormat
./gradlew :chawpi-integration-tests:ktlintCheck :chawpi-integration-tests:check
git status --short backend/build-logic backend/chawpi-test backend/chawpi-integration-tests
git log --oneline -1
```

Expected: `BUILD SUCCESSFUL` (`check` compiles all 20 suites — the Wave 1 ones are still empty — and runs the 6 unit tests, no IT); changes uncommitted; last commit unchanged.

### Task 2: Workflow API tests on the full app (Wave 1, suite `workflowIt`)

Port the original's `WorkflowApiTest` (17 tests). It needs pages too (the generated record page shows the WORKFLOW panel), which only the full app has.

**Files:**
- Create: `backend/chawpi-integration-tests/src/workflowIt/kotlin/chawpi/it/full/WorkflowApiTest.kt` (port of `/Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis/api/WorkflowApiTest.kt`)

**Interfaces:**
- Consumes: `chawpi.it.full.FullAppIntegrationTest` (Task 1; same package, no import needed), which inherits `client`, `uniqueName`, `bearer()`, `bearer(email, password)` from `chawpi.test.ChawpiIntegrationTest`. Suite `workflowIt` (Task 1): every starter, PostGIS.
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
```

- [ ] **Step 2: Copy and port**

```bash
source backend/chawpi-integration-tests/it-env.sh
mkdir -p $IT/src/workflowIt/kotlin/chawpi/it/full
cp $TSRC/WorkflowApiTest.kt $IT/src/workflowIt/kotlin/chawpi/it/full/
port_it $IT/src/workflowIt/kotlin/chawpi/it/full/WorkflowApiTest.kt
grep -n "sapgis\|com\.chawpi\|IntegrationTest()" $IT/src/workflowIt/kotlin/chawpi/it/full/WorkflowApiTest.kt
```

Expected: the grep prints only the `class WorkflowApiTest : FullAppIntegrationTest() {` line.

- [ ] **Step 3: Compile**

Run: `./gradlew :chawpi-integration-tests:compileWorkflowItKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:workflowIt --rerun > $IT/build/workflowIt-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/workflowIt/*.xml
```

Expected: exit 0, `tests="17" skipped="0" failures="0" errors="0"`. A failure is never fixed by editing an assertion or library code: report `BLOCKED: behaviour differs: …` (Global Constraints).

- [ ] **Step 5: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintWorkflowItSourceSetFormat
./gradlew :chawpi-integration-tests:ktlintWorkflowItSourceSetCheck
git status --short $IT/src/workflowIt
```

Expected: both pass; the file is untracked (`??`).

### Task 3: Automation API tests on the full app (Wave 1, suite `automationIt`)

Port the original's `AutomationApiTest` (10 tests). It drives `AutomationRunner.drainOnce` by hand (the base sets `poll-interval=0s`) and issues documents through the documents→automation adapter.

**Files:**
- Create: `backend/chawpi-integration-tests/src/automationIt/kotlin/chawpi/it/full/AutomationApiTest.kt` (port of `/Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis/api/AutomationApiTest.kt`)

**Interfaces:**
- Consumes: `chawpi.it.full.FullAppIntegrationTest` (Task 1); `chawpi.automation.AutomationRunner` (`suspend fun drainOnce(batch: Int): Int`, P2 bean). Suite `automationIt`: every starter, PostGIS.
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
```

- [ ] **Step 2: Copy and port**

```bash
source backend/chawpi-integration-tests/it-env.sh
mkdir -p $IT/src/automationIt/kotlin/chawpi/it/full
cp $TSRC/AutomationApiTest.kt $IT/src/automationIt/kotlin/chawpi/it/full/
port_it $IT/src/automationIt/kotlin/chawpi/it/full/AutomationApiTest.kt
grep -n "sapgis\|com\.chawpi\|IntegrationTest()\|^import chawpi" $IT/src/automationIt/kotlin/chawpi/it/full/AutomationApiTest.kt
```

Expected: `import chawpi.automation.AutomationRunner` and the `: FullAppIntegrationTest()` class line; nothing else.

- [ ] **Step 3: Compile**

Run: `./gradlew :chawpi-integration-tests:compileAutomationItKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:automationIt --rerun > $IT/build/automationIt-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/automationIt/*.xml
```

Expected: exit 0, `tests="10" skipped="0" failures="0" errors="0"`. A failure is never fixed by editing an assertion or library code: report `BLOCKED: behaviour differs: …`.

- [ ] **Step 5: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintAutomationItSourceSetFormat
./gradlew :chawpi-integration-tests:ktlintAutomationItSourceSetCheck
git status --short $IT/src/automationIt
```

Expected: both pass; the file is untracked.

### Task 4: Document and document-type API tests on the full app (Wave 1, suite `documentsIt`)

Port the original's `DocumentApiTest` (10 tests) and `DocumentTypeApiTest` (12 tests).

**Files:**
- Create: `backend/chawpi-integration-tests/src/documentsIt/kotlin/chawpi/it/full/DocumentApiTest.kt` (port of `/Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis/api/DocumentApiTest.kt`)
- Create: `backend/chawpi-integration-tests/src/documentsIt/kotlin/chawpi/it/full/DocumentTypeApiTest.kt` (port of `…/DocumentTypeApiTest.kt`)

**Interfaces:**
- Consumes: `chawpi.it.full.FullAppIntegrationTest` (Task 1). Suite `documentsIt`: every starter, PostGIS.
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
```

- [ ] **Step 2: Copy and port**

```bash
source backend/chawpi-integration-tests/it-env.sh
D=$IT/src/documentsIt/kotlin/chawpi/it/full
mkdir -p $D
cp $TSRC/DocumentApiTest.kt $TSRC/DocumentTypeApiTest.kt $D/
port_it $D/DocumentApiTest.kt $D/DocumentTypeApiTest.kt
grep -n "sapgis\|com\.chawpi\|IntegrationTest()" $D/*.kt
```

Expected: only the two `: FullAppIntegrationTest()` class lines.

- [ ] **Step 3: Replace the V14 replay (Ruling T11b)**

chawpi has no `V14__history_points_at_a_document.sql` to replay: documents' V1 builds V14's end state directly (clean start, like T11). In `$D/DocumentApiTest.kt`:

- delete from the `    @Test` line directly above ``    fun `the migration replays without error`() {`` down to and including that test's closing `    }`;
- delete the blank line before `    // runs a migration file by hand, against the already-migrated test database. proves the SQL`, and everything from that comment down to and including the helper's closing `        }`, keeping the class's final `}`;
- insert in place of the test:

```kotlin
    // clean start: the original replayed its V14 here by hand. chawpi has no V14 to replay; what is
    // left to pin is V14's end state, which chawpi-documents' V1 builds: ISSUE is an audit operation
    // and an audit row points at its document.
    @Test
    fun `the audit log is born knowing ISSUE and pointing at documents`() {
        val definitions =
            runBlocking {
                db
                    .sql(
                        """
                        SELECT conname, pg_get_constraintdef(oid) AS def FROM pg_constraint
                        WHERE conrelid = 'chawpi.audit_log'::regclass
                          AND conname IN ('audit_log_operation_valid', 'audit_log_document_id_fkey')
                        """.trimIndent()
                    ).map { row, _ -> row.get("conname", String::class.java)!! to row.get("def", String::class.java)!! }
                    .all()
                    .collectList()
                    .awaitFirstOrNull()
                    .orEmpty()
                    .toMap()
            }
        assertThat(definitions["audit_log_operation_valid"]).contains("'ISSUE'")
        assertThat(definitions["audit_log_document_id_fkey"]).contains("documents(id)").contains("ON DELETE SET NULL")
    }
```

`documents(id)` is used, not `chawpi.documents(id)`, because the R2DBC session's `search_path` (`"$user", public` with user `chawpi`) may print the reference unqualified. This uses the file's existing imports (`runBlocking`, `awaitFirstOrNull`, `assertThat`, `db`); do not add new ones unless the compiler asks.

Check:

```bash
source backend/chawpi-integration-tests/it-env.sh
grep -c "runMigrationSql\|db/migration" $D/DocumentApiTest.kt   # expect 0
grep -c 'fun `' $D/DocumentApiTest.kt                           # expect 10
```

- [ ] **Step 4: Compile**

Run: `./gradlew :chawpi-integration-tests:compileDocumentsItKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:documentsIt --rerun > $IT/build/documentsIt-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/documentsIt/*.xml
```

Expected: exit 0; two result lines, `tests="10"` and `tests="12"`, each with `failures="0" errors="0"`. A failure is never fixed by editing an assertion or library code: report `BLOCKED: behaviour differs: …`.

- [ ] **Step 6: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintDocumentsItSourceSetFormat
./gradlew :chawpi-integration-tests:ktlintDocumentsItSourceSetCheck
git status --short $IT/src/documentsIt
```

Expected: both pass; files untracked.

### Task 5: Page API tests on the full app, V11 replay replaced (Wave 1, suite `pagesIt`)

Port the original's `PageApiTest` (61 tests). Two of them replay the original's migration `V11__a_page_has_a_template.sql` by hand; chawpi has no such file (clean start, P2 M4). Ruling T11: drop both and the helper, add one test pinning V11's end state. Result: 60 tests. The MAP tests need gis, the WORKFLOW ones workflow: full app only.

**Files:**
- Create: `backend/chawpi-integration-tests/src/pagesIt/kotlin/chawpi/it/full/PageApiTest.kt` (port of `/Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt`, then edited in Step 3)

**Interfaces:**
- Consumes: `chawpi.it.full.FullAppIntegrationTest` (Task 1); `chawpi.pages.PageTemplate` (P2). The file's own helpers `createPage(name, objectName, template, root)`, `page(vararg regions)`, `region(name, children, layout)`, `text(column, content)`, `resolve(objectName)` and fields `db: DatabaseClient`, `token`, `predio`. Suite `pagesIt`: every starter, PostGIS.
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
```

- [ ] **Step 2: Copy and port**

```bash
source backend/chawpi-integration-tests/it-env.sh
F=$IT/src/pagesIt/kotlin/chawpi/it/full/PageApiTest.kt
mkdir -p $(dirname $F)
cp $TSRC/PageApiTest.kt $F
port_it $F
grep -n "sapgis\|com\.chawpi\|IntegrationTest()\|^import chawpi\|chawpi\.pages WHERE" $F
```

Expected: `import chawpi.pages.PageTemplate`, the `: FullAppIntegrationTest()` class line and the SQL line `…FROM chawpi.pages WHERE name = :name…`.

- [ ] **Step 3: Replace the V11 replay**

In `$F`, delete everything from the line

```kotlin
    // flyway already ran at boot, so a migration is replayed by hand against the rows just written
```

down to, and including, the closing `}` of the test `` `the migration replays without error` `` (the helper `runMigrationSql` and the two tests `` `a stored page does not survive the migration` `` and `` `the migration replays without error` ``). Keep the class's final closing `}`. In its place insert:

```kotlin
    // clean start: the original replayed its V11 here by hand to pin "a stored page does not survive
    // the migration". chawpi has no stored pages to migrate, so what is left to pin is V11's end state
    // -- template instead of layout -- and the fallback it relied on: a page gone means generated.
    @Test
    fun `the pages table is born in its final shape and a deleted page falls back to the generated one`() {
        val columns =
            runBlocking {
                db
                    .sql("SELECT column_name, is_nullable, column_default FROM information_schema.columns WHERE table_schema = 'chawpi' AND table_name = 'pages'")
                    .map { row, _ ->
                        Triple(
                            row.get("column_name", String::class.java)!!,
                            row.get("is_nullable", String::class.java)!!,
                            row.get("column_default", String::class.java)
                        )
                    }.all()
                    .collectList()
                    .awaitFirstOrNull()
                    .orEmpty()
            }
        assertThat(columns.map { it.first }).contains("template").doesNotContain("layout")
        val template = columns.first { it.first == "template" }
        assertThat(template.second).isEqualTo("NO")
        assertThat(template.third).isEqualTo("'one-region'::text")

        val name = uniqueName("page").take(30)
        createPage(name, predio, "one-region", page(region("MAIN", listOf(text(1, "mio")))))
            .expectStatus()
            .isCreated
        resolve(predio)
            .jsonPath("$.generated")
            .isEqualTo(false)

        client
            .delete()
            .uri("/api/pages/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent

        resolve(predio)
            .jsonPath("$.generated")
            .isEqualTo(true)
            .jsonPath("$.template.name")
            .isEqualTo("one-region")
    }
```

Check:

```bash
source backend/chawpi-integration-tests/it-env.sh
grep -c "runMigrationSql\|db/migration" $F   # expect 0
grep -c 'fun `' $F                           # expect 60
```

- [ ] **Step 4: Compile**

Run: `./gradlew :chawpi-integration-tests:compilePagesItKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:pagesIt --rerun > $IT/build/pagesIt-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/pagesIt/*.xml
```

Expected: exit 0, `tests="60" skipped="0" failures="0" errors="0"`. The one allowed difference against the original (P2 M10): with gis and workflow installed, an `Unknown component` error lists MAP and WORKFLOW at the end. If a ported assertion pins the full error text and fails only on that order, report it as `BLOCKED: behaviour differs: M10 order — <test>` (the controller decides); anything else also `BLOCKED: behaviour differs: …`.

- [ ] **Step 6: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintPagesItSourceSetFormat
./gradlew :chawpi-integration-tests:ktlintPagesItSourceSetCheck
git status --short $IT/src/pagesIt
```

Expected: both pass (format removes the imports the dropped helper used, if unused); file untracked.

### Task 6: View and form API tests on the full app (Wave 1, suite `viewsFormsIt`)

Port the original's `ViewApiTest` (11 tests) and `FormApiTest` (13 tests). `FormApiTest` also creates pages that name forms, so it needs pages: full app.

**Files:**
- Create: `backend/chawpi-integration-tests/src/viewsFormsIt/kotlin/chawpi/it/full/ViewApiTest.kt` (port of `/Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis/api/ViewApiTest.kt`)
- Create: `backend/chawpi-integration-tests/src/viewsFormsIt/kotlin/chawpi/it/full/FormApiTest.kt` (port of `…/FormApiTest.kt`)

**Interfaces:**
- Consumes: `chawpi.it.full.FullAppIntegrationTest` (Task 1). Suite `viewsFormsIt`: every starter, PostGIS.
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
```

- [ ] **Step 2: Copy and port**

```bash
source backend/chawpi-integration-tests/it-env.sh
D=$IT/src/viewsFormsIt/kotlin/chawpi/it/full
mkdir -p $D
cp $TSRC/ViewApiTest.kt $TSRC/FormApiTest.kt $D/
port_it $D/ViewApiTest.kt $D/FormApiTest.kt
grep -n "sapgis\|com\.chawpi\|IntegrationTest()" $D/*.kt
```

Expected: only the two `: FullAppIntegrationTest()` class lines.

- [ ] **Step 3: Compile**

Run: `./gradlew :chawpi-integration-tests:compileViewsFormsItKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:viewsFormsIt --rerun > $IT/build/viewsFormsIt-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/viewsFormsIt/*.xml
```

Expected: exit 0; `tests="11"` and `tests="13"`, each `failures="0" errors="0"`. A failure: report `BLOCKED: behaviour differs: …`.

- [ ] **Step 5: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintViewsFormsItSourceSetFormat
./gradlew :chawpi-integration-tests:ktlintViewsFormsItSourceSetCheck
git status --short $IT/src/viewsFormsIt
```

Expected: both pass; files untracked.

### Task 7: GIS layer API tests on the full app (Wave 1, suite `layersIt`)

Port the original's `LayerApiTest` (8 tests). GeoServer stays disabled (the test's own properties, as in the original); the workspace in its expected URLs and in its property both become `chawpi`.

**Files:**
- Create: `backend/chawpi-integration-tests/src/layersIt/kotlin/chawpi/it/full/LayerApiTest.kt` (port of `/Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis/api/LayerApiTest.kt`)

**Interfaces:**
- Consumes: `chawpi.it.full.FullAppIntegrationTest` (Task 1); `chawpi.gis.GeoServerProperties` bound from `chawpi.gis.geoserver.*` (P2 M8). Suite `layersIt`: every starter, PostGIS.
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
```

- [ ] **Step 2: Copy and port**

```bash
source backend/chawpi-integration-tests/it-env.sh
F=$IT/src/layersIt/kotlin/chawpi/it/full/LayerApiTest.kt
mkdir -p $(dirname $F)
cp $TSRC/LayerApiTest.kt $F
port_it $F
sed -n '7,15p' $F
grep -n "sapgis\|com\.chawpi" $F || echo "clean"
```

Expected: lines 7–15: the comment, the `@TestPropertySource` block with `chawpi.gis.geoserver.enabled=false`, `…url=http://geoserver.invalid:8081/geoserver`, `…workspace=chawpi`, then the class line (`class LayerApiTest : FullAppIntegrationTest() {`); then `clean`.

- [ ] **Step 3: Compile**

Run: `./gradlew :chawpi-integration-tests:compileLayersItKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:layersIt --rerun > $IT/build/layersIt-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/layersIt/*.xml
```

Expected: exit 0, `tests="8" skipped="0" failures="0" errors="0"`. A failure: report `BLOCKED: behaviour differs: …`.

- [ ] **Step 5: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintLayersItSourceSetFormat
./gradlew :chawpi-integration-tests:ktlintLayersItSourceSetCheck
git status --short $IT/src/layersIt
```

Expected: both pass; file untracked.

### Task 8: Agent API, Embabel and tools tests on the full app (Wave 1, suite `agentIt`)

Port the original's `AgentApiTest` (6 tests, no key: the assistant reports itself off), `AgentEmbabelTest` (5 tests, a fake key plus Embabel's scripted model double, so nothing reaches Anthropic) and `AgentToolsTest` (10 tests, tools called directly; `available_transitions` goes through the agent→workflow adapter).

**Files:**
- Create: `backend/chawpi-integration-tests/src/agentIt/kotlin/chawpi/it/full/AgentApiTest.kt` (port of `/Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis/api/AgentApiTest.kt`)
- Create: `backend/chawpi-integration-tests/src/agentIt/kotlin/chawpi/it/full/AgentEmbabelTest.kt` (port of `…/AgentEmbabelTest.kt`)
- Create: `backend/chawpi-integration-tests/src/agentIt/kotlin/chawpi/it/full/AgentToolsTest.kt` (port of `…/AgentToolsTest.kt`)

**Interfaces:**
- Consumes: `chawpi.it.full.FullAppIntegrationTest` (Task 1); `chawpi.agent.AgentAnswer`, `AgentService`, `AgentToolCatalog`, `AgentToolResult`, `AgentTools` (P2); `chawpi.agent.autoconfigure.EmbabelGate` reads `chawpi.agent.api-key` (hence the T7 rename of `sapgis.agent.apiKey`); `com.embabel.agent.test.integration.ScriptedLlmOperations` (transitive from the agent starter, as in the original). Suite `agentIt`: every starter (the agent starter brings the Anthropic provider), PostGIS; its Gradle task sets `ANTHROPIC_API_KEY=""`.
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
```

- [ ] **Step 2: Copy and port**

```bash
source backend/chawpi-integration-tests/it-env.sh
D=$IT/src/agentIt/kotlin/chawpi/it/full
mkdir -p $D
cp $TSRC/AgentApiTest.kt $TSRC/AgentEmbabelTest.kt $TSRC/AgentToolsTest.kt $D/
port_it $D/AgentApiTest.kt $D/AgentEmbabelTest.kt $D/AgentToolsTest.kt
grep -n "sapgis\|com\.chawpi\|apiKey\|api-key" $D/*.kt
```

Expected: no `sapgis`/`com.chawpi`; `AgentApiTest.kt` has `@TestPropertySource(properties = ["chawpi.agent.api-key="])`; `AgentEmbabelTest.kt` has `"chawpi.agent.api-key=sk-ant-not-a-real-key"` plus the two Embabel/Spring AI key properties unchanged.

- [ ] **Step 3: Compile**

Run: `./gradlew :chawpi-integration-tests:compileAgentItKotlin`
Expected: `BUILD SUCCESSFUL`. If `ScriptedLlmOperations` does not resolve, report `BLOCKED: embabel test double not on the agent starter's classpath` (no new library may be added without the controller).

- [ ] **Step 4: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:agentIt --rerun > $IT/build/agentIt-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/agentIt/*.xml
```

Expected: exit 0; three result lines `tests="6"`, `tests="5"`, `tests="10"`, each `failures="0" errors="0"`. A failure: report `BLOCKED: behaviour differs: …`. Copy the three result lines into your report now; Step 6 replaces them (it re-runs only `*AgentApiTest`, overwriting `build/test-results/agentIt`).

- [ ] **Step 5: Format and check**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintAgentItSourceSetFormat
./gradlew :chawpi-integration-tests:ktlintAgentItSourceSetCheck
```

Expected: both pass.

- [ ] **Step 6: A key in the developer's shell changes nothing (Review Focus 3)**

```bash
source backend/chawpi-integration-tests/it-env.sh
ANTHROPIC_API_KEY=sk-ant-fake-from-shell ./gradlew :chawpi-integration-tests:agentIt --rerun --tests '*AgentApiTest' > $IT/build/agentIt-shellkey.log 2>&1; echo "exit $?"
git status --short $IT/src/agentIt
```

(background again). Expected: exit 0 — `AgentApiTest` still sees `enabled=false`, because the task blanks the variable. Files untracked.
### Task 9: The original's core tests, geometry included, on the full app (Wave 1, suite `coreParityIt`)

Ruling T8. P1 cut the geometry cases out of core's copies of five original tests (core has no PostGIS). Port the five originals *whole* into the full app: that restores every cut assertion with the original's own wording — `MetadataApiTest`: spatial index, several geometries, first geometry as the object's, geometry not unique; `RecordApiTest`: CRS round-trip, wrong geometry type, FeatureCollection, bbox on features, several geometries, null clears, undeclared geometry, bbox on a named geometry; `PermissionEnforcementTest`: a locked geometry; `FieldApiTest`: `workflow_state` published with scope WORKFLOW; `ObjectCrudApiTest`: a field an automation depends on cannot be deleted — and re-runs the rest of the original's core contract with every module installed.

**Files:**
- Create: `backend/chawpi-integration-tests/src/coreParityIt/kotlin/chawpi/it/full/MetadataApiTest.kt` (port of `/Users/jorge/IdeaProjects/sapgis/backend/src/test/kotlin/com/sapgis/api/MetadataApiTest.kt`)
- Create: `…/coreParityIt/kotlin/chawpi/it/full/RecordApiTest.kt` (port of `…/RecordApiTest.kt`)
- Create: `…/coreParityIt/kotlin/chawpi/it/full/PermissionEnforcementTest.kt` (port of `…/PermissionEnforcementTest.kt`)
- Create: `…/coreParityIt/kotlin/chawpi/it/full/FieldApiTest.kt` (port of `…/FieldApiTest.kt`)
- Create: `…/coreParityIt/kotlin/chawpi/it/full/ObjectCrudApiTest.kt` (port of `…/ObjectCrudApiTest.kt`)

**Interfaces:**
- Consumes: `chawpi.it.full.FullAppIntegrationTest` (Task 1); `chawpi.core.metadata.SystemFieldResponse` (core). Suite `coreParityIt`: every starter, PostGIS.
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
```

- [ ] **Step 2: Copy and port the five originals**

```bash
source backend/chawpi-integration-tests/it-env.sh
D=$IT/src/coreParityIt/kotlin/chawpi/it/full
mkdir -p $D
for t in MetadataApiTest RecordApiTest PermissionEnforcementTest FieldApiTest ObjectCrudApiTest; do cp $TSRC/$t.kt $D/; done
port_it $D/*.kt
grep -n "sapgis\|com\.chawpi\|DATA_SCHEMA\|^import chawpi" $D/*.kt
for t in MetadataApiTest RecordApiTest PermissionEnforcementTest FieldApiTest ObjectCrudApiTest; do echo "$t $(grep -c 'fun `' $D/$t.kt)"; done
```

Expected: the only `import chawpi` line is `FieldApiTest.kt: import chawpi.core.metadata.SystemFieldResponse`; no `DATA_SCHEMA` (the port turned `'$DATA_SCHEMA'` into `'app_data'` and dropped its import); counts `MetadataApiTest 8`, `RecordApiTest 12`, `PermissionEnforcementTest 13`, `FieldApiTest 7`, `ObjectCrudApiTest 10`. No other edit: these files are the original contract.

- [ ] **Step 3: Compile**

Run: `./gradlew :chawpi-integration-tests:compileCoreParityItKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:coreParityIt --rerun > $IT/build/coreParityIt-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/coreParityIt/*.xml | awk -F'"' '{t+=$2; f+=$6+$8} END {print t" tests, "f" failed"}'
```

Expected: exit 0, `50 tests, 0 failed`. The P2 hardening (Task 16) changed link/unlink and the audit "after"; none of these five files covers those, so none is an allowed difference. A failure: report `BLOCKED: behaviour differs: <test> — <assertion>`.

- [ ] **Step 5: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintCoreParityItSourceSetFormat
./gradlew :chawpi-integration-tests:ktlintCoreParityItSourceSetCheck
git status --short $IT/src/coreParityIt
```

Expected: both pass; files untracked.

### Task 10: Wire JSON parity with GEOMETRY (Wave 1, suite `wireParityIt`)

Ruling T9. The jsonPath assertions of the ported tests check picked values; this checks whole bodies. The expected JSON below is the original's, derived from its DTOs: `ObjectDefinitionResponse`/`FieldResponse`/`GeometryResponse`/`ObjectResponse` (`metadata/MetadataDtos.kt`: every property serialised, nulls included), `RecordResponse` (`data/RecordService.kt`: `attributes` holds every non-GEOMETRY field, `geometries` every declared geometry, null included, per `PhysicalTableRecordStore.mapRow`; `state` null without a workflow), `Feature`/`FeatureCollection` (`gis/GeoJson.kt` + `gis/FeatureController.kt`: id `<record>:<geometry>`, collection properties = attributes + `__label` (first text field) + `__id`, single feature properties = attributes only). Geometries use SRID 4326, so `ST_AsGeoJSON` prints back exactly the coordinates sent.

**Files:**
- Create: `backend/chawpi-integration-tests/src/wireParityIt/kotlin/chawpi/it/full/WireJson.kt`
- Create: `backend/chawpi-integration-tests/src/wireParityIt/kotlin/chawpi/it/full/GeometryWireParityTest.kt`

**Interfaces:**
- Consumes: `chawpi.it.full.FullAppIntegrationTest` (Task 1). Suite `wireParityIt`: every starter, PostGIS.
- Produces: `object WireJson { fun normalize(raw: String, name: String): JsonNode; fun parse(expected: String): JsonNode }` (used only inside this suite).

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
mkdir -p $IT/src/wireParityIt/kotlin/chawpi/it/full
```

- [ ] **Step 2: The normaliser**

`backend/chawpi-integration-tests/src/wireParityIt/kotlin/chawpi/it/full/WireJson.kt`:

```kotlin
package chawpi.it.full

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

// the wire minus what differs per run: ids, timestamps, the object's unique name. jackson tree
// equality ignores object key order and keeps array order and number types.
object WireJson {
    private val mapper = JsonMapper.builder().build()
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun normalize(
        raw: String,
        name: String
    ): JsonNode {
        val tree = mapper.readTree(uuid.replace(raw, "<uuid>").replace(name, "<name>"))
        blankTimestamps(tree)
        return tree
    }

    fun parse(expected: String): JsonNode = mapper.readTree(expected)

    private fun blankTimestamps(node: JsonNode) {
        if (node is ObjectNode) {
            listOf("createdAt", "updatedAt").forEach { key ->
                if (node.has(key) && !node.get(key).isNull) node.put(key, "<ts>")
            }
        }
        node.forEach { blankTimestamps(it) }
    }
}
```

- [ ] **Step 3: The parity test**

`backend/chawpi-integration-tests/src/wireParityIt/kotlin/chawpi/it/full/GeometryWireParityTest.kt`:

```kotlin
package chawpi.it.full

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

// whole bodies, compared with the original's (derived from its dtos, see the plan's Task 10).
// a TEXT field, a 2d polygon and a 3d point, all in 4326 so coordinates come back as sent.
class GeometryWireParityTest : FullAppIntegrationTest() {
    private lateinit var token: String
    private lateinit var name: String

    @BeforeEach
    fun createObject() {
        token = bearer()
        name = uniqueName("wire")
        val created = send("POST", "/api/objects", OBJECT_REQUEST.plus("name" to name), 201)
        assertThat(WireJson.normalize(created, name)).isEqualTo(WireJson.parse(DEFINITION))
    }

    @Test
    fun `the object definition is the original's`() {
        assertThat(WireJson.normalize(send("GET", "/api/objects/$name", null, 200), name)).isEqualTo(WireJson.parse(DEFINITION))
    }

    @Test
    fun `the object list entry is the original's`() {
        val list = WireJson.normalize(send("GET", "/api/objects", null, 200), name)
        val entry = list.firstOrNull { it.get("name").asString() == "<name>" }
        assertThat(entry).isEqualTo(WireJson.parse(LIST_ENTRY))
    }

    @Test
    fun `a record with both geometries is the original's, on create and on read`() {
        val created = send("POST", "/api/objects/$name/records", RECORD_REQUEST, 201)
        assertThat(WireJson.normalize(created, name)).isEqualTo(WireJson.parse(RECORD))
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(created)!!.groupValues[1]
        assertThat(WireJson.normalize(send("GET", "/api/objects/$name/records/$id", null, 200), name)).isEqualTo(WireJson.parse(RECORD))
    }

    @Test
    fun `a record without geometries lists each one as null`() {
        val created = send("POST", "/api/objects/$name/records", mapOf("attributes" to mapOf("codigo" to "P-2")), 201)
        assertThat(WireJson.normalize(created, name)).isEqualTo(WireJson.parse(RECORD_EMPTY))
    }

    @Test
    fun `features are the original's geojson`() {
        val created = send("POST", "/api/objects/$name/records", RECORD_REQUEST, 201)
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(created)!!.groupValues[1]
        assertThat(WireJson.normalize(send("GET", "/api/gis/objects/$name/features", null, 200), name)).isEqualTo(WireJson.parse(FEATURES))
        assertThat(WireJson.normalize(send("GET", "/api/gis/objects/$name/features?geometry=punto", null, 200), name))
            .isEqualTo(WireJson.parse(FEATURES_PUNTO))
        assertThat(WireJson.normalize(send("GET", "/api/gis/objects/$name/features/$id", null, 200), name)).isEqualTo(WireJson.parse(FEATURE))
    }

    private fun send(
        method: String,
        uri: String,
        body: Any?,
        status: Int
    ): String {
        val spec =
            when (method) {
                "POST" ->
                    client
                        .post()
                        .uri(uri)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .bodyValue(body!!)
                else -> client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, token)
            }
        return spec
            .exchange()
            .expectStatus()
            .isEqualTo(status)
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
    }

    companion object {
        private val RING =
            listOf(listOf(-77.03, -12.05), listOf(-77.02, -12.05), listOf(-77.02, -12.04), listOf(-77.03, -12.04), listOf(-77.03, -12.05))

        private val OBJECT_REQUEST: Map<String, Any> =
            mapOf(
                "label" to "Predio",
                "pluralLabel" to "Predios",
                "description" to "Lote catastral",
                "fields" to
                    listOf(
                        mapOf("name" to "codigo", "label" to "Codigo", "type" to "TEXT", "required" to true),
                        mapOf("name" to "lote", "label" to "Lote", "type" to "GEOMETRY", "geometryType" to "POLYGON", "srid" to 4326),
                        mapOf("name" to "punto", "label" to "Punto", "type" to "GEOMETRY", "geometryType" to "POINT", "srid" to 4326, "dimension" to 3)
                    )
            )

        private val RECORD_REQUEST: Map<String, Any> =
            mapOf(
                "attributes" to mapOf("codigo" to "P-1"),
                "geometries" to
                    mapOf(
                        "lote" to mapOf("type" to "Polygon", "coordinates" to listOf(RING)),
                        "punto" to mapOf("type" to "Point", "coordinates" to listOf(-77.025, -12.045, 150.5))
                    )
            )

        private const val POLYGON = """{"type":"Polygon","coordinates":[[[-77.03,-12.05],[-77.02,-12.05],[-77.02,-12.04],[-77.03,-12.04],[-77.03,-12.05]]]}"""
        private const val POINT = """{"type":"Point","coordinates":[-77.025,-12.045,150.5]}"""

        private const val DEFINITION = """
            {"id":"<uuid>","name":"<name>","label":"Predio","pluralLabel":"Predios","description":"Lote catastral","enabled":true,
             "geometry":{"type":"POLYGON","srid":4326,"dimension":2},
             "fields":[
              {"id":"<uuid>","name":"codigo","label":"Codigo","type":"TEXT","required":true,"unique":false,"defaultValue":null,"description":null,
               "position":0,"enumOptions":null,"relationTarget":null,"geometry":null,"visible":true,"editable":true},
              {"id":"<uuid>","name":"lote","label":"Lote","type":"GEOMETRY","required":false,"unique":false,"defaultValue":null,"description":null,
               "position":1,"enumOptions":null,"relationTarget":null,"geometry":{"type":"POLYGON","srid":4326,"dimension":2},"visible":true,"editable":true},
              {"id":"<uuid>","name":"punto","label":"Punto","type":"GEOMETRY","required":false,"unique":false,"defaultValue":null,"description":null,
               "position":2,"enumOptions":null,"relationTarget":null,"geometry":{"type":"POINT","srid":4326,"dimension":3},"visible":true,"editable":true}
             ]}
        """

        private const val LIST_ENTRY = """
            {"id":"<uuid>","name":"<name>","label":"Predio","pluralLabel":"Predios","description":"Lote catastral","enabled":true,
             "geometry":{"type":"POLYGON","srid":4326,"dimension":2},"createdAt":"<ts>","updatedAt":"<ts>"}
        """

        private const val RECORD = """
            {"id":"<uuid>","createdAt":"<ts>","updatedAt":"<ts>","attributes":{"codigo":"P-1"},
             "geometries":{"lote":$POLYGON,"punto":$POINT},"state":null}
        """

        private const val RECORD_EMPTY = """
            {"id":"<uuid>","createdAt":"<ts>","updatedAt":"<ts>","attributes":{"codigo":"P-2"},
             "geometries":{"lote":null,"punto":null},"state":null}
        """

        private const val FEATURES = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","id":"<uuid>:lote","geometry":$POLYGON,"properties":{"codigo":"P-1","__label":"P-1","__id":"<uuid>"}}
            ]}
        """

        private const val FEATURES_PUNTO = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","id":"<uuid>:punto","geometry":$POINT,"properties":{"codigo":"P-1","__label":"P-1","__id":"<uuid>"}}
            ]}
        """

        private const val FEATURE = """
            {"type":"Feature","id":"<uuid>:lote","geometry":$POLYGON,"properties":{"codigo":"P-1"}}
        """
    }
}
```

(`const val` raw strings may reference other `const val`s in templates: Kotlin folds them at compile time. If the compiler refuses a template in a `const`, drop `const` from those seven `val`s — nothing else changes.)

- [ ] **Step 4: Compile**

Run: `./gradlew :chawpi-integration-tests:compileWireParityItKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:wireParityIt --rerun > $IT/build/wireParityIt-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/wireParityIt/*.xml
```

Expected: exit 0, `tests="5" skipped="0" failures="0" errors="0"`.

If a comparison fails, AssertJ prints both trees. Before reporting, check the differing key against the original's source (read-only): `/Users/jorge/IdeaProjects/sapgis/backend/src/main/kotlin/com/sapgis/metadata/MetadataDtos.kt` (lines 56–137), `…/data/RecordService.kt` (lines 29–37), `…/data/PhysicalTableRecordStore.kt` (`mapRow`, ~line 386), `…/gis/GeoJson.kt`, `…/gis/FeatureController.kt`. If the original's code agrees with the expected literal, it is a chawpi difference: report `BLOCKED: behaviour differs: wire <which body> — <key>: expected <x>, got <y>`. Only if the original's code proves the *literal* wrong (a key, a default, a null the original also sends differently), fix the literal and cite the file and line in your report.

- [ ] **Step 6: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintWireParityItSourceSetFormat
./gradlew :chawpi-integration-tests:ktlintWireParityItSourceSetCheck
git status --short $IT/src/wireParityIt
```

Expected: both pass; files untracked.

### Task 11: Schema parity against the original's final schema (Wave 1, suite `schemaParityIt`)

Ruling T10. Generate the expected catalog once from the original's migrations (never touching the database for good: one transaction, rolled back), check it in, and compare chawpi's `chawpi` schema after the full app's migrations with it on every run.

**Files:**
- Create: `backend/chawpi-integration-tests/src/schemaParityIt/resources/schema-parity/catalog.sql`
- Create: `backend/chawpi-integration-tests/src/schemaParityIt/resources/schema-parity/generate-expected.sh`
- Create (generated in Step 3, then checked in): `backend/chawpi-integration-tests/src/schemaParityIt/resources/schema-parity/legacy-final.catalog`
- Create: `backend/chawpi-integration-tests/src/schemaParityIt/kotlin/chawpi/it/full/SchemaParityTest.kt`

**Interfaces:**
- Consumes: `chawpi.it.full.FullAppIntegrationTest` (Task 1); `ChawpiTestDatabase.SUITE_LOCK` value `chawpi-test-suite` (the generator takes the same lock); the original's migrations `/Users/jorge/IdeaProjects/sapgis/backend/src/main/resources/db/migration/V1__core.sql` … `V14__history_points_at_a_document.sql` (read-only). Suite `schemaParityIt`: every starter, PostGIS.
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
R=$IT/src/schemaParityIt/resources/schema-parity
./gradlew :chawpi-integration-tests:testFixturesJar -q
mkdir -p $R $IT/src/schemaParityIt/kotlin/chawpi/it/full
```

- [ ] **Step 2: The catalog query and its generator**

`$R/catalog.sql`:

```sql
-- one line per catalog fact of schema __SCHEMA__, the schema name printed as META. run it with
-- search_path = public so every name in the schema prints qualified, the same way on both sides.
-- extension-owned objects and flyway's history tables are not the schema's own facts.
-- used by SchemaParityTest and generate-expected.sh: change it, then regenerate the fixture.
WITH s AS (
    SELECT oid FROM pg_namespace WHERE nspname = '__SCHEMA__'
),
ext AS (
    SELECT objid FROM pg_depend WHERE deptype = 'e'
),
rel AS (
    SELECT c.oid, c.relname
    FROM pg_class c
    WHERE c.relnamespace = (SELECT oid FROM s)
      AND c.relkind IN ('r', 'p')
      AND c.relname NOT LIKE 'flyway\_%'
      AND c.oid NOT IN (SELECT objid FROM ext)
),
facts AS (
    SELECT 'table ' || r.relname AS line
    FROM rel r
    UNION ALL
    SELECT format('column %s.%s #%s %s%s%s', r.relname, a.attname,
                  row_number() OVER (PARTITION BY r.oid ORDER BY a.attnum),
                  format_type(a.atttypid, a.atttypmod),
                  CASE WHEN a.attnotnull THEN ' NOT NULL' ELSE '' END,
                  COALESCE(' DEFAULT ' || pg_get_expr(d.adbin, d.adrelid), ''))
    FROM rel r
    JOIN pg_attribute a ON a.attrelid = r.oid AND a.attnum > 0 AND NOT a.attisdropped
    LEFT JOIN pg_attrdef d ON d.adrelid = r.oid AND d.adnum = a.attnum
    UNION ALL
    SELECT format('constraint %s.%s %s', r.relname, con.conname, pg_get_constraintdef(con.oid))
    FROM rel r
    JOIN pg_constraint con ON con.conrelid = r.oid
    UNION ALL
    SELECT format('index %s.%s %s', r.relname, i.relname, pg_get_indexdef(i.oid))
    FROM rel r
    JOIN pg_index x ON x.indrelid = r.oid
    JOIN pg_class i ON i.oid = x.indexrelid
    UNION ALL
    SELECT format('trigger %s.%s %s', r.relname, t.tgname, pg_get_triggerdef(t.oid))
    FROM rel r
    JOIN pg_trigger t ON t.tgrelid = r.oid AND NOT t.tgisinternal
    UNION ALL
    SELECT format('function %s(%s) returns %s body %s', p.proname, pg_get_function_identity_arguments(p.oid), pg_get_function_result(p.oid),
                  md5(replace(p.prosrc, '__SCHEMA__.', 'META.')))
    FROM pg_proc p
    WHERE p.pronamespace = (SELECT oid FROM s) AND p.oid NOT IN (SELECT objid FROM ext)
    UNION ALL
    SELECT format('view %s body %s', c.relname, md5(replace(pg_get_viewdef(c.oid), '__SCHEMA__.', 'META.')))
    FROM pg_class c
    WHERE c.relnamespace = (SELECT oid FROM s) AND c.relkind IN ('v', 'm') AND c.oid NOT IN (SELECT objid FROM ext)
    UNION ALL
    SELECT 'sequence ' || c.relname
    FROM pg_class c
    WHERE c.relnamespace = (SELECT oid FROM s) AND c.relkind = 'S' AND c.oid NOT IN (SELECT objid FROM ext)
)
SELECT replace(line, '__SCHEMA__.', 'META.') AS line
FROM facts
ORDER BY line;
```

`$R/generate-expected.sh`:

```bash
#!/usr/bin/env bash
# rebuilds legacy-final.catalog: the original app's migrations V1..V14 replayed with psql in ONE
# transaction on the postgis test database, catalog.sql run on its schema, then ROLLBACK -- the
# database is left as it was. takes the test-suite lock first, so it never runs under a suite.
# needs the five CHAWPI_TEST_DB_* vars and CHAWPI_TEST_GIS_DB_PORT (the original needs postgis).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
migrations="${SAPGIS_MIGRATIONS:-/Users/jorge/IdeaProjects/sapgis/backend/src/main/resources/db/migration}"
: "${CHAWPI_TEST_DB_HOST:?}" "${CHAWPI_TEST_DB_NAME:?}" "${CHAWPI_TEST_DB_USERNAME:?}" "${CHAWPI_TEST_DB_PASSWORD:?}" "${CHAWPI_TEST_GIS_DB_PORT:?}"
case "$CHAWPI_TEST_DB_NAME" in
  *_test) ;;
  *) echo "refusing: $CHAWPI_TEST_DB_NAME does not end in _test" >&2; exit 1 ;;
esac
out="$here/legacy-final.catalog"
{
  echo '\o /dev/null'
  echo "SELECT pg_advisory_lock(hashtext('chawpi-test-suite'));"
  echo "BEGIN;"
  # flyway ran them with the original's schema first on the search path
  echo "SET search_path TO sapgis, public;"
  for v in $(seq 1 14); do
    cat "$migrations"/V${v}__*.sql
    echo
  done
  echo "SET search_path TO public;"
  echo "\\o $out.tmp"
  sed 's/__SCHEMA__/sapgis/g' "$here/catalog.sql"
  echo '\o'
  echo "ROLLBACK;"
} | PGPASSWORD="$CHAWPI_TEST_DB_PASSWORD" psql -X -q -At -v ON_ERROR_STOP=1 \
  -h "$CHAWPI_TEST_DB_HOST" -p "$CHAWPI_TEST_GIS_DB_PORT" -U "$CHAWPI_TEST_DB_USERNAME" -d "$CHAWPI_TEST_DB_NAME"
mv "$out.tmp" "$out"
wc -l "$out"
```

```bash
source backend/chawpi-integration-tests/it-env.sh
chmod +x $R/generate-expected.sh
```

- [ ] **Step 3: Generate the expected catalog**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5442 || echo "TUNNEL DOWN"
$R/generate-expected.sh
grep -c '^table ' $R/legacy-final.catalog
grep '^table ' $R/legacy-final.catalog | tr '\n' ' '
grep -c 'sapgis\.' $R/legacy-final.catalog
PGPASSWORD=chawpi psql -X -At -h localhost -p 5442 -U chawpi -d chawpi_test -c "SELECT count(*) FROM pg_namespace WHERE nspname = 'sapgis'"
```

(If it prints nothing for a while, a suite holds the lock on 5442: it waits, as designed.) Expected: 19 tables — `audit_log automation_runs automations custom_fields custom_objects document_counters document_types documents field_permissions forms organizations pages permissions relationships roles user_roles users views workflows`; `0` lines still naming `sapgis.`; `0` schemas named `sapgis` left behind (the rollback). Several hundred lines in total. If psql stops on an error, report it verbatim as `BLOCKED: original migrations do not replay: <error>`.

- [ ] **Step 4: The parity test**

`backend/chawpi-integration-tests/src/schemaParityIt/kotlin/chawpi/it/full/SchemaParityTest.kt`:

```kotlin
package chawpi.it.full

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import java.sql.DriverManager

// the metadata schema chawpi's migrations build is the one the original's V1-V14 built, fact by
// fact (catalog.sql). the expected side comes from the original's own migrations
// (generate-expected.sh). a line on one side only is a schema difference.
class SchemaParityTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var environment: Environment

    // differences accepted on purpose: "<catalog line>" to "<reason, ADR or ruling>". empty until the
    // controller approves one.
    private val knownDeviations: Map<String, String> = emptyMap()

    @Test
    fun `the fixture is the original's whole schema`() {
        assertThat(expected().filter { it.startsWith("table ") }).contains(
            "table audit_log",
            "table automation_runs",
            "table automations",
            "table custom_fields",
            "table custom_objects",
            "table document_counters",
            "table document_types",
            "table documents",
            "table field_permissions",
            "table forms",
            "table organizations",
            "table pages",
            "table permissions",
            "table relationships",
            "table roles",
            "table user_roles",
            "table users",
            "table views",
            "table workflows"
        )
    }

    @Test
    fun `the metadata schema is the original's final schema`() {
        // the context is up, so every module migrated
        val actual = catalogOf("chawpi")
        val expected = expected()
        assertThat((expected - actual - knownDeviations.keys).sorted()).describedAs("in the original, not in chawpi").isEmpty()
        assertThat((actual - expected - knownDeviations.keys).sorted()).describedAs("in chawpi, not in the original").isEmpty()
    }

    private fun expected(): Set<String> = resource("legacy-final.catalog").lines().filter { it.isNotBlank() }.toSet()

    private fun resource(name: String): String = javaClass.getResource("/schema-parity/$name")!!.readText()

    private fun catalogOf(schema: String): Set<String> {
        require(Regex("[a-z_][a-z0-9_]*").matches(schema)) { "not a schema name: $schema" }
        val sql = resource("catalog.sql").replace("__SCHEMA__", schema)
        val url =
            "jdbc:postgresql://${environment.getProperty("chawpi.database.host")}:${environment.getProperty("chawpi.database.port")}/" +
                environment.getProperty("chawpi.database.name")
        return DriverManager
            .getConnection(url, environment.getProperty("chawpi.database.username"), environment.getProperty("chawpi.database.password"))
            .use { connection ->
            connection.createStatement().use { statement ->
                // the connection's own search_path would start with "$user" = chawpi and hide the prefix
                statement.execute("SET search_path TO public")
                statement.executeQuery(sql).use { rows ->
                    buildSet { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }
    }
}
```

- [ ] **Step 5: Compile**

Run: `./gradlew :chawpi-integration-tests:compileSchemaParityItKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:schemaParityIt --rerun > $IT/build/schemaParityIt-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/schemaParityIt/*.xml
```

Expected: exit 0, `tests="2" skipped="0" failures="0" errors="0"`. On a failure the message lists the lines only one side has. Do not add them to `knownDeviations` yourself and do not edit any migration: report `BLOCKED: schema differs:` followed by every listed line, grouped by table, so the controller can rule (a real regression goes back to the owning module's V1; a deliberate one gets a `knownDeviations` entry with its reason).

- [ ] **Step 7: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintSchemaParityItSourceSetFormat
./gradlew :chawpi-integration-tests:ktlintSchemaParityItSourceSetCheck
git status --short $IT/src/schemaParityIt
```

Expected: both pass; files untracked (`legacy-final.catalog` included: it is the checked-in fixture).
### Task 12: Slice — core alone (Wave 1, suite `coreOnly`)

The app a user gets from `chawpi-spring-boot-starter` and nothing else, on plain PostgreSQL: all 50 module routes answer 404, only core's migrations ran, records carry no module keys, GEOMETRY is refused, the field table has no geometry columns and no `workflow_state` is published.

**Files:**
- Create: `backend/chawpi-integration-tests/src/coreOnly/kotlin/chawpi/it/slice/core/CoreOnlyApplication.kt`
- Create: `backend/chawpi-integration-tests/src/coreOnly/kotlin/chawpi/it/slice/core/CoreOnlyApiTest.kt`

**Interfaces:**
- Consumes: `chawpi.it.support.SliceSmokeTest` (Task 1: `db`, `installed`, `admin`, `objectName` — a flat object with TEXT `codigo` —, `client`, `uniqueName`, three inherited tests). Suite `coreOnly`: `chawpi-spring-boot-starter` only, plain PostgreSQL (5443).
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
mkdir -p $IT/src/coreOnly/kotlin/chawpi/it/slice/core
```

- [ ] **Step 2: The app**

`backend/chawpi-integration-tests/src/coreOnly/kotlin/chawpi/it/slice/core/CoreOnlyApplication.kt`:

```kotlin
package chawpi.it.slice.core

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// chawpi-spring-boot-starter and nothing else on the classpath: the smallest app there is
@SpringBootConfiguration
@EnableAutoConfiguration
class CoreOnlyApplication
```

- [ ] **Step 3: The test**

`backend/chawpi-integration-tests/src/coreOnly/kotlin/chawpi/it/slice/core/CoreOnlyApiTest.kt`:

```kotlin
package chawpi.it.slice.core

import chawpi.it.support.SliceSmokeTest
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import tools.jackson.databind.json.JsonMapper

// core alone, from its starter, on plain postgres: every module is optional
// two checks repeat chawpi-core's CoreOnlyApiTest on purpose: here core comes from its starter, with nothing else on the classpath
class CoreOnlyApiTest : SliceSmokeTest() {
    override val installed = emptySet<String>()

    private val json = JsonMapper.builder().build()

    @Test
    fun `a record carries attributes and state, and nothing a module would add`() {
        val raw =
            client
                .post()
                .uri("/api/objects/$objectName/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "C-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        assertThat(json.readTree(raw).propertyNames().asSequence().toList())
            .containsExactlyInAnyOrder("id", "createdAt", "updatedAt", "attributes", "state")
    }

    @Test
    fun `a GEOMETRY field is refused without the gis module`() {
        client
            .post()
            .uri("/api/metadata/objects/$objectName/fields")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "lote", "type" to "GEOMETRY", "geometryType" to "POINT"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("type")
    }

    @Test
    fun `no module column exists, no geometry attributes, no workflow state`() {
        val columns =
            runBlocking {
                db
                    .sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'chawpi' AND table_name = 'custom_fields'")
                    .map { row, _ -> row.get("column_name", String::class.java)!! }
                    .all()
                    .collectList()
                    .awaitFirstOrNull()
                    .orEmpty()
            }
        assertThat(columns).contains("name", "type").doesNotContain("geometry_type", "srid", "dimension")

        client
            .get()
            .uri("/api/metadata/system-fields")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[?(@.name == 'workflow_state')]")
            .doesNotExist()
    }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :chawpi-integration-tests:compileCoreOnlyKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:coreOnly --rerun > $IT/build/coreOnly-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/coreOnly/*.xml
```

Expected: exit 0, `tests="6" skipped="0" failures="0" errors="0"` (3 inherited + 3). A route that answers something other than 404 is listed by the first two tests as `<route> -> <status>`: report it as `BLOCKED: behaviour differs: matrix — <lines>` (do not edit `ModuleRoutes`). If `$[?(@.name == 'workflow_state')]` `doesNotExist` fails on an empty-array match, replace that assertion with `.jsonPath("$[*].name").value<List<String>> { assertThat(it).doesNotContain("workflow_state") }` — same meaning.

- [ ] **Step 6: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintCoreOnlySourceSetFormat
./gradlew :chawpi-integration-tests:ktlintCoreOnlySourceSetCheck
git status --short $IT/src/coreOnly
```

Expected: both pass; files untracked.

### Task 13: Slice — views alone (Wave 1, suite `viewsOnly`)

Core + chawpi-views, plain PostgreSQL: a generated default view, a stored view round-trip, the metadata route views owns, and every other module absent.

**Files:**
- Create: `backend/chawpi-integration-tests/src/viewsOnly/kotlin/chawpi/it/slice/views/ViewsOnlyApplication.kt`
- Create: `backend/chawpi-integration-tests/src/viewsOnly/kotlin/chawpi/it/slice/views/ViewsOnlyApiTest.kt`

**Interfaces:**
- Consumes: `chawpi.it.support.SliceSmokeTest` (Task 1: `db`, `installed`, `admin`, `objectName` — a flat object with TEXT `codigo` —, `client`, `uniqueName`, three inherited tests). Suite `viewsOnly`: `chawpi-spring-boot-starter-views`, plain PostgreSQL.
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
mkdir -p $IT/src/viewsOnly/kotlin/chawpi/it/slice/views
```

- [ ] **Step 2: The app**

`backend/chawpi-integration-tests/src/viewsOnly/kotlin/chawpi/it/slice/views/ViewsOnlyApplication.kt`:

```kotlin
package chawpi.it.slice.views

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// core + views, nothing else on the classpath
@SpringBootConfiguration
@EnableAutoConfiguration
class ViewsOnlyApplication
```

- [ ] **Step 3: The test**

`backend/chawpi-integration-tests/src/viewsOnly/kotlin/chawpi/it/slice/views/ViewsOnlyApiTest.kt`:

```kotlin
package chawpi.it.slice.views

import chawpi.it.support.SliceSmokeTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

class ViewsOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("views")

    @Test
    fun `an object gets a generated default view, and a stored one round-trips`() {
        client
            .get()
            .uri("/api/objects/$objectName/views")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].generated")
            .isEqualTo(true)
            .jsonPath("$[0].name")
            .isEqualTo("default")

        val name = uniqueName("vista")
        client
            .post()
            .uri("/api/objects/$objectName/views")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Todos",
                    "isDefault" to false,
                    "definition" to mapOf("columns" to listOf("codigo"), "filters" to emptyMap<String, String>(), "pageSize" to 25)
                )
            ).exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.generated")
            .isEqualTo(false)

        client
            .get()
            .uri("/api/objects/$objectName/views/$name")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.name")
            .isEqualTo(name)
    }

    @Test
    fun `views owns its metadata route`() {
        client
            .get()
            .uri("/api/metadata/objects/$objectName/views")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
    }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :chawpi-integration-tests:compileViewsOnlyKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:viewsOnly --rerun > $IT/build/viewsOnly-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/viewsOnly/*.xml
```

Expected: exit 0, `tests="5" skipped="0" failures="0" errors="0"`. A failure: report `BLOCKED: behaviour differs: …` (matrix misses come as `<route> -> <status>` lines).

- [ ] **Step 6: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintViewsOnlySourceSetFormat
./gradlew :chawpi-integration-tests:ktlintViewsOnlySourceSetCheck
git status --short $IT/src/viewsOnly
```

Expected: both pass; files untracked.

### Task 14: Slice — forms alone (Wave 1, suite `formsOnly`)

Core + chawpi-forms, plain PostgreSQL: a generated default form, a stored form round-trip, the metadata route forms owns, and every other module (pages included) absent.

**Files:**
- Create: `backend/chawpi-integration-tests/src/formsOnly/kotlin/chawpi/it/slice/forms/FormsOnlyApplication.kt`
- Create: `backend/chawpi-integration-tests/src/formsOnly/kotlin/chawpi/it/slice/forms/FormsOnlyApiTest.kt`

**Interfaces:**
- Consumes: `chawpi.it.support.SliceSmokeTest` (Task 1: `db`, `installed`, `admin`, `objectName` — a flat object with TEXT `codigo` —, `client`, `uniqueName`, three inherited tests). Suite `formsOnly`: `chawpi-spring-boot-starter-forms`, plain PostgreSQL.
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
mkdir -p $IT/src/formsOnly/kotlin/chawpi/it/slice/forms
```

- [ ] **Step 2: The app**

`backend/chawpi-integration-tests/src/formsOnly/kotlin/chawpi/it/slice/forms/FormsOnlyApplication.kt`:

```kotlin
package chawpi.it.slice.forms

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// core + forms, nothing else on the classpath
@SpringBootConfiguration
@EnableAutoConfiguration
class FormsOnlyApplication
```

- [ ] **Step 3: The test**

`backend/chawpi-integration-tests/src/formsOnly/kotlin/chawpi/it/slice/forms/FormsOnlyApiTest.kt`:

```kotlin
package chawpi.it.slice.forms

import chawpi.it.support.SliceSmokeTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

class FormsOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("forms")

    @Test
    fun `an object gets a generated default form, and a stored one round-trips`() {
        client
            .get()
            .uri("/api/objects/$objectName/forms")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].generated")
            .isEqualTo(true)
            .jsonPath("$[0].name")
            .isEqualTo("default")

        val name = uniqueName("alta")
        client
            .post()
            .uri("/api/objects/$objectName/forms")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Alta",
                    "definition" to mapOf("sections" to listOf(mapOf("title" to "Datos", "fields" to listOf("codigo"))))
                )
            ).exchange()
            .expectStatus()
            .isCreated

        client
            .get()
            .uri("/api/objects/$objectName/forms/$name")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.name")
            .isEqualTo(name)
    }

    @Test
    fun `forms owns its metadata route`() {
        client
            .get()
            .uri("/api/metadata/objects/$objectName/forms")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
    }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :chawpi-integration-tests:compileFormsOnlyKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:formsOnly --rerun > $IT/build/formsOnly-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/formsOnly/*.xml
```

Expected: exit 0, `tests="5" skipped="0" failures="0" errors="0"`. A failure: report `BLOCKED: behaviour differs: …`.

- [ ] **Step 6: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintFormsOnlySourceSetFormat
./gradlew :chawpi-integration-tests:ktlintFormsOnlySourceSetCheck
git status --short $IT/src/formsOnly
```

Expected: both pass; files untracked.

### Task 15: Slice — pages alone (with forms) (Wave 1, suite `pagesOnly`)

Core + chawpi-pages (which brings chawpi-forms), plain PostgreSQL: the generated record page, a stored page round-trip, and the neighbours' absence — a MAP component (gis) and a WORKFLOW component (workflow) are refused like any unknown component.

**Files:**
- Create: `backend/chawpi-integration-tests/src/pagesOnly/kotlin/chawpi/it/slice/pages/PagesOnlyApplication.kt`
- Create: `backend/chawpi-integration-tests/src/pagesOnly/kotlin/chawpi/it/slice/pages/PagesOnlyApiTest.kt`

**Interfaces:**
- Consumes: `chawpi.it.support.SliceSmokeTest` (Task 1: `db`, `installed`, `admin`, `objectName` — a flat object with TEXT `codigo` —, `client`, `uniqueName`, three inherited tests); pages' `Unknown component '<type>'` validation (P2 M10). Suite `pagesOnly`: `chawpi-spring-boot-starter-pages`, plain PostgreSQL.
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
mkdir -p $IT/src/pagesOnly/kotlin/chawpi/it/slice/pages
```

- [ ] **Step 2: The app**

`backend/chawpi-integration-tests/src/pagesOnly/kotlin/chawpi/it/slice/pages/PagesOnlyApplication.kt`:

```kotlin
package chawpi.it.slice.pages

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// core + pages (+ forms, its one hard dependency), nothing else on the classpath
@SpringBootConfiguration
@EnableAutoConfiguration
class PagesOnlyApplication
```

- [ ] **Step 3: The test**

`backend/chawpi-integration-tests/src/pagesOnly/kotlin/chawpi/it/slice/pages/PagesOnlyApiTest.kt`:

```kotlin
package chawpi.it.slice.pages

import chawpi.it.support.SliceSmokeTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient

class PagesOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("pages", "forms")

    @Test
    fun `the generated record page is a one-region page`() {
        resolve()
            .jsonPath("$.generated")
            .isEqualTo(true)
            .jsonPath("$.template.name")
            .isEqualTo("one-region")
    }

    @Test
    fun `a stored page replaces the generated one`() {
        createPage(mapOf("type" to "TEXT", "column" to 1, "content" to "hola"))
            .expectStatus()
            .isCreated
        resolve()
            .jsonPath("$.generated")
            .isEqualTo(false)
    }

    // the MAP and WORKFLOW components come from gis and workflow, which this app has not got
    @Test
    fun `a MAP or WORKFLOW component is refused without the module that draws it`() {
        listOf(
            mapOf("type" to "MAP", "column" to 1, "title" to "Ubicacion"),
            mapOf("type" to "WORKFLOW", "column" to 1)
        ).forEach { component ->
            createPage(component)
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.detail")
                .value<String> { assertThat(it).contains(component.getValue("type").toString()) }
        }
    }

    private fun resolve(): WebTestClient.BodyContentSpec =
        client
            .get()
            .uri("/api/objects/$objectName/pages/record-detail")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    private fun createPage(component: Map<String, Any>): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/pages")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "objectName" to objectName,
                    "name" to uniqueName("page").take(30),
                    "label" to "Detalle",
                    "kind" to "RECORD_DETAIL",
                    "template" to "one-region",
                    "definition" to
                        mapOf(
                            "page" to
                                mapOf(
                                    "type" to "PAGE",
                                    "children" to
                                        listOf(mapOf("type" to "REGION", "region" to "MAIN", "layout" to "single-column", "children" to listOf(component)))
                                )
                        )
                )
            ).exchange()
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :chawpi-integration-tests:compilePagesOnlyKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:pagesOnly --rerun > $IT/build/pagesOnly-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/pagesOnly/*.xml
```

Expected: exit 0, `tests="6" skipped="0" failures="0" errors="0"`. A failure: report `BLOCKED: behaviour differs: …`.

- [ ] **Step 6: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintPagesOnlySourceSetFormat
./gradlew :chawpi-integration-tests:ktlintPagesOnlySourceSetCheck
git status --short $IT/src/pagesOnly
```

Expected: both pass; files untracked.

### Task 16: Slice — workflow alone (Wave 1, suite `workflowOnly`)

Core + chawpi-workflow, plain PostgreSQL: a workflow attached, records start in the initial state, transitions listed and applied, `workflow_state` published — and pages absent (no WORKFLOW panel to draw, its routes 404).

**Files:**
- Create: `backend/chawpi-integration-tests/src/workflowOnly/kotlin/chawpi/it/slice/workflow/WorkflowOnlyApplication.kt`
- Create: `backend/chawpi-integration-tests/src/workflowOnly/kotlin/chawpi/it/slice/workflow/WorkflowOnlyApiTest.kt`

**Interfaces:**
- Consumes: `chawpi.it.support.SliceSmokeTest` (Task 1: `db`, `installed`, `admin`, `objectName` — a flat object with TEXT `codigo` —, `client`, `uniqueName`, three inherited tests). Suite `workflowOnly`: `chawpi-spring-boot-starter-workflow`, plain PostgreSQL.
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
mkdir -p $IT/src/workflowOnly/kotlin/chawpi/it/slice/workflow
```

- [ ] **Step 2: The app**

`backend/chawpi-integration-tests/src/workflowOnly/kotlin/chawpi/it/slice/workflow/WorkflowOnlyApplication.kt`:

```kotlin
package chawpi.it.slice.workflow

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// core + workflow, nothing else on the classpath
@SpringBootConfiguration
@EnableAutoConfiguration
class WorkflowOnlyApplication
```

- [ ] **Step 3: The test**

`backend/chawpi-integration-tests/src/workflowOnly/kotlin/chawpi/it/slice/workflow/WorkflowOnlyApiTest.kt`:

```kotlin
package chawpi.it.slice.workflow

import chawpi.it.support.SliceSmokeTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

class WorkflowOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("workflow")

    @Test
    fun `a record walks its workflow without pages installed`() {
        client
            .put()
            .uri("/api/objects/$objectName/workflow")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to uniqueName("wf").take(30),
                    "label" to "Aprobacion",
                    "enabled" to true,
                    "definition" to
                        mapOf(
                            "states" to
                                listOf(
                                    mapOf("name" to "draft", "label" to "Borrador", "type" to "INITIAL"),
                                    mapOf("name" to "approved", "label" to "Aprobado", "type" to "FINAL")
                                ),
                            "transitions" to
                                listOf(
                                    mapOf("name" to "approve", "label" to "Aprobar", "from" to "draft", "to" to "approved"),
                                    mapOf("name" to "reject", "label" to "Rechazar", "from" to "draft", "to" to "draft")
                                )
                        )
                )
            ).exchange()
            .expectStatus()
            .isOk

        val created =
            client
                .post()
                .uri("/api/objects/$objectName/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "W-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val id = created.substringAfter("\"id\":\"").substringBefore("\"")

        client
            .get()
            .uri("/api/objects/$objectName/records/$id/transitions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(2)
            .jsonPath("$[0].name")
            .isEqualTo("approve")

        client
            .post()
            .uri("/api/objects/$objectName/records/$id/transitions/approve")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.id")
            .isEqualTo(id)
            .jsonPath("$.state")
            .isEqualTo("approved")
    }

    @Test
    fun `workflow publishes the column it keeps`() {
        client
            .get()
            .uri("/api/metadata/system-fields")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[?(@.name == 'workflow_state')].scope")
            .isEqualTo("WORKFLOW")
    }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :chawpi-integration-tests:compileWorkflowOnlyKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:workflowOnly --rerun > $IT/build/workflowOnly-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/workflowOnly/*.xml
```

Expected: exit 0, `tests="5" skipped="0" failures="0" errors="0"`. A failure: report `BLOCKED: behaviour differs: …`.

- [ ] **Step 6: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintWorkflowOnlySourceSetFormat
./gradlew :chawpi-integration-tests:ktlintWorkflowOnlySourceSetCheck
git status --short $IT/src/workflowOnly
```

Expected: both pass; files untracked.

### Task 17: Slice — automation alone (Wave 1, suite `automationOnly`)

Core + chawpi-automation, plain PostgreSQL: a rule runs when the test drains the queue by hand, and without chawpi-documents a GENERATE_DOCUMENT action is refused when saved (`NoDocumentIssuer`, P2 Review Focus 4), never a boot failure.

**Files:**
- Create: `backend/chawpi-integration-tests/src/automationOnly/kotlin/chawpi/it/slice/automation/AutomationOnlyApplication.kt`
- Create: `backend/chawpi-integration-tests/src/automationOnly/kotlin/chawpi/it/slice/automation/AutomationOnlyApiTest.kt`

**Interfaces:**
- Consumes: `chawpi.it.support.SliceSmokeTest` (Task 1: `db`, `installed`, `admin`, `objectName`, `client`, `uniqueName`, three inherited tests); `chawpi.automation.AutomationRunner.drainOnce(batch: Int): Int` (suspend). Suite `automationOnly`: `chawpi-spring-boot-starter-automation`, plain PostgreSQL.
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
mkdir -p $IT/src/automationOnly/kotlin/chawpi/it/slice/automation
```

- [ ] **Step 2: The app**

`backend/chawpi-integration-tests/src/automationOnly/kotlin/chawpi/it/slice/automation/AutomationOnlyApplication.kt`:

```kotlin
package chawpi.it.slice.automation

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// core + automation, nothing else on the classpath
@SpringBootConfiguration
@EnableAutoConfiguration
class AutomationOnlyApplication
```

- [ ] **Step 3: The test**

`backend/chawpi-integration-tests/src/automationOnly/kotlin/chawpi/it/slice/automation/AutomationOnlyApiTest.kt`:

```kotlin
package chawpi.it.slice.automation

import chawpi.automation.AutomationRunner
import chawpi.it.support.SliceSmokeTest
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.test.context.TestPropertySource

// no background drain: the test drives the runner by hand
@TestPropertySource(properties = ["chawpi.automation.poll-interval=0s"])
class AutomationOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("automation")

    @Autowired
    private lateinit var runner: AutomationRunner

    @Test
    fun `a rule runs on record creation without documents installed`() {
        val name = uniqueName("revision")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Revision",
                    "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"), mapOf("name" to "revisado", "type" to "TEXT"))
                )
            ).exchange()
            .expectStatus()
            .isCreated
        client
            .post()
            .uri("/api/objects/$name/automations")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to uniqueName("auto"),
                    "label" to "Automatizacion",
                    "definition" to
                        mapOf(
                            "trigger" to mapOf("type" to "RECORD_CREATED"),
                            "actions" to listOf(mapOf("type" to "UPDATE_FIELD", "field" to "revisado", "value" to "si"))
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
        val created =
            client
                .post()
                .uri("/api/objects/$name/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "A-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val id = created.substringAfter("\"id\":\"").substringBefore("\"")

        assertThat(runBlocking { runner.drainOnce(50) }).isGreaterThanOrEqualTo(1)

        client
            .get()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.attributes.revisado")
            .isEqualTo("si")
    }

    @Test
    fun `GENERATE_DOCUMENT is refused when saved, since no document type can exist`() {
        client
            .post()
            .uri("/api/objects/$objectName/automations")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to uniqueName("auto"),
                    "label" to "Automatizacion",
                    "definition" to
                        mapOf(
                            "trigger" to mapOf("type" to "RECORD_CREATED"),
                            "actions" to listOf(mapOf("type" to "GENERATE_DOCUMENT", "documentType" to "fantasma"))
                        )
                )
            ).exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .value<String> { assertThat(it).contains("Unknown document type 'fantasma'") }
    }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :chawpi-integration-tests:compileAutomationOnlyKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:automationOnly --rerun > $IT/build/automationOnly-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/automationOnly/*.xml
```

Expected: exit 0, `tests="5" skipped="0" failures="0" errors="0"`. A failure: report `BLOCKED: behaviour differs: …`.

- [ ] **Step 6: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintAutomationOnlySourceSetFormat
./gradlew :chawpi-integration-tests:ktlintAutomationOnlySourceSetCheck
git status --short $IT/src/automationOnly
```

Expected: both pass; files untracked.

### Task 18: Slice — documents alone (Wave 1, suite `documentsOnly`)

Core + chawpi-documents, plain PostgreSQL: a document type, an issued, numbered document and the record's document list work without automation; automation's routes are absent.

**Files:**
- Create: `backend/chawpi-integration-tests/src/documentsOnly/kotlin/chawpi/it/slice/documents/DocumentsOnlyApplication.kt`
- Create: `backend/chawpi-integration-tests/src/documentsOnly/kotlin/chawpi/it/slice/documents/DocumentsOnlyApiTest.kt`

**Interfaces:**
- Consumes: `chawpi.it.support.SliceSmokeTest` (Task 1: `db`, `installed`, `admin`, `objectName` — a flat object with TEXT `codigo` —, `client`, `uniqueName`, three inherited tests). Suite `documentsOnly`: `chawpi-spring-boot-starter-documents`, plain PostgreSQL.
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
mkdir -p $IT/src/documentsOnly/kotlin/chawpi/it/slice/documents
```

- [ ] **Step 2: The app**

`backend/chawpi-integration-tests/src/documentsOnly/kotlin/chawpi/it/slice/documents/DocumentsOnlyApplication.kt`:

```kotlin
package chawpi.it.slice.documents

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// core + documents, nothing else on the classpath
@SpringBootConfiguration
@EnableAutoConfiguration
class DocumentsOnlyApplication
```

- [ ] **Step 3: The test**

`backend/chawpi-integration-tests/src/documentsOnly/kotlin/chawpi/it/slice/documents/DocumentsOnlyApiTest.kt`:

```kotlin
package chawpi.it.slice.documents

import chawpi.it.support.SliceSmokeTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import java.time.Year
import java.time.ZoneOffset

class DocumentsOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("documents")

    @Test
    fun `a document is issued and numbered without automation installed`() {
        val prefix = uniqueName("s").uppercase().take(10)
        client
            .post()
            .uri("/api/objects/$objectName/document-types")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to "oficio",
                    "prefix" to prefix,
                    "label" to "Oficio",
                    "template" to
                        mapOf(
                            "type" to "doc",
                            "content" to
                                listOf(
                                    mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to "Original"))),
                                    mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "objectField", "attrs" to mapOf("field" to "codigo"))))
                                )
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
        val created =
            client
                .post()
                .uri("/api/objects/$objectName/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "D-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val id = created.substringAfter("\"id\":\"").substringBefore("\"")

        client
            .post()
            .uri("/api/objects/$objectName/records/$id/documents/oficio")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.number")
            .isEqualTo("$prefix-${Year.now(ZoneOffset.UTC).value}-001")
            .jsonPath("$.status")
            .isEqualTo("VALID")

        client
            .get()
            .uri("/api/objects/$objectName/records/$id/documents")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
    }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :chawpi-integration-tests:compileDocumentsOnlyKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:documentsOnly --rerun > $IT/build/documentsOnly-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/documentsOnly/*.xml
```

Expected: exit 0, `tests="4" skipped="0" failures="0" errors="0"`. A failure: report `BLOCKED: behaviour differs: …`.

- [ ] **Step 6: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintDocumentsOnlySourceSetFormat
./gradlew :chawpi-integration-tests:ktlintDocumentsOnlySourceSetCheck
git status --short $IT/src/documentsOnly
```

Expected: both pass; files untracked.

### Task 19: Slice — gis alone, the PostGIS round-trip (Wave 1, suite `gisOnly`)

Core + chawpi-gis on PostGIS, no pages (P2 Task 7 minor 4: a PostGIS IT round-trip). A polygon sent in WGS84 is stored in the field's projected SRID (32718, UTM 18S — Lima), read back as WGS84 GeoJSON, served as a feature, filtered by bbox; layers list with GeoServer disabled; the MAP component's module (pages) is absent.

**Files:**
- Create: `backend/chawpi-integration-tests/src/gisOnly/kotlin/chawpi/it/slice/gis/GisOnlyApplication.kt`
- Create: `backend/chawpi-integration-tests/src/gisOnly/kotlin/chawpi/it/slice/gis/GisOnlyApiTest.kt`

**Interfaces:**
- Consumes: `chawpi.it.support.SliceSmokeTest` (Task 1: `db`, `installed`, `admin`, `objectName`, `client`, `uniqueName`, three inherited tests). Suite `gisOnly`: `chawpi-spring-boot-starter-gis`, PostGIS (5442).
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
mkdir -p $IT/src/gisOnly/kotlin/chawpi/it/slice/gis
```

- [ ] **Step 2: The app**

`backend/chawpi-integration-tests/src/gisOnly/kotlin/chawpi/it/slice/gis/GisOnlyApplication.kt`:

```kotlin
package chawpi.it.slice.gis

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// core + gis, nothing else on the classpath (no pages: no MAP component)
@SpringBootConfiguration
@EnableAutoConfiguration
class GisOnlyApplication
```

- [ ] **Step 3: The test**

`backend/chawpi-integration-tests/src/gisOnly/kotlin/chawpi/it/slice/gis/GisOnlyApiTest.kt`:

```kotlin
package chawpi.it.slice.gis

import chawpi.it.support.SliceSmokeTest
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.json.JsonMapper

// geoserver stays out of the suite
@TestPropertySource(properties = ["chawpi.gis.geoserver.enabled=false", "chawpi.gis.geoserver.url=http://geoserver.invalid:8081/geoserver"])
class GisOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("gis")

    private val json = JsonMapper.builder().build()

    // about 1.1 km by 1.1 km in central lima
    private val ring =
        listOf(listOf(-77.03, -12.05), listOf(-77.02, -12.05), listOf(-77.02, -12.04), listOf(-77.03, -12.04), listOf(-77.03, -12.05))

    @Test
    fun `a polygon goes into postgis in the field's srid and comes back as wgs84`() {
        val name = createLoteObject()
        val raw = createRecord(name)
        val record = json.readTree(raw)
        val back = record.get("geometries").get("lote")
        assertThat(back.get("type").asString()).isEqualTo("Polygon")
        val corner = back.get("coordinates").get(0).get(0)
        assertThat(corner.get(0).asDouble()).isCloseTo(-77.03, within(1e-6))
        assertThat(corner.get(1).asDouble()).isCloseTo(-12.05, within(1e-6))

        val table = physicalTable(name)
        assertThat(scalar("SELECT ST_SRID(lote) FROM app_data.\"$table\" LIMIT 1").toInt()).isEqualTo(32718)
        // square metres, since 32718 is metric: ~1088 m x ~1106 m
        assertThat(scalar("SELECT ST_Area(lote) FROM app_data.\"$table\" LIMIT 1").toDouble()).isBetween(1.15e6, 1.25e6)
        assertThat(scalar("SELECT count(*) FROM pg_indexes WHERE schemaname = 'app_data' AND tablename = '$table' AND indexdef LIKE '%USING gist%'").toInt())
            .isEqualTo(1)
    }

    @Test
    fun `the record is a feature, and bbox filters it`() {
        val name = createLoteObject()
        val id = json.readTree(createRecord(name)).get("id").asString()

        client
            .get()
            .uri("/api/gis/objects/$name/features")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.type")
            .isEqualTo("FeatureCollection")
            .jsonPath("$.features.length()")
            .isEqualTo(1)
            .jsonPath("$.features[0].id")
            .isEqualTo("$id:lote")
            .jsonPath("$.features[0].properties.__label")
            .isEqualTo("L-1")

        listOf("-78,-13,-76,-11" to 1, "-70,-10,-69,-9" to 0).forEach { (bbox, count) ->
            client
                .get()
                .uri("/api/gis/objects/$name/features?bbox=$bbox")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.features.length()")
                .isEqualTo(count)
        }
    }

    @Test
    fun `layers list without geoserver`() {
        client
            .get()
            .uri("/api/gis/layers")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun createLoteObject(): String {
        val name = uniqueName("lote")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Lote",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "lote", "type" to "GEOMETRY", "geometryType" to "POLYGON", "srid" to 32718)
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun createRecord(name: String): String =
        client
            .post()
            .uri("/api/objects/$name/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "L-1"), "geometries" to mapOf("lote" to mapOf("type" to "Polygon", "coordinates" to listOf(ring)))))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

    private fun physicalTable(name: String): String =
        runBlocking {
            db
                .sql("SELECT physical_table FROM chawpi.custom_objects WHERE name = :name")
                .bind("name", name)
                .map { row, _ -> row.get("physical_table", String::class.java)!! }
                .one()
                .awaitFirstOrNull()!!
        }

    private fun scalar(sql: String): Number =
        runBlocking {
            db
                .sql(sql)
                .map { row, _ -> row.get(0) as Number }
                .one()
                .awaitFirstOrNull()!!
        }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :chawpi-integration-tests:compileGisOnlyKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:gisOnly --rerun > $IT/build/gisOnly-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/gisOnly/*.xml
```

Expected: exit 0, `tests="6" skipped="0" failures="0" errors="0"`. A failure: report `BLOCKED: behaviour differs: …` with the assertion (an area outside the range, a SRID other than 32718 or a missing GIST index are real regressions in chawpi-gis).

- [ ] **Step 6: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintGisOnlySourceSetFormat
./gradlew :chawpi-integration-tests:ktlintGisOnlySourceSetCheck
git status --short $IT/src/gisOnly
```

Expected: both pass; files untracked.

### Task 20: Slice — agent alone (Wave 1, suite `agentOnly`)

Core + chawpi-agent (its starter brings the Anthropic provider), plain PostgreSQL, no key: the app boots (`EmbabelGate` keeps Embabel out), the status says the assistant is off, and without chawpi-workflow the `available_transitions` tool answers an empty list instead of failing (`NoRecordTransitions`).

**Files:**
- Create: `backend/chawpi-integration-tests/src/agentOnly/kotlin/chawpi/it/slice/agent/AgentOnlyApplication.kt`
- Create: `backend/chawpi-integration-tests/src/agentOnly/kotlin/chawpi/it/slice/agent/AgentOnlyApiTest.kt`

**Interfaces:**
- Consumes: `chawpi.it.support.SliceSmokeTest` (Task 1: `db`, `installed`, `admin`, `objectName`, `client`, `uniqueName`, three inherited tests); `chawpi.agent.AgentTools` (`suspend fun invoke(name: String, input: Map<String, Any?>): AgentToolResult`), `chawpi.agent.AgentToolResult(json: String, summary: String, error: Boolean)`, `chawpi.agent.AgentToolCatalog.AVAILABLE_TRANSITIONS`; core's `ReactiveJwtDecoder` bean. Suite `agentOnly`: `chawpi-spring-boot-starter-agent`, plain PostgreSQL, `ANTHROPIC_API_KEY=""`.
- Produces: nothing other tasks use.

- [ ] **Step 1: Shell, warm build**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:testFixturesJar -q
mkdir -p $IT/src/agentOnly/kotlin/chawpi/it/slice/agent
```

- [ ] **Step 2: The app**

`backend/chawpi-integration-tests/src/agentOnly/kotlin/chawpi/it/slice/agent/AgentOnlyApplication.kt`:

```kotlin
package chawpi.it.slice.agent

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// core + agent, nothing else on the classpath (no workflow: no transitions to offer)
@SpringBootConfiguration
@EnableAutoConfiguration
class AgentOnlyApplication
```

- [ ] **Step 3: The test**

`backend/chawpi-integration-tests/src/agentOnly/kotlin/chawpi/it/slice/agent/AgentOnlyApiTest.kt`:

```kotlin
package chawpi.it.slice.agent

import chawpi.agent.AgentToolCatalog
import chawpi.agent.AgentTools
import chawpi.it.support.SliceSmokeTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

class AgentOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("agent")

    @Autowired
    private lateinit var tools: AgentTools

    @Autowired
    private lateinit var jwtDecoder: ReactiveJwtDecoder

    @Test
    fun `with no key the app boots and the assistant says it is off`() {
        client
            .get()
            .uri("/api/agent/status")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.enabled")
            .isEqualTo(false)
            .jsonPath("$.model")
            .isEqualTo("claude-haiku-4-5")
    }

    @Test
    fun `without workflow a record offers no transitions, and that is not an error`() {
        val created =
            client
                .post()
                .uri("/api/objects/$objectName/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "G-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val id = created.substringAfter("\"id\":\"").substringBefore("\"")

        // the real service's security hop; no-op without workflow, needed once a workflow-backed port answers
        val jwt = jwtDecoder.decode(admin.removePrefix("Bearer ")).block()!!
        val context = ReactiveSecurityContextHolder.withAuthentication(JwtAuthenticationToken(jwt))
        val result =
            runBlocking(context.asCoroutineContext()) {
                withContext(Dispatchers.IO) { tools.invoke(AgentToolCatalog.AVAILABLE_TRANSITIONS, mapOf("object" to objectName, "id" to id)) }
            }
        assertThat(result.error).isFalse()
        assertThat(result.json).contains("\"transitions\":[]")
    }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :chawpi-integration-tests:compileAgentOnlyKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the suite (background; it may queue on the suite lock)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p $IT/build
./gradlew :chawpi-integration-tests:agentOnly --rerun > $IT/build/agentOnly-run.log 2>&1; echo "exit $?"
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $IT/build/test-results/agentOnly/*.xml
```

Expected: exit 0, `tests="5" skipped="0" failures="0" errors="0"`. A failure: report `BLOCKED: behaviour differs: …`.

- [ ] **Step 6: Format and check, leave uncommitted**

```bash
source backend/chawpi-integration-tests/it-env.sh
./gradlew :chawpi-integration-tests:ktlintAgentOnlySourceSetFormat
./gradlew :chawpi-integration-tests:ktlintAgentOnlySourceSetCheck
git status --short $IT/src/agentOnly
```

Expected: both pass; files untracked.

### Task 21: Whole verification — build, every IT, counts (Wave 2)

Runs everything once, the way a developer or CI would, and closes the phase.

**Files:**
- Modify: none (read-only verification). If a check fails, report which task owns the fix; do not fix here.

**Interfaces:**
- Consumes: every suite of Tasks 1–20; `:chawpi-core:integrationTest` (P1/P2).
- Produces: the phase's evidence (counts per suite) for the ledger.

- [ ] **Step 1: The whole build (no database)**

```bash
cd /Users/jorge/IdeaProjects/chawpi
./gradlew build
```

Expected: `BUILD SUCCESSFUL` — ktlint on every source set, every unit test (265+ from P2, the 6 of `:chawpi-integration-tests:test`), and every one of the 20 suites compiled.

- [ ] **Step 2: Every IT (background; ~20 suites, one at a time per database)**

```bash
source backend/chawpi-integration-tests/it-env.sh
nc -z localhost 5443 && nc -z localhost 5442 || echo "TUNNEL DOWN"
mkdir -p backend/chawpi-integration-tests/build
./gradlew integrationTest --rerun-tasks --continue > backend/chawpi-integration-tests/build/all-run.log 2>&1; echo "exit $?"
```

Expected: exit 0. (`--rerun-tasks` forces every test task; `--continue` reports every failing suite, not just the first.)

- [ ] **Step 3: Counts per suite**

```bash
cd /Users/jorge/IdeaProjects/chawpi/backend
for d in chawpi-core/build/test-results/integrationTest chawpi-integration-tests/build/test-results/*/; do
  printf "%-60s " "$d"
  grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $d/*.xml 2>/dev/null | awk -F'"' '{t+=$2; s+=$4; f+=$6+$8} END {print t" tests, "s" skipped, "f" failed"}'
done
```

Expected (0 failed everywhere; `test` is the unit suite):

| Suite | Tests |
|---|---|
| core `integrationTest` | the current core IT count (read it from the report of the last run; ≥94) |
| `test` | 6 |
| `fullApp` | 6 |
| `workflowIt` | 17 |
| `automationIt` | 10 |
| `documentsIt` | 22 |
| `pagesIt` | 60 |
| `viewsFormsIt` | 24 |
| `layersIt` | 8 |
| `agentIt` | 21 |
| `coreParityIt` | 50 |
| `wireParityIt` | 5 |
| `schemaParityIt` | 2 |
| `coreOnly` | 6 |
| `viewsOnly` | 5 |
| `formsOnly` | 5 |
| `pagesOnly` | 6 |
| `workflowOnly` | 5 |
| `automationOnly` | 5 |
| `documentsOnly` | 4 |
| `gisOnly` | 6 |
| `agentOnly` | 5 |

Total ITs: the current core IT count (read it from the report of the last run; ≥94) core + 272 in `chawpi-integration-tests` (225 full-app + 47 slice). Skipped must be 0 in external mode (the one `assumeTrue` in `FullAppBootTest` only skips under Testcontainers).

- [ ] **Step 4: Every original module IT is ported**

```bash
cd /Users/jorge/IdeaProjects/chawpi/backend/chawpi-integration-tests/src
for t in WorkflowApiTest AutomationApiTest DocumentApiTest DocumentTypeApiTest PageApiTest ViewApiTest FormApiTest LayerApiTest AgentApiTest AgentEmbabelTest AgentToolsTest MetadataApiTest RecordApiTest PermissionEnforcementTest FieldApiTest ObjectCrudApiTest; do
  printf "%-28s %s\n" $t "$(ls */kotlin/chawpi/it/full/$t.kt 2>/dev/null || echo MISSING)"
done
```

Expected: 16 paths, no `MISSING`.

- [ ] **Step 5: Nothing named after the original, nothing committed**

```bash
cd /Users/jorge/IdeaProjects/chawpi
grep -rn -i "sapgis" backend --include=*.kt --include=*.kts --include=*.sql --include=*.imports --include=*.factories || echo "clean"
git status --short | head -40
git log --oneline -1
```

Expected: `clean` (only `generate-expected.sh` may say `sapgis`, and `*.sh` is not in these globs); every P3 file uncommitted; the last commit is the one from before P3.

## Out of scope (later phases)

- **Link/unlink "other end" permission (P2 ledger note).** Implemented and tested in chawpi-core by the P2 fix wave (`CoreHardeningApiTest`); none of the P3 ported tests calls link/unlink, so P3 neither pins nor contradicts it (Ruling T13).
- **Fixes to library code.** Any `BLOCKED: behaviour differs` / `schema differs` report goes back to the controller, who routes the fix to the owning module (a P2 follow-up task), then re-runs the suite.
- **Route-list parity against the original's running app** (`/actuator/mappings`): done statically in P2 (`AllModulesWiringTest`, 49/49 + metadata routes); a live diff belongs to the full-sample example (P6).
- **CI proof of the Testcontainers path.** The build wiring keeps it working (image per suite, no env), but the Docker daemon here is remote, so it is first exercised by GitHub Actions (P7 wires and watches CI). Run time grows by ~20 container starts.
- **Docs.** `docs/development` (how to run the ITs: the six variables, the suite lock replacing "one suite at a time", the PostGIS port) and a HISTORY entry: P7.
- Frontend (P4/P5), examples (P6).

## Self-review

- **Spec coverage ("Tests" / "Verification").**
  - "the 20 sapgis API ITs against a full test app": 9 core ITs were ported in P1 (still green in core, Task 21 re-runs them); the 11 module ITs are Tasks 2–8; the 5 originals with geometry cut out are re-ported whole in Task 9.
  - "a core-only app (asserts GIS routes absent, core works without PostGIS)": Task 12 (starter only, plain PostgreSQL, 50 routes 404) on top of core's own `CoreOnlyApiTest`.
  - "module-matrix smoke tests": Tasks 12–20 via `SliceSmokeTest` (Task 1), each module alone with its optional neighbours absent.
  - "same final DB schema": Task 11 (catalog diff instead of `pg_dump`, same content, rename-proof).
  - "`./gradlew integrationTest` green": Task 21; the Gradle wiring is Task 1 (Ruling T14).
  - "chawpi-test … external DB via `CHAWPI_TEST_DB_*`": kept, and made safe to launch in parallel (suite lock, Task 1).
- **Ledger notes.**
  - 404 not 403: `SliceSmokeTest`, admin and member.
  - PostGIS round-trip: Task 19, plus Task 9's original CRS test.
  - PageApiTest V10/V11: Ruling T11, Task 5.
  - V14 replay (T11b): Ruling T11b, Task 4.
  - Geometry assertions back: Task 9 (whole originals) + Task 10 (whole-body JSON).
  - Link/unlink other end: out of scope, recorded (Ruling T13).
  - `FieldApiTest` `workflow_state`: Task 9.
  - Postgis/pgcrypto `WITH SCHEMA public`: `FullAppBootTest`.
- **Placeholders.** None. Every new file is written out in full. Ported files are exact copies plus the `port_it` rules; the one hand edit (Task 5) gives the exact lines to delete and the full replacement. The fallbacks (Task 1 `failOnNoDiscoveredTests`, Task 10 `const`, Task 12 jsonPath) name the exact alternative line.
- **Type consistency.** These names are used identically in Tasks 1–21:
  - `FullAppIntegrationTest`, `FullTestApplication` (package `chawpi.it.full`);
  - `ModuleRoutes.{byModule, all, absentFrom, probe, PROBE_ID}`;
  - `SliceSmokeTest.{db, installed, admin, objectName, statusOf, memberWithoutGrants}`;
  - `ChawpiTestDatabase.SUITE_LOCK`;
  - the 20 suite names and their derived task names (`compile<Suite>Kotlin`, `ktlint<Suite>SourceSetFormat/Check`, `<suite>Classes`).
  
  Library names were checked on disk: `AutomationRunner.drainOnce(Int)`, `AgentTools.invoke` (suspend), `AgentToolResult(json, summary, error)`, `AgentToolCatalog.AVAILABLE_TRANSITIONS`, `PageTemplate`, `SystemFieldResponse` in `chawpi.core.metadata`, `ModuleMigration.historyTable = flyway_history_<name>`, core seed `core_seed`.
- **Review Focus.** Each of the five lines names the test or step that pins it (Tasks 1, 8, 9, 19).
- **Counts.** Test counts per suite are stated in each task and tabulated in Task 21: 272 ITs in `chawpi-integration-tests` + the current core IT count (read it from the report of the last run; ≥94) in core.
- **Known risks.**
  1. The first wipe against the PostGIS server (extension-created schemas; handled in Task 1 Step 3c, proven in Step 8).
  2. Gradle `jvm-test-suite` + Kotlin + ktlint per-suite task names (Task 1 Step 4 lists them before anyone relies on them).
  3. Hand-derived golden JSON (Task 10 says how to adjudicate against the original's source).
  4. Schema diffs the P2 reviews did not see (Task 11 reports, never patches).
  5. Many concurrent Gradle daemons (the controller caps Wave 1).
