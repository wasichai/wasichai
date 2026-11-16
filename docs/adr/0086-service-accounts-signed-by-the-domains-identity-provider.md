# ADR-086: Service accounts signed by the domain's identity provider

**Status**: accepted · 2026-11-16 · amends [ADR-043](0043-service-accounts.md) and
[ADR-084](0084-people-sign-in-through-the-domains-identity-provider.md)

## Context

srtm calls catastro and caja, caja calls srtm, and bulk loading writes to all three, each with a service account of
the app being called (ADR-043) whose secret is copied by hand into the caller's configuration. In production none is
configured, so the integrations are off, and a new tenant needs every account made again by an administrator. ADR-084
moved people to the domain's Keycloak realm and left service accounts local, as something to decide on its own. The
platform can create, per tenant and integration, a confidential realm client with a fixed `organization` claim and the
called app as audience. Verified on Keycloak 26.8.2: its client-credentials token carries `organization: ['muni-ica']`,
`aud` = the called app, `azp` = the client id, a stable `sub`, `preferred_username: service-account-<client>` and no
`email`. The design is in
[2026-11-16-declared-roles-and-realm-service-accounts-design.md](../superpowers/specs/2026-11-16-declared-roles-and-realm-service-accounts-design.md)
(decisions B).

## Decision

- **In `oidc` mode, a realm client can be a service account of the app.** A realm token (RS256, `iss`, `exp`, `aud`
  holding the app's client id, as ADR-084) with no `email`, an `azp`, and `preferred_username` =
  `service-account-<azp>` is a service account's; any other goes the person's way.
- **The app lists the clients it accepts:** an indexed list, `wasichai.security.oidc.service-accounts[i].client-id`
  and `.roles`, which environment variables set by index (`WASICHAI_SECURITY_OIDC_SERVICEACCOUNTS_0_CLIENTID`,
  `…_0_ROLES_0`). Boot fails on a client id that is not a valid account name or appears twice, a client with no role,
  or `ADMIN`.
- **The organization is the token's `organization` alias**, exactly one, as for a person.
- **A `service_accounts` row of that organization, found by client id** (`external_client_id`, V23), named like the
  client, with no secret (`secret_hash` NULL) and a backing `users` row with no password. The `sub` is not kept: a
  recreated client finds its row by client id. The row is created on the first token of a configured client, under a
  cluster lock; a local account already holding that name is not adopted (`403`).
- **Roles come from the configuration**, written to the row's `user_roles` when they differ; a role the organization
  lacks is a `403` and nothing is written. Declared roles (ADR-085) are what makes them exist in every tenant.
- **The translated token carries `service_account`**, so ADR-043 holds unchanged: never `ADMIN`, never
  `MANAGE_ORGANIZATION` or `MANAGE_TENANTS`; `/api/auth/me` answers `serviceAccount`; audit names it.
- **Refusals are `403` with a reason** (ADR-084's denied token): client not configured, row disabled, role missing,
  name clash.
- **Revoking:** disabling the client in Keycloak stops new tokens; a live one lasts the realm's access-token lifespan.
  Disabling the row in the app takes effect within `wasichai.security.oidc.roles-cache`.
- **Administration shows realm accounts read-only:** listed with `externalClientId`; `PUT` takes only `enabled`;
  rotating is `409`; `DELETE` is `409` while the client is configured. `POST /api/auth/token` with a realm account's id
  is `401 Invalid client credentials`.
- **Local service accounts** (`POST /api/auth/token`, ADR-043) are unchanged in both modes.

## Consequences

- A tenant's integration and loading accounts are born with the platform's `up`; no secret is copied by hand, and none
  is stored in the app.
- Revoking a realm account is not instant: up to the realm's token lifespan, unless the row is disabled in the app.
- The app trusts the realm's fixed `organization` mapper for a service account's tenant; the platform tests it.
- The kit sets the list with plain environment variables, one index per client.
- A difference from the original app: an ADR-031 entry; `external_client_id`, the nullable `secret_hash` and their
  constraint and index are known schema-parity deviations.
- Tested by `RealmPrincipalConverterTest`, `RealmServiceAccountsTest`, `OidcServiceAccountTest` and
  `ServiceAccountApiTest`.
