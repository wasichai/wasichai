# ADR-026: Each module owns its migrations and its Flyway history

**Status**: accepted · 2026-09-25 · amends ADR-008

> Moved from chawpi on 2026-09-26: identifiers renamed chawpi → wasichai; the decision is unchanged. See
> [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md) and [the origin page](../chawpi-origin.md).

## Context

sapgis ran one Flyway over `classpath:db/migration`, V1..V14. V1 alone created tables for eight
packages and the PostGIS extension; V14 put a foreign key from `audit_log` to `documents`. The schema
name `sapgis` was a literal in about 120 SQL strings. With modules optional, an app must get exactly
the tables of the modules it has, and may add a module later.

## Decision

**`WasichaiMigrations` runs one Flyway per `ModuleMigration(name, location, order)` bean**, in `order`
(ties broken alphabetically by name, not by dependency — a module whose migration reads another
module's tables must pick a strictly higher `order` than that module, not rely on name sorting after
it). Each has its own history table `flyway_history_<name>` in the metadata schema, and
`baselineOnMigrate(true)` at version `0`, so a module added to an existing database still runs its V1.
Order convention: core `0`, dev seed `10`, modules from `100` in dependency order. It stays JDBC-only
and runs at startup before traffic (ADR-008); `wasichai.database.migrate=false` turns it off.

`WasichaiMigrations` runs via `InitializingBean.afterPropertiesSet()`, not `@Bean(initMethod =
"migrate")`: an app that replaces the `wasichaiMigrations` bean with its own instance still gets
migrations run, because the container calls `afterPropertiesSet()` on whatever bean holds that name.
A bean that touches the database at startup must declare `@DependsOn("wasichaiMigrations")` so it does
not race the migration that creates its own tables.

**Schemas are configurable.** `wasichai.database.metadata-schema` (default `wasichai`) and
`wasichai.database.data-schema` (default `app_data`) become the `WasichaiSchemas` bean, validated as plain
identifiers at boot. Kotlin SQL interpolates them; migration SQL uses the Flyway placeholders
`${metadataSchema}` and `${dataSchema}`.

**The history was rebaselined, not replayed** (no wasichai database exists yet). Core's
`db/wasichai/core/V1__core.sql` is sapgis's final schema restricted to core tables: organizations, users,
roles, user_roles, custom_objects, custom_fields, relationships, permissions, field_permissions,
audit_log. Column order and constraint names are sapgis's, including the FK
`relationships_source_field_id_fkey` that kept its name when its column became `relation_field_id`.
Cross-module pieces move to the module that needs them:
- gis adds `custom_fields.geometry_type|srid|dimension`, their three CHECKs, re-adds
  `custom_fields_type_valid` with `GEOMETRY` appended, and creates `postgis`;
- documents adds `audit_log_document_id_fkey` and re-adds `audit_log_operation_valid` with `ISSUE`;
- views, forms, pages, workflow and automation create their own tables.
Installed together, the final schema equals sapgis's (modulo the schema rename), which P6 checks
with a `pg_dump --schema-only` diff.

Core creates `pgcrypto` and, when the server has it, `vector`, both `WITH SCHEMA public` — an
extension created without that clause lands wherever the connection's `search_path` points, which is
the metadata schema here, and a second schema pair in the same database would then either fail to find
it or create a second copy. It no longer creates `postgis`.

**The dev seed is opt-in**: `ModuleMigration("core_seed", "classpath:db/wasichai/core-seed", 10)` is
registered only with `wasichai.seed.dev=true`. It creates the Demo organization and
`admin@wasichai.local` / `admin` with every admin action, storing a precomputed bcrypt hash — the `$2a$`
prefix `BCryptPasswordEncoder` emits and the one pgcrypto's own `crypt()` accepts, not `$2y$` — rather
than calling `crypt()`, because with several schema pairs in one database pgcrypto lives wherever it
was created first.

## Consequences

- Adding a module to a running app is adding a dependency: its migrations run on the next boot.
- Removing a module leaves its tables and columns behind. Core ignores columns it did not ask for.
- A second module that adds a field type cannot simply re-add `custom_fields_type_valid` with its own
  list, because it would erase the first one's type. Only one module gets to own a given CHECK this
  way — the same limit applies to `audit_log_operation_valid` — and today only gis and documents do it;
  a third one needs this ADR revisited (for example, core dropping the CHECK in favour of
  `FieldTypeRegistry`).
- Beans that touch the database at startup must `@DependsOn("wasichaiMigrations")`.

## Addendum (2026-09-25, P7): a CHECK has one extending owner

Two core constraints list values that a module adds: `custom_fields_type_valid` (wasichai-gis adds `GEOMETRY`) and
`audit_log_operation_valid` (wasichai-documents adds `ISSUE`). PostgreSQL cannot append to a CHECK, so the module
drops the constraint and recreates it with its value added. That is safe only while one module extends a given
constraint. If a second module ever needs to add a value to the same CHECK, the constraint moves to a lookup table
or a trigger in core, and this addendum is superseded. The extending owners today: wasichai-gis for the field-type
CHECK, wasichai-documents for the audit-operation CHECK. A migration is never edited to record this (Flyway checksums),
so this addendum is where it is written down.
