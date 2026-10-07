# Security

## Authentication

`POST /api/auth/login` checks a BCrypt hash and issues an HS256 JWT:

```
sub    user id
org    organization id  (the tenant)
email  the user's email
roles  ["ADMIN", …]
iss    wasichai.security.jwt.issuer (`wasichai` by default)
exp    now + wasichai.security.jwt.ttl (8h by default)
```

The backend is a WebFlux OAuth2 resource server validating that token with a symmetric key. No
default secret ships: an app must set `wasichai.security.jwt.secret` (env `WASICHAI_JWT_SECRET`), at
least 32 bytes, or the application refuses to start with
`wasichai.security.jwt.secret must be at least 32 bytes (env WASICHAI_JWT_SECRET)`.
`wasichai.security.jwt.issuer` defaults to `wasichai`, and `wasichai.security.jwt.ttl` to 8 hours.

The signing key is a `WasichaiJwtKey` bean, which wraps the `SecretKey` in its own type so that an
app's unrelated `SecretKey` bean (some other encryption key, say) can never become the JWT key by
accident. An app that wants a different key declares its own `WasichaiJwtKey` bean.

Moving to OIDC later replaces the decoder and the login endpoint; the permission model does not move.

## Service accounts

A system that calls the API from a server (caja's origin systems posting payment orders, say) is a service account of
one organization, not a person with a shared password ([ADR-043](../adr/0043-service-accounts.md)). An administrator
creates it at `/api/service-accounts` with a name and roles; the server generates its secret (256 bits from
`SecureRandom`), shows it once and keeps only its hash, made by the same `PasswordEncoder` as passwords. Rotating
replaces it the same way. The caller exchanges client id and secret for a token at `POST /api/auth/token`:

```
sub              the account id (= its client id)
org              organization id
email            <id>@service-accounts.invalid, its backing user's address
roles            the account's roles
service_account  the account's name, e.g. rentas
exp              now + wasichai.security.jwt.service-account-ttl (15 min by default)
```

`CurrentUser` reads `service_account` into `AuthenticatedUser.serviceAccount` (`null` for a person), so an app binds
its own notion of the caller to the principal: the origin system of an order is `user.serviceAccount`, not a field of
the request a caller could fill with another system's key.

- **One refusal.** An unknown or malformed id, a wrong secret and a disabled account all get `401 Invalid client
  credentials`, and every request runs one hash check, against a decoy hash when the id is unknown, so the timing does
  not tell them apart either. The decoy is hashed at startup. The hash comparison is the encoder's (BCrypt's is
  constant time), and it runs off the Netty event loop, as login's password check does.
- **Revocation is not instant for tokens already issued.** Disabling, rotating or deleting stops new tokens at once;
  a token already issued is a stateless JWT and lives until it expires. That is why its TTL is short.
- **Never the administrator.** `ADMIN` cannot be given to a service account, `isAdmin` is `false` for one whatever its
  token says, and `CurrentUser` refuses it every `MANAGE_ORGANIZATION` check, whatever its roles grant. A leaked
  secret can do what the account's roles allow on data, and cannot create users, roles, tenants or more accounts.
- **Backed by a user row.** The account's id is also a row of `users`, so every foreign key to `users` (roles, the
  audit log's user, automation runs, issued documents, preferences) takes the token's subject unchanged, and audit
  identifies the account through it. That row is disabled, has a random password nobody is told, is hidden from
  `/api/users` and is `404` to the user routes, so it can never be signed into or re-enabled as a person.
- **Abuse of the public endpoint is not handled here.** `POST /api/auth/token` needs no token, and each call costs a
  hash check. Guessing a 256-bit secret is hopeless, but an app exposed to the internet puts rate limiting in front of
  it (a gateway or proxy), as it would for `/api/auth/login`.

## Tenancy

`CurrentUser` resolves the organization from the token, never from the request. Every query filters
by `organization_id`, and physical tables are per (organization, object). A token from one
organization cannot name a table in another: object lookup is scoped before any SQL is built.

Background code calls `RecordService.asPlatform(organizationId) { }`: one organization, no user, no permission check
([ADR-039](../adr/0039-background-work-runs-as-the-platform-with-a-cluster-lock.md)). Nothing a request carries turns
it on, and it refuses to run inside a request, authenticated or anonymous: it throws when Spring Security's context is
present.

## Authorization

Permissions are `(role, object, action)` with actions `READ`, `CREATE`, `UPDATE`, `DELETE` and
`MANAGE_METADATA`. `object_id NULL` means "every object in the organization". An object may also declare actions of
its own, such as `ANULAR_AJENO` on `recibo`: they are granted on that object only, checked with
`CurrentUser.requirePermission(user, "ANULAR_AJENO", objectId)`, and their grants go when the declaration does
([ADR-042](../adr/0042-app-declared-actions.md)). `ADMIN` short-circuits the check, declared actions included.
Field- and record-level permissions are enforced: `own_records_only` limits a role to the records it created (a
caller with several roles is restricted only if every one of them sets it), and field access hides unreadable fields
from responses and refuses writes to unwritable ones. `GET /api/auth/me/permissions` tells the caller what they may do
(ADR-020).

A write may only point at what the caller can read. A `RELATION` value sent by a person or a service account must name
a record of the target object the caller holds `READ` on, created by them when they are own-records-only; anything
else gets the answer for a missing record (`400` on the field), so it tells nothing about whether the record exists.
The check is one read per target object with the role and owner rules folded in, the same rules record reads apply.
`ADMIN`, the platform and automations check the organization only. A value an update leaves as stored is not checked
again (ADR-031 D30).

An app can narrow reads further, by project, territory or region, with a `RecordReadScope` bean
([ADR-048](../adr/0048-a-read-scope-narrows-what-a-caller-reads.md)). Its criterion is ANDed, in parentheses, behind
the tenant and owner filters into every read of the object: lists and counts, reads by id, related records on both
sides, history, `/api/audit`, the `RELATION` check above, and the lookups before an update, delete, link or workflow
transition, so GIS features and the assistant's tools follow it too. A record out of scope answers as a missing one,
`404`, never `403`. It is asked for people and service accounts, never for `ADMIN`, the platform or automations.
Without such a bean nothing changes.

## Organizational units

An organization's units (gerencia › subgerencia › área) say where a person sits, not what they may do
([ADR-045](../adr/0045-organizational-units.md), ADR-031 D31). **Membership grants nothing**: no permission check
reads it, and there are no rights scoped to a unit. It only addresses people, such as a notification sent to a unit,
which reaches its whole subtree.

Units are **not in the token**. Membership changes more often than an 8-hour token lives, so it is read when needed
(`OrgUnitDirectory`), and a change applies on the next read, without signing in again. `GET /api/auth/me` does not
carry them either.

Units and memberships are administered under `MANAGE_ORGANIZATION`: `/api/org-units` and
`PUT /api/users/{id}/org-units`, the caller's own tenant only (another tenant's unit is `404`). A service account is
refused (`403`) there whatever its roles grant, as on every `MANAGE_ORGANIZATION` route, and its backing user cannot
be put in a unit (`404`). Any signed-in caller reads their own units, with their paths, at
`GET /api/auth/me/org-units`; a service account sits in none and reads `[]`. See
[../api/rest.md#organizational-units](../api/rest.md#organizational-units).

## Long-lived streams

With wasichai-notifications, `GET /api/auth/me/notifications/stream` keeps its answer open, sending the caller's
notification summary as it changes ([ADR-047](../adr/0047-server-push-over-sse-and-listen-notify.md)). It is
authenticated like every route, and a few rules keep a connection that lives for hours from outliving its token:

- **Bearer header only.** The token travels in `Authorization: Bearer …`, never in the URL, where access logs, proxies
  and browser history would keep it. `?access_token=` is not read: without the header the stream is `401`. That is
  why the UI uses a `fetch`-based SSE client rather than `EventSource`, which cannot send a header.
- **Refused before it starts.** The caller is resolved before the first event, so a missing or invalid token is a
  plain `401` and a service account a plain `403`, not a broken stream. A service account is not a person: every route
  under `/api/auth/me/notifications/**` answers it `403`, and it is never a recipient by email.
- **It ends with the token.** The stream completes at the token's `exp`. The UI reconnects with the token it holds
  then, or signs out on the `401` (ADR-031 D11). Roles are the token's for the stream's life; units are re-read on every
  summary, as on every inbox read.
- **The same checks as the inbox.** The stream sends what `GET /api/auth/me/notifications/summary` answers, with the
  same visibility and the same `RECORD` link filtering; the UI fetches the notifications themselves through the usual
  routes. What wakes a stream is a `pg_notify` payload of ids only, matched against the caller's organization and
  user; the summary is always recomputed from the database, so a payload anyone sends on the channel can cause a
  recompute and nothing else.

Who publishes is coarse in v1: a manual notification needs `MANAGE_ORGANIZATION` (so never a service account), a date
rule `MANAGE_METADATA` on its object, and app code publishes as it sees fit through the `Notifications` bean. See
[../modules/notifications.md](../modules/notifications.md).

## The security chain

Core declares one `SecurityWebFilterChain` at `@Order(0)`. Spring Boot's reactive resource-server auto-configuration
always adds a chain of its own, whatever beans exist, so wasichai's must win on order rather than by being the only
one. Its public paths are `/api/auth/login`, `/api/auth/token`, `/api/health`, `/actuator/health/**` and every `OPTIONS`
request.
Everything else needs a valid token. Core's chain is `@ConditionalOnMissingBean`: an app that declares its own
`SecurityWebFilterChain` bean replaces core's chain entirely, public paths and CORS included. A module that needs
a chain next to core's declares it in an auto-configuration that runs after core's, with its own
`securityMatcher` and an `@Order` below `0`. See the [core module](../modules/core.md#security).

## In the browser

The frontend keeps the token in `localStorage` under the configured `storagePrefix`. A `401` on any
API call in the middle of a session signs the user out and returns them to the login page, instead
of only dropping the token and leaving a signed-in-looking user whose every call fails (ADR-031
D11). A `404` on a module route means the module is not installed (ADR-031 D1).

## Runtime DDL safety

Wasichai runs DDL from user-supplied metadata, so:

- technical names must match `^[a-z][a-z0-9_]{0,48}$` (objects: 39 characters, so the generated table
  name still fits), and are rejected if they are SQL keywords or platform column names
- identifiers are quoted in exactly one place, `SqlIdentifier`
- values are always bound parameters — the only literals ever interpolated are enum options, which
  are pattern-checked and quote-escaped, and the SRID, which must be a positive integer
- DDL is only ever issued by `ObjectSchemaManager`

## Development seed

`admin@wasichai.local` / `admin`, hashed through pgcrypto at migration time so no hash is committed.
It exists only with `wasichai.seed.dev=true` (ADR-031 D6); remove or leave that flag unset anywhere else.
