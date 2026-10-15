# ADR-068: An app on wasichai can be a GraalVM native image; the assistant cannot yet

**Status**: accepted · 2026-10-15

## Context

An app wants to ship as a GraalVM native image: faster start, less memory. Wasichai is libraries (ADR-024): the app
builds the image with Spring Boot's AOT and GraalVM's `org.graalvm.buildtools.native`, not this repository. What the
libraries owe it is a starter that survives the closed world. Spring Boot 4.1 builds images on GraalVM 25 or later (the
25 line, on JDK 25: the toolchain wasichai already uses).

Most of wasichai is already fine there: no JPA, no component scanning, no `Class.forName`, no `ServiceLoader`, no JDK
proxies. Controllers' bodies and answers, `@ConfigurationProperties` and `@Transactional` proxies get their hints from
Spring. The driver, Netty and the AWS SDK bring their own metadata. Four things do not:

- **JSON kept in the database.** Every module reads its definition (form, view, page, workflow, automation, document
  template and snapshot, notification rule, link) from a `jsonb` column with `readValue(json, X::class.java)` in its
  repository. Spring never sees those types: in the image Jackson finds no constructor.
- **Migrations.** `WasichaiMigrations` runs Flyway itself (ADR-008), on `classpath:db/wasichai/<module>`. An image has
  no jar to scan, and Spring Boot's own native resource provider only serves its own Flyway auto-configuration.
- **Values written as `Any`.** A field type's SPI writes extra properties into a `Map<String, Any?>` (gis puts a
  `GeometryResponse` there), and Kotlin answers `emptyList()` with its own `EmptyList`. Jackson's Kotlin module asks
  kotlin-reflect about each, and in an image kotlin-reflect knows only registered classes.
- **Decisions made at startup.** AOT evaluates every condition when the image is built, not when it starts.

## Decision

**The modules register what Spring cannot see.** Each module's auto-configuration names the types it reads from
`jsonb`, or writes into a response typed `Any`, in `@RegisterReflectionForBinding`; Spring walks their properties,
nested types and Kotlin constructors. A module turned off when the image is built registers nothing, and needs nothing.

**Core registers Kotlin's own collections.** `KotlinCollectionsRuntimeHints` registers `EmptyList`, `EmptySet`,
`EmptyMap` and the `buildList` family, which any answer may hold.

**Core makes migrations work in an image.** `WasichaiMigrationsRuntimeHints` adds every file under `db/wasichai/` to
the image: every module keeps its migrations there (`ModuleMigration`), so one pattern covers modules core does not
know. In an image (`NativeDetector.inNativeImage()`), `WasichaiMigrations` hands Flyway a `ClasspathMigrations`
resource provider that lists a location through Spring's resource resolver, which can list an image's resources. On
the JVM, Flyway scans as before.

**Startup switches become build switches in an image.** `wasichai.<module>.enabled`, `wasichai.files.store`,
`wasichai.notifications.email.enabled`, `wasichai.notifications.listen`, `wasichai.seed.dev`,
`wasichai.database.migrate` and `wasichai.metadata.reconcile-indexes` choose beans: in an image they hold the value the
build saw. Changing one means building the image again. Every other property (database, secrets, ttls, limits) still
reads at startup.

**The assistant is not supported in an image.** `EmbabelGate` decides at startup, from the API key, whether Embabel's
auto-configuration exists; in an image that decision would be the build's, and Embabel and Spring AI are not verified
there. An app with `wasichai-agent` builds for the JVM; one without it builds either way.

**Where an image is proven.** Not here (ADR-033: no app in this repository). Unit tests pin the hints;
[full-sample](https://github.com/wasichai/full-sample) builds the image against this repository and runs a smoke e2e
through every module against it and against the jar. A hint found missing there is fixed here, in the module that owns
the type. To find them, run the jar under GraalVM's `native-image-agent` through the same smoke and compare what it
saw with what the image registers.

## Consequences

- An app on core and every module but the assistant can add `org.graalvm.buildtools.native` and run `nativeCompile`.
  [build-your-app](../guides/build-your-app.md#native-image) says how.
- A module that reads a new type from `jsonb`, or writes one into a response as `Any`, adds it to its
  `@RegisterReflectionForBinding`; a new module keeps its migrations under `db/wasichai/<name>`, as it already must.
- No REST change, no migration, no change on the JVM.
