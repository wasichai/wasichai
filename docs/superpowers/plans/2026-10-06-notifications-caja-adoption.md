# Notifications ("Alertas"), caja-backend and caja-ui adoption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** caja's staff learn in the app, not from a log line, what needs doing: money charged and never recorded (a
`MUERTO` payment), a turno of an earlier day still open, payments that block today's closing, a day whose
reconciliation does not balance, a closing with a cash difference, fees about to end without a successor and new fees
in force with their legal document.

**Architecture:** caja-backend adopts wasichai 0.4.0 and `wasichai-spring-boot-starter-notifications`. Events caja
already detects in its own code publish through the `Notifications` bean: the outbox when a payment dies (keeping the
`DINERO COBRADO SIN REGISTRAR` ERROR line), the explanation that resolves it, the closing with a difference. Computed
states are `NotificationSource` beans in a new package `caja.alertas` that reuse caja's readers and pure rules:
`caja.turnos`, `caja.conciliacion`, `caja.tasas`. caja-ui mounts `@wasichai/notifications` in its portal shell.

**Tech Stack:** Kotlin 2.4.20, Spring Boot 4.1 WebFlux, wasichai 0.4.0, mockwebserver (tests); React 19,
`@wasichai/notifications` 0.6.x, vitest.

**Spec:** [`docs/superpowers/specs/2026-10-06-notifications-design.md`](../specs/2026-10-06-notifications-design.md)
(wasichai), "Use cases" (the caja rows) and sections B–D. UI side:
[`2026-10-06-notifications-wasichai-ui.md`](2026-10-06-notifications-wasichai-ui.md).

**Repositories:** `../caja-backend`, branch `feat/alertas` off `main` after caja-backend#28 is merged; `../caja-ui`,
branch `feat/alertas` off `origin/main` (caja-ui has no `dev`). PRs to `main`.

## Global Constraints

- **Prerequisites, in order:** caja-backend#28 (the upgrade from wasichai 0.2.0 to 0.3.x, which brings
  `RecordService.asPlatform`) merged; wasichai 0.4.0 released; wasichai-ui 0.6.0-dev.N published for caja-ui.
- **Explicit publish where caja writes without core.** The outbox marks `pago_evento` straight through
  `DatabaseClient` (`BuzonStore.marcar`), so no `RecordChangeListener` ever sees a payment die: the publisher publishes.
- **The log stays the record.** The ERROR line `DINERO COBRADO SIN REGISTRAR` is unchanged and goes first; a failing
  publish is logged (WARN) and never stops the outbox's round.
- **Inside caja's transactions, a draft must be valid.** A malformed draft is an `IllegalArgumentException` that would
  roll back a closing or an explanation: titles are cut to 200 characters, bodies to 4000, keys are built from UUIDs and
  ISO dates only.
- **Sources run outside any transaction** and as the platform; `ClienteDelSistemaDeOrigen.leer` refuses to run inside
  one (`check(!enTransaccion())`), and the conciliation source calls it.
- **Lima's day:** caja's `Clock` (`Relojes.reloj`, America/Lima) is the app's unique `Clock`, so the module counts days
  in Lima without configuration; a source still turns `now` into `LocalDate.ofInstant(now, LIMA)` itself.
- **Count, do not list**, except where each item is its own job to do (a dead payment, an open turno, a closing).
- Roles are caja's (`model/roles.json`): `CAJERO`, `SUPERVISOR_CAJA`, `TESORERIA`. A cashier is reached by email
  (`turno.cajero` is the cashier's email, `Audience.Email`).
- Spanish identifiers and comments as caja writes them (caveman style, say why). `.editorconfig` (160 columns),
  `./gradlew ktlintFormat`; `yarn format` in caja-ui. Conventional Commits (`feat(alertas): …`), each message ending with
  the session's attribution lines.

## Review Focus

1. **A dead payment is told once and resolved by its explanation:** key `muerto:<pagoId>`; the explanation resolves it
   in its own transaction (a rolled-back explanation leaves it open) (Task 2).
2. **No alert without the log, no lost round without the alert:** the ERROR line precedes the publish; a publish that
   throws is caught (Task 2).
3. **A closing never fails because of an alert** (draft limits above), and a reversal resolves its difference alert in
   the same transaction (Task 3).
4. **Conciliation without a buzón:** with `caja.buzon.habilitado=false` every day "does not balance" (no destination to
   ask): the source returns nothing (Task 5).
5. **A day keeps its alert while it is still wrong:** the source returns every unbalanced day of the window, not only
   yesterday, because a key the source no longer returns is resolved (Task 5).
6. **Fees:** an annual TUPA renewal (every fee ends on 31 December, the new rows loaded in December) gives one alert per
   end date, not one per fee (Task 6).

---

## Part A — caja-backend

### Task 1: wasichai 0.4.0, the starter, `caja.alertas`

**Files:** `build.gradle.kts` (`wasichaiVersion = "0.4.0"`,
`implementation("wasichai:wasichai-spring-boot-starter-notifications")`); create
`src/main/kotlin/caja/alertas/Alertas.kt` (source keys, route keys `caja:pagos-sin-entregar`, `caja:arqueo`,
`caja:conciliacion`, `caja:tasas`, role names, `inicioDelDia(fecha)`, `recortar(texto, max)`) and
`PropiedadesDeAlertas` (`@ConfigurationProperties("caja.alertas")`: `dias-turnos` 90, `dias-conciliacion` 7,
`dias-tasas` 30); `src/main/resources/application.yml` (the `caja.alertas` block); the test properties
(`wasichai.notifications.tick: 0s`: tests drive the loop with `runOnce()`); `README.md` (modules row, a section
"Alertas").

- [ ] Bump; `./gradlew build integrationTest` → PASS.
- [ ] Commit `build: wasichai 0.4.0 with notifications`.

### Task 2: a dead payment is an ACTION until someone explains it

**Files:**
- Modify `src/main/kotlin/caja/buzon/ConfiguracionDelBuzon.kt`: `ResponsableDeLaConciliacion` gains `correo` from
  `caja.conciliacion.correo` (optional; when set it must look like an email, checked at start-up). The existing
  `responsable` is a person's **name** and `canal` a channel, so neither is an email.
- Modify `src/main/resources/application.yml` (`correo: ${CAJA_CONCILIACION_CORREO:}`), `develop/example.env`,
  `README.md` (variables table).
- Modify `src/main/kotlin/caja/buzon/PublicadorDelBuzon.kt`: inject `Notifications`; in `deLaOrganizacion`, on
  `Marca.MUERTO`, call `alertar(intento)` and then `publicarMuerto(buzon, intento)`.
- Modify `src/main/kotlin/caja/buzon/ExplicarPagoSinEntregar.kt`: inside `transaccion.en`, after the `replace`, resolve
  `muerto:<id>`.
- Test `src/test/kotlin/caja/buzon/BuzonApiTest.kt` (new cases), `ResponsableDeLaConciliacionTest.kt` (the email check).

```kotlin
// el pago muerto también es una alerta en la app, para el supervisor y para el responsable de la conciliación. la
// línea ERROR ya salió (alertar) y sigue siendo el registro: si publicar falla, se dice y la vuelta sigue
private suspend fun publicarMuerto(
    buzon: BuzonStore.Buzon,
    muerto: Intento
) {
    val e = muerto.evento
    try {
        notifications.publish(
            buzon.organizacion,
            ALERTAS_DEL_BUZON, // "caja.buzon"
            NotificationDraft(
                kind = NotificationKind.ACTION,
                title = recortar("Dinero cobrado sin registrar: recibo ${muerto.numero ?: e.recibo}, destino ${e.sistemaDestino}", 200),
                body = recortar("${muerto.error}. Su turno no cierra hasta que se entregue o se explique por escrito.", 4000),
                audience = listOfNotNull(Audience.Role(SUPERVISOR_CAJA), responsable.correo?.let { Audience.Email(it) }),
                link = NotificationLink.Route(PAGOS_SIN_ENTREGAR), // "caja:pagos-sin-entregar"
                key = "muerto:${e.eventoId}"
            )
        )
    } catch (ex: CancellationException) {
        throw ex
    } catch (ex: Exception) {
        log.warn("La alerta del pago {} no se pudo publicar en la app; la línea ERROR ya salió", enUnaLinea(e.eventoId), ex)
    }
}
```

and in `ExplicarPagoSinEntregar.explicar`, inside the transaction:

```kotlin
// explicado, deja de ser un pendiente: con la explicación, o con nada si esta se revierte
notifications.resolve(usuario.organizationId, ALERTAS_DEL_BUZON, "muerto:$id")
```

**Tests (integration, the fake origin system answering `400`):** the payment dies → the ERROR line and one ACTION for
a `SUPERVISOR_CAJA` user and for the configured email, none for a `CAJERO`; a second round writes nothing; the
explanation resolves it (`GET /api/auth/me/notifications` no longer lists it); an explanation that fails validation
leaves it open; with `Notifications` throwing (a `@MockitoBean` spy), the round still marks the payment and logs.

- [ ] Tests first → FAIL; implement → PASS; commit `feat(alertas): a dead payment until it is explained`.

### Task 3: a closing with a cash difference

**Files:** modify `src/main/kotlin/caja/turno/CerrarTurno.kt` (inject `Notifications`; in `cerrarEnLaTransaccion`,
after the lines; in `reversarEnLaTransaccion`, resolve the reversed closing's key); test
`src/test/kotlin/caja/turno/CierreApiTest.kt`.

When `arqueo.diferencia` is not zero: source `caja.cierres`, WARNING, key `diferencia:<cierreId>`, audience
`Role(SUPERVISOR_CAJA)` and `Role(TESORERIA)`, title "Cierre con diferencia de S/ <diferencia> en la caja <codigo>
(<cajero>, <fecha>)" cut at 200, body the closing's observation, link `Route("caja:arqueo", {turnoId})`, `expiresAt` =
now + 7 days. It joins the closing's transaction: a closing that rolls back publishes nothing. A reversal resolves
`diferencia:<cierreRevertido>`; closing again with a difference publishes under the new closing's id.

**Tests:** a difference → one WARNING for supervisor and treasury, none for the cashier; no difference → none; a
closing that hits the duplicate key (`409`) leaves none; a reversal resolves it.

- [ ] Tests first; implement; commit `feat(alertas): a closing with a cash difference`.

### Task 4: `caja.turnos`, open turnos and blocked closings

**Files:** create `src/main/kotlin/caja/alertas/AlertasDeTurnos.kt` (source, interval 15 min) and a pure
`turnosQueAvisar(turnos, historias, pagos, hoy)`; tests `AlertasDeTurnosTest.kt` (pure) and
`src/test/kotlin/caja/alertas/AlertasApiTest.kt` (integration, `runOnce()`).

Reads, as the platform, the turnos of the last `dias-turnos` days (`fecha` between `hoy - dias` and `hoy`), their
histories (`LibroDelTurno.historias`) and, for today's open ones, `LibroDelTurno.pagosSinEntregar`:

- An **earlier day's turno still `ABIERTO`** → ACTION `turno-abierto:<turnoId>`, audience `Email(turno.cajero)` and
  `Role(SUPERVISOR_CAJA)`, due the start of the day after the turno's (so it is overdue), link
  `Route("caja:arqueo", {turnoId})`, title "El turno del <fecha> en la caja <codigo> sigue abierto".
- **Today's open turno with payments not delivered** → WARNING `bloqueado:<turnoId>`, audience `Email(turno.cajero)`,
  title "<n> pagos sin entregar impiden cerrar el turno de hoy en la caja <codigo>", same link.

Closing the turno, or the payments being delivered or explained, makes the key disappear and the loop resolves it.

**Tests:** yesterday's open turno → ACTION for its cashier and the supervisor; once closed → resolved at the next run;
today's turno with a `PENDIENTE` payment → WARNING for the cashier only; a turno older than the window → nothing.

- [ ] Tests first; implement; commit `feat(alertas): open turnos and blocked closings`.

### Task 5: `caja.conciliacion`, days that do not balance

**Files:**
- Modify `src/main/kotlin/caja/recaudacion/ConciliacionDelDia.kt`: extract `suspend fun lineas(dia: LocalDate):
  List<LineaDeConciliacion>` (the read in `Transaccion.lectura` and the calls to each origin outside it), used by `de`
  after its permission check and by the source.
- Create `src/main/kotlin/caja/alertas/AlertasDeConciliacion.kt`; tests `AlertasDeConciliacionTest.kt` (pure drafts) and
  cases in `AlertasApiTest.kt` (the fake origin answering a different count).

```kotlin
// la conciliación de los últimos días, como la ve tesorería: cada día que no cuadra es un pendiente con su fecha.
// todos los días de la ventana, no solo ayer: un día que la fuente deja de devolver se da por resuelto
@Component
class AlertasDeConciliacion(
    private val conciliacion: ConciliacionDelDia,
    private val buzon: PropiedadesDelBuzon,
    private val propiedades: PropiedadesDeAlertas
) : NotificationSource {
    override val key = "caja.conciliacion"
    override val interval: Duration = Duration.ofHours(1)

    override suspend fun currentNotifications(
        organizationId: UUID,
        now: Instant
    ): List<NotificationDraft> {
        // sin buzón no hay a quién preguntar: ningún día cuadraría, y la alerta no diría nada
        if (!buzon.habilitado) return emptyList()
        val hoy = LocalDate.ofInstant(now, LIMA)
        return (1L..propiedades.diasConciliacion).mapNotNull { atras ->
            val dia = hoy.minusDays(atras)
            val lineas = conciliacion.lineas(dia)
            if (cuadraElDia(lineas)) {
                null
            } else {
                NotificationDraft(
                    kind = NotificationKind.ACTION,
                    title = "La conciliación del ${dia.legible()} no cuadra",
                    body = recortar(lineas.filterNot { it.cuadra() }.joinToString("\n") { it.resumen() }, 4000),
                    audience = listOf(Audience.Role(TESORERIA)),
                    link = NotificationLink.Route(CONCILIACION, mapOf("fecha" to dia.toString())),
                    key = "conciliacion:$dia",
                    dueAt = inicioDelDia(dia.plusDays(2))
                )
            }
        }
    }
}
```

(`legible()` and `resumen()` are small helpers in `Alertas.kt`: the date as caja prints it, and a line as "rentas:
3 registrados en caja, 2 aplicados" or its `porQueNoSeSabe`.) An origin that does not answer makes its line not
balance: the alert says why, as the screen does.

**Tests:** buzón off → nothing; a day that balances → nothing; a day that does not → one ACTION for `TESORERIA` with
`?fecha=`; the next run, still wrong → no write; fixed at the origin → resolved; two wrong days → two keys.

- [ ] Tests first; implement; commit `feat(alertas): days whose reconciliation does not balance`.

### Task 6: fees about to end, and new fees in force

**Files:** create `src/main/kotlin/caja/alertas/AlertasDeTasas.kt` (the source) and `TasasNuevas.kt` (a
`RecordChangeListener` on `tasa`); tests `AlertasDeTasasTest.kt`, `TasasNuevasApiTest.kt`.

**Ending (a source, not a date rule).** caja renews a fee by adding a row (`clave_vigencia = <codigo>|<vigencia_desde>`);
`tasa` has no `estado` and no "replaced by" field, and a rule's conditions (`EQ`, `EMPTY`, `NOT_EMPTY`) cannot say "no
later row of the same code". A per-fee rule would warn about every fee of an annual TUPA renewal. So source
`caja.tasas` (interval 6 hours) reads the fees whose `vigencia_hasta` falls within `dias-tasas` days (or passed less
than 3 days ago), drops those with a row of the same `codigo` starting the day after, and groups by end date: key
`vencen:<vigencia_hasta>`, WARNING until the day, ACTION from it, due the day after, audience `Role(TESORERIA)`, title
"<n> tasas vencen el <fecha> sin una tarifa que las reemplace", body their codes, link `Route("caja:tasas",
{vigentes_a: <fecha + 1>})`.

**New fees.** Fees enter through core's REST (`import_tasas.py`), so a `RecordChangeListener` sees each `CREATED`
`tasa`. It counts the fees with the same `vigencia_desde` and publishes (source `caja.tasas-nuevas`, key
`nuevas:<vigencia_desde>`) an INFO to `Role(CAJERO)`: "<n> tasas nuevas rigen desde el <fecha>", `publishAt` the start
of that day in Lima (a scheduled one when it is in the future), `expiresAt` 15 days later. The link is
`Url(documento_fuente)` when every new fee of that day names the same `http(s)` URL, else `Route("caja:tasas",
{vigentes_a: <fecha>})`. An import of 200 fees is one notification, updated in place.

**Tests:** a fee with a successor → nothing; three fees ending on 31 December without one → one WARNING "3 tasas";
an import of three fees with one URL → one INFO with that URL, published at their start; a `documento_fuente` that is
not a URL ("Ordenanza 006-2026") → the route link.

- [ ] Tests first; implement; commit `feat(alertas): fees about to end and new fees in force`.

### Task 7: units (optional)

caja's `area` is the business unit a fee is charged to, not a group of people, and caja's audiences are roles. No
`model/org_units.json` in v1. If the owners want to address, say, "Subgerencia de Tesorería › Caja", the file and its
script are srtm's (`2026-10-06-notifications-srtm-adoption.md`, Task 2), copied.

### Task 8: the whole check and the PR

- [ ] `./gradlew ktlintFormat build integrationTest` → PASS; `python3 -m unittest discover model` unchanged.
- [ ] Run locally with the buzón on and the fake origin refusing: the ERROR line and the bell show the same payment.
- [ ] Push; PR to `main`.

---

## Part B — caja-ui

caja-ui's portal has its own shell (`src/portal/shell/AppShell.tsx`, `LateralPortal`, `NAV_TREE` and `PANTALLAS` in
`src/portal/shell/navTree.ts` and `src/portal/pantallas.ts`), so it mounts `@wasichai/notifications`' pieces itself.

### Task 9: the bell, the page and caja's links

**Files:**
- Modify `package.json` (`@wasichai/notifications`), `src/portal/PortalApp.tsx` (`NotificationsProvider` with
  `resolveLink` and `inboxPath="/alertas"`; a route `/alertas` drawing `InboxPage`), `src/portal/shell/AppShell.tsx`
  (`NotificationBell` in the header), `src/portal/shell/navTree.ts` (a leaf "Alertas" in Tesorería's tree, offered to
  every signed-in account), `src/portal/pantallas.ts` (its screen key).
- Create `src/portal/alertas/enlaces.ts` (+ test): `caja:pagos-sin-entregar`, `caja:arqueo` (`turnoId`),
  `caja:conciliacion` (`?fecha=`) and `caja:tasas` (`?vigentes_a=`) to the paths of their leaves; a key whose leaf the
  account is not offered (`arbolPara`) gives no link; the rest delegates to `defaultResolveLink`.

**Tests:** each caja key resolves; a cashier gets no link to the reconciliation (not offered); the bell and the leaf
show; the page says "Alertas".

- [ ] Tests first; implement; `yarn typecheck && yarn test && yarn lint && yarn format`; commit
  `feat(portal): Alertas in the portal`.

### Task 10: `@wasichai/*` 0.6.0-dev.N and the PR

- [ ] Bump `@wasichai/*`; full check; PR to `main`.

## Order

```
caja-backend#28 ── wasichai 0.4.0 ── T1 ──┬─ T2 · T3 · T4 · T5 · T6 (parallel) ── T7 ── T8
wasichai-ui 0.6.0-dev.N ──────────────────┴─ T9 (route keys of Tasks 2–6) ── T10
```
