# Notifications ("Alertas"), wasichai-ui Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** wasichai-ui shows people what needs doing: a bell with the unread count in the shell, live over SSE; a page
"Alertas" with three tabs (Comunicados, Advertencias, Pendientes); screens to compose manual notifications and to build
date rules; organizational units in the admin; and links that open a record on the right tab (`?tab=KEY`).

**Architecture:** a new package `@wasichai/notifications`, registered with `notificationsModule()`, owns the types, the
REST calls, a `fetch`-based SSE client, one stream shared by every browser tab (Web Locks + `BroadcastChannel`), the
hooks, link resolution and the screens. `@wasichai/core` gains four small things: a `shellActions` slot drawn by
`AppShell` (the bell), `links.routeParams(route)`, `?tab=KEY` in `PageRenderer` (with `PageComponent.key`) and the
organizational-unit admin. `@wasichai/pages` edits TAB keys. Apps with their own shell (srtm-ui, caja-ui) mount the
package's provider and components directly and pass their own link resolver.

**Tech Stack:** React 19.3, TypeScript, Vite 8, Tailwind CSS 4, react-query, i18next / react-i18next, react-router,
vitest + testing-library, Playwright (full-sample), yarn 1 workspaces.

**Spec:** [`docs/superpowers/specs/2026-10-06-notifications-design.md`](../specs/2026-10-06-notifications-design.md)
(wasichai), sections B (REST, wire shapes), D (stream) and E (TAB key). Backend plan:
[`2026-10-06-notifications-backend.md`](2026-10-06-notifications-backend.md). Registry contract:
[ADR-028](../../adr/0028-frontend-module-registry.md).

**Repositories:** tasks 1–14 in `../wasichai-ui`, branch `feat/notifications` off `origin/dev`, PR to `dev`. Task 15 in
`../full-sample`, branch `feat/notifications`. Task 16 in `../wasichai` (docs), on the branch that carries 0.4.0's docs.

## Global Constraints

- **Backend first (ADR-032).** wasichai 0.4.0 (core `V9`, `wasichai-notifications`, TAB `key`) is released before this
  plan's pre-release; until then develop against a local `./gradlew bootRun` of the backend branch. Every route, body
  and field name is the spec's; a task never invents a variant. A mismatch is fixed in the backend first.
- **The token never goes in a URL.** The stream sends `Authorization: Bearer` from `getToken()`; no `EventSource`.
- **Core never imports a module (ADR-028).** The bell reaches the shell only through the new `shellActions` slot.
- **No literal paths or storage keys in core** (core's `boundaries.test.ts`): new core paths go in `CORE_ROUTE_PATHS`;
  the lock and channel names derive from `config.storagePrefix` (`<prefix>.notifications`).
- **A body is plain text**: rendered as text (`whitespace-pre-line`), never as HTML; a title likewise.
- **A `404` from a notifications endpoint means "not installed"** (ADR-028, ADR-031): the bell and the screens hide,
  no error is shown. A `403` from `/api/auth/me/notifications/**` (a service account) hides the bell too.
- Strings through i18n, es and en. Spanish label **"Alertas"**, tabs **Comunicados / Advertencias / Pendientes**; English
  **"Notifications"**, tabs **Announcements / Warnings / To do**. Never "notificación" or "aviso" for the feature.
- `.editorconfig`: TS/TSX 2 spaces, JSON and CSS 4, max 160 columns, LF. `yarn lint && yarn test && yarn build &&
  yarn format:check` before each commit touching code. Conventional Commits with the package as scope
  (`feat(notifications)`, `feat(core)`, `feat(pages)`, `feat(ui)`), each message ending with the session's attribution
  lines. Pushing a `v*-dev.*` tag publishes: ask the user first.
- Comments in English, caveman style: short, say why.

## Review Focus

1. **Token handling:** the token is read per connection attempt, sent only as a header, never logged; `401` calls
   `signOut()` once; sign-out (in this tab or another, `storage` event on the token key) aborts the stream (Task 7, 8).
2. **One stream per browser:** two tabs of one origin open one `/stream` request; closing the leader hands the lock to
   another tab within a second; without Web Locks or `BroadcastChannel` each tab streams on its own (Task 8, Task 15).
3. **Never silent:** after a failed connect the summary is polled every 60 s until the stream is back; backoff is
   capped at 30 s with full jitter; a `403`/`404` stops both (Task 8).
4. **Links:** an unknown route key gives no link and no throw; a URL link opens with `rel="noopener noreferrer"` and only
   for `http`/`https`; ROUTE params that are not path segments go to the query string; `tab` always goes as `?tab=`
   (Task 9).
5. **`?tab=KEY`:** a key inside a nested strip selects every strip on its way; an unknown key falls back to the first
   tab; a page without keys behaves as before (Task 3).
6. **No flood of toasts:** the first summary after load sets the baseline; a toast appears only for a `latest` newer
   than the baseline, once per id per tab (Task 10).
7. **Boundaries:** core does not import `@wasichai/notifications`; `@wasichai/ui` imports nothing new from core.

---

### Task 1: `shellActions`, a slot in the shell (core)

**Files:**
- Modify: `packages/core/src/registry/contract.ts` (`ShellAction { key: string; order?: number; component: ComponentType }`,
  `WasichaiModule.shellActions?: ShellAction[]`), `packages/core/src/registry/createRegistry.ts` (merge, sort by
  `order` then key; duplicate key → `RegistryError` naming both modules), `packages/core/src/shell/AppShell.tsx` (draw
  them in the user area, before the language and sign-out buttons).
- Test: `packages/core/src/registry/createRegistry.test.ts`, `packages/core/src/shell/AppShell.test.tsx`.

**Tests:** two modules' actions drawn in order; a duplicate key refused at start-up; a shell without actions renders as
today (existing AppShell cases stay green).

- [ ] Branch: `git switch -c feat/notifications origin/dev && yarn install`.
- [ ] Write the tests; `yarn --cwd packages/core vitest run src/registry src/shell` → FAIL.
- [ ] Implement; tests → PASS. ADR-028 says a slot needs a second user: Task 16 records why this one goes in now.
- [ ] Commit `feat(core): a shellActions slot in the shell`.

### Task 2: `links.routeParams(route)` (core)

ROUTE links carry `params` without saying which are path segments; the UI decides (Review Focus 4).

**Files:** modify `packages/core/src/links/links.ts` (`routeParams(route): string[]`, the `:name` segments of a
registered route, `[]` for an unknown one); test `packages/core/src/links/links.test.ts`.

- [ ] Test (`routeParams('caja:arqueo')` → `['turnoId']`; unknown → `[]`); implement; commit
  `feat(core): links name a route's path params`.

### Task 3: a TAB has a key, and `?tab=KEY` opens it (core, ui)

**Files:**
- Modify: `packages/core/src/types/metadata.ts` (`PageComponent.key?: string`).
- Modify: `packages/ui/src/tabs.tsx` (optional controlled `value?: string` and `onValueChange?(id: string)`; uncontrolled
  stays as is) and `packages/ui/src/tabs.test.tsx`.
- Modify: `packages/core/src/components/page-renderer/PageRenderer.tsx`: a tab's id is `key ?? \`${title}-${position}\``;
  the renderer reads `useSearchParams().get('tab')`, upper-cases it, finds the TAB with that key and the chain of
  strips above it, and selects each; no match → the first tab. Clicking a keyed tab writes `?tab=KEY` with
  `replace: true`; an unkeyed tab removes the parameter.
- Modify: `packages/core/src/components/page-renderer/fallbackPage.ts`: the fallback tabs carry `DETAILS`, `RELATED`,
  `HISTORY` like the server's generated pages, so `?tab=HISTORY` works without the pages module.
- Test: `PageRenderer.test.tsx`, `fallbackPage.test.ts`.

**Tests:** `?tab=HISTORY` selects History; `?tab=history` too; a key in a nested strip selects outer and inner tabs;
`?tab=NOPE` → first tab; clicking a keyed tab updates the URL without a history entry; a page without keys unchanged.

- [ ] Tests first → FAIL; implement → PASS; commit `feat(core): ?tab=KEY opens a page's tab`.

### Task 4: the page builder edits TAB keys (pages)

**Files:** modify `packages/pages/src/builder/Inspector.tsx` (a "Clave" / "Key" input for a selected TAB, normalised
with `trim().toUpperCase()`, checked against `^[A-Z][A-Z0-9_]{0,39}$` and unique in the whole page, nested strips
included, before save), `packages/pages/src/builder/pageTree.ts` (collect keys), the pages i18n bundles; tests
`Inspector.test.tsx`, `pageTree.test.ts`.

**Tests:** typing `resoluciones` stores `RESOLUCIONES`; a bad format or a repeated key blocks the save with the reason;
the server's `400` ("key is only for TAB", repeated key) is shown under the field; a page saved without touching keys
keeps them.

- [ ] Tests first; implement; commit `feat(pages): edit a tab's key`.

### Task 5: organizational units in the admin (core)

**Files:**
- Create: `packages/core/src/features/admin/orgUnits.ts` (types; `buildOrgUnitTree(flat)` from `parentCode`, siblings
  by label; `descendantsOf(code)`), `orgUnitsApi.ts` (react-query hooks over `/api/org-units`,
  `/api/org-units/{code}`, `PUT /api/users/{id}/org-units`), `OrgUnitsPage.tsx`, and tests for the three.
- Modify: `packages/core/src/links/links.ts` (`CORE_ROUTE_PATHS.orgUnits = 'admin/org-units'`), the core module's
  routes and its admin nav group (visible to `permissions.admin`), `packages/core/src/features/admin/UsersPage.tsx` (a
  units multi-select in the user dialog, a "Unidades" column from `AdminUserResponse.orgUnits`), core's `es`/`en`
  bundles (`orgUnits.*`).

**Behaviour:** a foldable tree with member counts; create (code, label, parent), rename, move (the parent select leaves
out the unit and its subtree; the server's `400` for depth or cycles is shown), delete (the `409` "has sub-units or
members" message is shown as is), the members of a unit. Before deleting, the dialog warns that notifications addressed
to the unit stop reaching anyone (the spec has no count for it yet).

**Tests:** tree from a flat list; move dialog excludes the subtree; `409` on delete shown; user dialog saves units and
the table shows them; a `404` from `/api/org-units` (a backend before 0.4.0) hides the nav entry.

- [ ] Tests first; implement; commit `feat(core): organizational units in the admin`.

### Task 6: package `@wasichai/notifications`, types, REST, module

**Files:**
- Create: `packages/notifications/{package.json, tsconfig.json, tsconfig.build.json, vite.config.ts}` copied from a
  light module (`packages/views`), peer dependencies as every module (ADR-028).
- Create: `packages/notifications/src/types.ts` (spec's wire shapes: `NotificationKind`, `NotificationLink` RECORD /
  ROUTE / URL, `Audience`, `InboxItem`, `NotificationSummary`, `AdminNotification`, `NotificationRule`, `RunResult`).
- Create: `packages/notifications/src/api.ts` (one function per route of the spec, through core's `api()`) and
  `queryKeys.ts` (`['notifications', userId, 'summary']`, `['notifications', userId, 'list', filters]`, admin and rule
  keys).
- Create: `packages/notifications/src/i18n/{es,en}.json` and `src/module.ts`: `notificationsModule(options?: {
  resolveLink?: NotificationLinkResolver })` → `id: 'notifications'`, `basePath: 'notifications'`, routes `inbox`
  (`''`), `admin` (`'admin'`), `rules` (`'rules'`), all lazy; nav: "Alertas" for everyone, compose and rules in the
  admin group (`permissions.admin`); `providers: [NotificationsProvider]`; `shellActions: [{ key: 'notifications',
  component: NotificationBell }]`.
- Create: `packages/notifications/src/index.ts`; modify the root `package.json` workspaces and the `boundaries.test.ts`
  list of modules.
- Test: `module.test.ts` (registers with core; route keys `notifications:inbox|admin|rules`), `api.test.ts` (paths,
  bodies, `?kind&state&page&size`).

- [ ] Tests first; implement; `yarn build` builds the package; commit `feat(notifications): the package and its module`.

### Task 7: a `fetch`-based SSE client

**Files:** create `packages/notifications/src/stream/sse.ts` and `sse.test.ts`.

**Interface:** `openSummaryStream({ baseUrl, getToken, onSummary, onStatus, onUnauthorized, signal }): Promise<void>`;
`onStatus('open' | 'retrying' | 'unavailable')`.

**Behaviour:**
- `fetch(\`${baseUrl}/auth/me/notifications/stream\`, { headers: { Accept: 'text/event-stream', Authorization },
  signal })`; no token → return without fetching.
- Parse the body with a `TextDecoder` and a line buffer: `event:` and `data:` lines (several `data:` lines join with
  `\n`), a blank line dispatches, a `:` line is a heartbeat. Only `event: summary` is used; bad JSON is ignored.
- A watchdog aborts the request after 60 s without any byte (two missed heartbeats).
- `401` → `onUnauthorized()` and stop. `403` or `404` → `onStatus('unavailable')` and stop. Other statuses, network
  errors and a dropped body → retry with backoff: `random(0, min(30 s, 1 s × 2^attempt))` (full jitter); the attempt
  counter resets when an event arrives. A stream the server completes (token expiry) reconnects at once with the
  current token. `signal` aborts everything.

**Tests** (a fake `fetch` returning a `ReadableStream`, fake timers): events split across chunks; multi-line data;
heartbeat keeps the watchdog quiet; `401` calls `onUnauthorized` once; `404` stops; backoff never exceeds 30 s and
resets after an event; completion reconnects with a fresh token; abort stops retries.

- [ ] Tests first; implement; commit `feat(notifications): an SSE client over fetch`.

### Task 8: one stream for every tab, polling floor, provider and hooks

**Files:** create `packages/notifications/src/stream/sharedStream.ts` (+ test), `src/NotificationsProvider.tsx`,
`src/hooks.ts` (+ `hooks.test.tsx`).

**Behaviour:**
- **Leader:** `navigator.locks.request('<prefix>.notifications', …)` held by the tab that streams; the lock is
  released on sign-out, unmount or tab close, and the next waiting tab takes over. Without `navigator.locks` (jsdom,
  old browsers) every tab leads.
- **Fan-out:** the leader posts `{ type: 'summary', userId, summary }` on `BroadcastChannel('<prefix>.notifications')`;
  a follower applies only its own user's; a new tab posts `{ type: 'hello' }` and the leader answers with its last
  summary. Without `BroadcastChannel` every tab leads.
- **Floor:** while the stream is `retrying`, the summary is fetched every 60 s (`GET …/summary`); it stops when the
  stream is open again. `unavailable` stops both; `useNotificationSummary` then reports `status: 'unavailable'`.
- **Sign-out:** `onUnauthorized` calls core's `signOut()`; a `storage` event that removes the token key aborts too.
- **Cache:** each summary goes into react-query (`setQueryData` on the summary key); a summary that differs from the
  previous one invalidates the list queries.
- **Hooks:** `useNotificationSummary(): { summary?: NotificationSummary; status: 'live' | 'polling' | 'unavailable' }`,
  `useNotifications({ kind?, state?, page, size })`, `useMarkRead()`, `useDismiss()`, `useSnooze()` (`{ id, until }`),
  `useReadAll()` (`{ kind? }`). Mutations invalidate the summary and the lists on success (the stream confirms it).
- **`NotificationsProvider({ children, resolveLink?, inboxPath? })`**: starts the shared stream for a signed-in
  person, keeps the link resolver and the inbox path (default `links.to('notifications:inbox')` when registered) in
  context; renders nothing else. srtm-ui and caja-ui mount it around their portal.

**Tests:** two simulated tabs (a fake lock manager and channel) → one `fetch`; the follower gets the leader's summary;
the leader's unmount hands over; another user's message is ignored; `retrying` polls every 60 s and stops when open;
sign-out aborts and releases the lock; no `navigator.locks` → each tab streams.

- [ ] Tests first; implement; commit `feat(notifications): one live summary per browser, with a polling floor`.

### Task 9: links

**Files:** create `packages/notifications/src/links.ts` (+ test) and `src/NotificationLinkView.tsx`.

**Interface:** `type NotificationHref = { href: string; external: boolean }`,
`type NotificationLinkResolver = (link: NotificationLink, links: WasichaiLinks) => NotificationHref | null`,
`defaultResolveLink`.

**Rules (default):**
- `RECORD` → `links.record(object, recordId)`, plus `?tab=KEY` when `tab` is set.
- `ROUTE` → `null` unless `links.has(route)`; params named by `links.routeParams(route)` fill the path, the rest and
  `tab` go to the query string through `links.to(route, pathParams, search)`.
- `URL` → `{ href: url, external: true }` only for `http:`/`https:` (parsed with `URL`); anything else → `null`.
- An app resolver may answer its own keys and delegate the rest to `defaultResolveLink`.

`NotificationLinkView` draws a react-router `Link` for internal hrefs and `<a target="_blank" rel="noopener
noreferrer">` for external ones, and marks the notification read on click.

**Tests:** record with and without tab; route with path and query params; unknown route → `null`; `javascript:` and
`ftp:` URLs → `null`; an app resolver overrides one key and delegates the rest.

- [ ] Tests first; implement; commit `feat(notifications): links to records, routes and documents`.

### Task 10: the bell, toasts and announcements

**Files:** create `packages/notifications/src/NotificationBell.tsx`, `NotificationToasts.tsx`, tests for both.

**Behaviour:**
- **Bell:** a button with a bell icon and a badge of the unread total (`99+` over 99), a dot when any ACTION is
  `overdue`; `aria-label` "Alertas, N sin leer" / "Notifications, N unread"; the badge is `aria-hidden`. It links to
  `notifications:inbox` (or to the app's inbox path given to the provider). Hidden while `status` is `unavailable`.
- **Live region:** a visually hidden `aria-live="polite"` element announces when the unread total grows.
- **Toasts:** when `summary.latest` changes to an id newer than the baseline (the first summary after load), show an
  `Alert` from `@wasichai/ui` (INFO `notice`, WARNING `warning`, ACTION `danger`) with the title and a link to the inbox
  on its tab; dismissible; gone after 8 s; at most three stacked; one toast per id per tab.

**Tests:** badge count and `99+`; overdue dot; hidden on `unavailable`; first summary makes no toast; a newer `latest`
makes one; the same id again makes none; the live region text.

- [ ] Tests first; implement; commit `feat(notifications): the bell and its toasts`.

### Task 11: the "Alertas" page

**Files:** create `packages/notifications/src/pages/InboxPage.tsx`, `src/components/NotificationItem.tsx`,
`src/components/SnoozeMenu.tsx`, tests.

**Behaviour:** `PageHeader` "Alertas"; `Tabs` Comunicados (INFO), Advertencias (WARNING), Pendientes (ACTION), each with
its unread count; the tab follows `?tab=INFO|WARNING|ACTION` like any page. A state filter (Activas, Sin leer,
Pospuestas). Each item: title, body as text, its link (`NotificationLinkView`), publish date; in Pendientes the due
date and an "Vencida" badge when `overdue`, ordered as the server sends it. Actions: mark read, snooze (one hour,
tomorrow 08:00 local, one week; the server's `400` beyond `snooze-max` is shown), dismiss only when `dismissible`.
"Marcar todo como leído" per tab (`read-all` with the kind). `PageSizePagination`. `QueryState` for loading and errors.
`InboxPage` is exported from the index (a light page, ADR-028), so an app with its own shell mounts it at its own path.

**Tests:** tabs filter by kind; `?tab=ACTION` opens Pendientes; dismiss hidden when not dismissible; snooze posts the
chosen instant; read-all sends the kind; a body with `<b>` prints the characters.

- [ ] Tests first; implement; commit `feat(notifications): the Alertas page`.

### Task 12: compose manual notifications

**Files:** create `packages/notifications/src/pages/AdminPage.tsx`, `src/components/NotificationForm.tsx`,
`src/components/AudienceField.tsx`, `src/components/LinkField.tsx`, tests.

**Behaviour:** a paged table of `GET /api/notifications` filtered by `status` (open, scheduled, ended), `kind` and
`source`, with `readCount`. A form for `POST` / `PUT`: kind, title, body, link (none, URL, a record: object + id + tab,
a route: key + params + tab), audience (everyone; users by email; roles from core's roles; units from
`/api/org-units`), `publishAt`, `expiresAt`, `dueAt` as local date-times sent as ISO instants. Violations show under
their field (`audience[2]` under the third audience row). Rows of a source or a rule are read-only (the `409` is
explained). Delete asks first.

**Tests:** a valid body for each link and audience type; the `400` mapped to its row; a source row has no edit or delete.

- [ ] Tests first; implement; commit `feat(notifications): compose notifications by hand`.

### Task 13: the date rule builder

**Files:** create `packages/notifications/src/pages/RulesPage.tsx`, `src/components/RuleForm.tsx`,
`src/components/StagesField.tsx`, `src/rules.ts` (pure: window description, placeholder list), tests.

**Behaviour:** pick an object (`?object=` in the URL), list its rules (`GET /api/objects/{object}/notification-rules`),
create, edit, enable/disable, delete, run now (shows `{created, updated, reopened, resolved}`). The form: name, label,
the `DATE`/`DATETIME` field, 1–5 stages (`fromDays`, kind), `untilDays`, conditions (field; `EQ` with the field's own
input from core's `FieldRenderer`, `EMPTY`, `NOT_EMPTY`), audience (`AudienceField`), title and body with placeholder
chips (`{{<field>}}`, `{{days}}`, `{{date}}`, `{{object}}`), tab (the object's page TAB keys when pages are installed,
else free text). A sentence describes the window ("from 15 days before until 3 days after; ACTION from the day").
Server `400`s show under their field. A rule's notifications can only be ended by disabling or deleting it.

**Tests:** window sentence for negative and positive offsets; at most five stages; placeholders listed from the
object's fields; `400` for an unknown placeholder under the template; run result shown.

- [ ] Tests first; implement; commit `feat(notifications): build date rules`.

### Task 14: READMEs, the whole check, the pre-release

**Files:** create `packages/notifications/README.md`; modify the root `README.md` (packages table) and
`packages/core/README.md` (`shellActions`, `?tab=`, units).

The package README shows the two ways to mount it:

```tsx
// an app on WasichaiApp: the module brings routes, nav, provider and the bell
<WasichaiApp modules={[notificationsModule()]} />

// an app with its own shell (srtm-ui, caja-ui): provider around the portal; its header draws <NotificationBell />
<NotificationsProvider resolveLink={resolveAppLink} inboxPath="/alertas">
  <PortalRoutes />
</NotificationsProvider>
```

- [ ] `yarn lint && yarn test && yarn build && yarn format:check` → PASS.
- [ ] Push, open the PR to `dev`. After review and after wasichai 0.4.0 is out, **ask the user** before tagging
  `v0.6.0-dev.0`.

### Task 15: e2e in full-sample

**Files:**
- Modify: `../full-sample/server/build.gradle.kts` (`implementation("wasichai:wasichai-spring-boot-starter-notifications")`),
  the route-parity expectations under `../full-sample/server/src/test/resources/route-parity/` (the new routes are D32),
  `../full-sample/web/package.json` (`@wasichai/notifications`), `../full-sample/web/src/App.tsx` (`notificationsModule()`).
- Create: `../full-sample/web/e2e/notifications.spec.ts`.

**Tests (Playwright, admin seed user):**
1. Sign in; `POST /api/notifications` (INFO, audience `ALL`, a unique title, a URL link) through `request`; the toast
   with that title appears within 5 s (live, not the 60 s floor); the badge grows; the inbox's Comunicados tab lists it;
   "mark read" lowers the badge; clean up with `DELETE`.
2. A notification with a `RECORD` link and tab `HISTORY` to a record of a sample object: clicking it lands on
   `…/records/<id>?tab=HISTORY` with History selected (`aria-selected="true"`).
3. Two pages of one context: only one request to `/api/auth/me/notifications/stream` (`page.on('request')` on both).

- [ ] Write the spec → FAIL against the old build; wire the module → `yarn playwright test` PASS; `./gradlew
  integrationTest` in `server` PASS. Commit `feat(web): notifications and their e2e`.

### Task 16: decisions and docs in wasichai

**Files** (in `../wasichai`):
- Create `docs/adr/0048-a-shell-actions-slot.md` (next free number): the shell gains `shellActions`; why now although
  ADR-028 asks for a second user first (the bell has no other place in `AppShell`; the maintenance banner, spec G #5,
  is the expected next user; apps with their own shell use the components, not the slot). Add it to `docs/adr/README.md`.
- Modify `docs/modules/core.md` (frontend package: `shellActions`, `routeParams`, `?tab=`, units screens),
  `docs/modules/notifications.md` (frontend section: module, provider, resolver, hooks, stream sharing),
  `docs/HISTORY.md` (one entry, newest first).

- [ ] Write; hand-format to 160 columns; commit `docs: notifications in wasichai-ui`.

## Order

```
wasichai 0.4.0 backend (separate plan) ─┐
T1 · T2 · T3 · T5 (core) ── T4 (T3) ────┤
T6 ── T7 ── T8 ── T9 (T2) ── T10 (T1) ──┤── T11 · T12 · T13 ── T14 ── T15 ── T16
```

T1, T2, T3, T5 and T6 can run in parallel; T7–T10 in order; T11–T13 in parallel after T10.
