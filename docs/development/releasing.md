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

A release made with the workflow's `GITHUB_TOKEN` triggers no other workflow, hence the PAT.

Settings → Secrets and variables → Actions → New repository secret, or one organization secret visible to both
repositories. The PAT's resource owner is the `wasichai` organization, and the organization rejects a fine-grained
PAT that lives more than 366 days: renew it before it expires, or release-please fails. `release-please.yml` falls
back to `GITHUB_TOKEN` when `RELEASE_PLEASE_TOKEN` is absent, which only works if Settings → Actions → General allows
GitHub Actions to create pull requests, and even then nothing is published. Publishing itself uses the workflow's
`GITHUB_TOKEN` (`packages: write`).

**After the first publish (once per package):** Organization → Packages → each `wasichai-*` package → Package
settings → "Manage Actions access" → add `simple-sample`, `documents-sample`, `gis-sample` and `full-sample` with
the Read role. The alternative is a `WASICHAI_PACKAGES_TOKEN` secret, a classic PAT with `read:packages`, in each
sample repository. The organization must allow members' workflows to publish packages (Organization settings →
Packages).

## Versions

One version for every Maven artifact of this repository (`gradle.properties`); `wasichai-bom` aligns them. The npm
packages have their own version line.

## Guards

`.github/scripts/check-maven-publications.sh <dir>` compares what `./gradlew publishToMavenLocal
-Dmaven.repo.local=<dir>` produced with the twenty-two expected artifacts. CI runs it on every pull request
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
