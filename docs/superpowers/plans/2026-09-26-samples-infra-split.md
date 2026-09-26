# Samples and infrastructure split out of wasichai — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move each sample (server + web + extras) into its own sibling repository, move the docker compose setup
into `wasichai-infrastructure`, and leave `wasichai` (backend) and `wasichai-ui` (frontend) as libraries only, with
no infrastructure assumption left in the backend.

**Architecture:** Wave A runs six agents in parallel, one per target repository, each owning a disjoint set of
files: A1–A4 build one standalone sample repository each (a standalone Gradle build under `server/` that consumes
the published `wasichai-bom` and starters, with an automatic Gradle composite override onto the sibling `wasichai`
checkout; a standalone yarn project under `web/` that consumes the published `@wasichai/*`, with `yarn link:local`
onto the sibling `wasichai-ui`), A5 fills the infrastructure repository, A6 strips the sample webs out of
`wasichai-ui`. Wave B (B1) strips `examples/`, `infra/` and the sample convention plugin out of `wasichai`, fixes the
one infra-shaped default (`GeoServerProperties.datastore.host`), and writes ADR-033 and the HISTORY entry. Wave C
verifies all seven repositories together, including the published consumption path, then reviews the whole change.

**Tech Stack:** Kotlin 2.4.20, Spring Boot 4.1.1, Gradle 9.7.1 (composite builds, version catalogs), JDK 25,
ktlint-gradle 14.2.0 / ktlint 1.7.1, Testcontainers 2 via `wasichai-test`, PostgreSQL 18 / PostGIS 3.6 (test
databases behind an ssh tunnel on 5442/5443), React 19.3, Vite 8.3, vitest 5, Tailwind 4.3, yarn 1.22, Node 26
(`node --test`), Playwright 1.63, Python 3 (perene), GitHub Actions (actionlint), GitHub Packages (Maven + npm).

**Spec:** `/Users/jorge/.claude/plans/eres-un-experto-arquitecto-adaptive-lovelace.md` (the approved plan: Target,
Execution, Verification). Read it before your task. This plan adds the exact files and commands; where they
disagree, the spec wins and the disagreement goes into your report.

**Repositories (absolute paths, used everywhere below):**

| Short name | Path | Remote |
|---|---|---|
| wasichai | `/Users/jorge/IdeaProjects/wasichai` | github.com/wasichai/wasichai |
| wasichai-ui | `/Users/jorge/IdeaProjects/wasichai-ui` | github.com/wasichai/wasichai-ui |
| simple-sample | `/Users/jorge/IdeaProjects/simple-sample` | github.com/wasichai/simple-sample |
| documents-sample | `/Users/jorge/IdeaProjects/documents-sample` | github.com/wasichai/documents-sample |
| gis-sample | `/Users/jorge/IdeaProjects/gis-sample` | github.com/wasichai/gis-sample |
| full-sample | `/Users/jorge/IdeaProjects/full-sample` | github.com/wasichai/full-sample |
| wasichai-infrastructure | `/Users/jorge/IdeaProjects/wasichai-infrastructure` | github.com/wasichai/wasichai-infrastructure |

The four sample repositories and `wasichai-infrastructure` exist, are cloned, and hold only a `README.md` (one
heading). The path of `wasichai-infrastructure` is confirmed by the user.

## Global Constraints

Every task's requirements include this section.

- **No git commits, in any repository.** No `git add`, `git commit`, `git stash`, `git checkout -- <file>`,
  `git rm`, no push. Delete files with `rm`. "Done" means the files are on disk and the task's checks pass. Tasks
  end with a report, not a commit. The user commits.
- **Stay inside your ownership column** (section "File ownership"). Every other repository is read-only for you.
  A defect in a file you do not own goes into your report as `route to <task>: <path>: <problem>`, never into a fix.
  Copying *from* a read-only tree (`cp`, `rsync`) is reading; it must never write into the source tree.
- **Port 5432 is never used.** No test, bootRun, psql or Playwright run connects to 5432, and no task starts any
  compose file (`docker compose up` is forbidden in every task; `docker compose config` is allowed). Databases: the
  tunnelled test containers only, `5443` (plain PostgreSQL 18) and `5442` (PostGIS 3.6). Demo databases on the
  tunnel for bootRun and the e2e: `wasichai_simple`, `wasichai_documents`, `wasichai_gis`, `wasichai_full`.
  Integration tests use the wipeable test database named in `it-env.sh` (its name ends in `_test`).
- **Scratch.** Every shell starts with
  `export SCRATCH=/private/tmp/claude-502/-Users-jorge-IdeaProjects-chawpi/73f54845-6d0f-493f-961b-10662da123e9/scratchpad`
  (the session scratchpad; it also holds `tunnel.sh`). Temporary files go there, never into a repository.
- **Tunnel first.** Before any database step run
  `bash /private/tmp/claude-502/-Users-jorge-IdeaProjects-chawpi/73f54845-6d0f-493f-961b-10662da123e9/scratchpad/tunnel.sh`
  (idempotent; prints `tunnel up` or `tunnel started`; `tunnel FAILED` = stop and report).
- **Test database variables** come from the git-ignored
  `/Users/jorge/IdeaProjects/wasichai/wasichai-integration-tests/it-env.sh`. Source it, never print it, never copy
  it. It `cd`s into wasichai, so always source it in a subshell and `cd` afterwards. It does not set the PostGIS
  port; export it yourself. The canonical prefix (called **IT-ENV** below):

  ```bash
  set -a; source /Users/jorge/IdeaProjects/wasichai/wasichai-integration-tests/it-env.sh; set +a
  export WASICHAI_TEST_GIS_DB_PORT=5442
  ```

- **Gradle.** Never run `./gradlew --stop`, never kill a Gradle daemon or `java` process you did not start. Use
  `--no-daemon` on every build, test and publish run. Never run `clean` in `wasichai` from a sample build
  (composite builds build `wasichai`'s projects in place). Runs longer than ~9 minutes go to the background
  (`run_in_background`) and are polled; never use a foreground `sleep`.
- **Secrets never printed.** `infra/.local/secrets.env`, `it-env.sh`, tokens: no `cat`, `echo`, `head`, `diff` of
  them. Compare with `cmp -s a b && echo same`. Reports show `***`.
- **Permission denial = stop.** If the permission classifier denies an action, stop the task and report the exact
  command and message. Do not route around it.
- **Formatting follows `.editorconfig`** (all seven repositories carry the same file): Kotlin 4 spaces,
  TS/JS/YAML/MD 2 spaces, JSON keeps what the file has (the repositories' `package.json` files are 4-space), max 160
  columns, LF, final newline, no trailing whitespace. Kotlin and `.kts`: `./gradlew ktlintFormat` then
  `./gradlew ktlintCheck`. TS/JS/JSON/CSS/YAML: `npx prettier@3.8.2 --write <files>` then `--check` (config
  `.prettierrc.json`; in a sample repository use `web/node_modules/.bin/prettier`). Markdown is hand-formatted and
  checked with the MD check.
- **MD check** (every Markdown file a task creates or edits; prints nothing when clean; run from the repository root
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

- **Links.** Inside one repository: relative links. Across repositories: absolute GitHub URLs,
  `https://github.com/wasichai/<repo>` for a repository, `https://github.com/wasichai/wasichai/blob/main/docs/<path>.md`
  for a backend doc. Wrap doc prose at about 115 columns, like the existing docs.
- **Grep with `command grep`.** The shell's `grep` alias skips git-ignored files.
- **Test counter** (called **COUNT** below). Sums the JUnit XML under a directory for one Gradle test task:

  ```bash
  sum_junit() { # JUnit XML paths on stdin -> "N tests, F failures, E errors"
    xargs command grep -ho '<testsuite [^>]*' |
      awk '{ match($0, / tests="[0-9]+"/); t += substr($0, RSTART + 8, RLENGTH - 9)
             match($0, / failures="[0-9]+"/); f += substr($0, RSTART + 11, RLENGTH - 12)
             match($0, / errors="[0-9]+"/); e += substr($0, RSTART + 9, RLENGTH - 10) }
           END { print t + 0 " tests, " f + 0 " failures, " e + 0 " errors" }'
  }
  # $1 = root dir, $2 = test task name (test | integrationTest)
  count_tests() { find "$1" -path "*/build/test-results/$2/*.xml" -not -path '*/node_modules/*' | sum_junit; }
  # every test task but `test`: wasichai's integrationTest tasks plus the wasichai-integration-tests suites
  count_it() { find "$1" -path '*/build/test-results/*/*.xml' -not -path '*/build/test-results/test/*' -not -path '*/node_modules/*' | sum_junit; }
  ```

- **Counts are the bar.** Sample servers `integrationTest`: simple 4, documents 4, gis 3, full 5 (route parity:
  the log line `route parity: 95 live routes compared`). Sample webs: vitest 2 each, `test:scripts` 3 each.
  perene 36. full-sample e2e 1 passed. wasichai: `./gradlew build` green, build-logic tests 9, `integrationTest` 394
  (94 core + 300 suites), Maven guard `ok (20)`. wasichai-ui: package tests 669, tooling tests 21,
  `check-release --pack` `ok` (11 public packages).
- **Versions.** Libraries are `0.1.0` in both repositories (`wasichai/gradle.properties`, every
  `wasichai-ui/packages/*/package.json`). Samples consume `wasichai:wasichai-bom:0.1.0` and `@wasichai/*` `^0.1.0`.

## Review Focus

Inputs the spec implies but no ordinary build exercises, most likely first. Each has its pinning check in the named
task.

1. **A sample cloned next to `wasichai` must build against that checkout, BOM included.** The BOM is a
   `java-platform` project; if Gradle does not substitute `platform("wasichai:wasichai-bom:0.1.0")` by project, the
   build silently falls back to GitHub Packages (401 before the first release, a stale release after). Expected:
   `dependencyInsight` shows `project :…wasichai-bom` and `project :…wasichai-spring-boot-starter`. Pinned in A1–A4
   (step "Composite substitution"), with the explicit `dependencySubstitution` fallback written out in template S1.
2. **A sample with no sibling checkout and no GitHub credentials.** Expected: a one-line warning naming
   `gpr.user`/`gpr.key` and `GITHUB_ACTOR`/`GITHUB_TOKEN` at configuration time, before any 401. Pinned in A1–A4
   (step "Opt-out and credentials warning").
3. **Two copies of React after `yarn link:local`.** Linked packages resolve `react` from `wasichai-ui/node_modules`;
   without `resolve.dedupe` the app mounts with "Invalid hook call" or a blank page. Expected: tests pass through the
   linked dist too, and the full-sample e2e passes in link mode. Pinned in A1–A4 (`WASICHAI_LOCAL=false yarn test`
   while linked) and A4 (Playwright e2e while linked).
4. **Tailwind does not scan symlinked packages.** `@source '../node_modules/@wasichai'` must reach the linked
   `dist`; otherwise the app renders unstyled. Expected: `.flex{` in `web/dist/assets/*.css` after `yarn build` in
   link mode (A1–A4) and in published mode (C1).
5. **An app on a container network loses its GeoServer datastore.** After B1 the default datastore host is
   `localhost`; GeoServer inside compose must be told `postgres`, or published layers show no features. Expected:
   the default is pinned by a unit test (B1), and every README that starts GeoServer sets
   `WASICHAI_GIS_GEOSERVER_DATASTORE_HOST=postgres` (A3, A4, A5).

---

## Inventory (what exists today)

Read on 2026-09-26. Paths are relative to the repository named in each heading.

**wasichai**

- `settings.gradle.kts`: `pluginManagement { includeBuild("build-logic") }`, `rootProject.name = "wasichai"`,
  `RepositoriesMode.PREFER_SETTINGS` + `mavenCentral()`, a folder discovery `includeModules(...)`; its last three
  statements discover `examples/<sample>/<dir>` as project `:<sample>-<dir>` (lines 34–37, the comment
  `// examples/<sample>/server -> :<sample>-server` and the `children(file("examples"))…` block).
- `build.gradle.kts`: `allprojects { group = "wasichai"; version = rootProject.property("version") }`;
  `gradle.properties`: `version=0.1.0`, `org.gradle.caching=true`, `org.gradle.parallel=true`,
  `kotlin.code.style=official`. Wrapper: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.{jar,properties}`
  (Gradle 9.7.1).
- `gradle/libs.versions.toml`: kotlin 2.4.20, springBoot 4.1.1, ktlint 14.2.0, ktlintTool 1.7.1,
  testcontainers 2.0.5; library `spring-boot-gradle-plugin` (used only by build-logic for `wasichai.sample-app`);
  plugin `spring-boot` (unused by any build script).
- `wasichai-bom/build.gradle.kts`: `java-platform` + `wasichai.publishing`, `allowDependencies()`,
  `api(platform(libs.spring.boot.bom))`, constraints `api(project(it.path))` for every `wasichai-*` project except
  `wasichai-bom` and `wasichai-integration-tests`. Starters live in `starters/wasichai-spring-boot-starter[-<module>]`.
- `wasichai-test`: `api` spring-boot-starter-test, -webflux-test, spring-boot-testcontainers,
  testcontainers-junit-jupiter, testcontainers-postgresql, `wasichai-core`. `WasichaiIntegrationTest` is
  `@Tag("integration") @SpringBootTest(RANDOM_PORT)`. Image: system property `wasichai.test.db.image` or env
  `WASICHAI_TEST_DB_IMAGE`, default `postgres:18`. External mode: `WASICHAI_TEST_DB_HOST` set ⇒ `_PORT`, `_NAME`,
  `_USERNAME`, `_PASSWORD` required; the name must end in `_test`.
- `build-logic/src/main/kotlin/`: `wasichai.kotlin-library` (java-library, kotlin jvm, ktlint, group `wasichai`,
  `jvmToolchain(25)`, `-Xjsr305=strict`, `withSourcesJar()`, junit platform, `test` excludes tag `integration`,
  ktlint format tasks never cached), `wasichai.integration-test` (task `integrationTest`, includes tag
  `integration`, adds spring-boot-testcontainers + testcontainers to `testImplementation`,
  `failOnNoDiscoveredTests=false`), `wasichai.spring-module`, `wasichai.publishing`, and `wasichai.sample-app`
  (applies kotlin-library + integration-test + `org.jetbrains.kotlin.plugin.spring` + `org.springframework.boot`;
  throws `"<path> is an example app: example apps are never published"` when `maven-publish` is applied; `bootJar`
  → `app.jar`; `jar` and `sourcesJar` disabled; `test.failOnNoDiscoveredTests=false`).
  `build-logic/build.gradle.kts` has `implementation(libs.spring.boot.gradle.plugin)` with the comment
  `// wasichai.sample-app: the example apps are spring boot applications`.
- `build-logic/src/test/kotlin/wasichai/buildlogic/ConventionPluginsTest.kt`: 12 TestKit tests; the last three are
  the sample-app ones: `sample app builds one boot jar named app jar`, `sample app refuses to be published`,
  `sample app build succeeds with only integration-tagged tests`.
- `examples/README.md`; `examples/<s>/README.md`; `examples/<s>/server/{build.gradle.kts, src/main/kotlin/wasichai/examples/<pkg>/<App>.kt,
  src/main/resources/application.yml, src/test/kotlin/wasichai/examples/<pkg>/*Test.kt}`;
  `examples/full-sample/server/src/test/resources/route-parity/{generate-expected.sh, legacy-routes.txt}` (95 lines);
  `examples/gis-sample/perene/{README.md, apply.py, model.json, test_apply.py (11 tests), test_model.py (25 tests)}`
  plus a `__pycache__/` that must not be copied.
- Sample server builds use `id("wasichai.sample-app")` and project dependencies
  (`implementation(platform(project(":wasichai-bom")))`, `project(":wasichai-spring-boot-starter[-x]")`,
  `testImplementation(project(":wasichai-test"))`). gis and full add an `integrationTest` block (PostGIS image,
  `WASICHAI_TEST_GIS_DB_PORT`); full also blanks `ANTHROPIC_API_KEY` for every `Test` task.
- `infra/docker/compose.yml` (services `postgres` built from `./postgres`, port `${WASICHAI_PG_PORT:-5432}:5432`;
  `geoserver` profile `gis`, 8081; `postgres-plain` profile `core`, `5433:5432`), `infra/docker/postgres/Dockerfile`,
  `infra/docker/postgres/init/01-extensions.sql`, `infra/.local/secrets.env` (git-ignored by `infra/.local/`).
- `.github/workflows/ci.yml` jobs: `backend`, `integration`, `examples-servers`, `perene`, `format`,
  `publish-dry-run`. `.github/scripts/check-maven-publications.sh` expects 20 artifacts.
- `wasichai-gis/src/main/kotlin/wasichai/gis/GeoServerProperties.kt`: `GeoServerDataStoreProperties.host = "postgres"`,
  comment `// how geoserver itself reaches postgres. not how wasichai reaches it: geoserver lives in another container.`
  Test home: `wasichai-gis/src/test/kotlin/wasichai/gis/WasichaiGisAutoConfigurationTest.kt`
  (`geoserver settings bind under wasichai gis geoserver`).
- Docs with `examples/`, `infra/` or compose references outside ADRs and HISTORY: `README.md` (lines 30, 33, 34,
  41), `CLAUDE.md` (lines 12, 34, 48, 53), `docs/development/getting-started.md` (lines 5, 13, 16, 22–47, 68–78),
  `docs/guides/build-your-app.md` (203, 252–260), `docs/modules/gis.md` (12–13, 77–80), `docs/gis/geometry.md`
  (104–106), `docs/chawpi-origin.md` and `docs/sapgis-origin.md` (history pages). `.gitignore` (`infra/.local/`),
  `.prettierignore` (`infra/`, `examples/gis-sample/perene/`).
- ADRs: last is ADR-032; ADR-009 is "Docker Compose for development"; ADR-031's last entry is D16 and its
  groups are "Because modules are optional", "Fixed on the way", "Kept on purpose".

**wasichai-ui**

- `package.json` workspaces `["packages/*", "examples/*/web"]`; scripts `lint`, `test`, `test:tooling`, `build`
  (`node tooling/run-ordered.mjs build`).
- `examples/README.md`; `examples/<s>/web/{index.html, package.json, tsconfig.json, tsconfig.build.json,
  vite.config.ts, src/{App.tsx, App.test.tsx, index.css, main.tsx, test/setup.ts}}`; full-sample adds
  `playwright.config.ts` and `e2e/smoke.spec.ts`. Every web has 2 vitest tests. Deps `@wasichai/*` are `"*"`.
  `vite.config.ts` aliases `@wasichai/<m>` to `../../../packages/<m>/src/index.ts` for vitest only.
  `src/index.css` has `@source '../../../../node_modules/@wasichai';`. `playwright.config.ts` starts
  `java -jar ${WASICHAI_BACKEND_DIR ?? '../../../../wasichai'}/examples/full-sample/server/build/libs/app.jar`.
- `tooling/check-release.mjs`: `checkReleaseConfig` ends with a block that requires every workspace outside
  `packages/` to be `"private": true` (imports `expandWorkspaces` for it). `tooling/check-release.test.mjs` (8
  tests) has a fixture with `examples/one/web` and the test `a sample web that is not private is reported`.
  `tooling/run-ordered.test.mjs` uses `examples/*/web` as a fixture glob. Tooling tests total 22.
- `.github/workflows/ci.yml` jobs: `frontend`, `sample-webs` (matrix), `release-guard`, `e2e` (checks out
  `wasichai/wasichai` with `WASICHAI_REPO_TOKEN`).
- Docs: `README.md` (lines 31, 42), `CLAUDE.md` (lines 10, 39), `docs/README.md` (lines 20, 28, 30, 33–51, 66).
- `.npmrc`: `@wasichai:registry=https://npm.pkg.github.com`. `.prettierrc.json`: `semi false, singleQuote,
  trailingComma none, arrowParens always`. `.editorconfig` is byte-identical to wasichai's.

**Per-sample parameters** (used by every template below)

| Sample | Kotlin package / app class | Server port | Web port | DB default name / port | Starters (`wasichai-spring-boot-starter` + …) | Web `@wasichai/*` (vitest `modules`) | IT image | ITs |
|---|---|---|---|---|---|---|---|---|
| simple | `wasichai.examples.simple` / `SimpleSampleApplication` | 8091 | 5171 | `wasichai` / `5433` | — | ui, core, testing | `postgres:18` | 4 |
| documents | `wasichai.examples.documents` / `DocumentsSampleApplication` | 8092 | 5172 | `wasichai_documents` / required | documents, automation | ui, core, testing, documents, automation | `postgres:18` | 4 |
| gis | `wasichai.examples.gis` / `GisSampleApplication` | 8090 | 5173 | `wasichai_gis` / required | gis | ui, core, testing, gis | `postgis/postgis:18-3.6` | 3 |
| full | `wasichai.examples.full` / `FullSampleApplication` | 8093 | 5174 | `wasichai_full` / required | views, forms, pages, workflow, automation, documents, gis, agent | ui, core, testing, gis, workflow, pages, views, forms, documents, automation, agent | `postgis/postgis:18-3.6` | 5 |

## File ownership

One writer per path. Everything not in a task's column is read-only for that task.

| Task | Writes (creates, edits, deletes) | Reads (copies from) |
|---|---|---|
| A1 | everything under `/Users/jorge/IdeaProjects/simple-sample` | wasichai `examples/simple-sample/**`, wasichai wrapper + `.editorconfig`; wasichai-ui `examples/simple-sample/web/**`, `.prettierrc.json` |
| A2 | everything under `/Users/jorge/IdeaProjects/documents-sample` | same, for `documents-sample` |
| A3 | everything under `/Users/jorge/IdeaProjects/gis-sample` | same, for `gis-sample`, plus `examples/gis-sample/perene/**` |
| A4 | everything under `/Users/jorge/IdeaProjects/full-sample` | same, for `full-sample`, plus `route-parity/**`, `playwright.config.ts`, `e2e/**` |
| A5 | everything under `/Users/jorge/IdeaProjects/wasichai-infrastructure` | wasichai `infra/**` (secrets: `cp` only), `.editorconfig` |
| A6 | wasichai-ui: `examples/` (delete), `package.json`, `yarn.lock`, `node_modules/` (via `yarn install`), `tooling/check-release.mjs`, `tooling/check-release.test.mjs`, `tooling/run-ordered.test.mjs`, `.github/workflows/ci.yml`, `README.md`, `CLAUDE.md`, `docs/README.md` | — |
| B1 | wasichai: `examples/` and `infra/` (delete), `settings.gradle.kts`, `gradle/libs.versions.toml`, `build-logic/build.gradle.kts`, `build-logic/src/main/kotlin/wasichai.sample-app.gradle.kts` (delete), `build-logic/src/test/kotlin/wasichai/buildlogic/ConventionPluginsTest.kt`, `.github/workflows/ci.yml`, `.github/scripts/check-maven-publications.sh` (comment only), `.gitignore`, `.prettierignore`, `wasichai-gis/src/main/kotlin/wasichai/gis/GeoServerProperties.kt`, `wasichai-gis/src/test/kotlin/wasichai/gis/WasichaiGisAutoConfigurationTest.kt`, `README.md`, `CLAUDE.md`, `docs/development/getting-started.md`, `docs/guides/build-your-app.md`, `docs/modules/gis.md`, `docs/gis/geometry.md`, `docs/chawpi-origin.md` (one appended paragraph), `docs/adr/0031-deliberate-deviations-from-sapgis.md` (D17 only), `docs/adr/0033-samples-and-infrastructure-in-their-own-repositories.md` (new), `docs/adr/README.md` (one index line), `docs/HISTORY.md` (one new entry on top) | the six other repositories |
| C1 | nothing in any repository; only the scratchpad (`$SCRATCH` below) and build outputs (`build/`, `dist/`, `node_modules/`, `.gradle/`) the checks produce | all |
| C2 | whatever the review routes back, through the owning task's column (C2 dispatches a fix to the owner, it does not edit) | all |

`$SCRATCH` is `/private/tmp/claude-502/-Users-jorge-IdeaProjects-chawpi/73f54845-6d0f-493f-961b-10662da123e9/scratchpad`.

## Waves and gates

```
G0 (coordinator)  T7 of the migration reported done  ->  pre-flight
Wave A            A1  A2  A3  A4  A5  A6(phase 1)          all in parallel
G1 (coordinator)  A1–A4 reported "copied + web verified"   ->  A6(phase 2)
Wave B            B1                                       after every Wave A task reported done
Wave C            C1  ->  C2
```

- **G0, pre-flight (coordinator, before dispatching Wave A).** The migration's final verification (T7) must have
  finished: A1–A4 build `wasichai` projects in place through the composite build and start servers on 8090–8093 and
  Vite on 5171–5174. Then, once, so that the four composite builds find every `wasichai` task up to date instead of
  compiling the same outputs concurrently:

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai && ./gradlew assemble testClasses --no-daemon -q && echo wasichai warm
  cd /Users/jorge/IdeaProjects/wasichai-ui && yarn install --frozen-lockfile && yarn build && echo wasichai-ui warm
  ls /Users/jorge/IdeaProjects/wasichai-ui/packages/{ui,core,testing,gis,workflow,pages,views,forms,documents,automation,agent}/dist/index.js | wc -l
  ```

  Expected: `wasichai warm`, `wasichai-ui warm`, `11`. Nobody edits `wasichai` sources during Wave A (B1 is later).
- **G1.** A6 phase 2 deletes `wasichai-ui/examples/` and re-runs `yarn install` in `wasichai-ui`, which rewrites
  `wasichai-ui/node_modules`. A1–A4 read from `wasichai-ui/examples/` (copy) and resolve linked packages' imports from
  `wasichai-ui/node_modules` (web verification). The coordinator starts A6 phase 2 only after A1–A4 have each reported
  both. A4's e2e runs before G1 as part of its web verification.
- **Wave B** deletes `wasichai/examples/` and `wasichai/infra/`: it starts only after A1–A5 reported done (B1 step 1
  re-checks that every copy exists).
- **Wave C** is last. C2's fixes go to the owning task's agent (resumed or fresh) and C1 re-runs the affected checks.

## Shared templates

A1–A4 write the same files with per-sample values. The templates below are exact; `<…>` placeholders are filled
from the per-sample table in "Inventory" and the values each task lists. Nothing else changes.

### S1 `server/settings.gradle.kts`

```kotlin
// a standalone app on the published wasichai libraries (maven.pkg.github.com/wasichai/wasichai). a wasichai checkout
// next to this repository wins: gradle builds it and substitutes every wasichai:* module, the bom included, with its
// project. -Pwasichai.local=false uses the published artifacts anyway.
rootProject.name = "<SAMPLE>-server"

val wasichaiDir = file("../../wasichai")
val local = wasichaiDir.isDirectory && providers.gradleProperty("wasichai.local").orNull != "false"
if (local) {
    includeBuild(wasichaiDir)
}

// -Pwasichai.repo=<url> reads wasichai:* from a mirror or a local maven repository (file:///...) instead
val wasichaiRepo = providers.gradleProperty("wasichai.repo").getOrElse("https://maven.pkg.github.com/wasichai/wasichai")
val gprUser = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
val gprKey = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
if (!local && wasichaiRepo.startsWith("https:") && (gprUser == null || gprKey == null)) {
    logger.warn(
        "wasichai: no GitHub Packages credentials (gpr.user/gpr.key in ~/.gradle/gradle.properties, or GITHUB_ACTOR/GITHUB_TOKEN): " +
            "wasichai:* will not resolve. See README, \"Requirements\".",
    )
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // wasichai:* comes only from here (or from the composite build above), never from maven central
        exclusiveContent {
            forRepository {
                maven(wasichaiRepo) {
                    name = "wasichai"
                    if (wasichaiRepo.startsWith("https:")) {
                        credentials {
                            username = gprUser
                            password = gprKey
                        }
                    }
                }
            }
            filter { includeGroup("wasichai") }
        }
    }
}
```

**Fallback, only if the "Composite substitution" check of your task fails for the BOM** (the check prints the
published `wasichai:wasichai-bom:0.1.0` instead of a project). Replace the `includeBuild(wasichaiDir)` line with the
block below, listing every `wasichai:*` module your `build.gradle.kts` names; this is the complete list for
full-sample, drop the starters your sample does not use:

```kotlin
    includeBuild(wasichaiDir) {
        // the bom is a java-platform: substitute it as a platform, the rest as plain modules
        dependencySubstitution {
            substitute(platform(module("wasichai:wasichai-bom"))).using(platform(project(":wasichai-bom")))
            substitute(module("wasichai:wasichai-test")).using(project(":wasichai-test"))
            substitute(module("wasichai:wasichai-spring-boot-starter")).using(project(":wasichai-spring-boot-starter"))
            listOf("views", "forms", "pages", "workflow", "automation", "documents", "gis", "agent").forEach {
                substitute(module("wasichai:wasichai-spring-boot-starter-$it")).using(project(":wasichai-spring-boot-starter-$it"))
            }
        }
    }
```

Record in your report whether the fallback was needed; C2 decides whether all four samples take it.

### S2 `server/gradle/libs.versions.toml`

```toml
# what this app builds with. every other version (spring boot's included) comes through wasichai-bom
[versions]
wasichai = "0.1.0"
kotlin = "2.4.20"
springBoot = "4.1.1"
ktlint = "14.2.0"
ktlintTool = "1.7.1"

[libraries]
wasichai-bom = { module = "wasichai:wasichai-bom", version.ref = "wasichai" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-spring = { id = "org.jetbrains.kotlin.plugin.spring", version.ref = "kotlin" }
spring-boot = { id = "org.springframework.boot", version.ref = "springBoot" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint" }
```

### S3 `server/build.gradle.kts`

The former `wasichai.sample-app` convention (plus the parts of `wasichai.kotlin-library` and
`wasichai.integration-test` a sample used), inlined. Replace `// @DEPENDENCIES@` and `// @EXTRA@` with the blocks
your task gives (`// @EXTRA@` may become nothing: then delete the marker line and the blank line after it).

```kotlin
import org.springframework.boot.gradle.tasks.bundling.BootJar

// a sample app: a spring boot application assembled from the wasichai starters. never published.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.ktlint)
}

// @DEPENDENCIES@

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// ktlint reads the repository's .editorconfig
ktlint {
    version.set(libs.versions.ktlintTool)
}

pluginManager.withPlugin("maven-publish") {
    throw GradleException("${project.path} is a sample app: sample apps are never published")
}

// one runnable jar with a fixed name: the playwright smoke and plain `java -jar` starts need no glob
tasks.named<BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}

// an app, not a library: no plain jar next to the boot jar (and no withSourcesJar(), so no sources jar)
tasks.named<Jar>("jar") {
    enabled = false
}

// the smoke tests are all integration-tagged: `test` runs none of them, and zero is expected
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
    failOnNoDiscoveredTests.set(false)
}

// the integration-tagged tests on a real PostgreSQL: testcontainers, or an external database through
// WASICHAI_TEST_DB_* (the test jvm inherits the environment)
val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the integration-tagged smoke tests against PostgreSQL (Testcontainers or WASICHAI_TEST_DB_*)."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.named("test"))
}

// @EXTRA@
```

### S4 `server/gradle.properties`

```properties
org.gradle.caching=true
org.gradle.parallel=true
kotlin.code.style=official
```

### S5 Gradle wrapper (copied, never regenerated)

```bash
R=/Users/jorge/IdeaProjects/<SAMPLE>
mkdir -p "$R/server/gradle/wrapper"
cp /Users/jorge/IdeaProjects/wasichai/gradlew /Users/jorge/IdeaProjects/wasichai/gradlew.bat "$R/server/"
cp /Users/jorge/IdeaProjects/wasichai/gradle/wrapper/gradle-wrapper.jar \
   /Users/jorge/IdeaProjects/wasichai/gradle/wrapper/gradle-wrapper.properties "$R/server/gradle/wrapper/"
chmod +x "$R/server/gradlew"
command grep -c 'gradle-9.7.1-bin.zip' "$R/server/gradle/wrapper/gradle-wrapper.properties"
```

Expected: `1`.

### S6 `web/package.json` (rewrite of the copied file)

Run from the sample repository root after the web was copied. It pins `@wasichai/*` to `^0.1.0`, adds the engine
and the three scripts, and lets `lint` also check `scripts/`:

```bash
node --input-type=module -e '
import { readFileSync, writeFileSync } from "node:fs"
const path = "web/package.json"
const pkg = JSON.parse(readFileSync(path, "utf8"))
for (const field of ["dependencies", "devDependencies"])
  for (const name of Object.keys(pkg[field] ?? {})) if (name.startsWith("@wasichai/")) pkg[field][name] = "^0.1.0"
pkg.engines = { node: ">=26" }
pkg.scripts.lint = pkg.scripts.lint.replace("prettier --check src ", "prettier --check src scripts ")
pkg.scripts["test:scripts"] = "node --test scripts/"
pkg.scripts["link:local"] = "node scripts/local-packages.mjs link"
pkg.scripts["unlink:local"] = "node scripts/local-packages.mjs unlink"
writeFileSync(path, JSON.stringify(pkg, null, 4) + "\n")'
node -e 'const p = require("./web/package.json"); console.log(p.private, Object.entries({ ...p.dependencies, ...p.devDependencies }).filter(([n]) => n.startsWith("@wasichai/")).every(([, v]) => v === "^0.1.0"), p.scripts.lint.includes("src scripts"))'
```

Expected: `true true true`.

### S7 `web/vite.config.ts` (replaces the copied file)

```ts
import { existsSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

const here = (path: string) => fileURLToPath(new URL(path, import.meta.url))
// a wasichai-ui checkout next to this repository: vitest runs against its sources (no build, no link needed), and
// `yarn link:local` serves its built dist to dev and build. WASICHAI_LOCAL=false uses node_modules only
const uiRepo = here('../../wasichai-ui')
const local = existsSync(`${uiRepo}/packages`) && process.env.WASICHAI_LOCAL !== 'false'
const source = (name: string) => `${uiRepo}/packages/${name}/src/index.ts`
const api = process.env.WASICHAI_API_URL ?? 'http://localhost:<SERVER_PORT>'
const modules = [<MODULES>]

export default defineConfig({
  plugins: [react(), tailwindcss()],
  // linked or aliased packages import these from wasichai-ui's node_modules: one copy each, or hooks break
  resolve: { dedupe: ['react', 'react-dom', 'react-router', '@tanstack/react-query', 'i18next', 'react-i18next'] },
  server: { port: <WEB_PORT>, strictPort: true, proxy: { '/api': api }, fs: { allow: [here('.'), ...(local ? [uiRepo] : [])] } },
  preview: { port: <WEB_PORT>, strictPort: true, proxy: { '/api': api } },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    <E2E_COMMENT>include: ['src/**/*.test.tsx'],
    alias: local ? Object.fromEntries(modules.map((name) => [`@wasichai/${name}`, source(name)])) : {}
  }
})
```

`<MODULES>` is the quoted, comma-separated "Web `@wasichai/*`" column in table order (e.g. `'ui', 'core', 'testing'`).
`<E2E_COMMENT>` is empty, except for full-sample: `// e2e/ is playwright's (yarn e2e), not vitest's` plus a newline
and four spaces before `include`.

### S8 `web/scripts/local-packages.mjs` and `web/scripts/local-packages.test.mjs`

Test first (identical in all four samples):

```js
// the manifest logic of link:local, and its refusal to link packages that were never built.
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'

import { link, wasichaiPackages, withoutWasichai } from './local-packages.mjs'

const manifest = {
  name: 'demo-web',
  dependencies: { '@wasichai/core': '^0.1.0', react: '19.3.0' },
  devDependencies: { '@wasichai/testing': '^0.1.0', vite: '8.3.0' }
}

test('names every @wasichai package, dependencies and devDependencies alike', () => {
  assert.deepEqual(wasichaiPackages(manifest), ['core', 'testing'])
})

test('link mode installs everything but @wasichai/* and leaves the manifest alone', () => {
  const stripped = withoutWasichai(manifest)
  assert.deepEqual(stripped.dependencies, { react: '19.3.0' })
  assert.deepEqual(stripped.devDependencies, { vite: '8.3.0' })
  assert.equal(manifest.dependencies['@wasichai/core'], '^0.1.0')
})

test('refuses to link a package without a built dist, before touching anything', () => {
  const root = mkdtempSync(join(tmpdir(), 'link-local-'))
  try {
    const web = join(root, 'web')
    mkdirSync(web)
    writeFileSync(join(web, 'package.json'), JSON.stringify(manifest))
    mkdirSync(join(root, 'packages', 'core', 'dist'), { recursive: true })
    assert.throws(() => link(web, join(root, 'packages')), /testing.*yarn build in wasichai-ui/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
```

Implementation:

```js
#!/usr/bin/env node
// yarn link:local / yarn unlink:local. link: node_modules/@wasichai/* become links to the sibling
// ../../wasichai-ui/packages/* (their built dist), to try a wasichai-ui change here before it is released.
// unlink: back to the registry versions pinned in yarn.lock. package.json and yarn.lock never change.
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const DEP_FIELDS = ['dependencies', 'devDependencies']
const SCOPE = '@wasichai/'

// the @wasichai package names the manifest asks for, without the scope
export function wasichaiPackages(manifest) {
  const names = DEP_FIELDS.flatMap((field) => Object.keys(manifest[field] ?? {})).filter((name) => name.startsWith(SCOPE))
  return [...new Set(names.map((name) => name.slice(SCOPE.length)))].sort()
}

// what yarn installs in link mode: everything but @wasichai/*, which the links provide
export function withoutWasichai(manifest) {
  const copy = structuredClone(manifest)
  for (const field of DEP_FIELDS) {
    for (const name of Object.keys(copy[field] ?? {})) if (name.startsWith(SCOPE)) delete copy[field][name]
  }
  return copy
}

function yarn(args, cwd) {
  execFileSync('yarn', args, { cwd, stdio: 'inherit' })
}

export function link(web, packages) {
  const path = join(web, 'package.json')
  const original = readFileSync(path, 'utf8')
  const manifest = JSON.parse(original)
  const names = wasichaiPackages(manifest)
  const unbuilt = names.filter((name) => !existsSync(join(packages, name, 'dist')))
  if (unbuilt.length > 0) throw new Error(`no dist for ${unbuilt.join(', ')} under ${packages}: run yarn install && yarn build in wasichai-ui first`)
  // yarn would fetch @wasichai/* from the registry: install the rest, reading yarn.lock but never writing it
  writeFileSync(path, `${JSON.stringify(withoutWasichai(manifest), null, 4)}\n`)
  try {
    yarn(['install', '--pure-lockfile'], web)
  } finally {
    writeFileSync(path, original)
  }
  const scope = join(web, 'node_modules', '@wasichai')
  rmSync(scope, { recursive: true, force: true })
  mkdirSync(scope, { recursive: true })
  for (const name of names) symlinkSync(join(packages, name), join(scope, name), 'dir')
  console.log(`link:local: ${names.length} @wasichai packages -> ${packages}`)
}

export function unlink(web) {
  rmSync(join(web, 'node_modules', '@wasichai'), { recursive: true, force: true })
  yarn(['install', '--frozen-lockfile', '--check-files'], web)
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const web = resolve(dirname(fileURLToPath(import.meta.url)), '..')
  const packages = resolve(web, '../../wasichai-ui/packages')
  const mode = process.argv[2]
  if (mode === 'link') link(web, packages)
  else if (mode === 'unlink') unlink(web)
  else {
    console.error('usage: local-packages.mjs link|unlink')
    process.exit(1)
  }
}
```

### S9 `web/.npmrc`

```ini
# @wasichai/* come from GitHub Packages. the token goes in ~/.npmrc, never here:
# //npm.pkg.github.com/:_authToken=<classic PAT with read:packages>
@wasichai:registry=https://npm.pkg.github.com
```

### S10 `web/src/index.css` (replaces the copied file)

```css
@import 'tailwindcss';
@import '@wasichai/ui/theme.css';
/* the @wasichai packages ship class names, not css, so tailwind scans them (linked ones too, after yarn link:local) */
@source '../node_modules/@wasichai';
```

### S11 `.gitignore` (repository root)

```gitignore
# Gradle
.gradle/
build/
.kotlin/

# Node
node_modules/
dist/
.vite/
*.tsbuildinfo
coverage/
*.tgz

# Playwright
test-results/
playwright-report/
.playwright-mcp/

# Python
__pycache__/
*.pyc

# IDE / OS
.idea/
*.iml
.vscode/
.DS_Store

# Env / secrets
.env
.env.local

# Logs
*.log

# local harness settings
.claude/
.superpowers/
```

### S12 `CLAUDE.md` (repository root)

~~~markdown
# <SAMPLE> — a wasichai sample app

<ONE_LINE>. `server/` is a Spring Boot app assembled from the wasichai starters, `web/` a Vite + React app assembled
from the `@wasichai/*` packages; both use the published libraries unless the sibling checkouts `../wasichai` and
`../wasichai-ui` exist (README, "Libraries: published or local"). The libraries, their docs and every ADR live in
wasichai; local databases in `../wasichai-infrastructure`.

## Rules

- A sample only assembles the libraries. A missing feature or a bug belongs in wasichai or wasichai-ui, not here.
- Never published: `server/` refuses `maven-publish`, `web/` is `"private": true`.
- Port 5432 is never a default and no test connects to it.
- `.editorconfig` is law: `./gradlew ktlintFormat` in `server/`, `node_modules/.bin/prettier --write` in `web/`;
  Markdown by hand, max 160 columns.
- Code, identifiers and comments in English; comments caveman style: short, say why.
- Conventional Commits (`feat: …`, `fix(web): …`).

## Commands

```bash
(cd server && ./gradlew build integrationTest)   # Testcontainers, or WASICHAI_TEST_DB_*
(cd web && yarn install && yarn lint && yarn typecheck && yarn test && yarn test:scripts && yarn build)
(cd web && yarn link:local)                      # after yarn build in ../wasichai-ui
```<EXTRA_COMMANDS>
~~~

### S13 `.github/workflows/ci.yml` (base; A3 and A4 add a job)

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

# wasichai:* and @wasichai/* come from the wasichai organization's GitHub Packages
permissions:
  contents: read
  packages: read

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  server:
    name: Server
    runs-on: ubuntu-latest
    # a hung gradle or testcontainers wait must not burn the default 6 h
    timeout-minutes: 45
    defaults:
      run:
        working-directory: server
    env:
      # settings.gradle.kts reads these for maven.pkg.github.com/wasichai/wasichai. GITHUB_TOKEN reads another
      # repository's packages only when the package grants this repository access; WASICHAI_PACKAGES_TOKEN (a
      # classic PAT with read:packages) works either way
      GITHUB_ACTOR: ${{ github.actor }}
      GITHUB_TOKEN: ${{ secrets.WASICHAI_PACKAGES_TOKEN || secrets.GITHUB_TOKEN }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v4
      # testcontainers starts the database per test jvm: pull once so its start fits the timeout
      - name: Pull database image
        run: docker pull -q <IT_IMAGE>
      - name: Build and integration tests
        run: ./gradlew build integrationTest --no-daemon
      - name: Upload test reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: server-test-reports
          path: server/build/reports/tests/

  web:
    name: Web
    runs-on: ubuntu-latest
    # a hung yarn install or vite build must not burn the default 6 h
    timeout-minutes: 20
    defaults:
      run:
        working-directory: web
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '26'
          cache: yarn
          cache-dependency-path: web/yarn.lock
      # web/.npmrc names the registry; the token stays out of the repository
      - name: Registry token
        run: echo "//npm.pkg.github.com/:_authToken=${NODE_AUTH_TOKEN}" >> ~/.npmrc
        env:
          NODE_AUTH_TOKEN: ${{ secrets.WASICHAI_PACKAGES_TOKEN || secrets.GITHUB_TOKEN }}
      - run: yarn install --frozen-lockfile
      - run: yarn lint
      - run: yarn typecheck
      - run: yarn test
      - run: yarn test:scripts
      - run: yarn build
      # tailwind scanned the @wasichai packages: their utility classes are in the bundle
      - name: Tailwind scanned the packages
        run: grep -l '\.flex{' dist/assets/*.css
```

### S14 `README.md` (repository root)

The common skeleton; each task gives every `<…>` value. `<RUN_SECTION>` ends with the two common paragraphs below;
`<SAMPLE_SECTIONS>` are the sample's own `##` sections; an empty `<EXTRA_TESTS>` leaves one blank line before
`## CI`. Keep the order of sections exactly.

~~~markdown
# <SAMPLE>

<INTRO>

| | |
|---|---|
| Server | `server/`, a Spring Boot app on port <SERVER_PORT>. Dependencies: <SERVER_DEPS> |
| Web | `web/`, a Vite + React app on port <WEB_PORT>, proxying `/api` to the server (`WASICHAI_API_URL`). Packages: <WEB_DEPS> |
| Login | `admin@wasichai.local` / `admin` (dev seed, `WASICHAI_SEED_DEV=true` by default here) |

Never published: `server/` refuses `maven-publish` and `web/` is `"private": true`.

## Requirements

- Java 25, Node 26 and Yarn 1.
- <REQUIREMENT_DB>
- Read access to the wasichai GitHub Packages, unless you build against local checkouts (next section): a classic
  personal access token with `read:packages`, as `gpr.user` / `gpr.key` in `~/.gradle/gradle.properties` (or the
  environment variables `GITHUB_ACTOR` / `GITHUB_TOKEN`) for the server, and as
  `//npm.pkg.github.com/:_authToken=<token>` in `~/.npmrc` for the web.

## Libraries: published or local

By default the server uses `wasichai:wasichai-bom:0.1.0` and the starters from
`https://maven.pkg.github.com/wasichai/wasichai`, and the web uses `@wasichai/*` `^0.1.0` from
`https://npm.pkg.github.com`.

With [wasichai](https://github.com/wasichai/wasichai) and [wasichai-ui](https://github.com/wasichai/wasichai-ui)
checked out next to this repository (`../wasichai`, `../wasichai-ui`):

- **server**: nothing to do. `server/settings.gradle.kts` includes `../wasichai` as a composite build, and Gradle
  builds every `wasichai:*` module, the BOM included, from that checkout. `-Pwasichai.local=false` uses the published
  ones; `-Pwasichai.repo=<url>` reads them from another Maven repository (a mirror, or `file:///…` for a local one).
- **web**: `yarn install && yarn build` in `../wasichai-ui` once, then `yarn link:local` here:
  `node_modules/@wasichai/*` become links to its packages. `yarn unlink:local` goes back to the published versions.
  `yarn test` runs against `../wasichai-ui`'s sources whenever that checkout exists; `WASICHAI_LOCAL=false yarn test`
  uses `node_modules` instead.

## Run

<RUN_SECTION>

<SAMPLE_SECTIONS>

## Tests

```bash
cd server && ./gradlew build integrationTest   # <IT_DATABASE>
cd web && yarn lint && yarn typecheck && yarn test && yarn test:scripts && yarn build
```

Against an external database instead of Testcontainers: `WASICHAI_TEST_DB_HOST`, `_PORT`, `_NAME` (it must end in
`_test`: the tests wipe it), `_USERNAME` and `_PASSWORD`<IT_GIS_NOTE>. See wasichai's
[testing guide](https://github.com/wasichai/wasichai/blob/main/docs/modules/testing.md).
<EXTRA_TESTS>
## CI

`.github/workflows/ci.yml` builds and tests the server (Testcontainers) and the web<CI_EXTRA> against the published
libraries, so it turns green once wasichai and wasichai-ui have a release on GitHub Packages. Until then
`web/yarn.lock` pins every dependency except `@wasichai/*`: after the first wasichai-ui release, run `yarn install` in
`web/` once and commit the lockfile. The workflow reads the packages with `GITHUB_TOKEN` when each package grants
this repository access (package settings, "Manage Actions access"); otherwise add the repository secret
`WASICHAI_PACKAGES_TOKEN`, a classic personal access token with `read:packages`.
~~~

The "Run" section of every sample ends with these two paragraphs (after the sample's variables table):

~~~markdown
One database per sample: the samples install different modules, and one sample cannot read the field types another
left in a shared database ("Field type 'GEOMETRY' is not installed").

Port 5432 is never a default here, and no test connects to it, so a PostgreSQL already running there (another app's)
is never touched by accident. wasichai-infrastructure publishes its PostGIS on `${WASICHAI_PG_PORT:-5432}`: pick a
free port with `WASICHAI_PG_PORT` and give the server the same one as `WASICHAI_DB_PORT`, as the commands above do.
~~~

### S15 Copy commands (per sample; `<S>` is the sample name)

```bash
R=/Users/jorge/IdeaProjects/<S>
rsync -a --exclude build --exclude .gradle --exclude .kotlin /Users/jorge/IdeaProjects/wasichai/examples/<S>/server/ "$R/server/"
rsync -a --exclude node_modules --exclude dist --exclude test-results --exclude playwright-report \
  /Users/jorge/IdeaProjects/wasichai-ui/examples/<S>/web/ "$R/web/"
cp /Users/jorge/IdeaProjects/wasichai/.editorconfig "$R/.editorconfig"
# at the root, so prettier finds it for web/ and for .github/ alike
cp /Users/jorge/IdeaProjects/wasichai-ui/.prettierrc.json "$R/.prettierrc.json"
find "$R/server" "$R/web" -type f | sed "s#^$R/##" | sort
```

The last command lists what landed; compare it with the Inventory (no `build/`, `node_modules/`, `dist/`).

### S16 Initial `web/yarn.lock` (partial, before the first wasichai-ui release)

`@wasichai/*` are not on the registry yet, so the lockfile is written without them; after the first release one
`yarn install` adds them (README, "CI"). From the sample repository root:

```bash
cd web
cp package.json "$SCRATCH/<S>-package.json"
node --input-type=module -e '
import { readFileSync, writeFileSync } from "node:fs"
import { withoutWasichai } from "./scripts/local-packages.mjs"
writeFileSync("package.json", JSON.stringify(withoutWasichai(JSON.parse(readFileSync("package.json", "utf8"))), null, 4) + "\n")'
yarn install --ignore-scripts
cp "$SCRATCH/<S>-package.json" package.json
command grep -c '^"\?@wasichai/' yarn.lock; head -3 yarn.lock
```

Expected: `0`, then the `# THIS IS AN AUTOGENERATED FILE` header. `git diff --no-index "$SCRATCH/<S>-package.json"
package.json` prints nothing.

---

## Task A1: simple-sample repository

**Files (all under `/Users/jorge/IdeaProjects/simple-sample`):**
- Create: `server/**` (copied: `build.gradle.kts` replaced, `src/**` kept; new: `settings.gradle.kts`,
  `gradle.properties`, `gradle/libs.versions.toml`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`)
- Modify: `server/src/main/resources/application.yml` (one comment)
- Create: `web/**` (copied; `package.json`, `vite.config.ts`, `src/index.css` rewritten; new: `.npmrc`,
  `scripts/local-packages.mjs`, `scripts/local-packages.test.mjs`, `yarn.lock`)
- Create: `.editorconfig`, `.prettierrc.json`, `.gitignore`, `CLAUDE.md`, `.github/workflows/ci.yml`
- Modify: `README.md` (replaced)
- Test: `server/src/test/kotlin/wasichai/examples/simple/SimpleSampleSmokeTest.kt` (copied, unchanged),
  `web/src/App.test.tsx` (copied, unchanged), `web/scripts/local-packages.test.mjs`

**Interfaces:**
- Consumes: G0 (wasichai assembled, wasichai-ui built); wasichai projects `:wasichai-bom`,
  `:wasichai-spring-boot-starter`, `:wasichai-test` through the composite build; wasichai-ui
  `packages/{ui,core,testing}/{src,dist}`.
- Produces: a standalone repository; `server/build/libs/app.jar`; `yarn link:local` / `unlink:local` /
  `test:scripts`; the settings properties `wasichai.local` and `wasichai.repo` (C1 uses both); a report line
  "copied + web verified" for G1.

Values: `<SAMPLE>`/`<S>` = `simple-sample`, `<SERVER_PORT>` = `8091`, `<WEB_PORT>` = `5171`,
`<MODULES>` = `'ui', 'core', 'testing'`, `<IT_IMAGE>` = `postgres:18`, `<E2E_COMMENT>` empty.

- [ ] **Step 1: Copy.** Run S15 with `<S>` = `simple-sample`. Expected listing: `server/build.gradle.kts`,
  `server/src/main/kotlin/wasichai/examples/simple/SimpleSampleApplication.kt`,
  `server/src/main/resources/application.yml`, `server/src/test/kotlin/wasichai/examples/simple/SimpleSampleSmokeTest.kt`,
  `web/index.html`, `web/package.json`, `web/src/App.test.tsx`, `web/src/App.tsx`, `web/src/index.css`,
  `web/src/main.tsx`, `web/src/test/setup.ts`, `web/tsconfig.build.json`, `web/tsconfig.json`, `web/vite.config.ts`.

- [ ] **Step 2: Standalone server build.** Write S1 (`rootProject.name = "simple-sample-server"`), S2, S4, run S5.
  Replace `server/build.gradle.kts` with S3, `// @DEPENDENCIES@` =

  ```kotlin
  description = "simple-sample server: wasichai core alone, on plain PostgreSQL"

  dependencies {
      implementation(platform(libs.wasichai.bom))
      implementation("wasichai:wasichai-spring-boot-starter")
      testImplementation("wasichai:wasichai-test")
      testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  }
  ```

  and `// @EXTRA@` removed. In `server/src/main/resources/application.yml` replace the comment line
  `# compose's plain postgres: docker compose -f infra/docker/compose.yml --profile core up -d postgres-plain` with
  `# plain postgres on 5433: wasichai-infrastructure's profile core (README, "Run")`.

- [ ] **Step 3: Format and build.**

  ```bash
  cd /Users/jorge/IdeaProjects/simple-sample/server
  ./gradlew ktlintFormat --no-daemon -q && ./gradlew build --no-daemon
  ls build/libs
  ```

  Expected: `BUILD SUCCESSFUL` (the `test` task finds no tests and passes), then exactly `app.jar`.

- [ ] **Step 4: Composite substitution (Review Focus 1).**

  ```bash
  cd /Users/jorge/IdeaProjects/simple-sample/server
  ./gradlew dependencyInsight --dependency wasichai-bom --configuration compileClasspath --no-daemon -q | command grep -E 'project :(wasichai:)?wasichai-bom' | head -1
  ./gradlew dependencyInsight --dependency wasichai-test --configuration testCompileClasspath --no-daemon -q | command grep -E 'project :(wasichai:)?wasichai-test' | head -1
  ./gradlew dependencyInsight --dependency wasichai-spring-boot-starter --configuration compileClasspath --no-daemon -q | command grep -E 'project :(wasichai:)?wasichai-spring-boot-starter' | head -1
  ```

  Expected: one line per command, each naming a `project`. An empty result for the BOM: apply the S1 fallback,
  re-run steps 3–4, and say so in the report. Any other failure: stop and report the output.

- [ ] **Step 5: Never published.**

  ```bash
  printf 'allprojects { apply(plugin = "maven-publish") }\n' > "$SCRATCH/apply-maven-publish.init.gradle.kts"
  cd /Users/jorge/IdeaProjects/simple-sample/server
  ./gradlew help -I "$SCRATCH/apply-maven-publish.init.gradle.kts" --no-daemon 2>&1 | command grep -c 'sample apps are never published'
  ```

  Expected: a count of at least `1` (the build fails on purpose).

- [ ] **Step 6: Opt-out and credentials warning (Review Focus 2).**

  ```bash
  cd /Users/jorge/IdeaProjects/simple-sample/server
  command grep -c '^gpr\.\(user\|key\)=' ~/.gradle/gradle.properties 2>/dev/null
  env -u GITHUB_ACTOR -u GITHUB_TOKEN ./gradlew help -Pwasichai.local=false --no-daemon 2>&1 | command grep -c 'wasichai: no GitHub Packages credentials'
  ```

  Expected: second command prints `1` when the first printed `0` or nothing; `0` when the first printed `2` (the
  user's own credentials suppress the warning, which is correct). `help` succeeds either way (it resolves nothing).

- [ ] **Step 7: Integration tests.**

  ```bash
  bash "$SCRATCH/tunnel.sh"
  ( set -a; source /Users/jorge/IdeaProjects/wasichai/wasichai-integration-tests/it-env.sh; set +a
    export WASICHAI_TEST_GIS_DB_PORT=5442
    cd /Users/jorge/IdeaProjects/simple-sample/server && ./gradlew integrationTest --rerun --no-daemon )
  # COUNT helper from Global Constraints
  count_tests /Users/jorge/IdeaProjects/simple-sample/server integrationTest
  ```

  Expected: `4 tests, 0 failures, 0 errors`. (`$SCRATCH/tunnel.sh` is the tunnel script of Global Constraints.)
  Other agents run the same database: the suite lock queues you, so a wait is normal.

- [ ] **Step 8: Web package files.** Run S6. Replace `web/vite.config.ts` with S7 (values above), `web/src/index.css`
  with S10, write `web/.npmrc` from S9.

- [ ] **Step 9: Failing test for the link script.** Write `web/scripts/local-packages.test.mjs` from S8 (test part).

  ```bash
  cd /Users/jorge/IdeaProjects/simple-sample/web && node --test scripts/ 2>&1 | tail -5
  ```

  Expected: FAIL, `ERR_MODULE_NOT_FOUND` for `local-packages.mjs`.

- [ ] **Step 10: The link script.** Write `web/scripts/local-packages.mjs` from S8 (implementation part);
  `chmod +x web/scripts/local-packages.mjs`.

  ```bash
  cd /Users/jorge/IdeaProjects/simple-sample/web && node --test scripts/ 2>&1 | command grep -E '^# (pass|fail)'
  ```

  Expected: `# pass 3`, `# fail 0`.

- [ ] **Step 11: Partial lockfile.** Run S16 with `<S>` = `simple-sample`. Expected as stated there.

- [ ] **Step 12: Link, format, verify the web (Review Focus 3 and 4).**

  ```bash
  cd /Users/jorge/IdeaProjects/simple-sample/web
  shasum package.json yarn.lock > "$SCRATCH/simple-sample-web.sha"
  yarn link:local
  shasum -c "$SCRATCH/simple-sample-web.sha"
  ls -l node_modules/@wasichai | command grep -c -- '-> /Users/jorge/IdeaProjects/wasichai-ui/packages/'
  node_modules/.bin/prettier --write package.json vite.config.ts src/index.css scripts
  yarn lint && yarn typecheck && yarn test && yarn test:scripts && yarn build
  grep -l '\.flex{' dist/assets/*.css
  WASICHAI_LOCAL=false yarn test
  ```

  Expected: `link:local: 3 @wasichai packages -> /Users/jorge/IdeaProjects/wasichai-ui/packages`; both `OK` from
  `shasum -c`; `3`; lint, typecheck, test (`2 passed`), test:scripts (`# pass 3`) and build succeed; one css path;
  the last run `2 passed` again (through the linked dist, not the sources). A failing typecheck that names two
  `@types/react` copies, or "Invalid hook call": stop and report (Open risks 3 and 4), do not work around it.

- [ ] **Step 13: Repository files.** Write `.gitignore` (S11), `.github/workflows/ci.yml` (S13 with
  `<IT_IMAGE>` = `postgres:18`), `CLAUDE.md` (S12: `<ONE_LINE>` = `wasichai core alone, on plain PostgreSQL`,
  `<EXTRA_COMMANDS>` empty) and `README.md` (S14) with:
  - `<INTRO>`:
    ```
    wasichai **core alone**, on **plain PostgreSQL** (no PostGIS): objects, fields, records, relationships,
    history, users, roles and permissions. It proves every module, GIS included, is optional.
    ```
  - `<SERVER_DEPS>` = `` `wasichai-bom` + `wasichai-spring-boot-starter` ``; `<WEB_DEPS>` =
    `` `@wasichai/core`, `@wasichai/ui` ``; `<REQUIREMENT_DB>` = `PostgreSQL 18 (no PostGIS needed), on port 5433 by default.`
  - `<RUN_SECTION>`:

    ~~~markdown
    ```bash
    # 1. a database: plain PostgreSQL 18 on 5433 (or any PostgreSQL 18, see the variables below). with
    #    wasichai-infrastructure (https://github.com/wasichai/wasichai-infrastructure) next to this repository:
    (cd ../wasichai-infrastructure && docker compose -f docker/compose.yml --profile core up -d postgres-plain)

    # 2. the server
    (cd server && ./gradlew bootRun)

    # 3. the web, in another shell: http://localhost:5171
    (cd web && yarn install && yarn dev)
    ```

    | Variable | Default | |
    |---|---|---|
    | `WASICHAI_DB_HOST` / `WASICHAI_DB_PORT` / `WASICHAI_DB_NAME` | `localhost` / `5433` / `wasichai` | the database |
    | `WASICHAI_DB_USERNAME` / `WASICHAI_DB_PASSWORD` | `wasichai` / `wasichai` | |
    | `WASICHAI_SEED_DEV` | `true` | seeds the admin user; turn off outside a demo |
    | `WASICHAI_JWT_SECRET` | a sample-only value | set your own (>= 32 bytes) anywhere real |

    A remote database works the same way, e.g. through an ssh tunnel:
    `WASICHAI_DB_PORT=5443 WASICHAI_DB_NAME=wasichai_simple ./gradlew bootRun` in `server/`.
    ~~~

    followed by the two common paragraphs of S14.
  - `<SAMPLE_SECTIONS>`:

    ~~~markdown
    ## What it demonstrates

    - `server/build.gradle.kts`: the BOM plus one starter; the rest of the file is the sample conventions (one boot
      jar `app.jar`, never published, integration-tagged tests). `SimpleSampleApplication` is one annotation,
      `@WasichaiApplication`; `application.yml` is the database, the seed and the JWT secret.
    - `web/src/App.tsx`: `<WasichaiApp modules={[]} />`, the whole frontend.
    - Asking for a `GEOMETRY` field here is a 4xx: the type belongs to the gis module, which is absent.
    ~~~
  - `<IT_DATABASE>` = `Testcontainers postgres:18`; `<IT_GIS_NOTE>`, `<EXTRA_TESTS>`, `<CI_EXTRA>` empty.

- [ ] **Step 14: Checks.**

  ```bash
  cd /Users/jorge/IdeaProjects/simple-sample
  # MD check from Global Constraints with FILES="README.md CLAUDE.md"
  actionlint .github/workflows/ci.yml && echo actionlint ok
  web/node_modules/.bin/prettier --check .github/workflows/ci.yml web/package.json web/vite.config.ts web/src/index.css web/scripts
  command grep -rnE 'infra/docker|examples/|wasichai-ui/tree|WASICHAI_BACKEND_DIR|:simple-sample-server' --exclude-dir=node_modules --exclude-dir=build --exclude-dir=dist --exclude-dir=.gradle --exclude-dir=.git .
  ```

  Expected: MD check silent, `actionlint ok`, prettier `All matched files use Prettier code style!`, grep silent.

- [ ] **Step 15: Report (no commit).** Counts (build, ITs 4, vitest 2 twice, test:scripts 3), whether the S1
  fallback was needed, the `ls -l node_modules/@wasichai` result, and the line "A1 copied + web verified" (for G1).

## Task A2: documents-sample repository

**Files (all under `/Users/jorge/IdeaProjects/documents-sample`):**
- Create: `server/**` (copied: `build.gradle.kts` replaced, `src/**` kept; new: `settings.gradle.kts`,
  `gradle.properties`, `gradle/libs.versions.toml`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`)
- Modify: `server/src/main/resources/application.yml` (one comment)
- Create: `web/**` (copied; `package.json`, `vite.config.ts`, `src/index.css` rewritten; new: `.npmrc`,
  `scripts/local-packages.mjs`, `scripts/local-packages.test.mjs`, `yarn.lock`)
- Create: `.editorconfig`, `.prettierrc.json`, `.gitignore`, `CLAUDE.md`, `.github/workflows/ci.yml`
- Modify: `README.md` (replaced)
- Test: `server/src/test/kotlin/wasichai/examples/documents/DocumentsSampleSmokeTest.kt` (copied, unchanged),
  `web/src/App.test.tsx` (copied, unchanged), `web/scripts/local-packages.test.mjs`

**Interfaces:**
- Consumes: G0; wasichai projects `:wasichai-bom`, `:wasichai-spring-boot-starter`,
  `:wasichai-spring-boot-starter-documents`, `:wasichai-spring-boot-starter-automation`, `:wasichai-test` through the
  composite build; wasichai-ui `packages/{ui,core,testing,documents,automation}/{src,dist}`.
- Produces: a standalone repository; `server/build/libs/app.jar`; `yarn link:local` / `unlink:local` /
  `test:scripts`; a report line "copied + web verified" for G1.

Values: `<SAMPLE>`/`<S>` = `documents-sample`, `<SERVER_PORT>` = `8092`, `<WEB_PORT>` = `5172`,
`<MODULES>` = `'ui', 'core', 'testing', 'documents', 'automation'`, `<IT_IMAGE>` = `postgres:18`, `<E2E_COMMENT>`
empty.

- [ ] **Step 1: Copy.** Run S15 with `<S>` = `documents-sample`. Expected listing: `server/build.gradle.kts`,
  `server/src/main/kotlin/wasichai/examples/documents/DocumentsSampleApplication.kt`,
  `server/src/main/resources/application.yml`,
  `server/src/test/kotlin/wasichai/examples/documents/DocumentsSampleSmokeTest.kt`, `web/index.html`,
  `web/package.json`, `web/src/App.test.tsx`, `web/src/App.tsx`, `web/src/index.css`, `web/src/main.tsx`,
  `web/src/test/setup.ts`, `web/tsconfig.build.json`, `web/tsconfig.json`, `web/vite.config.ts`.

- [ ] **Step 2: Standalone server build.** Write S1 (`rootProject.name = "documents-sample-server"`), S2, S4, run S5.
  Replace `server/build.gradle.kts` with S3, `// @DEPENDENCIES@` =

  ```kotlin
  description = "documents-sample server: wasichai core, documents and automation"

  dependencies {
      implementation(platform(libs.wasichai.bom))
      implementation("wasichai:wasichai-spring-boot-starter")
      implementation("wasichai:wasichai-spring-boot-starter-documents")
      implementation("wasichai:wasichai-spring-boot-starter-automation")
      testImplementation("wasichai:wasichai-test")
      testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  }
  ```

  and `// @EXTRA@` removed. In `server/src/main/resources/application.yml` replace the comment
  `# no default on purpose: point it at your database (compose's postgres service, a tunnel, ...)` with
  `# no default on purpose: point it at your database (wasichai-infrastructure's postgres, a tunnel, ...)`.

- [ ] **Step 3: Format and build.**

  ```bash
  cd /Users/jorge/IdeaProjects/documents-sample/server
  ./gradlew ktlintFormat --no-daemon -q && ./gradlew build --no-daemon
  ls build/libs
  ```

  Expected: `BUILD SUCCESSFUL`, then exactly `app.jar`.

- [ ] **Step 4: Composite substitution (Review Focus 1).**

  ```bash
  cd /Users/jorge/IdeaProjects/documents-sample/server
  ./gradlew dependencyInsight --dependency wasichai-bom --configuration compileClasspath --no-daemon -q | command grep -E 'project :(wasichai:)?wasichai-bom' | head -1
  ./gradlew dependencyInsight --dependency wasichai-test --configuration testCompileClasspath --no-daemon -q | command grep -E 'project :(wasichai:)?wasichai-test' | head -1
  ./gradlew dependencyInsight --dependency wasichai-spring-boot-starter-documents --configuration compileClasspath --no-daemon -q | command grep -E 'project :(wasichai:)?wasichai-spring-boot-starter-documents' | head -1
  ```

  Expected: one line per command, each naming a `project`. An empty result for the BOM: apply the S1 fallback (keep
  only the base, `documents` and `automation` starters), re-run steps 3–4, say so in the report. Any other failure:
  stop and report.

- [ ] **Step 5: Never published.**

  ```bash
  printf 'allprojects { apply(plugin = "maven-publish") }\n' > "$SCRATCH/apply-maven-publish.init.gradle.kts"
  cd /Users/jorge/IdeaProjects/documents-sample/server
  ./gradlew help -I "$SCRATCH/apply-maven-publish.init.gradle.kts" --no-daemon 2>&1 | command grep -c 'sample apps are never published'
  ```

  Expected: at least `1`.

- [ ] **Step 6: Opt-out and credentials warning (Review Focus 2).**

  ```bash
  cd /Users/jorge/IdeaProjects/documents-sample/server
  command grep -c '^gpr\.\(user\|key\)=' ~/.gradle/gradle.properties 2>/dev/null
  env -u GITHUB_ACTOR -u GITHUB_TOKEN ./gradlew help -Pwasichai.local=false --no-daemon 2>&1 | command grep -c 'wasichai: no GitHub Packages credentials'
  ```

  Expected: `1` when the first command printed `0` or nothing, `0` when it printed `2`; `help` succeeds.

- [ ] **Step 7: Integration tests.**

  ```bash
  bash "$SCRATCH/tunnel.sh"
  ( set -a; source /Users/jorge/IdeaProjects/wasichai/wasichai-integration-tests/it-env.sh; set +a
    export WASICHAI_TEST_GIS_DB_PORT=5442
    cd /Users/jorge/IdeaProjects/documents-sample/server && ./gradlew integrationTest --rerun --no-daemon )
  count_tests /Users/jorge/IdeaProjects/documents-sample/server integrationTest   # COUNT helper
  ```

  Expected: `4 tests, 0 failures, 0 errors`. A wait on the suite lock is normal.

- [ ] **Step 8: Web package files.** Run S6. Replace `web/vite.config.ts` with S7 (values above), `web/src/index.css`
  with S10, write `web/.npmrc` from S9. `web/src/main.tsx` keeps `import '@wasichai/documents/print.css'`.

- [ ] **Step 9: Failing test for the link script.** Write `web/scripts/local-packages.test.mjs` from S8 (test part).

  ```bash
  cd /Users/jorge/IdeaProjects/documents-sample/web && node --test scripts/ 2>&1 | tail -5
  ```

  Expected: FAIL, `ERR_MODULE_NOT_FOUND` for `local-packages.mjs`.

- [ ] **Step 10: The link script.** Write `web/scripts/local-packages.mjs` from S8 (implementation part);
  `chmod +x web/scripts/local-packages.mjs`.

  ```bash
  cd /Users/jorge/IdeaProjects/documents-sample/web && node --test scripts/ 2>&1 | command grep -E '^# (pass|fail)'
  ```

  Expected: `# pass 3`, `# fail 0`.

- [ ] **Step 11: Partial lockfile.** Run S16 with `<S>` = `documents-sample`. Expected as stated there.

- [ ] **Step 12: Link, format, verify the web (Review Focus 3 and 4).**

  ```bash
  cd /Users/jorge/IdeaProjects/documents-sample/web
  shasum package.json yarn.lock > "$SCRATCH/documents-sample-web.sha"
  yarn link:local
  shasum -c "$SCRATCH/documents-sample-web.sha"
  ls -l node_modules/@wasichai | command grep -c -- '-> /Users/jorge/IdeaProjects/wasichai-ui/packages/'
  node_modules/.bin/prettier --write package.json vite.config.ts src/index.css scripts
  yarn lint && yarn typecheck && yarn test && yarn test:scripts && yarn build
  grep -l '\.flex{' dist/assets/*.css
  WASICHAI_LOCAL=false yarn test
  ```

  Expected: `link:local: 5 @wasichai packages -> /Users/jorge/IdeaProjects/wasichai-ui/packages`; both `OK`; `5`;
  lint, typecheck, test (`2 passed`), test:scripts (`# pass 3`), build succeed; one css path; `2 passed` again.
  Two `@types/react` copies in typecheck, or "Invalid hook call": stop and report (Open risks 3 and 4).

- [ ] **Step 13: Repository files.** Write `.gitignore` (S11), `.github/workflows/ci.yml` (S13 with
  `<IT_IMAGE>` = `postgres:18`), `CLAUDE.md` (S12: `<ONE_LINE>` = `wasichai core, documents and automation`,
  `<EXTRA_COMMANDS>` empty) and `README.md` (S14) with:
  - `<INTRO>`:
    ```
    wasichai **core + documents + automation**: document templates per object, issuing numbered documents
    from a record (each issue is written to the record's history), a printable sheet, and automations that
    issue documents on their own (`GENERATE_DOCUMENT`). documents and automation meet only through
    automation's optional `DocumentIssuer` port: drop either starter and the other still works.
    ```
  - `<SERVER_DEPS>` = `` `wasichai-bom`, `wasichai-spring-boot-starter`, `-documents`, `-automation` ``;
    `<WEB_DEPS>` = `` `@wasichai/core`, `@wasichai/ui`, `@wasichai/documents`, `@wasichai/automation` ``;
    `<REQUIREMENT_DB>` = `PostgreSQL 18 (PostGIS works too), with a database of its own for this sample.`
  - `<RUN_SECTION>`:

    ~~~markdown
    ```bash
    # 1. a database: any PostgreSQL 18, and this sample's own database in it (once). with wasichai-infrastructure
    #    (https://github.com/wasichai/wasichai-infrastructure) next to this repository, on a free port:
    (cd ../wasichai-infrastructure && WASICHAI_PG_PORT=5434 docker compose -f docker/compose.yml up -d postgres)
    docker exec wasichai-postgres createdb -U wasichai wasichai_documents

    # 2. the server. WASICHAI_DB_PORT has no default: say where the database is
    (cd server && WASICHAI_DB_PORT=5434 ./gradlew bootRun)

    # 3. the web, in another shell: http://localhost:5172
    (cd web && yarn install && yarn dev)
    ```

    | Variable | Default | |
    |---|---|---|
    | `WASICHAI_DB_HOST` / `WASICHAI_DB_PORT` / `WASICHAI_DB_NAME` | `localhost` / **required** / `wasichai_documents` | the database |
    | `WASICHAI_DB_USERNAME` / `WASICHAI_DB_PASSWORD` | `wasichai` / `wasichai` | |
    | `WASICHAI_SEED_DEV` | `true` | seeds the admin user; turn off outside a demo |
    | `WASICHAI_JWT_SECRET` | a sample-only value | set your own (>= 32 bytes) anywhere real |
    | `WASICHAI_AUTOMATION_POLL` | `1s` | automation queue drain; `0s` turns it off |

    Through an ssh tunnel: `WASICHAI_DB_PORT=5442 ./gradlew bootRun` in `server/`.
    ~~~

    followed by the two common paragraphs of S14.
  - `<SAMPLE_SECTIONS>`: the `## Try it` section of the old README copied verbatim (source:
    `/Users/jorge/IdeaProjects/wasichai/examples/documents-sample/README.md`, from the line `## Try it` up to the
    line before `## Tests`).
  - `<IT_DATABASE>` = `Testcontainers postgres:18`; `<IT_GIS_NOTE>`, `<EXTRA_TESTS>`, `<CI_EXTRA>` empty.

- [ ] **Step 14: Checks.**

  ```bash
  cd /Users/jorge/IdeaProjects/documents-sample
  # MD check from Global Constraints with FILES="README.md CLAUDE.md"
  actionlint .github/workflows/ci.yml && echo actionlint ok
  web/node_modules/.bin/prettier --check .github/workflows/ci.yml web/package.json web/vite.config.ts web/src/index.css web/scripts
  command grep -rnE 'infra/docker|examples/|wasichai-ui/tree|WASICHAI_BACKEND_DIR|:documents-sample-server' --exclude-dir=node_modules --exclude-dir=build --exclude-dir=dist --exclude-dir=.gradle --exclude-dir=.git .
  ```

  Expected: MD check silent, `actionlint ok`, prettier clean, grep silent.

- [ ] **Step 15: Report (no commit).** Counts (build, ITs 4, vitest 2 twice, test:scripts 3), whether the S1
  fallback was needed, and the line "A2 copied + web verified" (for G1).

## Task A3: gis-sample repository

**Files (all under `/Users/jorge/IdeaProjects/gis-sample`):**
- Create: `server/**` (copied: `build.gradle.kts` replaced, `src/**` kept; new: `settings.gradle.kts`,
  `gradle.properties`, `gradle/libs.versions.toml`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`)
- Modify: `server/src/main/resources/application.yml` (two comments)
- Create: `web/**` (copied; `package.json`, `vite.config.ts`, `src/index.css` rewritten; new: `.npmrc`,
  `scripts/local-packages.mjs`, `scripts/local-packages.test.mjs`, `yarn.lock`)
- Create: `perene/{README.md, apply.py, model.json, test_apply.py, test_model.py}` (copied; path mentions edited)
- Create: `.editorconfig`, `.prettierrc.json`, `.gitignore`, `CLAUDE.md`, `.github/workflows/ci.yml`
- Modify: `README.md` (replaced)
- Test: `server/src/test/kotlin/wasichai/examples/gis/GisSampleSmokeTest.kt` (copied, unchanged),
  `web/src/App.test.tsx` (copied, unchanged), `web/scripts/local-packages.test.mjs`, `perene/test_*.py` (36 tests)

**Interfaces:**
- Consumes: G0; wasichai projects `:wasichai-bom`, `:wasichai-spring-boot-starter`,
  `:wasichai-spring-boot-starter-gis`, `:wasichai-test` through the composite build; wasichai-ui
  `packages/{ui,core,testing,gis}/{src,dist}`.
- Produces: a standalone repository; `server/build/libs/app.jar`; `yarn link:local` / `unlink:local` /
  `test:scripts`; `perene/` runnable from the repository root; a report line "copied + web verified" for G1.

Values: `<SAMPLE>`/`<S>` = `gis-sample`, `<SERVER_PORT>` = `8090`, `<WEB_PORT>` = `5173`,
`<MODULES>` = `'ui', 'core', 'testing', 'gis'`, `<IT_IMAGE>` = `postgis/postgis:18-3.6`, `<E2E_COMMENT>` empty.

- [ ] **Step 1: Copy.** Run S15 with `<S>` = `gis-sample`, then the model without its bytecode cache:

  ```bash
  rsync -a --exclude __pycache__ /Users/jorge/IdeaProjects/wasichai/examples/gis-sample/perene/ /Users/jorge/IdeaProjects/gis-sample/perene/
  ls /Users/jorge/IdeaProjects/gis-sample/perene
  ```

  Expected S15 listing: `server/build.gradle.kts`, `server/src/main/kotlin/wasichai/examples/gis/GisSampleApplication.kt`,
  `server/src/main/resources/application.yml`, `server/src/test/kotlin/wasichai/examples/gis/GisSampleSmokeTest.kt`,
  `web/index.html`, `web/package.json`, `web/src/App.test.tsx`, `web/src/App.tsx`, `web/src/index.css`,
  `web/src/main.tsx`, `web/src/test/setup.ts`, `web/tsconfig.build.json`, `web/tsconfig.json`, `web/vite.config.ts`.
  `perene`: `README.md apply.py model.json test_apply.py test_model.py`.

- [ ] **Step 2: Standalone server build.** Write S1 (`rootProject.name = "gis-sample-server"`), S2, S4, run S5.
  Replace `server/build.gradle.kts` with S3, `// @DEPENDENCIES@` =

  ```kotlin
  description = "gis-sample server: wasichai core and gis, on PostGIS"

  dependencies {
      implementation(platform(libs.wasichai.bom))
      implementation("wasichai:wasichai-spring-boot-starter")
      implementation("wasichai:wasichai-spring-boot-starter-gis")
      testImplementation("wasichai:wasichai-test")
      testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  }
  ```

  and `// @EXTRA@` =

  ```kotlin
  // the smoke test needs PostGIS: the postgis image under testcontainers, or the external PostGIS
  // server (WASICHAI_TEST_GIS_DB_PORT; database name, user and password are shared with the plain one)
  val externalDb = providers.environmentVariable("WASICHAI_TEST_DB_HOST").isPresent
  val gisDbPort: String? = providers.environmentVariable("WASICHAI_TEST_GIS_DB_PORT").orNull

  tasks.named<Test>("integrationTest") {
      systemProperty("wasichai.test.db.image", "postgis/postgis:18-3.6")
      if (gisDbPort != null) environment("WASICHAI_TEST_DB_PORT", gisDbPort)
      val missingGisPort = externalDb && gisDbPort == null
      doFirst {
          if (missingGisPort) throw GradleException("gis-sample-server needs PostGIS: with WASICHAI_TEST_DB_HOST set, also set WASICHAI_TEST_GIS_DB_PORT")
      }
  }
  ```

  In `server/src/main/resources/application.yml` replace
  `# no default on purpose: a PostGIS server (compose's postgres service, a tunnel, ...)` with
  `# no default on purpose: a PostGIS server (wasichai-infrastructure's postgres, a tunnel, ...)` and
  `# optional: docker compose -f infra/docker/compose.yml --profile gis up -d, then WASICHAI_GEOSERVER_ENABLED=true`
  with `# optional: wasichai-infrastructure's profile gis (README, "GeoServer"), then WASICHAI_GEOSERVER_ENABLED=true`.

- [ ] **Step 3: Format and build.**

  ```bash
  cd /Users/jorge/IdeaProjects/gis-sample/server
  ./gradlew ktlintFormat --no-daemon -q && ./gradlew build --no-daemon
  ls build/libs
  ```

  Expected: `BUILD SUCCESSFUL`, then exactly `app.jar`.

- [ ] **Step 4: Composite substitution (Review Focus 1).**

  ```bash
  cd /Users/jorge/IdeaProjects/gis-sample/server
  ./gradlew dependencyInsight --dependency wasichai-bom --configuration compileClasspath --no-daemon -q | command grep -E 'project :(wasichai:)?wasichai-bom' | head -1
  ./gradlew dependencyInsight --dependency wasichai-test --configuration testCompileClasspath --no-daemon -q | command grep -E 'project :(wasichai:)?wasichai-test' | head -1
  ./gradlew dependencyInsight --dependency wasichai-spring-boot-starter-gis --configuration compileClasspath --no-daemon -q | command grep -E 'project :(wasichai:)?wasichai-spring-boot-starter-gis' | head -1
  ```

  Expected: one line per command, each naming a `project`. An empty result for the BOM: apply the S1 fallback (keep
  the base and `gis` starters), re-run steps 3–4, say so in the report. Any other failure: stop and report.

- [ ] **Step 5: Never published.**

  ```bash
  printf 'allprojects { apply(plugin = "maven-publish") }\n' > "$SCRATCH/apply-maven-publish.init.gradle.kts"
  cd /Users/jorge/IdeaProjects/gis-sample/server
  ./gradlew help -I "$SCRATCH/apply-maven-publish.init.gradle.kts" --no-daemon 2>&1 | command grep -c 'sample apps are never published'
  ```

  Expected: at least `1`.

- [ ] **Step 6: Opt-out and credentials warning (Review Focus 2).**

  ```bash
  cd /Users/jorge/IdeaProjects/gis-sample/server
  command grep -c '^gpr\.\(user\|key\)=' ~/.gradle/gradle.properties 2>/dev/null
  env -u GITHUB_ACTOR -u GITHUB_TOKEN ./gradlew help -Pwasichai.local=false --no-daemon 2>&1 | command grep -c 'wasichai: no GitHub Packages credentials'
  ```

  Expected: `1` when the first command printed `0` or nothing, `0` when it printed `2`; `help` succeeds.

- [ ] **Step 7: Integration tests, and the missing-port guard.**

  ```bash
  bash "$SCRATCH/tunnel.sh"
  ( set -a; source /Users/jorge/IdeaProjects/wasichai/wasichai-integration-tests/it-env.sh; set +a
    cd /Users/jorge/IdeaProjects/gis-sample/server
    unset WASICHAI_TEST_GIS_DB_PORT
    ./gradlew integrationTest --rerun --no-daemon 2>&1 | command grep -c 'also set WASICHAI_TEST_GIS_DB_PORT'
    export WASICHAI_TEST_GIS_DB_PORT=5442
    ./gradlew integrationTest --rerun --no-daemon )
  count_tests /Users/jorge/IdeaProjects/gis-sample/server integrationTest   # COUNT helper
  ```

  Expected: first `1` (external database without the PostGIS port is refused before any test runs), then
  `3 tests, 0 failures, 0 errors`.

- [ ] **Step 8: Perené paths and tests.**

  ```bash
  cd /Users/jorge/IdeaProjects/gis-sample/perene
  sed -i '' -e 's#`WASICHAI_DB_PORT=<puerto> ./gradlew :gis-sample-server:bootRun`#`WASICHAI_DB_PORT=<puerto> ./gradlew bootRun`#' \
            -e 's#  desde la raíz de `wasichai`, ver \[../README.md\]#  en `server/`, ver [../README.md]#' \
            -e 's#cd examples/gis-sample/perene#cd perene#g' README.md
  sed -i '' -e 's#cd examples/gis-sample/perene#cd perene#' test_apply.py test_model.py
  sed -i '' -e 's#Apply examples/gis-sample/perene/model.json#Apply perene/model.json#' apply.py
  command grep -rnE 'examples/|:gis-sample-server|desde la raíz' . ; python3 -m unittest -v 2>&1 | tail -3
  ```

  Expected: the grep prints nothing; `Ran 36 tests` and `OK`. `model.json` is byte-identical to the source:
  `cmp -s model.json /Users/jorge/IdeaProjects/wasichai/examples/gis-sample/perene/model.json && echo same`.

- [ ] **Step 9: apply.py against the real server.** Port 8090 must be free
  (`lsof -nP -iTCP:8090 -sTCP:LISTEN` prints nothing; otherwise stop and report).

  ```bash
  bash "$SCRATCH/tunnel.sh"
  ( set -a; source /Users/jorge/IdeaProjects/wasichai/wasichai-integration-tests/it-env.sh; set +a
    cd /Users/jorge/IdeaProjects/gis-sample/server
    WASICHAI_DB_HOST=localhost WASICHAI_DB_PORT=5442 WASICHAI_DB_NAME=wasichai_gis \
    WASICHAI_DB_USERNAME="$WASICHAI_TEST_DB_USERNAME" WASICHAI_DB_PASSWORD="$WASICHAI_TEST_DB_PASSWORD" \
      nohup java -jar build/libs/app.jar > "$SCRATCH/gis-sample-server.log" 2>&1 &
    echo $! > "$SCRATCH/gis-sample-server.pid" )
  ```

  Wait with the Monitor tool (until-loop on `curl -sf http://localhost:8090/actuator/health`, 3 minutes max), then:

  ```bash
  cd /Users/jorge/IdeaProjects/gis-sample/perene
  python3 apply.py --validate-only && python3 apply.py | tail -1 && python3 apply.py | tail -1
  kill "$(cat "$SCRATCH/gis-sample-server.pid")"
  ```

  Expected: validation passes; the first apply ends `done: 30 created, 0 skipped` (or `done: 0 created, 30 skipped`
  when the demo database already held the model), the second `done: 0 created, 30 skipped`. The server log never
  mentions port 5432.

- [ ] **Step 10: Web package files.** Run S6. Replace `web/vite.config.ts` with S7 (values above), `web/src/index.css`
  with S10, write `web/.npmrc` from S9. `web/src/main.tsx` keeps its MapLibre worker import.

- [ ] **Step 11: Failing test for the link script.** Write `web/scripts/local-packages.test.mjs` from S8 (test part).

  ```bash
  cd /Users/jorge/IdeaProjects/gis-sample/web && node --test scripts/ 2>&1 | tail -5
  ```

  Expected: FAIL, `ERR_MODULE_NOT_FOUND` for `local-packages.mjs`.

- [ ] **Step 12: The link script.** Write `web/scripts/local-packages.mjs` from S8 (implementation part);
  `chmod +x web/scripts/local-packages.mjs`.

  ```bash
  cd /Users/jorge/IdeaProjects/gis-sample/web && node --test scripts/ 2>&1 | command grep -E '^# (pass|fail)'
  ```

  Expected: `# pass 3`, `# fail 0`.

- [ ] **Step 13: Partial lockfile.** Run S16 with `<S>` = `gis-sample`. Expected as stated there.

- [ ] **Step 14: Link, format, verify the web (Review Focus 3 and 4).**

  ```bash
  cd /Users/jorge/IdeaProjects/gis-sample/web
  shasum package.json yarn.lock > "$SCRATCH/gis-sample-web.sha"
  yarn link:local
  shasum -c "$SCRATCH/gis-sample-web.sha"
  ls -l node_modules/@wasichai | command grep -c -- '-> /Users/jorge/IdeaProjects/wasichai-ui/packages/'
  node_modules/.bin/prettier --write package.json vite.config.ts src/index.css scripts
  yarn lint && yarn typecheck && yarn test && yarn test:scripts && yarn build
  grep -l '\.flex{' dist/assets/*.css
  WASICHAI_LOCAL=false yarn test
  ```

  Expected: `link:local: 4 @wasichai packages -> /Users/jorge/IdeaProjects/wasichai-ui/packages`; both `OK`; `4`;
  lint, typecheck, test (`2 passed`), test:scripts (`# pass 3`), build succeed (the MapLibre worker chunk is in
  `dist/assets`); one css path; `2 passed` again. Two `@types/react` copies, or "Invalid hook call": stop and report.

- [ ] **Step 15: Repository files.** Write `.gitignore` (S11), `.github/workflows/ci.yml` (S13 with
  `<IT_IMAGE>` = `postgis/postgis:18-3.6`, plus this job appended at the end of `jobs:`):

  ```yaml
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
          working-directory: perene
          run: python -m unittest -v
  ```

  `CLAUDE.md` (S12: `<ONE_LINE>` = `wasichai core and gis, on PostGIS, with the Perené cadastre model`,
  `<EXTRA_COMMANDS>` = a blank line, then
  `` `perene/` (the Perené cadastre model): `cd perene && python3 -m unittest -v` (36 tests, no server needed). ``)
  and `README.md` (S14) with:
  - `<INTRO>`:
    ```
    wasichai **core + gis** on **PostGIS**: `GEOMETRY` fields (points, lines, polygons, any SRID), the map on
    a record, the map page, layers and GeoJSON features. GeoServer (WMS publishing) is optional and off by
    default. `perene/` is a real cadastre model (13 objects, 11 spatial, EPSG:32718) to load into it.
    ```
  - `<SERVER_DEPS>` = `` `wasichai-bom`, `wasichai-spring-boot-starter`, `-gis` ``; `<WEB_DEPS>` =
    `` `@wasichai/core`, `@wasichai/ui`, `@wasichai/gis` (+ `maplibre-gl`) ``; `<REQUIREMENT_DB>` =
    `PostgreSQL 18 with PostGIS 3.6, with a database of its own for this sample. GeoServer is optional.`
  - `<RUN_SECTION>`:

    ~~~markdown
    ```bash
    # 1. PostGIS, and this sample's own database in it (once). with wasichai-infrastructure
    #    (https://github.com/wasichai/wasichai-infrastructure) next to this repository, on a free port:
    (cd ../wasichai-infrastructure && WASICHAI_PG_PORT=5434 docker compose -f docker/compose.yml up -d postgres)
    docker exec wasichai-postgres createdb -U wasichai wasichai_gis

    # 2. the server. WASICHAI_DB_PORT has no default: say where the database is
    (cd server && WASICHAI_DB_PORT=5434 ./gradlew bootRun)

    # 3. the web, in another shell: http://localhost:5173
    (cd web && yarn install && yarn dev)
    ```

    | Variable | Default | |
    |---|---|---|
    | `WASICHAI_DB_HOST` / `WASICHAI_DB_PORT` / `WASICHAI_DB_NAME` | `localhost` / **required** / `wasichai_gis` | a PostGIS database |
    | `WASICHAI_DB_USERNAME` / `WASICHAI_DB_PASSWORD` | `wasichai` / `wasichai` | |
    | `WASICHAI_SEED_DEV` | `true` | seeds the admin user; turn off outside a demo |
    | `WASICHAI_JWT_SECRET` | a sample-only value | set your own (>= 32 bytes) anywhere real |
    | `WASICHAI_GEOSERVER_ENABLED` | `false` | publish layers to GeoServer |
    | `WASICHAI_GEOSERVER_URL` | `http://localhost:8081/geoserver` | wasichai-infrastructure's `geoserver` (profile `gis`) |

    Through an ssh tunnel: `WASICHAI_DB_PORT=5442 ./gradlew bootRun` in `server/`.
    ~~~

    followed by the two common paragraphs of S14.
  - `<SAMPLE_SECTIONS>`:

    ~~~markdown
    ## GeoServer (optional)

    ```bash
    (cd ../wasichai-infrastructure && WASICHAI_PG_PORT=5434 docker compose -f docker/compose.yml --profile gis up -d)
    (cd server && WASICHAI_DB_PORT=5434 WASICHAI_GEOSERVER_ENABLED=true WASICHAI_GIS_GEOSERVER_DATASTORE_HOST=postgres \
      WASICHAI_GIS_GEOSERVER_DATASTORE_DATABASE=wasichai_gis ./gradlew bootRun)
    ```

    Without it, everything but WMS publishing works: features are served as GeoJSON by the server itself.

    GeoServer reads the database on its own, through the datastore settings `wasichai.gis.geoserver.datastore.host` /
    `.port` / `.database` (env `WASICHAI_GIS_GEOSERVER_DATASTORE_HOST` / `_PORT` / `_DATABASE`, defaults `localhost` /
    `5432` / `wasichai`). They must name the database the server uses, as GeoServer sees it: on
    wasichai-infrastructure's compose network that is host `postgres`, port 5432 (the container's own port, whatever
    `WASICHAI_PG_PORT` is) and this sample's `wasichai_gis`, as the command above sets. Otherwise GeoServer reads
    another, empty database and the published layers show no features.
    ~~~

    then the `## Load the Perené cadastre model` section of the old README copied verbatim (source:
    `/Users/jorge/IdeaProjects/wasichai/examples/gis-sample/README.md`, from `## Load the Perené cadastre model` up to
    the line before `## What it demonstrates`) with `cd examples/gis-sample/perene` replaced by `cd perene`, then:

    ~~~markdown
    ## What it demonstrates

    - `server/build.gradle.kts`: the BOM, the core starter and the gis starter. The app class is one annotation.
    - `web/src/main.tsx`: the MapLibre worker recipe for Vite (`?worker&url`) and `maplibre-gl.css`;
      `web/src/App.tsx`: `modules={[gisModule({ workerUrl })]}`.
    ~~~
  - `<IT_DATABASE>` = `Testcontainers postgis/postgis:18-3.6`; `<IT_GIS_NOTE>` =
    `, plus WASICHAI_TEST_GIS_DB_PORT, the port of a PostGIS server (name, user and password are shared)`;
    `<CI_EXTRA>` = `, and runs the Perené model's unit tests`; `<EXTRA_TESTS>`:

    ~~~markdown

    ```bash
    cd perene && python3 -m unittest -v   # 36 tests, no server needed
    ```

    ~~~

- [ ] **Step 16: Checks.**

  ```bash
  cd /Users/jorge/IdeaProjects/gis-sample
  # MD check from Global Constraints with FILES="README.md CLAUDE.md perene/README.md"
  actionlint .github/workflows/ci.yml && echo actionlint ok
  web/node_modules/.bin/prettier --check .github/workflows/ci.yml web/package.json web/vite.config.ts web/src/index.css web/scripts
  command grep -rnE 'infra/docker|examples/|wasichai-ui/tree|WASICHAI_BACKEND_DIR|:gis-sample-server' --exclude-dir=node_modules --exclude-dir=build --exclude-dir=dist --exclude-dir=.gradle --exclude-dir=.git .
  ```

  Expected: MD check silent (perene/README.md may have pre-existing long lines: report them, do not rewrap the
  Spanish text), `actionlint ok`, prettier clean, grep silent.

- [ ] **Step 17: Report (no commit).** Counts (build, ITs 3, perene 36, apply.py lines, vitest 2 twice,
  test:scripts 3), whether the S1 fallback was needed, and the line "A3 copied + web verified" (for G1).

## Task A4: full-sample repository

**Files (all under `/Users/jorge/IdeaProjects/full-sample`):**
- Create: `server/**` (copied, including `src/test/resources/route-parity/{generate-expected.sh, legacy-routes.txt}`;
  `build.gradle.kts` replaced; new: `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`,
  `gradlew`, `gradlew.bat`, `gradle/wrapper/*`)
- Modify: `server/src/main/resources/application.yml` (two comments)
- Create: `web/**` (copied, including `e2e/smoke.spec.ts`; `package.json`, `vite.config.ts`, `src/index.css`,
  `playwright.config.ts` rewritten; new: `.npmrc`, `scripts/local-packages.mjs`, `scripts/local-packages.test.mjs`,
  `yarn.lock`)
- Create: `.editorconfig`, `.prettierrc.json`, `.gitignore`, `CLAUDE.md`, `.github/workflows/ci.yml`
- Modify: `README.md` (replaced)
- Test: `server/src/test/kotlin/wasichai/examples/full/{FullSampleSmokeTest.kt, FullSampleRouteParityTest.kt}`
  (copied, unchanged), `web/src/App.test.tsx`, `web/e2e/smoke.spec.ts` (copied, unchanged),
  `web/scripts/local-packages.test.mjs`

**Interfaces:**
- Consumes: G0; wasichai projects `:wasichai-bom`, `:wasichai-spring-boot-starter`, the eight
  `:wasichai-spring-boot-starter-<module>` and `:wasichai-test` through the composite build; wasichai-ui
  `packages/{ui,core,testing,gis,workflow,pages,views,forms,documents,automation,agent}/{src,dist}`.
- Produces: a standalone repository; `server/build/libs/app.jar` (Playwright starts it as `../server/build/libs/app.jar`
  from `web/`); `yarn link:local` / `unlink:local` / `test:scripts` / `e2e`; a report line "copied + web verified"
  for G1 (it includes the e2e).

Values: `<SAMPLE>`/`<S>` = `full-sample`, `<SERVER_PORT>` = `8093`, `<WEB_PORT>` = `5174`,
`<MODULES>` = `'ui', 'core', 'testing', 'gis', 'workflow', 'pages', 'views', 'forms', 'documents', 'automation', 'agent'`,
`<IT_IMAGE>` = `postgis/postgis:18-3.6`, `<E2E_COMMENT>` = `// e2e/ is playwright's (yarn e2e), not vitest's`.

- [ ] **Step 1: Copy.** Run S15 with `<S>` = `full-sample`. Expected listing: `server/build.gradle.kts`,
  `server/src/main/kotlin/wasichai/examples/full/FullSampleApplication.kt`, `server/src/main/resources/application.yml`,
  `server/src/test/kotlin/wasichai/examples/full/FullSampleRouteParityTest.kt`,
  `server/src/test/kotlin/wasichai/examples/full/FullSampleSmokeTest.kt`,
  `server/src/test/resources/route-parity/generate-expected.sh`, `server/src/test/resources/route-parity/legacy-routes.txt`,
  `web/e2e/smoke.spec.ts`, `web/index.html`, `web/package.json`, `web/playwright.config.ts`, `web/src/App.test.tsx`,
  `web/src/App.tsx`, `web/src/index.css`, `web/src/main.tsx`, `web/src/test/setup.ts`, `web/tsconfig.build.json`,
  `web/tsconfig.json`, `web/vite.config.ts`. Then
  `test -x /Users/jorge/IdeaProjects/full-sample/server/src/test/resources/route-parity/generate-expected.sh && echo executable`
  and `wc -l < .../route-parity/legacy-routes.txt` → `executable`, `95`.

- [ ] **Step 2: Standalone server build.** Write S1 (`rootProject.name = "full-sample-server"`), S2, S4, run S5.
  Replace `server/build.gradle.kts` with S3, `// @DEPENDENCIES@` =

  ```kotlin
  description = "full-sample server: wasichai core and every module, on PostGIS"

  dependencies {
      implementation(platform(libs.wasichai.bom))
      implementation("wasichai:wasichai-spring-boot-starter")
      listOf("views", "forms", "pages", "workflow", "automation", "documents", "gis", "agent").forEach {
          implementation("wasichai:wasichai-spring-boot-starter-$it")
      }
      testImplementation("wasichai:wasichai-test")
      testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  }
  ```

  and `// @EXTRA@` =

  ```kotlin
  // the smoke test needs PostGIS: the postgis image under testcontainers, or the external PostGIS
  // server (WASICHAI_TEST_GIS_DB_PORT; database name, user and password are shared with the plain one)
  val externalDb = providers.environmentVariable("WASICHAI_TEST_DB_HOST").isPresent
  val gisDbPort: String? = providers.environmentVariable("WASICHAI_TEST_GIS_DB_PORT").orNull

  tasks.named<Test>("integrationTest") {
      systemProperty("wasichai.test.db.image", "postgis/postgis:18-3.6")
      if (gisDbPort != null) environment("WASICHAI_TEST_DB_PORT", gisDbPort)
      val missingGisPort = externalDb && gisDbPort == null
      doFirst {
          if (missingGisPort) throw GradleException("full-sample-server needs PostGIS: with WASICHAI_TEST_DB_HOST set, also set WASICHAI_TEST_GIS_DB_PORT")
      }
  }

  // the tests decide whether the assistant exists, not the developer's shell
  tasks.withType<Test>().configureEach {
      environment("ANTHROPIC_API_KEY", "")
  }
  ```

  In `server/src/main/resources/application.yml` replace
  `# no default on purpose: a PostGIS server (compose's postgres service, a tunnel, ...)` with
  `# no default on purpose: a PostGIS server (wasichai-infrastructure's postgres, a tunnel, ...)` and
  `# optional: docker compose -f infra/docker/compose.yml --profile gis up -d, then WASICHAI_GEOSERVER_ENABLED=true`
  with `# optional: wasichai-infrastructure's profile gis (README, "GeoServer"), then WASICHAI_GEOSERVER_ENABLED=true`.

- [ ] **Step 3: Format and build.**

  ```bash
  cd /Users/jorge/IdeaProjects/full-sample/server
  ./gradlew ktlintFormat --no-daemon -q && ./gradlew build --no-daemon
  ls build/libs
  ```

  Expected: `BUILD SUCCESSFUL`, then exactly `app.jar`.

- [ ] **Step 4: Composite substitution (Review Focus 1).**

  ```bash
  cd /Users/jorge/IdeaProjects/full-sample/server
  ./gradlew dependencyInsight --dependency wasichai-bom --configuration compileClasspath --no-daemon -q | command grep -E 'project :(wasichai:)?wasichai-bom' | head -1
  ./gradlew dependencyInsight --dependency wasichai-test --configuration testCompileClasspath --no-daemon -q | command grep -E 'project :(wasichai:)?wasichai-test' | head -1
  ./gradlew dependencyInsight --dependency wasichai-spring-boot-starter-agent --configuration compileClasspath --no-daemon -q | command grep -E 'project :(wasichai:)?wasichai-spring-boot-starter-agent' | head -1
  ```

  Expected: one line per command, each naming a `project`. An empty result for the BOM: apply the S1 fallback as
  written (full-sample needs every line), re-run steps 3–4, say so in the report. Any other failure: stop and report.

- [ ] **Step 5: Never published.**

  ```bash
  printf 'allprojects { apply(plugin = "maven-publish") }\n' > "$SCRATCH/apply-maven-publish.init.gradle.kts"
  cd /Users/jorge/IdeaProjects/full-sample/server
  ./gradlew help -I "$SCRATCH/apply-maven-publish.init.gradle.kts" --no-daemon 2>&1 | command grep -c 'sample apps are never published'
  ```

  Expected: at least `1`.

- [ ] **Step 6: Opt-out and credentials warning (Review Focus 2).**

  ```bash
  cd /Users/jorge/IdeaProjects/full-sample/server
  command grep -c '^gpr\.\(user\|key\)=' ~/.gradle/gradle.properties 2>/dev/null
  env -u GITHUB_ACTOR -u GITHUB_TOKEN ./gradlew help -Pwasichai.local=false --no-daemon 2>&1 | command grep -c 'wasichai: no GitHub Packages credentials'
  ```

  Expected: `1` when the first command printed `0` or nothing, `0` when it printed `2`; `help` succeeds.

- [ ] **Step 7: Integration tests and route parity.**

  ```bash
  bash "$SCRATCH/tunnel.sh"
  ( set -a; source /Users/jorge/IdeaProjects/wasichai/wasichai-integration-tests/it-env.sh; set +a
    export WASICHAI_TEST_GIS_DB_PORT=5442
    cd /Users/jorge/IdeaProjects/full-sample/server && ./gradlew integrationTest --rerun --no-daemon )
  count_tests /Users/jorge/IdeaProjects/full-sample/server integrationTest   # COUNT helper
  command grep -ho 'route parity: [0-9]* live routes compared' /Users/jorge/IdeaProjects/full-sample/server/build/test-results/integrationTest/*.xml
  ```

  Expected: `5 tests, 0 failures, 0 errors`; `route parity: 95 live routes compared`.

- [ ] **Step 8: Web package files.** Run S6. Replace `web/vite.config.ts` with S7 (values above), `web/src/index.css`
  with S10, write `web/.npmrc` from S9. Replace `web/playwright.config.ts` with:

  ```ts
  import { defineConfig, devices } from '@playwright/test'

  // the full-sample smoke: this repository's server jar (cd ../server && ./gradlew bootJar) on a real PostGIS
  // database, and the vite dev server in front of it. WASICHAI_DB_PORT (and usually WASICHAI_DB_NAME) pick the
  // database, exactly as for bootRun.
  export default defineConfig({
    testDir: './e2e',
    timeout: 120_000,
    expect: { timeout: 15_000 },
    workers: 1,
    retries: 0,
    reporter: 'list',
    use: { baseURL: 'http://localhost:5174', trace: 'retain-on-failure' },
    projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
    webServer: [
      {
        // playwright runs it from this file's directory
        command: 'java -jar ../server/build/libs/app.jar',
        url: 'http://localhost:8093/actuator/health',
        timeout: 180_000,
        reuseExistingServer: !process.env.CI,
        // no assistant in the smoke, whatever the shell has
        env: { ANTHROPIC_API_KEY: '' }
      },
      {
        command: 'yarn dev',
        url: 'http://localhost:5174',
        timeout: 60_000,
        reuseExistingServer: !process.env.CI
      }
    ]
  })
  ```

- [ ] **Step 9: Failing test for the link script.** Write `web/scripts/local-packages.test.mjs` from S8 (test part).

  ```bash
  cd /Users/jorge/IdeaProjects/full-sample/web && node --test scripts/ 2>&1 | tail -5
  ```

  Expected: FAIL, `ERR_MODULE_NOT_FOUND` for `local-packages.mjs`.

- [ ] **Step 10: The link script.** Write `web/scripts/local-packages.mjs` from S8 (implementation part);
  `chmod +x web/scripts/local-packages.mjs`.

  ```bash
  cd /Users/jorge/IdeaProjects/full-sample/web && node --test scripts/ 2>&1 | command grep -E '^# (pass|fail)'
  ```

  Expected: `# pass 3`, `# fail 0`.

- [ ] **Step 11: Partial lockfile.** Run S16 with `<S>` = `full-sample`. Expected as stated there.

- [ ] **Step 12: Link, format, verify the web (Review Focus 3 and 4).**

  ```bash
  cd /Users/jorge/IdeaProjects/full-sample/web
  shasum package.json yarn.lock > "$SCRATCH/full-sample-web.sha"
  yarn link:local
  shasum -c "$SCRATCH/full-sample-web.sha"
  ls -l node_modules/@wasichai | command grep -c -- '-> /Users/jorge/IdeaProjects/wasichai-ui/packages/'
  node_modules/.bin/prettier --write package.json vite.config.ts playwright.config.ts src/index.css scripts
  yarn lint && yarn typecheck && yarn test && yarn test:scripts && yarn build
  grep -l '\.flex{' dist/assets/*.css
  WASICHAI_LOCAL=false yarn test
  ```

  Expected: `link:local: 11 @wasichai packages -> /Users/jorge/IdeaProjects/wasichai-ui/packages`; both `OK`; `11`;
  lint, typecheck (it includes `e2e/` and `playwright.config.ts`), test (`2 passed`), test:scripts (`# pass 3`),
  build succeed; one css path; `2 passed` again. Two `@types/react` copies, or "Invalid hook call": stop and report.

- [ ] **Step 13: Playwright e2e, server and web of this repository, web linked (Review Focus 3).** Ports 8093 and
  5174 must be free (`lsof -nP -iTCP:8093 -iTCP:5174 -sTCP:LISTEN` prints nothing; otherwise stop and report: a
  running server would be reused).

  ```bash
  bash "$SCRATCH/tunnel.sh"
  cd /Users/jorge/IdeaProjects/full-sample/web && npx playwright install chromium
  ( set -a; source /Users/jorge/IdeaProjects/wasichai/wasichai-integration-tests/it-env.sh; set +a
    cd /Users/jorge/IdeaProjects/full-sample/web
    WASICHAI_DB_HOST=localhost WASICHAI_DB_PORT=5442 WASICHAI_DB_NAME=wasichai_full \
    WASICHAI_DB_USERNAME="$WASICHAI_TEST_DB_USERNAME" WASICHAI_DB_PASSWORD="$WASICHAI_TEST_DB_PASSWORD" yarn e2e )
  ```

  Expected: `1 passed`. A failure: keep `web/test-results/`, report the first error line; do not retry more than
  once.

- [ ] **Step 14: Repository files.** Write `.gitignore` (S11), `.github/workflows/ci.yml` (S13 with
  `<IT_IMAGE>` = `postgis/postgis:18-3.6`, plus this job appended at the end of `jobs:`):

  ```yaml
    e2e:
      name: End-to-end (Playwright)
      runs-on: ubuntu-latest
      # a hung gradle, jar start or browser must not burn the default 6 h
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
        GITHUB_ACTOR: ${{ github.actor }}
        GITHUB_TOKEN: ${{ secrets.WASICHAI_PACKAGES_TOKEN || secrets.GITHUB_TOKEN }}
      steps:
        - uses: actions/checkout@v4
        - uses: actions/setup-java@v4
          with:
            distribution: temurin
            java-version: '25'
        - uses: gradle/actions/setup-gradle@v4
        # playwright.config.ts starts ../server/build/libs/app.jar
        - name: Server jar
          working-directory: server
          run: ./gradlew bootJar --no-daemon
        - uses: actions/setup-node@v4
          with:
            node-version: '26'
            cache: yarn
            cache-dependency-path: web/yarn.lock
        # web/.npmrc names the registry; the token stays out of the repository
        - name: Registry token
          run: echo "//npm.pkg.github.com/:_authToken=${GITHUB_TOKEN}" >> ~/.npmrc
        - name: Install
          working-directory: web
          run: yarn install --frozen-lockfile
        - name: Headless browser
          working-directory: web
          run: npx playwright install --with-deps chromium
        - name: Playwright smoke
          working-directory: web
          run: yarn e2e
        - name: Upload playwright traces
          if: failure()
          uses: actions/upload-artifact@v4
          with:
            name: e2e-test-results
            path: web/test-results/
  ```

  `CLAUDE.md` (S12: `<ONE_LINE>` = `wasichai with every module, on PostGIS: the original app assembled from the
  libraries`, `<EXTRA_COMMANDS>` = a blank line, then these two lines:
  `` The Playwright smoke: `(cd server && ./gradlew bootJar)`, then `WASICHAI_DB_PORT=<postgis port> WASICHAI_DB_NAME=wasichai_full yarn e2e` in `web/`. ``
  and `` The route-parity snapshot: `server/src/test/resources/route-parity/generate-expected.sh` (needs the original app's sources, `SAPGIS_SRC`). ``)
  and `README.md` (S14) with:
  - `<INTRO>`:
    ```
    wasichai with **every module**: core, views, forms, pages, workflow, automation, documents, gis and the
    assistant (agent), on PostGIS. The same app as the
    [original](https://github.com/wasichai/wasichai/blob/main/docs/sapgis-origin.md), assembled from the libraries.
    ```
  - `<SERVER_DEPS>` = `` `wasichai-bom`, `wasichai-spring-boot-starter` and the 8 `-<module>` starters ``;
    `<WEB_DEPS>` = `` `@wasichai/core`, `@wasichai/ui` and all eight module packages ``; `<REQUIREMENT_DB>` =
    `PostgreSQL 18 with PostGIS 3.6, with a database of its own for this sample. GeoServer is optional.`
  - `<RUN_SECTION>`:

    ~~~markdown
    ```bash
    # 1. PostGIS, and this sample's own database in it (once). with wasichai-infrastructure
    #    (https://github.com/wasichai/wasichai-infrastructure) next to this repository, on a free port
    #    (GeoServer is optional, see "GeoServer" below):
    (cd ../wasichai-infrastructure && WASICHAI_PG_PORT=5434 docker compose -f docker/compose.yml up -d postgres)
    docker exec wasichai-postgres createdb -U wasichai wasichai_full

    # 2. the server. WASICHAI_DB_PORT has no default: say where the database is.
    #    ANTHROPIC_API_KEY switches the assistant on.
    (cd server && WASICHAI_DB_PORT=5434 ./gradlew bootRun)

    # 3. the web, in another shell: http://localhost:5174
    (cd web && yarn install && yarn dev)
    ```

    | Variable | Default | |
    |---|---|---|
    | `WASICHAI_DB_HOST` / `WASICHAI_DB_PORT` / `WASICHAI_DB_NAME` | `localhost` / **required** / `wasichai_full` | a PostGIS database |
    | `WASICHAI_DB_USERNAME` / `WASICHAI_DB_PASSWORD` | `wasichai` / `wasichai` | |
    | `WASICHAI_SEED_DEV` | `true` | seeds the admin user; turn off outside a demo |
    | `WASICHAI_JWT_SECRET` | a sample-only value | set your own (>= 32 bytes) anywhere real |
    | `WASICHAI_AUTOMATION_POLL` | `1s` | automation queue drain; `0s` turns it off |
    | `WASICHAI_GEOSERVER_ENABLED` / `WASICHAI_GEOSERVER_URL` | `false` / `http://localhost:8081/geoserver` | WMS publishing (optional) |
    | `ANTHROPIC_API_KEY` | – | the assistant; without it the rest works and the assistant says it is not configured |

    Through an ssh tunnel: `WASICHAI_DB_PORT=5442 ./gradlew bootRun` in `server/`.
    ~~~

    followed by the two common paragraphs of S14.
  - `<SAMPLE_SECTIONS>`:

    ~~~markdown
    ## GeoServer (optional)

    ```bash
    (cd ../wasichai-infrastructure && WASICHAI_PG_PORT=5434 docker compose -f docker/compose.yml --profile gis up -d)
    (cd server && WASICHAI_DB_PORT=5434 WASICHAI_GEOSERVER_ENABLED=true WASICHAI_GIS_GEOSERVER_DATASTORE_HOST=postgres \
      WASICHAI_GIS_GEOSERVER_DATASTORE_DATABASE=wasichai_full ./gradlew bootRun)
    ```

    GeoServer reads the database on its own, through the datastore settings `wasichai.gis.geoserver.datastore.host` /
    `.port` / `.database` (env `WASICHAI_GIS_GEOSERVER_DATASTORE_HOST` / `_PORT` / `_DATABASE`, defaults `localhost` /
    `5432` / `wasichai`). They must name the database the server uses, as GeoServer sees it: on
    wasichai-infrastructure's compose network that is host `postgres`, port 5432 (the container's own port, whatever
    `WASICHAI_PG_PORT` is) and this sample's `wasichai_full`, as the command above sets. Otherwise GeoServer reads
    another, empty database and the published layers show no features.

    ## Smoke test (Playwright, headless)

    ```bash
    (cd server && ./gradlew bootJar)
    cd web && npx playwright install chromium   # once
    WASICHAI_DB_PORT=<postgis port> WASICHAI_DB_NAME=wasichai_full yarn e2e
    ```

    Playwright starts `../server/build/libs/app.jar` and the Vite dev server itself. Locally it reuses a server
    already listening on 8093 or 5174, so make sure nothing else is. CI (`e2e` job) runs it against a PostGIS service
    database on port 5433.

    ## Route parity

    `FullSampleRouteParityTest` compares this app's routes with a snapshot of the original app's,
    `server/src/test/resources/route-parity/legacy-routes.txt` (95 routes). `generate-expected.sh` next to it
    rebuilds the snapshot from the original's controllers (`SAPGIS_SRC` points at them); every difference needs an
    [ADR-031](https://github.com/wasichai/wasichai/blob/main/docs/adr/0031-deliberate-deviations-from-sapgis.md) entry.
    ~~~

    then the `## Manual checklist (the original app's flows)` section of the old README copied verbatim (source:
    `/Users/jorge/IdeaProjects/wasichai/examples/full-sample/README.md`, from that heading up to the line before
    `## Tests`).
  - `<IT_DATABASE>` = `Testcontainers postgis/postgis:18-3.6`; `<IT_GIS_NOTE>` =
    `, plus WASICHAI_TEST_GIS_DB_PORT, the port of a PostGIS server (name, user and password are shared)`;
    `<CI_EXTRA>` = `, and runs the Playwright smoke`; `<EXTRA_TESTS>` empty.

- [ ] **Step 15: Checks.**

  ```bash
  cd /Users/jorge/IdeaProjects/full-sample
  # MD check from Global Constraints with FILES="README.md CLAUDE.md"
  actionlint .github/workflows/ci.yml && echo actionlint ok
  web/node_modules/.bin/prettier --check .github/workflows/ci.yml web/package.json web/vite.config.ts web/playwright.config.ts web/src/index.css web/scripts
  command grep -rnE 'infra/docker|examples/|wasichai-ui/tree|WASICHAI_BACKEND_DIR|WASICHAI_REPO_TOKEN|:full-sample-server' --exclude-dir=node_modules --exclude-dir=build --exclude-dir=dist --exclude-dir=.gradle --exclude-dir=.git --exclude-dir=test-results .
  ```

  Expected: MD check silent, `actionlint ok`, prettier clean, grep silent.

- [ ] **Step 16: Report (no commit).** Counts (build, ITs 5, route parity 95, vitest 2 twice, test:scripts 3, e2e 1),
  whether the S1 fallback was needed, and the line "A4 copied + web verified + e2e passed" (for G1).

## Task A5: wasichai-infrastructure repository

**Files (all under `/Users/jorge/IdeaProjects/wasichai-infrastructure`):**
- Create: `docker/compose.yml` (copied from wasichai `infra/docker/compose.yml`, comments adjusted)
- Create: `docker/postgres/Dockerfile`, `docker/postgres/init/01-extensions.sql` (copied, unchanged)
- Create: `.local/secrets.env` (copied with `cp`, never read), `.gitignore`, `.editorconfig`
- Modify: `README.md` (replaced)

**Interfaces:**
- Consumes: wasichai `infra/**` (read-only).
- Produces: service names `postgres` (PostGIS 3.6 + pgvector, host port `${WASICHAI_PG_PORT:-5432}`, container name
  `wasichai-postgres`), `geoserver` (profile `gis`, 8081, container `wasichai-geoserver`), `postgres-plain`
  (profile `core`, 5433, container `wasichai-postgres-plain`); user/password/database `wasichai`. The sample READMEs
  (A1–A4) and B1's docs name exactly these.

Never run `docker compose up` here (Global Constraints: port 5432).

- [ ] **Step 1: Copy.**

  ```bash
  I=/Users/jorge/IdeaProjects/wasichai-infrastructure
  mkdir -p "$I/docker" "$I/.local"
  rsync -a /Users/jorge/IdeaProjects/wasichai/infra/docker/ "$I/docker/"
  cp /Users/jorge/IdeaProjects/wasichai/infra/.local/secrets.env "$I/.local/secrets.env"
  cp /Users/jorge/IdeaProjects/wasichai/.editorconfig "$I/.editorconfig"
  cmp -s /Users/jorge/IdeaProjects/wasichai/infra/.local/secrets.env "$I/.local/secrets.env" && echo secrets same
  find "$I" -type f -not -path '*/.git/*' | sed "s#^$I/##" | sort
  ```

  Expected: `secrets same`; the list `.editorconfig`, `.local/secrets.env`, `README.md`, `docker/compose.yml`,
  `docker/postgres/Dockerfile`, `docker/postgres/init/01-extensions.sql`.

- [ ] **Step 2: `.gitignore` first, then prove the secret is ignored.** Write `.gitignore`:

  ```gitignore
  # local secrets (tokens, api keys): never committed
  .local/

  # IDE / OS
  .idea/
  *.iml
  .vscode/
  .DS_Store

  # local harness settings
  .claude/
  .superpowers/
  ```

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai-infrastructure
  git check-ignore -v .local/secrets.env
  git status --short --untracked-files=all | command grep -c 'secrets'
  ```

  Expected: `.gitignore:2:.local/	.local/secrets.env`, then `0`.

- [ ] **Step 3: compose comments.** In `docker/compose.yml` the content stays as copied (services, profiles, the
  `${WASICHAI_PG_PORT:-5432}` host port, volumes). Add one comment line above `services:`:

  ```yaml
  # local databases for the wasichai samples: `postgres` (PostGIS + pgvector) by default, `geoserver` with
  # --profile gis, `postgres-plain` (no PostGIS, 5433) with --profile core. WASICHAI_PG_PORT moves postgres off 5432.
  ```

  and replace the geoserver comment `# different origin than the vite dev server. local development only; k3s fronts both.`
  with `# different origin than the vite dev server. local development only; a deployment fronts both.`

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai-infrastructure
  docker compose -f docker/compose.yml config -q && echo compose ok
  docker compose -f docker/compose.yml --profile gis --profile core config --services | sort
  WASICHAI_PG_PORT=5434 docker compose -f docker/compose.yml config | command grep -c '5434'
  ```

  Expected: `compose ok`; `geoserver`, `postgres`, `postgres-plain`; at least `1`. `docker compose config` never
  contacts a daemon. If the `docker` CLI or its compose plugin is missing, skip these three and report it. The file
  was never prettier-formatted in wasichai (`infra/` was prettier-ignored) and stays hand-formatted.

- [ ] **Step 4: README.** Replace `README.md` with:

  ~~~markdown
  # wasichai-infrastructure

  Local infrastructure for [wasichai](https://github.com/wasichai/wasichai) apps and its sample repositories
  ([simple-sample](https://github.com/wasichai/simple-sample), [documents-sample](https://github.com/wasichai/documents-sample),
  [gis-sample](https://github.com/wasichai/gis-sample), [full-sample](https://github.com/wasichai/full-sample)):
  PostgreSQL 18 with PostGIS 3.6 and pgvector, GeoServer, and a plain PostgreSQL. The wasichai libraries do not
  depend on this repository; it is one ready-made way to get what they need on a laptop.

  | Service | Profile | Image | Host port | Container |
  |---|---|---|---|---|
  | `postgres` | (default) | built from `docker/postgres` (`postgis/postgis:18-3.6` + pgvector) | `${WASICHAI_PG_PORT:-5432}` | `wasichai-postgres` |
  | `geoserver` | `gis` | `docker.osgeo.org/geoserver:3.0.1`, admin `admin` / `geoserver` | 8081 | `wasichai-geoserver` |
  | `postgres-plain` | `core` | `postgres:18`, no PostGIS | 5433 | `wasichai-postgres-plain` |

  User, password and default database are `wasichai` everywhere. The `postgres` image creates the extensions
  `postgis`, `pgcrypto` and `vector` and the schemas `wasichai` and `app_data` on its first start
  (`docker/postgres/init/01-extensions.sql`, baked into the image so a remote docker daemon behaves the same).

  ## Use

  ```bash
  WASICHAI_PG_PORT=5434 docker compose -f docker/compose.yml up -d                 # postgres
  WASICHAI_PG_PORT=5434 docker compose -f docker/compose.yml --profile gis up -d   # + geoserver
  docker compose -f docker/compose.yml --profile core up -d postgres-plain         # plain postgres on 5433
  ```

  Pick a free host port with `WASICHAI_PG_PORT`: 5432 is often another PostgreSQL's, and no sample defaults to it.
  Give the app the same port as `WASICHAI_DB_PORT`.

  ## What the samples expect

  | Sample | Database | Create it (once) | Server env |
  |---|---|---|---|
  | simple-sample | `wasichai` on `postgres-plain` | nothing to do | none (it defaults to 5433) |
  | documents-sample | `wasichai_documents` on `postgres` | `docker exec wasichai-postgres createdb -U wasichai wasichai_documents` | `WASICHAI_DB_PORT=<WASICHAI_PG_PORT>` |
  | gis-sample | `wasichai_gis` on `postgres` | `docker exec wasichai-postgres createdb -U wasichai wasichai_gis` | `WASICHAI_DB_PORT=<WASICHAI_PG_PORT>` |
  | full-sample | `wasichai_full` on `postgres` | `docker exec wasichai-postgres createdb -U wasichai wasichai_full` | `WASICHAI_DB_PORT=<WASICHAI_PG_PORT>` |

  One database per sample: the samples install different modules, and one cannot read the field types another left
  in a shared database.

  GeoServer reaches PostgreSQL over the compose network, as host `postgres`, port 5432 (the container's port,
  whatever `WASICHAI_PG_PORT` is). An app that publishes layers to it sets
  `WASICHAI_GEOSERVER_ENABLED=true`, `WASICHAI_GIS_GEOSERVER_DATASTORE_HOST=postgres` and
  `WASICHAI_GIS_GEOSERVER_DATASTORE_DATABASE=<its database>`; wasichai's defaults (`localhost`, `wasichai`) do not
  know about this network. CORS is on for local development, because the layers screen previews WMS tiles straight
  from GeoServer.

  ## Local secrets

  `.local/` is git-ignored and holds machine-local secrets (`.local/secrets.env`: tokens used to set up the
  repositories' secrets, and API keys such as `ANTHROPIC_API_KEY` for the assistant). Load them into a shell with
  `set -a; source .local/secrets.env; set +a`. Never commit anything under `.local/`.
  ~~~

- [ ] **Step 5: Checks.**

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai-infrastructure
  # MD check from Global Constraints with FILES="README.md"
  command grep -rnE 'infra/|examples/|k3s' --exclude-dir=.git --exclude-dir=.local .
  ```

  Expected: both silent.

- [ ] **Step 6: Report (no commit).** The file list, `secrets same`, the compose checks, and that
  `.local/secrets.env` is ignored.

## Task A6: wasichai-ui cleanup

**Files (all under `/Users/jorge/IdeaProjects/wasichai-ui`):**
- Delete: `examples/` (phase 2)
- Modify: `package.json` (workspaces), `yarn.lock` (phase 2, by `yarn install`)
- Modify: `tooling/check-release.mjs`, `tooling/check-release.test.mjs`, `tooling/run-ordered.test.mjs`
- Modify: `.github/workflows/ci.yml`, `README.md`, `CLAUDE.md`, `docs/README.md`

**Interfaces:**
- Consumes: nothing from other tasks; phase 2 waits for G1.
- Produces: `checkReleaseConfig(repoRoot, expected)` unchanged in signature, now checking only `packages/`
  (the public set and release-please); workspaces `["packages/*"]`; CI jobs `frontend` and `release-guard` only.

**Phase 1 (parallel with A1–A5; no `yarn install`, no deletion)**

- [ ] **Step 1: Tests first: the fixtures stop having sample webs.** In `tooling/check-release.test.mjs`:
  - first line becomes `// fixture repo on disk: two public packages, one private, a release-please config.`
  - in `fixture()`, the root manifest becomes
    `write(root, 'package.json', { name: 'root', private: true, workspaces: ['packages/*'] })` and the line
    `write(root, 'examples/one/web/package.json', { name: 'one-web', version: '0.1.0', private: true })` is deleted;
  - the whole test `a sample web that is not private is reported` (from `test('a sample web` through its closing
    `})`) is deleted.

  In `tooling/run-ordered.test.mjs` the fixture keeps a second, non-package workspace glob, renamed:
  `'examples/simple-sample/web'` → `'apps/demo'`, `'simple-sample-web'` → `'demo-app'`,
  `'examples/gis-sample/web'` → `'apps/empty'`, every `['packages/*', 'examples/*/web']` → `['packages/*', 'apps/*']`,
  and the expected list becomes `['apps/demo', 'packages/core', 'packages/testing', 'packages/ui']`.

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai-ui && yarn test:tooling 2>&1 | command grep -E '^# (tests|pass|fail)'
  ```

  Expected: `# tests 21`, `# pass 21`, `# fail 0` (the check-release code still has the workspace block, which now
  finds nothing to report; the deleted test is the only count change).

- [ ] **Step 2: Remove the sample-web check from `check-release.mjs`.** Delete the import line
  `import { expandWorkspaces } from './run-ordered.mjs'` and, at the end of `checkReleaseConfig`, the block from
  `// workspaces outside packages are apps (sample webs): never published` through the closing `}` of its `for`
  loop, so the function ends with `return problems` right after the release-please loop.

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai-ui
  command grep -nE 'expandWorkspaces|workspace app|sample' tooling/check-release.mjs
  yarn test:tooling 2>&1 | command grep -E '^# (tests|pass|fail)'
  ```

  Expected: grep silent; `# tests 21`, `# pass 21`, `# fail 0`.

- [ ] **Step 3: Workspaces.** In `package.json` set `"workspaces": ["packages/*"]` (4-space JSON, as the file is).

- [ ] **Step 4: CI.** In `.github/workflows/ci.yml` delete the `sample-webs:` job and the `e2e:` job entirely, and
  change the comment above the frontend job's last step from `# every package, then every sample web against the
  packages' dist` to `# every package, in dependency order`. `jobs:` keeps `frontend` and `release-guard`.

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai-ui
  actionlint .github/workflows/*.yml && echo actionlint ok
  command grep -nE 'sample|e2e|WASICHAI_REPO_TOKEN|examples|playwright' .github/workflows/*.yml
  ```

  Expected: `actionlint ok`; grep silent.

- [ ] **Step 5: Docs.** `README.md`: in the Layout block delete the line
  `examples/   the sample webs (examples/<sample>/web); their servers are in wasichai`, and replace the line
  `Development, the sample webs, the cross-repository e2e and releasing: [docs/README.md](docs/README.md).` with:

  ```markdown
  Development and releasing: [docs/README.md](docs/README.md). Sample apps, each a repository with its server and
  its web: [simple-sample](https://github.com/wasichai/simple-sample),
  [documents-sample](https://github.com/wasichai/documents-sample), [gis-sample](https://github.com/wasichai/gis-sample)
  and [full-sample](https://github.com/wasichai/full-sample).
  ```

  `CLAUDE.md`: the stack line `- Node 26; vitest + testing-library; Playwright for the full-sample e2e` becomes
  `- Node 26; vitest + testing-library`; delete the command line
  `yarn workspace full-sample-web e2e                  # needs the server jar built in ../wasichai (docs/README.md)`;
  append to the first paragraph (after `read its \`docs/\` before changing behaviour.`) the sentence
  `` The sample apps are sibling repositories too (`../simple-sample`, `../documents-sample`, `../gis-sample`, `../full-sample`); their webs link this repository's packages with `yarn link:local`. ``
  (wrap at about 115 columns).

  `docs/README.md`:
  - "Requirements and layout": `` `packages/*` and `examples/*/web` are the yarn workspaces. `` →
    `` `packages/*` are the yarn workspaces. ``
  - in the Commands block: `# vitest in every package and sample web` → `# vitest in every package`, and
    `# every package, then every sample web against the packages' dist` → `# every package, in dependency order`.
  - replace the two sections `## Sample webs and the backend` and `## The cross-repository e2e` (from the first
    heading up to the line before `## Releasing`) with:

    ```markdown
    ## Samples

    The sample apps are repositories of their own, each with its server and its web:
    [simple-sample](https://github.com/wasichai/simple-sample), [documents-sample](https://github.com/wasichai/documents-sample),
    [gis-sample](https://github.com/wasichai/gis-sample) and [full-sample](https://github.com/wasichai/full-sample)
    (which also runs the Playwright e2e). Their webs depend on the published `@wasichai/*` packages. With this
    repository checked out next to one, `yarn build` here and `yarn link:local` in the sample's `web/` link these
    packages instead, and the sample's `yarn test` runs against these sources directly. A change to a package is
    released before a sample's CI sees it.
    ```
  - delete the secrets-table row
    `` | `WASICHAI_REPO_TOKEN` | the `e2e` job's checkout of `wasichai/wasichai` | on `wasichai/wasichai`: Contents read | ``.

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai-ui
  # MD check from Global Constraints with FILES="README.md CLAUDE.md docs/README.md"
  command grep -rnE 'examples|sample web|sample-web|WASICHAI_REPO_TOKEN|WASICHAI_BACKEND_DIR|cross-repository e2e' README.md CLAUDE.md docs/README.md tooling package.json .github
  npx prettier@3.8.2 --check package.json tooling .github
  ```

  Expected: MD check silent; grep silent; prettier clean.

- [ ] **Step 6: Phase 1 report.** "A6 phase 1 done", tooling 21/21.

**Phase 2 (after G1: A1–A4 reported "copied + web verified")**

- [ ] **Step 7: Remove the sample webs and re-install.**

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai-ui
  rm -rf examples
  yarn install
  command grep -cE '^"?(@playwright/test|playwright)@' yarn.lock
  yarn workspaces info | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s.slice(s.indexOf("{"),s.lastIndexOf("}")+1));console.log(Object.keys(j).length, Object.values(j).every(w=>w.location.startsWith("packages/")))})'
  ```

  Expected: `yarn install` succeeds and rewrites `yarn.lock`; `0` (Playwright was only a sample dependency);
  `12 true` (the eleven public packages and the private `smoke`, all under `packages/`).

- [ ] **Step 8: Full verification.**

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai-ui
  yarn install --frozen-lockfile && yarn format:check && yarn test:tooling && yarn lint && yarn test && yarn build
  node tooling/check-release.mjs --pack 0.0.0-local
  command grep -rnE 'examples/' --exclude-dir=node_modules --exclude-dir=dist --exclude-dir=.git --exclude-dir=.idea --exclude-dir=.superpowers --exclude=yarn.lock .
  ```

  Expected: every command green; the vitest summaries add up to 669 package tests (sum the `Tests  N passed` lines of
  `yarn test`); tooling `# pass 21`; `check-release: ok`; grep silent. `yarn build` now builds packages only.

- [ ] **Step 9: Report (no commit).** Counts (669, 21, `check-release: ok`), the yarn.lock change (lines removed),
  and "A6 done".

## Task B1: wasichai cleanup

**Files (all under `/Users/jorge/IdeaProjects/wasichai`):**
- Delete: `examples/`, `infra/`, `build-logic/src/main/kotlin/wasichai.sample-app.gradle.kts`
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`, `build-logic/build.gradle.kts`,
  `build-logic/src/test/kotlin/wasichai/buildlogic/ConventionPluginsTest.kt`
- Modify: `wasichai-gis/src/main/kotlin/wasichai/gis/GeoServerProperties.kt`
- Test: `wasichai-gis/src/test/kotlin/wasichai/gis/WasichaiGisAutoConfigurationTest.kt`
- Modify: `.github/workflows/ci.yml`, `.github/scripts/check-maven-publications.sh` (comment), `.gitignore`,
  `.prettierignore`
- Modify: `README.md`, `CLAUDE.md`, `docs/development/getting-started.md`, `docs/guides/build-your-app.md`,
  `docs/modules/gis.md`, `docs/gis/geometry.md`, `docs/chawpi-origin.md`, `docs/adr/0031-deliberate-deviations-from-sapgis.md`,
  `docs/adr/README.md`, `docs/HISTORY.md`
- Create: `docs/adr/0033-samples-and-infrastructure-in-their-own-repositories.md`

**Interfaces:**
- Consumes: A1–A5 done (copies exist; step 1 re-checks).
- Produces: `GeoServerDataStoreProperties.host` default `"localhost"`; ADR-033 at the path above (A1–A4 READMEs and
  ADR-031 D17 link wasichai docs by GitHub URL; D17 links ADR-033 relatively); a `wasichai` build with no sample
  projects and no `wasichai.sample-app` plugin.

- [ ] **Step 1: Every copy exists before anything is deleted.**

  ```bash
  P=/Users/jorge/IdeaProjects
  for s in simple documents gis full; do
    test -f $P/$s-sample/server/build.gradle.kts && test -f $P/$s-sample/server/settings.gradle.kts && test -f $P/$s-sample/web/package.json && echo "$s-sample ok"
  done
  test -f $P/gis-sample/perene/model.json && echo perene ok
  test -f $P/full-sample/server/src/test/resources/route-parity/legacy-routes.txt && test -f $P/full-sample/web/e2e/smoke.spec.ts && echo full extras ok
  test -f $P/wasichai-infrastructure/docker/compose.yml && test -f $P/wasichai-infrastructure/docker/postgres/Dockerfile && echo infra ok
  cmp -s $P/wasichai/infra/.local/secrets.env $P/wasichai-infrastructure/.local/secrets.env && echo secrets same
  ```

  Expected: seven `ok` lines and `secrets same`. Anything missing: stop and report (Wave A is not done).

- [ ] **Step 2: Failing test for the GeoServer datastore default.** Append to the class in
  `wasichai-gis/src/test/kotlin/wasichai/gis/WasichaiGisAutoConfigurationTest.kt` (before its closing brace):

  ```kotlin
      // infra-agnostic (ADR-031 D17): geoserver reaches postgres at localhost unless the app says otherwise.
      // a container network (compose's "postgres") is the app's setting, not the library's default
      @Test
      fun `the geoserver datastore defaults name no container network`() {
          runner.run { context ->
              val datastore = context.getBean(GeoServerProperties::class.java).datastore
              assertThat(datastore.host).isEqualTo("localhost")
              assertThat(datastore.port).isEqualTo(5432)
              assertThat(datastore.database).isEqualTo("wasichai")
          }
      }

      @Test
      fun `a container network sets the datastore host`() {
          runner.withPropertyValues("wasichai.gis.geoserver.datastore.host=postgres").run { context ->
              assertThat(context.getBean(GeoServerProperties::class.java).datastore.host).isEqualTo("postgres")
          }
      }
  ```

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai
  ./gradlew :wasichai-gis:test --tests 'wasichai.gis.WasichaiGisAutoConfigurationTest' --no-daemon 2>&1 | command grep -E 'FAILED|expected|tests completed'
  ```

  Expected: `the geoserver datastore defaults name no container network FAILED`, with `expected: "localhost"` /
  `but was: "postgres"`; the other test passes.

- [ ] **Step 3: The default.** In `wasichai-gis/src/main/kotlin/wasichai/gis/GeoServerProperties.kt` replace

  ```kotlin
  // how geoserver itself reaches postgres. not how wasichai reaches it: geoserver lives in another container.
  data class GeoServerDataStoreProperties(
      val name: String = "wasichai-postgis",
      val host: String = "postgres",
  ```

  with

  ```kotlin
  // how geoserver itself reaches postgres, not how wasichai does. localhost assumes no container network; an app
  // whose geoserver runs on one names the database's host there (ADR-031 D17)
  data class GeoServerDataStoreProperties(
      val name: String = "wasichai-postgis",
      val host: String = "localhost",
  ```

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai
  ./gradlew :wasichai-gis:test --no-daemon 2>&1 | tail -3
  command grep -rn '"postgres"' wasichai-*/src --include='*.kt'
  ```

  Expected: `BUILD SUCCESSFUL`; the grep lists only `GeoServerPayloadsTest.kt` (an explicit value, lines 11 and 44),
  `WasichaiTestDatabase.kt` (the Testcontainers image name) and the new test.

- [ ] **Step 4: No more sample projects.** Replace `settings.gradle.kts` with:

  ```kotlin
  pluginManagement {
      includeBuild("build-logic")
  }

  rootProject.name = "wasichai"

  dependencyResolutionManagement {
      repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
      repositories {
          mavenCentral()
      }
  }

  // a folder is a module when it has a build file. no list to keep in sync.
  fun includeModules(dirs: List<File>) {
      dirs
          .filter { it.isDirectory && File(it, "build.gradle.kts").isFile }
          .sortedBy { it.name }
          .forEach { dir ->
              include(dir.name)
              project(":${dir.name}").projectDir = dir
          }
  }

  fun children(parent: File): List<File> = parent.listFiles()?.toList() ?: emptyList()

  // libraries live at the repo root as wasichai-*; build-logic is an included build, not a module.
  // the sample apps are repositories of their own (ADR-033)
  includeModules(children(rootDir).filter { it.name.startsWith("wasichai-") })
  includeModules(children(file("starters")))
  ```

  Then `rm -rf examples infra`.

- [ ] **Step 5: No more sample convention.** `rm build-logic/src/main/kotlin/wasichai.sample-app.gradle.kts`. In
  `build-logic/build.gradle.kts` delete the two lines
  `// wasichai.sample-app: the example apps are spring boot applications` and
  `implementation(libs.spring.boot.gradle.plugin)`. In `gradle/libs.versions.toml` delete the library line
  `spring-boot-gradle-plugin = { module = "org.springframework.boot:spring-boot-gradle-plugin", version.ref = "springBoot" }`
  and the plugin line `spring-boot = { id = "org.springframework.boot", version.ref = "springBoot" }` (nothing else
  uses either: `command grep -rn 'spring.boot.gradle.plugin\|plugins.spring.boot\|org.springframework.boot"' --include='*.kts' .`
  prints nothing afterwards). In `ConventionPluginsTest.kt` delete the three tests
  `sample app builds one boot jar named app jar`, `sample app refuses to be published` and
  `sample app build succeeds with only integration-tagged tests` (each from its `@Test` line through its closing
  `}`), leaving the class's closing brace.

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai
  ./gradlew -p build-logic ktlintFormat test --no-daemon 2>&1 | tail -2
  count_tests build-logic test   # COUNT helper
  command grep -rn 'sample' build-logic/src settings.gradle.kts gradle/libs.versions.toml build-logic/build.gradle.kts
  ```

  Expected: `BUILD SUCCESSFUL`; `9 tests, 0 failures, 0 errors`; grep silent.

- [ ] **Step 6: CI and repository files.** In `.github/workflows/ci.yml` delete the jobs `examples-servers:` and
  `perene:` entirely; change the backend comment `# ktlint + unit + architecture tests of every library, starter and
  sample server` to `# ktlint + unit + architecture tests of every library and starter`, and the integration comment
  line `# every library, wasichai-integration-tests and every sample server apply wasichai.integration-test,` to
  `# every library and wasichai-integration-tests apply wasichai.integration-test,`. In
  `.github/scripts/check-maven-publications.sh` change `a sample or the integration tests leaking in fails here too.`
  to `the integration tests leaking in fail here too.` In `.gitignore` delete the line `infra/.local/`. In
  `.prettierignore` delete `infra/`, `# data copied from the original app, kept byte-identical` and
  `examples/gis-sample/perene/`.

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai
  actionlint .github/workflows/*.yml && echo actionlint ok
  command grep -nE 'examples|perene|sample|infra' .github/workflows/*.yml .github/scripts/*.sh .gitignore .prettierignore
  yarn format:check
  ```

  Expected: `actionlint ok`; grep silent; prettier clean.

- [ ] **Step 7: ADR-033.** Create `docs/adr/0033-samples-and-infrastructure-in-their-own-repositories.md`:

  ~~~markdown
  # ADR-033: Samples and infrastructure live in their own repositories; the libraries are infra-agnostic

  **Status**: accepted · 2026-09-26 · supersedes [ADR-009](0009-docker-compose-first.md) for this repository, amends
  [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md)

  ## Context

  After ADR-032 every sample was split across two repositories: its server here (`examples/<sample>/server`), its web
  in wasichai-ui (`examples/<sample>/web`). The full-sample e2e needed a cross-repository checkout with a repository
  secret (`WASICHAI_REPO_TOKEN`), and a sample could not be read, run or cloned as one app. The sample servers built
  from project dependencies, so they never exercised the published coordinates an app actually uses.

  This repository also carried `infra/docker` (a compose file and the PostGIS + pgvector image), and `wasichai-gis`
  defaulted GeoServer's datastore host to `postgres`, the compose service name: libraries meant for any deployment
  assumed one way of running a database.

  ## Decision

  **One repository per sample.** [simple-sample](https://github.com/wasichai/simple-sample),
  [documents-sample](https://github.com/wasichai/documents-sample), [gis-sample](https://github.com/wasichai/gis-sample)
  and [full-sample](https://github.com/wasichai/full-sample) each hold `server/` (its own Gradle build, wrapper and
  version catalog) and `web/` (its own yarn project), a README, and CI. gis-sample carries the Perené model
  (`perene/`); full-sample carries the Playwright e2e, now against a server of the same repository, and the
  route-parity snapshot with its generator. The `wasichai.sample-app` convention plugin is inlined into each
  sample's build (one boot jar `app.jar`, `maven-publish` refused, integration-tagged tests) and removed from
  `build-logic`. Samples are never published.

  **Published by default, local on demand.** A sample's server depends on `platform("wasichai:wasichai-bom:<version>")`
  and the starters from `maven.pkg.github.com/wasichai/wasichai`. When `../wasichai` exists next to the sample, its
  `settings.gradle.kts` includes it as a composite build, and Gradle substitutes every `wasichai:*` module, the BOM
  included, with the checkout's project; `-Pwasichai.local=false` opts out. The web depends on `@wasichai/*`
  `^<version>` from `npm.pkg.github.com`; `yarn link:local` links `../wasichai-ui/packages/*` (their built `dist`),
  Vite dedupes the React singletons, and vitest runs against the sibling sources when they exist.

  **Infrastructure in [wasichai-infrastructure](https://github.com/wasichai/wasichai-infrastructure).** The compose
  file (PostGIS + pgvector by default, GeoServer with the profile `gis`, a plain PostgreSQL with the profile `core`),
  the database image and the git-ignored local secrets live there. This repository has no compose file, Dockerfile
  or deployment assumption; its docs state what an app needs: PostgreSQL 18, PostGIS 3.6 with `wasichai-gis`,
  pgvector optional, GeoServer optional. The Testcontainers images in `wasichai-test` stay: they are a test fixture,
  not infrastructure.

  **Neutral defaults.** `wasichai.gis.geoserver.datastore.host` defaults to `localhost`, like
  `wasichai.database.host` ([ADR-031](0031-deliberate-deviations-from-sapgis.md) D17).

  **What left the two library repositories.** Here: `examples/`, `infra/`, the settings discovery of
  `examples/*/server`, the `wasichai.sample-app` plugin and its tests, and the CI jobs for the sample servers and the
  Perené model. In wasichai-ui: `examples/*/web` (workspaces are `packages/*` only), the sample-web and e2e CI jobs,
  the sample-web check of `check-release.mjs`, and `WASICHAI_REPO_TOKEN`.

  ## Consequences

  - A sample's CI turns green only once wasichai and wasichai-ui have a release on GitHub Packages; until then each
    sample's `web/yarn.lock` pins everything but `@wasichai/*`, and the first `yarn install` after the release
    completes it.
  - Reading the organization's packages from a sample's workflow needs each package to grant that repository access,
    or a classic token with `read:packages` as the sample's `WASICHAI_PACKAGES_TOKEN` secret.
  - A REST change is still backend first (ADR-032). The full-sample e2e is where a mismatch shows, after a release in
    CI, or before one locally through the composite build and `yarn link:local`.
  - This repository's integration run is 394 tests; the 16 sample smoke tests run in the sample repositories.
  - An app whose GeoServer runs on a container network sets the datastore host itself
    (`WASICHAI_GIS_GEOSERVER_DATASTORE_HOST=postgres` with wasichai-infrastructure).
  - Seven repositories instead of two, each with its own CI; the samples have no release process.
  ~~~

  Add to `docs/adr/README.md`, after the ADR-032 line:
  `- [ADR-033: Samples and infrastructure live in their own repositories; the libraries are infra-agnostic](0033-samples-and-infrastructure-in-their-own-repositories.md)`.

- [ ] **Step 8: ADR-031 D17.** In `docs/adr/0031-deliberate-deviations-from-sapgis.md`, insert before the paragraph
  that starts `**Kept on purpose, although they look like candidates.**`:

  ```markdown
  **Because the libraries assume no infrastructure**

  - **D17. GeoServer's datastore host.** Was `postgres`, the name of the original's docker compose service. Now
    `localhost`, like `wasichai.database.host`: nothing in the libraries names a container or a compose service
    ([ADR-033](0033-samples-and-infrastructure-in-their-own-repositories.md)). An app whose GeoServer runs on a
    container network sets `wasichai.gis.geoserver.datastore.host` (`WASICHAI_GIS_GEOSERVER_DATASTORE_HOST`) to the
    database's host there, e.g. `postgres` with wasichai-infrastructure.

  ```

- [ ] **Step 9: Docs.** Exact edits:
  - `README.md`: in the Layout block the `build-logic/` line becomes
    `build-logic/   Gradle convention plugins (wasichai.kotlin-library, .spring-module, .publishing, .integration-test)`;
    delete the `examples/` and `infra/` lines. In the Commands block delete the `docker compose …` line. After the
    Commands block's closing fence insert:

    ```markdown
    An app needs PostgreSQL 18 (with PostGIS 3.6 for `wasichai-gis`; GeoServer is optional); the integration tests
    start their own with Testcontainers. Runnable sample apps, each a repository with its server and its web:
    [simple-sample](https://github.com/wasichai/simple-sample), [documents-sample](https://github.com/wasichai/documents-sample),
    [gis-sample](https://github.com/wasichai/gis-sample), [full-sample](https://github.com/wasichai/full-sample).
    Local databases and GeoServer, if you want them: [wasichai-infrastructure](https://github.com/wasichai/wasichai-infrastructure).
    ```
  - `CLAUDE.md`: append to the first paragraph the sentence
    `` The sample apps are sibling repositories (`../simple-sample`, `../documents-sample`, `../gis-sample`, `../full-sample`); cloned next to this one they build against it (a Gradle composite build). ``
    (wrap at about 115 columns); the stack line `- **Infra**: Docker Compose for local development` becomes
    `` - **Infra**: none in this repository (ADR-033); local databases live in the sibling `wasichai-infrastructure` ``;
    rule 8's end `the\n   UI's e2e job builds this repository's \`main\`.` becomes
    `` the full-sample e2e (repository `full-sample`) is where a mismatch shows. `` (re-wrap the item);
    the working rule `- Port 5432 is never used by a test or a sample default; integration tests use Testcontainers or \`WASICHAI_TEST_DB_*\`.`
    becomes `` - Port 5432 is never used by a test; integration tests use Testcontainers or `WASICHAI_TEST_DB_*`. Nothing here assumes how a database or GeoServer is run (ADR-033). ``
    (wrapped); delete the command line `docker compose -f infra/docker/compose.yml up -d`.
  - `docs/development/getting-started.md`:
    - Requirements: `Java 25, Node 26, Yarn 1, Docker. The Gradle wrapper pins Gradle 9.7.1.` →
      `Java 25; Node 26 and Yarn 1 for the commit hooks and prettier; a docker daemon for the Testcontainers-backed
      integration tests (or an external database, see below). The Gradle wrapper pins Gradle 9.7.1.`
    - Layout: the `- \`examples/\` — …` bullet (three lines, through `[docs/README.md](…)).`) becomes
      ``- The npm packages live in [wasichai-ui](https://github.com/wasichai/wasichai-ui) (see its
      [docs/README.md](https://github.com/wasichai/wasichai-ui/blob/main/docs/README.md)); the sample apps and the
      local infrastructure in repositories of their own (next section).``; delete the `- \`infra/\` — …` bullet.
    - Replace from `## Run a sample app` through the line
      `[../../examples/README.md](../../examples/README.md) for the current samples and how to start each one.` with:

      ```markdown
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
      ```
    - Replace from `API keys live in \`infra/.local/secrets.local.env\`` through
      `Then start the sample server as [../../examples/README.md](../../examples/README.md) describes.` with:

      ```markdown
      API keys (`ANTHROPIC_API_KEY` for the assistant) come from the environment of the process that runs the app,
      never from a file in this repository. wasichai-infrastructure keeps machine-local secrets in a git-ignored
      `.local/` directory for that.
      ```
  - `docs/guides/build-your-app.md`: `[full-sample](../../examples/full-sample/README.md)` →
    `[full-sample](https://github.com/wasichai/full-sample)`; replace the `## Examples` section (to the end of the
    file) with:

    ```markdown
    ## Examples

    Four sample apps, each a repository with its server and its web, built the way this guide describes:

    - [simple-sample](https://github.com/wasichai/simple-sample): core only, on plain PostgreSQL.
    - [documents-sample](https://github.com/wasichai/documents-sample): core plus documents and automation.
    - [gis-sample](https://github.com/wasichai/gis-sample): core plus gis, with the Perené cadastre model.
    - [full-sample](https://github.com/wasichai/full-sample): every module, the app of "A full app" above in working form.
    ```
  - `docs/modules/gis.md`: the two lines starting `Needs a PostgreSQL server with PostGIS: the image` become
    `` Needs PostgreSQL 18 with PostGIS 3.6 (the `postgis` extension; the image `postgis/postgis:18-3.6` has it). GeoServer is optional. ``;
    in the properties table, `` | `wasichai.gis.geoserver.datastore.host` | `postgres` | how GeoServer, in its own container, reaches Postgres | ``
    becomes `` | `wasichai.gis.geoserver.datastore.host` | `localhost` | how GeoServer reaches Postgres; on a container network, the database's host there (e.g. `postgres`) | ``,
    and in the other datastore rows `how GeoServer, in its own container, reaches Postgres` becomes
    `how GeoServer reaches Postgres`. Below the table's `Env form once below the table` line add the paragraph
    `The datastore settings are how GeoServer, not wasichai, reaches the database: when GeoServer runs on a container network, set them to the database as seen from there ([ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D17).`
    (wrapped).
  - `docs/gis/geometry.md`: `` Local development runs GeoServer with CORS on (`CORS_ENABLED` in `infra/docker/compose.yml`), because ``
    → `` A GeoServer used from a local dev server needs CORS on (`CORS_ENABLED` in its container's environment), because ``
    (re-wrap the paragraph).
  - `docs/chawpi-origin.md`: append

    ```markdown

    Later on 2026-09-26 the samples and the local infrastructure moved again, out of both repositories
    ([ADR-033](adr/0033-samples-and-infrastructure-in-their-own-repositories.md)): each `examples/<sample>` (server and
    web) is now the repository `wasichai/<sample>`, `examples/gis-sample/perene` is `gis-sample/perene`, and
    `infra/docker` is `wasichai-infrastructure/docker`.
    ```

- [ ] **Step 10: HISTORY.** In `docs/HISTORY.md`, insert above `## 2026-09-26 — Chawpi becomes wasichai, in two
  repositories`:

  ```markdown
  ## 2026-09-26 — Samples and local infrastructure move to their own repositories

  Each sample is now a repository of its own in the wasichai organization, holding its server and its web together:
  simple-sample, documents-sample, gis-sample (with the Perené model) and full-sample (with the Playwright e2e, now
  against a server of the same repository, and the route-parity snapshot). They build against the published
  libraries by default and pick up sibling wasichai and wasichai-ui checkouts on their own (a Gradle composite build,
  `yarn link:local`). The docker compose setup moved to wasichai-infrastructure. wasichai states what an app needs
  (PostgreSQL 18, PostGIS 3.6 for wasichai-gis, GeoServer optional) and assumes nothing about how it runs: GeoServer's
  datastore host now defaults to `localhost` (ADR-031 D17). The `wasichai.sample-app` convention plugin is gone,
  wasichai-ui is packages and tooling only, and `WASICHAI_REPO_TOKEN` is no longer needed. ADR-033 records the
  decision. wasichai's integration run is 394 tests; the 16 sample smoke tests run in the sample repositories.

  ```

- [ ] **Step 11: Leftovers and Markdown.**

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai
  command grep -rnE 'compose|infra/|docker compose' --exclude-dir=node_modules --exclude-dir=build --exclude-dir=.gradle \
    --exclude-dir=.git --exclude-dir=.idea --exclude-dir=.kotlin --exclude-dir=.superpowers --exclude-dir=superpowers \
    --exclude-dir=adr --exclude=HISTORY.md --exclude=chawpi-origin.md --exclude=sapgis-origin.md . \
    | command grep -v 'WasichaiApplicationTest.kt:.*composed annotation'
  command grep -rnE 'examples/|:[a-z]+-sample-server|sample-app' --exclude-dir=node_modules --exclude-dir=build \
    --exclude-dir=.gradle --exclude-dir=.git --exclude-dir=.idea --exclude-dir=superpowers --exclude-dir=.superpowers \
    --exclude-dir=adr --exclude=HISTORY.md --exclude=chawpi-origin.md --exclude=sapgis-origin.md .
  # MD check with FILES="README.md CLAUDE.md docs/development/getting-started.md docs/guides/build-your-app.md docs/modules/gis.md docs/gis/geometry.md docs/chawpi-origin.md docs/adr/0031-deliberate-deviations-from-sapgis.md docs/adr/0033-samples-and-infrastructure-in-their-own-repositories.md docs/adr/README.md docs/HISTORY.md"
  ```

  Expected: both greps silent (the one legitimate `compose` hit, "a composed annotation" in a Kotlin comment, is
  filtered out by the `grep -v`; `docker daemon` mentions for Testcontainers stay on purpose); MD check silent, except
  lines over 160 columns that `docs/HISTORY.md` already had before this task (report them, do not rewrap history).

- [ ] **Step 12: Build, guard and integration tests.**

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai
  ./gradlew ktlintFormat --no-daemon -q && ./gradlew build --no-daemon 2>&1 | tail -2
  ./gradlew projects --no-daemon -q | command grep -ci sample
  ./gradlew publishToMavenLocal --no-daemon -q -Pversion=0.0.0-ci -Dmaven.repo.local="$SCRATCH/m2-guard"
  .github/scripts/check-maven-publications.sh "$SCRATCH/m2-guard"
  ```

  Expected: `BUILD SUCCESSFUL`; `0`; `maven publications: ok (20)`.

  Integration tests, in the background (they take longer than one tool call), then counted:

  ```bash
  bash "$SCRATCH/tunnel.sh"
  ( set -a; source /Users/jorge/IdeaProjects/wasichai/wasichai-integration-tests/it-env.sh; set +a
    export WASICHAI_TEST_GIS_DB_PORT=5442
    cd /Users/jorge/IdeaProjects/wasichai && ./gradlew integrationTest --rerun --no-daemon --max-workers=2 ) > "$SCRATCH/b1-it.log" 2>&1
  # when it finished:
  tail -3 "$SCRATCH/b1-it.log"; count_it /Users/jorge/IdeaProjects/wasichai
  ```

  Expected: `BUILD SUCCESSFUL`; `394 tests, 0 failures, 0 errors`. If the sum differs, print the per-directory sums
  (`for d in */build/test-results/*/; do echo "$d $(find "$d" -name '*.xml' | sum_junit)"; done`) and check it is
  94 for `wasichai-core/…/integrationTest` and 300 over the suites before reporting.

- [ ] **Step 13: Report (no commit).** Build, build-logic 9, guard 20, ITs 394, the grep and MD results, and the
  list of files changed.

## Task C1: final verification

**Files:** none in any repository. Scratch only: `$SCRATCH/m2-pub`, `$SCRATCH/ui-pack`, `$SCRATCH/tgz`,
`$SCRATCH/pub`, logs. Build outputs the checks produce (`build/`, `dist/`, `node_modules/`) are expected.

**Interfaces:**
- Consumes: every task's deliverable; S1's `wasichai.local` / `wasichai.repo`; S7's `WASICHAI_LOCAL`; wasichai-ui's
  `tooling/set-version.mjs <version> [packagesRoot]`.
- Produces: one report table (repository × check × expected × actual) that C2 and the user read.

Run the steps in order, one repository at a time (they share the test database and ports 8090–8093, 5171–5174).
A failure is recorded and routed (`route to <task>`), never fixed here.

- [ ] **Step 1: wasichai.**

  ```bash
  bash "$SCRATCH/tunnel.sh"
  cd /Users/jorge/IdeaProjects/wasichai
  ./gradlew -p build-logic test --no-daemon -q && count_tests build-logic test
  ./gradlew build --no-daemon 2>&1 | tail -1
  ( set -a; source wasichai-integration-tests/it-env.sh; set +a; export WASICHAI_TEST_GIS_DB_PORT=5442
    ./gradlew integrationTest --rerun --no-daemon --max-workers=2 ) > "$SCRATCH/c1-it.log" 2>&1   # background
  tail -1 "$SCRATCH/c1-it.log"; count_it /Users/jorge/IdeaProjects/wasichai
  ./gradlew publishToMavenLocal --no-daemon -q -Pversion=0.0.0-ci -Dmaven.repo.local="$SCRATCH/m2-guard"
  .github/scripts/check-maven-publications.sh "$SCRATCH/m2-guard"
  yarn format:check
  ```

  Expected: `9 tests, 0 failures, 0 errors`; `BUILD SUCCESSFUL`; `BUILD SUCCESSFUL` and `394 tests, 0 failures,
  0 errors`; `maven publications: ok (20)`; prettier clean.

- [ ] **Step 2: wasichai-ui.**

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai-ui
  yarn install --frozen-lockfile && yarn format:check && yarn test:tooling 2>&1 | command grep -E '^# (pass|fail)'
  yarn lint && yarn test 2>&1 | command grep -E 'Tests +[0-9]+ passed' && yarn build
  node tooling/check-release.mjs --pack 0.0.0-local
  ```

  Expected: `# pass 21`, `# fail 0`; the `Tests N passed` lines sum to 669; `check-release: ok`.

- [ ] **Step 3: The four samples, local mode** (composite onto wasichai, linked wasichai-ui). For each `S` in
  `simple-sample documents-sample gis-sample full-sample`, with the expected IT count 4, 4, 3, 5:

  ```bash
  S=simple-sample   # then documents-sample, gis-sample, full-sample
  ( set -a; source /Users/jorge/IdeaProjects/wasichai/wasichai-integration-tests/it-env.sh; set +a
    export WASICHAI_TEST_GIS_DB_PORT=5442
    cd /Users/jorge/IdeaProjects/$S/server && ./gradlew build integrationTest --rerun --no-daemon 2>&1 | tail -1 )
  count_tests /Users/jorge/IdeaProjects/$S/server integrationTest
  ls /Users/jorge/IdeaProjects/$S/server/build/libs
  cd /Users/jorge/IdeaProjects/$S/web && yarn link:local >/dev/null && yarn lint && yarn typecheck && yarn test && yarn test:scripts && yarn build
  grep -l '\.flex{' dist/assets/*.css
  ```

  Expected per sample: `BUILD SUCCESSFUL`; the IT count with 0 failures; `app.jar`; `2 passed`, `# pass 3`, one css
  path. Also: `cd /Users/jorge/IdeaProjects/gis-sample/perene && python3 -m unittest 2>&1 | tail -2` → `Ran 36 tests`,
  `OK`; full-sample's route parity line (A4 step 7 command) → `route parity: 95 live routes compared`; the full-sample
  e2e exactly as A4 step 13 → `1 passed`.

- [ ] **Step 4: Published path (the default consumption, no sibling, no link).** Publish and pack to scratch:

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai
  ./gradlew publishToMavenLocal --no-daemon -q -Dmaven.repo.local="$SCRATCH/m2-pub"
  ls "$SCRATCH/m2-pub/wasichai/wasichai-bom"
  rm -rf "$SCRATCH/ui-pack" "$SCRATCH/tgz" && mkdir -p "$SCRATCH/tgz"
  rsync -a --exclude node_modules /Users/jorge/IdeaProjects/wasichai-ui/packages/ "$SCRATCH/ui-pack/"
  node /Users/jorge/IdeaProjects/wasichai-ui/tooling/set-version.mjs 0.1.0 "$SCRATCH/ui-pack"
  for d in "$SCRATCH"/ui-pack/*/; do
    node -e 'process.exit(require(process.argv[1] + "package.json").private ? 1 : 0)' "$d" &&
      (cd "$d" && npm pack --ignore-scripts --pack-destination "$SCRATCH/tgz" >/dev/null)
  done
  ls "$SCRATCH/tgz" | wc -l
  ```

  Expected: `0.1.0`; `11` tarballs (`wasichai-<name>-0.1.0.tgz`).

  A copy of simple-sample outside `IdeaProjects`, so neither `../../wasichai` nor `../../wasichai-ui` exists:

  ```bash
  rm -rf "$SCRATCH/pub" && mkdir -p "$SCRATCH/pub"
  rsync -a --exclude build --exclude .gradle --exclude .kotlin --exclude node_modules --exclude dist --exclude .git \
    /Users/jorge/IdeaProjects/simple-sample/ "$SCRATCH/pub/simple-sample/"
  cd "$SCRATCH/pub/simple-sample/server"
  ./gradlew build -Pwasichai.local=false -Pwasichai.repo="file://$SCRATCH/m2-pub" --no-daemon 2>&1 | tail -1
  ./gradlew dependencyInsight --dependency wasichai-bom --configuration compileClasspath -Pwasichai.local=false \
    -Pwasichai.repo="file://$SCRATCH/m2-pub" --no-daemon -q | head -3
  ( set -a; source /Users/jorge/IdeaProjects/wasichai/wasichai-integration-tests/it-env.sh; set +a
    cd "$SCRATCH/pub/simple-sample/server" && ./gradlew integrationTest --rerun -Pwasichai.local=false \
    -Pwasichai.repo="file://$SCRATCH/m2-pub" --no-daemon 2>&1 | tail -1 )
  count_tests "$SCRATCH/pub/simple-sample/server" integrationTest
  ```

  Expected: `BUILD SUCCESSFUL`; the insight names `wasichai:wasichai-bom:0.1.0` and no `project`; `4 tests,
  0 failures, 0 errors`.

  The web against the tarballs (the scratch `package.json` is rewritten, the repository's never is):

  ```bash
  cd "$SCRATCH/pub/simple-sample/web"
  node --input-type=module -e '
  import { readFileSync, readdirSync, writeFileSync } from "node:fs"
  const tgz = process.env.SCRATCH + "/tgz"
  const file = (name) => "file:" + tgz + "/" + readdirSync(tgz).find((f) => f === "wasichai-" + name + "-0.1.0.tgz")
  const pkg = JSON.parse(readFileSync("package.json", "utf8"))
  const all = readdirSync(tgz).map((f) => f.replace(/^wasichai-/, "").replace(/-0\.1\.0\.tgz$/, ""))
  for (const field of ["dependencies", "devDependencies"])
    for (const name of Object.keys(pkg[field])) if (name.startsWith("@wasichai/")) pkg[field][name] = file(name.slice(10))
  // nested @wasichai deps (core -> ui, ...) come from the tarballs too, never from the registry
  pkg.resolutions = Object.fromEntries(all.map((name) => ["@wasichai/" + name, file(name)]))
  writeFileSync("package.json", JSON.stringify(pkg, null, 4) + "\n")'
  yarn install --no-lockfile
  ls -ld node_modules/@wasichai/* | command grep -c '^d'
  yarn list --pattern react-dom --depth=99 2>/dev/null | command grep -c 'react-dom@'
  WASICHAI_LOCAL=false yarn typecheck && WASICHAI_LOCAL=false yarn test && yarn build
  grep -l '\.flex{' dist/assets/*.css
  ```

  Expected: `3` real directories (not links); `1` copy of react-dom; typecheck, `2 passed`, build succeed; one css
  path. Then `rm -rf "$SCRATCH/pub" "$SCRATCH/ui-pack"` (keep `m2-pub` and `tgz` for C2).

- [ ] **Step 5: wasichai-infrastructure.**

  ```bash
  cd /Users/jorge/IdeaProjects/wasichai-infrastructure
  docker compose -f docker/compose.yml config -q && echo compose ok
  git check-ignore -q .local/secrets.env && echo secrets ignored
  ```

  Expected: `compose ok`, `secrets ignored`.

- [ ] **Step 6: Leftovers across every repository.**

  ```bash
  cd /Users/jorge/IdeaProjects
  EX='--exclude-dir=node_modules --exclude-dir=build --exclude-dir=dist --exclude-dir=.gradle --exclude-dir=.git --exclude-dir=.idea --exclude-dir=.kotlin --exclude-dir=.superpowers --exclude-dir=test-results'
  command grep -rnE 'examples/|WASICHAI_BACKEND_DIR|WASICHAI_REPO_TOKEN|wasichai\.sample-app|:[a-z]+-sample-(server|web)|yarn workspace [a-z]+-sample-web' $EX \
    --exclude-dir=superpowers --exclude-dir=adr --exclude=HISTORY.md --exclude=chawpi-origin.md --exclude=sapgis-origin.md --exclude=yarn.lock \
    wasichai wasichai-ui simple-sample documents-sample gis-sample full-sample wasichai-infrastructure
  command grep -rnE 'compose|infra/|docker compose' $EX --exclude-dir=superpowers --exclude-dir=adr --exclude=HISTORY.md \
    --exclude=chawpi-origin.md --exclude=sapgis-origin.md wasichai | command grep -v 'composed annotation'
  (cd wasichai-ui && command grep -rnE 'compose|docker' README.md CLAUDE.md docs .github package.json tooling)
  ```

  Expected: all three silent. (The sample repositories and wasichai-infrastructure mention compose on purpose.)

- [ ] **Step 7: Markdown and workflows.**

  ```bash
  cd /Users/jorge/IdeaProjects
  for r in wasichai-ui simple-sample documents-sample gis-sample full-sample wasichai-infrastructure; do
    (cd $r && FILES="$(git ls-files --others --cached --exclude-standard '*.md' | command grep -v '^node_modules/')" && echo "== $r" && <MD check body>)
  done
  for r in wasichai wasichai-ui simple-sample documents-sample gis-sample full-sample; do (cd $r && actionlint .github/workflows/*.yml && echo "$r actionlint ok"); done
  ```

  For wasichai run the MD check on B1's file list only (the other docs are out of scope). `<MD check body>` is the
  Global Constraints block after its `FILES=` line. Expected: MD checks silent (known pre-existing long lines in
  `gis-sample/perene/README.md` reported by A3 are listed, not failures); six `actionlint ok`.

- [ ] **Step 8: Report.** One table: repository, check, expected, actual, route-to. Plus the scratch paths kept.

## Task C2: whole-change review and one fix round

**Files:** none directly; fixes go through the owning task's column.

**Interfaces:**
- Consumes: C1's report; the spec; this plan; every A/B report (in particular which samples needed the S1 fallback).
- Produces: a review verdict per repository and at most one fix round.

- [ ] **Step 1: Review.** Dispatch one fresh reviewer on the most capable model with
  `superpowers:requesting-code-review`, giving it the spec path, this plan's path, C1's table, and the seven
  repository paths (the changes are uncommitted: `git status` and the file lists of each task show them). It checks,
  at least: the spec's Target per repository; Review Focus 1–5 and their evidence in the reports; that the four
  sample builds are identical except the per-sample values (diff `server/settings.gradle.kts`,
  `server/build.gradle.kts`, `server/gradle/libs.versions.toml`, `web/vite.config.ts`, `web/scripts/*`,
  `.github/workflows/ci.yml` pairwise); that no secret value appears in any file or report; and the Open risks below.
- [ ] **Step 2: S1 fallback consistency.** If any of A1–A4 needed the S1 `dependencySubstitution` fallback, all four
  take it (one pattern, not two): route the change to the other sample tasks.
- [ ] **Step 3: One fix round.** Each finding the coordinator accepts goes to the owning task's agent (resumed if
  possible) as `route to <task>: <path>: <problem>`; the owner fixes and re-runs its own checks; C1 re-runs the
  affected steps. A second failure goes to the user, not to another round.
- [ ] **Step 4: Report to the user (no commit).** Per repository: what changed, the counts, what is still red and
  why (expected: the samples' CI until the first releases), and the one-time GitHub settings the user must make
  (package access for the sample repositories or `WASICHAI_PACKAGES_TOKEN`; after the first wasichai-ui release,
  `yarn install` in each `web/` to complete `yarn.lock`).

Ordered by likelihood × cost. Each names where it shows up and what to do.

1. **Gradle composite substitution of the BOM (`java-platform`).** Auto-substitution matches an included build's
   projects by `group:name`; `wasichai`'s `allprojects { group = "wasichai" }` and the project name `wasichai-bom`
   give `wasichai:wasichai-bom`, and a `java-platform` project exposes variants with category `platform`, which is
   what `platform(...)` requests, so it is expected to work without configuration. What can still go wrong:
   - the BOM resolves from the repository instead (before the first release: a 401 or "could not find"; after it: a
     stale published BOM that silently pins older starters). A1–A4 step 4 catches it; the S1 fallback substitutes
     `platform(module("wasichai:wasichai-bom"))` with `platform(project(":wasichai-bom"))` explicitly. Declaring
     explicit substitutions may stop Gradle from substituting the other modules automatically, which is why the
     fallback lists every `wasichai:*` module a sample uses, and C2 keeps the four samples on one pattern;
   - the BOM's constraints are `api(project(...))` over `rootProject.subprojects`: in a composite they point at the
     included build's projects, so versions line up; its `api(platform(spring-boot-dependencies))` still applies;
   - `version` comes from the included build's own `gradle.properties` (`0.1.0`); if an included build did not read
     it, `rootProject.property("version")` fails at configuration time, loudly, not silently;
   - A1–A4 run four composite builds at once that all point at the same `wasichai` checkout. Gradle locks its caches
     across processes but not the projects' `build/` directories: two builds recompiling `wasichai-core` at the
     same moment can corrupt a jar. G0's warm-up leaves every needed task up to date so nothing is rebuilt; if a
     sample still fails with a truncated or missing class from a `wasichai-*` project, re-run it once alone, and if
     it repeats, the coordinator serializes A1–A4's Gradle steps;
   - opting out: `-Pwasichai.local=false` is read in `settings.gradle.kts` through `providers.gradleProperty`, which
     `-P` sets; C1 step 4 proves the published path from a copy where no sibling exists at all.
2. **GitHub Packages access from the sample repositories' CI.** The Maven registry of GitHub Packages scopes
   permissions to the publishing repository; a sample's `GITHUB_TOKEN` may be refused even with `packages: read`
   unless each package grants the sample repository access. The workflows fall back to a `WASICHAI_PACKAGES_TOKEN`
   secret (classic PAT, `read:packages`). Not verifiable before the first release; C2 lists it for the user.
3. **Two copies of `@types/react` in link mode.** `tsc` follows `node_modules/@wasichai/*` to their real path, so
   the linked `.d.ts` files resolve `react`'s types from `wasichai-ui/node_modules`. Same version, so usually
   compatible; if typecheck fails, the likely fix is `"preserveSymlinks": true` in `web/tsconfig.json` (types then
   resolve from the sample's `node_modules`). A1–A4 stop and report instead of guessing; C2 decides once for all.
4. **Vitest and `resolve.dedupe` with aliased sources.** The sources under `../../wasichai-ui/packages/*/src` import
   `react` from `wasichai-ui/node_modules`; dedupe must redirect them to the sample's copy, or hooks break.
   Pinned by A1–A4 step "Link, format, verify" (`yarn test` aliased, `WASICHAI_LOCAL=false yarn test` linked).
5. **Tailwind v4 and symlinks.** `@source '../node_modules/@wasichai'` must follow the links. If `.flex{` is missing
   in link mode only, add `@source '../../../wasichai-ui/packages/*/dist';` as a second line (a missing directory
   is ignored) and re-check both modes.
6. **Partial `yarn.lock` until the first wasichai-ui release.** `yarn install --frozen-lockfile` fails in each
   sample's CI until someone runs `yarn install` in `web/` after the release and commits the result. Documented in
   every sample README ("CI"); the samples' CI is expected red until then (spec: "Green only after the first
   wasichai / wasichai-ui release is published").
7. **Shared resources during Wave A.** One test database (the suite lock queues ITs), one demo database per sample,
   and fixed ports: 8090 (A3's apply.py run), 8093/5174 (A4's e2e). The migration's T7 must be finished (G0), and
   `wasichai-ui/node_modules` must not change under A1–A4 (G1).
8. **Demo database credentials.** A3 step 9 and A4 step 13 use the tunnel's test user for `wasichai_gis` and
   `wasichai_full`. If that user cannot reach them, the steps fail at start-up with an authentication error: report,
   do not create users or databases.
9. **`logger` in `settings.gradle.kts`.** Kotlin settings scripts expose `logger`; if the Gradle version at hand
   does not, `ktlint`/compile of the settings fails at once, and `println("w: …")` with the same text is the
   replacement (A1–A4 step 6 still greps the message).
10. **Editing ADR-031 and `docs/chawpi-origin.md`.** The rule "a decision is changed by a new ADR" is kept: ADR-033
    is the decision; ADR-031 itself says a new difference needs a new entry there (D17), and the origin page gets an
    appended paragraph, not a rewrite.
11. **`WASICHAI_TEST_DB_NAME` is a wipe target.** Sample ITs in external mode wipe the test database named in
    `it-env.sh` (it ends in `_test`); they never touch the demo databases. Nobody points `WASICHAI_TEST_DB_NAME` at a
    `wasichai_<sample>` database.

## Self-review notes

- Spec coverage: sample repositories (A1–A4: server, web, extras, standalone builds, local override, CI, README,
  CLAUDE.md, `.editorconfig`, `.gitignore`); infrastructure repository (A5); wasichai-ui cleanup (A6); wasichai
  cleanup, GeoServer default, ADR-033, ADR-031 D17, HISTORY, docs (B1); every Verification line of the spec (A1–A4
  local checks, C1 steps 1–7, including the published-path check); spec step 4's review and one fix round (C2).
  Spec step 0 (finish T7) is gate G0.
- Placeholders: every `<…>` in S1–S16 has its value in the task that uses the template; nothing is left "to decide"
  except the explicitly conditional fallbacks (S1, Open risks 3 and 5), each with its exact replacement text.
- Names used across tasks: `wasichai.local`, `wasichai.repo` (S1, C1), `WASICHAI_LOCAL` (S7, C1),
  `link:local` / `unlink:local` / `test:scripts` (S6, S8, A1–A4, C1), `count_tests` / `count_it` / `sum_junit`
  (Global Constraints), `WASICHAI_PACKAGES_TOKEN` (S13, S14, ADR-033, C2),
  `0033-samples-and-infrastructure-in-their-own-repositories.md` (B1 steps 7–10).
