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
    implementation(platform("wasichai:wasichai-bom:1.0.0"))
    implementation("wasichai:wasichai-spring-boot-starter")          // core
    implementation("wasichai:wasichai-spring-boot-starter-documents") // opt-in module
}
```

Frontend: `<WasichaiApp config={{ apiBaseUrl: '/api' }} modules={[documentsModule()]} />` from `@wasichai/core`, see
[wasichai-ui](https://github.com/wasichai/wasichai-ui). Step by step, minimal to full:
[docs/guides/build-your-app.md](docs/guides/build-your-app.md). Modules: views, forms, pages, workflow, automation,
documents, gis, agent, notifications — one page each in [docs/modules](docs/modules/README.md).

## Layout

```
build-logic/   Gradle convention plugins (wasichai.kotlin-library, .spring-module, .publishing, .integration-test)
wasichai-*/    libraries: wasichai-core, wasichai-<module>, wasichai-bom, wasichai-test, wasichai-integration-tests
starters/      wasichai-spring-boot-starter and one wasichai-spring-boot-starter-<module> per module
docs/          architecture, modules, guides, domain, api, gis, security, development, adr, HISTORY.md
```

## Commands

```bash
./gradlew build                                   # ktlint + unit + architecture tests
./gradlew integrationTest                         # API tests against Testcontainers (or WASICHAI_TEST_DB_*)
```

An app needs PostgreSQL 18 (with PostGIS 3.6 for `wasichai-gis`; GeoServer is optional); the integration tests
start their own with Testcontainers. Runnable sample apps, each a repository with its server and its web:
[simple-sample](https://github.com/wasichai/simple-sample), [documents-sample](https://github.com/wasichai/documents-sample),
[gis-sample](https://github.com/wasichai/gis-sample), [full-sample](https://github.com/wasichai/full-sample).
Local databases and GeoServer, if you want them: [wasichai-infrastructure](https://github.com/wasichai/wasichai-infrastructure).

Commits follow [Conventional Commits](https://www.conventionalcommits.org). Releases are cut by release-please and
published to GitHub Packages: [docs/development/releasing.md](docs/development/releasing.md).

## Decisions

Every architectural decision is an ADR in [docs/adr](docs/adr/README.md), frontend ones included; what shipped and
when is in [docs/HISTORY.md](docs/HISTORY.md).
