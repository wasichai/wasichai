# Notifications ("Alertas") and organizational units: design

**Status:** approved 2026-10-06. **Scope:** wasichai (backend: core, pages, a new `wasichai-notifications` module);
wasichai-ui, srtm-backend and caja-backend through their own plans. **ADRs:**
[ADR-045](../../adr/0045-organizational-units.md), [ADR-046](../../adr/0046-notifications-module.md),
[ADR-047](../../adr/0047-server-push-over-sse-and-listen-notify.md); deviations D31–D33 in
[ADR-031](../../adr/0031-deliberate-deviations-from-sapgis.md).

## Question

Municipal staff using srtm (tax, sanctions) and caja (cashier, treasury) learn what they must do only when they open the
right screen. srtm computes deadlines in Kotlin and shows them on a panel (`vencen_esta_semana`); caja computes what
blocks a closing (`lo_que_impide_cerrar`) on the arqueo. The urgent cases are log lines nobody reads: caja's
`DINERO COBRADO SIN REGISTRAR` names a person in `caja.conciliacion.responsable`, srtm logs a failed emisión lot.

Can wasichai tell people, by person, role or area, within a time window, three kinds of things: information (with a link
to a law, an ordinance, a manual), a warning (something expires soon) and a call to action (a link to the screen, and
the tab, where the work is done)? And what else would make the system easier to understand and run day to day?

Decisions taken with the user:

- **Areas are organizational units in core**: a tree per organization (gerencia › subgerencia › área) and who belongs
  where. Targeting a unit reaches its whole subtree.
- **Scope of this delivery:** this spec, a backend plan and the full backend in wasichai. wasichai-ui, srtm and caja get
  plans of their own (backend first, ADR-032).
- **Label:** the UI says **"Alertas"**, with three groups: Comunicados (INFO), Advertencias (WARNING), Pendientes
  (ACTION). srtm already uses "notificación" for the legal act served on a taxpayer and "aviso" for a kind of
  advertisement (`tipo_anuncio` `AVISO_*`). The code says `notifications`: wasichai-ui 0.5.0 already has an `Alert`
  primitive (an inline message), and the module must not be confused with it.
- **Channel:** in the app, live over Server-Sent Events, with PostgreSQL `LISTEN/NOTIFY` between replicas. No email in
  v1.

## What there is today

- **Identity:** organizations, users, roles, permissions, field permissions, service accounts. No unit, area, group or
  team (srtm's gerencia is free text, caja's `area` is a business object fees are charged to).
- **No notification of any kind:** no inbox, no mail, no SSE, no websocket. No controller returns a `Flux`.
- **Background work:** no scheduler (ADR-039). `ClusterLock` and `RecordService.asPlatform` exist; apps run their own
  `SmartLifecycle` loops (caja's outbox, srtm's emisión workers). The automation module drains its queue with
  `AutomationDrain` (`Flux.interval().onBackpressureDrop().concatMap { mono {} }`).
- **Hooks:** `RecordChangeListener` (after a write, synchronous), `RecordWriteGuard` (before), `FieldUsage`.
- **Pages:** a `TAB` has a `title` and no key. Generated pages title their tabs `DETAILS`, `RELATED`, `HISTORY` (gis adds
  `MAP`), which the UI translates. The UI has no `?tab=` (planned in the metadata-ui spec, phase 2).
- **Both apps** apply their metadata over REST with Python scripts (`model/*.json`, `apply.py`) and have no Flyway of
  their own. Both are on wasichai 0.2.0: caja upgrades through caja-backend#28, srtm through srtm-backend PR #90.
- **Most "pending" states are computed, not stored:** srtm's `Notificaciones.vencida`, `Plazos.hasta` (business days,
  holidays), `Anuncios.estado`; caja's open turno, blocked closing, reconciliation. Some are plain date fields
  (`tasa.vigencia_hasta`, `condicion_fecha_fin`, `relacionado.fecha_fin`, `anuncio.vigencia_hasta`).

## Use cases that shape the design

| App | Case | Kind | Audience | Trigger | Link |
|---|---|---|---|---|---|
| caja | Dead payment (`MUERTO`, money charged and not recorded) | ACTION | role `SUPERVISOR_CAJA` + the responsible's email | explicit `publish` in the outbox; resolved when explained | unsent payments |
| caja | A turno of an earlier day still open | ACTION | the cashier's email + supervisor | `NotificationSource` `caja.turnos` | closing |
| caja | Pending payments block the closing | WARNING | the turno's cashier | `NotificationSource` | arqueo |
| caja | The day's reconciliation does not balance | ACTION | `TESORERIA` | `NotificationSource` `caja.conciliacion` | reconciliation `?fecha=` |
| caja | Closing with a cash difference | WARNING | `SUPERVISOR_CAJA`, `TESORERIA` | explicit `publish` in `CerrarTurno` | arqueo |
| caja | Fees about to end without a successor / new fee in force | WARNING / INFO + ordinance URL | `TESORERIA` / `CAJERO` | `NotificationSource` `caja.tasas`, aggregated (a renewal is a new row) / `publish` | fee list |
| srtm | Notificaciones previas due this week / overdue without acta | WARNING / ACTION, aggregated | Fiscalización unit | `NotificationSource` `srtm.sanciones` | Infracciones › Notificaciones |
| srtm | Acta without RIS, descargo not resolved, RIS not served | ACTION | resolutor / notificador | `NotificationSource` | acta › Resoluciones tab |
| srtm | Next year's parameters missing (UIT, FERIADOS, PLAZO) | WARNING | ADMIN / Parámetros | `NotificationSource` `srtm.parametros` | parameters |
| srtm | Mass emisión finished / failed | ACTION / WARNING | who launched it | explicit `publish` from the job | Emisiones › detail |
| srtm | Advertisement about to expire (its term lives in `movimiento_anuncio`) | WARNING | role or unit | `NotificationSource` `srtm.anuncios` | record › tab |
| srtm | Special condition or representative ends (`condicion_fecha_fin`, `relacionado.fecha_fin`) | WARNING | role or unit | date rule | record › tab |
| both | New ordinance, TUPA, manual | INFO + URL | everyone, a unit or a role | manual, with a window | external document |

Sources that see many records must **aggregate** ("12 due this week", linking to the list): an inbox that floods is
ignored.

## Verdict

One opt-in module, `wasichai-notifications`, over a small core addition, organizational units. The audience is matched
when the inbox is read, so a notification for a role or a unit costs one row however many people it reaches, and new
members see it at once. Three producers feed the same table: people (manual, over REST), app code (`Notifications`,
a Kotlin bean, and `NotificationSource`, an SPI the module runs on a schedule) and metadata (date rules over `DATE` /
`DATETIME` fields, applied over REST like the rest of an app's model). Everything a source or a rule produces carries a
key, and what disappears from the source is resolved: the inbox only shows what is still true. A live summary goes out
over SSE; `pg_notify` inside the writing transaction tells every replica.

## A. Core: organizational units (ADR-045, D31)

### Tables (`wasichai-core`, `db/wasichai/core/V9__org_units.sql`)

```sql
CREATE TABLE ${metadataSchema}.org_units (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    parent_id       uuid,
    code            text NOT NULL,
    label           text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT org_units_code_unique UNIQUE (organization_id, code),
    CONSTRAINT org_units_org_id_unique UNIQUE (organization_id, id),
    CONSTRAINT org_units_code_valid CHECK (code ~ '^[A-Z][A-Z0-9_]{1,48}$'),
    CONSTRAINT org_units_label_valid CHECK (length(label) BETWEEN 1 AND 120),
    CONSTRAINT org_units_not_own_parent CHECK (parent_id <> id),
    CONSTRAINT org_units_parent_fkey FOREIGN KEY (organization_id, parent_id)
        REFERENCES ${metadataSchema}.org_units (organization_id, id)
);
CREATE INDEX org_units_parent_idx ON ${metadataSchema}.org_units (parent_id);

CREATE TABLE ${metadataSchema}.user_org_units (
    user_id uuid NOT NULL REFERENCES ${metadataSchema}.users (id) ON DELETE CASCADE,
    unit_id uuid NOT NULL REFERENCES ${metadataSchema}.org_units (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, unit_id)
);
CREATE INDEX user_org_units_unit_idx ON ${metadataSchema}.user_org_units (unit_id);
```

- The parent foreign key is composite, so a parent is always of the same tenant. It is `NO ACTION`: deleting the
  organization removes the whole tree in one statement; the admin route refuses to delete a unit with children.
- **Codes** are upper case, normalised with `trim().uppercase()`, the token style of role names and declared actions.
  A code never changes (apps bind to it: `Audience.Unit("SGFT")`, `model/org_units.json`); the label can.
- **Membership is many-to-many**: encargaturas and shared staff are real.
- **Not in v1** (rule 10): a head flag (only escalation needs it), an `active` flag, a position for ordering (siblings
  sort by label).
- **Not in the token.** A unit is not a permission and changes more often than an 8-hour token lives. It is read when an
  inbox is read, by one small recursive query.

### Ports (`wasichai.core.identity`, beans in `WasichaiSecurityAutoConfiguration`, `@ConditionalOnMissingBean`)

Modules never read core's tables; they get these ports.

```kotlin
data class OrgUnitRef(val id: UUID, val code: String, val label: String, val path: List<String>)

class OrgUnitDirectory(db: DatabaseClient, schemas: WasichaiSchemas) {
    // the user's units and every unit above them: an alert for a unit reaches its subtree, so this is the match set
    suspend fun closureOf(organizationId: UUID, userId: UUID): Set<UUID>
    suspend fun idsByCode(organizationId: UUID, codes: Collection<String>): Map<String, UUID>
    suspend fun codesById(organizationId: UUID, ids: Collection<UUID>): Map<UUID, String>
    suspend fun unitsOf(organizationId: UUID, userId: UUID): List<OrgUnitRef>   // direct units, path root -> unit, by label, code
}

class UserDirectory(db: DatabaseClient, schemas: WasichaiSchemas) {
    // enabled people of the tenant by lower-cased email; a service account (disabled backing row) is nobody's recipient
    suspend fun idsByEmail(organizationId: UUID, emails: Collection<String>): Map<String, UUID>
    // enabled people of the tenant among ids
    suspend fun existing(organizationId: UUID, ids: Collection<UUID>): Set<UUID>
    // any user of the tenant, disabled ones too: shows who an alert named
    suspend fun emailsById(organizationId: UUID, ids: Collection<UUID>): Map<UUID, String>
}
```

`closureOf` walks up with `WITH RECURSIVE … UNION` (not `UNION ALL`: duplicates collapse and a cycle, should one ever
exist, ends). Empty input collections answer empty maps without a query.

Also in core:

- `OrganizationRepository.ids(): List<UUID>` for background work (there is still no REST list of organizations).
- `wasichai.core.platform.Connections.unpooled(factory: ConnectionFactory): ConnectionFactory`, `ClusterLock`'s private
  unwrap made public. Its second user is the notifications module's `LISTEN` connection.
- `CoreArchitectureTest`: `notifications` joins the module-name regex and `notification\w*` the module-table regex.

### REST (`wasichai.core.admin.OrgUnitAdmin.kt`; `MANAGE_ORGANIZATION`, so a service account is refused, ADR-043)

| Route | Body / answer |
|---|---|
| `GET /api/org-units` | `[{code, label, parentCode, memberCount}]`, sorted by label, then code; the UI builds the tree |
| `POST /api/org-units` | `{code, label, parentCode?}` → `201` `{code, label, parentCode, memberCount}`; a missing or blank `parentCode` is a root; `409` repeated code; `400` bad code or label, unknown parent, too deep |
| `GET /api/org-units/{code}` | `{code, label, parentCode, members: [{id, email, displayName}]}`, members by email; `404` unknown |
| `PUT /api/org-units/{code}` | a map, as `PUT /api/auth/me/preferences`: `label` sets it; `parentCode` moves (`null` or blank = root); a missing key keeps; an unknown key is `400` |
| `DELETE /api/org-units/{code}` | `204`; `409` when it has sub-units or members |
| `PUT /api/users/{id}/org-units` | `{units: [codes]}` replaces the user's set (a body without `units` empties it) → `AdminUserResponse`; `400` unknown code; `404` unknown user or a service account |
| `GET /api/auth/me/org-units` | any signed-in caller: `[{code, label, path}]`, the caller's direct units by label, then code; a service account reads `[]` |

- The path `{code}` is normalised as a code is (`trim().uppercase()`).
- **Every write** (create, update, delete, a user's set) runs under `ClusterLock.withXactLock("wasichai.org-units.<org>")`
  inside the service's transaction, so two moves cannot cross into a cycle, a child cannot land on a unit being
  deleted, and depth holds against a concurrent move. A move under the unit itself or one of its descendants is `400`.
  Depth (root = 1) is capped at 10 on create and on move, a move counting the moved subtree's height.
- `AdminUserResponse` gains `orgUnits: List<String>` (codes, sorted by code). `GET /api/auth/me` does not change.

## B. Module `wasichai-notifications` (ADR-046, D32)

### Install and wiring

- Gradle project `wasichai-notifications` (`api(project(":wasichai-core"))`, `compileOnly(libs.r2dbc.postgresql)`),
  starter `starters/wasichai-spring-boot-starter-notifications`, picked up by the BOM automatically.
- Package `wasichai.notifications`; auto-configuration `wasichai.notifications.autoconfigure.WasichaiNotificationsAutoConfiguration`
  (`@AutoConfiguration(after = [WasichaiDataAutoConfiguration::class])`, `@ConditionalOnProperty(prefix =
  "wasichai.notifications", name = ["enabled"], havingValue = "true", matchIfMissing = true)`), every bean
  `@ConditionalOnMissingBean` except the migration bean
  `ModuleMigration("notifications", "classpath:db/wasichai/notifications", ModuleMigration.MODULE_ORDER)`.
- `NotificationsProperties` (`@ConfigurationProperties("wasichai.notifications")`):

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | the module's switch |
| `tick` | `30s` | how often the loop looks for due sources; `0s` turns the loop off (tests) |
| `rule-interval` | `15m` | how often date rules run; must be positive, or the start fails |
| `rule-max-notifications` | `100` | cap per rule and organization, 1–200; out of range fails the start |
| `retention` | `90d` | resolved or expired notifications older than this are purged |
| `snooze-max` | `30d` | the furthest a snooze may reach |
| `listen` | `true` | open the `LISTEN` connection |
| `stream-refresh` | `60s` | every stream recomputes this often, whatever it heard |
| `stream-heartbeat` | `25s` | a comment line keeps proxies from closing an idle stream |
| `stream-debounce` | `500ms` | signals closer than this cost one recompute |
| `zone` | unset | the zone date rules count days in; unset: the app's unique `Clock` bean's zone, else the system's, with a WARN at start |
| `date-pattern` | `dd/MM/yyyy` | how `{{date}}` and DATE values print in rule templates |

### Tables (`db/wasichai/notifications/V1__notifications.sql`)

```sql
CREATE TABLE ${metadataSchema}.notifications (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    kind            text NOT NULL,
    title           text NOT NULL,
    body            text,
    link            jsonb,
    -- the object a RECORD link opens: the inbox drops the link for a reader without READ on it; gone with the object
    link_object_id  uuid REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE SET NULL,
    publish_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz,
    due_at          timestamptz,
    source          text NOT NULL,
    source_key      text,
    fingerprint     text NOT NULL,
    resolved_at     timestamptz,
    created_by      uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT notifications_kind_valid CHECK (kind IN ('INFO', 'WARNING', 'ACTION')),
    CONSTRAINT notifications_window_valid CHECK (expires_at IS NULL OR expires_at > publish_at),
    CONSTRAINT notifications_source_key_unique UNIQUE (organization_id, source, source_key)
);
CREATE INDEX notifications_open_idx ON ${metadataSchema}.notifications (organization_id, publish_at DESC)
    WHERE resolved_at IS NULL;
CREATE INDEX notifications_source_idx ON ${metadataSchema}.notifications (organization_id, source)
    WHERE resolved_at IS NULL;
CREATE INDEX notifications_link_object_idx ON ${metadataSchema}.notifications (link_object_id)
    WHERE link_object_id IS NOT NULL;

CREATE TABLE ${metadataSchema}.notification_targets (
    notification_id uuid NOT NULL REFERENCES ${metadataSchema}.notifications (id) ON DELETE CASCADE,
    type            text NOT NULL,
    user_id         uuid REFERENCES ${metadataSchema}.users (id) ON DELETE CASCADE,
    role_name       text,
    unit_id         uuid REFERENCES ${metadataSchema}.org_units (id) ON DELETE CASCADE,
    CONSTRAINT notification_targets_type_valid CHECK (type IN ('ALL', 'USER', 'ROLE', 'UNIT')),
    CONSTRAINT notification_targets_shape CHECK (
        ((type = 'USER') = (user_id IS NOT NULL))
        AND ((type = 'ROLE') = (role_name IS NOT NULL))
        AND ((type = 'UNIT') = (unit_id IS NOT NULL))
    )
);
CREATE INDEX notification_targets_notification_idx ON ${metadataSchema}.notification_targets (notification_id);
CREATE INDEX notification_targets_user_idx ON ${metadataSchema}.notification_targets (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX notification_targets_unit_idx ON ${metadataSchema}.notification_targets (unit_id) WHERE unit_id IS NOT NULL;

-- one row per person who did something with a notification. no row = unread.
CREATE TABLE ${metadataSchema}.notification_receipts (
    notification_id uuid NOT NULL REFERENCES ${metadataSchema}.notifications (id) ON DELETE CASCADE,
    user_id         uuid NOT NULL REFERENCES ${metadataSchema}.users (id) ON DELETE CASCADE,
    read_at         timestamptz,
    dismissed_at    timestamptz,
    snoozed_until   timestamptz,
    PRIMARY KEY (notification_id, user_id)
);
CREATE INDEX notification_receipts_user_idx ON ${metadataSchema}.notification_receipts (user_id);

CREATE TABLE ${metadataSchema}.notification_rules (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    object_id       uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    name            text NOT NULL,
    label           text NOT NULL,
    enabled         boolean NOT NULL DEFAULT true,
    definition      jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT notification_rules_name_unique UNIQUE (organization_id, name),
    CONSTRAINT notification_rules_name_valid CHECK (name ~ '^[a-z][a-z0-9_]{1,48}$')
);
CREATE INDEX notification_rules_object_idx ON ${metadataSchema}.notification_rules (organization_id, object_id);

-- when each scheduled source last ran: N replicas run it once per interval, not N times
CREATE TABLE ${metadataSchema}.notification_source_runs (
    source      text PRIMARY KEY,
    last_run_at timestamptz NOT NULL
);
```

Deleting a unit or a user removes the targets that named them; a notification whose last target is gone reaches nobody
(documented; the admin UI warns before deleting a unit).

### The public contract (fixed first, so tasks can run in parallel)

```kotlin
package wasichai.notifications

enum class NotificationKind { INFO, WARNING, ACTION }

sealed interface Audience {
    data object All : Audience
    data class User(val id: UUID) : Audience
    data class Email(val email: String) : Audience     // caja: a cashier is an email on its records
    data class Role(val name: String) : Audience
    data class Unit(val code: String) : Audience       // reaches the unit's whole subtree
}

sealed interface NotificationLink {
    data class Record(val objectName: String, val recordId: UUID, val tab: String? = null) : NotificationLink
    data class Route(val route: String, val params: Map<String, String> = emptyMap(), val tab: String? = null) : NotificationLink
    data class Url(val url: String) : NotificationLink
}

data class NotificationDraft(
    val kind: NotificationKind,
    val title: String,
    val audience: List<Audience>,
    val body: String? = null,
    val link: NotificationLink? = null,
    // stable id inside the source; with one, publish is an upsert
    val key: String? = null,
    val publishAt: Instant? = null,
    val expiresAt: Instant? = null,
    val dueAt: Instant? = null
)

// the bean apps call. joins the caller's transaction (ADR-038); the pg_notify goes out with its commit.
class Notifications {
    // null when no recipient is left (each unknown one is dropped with a WARN)
    suspend fun publish(organizationId: UUID, source: String, draft: NotificationDraft): UUID?
    suspend fun resolve(organizationId: UUID, source: String, key: String): Boolean
    suspend fun resolveAll(organizationId: UUID, source: String): Int
}

// an app's computed states. everything that should be open now, each with a key; what is missing next time is resolved.
// key: a source format, not manual, rule:…, purge or rules (the loop's own items), unique; interval positive. else no start.
interface NotificationSource {
    val key: String
    val interval: Duration get() = Duration.ofMinutes(15)
    suspend fun currentNotifications(organizationId: UUID, now: Instant): List<NotificationDraft>
}
```

**Wire shapes** (REST and the `link` jsonb):

```json
{"type": "RECORD", "object": "tasa", "recordId": "7c1…", "tab": "VIGENCIA"}
{"type": "ROUTE", "route": "caja:pagos-sin-entregar", "params": {"fecha": "2026-10-06"}, "tab": null}
{"type": "URL", "url": "https://www.munixyz.gob.pe/ordenanzas/2026-006.pdf"}
```

A `ROUTE` link names a route key the UI knows (a wasichai-ui module route, or one an app's own shell resolves); its
`params` fill the route's path parameters first and the rest go to the query string.

Audience over REST: `{"type": "ALL"}`, `{"type": "USER", "value": "<uuid>"}`, `{"type": "EMAIL", "value": "a@b.pe"}`,
`{"type": "ROLE", "value": "CAJERO"}`, `{"type": "UNIT", "value": "SGFT"}`. `EMAIL` is stored as `USER`.

### Validation (`NotificationValidation`, pure)

| What | Rule |
|---|---|
| `title` | trimmed, 1–200 characters |
| `body` | at most 4000 characters, plain text (the UI never renders it as HTML) |
| `source` | `^[a-z][a-z0-9_.:-]{1,80}$`; `manual` and anything starting with `rule:` are the module's own |
| `key` | `^[A-Za-z0-9_.:/-]{1,200}$` |
| `audience` | not empty; a role name and a unit code are trimmed, upper-cased and must match `^[A-Z][A-Z0-9_]{1,48}$`; an email is trimmed, lower-cased and not blank |
| `link.route` | `^[a-z][a-z0-9-]*:[A-Za-z0-9_.-]+$`, at most 10 params, each key `^[A-Za-z][A-Za-z0-9_]{0,39}$`, each value ≤ 200 |
| `link.tab` | trimmed, upper-cased, `^[A-Z][A-Z0-9_]{0,39}$` (the TAB key format, section E); not checked against pages |
| `link.url` | absolute `http` or `https` with a host, at most 2000 characters |
| `link.object` | an object of the organization (`MetadataService.loadDefinition`); its id is stored in `link_object_id` |
| window | `expiresAt > publishAt`; without `publishAt`, `expiresAt` in the future |

**What "of a source" means:** any `source` other than `manual` (`rule:*`, an app's `NotificationSource` key, or a
key an app only publishes under). Such a notification cannot be edited or deleted over REST, and its ACTIONs cannot be
dismissed: the app resolves them (`resolve`, or by leaving them out of its source) or gives them an `expiresAt`.

**REST is strict, Kotlin is lenient about recipients and length.** From Kotlin a title over 200 characters or a body
over 4000 is cut with "…" rather than refused, so wording never aborts a business transaction. Over REST an unknown
user, email, role or unit is a `400` naming `audience[i]`. From Kotlin an unknown recipient is dropped with a WARN, so
a person who left never rolls back the business transaction the notification was published in; if none is left,
`publish` answers `null` and writes nothing. A malformed draft (format, an unknown object) is an
`IllegalArgumentException` from Kotlin: that is a bug in the app.

**Fingerprint:** SHA-256 (hex) over a canonical rendering of `kind`, `title`, `body`, `link`, the draft's own
`publishAt` (null stays null), `expiresAt`, `dueAt` and the sorted resolved targets. A draft with no `publishAt` does not
change its fingerprint as time passes.

### Upsert semantics (shared by `publish` and the reconciler)

| Stored row for (org, source, key) | Draft | Effect |
|---|---|---|
| none | any | insert; `publish_at` = draft's or now |
| open, same fingerprint | — | no write |
| open, other fingerprint | — | update in place, replace targets; `publish_at` = draft's or the stored one; receipts kept, **unless `kind` changed** (then deleted: WARNING → ACTION is news) |
| resolved | — | reopen: `resolved_at` = null, update, receipts deleted, `publish_at` = draft's or now |
| (no key) | — | always insert |

Two writers racing on one key meet on the unique constraint: insert with `ON CONFLICT DO NOTHING`, and on conflict go
the update path with the row locked (`FOR UPDATE`). Every change is followed by one `pg_notify` (section D). Writers of
one (organization, source) also take turns: each write holds
`ClusterLock.withXactLock("wasichai.notifications.<org>.<source>")` until its transaction ends (section C, the
reconciler). A draft without `publishAt` keeps the stored one (or takes now); an `expiresAt` not after that is an
`IllegalArgumentException` (`expiresAt must be after publishAt`) before anything is written, never the table's window
check failing.

`resolve` sets `resolved_at = now()` on the open row with that key; `resolveAll` on every open row of the source.

### What a person sees (one predicate for the list, the summary and every receipt action)

```sql
FROM ${m}.notifications n
LEFT JOIN ${m}.notification_receipts r ON r.notification_id = n.id AND r.user_id = :userId
WHERE n.organization_id = :org
  AND n.resolved_at IS NULL
  AND n.publish_at <= :now AND (n.expires_at IS NULL OR n.expires_at > :now)
  AND EXISTS (SELECT 1 FROM ${m}.notification_targets t WHERE t.notification_id = n.id
              AND (t.type = 'ALL' OR t.user_id = :userId OR t.role_name = ANY(:roles) OR t.unit_id = ANY(:units)))
```

- `:roles` are the token's roles (`AuthenticatedUser.roles`, as every permission check), `:units` is
  `OrgUnitDirectory.closureOf`. Bind arrays and use `= ANY`, never `IN (:list)` (an empty list breaks `IN`).
- **States:** `active` (default): not dismissed and not snoozed past now. `unread`: active and `read_at IS NULL`.
  `snoozed`: `snoozed_until > now` and not dismissed.
- **Order:** with `kind=ACTION`, `due_at ASC NULLS LAST, publish_at DESC, id`; otherwise `publish_at DESC, id`.
- **Links:** `CurrentUser.permittedObjects(user, READ)` once per request. A `RECORD` link is kept only when the object
  is allowed; a null `link_object_id` (object deleted) drops it. `ROUTE` and `URL` links are kept (the UI guards its
  routes). Record-level scope (`own_records_only`) is not checked: the record route still answers `404`, and the
  notification only carries what its publisher wrote.

### REST

**Administration** — `MANAGE_ORGANIZATION`; manual notifications only (a notification of a source or a rule answers
`409 Notification is owned by its source` to `PUT` and `DELETE`):

| Route | |
|---|---|
| `GET /api/notifications?source&kind&unit&status=open\|scheduled\|ended&page&size` | `PageResponse` of the admin view, by `created_at DESC, id DESC`; `unit` (a code) keeps those addressed to that unit, so the UI can warn before deleting it; `kind`, `status` and `unit` are case-insensitive, an unknown one is `400` |
| `POST /api/notifications` | `{kind, title, body?, link?, audience, publishAt?, expiresAt?, dueAt?}` → `201`; `source` = `manual`, `created_by` = the caller |
| `GET /api/notifications/{id}` | the admin view |
| `PUT /api/notifications/{id}` | same body, full replace, same receipt rule as an upsert; without `publishAt` the stored one stays, and `expiresAt` must follow it while it is ahead |
| `DELETE /api/notifications/{id}` | `204` |

Admin view: `{id, kind, title, body, link, audience: [{type, value, email?}], publishAt, expiresAt, dueAt, source,
key, resolvedAt, createdAt, updatedAt, readCount}`. `status`: `open` = not resolved and in its window; `scheduled` = not
resolved, `publish_at > now`; `ended` = resolved or expired.

**My notifications** — any person; a service account gets `403 A service account has no notifications`; a notification
the caller cannot see, or of another tenant, is `404`. Receipts do not hide an item from these routes: a dismissed one
can still be read.

| Route | |
|---|---|
| `GET /api/auth/me/notifications?kind&state=active\|unread\|snoozed&page&size` | `PageResponse` of inbox items; `kind` and `state` are case-insensitive, an unknown one is `400` |
| `GET /api/auth/me/notifications/summary` | `{kinds: {INFO: {active, unread, overdue}, WARNING: {…}, ACTION: {…}}, latest: {id, kind, title, publishAt, link} \| null}` |
| `POST /api/auth/me/notifications/{id}/read` | `204` |
| `POST /api/auth/me/notifications/{id}/dismiss` | `204`; `409 This notification leaves when its work is done` for an ACTION of a source or a rule |
| `POST /api/auth/me/notifications/{id}/snooze` | `{until}` → `204`; `400` unless `now < until ≤ now + snooze-max` |
| `POST /api/auth/me/notifications/read-all` | `{kind?}` → `204`; marks active unread items only (snoozed and dismissed stay) |
| `GET /api/auth/me/notifications/stream` | `text/event-stream`, section D |

Inbox item: `{id, kind, title, body, link, publishAt, expiresAt, dueAt, overdue, source, read, snoozedUntil,
dismissible}`. `overdue` = `due_at < now`. `latest` is the newest active notification by `publish_at`, or null.

## C. Scheduled sources and date rules

### The loop (`NotificationLoop`, a `SmartLifecycle` built like `AutomationDrain`)

Work items: every `NotificationSource` bean (`ObjectProvider<NotificationSource>.orderedStream()`), plus the module's
own `rules` item at `rule-interval`. Two items with the same key fail the start. `rules` and `purge` (the purge's run
key) are the loop's own keys: a source keyed with either fails the start saying so, and `Notifications.publish`,
`resolve` and `resolveAll` refuse them as sources (`IllegalArgumentException`). A source's `interval` (and
`rule-interval`) must be positive, or the start fails. Each tick (`tick`; `0s` = off, for tests only, which drive the
loop with `runSource`):

1. A work item is due when `notification_source_runs.last_run_at` is missing or older than its interval.
2. A due item runs under `ClusterLock.tryLock("wasichai.notifications.<key>")`; inside, it re-checks due-ness, so N
   replicas run it once.
3. For every `OrganizationRepository.ids()`, each on its own: a source runs inside `RecordService.asPlatform(org)`,
   `drafts = source.currentNotifications(org, now)` **outside any transaction** (a source may call remote systems), then
   the reconcile, which is the only transactional step. The `rules` item runs every enabled rule of the organization
   (below); one failing rule is logged and the others still run.
4. `last_run_at` is upserted. A failing organization or source is logged and the loop moves on. A
   `CancellationException` from a source or rule (its own `withTimeout`, a cancelled future) is such a failure: only
   the loop's own cancellation (`stop`) propagates. A tick that fails anyway is logged and the next one still comes:
   an error never reaches `Flux.interval`.

Daily, under `tryLock("wasichai.notifications.purge")`, notifications resolved or expired longer than `retention` ago
are deleted.

ADR-039 left loops to apps because no library work needed one. This loop is the module's own work, as
`AutomationDrain` is automation's; ADR-046 says so.

### The reconciler (`NotificationReconciler`)

`apply(org, source, drafts)` runs in one transaction per organization:

- Validates every draft (strict format, lenient recipients). A malformed draft, a draft without a key, or two drafts
  with one key, refuse the whole batch for that organization (logged): a source's output is a set keyed by `key`. A
  draft whose recipients are all unknown is left out of the batch, so its stored notification resolves.
- Loads the source's open rows plus resolved rows with the drafts' keys, then a pure `diff` decides insert, update,
  reopen or skip per draft (the upsert table above) and resolves every open row whose key is not among the drafts,
  keyless open rows of the source included.
- One `pg_notify` per organization when anything changed. Answers `{created, updated, reopened, resolved}`.
- **Writers take turns.** The reconcile, like every write of the source (`publish`, `resolve`, `resolveAll`, the rule
  listener, an admin `PUT` or `DELETE`), holds `ClusterLock.withXactLock("wasichai.notifications.<org>.<source>")`
  until its transaction ends. The lock alone is not enough: the drafts were read before it, outside any transaction.
  So the reconcile takes the instant its read began (`readStart`, the run's `now`) and leaves alone every row whose
  `updated_at` is later: someone who saw newer data wrote it (the listener, a publish). Such a row is neither updated,
  reopened nor resolved; the next run catches up. Equal is not later: a frozen test clock stamps the run's own instant.
  What remains is a write stamped before the read began but committed after it (the moment between the listener's
  write and its commit); a run can undo it, and the next write or run puts it right.
- A draft that would expire before the publication its stored row keeps refuses the batch (logged), like a malformed
  draft.

Mixing `Notifications.publish` and a `NotificationSource` under the same source key is unsupported (the source would
resolve what `publish` wrote); documented.

### Date rules (`/api/objects/{object}/notification-rules`, `MANAGE_METADATA` on the object)

| Route | |
|---|---|
| `GET /api/notification-rules` | every rule of the organization, with its object (`MANAGE_METADATA`), by object name, then rule name |
| `GET /api/objects/{object}/notification-rules` | the object's rules, by name |
| `POST /api/objects/{object}/notification-rules` | `201`; runs the rule at once when enabled, in one transaction with the insert; a name the organization already has is `409` |
| `GET`, `PUT`, `DELETE /api/objects/{object}/notification-rules/{name}` | `PUT` replaces (a body `name` other than the path's is `400`: a rule is not renamed); enabled it runs, disabled it resolves its notifications, in one transaction with the save; `DELETE` → `204`, deleting and resolving in one transaction |
| `POST /api/objects/{object}/notification-rules/{name}/run` | `{created, updated, reopened, resolved}`; `409` for a disabled rule |

Every answer is `{object, name, label, enabled, field, stages, untilDays, conditions, audience, title, body, tab}`:
the normalised rule (stages by `fromDays`, the audience normalised) and the name of its object.

```json
{
  "name": "licencia_por_vencer", "label": "Licencias por vencer", "enabled": true,
  "field": "vigencia_hasta",
  "stages": [{"fromDays": -15, "kind": "WARNING"}, {"fromDays": 0, "kind": "ACTION"}],
  "untilDays": 3,
  "conditions": [{"field": "estado", "op": "EQ", "value": "VIGENTE"}, {"field": "baja", "op": "EMPTY"}],
  "audience": [{"type": "ROLE", "value": "TESORERIA"}],
  "title": "La licencia {{numero}} vence el {{date}}",
  "body": "Quedan {{days}} días.",
  "tab": "VIGENCIA"
}
```

- **Zone.** The rule zone is `wasichai.notifications.zone`; unset, the app's unique `Clock` bean's zone; else the
  system's, with a WARN when the bean is built.
- **Offset** = today − the field's date, in days, in the rule zone (`DATETIME`: its date in that zone). The record is
  in the window while `min(stages.fromDays) ≤ offset ≤ untilDays`; its kind is the stage with the largest
  `fromDays ≤ offset`. `fromDays` and `untilDays` are within ±365, `stages` holds 1–5 entries with distinct `fromDays`,
  each at most `untilDays`.
- **Due:** a `DATE` field is due at the start of the next day in the zone (the date itself still counts); a `DATETIME`
  at its value. A notification is `overdue` once that passes.
- **Query:** through core's `RecordStore` port, `store.query(definition, org, RecordQuery(page = PageRequest(0, cap +
  1), sort = field, filters = <EQ conditions>, criteria = [window, EMPTY / NOT_EMPTY], count = false))`, outside any
  transaction. Not `RecordService.asPlatform { rows }`: `POST`, `PUT` and `run` run a rule inside a request, where
  `asPlatform` throws; the port works there and in the loop alike. No permission or record-level scope applies. The
  window is a `RecordCriterion` on the quoted column with bound bounds. Earliest dates first; more than `cap` records
  in the window log a WARN and only the first `cap` are notified (an app that needs hundreds should aggregate in a
  `NotificationSource`). A record whose date does not read is logged and left out.
- **Each record** gives a draft: key = record id, source = `rule:<name>`, kind by stage, link
  `Record(object, id, tab)`, the rule's audience, `dueAt` as above, title and body rendered.
- **Templates:** `{{<field>}}` (a field of a core type), `{{days}}` (date − today, may be negative), `{{date}}` and
  `{{object}}` (the object's label); the three built-ins win over a field of the same name. A DATE value prints with
  `date-pattern`, a DATETIME as `<date-pattern> HH:mm` in the rule zone, others as they come, null as empty. An unknown
  placeholder is a `400` on save. A title that renders blank falls back to the rule's label; the rendered title is cut
  at 200 characters and the body at 4000, with "…"; a body that renders blank is none. Templates print field values
  without field permissions: the author is a metadata administrator, and that is documented.
- **On save:** `name` matches `^[a-z][a-z0-9_]{1,48}$`; `label` is trimmed, 1–120 characters; the field exists and is
  `DATE` or `DATETIME`; at most 10 conditions, each on a field of a core type (a module type such as a geometry is
  refused); `op` is `EQ`, `EMPTY` or `NOT_EMPTY`; one `EQ` per field; an `EQ` value passes the field's codec, `EMPTY` and
  `NOT_EMPTY` take none; on a text field (`TEXT`, `LONG_TEXT`, `ENUM`, `EMAIL`, `URL`) blank counts as empty; the
  title template is 1–200 characters and the body at most 4000; the audience is strict; the tab passes its format
  (its violation is named `tab`).
- **On record change** (`NotificationRuleListener : RecordChangeListener`, last in order): for an object with enabled
  rules, the changed record is evaluated against `change.after` by the same pure evaluator, and only a key the rule has
  **open** is touched: updated in place, or resolved when the record left the window; `DELETED` resolves. A renewed
  licence stops saying "vence" at once instead of at the next run. Creating a notification, or reopening a resolved
  one, is left to the next run (`POST` and `PUT` run the rule at once, the loop every `rule-interval`): only the run
  sees the cap, so a record past it does not flap (published by a write, resolved by the run, reopened by the next
  write, its receipts wiped each time). Evaluation errors are logged and skipped; SQL errors propagate (the writer's
  transaction is aborted anyway).
- **A stale rule.** Saving checks the field, and core refuses to delete one a rule reads; should the field still be
  missing or no longer `DATE`/`DATETIME`, the run and the listener skip that rule with one WARN, never an error per
  record. In the loop, each rule runs on its own: an object whose definition does not load fails its own rules only.
- **`POST` and `PUT`** save and run (or resolve) in one transaction: a run that fails leaves the rule as it was.
- **Lifecycle:** disabling or deleting a rule resolves its notifications. Deleting the object resolves what its rules
  published first (`NotificationRuleCleanup : ObjectRemovalListener`, in the delete's transaction), then the cascade
  removes the rules. `NotificationRuleFieldUsage : FieldUsage` names "notification rule '<name>'", disabled rules
  included, so deleting a field a rule reads is core's `409`.

Deferred (no user yet): an audience taken from the record (`created_by`, an email field), rules on a workflow state's
age, an automation `NOTIFY` action (neither srtm nor caja installs automation).

## D. Live delivery: SSE over LISTEN/NOTIFY (ADR-047)

- **NOTIFY.** `SELECT pg_notify(:channel, :payload)` through `DatabaseClient`, inside the writing transaction: delivered
  on commit, dropped on rollback. The channel is `<metadataSchema>_notifications`, at most 63 characters (PostgreSQL
  would truncate it), so the metadata schema's name is effectively at most 49; the length is checked when the
  repositories are built (so a long name fails the start, listener or not) and on every notify. The payload is ids only: `{"o":"<org>"}` for a change to
  notifications, `{"o":"<org>","u":"<user>"}` for one person's receipts. PostgreSQL folds identical payloads within one
  transaction, so a batch notifies once.
- **`NotificationListener`** (`SmartLifecycle`): one connection under the pool (`Connections.unpooled`), cast to
  `io.r2dbc.postgresql.api.PostgresqlConnection`, `LISTEN "<channel>"`, then `getNotifications()` into
  `NotificationSignals` (a `Sinks.many().multicast().directBestEffort()` hub). It reconnects with backoff (1 s up to
  30 s), checks the connection with `SELECT 1` every 60 s (a check that takes over 10 s counts as lost), and emits a
  wildcard signal after every (re)connect so a gap costs one recompute and loses nothing. The bean is absent with
  `listen=false` or without the r2dbc-postgresql driver on the classpath; a connection that is not PostgreSQL's turns it
  off with a WARN (the one failure it does not retry).
- **`GET /api/auth/me/notifications/stream`** (`produces = text/event-stream`):
  - The user is resolved in the `suspend` controller method before the `Flux` is built.
  - Triggers: an initial one; this user's and this organization's signals (and wildcards), `sample`d at
    `stream-debounce`; a tick every `stream-refresh` (a window that opens, or an expiry, writes nothing and notifies
    nobody).
  - `onBackpressureLatest()`, then `concatMap` to the summary, `distinctUntilChanged()`, each one an event named
    `summary` whose data is the summary JSON.
  - A comment line (`:ping`) every `stream-heartbeat`; header `X-Accel-Buffering: no`.
  - The stream completes at the JWT's `exp`; the UI reconnects with its current token or signs out on `401`.
  - The token travels in the `Authorization` header, never in the URL. The UI uses a `fetch`-based SSE client, not
    `EventSource`.
- If `LISTEN` is down, every stream still refreshes at `stream-refresh`: delivery degrades to polling, never to
  silence. PgBouncer in transaction mode breaks `LISTEN`; documented.

## E. Pages: a TAB has a key (ADR-046, D33)

- `PageComponent.key: String?` with `@field:JsonInclude(NON_NULL)` (the wire and the stored jsonb do not change for a
  component without one); `PageComponentRequest.key` too.
- Only a `TAB` may carry it (`400` "key is only for TAB"); trimmed and upper-cased; `^[A-Z][A-Z0-9_]{0,39}$`; unique in
  the whole page (nested tab strips included), so `?tab=KEY` is never ambiguous.
- Generated pages key their tabs `DETAILS`, `RELATED`, `HISTORY` and each module tab (`MAP`) with its title. The three
  built-in keys are taken first, `RELATED` even on an object with no related tab; a module tab whose title repeats a
  taken key keeps its tab without a key. A module tab title that is not a valid key throws `IllegalArgumentException`
  when its `GeneratedComponent` is built, that is when the page is generated (the module's own tests catch it).
- A `PUT` without a definition keeps the keys.
- `?tab=KEY` in `PageRenderer` and key editing in the page builder belong to the wasichai-ui plan.

## F. Security

- Tenant: every query of the module filters by `organization_id` from the token or the platform's organization;
  receipt actions use the visibility predicate, so an id of another tenant, or one not addressed to the caller, is a
  `404`.
- A unit is not authorization: membership grants nothing, it only addresses notifications.
- Service accounts: refused by the org-unit routes (`MANAGE_ORGANIZATION`) and by `/api/auth/me/notifications/**`
  (`403`); never a recipient by email.
- Who publishes: manual notifications need `MANAGE_ORGANIZATION`; rules need `MANAGE_METADATA` on the object. A unit
  head publishing to their own unit waits for units v2.
- The stream is a long-lived authenticated GET: Bearer header only, completes at token expiry.

## G. What else would ease the day of municipal staff

| # | Feature | When | Why | Helps |
|---|---|---|---|---|
| 1 | "Mis pendientes": the ACTION tab, due dates, an overdue badge, auto-resolution | **v1** | the ACTION group of this delivery | both |
| 2 | Deep link to the screen and tab | **v1** | sections B and E | both |
| 3 | `helpUrl` on fields and objects (ordinance, law, manual) shown next to `description` | next, small core ADR | "why is this required?" answered by the legal basis, one click away | srtm (`base_legal`, `norma`), caja (`documento_fuente`) |
| 4 | Disabled actions say why ("no se puede porque…") from the apps' `acciones {permitida, motivo}` | next (UI pattern) | both apps already compute the reason | both |
| 5 | Banner for maintenance and important announcements | next | needs a header slot in wasichai-ui's shell | both |
| 6 | Read receipt for new regulations (`requires_ack`, who confirmed, export) | next | legal weight needs its own design | srtm (TUPA, ordinances), caja (procedures) |
| 7 | Deadline calendar from `due_at` | next | cheap once due dates exist | srtm |
| 8 | Recent items and favourites | next | fewer clicks to the records of the day | both |
| 9 | Audience from the record (`created_by`, an email field); rules on a workflow state's age | next | when a second user appears | both |
| 10 | Automation `NOTIFY` action | when an app installs automation | no user today | future apps |
| 11 | Escalate overdue ACTIONs to the unit's head | later (units v2 with a head) | needs a policy | srtm (legal deadlines), caja (unexplained payments) |
| 12 | Delegation for encargaturas and leave | later (an authorization ADR) | touches permissions | srtm |
| 13 | Daily email digest | later | no mail infrastructure in v1 | both |
| 14 | Global search, saved views per person, guided tours | later | each a design of its own | both |

## Plans

- Backend (this repository): [2026-10-06-notifications-backend.md](../plans/2026-10-06-notifications-backend.md).
- wasichai-ui: [2026-10-06-notifications-wasichai-ui.md](../plans/2026-10-06-notifications-wasichai-ui.md).
- srtm-backend and srtm-ui: [2026-10-06-notifications-srtm-adoption.md](../plans/2026-10-06-notifications-srtm-adoption.md).
- caja-backend and caja-ui: [2026-10-06-notifications-caja-adoption.md](../plans/2026-10-06-notifications-caja-adoption.md).

## Risks and open questions

- **Volume:** a per-record rule is capped; sources must aggregate. An inbox people learn to ignore is worse than none.
- **LISTEN in production:** PgBouncer in transaction mode or a buffering proxy turns live into a 60-second refresh;
  correct, slower. One extra database connection per replica.
- **Browser connections:** HTTP/1.1 allows six per origin and every browser tab opens a stream; the UI shares one
  stream between tabs (`BroadcastChannel`) or relies on HTTP/2.
- **App versions:** caja (0.2.0, caja-backend#28) and srtm (0.2.0, srtm-backend PR #90) must reach 0.3.x first; 0.4.0
  adds core migration `V9`.
- **srtm's audiences:** srtm ships no named roles; units are the natural audience. To settle in its plan.
- **A deleted unit** silently drops its targets; the admin UI should say "N notifications address this unit" first.
- **Who publishes** is coarse in v1 (`MANAGE_ORGANIZATION`).
- **Rule conditions are ANDed equalities and emptiness**; "one of" (`IN`) and "equal or empty" wait for a second
  user (srtm needs two rules for `estado` VIGENTE or empty). A value that is replaced by a new row, not edited (a fee
  renewal), needs a source, not a rule.

## As built

Details the sections above leave out, as the code has them:

- **Org-unit answers:** `409 Organizational unit '<code>' already exists`, `… has sub-units`, `… has members`;
  `400 A unit cannot move under itself`, `400 Too deep`, `400 Unknown organizational unit '<code>'` on `parentCode`,
  `400 Invalid organizational unit` with each bad field (an unknown `PUT` key: `is not a unit property`).
- **Rule answers:** `409 Notification rule '<name>' already exists`, `409 Notification rule '<name>' is disabled`,
  `404 Object '<object>' has no notification rule '<name>'`; a bad rule is `400 Invalid notification rule` naming each
  field (`stages[i].fromDays`, `conditions[i].value`, `audience[i]`, `tab`…).
- **Beans:** the date rules add `notificationRuleRepository`, `ruleNotifications` (holds the settled zone),
  `notificationRuleService`, `notificationRuleController`, `notificationRuleListener`, `notificationRuleCleanup` and
  `notificationRuleFieldUsage`, each `@ConditionalOnMissingBean`. `notificationRuleCleanup` is a bean of its own:
  `MetadataService` collects its removal listeners when it is built, and the record listener needs `MetadataService`.
- **Rule rows:** `name`, `label` and `enabled` are columns and also sit in `definition`; the columns win on read.
