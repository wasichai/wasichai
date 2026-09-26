# ADR-029: One repository, one version, published to GitHub Packages

**Status**: accepted · 2026-09-25 · amended by [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md)

> Moved from chawpi on 2026-09-26: identifiers renamed chawpi → wasichai; the decision is unchanged. See
> [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md) and [the origin page](../chawpi-origin.md).

## Context

Wasichai is Kotlin libraries and npm packages that change together: a new field type is a backend handler plus a
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
`vX.Y.Z` and creates the GitHub Release. The first release is pinned with `release-as: 0.1.0`. `wasichai-bom` aligns
the Maven side.

**Internal npm ranges are `"*"` in the repository.** Yarn links workspace packages whatever the range, and a real
version would have to be bumped in every dependent package on every release, which release-please cannot do for
dependency entries. At publish time,
`frontend/tooling/set-version.mjs <version>` rewrites each package's own version and every `@wasichai/*` entry in
`dependencies`, `peerDependencies` and `optionalDependencies` to the exact release version. A published package
therefore always points at its own release.

**RELEASE_PLEASE_TOKEN.** A tag or release created with the workflow's `GITHUB_TOKEN` does not trigger other
workflows, so the release would never publish. release-please runs with a fine-grained token stored as the
`RELEASE_PLEASE_TOKEN` secret (contents and pull requests: write). `docs/development/releasing.md` has the setup.

**Publishing.** `publish.yml` runs on `release: published`. Maven: `./gradlew publish -Pversion=<tag without v>`
publishes every project that applies `wasichai.publishing` to GitHub Packages. npm: build, `set-version.mjs`, then
`npm publish` for every package under `frontend/packages` that is not `"private": true`. Never published: the
examples, `wasichai-integration-tests`, `@wasichai/smoke`, and the sample webs. Before anything is uploaded, two guard
scripts compare what would be published with the expected set and fail the job on any difference:
`.github/scripts/check-maven-publications.sh` (20 Maven artifacts) and `frontend/tooling/check-release.mjs`
(11 npm packages, release-please coverage, tarball contents). CI runs the same guards on every pull request.

## Consequences

- A consumer never mixes versions: `wasichai-bom:X` and `@wasichai/*@X` are one release.
- A module that changed nothing still gets a new version. That is the price of never needing a compatibility
  table.
- Adding a library means applying `wasichai.publishing` (Maven) or adding the package to release-please
  `extra-files` (npm). The guard scripts fail CI until both lists agree.
- GitHub Packages needs a token even to read, so consumers configure credentials (`releasing.md`, "Consuming a
  published library").

## Addendum (2026-09-25): npm scope renamed to `@hneyra`

GitHub Packages requires an npm package's scope to equal the repository owner, and the `chawpi` organization name
is taken. The repository is `hneyra/chawpi`, so the npm packages are published as `@hneyra/ui`, `@hneyra/core`,
`@hneyra/<module>` and `@hneyra/testing`, and consumers write `@hneyra:registry=https://npm.pkg.github.com` in their
`.npmrc`. The decision text above was written with `@chawpi/*`; read it as `@hneyra/*`. Nothing else moves:
the Maven group stays `chawpi`, the Kotlin packages `chawpi.*`, the artifacts `chawpi-*`, and the product is still
chawpi.

[2026-09-26: the decision text above now names `@wasichai/*` and this addendum is history; see
[ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md).]
