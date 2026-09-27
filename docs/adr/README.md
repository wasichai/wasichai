# Architecture decision records

One file per decision, numbered in order. ADRs 0001–0023 were imported from the original app with identifiers
renamed (see [where wasichai comes from](../sapgis-origin.md)). A decision is never edited to change it: a later ADR amends or
supersedes it and says so in its status line.

- [ADR-001: Modular monolith](0001-modular-monolith.md)
- [ADR-002: PostgreSQL 18 + PostGIS (+ pgvector)](0002-postgis.md)
- [ADR-003: Metadata-driven architecture](0003-metadata-driven.md)
- [ADR-004: One physical table per Custom Object](0004-physical-table-per-object.md)
- [ADR-005: MapLibre GL JS for the geoviewer](0005-maplibre.md)
- [ADR-006: GeoServer as publisher, not as the core](0006-geoserver.md)
- [ADR-007: Geometry over R2DBC as GeoJSON in SQL](0007-geometry-over-r2dbc.md)
- [ADR-008: Flyway over a short-lived JDBC DataSource](0008-flyway-over-jdbc.md)
- [ADR-009: Docker Compose for development, Pulumi/k3s deferred](0009-docker-compose-first.md)
- [ADR-010: Own JWT for the MVP, OIDC-ready](0010-own-jwt.md)
- [ADR-011: Pages defined by metadata, with a generated default](0011-metadata-defined-pages.md)
- [ADR-012: Views and forms as named metadata, resolvable to a default](0012-views-and-forms.md)
- [ADR-013: Workflow state lives on the record, transitions are the only way to move it](0013-workflow-state-on-the-record.md)
- [ADR-014: The agent reaches data only through Wasichai's own services](0014-agents-through-the-api.md)
- [ADR-015: Embabel as the agent runtime](0015-embabel-as-the-agent-runtime.md)
- [ADR-016: Automations are queued work that runs as the platform](0016-automations-queue-and-system-context.md)
- [ADR-017: Deleting metadata refuses what it cannot undo, and says who is in the way](0017-metadata-deletion-refuses-instead-of-cascading.md)
- [ADR-018: Workflows are edited on a canvas, and the canvas remembers where things are](0018-workflows-are-edited-on-a-canvas.md)
- [ADR-019: A geometry is a field, so an object can have more than one](0019-a-geometry-is-a-field.md)
- [ADR-020: The caller can ask what they may do, and asking grants nothing](0020-the-caller-can-ask-what-they-may-do.md)
- [ADR-021: A page is a tree](0021-a-page-is-a-tree.md)
- [ADR-022: A page has a template](0022-a-page-has-a-template.md)
- [ADR-023: A document is frozen when it is issued](0023-a-document-is-frozen-when-it-is-issued.md)
- [ADR-024: Wasichai ships as libraries: starters, a BOM and explicit auto-configuration](0024-libraries-and-starters.md)
- [ADR-025: The core is extended through SPIs, never by knowing its modules](0025-extension-spis.md)
- [ADR-026: Each module owns its migrations and its Flyway history](0026-per-module-migrations.md)
- [ADR-027: GIS is optional, and the API is unchanged when it is present](0027-gis-optional.md)
- [ADR-028: Frontend modules plug into a registry](0028-frontend-module-registry.md)
- [ADR-029: One repository, one version, published to GitHub Packages](0029-polyglot-monorepo-and-publishing.md)
- [ADR-030: Sapgis is renamed chawpi, and its history stays readable](0030-rebrand-sapgis-to-chawpi.md)
- [ADR-031: Where wasichai deliberately behaves differently from the original](0031-deliberate-deviations-from-sapgis.md)
- [ADR-032: Rebrand to wasichai and split into two repositories](0032-rebrand-to-wasichai-and-split-repositories.md)
- [ADR-033: Samples and infrastructure live in their own repositories; the libraries are
  infra-agnostic](0033-samples-and-infrastructure-in-their-own-repositories.md)
- [ADR-034: A stored resource for user preferences, starting with theme and
  locale](0034-user-preferences-and-themes.md)
- [ADR-035: Themes get extension tokens and data-slot hooks, and the library ships themes as optional
  sheets](0035-theme-extension-tokens-slots-and-optional-sheets.md)
