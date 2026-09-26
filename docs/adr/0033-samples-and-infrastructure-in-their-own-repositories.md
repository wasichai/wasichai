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
