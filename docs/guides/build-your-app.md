# Build your app

An app is your own Spring Boot main class plus one or more wasichai starters, and your own React entry plus one or
more `@wasichai/*` packages. Start minimal — the core starter and a plain PostgreSQL database — and add modules one
line at a time as you need them.

## Before you start

- Java 25 (the backend's Gradle toolchain), Node >=26, PostgreSQL 18 (add PostGIS only if you install `gis`).
- A GitHub token with `read:packages`, and registry setup for Gradle and npm: see
  [../development/releasing.md](../development/releasing.md#consuming-a-published-library) ("Consuming a published
  library").

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
        url = uri("https://maven.pkg.github.com/wasichai/wasichai")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation(platform("wasichai:wasichai-bom:0.1.0"))
    implementation("wasichai:wasichai-spring-boot-starter")
}

kotlin { jvmToolchain(25) }
```

```kotlin
// src/main/kotlin/com/example/myapp/MyApp.kt
package com.example.myapp

import wasichai.core.autoconfigure.WasichaiApplication
import org.springframework.boot.runApplication

@WasichaiApplication
class MyApp

fun main(args: Array<String>) {
    runApplication<MyApp>(*args)
}
```

```yaml
# src/main/resources/application.yml
wasichai:
  security:
    jwt:
      secret: ${WASICHAI_JWT_SECRET}
  seed:
    dev: true   # admin@wasichai.local / admin, development only
server:
  port: 8090
```

Without any of that yaml, the starter still boots: `WasichaiEnvironmentPostProcessor` fills in
`WASICHAI_DB_HOST` (`localhost`), `WASICHAI_DB_PORT` (`5432`), `WASICHAI_DB_NAME`/`WASICHAI_DB_USERNAME`/`WASICHAI_DB_PASSWORD`
(`wasichai`/`wasichai`/`wasichai`) and the R2DBC URL built from them, at the lowest precedence — set the environment
variables, or `wasichai.database.*` in your own yaml, to point at a different server. It never fills in
`wasichai.security.jwt.secret`: a library must not ship a secret that works, so an app with no secret configured
fails to start, on purpose.

One rule that only matters once, and matters a lot: **your app must not live in package `wasichai` or below it**
(ADR-024). `@WasichaiApplication` component-scans from your application class's package downward; if that package is
`wasichai` or a sub-package, the scan also reaches the library's own controllers and registers them a second time.

This gets you `/api/objects`, `/api/objects/{object}/records`, identity, audit and admin, already routed — see
[../api/rest.md](../api/rest.md) for the full surface.

## A minimal app: frontend

`"*"` is a repository-internal convention (ADR-029): inside the wasichai-ui workspace, Yarn links the packages
regardless of the range. A consumer outside the repository pins the version instead, matching the `wasichai-bom`
version used on the backend:

```json
{
  "dependencies": {
    "@wasichai/core": "0.1.0",
    "@wasichai/ui": "0.1.0",
    "@tanstack/react-query": "^5.103.1",
    "i18next": "^26.4.2",
    "react": "^19.3.0",
    "react-dom": "^19.3.0",
    "react-i18next": "^17.0.14",
    "react-router": "^8.4.0"
  }
}
```

```tsx
// src/main.tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { WasichaiApp } from '@wasichai/core'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <WasichaiApp config={{ apiBaseUrl: '/api', appName: 'My App' }} modules={[]} />
  </StrictMode>
)
```

```css
/* src/index.css */
@import 'tailwindcss';
@import '@wasichai/ui/theme.css';
@source '../node_modules/@wasichai';
```

### Themes

`theme.css` ships `light` and `dark`; `<html>` carries `data-theme="<id>"`, and every Tailwind class from
`@wasichai/ui` (`bg-surface`, `text-ink`, …) resolves against whichever theme is active — no component to rewrite.
`config.themes?: ThemeDefinition[]` (`{ id, label, colorScheme: 'light' | 'dark' }`) adds your own themes to the two
built-ins; `label` is an i18n key. An app adds a theme with one CSS block that sets every token (all 28, including
the ten extension tokens such as `danger-soft`, `link` and `table-stripe`; `--font-sans` and the radii are yours to
set too), e.g.:

```css
[data-theme='high-contrast'] { --surface: oklch(...); --ink: oklch(...); /* every token in theme.css */ }
```

and one entry in `config.themes`. Beyond the tokens, the primitives carry `data-slot` hooks (`button` with
`data-variant` and `data-size`, `input`, `select-trigger`, `table-cell`, `tabs-trigger`, …) that your theme's CSS
can style while light and dark leave them alone
([ADR-035](../adr/0035-theme-extension-tokens-slots-and-optional-sheets.md)). One ready-made theme ships with the
library, optional and never built in: to offer it, import `@wasichai/ui/themes/portal-tributario.css` right after
`@wasichai/ui/theme.css` in `src/index.css` and add `PORTAL_TRIBUTARIO_THEME` (from `@wasichai/core`) to
`config.themes`. `useTheme()` (exported from `@wasichai/core`) returns
`{ preference, theme, colorScheme, themes, setPreference }`: `preference` is what the user picked (including
`system`), `theme` is what is actually applied. The user footer in `AppShell` already offers System plus your
configured themes; you rarely call `setPreference` yourself.

To avoid a flash of the wrong theme before the bundle loads, add this to `index.html`, before the module script that
boots the app (`wasichai` below is your `storagePrefix`), and list in `schemes` every theme id your app offers, with
its color scheme:

```html
<script>
  // before the bundle: apply the stored theme so a dark user never sees a light flash. `wasichai` = storagePrefix.
  // every theme the app offers, id -> color scheme: light, dark and each config.themes entry.
  // system and an unknown id (an old build, another app's theme) follow the os, as core's resolveTheme does
  try {
    const schemes = { light: 'light', dark: 'dark', 'portal-tributario': 'light' }
    const stored = localStorage.getItem('wasichai.theme')
    const dark = matchMedia('(prefers-color-scheme: dark)').matches
    const theme = stored && Object.hasOwn(schemes, stored) ? stored : dark ? 'dark' : 'light'
    document.documentElement.dataset.theme = theme
    document.documentElement.style.colorScheme = schemes[theme]
  } catch {}
</script>
```

Without it the app only flashes the light theme for a moment on load; nothing else breaks.

Point your dev server's proxy at the backend: Vite, `server.proxy['/api'] = 'http://localhost:8090'` (or whatever
`server.port` you set above). `WasichaiApp` needs no extra providers around it — router, query client and i18n are
built inside it from `config` and `modules`.

## Add modules

Core is always installed. Every other module is one backend starter plus one frontend package; an app adds a
module by listing both.

Registration always looks like `<WasichaiApp modules={[<name>Module()]} />` (`gis` also takes `{ workerUrl }`, see
below).

| Module | Backend starter | Frontend package | Extra requirements | Doc |
|---|---|---|---|---|
| views | `wasichai-spring-boot-starter-views` | `@wasichai/views` | None | [views.md](../modules/views.md) |
| forms | `wasichai-spring-boot-starter-forms` | `@wasichai/forms` | None | [forms.md](../modules/forms.md) |
| pages | `wasichai-spring-boot-starter-pages` | `@wasichai/pages` | Backend starter pulls in `wasichai-forms` | [pages.md](../modules/pages.md) |
| workflow | `wasichai-spring-boot-starter-workflow` | `@wasichai/workflow` | None | [workflow.md](../modules/workflow.md) |
| automation | `wasichai-spring-boot-starter-automation` | `@wasichai/automation` | Calls `DocumentIssuer` | [automation.md](../modules/automation.md) |
| documents | `wasichai-spring-boot-starter-documents` | `@wasichai/documents` | Implements `DocumentIssuer` | [documents.md](../modules/documents.md) |
| gis | `wasichai-spring-boot-starter-gis` | `@wasichai/gis` | PostGIS database; web needs `maplibre-gl`, a `workerUrl` | [gis.md](../modules/gis.md) |
| agent | `wasichai-spring-boot-starter-agent` | `@wasichai/agent` | Needs `ANTHROPIC_API_KEY` (or another provider) | [agent.md](../modules/agent.md) |

`documents` and `automation` connect through `DocumentIssuer`: `automation`'s `GENERATE_DOCUMENT` action calls it,
`documents` implements it, and installing only one of the two still works — the call is optional. `gis` needs a
PostGIS-enabled PostgreSQL; `terra-draw` and its adapter come as regular dependencies of
`@wasichai/gis`, but `maplibre-gl` is a peer you add yourself, and `gisModule` takes a `workerUrl` pointing at
MapLibre's worker script — see [gis.md](../modules/gis.md) for the exact recipe. `pages`' backend starter brings in
`wasichai-forms`; the frontend needs no `@wasichai/forms` package, because `@wasichai/core` draws the `FORM` page
component itself. `agent` falls back to
`ANTHROPIC_API_KEY` for `wasichai.agent.api-key`; another Embabel provider starter works too, see
[agent.md](../modules/agent.md).

## A full app

```kotlin
dependencies {
    implementation(platform("wasichai:wasichai-bom:0.1.0"))
    implementation("wasichai:wasichai-spring-boot-starter")
    implementation("wasichai:wasichai-spring-boot-starter-views")
    implementation("wasichai:wasichai-spring-boot-starter-forms")
    implementation("wasichai:wasichai-spring-boot-starter-pages")
    implementation("wasichai:wasichai-spring-boot-starter-workflow")
    implementation("wasichai:wasichai-spring-boot-starter-automation")
    implementation("wasichai:wasichai-spring-boot-starter-documents")
    implementation("wasichai:wasichai-spring-boot-starter-gis")
    implementation("wasichai:wasichai-spring-boot-starter-agent")
}
```

```tsx
import { WasichaiApp } from '@wasichai/core'
import { agentModule } from '@wasichai/agent'
import { automationModule } from '@wasichai/automation'
import { documentsModule } from '@wasichai/documents'
import { formsModule } from '@wasichai/forms'
import { gisModule } from '@wasichai/gis'
import { pagesModule } from '@wasichai/pages'
import { viewsModule } from '@wasichai/views'
import { workflowModule } from '@wasichai/workflow'

<WasichaiApp
  config={{ apiBaseUrl: '/api', appName: 'My App' }}
  modules={[
    gisModule({ workerUrl }),
    workflowModule(),
    pagesModule(),
    viewsModule(),
    formsModule(),
    documentsModule(),
    automationModule(),
    agentModule()
  ]}
/>
```

That order, and the rest of the setup (worker/CSS imports, `@source`), is `@wasichai/core`'s README "Full app"
section — follow it exactly; this guide only lists the pieces. [full-sample](https://github.com/wasichai/full-sample)
shows the whole setup in a running app.

## Configure

The settings apps change most, each under `wasichai.*` (environment `WASICHAI_*`):

| Setting | Property | Module doc |
|---|---|---|
| Database coordinates | `wasichai.database.host`/`port`/`name`/`username`/`password` | [core.md](../modules/core.md) |
| JWT secret | `wasichai.security.jwt.secret` | [core.md](../modules/core.md) |
| Metadata/data schemas | `wasichai.database.metadata-schema`, `wasichai.database.data-schema` | [core.md](../modules/core.md) |
| CORS origins | `wasichai.web.cors-allowed-origin-patterns` | [core.md](../modules/core.md) |
| Dev seed data | `wasichai.seed.dev` | [core.md](../modules/core.md) |
| Module enabled flags | `wasichai.<module>.enabled` (default `true`) | each module's doc |
| GeoServer URL | `wasichai.gis.geoserver.url` | [gis.md](../modules/gis.md) |
| Model provider key | `wasichai.agent.api-key` (defaults to `ANTHROPIC_API_KEY`) | [agent.md](../modules/agent.md) |

## Override a bean

Every wasichai bean an app may replace is `@ConditionalOnMissingBean`, so an app overrides one by declaring its own
bean of that type. The exception is the two beans that run schema migrations at start-up, `wasichaiCoreMigration` and
`wasichaiCoreSeedMigration`: they are not conditional, because every module's migration must be in the list.

```kotlin
@Configuration
class SecurityBeans {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(12)
}
```

Spring picks up your `@Bean` before the library's auto-configuration runs its own `@ConditionalOnMissingBean`
method, so the library's `PasswordEncoder` never gets created.

## Write several records atomically

`RecordService` opens no transaction and joins the one you have, the audit row and the listeners' writes included
([ADR-038](../adr/0038-record-service-joins-the-callers-transaction.md)). Wrap the calls in Spring's
`TransactionalOperator`; an exception out of the block, from a listener too, undoes all of them:

```kotlin
transactions.executeAndAwait {
    val receipt = records.create("receipt", RecordRequest(mapOf("total" to total)))
    orders.forEach { records.update("order", it, RecordRequest(mapOf("status" to "PAID"))) }
    records.create("outbox_event", RecordRequest(mapOf("receipt" to receipt.id)))
}
```

`CurrentUser.require()` and the permission checks work inside the block.

One exception to "opens no transaction": deleting a record that an append-only object can point at locks the record,
checks and deletes in a short transaction of its own when you have none
([ADR-044](../adr/0044-append-only-delete-check-under-a-row-lock.md)).

## Background work

A job with no user behind it, an outbox publisher say, calls `RecordService` as the platform and takes a
`ClusterLock` so that one replica does the work, not all of them
([ADR-039](../adr/0039-background-work-runs-as-the-platform-with-a-cluster-lock.md)):

```kotlin
@Component
class OutboxPublisher(
    private val records: RecordService,
    private val clusterLock: ClusterLock
) {
    @Scheduled(fixedDelay = 5_000)
    suspend fun tick() {
        // whoever gets the lock publishes; the other replicas skip this tick
        clusterLock.tryLock("caja.outbox")?.use {
            records.asPlatform(organizationId) {
                records.list("outbox_event", pending).content.forEach { publish(it) }
            }
        }
    }
}
```

- Inside `asPlatform` every `RecordService` call acts in that organization with no permission check, like `ADMIN`,
  and writes `created_by`, `updated_by` and the audit `user_id` as null, like an automation. Other services still need
  a user.
- `asPlatform` throws inside a request, with a token or without one: hand the work to a job instead.
- `tryLock(key)` returns a lease or null, without waiting; `use { }` releases it. `withXactLock(key) { }` waits for
  the lock and holds it until the transaction ends, joining yours if there is one.
- Both compose with `TransactionalOperator`, either way round.

## Records nobody rewrites, and rules the record API cannot skip

Receipts and an outbox are written once. Mark the object `appendOnly` and nobody, `ADMIN` and the platform included,
changes or deletes its records, or deletes a record one of them points at: `409`. Mark it `apiOnly` and the generic
record API refuses writes on it (`403`), so a user with `CREATE` cannot skip your own endpoint's numbering and locks;
your code still calls `RecordService` ([ADR-040](../adr/0040-append-only-objects-and-a-pre-write-guard.md)):

```json
POST /api/objects
{ "name": "recibo", "label": "Recibo", "appendOnly": true, "apiOnly": true, "fields": [ … ] }
```

A rule of your own goes in a `RecordWriteGuard` bean. It runs before every record write, on every route, and a throw
stops the write with nothing stored or audited:

```kotlin
@Component
class ReceiptLines : RecordWriteGuard {
    override suspend fun beforeWrite(change: RecordWrite) {
        if (change.objectName != "recibo_linea" || change.kind != RecordChangeKind.CREATED) return
        val amount = (change.attributes?.get("monto") as? Number)?.toDouble() ?: return
        if (amount < 0) throw ValidationException("A line cannot be negative", "monto", "must be 0 or more")
    }
}
```

## Every change says why

When each change must carry an observation, mark the object `requiresReason`: a write without a reason is a `400` on
`reason`, nothing stored. The UI sends the `X-Change-Reason` header; your code passes the reason to `RecordService`,
and a job running as the platform must pass one too. It lands on the write's audit entry
([ADR-041](../adr/0041-a-change-reason-on-record-writes.md)):

```kotlin
records.update("recibo", id, RecordRequest(mapOf("monto" to 120)), reason = "corrección por error de digitación")
records.asPlatform(organizationId) { records.create("cierre", RecordRequest(values), reason = "cierre nocturno") }
```

## Write your own module

Backend: a library with an `@AutoConfiguration` class registered in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, beans implementing core's SPIs
(for example a `FieldTypeHandler`), its own `ModuleMigration` for schema changes, and a `wasichai.<name>.enabled`
switch. Frontend: a `WasichaiModule` factory (routes, nav, field renderers, page components — see `@wasichai/core`'s
README, "Writing a module"). [ADR-025](../adr/0025-extension-spis.md) documents the SPIs and
[ADR-028](../adr/0028-frontend-module-registry.md) the frontend registry; `views` is the smallest shipped module
and a good model to copy from.

## Test it

See [../modules/testing.md](../modules/testing.md).

## Examples

Four sample apps, each a repository with its server and its web, built the way this guide describes:

- [simple-sample](https://github.com/wasichai/simple-sample): core only, on plain PostgreSQL.
- [documents-sample](https://github.com/wasichai/documents-sample): core plus documents and automation.
- [gis-sample](https://github.com/wasichai/gis-sample): core plus gis, with the Perené cadastre model.
- [full-sample](https://github.com/wasichai/full-sample): every module, the app of "A full app" above in working form.
