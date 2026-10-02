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
`MANAGE_METADATA`. `object_id NULL` means "every object in the organization". `ADMIN` short-circuits
the check. Field- and record-level permissions are enforced: `own_records_only` limits a role to the
records it created (a caller with several roles is restricted only if every one of them sets it), and
field access hides unreadable fields from responses and refuses writes to unwritable ones.
`GET /api/auth/me/permissions` tells the caller what they may do (ADR-020).

## The security chain

Core declares one `SecurityWebFilterChain` at `@Order(0)`. Spring Boot's reactive resource-server auto-configuration
always adds a chain of its own, whatever beans exist, so wasichai's must win on order rather than by being the only
one. Its public paths are `/api/auth/login`, `/api/health`, `/actuator/health/**` and every `OPTIONS` request.
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
