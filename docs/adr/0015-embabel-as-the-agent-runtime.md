# ADR-015: Embabel as the agent runtime

**Status**: accepted · 2026-09-18

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).
>
> Moved from chawpi on 2026-09-26: identifiers renamed chawpi → wasichai; the decision is unchanged. See
> [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md) and [the origin page](../chawpi-origin.md).

## Context

Phase 11 shipped an assistant on a hand-written tool loop over the Anthropic Java SDK. The product
spec named Embabel — a Spring-based agent framework for the JVM — as the intended runtime, so the
assistant was migrated to it.

## Decision

Embabel 1.5.2 runs the assistant. The tool layer, the HTTP contract and every guarantee in ADR-014
stay exactly as they were: the nine tools still call Wasichai services, the agent still runs as the
person asking, there are still no write tools, and answers still carry the tools they were built
from. `AgentToolsTest` passed the migration with its assertions untouched, which is the evidence that
the security model did not move.

## What it cost, stated plainly

- **The model.** Embabel 1.5.2's Anthropic catalogue stops at `claude-opus-4-8`; it does not know
  `claude-opus-5`, which phase 11 ran on. The assistant now runs `claude-haiku-4-5`
  (`WASICHAI_AGENT_MODEL`). This is a capability regression, not a preference.
- **Bootability.** Embabel's platform asserts that a model exists and fails the context without one.
  Wasichai must start without a key — CI runs the whole suite that way. `EmbabelGate` excludes every
  Embabel autoconfiguration when no key is configured. Without it, adding the framework would have
  made the platform unstartable for anyone without an API key.
- **Control.** The tool-loop cap is fixed at 20 inside Embabel and is not configurable in 1.5.2;
  `wasichai.agent.maxIterations` now bounds the number of actions a run may take, not tool calls.
- **Schema expressiveness.** Tool schemas are derived from Kotlin signatures, so `direction` lost its
  `enum: [asc, desc]` and states its allowed values in prose instead.
- **Dependencies.** Jackson 2 did not leave with the Anthropic SDK; Embabel brings it by five
  independent routes, along with a2a, jinjava and the rest of its tree. ADR-014 is corrected.

## What it bought

- A tool loop we no longer maintain, and `ScriptedLlmOperations` — shipped in Embabel's main jar —
  which let the suite drive the whole agent path with a stubbed model. The assistant now has five
  end-to-end tests that never touch the network; before, the loop itself was untested.
- Provider portability: changing models or providers becomes configuration.
- Goal-oriented planning, multi-agent orchestration and MCP, none of which the current single-question
  assistant uses.

## Consequences

- **The value of this decision is conditional.** For one question and nine read-only tools, the
  framework is overhead: the planner runs a single action and we paid for it with the model. It earns
  its place when the automations the spec describes (event → rule → action, on top of the
  `RecordChangeListener` seam) grow an action that asks a model, because that is the shape Embabel
  exists for. The rule engine shipped in ADR-016 is deterministic and uses none of it.
- If Embabel adds `claude-opus-5` to its catalogue, the largest cost disappears on a version bump.
- The toolbox is built per question rather than as a bean, closing over the caller's coroutine
  context. A singleton would have to find the caller at call time, which is how an agent ends up
  querying as nobody.
- Embabel registers agents through a post-processor annotated `@Profile("!test")`. A profile named
  exactly `test` silently stops the assistant from being deployed; `IntegrationTest` carries a comment
  saying so.
