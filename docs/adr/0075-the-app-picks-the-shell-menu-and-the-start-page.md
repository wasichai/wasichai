# ADR-075: The app picks the shell menu and the start page

**Status**: accepted · 2026-11-02 · extends [ADR-028](0028-frontend-module-registry.md)

## Context

`WasichaiApp` always registers `coreModule`, and `AppShell` draws every group of the registry, filtered only by each
entry's `visible(permissions)`. `/` is always `core:home`, the generic dashboard, and «Inicio» points there. An app
that mounts all its screens as modules of one `WasichaiApp`, like wasichai-learning's LMS
([elearning-frontend#142](https://github.com/wasichai/elearning-frontend/issues/142)), cannot keep «Datos» and
«Administración» away from a student, show them only inside an admin section, or start a student on their courses.
Its creators open `/data/objects/leccion/records/:id` with `links.record()`, so core's routes must stay in that app.

srtm-ui, caja-ui and sgspe-ui split into two apps in one bundle (`basename: '/admin'`). That does not fit here:
core's paths already start with `admin/`, so `/admin/users` would become `/admin/admin/users`, and the LMS app would
still need to hide core's menu. A module cannot register `''` either: it collides with `core:home`. Redirecting from a
`dashboardCards` entry would mount the dashboard and fetch its data before leaving.

## Decision

Two optional functions in `WasichaiConfig`, both courtesies: routes stay mounted, `links.*` keep their urls, the
server decides access.

- `shellNav(groups, { pathname, permissions })` returns the groups `AppShell` offers
  ([wasichai-ui#119](https://github.com/wasichai/wasichai-ui/issues/119)). It runs before `visible` and the
  empty-group drop, on every route and permissions change; `pathname` is relative to the `basename`, `permissions` is
  `null` while they load. «Inicio» stays fixed: it is the way back.
- `homeRoute(permissions)` returns a registered route key, or `null` for the dashboard
  ([wasichai-ui#120](https://github.com/wasichai/wasichai-ui/issues/120)). `core:home` mounts `HomePage`, which
  redirects there with `replace`; `links.home()` answers the same url, so «Inicio», unknown paths and a sign in
  without `returnTo` agree. It only gets `GET /auth/me/permissions`, never role names. While they load `/` shows the
  loading text, never the dashboard; if they fail, or nobody is signed in, it is the dashboard as before. An
  unregistered key fails on `/` with the key in the message.

Declarative `navSections` and `section` on nav groups and routes, with the switching logic in core, were set aside:
there is one user today, and an abstraction needs a concrete second one.

## Consequences

- Without either option nothing changes: same menu, `/` is the dashboard, `links.home()` is `/`.
- One `WasichaiApp` serves students and admins, and core's record pages stay reachable through `links.*`.
- The app writes its section rule as code (`pathname.startsWith('/admin')`); core has no notion of sections. A
  second user wanting the same rule is the moment to revisit the declarative form.
- `links.home()` depends on the session: a component that keeps its result across a sign in or a permissions change
  must read it again.
