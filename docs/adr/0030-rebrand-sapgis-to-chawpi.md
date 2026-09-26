# ADR-030: Sapgis is renamed chawpi, and its history stays readable

**Status**: accepted · 2026-09-25 · names superseded by [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md)

> Kept verbatim when chawpi became wasichai (2026-09-26): this ADR records the sapgis → chawpi rename. The current names are in ADR-032.

## Context

The platform began as sapgis, a GIS-first app. As libraries, GIS is one optional module among eight (ADR-027), and a
name that promises GIS would mislead an app that has none. The code also carried the name as literals: the
metadata schema in about 120 SQL strings, the `sapgis.*` configuration keys, `SapgisException`, the problem type URI,
the seed user and the browser storage keys.

## Decision

Every identifier is renamed, and every literal that an app might need to change becomes configuration:

| Was | Is |
|---|---|
| package `com.sapgis.<area>` | `chawpi.core.<area>` for core areas, `chawpi.<module>` for modules |
| Maven group, npm scope | `chawpi`, `@chawpi` |
| configuration `sapgis.*`, environment `SAPGIS_*` | `chawpi.*`, `CHAWPI_*` |
| schema literal `sapgis` | `chawpi.database.metadata-schema`, default `chawpi` (`ChawpiSchemas`) |
| data schema `app_data` | unchanged by default, configurable as `chawpi.database.data-schema` |
| `SapgisException` | `ChawpiException` |
| problem type URI | `chawpi.web.problem-base-uri`, default `https://chawpi.dev/problems` |
| seed user `admin@sapgis.local`, always seeded | `admin@chawpi.local`, only with `chawpi.seed.dev=true` |
| browser storage keys `sapgis.*` | prefix `storagePrefix` in `ChawpiApp` config, default `chawpi` |
| Docker Compose project, database, user | `chawpi` |

**History.** The repository starts with a fresh git history. The original repository stays the historical reference,
and `docs/sapgis-origin.md` says where it is and what came from it. Nothing is lost: ADRs 0001–0023 are imported
with the original decisions unchanged, identifiers renamed, and the header line "Imported from sapgis …". Changes to
those decisions are made by new ADRs, never by editing them. `docs/HISTORY.md` keeps every original entry as
written, below the entry "Chawpi starts from sapgis". The original plans and specs stay in `docs/superpowers/`.

**Where the old name stays.** Only where it is history or provenance: `docs/HISTORY.md`, `docs/sapgis-origin.md`,
`docs/superpowers/**`, the provenance header of ADRs 0001–0023, the Context and Decision text of ADRs 0024–0031,
the one README and CLAUDE.md line pointing at `docs/sapgis-origin.md`, tests asserting that a migration or bundle
does not contain the name, the porting tools (`frontend/tooling/port-from-sapgis*.mjs`) and the integration-test
porting scripts, and byte-identical copied data (`examples/gis-sample/perene/`). Everywhere else, code and docs say
"the original app".

## Consequences

- A fresh database gets the same tables as the original, in a schema named `chawpi` (or whatever the app
  configures). There is no in-place migration from an original database, because none exists to migrate.
- Two apps can share one PostgreSQL database by giving each its own metadata and data schema.
- Anyone searching the code for the old name finds only provenance. A hit anywhere else is a leftover, and the
  final P7 check fails on it.
