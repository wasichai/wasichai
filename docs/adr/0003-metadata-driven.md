# ADR-003: Metadata-driven architecture

**Status**: accepted · 2026-09-17

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).

## Context

The product promise is that an administrator configures an application instead of a team building
one. Code generation per Custom Object would put a compile-and-deploy cycle in the middle of that.

## Decision

Metadata is the program. `custom_objects` and `custom_fields` drive the physical schema, the REST
API, validation, the form, the table, the detail page and the map layer — interpreted at runtime, on
both sides of the wire. There is no `PredioEntity.kt` and no `PredioForm.tsx`, and there never will
be.

## Consequences

- Adding a field is an API call, effective immediately, everywhere.
- Validation must exist at runtime in both places: `FieldValueCodec` on the server, zod schemas built
  from the same metadata on the client.
- No compile-time type safety per object. Tests cover the interpreters instead, which is where bugs
  would otherwise be duplicated per object.
