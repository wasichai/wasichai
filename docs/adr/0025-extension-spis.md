# ADR-025: The core is extended through SPIs, never by knowing its modules

**Status**: accepted · 2026-09-25 · amends ADR-001, ADR-010, ADR-013, ADR-016, ADR-017

> Moved from chawpi on 2026-09-26: identifiers renamed chawpi → wasichai; the decision is unchanged. See
> [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md) and [the origin page](../chawpi-origin.md).

## Context

In sapgis the core packages knew the optional ones. `metadata` injected `PageService`, `ViewService`
and `FormService`; `MetadataService` could not start without a `FieldUsage` (automation) and an
`ObjectRemovalListener` (gis); `RecordService` needed exactly one `RecordChangeListener` and one
`WorkflowStates`; `platform.SqlIdentifier` listed `workflow_state`; and there were five package cycles
(metadata↔data, metadata↔identity, metadata↔forms/views/pages, data→audit→metadata, pages→forms→metadata).
A library cannot work like that: an app that leaves a module out must still boot.

## Decision

**`wasichai-core` (`wasichai.core.*`) depends on no module.** Modules plug in by declaring beans of
core-owned interfaces. Core collects every bean of a kind as an ordered list and falls back to a null
object when a single answer is needed.

| SPI | Package | Pattern | Core default |
|---|---|---|---|
| `FieldTypeHandler` + `FieldTypeRegistry` | `metadata` | Strategy + Registry | the 12 scalar types |
| `RecordQueryContributor` + `RecordCriterion` | `data` | Strategy | none |
| `SystemColumnContributor` → `SystemColumns` | `platform` | Registry | none |
| `RecordChangeListener` | `data` | Observer (list) | none |
| `ObjectRemovalListener`, `FieldUsage` | `metadata` | Observer / Chain (lists) | none |
| `WorkflowStates` | `data` | Null Object | `NoWorkflowStates` |
| `ModuleMigration` | `platform` | Registry | core, optional dev seed (ADR-026) |

Listeners are called synchronously, right after the write, in `@Order`, as before. `RecordService`
opens no transaction of its own (sapgis parity); a listener that needs atomicity opens one itself. The
listener lists (`RecordChangeListener`, `ObjectRemovalListener`, `FieldUsage`) are ordered and may be
empty — no module installed means no listener runs, not a null pointer. A `RecordCriterion` a
`RecordQueryContributor` returns is always parenthesised before it joins the rest of the `WHERE`
clause, so a module's own `OR` cannot widen another module's filter or, worse, a tenant's.

`FieldTypeRegistry` checks all of this at boot, not on first use: two handlers cannot claim the same
type name, the same payload section, the same `custom_fields` attribute column with two different Java
types, or a JSON key `FieldResponse`, `ObjectResponse` or `RecordResponse` already owns. A collision
fails startup with the names in the message, not a corrupted response the first time both fire.
`FieldTypeHandler.select` never gets an alias from core — a handler that rewrites the column (gis's
`ST_AsGeoJSON`) appends its own `AS quote(readName(field))`, because core would otherwise have to guess
a rewritten expression's output name.

**Inside core the packages form a DAG**: `common ← platform ← identity ← metadata ← audit ← data`,
with `admin` and `organization` on top and `autoconfigure` above all. Three moves made it one:
`ObjectSchemaManager` moved into `metadata`, record-level relationship walking moved into
`data.RelatedRecordService`, and user/role administration moved out of `identity` into `admin`.
`CoreArchitectureTest` enforces the DAG and the ban on module names.

**No component scanning of library code.** Every bean is declared in an `@AutoConfiguration`
(`WasichaiPlatform…`, `WasichaiSecurity…`, `WasichaiMetadata…`, `WasichaiData…`, `WasichaiAdminAutoConfiguration`)
with `@ConditionalOnMissingBean`, so an app replaces any of them by declaring its own. A module that
replaces a core default (`WorkflowStates`, `RecordStore`) orders its auto-configuration
`before = [WasichaiDataAutoConfiguration::class]`. Stereotype annotations stay on the classes: the
kotlin-spring plugin opens those classes for `@Transactional` proxies, and `@RestController` is how
WebFlux finds handlers. The JWT signing key is wrapped in `WasichaiJwtKey` rather than exposed as a bare
`SecretKey`, so an app's own unrelated `SecretKey` bean cannot be picked up by
`@ConditionalOnMissingBean`. The wasichai `SecurityWebFilterChain` registers at `@Order(0)`: Boot 4.1.1's
reactive resource-server auto-configuration always contributes a chain of its own, regardless of
`@ConditionalOnMissingBean`, so ours has to win the order rather than rely on being the only one. A
module that wants its own `SecurityWebFilterChain` (its own `securityMatcher`) needs `@Order` below
zero to run first.

**What core does not define.** Three rows of the design's SPI table live elsewhere:
- `ObjectMetadataContributor`: no interface. views, forms and pages each map their own
  `GET /api/metadata/objects/{object}/views|forms|pages` (same URLs); core's controller simply lost
  the three routes.
- `PageComponentProvider` belongs to wasichai-pages. pages registers the HISTORY component itself.
- `DocumentIssuer` belongs to wasichai-automation.

**Kept as data, not as knowledge.** The record-state column is still called `workflow_state`
(`ObjectSchemaManager.STATE_COLUMN`) and `RecordResponse.state` is still core JSON (ADR-013); only
the reservation of the name comes from the workflow module. `audit_log.document_id` and
`AuditOperation.ISSUE` stay in core, and wasichai-documents adds the FK and the CHECK value.

## Consequences

- An app with only `wasichai-core` boots on plain PostgreSQL; `CoreOnlyApiTest` proves it.
- A module that stores something about an object (a layer, a rule) must implement the matching
  listener, or deleting the object leaves it dangling. Core cannot check this for it.
- Without the workflow module, nothing stops a user field called `workflow_state`. Installing workflow
  later then finds the column taken. Accepted: the name is unusual and the conflict is loud.
- **Even a label-only edit of a field whose type module is uninstalled answers `409`.** Editing a field
  revalidates it against its handler regardless of which part of the request changed, so there is no
  partial edit that skips the check — a behaviour change from sapgis, where every type shipped in the
  one binary and "uninstalled" could not happen.

**Known gaps, inherited from the original — both fixed (P2 Task 16, the one deliberate behaviour change
against the original in P2).**
- `RelatedRecordService.link`/`unlink` now hold both records to the record api's rules: same
  organization, and only the caller's own under `own_records_only`. A missing, foreign or not-yours
  record answers `404`, as on GET/PUT/DELETE (the original answered `204`, or `500` for a missing one on
  link). Every effective link or unlink writes one `UPDATE` row on each record's history, keyed
  `rel:<relationship name>` so it cannot collide with a real field. No new audit operation: the `audit_log_operation_valid` CHECK stays core's
  `CREATE, UPDATE, DELETE`, with `ISSUE` added by wasichai-documents.
- The audit `after` snapshot and `RecordChange.after` carry every stored field, not the caller's
  writable projection, so a locked field no longer reads as cleared. When the write's own `RETURNING`
  row already has every field (as `PhysicalTableRecordStore`'s does), it is used as is: it is atomic
  with the write. Only a store that returns a projection triggers a re-read with the full definition,
  and that re-read is best-effort: `RecordService` still opens no transaction, so a concurrent write
  may land between the two. Listeners get the full row, not the caller's projection: automations
  judge and act on the whole record as the platform (ADR-016). So the automation webhook's `record`
  carries every stored field whatever the caller's field access, as in the original app.
- Sections (`RecordRequest`/`RecordResponse.sections`, one per installed field type) stay out of audit
  diffs and `RecordChange` the same way ADR-0019 keeps geometry out of both.
