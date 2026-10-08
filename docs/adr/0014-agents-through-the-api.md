# ADR-014: The agent reaches data only through Wasichai's own services

**Status**: accepted · 2026-09-18 · amended by [ADR-056](0056-what-reaches-the-model-is-the-apps-to-shape.md)

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).
>
> Moved from chawpi on 2026-09-26: identifiers renamed chawpi → wasichai; the decision is unchanged. See
> [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md) and [the origin page](../chawpi-origin.md).

## Context

The platform holds a tenant's business data behind permissions, field visibility, tenancy and
workflow rules that phases 7 and 10 made real. An assistant that answers questions about that data
could be built two ways: let the model query the database (text-to-SQL over the schema), or give it
tools that call the same services a person's requests go through.

Text-to-SQL is the shorter path and the wrong one. Every rule the platform enforces lives above SQL,
so a model with database access would answer questions its user has no right to ask — quietly,
plausibly, and in fluent Spanish.

## Decision

The agent has no database access. It is given nine read-only tools, each of which calls a Wasichai
service: objects, object definitions, record queries and counts, a single record, relationships and
related records, record history, and available transitions. Argument parsing is separate from
execution, limits are clamped server-side, and a service refusal comes back to the model as a tool
error it can reason about rather than as a failed turn.

Object definitions come from `MetadataService.definitionOf`, the caller-filtered view — the same
method the UI uses — so a field the user may not read is invisible to the agent as well.

The agent runs **as the person asking**. The Anthropic SDK is blocking, so the model call is wrapped
in `Dispatchers.IO`; the tools run on the caller's coroutine, and the security context rides the
Reactor context element across the hop. That is asserted by a test, not assumed.

## Consequences

- The assistant cannot exceed its user. A role granted READ on one object gets answers about that
  object and refusals about everything else — proven by `AgentToolsTest`, which is the security claim
  of this phase and the reason those tests exist rather than tests of the model's prose.
- **No write tools.** A tool that creates records or moves workflow states turns a sentence into a
  change, and the confirmation step that would make that safe does not exist yet. The gap is
  deliberate and commented where a reader looks for the missing tools.
- Answers carry the tools they were built from, and the UI shows them. An answer without visible
  provenance is worse than no answer, because it cannot be checked.
- With no API key the feature reports itself unavailable and the rest of Wasichai is untouched — the
  same rule GeoServer follows.
- Jackson 2 sits on a classpath where Spring Boot 4 uses Jackson 3. This was first noticed as a
  consequence of the Anthropic SDK, and the expectation written here — that removing the SDK would
  remove it — turned out to be wrong. Migrating to Embabel (ADR-015) dropped the direct dependency and
  Jackson 2 stayed, arriving now by five independent transitive routes through Embabel itself
  (`anthropic-java-core`, Spring AI, `a2a-java-sdk-spec`, `jinjava`, `hubspot:algebra`). Different
  packages, still no conflict, and no longer something a dependency change is going to fix.
