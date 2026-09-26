# ADR-012: Views and forms as named metadata, resolvable to a default

**Status**: accepted · 2026-09-17

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).

## Context

The record list showed whatever fields fitted — the first eight visible ones — and the dynamic form
showed every field in one flat grid. Neither could be arranged by the people who configure the
platform, and both had the same shape for every audience: a cadastral reviewer and a front-desk
clerk saw the same columns and the same form.

## Decision

Views and forms are metadata, named and stored per object, following the model Pages already set.

- A **view** is a list configuration: columns and their order, exact-match filters, a sort and a page
  size. An object may have several; one is the default the list opens with.
- A **form** is an arrangement of fields into titled sections. A page's `FORM` component may name one
  instead of listing fields.

As with pages, the server resolves a default derived from metadata when nothing is stored, so a
usable list and a usable form exist the moment an object does, and configuring one starts from what
is already on screen.

## Consequences

- Several views per object means several audiences, without a line of code per audience.
- Definitions are validated on write against the object's fields, so a renamed or deleted field is
  caught when the view is saved. A column naming a field that vanished later is skipped at render
  time rather than breaking the table.
- Sorting a column in the UI overrides the view for that session and does not silently rewrite the
  stored definition — a view is configuration, not a scratchpad.
- `form` and `fields` on a `FORM` component are mutually exclusive; allowing both would leave two
  sources of truth for the same question.
- Views describe filters as exact matches only. Ranges, null checks and spatial predicates are the
  obvious next step, and the definition is a JSON document, so they extend it without a migration.
