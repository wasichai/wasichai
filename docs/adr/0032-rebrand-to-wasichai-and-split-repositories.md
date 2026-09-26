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
