# ADR-011: Pages defined by metadata, with a generated default

**Status**: accepted · 2026-09-17

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).

## Context

Until now the record detail screen was a hardcoded two-column React layout: form on the left, map on
the right, related lists underneath. That contradicts the platform's premise — an administrator
should arrange a screen the way they arrange an object.

Two ways to get there. Either every object must have a page configured before it can be shown, or
the UI keeps a hardcoded fallback and pages are an optional override.

## Decision

Pages are metadata: a layout plus an ordered list of components (`FORM`, `MAP`, `RELATED_LIST`,
`TEXT`), each declaring the column it lives in. The frontend renders **only** from a page definition.

When no page is stored for an object, the server derives one from the object's metadata — form,
map when the object has geometry, one related list per relationship — and returns it flagged as
generated. There is no second rendering path in the client.

## Consequences

- One rendering path, so a configured page and a default page cannot drift apart. Configuring a page
  starts from the generated one rather than from a blank screen.
- Definitions are validated on write: a related list must name a relationship that involves the
  object, a map needs the object to have geometry, form fields must exist, and a component cannot
  sit in a column the layout does not have. Bad layouts fail at configuration time, not at render
  time in front of a user.
- Deleting a page is a reset, not a deletion of the screen.
- The visual, drag-and-drop builder remains a later phase; the current builder edits the same
  definition through a form, which is enough to prove the model.
