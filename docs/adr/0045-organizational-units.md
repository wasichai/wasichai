# ADR-045: Organizational units: a tree per organization, and who belongs where

**Status**: accepted · 2026-10-06 · builds on [ADR-043](0043-service-accounts.md); first user
[ADR-046](0046-notifications-module.md)

## Context

A municipality is organized as gerencias, subgerencias and áreas, and people work in one or more of them. wasichai
knew only roles, which say what someone may do, not where they sit. Notifications must reach "the Gerencia de Rentas"
or "Fiscalización" (ADR-046), and srtm and caja have nothing to offer: srtm's gerencia is free text printed on a PDF,
caja's `area` is the business unit a fee is charged to, not a group of people. Using roles as areas would mix "what I
may do" with "where I am" and lose the hierarchy, and leaving areas to each app would build the same tree twice.

## Decision

**A tree of units per organization, and a many-to-many membership.** Core migration `V9__org_units` adds
`org_units (id, organization_id, parent_id, code, label, timestamps)` and `user_org_units (user_id, unit_id)`.

- A **code** is upper case (`^[A-Z][A-Z0-9_]{1,48}$`), unique in the tenant and never changes: apps bind to it. The
  label can change.
- The **parent** is a composite foreign key `(organization_id, parent_id)`, so a parent is always of the same tenant.
  A unit is never its own parent (`CHECK`); a move under its own subtree is refused under a transaction lock
  (`ClusterLock.withXactLock`), and depth is capped at 10.
- **Many-to-many** membership, because encargaturas and shared staff are real.
- **Administered by `MANAGE_ORGANIZATION`**: `/api/org-units`, `PUT /api/users/{id}/org-units`; any person reads their
  own at `GET /api/auth/me/org-units`. `AdminUserResponse` carries `orgUnits`. A unit with sub-units or members cannot be
  deleted (`409`).
- **Not authorization and not in the token.** Membership grants nothing. It changes more often than a token lives, so
  it is read when needed: `OrgUnitDirectory.closureOf` walks a user's units and their ancestors with a recursive query.
- **Ports, not tables:** modules read units and users through `OrgUnitDirectory` and `UserDirectory` (by email, by
  id), never through core's tables.

Left out until a second user asks: a head of unit (escalation), an `active` flag (historical restructurings), an order
among siblings (they sort by label), rights scoped to a unit.

## Consequences

- A notification addressed to a unit reaches its whole subtree, and a new member sees what is already open.
- Each app adopting 0.4.0 runs `V9` and may load its tree like its roles (`model/org_units.json`).
- `org_units` and `user_org_units` are a schema-parity deviation ([ADR-031](0031-deliberate-deviations-from-sapgis.md)
  D31).
- Deleting a unit drops the notification targets that named it (ADR-046); the UI should warn first.
