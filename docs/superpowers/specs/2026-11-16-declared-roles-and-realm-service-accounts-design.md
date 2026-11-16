# Roles an app declares, and service accounts signed by the realm: design

**Status:** approved 2026-11-16. **Scope:** wasichai (core). The platform (wasichai-infrastructure: one Keycloak client
per integration and tenant, its mappers, the kit's configuration) and the apps (caja, srtm, catastro: their declared
roles, asking the realm for a token) follow in their own plans. **ADRs:**
[ADR-085](../../adr/0085-roles-an-app-declares-in-code.md), which extends
[ADR-081](../../adr/0081-models-an-app-declares-in-code.md), and
[ADR-086](../../adr/0086-service-accounts-signed-by-the-domains-identity-provider.md), which amends
[ADR-043](../../adr/0043-service-accounts.md) and [ADR-084](../../adr/0084-people-sign-in-through-the-domains-identity-provider.md).

## Question

Bulk loading and the integrations between caja, srtm and catastro still need manual steps, each by someone who
administers the app (`MANAGE_ORGANIZATION`):

- **Roles.** A script (`apply_roles.py`) creates `SRTM_LECTOR`, `CATASTRO_LECTOR`, `CATASTRO_ANALISTA`, `ANALITICA`,
  `SISTEMA_ORIGEN`, `CAJERO`, `SUPERVISOR_CAJA` and `TESORERIA` over the REST API; srtm's `CAJA` is made by hand.
- **Service accounts.** Each lives in the app being called (`srtm` in catastro, `rentas` in caja, `caja` and
  `analitica` in srtm, `carga` in srtm and catastro), and its secret is copied by hand into the caller's configuration.
  In production none is configured, so the three integrations are off.

ADR-043 refuses `MANAGE_ORGANIZATION` to every service account, so none of this can be automated with one, and that
refusal stays. The goal: every environment and every municipality (tenant) is born with its roles and its integration
and loading accounts, with no manual step and no administration by script.

Can an app declare its roles in code, as it declares its model, and can a Keycloak client of the domain's realm be a
service account of an app, without weakening ADR-043? The SSO design
([2026-11-16-sso-keycloak-design.md](2026-11-16-sso-keycloak-design.md)) left "service accounts as Keycloak clients"
out; this design decides it.

Facts that frame the answer (wasichai at fcb9df6):

- `DeclaredModel` (`wasichai-core/.../metadata/DeclaredModels.kt`) declares objects, relationships and object actions
  in the request shapes of the metadata API. `DeclaredModels.ensure` runs them in every tenant at startup
  (`ensureAll`, on `ApplicationReadyEvent`) and inside `OrganizationService.provision`'s transaction, under
  `ClusterLock.withXactLock("wasichai:declared-model:<org>")` and `ModelOwner.run`, so the metadata services see the
  platform as the actor (`ModelActor`, `metadata/ModelActor.kt`). An action declared on an object the tenant does not
  have is reported in `DeclaredModelReport.incompatible`, not applied, and the rest of the model applies.
- What a model owns carries `declared_by` (`V21__declared_models.sql`, on `custom_objects` and `relationships`), and
  `ModelActor.requireMayChange` answers a person's change with `409`.
- Roles are administered by `AdminService` (`admin/AdminService.kt`): `createRole` (name `^[A-Z][A-Z0-9_]{1,48}$`,
  label, `ownRecordsOnly`), `updateRole`, `deleteRole` (`ADMIN` cannot be deleted), `setPermissions` (replaces the
  whole set; `objectName` null is organization-wide; an action is built in or declared on that object;
  `MANAGE_TENANTS` only by a holder) and `setFieldPermissions` (replaces the rules of the objects it names). Every one
  starts with `currentUser.requireWithPermission(MANAGE_ORGANIZATION)` and writes an admin audit entry.
- `CoreArchitectureTest`: `metadata` may depend on `common`, `platform` and `identity`, not on `admin`; `admin` may
  depend on `metadata`.
- Service accounts (`V8__service_accounts.sql`, `admin/ServiceAccountAdmin.kt`, `identity/ServiceAccountTokens.kt`):
  a row per organization (`name` `^[a-z][a-z0-9_-]{1,48}$`, `secret_hash NOT NULL`, `enabled`), backed by a `users`
  row with the same id. `ServiceAccountTokens.find` reads `secret_hash` with `Rows.string`, which fails on a null.
- `CurrentUser` (`identity/CurrentUser.kt`) reads `service_account` into `AuthenticatedUser.serviceAccount`; with it,
  `isAdmin` is false and `hasPermission` refuses `MANAGE_ORGANIZATION` and `MANAGE_TENANTS`, whatever the roles grant.
- In `oidc` mode (ADR-084, `identity/oidc/*`): `IssuerRoutingJwtDecoder` routes by `iss`; `RealmJwtDecoder` checks
  RS256, the realm's issuer, `exp` and that `aud` holds `wasichai.security.oidc.client-id`. `RealmPrincipalConverter`
  reads `organizationAlias(jwt)` (exactly one alias, list or map), finds the organization by slug, then a person by
  `users.external_subject` (`V22__external_subject.sql`) or by verified email; a token with no access becomes a
  denied token (`wasichai_denied`) that the chain answers with `403` and its reason. `RealmUsers.link` never links a
  service account's backing row.
- `TokenRevocation` (`identity/TokenRevocation.kt`) wraps the app's own decoder only: it applies to local tokens, not
  to the realm's.
- **Verified** (Keycloak 26.8.2, 2026-11-16): a client-credentials access token of a confidential client with a
  `HardcodedClaimProtocolMapper` (`organization` = JSON `["muni-ica"]`) and an audience mapper carries
  `organization: ['muni-ica']` (also when `scope=organization` is asked for), `aud` = the target app, `azp` = the
  client id, a stable `sub` (the client's service-account user), `preferred_username: service-account-<client>`, and
  no `email`. Not verified: that `sub` survives recreating the client; assume it does not.

## Decisions

### A. Roles an app declares in code

**A1. A role is part of the model, in the API's shapes.** `DeclaredModel` gains `roles: List<DeclaredRole>`:

```kotlin
DeclaredRole(
    name = "CARGA_DATOS",
    label = "Carga de datos",
    ownRecordsOnly = false,
    permissions = listOf(PermissionEntry(objectName = null, action = "MANAGE_METADATA"), PermissionEntry(null, "CREATE")),
    fieldPermissions = listOf(FieldPermissionEntry("contribuyente", "dni", read = true, write = false))
)
```

`PermissionEntry` and `FieldPermissionEntry` are the entries of `PUT /api/roles/{name}/permissions` and
`field-permissions`: an organization-wide grant is a null `objectName`, and an action is a built-in one or one the
object declares (ADR-042). No second vocabulary, as ADR-081 asks.

**A2. Checked when the context starts.** A role name must match `^[A-Z][A-Z0-9_]{1,48}$` once upper-cased; `ADMIN`
cannot be declared; a declared role grants neither `MANAGE_ORGANIZATION` nor `MANAGE_TENANTS`; one role name in two
models refuses the context, as an object in two models does. Why refuse the two administration actions: tenant
administration is the administrator's to hand out, and a role that came with the app's code would hand it to whoever
an administrator assigns it to without the administrator choosing it; `MANAGE_TENANTS` is, besides, only grantable by
a holder (ADR-055). `MANAGE_METADATA` stays grantable: `CARGA_DATOS` needs it, and it is scoped to data (ADR-043).

**A3. Ensured after the rest of the model, in the same run.** Roles are ensured after objects, relationships and
actions, because their grants name objects and declared actions: in the same transaction, cluster lock and
`ModelOwner.run`, at startup in every tenant and when a tenant is provisioned. A tenant is born with the app's roles
or not at all, next to its `ADMIN`.

**A4. Through the services a person calls.** `AdminService`'s role methods accept the platform as the actor, as the
metadata services do: inside `ModelOwner.run` the actor is the platform (no user, audit source `model:<name>`); outside
it, the caller with `MANAGE_ORGANIZATION`, as today. `metadata` may not call `admin`, so `DeclaredModels` reaches them
through a port it owns, `DeclaredModelRoles`, implemented in `admin` by `DeclaredRoles` and wired by the
auto-configuration. The port exists because of the layering rule, not for a second implementation; with no
implementation (a context without `AdminService`) a model with roles refuses to start.

**A5. The declaration is the whole role.** Label, `ownRecordsOnly`, the permission set and the field-permission set
are what the model says. Unlike `PUT /field-permissions`, which replaces the rules of the objects it names, a declared
role's field rules on an object it no longer names are removed: nobody else can have written them. What already
matches is left alone; a second run writes nothing.

**A6. A person's role under a declared name is taken over** and made as declared, reported as `updated role X (taken
over)`. These names come from the apps' own scripts (`roles.json`), so the roles exist already wherever those ran;
the users who hold one keep it, now with the declared grants.

**A7. A grant the tenant cannot hold leaves the role as it was, and is reported.** A permission or field rule that
names an object, a field or a declared action the tenant does not have makes the role incompatible: it is not created,
or not changed, and `DeclaredModelReport.incompatible` says which grant; the rest of the model applies. Why report and
not fail: this is how ADR-081 treats an action on an object the tenant lacks; a role whose missing grant was silently
dropped would mean different things in different tenants; and failing the whole model would fail provisioning over an
object a module or a person was to make. The app's own objects are ensured first, so a role over them never hits this.
A role another model owns is incompatible too.

**A8. A role removed from the code is released, not deleted.** A role whose `declared_by` is this model and that the
model no longer declares gets `declared_by` cleared and is reported `released role X`. Its grants and its assignments
stay: deleting it would cut access silently, and a person may now change or delete it. A model that disappears whole
leaves its roles locked, as it leaves its objects (ADR-081); renaming a model is a code change that should not happen.

**A9. Locked like the model.** `roles.declared_by` (V23). A person's `PUT /api/roles/{name}`, `DELETE`,
`PUT …/permissions` and `PUT …/field-permissions` on a declared role answer `409` naming the model
(`ModelActor.requireMayChange`). People still create their own roles, and still assign declared roles to users and to
service accounts: assignment is not a change to the role. `RoleResponse` carries `declaredBy` only when it is set.

### B. Service accounts signed by the domain's realm

**B1. Which realm token is a service account.** In `oidc` mode a realm token (already checked: RS256, `iss`, `exp`,
`aud` holds this app's client id) is a service account's when it has no `email`, an `azp`, and `preferred_username`
equal to `service-account-` followed by `azp` in lower case. Anything else goes the person's way, unchanged.

**B2. The app says which clients it accepts, and with which roles.**

```yaml
wasichai:
  security:
    oidc:
      service-accounts:
        carga-muni-ica:
          roles: [CARGA_DATOS]
        srtm-muni-ica:
          roles: [SRTM_LECTOR]
```

Boot fails when a key does not match a service account's name (`^[a-z][a-z0-9_-]{1,48}$`), when a client has no role,
or when one of its roles is `ADMIN`. The roles must exist in the organization; typically they are declared roles
(decision A), so every tenant has them. The platform sets the map from its contract (one client per integration and
tenant); an app changes nothing in its code. Environment variables cannot carry a `-` inside a map key, so the kit
passes this map through `SPRING_APPLICATION_JSON`.

**B3. The organization is the token's, as for a person.** `organizationAlias(jwt)`: exactly one alias, which is the
organization's slug; none, several or an alias the app does not have is the same `403` a person gets. The realm puts
it there with a mapper of fixed value, one client per tenant.

**B4. A row of that organization, found by client id.** `service_accounts` gains `external_client_id`. The account is
the row with `(organization_id, external_client_id = azp)`; its name is the client id. The `sub` is not stored: a
recreated Keycloak client gets a new `sub` and the same client id, and finds its row again. A realm account never has
a secret (`secret_hash` NULL), and its backing `users` row is as ADR-043 makes it, except `password_hash` is NULL
(V22 allows it; a null hash is invalid credentials).

**B5. Created on its first token, when configured.** A configured client with no row in that organization gets one,
in its own transaction, under `ClusterLock.withXactLock("wasichai:realm-service-account:<org>:<client>")` so two
first tokens make one row, with a platform admin entry (source `realm:<client-id>`). A local account of that
organization already named like the client is not adopted: `403` naming the clash, which an administrator settles by
deleting the local account.

**B6. Roles follow the configuration.** On resolution the row's `user_roles` are set to the configured roles when they
differ (audited the same way), so a configuration change applies on the first token after the app restarts with it.
A configured role the organization lacks is a `403` naming it, and nothing is written.

**B7. The translated token is a service account's, so ADR-043 holds.** `sub` = the row's id, `org`, `email` = its
backing address, `roles` = its roles, `service_account` = its name. `isAdmin` is false and `MANAGE_ORGANIZATION` and
`MANAGE_TENANTS` are refused, whatever its roles grant; `/api/auth/me` answers `serviceAccount`; audit identifies it as
any service account.

**B8. Refusals, each a `403` with its reason** (the denied-token path of ADR-084): a client not configured in this app
(`client <azp> is not a service account of this app`), a disabled row (`service account <name> is disabled in this
app`), a missing role, a name clash. A token for another app's audience is a `401`, as for a person.

**B9. Revoking.** Disable the client in Keycloak: no new tokens, and a live one runs out with the realm's access-token
lifespan (minutes). Or disable the row in the app (`PUT /api/service-accounts/{id}` `enabled: false`): refused within
`wasichai.security.oidc.roles-cache`, which also bounds how long a resolution is cached. `TokenRevocation` (ADR-059)
stays for local tokens only. Rotating is the realm's (the platform regenerates the client secret).

**B10. Administered by configuration; shown read-only.** `GET /api/service-accounts` lists realm accounts too, with
`externalClientId` (absent for a local one) and no `secretRotatedAt`. On a realm account `PUT` takes `enabled` (a
local kill switch) and refuses `roles` (`409`: they come from configuration); rotating is `409` (no secret here);
`DELETE` is `409` while the client is configured (it would come back on its next token) and allowed once it is not.
`POST` makes only local accounts. `POST /api/auth/token` with a realm account's id is `401 Invalid client
credentials`, after the same one hash check, never a `500`.

**B11. Local service accounts are unchanged.** `/api/auth/token`, their secrets, their administration: as ADR-043,
in both modes. They remain what an app without Keycloak uses.

### Data model (V23)

```sql
ALTER TABLE ${metadataSchema}.roles ADD COLUMN declared_by text
    CONSTRAINT roles_declared_by_valid CHECK (declared_by ~ '^[a-z][a-z0-9_-]{0,48}$');
ALTER TABLE ${metadataSchema}.service_accounts ADD COLUMN external_client_id text;
ALTER TABLE ${metadataSchema}.service_accounts ALTER COLUMN secret_hash DROP NOT NULL;
ALTER TABLE ${metadataSchema}.service_accounts ADD CONSTRAINT service_accounts_one_credential
    CHECK ((secret_hash IS NULL) <> (external_client_id IS NULL));
CREATE UNIQUE INDEX service_accounts_org_external_client_uq
    ON ${metadataSchema}.service_accounts (organization_id, external_client_id)
    WHERE external_client_id IS NOT NULL;
```

The check makes every row exactly one kind: a local account has a secret and no client id, a realm account the
reverse. Every existing row is local, so the migration needs no data step.

## What changes where

- **wasichai**: V23; `DeclaredRole`, `DeclaredModel.roles`, the port `DeclaredModelRoles` and its `admin`
  implementation; `AdminService`'s role methods take the platform as actor and refuse a person's change to a declared
  role; `RoleResponse.declaredBy`; `Oidc.serviceAccounts`; `RealmServiceAccounts` and the service-account branch of
  `RealmPrincipalConverter`; `ServiceAccountTokens` treats a null hash as invalid credentials;
  `ServiceAccountService` lists realm accounts and refuses what they do not take; ADR-085, ADR-086, ADR-031 entries,
  `docs/security/authentication.md`, `docs/api/rest.md`, the manuals' configuration reference, `docs/HISTORY.md`.
- **wasichai-ui**: the roles screen shows a declared role read-only; the service-accounts screen (when there is one)
  shows a realm account without secret actions. Both read the new optional fields.
- **wasichai-infrastructure**: per tenant and integration, a confidential Keycloak client with only service accounts
  enabled, an audience mapper and a fixed `organization` mapper; the secret to Infisical; the contract and the kit pass
  `wasichai.security.oidc.service-accounts` to the app being called.
- **caja, srtm, catastro**: declare their roles; ask the realm for tokens; retire `roles.json`, `apply_roles.py` and
  `crear_cuenta_*.py`.

## Risks and costs

- **Trusting the realm with the organization.** A service account's organization is a fixed mapper the platform sets,
  not a membership Keycloak checks. A wrong mapper gives a client another tenant's access to the roles configured for
  it. The platform's tests on the domain stack are where this is caught; the app cannot tell.
- **A person shaped like a service account.** Only a token with no `email`, an `azp` that is configured and the
  matching `service-account-<azp>` username takes this path. The configured clients must allow no browser flow (the
  platform makes them service-account only), so no person ever gets a token whose `azp` is one.
- **Taking over a person's role** changes the grants of everyone who holds it, on the first start of the app that
  declares it. That is the intent for the apps' existing roles; the report lists every take-over.
- **A role left locked** by a model that was removed whole cannot be changed by anyone. Rare; a later ADR can add a
  release for it if it happens.
- **Configuration in a map keyed by client id** cannot be set with plain environment variables; the kit must use
  `SPRING_APPLICATION_JSON`, and the manual says so.
- **Not verified:** that Keycloak keeps a client's service-account `sub` across a recreation. The design does not
  depend on it (B4).

## Not in this design

- Roles or permissions read from Keycloak; a realm client's roles in the token.
- Realm service accounts in `local` mode: there is no realm.
- Creating realm accounts through `POST /api/service-accounts`, or editing their roles in the app.
- A declared role that administers the tenant, or a declared `ADMIN`.
- Releasing the roles of a model that is gone; renaming a model.
- Revoking a live realm token before it expires (the realm's lifespan is the bound).

## Plans

wasichai first (backend, then the UI's read-only views), then the platform, then the apps, each verified in staging:
[2026-11-16-declared-roles-and-realm-service-accounts-backend.md](../plans/2026-11-16-declared-roles-and-realm-service-accounts-backend.md).
