# ADR-084: People sign in through the domain's identity provider; each app keeps their permissions

**Status**: proposed · 2026-11-16 · amends [ADR-010](0010-own-jwt.md)

## Context

ADR-010 chose an own HS256 JWT for the MVP and left the door open to OIDC: "swaps the decoder and the login endpoint;
the permission model and every call site stay as they are". The apps on the platform (caja, srtm, catastro) now share a
cluster, a domain and its municipalities, and the platform runs a Keycloak realm per domain with an organization per
municipality. Each app still keeps its own accounts and passwords, so one person has one account per app. Production
has no users yet. The design is in
[2026-11-16-sso-keycloak-design.md](../superpowers/specs/2026-11-16-sso-keycloak-design.md).

## Decision

- `wasichai.security.identity` picks `local` (default, today's behaviour) or `oidc`.
- In `oidc` mode people sign in through the domain's realm (Authorization Code with PKCE, in `@wasichai/core`). A
  backend accepts the realm's RS256 tokens whose audience is its own client, and its own service accounts' HS256
  tokens (ADR-043), told apart by `iss`.
- **Keycloak says who a person is; the app says what they may do.** The token's `organization` (exactly one, its alias
  is the wasichai organization's `slug`) and `sub` find the app's `users` row (`external_subject`); its roles come from
  the app's database, not the token. `AuthenticatedUser` and every permission check stay as they are.
- A person enters an app only if its administrator invited them by email; the first sign-in with that verified email
  links the row to the `sub`. No row is created just because someone signed in.
- In `oidc` mode the local login, password change and logout answer 404 for people, and the dev seed refuses to start.
  Revocation, login limits and the password policy (ADR-059) keep applying to service accounts; for people they are
  the realm's.

## Consequences

- One account, one sign-in and one sign-out for every app of a domain; leaving a municipality is one change in
  Keycloak.
- Roles, permissions, org units and read scope are untouched, and so is everything that points at `users.id`.
- Signing in depends on Keycloak being up; tokens live 5 minutes and the UI refreshes them.
- `local` mode is unchanged, so samples, development and tests do not need a Keycloak.
- `users.password_hash` becomes nullable; `users.external_subject` is new.
