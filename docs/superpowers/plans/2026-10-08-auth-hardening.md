# Authentication hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Token revocation, login attempt limits and a password policy
([#55](https://github.com/wasichai/wasichai/issues/55)), three independent parts, each off or neutral by default.

**Architecture:** Revocation: a `jti` claim, `users.tokens_valid_after` (core `V17`), moved by `TokenRevocation` to the
next whole second on every change that must sign a user out; `RevocationCheckingJwtDecoder` wraps the decoder when
`wasichai.security.jwt.revocation=true` and rejects a token whose `iat` is before it, through a per-user cache of
`revocation-cache`. A login right after a change gets `iat` = the marker, so it works at once. Throttling:
`LoginThrottle` counts attempts in a `LoginAttemptStore` (in-memory default) per (email, client address), per email and
per client id; over the limit a `429` with `Retry-After`. Password policy: `PasswordPolicy` SPI, default
`ConfiguredPasswordPolicy` from `wasichai.security.password.*`, enforced on user create, password change and
provisioning. Decision: ADR-059.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 WebFlux, Spring Security resource server, R2DBC `DatabaseClient`, JUnit 5 +
AssertJ.

## Global Constraints

- No property set: same answers as before (the new `jti` claim and `POST /api/auth/logout` aside, ADR-031 D44).
- The client address is the request's remote address; forwarded headers count only through Spring's own handling.
- The marker query is tenant-filtered; values bound.

## Task 1: Revocation

- [ ] `V17__tokens_valid_after.sql`, `User.tokensValidAfter`, `JwtService` `jti` and `iat` clamp.
- [ ] `TokenRevocation`, `RevocationCheckingJwtDecoder`, `JwtProperties.revocation`, `revocationCache`.
- [ ] Marker moves in `AdminService` (disable, password, roles, delete), `ServiceAccountService` (disable, roles,
  rotate, delete) and `POST /api/auth/logout`.

## Task 2: Throttling

- [ ] `LoginAttemptStore`, `InMemoryLoginAttemptStore`, `LoginThrottle`, `WasichaiLoginProperties`; `RetryLaterException`
  takes a status; `AuthService` and `ServiceAccountTokenService` guarded; client address from the controller.

## Task 3: Password policy

- [ ] `PasswordPolicy`, `UserInfo`, `ConfiguredPasswordPolicy`, `WasichaiPasswordProperties`; `AdminService`,
  `OrganizationService` call it.

## Task 4: Tests and docs

- [ ] Unit: `TokenRevocationTest`, `LoginThrottleTest`, `InMemoryLoginAttemptStoreTest`, `ConfiguredPasswordPolicyTest`,
  `JwtServiceTest`, `WasichaiAutoConfigurationTest`.
- [ ] Integration: `TokenRevocationApiTest`, `LoginThrottleApiTest`, `PasswordPolicyApiTest`, `AuthDefaultsApiTest`;
  schema parity deviation for the column.
- [ ] ADR-059, ADR-031 D44, HISTORY, authentication.md, rest.md, core.md, build-your-app.md.
