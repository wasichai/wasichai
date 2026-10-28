# ADR-073: Claude Haiku 5.5 as the assistant's default model

**Status**: accepted · 2026-10-28 · amends the model cost of [ADR-015](0015-embabel-as-the-agent-runtime.md)

## Context

ADR-015 moved the assistant to Embabel 1.5.2, whose Anthropic catalogue stopped at `claude-opus-4-8`, and the
default model became `claude-haiku-4-5`. Embabel only runs a model it has in its catalogue: `embabel.models.default-llm`
names an entry, not a free model id.

Embabel 1.5.4 ([release](https://github.com/embabel/embabel-agent/releases/tag/v1.5.4)) adds `claude-haiku-5-5`
([embabel-agent#2173](https://github.com/embabel/embabel-agent/pull/2173)) beside `claude-sonnet-5-5`,
`claude-opus-5-5` and `claude-fable-5-1`. In that catalogue Haiku 5.5 costs $0.10 / $0.50 per million input / output
tokens, against $1 / $5 for Haiku 4.5.

## Decision

- Wasichai depends on Embabel 1.5.4.
- `wasichai.agent.model` defaults to `claude-haiku-5-5`, and so does the `embabel.models.default-llm` that
  `EmbabelGate` sets from it.

## Consequences

- A newer model at a tenth of the price, with no change to the REST API beyond the `model` value that
  `GET /api/agent/status` and the `usage` of `POST /api/agent/ask` report.
- An app that pinned `WASICHAI_AGENT_MODEL` keeps its model. One that wants more capability sets
  `claude-sonnet-5-5` or `claude-opus-5-5`; nothing in wasichai changes for it.
- `claude-opus-5`, which phase 11 ran on, is still not in the catalogue; the 5.5 family is its successor, so the
  regression ADR-015 recorded is gone for any app that chooses one of them.
