# ADR-070: Per-language workflow labels, resolved by the client

**Status**: accepted · 2026-10-23 · builds on [ADR-013](0013-workflow-state-on-the-record.md),
[ADR-018](0018-workflows-are-edited-on-a-canvas.md) and [ADR-034](0034-user-preferences-and-themes.md)

## Context

A workflow state and a transition have one `label`. An app in two languages (SGSPE: es-PE and en) shows its 19
workflows' states and buttons in Spanish to an English user, while everything else around them follows the chosen
language ([wasichai/wasichai-ui#69](https://github.com/wasichai/wasichai-ui/issues/69), W-08). The server has no
language: the user's is a preference the client applies (ADR-034), generated pages carry keys the client translates,
and nothing on the server reads `Accept-Language`.

## Decision

**A state and a transition may carry `labels`, a map from a language tag to its text, beside `label`.** `label`
stays required in effect (it falls back to the name) and is the text for any language the map lacks. On `PUT`, each
key must be a language tag (`^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$`: `es`, `en`, `pt-BR`), else `400` on `states` or
`transitions`; texts are trimmed and a blank one drops its language. A definition stored before the change reads with
empty maps: the column is jsonb and the field has a default, so there is no migration.

**The server hands the maps out; the client picks.** `GET …/workflow` returns them in the definition. The transition
listing adds `labels` (the transition's) and `toLabels` (its target state's) beside `label` and `toLabel`, which keep
their meaning. wasichai-ui resolves a text as: the active language, then its primary subtag (`pt` for `pt-BR`), then
`label`. The workflow editor edits one text per language of `config.languages`.

## Consequences

- No REST change for a client that ignores the maps: every field it read keeps its value.
- The agent's `AgentTransition` mirrors `AvailableTransition` and carries both maps.
- Object and field labels stay single-language; a map there would be its own decision.
