# ADR-049: Changes to users, roles, permissions and the model are in the audit log

**Status**: accepted · 2026-10-07 · builds on [ADR-025](0025-extension-spis.md),
[ADR-041](0041-a-change-reason-on-record-writes.md), [ADR-043](0043-service-accounts.md) and
[ADR-048](0048-a-read-scope-narrows-what-a-caller-reads.md)

## Context

`audit_log` was written only on record paths: `RecordService`, `RelatedRecordService`, workflow, documents and
automations. Nothing recorded who created a user, granted a role `DELETE`, rotated a service account's secret,
switched `requiresReason` off on an object or dropped a field and its data. A social-management app (SGSPE) must audit
every critical action, administration included, and a change to `requiresReason` or `appendOnly` weakens the record
trail itself ([#49](https://github.com/wasichai/wasichai/issues/49)).

## Decision

### The same table, under reserved names

Administration changes are rows of `audit_log`, with no new table, column or migration. Their `object_name` is one of:

| `object_name` | `record_id` | Written by |
|---|---|---|
| `admin:user` | the user | `POST /api/users`, `PUT /api/users/{id}`, `PUT /api/users/{id}/roles`, `DELETE /api/users/{id}`, `PUT /api/users/{id}/org-units` |
| `admin:role` | the role | `POST /api/roles`, `PUT /api/roles/{name}`, `DELETE /api/roles/{name}` |
| `admin:permission` | the role | `PUT /api/roles/{name}/permissions`, `PUT /api/roles/{name}/field-permissions` |
| `admin:service-account` | the account | create, update, `POST .../{id}/secret`, delete under `/api/service-accounts` |
| `admin:org-unit` | the unit | `POST`, `PUT`, `DELETE` under `/api/org-units` |
| `admin:object` | the object | `POST`, `PUT`, `DELETE /api/objects/{object}`; `POST` and `DELETE` of `/api/metadata/objects/{object}/actions` |
| `admin:field` | the field | `POST`, `PUT`, `DELETE /api/metadata/objects/{object}/fields/...` |
| `admin:relationship` | the relationship | `POST`, `PUT`, `DELETE /api/relationships` |
| `admin:organization` | the organization | `POST /api/organizations`, `PUT` and `DELETE /api/organizations/current` |

An object name matches `^[a-z][a-z0-9_]{0,48}$`, which has no `:`, so an `admin:*` row never mixes with an object's.
`operation` is `CREATE`, `UPDATE` or `DELETE`, the values `audit_log_operation_valid` has always accepted; the CHECK is
unchanged. `user_id` is the acting person or service account, as on record rows. Every admin entity has a uuid id, so
`record_id` needs no change: a permission set is the role's, so its rows carry the role's id.

Where things go:

- **Indexes, unique constraints, write flags (`appendOnly`, `apiOnly`, `requiresReason`) and declared actions** belong
  to the object, so they are `admin:object` `UPDATE` rows. The object's snapshot carries them all, its fields and its
  declared actions (`{"ANULAR": "Anular"}`) too, so creating or dropping an object records everything it had.
- **A relationship's own column** (`MANY_TO_ONE`, `ONE_TO_ONE`, `ONE_TO_MANY`) is described by its `admin:relationship`
  row (`fieldName`, `joinTable`); it gets no `admin:field` row of its own. One call, one row.
- **Role membership** and **unit membership** are part of the user: `admin:user` `UPDATE` rows whose `roles` or
  `orgUnits` differ. The same for a service account's roles.
- **Organizational units** (ADR-045) are audited too, as `admin:org-unit`, although the issue did not list them: they
  grant nothing in core, but an app's read scope (ADR-048) may narrow by them, so moving a unit changes who reads what.
- **Provisioning** a tenant is the provisioner's act: its row is in the provisioner's organization, with the new
  tenant's id as `record_id`. Renaming and deleting a tenant are rows of that tenant. `audit_log` has no foreign key to
  `organizations`, so a deleted tenant's rows stay; nobody can sign in to read them, the database still holds them.

### Snapshots, never secrets

`before_state` and `after_state` hold the entity as the API shows it, minus timestamps, so the diff names what someone
changed. They are built from the API answers, never from a row that holds a hash: no `password_hash`, no
`secret_hash`, no `clientSecret`. A password change is `"passwordChanged": true` in `after`, a secret rotation
`"secretRotated": true`, a provisioned tenant's administrator is `adminEmail`.

A permission set is stored whole on both sides, one key per grant: `"<object>.<ACTION>"` (or `"*.<ACTION>"` for every
object) to `allowed`, and `"<object>.<field>"` to `{"read": …, "write": …}` for field rules, beside `"role"`. The
existing `AuditDiff`, which compares key by key, then lists only the grants that were added, removed or flipped. A role's
own snapshot carries both sets too, so a deleted role's row says what it could do.

### Written in the transaction of the change

Each admin service writes its row at the end of its `@Transactional` method, after the change and its DDL. A call
refused by validation, a `403` or a `409` throws before it, and a rollback takes the row with the change.

Core's packages are a DAG (ADR-025): `metadata`, `admin` and `organization` may not use `audit`, which uses
`metadata`. So they write through a port in `identity`, which all of them may use:

```kotlin
interface AdminAudit {
    suspend fun record(actor: AuthenticatedUser, entity: AdminEntity, id: UUID, operation: AdminOperation,
                       before: Map<String, Any?>?, after: Map<String, Any?>?)
}
```

`AdminEntity` holds the reserved names. `audit` implements the port over `AuditService` (`AuditLogAdminAudit`, bean
`adminAudit`, `@ConditionalOnMissingBean`: an app may send the trail elsewhere as well, but it then owns it).

### Read by `MANAGE_ORGANIZATION`, and only by it

The admin trail is not data of an object, so object-level `READ` says nothing about it.

- `GET /api/audit?objectName=admin:<entity>` needs `MANAGE_ORGANIZATION` and nothing else. Anyone else gets `200 []`,
  not a `403` that would say the trail is there. A service account never holds `MANAGE_ORGANIZATION` (ADR-043), so it
  always gets `[]`.
- Without an `admin:*` filter, `/api/audit` keeps its rule (an organization-wide `READ`, else `403`), and leaves the
  `admin:*` rows out for a caller without `MANAGE_ORGANIZATION`, in the query, before `limit`.
- An `admin:*` row is shown **whole** to whoever may see it: field permissions name fields of objects and say nothing
  about it, so `readableFields` no longer turns it into "nothing readable", and a read scope (ADR-048) never asks about
  it or drops it.
- Its `CREATE` and `DELETE` list every key (`null` on the other side), so "who created this role" shows what it was.
  A record's `CREATE` and `DELETE` keep their empty `changes`.
- `GET /api/objects/{object}/records/{id}/history` stays a record's: `admin:*` is not an object, so it is a `404`.

## Consequences

- Every listed admin and metadata call writes one row more, in its own transaction, and the object rows read the
  object's fields and actions once more. Admin calls are rare; record writes are untouched.
- `/api/audit` lists admin entries to an administrator without a filter. wasichai-ui's audit screen shows them as
  entries of an object it does not know; it should label `admin:*` itself (a follow-up there). ADR-031 D34.
- Constructors: `MetadataService` takes an `ObjectActionRepository` and an `AdminAudit`; `ObjectActionService` a
  `CustomFieldRepository` and an `AdminAudit`; `RelationshipService`, `AdminService`, `ServiceAccountService`,
  `OrgUnitService` and `OrganizationService` an `AdminAudit`. `MetadataService.addRelationField` is
  `addField` without the field row, for `RelationshipService`. `CurrentUser.hasPermission` answers what
  `requirePermission` enforces.
- Not covered: module admin routes (views, forms, pages, workflow definitions, automations, notifications, layers),
  user preferences, and the dev seed, which is a migration. Each can join later through the same port.
- Tested by `AdminSnapshotsTest`, `AuditQueryServiceAdminTest`, `AuditLogAdminAuditTest`,
  `WasichaiAutoConfigurationTest` and the integration test `AdminAuditApiTest`.
