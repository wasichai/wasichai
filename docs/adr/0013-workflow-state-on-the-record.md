# ADR-013: Workflow state lives on the record, transitions are the only way to move it

**Status**: accepted · 2026-09-18

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).

## Context

Phase 10 asks for states and transitions and explicitly not for a BPM engine. Two questions had to be
answered before writing any of it: where a record's state is kept, and whether anything other than a
transition may change it.

## Decision

**State is a real column** — `workflow_state` on the object's physical table, added by
`ObjectSchemaManager` when a workflow is attached, and reserved in `SqlIdentifier` so no Custom Field
can collide with it. A side table keyed by record id was the alternative; it would have kept state
out of every query that already reads the record, and made a view that filters by state a join
instead of a column.

**A transition is the only way to move it.** The column is not a Custom Field, so it never appears in
the `attributes` map the dynamic write path accepts; there is no payload that sets it. Moving a
record means naming a transition that leaves its current state, and the server checks the state and
the caller's roles before it writes.

**Deleting a workflow leaves the column and its data.** Removing a definition is an administrative
decision that may be reversed; dropping the column would destroy the record of where every record
stood.

## Consequences

- Views, filters and the map can treat state as an ordinary column, because it is one.
- A record created before its object had a workflow has a null state. Transitions out of the initial
  state report themselves as not allowed, with a reason, rather than pretending the record is in a
  state it never entered. Backfilling is an explicit decision for an administrator, not a silent
  migration.
- Every transition writes an audit entry through the existing service, so the history screen shows
  workflow movement beside field changes without knowing anything about workflows.
- A transition reports itself through the `RecordChangeListener` port, which is how automations see
  it (ADR-016). This started life as a `RecordTransitioned` event with no listeners; the port replaced
  it once automations arrived, because one seam is better than two.
