# ADR-016: Automations are queued work that runs as the platform

**Status**: accepted · 2026-09-18

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).
>
> Moved from chawpi on 2026-09-26: identifiers renamed chawpi → wasichai; the decision is unchanged. See
> [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md) and [the origin page](../chawpi-origin.md).

## Context

The spec asks for automations: when something happens to a record, the platform does something. Two
questions decide the whole design — *when* an automation runs relative to the change that triggered
it, and *as whom* it acts.

Getting the first one wrong is the classic failure. Running rules inline, inside the request, means a
webhook that hangs for ten seconds hangs the user's `PUT`, and a rule that throws rolls back a write
the user already saw succeed. Running them after the fact without a record of it means "nothing
happened" has no answer.

## Decision

**Matching is synchronous, acting is not.** When a record changes, `AutomationDispatcher` runs inside
the caller's transaction: it loads the automations watching that object, matches the trigger, and
evaluates the conditions against the snapshot of the change. Each automation that matched leaves a
row in `wasichai.automation_runs` — `PENDING` when it will act, `SKIPPED` with a reason when it will
not. `AutomationRunner` claims pending rows (`FOR UPDATE SKIP LOCKED`) and performs the actions off
the request; `AutomationDrain` polls the queue on a timer that tests turn off.

Deciding *later* was the alternative. It was rejected because the row the rule was written about may
have moved on by then: the condition would judge a state of the world that never triggered anything.

**Conditions read the snapshot, writes land on the row as it is now.** A condition asks what
happened; an `UPDATE_FIELD` action re-reads the record and writes the current values plus its own
field. Reusing the snapshot as an update body would blank whatever changed in between.

**The snapshot is the whole record, not the caller's projection.** Field permissions decide what a
user may see; they must not decide what a rule may judge. The record path therefore hands the
dispatcher the unfiltered row, before it filters the response.

**Automations act as the platform.** No user sits behind a queued run, so the runner asks
`CurrentUser` nothing and works from the organization that owns the record. Audit rows for automation
writes carry a null user, which reads as "the platform did this". Running rules as the triggering
user was the alternative: it makes an automation fail for exactly the restricted users it exists to
help, and makes what a rule does depend on who touched the record.

**Loops are bounded twice.** An automation never answers its own writes (the change carries the
automation that caused it), and every automation-caused change is one step deeper than the one before
it, up to `wasichai.automation.max-depth`. Past it, the run is recorded as `SKIPPED` saying so.

**A webhook URL is checked on save and again before every call.** Only `http(s)`, and the host must
not resolve to a loopback, private, link-local or multicast address unless
`wasichai.automation.allow-private-webhooks` says otherwise. An automation is the one place where the
platform makes an outbound call on a user's say-so, which is a request forgery waiting to happen.

## Consequences

- A slow or failing action never touches the request that triggered it. A failure is a `FAILED` run
  with its message, not a 500 the user cannot explain.
- The run log is the feature, not a byproduct: "why did my record change" and "why did nothing
  happen" are answered in the same table, per rule and per organization.
- The queue is durable. A restart mid-flight leaves the work `RUNNING`; the row stays, with its
  attempt count, rather than the automation vanishing.
- Automations bypass user permissions by construction. That is the point, and it is why only
  `MANAGE_METADATA` may write one. Reading a rule's runs needs only `READ` on its object, so the
  person whose record changed can find out why.
- `RecordTransitioned` is gone. It existed as the seam for this phase; the seam is now the
  `RecordChangeListener` port, and two mechanisms for one thing is worse than one.
- The engine is deterministic — no model decides whether a field gets written. An agent-backed action
  is a later decision (ADR-015), not this one.
