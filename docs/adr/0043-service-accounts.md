# ADR-043: Service accounts sign in with client credentials, as a user row that is not a person

**Status**: accepted · 2026-10-02

## Context

The only way into the API is `POST /api/auth/login`: an email and a password for an HS256 JWT that lives 8 hours.
Payment orders reach caja from other systems, server to server, and caja-backend gives each of them a user with role
`SISTEMA_ORIGEN` whose password lives in that system's configuration. The system identity is not bound to the
principal: any `SISTEMA_ORIGEN` user can create orders under another system's key, because the key is a field of the
request (issue #17).

What such a caller needs is its own identity in one organization, roles like anyone else, a credential the server
generates and can revoke, and a name the app can read off the principal.

## Decision

**A service account per organization: a name, roles, an enabled flag, and a client id with a secret.** Core migration
`V8__service_accounts` adds `service_accounts (id, organization_id, name, secret_hash, enabled, created_at,
secret_rotated_at)`. The name is lower case, `^[a-z][a-z0-9_-]{1,48}$` (a `CHECK` too), unique in the tenant, and does
not change: it is what apps bind to. The client id is the account's id, a UUID, so it is unique across tenants and the
token endpoint can look it up before it knows a tenant.

**Backed by a user row with the same id.** The token's `sub` must be something every consumer of
`AuthenticatedUser.userId` accepts. Most of them store it without a foreign key (`created_by`, `updated_by`,
`audit_log.user_id`), but not all: `user_roles`, `user_preferences`, `automation_runs.user_id` and
`documents.issued_by` reference `users (id)`. A separate id space would make a service account's write that queues an
automation, or issues a document, fail on a foreign key, and would need a second roles table. So creating an account
also inserts a `users` row with its id, `display_name` = its name, `email` = `<id>@service-accounts.invalid` (a reserved
TLD, unique because of the id), `enabled = false` and a password hash of a random value nobody is told.
`service_accounts.id` references that row `ON DELETE CASCADE`, roles live in `user_roles` as anyone's, and
`RoleQueries.roleNamesOf` needs no change. The row is no person, and three things keep it from becoming one:
it is disabled and its password unknown, so login refuses it; `AdminService` leaves it out of `GET /api/users` and
answers `404` to `PUT /api/users/{id}`, `PUT /api/users/{id}/roles` and `DELETE /api/users/{id}`, so no administrator
can set a password on it or re-enable it; and the service-account routes are the only ones that touch it.

**The secret is generated, shown once, and stored as a hash.** 32 bytes from `SecureRandom`, base64url without
padding (43 characters), hashed with the application's `PasswordEncoder`, the one passwords use (BCrypt by default).
It is in the answer to create and to rotate, and nowhere else; no endpoint returns the hash.

**Administration.** `GET/POST /api/service-accounts`, `GET/PUT/DELETE /api/service-accounts/{id}` (`PUT` sets
`enabled` and replaces `roles`) and `POST /api/service-accounts/{id}/secret` to rotate. All need `MANAGE_ORGANIZATION`,
like users and roles. Every route first resolves the account within the caller's tenant (another tenant's account is
`404`), and every statement on `service_accounts` and `users` also filters by that tenant. The `user_roles` rows are
then replaced by the account's id alone, after that check, and a role name resolves only among the caller's roles.
Delete removes the backing user, which cascades to the account and its roles.

**The token endpoint.** `POST /api/auth/token` with `{ "clientId", "clientSecret" }`, public like login. JSON rather than
the form-encoded OAuth2 `grant_type=client_credentials` request, to match `/api/auth/login`; a standards client is not
a user yet. A known, enabled account whose secret matches gets a JWT with the same claims as a person's (`sub` its id,
`org`, `email` its backing address, `roles`) plus `service_account: "<name>"`, signed with the same key, for
`wasichai.security.jwt.service-account-ttl`, 15 minutes by default. Every refusal, an unknown or malformed id, a wrong
secret, a disabled account, is the same `401 Invalid client credentials`, and every request runs exactly one hash check,
against a decoy hash when the id is unknown, so neither the answer nor the time it takes separates an unknown id from a
wrong secret. The decoy is hashed when the bean is built, so even the first unknown id pays no extra cost. The
comparison is the encoder's own (constant time for BCrypt), and it runs on `Dispatchers.Default`, off the Netty event
loop: a public endpoint that hashed on the event loop would let anyone stall every request with a burst of calls.
Login's password check moves off the event loop the same way. An encoder that throws on input it cannot
hash (BCrypt past 72 bytes) counts as a mismatch, not a `500`. A malformed id skips the lookup, which tells a caller
only that its id is not a UUID.

**The principal names the account.** `AuthenticatedUser` gains `serviceAccount: String?`, last and defaulted, so
existing constructor calls compile unchanged. `CurrentUser` reads it from the `service_account` claim; it is `null` for
a person. An app binds its own notion of the caller to it: caja's order origin is `user.serviceAccount`, so one system
can no longer create orders under another's key.

**A service account never administers the tenant.** `ADMIN` is refused when assigning roles (`400`). Beyond that,
`AuthenticatedUser.isAdmin` is `false` whenever `serviceAccount` is set, and `CurrentUser.requirePermission` refuses a
service account every `MANAGE_ORGANIZATION` check, even when one of its roles grants it. Refusing `ADMIN` alone would not
be enough: any role can be granted `MANAGE_ORGANIZATION`, and the role's grants can change after the assignment. The
reason is blast radius. A service account's secret lives in another system's configuration, the place it is most likely
to leak from; it should reach the data its roles allow and nothing that creates users, roles, tenants or more accounts,
which would let a leak outlive revoking the account. `MANAGE_METADATA` and declared actions (ADR-042) stay grantable: a
system that defines objects is a real use, and both are scoped to data. Its roles also give it declared actions with no
extra step.

**Revocation stops new tokens, not issued ones.** Disabling, rotating or deleting takes effect on the next
`POST /api/auth/token`. A token already issued is a stateless JWT and stays valid until it expires; nothing checks the
account on each request, as nothing checks a person's account today. The short TTL bounds that window to 15 minutes by
default, and an app that needs a smaller one lowers the property. A token of a deleted account that is still live can
still write; a write that reaches a foreign key to `users` (an automation run, say) then fails, which is the right
outcome for a revoked caller.

**Audit identifies the account through `audit_log.user_id`.** That column holds the token's `sub`, which is the
account's id and its backing user's id, so no column is added. `GET /api/audit` and a record's history already join
`users` for `userEmail` (the backing address); they now also join `service_accounts` and answer `serviceAccount`, its
name, on the entries it made. Deleting an account deletes its backing user, so its old entries lose both, as a deleted
person's lose `userEmail`; an administrator who wants the trail readable disables the account instead.

## Consequences

- caja gives each origin system a service account and checks the order's origin against `user.serviceAccount`; the
  `SISTEMA_ORIGEN` users and their passwords go.
- The REST change is additive: new routes, a new public path, and a `serviceAccount` field on `GET /api/auth/me` and on
  audit entries that is present only for a service account (`NON_NULL`). A person's login, token and every existing
  answer are byte for byte unchanged. `GET /api/users` lists the same
  people; the rows it leaves out are only ever created by this feature.
- The new table is a schema difference from the original, so it is ADR-031 D27, and `SchemaParityTest` lists its
  lines as known deviations under that entry.
- Not handled here: rate limiting the public token endpoint. Guessing a 256-bit secret is hopeless, but every call costs
  a hash check, so an exposed app puts rate limiting in front (a gateway or proxy), as it should for login. Also not
  done, for want of a user: renaming an account, several live secrets per account for overlap during rotation, the
  OAuth2 form-encoded request, and a revocation list that would kill live tokens. Each is additive.
- wasichai-ui has no screen for service accounts yet; they are administered through the API.
