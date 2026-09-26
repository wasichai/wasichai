# ADR-017: Deleting metadata refuses what it cannot undo, and says who is in the way

**Status**: accepted · 2026-09-18

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).

## Context

Objects and fields could be created but not meaningfully changed or removed. The endpoints existed —
`PUT` and `DELETE` on both — but `deleteObject` only ran `DROP TABLE … CASCADE` and deleted the row,
which is not the same thing as deleting an object.

Three concrete failures came out of that gap:

- `custom_fields.relation_target_object_id` is `ON DELETE RESTRICT`. Deleting an object another
  object pointed at hit the constraint and surfaced as `500 Unexpected error`. Worse, the `DROP TABLE
  … CASCADE` ran first, so the other object had already lost its foreign key by the time the metadata
  delete failed — the physical schema and the metadata disagreed.
- Relationship rows cascade away with the object; their physical join tables do not. Every deleted
  object with a many-to-many relationship left a table behind that nothing referenced.
- A published GeoServer layer names the physical table. Dropping the table left the layer answering
  every WMS request with an error.

A fourth was quieter: `enabled` was accepted by the API, stored, and honoured by nothing.

## Decision

**A delete that cannot be done cleanly is refused, and the refusal names what blocked it.**

`DELETE /api/objects/{object}` answers `409` when another object holds a `RELATION` field pointing at
it, listing them as `object.field`. The alternative — cascading into someone else's schema — changes
an object the caller did not ask about. Everything the object genuinely owns does go with it: join
tables are dropped explicitly before the metadata cascades, and the GeoServer layer is unpublished
first. GeoServer being unreachable does not block the delete; the table goes either way and the
failure is logged.

`DELETE …/fields/{field}` answers `409` for a field a relationship owns (delete the relationship,
which removes both sides) and for a field an automation reads, writes, or fills. That second one is
the reason the guard exists at all: a rule that lost its field fails only the next time it fires, in
a run log, far away from the admin who deleted it.

**Names and types are immutable, and say so.** An object's name backs its physical table and its API
path; a field's name is what views, forms and rules store. A type change may lose data. All three are
now accepted by the request body only to be refused with `400` naming the field. Silently ignoring
them would let the caller believe the edit landed — the same reasoning as `RecordService`'s
`rejectUnwritable`.

**`enabled` means read-only, not hidden.** A disabled object keeps its data and its pages; `POST`,
`PUT` and `DELETE` on its records answer `409`. It is the reversible way to retire an object, and the
delete screen points at it before offering the irreversible one.

## Consequences

Two new ports keep the module boundaries intact, following `RecordChangeListener`: `gis` implements
`ObjectRemovalListener` to unpublish, and `automation` implements `FieldUsage` to report which rules
depend on a field. `metadata` does not know either module exists.

Deleting an object is now a multi-step operation that can be refused halfway. It runs inside one
`@Transactional` method, so a refusal leaves the table standing — verified by a test that asserts the
physical table survives a rejected delete.

Renaming stays unimplemented rather than half-implemented. Doing it properly means moving the
physical table, its indexes, the GeoServer layer, and every field name stored in a layout or a rule.
Refusing loudly is honest; accepting and ignoring would not be.
