# Notifications ("Alertas"), srtm-backend and srtm-ui adoption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** srtm's staff are told, in the app, what needs doing: previas that end this week or ended without an acta,
actas without a resolución, descargos to resolve, resoluciones to serve, next year's missing parameters, anuncios about
to end, the end of their mass emisión or determinación, and special conditions or representatives that end; and they
read manual comunicados (ordinances, TUPA, manuals) with a link to the document.

**Architecture:** srtm-backend adopts wasichai 0.4.0 and `wasichai-spring-boot-starter-notifications`. A new package
`srtm.alertas` holds three `NotificationSource` beans that count, never list (`srtm.sanciones`, `srtm.parametros`,
`srtm.anuncios`), each reusing the pure functions and readers srtm already has, and one component that publishes when a
mass job ends or fails (`srtm.trabajos`). Units (`model/org_units.json`) and two date rules (`model/notifications.json`)
are applied over REST by Python scripts, like the model. srtm-ui mounts `@wasichai/notifications`' provider, bell and
page in its own portal shell and gives its fichas' tabs keys.

**Tech Stack:** Kotlin 2.4.20, Spring Boot 4.1 WebFlux, wasichai 0.4.0, Python 3.11 (stdlib) for `model/`; React 19,
`@wasichai/notifications` 0.6.x, vitest.

**Spec:** [`docs/superpowers/specs/2026-10-06-notifications-design.md`](../specs/2026-10-06-notifications-design.md)
(wasichai), "Use cases" and sections B–E. UI side: [`2026-10-06-notifications-wasichai-ui.md`](2026-10-06-notifications-wasichai-ui.md).

**Repositories:** `../srtm-backend`, branch `feat/alertas` off PR #90's base once #90 is merged, PR to that base;
`../srtm-ui`, branch `feat/alertas` off `origin/dev`, PR to `dev`.

## Global Constraints

- **Prerequisites, in order:** srtm-backend PR #90 (`chore/wasichai-0-3-2`, wasichai 0.3.2) merged; wasichai 0.4.0
  released (core `V9`, the notifications module, TAB keys); wasichai-ui 0.6.0-dev.N published for srtm-ui.
- **Naming.** The UI says **"Alertas"** (Comunicados, Advertencias, Pendientes). In srtm, "notificación" is the legal act
  served on a taxpayer and "aviso" a kind of anuncio (`AVISO_*`): never use either for this feature, in identifiers,
  strings, commits or docs. The package is `srtm.alertas`; wasichai's own types keep their names (`NotificationSource`,
  `Notifications`). A title may name the legal acts ("3 notificaciones previas vencen esta semana"): that is the domain.
- **Count, do not list.** A source returns a handful of drafts per organization (one per kind of pending work), with a
  link to the list that shows them. When a count is exactly 1, the draft may link the record itself.
- **Days.** A source receives `now: Instant` and turns it into Lima's date (`LocalDate.ofInstant(now, LIMA)`); srtm has
  no `Clock` bean, so `application.yml` sets `wasichai.notifications.zone: America/Lima` for the date rules. Legal
  plazos in business days (`Plazos.hasta`) are never date rules: they are computed in sources.
- **Reuse, do not re-derive:** "vence" is `Notificaciones.vencimiento`, the fase is `Procedimiento.fase`, an anuncio's
  term is `Anuncios.vigencia`, missing parameters are `Plazos.cargados(...).faltan` and `ArbitriosService.parametros`.
- Sources run as the platform (`RecordService.asPlatform`, inside the module's loop): every reader they call goes
  through `Registros`/`RecordService` and must not call `currentUser.require()`.
- **Audiences:** srtm ships no named roles, so units are the audience. The tree below is a proposal: units, codes and
  who belongs where are to be agreed with srtm's owners before Task 1 is merged.
- `.editorconfig` (160 columns), `./gradlew ktlintFormat` before each commit; `yarn format` in srtm-ui. Conventional
  Commits (`feat(alertas): …`, `build: …`), each message ending with the session's attribution lines.

## Review Focus

1. **Volume:** no source returns one draft per act; every key is stable (`previas-semana:<lunes>`, `parametros:<anio>`)
   so a repeated run writes nothing (Tasks 3–5).
2. **Lima's day:** a source run at 00:30 Lima (05:30 UTC) counts Lima's day, not UTC's (Task 2 test with a fixed instant).
3. **A job's end is told once and never breaks the worker:** the key is the job's id; a failing publish is logged and
   the worker goes on (Task 6).
4. **Anuncios are not a date rule:** `anuncio.vigencia_hasta` is the authorization's term; a renewal adds a
   `movimiento_anuncio` and never edits it (`Anuncios.vigencia`, `Anuncios.kt:58`), so a rule on it would warn about
   renewed, ceased and removed anuncios (Task 5).
5. **Naming:** `grep -rni "aviso\|notificaci" src/main/kotlin/srtm/alertas` shows only the legal acts in titles (Task 9).

---

## Part A — srtm-backend

### Task 1: wasichai 0.4.0, the starter, Lima

**Files:** `build.gradle.kts` (`wasichaiVersion = "0.4.0"`,
`implementation("wasichai:wasichai-spring-boot-starter-notifications")`), `src/main/resources/application.yml`
(`wasichai.notifications.zone: America/Lima`), `src/test/resources/application-test.yml` or the test properties the
suite uses (`wasichai.notifications.tick: 0s`, so tests drive the loop), `README.md` (modules row: "core, workflow,
documents, views, forms, pages, gis, notifications"; a section "Alertas").

- [ ] Bump; `./gradlew build integrationTest` → PASS (core `V9` and the module's `V1` migrate on the test database).
- [ ] Commit `build: wasichai 0.4.0 with notifications`.

### Task 2: units of the organization

**Files:**
- Create `model/org_units.json`, `model/apply_org_units.py` (stdlib, `core_client.py`; `--dry-run`, `--core`,
  `--email`, `--password` like `apply.py`), `model/test_apply_org_units.py` (with `fake_core.py`, which learns
  `GET/POST/PUT /api/org-units`).
- Modify `README.md` ("Cargar las unidades").

The proposal (codes never change once apps bind to them; labels can):

```json
{
  "units": [
    {"code": "GAT", "label": "Gerencia de Administración Tributaria"},
    {"code": "SGRT", "label": "Subgerencia de Registro y Recaudación Tributaria", "parent": "GAT"},
    {"code": "SGFT", "label": "Subgerencia de Fiscalización Tributaria", "parent": "GAT"},
    {"code": "FISCALIZACION", "label": "Fiscalización", "parent": "SGFT"},
    {"code": "RESOLUCIONES", "label": "Resoluciones", "parent": "SGFT"},
    {"code": "NOTIFICADORES", "label": "Notificadores", "parent": "SGFT"}
  ]
}
```

The script creates what is missing parents first, fixes a label or parent that differs (`PUT` with only those keys),
never deletes, and prints `done: N created, M updated, K skipped`. Members are assigned in the admin (users page) or
with `PUT /api/users/{id}/org-units`; a unit is never a permission.

**Tests:** first run creates six in order; second run skips six; a changed label is one `PUT`; an unknown parent in
the file fails before any write.

- [ ] Tests first (`python3 -m unittest model/test_apply_org_units.py`); implement; commit
  `feat(model): units of the organization`.

### Task 3: `srtm.sanciones`, what the sanciones leave pending

**Files:**
- Create `src/main/kotlin/srtm/alertas/Alertas.kt` (constants: unit codes, `LIMA`, `inicioDelDia(fecha)`,
  `cuenta(n: Long, singular, plural)`), `src/main/kotlin/srtm/alertas/AlertasDeSanciones.kt`.
- Create `src/main/kotlin/srtm/sanciones/Pendientes.kt`: pure `Pendientes.contar(hechos, hoy)` over the actas read
  with `ActasService.hechos`: actas `PENDIENTE` in fase `CONSTATADA` (no RIS), descargos with no resolución naming them
  (`descargo`), RIS (`ADMINISTRATIVA`) with no notificación that takes effect
  (`NotificacionesDeResolucion.surteEfecto`) and nothing in the way (`Procedimiento.impedimentoDeNotificar == null`),
  split into "never tried" and "last try `NO_UBICADO`". Plus `PendientesService` that reads the actas of the last two
  years by `fecha_infraccion` (the window is to be agreed) and their hechos.
- Test `src/test/kotlin/srtm/sanciones/PendientesTest.kt`, `src/test/kotlin/srtm/alertas/AlertasDeSancionesTest.kt`
  (pure: drafts from given counts), `src/test/kotlin/srtm/alertas/AlertasApiTest.kt` (integration: seed previas and
  actas, run the loop's `runOnce()`, read `GET /api/auth/me/notifications` as a member of `FISCALIZACION`).

**Drafts** (source `srtm.sanciones`, interval 30 min):

| Key | Kind | Audience | Link |
|---|---|---|---|
| `previas-semana:<lunes>` | WARNING, due the Monday after | `FISCALIZACION` | `srtm:notificaciones` `{vencen: semana}` |
| `previas-vencidas` | ACTION | `FISCALIZACION` | `srtm:notificaciones-vencidas` |
| `actas-sin-ris` | ACTION | `RESOLUCIONES` | `srtm:actas` `{fase: CONSTATADA}`; one → `srtm:acta` `{id}` tab `RESOLUCIONES` |
| `descargos-sin-resolver` | ACTION | `RESOLUCIONES` | `srtm:actas` `{descargo: pendiente}`; one → `srtm:acta` tab `RESOLUCIONES` |
| `ris-por-notificar` | ACTION | `NOTIFICADORES` | `srtm:resoluciones` `{por-notificar: true}` |

A count of zero gives no draft, so the loop resolves the key. The route keys are srtm-ui's (Part B, Task 10).

The source, as it will be written:

```kotlin
package srtm.alertas

// what the sanciones leave pending, counted: an inbox with one line per act is an inbox nobody reads. «vence» is the
// panel's and the padrón's own (Notificaciones.vencimiento), never a second definition
@Component
class AlertasDeSanciones(
    private val paneles: PanelService,
    private val notificaciones: NotificacionesService,
    private val pendientes: PendientesService
) : NotificationSource {
    override val key = "srtm.sanciones"
    override val interval: Duration = Duration.ofMinutes(30)

    override suspend fun currentNotifications(
        organizationId: UUID,
        now: Instant
    ): List<NotificationDraft> {
        val hoy = LocalDate.ofInstant(now, LIMA)
        val panel = paneles.panel(hoy.year, hoy)
        val vencidas = notificaciones.vencidas(hoy, 0, 1).totalElements
        val p = pendientes.de(hoy)
        return listOfNotNull(
            panel.vencenEstaSemana.toLong().takeIf { it > 0 }?.let { n ->
                NotificationDraft(
                    kind = NotificationKind.WARNING,
                    title = cuenta(n, "notificación previa vence esta semana", "notificaciones previas vencen esta semana"),
                    audience = listOf(Audience.Unit(FISCALIZACION)),
                    link = NotificationLink.Route("srtm:notificaciones", mapOf("vencen" to "semana")),
                    key = "previas-semana:${panel.semana.desde}",
                    dueAt = inicioDelDia(panel.semana.hasta.plusDays(1))
                )
            },
            vencidas.takeIf { it > 0 }?.let { n ->
                NotificationDraft(
                    kind = NotificationKind.ACTION,
                    title = cuenta(n, "notificación previa vencida sin acta", "notificaciones previas vencidas sin acta"),
                    audience = listOf(Audience.Unit(FISCALIZACION)),
                    link = NotificationLink.Route("srtm:notificaciones-vencidas"),
                    key = "previas-vencidas"
                )
            }
        ) + delProcedimiento(p) // the three ACTION rows of the table, same shape
    }
}
```

- [ ] Tests first (`./gradlew test --tests '*Pendientes*' --tests '*AlertasDeSanciones*'` → FAIL); implement → PASS;
  `./gradlew integrationTest --tests '*AlertasApiTest*'` → PASS.
- [ ] Commit `feat(alertas): what the sanciones leave pending`.

### Task 4: `srtm.parametros`, next year's missing parameters

**Files:** create `src/main/kotlin/srtm/alertas/AlertasDeParametros.kt`; test `AlertasDeParametrosTest.kt`.

From 1 November (Lima) the source checks `anio = hoy.year + 1`: `Plazos.cargados(anio, hoy, parametros).faltan`
(PLAZO, FERIADOS), `ArbitriosService.parametros(anio).faltan`, and a UIT row in force on 1 January of `anio` (the
reading `ImpuestoPredial.liquidar` does; extract a pure `faltaUit(parametros, anio)` beside it if none exists). One
draft, key `parametros:<anio>`, WARNING until 14 December and ACTION from 15 December (a kind change is news), due 1
January 00:00 Lima, audience `Role("ADMIN")` and `Unit(SGRT)`, body the missing names one per line (cut at 4000), link
`srtm:parametros` `{anio}`. Nothing missing, or before November: no draft. Interval: 6 hours.

**Tests:** 31 October → none; 1 November with FERIADOS missing → WARNING naming `FERIADOS 2027`; 15 December → ACTION;
everything loaded → none.

- [ ] Tests first; implement; commit `feat(alertas): next year's missing parameters`.

### Task 5: `srtm.anuncios`, anuncios about to end (a source, not a date rule)

**Files:** create `src/main/kotlin/srtm/alertas/AlertasDeAnuncios.kt`; test `AlertasDeAnunciosTest.kt`.

The anuncios and their movimientos are read once (as `AnunciosService` lists them); per anuncio `Anuncios.estado` and
`Anuncios.vigencia` at `hoy`. Drafts: `anuncios-por-vencer` (WARNING, `VIGENTE` with a term within 30 days, due the
earliest term + 1 day) and `anuncios-vencidos` (ACTION, `VENCIDO`), audience `Unit(SGRT)` (to agree), link
`srtm:anuncios` `{estado: …}`. Interval: 6 hours.

**Tests:** a renewed anuncio (movimiento with a later `vigencia_hasta`) is not "por vencer"; a `CESADO` or `RETIRADO`
one is neither; a `VENCIDO` one counts in the ACTION.

- [ ] Tests first; implement; commit `feat(alertas): anuncios about to end`.

### Task 6: the end of a mass emisión or determinación is told to whoever launched it

**Files:**
- Create `src/main/kotlin/srtm/alertas/AlertasDeTrabajos.kt`.
- Modify `src/main/kotlin/srtm/emision/EstadoTrabajos.kt` (constructor takes `AlertasDeTrabajos`; `fallida` calls it
  when the transition applied; a small read of the job's `created_by` and `anio`),
  `src/main/kotlin/srtm/emision/EstadoEmisiones.kt` (`terminar` calls it when the transition applied),
  `src/main/kotlin/srtm/arbitrios/TrabajoDeterminacion.kt` (`cerrar`, after `cerrada`),
  `src/main/kotlin/srtm/arbitrios/DeterminacionMasiva.kt` (`MaquinaDeterminacion` passes the bean),
  `EmisionMasivaService.eliminar` and the determinación's delete (resolve the key: the job is gone).
- Test `src/test/kotlin/srtm/emision/EmisionMasivaAlertasApiTest.kt` (integration: a small emisión ends → its
  launcher has one ACTION; a forced failure → one WARNING; a second `terminar` on the same job writes nothing).

Source `srtm.trabajos`, keys `emision:<id>` and `determinacion:<id>`, audience the job row's `created_by`; finished →
ACTION ("download / review", link `srtm:emision` or `srtm:determinacion` `{id}`), failed → WARNING with the job's
`mensaje` as body; both expire 7 days after the end. No `created_by` (a job created by the system): nothing is published.

```kotlin
// a mass job ended: whoever launched it hears it once (the key is the job). the job is already marked when this runs,
// so a publish that fails is logged and the worker goes on
suspend fun termino(trabajo: TrabajoTerminado) {
    val creador = trabajo.creadoPor ?: return
    try {
        notifications.publish(
            trabajo.organizacion,
            "srtm.trabajos",
            NotificationDraft(
                kind = if (trabajo.fallo == null) NotificationKind.ACTION else NotificationKind.WARNING,
                title = trabajo.titulo().take(200),
                body = trabajo.fallo?.take(4000),
                audience = listOf(Audience.User(creador)),
                link = NotificationLink.Route(trabajo.ruta, mapOf("id" to trabajo.id.toString())),
                key = "${trabajo.prefijo}:${trabajo.id}",
                expiresAt = trabajo.terminado.plus(Duration.ofDays(7))
            )
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("El fin del trabajo {} no se pudo publicar en Alertas", trabajo.id, e)
    }
}
```

- [ ] Tests first; implement; commit `feat(alertas): a mass job's end reaches whoever launched it`.

### Task 7: date rules for conditions and representatives

**Files:** create `model/notifications.json`, `model/apply_notifications.py` (`GET` the object's rules; `POST` a new
one, `PUT` one that differs, skip an equal one; `--dry-run`, `--core`, `--email`, `--password`),
`model/test_apply_notifications.py`; modify `README.md` ("Cargar las reglas de alertas").

```json
{
  "rules": [
    {
      "object": "declaracion_predial", "name": "condicion_por_terminar", "label": "Condiciones especiales por terminar",
      "enabled": true, "field": "condicion_fecha_fin",
      "stages": [{"fromDays": -30, "kind": "WARNING"}, {"fromDays": 0, "kind": "ACTION"}], "untilDays": 15,
      "conditions": [{"field": "condicion_especial", "op": "NOT_EMPTY"}, {"field": "estado", "op": "EQ", "value": "VIGENTE"}],
      "audience": [{"type": "UNIT", "value": "SGRT"}],
      "title": "La condición {{condicion_especial}} de una declaración jurada termina el {{date}}",
      "body": "Revise el sustento antes de la próxima emisión."
    },
    {
      "object": "declaracion_predial", "name": "condicion_por_terminar_importada", "label": "Condiciones por terminar (importadas)",
      "enabled": true, "field": "condicion_fecha_fin",
      "stages": [{"fromDays": -30, "kind": "WARNING"}, {"fromDays": 0, "kind": "ACTION"}], "untilDays": 15,
      "conditions": [{"field": "condicion_especial", "op": "NOT_EMPTY"}, {"field": "estado", "op": "EMPTY"}],
      "audience": [{"type": "UNIT", "value": "SGRT"}],
      "title": "La condición {{condicion_especial}} de una declaración jurada termina el {{date}}"
    },
    {
      "object": "relacionado", "name": "relacionado_por_terminar", "label": "Relacionados por terminar",
      "enabled": true, "field": "fecha_fin",
      "stages": [{"fromDays": -15, "kind": "WARNING"}], "untilDays": 0,
      "conditions": [{"field": "estado", "op": "EQ", "value": "ACTIVO"}],
      "audience": [{"type": "UNIT", "value": "SGRT"}],
      "title": "{{tipo_relacionado}} {{nombres}} {{apellido_paterno}}{{razon_social}} deja de serlo el {{date}}"
    }
  ]
}
```

Two rules for the condition because an imported declaración has an empty `estado` that counts as `VIGENTE`, and the
rule conditions have no "or". Days are calendar days, in Lima (Task 1). A declaración replaced by a later one keeps its
`condicion_fecha_fin`: run the rule over the data once with srtm's owners; if old declaraciones warn, move this case to
a source like Task 5 instead. A `tab` is added once srtm-ui's fichas carry keys (Task 11).

**Tests:** first run posts three; second skips three; a changed title is one `PUT`; the file passes the spec's checks
before any call (name format, 1–5 stages, ops).

- [ ] Tests first; implement; run against a local server and check the counts in the inbox; commit
  `feat(model): date rules for conditions and representatives`.

### Task 8: comunicados with a link to the document

No code. `README.md` ("Comunicados") documents the routine: an administrator writes an INFO in the admin (or
`POST /api/notifications`) with a `URL` link to the ordinance, the TUPA or the manual, the audience (`ALL`, `GAT` or a
subgerencia) and a window (`expiresAt` 30 days later, say). Example body for a new arbitrios ordinance, with the link
to its PDF on the municipality's site.

- [ ] Write the section; commit `docs: comunicados in Alertas`.

### Task 9: the whole check and the PR

- [ ] `./gradlew ktlintFormat build integrationTest` and `python3 -m unittest discover model` → PASS.
- [ ] `grep -rni "aviso\|notificaci" src/main/kotlin/srtm/alertas README.md` → only the legal acts.
- [ ] Push; PR to PR #90's base, describing the units to agree (Task 2) and the rule check (Task 7).

---

## Part B — srtm-ui

srtm-ui's portal has its own shell (`src/portal/shell/AppShell.tsx`, `LateralPortal`, `NAV_TREE` in
`src/portal/shell/navTree.ts`) and runs `createRegistry([])`, so it does not register `notificationsModule()` in the
portal: it mounts the package's pieces and resolves its own route keys. The `/admin` `WasichaiApp` registers
`notificationsModule()` so administrators compose comunicados and edit rules there.

### Task 10: the bell, the page and srtm's links

**Files:**
- Modify `package.json` (`@wasichai/notifications`), `src/portal/PortalApp.tsx` (`NotificationsProvider` with
  `resolveLink` and `inboxPath="/alertas"`, a route `/alertas` drawing `InboxPage`), `src/portal/shell/AppShell.tsx`
  (`NotificationBell` in the header bar), `src/portal/shell/navTree.ts` (a leaf "Alertas" at `/alertas`), the admin
  `WasichaiApp`'s module list (`notificationsModule()`).
- Create `src/portal/alertas/enlaces.ts` (+ `enlaces.test.ts`): `resolverEnlace(link, links)` maps `srtm:notificaciones`,
  `srtm:notificaciones-vencidas`, `srtm:actas`, `srtm:acta`, `srtm:resoluciones`, `srtm:parametros`, `srtm:anuncios`,
  `srtm:emision`, `srtm:determinacion` to the portal's paths (params to the path or the query, `tab` as `?tab=`);
  `RECORD` links of `declaracion_predial` and `relacionado` go to the portal's fichas when it has one, else to
  `/admin` + `links.record`; anything else delegates to `defaultResolveLink`.

**Tests:** each srtm key resolves to its path; an unknown `srtm:` key gives no link; the bell shows in the portal
header and the leaf in the tree; "Alertas" everywhere, no "aviso" or "notificación" for the feature.

- [ ] Tests first; implement; commit `feat(portal): Alertas in the portal`.

### Task 11: the fichas' tabs have keys and follow `?tab=`

**Files:** the `*_TABS` constants of the fichas (grep `_TABS` in `src/portal`) and the tabs component that draws them
(`FichaTabs`).

Each tab spec gains `key`, its id upper-cased (`RESOLUCIONES`, `DESCARGOS`, `NOTIFICACIONES`…, matching the
`^[A-Z][A-Z0-9_]{0,39}$` format); `FichaTabs` selects the tab named by `?tab=` (case-insensitive) and falls back to the
first; a click writes `?tab=KEY` with `replace`. The keys used by srtm-backend's links (`RESOLUCIONES` in Task 3) must
exist; a test lists them.

- [ ] Tests first; implement; commit `feat(portal): a ficha opens on the tab a link names`.

### Task 12: `@wasichai/*` 0.6.0-dev.N and the PR

- [ ] Bump `@wasichai/*`; `yarn typecheck && yarn test && yarn lint && yarn format`; PR to `dev`.

## Order

```
PR #90 merged ── wasichai 0.4.0 ── T1 ── T2 ──┬─ T3 · T4 · T5 · T6 · T7 (parallel) ── T8 ── T9
wasichai-ui 0.6.0-dev.N ───────────────────────┴─ T10 ── T11 ── T12 (after T3's route keys are fixed)
```
