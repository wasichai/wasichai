# ADR-056: Creating and deleting tenants can be kept apart from administering one

**Status**: accepted · 2026-10-08 · amends [ADR-053](0053-the-caller-is-told-their-tenant-wide-capabilities.md),
builds on [ADR-042](0042-app-declared-actions.md), [ADR-043](0043-service-accounts.md) and
[ADR-049](0049-admin-changes-in-the-audit-log.md)

## Context

Tenant lifecycle and tenant administration shared one permission. `POST /api/organizations` (provision a tenant and
its administrator), `PUT /api/organizations/current` (rename) and `DELETE /api/organizations/current` (drop the tenant
and every business table it owns) all checked `MANAGE_ORGANIZATION`, the same right that administers users, roles,
service accounts and units. `ADMIN` short-circuits every permission, and the `ADMIN` role that provisioning creates
holds every permission.

So whoever administers the users of one organization could also create other organizations, each with a fresh
administrator, and delete their own. In a multi-customer deployment a customer's administrator held the operator's
powers. A social-management app (SGSPE), operated for several customer organizations, relied on an unenforced rule
that customer administrators never call those routes ([#56](https://github.com/wasichai/wasichai/issues/56)).

## Decision

A new built-in action, `MANAGE_TENANTS`, guards `POST /api/organizations` and `DELETE /api/organizations/current`,
behind a switch so nothing changes by default.

- **`wasichai.organizations.separate-provisioning`** (default `false`). Off, `MANAGE_TENANTS` means
  `MANAGE_ORGANIZATION`: whoever passes the `MANAGE_ORGANIZATION` check (`ADMIN` included, never a service account)
  passes it, exactly as before, and a `MANAGE_TENANTS` grant on its own changes nothing.
- **On**, only an org-wide `MANAGE_TENANTS` grant on one of the caller's roles counts. The `ADMIN` short-circuit does
  not apply to it, and `MANAGE_ORGANIZATION` does not imply it. A role holds it like any other object-less action
  (`PUT /api/roles/{name}/permissions`, `objectName: null`); an `ADMIN` role granted it holds it too.
- **A service account never holds it**, whatever the switch and its roles (ADR-043).
- **`PUT /api/organizations/current`** (rename) stays `MANAGE_ORGANIZATION`: it administers the tenant, it does not
  create or destroy one.
- **Object-less only.** It is a built-in action (`Actions.BUILT_IN`), so an object may not declare an action of that
  name (ADR-042), and a grant of it naming an object is a `400` naming `objectName`. Core's `V15__manage_tenants.sql`
  extends `permissions_action_valid` and `object_actions_not_builtin` and adds `permissions_tenants_no_object`.
- **Only a holder hands it on.** Adding, removing or changing a role's `MANAGE_TENANTS` row through
  `PUT /api/roles/{name}/permissions` takes a caller whose own roles hold the grant (`CurrentUser.holdsTenantsGrant`),
  whatever the switch; anyone else gets `403`. `ADMIN` alone is not enough. Without this rule every tenant's
  administrator could grant the action to itself, and with the switch off a customer could arm itself before the
  operator turns the switch on. Replacing a role's other permissions while leaving its `MANAGE_TENANTS` row as it is
  stays a `MANAGE_ORGANIZATION` call.
- **Provisioning never grants it.** The `ADMIN` role created by `POST /api/organizations` gets the six actions it
  always got, so every tenant created through the API is a customer tenant.
- **The first holder is bootstrapped out of band**, as the first tenant already is: one `permissions` row for a role of
  the operator's tenant, written by whoever runs the database (see the build-your-app guide). From there the operator
  hands it on through the API.
- **`GET /api/auth/me/permissions`** reports it in `capabilities`, third, after `MANAGE_METADATA` and
  `MANAGE_ORGANIZATION`, answered by `CurrentUser.hasPermission` like the other two. It is listed exactly when the two
  routes would let the caller in: with the switch off, for every holder of `MANAGE_ORGANIZATION` (the administrator
  included); with it on, for holders of the grant only. A client can show "new organization" and "delete
  organization" on `MANAGE_TENANTS` alone, whatever the deployment's switch.
- The admin audit (ADR-049) is unchanged: a provisioning still leaves one `admin:organization` `CREATE` entry in the
  provisioner's trail, a deletion one `DELETE` entry.

## Consequences

- Default deployments behave as before on every route. The only visible change is one more entry in the
  administrator's `capabilities`, and a `403` instead of a `400` for an attempt to grant `MANAGE_TENANTS` without
  holding it; both are ADR-031 D41.
- A multi-customer deployment turns the switch on after granting `MANAGE_TENANTS` to a role of the operator's tenant.
  Customer administrators keep their users, roles, service accounts, units and the tenant's name, and can no longer
  create or delete tenants.
- Inside the operator's tenant, whoever holds `MANAGE_ORGANIZATION` can still assign the role that holds
  `MANAGE_TENANTS` to a user. That tenant is the operator's own; its administration should stay with the operator's
  people.
- An app that declared an action named `MANAGE_TENANTS` on an object must rename it before upgrading: `V15` fails on
  such a row rather than leave a built-in name ambiguous.
- ADR-053's list of capabilities gains its third entry, and its "`ADMIN` holds both" becomes "`ADMIN` holds the first
  two, and `MANAGE_TENANTS` while the switch is off". Its decision is otherwise unchanged.
- wasichai-ui needs no change to keep working; it may show the tenant routes on `MANAGE_TENANTS`.
- Tested by `CurrentUserTest`, `CallerPermissionsServiceTest` and `WasichaiAutoConfigurationTest`, and the integration
  tests `SeparateProvisioningApiTest` (switch on), `OrganizationApiTest` and `PermissionEnforcementTest` (switch off).
