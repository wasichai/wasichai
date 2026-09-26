# ADR-009: Docker Compose for development, Pulumi/k3s deferred

**Status**: accepted · 2026-09-17

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).

## Context

The target deployment is Pulumi onto k3s. Standing that up before the first vertical slice works
would slow every iteration and prove nothing about the product.

## Decision

Docker Compose is the development environment: PostGIS + pgvector and GeoServer, one command. The
Pulumi/k3s program is a later deliverable; nothing in the application assumes either.

## Consequences

- A contributor needs Docker and one command.
- Deployment work is real work still to be done, not a script that silently rots.
- Database init scripts are baked into the image rather than bind-mounted, so a remote Docker daemon
  behaves the same as a local one.
