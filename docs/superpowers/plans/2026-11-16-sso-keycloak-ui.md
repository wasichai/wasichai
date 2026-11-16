# Single sign-on with Keycloak, UI: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `@wasichai/core` signs people in through the domain's Keycloak realm when the backend says `oidc`, and keeps
today's login form when it says `local`.

**Architecture:** `GET /api/auth/config` picks the mode once at start. In `oidc` an `OidcSession` (over
`oidc-client-ts`) runs the Authorization Code flow with PKCE, keeps the session per tab and refreshes the access token;
the API client takes its token from a pluggable source and, on a 401, asks it for one refresh before signing out. The
signed-in user comes from `GET /api/auth/me` (the app's user id, organization and roles) plus the realm's `name`.
Admin screens drop passwords and show `linked`, with an «unlink» action.

**Tech Stack:** React 19.3, TypeScript, react-query, react-router, i18next, vitest + testing-library, yarn 1 workspaces;
new dependency `oidc-client-ts` in `@wasichai/core`.

**Spec:** [2026-11-16-sso-keycloak-design.md](../specs/2026-11-16-sso-keycloak-design.md) (decision 6),
[ADR-084](../../adr/0084-people-sign-in-through-the-domains-identity-provider.md); the backend contract is wasichai's
`docs/security/authentication.md` and `docs/api/rest.md` after the backend plan
([2026-11-16-sso-keycloak-backend.md](2026-11-16-sso-keycloak-backend.md)). Runs in the wasichai-ui repository.

## Global Constraints

- `local` mode is today's behaviour, unchanged: login form, `POST /auth/login`, token in `localStorage`
  (`<prefix>.token`), sign-out on a 401. Every existing test passes untouched.
- `GET /auth/config` answers `{"mode":"local"}` or `{"mode":"oidc","issuer":…,"clientId":…}`; an older backend
  without the route (404) means `local`. Any other failure is shown as an error, not as `local`.
- `oidc`: Authorization Code with PKCE (`S256`), scope `openid organization`, redirect
  `<origin><basename>/auth/callback`, post-logout redirect `<origin><basename>/`. Sign-out is RP-initiated (ends the
  realm session). The session lives in `sessionStorage`, never `localStorage`.
- `oidc`: the access token is refreshed before it expires (`automaticSilentRenew` with the refresh token); a 401 gets
  one refresh and one retry of the same request before signing out.
- `oidc`: `AuthUser.id` is the app's user id from `/auth/me` (`userId`), never the realm's `sub`.
- A 403 whose problem `detail` comes from the backend's denial (not invited, organization not in the app, disabled) is
  shown on a full-page screen with that text and a «Cerrar sesión» button, not as an empty app.
- `getToken()` keeps its signature for the gis package and the notifications stream.
- Texts in Spanish and English (the package's i18n bundles). Comments caveman style, English. `.editorconfig`.
  Conventional Commits, scope `core`.

## Review Focus

- The callback page reloaded or opened twice (code already used): it must end on the login route with an error, not
  loop — test in Task 3.
- Two tabs: each keeps its own session (`sessionStorage`); signing out in one ends the realm session, and the other's
  next refresh fails and signs it out cleanly — test in Task 2 (refresh failure path).
- A 401 during a refresh already in flight: one refresh for all concurrent requests, not one each — test in Task 2.
- `/auth/config` slow or down at start: the app shows a loading state, then an error with «Reintentar», never the login
  form of the wrong mode — test in Task 3.
- `returnTo` survives the round trip to Keycloak (stored in the sign-in request's state) — test in Task 3.

---

### Task 1: The mode, and a token source the client can refresh

**Files:**
- Create: `packages/core/src/auth/authConfig.ts`, `packages/core/src/auth/authConfig.test.ts`
- Modify: `packages/core/src/api/client.ts`, `packages/core/src/api/client.test.ts`

**Interfaces:**
- Produces:
  - `type AuthConfig = { mode: 'local' } | { mode: 'oidc'; issuer: string; clientId: string }`
  - `fetchAuthConfig(client: ApiClient): Promise<AuthConfig>` — 404 → `{ mode: 'local' }`; other errors rethrown.
  - `interface TokenSource { get(): string | null; refresh(): Promise<boolean> }`
  - `ApiClient.setTokenSource(source: TokenSource | null): void` — when set, `request` and `getToken` read it instead of
    `localStorage`; on a 401 it awaits `refresh()` once (shared by concurrent requests) and, when true, retries the
    request once; when false (or no source), today's path: clear, `onUnauthorized`.

- [ ] **Step 1: Failing tests** — `authConfig.test.ts`: local, oidc, 404 → local, 500 → rejects. `client.test.ts`:
  `a token source replaces storage`, `a 401 refreshes once and retries`, `concurrent 401s share one refresh`,
  `a failed refresh signs out as before`, `without a source a 401 behaves as today`.
- [ ] **Step 2: Run, expect FAIL** — `yarn workspace @wasichai/core test src/auth/authConfig.test.ts src/api/client.test.ts`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS**; `yarn workspace @wasichai/core test`.
- [ ] **Step 5: Commit** — `feat(core): the sign-in mode, and a token source the client can refresh`

### Task 2: The oidc session

**Files:**
- Modify: `packages/core/package.json` (dependency `oidc-client-ts`, exact version, the latest 3.x)
- Create: `packages/core/src/auth/oidcSession.ts`, `packages/core/src/auth/oidcSession.test.ts`

**Interfaces:**
- Consumes: `AuthConfig` (Task 1), `TokenSource`.
- Produces: `createOidcSession(config: { issuer: string; clientId: string; basePath: string; storage?: Storage }): OidcSession`
  with `signIn(returnTo: string): Promise<void>` (redirect; `returnTo` in the request state),
  `completeSignIn(): Promise<{ returnTo: string }>`, `signOut(): Promise<void>` (RP-initiated),
  `current(): Promise<{ accessToken: string; name?: string } | null>`, `tokenSource: TokenSource`,
  `onExpired(handler: () => void): void` (refresh failed or session ended). Built on `UserManager` with
  `WebStorageStateStore({ store: storage ?? sessionStorage })` for both `userStore` and `stateStore`,
  `automaticSilentRenew: true`, `response_type: 'code'`, scope `openid organization`.

- [ ] **Step 1: Failing tests** with `UserManager` mocked (`vi.mock('oidc-client-ts')`): the settings above (authority,
  client_id, redirect and post-logout uris from `basePath`, scope, stores on `sessionStorage`); `signIn` passes
  `returnTo` in `state`; `completeSignIn` returns it; `tokenSource.get` reads the current access token;
  `tokenSource.refresh` → `signinSilent` true/false; a failed silent renew fires `onExpired`; `signOut` →
  `signoutRedirect`.
- [ ] **Step 2: Run, expect FAIL.** **Step 3: Implement.** **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — `feat(core): an oidc session over oidc-client-ts`

### Task 3: Sign-in, callback and sign-out in oidc mode

**Files:**
- Modify: `packages/core/src/auth/AuthProvider.tsx` (+ test), `packages/core/src/auth/LoginPage.tsx` (+ test),
  `packages/core/src/app/coreModule.ts` and wherever `CORE_ROUTE_PATHS` lives (a public `authCallback` route at
  `/auth/callback`), `packages/core/src/app/WasichaiProviders.tsx` (load the config before the auth provider renders)
- Create: `packages/core/src/auth/AuthCallbackPage.tsx` (+ test), `packages/core/src/auth/AccessDenied.tsx` (+ test)
- Modify: the core i18n bundles (`auth.*` keys, es and en)

**Interfaces:**
- Consumes: Tasks 1-2.
- Produces: `AuthContextValue` unchanged in shape; in `oidc`, `signIn(email, password)` is replaced by a redirect —
  add `mode: 'local' | 'oidc'` and `signInWithRealm(returnTo: string): Promise<void>` to the context; `signOut()` ends
  the realm session in `oidc`. `user` is `/auth/me` mapped to `AuthUser` (`id` = `userId`, `displayName` = the realm's
  `name`, falling back to the email).

- [ ] **Step 1: Failing tests:** `LoginPage` in oidc shows one «Ingresar» button that calls `signInWithRealm(returnTo)`
  and no fields; `AuthCallbackPage` completes and navigates to `returnTo`, and on an error (code reused) goes to the
  login route with the error text; `AuthProvider` in oidc builds `user` from `/auth/me`, installs the session's token
  source on the client, signs out on `onExpired`; a 403 on `/auth/me` with a denial renders `AccessDenied` with the
  detail and a «Cerrar sesión» button; `WasichaiProviders` shows loading, then an error with «Reintentar» when
  `/auth/config` fails; local mode tests untouched and green.
- [ ] **Step 2: Run, expect FAIL.** **Step 3: Implement.** **Step 4: Run, expect PASS**; `yarn lint && yarn test`.
- [ ] **Step 5: Commit** — `feat(core): people sign in through the realm when the backend says oidc`

### Task 4: Users without passwords, linked and unlinked

**Files:**
- Modify: `packages/core/src/features/admin/types.ts` (`AdminUser.linked: boolean`; `password` optional on create;
  `UpdateUserInput.unlink?: boolean`), `UsersPage.tsx` (+ test), `api.ts`, `i18n.ts`

**Interfaces:**
- Consumes: `useAuth().mode` (Task 3).

- [ ] **Step 1: Failing tests:** in oidc the create form has no password field and submits without one; the list shows
  «Vinculado» / «Pendiente» from `linked`; a linked user's edit dialog offers «Desvincular», which confirms and sends
  `PUT /users/{id}` `{ unlink: true }`, then shows «Pendiente»; in local the form and edit dialog are as today and no
  unlink appears.
- [ ] **Step 2: Run, expect FAIL.** **Step 3: Implement.** **Step 4: Run, expect PASS**; `yarn lint && yarn test && yarn build`.
- [ ] **Step 5: Commit** — `feat(core): invite users without a password and unlink a recreated account`

### Task 5: Documentation and history

**Files:** wasichai-ui `README.md` (how an app gets oidc: nothing to configure, the backend decides; `/auth/callback`
must be a valid redirect of the realm's client); wasichai `docs/HISTORY.md` (entry for the UI side, in the wasichai
repository, separate PR) and `docs/security/authentication.md` «In the browser» (the session per tab, refresh,
RP-initiated sign-out).

- [ ] **Step 1: Write.** **Step 2: `yarn format:check`, line length ≤ 160.** **Step 3: Commit** —
  `docs(core): signing in through the realm`
