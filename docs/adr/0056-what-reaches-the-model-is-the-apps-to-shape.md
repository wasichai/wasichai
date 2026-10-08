# ADR-056: What reaches the model is the app's to shape, per caller, and every run is reported

**Status**: accepted · 2026-10-08 · amends [ADR-014](0014-agents-through-the-api.md), builds on
[ADR-015](0015-embabel-as-the-agent-runtime.md) and [ADR-025](0025-extension-spis.md)

## Context

ADR-014 made the assistant read only through Wasichai's services, as the person asking, so it never sees more than
that person may see. That answers what a person may *see*; it does not answer what an organization may *send to a
third-party model provider*. A person may read a field the organization still keeps home. Three gaps followed, all
inside the module, where an app could not reach them
([#59](https://github.com/wasichai/wasichai/issues/59)):

- `AgentToolbox.call` returned each tool's JSON to the model as it was.
- Availability was one switch for the deployment (`enabled` and an API key): in a multi-tenant deployment every
  tenant's data could reach the provider, or none could.
- Nothing reported what a question cost. An app that must record every AI interaction could not say how many tokens
  it spent.

A social-management app (SGSPE) must pseudonymize personal data before it reaches an external model, offer the
assistant only to organizations that opted in, and record every interaction with its token usage. Its workaround
closed `POST /api/agent/ask` behind its own endpoint and limited the assistant to roles that cannot read the
restricted fields at all.

## Decision

Three extension points in `wasichai-agent`, each with a no-op default, so an app that declares none gets the assistant
it had.

- **`AgentResultFilter`** — `filter(caller, tool, input, resultJson): String`. Every bean runs, in `@Order`, in
  `AgentToolbox.call`, on the JSON that goes to the model and again on the step's `summary`, so the steps the UI
  shows obey it too. It runs inside the run's bridge to the caller (`AgentRun.asCaller`), the same reactive context as
  the tools. An optional **`AgentAnswerFilter`** — `restore(caller, answer): String`, every bean, in `@Order` — runs on
  the model's final text, for a reversible pseudonym; never on a truncated run.
- **Fail closed.** A filter that throws closes the run: the result is not handed to the model, Embabel's tool loop is
  asked to stop before its next model call, every later tool call and any retried action stop at once
  (`AgentRun.requireOpen`), and `ask` answers with the filter's own `WasichaiException` (its status) or else
  `AgentFilterException`, a `500` "could not prepare the data for the model; nothing was sent". Not a `503`: the
  model did nothing wrong. Embabel turns a tool's exception into a tool error for the model, so the exception the
  toolbox throws says nothing about the data.
- **`AgentAccessPolicy`** — `check(caller): AgentAccess` (`Allowed` or `Denied(reason)`), one bean,
  `@ConditionalOnMissingBean`, default `ALLOW_ALL`. `GET /api/agent/status` asks it per caller and reports
  `enabled: false` when denied; `POST /api/agent/ask` asks it after the permission, question and availability checks
  and before the run, and answers `403` with the reason. The model is never called. It is asked only when the server
  has a model at all, so a server without a key keeps answering `503`.
- **`AgentRunListener`** — `onRun(caller, question, answer, usage, error)`. Every bean, in `@Order`, once per question
  that reached the run: an answer, a truncated run (`answer.truncated`) or a failure (`answer` null, `error` set). A
  question refused before the run (permission, policy, no model, blank) is not a run. A listener that throws fails
  the request (a failure's own error keeps it as a suppressed exception): an app that must record every interaction
  would rather not answer unrecorded.
- **`AgentUsage(model, inputTokens, outputTokens)`** is summed over the `LlmInvocation`s Embabel recorded on the run's
  process — every model call, the tool loop's and a retried action's included; `model` names each model used once,
  comma-separated. The action hands its process to the run (`AgentRun.attach`), so the usage of a truncated or failed
  run is still there. None recorded, `null`. `AgentAnswer` gains `usage`, which the response leaves out when null.

`AgentService.status()` became `suspend`, since it now reads the caller. Its default answer is unchanged.

## Consequences

- An app can pseudonymize what leaves, switch the assistant on per organization and log every interaction with its
  tokens, without closing the module's endpoint. SGSPE can drop its workaround.
- ADR-014's claim is now two claims: the assistant cannot exceed its user (unchanged), and what it sends a provider
  is what the app's filters let through. A filter is the app's code and sees every tool result; it runs as the caller,
  never with more rights.
- A filter must cope with both the JSON and the one-line summary; one that parses JSON hands non-JSON text back.
- `usage` appears in `POST /api/agent/ask` whenever the provider reports token counts, which a real one does: an
  additive key. With no beans and a model that reports none (the scripted test doubles), the JSON is byte for byte
  what it was. Observable differences from the original app: ADR-031 D43.
- Tested by `AgentExtensionsTest` (on Embabel's in-memory platform with a recording scripted model),
  `WasichaiAgentAutoConfigurationTest`, and the integration tests `AgentAccessPolicyApiTest` and `AgentEmbabelTest`.
