# ADR-079: An object says what its record writes leave in the audit log

**Status**: accepted · 2026-11-14 · refines [ADR-054](0054-audit-log-is-append-only-in-the-database.md); keeps
[ADR-049](0049-admin-changes-in-the-audit-log.md) and [ADR-050](0050-correlation-id-and-change-source-on-audit-rows.md)

## Context

Every create, update and delete of a record writes its `before_state` and `after_state` whole to `audit_log`
(`AuditService.record`, called from `RecordService`, automations, workflow transitions and related-record links).
`audit_log` takes inserts only (ADR-054), so what goes in stays. For some objects that buys nothing and grows without
end ([#164](https://github.com/wasichai/wasichai/issues/164)):

- a copy the app rebuilds from its own schema, which keeps its own trail. `hneyra/curricula` publishes its catalog as
  Custom Objects, rebuilt on every import, with its history in `curricula.event_log` and immutable revisions;
- a large `LONG_TEXT` rewritten often and copied whole into every entry.

Nothing in an object's or a field's definition said anything about the audit.

## Decision

**An object takes `auditMode`**: `FULL` (the default, what every object did), `OPERATION` or `NONE`.

- `FULL`: the entry and both states, as before.
- `OPERATION`: the entry, who, when, the correlation id, the source and the reason; `before_state` and `after_state`
  stay `NULL`, so the history lists the operation with no changes.
- `NONE`: no entry.

It applies to every record write of the object: `RecordService` (create, update, patch, delete, `createAll`,
`upsertAll`), an automation's `UPDATE_FIELD` and `CREATE_RECORD`, a workflow transition and a link or unlink, each end
as its own object says. A document's `ISSUE` entry always stays: a document never exists without its history entry.
The admin trail (`admin:*`) is not a record write and keeps every entry.

**A field takes `audited: false`**: its value stays out of both states, which then carry `"_omitted": ["<name>", …]`,
a key no field can have (field names start with a letter). The history diffs the rest and answers `omitted` with the
names, those the caller may read. A link or a transition carries no field values, so only the mode applies to them.

**One path.** `AuditService.recordWrite(definition, …)` applies both and calls `record`; every record write of core
and the modules goes through it. Listeners (`RecordChangeListener`, automations, notifications) still hear of every
write, whole: the flags shape the log, not what happens.

**Metadata like any other.** Both flags are properties of the create and update requests of objects and fields, so
they need `MANAGE_METADATA` and every change is an `admin:object` or `admin:field` entry (ADR-049). On update a flag
left out keeps its value, as the write rules do (ADR-040), so an editor that predates them never switches one back. A
response writes `auditMode` only when it is not `FULL` and `audited` only when it is `false`: an object that declares
nothing reads exactly as before. `V20__audit_modes.sql` adds `custom_objects.audit_mode` (`FULL` by default, a `CHECK`
on the three values) and `custom_fields.audited` (`true` by default).

**Why not a retention instead.** The log is append-only by design (ADR-054) and a purge is an operator's act. What the
issue asks for is not to write what has no value, at the object that knows it.

## Consequences

- Without the flags nothing changes: the same rows, the same responses.
- An object with `NONE` has no trail of its record writes; who changed what comes from the app's own records. Turning
  it on again starts a trail from that point.
- `_omitted` is stored in the states; a reader of `audit_log` outside the API sees it.
- A difference from the original app: ADR-031 D56. The two columns are known schema-parity deviations.
- Tested by `AuditStatesTest` and `AuditModeApiTest`.
