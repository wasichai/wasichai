# ADR-046: A notifications module: audience matched on read, keyed sources that resolve themselves, tab keys

**Status**: accepted · 2026-10-06 · builds on [ADR-024](0024-libraries-and-starters.md),
[ADR-025](0025-extension-spis.md), [ADR-039](0039-background-work-runs-as-the-platform-with-a-cluster-lock.md),
[ADR-045](0045-organizational-units.md) · design:
[the spec](../superpowers/specs/2026-10-06-notifications-design.md)

## Context

srtm and caja compute what their staff must do (deadlines in business days, a turno left open, a payment charged and
never recorded) and show it only on the screen that computes it, or in a log line. Staff need to be told, by person,
role or unit, within a time window: information with a link to a law or a manual, a warning that something expires, a
call to action that opens the screen and the tab where the work is done. Most of these states are computed, a few are
plain date fields, and some are events in the app's own code.

## Decision

**An opt-in module, `wasichai-notifications`** (`wasichai.notifications.enabled`, its own migrations and starter). Its
UI label is "Alertas"; the code says notifications because wasichai-ui already has an `Alert` primitive.

- **Three kinds:** `INFO`, `WARNING`, `ACTION`. A notification has a title, a plain-text body, an optional link, a
  window (`publish_at`, `expires_at`) and an optional `due_at`.
- **The audience is matched when the inbox is read**, not copied per person: targets `ALL`, `USER`, `ROLE` (the token's
  roles), `UNIT` (the reader's units and their ancestors). One row reaches a whole role; a new member sees what is
  open; nothing fans out. An email is turned into a user when published.
- **Per-person state** lives in receipts (read, dismissed, snoozed); no row is unread.
- **A link is data, not a URL:** `RECORD` (object, id, tab), `ROUTE` (a wasichai-ui route key, params, tab) or `URL`.
  The UI builds the address with its own link helpers. A `RECORD` link is dropped for a reader without `READ` on the
  object.
- **A TAB gets a stable key** in the pages module (`DETAILS`, `RELATED`, `HISTORY`, `MAP` on generated pages; any
  upper-case token on built ones), so a link can name a tab. The module checks the key's format only; the UI falls
  back to the first tab.
- **Three producers, one table:** people over REST (`MANAGE_ORGANIZATION`), app code through the `Notifications` bean
  (joins the caller's transaction) and `NotificationSource` beans run on a schedule, and metadata date rules over
  `DATE`/`DATETIME` fields managed over REST (`MANAGE_METADATA` on the object).
- **Keyed and self-resolving:** what a source or a rule produces has a key; publishing a key again is an upsert that
  writes nothing when its fingerprint is unchanged; a key a source no longer returns is resolved. Receipts reset only
  when a notification reopens or changes kind, so "vence en 5 días" becoming "vence en 4" is not news, WARNING becoming
  ACTION is.
- **Strict over REST, lenient from Kotlin:** an unknown recipient is a `400` for a person, and dropped with a WARN for
  code, so a person who left never rolls back a business transaction.
- **The module runs its own loop.** ADR-039 left loops to apps because no library work needed one; this work is the
  module's, as `AutomationDrain` is automation's. Each source runs once per interval across replicas
  (`ClusterLock.tryLock`, `notification_source_runs`), as the platform (`RecordService.asPlatform`), per organization;
  a failing organization does not stop the others. A record change re-evaluates date rules for that record at once
  (`RecordChangeListener`). Resolved and expired notifications are purged after a retention.
- **Live delivery** is ADR-047.

Left out until a second user asks: email, a required acknowledgement, a banner flag, an audience taken from the record,
rules on a workflow state's age, an automation `NOTIFY` action, publishing rights scoped to a unit.

## Consequences

- srtm and caja publish from code, declare sources for computed states, and apply date rules and units with their
  model scripts; their plans say how.
- Sources must aggregate: a per-record rule is capped (`rule-max-notifications`), because an inbox that floods is
  ignored.
- A notification of a source or a rule cannot be edited or deleted by hand (`409`), and an `ACTION` of one cannot be
  dismissed: it leaves when the work is done.
- New routes, tables and a tab key are deviations D32 and D33 of [ADR-031](0031-deliberate-deviations-from-sapgis.md).
