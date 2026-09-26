# Where chawpi comes from

> Chawpi is now wasichai (2026-09-26). This page is kept as written; see [chawpi origin](chawpi-origin.md).

Chawpi started on 2026-09-25 as a copy of **sapgis** (Spatial Application Platform for GIS), a
metadata-driven platform built as a single application. Chawpi keeps every decision and feature and
reshapes them into reusable libraries (see
[the design](superpowers/specs/2026-09-25-chawpi-libraries-design.md)).

The git history was not imported. What was copied:

| sapgis | chawpi | Changes |
|---|---|---|
| `docs/adr/0001–0023` | `docs/adr/0001–0023` | identifiers renamed, provenance header added |
| `docs/{architecture,domain,api,gis,security,development}` | same paths | identifiers renamed; updated to the module layout in P7 |
| `docs/HISTORY.md` | `docs/HISTORY.md` | verbatim below the chawpi entries |
| `docs/superpowers/{specs,plans}` | same paths | verbatim (historical, paths point at sapgis) |
| `examples/perene` | `examples/gis-sample/perene` | identifiers renamed |
| `infra/docker` | `infra/docker` | renamed; geoserver behind the `gis` profile |
| `.editorconfig`, `.prettierrc.json` | repo root | verbatim |

Renames: `com.sapgis` → `chawpi`, `sapgis.*` properties → `chawpi.*`, `SAPGIS_*` → `CHAWPI_*`,
schema `sapgis` → `chawpi`, `admin@sapgis.local` → `admin@chawpi.local`, `GEOFORGE_*` → `CHAWPI_*`.
