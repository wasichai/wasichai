# ADR-059: Token revocation by a per-user marker, sign-in attempt limits and a password policy

**Status**: accepted · 2026-10-08 · builds on [ADR-010](0010-own-jwt.md), [ADR-043](0043-service-accounts.md)
and [ADR-049](0049-admin-changes-in-the-audit-log.md)

## Context

Tokens are stateless HS256 JWTs checked by signature and `exp` only. After an administrator disables a user, changes
their roles or password or deletes them, the token they hold keeps working with the old roles for up to 8 hours;
there is no logout. `POST /api/auth/login` and `POST /api/auth/token` are public, cost a BCrypt check each, and count
nothing. The only password rule is 8 characters. An app that handles sensitive personal data needs a stolen token to
be killable, a disabled user to stop at once, and guessing to be throttled even without a smart proxy
([#55](https://github.com/wasichai/wasichai/issues/55)).

## Decision

Three independent parts, each off or neutral by default (0.x compatibility), recommended before production.

**1. Revocation by a "valid after" marker** (`wasichai.security.jwt.revocation`, default `false`).

- Core `V17` adds `users.tokens_valid_after timestamptz` (null: nothing revoked). A service account's marker is its
  backing user's (ADR-043).
- `TokenRevocation.revoke` moves it on every change that must sign someone out: an administrator disabling a user,
  setting a password, replacing roles; disabling a service account, replacing its roles, rotating its secret; and
  `POST /api/auth/logout` for the caller. Deleting a user or account removes the row, which refuses its tokens.
  Renaming, re-enabling and org units do not move it.
- `RevocationCheckingJwtDecoder` wraps the decoder when the switch is on and refuses (`BadJwtException`, so `401
  invalid_token`) a token whose `iat` is before the marker, or whose user is not in the token's tenant any more.
- **Precision.** `iat` is whole seconds. The marker is the next whole second after the change, by the app's clock
  (not the database's: skew between them would let old tokens through). Every token issued until the change, in its
  second too, is before it. A login or token request in that second gets `iat` = the marker (it reads the marker with
  the credentials), so a token issued after the change works at once. A second change while the marker is already at
  or past the next second moves it one more second, so a token issued between two changes in one second goes as well.
- **Cache.** A per-user entry (marker, or "gone") lives `wasichai.security.jwt.revocation-cache` (default `5s`): one
  indexed read per user per window, none on a hit. The node that made the change stores the new marker in its cache at
  once; other nodes see it within the window, the stated maximum delay. A rolled-back change leaves its marker cached
  until expiry: refused early, never accepted late.
- The marker is **written whatever the switch**: logout answers `204` always, so a client calls it unconditionally,
  and a missing route (`404`) would read as a missing module (ADR-031 D1). The write is one `UPDATE` by primary key.
- Every token gets a **`jti`** (random UUID): it names a token for logs or an app's own deny list. Revocation does not
  use it: a deny list per token would be a table and a lookup per request for what one marker per user does.

**2. Sign-in attempt limits** (`wasichai.security.login.enabled`, default `false`; `max-attempts` 5,
`account-max-attempts` 20, `window` 15m).

- `LoginThrottle` counts each attempt *before* checking credentials, in a fixed window opened by the first, under
  `login:<email>|<address>` (limit `max-attempts`), `account:<email>` (limit `account-max-attempts`) and, for
  `/api/auth/token`, `token:<client id>` (limit `max-attempts`). Over a limit: `429` with `Retry-After` for the rest of
  the window, nothing checked, so a right password is refused too and a known email answers as an unknown one. The
  attempt that reaches a limit and fails is a `429` as well. A success resets the keys it touched.
- Two keys for login, where the issue named both with one limit: with one limit the pair key would add nothing. One
  client is locked out of one account after 5; guessing spread across addresses stops at 20 per account and window,
  which also locks the account for its owner until the window closes. That is the accepted price.
- The client address is `ServerHttpRequest.remoteAddress`. Behind a proxy it is the client's only when the app sets
  `server.forward-headers-strategy`; a raw `X-Forwarded-For` is never read, any client can forge one.
- `LoginAttemptStore` (`increment(key, window)`, `reset(key)`) is the SPI, `@ConditionalOnMissingBean`. The default
  `InMemoryLoginAttemptStore` is one node's view, bounded at 100,000 keys (least recently counted dropped). A cluster
  backs it with PostgreSQL or Redis.
- `RetryLaterException` takes a status, so the `429` reuses its `Retry-After` handling.

**3. Password policy.** `fun interface PasswordPolicy { fun check(password: String, user: UserInfo): List<String> }`,
`@ConditionalOnMissingBean`; the default `ConfiguredPasswordPolicy` reads `wasichai.security.password.*`
(`min-length` 8, `require-uppercase`, `require-lowercase`, `require-digit`, `require-symbol`, `not-equal-email`).
Enforced on user create, password change and provisioning, as a `400` with one `errors` entry per broken rule on the
request's field (`password`; `adminPassword` for provisioning, the name it always had). A lone length failure keeps
the detail `Password too short`; anything else says `Password does not meet the password policy`. Service account
secrets are generated, so not judged. Expiry is out of scope.

Admin audit rows (ADR-049) are unchanged: the marker is not part of a user's snapshot, and logout is not an admin act.

## Consequences

- With no property set, every answer is as before, except the `jti` claim and `POST /api/auth/logout` (`204`, no
  effect), ADR-031 D44. The marker column is a known schema-parity deviation.
- `AdminService` and `OrganizationService` take a `PasswordPolicy`, `AdminService` and `ServiceAccountService` a
  `TokenRevocation`, `AuthService` a `LoginThrottle` and a `TokenRevocation`, `ServiceAccountTokenService` a
  `LoginThrottle` (new last constructor arguments). `AuthService.login` takes an optional client address.
- An administrator who changes their own password or roles signs themselves out (with revocation on).
- Login for an unknown email still skips the hash check, so its timing differs from a known one's (unchanged here);
  the throttle's answers do not.
- Tested by `LoginThrottleTest`, `PasswordPolicyTest`, `JwtServiceTest`, `WasichaiAutoConfigurationTest` and the
  integration tests `TokenRevocationApiTest`, `LoginThrottleApiTest`, `PasswordPolicyApiTest`, `AuthDefaultsApiTest`.
