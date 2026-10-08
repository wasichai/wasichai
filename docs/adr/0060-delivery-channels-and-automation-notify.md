# ADR-060: Notifications leave the app through delivery channels, fanned out to people when news is written

**Status**: accepted · 2026-10-08 · builds on [ADR-046](0046-notifications-module.md),
[ADR-039](0039-background-work-runs-as-the-platform-with-a-cluster-lock.md), [ADR-034](0034-user-preferences-and-themes.md),
[ADR-045](0045-organizational-units.md) and [ADR-024](0024-libraries-and-starters.md)

## Context

[#57](https://github.com/wasichai/wasichai/issues/57) asked for notifications with an in-app inbox, email delivery
with retries, a per-person choice of what goes by email, and an automation action that notifies. The module of
ADR-046 already holds the inbox, the audiences, the producers and the cluster-locked loop; it left email and an
automation `NOTIFY` out "until a second user asks". SGSPE is that user: tasks assigned, approvals requested and
deadlines missed are driven by workflow transitions, and its people must hear of them outside the app too.

ADR-046 matches the audience **on read**: one row reaches a whole role, a unit's subtree or everyone, and nothing is
copied per person. A channel outside the app (an SMTP server) needs names and addresses, and must not send twice.

## Decision

**A channel SPI, `DeliveryChannel`** (`name`, `suspend fun deliver(message, recipient)`). The in-app inbox is always
on and is not a channel. The module ships one channel, email; an app adds its own as a bean. A channel's name matches
`^[a-z][a-z0-9-]{1,30}$`, is unique and is never `in-app`, or the app does not start. The issue named the interface
`NotificationChannel`; the module already had an object of that name (the `LISTEN` channel of ADR-047), so the SPI is
`DeliveryChannel`.

**News is fanned out when it is written, in the writer's transaction.** "News" is what restarts receipts in ADR-046:
a notification created, reopened, or whose kind changed. `NotificationRepository` tells `Deliveries` at exactly those
points, so every producer (REST, `Notifications.publish`, sources, rules, automation) gets it without code of its own.
`Deliveries` names the people the audience reaches **at that moment** through core's ports (ADR-045), each enabled
and of the tenant: `UserDirectory.enabledIds` for `ALL`, `existing` for users, the new
`RoleDirectory.holderIds` (stored roles, as the next sign-in puts them in the token) and the new
`OrgUnitDirectory.memberIdsWithin` (a unit's subtree, the other direction of `closureOf`). It writes one
`notification_deliveries` row per person and channel (`PENDING`, `next_attempt_at` = the publication, or now), minus
the people whose preference leaves the kind out. A rollback takes the rows with the notification. An edit that is not
news (a new count in a title) sends nothing: an inbox updated in place must not become an email per change. News again
resets the row (unique per notification, person and channel), so one news is one copy. The inbox keeps matching on
read; the rows only drive channels. With no channel in the app nothing is fanned out, written or scheduled.

**A worker sends later, once per cluster.** `DeliveryWork` is one more item of the notifications loop (key
`deliveries`, every `delivery-interval`, 1 minute), so it runs under `ClusterLock.tryLock` for every organization
(ADR-039): N replicas send once. Per organization and run it first marks `SKIPPED` what ended (resolved or expired)
before its turn, then takes at most `delivery-batch` (100) due rows of the app's channels, oldest first, whose
notification is open and published. A recipient no longer enabled is `SKIPPED`. `deliver` throwing is a failed
attempt: `attempts` and `last_error` are kept and `next_attempt_at` moves by `delivery-backoff` (1 minute) doubled
per failure; at `delivery-max-attempts` (5) the row is `FAILED` and keeps its error. A channel taken out of the app
leaves its rows waiting, not failing. Delivery is at least once: a replica that dies between `deliver` and the update
sends again. The originating write never waits on, and never fails because of, a channel: it only inserts rows.

**A message never carries a `RECORD` link.** The inbox drops one per reader without `READ` on its object; a channel
cannot ask, so `DeliveryMessage.link` is a `ROUTE` or `URL` link, or none.

**Email over Spring Mail, off unless asked.** `spring-boot-mail` is a `compileOnly` dependency of the module; the app
brings `spring-boot-starter-mail` and `spring.mail.*`. `WasichaiNotificationsEmailAutoConfiguration` declares the
`emailChannel` bean (name `email`) only with `wasichai.notifications.email.enabled=true` and a `MailSender` bean;
`wasichai.notifications.email.from` is then required, or the start fails. It sends plain text: `subject-prefix` plus
the title as subject, the body (or the title) as text, on `Dispatchers.IO`. An app wanting HTML or a link to its own
screens declares its own bean named `emailChannel`, or another channel.

**Preferences in a table of the module.** Core's `user_preferences` (ADR-034) has fixed columns and a closed set of
keys that core validates, and core never names a module, so the module keeps `notification_preferences` (user,
channel, kinds). `GET/PUT /api/auth/me/notification-preferences` follows ADR-034: a map of channel to kinds, a missing
key keeps, `[]` stops the channel, no row is every kind. Every kind by default: an app that turns email on wants its
people to get it, and each person narrows it. A service account gets `403`.

**`NOTIFY` in automation through a port, like `GENERATE_DOCUMENT`.** Automation defines `AutomationNotifier` (with a
`NoAutomationNotifier` fallback) and never names the notifications module; the module implements it in
`AutomationNotifierAdapter`, loaded by `WasichaiNotificationsAutomationAutoConfiguration` only when automation is on
the classpath (`compileOnly`, M2), the documents module's pattern. The action is
`{ "type": "NOTIFY", "to", "title", "body"?, "kind"? }`; `to`, `title` and `body` take `{{field}}` like the other
actions. `to` is a comma-separated list of a user id, an email, `role:<NAME>` or `unit:<CODE>`; an entry naming
nobody is dropped (an empty `{{owner}}` must not fail the run). The source is `automation:<name>`, now reserved like
`manual` and `rule:`; the key is `<recordId>.<action index>`, so entering a state again reopens or updates the same
notification and is news again, never a second row. `kind` is `INFO` or `WARNING`: an `ACTION` of a source cannot be
dismissed and waits to be resolved, and nothing in an automation resolves it. Without the module, saving a `NOTIFY` is
a `400`, and a stored one fails its run, never the boot.

**Retention.** ADR-046's daily purge already deletes resolved or expired notifications after `retention`; delivery
rows go with their notification (`ON DELETE CASCADE`), so there is no purge of their own. Read notifications that are
still open stay: under ADR-046 the inbox shows what is still true, and a source resolves what is done.

## Consequences

- One more migration of the module (`V2__deliveries.sql`): `notification_deliveries`, `notification_preferences`.
- New routes `GET` and `PUT /api/auth/me/notification-preferences`, a new action `NOTIFY` and the reserved source
  prefix `automation:` and loop key `deliveries` (ADR-031 D46). The tables are a known schema-parity deviation.
- Who gets an email is who the audience reached when the news was written; someone who joins a role later sees the
  notification in the inbox but gets no email for it. A role is read from stored roles, not from a token.
- A fan-out to `ALL` writes one row per enabled person and channel: an app that emails a whole organization pays a row
  per person. A source must still aggregate (ADR-046).
- Core gains two port methods and `UserDirectory.enabledIds`; it still names no module.
- Tested by `DeliveriesTest`, `AutomationNotifyTest`, `DeliveryChannelsWiringTest`, `DeliveriesMigrationSqlTest`,
  `OrgUnitDirectoryTest`, `NoAutomationNotifierTest`, `WasichaiAutomationAutoConfigurationTest`,
  `NotificationsOnlyApiTest`, `AutomationOnlyApiTest` and `AutomationApiTest`.
