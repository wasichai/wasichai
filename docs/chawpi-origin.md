# Where wasichai comes from

Wasichai started on 2026-09-26 as a copy of **chawpi**, the metadata-driven platform extracted from sapgis as
libraries (see [sapgis origin](sapgis-origin.md) for that earlier step). Nothing changed but the names and the
repository layout: [ADR-032](adr/0032-rebrand-to-wasichai-and-split-repositories.md) has the full naming table and the
reasons.

The git history was not imported. The source was chawpi's working tree, including its last uncommitted change (the
`@hneyra` npm scope, superseded here by `@wasichai`). What went where:

| chawpi | wasichai | wasichai-ui |
|---|---|---|
| `backend/build-logic`, `backend/chawpi-*`, `backend/starters` | `build-logic`, `wasichai-*`, `starters` (repo root) | — |
| `frontend/packages`, `frontend/tooling` | — | `packages`, `tooling` (repo root) |
| `examples/*/server`, `examples/gis-sample/perene` | same paths | — |
| `examples/*/web` (incl. the Playwright e2e) | — | same paths |
| `infra/docker` | same path | — |
| `docs/**` (ADRs, HISTORY, modules, guides, api, security, specs, plans) | same paths | `docs/README.md` links here |
| `.github/workflows`, `.github/scripts` | backend jobs, Maven publish | frontend jobs, e2e, npm publish |

Renames: `chawpi` → `wasichai`, `Chawpi` → `Wasichai`, `CHAWPI_` → `WASICHAI_`, `@hneyra/*` and `@chawpi/*` →
`@wasichai/*`, `maven.pkg.github.com/hneyra/chawpi` → `maven.pkg.github.com/wasichai/wasichai`. `docs/superpowers/**`
and the HISTORY entries before 2026-09-26 are verbatim, so they still say chawpi, `backend/` and `frontend/`.

Later on 2026-09-26 the samples and the local infrastructure moved again, out of both repositories
([ADR-033](adr/0033-samples-and-infrastructure-in-their-own-repositories.md)): each `examples/<sample>` (server and
web) is now the repository `wasichai/<sample>`, `examples/gis-sample/perene` is `gis-sample/perene`, and
`infra/docker` is `wasichai-infrastructure/docker`.
