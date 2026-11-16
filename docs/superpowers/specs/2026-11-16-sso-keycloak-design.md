# Single sign-on with Keycloak: design

**Status:** proposed 2026-11-16. **Scope:** wasichai (core), wasichai-ui (`@wasichai/core`), and the platform in
wasichai-infrastructure (one Keycloak client per project); caja, srtm and catastro only change configuration. **ADR:**
[ADR-084](../../adr/0084-people-sign-in-through-the-domains-identity-provider.md), which amends
[ADR-010](../../adr/0010-own-jwt.md).

## Question

caja, srtm and catastro run on one cluster, in one domain (`peru`), for the same municipalities. Each keeps its own
`wasichai.users`, its own passwords and its own login form, and signs its own HS256 tokens with its own secret. A
treasurer who works in caja and srtm has two accounts, two passwords and two sign-ins; leaving the municipality means
being disabled twice. Meanwhile the platform already runs a Keycloak realm per domain (`peru`) with an organization per
municipality, a password policy and brute-force lockout, and publishes its issuer in the contract every project reads
(`identity.issuerUrl`). No app uses it.

Can people sign in once, in Keycloak, and use every app of the domain, while each app keeps deciding what they may do?

Facts that frame the answer (wasichai at e0eb789):

- ADR-010 already promised this: moving to OIDC "swaps the decoder and the login endpoint; the permission model and
  every call site stay as they are". Core is an OAuth2 resource server already
  (`WasichaiSecurityAutoConfiguration.jwtDecoder`, HS256 secret key, no issuer or audience check).
- `CurrentUser` builds `AuthenticatedUser(userId=sub, organizationId=org, email, roles, serviceAccount)` from the
  claims `sub`, `org`, `email`, `roles`, `service_account`. Every permission check goes through
  `roleQueries.hasPermission(roles, organizationId, …)`. The tenant always comes from the token.
- A user belongs to exactly one organization (`users.organization_id NOT NULL`, `UNIQUE(organization_id, email)`).
  Roles are per organization (`roles`, `user_roles`). `password_hash` is `NOT NULL`.
- Service accounts (ADR-043) are local: `POST /api/auth/token` with client credentials, 15-minute HS256 tokens. srtm
  calls caja and catastro with them.
- Revocation, login limits and the password policy (ADR-059) all hang on local passwords and on the locally issued
  `iat`.
- The UI (`@wasichai/core`) posts email and password to `/api/auth/login`, keeps the token in `localStorage`
  (`<prefix>.token`), never refreshes it (8 h TTL) and signs out on a 401.
- Production has no users yet: `wasichai.users` and `wasichai.organizations` are empty in caja, srtm and catastro
  (2026-11-16). There is nothing to migrate if this lands before the first municipality starts.

## Decisions

### 1. Keycloak says who you are; each app says what you may do

Keycloak owns the **identity**: the account, its password and second factor, the session shared by every app of the
domain, the lockout, and which municipalities a person belongs to. Each app keeps the **authorization** exactly as
today: its `users` row in an organization, its roles, its permissions, its org units, its read scope. Being a member of
a municipality in Keycloak gives access to no app by itself.

Why not roles in Keycloak: wasichai's roles are metadata of each app and of each organization (`roles` rows, edited in
the app's admin, with object and field permissions under them). Copying them into Keycloak would give two places to
edit one thing, and a role named `ADMIN` in caja is not srtm's.

### 2. One switch, two modes

`wasichai.security.identity: local | oidc`, `local` by default. `local` is today's behaviour, unchanged: the samples,
the development environments, the integration tests and any app that is not on the platform keep it. `oidc` takes:

```yaml
wasichai:
  security:
    identity: oidc
    oidc:
      issuer-uri: https://auth.srtm.app/realms/peru   # WASICHAI_SECURITY_OIDC_ISSUER_URI
      client-id: caja                                 # WASICHAI_SECURITY_OIDC_CLIENT_ID, also the audience
```

The platform sets both (the kit, from the contract): an app changes nothing in its code.

### 3. Which tokens a backend accepts

In `oidc` mode the decoder accepts two issuers, chosen by the `iss` claim before any signature is checked:

| `iss` | Who | Checked with |
| --- | --- | --- |
| the realm (`issuer-uri`) | people | RS256 against the realm's JWKS (cached, rotated by Keycloak), `aud` contains `client-id`, timestamps |
| `wasichai.security.jwt.issuer` | this app's service accounts | HS256 with `WASICHAI_JWT_SECRET`, as today (ADR-043, ADR-059) |

Anything else is a 401. The audience check is what keeps a token caja got from working against srtm: each app is its
own client, and the browser holds one token per app.

The people's tokens are short (the realm gives 5 minutes); the UI refreshes them (decision 6).

### 4. From a Keycloak token to an `AuthenticatedUser`

`CurrentUser` keeps its shape; only where the fields come from changes, in a `ReactiveJwtAuthenticationConverter` of
`oidc` mode:

- **Organization.** Keycloak 26 puts the person's organization in the `organization` claim when the client asks for
  the `organization` scope. The UI asks for `organization:<alias>` when it knows the municipality and `organization`
  otherwise, so Keycloak lets a person who belongs to several pick one: **a token carries exactly one**. The alias is
  the wasichai organization's `slug`: `perene` in Keycloak is the `organizations` row with `slug = 'perene'`. No
  organization, several, or an alias the app does not have: 403 with a body that says which.
- **User.** The row of that organization linked to the token's `sub` (new column `users.external_subject`,
  `UNIQUE(organization_id, external_subject)`). No row: decision 5. A disabled row: 403.
- **Roles.** Read from `user_roles` for that row, not from the token, through a cache of a few seconds keyed by user.
  A role removed in the app takes effect within the cache's life; disabling a person in Keycloak, within the token's
  5 minutes, and at once for new sign-ins.
- `email` and the display name come from the token (`email`, `name`) and update the row when they change, so the
  admin screens show what Keycloak knows.

`userId` stays the wasichai user's id, not the `sub`: every `created_by`, audit row, preference and notification keeps
pointing at `users.id`.

### 5. Who may enter an app: invited by email, linked on first sign-in

An administrator of the app creates the user as today (`POST /api/users`, roles included), **without a password** in
`oidc` mode. The first time a person of that organization signs in with that email, and Keycloak says it is verified
(`email_verified`), the row gets the `sub` and stays linked: later changes of email in Keycloak follow the `sub`, not
the address. A person signed in to Keycloak but not invited to this app sees "you have no access to <app>; ask its
administrator" (403), not an empty app.

Why not create the row on first sign-in (just in time): a row with no roles is an account that can see nothing, and
every app would fill with people who only clicked a link. Inviting keeps today's admin flow and today's meaning of "a
user of caja".

Organization provisioning (ADR-055) changes the same way: `POST /api/organizations` takes the admin's email and no
password; that person links on their first sign-in. The organization's slug must be the alias of a Keycloak
organization (the platform creates those per tenant, from the domain stack's `tenants`).

### 6. The UI signs in through Keycloak

`@wasichai/core` asks `GET /api/auth/config` (public) what to do: `{ mode: "local" }` keeps the login page, or
`{ mode: "oidc", issuer, clientId }` turns sign-in into the Authorization Code flow with PKCE, through `oidc-client-ts`:

- the login route redirects to Keycloak and comes back to `/auth/callback`; `AuthGate` keeps its `returnTo`;
- the access token lives in memory and the refresh token in `sessionStorage` (never `localStorage`): a refresh before
  `exp`, and on a 401 one refresh before signing out;
- signing out ends the Keycloak session (RP-initiated logout), so it ends in every app of the domain;
- `getToken` keeps its signature: the API client, the SSE stream (it already reconnects at `exp`, ADR-047) and the
  WMS tiles of `@wasichai/gis` get the current token without knowing where it came from.

The realm refreshes once per refresh token (`revokeRefreshToken`, reuse 0) and idles out after 30 minutes, ten hours
at most: those are the platform's settings, not the app's.

### 7. What stays local in `oidc` mode

- **Service accounts** (ADR-043), as they are: srtm keeps calling caja and catastro with client credentials of the
  app it calls. Moving them to Keycloak clients would need the Keycloak organization of a service account and a role
  mapping per app, for no new capability today. It can be decided later on its own.
- **Revocation, login limits and the password policy** (ADR-059) keep applying to service accounts. For people they are
  Keycloak's: its brute-force detection, its password policy, its sessions. `POST /api/auth/login`, password change
  and `POST /api/auth/logout` answer 404 in `oidc` mode for people (ADR-031 D1: a route that is not there is a 404).
- **The dev seed** (ADR-031 D6) is a `local`-mode thing: with `oidc` it refuses to start rather than create
  `admin@wasichai.local` with a known password.

## What changes where

- **wasichai**: the `identity` switch and the `oidc` properties; the two-issuer decoder; the converter of decision 4;
  one migration (`users.external_subject`, `password_hash` nullable); invite and link (decision 5);
  `GET /api/auth/config`; in `oidc`, `/api/auth/login`, password change and logout answer 404 for people; the seed
  refuses `oidc`; `docs/security/authentication.md` and ADR-084.
- **wasichai-ui**: in `@wasichai/core`, the `oidc` mode of `AuthProvider` with `oidc-client-ts`, `/auth/callback`,
  refresh and RP-initiated logout; the admin's user form without a password in `oidc`.
- **wasichai-infrastructure**: the domain stack creates one Keycloak client per project (public, PKCE S256, redirect
  `https://<host>/*`, an audience mapper with its own client id, the `organization` scope); the contract carries each
  project's `clientId`; the kit sets `WASICHAI_SECURITY_IDENTITY=oidc`, the issuer and the client id.
- **caja, srtm, catastro**: the wasichai and kit versions that bring it; nothing in their code.

## Risks and costs

- **Sign-in depends on Keycloak.** If it is down nobody signs in to any app; tokens already issued work until they
  expire (5 minutes), so a Keycloak restart is felt. It runs on the same node as the engine, as the database already
  does.
- **The JWKS fetch.** A backend that starts while Keycloak is down cannot check people's tokens until it reaches it;
  service-account tokens still work. The decoder retries; health does not depend on it.
- **Tokens of 5 minutes** mean a refresh every few minutes per open tab. Cheap, but a UI bug there signs people out:
  the UI plan needs tests for refresh and for the 401-then-refresh path.
- **`users.findByEmail`** runs across every organization with `.one()`: the same email in two organizations makes it
  fail. In `oidc` mode lookups go by `(organization_id, external_subject)` or `(organization_id, email)`; the local
  login keeps its query, and the bug is worth its own fix.

## Not in this design

- Roles or permissions in Keycloak; groups mapped to roles.
- Service accounts as Keycloak clients.
- Identity brokering (signing in with a government or Google account): Keycloak can add it later without any change
  here.
- Several organizations in one session of one app (switching municipality without signing in again).

## Plans

Three, in order (backend first, ADR-032): wasichai (this design's backend), wasichai-ui, then the platform. caja, srtm
and catastro move when all three are released, before their first real users.
