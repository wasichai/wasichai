# ADR-078: Per-language texts of objects, fields, enum options and relationships

**Status**: accepted · 2026-11-03 · extends [ADR-070](0070-per-language-workflow-labels-resolved-by-the-client.md)

## Context

ADR-070 gave workflow states and transitions a `labels` map and left this open: "Object and field labels stay
single-language; a map there would be its own decision." An app in two languages hits that limit on every metadata
screen: wasichai-learning declares 32 objects, 221 fields, 37 relationships and 98 enum options in Spanish, and an
English user reads `Código` and `LECTURA` in core's record screens while everything around them is in English
([wasichai#155](https://github.com/wasichai/wasichai/issues/155),
[wasichai-ui#140](https://github.com/wasichai/wasichai-ui/issues/140)).

## Decision

**ADR-070's rule, applied to the model.** Beside each single-language text, a map from a language tag to its text:

| Where | Beside | Map |
|---|---|---|
| Object | `label`, `pluralLabel`, `description` | `labels`, `pluralLabels`, `descriptions` |
| Field | `label`, `description` | `labels`, `descriptions` |
| `ENUM` field | `enumOptions` | `enumOptionLabels`: option value → language → text |
| Relationship | `label`, `inverseLabel` | `labels`, `inverseLabels` |

The single texts keep their meaning and stay the fallback; the server never picks a language. wasichai-ui resolves a
text as ADR-070 does: the active language, its primary subtag, then the single text.

**Validated as ADR-070's.** A key that is not a language tag (`^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$`) is a `400` naming
the map; texts are trimmed and a blank one drops its language. An `enumOptionLabels` key that is not an option of the
field is a `400` naming `enumOptionLabels`. The texts are for people: the stored value, its validation and the
column's `CHECK` read only `enumOptions`, and an option that is removed takes its texts with it.

**Replace, not merge.** On create a map is optional. On update `null` (left out) keeps what is stored, so a client
that predates the maps never clears them by saving a label, and a map replaces it whole (`{}` clears it). Sending the
same maps again changes nothing, so a model applied on every deployment stays a no-op.

**Stored in one `translations` jsonb column per table** (`custom_objects`, `custom_fields`, `relationships`, migration
`V19`), `NOT NULL DEFAULT '{}'`: definitions stored before read with empty maps and no installation runs anything by
hand. A response writes a map only when it holds something, so a model without texts answers exactly as before.

**Field permissions apply unchanged.** The texts of a field live on the field: a field the caller may not read is not
listed, and its texts with it. A related side (`GET /api/objects/{object}/relationships`) adds `labels`, the texts of
its own `label` (the relationship's, the inverse's, or the other object's plural ones), and `objectLabels`, the other
object's plural ones.

## Consequences

- No REST change for a client that ignores the maps: every field it read keeps its value, and none is added when
  nothing is stored.
- The admin trail (ADR-049) tells of a change to a map like any other property; an entry of a model without maps reads
  as before.
- The texts are model, like the labels beside them: an app keeps them in its repository and applies them through the
  same endpoints.
