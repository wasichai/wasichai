# ADR-087: The assistant's conversations, an app's own tools and a model per user

**Status**: accepted · 2026-11-24 · extends [ADR-014](0014-agents-through-the-api.md),
[ADR-015](0015-embabel-as-the-agent-runtime.md), [ADR-056](0056-what-reaches-the-model-is-the-apps-to-shape.md) and
[ADR-073](0073-claude-haiku-5-5-as-the-default-model.md)

## Context

The assistant answered one question at a time and forgot it: the transcript lived in the page, a follow-up ("and the
year before?") reached the model with nothing to refer to, and nobody could reopen what they asked yesterday. An app
whose data is more than custom objects (srtm's tax-gap runs, reports and taxpayer files, computed by its own services)
could not hand the model those answers, nor tell it its vocabulary. And the model was one per server, while a domain
user may want a stronger or a cheaper one.

## Decision

- **Conversations.** `wasichai-agent` gets its first migration (`db/wasichai/agent`, `flyway_history_agent`):
  `agent_conversations` (organization, user, title, archived), `agent_messages` (role, content, steps, usage, model,
  truncated) and `agent_user_settings`. A conversation is its owner's alone; every query filters by the caller's
  organization and user, and someone else's id is `404`. `DELETE` archives.
- `POST /api/agent/conversations/{id}/messages` asks with the last `wasichai.agent.history-turns` (default 10, 0–50)
  answered exchanges, **text only**, as Embabel `UserMessage`/`AssistantMessage` through `PromptRunner.respond`. Earlier
  tool results are not sent again: the model queries afresh instead of quoting stale figures. A truncated exchange is
  left out of the history. Both sides are kept only once the run answered; a refused or failed run keeps nothing. The
  first question titles the conversation. `POST /api/agent/ask` is unchanged and remembers nothing.
- **An app's tools.** `AgentToolContributor.tools(scope)` returns tool objects with `@LlmTool` methods, handed to the
  model next to the platform's. `AgentToolScope.call` is their only way in: as the caller, a `WasichaiException` turned
  into `{"error"}`, the result through the `AgentResultFilter`s, the step recorded — the platform's own tools'
  guarantees. Read-only, as ADR-014.
- **An app's rules.** `AgentInstructions.instructions(caller)` is appended to the platform's system prompt, resolved
  as the caller before the run.
- **A model per user.** `wasichai.agent.models` lists what a user may pick besides `wasichai.agent.model`;
  `GET/PUT /api/agent/settings` reads and sets the pick (`400` outside the list, null clears it). A pick the app later
  drops falls back to the default. `status` reports the caller's model and the list; an answer stores the model asked.

## Consequences

- The assistant module now owns tables; an app that does not want them sets `wasichai.agent.enabled=false` as before.
- What a user asked is kept in the organization's database, next to the data it was about. Deleting a user deletes
  their conversations (FK cascade).
- The history costs input tokens on every follow-up, bounded by `history-turns`.
- Streaming the answer, pseudonymizing an app tool's result by default and indexing documents for retrieval are not
  part of this decision.
