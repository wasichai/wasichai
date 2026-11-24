# Agent module

An app that installs this module gets an AI assistant that answers questions about the tenant's own objects,
records and geometries in plain language, and shows the tools it used to get there
([ADR-014](../adr/0014-agents-through-the-api.md)). The assistant runs on Embabel
([ADR-015](../adr/0015-embabel-as-the-agent-runtime.md)) and reads through the platform's own services, so it never
sees more than the asking user is allowed to see.

## Install

```kotlin
implementation("wasichai:wasichai-spring-boot-starter-agent")
```

```bash
yarn add @wasichai/agent
```

```tsx
<WasichaiApp modules={[agentModule()]} />
```

See [../guides/build-your-app.md](../guides/build-your-app.md).

## What it adds

The assistant: nine read-only tools over the caller's own objects, definitions, records, relationships, history
and workflow transitions — never a write tool ([ADR-014](../adr/0014-agents-through-the-api.md)). Every tool calls a
core or workflow service as the caller, so the app's read scope holds for it too: a record out of scope is refused as
missing and left out of every count ([ADR-048](../adr/0048-a-read-scope-narrows-what-a-caller-reads.md)).

REST routes:

| Method | Path |
|---|---|
| GET | `/api/agent/status` |
| POST | `/api/agent/ask` |
| GET, POST | `/api/agent/conversations` |
| GET, PATCH, DELETE | `/api/agent/conversations/{id}` |
| POST | `/api/agent/conversations/{id}/messages` |
| GET, PUT | `/api/agent/settings` |

`/ask` answers one question and remembers nothing. A **conversation** ([ADR-087](../adr/0087-assistant-conversations-app-tools-and-model-per-user.md))
belongs to the user who created it (anyone else gets `404`); `POST …/messages` with `{question}` asks with the last
`history-turns` answered exchanges, text only, and answers `{question, answer}`, both stored messages (the answer with
its `steps`, `usage`, `model` and `truncated`). The first question titles it; `PATCH {title}` renames it; `DELETE`
archives it. `GET /settings` answers `{model, selected, defaultModel, models}`; `PUT {model}` picks one of `models`
(`400` otherwise, null clears it).

Frontend routes and slots:

| Slot | Contribution |
|---|---|
| route `agent:assistant` | `/automation/assistant` → `AssistantPage` (lazy) |
| nav | "Asistente" / "Assistant" in core's automation group, order 40 |
| i18n | namespace `agent` (es, en) |

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `wasichai.agent.enabled` | `true` | `false` removes the agent beans, routes and menu entry |
| `wasichai.agent.api-key` | *(empty)* | the model provider's API key |
| `wasichai.agent.model` | `claude-haiku-5-5` | the model name passed to Embabel |
| `wasichai.agent.max-tokens` | `16000` | clamped to 1024–64000 before it reaches the model |
| `wasichai.agent.max-iterations` | `8` | clamped to 1–16; how many actions one run may take before the platform stops it |
| `wasichai.agent.models` | *(empty)* | models a user may pick besides `model`; each must be in Embabel's catalogue |
| `wasichai.agent.history-turns` | `10` | clamped to 0–50; earlier exchanges sent with a question in a conversation |

Env form: `WASICHAI_AGENT_ENABLED`, `WASICHAI_AGENT_API_KEY`, `WASICHAI_AGENT_MODEL`, `WASICHAI_AGENT_MAX_TOKENS`,
`WASICHAI_AGENT_MAX_ITERATIONS`.

The assistant is `available` (as reported by `GET /api/agent/status`) only when the module is enabled, an API
key is present and the app's `AgentAccessPolicy` allows the caller (see below). `EmbabelGate` also binds
`wasichai.agent.api-key` to `${ANTHROPIC_API_KEY:}` by default, so the key usually arrives as the `ANTHROPIC_API_KEY`
environment variable rather than as `wasichai.agent.api-key` directly; the gate also accepts
`embabel.agent.platform.models.anthropic.api-key`, Embabel's own property. Without a key, or with the module disabled,
`EmbabelGate` keeps every Embabel auto-configuration out of the context so the app still boots.

## Extension points

**Defines:** `wasichai.agent.RecordTransitions`, a port the assistant's `available_transitions` tool calls.
`WasichaiAgentWorkflowAutoConfiguration` supplies `WorkflowRecordTransitions` when `wasichai-workflow` is on the
classpath and its `WorkflowService` bean exists; otherwise `NoRecordTransitions` answers an empty list, the same
answer "no workflow" has always given.

**Defines, for what leaves the platform** ([ADR-056](../adr/0056-what-reaches-the-model-is-the-apps-to-shape.md)).
Each has a no-op default; with none declared the assistant is the one it always was.

| Bean | Called | Default |
|---|---|---|
| `AgentResultFilter` — `filter(caller, tool, input, resultJson)` | on each tool result and step `summary` | none |
| `AgentAnswerFilter` — `restore(caller, answer)` | on the final text, not on a truncated run | none |
| `AgentAccessPolicy` — `check(caller)`: `Allowed` or `Denied(reason)` | by `status` and `ask`, before anything is sent | `ALLOW_ALL` |
| `AgentRunListener` — `onRun(caller, question, answer, usage, error)` | once per question that reached the run | none |

Filters and listeners: every bean, in `@Order`. The policy: one bean, `@ConditionalOnMissingBean`.

- **Result filters** run as the asking caller, in the same reactive context as the tools. A filter gets both the JSON
  for the model and the one-line summary the UI shows: one that parses JSON must hand other text back unchanged. To
  pseudonymize reversibly, replace a value with a token in the filter and the token with the value in an
  `AgentAnswerFilter`.
- **A filter that throws fails the run closed**: the result is not handed to the model, the tool loop is asked to stop
  before its next model call, later tool calls and retried actions stop at once, and `ask` answers with the filter's
  own `WasichaiException` or a `500` (`AgentFilterException`, "nothing was sent").
- **The policy** is asked only when the server has a model (enabled and a key). Denied: `status` says
  `enabled: false` for that caller, and `ask` answers `403` with the reason (`AgentDeniedException`). It is asked after
  the permission and question checks.
- **Listeners** get `answer` null and `error` set on a failure, and `usage` (`AgentUsage(model, inputTokens,
  outputTokens)`) whenever Embabel recorded model calls on the run, failures and truncated runs included, summed over
  every call (the tool loop's and a retried action's). A question refused before the run (permission, policy, no
  model, blank question) is not reported. A listener that throws fails the request.

```kotlin
@Bean
fun agentAccessPolicy(features: MyFeatures) =
    AgentAccessPolicy { caller ->
        if (features.assistantOn(caller.organizationId)) AgentAccess.Allowed
        else AgentAccess.Denied("The AI assistant is not enabled for your organization")
    }

@Bean
fun pseudonyms(vault: MyPseudonyms) = AgentResultFilter { caller, _, _, text -> vault.pseudonymize(caller, text) }

@Bean
fun restore(vault: MyPseudonyms) = AgentAnswerFilter { caller, text -> vault.restore(caller, text) }

@Bean
fun aiLog(log: MyAiLog) =
    AgentRunListener { caller, question, answer, usage, error -> log.record(caller, question, answer, usage, error) }
```

**Defines, for what an app adds** ([ADR-087](../adr/0087-assistant-conversations-app-tools-and-model-per-user.md)):

| Bean | Called | Default |
|---|---|---|
| `AgentToolContributor` — `tools(scope)`: tool objects with `@LlmTool` methods | once per question | none |
| `AgentInstructions` — `instructions(caller)`: text appended to the system prompt | once per question, as the caller | none |

An app tool must do its work inside `scope.call(name, input) { AgentToolReply(payload, summary) }`: that runs it as
the caller, turns a `WasichaiException` into `{"error"}` for the model, passes the result through the
`AgentResultFilter`s and records the step. Read-only, like the platform's tools.

```kotlin
class TaxTools(private val scope: AgentToolScope, private val gaps: GapService) {
    @LlmTool(name = "tax_gap", description = "The tax gap of one fiscal year")
    fun taxGap(@LlmTool.Param(description = "Fiscal year") year: Int): String =
        scope.call("tax_gap", mapOf("year" to year)) { AgentToolReply(gaps.panel(year), "gap of $year") }
}

@Bean
fun taxTools(gaps: GapService) = AgentToolContributor { scope -> listOf(TaxTools(scope, gaps)) }
```

**Overridable beans:** `agentTools`, `wasichaiAgent`, `agentAccessPolicy`, `agentService`, `agentController`,
`agentSettingsService`, `agentConversationService`, `agentConversationController` (`WasichaiAgentAutoConfiguration`) and `workflowRecordTransitions` (`WasichaiAgentWorkflowAutoConfiguration`) — all
`@ConditionalOnMissingBean`, so an app can replace any of them.

## Database

Migrations in `db/wasichai/agent` (history table `flyway_history_agent`): `agent_conversations`, `agent_messages` and
`agent_user_settings` (V1). Conversations go with their user and organization (FK cascade).

## Frontend package

`@wasichai/agent`: `agentModule(options)`, with `options.basePath` (default `'automation'`) prefixing the route.
Main exports from `index.ts`: `agentModule`, `AGENT_MODULE_ID`, the `AgentModuleOptions` type, `agentMessages`,
`AssistantPage`, the query hooks `useAgentStatus` and `useAskAgent` (for an app that wants to ask from its own
screens), and the `AgentAnswer`/`AgentStatus`/`AgentStep` types. i18n namespace `agent`.

The only runtime dependency is `lucide-react` (the nav icon); `@wasichai/core`, `@wasichai/ui`, `@tanstack/react-query`,
`i18next`, `react`, `react-dom`, `react-i18next` and `react-router` are peers.

See [`packages/agent` in wasichai-ui](https://github.com/wasichai/wasichai-ui/tree/main/packages/agent) for the full API.

## Without this module

No assistant screen and no nav entry. `GET /api/agent/status` and `POST /api/agent/ask` answer `404`
([ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D1).

## Behaviour differences

[ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D43: an answer carries `usage` when the provider
reported token counts, and an app's policy, filters and listeners can change what status, ask and the steps say.

[ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D7: the original app built Anthropic in.
`wasichai-agent` is provider-neutral — it depends only on `embabel-agent-starter` — and
`wasichai-spring-boot-starter-agent` adds Anthropic as the default provider. An app can exclude it and depend on
another Embabel provider starter instead.

[ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D61: the assistant keeps each user's conversations, asks
with the earlier exchanges, takes an app's tools and rules, and lets each user pick a model from the app's list.

## Known limitations

- No write tools: the assistant can read the tenant's data but cannot create records, edit them or move a workflow
  state. The confirmation step that would make a write safe does not exist yet
  ([ADR-014](../adr/0014-agents-through-the-api.md)).
- The model must be in Embabel 1.5.4's Anthropic catalogue. The default is `claude-haiku-5-5`; `claude-sonnet-5-5`,
  `claude-opus-5-5` and `claude-fable-5-1` are one `WASICHAI_AGENT_MODEL` away
  ([ADR-073](../adr/0073-claude-haiku-5-5-as-the-default-model.md)).
