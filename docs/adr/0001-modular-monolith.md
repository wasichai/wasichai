# ADR-001: Modular monolith

**Status**: accepted · 2026-09-17

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).
>
> Moved from chawpi on 2026-09-26: identifiers renamed chawpi → wasichai; the decision is unchanged. See
> [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md) and [the origin page](../chawpi-origin.md).

## Context

Wasichai spans identity, metadata, dynamic data, GIS, audit, workflows and automation. Splitting that
into services now would multiply deployment and transaction complexity before a single object exists.

## Decision

One deployable, organised as packages with explicit seams: `identity`, `organization`, `metadata`,
`data`, `gis`, `audit`, `platform`, `common`. Modules communicate through services and ports, never
through another module's repositories. Gradle modules are deliberately not used yet — a package
boundary costs nothing and can be promoted when a module actually needs its own lifecycle.

## Consequences

- Creating an object writes metadata and runs DDL in one transaction, which no distributed design
  would give us for free.
- Boundaries are a convention. Reviews enforce them; a lint rule or a Gradle module can later.
- Extracting a service means moving a package and replacing a port implementation.
