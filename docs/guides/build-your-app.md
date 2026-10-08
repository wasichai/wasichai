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
| notifications | `wasichai-spring-boot-starter-notifications` | `@wasichai/notifications` (planned) | None | [notifications.md](../modules/notifications.md) |
| files | `wasichai-spring-boot-starter-files` | (planned) | S3 store needs `software.amazon.awssdk:s3` | [files.md](../modules/files.md) |
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
    implementation("wasichai:wasichai-spring-boot-starter-notifications")
    implementation("wasichai:wasichai-spring-boot-starter-files")
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
| Audit purge role | `wasichai.audit.purge-role` (default none: no purge) | [core.md](../modules/core.md) |
| Who creates and deletes tenants | `wasichai.organizations.separate-provisioning` | [core.md](../modules/core.md) |
| Sign-in hardening | `wasichai.security.jwt.revocation`, `…login.*`, `…password.*` | [authentication.md](../security/authentication.md) |
| Module enabled flags | `wasichai.<module>.enabled` (default `true`) | each module's doc |
| GeoServer URL | `wasichai.gis.geoserver.url` | [gis.md](../modules/gis.md) |
| File store, size cap, cleanup | `wasichai.files.store`, `…local.path`, `…s3.*`, `…max-bytes`, `…cleanup.*` | [files.md](../modules/files.md) |
| Model provider key | `wasichai.agent.api-key` (defaults to `ANTHROPIC_API_KEY`) | [agent.md](../modules/agent.md) |
| Notification loop, stream, date rule zone | `wasichai.notifications.tick`, `stream-refresh`, `zone` | [notifications.md](../modules/notifications.md) |

### Before production: sign-in hardening

All three are off or neutral by default, for 0.x compatibility; an app that holds personal data turns them on
([ADR-059](../adr/0059-token-revocation-login-limits-and-password-policy.md)):

```yaml
wasichai:
  security:
    jwt:
      revocation: true        # disabling, new roles or password, deleting and logout kill live tokens
    login:
      enabled: true           # 429 after 5 failures per email and client, 20 per email, for 15 minutes
    password:
      min-length: 12
      require-digit: true
      not-equal-email: true
```

The UI calls `POST /api/auth/logout` on sign-out. Behind a reverse proxy, set `server.forward-headers-strategy:
native` (or `framework`) so the attempt limit sees the client's address and not the proxy's. On more than one node,
declare a `LoginAttemptStore` bean shared by all of them, or each counts on its own; a revocation reaches the other
nodes within `wasichai.security.jwt.revocation-cache`.

## The AI assistant and what it sends out

The assistant answers as the person asking and never sees more than they may (ADR-014), but what it reads goes to a
third-party model provider. Four beans let your app decide what leaves and keep a record of it
([agent.md](../modules/agent.md#extension-points),
[ADR-056](../adr/0056-what-reaches-the-model-is-the-apps-to-shape.md)):

- `AgentAccessPolicy` — switch the assistant on only for the organizations (or roles) that opted in. Denied callers
  see it off in `GET /api/agent/status` and get `403` from `POST /api/agent/ask`; nothing is sent.
- `AgentResultFilter` — replace personal data in every tool result with a pseudonym before the model sees it (the
  steps shown in the UI get the same text). If it throws, the question fails and nothing more is sent.
- `AgentAnswerFilter` — put the real values back in the final answer.
- `AgentRunListener` — record every question with its answer or error and its token usage (`AgentUsage`).

Declare none and the assistant behaves as before.

## Operator and customer tenants

By default every tenant's administrator can create tenants (`POST /api/organizations`) and delete their own
(`DELETE /api/organizations/current`). That is fine when one organization runs the deployment. When you run it for
several customers, keep those two routes with your own people: turn on
`wasichai.organizations.separate-provisioning`, and only roles granted `MANAGE_TENANTS` may use them; a customer's
`ADMIN` keeps its users, roles, service accounts, units and the tenant's name
([ADR-055](../adr/0055-tenant-provisioning-apart-from-tenant-administration.md),
[authentication.md](../security/authentication.md#tenant-administration-and-tenant-lifecycle)).

The **operator tenant** is the one your people sign in to; **customer tenants** are the ones they create. Nobody gets
`MANAGE_TENANTS` from the API without already holding it, so the first grant is written into the database, once, the
same way the first tenant is (the dev seed's `demo`, or your own SQL):

1. In the operator tenant, create a role for the operator's people, say `OPERATOR` (`POST /api/roles`), and give it
   to them. Granting the action to the operator tenant's `ADMIN` role instead works too.
2. Grant it `MANAGE_TENANTS`, with no object, in the metadata schema (`wasichai.database.metadata-schema`, `wasichai`
   by default). The slug and the role name are yours:

   ```sql
   INSERT INTO wasichai.permissions (role_id, object_id, action)
   SELECT r.id, NULL, 'MANAGE_TENANTS'
   FROM wasichai.roles r
   JOIN wasichai.organizations o ON o.id = r.organization_id
   WHERE o.slug = 'operator' AND r.name = 'OPERATOR';
   ```

   It inserts one row; zero means the slug or the role name is wrong.
3. Set the switch and restart:

   ```yaml
   wasichai:
     organizations:
       separate-provisioning: true
   ```

4. Sign in as an operator and create customer tenants with `POST /api/organizations`. Each gets an `ADMIN` role with
   every permission except `MANAGE_TENANTS`, so it can neither create tenants nor delete itself.

From then on an operator whose roles also hold `MANAGE_ORGANIZATION` hands `MANAGE_TENANTS` to more roles of the
operator tenant with `PUT /api/roles/{name}/permissions` (`{ "objectName": null, "action": "MANAGE_TENANTS" }`); a
customer administrator who tries gets `403`. Do steps 1 and 2 before step 3, or nobody can create a tenant once the
switch is on. `GET /api/auth/me/permissions` lists `MANAGE_TENANTS` in `capabilities` exactly when the two routes let
the caller in, so a client can show them on that alone.

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
- Its audit rows say `source: platform`. To name the job instead, pass a label matching `^[A-Za-z0-9._:-]{1,64}$`:
  `records.asPlatform(organizationId, source = "job:retention") { }`. Then `GET /api/audit?source=job:retention`
  lists what it changed ([ADR-050](../adr/0050-correlation-id-and-change-source-on-audit-rows.md)).
- `tryLock(key)` returns a lease or null, without waiting; `use { }` releases it. `withXactLock(key) { }` waits for
  the lock and holds it until the transaction ends, joining yours if there is one.
- Both compose with `TransactionalOperator`, either way round.

A job that runs for every tenant gets them from `TenantDirectory`, or lets `RecordService.forEachOrganization` loop
for it ([ADR-057](../adr/0057-background-work-finds-the-tenants-through-a-tenant-directory.md)):

```kotlin
@Component
class Retention(
    private val records: RecordService,
    private val clusterLock: ClusterLock
) {
    @Scheduled(cron = "0 30 2 * * *")
    suspend fun nightly() {
        clusterLock.tryLock("sgspe.retention")?.use {
            // only the tenants that have the app's model; each block runs as the platform of that tenant
            records.forEachOrganization("expediente", source = "job:retention") { organizationId ->
                records.list("expediente", expired).content.forEach { records.delete("expediente", UUID.fromString(it.id)) }
            }
        }
    }
}
```

- `TenantDirectory.organizations()` lists every organization, `organizationsWithObject(name)` only those that define
  that object (enabled or not). Both answer `TenantRef(id, slug)`, ordered by id: a snapshot, not a live view.
- `forEachOrganization(objectName = null, source = "platform") { }` walks that list in order and runs the block inside
  `asPlatform(organizationId, source)`. One tenant's exception is logged and the next tenant still runs; keep what
  must not be lost inside the block.
- Both throw `IllegalStateException` inside a request, with a token or without one, so a tenant can never list the
  others. Keep `TenantDirectory` out of your controllers, as wasichai's own build does for every controller of core
  and the modules.
- `CustomObjectRepository.findAllOrganizations()` is deprecated: repositories are internal (ADR-024).

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

## Who reads which records

`READ` on an object opens every record of it, and `own_records_only` narrows that to the caller's own. When several
projects, territories or regions share a tenant, declare a `RecordReadScope` bean: it tells core which records of an
object a person or a service account reads, and core applies it to every read of that object, next to the owner
filter ([ADR-048](../adr/0048-a-read-scope-narrows-what-a-caller-reads.md)):

```kotlin
// a person reads the records of the projects they are assigned to; an object without project_id is not scoped
@Component
class ProjectScope(
    private val assignments: Assignments
) : RecordReadScope {
    override suspend fun criterion(
        caller: AuthenticatedUser,
        definition: ObjectDefinition
    ): RecordCriterion? {
        val field = definition.fields.firstOrNull { it.name == "project_id" } ?: return null
        // asked once per object a read touches: cache the caller's assignments per request
        val projects = assignments.projectsOf(caller.userId)
        if (projects.isEmpty()) return RecordCriterion { _, _ -> "false" }
        val column = SqlIdentifier.quote(field.columnName)
        return RecordCriterion { _, bind -> "$column IN (${bind(projects)})" }
    }
}
```

- Return `null` for no restriction, a criterion to narrow, `false` to read nothing (every total is then `0`).
- Every value goes through `bind`. Your condition is put in parentheses behind the tenant and owner filters, so an
  `OR` in it cannot widen them. It is handed the object's whole definition, so it may name a field the caller cannot
  read.
- It applies to lists and their counts, reads by id, related records on both sides, history and `/api/audit`, a
  `RELATION` value on a write, the lookups before an update, delete, link or workflow transition, and so to GIS
  features and the assistant's tools. A record out of scope answers as a missing one: `404`, or `400` on a `RELATION`
  field. `ADMIN`, the platform and automations are never asked; a service account is (`caller.serviceAccount` names it).
- Several beans all apply, in `@Order`. The scope hides records, it does not judge values: a caller may still create
  a record in another project. Keep creates in scope with a `RecordWriteGuard`.
- GeoServer layers read the table themselves: do not publish a scoped object as a layer.

## Every change says why

When each change must carry an observation, mark the object `requiresReason`: a write without a reason is a `400` on
`reason`, nothing stored. The UI sends the `X-Change-Reason` header; your code passes the reason to `RecordService`,
and a job running as the platform must pass one too. It lands on the write's audit entry
([ADR-041](../adr/0041-a-change-reason-on-record-writes.md)):

```kotlin
records.update("recibo", id, RecordRequest(mapOf("monto" to 120)), reason = "corrección por error de digitación")
records.asPlatform(organizationId) { records.create("cierre", RecordRequest(values), reason = "cierre nocturno") }
```

## Two database roles, and a trail nobody rewrites

`audit_log` refuses `UPDATE`, `DELETE` and `TRUNCATE` in the database itself, for every role
([ADR-054](../adr/0054-audit-log-is-append-only-in-the-database.md)). Inserts and reads are unaffected; deleting a
document or a tenant still works, and a deleted tenant's entries stay (there is no foreign key to `organizations`).
The owner of the table can still drop or disable the triggers, and by default wasichai connects with the credential
Flyway migrates with, which owns everything. While that is so, wasichai logs a `WARN` at startup. In production, split
them: a migration role owns the schemas and runs the migrations, a runtime role reads and writes.

Once, as a superuser (the names are examples):

```sql
CREATE ROLE wasichai_owner LOGIN PASSWORD '…';
CREATE ROLE wasichai_app LOGIN PASSWORD '…';
CREATE DATABASE wasichai OWNER wasichai_owner;
```

In that database, as `wasichai_owner`, before the first start (default schema names):

```sql
CREATE SCHEMA wasichai;
CREATE SCHEMA app_data;
GRANT USAGE ON SCHEMA wasichai TO wasichai_app;
-- the app builds one table per object at runtime (ADR-004) and owns those
GRANT USAGE, CREATE ON SCHEMA app_data TO wasichai_app;
-- every table the migrations create, now and in later versions
ALTER DEFAULT PRIVILEGES IN SCHEMA wasichai GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO wasichai_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA wasichai GRANT USAGE, SELECT ON SEQUENCES TO wasichai_app;
```

After the first migration, take back what the trail does not need. The triggers refuse it anyway; this is a second
layer:

```sql
REVOKE UPDATE, DELETE, TRUNCATE ON wasichai.audit_log FROM wasichai_app;
```

The app connects as `wasichai_app` (`wasichai.database.username`, `password`). Run the migrations as `wasichai_owner`
in a deploy step of your own and set `wasichai.database.migrate=false` on the service, so the service never holds the
owner's password. Or let the service migrate at start with its own Flyway credentials, by replacing the
`wasichaiMigrations` bean (keep that name: modules depend on it):

```kotlin
@Configuration
class Migrations {
    // flyway as the owner; the r2dbc pool keeps wasichai.database.username
    @Bean
    fun wasichaiMigrations(
        database: WasichaiDatabaseProperties,
        schemas: WasichaiSchemas,
        migrations: ObjectProvider<ModuleMigration>,
        audit: WasichaiAuditProperties,
        @Value("\${app.migration.username}") username: String,
        @Value("\${app.migration.password}") password: String
    ) = WasichaiMigrations(database.copy(username = username, password = password), schemas, migrations.orderedStream().toList(), audit)
}
```

**Purging, when a retention rule says so.** Nobody can by default. Name a role in `wasichai.audit.purge-role`, in the
configuration the migrations run with: the migration writes it into the database, where the runtime role cannot change
it. Then, as `wasichai_owner`:

```sql
CREATE ROLE wasichai_purge LOGIN PASSWORD '…';
GRANT USAGE ON SCHEMA wasichai TO wasichai_purge;
GRANT SELECT, DELETE ON wasichai.audit_log TO wasichai_purge;
```

Purge logged in as that role, stating the intent in the transaction:

```sql
BEGIN;
SET LOCAL wasichai.audit.purge = 'on';
DELETE FROM wasichai.audit_log WHERE occurred_at < now() - interval '10 years';
COMMIT;
```

- Only a login as the purge role counts (`session_user`): `SET ROLE wasichai_purge` from another login is refused, so
  never name the runtime role.
- The flag lifts `DELETE` and `TRUNCATE` (with the `TRUNCATE` privilege), never `UPDATE`: entries are removed, not
  rewritten.
- A deleted tenant's entries are purged the same way, by `organization_id`.
- A new name takes effect on the next migration; an empty one takes the right away again.

## Two people edit the same record

A record answer carries its version as `ETag` (`"<updatedAt>"`). Send it back as `If-Match` on `PUT`, `PATCH` or
`DELETE` and the write lands only if nobody wrote the record in between; otherwise it is a `412` and nothing changed,
so the screen re-reads and asks again. `PATCH` writes only the attributes sent, so a form that knows some fields never
blanks the others. Your own commands get the same check, in one statement, inside your transaction
([ADR-051](../adr/0051-optimistic-locking-and-partial-update-of-records.md)):

```kotlin
val read = records.get("caso", id)
records.patch("caso", id, RecordRequest(mapOf("estado" to "CERRADO")), reason = null, expectedUpdatedAt = read.updatedAt)
records.update("caso", id, RecordRequest(values), reason = null, expectedUpdatedAt = read.updatedAt)
```

`PreconditionFailedException` (412) means stale. Without `If-Match`, or with `*`, a write is last-writer-wins as
before.

## Tell people what needs doing

Staff should not have to open the right screen to learn that a turno is still open or a licence expires on Friday.
Add the notifications module and tell them, by person, role or organizational unit, within a time window
([notifications.md](../modules/notifications.md)):

```kotlin
implementation("wasichai:wasichai-spring-boot-starter-notifications")
```

An administrator publishes notices by hand (`POST /api/notifications`); the rest comes from your app, in one of three
ways, depending on where the truth lives:

| The truth is… | Example | Use |
|---|---|---|
| an event in your code | a turno closed with a cash difference, a mass job finished | `Notifications.publish`, inside the business transaction |
| a state you compute | turnos of an earlier day still open, a reconciliation that does not balance | a `NotificationSource` |
| a date field of a record | a licence's `vigencia_hasta` | a date rule, applied with your model |
| a record reaching a workflow state | a trámite approved, a task assigned | an automation with a `NOTIFY` action (`STATE_ENTERED`) |

To reach people outside the app too, add `org.springframework.boot:spring-boot-starter-mail`, set `spring.mail.host`
and `wasichai.notifications.email.enabled=true` with `wasichai.notifications.email.from`: every new notification is
then also emailed to the people it reaches, sent in the background and retried, and each person picks the kinds they
want by email (`/api/auth/me/notification-preferences`). Another way out (a push service) is a `DeliveryChannel` bean
([notifications.md](../modules/notifications.md#email-and-delivery-channels)).

The frontend package, `@wasichai/notifications`, is planned
([plan](../superpowers/plans/2026-10-06-notifications-wasichai-ui.md)); until it ships, a client reads
`/api/auth/me/notifications` itself.

### Publish from code

Call the `Notifications` bean where the event happens. It joins your transaction: if the closing rolls back, so does
the notification, and the live signal goes out only with the commit:

```kotlin
transactions.executeAndAwait {
    records.update("turno", turnoId, RecordRequest(mapOf("estado" to "CERRADO")))
    if (diferencia.signum() != 0) {
        notifications.publish(
            user.organizationId,
            "caja.cierres",
            NotificationDraft(
                kind = NotificationKind.WARNING,
                title = "Turno cerrado con una diferencia de S/ $diferencia",
                audience = listOf(Audience.Role("SUPERVISOR_CAJA"), Audience.Email(cajero)),
                link = NotificationLink.Route("caja:arqueo", mapOf("turnoId" to turnoId.toString())),
                key = "cierre-$turnoId"
            )
        )
    }
}
```

- `source` (`"caja.cierres"`) names the producer: lower case, your app's prefix. `key` makes publishing again an update
  of the same notification; `notifications.resolve(organizationId, "caja.cierres", "cierre-$turnoId")` takes it away
  when the difference is explained, in that transaction too.
- It never breaks your business work over people: an unknown role, unit or email is dropped with a WARN, and a title
  over 200 characters is cut. A malformed draft (a bad key, route or URL) is an `IllegalArgumentException`: build keys
  from ids and ISO dates.

### Report computed states

A state your code computes, rather than an event, is a `NotificationSource`: a bean the module asks every `interval`,
once per cluster, for every organization, for everything that should be open now. What it stops answering is resolved,
so nobody resolves anything by hand. Count rather than list: one notification saying how many, linking to the list,
is read; fifty are ignored:

```kotlin
@Component
class TurnosAbiertos(
    private val records: RecordService
) : NotificationSource {
    override val key = "caja.turnos"
    override val interval: Duration = Duration.ofMinutes(5)

    override suspend fun currentNotifications(
        organizationId: UUID,
        now: Instant
    ): List<NotificationDraft> {
        val abiertos = records.list("turno", RecordQuery(page = PageRequest(0, 1), filters = mapOf("estado" to "ABIERTO")))
        val total = abiertos.totalElements ?: 0
        if (total == 0L) return emptyList()
        return listOf(
            NotificationDraft(
                kind = NotificationKind.ACTION,
                title = "$total turnos siguen abiertos",
                audience = listOf(Audience.Role("SUPERVISOR_CAJA")),
                link = NotificationLink.Route("caja:turnos"),
                key = "abiertos"
            )
        )
    }
}
```

It runs outside any transaction (it may call another system) and as the platform, like the jobs of
[Background work](#background-work). A new count updates the notification in place and keeps who read it. Give each
source its own key and never `publish` under it. In a test, set `wasichai.notifications.tick=0s` and call
`NotificationLoop.runSource("caja.turnos")`.

### Date rules from your model

A rule over a `DATE` or `DATETIME` field is metadata, applied over REST like the rest of your model. Keep the rules
in `model/notifications.json`, next to `model/roles.json`, and apply them with a script the same way: for each rule,
`GET /api/objects/{object}/notification-rules`, then `POST` one the object does not have, `PUT` one that differs, and
leave an equal one alone. Saving runs the rule at once.

```json
{
  "rules": [
    {
      "object": "licencia", "name": "licencia_por_vencer", "label": "Licencias por vencer", "enabled": true,
      "field": "vigencia_hasta",
      "stages": [{"fromDays": -15, "kind": "WARNING"}, {"fromDays": 0, "kind": "ACTION"}], "untilDays": 3,
      "conditions": [{"field": "estado", "op": "EQ", "value": "VIGENTE"}],
      "audience": [{"type": "UNIT", "value": "SGFT"}],
      "title": "La licencia {{numero}} vence el {{date}}",
      "tab": "VIGENCIA"
    }
  ]
}
```

Each licence in the window becomes one notification linking to its record and tab, a warning from 15 days before,
an action from the day itself; renewing it resolves it at once. The `object` key is the script's, to build the path;
the rest is the rule's body ([../api/rest.md#notification-rules](../api/rest.md#notification-rules)). A rule's
conditions are ANDed: "estado is VIGENTE or empty" is two rules.

### Organizational units

Units address people by where they sit (gerencia › subgerencia › área); a notification for a unit reaches its whole
subtree. Keep them in `model/org_units.json` and apply them the same way, parents first:
`GET /api/org-units`, `POST` a missing code, `PUT /api/org-units/{code}` a changed label or parent. A code never
changes once your code or a rule names it; a label can. Who sits where is `PUT /api/users/{id}/org-units`
([../api/rest.md#organizational-units](../api/rest.md#organizational-units)).

```json
{
  "units": [
    {"code": "GAT", "label": "Gerencia de Administración Tributaria"},
    {"code": "SGFT", "label": "Subgerencia de Fiscalización Tributaria", "parent": "GAT"}
  ]
}
```

### Call them "Alertas"

In a Spanish UI, call the feature **"Alertas"**, with three groups: Comunicados (`INFO`), Advertencias (`WARNING`)
and Pendientes (`ACTION`). A municipal app already uses "notificación" for the legal act served on a taxpayer, and
"aviso" may name a thing of its own (an advertisement); the code still says notifications. Titles say what is wrong
and where, for the person who must act: "La licencia 0042 vence el 10/10/2026", not "Recordatorio".

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
