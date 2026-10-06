# Notifications module

An app that installs this module tells people what they must know or do, by person, role or organizational unit,
within a time window: information with a link to a law or a manual (`INFO`), a warning that something expires soon
(`WARNING`) and a call to action that opens the screen, and the tab, where the work is done (`ACTION`). People publish
by hand, app code publishes from inside its own transactions, scheduled sources report computed states, and date
rules watch `DATE` and `DATETIME` fields. What a source or a rule stops reporting is resolved, so the inbox only shows
what is still true. A live summary reaches the browser over Server-Sent Events.

The UI says **"Alertas"**, with three groups: Comunicados (`INFO`), Advertencias (`WARNING`) and Pendientes (`ACTION`).
The code says notifications, because wasichai-ui already has an `Alert` primitive (an inline message). Decisions:
[ADR-046](../adr/0046-notifications-module.md) (the module) and
[ADR-047](../adr/0047-server-push-over-sse-and-listen-notify.md) (live delivery); design:
[the spec](../superpowers/specs/2026-10-06-notifications-design.md).

## Install

```kotlin
implementation("wasichai:wasichai-spring-boot-starter-notifications")
```

The frontend package, `@wasichai/notifications`, is planned (see [Frontend package](#frontend-package)). See
[../guides/build-your-app.md](../guides/build-your-app.md#tell-people-what-needs-doing).

## What it adds

A notification has a kind, a title (1–200 characters), a plain-text body (at most 4000, never rendered as HTML), an
optional link, a window (`publishAt`, `expiresAt`) and an optional `dueAt`; an `ACTION` past its `dueAt` is `overdue`.

- **The audience is matched when the inbox is read**, not copied per person. Targets are `ALL`, `USER`, `ROLE` (the
  reader's token roles) and `UNIT` (the reader's units and every unit above them, so a unit reaches its whole
  subtree; [core's organizational units](core.md#organizational-units)). One row reaches a whole role, and someone who
  joins a unit sees what is open for it at once. An `EMAIL` audience is turned into a `USER` when published; a service
  account is never anybody's recipient.
- **Per-person state** lives in receipts: read, dismissed, snoozed. No receipt is unread.
- **A link is data, not a URL**: `RECORD` (an object, a record id and an optional tab key, the
  [TAB key](pages.md#tab-keys) of the pages module), `ROUTE` (a route key the UI knows, its params and a tab) or `URL`
  (absolute `http` or `https`). The inbox drops a `RECORD` link for a reader without `READ` on its object, and for
  everyone once the object is deleted. `ROUTE` and `URL` links are kept: the UI guards its routes.
- **Keyed and self-resolving.** A notification of a source or a rule carries a `key`. Publishing a key again is an
  upsert: the same content (a SHA-256 fingerprint) writes nothing; new content updates in place and keeps who read it,
  unless the kind changed (`WARNING` becoming `ACTION` is news); a resolved key reopens as new. A key a source no
  longer reports is resolved.
- **Who owns it.** A notification of source `manual` is a person's: it is edited and deleted over REST. Any other
  source (an app's key, `rule:<name>`) owns its notifications: REST answers `409` to `PUT` and `DELETE`, and its
  `ACTION`s cannot be dismissed; they leave when the work is done.

REST routes ([../api/rest.md#notifications](../api/rest.md#notifications)):

| Method | Path | Who |
|---|---|---|
| GET | `/api/notifications` | `MANAGE_ORGANIZATION` |
| POST | `/api/notifications` | `MANAGE_ORGANIZATION` |
| GET | `/api/notifications/{id}` | `MANAGE_ORGANIZATION` |
| PUT | `/api/notifications/{id}` | `MANAGE_ORGANIZATION`, manual only |
| DELETE | `/api/notifications/{id}` | `MANAGE_ORGANIZATION`, manual only |
| GET | `/api/auth/me/notifications` | any person |
| GET | `/api/auth/me/notifications/summary` | any person |
| GET | `/api/auth/me/notifications/stream` | any person, `text/event-stream` |
| POST | `/api/auth/me/notifications/{id}/read` | any person |
| POST | `/api/auth/me/notifications/{id}/dismiss` | any person |
| POST | `/api/auth/me/notifications/{id}/snooze` | any person |
| POST | `/api/auth/me/notifications/read-all` | any person |
| GET | `/api/notification-rules` | `MANAGE_METADATA` |
| GET | `/api/objects/{object}/notification-rules` | `MANAGE_METADATA` on the object |
| POST | `/api/objects/{object}/notification-rules` | `MANAGE_METADATA` on the object |
| GET | `/api/objects/{object}/notification-rules/{name}` | `MANAGE_METADATA` on the object |
| PUT | `/api/objects/{object}/notification-rules/{name}` | `MANAGE_METADATA` on the object |
| DELETE | `/api/objects/{object}/notification-rules/{name}` | `MANAGE_METADATA` on the object |
| POST | `/api/objects/{object}/notification-rules/{name}/run` | `MANAGE_METADATA` on the object |

A service account gets `403` on `/api/auth/me/notifications/**` (it is not a person) and on the admin routes (as on
every `MANAGE_ORGANIZATION` route, ADR-043).

**The loop.** `NotificationLoop`, a `SmartLifecycle` built like automation's drain, looks every `tick` for due work:
each `NotificationSource` bean at its own interval and the date rules at `rule-interval`. A due item runs under
`ClusterLock.tryLock("wasichai.notifications.<key>")` and checks again that it is still due, so N replicas run it once
per interval (`notification_source_runs` records the last run). It runs for every organization: a source as the
platform (`RecordService.asPlatform`), the date rules through core's `RecordStore` port (below); a failing
organization, source or rule is logged and the loop moves on. Once a day, under its own lock, it deletes notifications
resolved or expired longer than `retention` ago. ADR-039 left loops to apps; this one is the module's own work, as the
drain is automation's (ADR-046).

**Date rules** (`definition` of a rule, [../api/rest.md#notification-rules](../api/rest.md#notification-rules)): a
`DATE` or `DATETIME` field, 1–5 stages (`fromDays` → kind), an `untilDays`, conditions (`EQ`, `EMPTY`, `NOT_EMPTY`,
all of them must hold), an audience, a title and body with `{{field}}`, `{{days}}`, `{{date}}` and `{{object}}`, and a
tab. Each record in the window gives one notification of source `rule:<name>`, keyed by the record id, linking to the
record and the tab. A run reads the window's records through core's `RecordStore` port, outside any transaction and
with no permission check, so it works the same inside a request (`POST`, `PUT`, `run`) and in the loop;
`RecordService.asPlatform` would refuse inside a request. A run is capped at `rule-max-notifications` records per rule
and organization, earliest dates first, with a WARN past the cap. A record change re-evaluates that record at once
(the cap does not apply to that one record), so a renewed licence stops saying "vence" without waiting for the next
run; a deleted record resolves its notification. Disabling or deleting a rule resolves its notifications; deleting the
object resolves what its rules published (`NotificationRuleCleanup`), then deletes the rules.

**The rule zone** is where a day starts for date rules: `wasichai.notifications.zone`; unset, the zone of the app's
`Clock` bean when it has exactly one; else the system's, with a WARN at start. It is settled once, when the
`ruleNotifications` bean is built.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `wasichai.notifications.enabled` | `true` | `false` removes the notifications beans, routes, loop, stream and migration |
| `wasichai.notifications.tick` | `30s` | how often the loop looks for due sources; `0s` turns the loop off (tests) |
| `wasichai.notifications.rule-interval` | `15m` | how often date rules run; must be positive, or the start fails |
| `wasichai.notifications.rule-max-notifications` | `100` | cap per rule and organization, 1–200; out of range fails the start |
| `wasichai.notifications.retention` | `90d` | resolved or expired notifications older than this are purged |
| `wasichai.notifications.snooze-max` | `30d` | the furthest a snooze may reach |
| `wasichai.notifications.listen` | `true` | open the `LISTEN` connection; `false`: streams live on the refresh alone |
| `wasichai.notifications.stream-refresh` | `60s` | every stream recomputes this often, whatever it heard |
| `wasichai.notifications.stream-heartbeat` | `25s` | a comment line keeps proxies from closing an idle stream |
| `wasichai.notifications.stream-debounce` | `500ms` | signals closer than this cost one recompute |
| `wasichai.notifications.zone` | unset | the zone date rules count days in; unset: the app's unique `Clock` bean's zone, else the system's (WARN) |
| `wasichai.notifications.date-pattern` | `dd/MM/yyyy` | how `{{date}}` and `DATE` values print in rule templates |

Env form: `WASICHAI_NOTIFICATIONS_ENABLED`. The module reads "now" from the app's `Clock` bean when it has exactly one
(tests fix time with it), else from the system's.

## Extension points

**Defines: `Notifications`**, the bean an app calls to publish from its own code:

```kotlin
suspend fun publish(organizationId: UUID, source: String, draft: NotificationDraft): UUID?
suspend fun resolve(organizationId: UUID, source: String, key: String): Boolean
suspend fun resolveAll(organizationId: UUID, source: String): Int
```

```kotlin
notifications.publish(
    organizationId,
    "caja.pagos",
    NotificationDraft(
        kind = NotificationKind.ACTION,
        title = "Pago cobrado sin registrar",
        audience = listOf(Audience.Role("SUPERVISOR_CAJA"), Audience.Email(responsable)),
        link = NotificationLink.Route("caja:pagos-sin-entregar", mapOf("fecha" to hoy.toString())),
        key = "pago-$pagoId",
        dueAt = finDelDia
    )
)
// later, when someone explained it
notifications.resolve(organizationId, "caja.pagos", "pago-$pagoId")
```

- **Transactions.** Every call joins the caller's transaction ([ADR-038](../adr/0038-record-service-joins-the-callers-transaction.md)),
  or runs in its own. A rollback takes the notification with it; the live signal goes out only with the commit.
- **`source`** names the producer: `^[a-z][a-z0-9_.:-]{1,80}$`. `manual` and `rule:…` are the module's own and refused.
- **`key`** (`^[A-Za-z0-9_.:/-]{1,200}$`) makes `publish` an upsert on (organization, source, key); without one every
  call inserts a new notification. `resolve` answers `false` when no open notification has that key.
- **Lenient about people.** An unknown user, email, role or unit is dropped with a WARN, so a person who left never
  rolls back the business work; when nobody is left, `publish` writes nothing and answers `null`.
- **Lenient about length.** A title over 200 characters or a body over 4000 is cut, ending in "…".
- **Strict about format.** A bad key, route, URL or tab, an expiry before the publication (or, without `publishAt`, not
  in the future), or a `RECORD` link to an object the organization does not have is an `IllegalArgumentException`: a
  bug in the app. Role names and unit codes are trimmed and upper-cased, emails trimmed and lower-cased.

The audience is `Audience.All`, `User(id)`, `Email(email)`, `Role(name)` or `Unit(code)`; the link
`NotificationLink.Record(objectName, recordId, tab)`, `Route(route, params, tab)` or `Url(url)`. A route key is
`^[a-z][a-z0-9-]*:[A-Za-z0-9_.-]+$`, with at most 10 params (keys `^[A-Za-z][A-Za-z0-9_]{0,39}$`, values at most 200
characters); its params fill the route's path parameters first and the rest go to the query string. A tab is checked
for the TAB key format only (`^[A-Z][A-Z0-9_]{0,39}$`), never against pages.

**Defines: `NotificationSource`**, an SPI for computed states. Declare one as a bean; the loop asks it every
`interval` (15 minutes by default), once per cluster, for every organization, and `currentNotifications` answers
**everything that should be open now**, each draft with a stable key:

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
        // nothing to say: last run's notification is resolved
        if (total == 0L) return emptyList()
        return listOf(
            NotificationDraft(
                kind = NotificationKind.WARNING,
                title = "$total turnos siguen abiertos",
                audience = listOf(Audience.Role("SUPERVISOR_CAJA")),
                link = NotificationLink.Route("caja:turnos"),
                key = "abiertos"
            )
        )
    }
}
```

- What it answered last time and leaves out now is resolved; what comes back is reopened; a draft whose content did
  not change writes nothing. A draft without a key, or two with the same key, refuse the whole answer for that
  organization (logged), and so does a malformed draft. A draft with no recipient left stays out, which resolves its
  stored notification.
- It runs **outside any transaction**, so it may call remote systems, and **as the platform**: `RecordService` reads
  and writes are scoped to the organization with no permission check; services that ask `CurrentUser` still refuse.
  Only the reconcile that follows is a transaction, one per organization.
- `key` is the notifications' `source`: the same format as above, not `manual`, not `rule:…`, not `purge` or `rules`
  (the loop's own items), unique among the app's sources, and the interval must be positive; otherwise the app does
  not start.
- Do not also `Notifications.publish` under a source's key: the source would resolve what `publish` wrote.
- **Aggregate.** "12 permits expire this week" with a link to the list, not one notification per record: an inbox
  that floods is ignored. Here the title carries the count, so a new count updates the notification in place and
  keeps who read it.

`NotificationLoop.runSource(key)` runs one source now, due or not, for every organization: for an app's tests (with
`wasichai.notifications.tick=0s`) and manual runs. It answers `false` when another replica holds the source's lock.

**Implements:** `wasichai.core.data.RecordChangeListener`, for date rules (`NotificationRuleListener`, last in order:
a changed record is evaluated against its rules at once, inside the writer's transaction; a deleted one resolves);
`wasichai.core.metadata.ObjectRemovalListener`, for date rules (`NotificationRuleCleanup`: deleting an object resolves
what its rules published, in the delete's transaction, before the rules go with the object);
`wasichai.core.metadata.FieldUsage`, for date rules (`NotificationRuleFieldUsage`: a rule, disabled ones included,
names itself, "notification rule '<name>'", as a user of a field it reads, so deleting that field is a `409`). It uses
core's ports, never core's tables: `OrgUnitDirectory`, `UserDirectory`, `RoleDirectory`, `RecordStore`,
`OrganizationRepository.ids()`, `ClusterLock` and `Connections.unpooled`.

**Overridable beans:** `audienceResolver`, `notificationPreparer`, `notificationRepository`, `inboxRepository`,
`notificationWriter`, `inboxService`, `inboxController`, `notifications`, `notificationAdminService`,
`notificationAdminController`, `notificationLoop`, `notificationSignals`, `notificationListener`,
`notificationStreamController`, and for date rules `notificationRuleRepository`, `ruleNotifications`,
`notificationRuleService`, `notificationRuleController`, `notificationRuleListener`, `notificationRuleCleanup` and
`notificationRuleFieldUsage` — all `@ConditionalOnMissingBean`, so an app can replace any of them. The migration bean
(`wasichaiNotificationsMigration`) is not: core's own `ModuleMigration` would always back off first.

## Live delivery

`GET /api/auth/me/notifications/stream` answers `text/event-stream` with the person's summary, never the notifications
themselves: the UI fetches what it shows through the usual routes, with the usual checks
([ADR-047](../adr/0047-server-push-over-sse-and-listen-notify.md)).

```
event:summary
data:{"kinds":{"INFO":{"active":2,"unread":1,"overdue":0},"WARNING":{…},"ACTION":{…}},"latest":{…}}

:ping

```

- One event type, `summary`: the first one at once, then one whenever the summary changes. Its data is the JSON of
  `GET /api/auth/me/notifications/summary`. A comment line, `:ping`, goes out every `stream-heartbeat`, and the answer
  carries `X-Accel-Buffering: no`, so nginx does not buffer it.
- **Writers notify inside their transaction**: `pg_notify('<metadataSchema>_notifications', payload)`, delivered on
  commit and dropped on rollback. The payload is ids only, `{"o":"<org>"}` for a change to notifications and
  `{"o":"<org>","u":"<user>"}` for one person's receipts, so another person's reads never wake my stream. A payload
  that is not one of these is ignored. The channel name must fit PostgreSQL's 63 characters, which bounds the
  metadata schema's name to 49.
- **`NotificationListener`** holds one `LISTEN` connection per replica, outside the pool (`Connections.unpooled`), and
  feeds every open stream of that replica. It reconnects with backoff (1 s up to 30 s), checks the connection with
  `SELECT 1` every 60 s (one that takes over 10 s counts as lost), and after every (re)connect tells every stream to
  recompute, so a gap costs one recompute and loses nothing. It is not created with `listen=false` or without the
  r2dbc-postgresql driver on the classpath. A connection that is not PostgreSQL's is the one failure it does not
  retry: it turns itself off with a WARN.
- **The refresh floor.** Every stream recomputes every `stream-refresh` whatever it heard: a window that opens, or an
  expiry, writes nothing and notifies nobody. Signals closer than `stream-debounce` cost one recompute. With `LISTEN`
  down, the stream degrades to polling at the floor, never to silence.
- **PgBouncer** in transaction mode breaks `LISTEN`: run it in session mode, or give the replica a direct connection.
  Otherwise the stream still works, at the refresh floor. The same holds behind a proxy that buffers responses.
- **Security.** The token travels in the `Authorization` header only, never in the URL, so the UI uses a `fetch`-based
  SSE client rather than `EventSource`. The caller is resolved before the stream starts, so a missing token is a plain
  `401` and a service account a plain `403`. The stream completes at the token's `exp`; the UI reconnects with the
  token it holds then, or signs out on `401`. See
  [../security/authentication.md#long-lived-streams](../security/authentication.md#long-lived-streams).

## Database

Migration location `classpath:db/wasichai/notifications`, history table `flyway_history_notifications`. Tables:

- `notifications`: one row per notification, with its kind, title, body, `link` jsonb, `link_object_id` (the object a
  `RECORD` link opens, set null when the object is deleted), window, `due_at`, `source`, `source_key` (unique per
  organization and source), `fingerprint`, `resolved_at` and `created_by`.
- `notification_targets`: who it is for, one row per `ALL`, `USER`, `ROLE` or `UNIT`. Deleting a user or a unit deletes
  the targets that named them; a notification whose last target is gone reaches nobody.
- `notification_receipts`: one row per person who read, dismissed or snoozed a notification.
- `notification_rules`: date rules, one per name and organization (`^[a-z][a-z0-9_]{1,48}$`), the whole rule as
  `definition` jsonb; deleted with their object.
- `notification_source_runs`: when each source (and the rules, and the purge) last ran, shared by every replica.

The tables reference core's `organizations`, `users`, `custom_objects` and `org_units`, and are deleted with their
organization.

## Frontend package

`@wasichai/notifications` is planned in wasichai-ui
([plan](../superpowers/plans/2026-10-06-notifications-wasichai-ui.md)), backend first (ADR-032):
`notificationsModule({ resolveLink? })` with routes `notifications:inbox`, `notifications:admin` and
`notifications:rules`, a bell in the shell fed by the stream (one stream shared between browser tabs), and "Alertas" in
the nav. Until it ships, an app calls the REST routes itself.

## Without this module

Nothing is published, and every route above answers `404` to an authenticated caller
([ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D1), so the frontend can tell "not installed" from "not
allowed". Organizational units are core's and stay. The routes and tables are deviation D32 of ADR-031.

## Known limitations

- **Volume.** A per-record rule is capped (`rule-max-notifications`); a source must aggregate. An app that needs
  hundreds of notifications from one rule wants a `NotificationSource` that counts them.
- **Rule conditions are ANDed equalities and emptiness**: no "one of" (`IN`), no "equal or empty" (two rules do it),
  no comparison. A value replaced by a new row rather than edited (a fee renewal) needs a source, not a rule.
- **Rule audiences are fixed**: no audience taken from the record (`created_by`, an email field), no rule on a workflow
  state's age, no automation `NOTIFY` action. They wait for a second user.
- **Who publishes is coarse**: manual notifications need `MANAGE_ORGANIZATION`, rules `MANAGE_METADATA` on the object.
  A unit head publishing to their own unit waits for units with a head.
- **Templates print values without field permissions**: a rule's author is a metadata administrator.
- **Roles are the token's**, as in every permission check: a role granted meanwhile reaches the person after the next
  sign-in. Units are not in the token and apply on the next read, an open stream's next summary included.
- **A deleted unit or user** silently drops its targets: list `GET /api/notifications?unit=<code>` before deleting a
  unit.
- **One extra database connection per replica** for `LISTEN`. Every browser tab opens a stream; over HTTP/1.1 (six
  connections per origin) the UI shares one between tabs, or the app serves HTTP/2.
- **In the app only**: no email, digest, required acknowledgement, banner or escalation in v1.
