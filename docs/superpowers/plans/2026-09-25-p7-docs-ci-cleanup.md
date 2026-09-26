# P7 — Docs, ADRs, CLAUDE.md, CI/release hardening and deferred cleanups — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the chawpi library split: record the remaining decisions as ADRs, bring every living doc in line with
the module layout, write one doc per module and a "build your app" guide, rewrite CLAUDE.md and README.md, finish
HISTORY.md, apply the small code cleanups the phase ledgers deferred to P7, harden CI and publishing, and verify the
whole repository end to end.

**Architecture:** Nearly all of it is documentation, written from the code (the code is the source of truth, the
old docs are not). The ADR and doc tasks own disjoint files, so they run in parallel with each other and with P3
(backend ITs) and P6 (example apps). Code cleanups keep behaviour identical: one task per module or package, each
running that module's tests. They wait for P3 and P6 to finish, because a change to build-logic, core or `yarn.lock`
while those phases build would race their Gradle and yarn runs. A final task verifies everything.

**Tech Stack:** Markdown (hand-formatted, `.editorconfig`), Kotlin 2.4.20 / Spring Boot 4.1.1 / Gradle 9.7.1
(build-logic convention plugins, TestKit), React 19.3 / Vite 8 / vitest, Node 26 (`node --test` for tooling), GitHub
Actions, release-please, GitHub Packages.

**Spec:** `docs/superpowers/specs/2026-09-25-chawpi-libraries-design.md` (binding; sections "Docs & preservation",
"Commits, versioning and releases", "Tests", "Verification"). Phase ledgers:
`.superpowers/sdd/2026-09-25-p{0..5}-*/progress.md` and `.superpowers/sdd/2026-09-25-p3-integration-tests/progress.md`.

## Global Constraints

Every task's requirements include this section.

- **No git commits, ever.** The user forbids them. No `git add`, `git commit`, `git stash`, `git checkout -- <file>`.
  "Done" means the files are on disk and the task's checks pass. Tasks have no commit step.
- **Hands off P3 and P6 paths.** Never create, edit or delete anything under `backend/chawpi-integration-tests/**` or
  `examples/**`. Read-only access is fine. A problem found there goes into the task report, not into a fix.
- **Do not touch** `/Users/jorge/IdeaProjects/sapgis` (read-only reference) or `.superpowers/**` (ledgers).
- **Formatting (spec "Formatting (mandatory)")**: `.editorconfig` applies to every file. Kotlin 4 spaces, TS/YAML/MD
  2 spaces, max 160 columns, LF, final newline, no trailing whitespace. Kotlin: `./gradlew :<project>:ktlintFormat`
  then `ktlintCheck`. TS/JSON/YAML under `frontend/`, `.github/` and the root: `yarn prettier --write <files>` then
  `--check`. Markdown is prettier-ignored (`.prettierignore`: "docs, adrs and history keep their hand formatting"),
  so Markdown is checked with the MD check below. Wrap prose in docs at about 110 columns, like the existing docs.
- **MD check** (run on every Markdown file a task creates or edits; prints nothing when clean):

  ```bash
  cd /Users/jorge/IdeaProjects/chawpi
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

  A link to a file that another Wave 1 task creates (names fixed in "File map and ownership") may be reported
  broken while that task is still running. Report such links in the task report as "pending <task>". Task 40 re-runs
  the check over all docs.
- **Relative links only** inside the repo (`../modules/gis.md`, not absolute paths or GitHub URLs).
- **The word "sapgis"** may appear only in the places listed in Task 40 Step 4 (the allow list). New and rewritten
  docs, code comments and test names say "the original app" instead, or link to `docs/sapgis-origin.md` or ADR-031.
  ADRs 0024–0031 may name sapgis in their Context and Decision text, because they describe the split.
- **ADR house style** (copy it exactly): file `docs/adr/00NN-kebab-title.md`; first line `# ADR-0NN: <Title>` (three
  digits after `ADR-`, as in `# ADR-025: ...`); then a blank line and
  `**Status**: accepted · 2026-09-25[ · amends ADR-0xx, ...]`; sections `## Context`, `## Decision`,
  `## Consequences`; cross-references written `ADR-025` and linked `[ADR-025](0025-extension-spis.md)`.
- **Behaviour stays identical** in every code task. A cleanup that would change an HTTP response, a SQL statement's
  effect, a bean's presence or a rendered UI is out of scope. Stop and report it instead.
- **Docs describe the code as it is.** When a doc task finds the code contradicting this plan's text, the code wins
  for facts (names, defaults, routes). The task writes the doc from the code and records the mismatch in its report.
  Decisions in this plan (ADR text, rulings) are not facts and are not rewritten by the executor.
- **Parallel runs.** Wave 1 tasks run concurrently with each other and with P3/P6, so they run no Gradle task and no
  `yarn build`. They may run `yarn workspace @chawpi/<pkg> test|lint` and `yarn test:tooling`. Wave 2 tasks may
  run Gradle builds concurrently; each builds only its own project(s).
- **Module doc template (Tasks 12–20).** Every `docs/modules/<name>.md` uses these headings, in this order. A
  heading with nothing to say gets one line ("None."), never a missing heading.

  ```markdown
  # <Name> module

  <One paragraph: what an app gets by installing it, in plain words.>

  ## Install
  <Backend: the starter line under the BOM, e.g. implementation("chawpi:chawpi-spring-boot-starter-<name>").
   Frontend: yarn add @chawpi/<name> (plus any extra peer the package README requires), and the registration line
   <ChawpiApp modules={[<name>Module()]} />. Link ../guides/build-your-app.md.>

  ## What it adds
  <Bullets: tables, REST routes (a table: method + path, taken from ModuleRoutes.kt), screens and sidebar entries
   (from module.tsx), registry slots it fills (field renderers, page components, page actions, record panels,
   history renderers...).>

  ## Configuration
  | Property | Default | Meaning |
  <One row per property of the module's @ConfigurationProperties classes, in kebab-case as written in
   application.yml (chawpi.<name>.enabled first). Env form once below the table: CHAWPI_<NAME>_ENABLED.>

  ## Extension points
  <Implements: the core SPIs it provides beans for, each with the bean's class. Defines: SPIs other modules
   implement, if any. Overridable beans: the @ConditionalOnMissingBean beans an app may replace.>

  ## Database
  <Migration location classpath:db/chawpi/<name>, history table flyway_history_<name>, tables it creates, changes it
   makes to core tables. "None." for modules without migrations.>

  ## Frontend package
  <@chawpi/<name>: the factory and its options, main exports from index.ts, i18n namespace (= module id, core keys
   reachable through fallbackNS), heavy dependencies and how they stay lazy, default basePath. Link the package
   README (../../frontend/packages/<name>/README.md) for the full API.>

  ## Without this module
  <What an app sees when the module is not installed: its routes answer 404 (ADR-031 D1), its screens and nav
   entries are absent, what core does instead.>

  ## Behaviour differences
  <Links to the ADR-031 entries that concern this module, e.g. "[ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md)
   D3: …". "None." otherwise.>

  ## Known limitations
  <Bullets, or "None.">
  ```

- **Module fact commands (Tasks 12–20).** Run with `M` set to the module (`core`, `views`, `forms`, `pages`,
  `workflow`, `automation`, `documents`, `gis`, `agent`):

  ```bash
  cd /Users/jorge/IdeaProjects/chawpi
  M=<module>
  ls backend/chawpi-$M/src/main/kotlin/chawpi/$M backend/chawpi-$M/src/main/resources/db/chawpi/ 2>/dev/null
  cat backend/chawpi-$M/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  command grep -rn -B1 -A12 '@ConfigurationProperties(' backend/chawpi-$M/src/main/kotlin --include='*.kt' | command grep -E 'ConfigurationProperties|val '
  command grep -rn -E '@Bean|@ConditionalOn|@Order|@AutoConfiguration' backend/chawpi-$M/src/main/kotlin --include='*AutoConfiguration.kt'
  sed -n "/\"$M\" to/,/)/p" backend/chawpi-integration-tests/src/testFixtures/kotlin/chawpi/it/support/ModuleRoutes.kt
  command grep -n -E "id:|basePath|path:|labelKey|lazy|group:" frontend/packages/$M/src/module.tsx
  command grep -n '^export' frontend/packages/$M/src/index.ts
  node -p "const p=require('./frontend/packages/$M/package.json'); JSON.stringify({deps:p.dependencies,peers:p.peerDependencies})"
  ```

  The fact commands are read-only and may run concurrently with P3/P6.
- **Package README touch-ups (Tasks 12–20).** In each `frontend/packages/<name>/README.md` the task owns:
  (a) directly under the first heading, add `Module guide: [docs/modules/<name>.md](../../../docs/modules/<name>.md).`;
  (b) replace any "sapgis" with "the original app";
  (c) where the README lists backend endpoints, add once, right before the list: "Paths are the backend's routes.
  The frontend reaches them through `apiBaseUrl` (default `/api`), so a proxy that mounts the API elsewhere changes
  the prefix, not these paths."
  Nothing else in the README changes. `*.md` is prettier-ignored, so check the README with the MD check.
- **Tone for docs** (the house style of `docs/HISTORY.md` and the ADRs): plain declarative sentences, present tense,
  no marketing, no emojis. Say what the thing does and why. Tables for properties and routes.

## Review Focus

The failure modes most likely to hurt someone using what P7 ships, most likely first. Each has a test in the task
that owns it.

1. **A consumer installs a published `@chawpi/*` package and an internal dependency does not resolve.** The
   dependency is still `"*"`, or points at a version that was never published, or `dist/` is missing from the
   tarball. A consumer expects `npm install @chawpi/gis@X` to pull `@chawpi/core@X` and get working ESM plus types.
   Pinned by Task 26 (`packDryRun` test on a fixture and on the real packages) and wired into CI by Task 39.
2. **A release publishes the wrong set.** A private artifact leaks (`@chawpi/smoke`, an example app,
   `chawpi-integration-tests`), or a new module is silently left out of the BOM, release-please `extra-files` or the
   publish loop. A maintainer expects exactly the 20 Maven artifacts and 11 npm packages. Pinned by Task 26
   (`checkReleaseConfig` tests, `check-maven-publications.sh`) and run in CI and before `publish` by Task 39.
3. **An app's own beans and properties go missing, or library beans register twice, with `@ChawpiApplication`.**
   A developer expects the annotation to scan their package and bind their `@ConfigurationProperties`, and never to
   scan `chawpi.*`. Pinned by Task 29's live-context test.
4. **A developer copies a snippet from the guide or a module doc and it does not work.** The artifact name,
   property key, route or export is wrong. They expect every `chawpi:<artifact>`, `chawpi.<key>` and `@chawpi/<pkg>`
   in docs to exist in the code. Pinned by the name check in Task 21 Step 5, repeated over all docs by Task 40
   Step 5.
5. **Removing inert stereotype annotations drops a bean that only component scanning had registered.** A developer
   expects every service to exist exactly as before. Pinned in Tasks 29–37 by a check that every class losing
   `@Service`/`@Component` is constructed in an auto-configuration `@Bean` method, plus the module's auto-config
   tests (`AllModulesWiringTest` runs in Task 40).

## File map and ownership

Each file has exactly one owning task, so parallel tasks never edit the same file. Names below are fixed. Other
tasks link to them by these names.

| Task | Owns (create C / modify M) |
|---|---|
| 1 | C `docs/adr/0024-libraries-and-starters.md` |
| 2 | C `docs/adr/0028-frontend-module-registry.md` |
| 3 | C `docs/adr/0029-polyglot-monorepo-and-publishing.md` |
| 4 | C `docs/adr/0030-rebrand-sapgis-to-chawpi.md` |
| 5 | C `docs/adr/0031-deliberate-deviations-from-sapgis.md` |
| 6 | M `docs/adr/0026-per-module-migrations.md`, M `docs/adr/0027-gis-optional.md`, C `docs/adr/README.md` |
| 7 | M `docs/architecture/overview.md` |
| 8 | M `docs/domain/metadata-model.md`, M `docs/gis/geometry.md` |
| 9 | M `docs/api/rest.md` |
| 10 | M `docs/security/authentication.md` |
| 11 | M `docs/development/getting-started.md` |
| 12 | C `docs/modules/core.md`, M `frontend/packages/core/README.md`, M `frontend/packages/ui/README.md` |
| 13 | C `docs/modules/gis.md`, M `frontend/packages/gis/README.md` |
| 14 | C `docs/modules/documents.md`, M `frontend/packages/documents/README.md` |
| 15 | C `docs/modules/workflow.md`, M `frontend/packages/workflow/README.md` |
| 16 | C `docs/modules/automation.md`, M `frontend/packages/automation/README.md` |
| 17 | C `docs/modules/agent.md`, M `frontend/packages/agent/README.md`, C `backend/starters/chawpi-spring-boot-starter-agent/README.md` |
| 18 | C `docs/modules/pages.md`, M `frontend/packages/pages/README.md` |
| 19 | C `docs/modules/views.md`, C `docs/modules/forms.md`, M `frontend/packages/views/README.md`, M `frontend/packages/forms/README.md` |
| 20 | C `docs/modules/testing.md`, M `frontend/packages/testing/README.md` |
| 21 | C `docs/guides/build-your-app.md`, C `docs/modules/README.md` |
| 22 | M `CLAUDE.md`, M `README.md` |
| 23 | M `docs/HISTORY.md` (entries for P2, P4, P5; fix the stale P1 entry) |
| 24 | M the frontend source and test files listed in Task 24 (comments and test titles only) |
| 25 | M `frontend/packages/core/src/i18n/locales/{en,es}/common.json` |
| 26 | C `frontend/tooling/check-release.mjs`, C `frontend/tooling/check-release.test.mjs`, C `.github/scripts/check-maven-publications.sh`, M `docs/development/releasing.md` |
| 27 | M `backend/build-logic/src/main/kotlin/chawpi.kotlin-library.gradle.kts`, M `backend/build-logic/src/test/kotlin/chawpi/buildlogic/ConventionPluginsTest.kt` |
| 28 | M `backend/chawpi-test/build.gradle.kts`, M `backend/chawpi-test/src/main/kotlin/chawpi/test/ChawpiContextRunner.kt` (comment only) |
| 29 | M `backend/chawpi-core/src/main/**` (stereotypes, FQNs, `ChawpiSchemas` comment), C `backend/chawpi-core/src/test/kotlin/testapp/live/*` |
| 30–37 | M `backend/chawpi-<module>/src/main/**` for views, forms, pages, workflow, automation, documents, gis, agent |
| 38 | M `frontend/packages/documents/package.json`, M `yarn.lock` |
| 39 | M `.github/workflows/ci.yml`, M `.github/workflows/publish.yml`, M `commitlint.config.mjs`, M `.prettierignore` |
| 40 | M `docs/HISTORY.md` (entries for P3, P6, P7); otherwise read-only |

`docs/modules/<name>.md` names: `core`, `views`, `forms`, `pages`, `workflow`, `automation`, `documents`, `gis`,
`agent`, `testing`. `@chawpi/ui` is documented in `core.md`, and `chawpi-test` plus `@chawpi/testing` in
`testing.md`.

## Waves

| Wave | Tasks | Starts when | Why this boundary |
|---|---|---|---|
| 1 | 1–26 (26 tasks, all parallel) | now, alongside P3 and P6 | Docs and frontend-source-only edits with disjoint files. No Gradle build and no `yarn build`, so nothing races P3/P6. |
| 2 | 27–39 (13 tasks, all parallel) | **wait for P3/P6 completion**: both ledgers (`.superpowers/sdd/2026-09-25-p3-integration-tests/progress.md` and the P6 ledger) end with `P3 COMPLETE` / `P6 COMPLETE` | Build-logic, core, module, `yarn.lock` and workflow changes would force rebuilds under P3/P6's running builds. CI wiring needs P3's final IT task names and P6's sample names. |
| 3 | 40 | every task of waves 1 and 2 is complete | Whole-repo verification and the last HISTORY entries. |

Controller notes:
- Dispatch Wave 1 in batches of about 8 (the machine's limit on concurrent agents is the only constraint). Order
  inside Wave 1 does not matter. Tasks 12–21 link to ADR files from Tasks 1–5 by fixed names.
- In Wave 2, Tasks 27–29 change code that every other backend project compiles against. Tasks 30–37 build only their
  own project. Running them concurrently is safe (behaviour-neutral edits) but may cause Gradle lock waits.
- Review gate per task: a fresh reviewer checks the task's diff against its section of this plan. For doc tasks,
  the reviewer also spot-checks five facts against the code paths listed under "Sources".

## Wave 1 — docs, ADRs, frontend-source-only cleanups, release tooling (runs alongside P3 and P6)

### Task 1: ADR-024 — libraries and starters

**Files:**
- Create: `docs/adr/0024-libraries-and-starters.md`

**Interfaces:**
- Consumes: ADR house style (Global Constraints). Existing ADRs `0001-modular-monolith.md`, `0025-extension-spis.md`.
- Produces: `docs/adr/0024-libraries-and-starters.md`, title `ADR-024: Chawpi ships as libraries: starters, a BOM and
  explicit auto-configuration`. Later tasks cite it as ADR-024 for: starters, BOM, `chawpi.<module>.enabled`, no
  component scan, `@ChawpiApplication`, the property-class placement rule, and the public-surface (no
  `explicitApi()`) ruling.

**Sources to verify facts against (read before writing):** `backend/chawpi-bom/build.gradle.kts`,
`backend/starters/*/build.gradle.kts`, `backend/chawpi-*/src/main/resources/META-INF/spring/*.imports`,
`backend/chawpi-core/src/main/kotlin/chawpi/core/autoconfigure/{ChawpiApplication,ChawpiEnvironmentPostProcessor}.kt`,
every `@ConfigurationProperties` class (`command grep -rn '@ConfigurationProperties' backend --include='*.kt'`).

- [ ] **Step 1: Check the facts the text below states**

```bash
cd /Users/jorge/IdeaProjects/chawpi
ls backend/starters
cat backend/chawpi-bom/build.gradle.kts
command grep -rn '@ConfigurationProperties(' backend --include='*.kt' | command grep '/main/'
```

Expected: nine starters (`chawpi-spring-boot-starter` plus `-agent -automation -documents -forms -gis -pages -views
-workflow`). The BOM excludes `chawpi-bom` and `chawpi-integration-tests`. The property classes are in the places
the Decision below names. If any fact differs, fix that sentence to match the code and report it.

- [ ] **Step 2: Write the ADR with exactly this content**

````markdown
# ADR-024: Chawpi ships as libraries: starters, a BOM and explicit auto-configuration

**Status**: accepted · 2026-09-25 · amends ADR-001

## Context

The original app was one Gradle module and one Spring Boot application. Its packages found each other through
component scanning, five of them formed cycles, and several ports were single required beans, so metadata could
not boot without automation and gis. Nobody could take part of it: a new app had to fork the whole thing. ADR-001
chose a modular monolith for one deployable; that choice still holds for an app built on chawpi. What changes is
that the modules become libraries an app assembles.

## Decision

**Artifacts.** Maven group `chawpi`, one version for all of them (ADR-029):

| Artifact | What it is |
|---|---|
| `chawpi-core` | identity, organization, metadata, dynamic data, audit, admin, platform plumbing, the SPIs (ADR-025) |
| `chawpi-<module>` | one optional module each: views, forms, pages, workflow, automation, documents, gis, agent. Each depends on core, pages also on forms |
| `chawpi-spring-boot-starter` | core plus what an app runs on: R2DBC PostgreSQL, JDBC PostgreSQL and Flyway at runtime (ADR-008), actuator |
| `chawpi-spring-boot-starter-<module>` | the starter plus that module. The agent starter also adds Embabel's Anthropic provider (ADR-031 D7) |
| `chawpi-bom` | a `java-platform` importing Spring Boot's BOM, with a constraint on every published chawpi artifact |
| `chawpi-test` | test fixtures an app uses to test itself on a real PostgreSQL |

The module graph is acyclic: core ← every module, forms ← pages. `CoreArchitectureTest` fails the build if core
imports a module package.

**Wiring.** Each library registers its `@AutoConfiguration` classes in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Every bean is declared in an
auto-configuration `@Bean` method, with `@ConditionalOnMissingBean` where an app may replace it: an app overrides a
chawpi bean by declaring its own bean of that type. Library code is never component-scanned. So library classes
carry no `@Service` or `@Component`. `@RestController` stays because WebFlux's handler mapping looks for it on the
bean, and `@Repository` stays because exception translation keys on it.

**Switches.** Every module has `chawpi.<module>.enabled`, default `true`. When it is `false`, the module adds no
beans, no routes and no migration. Core has no switch, because it is the base the others stand on.

**Configuration.** Everything lives under `chawpi.*` (environment `CHAWPI_*`). `ChawpiEnvironmentPostProcessor` adds
lowest-precedence defaults: database coordinates from `CHAWPI_DB_*`, the R2DBC URL and pool, and RFC 7807 problem
details. It never adds a JWT secret: a library must not ship one that works.

**Property classes.** A module's switch lives in `Chawpi<Module>Properties` in `chawpi.<module>.autoconfigure`.
Settings that the module's own code reads live beside that code: `AgentProperties` (`chawpi.agent`),
`AutomationProperties` (`chawpi.automation`), `GeoServerProperties` (`chawpi.gis.geoserver`). Moving them into
`autoconfigure` would make domain code import its own wiring. Core's classes (`ChawpiDatabaseProperties`,
`JwtProperties`, `ChawpiWebProperties`) live in `chawpi.core.platform`, the bottom of core's layering.

**The app.** `@SpringBootApplication` is enough. `@ChawpiApplication` is an optional shorthand for
`@SpringBootApplication` plus `@ConfigurationPropertiesScan`, forwarding `exclude`, `excludeName` and
`scanBasePackages`. Scanning starts at the app's package, so **an app must not live in package `chawpi` or below
it**: its scan would reach the library's controllers and register them a second time.

**Public surface.** Kotlin's `explicitApi()` stays off for 0.x. The supported API is: the SPIs of ADR-025, the bean
types an auto-configuration exposes for overriding, the `@ConfigurationProperties` classes, `@ChawpiApplication`,
and the `chawpi-test` fixtures. Every other public declaration is implementation and may change in any 0.x minor.
Turning `explicitApi()` on means adding a visibility modifier to every declaration in nine libraries. That cost buys
nothing until there are outside users to protect, so it is revisited before 1.0.

## Consequences

- An app gets the platform with two dependency lines and no code: the BOM and a starter. Each module is one more
  starter line.
- Any bean can be replaced without forking, and removing a module is removing a dependency.
- `chawpi.<module>.enabled=false` lets an app ship a module's jar and still keep it off.
- Without component scanning, a new library class does nothing until an auto-configuration declares it. Each
  module's auto-configuration test catches a forgotten one.
- Starters carry no code. A change of runtime driver or default provider is a one-line starter change.
````

- [ ] **Step 3: Run the MD check (Global Constraints) on the new file**

Expected: no output, exit 0. `[ADR-025]` etc. are plain text here; the file links nothing, so the link check is
trivially clean.

---

### Task 2: ADR-028 — frontend module registry

**Files:**
- Create: `docs/adr/0028-frontend-module-registry.md`

**Interfaces:**
- Consumes: ADR house style.
- Produces: `docs/adr/0028-frontend-module-registry.md`, title `ADR-028: Frontend modules plug into a registry`. Cited
  by Tasks 7, 12–21 for the `ChawpiModule` contract, slots, links, i18n namespaces and lazy heavy libraries.

**Sources:** `frontend/packages/core/src/registry/{contract.ts,createRegistry.ts,hooks.ts}`,
`frontend/packages/core/src/i18n/createI18n.ts`, `frontend/packages/core/src/links/`,
`frontend/packages/*/src/{module.tsx,index.ts}`, `frontend/packages/*/package.json` (`dependencies`,
`peerDependencies`).

- [ ] **Step 1: Check the facts**

```bash
cd /Users/jorge/IdeaProjects/chawpi/frontend/packages
command grep -n -E '^  [a-zA-Z]+\??:' core/src/registry/contract.ts | sed -n '/ChawpiModule/,$p' | head -40
sed -n '/export interface ChawpiModule/,/^}/p' core/src/registry/contract.ts
command grep -n -E 'defaultNS|fallbackNS|CORE_NAMESPACE =' core/src/i18n/createI18n.ts
command grep -n -E 'WorkflowBuilderPage|PageBuilderPage|TemplateEditor' workflow/src/index.ts pages/src/index.ts documents/src/index.ts
command grep -rn 'export function useChawpiLinks' core/src
```

Expected: `ChawpiModule` has the members listed in the Decision table below. `CORE_NAMESPACE = 'common'` is both
`defaultNS` and `fallbackNS`. None of the three heavy pages is exported from its package index. `useChawpiLinks`
exists. Adjust the member table to the real interface if it differs (names only) and report it.

- [ ] **Step 2: Write the ADR with exactly this content**

````markdown
# ADR-028: Frontend modules plug into a registry

**Status**: accepted · 2026-09-25

## Context

The original frontend was one Vite app with sixteen feature folders. Routes and the sidebar were hardcoded,
`DynamicForm` imported MapLibre statically, the page renderer switched over every component type, history imported
documents and documents imported history. An app could not leave GIS out, and it could not add a screen without
editing the shell.

## Decision

**Packages.** `@chawpi/ui` (primitives and Tailwind theme), `@chawpi/core` (the app shell and everything that works
without a module), one package per backend module (`@chawpi/views`, `forms`, `pages`, `workflow`, `automation`,
`documents`, `gis`, `agent`) and `@chawpi/testing`. Each is a Vite library build (ESM plus `tsc` declarations). React,
react-dom, react-query, i18next, react-i18next and react-router are peer dependencies, so the app owns one copy.

**The contract.** A module is a value of type `ChawpiModule`, usually built by a factory
(`gisModule({ workerUrl })`). The app passes the list to `<ChawpiApp modules={[...]} />`, and `createRegistry` merges
the list once:

| Member | What the module contributes |
|---|---|
| `id` | its name, also its i18n namespace. Unique, and not one of core's reserved ids |
| `basePath`, `routes` | routes relative to `basePath`. The default `basePath` keeps the original app's URLs |
| `navGroups`, `nav` | sidebar groups and items, each with an order, an icon and a permission check |
| `fieldRenderers` | input, display and settings for a field type (gis: `GEOMETRY`), keyed by the section it owns |
| `pageComponents`, `pageActions` | page-builder components (gis: `MAP`, workflow: `WORKFLOW`) and action kinds (workflow: `TRANSITION`) |
| `recordPanels`, `recordListActions` | panels on a record's page, buttons above a record list |
| `historyRenderers`, `auditValueFormatters`, `auditFieldLabels` | how the history shows a module's operations (documents: `ISSUE`) and values |
| `dashboardCards`, `objectColumns`, `objectTileDetails`, `objectFlags` | additions to the dashboard and object lists |
| `recordQueryKeys` | extra react-query keys to invalidate when a record changes |
| `providers` | context providers wrapped around the whole app |
| `i18n` | resources per language, loaded under the namespace `id` |

`createRegistry` refuses a bad combination with a `RegistryError` at start-up, before anything renders: a duplicate
module id, route, path, nav item or page component, a nav item pointing at an unknown route, a field renderer whose
section collides with a core record key, or two modules claiming the same field-renderer setting.

**Slots, not imports.** Core never imports a module. `DynamicForm` asks the registry for a field type's renderer,
and the page renderer asks it for a component type. An unknown component type draws a muted placeholder rather than
nothing. Core's own page action is `NAVIGATE`, and core's history operations are `CREATE`, `UPDATE` and `DELETE`.
Everything else arrives through a slot, which is how the history↔documents cycle disappears.

**Links.** Code never writes a URL by hand. `useChawpiLinks()` builds every link from the registry's routes, so a
module mounted under another `basePath` keeps working.

**i18n.** Core's resources are the namespace `common`, which is both `defaultNS` and `fallbackNS`. A module's keys
live in its own namespace (its `id`), and a module component may still use a core key unprefixed, because lookups
fall back to `common`. The keys that belong to one module moved out of core's `common.json` into that module.

**Heavy libraries stay lazy.** MapLibre and terra-draw (gis), xyflow (workflow), tiptap (documents) and dnd-kit
(pages) are reached only through lazy routes and `React.lazy`, so an app that registers the module pays for the
library only when a user opens that screen. The pages that pull them in (`WorkflowBuilderPage`, `PageBuilderPage`,
`TemplateEditor`) are not exported from their package's index. They are reachable only as routes. Light modules
still re-export their pages from the index. Vite then warns that the dynamic import is ineffective, but those
pages add nothing heavy to the initial chunk, so this is accepted.

## Consequences

- An app picks modules by listing them. Leaving one out removes its routes, sidebar entries, renderers and strings.
  Its data stays readable through core's generic views.
- A backend module the frontend does not register is invisible, and a registered frontend module whose backend is
  missing gets `404`s. Core treats a `404` on a module endpoint as "not installed" (ADR-031).
- Configuration errors fail at start-up with a message naming both modules, not as a broken screen later.
- An app cannot import the heavy builder pages directly. None needs to, and exporting them would undo the lazy
  loading.
- Adding a slot is a change to `@chawpi/core`'s contract, so it needs a second user first (CLAUDE.md, "no
  overengineering").
````

- [ ] **Step 3: Run the MD check on the new file**

Expected: no output.

---

### Task 3: ADR-029 — polyglot monorepo and publishing

**Files:**
- Create: `docs/adr/0029-polyglot-monorepo-and-publishing.md`

**Interfaces:**
- Consumes: ADR house style; facts from `release-please-config.json`, `.release-please-manifest.json`,
  `.github/workflows/{ci,commits,publish,release-please}.yml`, `frontend/tooling/set-version.mjs`,
  `docs/development/releasing.md`, `gradle.properties`, root `package.json`.
- Produces: `docs/adr/0029-polyglot-monorepo-and-publishing.md`, title `ADR-029: One repository, one version, published
  to GitHub Packages`. Cited by Tasks 22, 26, 39 for lockstep versioning, `"*"` internal ranges and
  `RELEASE_PLEASE_TOKEN`. The guard scripts `frontend/tooling/check-release.mjs` and
  `.github/scripts/check-maven-publications.sh` come from Task 26.

- [ ] **Step 1: Check the facts**

```bash
cd /Users/jorge/IdeaProjects/chawpi
cat release-please-config.json .release-please-manifest.json
command grep -n -i -E 'token|RELEASE_PLEASE' .github/workflows/release-please.yml docs/development/releasing.md
command grep -n '"@chawpi/' frontend/packages/*/package.json | head
```

Expected: one package `"."`, `release-type: simple`, `extra-files` with `gradle.properties`, root `package.json` and
the eleven public frontend packages. The workflow uses `RELEASE_PLEASE_TOKEN`. Internal ranges are `"*"`.

- [ ] **Step 2: Write the ADR with exactly this content**

````markdown
# ADR-029: One repository, one version, published to GitHub Packages

**Status**: accepted · 2026-09-25

## Context

Chawpi is Kotlin libraries and npm packages that change together: a new field type is a backend handler plus a
frontend renderer, and a REST change is both sides at once. Separate repositories would need cross-repository
releases for nearly every change. Separate versions per library would need a compatibility matrix nobody would
keep.

## Decision

**One repository.** `backend/` is a Gradle multi-project (convention plugins in `backend/build-logic`, a module is
any folder with a `build.gradle.kts`), `frontend/` is a yarn workspace (`frontend/packages/*`), `examples/` holds
sample apps (their servers join the Gradle build and their webs join the workspace), and `infra/` holds Docker
Compose. The root carries the shared `.editorconfig`, commitlint and release configuration.

**Conventional Commits.** A husky `commit-msg` hook runs commitlint (`@commitlint/config-conventional`, scopes free),
and CI checks every commit of a pull request plus its title (`amannn/action-semantic-pull-request`), because a
squash merge uses the title as the commit.

**One lockstep version.** release-please runs in manifest mode with a single package `"."` of type `simple`. Its
release pull request bumps `version=` in `gradle.properties`, the root `package.json` and every public
`frontend/packages/*/package.json` (listed one by one in `extra-files`), and writes `CHANGELOG.md`. Merging it tags
`vX.Y.Z` and creates the GitHub Release. The first release is pinned with `release-as: 0.1.0`. `chawpi-bom` aligns
the Maven side.

**Internal npm ranges are `"*"` in the repository.** Yarn links workspace packages whatever the range, and a real
version would have to be bumped in every dependent package on every release, which release-please cannot do for
dependency entries. At publish time,
`frontend/tooling/set-version.mjs <version>` rewrites each package's own version and every `@chawpi/*` entry in
`dependencies`, `peerDependencies` and `optionalDependencies` to the exact release version. A published package
therefore always points at its own release.

**RELEASE_PLEASE_TOKEN.** A tag or release created with the workflow's `GITHUB_TOKEN` does not trigger other
workflows, so the release would never publish. release-please runs with a fine-grained token stored as the
`RELEASE_PLEASE_TOKEN` secret (contents and pull requests: write). `docs/development/releasing.md` has the setup.

**Publishing.** `publish.yml` runs on `release: published`. Maven: `./gradlew publish -Pversion=<tag without v>`
publishes every project that applies `chawpi.publishing` to GitHub Packages. npm: build, `set-version.mjs`, then
`npm publish` for every package under `frontend/packages` that is not `"private": true`. Never published: the
examples, `chawpi-integration-tests`, `@chawpi/smoke`, and the sample webs. Before anything is uploaded, two guard
scripts compare what would be published with the expected set and fail the job on any difference:
`.github/scripts/check-maven-publications.sh` (20 Maven artifacts) and `frontend/tooling/check-release.mjs`
(11 npm packages, release-please coverage, tarball contents). CI runs the same guards on every pull request.

## Consequences

- A consumer never mixes versions: `chawpi-bom:X` and `@chawpi/*@X` are one release.
- A module that changed nothing still gets a new version. That is the price of never needing a compatibility
  table.
- Adding a library means applying `chawpi.publishing` (Maven) or adding the package to release-please
  `extra-files` (npm). The guard scripts fail CI until both lists agree.
- GitHub Packages needs a token even to read, so consumers configure credentials (`releasing.md`, "Consuming a
  published library").
````

- [ ] **Step 3: Run the MD check on the new file**

Expected: no output.

---

### Task 4: ADR-030 — rebrand sapgis to chawpi

**Files:**
- Create: `docs/adr/0030-rebrand-sapgis-to-chawpi.md`

**Interfaces:**
- Consumes: ADR house style; `docs/sapgis-origin.md`; the ADR provenance header already on ADRs 0001–0023.
- Produces: `docs/adr/0030-rebrand-sapgis-to-chawpi.md`, title `ADR-030: Sapgis is renamed chawpi, and its history
  stays readable`. Task 40's allow list for the word "sapgis" follows the "Where the old name stays" list here.

**Sources:** `docs/sapgis-origin.md`, `backend/chawpi-core/src/main/kotlin/chawpi/core/platform/{ChawpiDatabaseProperties,ChawpiWebProperties}.kt`,
`backend/chawpi-core/src/main/kotlin/chawpi/core/common/Errors.kt`, `frontend/packages/core/src/app/config.ts`,
`infra/docker/compose.yml`, `backend/chawpi-core/src/main/resources/db/chawpi/core-seed/`.

- [ ] **Step 1: Check the facts**

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -n -E 'metadataSchema|dataSchema|problemBaseUri' backend/chawpi-core/src/main/kotlin/chawpi/core/platform/*.kt
command grep -n 'class ChawpiException' -r backend/chawpi-core/src/main/kotlin
command grep -n -E "storagePrefix: '|defaultLoginEmail: '" frontend/packages/core/src/app/config.ts
command grep -rn 'admin@chawpi.local' backend/chawpi-core/src/main/resources | head -2
command grep -n -E 'container_name|name:' infra/docker/compose.yml | head
```

Expected: `chawpi` / `app_data` / `https://chawpi.dev/problems`, `ChawpiException`, `storagePrefix: 'chawpi'`,
the seed user, compose names with `chawpi`. Fix any sentence that differs and report it.

- [ ] **Step 2: Write the ADR with exactly this content**

````markdown
# ADR-030: Sapgis is renamed chawpi, and its history stays readable

**Status**: accepted · 2026-09-25

## Context

The platform began as sapgis, a GIS-first app. As libraries, GIS is one optional module among eight (ADR-027), and a
name that promises GIS would mislead an app that has none. The code also carried the name as literals: the
metadata schema in about 120 SQL strings, the `sapgis.*` configuration keys, `SapgisException`, the problem type URI,
the seed user and the browser storage keys.

## Decision

Every identifier is renamed, and every literal that an app might need to change becomes configuration:

| Was | Is |
|---|---|
| package `com.sapgis.<area>` | `chawpi.core.<area>` for core areas, `chawpi.<module>` for modules |
| Maven group, npm scope | `chawpi`, `@chawpi` |
| configuration `sapgis.*`, environment `SAPGIS_*` | `chawpi.*`, `CHAWPI_*` |
| schema literal `sapgis` | `chawpi.database.metadata-schema`, default `chawpi` (`ChawpiSchemas`) |
| data schema `app_data` | unchanged by default, configurable as `chawpi.database.data-schema` |
| `SapgisException` | `ChawpiException` |
| problem type URI | `chawpi.web.problem-base-uri`, default `https://chawpi.dev/problems` |
| seed user `admin@sapgis.local`, always seeded | `admin@chawpi.local`, only with `chawpi.seed.dev=true` |
| browser storage keys `sapgis.*` | prefix `storagePrefix` in `ChawpiApp` config, default `chawpi` |
| Docker Compose project, database, user | `chawpi` |

**History.** The repository starts with a fresh git history. The original repository stays the historical reference,
and `docs/sapgis-origin.md` says where it is and what came from it. Nothing is lost: ADRs 0001–0023 are imported
with the original decisions unchanged, identifiers renamed, and the header line "Imported from sapgis …". Changes to
those decisions are made by new ADRs, never by editing them. `docs/HISTORY.md` keeps every original entry as
written, below the entry "Chawpi starts from sapgis". The original plans and specs stay in `docs/superpowers/`.

**Where the old name stays.** Only where it is history or provenance: `docs/HISTORY.md`, `docs/sapgis-origin.md`,
`docs/superpowers/**`, the provenance header of ADRs 0001–0023, the Context and Decision text of ADRs 0024–0031,
the one README and CLAUDE.md line pointing at `docs/sapgis-origin.md`, tests asserting that a migration or bundle
does not contain the name, the porting tools (`frontend/tooling/port-from-sapgis*.mjs`) and the integration-test
porting scripts, and byte-identical copied data (`examples/gis-sample/perene/`). Everywhere else, code and docs say
"the original app".

## Consequences

- A fresh database gets the same tables as the original, in a schema named `chawpi` (or whatever the app
  configures). There is no in-place migration from an original database, because none exists to migrate.
- Two apps can share one PostgreSQL database by giving each its own metadata and data schema.
- Anyone searching the code for the old name finds only provenance. A hit anywhere else is a leftover, and the
  final P7 check fails on it.
````

- [ ] **Step 3: Run the MD check on the new file**

Expected: no output.

---

### Task 5: ADR-031 — deliberate deviations from the original app

**Files:**
- Create: `docs/adr/0031-deliberate-deviations-from-sapgis.md`

**Interfaces:**
- Consumes: ADR house style; ADR-025 (`0025-extension-spis.md`) already records the link/unlink and audit-after
  changes and the `409` on a label-only edit. This ADR lists them again as one-line entries that link there.
- Produces: `docs/adr/0031-deliberate-deviations-from-sapgis.md`, title `ADR-031: Where chawpi deliberately behaves
  differently from the original`, entries numbered `D1`…`D16`. Module docs (Tasks 12–20) link individual entries as
  `../adr/0031-deliberate-deviations-from-sapgis.md` and name them by number ("ADR-031 D3").

**Sources (each entry's evidence):** ledgers under `.superpowers/sdd/*/progress.md` (lines starting `Ruling:` or
`Note for ADR`), plus the code named in each row.

- [ ] **Step 1: Confirm each entry in the code** (one command per entry group; every command must print a hit)

```bash
cd /Users/jorge/IdeaProjects/chawpi
# D1 uninstalled module -> 404 / 401
command grep -rln -i 'unmapped\|404' backend/chawpi-core/src/test/kotlin | head -3
# D3 GENERATE_DOCUMENT refused without documents
command grep -n 'refused when it is saved' backend/chawpi-automation/src/main/kotlin/chawpi/automation/DocumentIssuer.kt
# D5 FieldType parse trims
command grep -n 'raw.trim().uppercase()' backend/chawpi-core/src/main/kotlin/chawpi/core/metadata/FieldTypeRegistry.kt
# D6 seed opt-in
command grep -n 'chawpi.seed' backend/chawpi-core/src/main/kotlin/chawpi/core/autoconfigure/ChawpiPlatformAutoConfiguration.kt
# D7 agent provider
cat backend/starters/chawpi-spring-boot-starter-agent/build.gradle.kts | command grep -n anthropic
# D10 401 signs out, D11 404-only fallback, D14 default login email
command grep -rn -E "defaultLoginEmail: ''" frontend/packages/core/src/app/config.ts
command grep -rln 'componentUnavailable' frontend/packages/core/src | head -2
# D15 ISSUE invalidates history
command grep -rn -i 'invalidat' frontend/packages/documents/src/api.ts* | head -3
```

If an entry has no evidence, drop it (keep the numbering contiguous) and report it.

- [ ] **Step 2: Write the ADR with exactly this content**

````markdown
# ADR-031: Where chawpi deliberately behaves differently from the original

**Status**: accepted · 2026-09-25

## Context

The library split promised no functional change: same REST API, same behaviour, same UI. Two things forced small
exceptions. First, modules became optional, so situations the original could never meet now happen: a field whose
type's module is gone, a page component nobody draws, an automation action whose module is missing. Second,
porting every line under review surfaced a few bugs that were cheaper to fix than to preserve. This ADR is the one
list of both, so "same behaviour" has a precise meaning.

## Decision

These are the only intended differences. Anything else that behaves differently is a bug.

**Because modules are optional**

- **D1. A route of a module that is not installed.** Could not happen in the original. Answers `404` to an
  authenticated caller and `401` without a token, never `403`, so the frontend reads it as "not installed".
- **D2. Any edit, even label-only, of a field whose type's module is not installed.** Could not happen. Answers
  `409`: the field is revalidated against its handler on every edit (ADR-025).
- **D3. An automation with `GENERATE_DOCUMENT` when chawpi-documents is absent.** Could not happen. Refused when
  saved. If documents is removed later, the run fails with a message instead of issuing.
- **D4. A page component or action kind that no registered module draws.** Could not happen. The component draws
  a muted placeholder, and an action with a null or unknown kind draws nothing.
- **D5. A freshly dropped page action.** Was always `TRANSITION`. Now the first registered kind: `TRANSITION` with
  workflow installed, `NAVIGATE` without it.
- **D6. The development seed user.** Was always created by a migration. Now created only with
  `chawpi.seed.dev=true`, as `admin@chawpi.local` (ADR-030).
- **D7. The AI provider.** Was Anthropic, built in. `chawpi-agent` is now provider-neutral, and
  `chawpi-spring-boot-starter-agent` adds Anthropic as the default. An app can exclude it and add another Embabel
  provider.
- **D8. A switch for core.** Modules have `chawpi.<module>.enabled`, core has none (ADR-024).

**Fixed on the way**

- **D9. Link and unlink of related records.** Were unchecked on the other record's organization and owner, and
  answered `204` or `500`. Both records are now held to the record API's rules, with `404` otherwise and one
  `UPDATE` history row on each (ADR-025).
- **D10. The audit "after" snapshot.** Was the caller's writable projection, so a locked field looked cleared. Now
  every stored field (ADR-025).
- **D11. A `401` in the middle of a session.** Cleared only the token, leaving a signed-in-looking user whose every
  call failed. Now signs the user out and returns to the login page.
- **D12. The record detail page when the custom page fails to load.** Any error fell back to the default page. Now
  only a `404` does. Other errors show the error and a retry, so an outage is not hidden behind a working-looking
  page.
- **D13. A `NAVIGATE` action with no target.** Linked to `/undefined`. Now links to the objects list.
- **D14. The login form's email.** Was prefilled with the seed user. Now empty by default, configurable with
  `ChawpiApp` `config.defaultLoginEmail`.
- **D15. Issuing a document.** The record history did not refresh. Now it does, so the `ISSUE` entry shows at once.
- **D16. Field type names in requests.** Matched exactly after upper-casing. Now surrounding whitespace is trimmed
  first (`" text "` is `TEXT`).

**Kept on purpose, although they look like candidates.** Sections such as geometries stay out of audit diffs and
automation payloads (ADR-019). `RecordService` still opens no transaction of its own (ADR-025). A `MULTI*` geometry
field still cannot be drawn in the UI, because the draw mode is single-part (docs/modules/gis.md, "Known
limitations").

## Consequences

- "No functional change" is testable: the ported integration tests assert the original behaviour everywhere except
  these entries, and each entry has its own test.
- A future difference needs a new entry here, or it is a regression.
````

- [ ] **Step 3: Run the MD check on the new file**

Expected: no output.

---

### Task 6: ADR addenda (0026, 0027) and the ADR index

**Files:**
- Modify: `docs/adr/0026-per-module-migrations.md` (append one section at the end)
- Modify: `docs/adr/0027-gis-optional.md` (one note under the heading `### Illustrative sketch of chawpi-gis (P2, not built here)`)
- Create: `docs/adr/README.md`

**Interfaces:**
- Consumes: the ADR file names of Tasks 1–5 (File map).
- Produces: `docs/adr/README.md`, the index every other doc may link to as `../adr/README.md`.

These two ADRs are chawpi's own (not imported), and the additions are dated addenda, not edits to the decision.

- [ ] **Step 1: Append to `docs/adr/0026-per-module-migrations.md`**

```markdown

## Addendum (2026-09-25, P7): a CHECK has one extending owner

Two core constraints list values that a module adds: `custom_fields`' type CHECK (chawpi-gis adds `GEOMETRY`) and
`audit_log_operation_valid` (chawpi-documents adds `ISSUE`). PostgreSQL cannot append to a CHECK, so the module
drops the constraint and recreates it with its value added. That is safe only while one module extends a given
constraint. If a second module ever needs to add a value to the same CHECK, the constraint moves to a lookup table
or a trigger in core, and this addendum is superseded. The extending owners today: chawpi-gis for the field-type
CHECK, chawpi-documents for the audit-operation CHECK. A migration is never edited to record this (Flyway checksums),
so this addendum is where it is written down.
```

Check the constraint names against the module migrations and use the real names in the text:

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -n -i 'DROP CONSTRAINT\|ADD CONSTRAINT.*CHECK' backend/chawpi-gis/src/main/resources/db/chawpi/gis/*.sql backend/chawpi-documents/src/main/resources/db/chawpi/documents/*.sql
```

Expected: gis drops and re-adds the `custom_fields` type CHECK, and documents drops and re-adds
`audit_log_operation_valid`.

- [ ] **Step 2: Add the note to `docs/adr/0027-gis-optional.md`**

Insert directly under the line `### Illustrative sketch of chawpi-gis (P2, not built here)`:

```markdown

> Addendum (2026-09-25, P7): chawpi-gis was built in P2 along these lines. The module as it is, with its
> properties and routes, is described in [docs/modules/gis.md](../modules/gis.md). This sketch stays as the
> reasoning at the time.
```

- [ ] **Step 3: Write `docs/adr/README.md`**

Generate the list from the files, so no title is mistyped:

```bash
cd /Users/jorge/IdeaProjects/chawpi/docs/adr
for f in 0*.md; do printf -- '- [%s](%s)\n' "$(head -1 "$f" | sed 's/^# //')" "$f"; done
```

The file content is this header, then the generated list (0001–0031, with no 0024–0031 gaps):

```markdown
# Architecture decision records

One file per decision, numbered in order. ADRs 0001–0023 were imported from the original app with identifiers
renamed (see [where chawpi comes from](../sapgis-origin.md)). A decision is never edited to change it: a later ADR amends or
supersedes it and says so in its status line.

```

If a Task 1–5 file is not on disk yet, write its line by hand from the File map title and report "pending".

- [ ] **Step 4: Run the MD check on the three files**

Expected: no output, apart from "pending" links to `../modules/gis.md` (Task 13) while it runs.

---

### Task 7: Architecture overview for the module layout

**Files:**
- Modify (rewrite): `docs/architecture/overview.md`

**Interfaces:**
- Consumes: ADR file names (File map); `docs/modules/<name>.md` names.
- Produces: `docs/architecture/overview.md` with the section anchors `#libraries-and-the-module-graph`,
  `#extension-spis`, `#frontend-packages`, which Tasks 21 and 22 may link to.

**Sources:** `backend/*/build.gradle.kts` (project dependencies), `backend/chawpi-core/src/main/kotlin/chawpi/core/*/`
(package list), SPI interfaces (commands below), `docs/adr/0025-extension-spis.md`,
`frontend/packages/*/package.json`, `backend/chawpi-core/src/test/kotlin/**/CoreArchitectureTest.kt`.

**What is stale today:** the "Shape" diagram lists sapgis-era packages as one app. The "Modules" table mixes core
areas and modules. "Reactive persistence" says "Spring Data R2DBC repositories", but there are none: fixed and
dynamic schema alike go through `DatabaseClient`, with schema names from `ChawpiSchemas`. "Extension points already
in place" describes future phases that are now done.

- [ ] **Step 1: Collect the facts**

```bash
cd /Users/jorge/IdeaProjects/chawpi/backend
for f in chawpi-*/build.gradle.kts; do echo "== $f"; command grep -n 'project(' $f; done
ls chawpi-core/src/main/kotlin/chawpi/core
command grep -rn -E '^(fun )?interface [A-Z]' chawpi-*/src/main/kotlin --include='*.kt' | sed 's|/src/main/kotlin/| |' | cut -c1-150
command grep -rn -i 'spring-data\|R2dbcRepository\|CoroutineCrudRepository' chawpi-*/src/main chawpi-*/build.gradle.kts | head -3
```

Expected: the only project edges are core ← modules and forms ← pages (plus chawpi-test and the starters). There
are no Spring Data repositories (last command prints nothing).

- [ ] **Step 2: Rewrite the file with this outline** (headings exactly as given; body written from Step 1's facts)

```markdown
# Architecture

## Shape
<!-- An app = its own Spring Boot main class + chawpi starters, and its own React entry + @chawpi packages.
     Diagram (text art, like today's): React app (ChawpiApp + modules) -> REST /api + GeoJSON -> Spring Boot 4.1
     WebFlux app (starters: core + chosen modules) -> R2DBC -> PostgreSQL 18 (+ PostGIS only with chawpi-gis)
     -> GeoServer only with chawpi-gis. One deployable per app: still a modular monolith (ADR-001, amended by
     ADR-024). -->

## Libraries and the module graph
<!-- The backend graph as text art, from the spec:
       core <- views, forms, workflow, automation, documents, gis, agent
       core <- forms <- pages
       automation -> documents only through the optional DocumentIssuer port (documents implements it)
     Then one table: module | Maven artifact | starter | npm package | doc. One row each for core (with
     @chawpi/ui), views, forms, pages, workflow, automation, documents, gis, agent, and one "testing" row
     (chawpi-test, @chawpi/testing). The doc column links ../modules/<name>.md.
     Say that CoreArchitectureTest fails the build if core imports a module package. Link ADR-024. -->

## Core packages
<!-- Table: package (chawpi.core.<area>) | responsibility, one row per folder in chawpi-core/src/main/kotlin/chawpi/core
     (admin, audit, autoconfigure, common, data, identity, metadata, organization, platform). Mention the layering
     order enforced by CoreArchitectureTest (platform at the bottom). -->

## Extension SPIs
<!-- Table: SPI | lives in | pattern | implemented by. Rows from Step 1's interfaces and ADR-025:
     FieldTypeHandler + FieldTypeRegistry, RecordQueryContributor, SystemColumnContributor, RecordChangeListener,
     ObjectRemovalListener, FieldUsage, WorkflowStates (NoWorkflowStates default), ObjectCatalog, UserDirectory,
     PageComponentProvider (in chawpi-pages), DocumentIssuer (in chawpi-automation). Lists run in @Order, inside the
     caller's call (no transaction of their own, ADR-025). Link ADR-025. -->

## Frontend packages
<!-- @chawpi/ui, @chawpi/core, one package per module, @chawpi/testing. The ChawpiModule registry in three
     sentences; heavy libraries lazy. Link ADR-028. -->

## Two schemas, two lifecycles
<!-- metadata schema (default chawpi, chawpi.database.metadata-schema) evolved by per-module Flyway runs, each with
     its own history table flyway_history_<module> (ADR-026); data schema (default app_data,
     chawpi.database.data-schema) with one physical table per Custom Object built at runtime by ObjectSchemaManager
     (ADR-004). -->

## Request path
<!-- Keep today's diagram, with "SQL" instead of "SQL -> PostGIS", and note that field-type handlers contribute
     their own SQL fragments (gis: ST_AsGeoJSON / ST_GeomFromGeoJSON). Tenancy sentence kept. -->

## Reactive persistence
<!-- WebFlux: no JPA/Hibernate/Envers. No Spring Data repositories either: every query, fixed or dynamic schema,
     is SQL through DatabaseClient with bound values. Schema names come from ChawpiSchemas (validated once), and
     identifiers from SqlIdentifier. Migrations: Flyway over short-lived JDBC (ADR-008), one run per module
     (ADR-026). Geometry: converted in SQL by chawpi-gis's field type handler (ADR-007). -->

## Security chain
<!-- One SecurityWebFilterChain from core, @Order(0), because Boot's resource-server auto-config always adds a
     chain of its own. A module or app chain needs its own securityMatcher and @Order below 0. Link
     ../modules/core.md#security and ../security/authentication.md. -->

## Decisions
<!-- One line: "Every decision is an ADR: ../adr/README.md". Then a bullet list of the ADRs this page leans on:
     ADR-001, 004, 007, 008, 024, 025, 026, 027, 028, 029, with links. -->
```

The `<!-- -->` blocks are instructions to you, not file content. Replace each with prose and tables.

- [ ] **Step 3: Check that no stale claim survives**

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -n -i -E 'spring data|repositories|future phase|sapgis|phase [0-9]' docs/architecture/overview.md
```

Expected: no output (the word "repositories" must not appear; "repository" as in git is fine if it has to).

- [ ] **Step 4: Run the MD check on `docs/architecture/overview.md`**

Expected: clean, except links to Wave 1 files still being written ("pending").

---

### Task 8: Metadata model and GIS docs — a geometry is a field from chawpi-gis

**Files:**
- Modify: `docs/domain/metadata-model.md`
- Modify: `docs/gis/geometry.md`

**Interfaces:**
- Consumes: `docs/modules/gis.md` (Task 13) by name only; ADR-019, ADR-027.
- Produces: corrected domain and GIS docs.

**Sources:** `backend/chawpi-core/src/main/resources/db/chawpi/core/V1__core.sql` (table `custom_objects`,
`custom_fields`), `backend/chawpi-core/src/main/kotlin/chawpi/core/metadata/{FieldType,FieldTypeHandler,FieldTypeRegistry,ScalarFieldTypes,CustomObject}.kt`,
`backend/chawpi-gis/src/main/kotlin/chawpi/gis/**`, `backend/chawpi-gis/src/main/resources/db/chawpi/gis/*.sql`,
`backend/chawpi-core/src/test/kotlin/**/CoreOnlyApiTest.kt`, `docs/adr/0019-a-geometry-is-a-field.md`,
`docs/adr/0027-gis-optional.md`.

**What is stale today (metadata-model.md):** the tree shows `GeometryDefinition (geometry_type, srid, dimension — held
on the object)`. The CustomObject table lists `geometry_type`, `srid` and `dimension` columns. The paragraph "Geometry
is a property of the object, not a field" contradicts ADR-019. The field-type list says adding a type means "a new
entry in `FieldType`", but a type now comes from a `FieldTypeHandler` bean.

- [ ] **Step 1: Collect the facts**

```bash
cd /Users/jorge/IdeaProjects/chawpi/backend
sed -n '/CREATE TABLE.*custom_objects/,/);/p' chawpi-core/src/main/resources/db/chawpi/core/V1__core.sql
command grep -n -E 'object|val |fun ' chawpi-core/src/main/kotlin/chawpi/core/metadata/ScalarFieldTypes.kt | head -40
ls chawpi-gis/src/main/kotlin/chawpi/gis chawpi-gis/src/main/resources/db/chawpi/gis
command grep -n -E 'bbox|geometry' chawpi-core/src/test/kotlin/chawpi/core/**/CoreOnlyApiTest.kt | head
```

Note which scalar types core registers, which columns `custom_objects` really has, and what a core-only app answers
for a `GEOMETRY` field and for `?bbox=`.

- [ ] **Step 2: Fix `docs/domain/metadata-model.md`**

1. Tree: remove the `GeometryDefinition` line. Under `CustomField` add a child comment line
   `(GEOMETRY fields come from chawpi-gis)`.
2. CustomObject table: keep only the columns `V1__core.sql` has. Remove `geometry_type`, `srid`, `dimension`.
3. Replace the paragraph starting "Geometry is a property of the object" with exactly:

   ```markdown
   An object has no geometry of its own. A geometry is a field (ADR-019): a Custom Field of type `GEOMETRY` with its
   own shape, SRID and dimension, so an object can carry as many as it needs. The `GEOMETRY` type exists only when
   `chawpi-gis` is installed ([gis module](../modules/gis.md)). Without it, core runs on plain PostgreSQL and the
   type is unknown: creating such a field is refused, and editing one that already exists answers `409` (ADR-031 D2).
   ```

4. CustomField section: the types list comes from `ScalarFieldTypes.kt` (core) plus `GEOMETRY` (gis). Replace
   "adding one means a new entry in `FieldType` plus its column type and codec rules" with: "Adding one means a
   `FieldTypeHandler` bean (ADR-025), which owns the column type, validation and SQL. A module ships it, and core
   does not change." Keep the column-type table and add a `GEOMETRY` row: `geometry(<type>, <srid>)` | chawpi-gis
   handler, PostGIS.
5. In "Relationships", "Pages" and any other section that belongs to a module, add one line saying which library
   owns it: "Provided by chawpi-pages ([pages module](../modules/pages.md))". Relationships belong to core.

- [ ] **Step 3: Fix `docs/gis/geometry.md`**

1. Directly under `# GIS and geometry`, add:

   ```markdown
   Everything on this page needs the gis module: `chawpi-spring-boot-starter-gis` on the backend, `gisModule()` from
   `@chawpi/gis` on the frontend, and a PostgreSQL with PostGIS. Without it, chawpi runs on plain PostgreSQL and none
   of these routes, types or parameters exist ([gis module](../modules/gis.md), ADR-027).
   ```

2. Check every class, property, route and SQL function the page names against `chawpi-gis`. Configuration keys
   are `chawpi.gis.*` (`GeoServerProperties` is `chawpi.gis.geoserver`). Routes are `/api/gis/**`. The `bbox` and
   `geometry` record query parameters come from gis's `RecordQueryContributor`. PostGIS is created
   `WITH SCHEMA public` by the gis migration. Fix whatever differs.
3. In "Storage", say that the geometry columns on `custom_fields` are added by gis's migration, not core's
   (ADR-026).
4. Add a final section `## Known limitations` with one bullet: "A `MULTI*` geometry field cannot be drawn in the UI:
   the draw modes are single-part, as in the original app. The API accepts `MULTI*` GeoJSON."

- [ ] **Step 4: Check that no stale claim survives**

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -n -E 'GeometryDefinition|held on the object|property of the object|entry in `FieldType`|sapgis' docs/domain/metadata-model.md docs/gis/geometry.md
```

Expected: no output.

- [ ] **Step 5: Run the MD check on both files**

Expected: clean, except "pending" links to Task 13/18 files.

---

### Task 9: REST API — which module owns which routes

**Files:**
- Modify: `docs/api/rest.md`

**Interfaces:**
- Consumes: `docs/modules/<name>.md` names; ADR-031 D1.
- Produces: `docs/api/rest.md` with a new section `## Modules and routes` right after the intro.

**Sources:** `backend/chawpi-integration-tests/src/testFixtures/kotlin/chawpi/it/support/ModuleRoutes.kt` (read-only:
the 50 module routes by module), controller classes (`command grep -rn -E '@(Request|Get|Post|Put|Delete|Patch)Mapping'
backend/chawpi-*/src/main --include='*.kt'`), `backend/chawpi-core/src/test/kotlin/**/CoreOnlyApiTest.kt` and the
core IT for an unmapped module route (`command grep -rln 'unmapped\|not installed' backend/chawpi-core/src/test`).

- [ ] **Step 1: Write the new section** directly after the intro paragraph ("Base path `/api`. …"):

```markdown
## Modules and routes

Core serves auth, organizations, objects, fields, relationships, records, related records, caller permissions,
audit and history, and admin. Every other route belongs to one module and exists only when that module is
installed (its starter is on the classpath and `chawpi.<module>.enabled` is not `false`):

| Module | Routes | Doc |
|---|---|---|
```

Fill the table with one row per module (views, forms, pages, workflow, automation, documents, gis, agent). The
"Routes" cell holds the path prefixes from `ModuleRoutes.byModule`, for example views:
`/api/metadata/objects/{object}/views`, `/api/objects/{object}/views/**`. The "Doc" cell links
`../modules/<name>.md`. Then add exactly:

```markdown
A route of a module that is not installed answers `404` to an authenticated caller and `401` without a token, never
`403`. The frontend relies on that `404` to tell "not installed" from "not allowed" (ADR-031 D1).
```

- [ ] **Step 2: Tag each existing section with its module**

Under each `##` heading that documents module routes (Pages, Views, Forms, Documents, GIS layers, GIS, Workflows,
Automations, AI assistant), add one line directly after the heading: `Module: chawpi-<module>.` Core sections get no
tag.

- [ ] **Step 3: Check every documented route exists**

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -o -E '^(GET|POST|PUT|DELETE|PATCH) /api/[^ `]*' docs/api/rest.md | sort -u
```

For each line, find its controller mapping (grep the last literal path segment in `backend/chawpi-*/src/main`). A
documented route with no mapping is fixed in the doc (corrected path) or removed, and listed in the report. Do not
add routes the doc never covered, except that a module route listed in `ModuleRoutes.kt` with no doc section at all
gets one line in its module's section.

- [ ] **Step 4: Run the MD check on `docs/api/rest.md`**

Expected: clean, except "pending" module-doc links.

---

### Task 10: Security doc — current enforcement, `ChawpiJwtKey`, sign-out on 401

**Files:**
- Modify: `docs/security/authentication.md`

**Interfaces:**
- Consumes: ADR-031 D11; `docs/modules/core.md` (Task 12) by name.
- Produces: corrected security doc with a new section `## The security chain`.

**Sources:** `backend/chawpi-core/src/main/kotlin/chawpi/core/identity/{ChawpiJwtKey,JwtService,AuthService,AccessPolicy,CurrentUser}.kt`,
`backend/chawpi-core/src/main/kotlin/chawpi/core/autoconfigure/ChawpiSecurityAutoConfiguration.kt`,
`backend/chawpi-core/src/main/kotlin/chawpi/core/platform/JwtProperties.kt`,
`backend/chawpi-core/src/main/kotlin/chawpi/core/metadata/CallerPermissionsService.kt`,
`backend/chawpi-core/src/main/kotlin/chawpi/core/data/RecordService.kt` (field access, `own_records_only`),
`frontend/packages/core/src/auth/` (the 401 handling).

**What is stale today:** "Field-level and record-level permissions are modelled but not enforced yet (phase 7)" is
false: the original enforced them from its "Security enforcement (phase 7)" onward, and chawpi ports that. The doc
also does not say how an app replaces the key or the chain, or what the frontend does on a `401`.

- [ ] **Step 1: Confirm enforcement in the code**

```bash
cd /Users/jorge/IdeaProjects/chawpi/backend/chawpi-core/src/main/kotlin/chawpi/core
command grep -rn -i -E 'own_records_only|ownRecordsOnly|writable|readable' data metadata identity | head -12
command grep -n -E '@Bean|@Order|ConditionalOnMissingBean|ChawpiJwtKey' autoconfigure/ChawpiSecurityAutoConfiguration.kt
command grep -rn -E 'secret|require|32' platform/JwtProperties.kt identity/JwtService.kt autoconfigure/ChawpiSecurityAutoConfiguration.kt | head
```

Write down what is enforced (record-level `own_records_only`, field read/write access), what happens with a
missing or short secret (the app refuses to start, and the message), and which beans are `@ConditionalOnMissingBean`.

- [ ] **Step 2: Edit the doc**

1. "Authentication": keep the claim list. Replace the secret paragraph with: no default secret ships. An app must set
   `chawpi.security.jwt.secret` (env `CHAWPI_JWT_SECRET`), at least 32 bytes, or it refuses to start (quote the
   real condition from Step 1). `chawpi.security.jwt.issuer` defaults to `chawpi`, and `ttl` to 8h.
2. Add a paragraph on the key: the signing key is a `ChawpiJwtKey` bean, which wraps the `SecretKey` in its own type
   so that an app's unrelated `SecretKey` bean can never become the JWT key by accident. An app that wants another
   key declares its own `ChawpiJwtKey` bean.
3. "Authorization": replace the sentence "Field-level and record-level permissions are modelled but not enforced
   yet (phase 7)." with the enforcement you confirmed in Step 1: `own_records_only` limits a role to the records it
   created, and field access hides unreadable fields and refuses writes to unwritable ones. Say that
   `GET /api/permissions` (check the real path in `CallerPermissionsController`) tells the caller what they may do
   (ADR-020).
4. New section `## The security chain`, before "Runtime DDL safety":

   ```markdown
   ## The security chain

   Core declares one `SecurityWebFilterChain` at `@Order(0)`. Spring Boot's reactive resource-server auto-configuration
   always adds a chain of its own, whatever beans exist, so chawpi's must win on order rather than by being the only
   one. Its public paths are `/api/auth/login`, `/api/health`, `/actuator/health/**` and every `OPTIONS` request.
   Everything else needs a valid token. Core's chain is `@ConditionalOnMissingBean`: an app that declares its own
   `SecurityWebFilterChain` bean replaces core's chain entirely, public paths and CORS included. A module that needs
   a chain next to core's declares it in an auto-configuration that runs after core's, with its own
   `securityMatcher` and an `@Order` below `0`. See the [core module](../modules/core.md#security).
   ```

   Check the public path list and the conditions against `ChawpiSecurityAutoConfiguration` and correct them if they
   differ.
5. New section `## In the browser`, after the chain section: the frontend keeps the token under the configured
   `storagePrefix`. A `401` in the middle of a session signs the user out and returns them to the login page,
   instead of only dropping the token (ADR-031 D11). A `404` on a module route means the module is not installed
   (ADR-031 D1).
6. "Development seed": say it exists only with `chawpi.seed.dev=true` (ADR-031 D6).

- [ ] **Step 3: Check that no stale claim survives**

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -n -i -E 'not enforced|phase 7|sapgis' docs/security/authentication.md
```

Expected: no output.

- [ ] **Step 4: Run the MD check**

Expected: clean, except the "pending" link to `../modules/core.md`.

---

### Task 11: Development guide — running, integration tests, suite lock, `it-env.sh`

**Files:**
- Modify: `docs/development/getting-started.md`

**Interfaces:**
- Consumes: `docs/guides/build-your-app.md` (Task 21), `docs/development/releasing.md` by name.
- Produces: a dev guide whose `## Integration tests` section is linked from CLAUDE.md (Task 22) as
  `docs/development/getting-started.md#integration-tests`.

**Sources:** `backend/chawpi-test/src/main/kotlin/chawpi/test/{ChawpiTestDatabase,ChawpiIntegrationTest}.kt`,
`backend/build-logic/src/main/kotlin/chawpi.integration-test.gradle.kts`,
`backend/chawpi-integration-tests/build.gradle.kts` and `it-env.sh` (read-only),
`backend/chawpi-core/src/main/kotlin/chawpi/core/autoconfigure/ChawpiEnvironmentPostProcessor.kt`, `.gitignore`,
`infra/docker/compose.yml`, `examples/README.md` (read-only).

**What is stale today:** `./gradlew :backend:bootRun` does not exist (chawpi is libraries, and an app runs from an
example). `CHAWPI_JWT_SECRET` has no development default. "Adding a field type" describes the enum-plus-codec way,
not the `FieldTypeHandler` SPI. The test section predates external-database mode, the suite lock and PostGIS-per-suite
images.

- [ ] **Step 1: Collect the facts**

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -n -E 'ENV_|CHAWPI_TEST|SUITE_LOCK|DEFAULT_IMAGE|chawpi.test.db.image' backend/chawpi-test/src/main/kotlin/chawpi/test/ChawpiTestDatabase.kt
command grep -n -E 'CHAWPI_TEST|image|register|Test::class|dependsOn' backend/chawpi-integration-tests/build.gradle.kts | head -40
command grep -n 'it-env' .gitignore
command grep -n -E 'profiles|ports|image' infra/docker/compose.yml | head -20
```

Note: the five `CHAWPI_TEST_DB_*` variables (all required once `CHAWPI_TEST_DB_HOST` is set), the sixth
`CHAWPI_TEST_GIS_DB_PORT` used by the PostGIS suites, the default image (`postgres:18`) and the PostGIS image
(`postgis/postgis:18-3.6`), and the IT task names.

- [ ] **Step 2: Rewrite the file with this outline**

```markdown
# Development

## Requirements
<!-- Java 25, Node 26, Yarn 1, Docker. Gradle wrapper pins 9.7.1. -->

## Layout
<!-- backend/ (build-logic, chawpi-*, starters, chawpi-bom, chawpi-test, chawpi-integration-tests), frontend/
     (packages, tooling), examples/, infra/, docs/. One line each. Link ../architecture/overview.md. -->

## Run a sample app
<!-- docker compose -f infra/docker/compose.yml up -d (profiles as compose.yml defines them: core for plain
     postgres, gis for GeoServer). Run a sample server and web as examples/README.md says (link it; do not invent
     task names; if P6 has not written them yet, say "see examples/README.md"). Env: CHAWPI_DB_* defaults from
     ChawpiEnvironmentPostProcessor; CHAWPI_JWT_SECRET required (no default, >= 32 bytes). -->

## Configuration
<!-- Table of the env vars an app reads by default: CHAWPI_DB_HOST/_PORT/_NAME/_USERNAME/_PASSWORD with defaults
     from ChawpiEnvironmentPostProcessor, CHAWPI_JWT_SECRET (required, no default). Point to ../modules/*.md for
     module properties. -->

## Local secrets
<!-- Keep today's text, but the start command becomes the sample server's; ANTHROPIC_API_KEY note stays (EmbabelGate). -->

## Tests
<!-- ./gradlew build = ktlint + unit tests + arch tests, no docker. yarn test / yarn test:tooling. -->

## Integration tests
<!-- 1. Default: Testcontainers. `./gradlew integrationTest` starts postgres:18 for core-only suites and
        postgis/postgis:18-3.6 for suites that need PostGIS (per-suite system property chawpi.test.db.image).
        Needs a local docker daemon. CI runs it on GitHub runners.
     2. Remote docker daemon: published ports are not on localhost, so Testcontainers cannot reach them. Use
        external-database mode: start the databases on the remote host, tunnel them (e.g. 5443 plain, 5442 PostGIS),
        check the tunnel (`nc -z localhost 5443`), and export the six variables (table: name, meaning, example).
        Once CHAWPI_TEST_DB_HOST is set, the other four DB vars are required (no silent defaults: the suite wipes that
        database). --rerun, because build cache could otherwise replay a green result.
     3. `it-env.sh`: a local, git-ignored helper in backend/chawpi-integration-tests that exports those variables for
        this machine. Source it, never execute it; never commit it; it holds machine paths.
     4. Suite lock: each JVM takes a session advisory lock on the external database before its one-time wipe and
        holds it until it exits, so two suites against the same database run one after the other instead of wiping
        each other. Different databases (the plain port and the PostGIS port) have separate locks and may overlap.
        Still: start one suite at a time per database when running by hand; parallel Gradle invocations just queue.
     5. A persistent external database grows leftover physical tables; the wipe runs once per JVM. The old manual
        DROP DATABASE recipe is only needed if a run was killed mid-wipe.
     6. Commands: the IT task names from Step 1 (e.g. how to run one suite). -->

## Conventions
<!-- English, caveman comments; .editorconfig (Kotlin 4, TS/YAML/MD 2, 160 cols) via ktlintFormat and prettier;
     Conventional Commits (commitlint hook + CI); ADRs in docs/adr, history in docs/HISTORY.md. Link
     ../adr/README.md. -->

## Adding a field type
<!-- 1. A FieldTypeHandler bean in the module's auto-configuration (column type, validation, select/bind SQL,
        optional section). Note: core never adds a SELECT alias; a handler that rewrites the select adds its own
        `AS <quoted read name>`.
     2. Frontend: a fieldRenderers entry in the module's ChawpiModule (input, display, settings).
     3. Tests: the handler's unit test plus an IT through the API. No object-specific code anywhere.
     Link ADR-025 and ADR-028. -->

## Releasing
<!-- One line linking releasing.md. -->
```

- [ ] **Step 3: Check that no stale claim survives**

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -n -E ':backend:bootRun|development-only default|FieldValueCodec|metadata-to-zod.ts. and .FieldInput|sapgis' docs/development/getting-started.md
```

Expected: no output. (If `FieldValueCodec` still exists in core and the new text names it on purpose, keep it and
say so in the report.)

- [ ] **Step 4: Run the MD check**

Expected: clean, except "pending" links.

---

### Task 12: Core module doc (+ `@chawpi/core`, `@chawpi/ui` READMEs)

**Files:**
- Create: `docs/modules/core.md`
- Modify: `frontend/packages/core/README.md`, `frontend/packages/ui/README.md` (package README touch-ups only)

**Interfaces:**
- Consumes: the Module doc template, Module fact commands and Package README touch-ups (Global Constraints);
  ADR-024, 025, 026, 028, 031.
- Produces: `docs/modules/core.md` with the anchor `#security` (a `## Security` section inside "What it adds" is not
  enough: make `## Security` its own heading after "Configuration"), linked by Tasks 7 and 10.

**Sources:** `backend/chawpi-core/src/main/kotlin/chawpi/core/**` (all areas), `backend/chawpi-core/src/main/resources/db/chawpi/{core,core-seed}/`,
`backend/starters/chawpi-spring-boot-starter/build.gradle.kts`, `frontend/packages/core/src/{index.ts,app/coreModule.ts,app/config.ts,registry/contract.ts}`,
`frontend/packages/ui/src/{index.ts,theme.css}`, both READMEs.

- [ ] **Step 1: Run the Module fact commands with `M=core`**

`ModuleRoutes.kt` has no `core` entry, and core's frontend module is `frontend/packages/core/src/app/coreModule.ts`,
not `module.tsx`. Use these instead for routes and nav:

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -rhn -E '@RequestMapping\("' backend/chawpi-core/src/main/kotlin | sed 's/.*@RequestMapping("\([^"]*\)").*/\1/' | sort -u
command grep -n -E "id:|path:|labelKey" frontend/packages/core/src/app/coreModule.ts
command grep -n -E '^  [a-zA-Z]+\??:' frontend/packages/core/src/app/config.ts
```

- [ ] **Step 2: Write `docs/modules/core.md` from the template, with these core-specific contents**

- Title `# Core module`. Install: `chawpi-spring-boot-starter` (core plus drivers, Flyway, actuator), `@chawpi/ui` and
  `@chawpi/core`, and `<ChawpiApp config={...} modules={[]} />`. Core is always installed and has no `enabled`
  switch (ADR-024).
- What it adds: identity (login, JWT), organizations, Custom Objects and Fields, relationships, dynamic records and
  related records, caller permissions, audit and history, admin. Route prefixes come from the `@RequestMapping`
  list of Step 1 (link `../api/rest.md` for details). Screens: the core routes and nav groups of `coreModule.ts`.
- Configuration: every property of `ChawpiDatabaseProperties` (`chawpi.database.*`, including `metadata-schema`,
  `data-schema`, `migrate`), `JwtProperties` (`chawpi.security.jwt.*`: `secret` required, no default), and
  `ChawpiWebProperties` (`chawpi.web.problem-base-uri`, `chawpi.web.cors-allowed-origin-patterns`), plus
  `chawpi.seed.dev` (default `false`). Below the table, list the defaults `ChawpiEnvironmentPostProcessor` adds
  (`CHAWPI_DB_*` → `spring.r2dbc.*`, pool 5/20, problem details on).
- `## Security` (own heading, after Configuration): the chain (`@Order(0)`, public paths, `@ConditionalOnMissingBean`,
  module chains with `securityMatcher` and `@Order` below 0), `ChawpiJwtKey`, the `PasswordEncoder` bean. Link
  `../security/authentication.md`.
- Extension points: the SPIs core defines, as in `docs/architecture/overview.md#extension-spis` (list them again
  here with one line each), and the overridable beans from Step 1's `@ConditionalOnMissingBean` list.
- Database: `db/chawpi/core` (history `flyway_history_core`) and the opt-in `db/chawpi/core-seed`
  (`chawpi.seed.dev=true`). Extensions `pgcrypto` created `WITH SCHEMA public`. Table list from `V1__core.sql`.
- Frontend package: `@chawpi/core` exports (`ChawpiApp`, config keys from `config.ts` with defaults: `apiBaseUrl`
  `/api`, `appName`, `storagePrefix` `chawpi`, `defaultLoginEmail` `''`), the registry types, `useChawpiLinks`,
  `useAuth`. `@chawpi/ui`: primitives, `cn`, and `theme.css`, consumed with `@import "@chawpi/ui/theme.css"` plus
  `@source "../node_modules/@chawpi"` in the app's Tailwind entry.
- Also a short `## Your app` subsection inside "Install": never put the app's main class in package `chawpi` or
  below it (ADR-024), and override a bean by declaring one of the same type.
- Without this module: "Core is always installed."
- Behaviour differences: D1, D2, D6, D8, D9, D10, D11, D12, D13, D14, D16.
- Known limitations: none, unless Step 1 shows one.

- [ ] **Step 3: README touch-ups** on `frontend/packages/core/README.md` (link to `docs/modules/core.md`) and
  `frontend/packages/ui/README.md` (link to `docs/modules/core.md`, since ui has no module doc of its own).

- [ ] **Step 4: Name check and MD check**

```bash
cd /Users/jorge/IdeaProjects/chawpi
for k in $(command grep -o -E 'chawpi\.[a-z-]+(\.[a-z-]+)+' docs/modules/core.md | sort -u); do
  camel=$(echo "${k##*.}" | perl -pe 's/-(\w)/\U$1/g'); command grep -rqs "$camel\|${k##*.}" backend/chawpi-core/src/main || echo "unknown property $k"
done
```

Expected: no "unknown property". Then the MD check on the three files: clean except "pending" links.

---

### Task 13: GIS module doc (+ `@chawpi/gis` README)

**Files:**
- Create: `docs/modules/gis.md`
- Modify: `frontend/packages/gis/README.md` (touch-ups only)

**Interfaces:**
- Consumes: Module doc template, fact commands, README touch-ups; ADR-019, ADR-027, ADR-028, ADR-031.
- Produces: `docs/modules/gis.md` with the heading `## Known limitations`, linked by Tasks 5, 6 and 8.

**Sources:** `backend/chawpi-gis/src/main/**` (field type handler, `RecordQueryContributor`, `ObjectRemovalListener`,
`FieldUsage`, layer publishing, `GeoServerProperties`, `ChawpiGisProperties`, the MAP `PageComponentProvider` in
`ChawpiGisPagesAutoConfiguration`), `backend/chawpi-gis/src/main/resources/db/chawpi/gis/`,
`backend/starters/chawpi-spring-boot-starter-gis/build.gradle.kts`, `frontend/packages/gis/**`, `infra/docker/compose.yml`
(GeoServer profile), `docs/gis/geometry.md`.

- [ ] **Step 1: Run the Module fact commands with `M=gis`**

- [ ] **Step 2: Write `docs/modules/gis.md` from the template, with these gis-specific contents**

- Install: the gis starter, and a PostgreSQL with PostGIS (the image `postgis/postgis:18-3.6`, as compose uses).
  Frontend: `@chawpi/gis` plus `maplibre-gl`, `terra-draw` and `terra-draw-maplibre-gl-adapter` exactly as the
  package README says, and `gisModule({ workerUrl })` with the README's worker recipe (link it; do not copy it).
- What it adds: the `GEOMETRY` field type (handler with section `geometries`), the `bbox` and `geometry` record query
  parameters, feature endpoints, GeoServer layer publishing, the `MAP` page component (at `@Order(100)`). Routes from
  `ModuleRoutes.kt` (8). Screens: map and layers pages.
- Configuration: `ChawpiGisProperties` (`chawpi.gis.enabled`) and every `GeoServerProperties` property
  (`chawpi.gis.geoserver.*`, with its defaults). Say that the GeoServer datastore schema follows
  `chawpi.database.data-schema`.
- Extension points: implements `FieldTypeHandler`, `RecordQueryContributor`, `ObjectRemovalListener` and
  `FieldUsage` (unpublishing a layer when an object or field goes), and pages' `PageComponentProvider` (MAP, only when
  chawpi-pages is present).
- Database: `db/chawpi/gis` (history `flyway_history_gis`): `postgis` `WITH SCHEMA public`, the geometry columns on
  `custom_fields`, and the type CHECK redefined with `GEOMETRY` (ADR-026 addendum).
- Frontend: `gisModule` options, `GeometryField` renderer, `MapView` lazy (MapLibre never in the initial chunk), the
  i18n namespace `gis`, the exported message bundle (`gisMessages`, check `index.ts`). Field payload:
  `{ geometryType, srid }` with defaults `POLYGON`/`4326`, and `dimension` never sent.
- Without this module: plain PostgreSQL works. `GEOMETRY` is an unknown type, gis routes answer `404`, and the map
  screens and MAP component are absent (a page that still has a MAP component draws the placeholder, D4).
- Behaviour differences: D1, D2, D4.
- Known limitations: exactly this bullet: "A `MULTI*` geometry field cannot be drawn in the UI: the draw modes are
  single-part, as in the original app. The API accepts `MULTI*` GeoJSON." Link `../gis/geometry.md` for storage and
  wire format.

- [ ] **Step 3: README touch-ups on `frontend/packages/gis/README.md`**

- [ ] **Step 4: MD check on both files.** Expected: clean except "pending" links.

---

### Task 14: Documents module doc (+ `@chawpi/documents` README)

**Files:**
- Create: `docs/modules/documents.md`
- Modify: `frontend/packages/documents/README.md` (touch-ups only)

**Interfaces:**
- Consumes: Module doc template, fact commands, README touch-ups; ADR-023, ADR-031.
- Produces: `docs/modules/documents.md`.

**Sources:** `backend/chawpi-documents/src/main/**` (including `ChawpiDocumentsAutomationAutoConfiguration`, which
implements automation's `DocumentIssuer`), `backend/chawpi-documents/src/main/resources/db/chawpi/documents/`,
`frontend/packages/documents/**` (`print.css`, history renderer for `ISSUE`, record panel, print route).

- [ ] **Step 1: Run the Module fact commands with `M=documents`**

- [ ] **Step 2: Write `docs/modules/documents.md` from the template, with these contents**

- What it adds: document types per object (a template edited in tiptap), issuing a frozen document from a record
  (ADR-023), numbering (counter), the print page `/documents/:id/print`, the `ISSUE` operation in record history.
  Routes: the 8 from `ModuleRoutes.kt`.
- Configuration: `chawpi.documents.enabled`.
- Extension points: implements automation's `DocumentIssuer` when chawpi-automation is present, so a workflow state
  or automation can issue a document. Otherwise it is standalone.
- Database: `db/chawpi/documents`: its tables, the FK `audit_log.document_id`, and the `audit_log_operation_valid`
  CHECK redefined with `ISSUE` (ADR-026 addendum).
- Frontend: `documentsModule()`, the history renderer and value formatter for `ISSUE` (the "info" tone), the record
  panel, `TemplateEditor` only through its lazy route (tiptap stays lazy, ADR-028), and `print.css` shipped in
  `dist/print.css` (say how the app imports it, from the README).
- Without this module: no issue button, no print route, routes `404`. An automation `GENERATE_DOCUMENT` action is
  refused when saved (D3).
- Behaviour differences: D3, D15.

- [ ] **Step 3: README touch-ups on `frontend/packages/documents/README.md`**

- [ ] **Step 4: MD check on both files.** Expected: clean except "pending" links.

---

### Task 15: Workflow module doc (+ `@chawpi/workflow` README)

**Files:**
- Create: `docs/modules/workflow.md`
- Modify: `frontend/packages/workflow/README.md` (touch-ups; its line "as it did in sapgis" becomes "as it did in the
  original app")

**Interfaces:**
- Consumes: Module doc template, fact commands, README touch-ups; ADR-013, ADR-018, ADR-031.
- Produces: `docs/modules/workflow.md`.

**Sources:** `backend/chawpi-workflow/src/main/**` (`SystemColumnContributor` for `workflow_state`, `WorkflowStates`
implementation, `ChawpiWorkflowPagesAutoConfiguration` for the WORKFLOW component at `@Order(200)`),
`db/chawpi/workflow`, `frontend/packages/workflow/**` (canvas builder, `WorkflowPanel`, `TRANSITION` page action).

- [ ] **Step 1: Run the Module fact commands with `M=workflow`**

- [ ] **Step 2: Write `docs/modules/workflow.md` from the template, with these contents**

- What it adds: a workflow per object (states, transitions, who may apply them), drawn on a canvas (ADR-018), the
  state kept on the record in `workflow_state` (ADR-013), the transitions API, the WORKFLOW page component and the
  TRANSITION page action. Routes: the 5 from `ModuleRoutes.kt`.
- Configuration: `chawpi.workflow.enabled`.
- Extension points: implements `WorkflowStates` (replacing core's `NoWorkflowStates`) and `SystemColumnContributor`
  (reserves `workflow_state`), plus pages' `PageComponentProvider` (WORKFLOW) when chawpi-pages is present.
- Database: `db/chawpi/workflow`, its tables.
- Frontend: `workflowModule()`, `WorkflowBuilderPage` only as a lazy route (xyflow never in the initial chunk, and not
  exported from the index), `WorkflowPanel` record panel, the TRANSITION action (with pages installed, a fresh
  ACTION defaults to TRANSITION, D5).
- Without this module: records have no state transitions, `NoWorkflowStates` answers. A field named `workflow_state`
  is not reserved, and installing workflow later then finds the column taken (ADR-025 consequence). Say so.
- Behaviour differences: D4, D5.

- [ ] **Step 3: README touch-ups on `frontend/packages/workflow/README.md`**

- [ ] **Step 4: MD check on both files.** Expected: clean except "pending" links.

---

### Task 16: Automation module doc (+ `@chawpi/automation` README)

**Files:**
- Create: `docs/modules/automation.md`
- Modify: `frontend/packages/automation/README.md` (touch-ups only)

**Interfaces:**
- Consumes: Module doc template, fact commands, README touch-ups; ADR-016, ADR-031.
- Produces: `docs/modules/automation.md`.

**Sources:** `backend/chawpi-automation/src/main/**` (`AutomationProperties`, `AutomationDrain`, `WebhookSender`,
`AutomationDispatcher`, `DocumentIssuer`, `AutomationFieldUsage`, the `RecordChangeListener`), `db/chawpi/automation`,
`frontend/packages/automation/**`.

- [ ] **Step 1: Run the Module fact commands with `M=automation`**

- [ ] **Step 2: Write `docs/modules/automation.md` from the template, with these contents**

- What it adds: rules that answer record changes, a queue drained in the background, a run log, actions (list the
  `ActionType` values from the code), webhooks. Routes: the 7 from `ModuleRoutes.kt`.
- Configuration: every `AutomationProperties` property with its default and its clamp (`batch-size` 1–200,
  `max-depth` 1–10), `poll-interval` `0` disables the background drain, `allow-private-webhooks` `false` (a webhook
  to a private address is refused, to prevent request forgery), `webhook-timeout`.
- Extension points: implements `RecordChangeListener` and `FieldUsage`. Defines `DocumentIssuer` (optional port;
  chawpi-documents implements it).
- Database: `db/chawpi/automation`, its tables.
- Frontend: `automationModule()`, builder and runs pages, nav entries in core's `automation` group.
- Without this module: nothing reacts to record changes, and the routes `404`.
- Behaviour differences: D3.
- Known limitations: listeners run inside the caller's request without a transaction of their own (ADR-025). Say it
  as a fact, not a limitation, under "Extension points" instead if that reads better.

- [ ] **Step 3: README touch-ups on `frontend/packages/automation/README.md`**

- [ ] **Step 4: MD check on both files.** Expected: clean except "pending" links.

---

### Task 17: Agent module doc (+ `@chawpi/agent` README, agent starter README)

**Files:**
- Create: `docs/modules/agent.md`
- Create: `backend/starters/chawpi-spring-boot-starter-agent/README.md`
- Modify: `frontend/packages/agent/README.md` (touch-ups only)

**Interfaces:**
- Consumes: Module doc template, fact commands, README touch-ups; ADR-014, ADR-015, ADR-031 D7.
- Produces: the three files. The starter README is the "agent starter README about the default provider" deferred
  from P2.

**Sources:** `backend/chawpi-agent/src/main/**` (`AgentProperties`, `EmbabelGate`, `ChawpiAgentWorkflowAutoConfiguration`),
`backend/chawpi-agent/build.gradle.kts`, `backend/starters/chawpi-spring-boot-starter-agent/build.gradle.kts`,
`gradle/libs.versions.toml` (Embabel coordinates), `frontend/packages/agent/**`.

- [ ] **Step 1: Run the Module fact commands with `M=agent`**, then:

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -n -i embabel gradle/libs.versions.toml backend/chawpi-agent/build.gradle.kts backend/starters/chawpi-spring-boot-starter-agent/build.gradle.kts
command grep -rn -i 'ANTHROPIC_API_KEY\|api-key\|apiKey' backend/chawpi-agent/src/main | head
```

- [ ] **Step 2: Write `docs/modules/agent.md` from the template, with these contents**

- What it adds: the assistant (read-only tools over the platform's services, ADR-014), run on Embabel (ADR-015).
  Routes: `GET /api/agent/status`, `POST /api/agent/ask`.
- Configuration: every `AgentProperties` property with default and clamp (`max-iterations` 1–16, `max-tokens`
  1024–64000). `available` = enabled and an API key is set. Say where the key comes from (the property or env var
  Step 1 shows).
- Install, provider: `chawpi-agent` is provider-neutral (only `embabel-agent-starter`). `chawpi-spring-boot-starter-agent`
  adds `embabel-agent-starter-anthropic`, the original app's default (D7). To use another provider, depend on
  `chawpi-agent` plus the Embabel provider starter of your choice instead of the agent starter, or exclude
  `embabel-agent-starter-anthropic` from the starter.
- Without an API key: the app boots, the status route reports the assistant unavailable, and everything else works
  (`EmbabelGate`). Without the module: the routes `404` and the assistant screen is absent.
- Behaviour differences: D7.

- [ ] **Step 3: Write `backend/starters/chawpi-spring-boot-starter-agent/README.md` with exactly this content**

```markdown
# chawpi-spring-boot-starter-agent

The chawpi starter plus `chawpi-agent`, the AI assistant, with Anthropic as the model provider.

`chawpi-agent` itself is provider-neutral: it depends only on `embabel-agent-starter`. This starter adds
`embabel-agent-starter-anthropic`, the provider the original app always used, so the assistant works as soon as an
Anthropic API key is configured. Without a key the app still boots and the assistant reports itself unavailable.

To use another provider, depend on `chawpi:chawpi-agent` plus that provider's Embabel starter instead of this
starter, or keep this starter and exclude the Anthropic provider:

    implementation("chawpi:chawpi-spring-boot-starter-agent") {
        exclude(group = "com.embabel.agent", module = "embabel-agent-starter-anthropic")
    }

See [docs/modules/agent.md](../../../docs/modules/agent.md).
```

Check the exclude group against `gradle/libs.versions.toml` and correct it if the Embabel group differs.

- [ ] **Step 4: README touch-ups on `frontend/packages/agent/README.md`**

- [ ] **Step 5: MD check on the three files.** Expected: clean except "pending" links.

---

### Task 18: Pages module doc (+ `@chawpi/pages` README)

**Files:**
- Create: `docs/modules/pages.md`
- Modify: `frontend/packages/pages/README.md` (touch-ups only)

**Interfaces:**
- Consumes: Module doc template, fact commands, README touch-ups; ADR-011, ADR-021, ADR-022, ADR-031.
- Produces: `docs/modules/pages.md`.

**Sources:** `backend/chawpi-pages/src/main/**` (defines `PageComponentProvider`, registers HISTORY itself, owns
`GET /api/metadata/objects/{object}/pages`), `backend/chawpi-pages/build.gradle.kts` (depends on forms),
`db/chawpi/pages`, `frontend/packages/pages/**` (builder with dnd-kit, templates, registry slots).

- [ ] **Step 1: Run the Module fact commands with `M=pages`**

- [ ] **Step 2: Write `docs/modules/pages.md` from the template, with these contents**

- What it adds: pages defined as metadata (ADR-011), a tree of components (ADR-021) with templates (ADR-022), a
  record-detail page per object, the drag-and-drop builder. Routes: the 8 from `ModuleRoutes.kt`. Needs chawpi-forms
  (the starter brings it).
- Configuration: `chawpi.pages.enabled`.
- Extension points: defines `PageComponentProvider`. The core component types (list them from the code) plus HISTORY
  are its own; gis adds MAP (`@Order(100)`) and workflow adds WORKFLOW (`@Order(200)`). A module adds a component type
  with a provider bean, and the frontend adds its renderer through `pageComponents` (ADR-028).
- Frontend: `pagesModule()`, `PageBuilderPage` only as a lazy route (dnd-kit lazy, not exported), the templates, the
  page actions slot (NAVIGATE from core, TRANSITION from workflow).
- Without this module: record detail pages fall back to the default page derived from metadata (core, on `404`,
  D12). The builder is absent.
- Behaviour differences: D4, D5, D12, D13.

- [ ] **Step 3: README touch-ups on `frontend/packages/pages/README.md`**

- [ ] **Step 4: MD check on both files.** Expected: clean except "pending" links.

---

### Task 19: Views and forms module docs (+ their READMEs)

**Files:**
- Create: `docs/modules/views.md`, `docs/modules/forms.md`
- Modify: `frontend/packages/views/README.md`, `frontend/packages/forms/README.md` (touch-ups only)

**Interfaces:**
- Consumes: Module doc template, fact commands, README touch-ups; ADR-012.
- Produces: the two module docs.

**Sources:** `backend/chawpi-views/src/main/**`, `backend/chawpi-forms/src/main/**`, their `db/chawpi/<m>` migrations,
`frontend/packages/{views,forms}/**`.

- [ ] **Step 1: Run the Module fact commands twice, with `M=views` and `M=forms`**

- [ ] **Step 2: Write `docs/modules/views.md` from the template**

- What it adds: saved list configurations per object (columns, filters, sort, page size, ADR-012), the view builder,
  and `GET /api/metadata/objects/{object}/views` (moved out of core's metadata controller, same URL). Routes: the 6
  from `ModuleRoutes.kt`.
- Configuration: `chawpi.views.enabled`. Database: `db/chawpi/views`. Frontend: `viewsModule()`, nav in core's
  `builder` group.
- Without this module: record lists use the default columns derived from metadata. Behaviour differences: None.

- [ ] **Step 3: Write `docs/modules/forms.md` from the template**

- What it adds: named field arrangements in sections (ADR-012), the form builder (drag the object's own fields), and
  `GET /api/metadata/objects/{object}/forms`. Routes: the 6 from `ModuleRoutes.kt`. chawpi-pages depends on it.
- Configuration: `chawpi.forms.enabled`. Database: `db/chawpi/forms`. Frontend: `formsModule()`.
- Without this module: record forms show every field in metadata order. Behaviour differences: None.

- [ ] **Step 4: README touch-ups on both READMEs**

- [ ] **Step 5: MD check on the four files.** Expected: clean except "pending" links.

---

### Task 20: Testing doc — `chawpi-test` and `@chawpi/testing` (+ README)

**Files:**
- Create: `docs/modules/testing.md`
- Modify: `frontend/packages/testing/README.md` (touch-ups only)

**Interfaces:**
- Consumes: README touch-ups; `docs/development/getting-started.md#integration-tests` (Task 11) by name.
- Produces: `docs/modules/testing.md`. This doc does not use the module template: it has the outline below.

**Sources:** `backend/chawpi-test/src/main/kotlin/chawpi/test/{ChawpiIntegrationTest,ChawpiTestDatabase,ChawpiContextRunner}.kt`,
`backend/chawpi-test/build.gradle.kts`, `backend/build-logic/src/main/kotlin/chawpi.integration-test.gradle.kts`,
`frontend/packages/testing/src/**` (`renderWithProviders`, `mockFetch`).

- [ ] **Step 1: Collect the facts**

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -n -E '^(abstract )?class|^object|fun [a-zA-Z]+\(|@Tag|@SpringBootTest' backend/chawpi-test/src/main/kotlin/chawpi/test/*.kt | head -30
command grep -n '^export' frontend/packages/testing/src/index.ts
command grep -rn 'coreModule' frontend/packages/testing/src | head -3
```

- [ ] **Step 2: Write `docs/modules/testing.md` with this outline**

```markdown
# Testing your app

## Backend: chawpi-test
<!-- testImplementation("chawpi:chawpi-test"). ChawpiIntegrationTest: the base class, what it boots, how to log in
     (the helpers Step 1 lists), the tag "integration" and the chawpi.integration-test Gradle convention (or the
     plain equivalent for an app outside this repo: a Test task including the tag). Database: Testcontainers
     postgres:18 by default; chawpi.test.db.image=postgis/postgis:18-3.6 for gis; external database via the five
     CHAWPI_TEST_DB_* variables and the suite lock (link ../development/getting-started.md#integration-tests).
     ChawpiContextRunner for auto-configuration tests without a database. -->

## Frontend: @chawpi/testing
<!-- renderWithProviders(ui, { modules, ... }) and mockFetch(routes, { baseUrl }). Note: renderWithProviders does
     not register coreModule by default; a module test that needs core's nav groups passes coreModule itself
     (check Step 1's grep and state what the code does). -->

## Example
<!-- One short backend test and one short frontend test, taken from real tests in this repo (cite the file). -->
```

- [ ] **Step 3: README touch-ups on `frontend/packages/testing/README.md`** (link to `docs/modules/testing.md`)

- [ ] **Step 4: MD check on both files.** Expected: clean except "pending" links.

---

### Task 21: "Build your app" guide and the modules index

**Files:**
- Create: `docs/guides/build-your-app.md`
- Create: `docs/modules/README.md`

**Interfaces:**
- Consumes: module doc names (File map), ADR-024, ADR-028, ADR-029; `docs/development/releasing.md` section
  "Consuming a published library"; `frontend/packages/core/README.md` section on a full app.
- Produces: `docs/guides/build-your-app.md` (linked from README.md by Task 22 and from every module doc's "Install"),
  `docs/modules/README.md` (the index of module docs).

**Sources:** `docs/development/releasing.md`, `backend/build-logic/src/main/kotlin/chawpi.publishing.gradle.kts`
(repository URL), `gradle/libs.versions.toml` (Kotlin, Boot versions), `frontend/packages/core/README.md`,
`frontend/packages/core/package.json` (`peerDependencies`), `frontend/packages/ui/README.md` (Tailwind),
`frontend/packages/gis/README.md` (worker recipe), `examples/*/server` and `examples/*/web` if P6 has written them
(read-only; if absent, the core README is the reference).

- [ ] **Step 1: Collect the facts**

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -n -E '^kotlin|^spring-boot|^springBoot|^boot' gradle/libs.versions.toml
command grep -n 'maven.pkg.github.com' backend/build-logic/src/main/kotlin/chawpi.publishing.gradle.kts docs/development/releasing.md
node -p "JSON.stringify(require('./frontend/packages/core/package.json').peerDependencies, null, 1)"
sed -n '/^## /p' frontend/packages/core/README.md
ls examples/*/server examples/*/web 2>/dev/null | head
```

- [ ] **Step 2: Write `docs/guides/build-your-app.md` with this outline** (the Kotlin and TSX snippets below are
  required content; adjust versions and names only to match Step 1)

````markdown
# Build your app

<!-- One paragraph: an app is your own Spring Boot main class plus chawpi starters, and your own React entry plus
     @chawpi packages. Start minimal (core only, plain PostgreSQL), add modules one line at a time. -->

## Before you start
<!-- Java 25, Node 26, PostgreSQL 18 (PostGIS only for gis), a GitHub token with read:packages. Registry setup for
     Gradle and npm: link ../development/releasing.md#consuming-a-published-library (check the real anchor). -->

## A minimal app: backend

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    id("org.springframework.boot") version "4.1.1"
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/hneyra/chawpi")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation(platform("chawpi:chawpi-bom:0.1.0"))
    implementation("chawpi:chawpi-spring-boot-starter")
}

kotlin { jvmToolchain(25) }
```

```kotlin
// src/main/kotlin/com/example/myapp/MyApp.kt
package com.example.myapp

import chawpi.core.autoconfigure.ChawpiApplication
import org.springframework.boot.runApplication

@ChawpiApplication
class MyApp

fun main(args: Array<String>) {
    runApplication<MyApp>(*args)
}
```

```yaml
# src/main/resources/application.yml
chawpi:
  security:
    jwt:
      secret: ${CHAWPI_JWT_SECRET}
  seed:
    dev: true   # admin@chawpi.local / admin, development only
server:
  port: 8090
```

<!-- Then: CHAWPI_DB_* defaults (localhost:5432, chawpi/chawpi/chawpi) and how to override; the package rule (never
     package chawpi, ADR-024); what you get at /api (link ../api/rest.md). -->

## A minimal app: frontend

<!-- package.json dependencies: @chawpi/ui, @chawpi/core, and the peers from Step 1 with those ranges. Then: -->

```tsx
// src/main.tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { ChawpiApp } from '@chawpi/core'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ChawpiApp config={{ apiBaseUrl: '/api', appName: 'My App' }} modules={[]} />
  </StrictMode>
)
```

```css
/* src/index.css */
@import 'tailwindcss';
@import '@chawpi/ui/theme.css';
@source '../node_modules/@chawpi';
```

<!-- Vite dev proxy: /api -> http://localhost:8090. Check every line above against the core and ui READMEs
     (ChawpiApp may need no extra providers; if the README shows more, use the README's version). -->

## Add modules
<!-- Table: module | backend starter | frontend package + registration | extra requirements | doc. One row per
     module (views, forms, pages, workflow, automation, documents, gis, agent). Extra requirements: pages brings
     forms; gis needs PostGIS and maplibre-gl + terra-draw + adapter + workerUrl; agent needs an Anthropic key
     (or another provider, see agent doc); documents + automation connect through DocumentIssuer. -->

## A full app
<!-- The build.gradle.kts dependencies block with every starter, and the ChawpiApp modules array with every module
     factory, in the order the core README's full-app section uses. -->

## Configure
<!-- The ten settings people change most (db, jwt, schemas, cors origins, seed, module enabled flags, geoserver url,
     agent key), each linking its module doc. -->

## Override a bean
<!-- Example: an app-defined PasswordEncoder @Bean replaces core's (ConditionalOnMissingBean). One Kotlin snippet. -->

## Write your own module
<!-- Backend: a library with an @AutoConfiguration registered in AutoConfiguration.imports, beans implementing core
     SPIs (e.g. a FieldTypeHandler), its own ModuleMigration and chawpi.<name>.enabled. Frontend: a ChawpiModule
     factory. Point to ADR-025, ADR-028 and an existing small module (views) as the model. -->

## Test it
<!-- Link ../modules/testing.md. -->

## Examples
<!-- Link ../../examples/README.md: simple-sample (core only, plain PostgreSQL), documents-sample, gis-sample,
     full-sample. -->
````

- [ ] **Step 3: Write `docs/modules/README.md`**

```markdown
# Modules

Core is always installed. Every other module is one backend starter plus one frontend package, and an app adds it
by listing both. [Build your app](../guides/build-your-app.md) shows how.

| Module | What it adds | Backend | Frontend |
|---|---|---|---|
| [core](core.md) | objects, fields, records, relationships, identity, audit, admin | `chawpi-spring-boot-starter` | `@chawpi/core`, `@chawpi/ui` |
| [views](views.md) | saved list configurations | `chawpi-spring-boot-starter-views` | `@chawpi/views` |
| [forms](forms.md) | field arrangements in sections | `chawpi-spring-boot-starter-forms` | `@chawpi/forms` |
| [pages](pages.md) | metadata-defined pages and their builder | `chawpi-spring-boot-starter-pages` | `@chawpi/pages` |
| [workflow](workflow.md) | states and transitions on records | `chawpi-spring-boot-starter-workflow` | `@chawpi/workflow` |
| [automation](automation.md) | rules that react to record changes | `chawpi-spring-boot-starter-automation` | `@chawpi/automation` |
| [documents](documents.md) | document templates, issuing, printing | `chawpi-spring-boot-starter-documents` | `@chawpi/documents` |
| [gis](gis.md) | geometry fields, maps, GeoServer layers | `chawpi-spring-boot-starter-gis` | `@chawpi/gis` |
| [agent](agent.md) | the AI assistant | `chawpi-spring-boot-starter-agent` | `@chawpi/agent` |
| [testing](testing.md) | test fixtures for your app | `chawpi-test` | `@chawpi/testing` |
```

- [ ] **Step 4: Run the MD check on both files**

Expected: clean except "pending" links to module docs still being written.

- [ ] **Step 5: Name check** (every artifact, package and property named in the two files exists)

```bash
cd /Users/jorge/IdeaProjects/chawpi
node -e '
const fs = require("fs"), path = require("path"), root = process.cwd()
const artifacts = new Set([...fs.readdirSync("backend"), ...fs.readdirSync("backend/starters")])
const pkgs = new Set(fs.readdirSync("frontend/packages").map((d) => { try { return require(path.join(root, "frontend/packages", d, "package.json")).name } catch { return null } }))
const props = new Set(["chawpi.seed.dev", "chawpi.test.db.image"])
const kotlinPackages = new Set()
const walk = (d) => { for (const e of fs.readdirSync(d, { withFileTypes: true })) {
  const p = path.join(d, e.name)
  if (e.isDirectory()) { if (!["build", "node_modules", ".gradle"].includes(e.name)) walk(p); continue }
  if (!e.name.endsWith(".kt")) continue
  const t = fs.readFileSync(p, "utf8")
  const pkg = t.match(/^package ([\w.]+)/m); if (pkg) kotlinPackages.add(pkg[1])
  const m = t.match(/@ConfigurationProperties\((?:prefix = )?"([^"]+)"\)/); if (!m) continue
  for (const v of t.matchAll(/^\s+val (\w+)\s*:/gm)) props.add(m[1] + "." + v[1].replace(/[A-Z]/g, (c) => "-" + c.toLowerCase()))
} }
walk("backend")
const plugins = new Set(fs.readdirSync("backend/build-logic/src/main/kotlin").map((f) => f.replace(/\.gradle\.kts$/, "")))
let bad = 0
for (const f of process.argv.slice(1)) {
  const t = fs.readFileSync(f, "utf8")
  for (const m of t.matchAll(/chawpi:(chawpi-[a-z-]+)/g)) if (!artifacts.has(m[1])) { console.log(f + ": unknown artifact " + m[1]); bad++ }
  for (const m of t.matchAll(/@chawpi\/([a-z-]+)/g)) if (!pkgs.has("@chawpi/" + m[1])) { console.log(f + ": unknown package @chawpi/" + m[1]); bad++ }
  for (const m of t.matchAll(/`(chawpi\.[a-z][a-z0-9.-]*[a-z0-9])`/g)) {
    const k = m[1]
    if (props.has(k) || kotlinPackages.has(k) || plugins.has(k) || [...props].some((p) => p.startsWith(k + "."))) continue
    console.log(f + ": unknown property/package " + k); bad++
  }
}
process.exit(bad ? 1 : 0)' docs/guides/build-your-app.md docs/modules/README.md
```

Expected: no output, exit 0. Any hit is a wrong name in the doc: fix the doc.

---

### Task 22: CLAUDE.md and README.md

**Files:**
- Modify (rewrite): `CLAUDE.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: doc names (File map); `docs/development/getting-started.md#integration-tests` (Task 11).
- Produces: the project rules every future agent reads first.

- [ ] **Step 1: Write `CLAUDE.md` with exactly this content**

````markdown
# Chawpi — metadata-driven application platform, as libraries

Reusable libraries (Spring Boot starters + npm packages) extracted from the original app
([sapgis origin](docs/sapgis-origin.md)). An app adds the core and opts into modules; metadata drives schema, API
and UI at runtime.

## Non-negotiable stack

- **Backend**: Kotlin 2.4.20, Spring Boot 4.1 **WebFlux** (reactive), Gradle 9.7.1 (Kotlin DSL, version catalog,
  convention plugins in `backend/build-logic`), JDK 25
- **Database**: PostgreSQL 18 (+ PostGIS only with `chawpi-gis`, + pgvector optional)
- **Frontend**: React 19.3 (yarn workspaces) + Vite 8 + Tailwind CSS 4 + shadcn-style components + i18next +
  react-router + react-query + zod
- **GIS module**: GeoServer (WMS/WFS/WMTS), MapLibre GL JS
- **Infra**: Docker Compose for local development

## Architectural rules

1. **Metadata-driven**: never generate code per Custom Object. Metadata drives behaviour at runtime.
2. **WebFlux is reactive** ⇒ JPA / Hibernate / Envers are forbidden, and so are Spring Data repositories. Every
   query, fixed schema or dynamic, is SQL through `DatabaseClient` with bound values. Schema names come from
   `ChawpiSchemas` (`${schemas.metadata}.<table>`, `schemas.dataTable(<table>)`), never a literal.
3. **Libraries, not an app**: `chawpi-core` never depends on a module (`CoreArchitectureTest`). Modules extend the
   core only through its SPIs (field types, query contributors, listeners, page components, ports). Every module
   ships its own auto-configuration, `chawpi.<module>.enabled` switch, properties and Flyway migrations (ADR-024,
   ADR-025, ADR-026).
4. **No component scanning of library code**: beans are declared in auto-configurations, with
   `@ConditionalOnMissingBean` where an app may override them. No `@Service`/`@Component` on library classes.
5. **Multi-tenancy** by `organization_id`; every query filters by the tenant resolved from the JWT.
6. **Dynamic DDL only through `ObjectSchemaManager`**. Identifiers validated and quoted by `SqlIdentifier`. Values
   always bound, never interpolated.
7. **Geometry is a field, and only with `chawpi-gis`**: PostGIS columns with their SRID, GeoJSON in EPSG:4326 over
   the API. Core runs on plain PostgreSQL.
8. **Frontend modules register themselves** (`ChawpiModule`: routes, nav, field renderers, page components, slots,
   i18n). No hardcoded routes or URLs outside the registry; links come from `useChawpiLinks()`. Heavy libraries stay
   behind lazy routes (ADR-028).
9. **Same behaviour as the original app**, except the entries of ADR-031. A new difference needs a new entry.
10. **No overengineering**: an abstraction needs a concrete second user.

## Working rules

- **No commits.** Never run `git commit`, `git add`, `git stash`, `git push` or anything else that writes git history
  or the index unless the user explicitly asks in the current conversation. Leave changes in the working tree.
- **`.editorconfig` is law** for every file (Kotlin 4 spaces, TS/YAML/MD 2, max 160 columns, LF, final newline).
  Format before finishing: `./gradlew ktlintFormat` for Kotlin, `yarn format` for frontend code. Markdown is
  hand-formatted to the same rules.
- **Conventional Commits** for every commit message and PR title (`feat(core): …`, `fix(gis): …`, `docs: …`).
  Enforced by the commitlint hook and CI. Scopes are free; use the module name.
- Code, identifiers and comments in **English**. Comments **caveman style**: short, say why.
- Change history lives in `docs/HISTORY.md` (newest first), decisions in `docs/adr/` ([index](docs/adr/README.md)).
  A decision is changed by a new ADR, never by editing an old one.

## Commands

```bash
docker compose -f infra/docker/compose.yml up -d
./gradlew build                 # ktlint + unit tests + architecture tests
./gradlew integrationTest       # Testcontainers, or an external DB via CHAWPI_TEST_DB_* (see below)
yarn install && yarn lint && yarn test && yarn build
yarn test:tooling               # frontend/tooling scripts
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
· core stays module-agnostic · behaviour matches the original app or ADR-031 · formatted with `.editorconfig` ·
nothing committed unless asked.
````

- [ ] **Step 2: Edit `README.md`**

Keep the intro and the provenance line "Chawpi comes from [sapgis](docs/sapgis-origin.md)." Then:
1. Under "Use it in your app", add after the frontend snippet:
   `Step by step, minimal to full: [docs/guides/build-your-app.md](docs/guides/build-your-app.md).`
2. Replace the line "Modules: views, forms, pages, … See `examples/` …" with:
   `Modules: views, forms, pages, workflow, automation, documents, gis, agent — one page each in
   [docs/modules](docs/modules/README.md). Runnable samples in [examples/](examples/README.md), from
   \`simple-sample\` (core only, plain PostgreSQL) to \`full-sample\` (every module).`
3. In "Layout", change the `docs/` line to
   `docs/       architecture, modules, guides, domain, api, gis, security, development, adr, HISTORY.md`.
4. Add a last section:

```markdown
## Decisions

Every architectural decision is an ADR in [docs/adr](docs/adr/README.md); what shipped and when is in
[docs/HISTORY.md](docs/HISTORY.md).
```

- [ ] **Step 3: Check**

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -n -i 'sapgis' CLAUDE.md README.md
command grep -n -i 'Spring Data' CLAUDE.md
```

Expected: exactly one `sapgis` line per file (the provenance link), and no "Spring Data" line except rule 2's "and so
are Spring Data repositories". Then the MD check on both files (pending links allowed).

---

### Task 23: HISTORY.md — entries for P2, P4 and P5

**Files:**
- Modify: `docs/HISTORY.md`

**Interfaces:**
- Consumes: ledgers (numbers below come from them).
- Produces: three new entries. Task 40 later adds P3, P6 and P7 entries above them.

The house style: `## <date> — <sentence-case title that states the change>`, then short paragraphs in the past or
present tense, no bullet lists, `--` as the dash, ADR references as `ADR-0NN`. Entries are newest first. The two
existing chawpi entries stay unchanged: "The core becomes a library" (P1) is accurate for its moment, and "A link
answers to the same rules as the record" is P2's Task 16.

- [ ] **Step 1: Insert the P2 entry** directly above `## 2026-09-25 — The core becomes a library`:

```markdown
## 2026-09-25 — Every module becomes a library

Views, forms, pages, workflow, automation, documents, gis and agent each moved into a library of their own, with
their own auto-configuration, `chawpi.<module>.enabled` switch and Flyway migrations. Each has a thin Spring Boot
starter, and `chawpi-bom` lines up all nineteen published artifacts (ADR-024). None of them is known to the core:
they plug in through the SPIs of ADR-025, so an app that leaves gis out runs on plain PostgreSQL and its geometry
routes simply do not exist (ADR-027). The GEOMETRY field type, the bbox query and the MAP page component all arrive
from chawpi-gis now, and the resulting `custom_fields` table is column for column and CHECK for CHECK the original's.

The agent library no longer picks a model provider; its starter does, and it picks the original's (ADR-031 D7).
265 unit tests pass across the modules, and every one of the original's 49 module routes answers at the same URL.
```

- [ ] **Step 2: Insert the P4 and P5 entries** directly above `## 2026-09-25 — A link answers to the same rules as the record`
  (P5 on top):

```markdown
## 2026-09-25 — The frontend modules become packages

The screens of every optional module left `@chawpi/core` for a package of their own: `@chawpi/views`, `forms`,
`pages`, `workflow`, `automation`, `documents`, `gis` and `agent`. Each registers its routes, sidebar entries,
renderers and strings with the registry, and 224 translation keys moved out of core into the module that uses them
(ADR-028). MapLibre, xyflow, tiptap and dnd-kit now load only when someone opens the screen that needs them -- the
builder pages that pull them in are reachable only as lazy routes. Issuing a document now refreshes the record's
history at once, which the original never did (ADR-031 D15). 669 frontend tests pass.

## 2026-09-25 — The frontend becomes a set of packages

`@chawpi/ui`, `@chawpi/core` and `@chawpi/testing` replace the one Vite app. An app renders `<ChawpiApp>` with a
config and a list of modules, and a registry merges what the modules contribute, refusing a clash at start-up
rather than drawing a broken screen (ADR-028). No URL is written by hand any more: links come from the registry,
the API base and storage prefix are configuration, and the history no longer imports documents to draw an issue.
A `401` in the middle of a session now signs the user out instead of leaving a signed-in-looking page whose every
call fails (ADR-031 D11). 284 tests pass.
```

- [ ] **Step 3: MD check on `docs/HISTORY.md`**

Expected: no output. The file's older entries are hand-formatted and must not be touched; if the check flags an
old line, leave it and report it.

---

### Task 24: Frontend source — "the original app" instead of "sapgis" in comments and test titles

**Files:**
- Modify (comments and test titles only):
  `frontend/packages/smoke/src/allModules.smoke.test.tsx`,
  `frontend/packages/forms/src/{i18n.ts,module.tsx,FormBuilderPage.test.tsx}`,
  `frontend/packages/core/src/components/page-renderer/ActionButton.test.tsx`,
  `frontend/packages/core/src/components/dynamic-form/{DynamicForm.tsx,DynamicForm.test.tsx}`,
  `frontend/packages/core/src/links/links.test.ts`,
  `frontend/packages/workflow/src/{i18n.ts,module.tsx}`,
  `frontend/packages/automation/src/{i18n.ts,module.tsx,AutomationBuilderPage.test.tsx}`,
  `frontend/packages/agent/src/{i18n.ts,module.tsx}`,
  `frontend/packages/views/src/{i18n.ts,module.tsx,ViewBuilderPage.test.tsx}`,
  `frontend/packages/pages/src/{i18n.ts,module.tsx,builder/registrySlots.ts}`,
  `frontend/packages/documents/src/{module.tsx,module.test.tsx,api.test.tsx,slots.test.tsx}`,
  `frontend/packages/gis/src/{i18n.ts,module.tsx,types.ts}`,
  `frontend/tooling/scaffold-module.mjs` (one comment)
- Leave alone: `frontend/packages/agent/src/i18n.test.ts` (it asserts the bundle does **not** contain the word — a
  guard), `frontend/tooling/port-from-sapgis*.mjs` (porting tool, provenance).

**Interfaces:**
- Consumes: nothing. Produces: no "sapgis" in frontend source outside the two exceptions.

- [ ] **Step 1: List every hit**

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -rn -i sapgis frontend/packages frontend/tooling --include='*.ts' --include='*.tsx' --include='*.mjs' | command grep -v node_modules | command grep -v '/dist/'
```

Expected: the files above plus the two left alone.

- [ ] **Step 2: Rewrite each hit**

Only inside `//` comments and inside the first string argument of `it(`/`test(`/`describe(`. Word choices:
"sapgis" → "the original app", "sapgis's" → "the original app's", "in sapgis" → "in the original app", "sapgis urls"
→ "the original urls". Examples:
- `// url prefix of every route of the module. default keeps sapgis's /builder/forms` →
  `// url prefix of every route of the module. default keeps the original app's /builder/forms`
- `it('builds the same urls sapgis hardcoded', …` → `it('builds the same urls the original app hardcoded', …`
- `it('mounts its routes at the sapgis urls by default', …` → `it('mounts its routes at the original urls by default', …`

Keep each line within 160 columns; re-wrap a comment onto two lines if needed. No code, import, string literal
(other than test titles) or assertion changes.

- [ ] **Step 3: Verify nothing else changed and the tests pass**

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -rn -i sapgis frontend/packages frontend/tooling --include='*.ts' --include='*.tsx' --include='*.mjs' | command grep -v node_modules | command grep -v '/dist/' | command grep -v -E 'agent/src/i18n.test.ts|port-from-sapgis'
for p in core forms views pages workflow automation documents gis agent smoke; do yarn workspace @chawpi/$p lint >/dev/null && yarn workspace @chawpi/$p test >/dev/null && echo "$p ok" || echo "$p FAILED"; done
yarn test:tooling
```

Expected: the grep prints nothing. Ten `ok` lines. Tooling tests pass. (These run no build, so they are safe beside
P6.)

---

### Task 25: Core i18n — drop the keys nothing uses

**Files:**
- Modify: `frontend/packages/core/src/i18n/locales/en/common.json`, `frontend/packages/core/src/i18n/locales/es/common.json`

**Interfaces:**
- Consumes: every package's source (read-only), because modules resolve core keys through `fallbackNS`.
- Produces: smaller core bundles; en and es keep identical key sets.

P5 moved 224 module keys out of core. What is left may include keys no code reads any more (the P5 final review's
deferred "unused core keys"). A key is unused only when no package reads it literally **and** no dynamic lookup
could build it.

- [ ] **Step 1: List candidates**

```bash
cd /Users/jorge/IdeaProjects/chawpi
node -e '
const fs = require("fs"), path = require("path")
const flat = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (v && typeof v === "object" ? flat(v, p + k + ".") : [p + k]))
const en = flat(JSON.parse(fs.readFileSync("frontend/packages/core/src/i18n/locales/en/common.json", "utf8")))
const files = []
const walk = (d) => { for (const e of fs.readdirSync(d, { withFileTypes: true })) { const p = path.join(d, e.name)
  if (e.isDirectory()) { if (!["node_modules", "dist"].includes(e.name)) walk(p) } else if (/\.(ts|tsx)$/.test(e.name) && !/\.test\.tsx?$/.test(e.name)) files.push(p) } }
walk("frontend/packages")
const src = files.map((f) => fs.readFileSync(f, "utf8")).join("\n")
const dynamic = [...src.matchAll(/t\(\s*`([a-zA-Z0-9_.]+)\$\{/g)].map((m) => m[1])
const unused = en.filter((k) => !src.includes(`"${k}"`) && !src.includes(`'"'"'${k}'"'"'`) && !src.includes("`" + k + "`") && !dynamic.some((d) => k.startsWith(d)))
console.log("dynamic prefixes:", [...new Set(dynamic)].join(", "))
console.log(unused.join("\n"))'
```

- [ ] **Step 2: Vet every candidate by hand**

For each candidate key, `command grep -rn '<last segment>' frontend/packages/*/src` and read the hits. Keep the key
if it is built dynamically in a way the script missed (a variable holding a key prefix, a `labelKey`/`headerKey` in
a registry entry, `i18nKey` props, keys passed through `nav` or `fieldTypes`, plural suffixes such as `_one`/`_other`
whose base is used, or keys read by `@chawpi/testing` fixtures). List the kept ones with the reason.

- [ ] **Step 3: Remove the truly unused keys from both `en` and `es`**, preserving the files' key order and 4-space
  JSON indentation (as the files use today). Remove an object that becomes empty.

- [ ] **Step 4: Verify**

```bash
cd /Users/jorge/IdeaProjects/chawpi
node -e '
const fs = require("fs")
const flat = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (v && typeof v === "object" ? flat(v, p + k + ".") : [p + k]))
const read = (l) => flat(JSON.parse(fs.readFileSync(`frontend/packages/core/src/i18n/locales/${l}/common.json`, "utf8"))).sort().join("\n")
if (read("en") !== read("es")) { console.error("en and es differ"); process.exit(1) } else console.log("same keys")'
yarn prettier --check frontend/packages/core/src/i18n/locales
for p in core forms views pages workflow automation documents gis agent smoke; do yarn workspace @chawpi/$p test >/dev/null && echo "$p ok" || echo "$p FAILED"; done
```

Expected: `same keys`, prettier clean, ten `ok` lines (i18next logs of a missing key would make a test fail or
show up; also scan one run's output for `missingKey`: `yarn workspace @chawpi/core test 2>&1 | command grep -i missingkey`
prints nothing). Report the removed keys and the kept candidates.

---

### Task 26: Release guard tooling and the releasing doc

**Files:**
- Create: `frontend/tooling/check-release.mjs`
- Create: `frontend/tooling/check-release.test.mjs`
- Create: `.github/scripts/check-maven-publications.sh`
- Modify: `docs/development/releasing.md`

**Interfaces:**
- Consumes: `setVersion(version, packagesRoot)` from `frontend/tooling/set-version.mjs`;
  `expandWorkspaces(root, patterns)` from `frontend/tooling/run-ordered.mjs` (returns absolute dirs that hold a
  `package.json`).
- Produces (used by Task 39's workflows):
  - `node frontend/tooling/check-release.mjs` → exit 0 and prints `check-release: ok`, or prints one
    `check-release: <problem>` line per problem and exits 1.
  - `node frontend/tooling/check-release.mjs --pack <version>` → the same plus the tarball dry run. It needs built
    `dist/` folders.
  - exports `EXPECTED_PUBLIC: string[]`, `publicPackages(packagesRoot): {dir, pkg}[]`,
    `checkReleaseConfig(repoRoot, expected?): string[]`, `packDryRun(repoRoot, version, { pack }?): string[]`,
    `npmPack(dir): string[]` (file paths in the tarball).
  - `.github/scripts/check-maven-publications.sh <local-repo-dir>` → compares `<local-repo-dir>/chawpi/*` with the 20
    expected artifact ids. Exit 0 and prints `maven publications: ok (20)`, or prints a diff and exits 1.

- [ ] **Step 1: Write the failing tests** — `frontend/tooling/check-release.test.mjs`

```js
// fixture repo on disk: two public packages, one private, one sample web, a release-please config.
// each test breaks one thing and expects exactly that problem back.
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'

import { checkReleaseConfig, packDryRun } from './check-release.mjs'

const EXPECTED = ['@chawpi/a', '@chawpi/b']

function write(root, path, contents) {
  const full = join(root, path)
  mkdirSync(join(full, '..'), { recursive: true })
  writeFileSync(full, typeof contents === 'string' ? contents : JSON.stringify(contents, null, 4))
}

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'check-release-'))
  const lib = (name, deps = {}) => ({
    name,
    version: '0.1.0',
    main: './dist/index.js',
    types: './dist/index.d.ts',
    exports: { '.': { types: './dist/index.d.ts', import: './dist/index.js' }, './package.json': './package.json' },
    files: ['dist'],
    peerDependencies: deps
  })
  write(root, 'package.json', { name: 'root', private: true, workspaces: ['frontend/packages/*', 'examples/*/web'] })
  write(root, 'frontend/packages/a/package.json', lib('@chawpi/a'))
  write(root, 'frontend/packages/b/package.json', lib('@chawpi/b', { '@chawpi/a': '*', react: '^19.3.0' }))
  write(root, 'frontend/packages/smoke/package.json', { name: '@chawpi/smoke', version: '0.1.0', private: true })
  write(root, 'examples/one/web/package.json', { name: 'one-web', version: '0.1.0', private: true })
  write(root, 'release-please-config.json', {
    packages: {
      '.': {
        'extra-files': [
          'gradle.properties',
          { type: 'json', path: 'package.json', jsonpath: '$.version' },
          { type: 'json', path: 'frontend/packages/a/package.json', jsonpath: '$.version' },
          { type: 'json', path: 'frontend/packages/b/package.json', jsonpath: '$.version' }
        ]
      }
    }
  })
  return root
}

const packAll = () => ['package.json', 'dist/index.js', 'dist/index.d.ts']

test('a consistent repo has no problems', () => {
  const root = fixture()
  try {
    assert.deepEqual(checkReleaseConfig(root, EXPECTED), [])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('a private package that loses its flag would leak', () => {
  const root = fixture()
  try {
    write(root, 'frontend/packages/smoke/package.json', { name: '@chawpi/smoke', version: '0.1.0' })
    const problems = checkReleaseConfig(root, EXPECTED)
    assert.ok(problems.some((p) => p.includes('@chawpi/smoke would be published')), problems.join('\n'))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('an expected package that goes missing or private is reported', () => {
  const root = fixture()
  try {
    assert.ok(checkReleaseConfig(root, [...EXPECTED, '@chawpi/c']).some((p) => p.includes('@chawpi/c is expected')))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('a public package release-please does not bump is reported', () => {
  const root = fixture()
  try {
    const config = JSON.parse(readFileSync(join(root, 'release-please-config.json'), 'utf8'))
    config.packages['.']['extra-files'] = config.packages['.']['extra-files'].filter((f) => f.path !== 'frontend/packages/b/package.json')
    write(root, 'release-please-config.json', config)
    assert.ok(checkReleaseConfig(root, EXPECTED).some((p) => p.includes('frontend/packages/b/package.json is not bumped')))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('a sample web that is not private is reported', () => {
  const root = fixture()
  try {
    write(root, 'examples/one/web/package.json', { name: 'one-web', version: '0.1.0' })
    assert.ok(checkReleaseConfig(root, EXPECTED).some((p) => p.includes('one-web')))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('pack dry run pins internal ranges to the release and leaves the repo untouched', () => {
  const root = fixture()
  try {
    assert.deepEqual(packDryRun(root, '1.2.3', { pack: packAll }), [])
    const b = JSON.parse(readFileSync(join(root, 'frontend/packages/b/package.json'), 'utf8'))
    assert.equal(b.peerDependencies['@chawpi/a'], '*')
    assert.equal(b.version, '0.1.0')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('pack dry run reports a tarball without its entry points', () => {
  const root = fixture()
  try {
    const problems = packDryRun(root, '1.2.3', { pack: () => ['package.json'] })
    assert.ok(problems.some((p) => p.includes('@chawpi/a tarball lacks dist/index.js')), problems.join('\n'))
    assert.ok(problems.some((p) => p.includes('dist/index.d.ts')))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('pack dry run refuses a missing version', () => {
  const root = fixture()
  try {
    assert.throws(() => packDryRun(root, undefined, { pack: packAll }), /version/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
```

- [ ] **Step 2: Run the tests and see them fail**

Run: `cd /Users/jorge/IdeaProjects/chawpi && node --test frontend/tooling/check-release.test.mjs`
Expected: FAIL with `Cannot find module` / `ERR_MODULE_NOT_FOUND` for `check-release.mjs`.

- [ ] **Step 3: Write `frontend/tooling/check-release.mjs`**

```js
#!/usr/bin/env node
// guards a release before anything is uploaded: which npm packages would be published, whether
// release-please bumps each of them, and what their tarballs would hold once set-version.mjs ran.
// publish.yml runs it right before `npm publish`, ci.yml on every pull request.
import { execFileSync } from 'node:child_process'
import { cpSync, mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { expandWorkspaces } from './run-ordered.mjs'
import { setVersion } from './set-version.mjs'

// the public set, by name. a new package fails the check until someone adds it here on purpose.
export const EXPECTED_PUBLIC = [
  '@chawpi/agent',
  '@chawpi/automation',
  '@chawpi/core',
  '@chawpi/documents',
  '@chawpi/forms',
  '@chawpi/gis',
  '@chawpi/pages',
  '@chawpi/testing',
  '@chawpi/ui',
  '@chawpi/views',
  '@chawpi/workflow'
]

const DEP_FIELDS = ['dependencies', 'peerDependencies', 'optionalDependencies']

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'))
}

function readPackage(dir) {
  try {
    return readJson(join(dir, 'package.json'))
  } catch {
    return null // a folder without package.json is not a package
  }
}

export function publicPackages(packagesRoot) {
  return readdirSync(packagesRoot, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => join(packagesRoot, entry.name))
    .map((dir) => ({ dir, pkg: readPackage(dir) }))
    .filter(({ pkg }) => pkg && pkg.private !== true)
}

// the files a consumer's import resolves to: main, types and every exports target
function entryPoints(pkg) {
  const targets = [pkg.main, pkg.types]
  const walk = (value) => {
    if (typeof value === 'string') targets.push(value)
    else if (value && typeof value === 'object') Object.values(value).forEach(walk)
  }
  walk(pkg.exports)
  return [...new Set(targets.filter(Boolean).map((t) => t.replace(/^\.\//, '')))]
}

export function checkReleaseConfig(repoRoot, expected = EXPECTED_PUBLIC) {
  const problems = []
  const packagesRoot = join(repoRoot, 'frontend/packages')
  const published = publicPackages(packagesRoot)
  const names = published.map(({ pkg }) => pkg.name)

  for (const name of names) if (!expected.includes(name)) problems.push(`${name} would be published but is not in the expected public set`)
  for (const name of expected) if (!names.includes(name)) problems.push(`${name} is expected to be published but is missing or private`)

  const config = readJson(join(repoRoot, 'release-please-config.json'))
  const bumped = (config.packages?.['.']?.['extra-files'] ?? []).map((file) => (typeof file === 'string' ? file : file.path))
  const publishedFiles = published.map(({ dir }) => relative(repoRoot, join(dir, 'package.json')))
  for (const file of publishedFiles) if (!bumped.includes(file)) problems.push(`${file} is not bumped by release-please (extra-files)`)
  for (const file of bumped) {
    if (file.startsWith('frontend/packages/') && !publishedFiles.includes(file)) problems.push(`release-please bumps ${file}, which is not a public package`)
  }

  // workspaces outside frontend/packages are apps (sample webs): never published
  const root = readJson(join(repoRoot, 'package.json'))
  const others = (root.workspaces ?? []).filter((pattern) => !pattern.startsWith('frontend/packages/'))
  for (const dir of expandWorkspaces(repoRoot, others)) {
    const pkg = readPackage(dir)
    if (pkg && pkg.private !== true) problems.push(`${pkg.name} (${relative(repoRoot, dir)}) is a workspace app and must be "private": true`)
  }
  return problems
}

export function npmPack(dir) {
  const out = execFileSync('npm', ['pack', '--dry-run', '--json', '--ignore-scripts'], { cwd: dir, encoding: 'utf8' })
  return JSON.parse(out)[0].files.map((file) => file.path)
}

// what publish.yml does, on a throwaway copy: set-version, then pack every public package
export function packDryRun(repoRoot, version, { pack = npmPack } = {}) {
  if (!version || !/^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/.test(version)) throw new Error(`pack dry run needs a release version, got '${version}'`)
  const problems = []
  const tmp = mkdtempSync(join(tmpdir(), 'chawpi-pack-'))
  try {
    const copy = join(tmp, 'packages')
    cpSync(join(repoRoot, 'frontend/packages'), copy, { recursive: true, filter: (src) => !src.split(/[\\/]/).includes('node_modules') })
    setVersion(version, copy)
    for (const { dir, pkg } of publicPackages(copy)) {
      if (pkg.version !== version) problems.push(`${pkg.name} has version ${pkg.version}, expected ${version}`)
      for (const field of DEP_FIELDS) {
        for (const [name, range] of Object.entries(pkg[field] ?? {})) {
          if (name.startsWith('@chawpi/') && range !== version) problems.push(`${pkg.name} ${field} ${name}@${range}, expected ${version}`)
        }
      }
      const files = pack(dir)
      for (const needed of ['package.json', ...entryPoints(pkg).filter((p) => p !== 'package.json')]) {
        if (!files.includes(needed)) problems.push(`${pkg.name} tarball lacks ${needed}`)
      }
      if (files.some((file) => file.startsWith('src/'))) problems.push(`${pkg.name} tarball ships src/`)
    }
  } finally {
    rmSync(tmp, { recursive: true, force: true })
  }
  return problems
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
  const args = process.argv.slice(2)
  const problems = checkReleaseConfig(repoRoot)
  const at = args.indexOf('--pack')
  if (at >= 0) problems.push(...packDryRun(repoRoot, args[at + 1]))
  for (const problem of problems) console.error(`check-release: ${problem}`)
  if (problems.length > 0) process.exit(1)
  console.log('check-release: ok')
}
```

- [ ] **Step 4: Run the tests and see them pass, then the real repo**

```bash
cd /Users/jorge/IdeaProjects/chawpi
node --test frontend/tooling/check-release.test.mjs
yarn test:tooling
node frontend/tooling/check-release.mjs
ls frontend/packages/core/dist/index.js >/dev/null && node frontend/tooling/check-release.mjs --pack 0.0.0-dryrun
git status --short frontend/packages | head -3
```

Expected: 8 tests pass; `yarn test:tooling` passes (it globs `*.test.mjs`); `check-release: ok` twice (the second
needs the `dist/` P5 built; if `dist/` is missing, report "pack not run: no dist" and do not run `yarn build`,
because Wave 1 must not build). If P6 has added sample webs that are not `"private": true`, the check reports
them: do not edit `examples/**`, put it in the report for the controller (it is a P6 fix). The final `git status`
shows no `package.json` modified by the dry run.

- [ ] **Step 5: Write `.github/scripts/check-maven-publications.sh`**

```bash
#!/usr/bin/env bash
# what `./gradlew publish` would upload, checked before it does: publish to a throwaway local
# repository first, then compare the artifact ids with the list below. a new library fails here
# until someone adds it on purpose; a sample or the integration tests leaking in fails here too.
# usage: check-maven-publications.sh <local-repo-dir>   (the dir given to -Dmaven.repo.local)
set -euo pipefail

repo="${1:?usage: check-maven-publications.sh <local-repo-dir>}"

expected="chawpi-agent
chawpi-automation
chawpi-bom
chawpi-core
chawpi-documents
chawpi-forms
chawpi-gis
chawpi-pages
chawpi-spring-boot-starter
chawpi-spring-boot-starter-agent
chawpi-spring-boot-starter-automation
chawpi-spring-boot-starter-documents
chawpi-spring-boot-starter-forms
chawpi-spring-boot-starter-gis
chawpi-spring-boot-starter-pages
chawpi-spring-boot-starter-views
chawpi-spring-boot-starter-workflow
chawpi-test
chawpi-views
chawpi-workflow"

if [ ! -d "$repo/chawpi" ]; then
  echo "maven publications: nothing under $repo/chawpi" >&2
  exit 1
fi

actual="$(ls "$repo/chawpi" | LC_ALL=C sort)"
if [ "$actual" != "$(printf '%s\n' "$expected" | LC_ALL=C sort)" ]; then
  echo "maven publications differ from the expected set (< expected, > actual):" >&2
  diff <(printf '%s\n' "$expected" | LC_ALL=C sort) <(printf '%s\n' "$actual") >&2 || true
  exit 1
fi
echo "maven publications: ok ($(printf '%s\n' "$actual" | wc -l | tr -d ' '))"
```

Then `chmod +x .github/scripts/check-maven-publications.sh`.

- [ ] **Step 6: Test the Maven script without Gradle** (Wave 1 runs no Gradle): fake local repositories.

```bash
cd /Users/jorge/IdeaProjects/chawpi
S=$(mktemp -d)
mkdir -p "$S/good/chawpi" && for a in chawpi-agent chawpi-automation chawpi-bom chawpi-core chawpi-documents chawpi-forms chawpi-gis chawpi-pages chawpi-spring-boot-starter chawpi-spring-boot-starter-agent chawpi-spring-boot-starter-automation chawpi-spring-boot-starter-documents chawpi-spring-boot-starter-forms chawpi-spring-boot-starter-gis chawpi-spring-boot-starter-pages chawpi-spring-boot-starter-views chawpi-spring-boot-starter-workflow chawpi-test chawpi-views chawpi-workflow; do mkdir "$S/good/chawpi/$a"; done
.github/scripts/check-maven-publications.sh "$S/good"; echo "exit $?"
cp -R "$S/good" "$S/leak" && mkdir "$S/leak/chawpi/chawpi-integration-tests"
.github/scripts/check-maven-publications.sh "$S/leak"; echo "exit $?"
rm -rf "$S"
```

Expected: `maven publications: ok (20)`, `exit 0`; then the diff naming `chawpi-integration-tests`, `exit 1`. The
real Gradle run happens in Task 39.

- [ ] **Step 7: Update `docs/development/releasing.md`**

Add a section `## Guards` after `## Versions are lockstep`:

```markdown
## Guards

Two scripts stop a release from publishing the wrong thing. CI runs both on every pull request, and `publish.yml`
runs both right before uploading:

- `node frontend/tooling/check-release.mjs [--pack <version>]` checks that exactly the eleven public `@chawpi/*`
  packages would be published, that release-please bumps each of them, and that every workspace app is private.
  With `--pack`, it copies the packages, applies `set-version.mjs`, and runs `npm pack --dry-run` on each: every
  internal range must be the release version and every entry point must be in the tarball.
- `.github/scripts/check-maven-publications.sh <dir>` compares what `./gradlew publishToMavenLocal
  -Dmaven.repo.local=<dir>` produced with the twenty expected Maven artifacts.

A new library fails both until it is added on purpose: to `EXPECTED_PUBLIC` and release-please `extra-files` for npm,
to the script's list for Maven.
```

Also check the rest of `releasing.md` against ADR-029 and the workflows (token name, the pinned first release, the
repository URL in "Consuming a published library") and fix any stale sentence. Then run the MD check on it.

- [ ] **Step 8: Format**

```bash
cd /Users/jorge/IdeaProjects/chawpi
yarn prettier --write frontend/tooling/check-release.mjs frontend/tooling/check-release.test.mjs
yarn prettier --check frontend/tooling
node --test frontend/tooling/check-release.test.mjs
```

Expected: clean, 8 pass.

## Wave 2 — code cleanups and CI wiring (starts only after P3 and P6 are complete)

**Before dispatching any Wave 2 task:** wait for P3/P6 completion. Confirm that
`.superpowers/sdd/2026-09-25-p3-integration-tests/progress.md` has a `P3 COMPLETE` line and that the P6 ledger
(`.superpowers/sdd/2026-09-25-p6-*/progress.md`) has a `P6 COMPLETE` line. Until then, Wave 2 does not start.

### Task 27: build-logic — ktlint format never comes from the cache, and check runs after format

**Files:**
- Modify: `backend/build-logic/src/main/kotlin/chawpi.kotlin-library.gradle.kts`
- Test: `backend/build-logic/src/test/kotlin/chawpi/buildlogic/ConventionPluginsTest.kt`

**Interfaces:**
- Consumes: ktlint-gradle 14.2.0 task types `org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask` and
  `org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask` (on build-logic's classpath already).
- Produces: in every project applying `chawpi.kotlin-library`, format tasks are never cacheable, and check tasks
  `mustRunAfter` format tasks. No other behaviour change.

Deferred from the P1 ledger ("ktlintCheck should mustRunAfter ktlintFormat … to avoid the race in one
invocation") and the P3 ledger ("ktlint format tasks non-cacheable in build-logic"). `gradle.properties` has
`org.gradle.caching=true`. A format task restored from the cache skips the rewrite of the sources, so they stay
unformatted.

- [ ] **Step 1: Write the failing tests** — add to `ConventionPluginsTest` (after `badly formatted kotlin fails the build`):

```kotlin
    @Test
    fun `check after format in one invocation sees the formatted source`() {
        write("build.gradle.kts", "plugins { id(\"chawpi.kotlin-library\") }\n")
        write("src/main/kotlin/probe/Probe.kt", "package probe\nfun   probe( ) : String   =\"ok\"")

        // check named first on purpose: without mustRunAfter it would run first and fail
        runner("ktlintCheck", "ktlintFormat").build()

        assertEquals("package probe\n\nfun probe(): String = \"ok\"\n", File(dir, "src/main/kotlin/probe/Probe.kt").readText())
    }

    @Test
    fun `format is never restored from the build cache`() {
        write("build.gradle.kts", "plugins { id(\"chawpi.kotlin-library\") }\n")
        val unformatted = "package probe\nfun   probe( ) : String   =\"ok\""
        val source = write("src/main/kotlin/probe/Probe.kt", unformatted)

        runner("ktlintFormat", "--build-cache").build()
        // same unformatted input again, fresh build dir: a cacheable format task would come back FROM-CACHE
        source.writeText(unformatted)
        File(dir, "build").deleteRecursively()
        val second = runner("ktlintFormat", "--build-cache").build()

        assertTrue(second.tasks.none { it.path.contains("Format") && it.outcome == TaskOutcome.FROM_CACHE }, second.output)
        assertEquals("package probe\n\nfun probe(): String = \"ok\"\n", source.readText())
    }
```

- [ ] **Step 2: Run them and see them fail**

Run: `cd /Users/jorge/IdeaProjects/chawpi && ./gradlew -p backend/build-logic test --tests 'chawpi.buildlogic.ConventionPluginsTest'`
Expected: `check after format in one invocation sees the formatted source` FAILS (ktlintCheck runs first and
reports the violation). `format is never restored from the build cache` FAILS if the format task is cacheable
today. If this second test already passes, keep it and the fix anyway (it pins the property) and say so in the
report.

- [ ] **Step 3: Implement** — append to `chawpi.kotlin-library.gradle.kts`:

```kotlin
// format rewrites sources in place: a cache hit would skip the rewrite and leave them unformatted
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask>().configureEach {
    outputs.cacheIf("formats sources in place") { false }
}

// `ktlintCheck ktlintFormat` in one invocation: check the formatted sources, not the old ones
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask>().configureEach {
    mustRunAfter(tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask>())
}
```

If the build-logic compiler rejects the fully qualified types, add
`import org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask` and `...KtLintFormatTask` at the top of the script
instead.

- [ ] **Step 4: Run the tests and the whole build-logic suite**

```bash
cd /Users/jorge/IdeaProjects/chawpi
./gradlew -p backend/build-logic ktlintFormat test
./gradlew -p backend/build-logic ktlintCheck
./gradlew :chawpi-views:ktlintCheck :chawpi-views:ktlintFormat
```

Expected: all build-logic tests pass (the 7 existing plus 2 new). The last line proves a real project still
configures and runs both tasks in one invocation.

---

### Task 28: chawpi-test — its POM declares chawpi-core

**Files:**
- Modify: `backend/chawpi-test/build.gradle.kts`
- Modify: `backend/chawpi-test/src/main/kotlin/chawpi/test/ChawpiContextRunner.kt` (comment only, if it mentions
  `compileOnly`)

**Interfaces:**
- Consumes: nothing new.
- Produces: the published `chawpi-test` POM lists `chawpi:chawpi-core` in `compile` scope, so an app that adds only
  `testImplementation("chawpi:chawpi-test")` gets a consistent core.

The P2 ledger deferred this: `compileOnly(project(":chawpi-core"))` compiles `ChawpiContextRunner` (which names
core's auto-configurations) but leaves the POM without the edge.

- [ ] **Step 1: See the gap**

```bash
cd /Users/jorge/IdeaProjects/chawpi
./gradlew :chawpi-test:generatePomFileForMavenPublication
command grep -c '<artifactId>chawpi-core</artifactId>' backend/chawpi-test/build/publications/maven/pom-default.xml
```

Expected: `0`.

- [ ] **Step 2: Change the dependency**

In `backend/chawpi-test/build.gradle.kts`, replace the two lines

```kotlin
    // ChawpiContextRunner names core's auto-configs. compileOnly: every user already has core.
    compileOnly(project(":chawpi-core"))
    testImplementation(project(":chawpi-core"))
```

with

```kotlin
    // ChawpiContextRunner names core's auto-configs, so the pom must say which core it was built against
    api(project(":chawpi-core"))
```

If `ChawpiContextRunner.kt` has a comment saying core is `compileOnly`, reword it to match. No code change.

- [ ] **Step 3: Verify the POM and the builds that use chawpi-test**

```bash
cd /Users/jorge/IdeaProjects/chawpi
./gradlew :chawpi-test:generatePomFileForMavenPublication
command grep -A3 '<artifactId>chawpi-core</artifactId>' backend/chawpi-test/build/publications/maven/pom-default.xml
./gradlew :chawpi-test:build :chawpi-core:test :chawpi-gis:test
```

Expected: `chawpi-core` with `<scope>compile</scope>` and version `${version}` from `gradle.properties` (the
`versionMapping` resolves it). All three builds green. No dependency cycle error. (core's tests use chawpi-test, and
chawpi-test's main code uses core's main code, which Gradle resolves as before.)

---

### Task 29: chawpi-core — inert stereotypes out, FQNs imported, `@ChawpiApplication` live test

**Files:**
- Modify: `backend/chawpi-core/src/main/kotlin/chawpi/core/**` (annotations and imports only; `ChawpiSchemas.kt`
  one comment)
- Create: `backend/chawpi-core/src/test/kotlin/testapp/live/ChawpiApplicationLiveContextTest.kt`

**Interfaces:**
- Consumes: `chawpi.core.autoconfigure.ChawpiApplication` (exists).
- Produces: no `@Service`/`@Component` in chawpi-core main code. `@Repository` and `@RestController` stay (ADR-024).

Rulings for the P1-deferred items, applied here:
- **`metadataTable()` helper: not added.** The metadata schema is validated once by `ChawpiSchemas`, and the table
  names in `${schemas.metadata}.<table>` are literals in the source, so there is nothing to quote. A helper would
  touch about 120 SQL strings in eight libraries that only integration tests cover, to produce the same SQL. Record
  this as a comment on `ChawpiSchemas.metadata` (Step 5).
- **`explicitApi()`: not enabled** (ADR-024, "Public surface").
- **`AdminService` size (549 lines): not split.** It is one responsibility (the admin API over users, roles and
  permissions), and most of its length is SQL text. Splitting it changes no behaviour and has no second user.
- **FQNs:** imported (Step 4).

- [ ] **Step 1: Record which classes lose an annotation, and prove each is declared in an auto-configuration**

```bash
cd /Users/jorge/IdeaProjects/chawpi
M=chawpi-core
LIST=$(mktemp)
command grep -rn -A4 -E '^@(Service|Component)\b' backend/$M/src/main/kotlin --include='*.kt' | command grep -oE 'class [A-Z][A-Za-z0-9]*' | awk '{print $2}' | sort -u > "$LIST"
wc -l < "$LIST"
while read -r cls; do
  command grep -rqE "\b$cls(\(|::class)" backend/$M/src/main/kotlin --include='*AutoConfiguration.kt' || echo "NOT DECLARED IN AN AUTO-CONFIG: $cls"
done < "$LIST"
```

Expected: a count, and no `NOT DECLARED` line. A `NOT DECLARED` class stops the task: removing its annotation would
drop a bean. Report it to the controller instead of continuing.

- [ ] **Step 2: Write the live-context test (it must pass before and after the change)**

`backend/chawpi-core/src/test/kotlin/testapp/live/ChawpiApplicationLiveContextTest.kt`:

```kotlin
package testapp.live

import chawpi.core.autoconfigure.ChawpiApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.stereotype.Component

// an app class in a package of its own, the way an app uses the annotation. auto-configuration is
// switched off (no database here): this pins what the annotation itself scans and binds.
@ChawpiApplication
class LiveApp

@ConfigurationProperties("live")
data class LiveProperties(
    val greeting: String = "hello"
)

@Component
class LiveService

class ChawpiApplicationLiveContextTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(LiveApp::class.java)
            .withPropertyValues("spring.boot.enableautoconfiguration=false", "live.greeting=hola")

    @Test
    fun `the app's own components and properties are picked up`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(LiveService::class.java)
            assertThat(context.getBean(LiveProperties::class.java).greeting).isEqualTo("hola")
        }
    }

    @Test
    fun `the scan starts at the app's package and never reaches chawpi`() {
        runner.run { context ->
            val fromLibrary =
                context.beanDefinitionNames.filter { name ->
                    context.getType(name)?.packageName?.startsWith("chawpi.") == true
                }
            assertThat(fromLibrary).isEmpty()
        }
    }
}
```

Run: `./gradlew :chawpi-core:test --tests 'testapp.live.ChawpiApplicationLiveContextTest'`
Expected: 2 tests PASS. This is a characterization test of existing behaviour, so it is green from the start. If it
fails, the annotation is broken: stop and report.

- [ ] **Step 3: Remove `@Service` and `@Component` from chawpi-core main code**

For every class in `$LIST`: delete the annotation line and its import (`org.springframework.stereotype.Service` /
`Component`) when nothing else in the file uses it. Do not touch `@Repository`, `@RestController`, `@Controller`,
`@Configuration` or `@AutoConfiguration`.

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -rn -E '^@(Service|Component)\b|import org\.springframework\.stereotype\.(Service|Component)$' backend/chawpi-core/src/main/kotlin
```

Expected after the edit: no output.

- [ ] **Step 4: Import the fully qualified names**

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -rn -E '(:|<|\(|, )\s*(io|org|chawpi|java|kotlin|reactor)\.[a-z0-9_.]+\.[A-Z][A-Za-z0-9]*' backend/chawpi-core/src/main/kotlin --include='*.kt' | command grep -v -E '^\S+:\s*import |^\S+:[0-9]+:\s*//'
```

Known hits: `Relationship.kt` (`metadata: io.r2dbc.spi.RowMetadata`) and `RelationshipController.kt`
(`currentUser: chawpi.core.identity.CurrentUser`). Replace each FQN in a type position with an import. Leave FQNs
inside strings and KDoc alone.

- [ ] **Step 5: Comment the `metadataTable()` ruling in `ChawpiSchemas.kt`**

Replace the comment above `val metadata` with:

```kotlin
    // metadata + identity, owned by flyway. sql writes "${schemas.metadata}.<table>" with a literal table
    // name: the schema is checked here once, so there is nothing left to quote. no metadataTable() helper.
```

- [ ] **Step 6: Format and test**

```bash
cd /Users/jorge/IdeaProjects/chawpi
./gradlew :chawpi-core:ktlintFormat :chawpi-core:build
```

Expected: green. `:chawpi-core:build` runs the unit tests, the auto-configuration tests, `CoreArchitectureTest` and
the new live test. Core's integration tests run in Task 40.

---

### Task 30: chawpi-views — inert stereotypes out

**Files:**
- Modify: `backend/chawpi-views/src/main/kotlin/chawpi/views/**` (annotation lines and their imports only)

**Interfaces:**
- Consumes: nothing new.
- Produces: no `@Service`/`@Component` in chawpi-views main code. `@Repository` and `@RestController` stay (ADR-024).

The P2 ledger deferred this: the stereotypes are inert, because component scanning never reaches library packages.
They are also a trap: an app placed in package `chawpi` would scan them and register the beans twice.

- [ ] **Step 1: Record which classes lose an annotation, and prove each is declared in an auto-configuration**

```bash
cd /Users/jorge/IdeaProjects/chawpi
M=chawpi-views
LIST=$(mktemp)
command grep -rn -A4 -E '^@(Service|Component)\b' backend/$M/src/main/kotlin --include='*.kt' | command grep -oE 'class [A-Z][A-Za-z0-9]*' | awk '{print $2}' | sort -u > "$LIST"
cat "$LIST"
while read -r cls; do
  command grep -rqE "\b$cls(\(|::class)" backend/$M/src/main/kotlin --include='*AutoConfiguration.kt' || echo "NOT DECLARED IN AN AUTO-CONFIG: $cls"
done < "$LIST"
```

Expected: the class list, and no `NOT DECLARED` line. A `NOT DECLARED` class stops the task, because removing its
annotation would drop a bean. Report it to the controller.

- [ ] **Step 2: Run the module's tests before the change (baseline)**

Run: `cd /Users/jorge/IdeaProjects/chawpi && ./gradlew :chawpi-views:test`
Expected: green. Note the test count:
`command grep -h -o 'tests="[0-9]*"' backend/chawpi-views/build/test-results/test/*.xml | tr -dc '0-9\n' | paste -sd+ - | bc`

- [ ] **Step 3: Remove `@Service` and `@Component`**

For every class in `$LIST`: delete the annotation line, and delete its import (`org.springframework.stereotype.Service`
/ `Component`) when nothing else in the file uses it. Do not touch `@Repository`, `@RestController`, `@Controller`,
`@Configuration`, `@AutoConfiguration` or any `@Order`.

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -rn -E '^@(Service|Component)\b|import org\.springframework\.stereotype\.(Service|Component)$' backend/chawpi-views/src/main/kotlin
```

Expected: no output.

- [ ] **Step 4: Format and test**

```bash
cd /Users/jorge/IdeaProjects/chawpi
./gradlew :chawpi-views:ktlintFormat :chawpi-views:build
```

Expected: green, with the same test count as Step 2 (the auto-configuration tests prove every bean still exists).
The module's integration tests run in Task 40.

---

### Task 31: chawpi-forms — inert stereotypes out

**Files:**
- Modify: `backend/chawpi-forms/src/main/kotlin/chawpi/forms/**` (annotation lines and their imports only)

**Interfaces:**
- Consumes: nothing new.
- Produces: no `@Service`/`@Component` in chawpi-forms main code. `@Repository` and `@RestController` stay (ADR-024).

The P2 ledger deferred this: the stereotypes are inert, because component scanning never reaches library packages.
They are also a trap: an app placed in package `chawpi` would scan them and register the beans twice.

- [ ] **Step 1: Record which classes lose an annotation, and prove each is declared in an auto-configuration**

```bash
cd /Users/jorge/IdeaProjects/chawpi
M=chawpi-forms
LIST=$(mktemp)
command grep -rn -A4 -E '^@(Service|Component)\b' backend/$M/src/main/kotlin --include='*.kt' | command grep -oE 'class [A-Z][A-Za-z0-9]*' | awk '{print $2}' | sort -u > "$LIST"
cat "$LIST"
while read -r cls; do
  command grep -rqE "\b$cls(\(|::class)" backend/$M/src/main/kotlin --include='*AutoConfiguration.kt' || echo "NOT DECLARED IN AN AUTO-CONFIG: $cls"
done < "$LIST"
```

Expected: the class list, and no `NOT DECLARED` line. A `NOT DECLARED` class stops the task, because removing its
annotation would drop a bean. Report it to the controller.

- [ ] **Step 2: Run the module's tests before the change (baseline)**

Run: `cd /Users/jorge/IdeaProjects/chawpi && ./gradlew :chawpi-forms:test`
Expected: green. Note the test count:
`command grep -h -o 'tests="[0-9]*"' backend/chawpi-forms/build/test-results/test/*.xml | tr -dc '0-9\n' | paste -sd+ - | bc`

- [ ] **Step 3: Remove `@Service` and `@Component`**

For every class in `$LIST`: delete the annotation line, and delete its import (`org.springframework.stereotype.Service`
/ `Component`) when nothing else in the file uses it. Do not touch `@Repository`, `@RestController`, `@Controller`,
`@Configuration`, `@AutoConfiguration` or any `@Order`.

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -rn -E '^@(Service|Component)\b|import org\.springframework\.stereotype\.(Service|Component)$' backend/chawpi-forms/src/main/kotlin
```

Expected: no output.

- [ ] **Step 4: Format and test**

```bash
cd /Users/jorge/IdeaProjects/chawpi
./gradlew :chawpi-forms:ktlintFormat :chawpi-forms:build
```

Expected: green, with the same test count as Step 2 (the auto-configuration tests prove every bean still exists).
The module's integration tests run in Task 40.

---

### Task 32: chawpi-pages — inert stereotypes out

**Files:**
- Modify: `backend/chawpi-pages/src/main/kotlin/chawpi/pages/**` (annotation lines and their imports only)

**Interfaces:**
- Consumes: nothing new.
- Produces: no `@Service`/`@Component` in chawpi-pages main code. `@Repository` and `@RestController` stay (ADR-024).

The P2 ledger deferred this: the stereotypes are inert, because component scanning never reaches library packages.
They are also a trap: an app placed in package `chawpi` would scan them and register the beans twice.

chawpi-pages depends on chawpi-forms, so its build also compiles forms.

- [ ] **Step 1: Record which classes lose an annotation, and prove each is declared in an auto-configuration**

```bash
cd /Users/jorge/IdeaProjects/chawpi
M=chawpi-pages
LIST=$(mktemp)
command grep -rn -A4 -E '^@(Service|Component)\b' backend/$M/src/main/kotlin --include='*.kt' | command grep -oE 'class [A-Z][A-Za-z0-9]*' | awk '{print $2}' | sort -u > "$LIST"
cat "$LIST"
while read -r cls; do
  command grep -rqE "\b$cls(\(|::class)" backend/$M/src/main/kotlin --include='*AutoConfiguration.kt' || echo "NOT DECLARED IN AN AUTO-CONFIG: $cls"
done < "$LIST"
```

Expected: the class list, and no `NOT DECLARED` line. A `NOT DECLARED` class stops the task, because removing its
annotation would drop a bean. Report it to the controller.

- [ ] **Step 2: Run the module's tests before the change (baseline)**

Run: `cd /Users/jorge/IdeaProjects/chawpi && ./gradlew :chawpi-pages:test`
Expected: green. Note the test count:
`command grep -h -o 'tests="[0-9]*"' backend/chawpi-pages/build/test-results/test/*.xml | tr -dc '0-9\n' | paste -sd+ - | bc`

- [ ] **Step 3: Remove `@Service` and `@Component`**

For every class in `$LIST`: delete the annotation line, and delete its import (`org.springframework.stereotype.Service`
/ `Component`) when nothing else in the file uses it. Do not touch `@Repository`, `@RestController`, `@Controller`,
`@Configuration`, `@AutoConfiguration` or any `@Order`.

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -rn -E '^@(Service|Component)\b|import org\.springframework\.stereotype\.(Service|Component)$' backend/chawpi-pages/src/main/kotlin
```

Expected: no output.

- [ ] **Step 4: Format and test**

```bash
cd /Users/jorge/IdeaProjects/chawpi
./gradlew :chawpi-pages:ktlintFormat :chawpi-pages:build
```

Expected: green, with the same test count as Step 2 (the auto-configuration tests prove every bean still exists).
The module's integration tests run in Task 40.

---

### Task 33: chawpi-workflow — inert stereotypes out

**Files:**
- Modify: `backend/chawpi-workflow/src/main/kotlin/chawpi/workflow/**` (annotation lines and their imports only)

**Interfaces:**
- Consumes: nothing new.
- Produces: no `@Service`/`@Component` in chawpi-workflow main code. `@Repository` and `@RestController` stay (ADR-024).

The P2 ledger deferred this: the stereotypes are inert, because component scanning never reaches library packages.
They are also a trap: an app placed in package `chawpi` would scan them and register the beans twice.

Two auto-configurations: `ChawpiWorkflowAutoConfiguration` and `ChawpiWorkflowPagesAutoConfiguration`.

- [ ] **Step 1: Record which classes lose an annotation, and prove each is declared in an auto-configuration**

```bash
cd /Users/jorge/IdeaProjects/chawpi
M=chawpi-workflow
LIST=$(mktemp)
command grep -rn -A4 -E '^@(Service|Component)\b' backend/$M/src/main/kotlin --include='*.kt' | command grep -oE 'class [A-Z][A-Za-z0-9]*' | awk '{print $2}' | sort -u > "$LIST"
cat "$LIST"
while read -r cls; do
  command grep -rqE "\b$cls(\(|::class)" backend/$M/src/main/kotlin --include='*AutoConfiguration.kt' || echo "NOT DECLARED IN AN AUTO-CONFIG: $cls"
done < "$LIST"
```

Expected: the class list, and no `NOT DECLARED` line. A `NOT DECLARED` class stops the task, because removing its
annotation would drop a bean. Report it to the controller.

- [ ] **Step 2: Run the module's tests before the change (baseline)**

Run: `cd /Users/jorge/IdeaProjects/chawpi && ./gradlew :chawpi-workflow:test`
Expected: green. Note the test count:
`command grep -h -o 'tests="[0-9]*"' backend/chawpi-workflow/build/test-results/test/*.xml | tr -dc '0-9\n' | paste -sd+ - | bc`

- [ ] **Step 3: Remove `@Service` and `@Component`**

For every class in `$LIST`: delete the annotation line, and delete its import (`org.springframework.stereotype.Service`
/ `Component`) when nothing else in the file uses it. Do not touch `@Repository`, `@RestController`, `@Controller`,
`@Configuration`, `@AutoConfiguration` or any `@Order`.

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -rn -E '^@(Service|Component)\b|import org\.springframework\.stereotype\.(Service|Component)$' backend/chawpi-workflow/src/main/kotlin
```

Expected: no output.

- [ ] **Step 4: Format and test**

```bash
cd /Users/jorge/IdeaProjects/chawpi
./gradlew :chawpi-workflow:ktlintFormat :chawpi-workflow:build
```

Expected: green, with the same test count as Step 2 (the auto-configuration tests prove every bean still exists).
The module's integration tests run in Task 40.

---

### Task 34: chawpi-automation — inert stereotypes out

**Files:**
- Modify: `backend/chawpi-automation/src/main/kotlin/chawpi/automation/**` (annotation lines and their imports only)

**Interfaces:**
- Consumes: nothing new.
- Produces: no `@Service`/`@Component` in chawpi-automation main code. `@Repository` and `@RestController` stay (ADR-024).

The P2 ledger deferred this: the stereotypes are inert, because component scanning never reaches library packages.
They are also a trap: an app placed in package `chawpi` would scan them and register the beans twice.

Six annotated classes today. The background drain and the webhook sender must stay declared in `ChawpiAutomationAutoConfiguration`.

- [ ] **Step 1: Record which classes lose an annotation, and prove each is declared in an auto-configuration**

```bash
cd /Users/jorge/IdeaProjects/chawpi
M=chawpi-automation
LIST=$(mktemp)
command grep -rn -A4 -E '^@(Service|Component)\b' backend/$M/src/main/kotlin --include='*.kt' | command grep -oE 'class [A-Z][A-Za-z0-9]*' | awk '{print $2}' | sort -u > "$LIST"
cat "$LIST"
while read -r cls; do
  command grep -rqE "\b$cls(\(|::class)" backend/$M/src/main/kotlin --include='*AutoConfiguration.kt' || echo "NOT DECLARED IN AN AUTO-CONFIG: $cls"
done < "$LIST"
```

Expected: the class list, and no `NOT DECLARED` line. A `NOT DECLARED` class stops the task, because removing its
annotation would drop a bean. Report it to the controller.

- [ ] **Step 2: Run the module's tests before the change (baseline)**

Run: `cd /Users/jorge/IdeaProjects/chawpi && ./gradlew :chawpi-automation:test`
Expected: green. Note the test count:
`command grep -h -o 'tests="[0-9]*"' backend/chawpi-automation/build/test-results/test/*.xml | tr -dc '0-9\n' | paste -sd+ - | bc`

- [ ] **Step 3: Remove `@Service` and `@Component`**

For every class in `$LIST`: delete the annotation line, and delete its import (`org.springframework.stereotype.Service`
/ `Component`) when nothing else in the file uses it. Do not touch `@Repository`, `@RestController`, `@Controller`,
`@Configuration`, `@AutoConfiguration` or any `@Order`.

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -rn -E '^@(Service|Component)\b|import org\.springframework\.stereotype\.(Service|Component)$' backend/chawpi-automation/src/main/kotlin
```

Expected: no output.

- [ ] **Step 4: Format and test**

```bash
cd /Users/jorge/IdeaProjects/chawpi
./gradlew :chawpi-automation:ktlintFormat :chawpi-automation:build
```

Expected: green, with the same test count as Step 2 (the auto-configuration tests prove every bean still exists).
The module's integration tests run in Task 40.

---

### Task 35: chawpi-documents — inert stereotypes out

**Files:**
- Modify: `backend/chawpi-documents/src/main/kotlin/chawpi/documents/**` (annotation lines and their imports only)

**Interfaces:**
- Consumes: nothing new.
- Produces: no `@Service`/`@Component` in chawpi-documents main code. `@Repository` and `@RestController` stay (ADR-024).

The P2 ledger deferred this: the stereotypes are inert, because component scanning never reaches library packages.
They are also a trap: an app placed in package `chawpi` would scan them and register the beans twice.

Two auto-configurations: `ChawpiDocumentsAutoConfiguration` and `ChawpiDocumentsAutomationAutoConfiguration` (the `DocumentIssuer` adapter).

- [ ] **Step 1: Record which classes lose an annotation, and prove each is declared in an auto-configuration**

```bash
cd /Users/jorge/IdeaProjects/chawpi
M=chawpi-documents
LIST=$(mktemp)
command grep -rn -A4 -E '^@(Service|Component)\b' backend/$M/src/main/kotlin --include='*.kt' | command grep -oE 'class [A-Z][A-Za-z0-9]*' | awk '{print $2}' | sort -u > "$LIST"
cat "$LIST"
while read -r cls; do
  command grep -rqE "\b$cls(\(|::class)" backend/$M/src/main/kotlin --include='*AutoConfiguration.kt' || echo "NOT DECLARED IN AN AUTO-CONFIG: $cls"
done < "$LIST"
```

Expected: the class list, and no `NOT DECLARED` line. A `NOT DECLARED` class stops the task, because removing its
annotation would drop a bean. Report it to the controller.

- [ ] **Step 2: Run the module's tests before the change (baseline)**

Run: `cd /Users/jorge/IdeaProjects/chawpi && ./gradlew :chawpi-documents:test`
Expected: green. Note the test count:
`command grep -h -o 'tests="[0-9]*"' backend/chawpi-documents/build/test-results/test/*.xml | tr -dc '0-9\n' | paste -sd+ - | bc`

- [ ] **Step 3: Remove `@Service` and `@Component`**

For every class in `$LIST`: delete the annotation line, and delete its import (`org.springframework.stereotype.Service`
/ `Component`) when nothing else in the file uses it. Do not touch `@Repository`, `@RestController`, `@Controller`,
`@Configuration`, `@AutoConfiguration` or any `@Order`.

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -rn -E '^@(Service|Component)\b|import org\.springframework\.stereotype\.(Service|Component)$' backend/chawpi-documents/src/main/kotlin
```

Expected: no output.

- [ ] **Step 4: Format and test**

```bash
cd /Users/jorge/IdeaProjects/chawpi
./gradlew :chawpi-documents:ktlintFormat :chawpi-documents:build
```

Expected: green, with the same test count as Step 2 (the auto-configuration tests prove every bean still exists).
The module's integration tests run in Task 40.

---

### Task 36: chawpi-gis — inert stereotypes out

**Files:**
- Modify: `backend/chawpi-gis/src/main/kotlin/chawpi/gis/**` (annotation lines and their imports only)

**Interfaces:**
- Consumes: nothing new.
- Produces: no `@Service`/`@Component` in chawpi-gis main code. `@Repository` and `@RestController` stay (ADR-024).

The P2 ledger deferred this: the stereotypes are inert, because component scanning never reaches library packages.
They are also a trap: an app placed in package `chawpi` would scan them and register the beans twice.

Two auto-configurations: `ChawpiGisAutoConfiguration` and `ChawpiGisPagesAutoConfiguration` (the MAP provider).

- [ ] **Step 1: Record which classes lose an annotation, and prove each is declared in an auto-configuration**

```bash
cd /Users/jorge/IdeaProjects/chawpi
M=chawpi-gis
LIST=$(mktemp)
command grep -rn -A4 -E '^@(Service|Component)\b' backend/$M/src/main/kotlin --include='*.kt' | command grep -oE 'class [A-Z][A-Za-z0-9]*' | awk '{print $2}' | sort -u > "$LIST"
cat "$LIST"
while read -r cls; do
  command grep -rqE "\b$cls(\(|::class)" backend/$M/src/main/kotlin --include='*AutoConfiguration.kt' || echo "NOT DECLARED IN AN AUTO-CONFIG: $cls"
done < "$LIST"
```

Expected: the class list, and no `NOT DECLARED` line. A `NOT DECLARED` class stops the task, because removing its
annotation would drop a bean. Report it to the controller.

- [ ] **Step 2: Run the module's tests before the change (baseline)**

Run: `cd /Users/jorge/IdeaProjects/chawpi && ./gradlew :chawpi-gis:test`
Expected: green. Note the test count:
`command grep -h -o 'tests="[0-9]*"' backend/chawpi-gis/build/test-results/test/*.xml | tr -dc '0-9\n' | paste -sd+ - | bc`

- [ ] **Step 3: Remove `@Service` and `@Component`**

For every class in `$LIST`: delete the annotation line, and delete its import (`org.springframework.stereotype.Service`
/ `Component`) when nothing else in the file uses it. Do not touch `@Repository`, `@RestController`, `@Controller`,
`@Configuration`, `@AutoConfiguration` or any `@Order`.

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -rn -E '^@(Service|Component)\b|import org\.springframework\.stereotype\.(Service|Component)$' backend/chawpi-gis/src/main/kotlin
```

Expected: no output.

- [ ] **Step 4: Format and test**

```bash
cd /Users/jorge/IdeaProjects/chawpi
./gradlew :chawpi-gis:ktlintFormat :chawpi-gis:build
```

Expected: green, with the same test count as Step 2 (the auto-configuration tests prove every bean still exists).
The module's integration tests run in Task 40.

---

### Task 37: chawpi-agent — inert stereotypes out

**Files:**
- Modify: `backend/chawpi-agent/src/main/kotlin/chawpi/agent/**` (annotation lines and their imports only)

**Interfaces:**
- Consumes: nothing new.
- Produces: no `@Service`/`@Component` in chawpi-agent main code. `@Repository` and `@RestController` stay (ADR-024).

The P2 ledger deferred this: the stereotypes are inert, because component scanning never reaches library packages.
They are also a trap: an app placed in package `chawpi` would scan them and register the beans twice.

Two auto-configurations: `ChawpiAgentAutoConfiguration` and `ChawpiAgentWorkflowAutoConfiguration`. Embabel's own annotations (`@Agent`, `@Action` and the like) are not Spring stereotypes and stay.

- [ ] **Step 1: Record which classes lose an annotation, and prove each is declared in an auto-configuration**

```bash
cd /Users/jorge/IdeaProjects/chawpi
M=chawpi-agent
LIST=$(mktemp)
command grep -rn -A4 -E '^@(Service|Component)\b' backend/$M/src/main/kotlin --include='*.kt' | command grep -oE 'class [A-Z][A-Za-z0-9]*' | awk '{print $2}' | sort -u > "$LIST"
cat "$LIST"
while read -r cls; do
  command grep -rqE "\b$cls(\(|::class)" backend/$M/src/main/kotlin --include='*AutoConfiguration.kt' || echo "NOT DECLARED IN AN AUTO-CONFIG: $cls"
done < "$LIST"
```

Expected: the class list, and no `NOT DECLARED` line. A `NOT DECLARED` class stops the task, because removing its
annotation would drop a bean. Report it to the controller.

- [ ] **Step 2: Run the module's tests before the change (baseline)**

Run: `cd /Users/jorge/IdeaProjects/chawpi && ./gradlew :chawpi-agent:test`
Expected: green. Note the test count:
`command grep -h -o 'tests="[0-9]*"' backend/chawpi-agent/build/test-results/test/*.xml | tr -dc '0-9\n' | paste -sd+ - | bc`

- [ ] **Step 3: Remove `@Service` and `@Component`**

For every class in `$LIST`: delete the annotation line, and delete its import (`org.springframework.stereotype.Service`
/ `Component`) when nothing else in the file uses it. Do not touch `@Repository`, `@RestController`, `@Controller`,
`@Configuration`, `@AutoConfiguration` or any `@Order`.

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -rn -E '^@(Service|Component)\b|import org\.springframework\.stereotype\.(Service|Component)$' backend/chawpi-agent/src/main/kotlin
```

Expected: no output.

- [ ] **Step 4: Format and test**

```bash
cd /Users/jorge/IdeaProjects/chawpi
./gradlew :chawpi-agent:ktlintFormat :chawpi-agent:build
```

Expected: green, with the same test count as Step 2 (the auto-configuration tests prove every bean still exists).
The module's integration tests run in Task 40.

---


### Task 38: `@chawpi/documents` — pin tiptap exactly

**Files:**
- Modify: `frontend/packages/documents/package.json`
- Modify: `yarn.lock` (only the tiptap entry keys, via `yarn install`)

**Interfaces:**
- Consumes: the tiptap versions resolved in `yarn.lock` today.
- Produces: `@tiptap/core`, `@tiptap/react` and `@tiptap/starter-kit` pinned to one exact version, so a published
  `@chawpi/documents` cannot pull in a newer tiptap 3 minor that changes the editor's JSON and breaks frozen documents
  (ADR-023). The P5 final review deferred this "before publish".

Wave 2 only: `yarn install` rewrites `yarn.lock`, which P6 also writes.

- [ ] **Step 1: Read the resolved versions**

```bash
cd /Users/jorge/IdeaProjects/chawpi
for p in core react starter-kit; do command grep -A1 -E "^\"?@tiptap/$p@" yarn.lock | head -2; done
node -p "JSON.stringify(require('./frontend/packages/documents/package.json').dependencies)"
```

Expected: each resolves to one version (3.31.3 at the time of writing), and the ranges are `"3"`. If the three
packages resolve to different versions, pin each to its own resolved version.

- [ ] **Step 2: Pin** — in `frontend/packages/documents/package.json` `dependencies`, replace `"3"` with the exact
  resolved version for each of the three entries (for example `"@tiptap/core": "3.31.3"`). No other change.

- [ ] **Step 3: Update the lockfile and verify**

```bash
cd /Users/jorge/IdeaProjects/chawpi
BEFORE=$(mktemp); AFTER=$(mktemp)
command grep -E '^  version "' yarn.lock > "$BEFORE"
yarn install
command grep -E '^  version "' yarn.lock > "$AFTER"
diff "$BEFORE" "$AFTER" && echo "no resolved version changed"
command grep -n -E '^"?@tiptap/(core|react|starter-kit)@' yarn.lock
yarn install --frozen-lockfile
yarn prettier --check frontend/packages/documents/package.json
yarn workspace @chawpi/documents lint && yarn workspace @chawpi/documents test && yarn workspace @chawpi/documents build
node frontend/tooling/check-release.mjs
```

Expected: `no resolved version changed` (only entry keys change), the tiptap keys include the exact versions,
`--frozen-lockfile` passes, lint, test and build are green, and `check-release: ok`.

---

### Task 39: CI and publish workflows — every suite on every pull request, guards before every upload

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/publish.yml`
- Modify: `commitlint.config.mjs` (one comment), `.prettierignore` (one comment)

**Interfaces:**
- Consumes: `node frontend/tooling/check-release.mjs [--pack <version>]` and
  `.github/scripts/check-maven-publications.sh <dir>` (Task 26); the IT task names from
  `backend/chawpi-integration-tests/build.gradle.kts` (P3, final); the sample layout from `examples/README.md` and the
  P6 ledger (P6, final).
- Produces: CI jobs `backend`, `integration`, `frontend`, `examples`, `publish-dry-run`. The publish jobs run the
  guards before uploading.

- [ ] **Step 1: Read what P3 and P6 left**

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -n -E 'register|named|integrationTest|dependsOn|systemProperty' backend/chawpi-integration-tests/build.gradle.kts | head -40
tail -15 .superpowers/sdd/2026-09-25-p3-integration-tests/progress.md
ls .superpowers/sdd | command grep p6 && tail -15 .superpowers/sdd/2026-09-25-p6-*/progress.md
cat examples/README.md
ls examples/*/server examples/*/web 2>/dev/null
command grep -rn -E '"(e2e|smoke|test:e2e)"' examples/*/web/package.json 2>/dev/null
```

Write down: (a) whether `./gradlew integrationTest` runs every IT suite of `chawpi-integration-tests` (P3 wired its
suite tasks to it, per its ledger's "Gradle wiring" ruling) or which extra task names must be listed; (b) the
sample server and web names; (c) whether P6 added a headless end-to-end command.

- [ ] **Step 2: Rewrite `.github/workflows/ci.yml`**

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
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v4
      - name: build-logic tests
        run: ./gradlew -p backend/build-logic test --no-daemon
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
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v4
      # github runners have a local docker daemon: testcontainers starts postgres:18 and
      # postgis/postgis:18-3.6 per suite. no CHAWPI_TEST_DB_* here, on purpose.
      - name: Integration tests
        run: ./gradlew integrationTest --no-daemon
      - name: Upload test reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: integration-test-reports
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
      - run: yarn test:tooling
      - run: yarn lint
      - run: yarn test
      # every package, then every sample web against the packages' dist
      - run: yarn build
      - name: Release guard (npm)
        run: node frontend/tooling/check-release.mjs --pack 0.0.0-ci

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

  publish-dry-run:
    name: Publish dry run (Maven)
    runs-on: ubuntu-latest
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

Adjust from Step 1: if `integrationTest` does not reach every P3 suite, list the missing task names on the same
`run` line. If P6 added a headless end-to-end command that needs only Docker and Node, add it as a step to
`examples` (with the setup steps it needs); otherwise leave `examples` as above and name the missing command in the
report. The obsolete "Check for integration tests" gate and the `hashFiles` condition go away: both modules and
packages exist now.

- [ ] **Step 3: Harden `.github/workflows/publish.yml`**

In the `maven` job, insert before the `Publish to GitHub Packages` step:

```yaml
      - name: Release guard (Maven)
        run: |
          ./gradlew publishToMavenLocal --no-daemon -Pversion="${GITHUB_REF_NAME#v}" -Dmaven.repo.local="$RUNNER_TEMP/m2"
          .github/scripts/check-maven-publications.sh "$RUNNER_TEMP/m2"
```

In the `npm` job, remove the `packages` presence step and every `if: steps.packages.outputs.present == 'true'`
(packages exist now). The guard runs **before** `set-version.mjs` touches the checkout, so the publish step becomes:

```yaml
      - name: Publish every public @chawpi package
        env:
          NODE_AUTH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          version="${GITHUB_REF_NAME#v}"
          # dry run on a copy first: the exact set, internal ranges pinned, entry points in every tarball
          node frontend/tooling/check-release.mjs --pack "$version"
          # bumps every package's own version AND internal @chawpi/* dep ranges; plain
          # `npm version` per package would leave internal deps pointing at the old version.
          node frontend/tooling/set-version.mjs "$version"
          for dir in frontend/packages/*/; do
            if [ "$(node -p "require('./$dir/package.json').private === true")" = "true" ]; then continue; fi
            (cd "$dir" && npm publish)
          done
```

The loop only walks `frontend/packages/*`, so sample webs (`examples/*/web`) are never published, and `@chawpi/smoke`
is skipped as private. The Maven publish only reaches projects that apply `chawpi.publishing`; the guard proves it.

- [ ] **Step 4: Reword the two config comments**

- `commitlint.config.mjs`: `// sapgis history uses long, sentence-like subjects` →
  `// the imported history uses long, sentence-like subjects`
- `.prettierignore`: `# copied sapgis data, kept byte-identical` →
  `# data copied from the original app, kept byte-identical`

- [ ] **Step 5: Validate the workflows and run their commands locally**

```bash
cd /Users/jorge/IdeaProjects/chawpi
ruby -ryaml -e 'ARGV.each { |f| YAML.load_file(f); puts "#{f}: yaml ok" }' .github/workflows/*.yml
command -v actionlint >/dev/null && actionlint || echo "actionlint not installed: yaml parse only"
yarn prettier --check .github commitlint.config.mjs
yarn commitlint --help >/dev/null && echo "commitlint loads its config"
S=$(mktemp -d)
./gradlew publishToMavenLocal --no-daemon -Pversion=0.0.0-ci -Dmaven.repo.local="$S/m2"
.github/scripts/check-maven-publications.sh "$S/m2"
rm -rf "$S"
yarn build && node frontend/tooling/check-release.mjs --pack 0.0.0-ci
```

Expected: every workflow parses. prettier is clean. `maven publications: ok (20)`: no sample server, no
`chawpi-integration-tests`. `check-release: ok`. If the Maven guard reports an extra artifact from a P6 sample, the
sample applies `chawpi.publishing` by mistake: report it (P6's fix; do not edit `examples/**`).

---

## Wave 3 — final verification

### Task 40: Whole-repository verification and the last HISTORY entries

**Files:**
- Modify: `docs/HISTORY.md` (entries for P3, P6 if missing, and P7)
- Everything else read-only. A failure found here goes back to the task that owns the file (File map), or to the
  controller for P3/P6 paths, and this task re-runs from the failed step.

**Interfaces:**
- Consumes: every task of waves 1 and 2; the finished P3 and P6.
- Produces: the evidence for "P7 COMPLETE" (each step's output quoted in the report).

- [ ] **Step 0: Wait for P3/P6 completion and for Tasks 1–39**

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -n 'P3 COMPLETE' .superpowers/sdd/2026-09-25-p3-integration-tests/progress.md
command grep -n 'P6 COMPLETE' .superpowers/sdd/2026-09-25-p6-*/progress.md
git log --oneline -1
```

Expected: both lines exist. HEAD is still `d94065c` or whatever commit P7 started on, because nothing was committed.

- [ ] **Step 1: Backend build and unit tests**

```bash
cd /Users/jorge/IdeaProjects/chawpi
./gradlew -p backend/build-logic test
./gradlew build
```

Expected: green. This covers ktlint, the unit tests of every library, the architecture test, the auto-configuration
tests and the sample servers.

- [ ] **Step 2: Integration tests (all suites, core-only and full)**

This machine's Docker daemon is remote, so the ITs run against the tunnelled external databases:

```bash
cd /Users/jorge/IdeaProjects/chawpi
nc -z localhost 5443 && nc -z localhost 5442 && echo "tunnel up" || echo "TUNNEL DOWN"
source backend/chawpi-integration-tests/it-env.sh
./gradlew integrationTest --rerun
```

Expected: `tunnel up`, then green with every suite reporting (count the results:
`find backend -path '*/build/test-results/*' -name 'TEST-*.xml' -newer settings.gradle.kts | wc -l`). If the tunnel
is down, the report says "ITs not run: tunnel down", and P7 is not complete until they run. Never claim them green.
The Testcontainers path is proven by the CI `integration` job on the first push.

- [ ] **Step 3: Frontend and tooling**

```bash
cd /Users/jorge/IdeaProjects/chawpi
yarn install --frozen-lockfile
yarn format:check && yarn test:tooling && yarn lint && yarn test && yarn build
node frontend/tooling/check-release.mjs --pack 0.0.0-verify
(cd examples/gis-sample/perene && python3 -m unittest -q)
```

Expected: all green, `check-release: ok`, and the perene tests pass. `yarn build` also builds every sample web
against the packages' `dist`.

- [ ] **Step 4: The word "sapgis" appears only where ADR-030 allows it**

```bash
cd /Users/jorge/IdeaProjects/chawpi
command grep -rIn -i sapgis . \
  --exclude-dir=node_modules --exclude-dir=build --exclude-dir=dist --exclude-dir=.git --exclude-dir=.superpowers \
  --exclude-dir=.gradle --exclude-dir=.idea --exclude-dir=.kotlin --exclude-dir=coverage \
  | command grep -v -E '^\./docs/(HISTORY\.md|sapgis-origin\.md|superpowers/)' \
  | command grep -v -E '^\./docs/adr/00(2[4-9]|3[01])-' \
  | command grep -v -E '^\./docs/adr/00(0[1-9]|1[0-9]|2[0-3])-[a-z0-9-]+\.md:[0-9]+:> Imported from sapgis' \
  | command grep -v -E '^\./backend/chawpi-integration-tests/' \
  | command grep -v -E '^\./examples/gis-sample/perene/' \
  | command grep -v -E '^\./frontend/tooling/port-from-sapgis' \
  | command grep -v -E 'doesNotContainIgnoringCase\("sapgis"\)|not\.toMatch\(/sapgis/i\)|carries no sapgis name' \
  | command grep -v -E '^\./(README|CLAUDE)\.md:[0-9]+:.*\[sapgis( origin)?\]\(' \
  | sed -E 's/(deliberate-deviations-from-sapgis|rebrand-sapgis-to-chawpi|sapgis-origin)\.md//g' \
  | command grep -i sapgis
```

Expected: no output. The allowed places are exactly ADR-030's list: history, the origin page, `docs/superpowers`,
ADR provenance headers (0001–0023) and ADRs 0024–0031, the README/CLAUDE.md provenance link, the integration-test
porting scripts, the porting tool, copied Perené data, and the guard tests. The `sed` lets a doc link to ADR-030,
ADR-031 or the origin page by file name. Any other hit is a leftover: route it to the task that owns the file. A hit
under `examples/**` outside `perene/` goes to the controller as a P6 fix.

- [ ] **Step 5: Docs — links and names**

```bash
cd /Users/jorge/IdeaProjects/chawpi
DOCS="README.md CLAUDE.md examples/README.md $(ls docs/*.md docs/adr/*.md docs/architecture/*.md docs/domain/*.md docs/api/*.md docs/gis/*.md docs/security/*.md docs/development/*.md docs/modules/*.md docs/guides/*.md frontend/packages/*/README.md backend/starters/*/README.md 2>/dev/null)"
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
process.exit(bad ? 1 : 0)' $DOCS && echo "links ok"
```

Then run the name check (the same script as Task 21 Step 5, repeated here in full) over the docs that name
artifacts, packages and properties:

```bash
cd /Users/jorge/IdeaProjects/chawpi
node -e '
const fs = require("fs"), path = require("path"), root = process.cwd()
const artifacts = new Set([...fs.readdirSync("backend"), ...fs.readdirSync("backend/starters")])
const pkgs = new Set(fs.readdirSync("frontend/packages").map((d) => { try { return require(path.join(root, "frontend/packages", d, "package.json")).name } catch { return null } }))
const props = new Set(["chawpi.seed.dev", "chawpi.test.db.image"])
const kotlinPackages = new Set()
const walk = (d) => { for (const e of fs.readdirSync(d, { withFileTypes: true })) {
  const p = path.join(d, e.name)
  if (e.isDirectory()) { if (!["build", "node_modules", ".gradle"].includes(e.name)) walk(p); continue }
  if (!e.name.endsWith(".kt")) continue
  const t = fs.readFileSync(p, "utf8")
  const pkg = t.match(/^package ([\w.]+)/m); if (pkg) kotlinPackages.add(pkg[1])
  const m = t.match(/@ConfigurationProperties\((?:prefix = )?"([^"]+)"\)/); if (!m) continue
  for (const v of t.matchAll(/^\s+val (\w+)\s*:/gm)) props.add(m[1] + "." + v[1].replace(/[A-Z]/g, (c) => "-" + c.toLowerCase()))
} }
walk("backend")
const plugins = new Set(fs.readdirSync("backend/build-logic/src/main/kotlin").map((f) => f.replace(/\.gradle\.kts$/, "")))
let bad = 0
for (const f of process.argv.slice(1)) {
  const t = fs.readFileSync(f, "utf8")
  for (const m of t.matchAll(/chawpi:(chawpi-[a-z-]+)/g)) if (!artifacts.has(m[1])) { console.log(f + ": unknown artifact " + m[1]); bad++ }
  for (const m of t.matchAll(/@chawpi\/([a-z-]+)/g)) if (!pkgs.has("@chawpi/" + m[1])) { console.log(f + ": unknown package @chawpi/" + m[1]); bad++ }
  for (const m of t.matchAll(/`(chawpi\.[a-z][a-z0-9.-]*[a-z0-9])`/g)) {
    const k = m[1]
    if (props.has(k) || kotlinPackages.has(k) || plugins.has(k) || [...props].some((p) => p.startsWith(k + "."))) continue
    console.log(f + ": unknown property/package " + k); bad++
  }
}
process.exit(bad ? 1 : 0)' README.md CLAUDE.md docs/architecture/overview.md docs/modules/*.md docs/guides/*.md docs/development/*.md docs/security/*.md docs/gis/*.md && echo "names ok"
```

Expected: `links ok` and `names ok`. Also run the MD check (Global Constraints) over every file in `$DOCS` that P7
created or edited (File map), and expect it clean.

- [ ] **Step 6: The published set, for real**

```bash
cd /Users/jorge/IdeaProjects/chawpi
S=$(mktemp -d)
./gradlew publishToMavenLocal --no-daemon -Pversion=0.0.0-verify -Dmaven.repo.local="$S/m2"
.github/scripts/check-maven-publications.sh "$S/m2"
command grep -A2 '<artifactId>chawpi-core</artifactId>' "$S/m2/chawpi/chawpi-test/0.0.0-verify/chawpi-test-0.0.0-verify.pom"
rm -rf "$S"
```

Expected: `maven publications: ok (20)`, and the chawpi-test POM names chawpi-core (Task 28).

- [ ] **Step 6b: Consume the libraries from outside the repository** (spec "Verification": `publishToMavenLocal` +
  `npm pack` consumed outside the workspace; also proves the guide's minimal app)

```bash
cd /Users/jorge/IdeaProjects/chawpi
S=$(mktemp -d); V=0.0.0-verify
./gradlew publishToMavenLocal --no-daemon -Pversion=$V -Dmaven.repo.local="$S/m2"
mkdir -p "$S/app/src/main/kotlin/com/example/myapp"
cat > "$S/app/settings.gradle.kts" <<'KTS'
rootProject.name = "myapp"
KTS
cat > "$S/app/build.gradle.kts" <<KTS
plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    id("org.springframework.boot") version "4.1.1"
}
repositories {
    mavenCentral()
    maven { url = uri("file://$S/m2") }
}
dependencies {
    implementation(platform("chawpi:chawpi-bom:$V"))
    implementation("chawpi:chawpi-spring-boot-starter")
    implementation("chawpi:chawpi-spring-boot-starter-gis")
}
kotlin { jvmToolchain(25) }
KTS
cat > "$S/app/src/main/kotlin/com/example/myapp/MyApp.kt" <<'KT'
package com.example.myapp

import chawpi.core.autoconfigure.ChawpiApplication
import org.springframework.boot.runApplication

@ChawpiApplication
class MyApp

fun main(args: Array<String>) {
    runApplication<MyApp>(*args)
}
KT
./gradlew -p "$S/app" --no-daemon compileKotlin
mkdir -p "$S/web/pkgs" && cp -R frontend/packages/ui frontend/packages/core "$S/web/pkgs/" && rm -rf "$S/web/pkgs/"*/node_modules
node frontend/tooling/set-version.mjs $V "$S/web/pkgs"
(cd "$S/web/pkgs/ui" && npm pack --ignore-scripts --pack-destination "$S/web") && (cd "$S/web/pkgs/core" && npm pack --ignore-scripts --pack-destination "$S/web")
node -e '
const [dir, v] = process.argv.slice(1)
const peers = require("./frontend/packages/core/package.json").peerDependencies
const deps = { "@chawpi/ui": `file:./chawpi-ui-${v}.tgz`, "@chawpi/core": `file:./chawpi-core-${v}.tgz` }
for (const [n, r] of Object.entries(peers)) if (!n.startsWith("@chawpi/")) deps[n] = r
require("fs").writeFileSync(dir + "/package.json", JSON.stringify({ name: "consumer", private: true, type: "module", dependencies: deps, devDependencies: { typescript: "5.9.3", "@types/react": "19.3.0", "@types/react-dom": "19.3.0" } }, null, 2))' "$S/web" $V
cat > "$S/web/main.tsx" <<'TSX'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { ChawpiApp } from '@chawpi/core'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ChawpiApp config={{ apiBaseUrl: '/api', appName: 'My App' }} modules={[]} />
  </StrictMode>
)
TSX
(cd "$S/web" && npm install --no-audit --no-fund && npx tsc --noEmit --jsx react-jsx --module esnext --moduleResolution bundler --target es2022 --strict --skipLibCheck false --lib dom,es2022 main.tsx)
rm -rf "$S"
```

Expected: `compileKotlin` succeeds against the locally published BOM and starters (the POMs resolve with no
project references). `npm install` resolves the two tarballs with their internal range pinned to `0.0.0-verify`, and
`tsc` type-checks the guide's `main.tsx` against the published declarations. `set-version.mjs` gets the copy's
path as its second argument; without it, it would rewrite the real `frontend/packages`, so check afterwards that
`node -p "require('./frontend/packages/core/package.json').version"` still prints the repository version. A failure here is a packaging defect: route it to Task 26
(npm) or Task 28 / the owning module (Maven).

- [ ] **Step 7: Every deferred P7 item is closed** (read each ledger line and point at its evidence)

| Deferred item (ledger) | Closed by |
|---|---|
| CLAUDE.md rule 2 (P1) | Task 22 |
| docs: one suite at a time, suite lock, `it-env.sh`, never app in package chawpi (P1, P3) | Tasks 11, 12, 21, ADR-024 |
| `metadataTable()` helper, `explicitApi`, `AdminService` size, FQNs (P1) | Task 29 rulings + Step 4, ADR-024 |
| module chains `@Order` < 0, overriding the chain (P1) | Tasks 10, 12 |
| `@ChawpiApplication` live-context test (P2) | Task 29 |
| stereotype annotations (P2) | Tasks 29–37 |
| chawpi-test POM core edge (P2) | Task 28, Step 6 |
| property class placement (P2) | ADR-024 ruling (no move) |
| CHECK single-owner note (P2) | Task 6 |
| agent starter README, default provider (P2) | Task 17 |
| ktlint format non-cacheable, check after format (P1, P3) | Task 27 |
| tiptap pin (P5) | Task 38 |
| unused core keys (P5) | Task 25 |
| static exports in light modules (P5) | ADR-028 ruling (kept) |
| MULTI* geometry (P5) | Tasks 8, 13 (known limitation) |
| README `/api` wording (P5) | Tasks 12–20 touch-ups |
| smoke against dist (P5) | `yarn build` of sample webs in CI (Task 39) |
| label-only edit → 409 ADR note (P1) | ADR-031 D2 |
| CI proof of the Testcontainers path (P3) | Task 39 `integration` job |
| double CI run per PR push (P0) | not a defect: `push` is limited to `main` (Task 39 keeps it) |

Expected: every row has its evidence in this task's outputs or in the owning task's report.

- [ ] **Step 8: HISTORY entries for P3, P6 and P7**

Insert at the top of `docs/HISTORY.md` (below the intro line), newest first: P7's entry on top, then P6's (only if
the P6 phase did not add its own entry; check with `command grep -n '^## 2026-09-2' docs/HISTORY.md`), then P3's.

P3 entry. Title and first paragraph are fixed. The numbers come from the P3 ledger's last lines and from Step 2's
count:

```markdown
## 2026-09-25 — The original's API tests run against the libraries

All twenty of the original app's API integration tests now run against apps assembled from the chawpi starters: a
full app with every module, and a core-only app on plain PostgreSQL that proves the geometry routes are simply
absent. Each module is also booted alone next to its optional neighbours' absence, and the final schema is compared
table by table, column by column and constraint by constraint with the original's.
```

Add a second paragraph with the counts from the P3 ledger ("N suites, M tests green") and one sentence on the
suite lock: two suites against one external database now take turns instead of wiping each other.

P6 entry (only if missing). Title: `## 2026-09-25 — Four sample apps`. One paragraph: the four samples from
`examples/README.md`, what each proves (simple-sample: core on plain PostgreSQL; documents-sample; gis-sample with the
Perené cadastre model; full-sample: every module), written from `examples/README.md` and the P6 ledger.

P7 entry, exactly:

```markdown
## 2026-09-25 — The libraries are documented, guarded and ready to publish

Every module now has a page saying what it adds, how to switch it on and off, which properties and routes it owns
and what happens without it, and a guide walks from a core-only app to one with every module. The decisions the
split took along the way are ADRs: libraries and starters (ADR-024), the frontend registry (ADR-028), one repository
and one version (ADR-029), the rename (ADR-030), and the short list of places where chawpi deliberately behaves
differently from the original (ADR-031). Everything else behaves as it did.

CI now builds and tests everything on every pull request: build-logic, every library, the integration tests on
real containers, every frontend package and sample web, and the Perené model. Before anything is published, two
guards check that exactly the twenty Maven artifacts and eleven npm packages would go out, and that each npm package
points at its own release. Library code no longer carries stereotype annotations it never used, and tiptap is
pinned, so a frozen document cannot change under a new editor version.
```

Then run the MD check on `docs/HISTORY.md`.

- [ ] **Step 9: Nothing committed**

```bash
cd /Users/jorge/IdeaProjects/chawpi
git log --oneline -1
git status --short | wc -l
```

Expected: the same HEAD as in Step 0, and a non-zero count of uncommitted changes. The report ends with the
outputs of Steps 1–8 and the line `P7 COMPLETE` only if every step is green (Step 2 included).

## Self-review

Checked against the spec with the plan complete.

**1. Spec coverage**

| Spec requirement | Task |
|---|---|
| New ADRs 0024, 0028, 0029, 0030 ("Docs & preservation") | 1, 2, 3, 4 |
| ADRs for significant rulings not yet recorded | 5 (ADR-031 deviations), 6 (0026 CHECK owner addendum). The security chain `@Order(0)` is already recorded in ADR-025 ("security chain" paragraph), so it gets no ADR of its own: Tasks 7, 10 and 12 document it |
| Imported ADRs are not edited to change decisions | 6 adds dated addenda to chawpi's own 0026/0027 only; 0001–0023 untouched |
| Fix stale geometry section in metadata-model.md and the auth doc | 8, 10 |
| `docs/modules/<module>.md` per library (what it adds, config, SPIs, frontend package) | 12–20 (template in Global Constraints) |
| "Build your app" guide | 21 |
| architecture, api, gis, development docs updated to the module layout | 7, 9, 8, 11 |
| CLAUDE.md rewritten, rules preserved (rule 2 fixed; no-commit, editorconfig, Conventional Commits) | 22 |
| HISTORY.md, one entry per phase | 23 (P2, P4, P5), 40 (P3, P6, P7). P0 and P1 entries exist |
| Deferred code cleanups, behaviour identical, one task per module/package | 25, 27, 28, 29–37, 38. Rulings with no code change: ADR-024 (explicitApi, property placement), Task 29 (metadataTable, AdminService), ADR-028 (static exports) |
| Commitlint in CI + PR title | kept as is in `commits.yml` (verified in Task 39 Step 5) |
| release-please lockstep, publish.yml on release, GitHub Packages, samples/ITs/private never published | 3 (ADR), 26 (guards), 39 (wiring) |
| CI: build-logic tests, gradle build, ITs on GitHub runners via Testcontainers, frontend lint/test/build, perene, sample builds | 39 |
| `set-version.mjs` + `npm pack` dry run for every public package | 26 (`packDryRun`), 39 (CI + publish), 40 Step 3 |
| Verification: build, ITs, frontend, publishToMavenLocal + npm pack consumed outside the workspace | 40 Steps 1–3, 6, 6b |
| Verification: `/actuator/mappings` parity, `pg_dump` schema diff, Playwright smoke on full-sample | owned by P3 (schema parity suite, route list) and P6 (full-sample). Task 40 Step 2 re-runs P3's suites. Task 39 wires P6's end-to-end command into CI if P6 provides a headless one |
| No git commits (user) | Global Constraints; Task 40 Step 9 |
| P7 never touches `backend/chawpi-integration-tests/**` or `examples/**` | Global Constraints; every finding there goes to the controller |

No spec requirement is without a task.

**2. Placeholder scan.** Searched for "TBD", "TODO", "implement later", "fill in", "similar to Task". None. The
`<!-- … -->` blocks inside doc outlines (Tasks 7, 11, 20, 21) are content instructions for prose that must be
written from code facts. Each says exactly which facts and which sources. Task 40's P3 entry takes its counts from
the P3 ledger because they do not exist yet. Every code step shows the code.

**3. Consistency.** File names used across tasks match the File map (ADR files `0024-libraries-and-starters.md`,
`0028-frontend-module-registry.md`, `0029-polyglot-monorepo-and-publishing.md`, `0030-rebrand-sapgis-to-chawpi.md`,
`0031-deliberate-deviations-from-sapgis.md`, and the module doc names). The deviation IDs D1–D16 are defined in Task 5
and cited with the same meaning in Tasks 8, 10, 12–19, 22 and 23. The `check-release.mjs` exports (`EXPECTED_PUBLIC`,
`publicPackages`, `checkReleaseConfig`, `packDryRun`, `npmPack`) and the CLI contract (`--pack <version>`, `check-release: ok`)
are the same in Tasks 26, 38, 39 and 40. `check-maven-publications.sh <dir>` and its `maven publications: ok (20)`
line are the same in 26, 39 and 40. The anchors `#security` (core.md) and `#integration-tests`
(getting-started.md) are produced by Tasks 12 and 11, which say so.

**4. Review Focus.** Each of the five lines has its test in the owning task: 1 → Task 26 tests 6–8 plus the real
`--pack` run; 2 → Task 26 tests 1–5 and Step 6, Task 39 Step 5; 3 → Task 29 Step 2; 4 → Task 21 Step 5 and
Task 40 Steps 5 and 6b; 5 → Tasks 29–37 Step 1 plus Step 4's unchanged test count.
