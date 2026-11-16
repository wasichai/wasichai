# ADR-085: Roles an app declares in code, ensured and locked like its model

**Status**: accepted · 2026-11-16 · extends [ADR-081](0081-models-an-app-declares-in-code.md)

## Context

The apps of the `peru` domain need the same roles in every tenant: catastro's `SRTM_LECTOR`, `CATASTRO_LECTOR` and
`CATASTRO_ANALISTA`; srtm's `ANALITICA` and `CAJA`; caja's `SISTEMA_ORIGEN`, `CAJERO`, `SUPERVISOR_CAJA` and
`TESORERIA`; and `CARGA_DATOS` in the three, for bulk loading. Today a script (`apply_roles.py`) makes them over the REST
API with an administrator's token, and srtm's `CAJA` is made by hand. A service account cannot do it: ADR-043 refuses
it `MANAGE_ORGANIZATION`, and that stays. ADR-081 already ensures an app's objects, relationships and actions in every
tenant, at startup and on provisioning, and locks them; roles are the part of the model it left out. The design is in
[2026-11-16-declared-roles-and-realm-service-accounts-design.md](../superpowers/specs/2026-11-16-declared-roles-and-realm-service-accounts-design.md)
(decisions A).

## Decision

- **`DeclaredModel.roles`**, a list of `DeclaredRole(name, label, ownRecordsOnly, permissions, fieldPermissions)`,
  whose entries are the API's `PermissionEntry` (a null `objectName` is organization-wide; an action is built in or
  declared on the object) and `FieldPermissionEntry`.
- **Checked at startup:** the name matches `^[A-Z][A-Z0-9_]{1,48}$`; `ADMIN` cannot be declared; a declared role
  grants neither `MANAGE_ORGANIZATION` nor `MANAGE_TENANTS`; one role in two models refuses the context. Tenant
  administration is the administrator's to hand out, not the app's.
- **Ensured after objects, relationships and actions**, in the same run, transaction, cluster lock and
  `ModelOwner.run` (ADR-081): at startup in every tenant, and inside provisioning, so a tenant is born with them.
- **Through `AdminService`'s role methods**, which take the platform as actor inside `ModelOwner.run` (audit source
  `model:<name>`) and the caller with `MANAGE_ORGANIZATION` otherwise. `metadata` may not depend on `admin`, so
  `DeclaredModels` calls them through a port it owns, `DeclaredModelRoles`, implemented in `admin`.
- **The declaration is the whole role:** label, `ownRecordsOnly`, every permission and every field rule. What matches is
  left alone; a second run writes nothing.
- **A person's role under a declared name is taken over** and made as declared; its holders keep it.
- **A grant the tenant cannot hold** (an object, field or declared action it does not have) leaves that role as it
  was and is reported incompatible; the rest of the model applies, as with an action on a missing object.
- **A role removed from the code is released:** `declared_by` cleared, grants and holders kept, reported.
- **Locked:** `roles.declared_by` (V23). A person's change to a declared role, its label, flag, permissions, field
  rules or deleting it, is a `409` naming the model. People still create roles of their own and assign declared roles
  to users and service accounts. `RoleResponse` carries `declaredBy` when set.

## Consequences

- Every tenant has the roles the app's code and its integrations rely on; `roles.json` and `apply_roles.py` retire once
  each app's declaring version is deployed.
- Starting the declaring version over a tenant that ran the script changes the grants of the roles it took over to the
  declared ones; the report lists each.
- The roles of a model that disappears whole stay locked, like its objects.
- A difference from the original app: an ADR-031 entry; `roles.declared_by` is a known schema-parity deviation.
- Tested by `DeclaredModelsTest`, `DeclaredRolesApiTest` and `SchemaParityTest`.
