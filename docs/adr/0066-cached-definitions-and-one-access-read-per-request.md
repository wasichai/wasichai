# ADR-066: Object definitions are cached; what the caller's roles say about an object is read once per request

**Status**: accepted · 2026-10-11 · refines [ADR-062](0062-batch-record-creation.md) (its "why not the cache");
adds ADR-031 D49 ([ADR-031](0031-deliberate-deviations-from-sapgis.md))

## Context

An app on wasichai 1.0.0 (SGSPE) measured its record lists through a link of about 35 ms per query
([#105](https://github.com/wasichai/wasichai/issues/105)). After it cached its own reads, a list by a caller who is not
`ADMIN` still cost 10 statements (`ADMIN` + 4), and a command that creates one record and links three others cost 188
(about 7.5 s): 26 `custom_objects` and 30 `custom_fields` reads, 14 role reads and 16 owner-rule reads. Every `get`,
`list`, `create` and `link` loads the definition again (`MetadataService.loadDefinition`, two statements, no cache),
and for a caller who is not `ADMIN` asks again `RoleQueries.hasPermission`, `AccessPolicy.fieldAccess` and
`AccessPolicy.ownRecordsOnly`, three statements that depend only on the caller's roles and the object.

ADR-062 turned down a cache "until a user shows up", for four reasons: a definition changed in the same transaction, a
target deleted after it was found, a role or read scope that changed, an app's read scope that depends on data the
transaction wrote. This issue is that user. The decision below caches only what none of the four reach: definitions
(no record data), and role answers for the length of one request.

## Decision

**Definitions are cached by organization, for `wasichai.metadata.cache.ttl` (default `5s`).** `loadDefinition` and
`loadDefinitionById` keep their signatures and answer from an `ObjectDefinitionCache`, by name and by id. A missing
object is never cached: it throws before anything is kept. `0s` caches nothing, every read loads as before. So an
app's own lookups (a `RecordReadScope` that walks a parent chain) call `loadDefinition` and need no cache of their own:
the issue's fourth point needs no new method. `definitionOf` (`GET /api/metadata/objects/{object}`) reads through it
too.

**Every change drops the organization's definitions, twice.** Whatever writes objects, fields or relationships tells
one `MetadataChanges` bean: `MetadataService` (`createObject`, `updateObject`, `deleteObject`, `addField`,
`addRelationField`, `updateField`, `deleteField`), `RelationshipService` (`create`, `update`, `delete`) and
`OrganizationService.deleteCurrent`. The organization's entries go at once, and again when the transaction ends,
committed or rolled back, through a reactive `TransactionSynchronization`. The whole organization goes, not one object:
changes are rare and a relationship touches two. Its bucket is replaced rather than emptied, so a read that loaded the
old model before the change lands in a bucket nobody reads any more.

**A transaction that changed the model reads it from the database, and caches none of it.** The first change marks
the transaction (a resource of its `TransactionSynchronizationManager`). Until it ends, a definition read inside it
goes to the database, seeing its own uncommitted model, and is never kept, so no other request ever sees an
uncommitted or rolled back model (ADR-062's first reason).

**One node at once, the others within the ttl.** The cache is per node. A change on another node is seen here within
`wasichai.metadata.cache.ttl`: for at most that long a node may answer a field added elsewhere as missing, or select a
column dropped elsewhere (a `500`). That window is D49. It is the same trade as the revocation cache (ADR-059, `5s`).

**A port tells the app after commit.** `MetadataChangeListener.metadataChanged(MetadataChange)` (organization, kind
`OBJECT`, `FIELD` or `RELATIONSHIP`, `CREATE`, `UPDATE` or `DELETE`, the object's id and name, the field's or
relationship's name), every bean in `@Order`, after the commit and never for a rolled back change; with no transaction,
at once. It is for an app that keeps something derived from the model and would rather drop it now than wait out a ttl.
A listener that throws is logged and the next one runs: the change already happened. It must not write to the
database, the transaction is over. A deleted tenant tells nobody.

**What the caller's roles say about an object is one statement, once per request.** `RoleQueries.objectAccess(roles,
organization, object)` reads, in one statement, the actions the roles allow on the object (org-wide rows count), the
owner rule (every role says own records only) and the fields some rule restricts (the permissive union, as before).
`hasPermission` with an object, `AccessPolicy.fieldAccess` and the owner rule answer from it, and a request asks it once
per object whatever the action: `READ`, `CREATE` and `UPDATE` on one object in one request are one statement. The owner
rule has no object; the first object answers it for the request, and on its own (a read that has asked about no object
yet) it is one statement remembered for the request. `hasPermission` without an object (`MANAGE_METADATA`,
`MANAGE_ORGANIZATION`) keeps its own statement, remembered too. `ADMIN`, the platform and a caller without roles read
nothing, as before. The SQL `RelationTargets` folds into its lookup is unchanged.

**The memo lives in the request.** `RequestMemoWebFilter` puts a fresh `RequestMemo` in every request's Reactor
context, as the correlation id is (ADR-050), so it reaches transactions and coroutines alike. Without one (a job,
`asPlatform`, the agent's own runs) every call reads, as before. The next request reads again, so a permission changed
in between is seen at once (ADR-062's third reason). A write to roles, permissions or field rules
(`AdminService.createRole`, `updateRole`, `deleteRole`, `setPermissions`, `setFieldPermissions`) clears the memo of the
request that made it. Role answers are never cached across requests.

**Not done.** No Redis or other shared cache: it is outside the stack (ADR-033), and a round trip to it costs what the
query it saves does. No invalidation across nodes by `LISTEN`/`NOTIFY`: the ttl bounds the window, and the port lets an
app broadcast if it must (rule 10). `permittedObjects` (only `GET /api/objects`) is unchanged.

## Consequences

- A list by a caller who is not `ADMIN` costs `ADMIN`'s statements plus one, and on a warm cache neither reads the
  definition. A request with N record operations on the same objects reads each definition at most once (none when
  cached) and the role answer once per object. Pinned by `MetadataCacheApiTest` (statements counted on the driver).
- Several nodes: a change to the model is seen elsewhere within the ttl (D49). An app that cannot accept that sets
  `wasichai.metadata.cache.ttl=0s`, or listens and broadcasts.
- Within one request, role answers are what they were when first asked: a write by another request to the same roles
  is seen by the next request, not by the rest of this one.
- `AccessPolicy` takes `RoleQueries`; its `(DatabaseClient, WasichaiSchemas)` constructor stays. `MetadataService`,
  `RelationshipService` and `OrganizationService` gain a defaulted `MetadataChanges` (uncached) as last argument, so
  code that builds them itself keeps compiling and behaves as before.
- No REST change, no migration, nothing in wasichai-ui.
- Tested by `ObjectDefinitionCacheTest`, `MetadataChangesTest`, `RequestMemoTest`, `RoleQueriesMemoTest` and
  `MetadataCacheApiTest`.
