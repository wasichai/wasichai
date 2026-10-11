# ADR-067: The rest of the model a request reads is cached like a definition

**Status**: accepted · 2026-10-11 · extends [ADR-066](0066-cached-definitions-and-one-access-read-per-request.md);
adds ADR-031 D50 ([ADR-031](0031-deliberate-deviations-from-sapgis.md))

## Context

After ADR-066, an app on wasichai (SGSPE) counted its statements again
([#108](https://github.com/wasichai/wasichai/issues/108)). A command that creates one record and links three others
went from 178 statements to 81, and a list by a caller who is not `ADMIN` to `ADMIN`'s plus two. Of the 81, 31 still
read the model on every operation, outside the definition cache:

- the object's workflow (`WorkflowRepository.findByObject`): once per record operation, and once per list for every
  caller, `ADMIN` included. 15 in that command;
- the object and the relationship by name on every link (`RelatedRecordService`, through the repositories): 6;
- the object's file fields on every write (`StoredFileRepository.fileFieldNames`, from `StoredFileGuard`): 6;
- the relation target's definition, built by `RelationTargets` from the repositories: 2;
- the object's enabled automations and notification rules, on every write: 2.

## Decision

**The definition cache keeps any other value read from the model, under its reader's key.**
`ObjectDefinitionCache.value(organization, key, load)` keeps what `load` answered, null included (no workflow is the
usual answer), for the same `wasichai.metadata.cache.ttl`, in the organization's bucket: every change ADR-066 drops
drops these too. The key is the reader's own private data class, so two readers never share one. `MetadataChanges.cached`
is the way in, with ADR-066's rule: a transaction that changed the model or the setup reads from the database and keeps
nothing.

**A module's setup drops the organization like a model change.** A write to a workflow, an automation or a notification
rule runs inside `MetadataChanges.setupChanging(organization) { … }`: the organization is dropped before (the
transaction then reads its own) and after (outside a transaction, a read in between kept the old value), and again when
the transaction ends. `MetadataChangeListener`s are not told: they hear of objects, fields and relationships, and their
contract stays as ADR-066 left it.

**Who reads through it.**
- Core: `RelatedRecordService` finds objects with `MetadataService.loadDefinition` and relationships by name through
  `cached`; `RelationTargets` reads the target's definition through `MetadataChanges.definitionById`, the same entry
  `loadDefinitionById` fills.
- Workflow: `WorkflowRepository.forObject`, for `WorkflowStates`, `transitionsOf` and `apply`. The admin's `save` and
  `delete` still read `findByObject` from the database.
- Automation: `AutomationRepository.findEnabledByObject`.
- Notifications: `NotificationRuleRepository.enabledByObject`.
- Files: `StoredFileRepository.fileFieldNames(organization, object)`, for `StoredFileGuard`.

Each takes `MetadataChanges` as a last, defaulted constructor argument (`MetadataChanges.uncached()`): code that builds
them itself keeps compiling and reads every time, as before. `WorkflowRepository.delete` and
`AutomationRepository.delete` take the organization too, and their `DELETE` filters by it.

**Not done.** No cache per module: one bucket per organization, dropped as a whole, is what keeps a workflow and the
definition it names consistent. Background jobs (automation runs, the notifications loop) read as before.

## Consequences

- With a warm cache, a request that writes, links and asks for transitions reads none of the model: no definition,
  workflow, automation, rule, relationship or file field. Pinned by `ModelLookupCacheApiTest` (statements counted on the
  driver) and by `ObjectDefinitionCacheTest` and `MetadataChangesTest`.
- Several nodes: a workflow, automation or rule changed on one node reaches another within the ttl (D50), as a model
  change does (D49).
- No REST change, no migration.
