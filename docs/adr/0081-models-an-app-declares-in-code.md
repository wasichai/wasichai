# ADR-081: Models an app declares in code, ensured by the platform

**Status**: accepted · 2026-11-14 · builds on
[ADR-039](0039-background-work-runs-as-the-platform-with-a-cluster-lock.md),
[ADR-049](0049-admin-changes-in-the-audit-log.md),
[ADR-055](0055-tenant-provisioning-apart-from-tenant-administration.md) and
[ADR-057](0057-background-work-finds-the-tenants-through-a-tenant-directory.md)

## Context

An app whose code writes certain objects needs them with one fixed shape in every tenant. `hneyra/curricula` will
publish its plans, cycles, courses and syllabi as read-only Custom Objects its code writes
([#165](https://github.com/wasichai/wasichai/issues/165)). Today:

- `MetadataService`, `RelationshipService` and `ObjectActionService` start every change with
  `currentUser.requireWithPermission(MANAGE_METADATA)`, which reads the token only: `RecordService.asPlatform`
  (ADR-039) does not reach them, and the call answers `401`;
- there is no declared model, template or seed beyond the development seed of ADR-026.

So a person with `MANAGE_METADATA` made the model over REST in each tenant, and nothing stopped anyone from changing
an object the app's code depends on afterwards.

## Decision

**A bean in the shapes the API takes.** An app registers `DeclaredModel` beans:

```kotlin
@Bean
fun curricula() =
    DeclaredModel(
        name = "curricula",
        objects = listOf(CreateObjectRequest(name = "curso", label = "Curso", apiOnly = true, fields = listOf(/* … */))),
        relationships = listOf(CreateRelationshipRequest(/* … */)),
        actions = mapOf("curso" to listOf(ObjectActionRequest("PUBLICAR")))
    )
```

The objects, fields, indexes, uniques, flags, relationships and actions are the requests the metadata API takes, so
an app writes its model once, as it would send it, and there is no second vocabulary. `name` owns what the model
declares (`^[a-z][a-z0-9_-]{0,48}$`). Two models with one name, or one object or relationship in two models, refuse
the context: they would undo each other on every start.

**Ensured, not applied.** `DeclaredModels` (core, `@ConditionalOnMissingBean`) ensures every model in a tenant:

- what is missing is created, through the same services a person calls, so every check and DDL path is theirs;
- what differs and can change is changed, through the same updates: labels, flags, options, defaults, sets;
- what already matches is left alone: a second run writes nothing, not even an admin entry;
- an object or a relationship a person made under a declared name is taken over once it matches, so a tenant set up
  by hand before the app declared its model is adopted, not duplicated;
- a field the tenant has and the model does not is kept: dropping it would drop its data;
- **an incompatible change is reported, never applied**: a field declared with another type, a relation that points
  elsewhere, a module type's settings, a relationship of another shape or between other objects, an object or
  relationship another model owns. The rest of the model applies;
- a change the database refuses (a unique the data repeats, a required field with empty rows) rolls back the whole
  model in that tenant and is logged; the next start tries again.

`DeclaredModelReport` says what each run created, updated and left incompatible, line by line. Objects are ensured in
the order given, so a `RELATION` field's target comes first; then relationships, then actions. An action the tenant
has keeps its label.

**When.** Once the app is ready, for every tenant of the `TenantDirectory` (ADR-057), each model in its own
transaction: one tenant's failure is logged and the next goes on. And when a tenant is provisioned, inside
`OrganizationService.provision`'s transaction (ADR-055): a tenant is born with the app's model, or not at all. Each run
holds a cluster lock for its tenant (`ClusterLock.withXactLock`, ADR-039), so two instances starting together never
race on the same DDL.

**The platform as the one who changes a model.** `ModelOwner.run(organizationId, model) { }` puts a private key in
the Reactor context, as the record platform caller does; `MetadataService`, `RelationshipService` and
`ObjectActionService` read it before the token. Only `DeclaredModels` sets it. Unlike `asPlatform` it also runs inside
a request, because provisioning ensures the new tenant's model in the provisioner's transaction; nothing a request
carries can put the key there. The admin entries (ADR-049) have no user and say `model:<name>` as their source
(ADR-050), through a new `AdminAudit.recordPlatform`.

**What a model owns is locked.** An object or a relationship a model made or took over carries `declaredBy`.
Through the metadata API a person's change to it is a `409` naming the model: relabelling, enabling, deleting the
object, adding, changing or deleting its fields, declaring or removing its actions, changing or deleting the
relationship, a new relationship that would put its column on the object, and deleting another object a declared
relationship joins. Its records are not locked: they follow the object's own write rules (`apiOnly`, `appendOnly`).
Responses carry `declaredBy` only when it is set, so a client can show the model as read-only.
`V21__declared_models.sql` adds `declared_by` to `custom_objects` and `relationships`.

**Why not a migration per app, or a seed.** A Flyway script runs once per database, not once per tenant, and knows
nothing of the tenants provisioned afterwards; the development seed (ADR-026) is data for one tenant. A model in code
is checked on every start against what each tenant holds, which is what lets it report drift.

## Consequences

- The app's code can rely on its objects: every tenant has them, and only the app changes them.
- A change to a declared model reaches every tenant on the next start; an incompatible one needs a new field or object,
  as the API would ask of a person.
- Provisioning fails when the model cannot be made: a broken declaration is a bug of the app.
- `AdminAudit` has a new member; an app that implements it itself implements `recordPlatform`. Breaking, for 2.0.0.
- A difference from the original app: ADR-031 D57. The two columns are known schema-parity deviations.
- Tested by `DeclaredModelsTest` and `DeclaredModelApiTest`.
