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
- [ADR-036: Declared indexes, an optional count and keyset
  reads](0036-declared-indexes-optional-count-and-keyset-reads.md)
- [ADR-037: Composite unique constraints, and a repeated unique value as a
  409](0037-composite-unique-constraints-and-409-on-repeats.md)
- [ADR-038: RecordService joins the caller's transaction, and that is supported
  API](0038-record-service-joins-the-callers-transaction.md)
- [ADR-039: Background work runs RecordService as the platform, and takes a cluster
  lock](0039-background-work-runs-as-the-platform-with-a-cluster-lock.md)
- [ADR-040: Append-only objects, a pre-write guard SPI, and api-only
  objects](0040-append-only-objects-and-a-pre-write-guard.md)
- [ADR-041: A change reason on record writes, required per object](0041-a-change-reason-on-record-writes.md)
- [ADR-042: An object declares its own actions, and they are granted, checked and listed like
  CRUD](0042-app-declared-actions.md)
- [ADR-043: Service accounts sign in with client credentials, as a user row that is not a
  person](0043-service-accounts.md)
- [ADR-044: The append-only delete check runs under a row lock, in one transaction with the
  delete](0044-append-only-delete-check-under-a-row-lock.md)
- [ADR-045: Organizational units: a tree per organization, and who belongs where](0045-organizational-units.md)
- [ADR-046: A notifications module: audience matched on read, keyed sources that resolve themselves, tab
  keys](0046-notifications-module.md)
- [ADR-047: Server push over SSE, with PostgreSQL LISTEN/NOTIFY between
  replicas](0047-server-push-over-sse-and-listen-notify.md)
- [ADR-048: A read scope SPI narrows what a caller reads of an
  object](0048-a-read-scope-narrows-what-a-caller-reads.md)
- [ADR-049: Changes to users, roles, permissions and the model are in the audit
  log](0049-admin-changes-in-the-audit-log.md)
- [ADR-050: Every audit row carries a correlation id and the source of the
  change](0050-correlation-id-and-change-source-on-audit-rows.md)
- [ADR-051: Records answer an ETag, honour If-Match in the write itself, and take a partial
  PATCH](0051-optimistic-locking-and-partial-update-of-records.md)
- [ADR-052: The audit list pages by cursor and narrows by period and
  user](0052-audit-pages-by-cursor-period-and-user.md)
- [ADR-053: The caller is told their tenant-wide capabilities
  too](0053-the-caller-is-told-their-tenant-wide-capabilities.md)
- [ADR-054: The audit log is append-only in the database, with a purge only a configured login can
  run](0054-audit-log-is-append-only-in-the-database.md)
- [ADR-055: Creating and deleting tenants can be kept apart from administering
  one](0055-tenant-provisioning-apart-from-tenant-administration.md)
- [ADR-056: What reaches the model is the app's to shape, per caller, and every run is
  reported](0056-what-reaches-the-model-is-the-apps-to-shape.md)
- [ADR-057: Background work finds the tenants through a tenant
  directory](0057-background-work-finds-the-tenants-through-a-tenant-directory.md)
- [ADR-058: A record create takes an Idempotency-Key, held by an advisory lock and stored with the
  record](0058-idempotency-key-on-record-creation.md)
- [ADR-059: Token revocation by a per-user marker, sign-in attempt limits and a password
  policy](0059-token-revocation-login-limits-and-password-policy.md)
- [ADR-060: Notifications leave the app through delivery channels, fanned out to people when news is
  written](0060-delivery-channels-and-automation-notify.md)
- [ADR-061: FILE and IMAGE fields, stored through a FileStore SPI and written as record
  writes](0061-file-and-image-fields-with-a-storage-spi.md)
- [ADR-062: RecordService.createAll, a batch create that looks things up once](0062-batch-record-creation.md)
