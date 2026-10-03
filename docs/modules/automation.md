# Automation module

An app that installs this module lets an admin write rules that answer record changes: a trigger, optional
conditions, and one or more actions. A background drain runs the matched actions off the request that caused
them, and every run is logged, so "why did my record change" and "why did nothing happen" both have an answer.

## Install

```kotlin
implementation("wasichai:wasichai-spring-boot-starter-automation")
```

```bash
yarn add @wasichai/automation
```

```tsx
<WasichaiApp modules={[automationModule()]} />
```

See [../guides/build-your-app.md](../guides/build-your-app.md).

## What it adds

Rules that answer record changes: a trigger (record created, updated, deleted, a named transition applied, or a
state entered), a list of conditions matched against the snapshot of the change, and a list of actions. Matching
happens synchronously, inside the caller's transaction; the actions run later, off the request, claimed from a
queue by a background drain (`AutomationDrain`, polling on a timer) and executed one batch at a time
([ADR-016](../adr/0016-automations-queue-and-system-context.md)). Every match leaves a row in the run log, whether
it ran, was skipped (an unmet condition or the depth cap), or failed.
A record written by the platform (`RecordService.asPlatform`,
[ADR-039](../adr/0039-background-work-runs-as-the-platform-with-a-cluster-lock.md)) triggers rules like any other;
with no user behind the change, the rows its actions write carry a null `created_by`/`updated_by`.

Action kinds (`ActionType`): `UPDATE_FIELD`, `CREATE_RECORD`, `WEBHOOK`, `GENERATE_DOCUMENT`. `WEBHOOK` posts JSON
to an admin-supplied URL; the URL is validated on save and again before every call, and a host that resolves to a
loopback, private, link-local or multicast address is refused unless `wasichai.automation.allow-private-webhooks` is
set, since an automation is the one place the platform makes an outbound call on a user's say-so.

REST routes:

| Method | Path |
|---|---|
| GET | `/api/automation-runs` |
| GET | `/api/objects/{object}/automations` |
| POST | `/api/objects/{object}/automations` |
| GET | `/api/objects/{object}/automations/{name}` |
| PUT | `/api/objects/{object}/automations/{name}` |
| DELETE | `/api/objects/{object}/automations/{name}` |
| GET | `/api/objects/{object}/automations/{name}/runs` |

Frontend routes and slots:

| Slot | Contribution |
|---|---|
| route `automation:rules` | `/automation/rules`: the rule builder (trigger, conditions, actions), lazy |
| route `automation:runs` | `/automation/runs`: the organization-wide run log, lazy |
| nav | group `automation` (core's): "Reglas" (order 20), "Ejecuciones" (order 30) |

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `wasichai.automation.enabled` | `true` | `false` removes the automation beans, routes, drain and migration |
| `wasichai.automation.poll-interval` | `1s` | how often the drain polls the queue; `0` disables the background drain |
| `wasichai.automation.batch-size` | `20` | runs claimed per drain tick, clamped to 1–200 |
| `wasichai.automation.max-depth` | `3` | how many automation-caused changes may chain before the run is skipped, clamped to 1–10 |
| `wasichai.automation.webhook-timeout` | `10s` | timeout for a `WEBHOOK` action's HTTP call |
| `wasichai.automation.allow-private-webhooks` | `false` | `true` allows a webhook host that resolves to a private address |

Env form: `WASICHAI_AUTOMATION_ENABLED`.

## Extension points

**Implements:** `wasichai.core.data.RecordChangeListener`, through `AutomationDispatcher` (matches the trigger and
conditions against the change and queues a run row); `wasichai.core.metadata.FieldUsage`, through
`AutomationFieldUsage` (an automation counts as a user of a field it reads in a condition or writes in an action,
so a field delete warns about it while the admin can still see it).

**Defines:** `DocumentIssuer`, an optional port a `GENERATE_DOCUMENT` action calls to issue a document.
wasichai-documents implements it when both modules are installed; without wasichai-documents, `NoDocumentIssuer`
answers, so the action is refused when it is saved. No auto-configuration order has to be right for this: the bean
is looked up (`ObjectProvider<DocumentIssuer>`), not required.

**Overridable beans:** `automationRepository`, `automationRunRepository`, `automationDispatcher`,
`automationFieldUsage`, `webhookSender`, `automationRunner`, `automationService`, `automationDrain`,
`automationController` — all `@ConditionalOnMissingBean`, so an app can replace any of them. The migration bean is
not: core's own `ModuleMigration` would always back off first.

## Database

Migration location `classpath:db/wasichai/automation`, history table `flyway_history_automation`. Tables:
`automations` (one rule per name and organization: trigger, conditions and actions stored together as
`definition` jsonb) and `automation_runs` (one row per automation matched against one change: status, depth, the
change's payload, the steps it performed once it ran, and its error if it failed). `automation_runs` is claimed
with `FOR UPDATE SKIP LOCKED`, so more than one instance can drain the same queue without stepping on each other.

## Frontend package

`@wasichai/automation`: `automationModule(options)`, with `options.basePath` (default `'automation'`) prefixing both
routes. Main exports from `index.ts`: `automationModule`, `automationMessages`, `AutomationBuilderPage`,
`AutomationRunsPage`, `RunTable`, the query hooks (`useAutomations`, `useSaveAutomation`, `useDeleteAutomation`,
`useAutomationRuns`, `useRecentRuns`, `useWorkflowOutline`, `useDocumentTypeOptions`, plus their query-key
factories `workflowOutlineQuery` and `documentTypeOptionsQuery`), and the automation types. i18n namespace
`automation`.

The package works with or without `@wasichai/workflow` and `@wasichai/documents`: it reads an object's workflow and
document types under the same query keys those modules use, so the cache is shared; when either module is absent
its endpoint answers 404 and the corresponding picker in the builder stays empty.

See [`packages/automation` in wasichai-ui](https://github.com/wasichai/wasichai-ui/tree/main/packages/automation) for the full API.

## Without this module

Nothing reacts to record changes: no rows are matched or queued, and the rule builder and run log routes answer
`404` ([ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D1), so the frontend shows neither nav entry's
target. Any other module's `GENERATE_DOCUMENT`-shaped feature has nothing to call into, since `DocumentIssuer` is
this module's own port.

## Behaviour differences

[ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D3: a `GENERATE_DOCUMENT` automation action is refused
at save time when wasichai-documents is absent, rather than failing later.

## Known limitations

- `AutomationDispatcher` runs as a `RecordChangeListener`, synchronously inside the caller's request, without a
  transaction of its own ([ADR-025](../adr/0025-extension-spis.md)): matching a change and writing the
  `automation_runs` row land in whatever transaction the triggering write already opened.
