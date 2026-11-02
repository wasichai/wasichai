# Shell menu and start page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an app that is one `WasichaiApp` pick the sidebar groups by route and permissions
([wasichai-ui#119](https://github.com/wasichai/wasichai-ui/issues/119)) and the start page by permissions
([wasichai-ui#120](https://github.com/wasichai/wasichai-ui/issues/120)). First user: wasichai-learning.

**Architecture:** `WasichaiConfig.shellNav` runs in `AppShell` before `visible`. `WasichaiConfig.homeRoute` is read by
a pure `resolveHome(homeRoute, registry, auth)` (`dashboard | loading | route | error`) in `packages/core/src/app/home.tsx`,
used by `HomePage` (now `core:home`) and by `useWasichaiLinks`, which passes the result to `createLinks(registry, { home })`.
Decision: ADR-076.

**Tech Stack:** React 19.3, react-router, react-query, vitest + testing-library.

## Global Constraints

- Unset options change nothing: same menu, `/` is the dashboard, `links.home()` is `/`.
- Courtesy only: routes stay mounted, `links.*` keep their urls, the server decides.
- No dashboard flash: `HomePage` never mounts `DashboardPage` while permissions load.

## Task 1: shellNav (#119)

- [x] Failing cases in `AppShell.test.tsx`: groups returned, context (`pathname`, `permissions`, `null` while
  loading), `visible` and empty drop after it, re-run on navigation, links untouched.
- [x] `ShellNav` type and `shellNav` option in `config.ts`; one line in `AppShell`.

## Task 2: homeRoute (#120)

- [x] Failing cases in `home.test.tsx` with permissions really fetched: redirect without `/objects`, «Inicio» and
  `links.home()` agree, `null` keeps the dashboard, loading, failure, unknown key, unknown path, sign in, `returnTo`.
- [x] `HomeRoute` type and option; `home.tsx` (`resolveHome`, `homePath`, `HomePage`); `useOptionalAuth`;
  `createLinks(registry, { home })`; `coreModule` mounts `HomePage`.

## Task 3: Docs and verification

- [x] `@wasichai/core` README: config rows and "Menu and start page".
- [x] ADR-076, `docs/HISTORY.md`.
- [x] `yarn format`, `yarn lint && yarn test && yarn build`.
