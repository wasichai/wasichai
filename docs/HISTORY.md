# Change history

Newest first. Architectural reasoning lives in `docs/adr/`; this file records what shipped.

## 2026-10-02 — RecordService joins the caller's transaction, and it is supported

[caja-backend](https://github.com/wasichai/caja-backend) charges payment orders: a turno, a receipt, its lines, the
orders and an outbox event must commit together. `RecordService` already did join a transaction the caller opened,
because every write, the audit row and the listeners' writes go through R2DBC `DatabaseClient` and so onto the
connection the transaction holds, but nothing in wasichai said so or tested it, and ADR-024 calls an implementation
detail unsupported. [ADR-038](adr/0038-record-service-joins-the-callers-transaction.md) makes it supported API: an app
wraps its calls in `TransactionalOperator.executeAndAwait { }`, and a failure at the end, in a listener or in the app's
own code, leaves no record and no audit row. `RecordServiceTransactionTest` in wasichai-core pins it (a failing
listener, an explicit throw, the committing case, the same writes with no transaction, the security context inside the
block). There is no `RecordService.inTransaction { }` helper: it would only wrap Spring's operator, and an abstraction
needs a second user. No behaviour changed; the guide ("Write several records atomically") and the architecture overview
link the ADR.

## 2026-10-02 — Composite unique constraints, and a repeat as a 409

An object can declare `uniqueConstraints: [["sistema_origen", "referencia_externa"]]`, in the shape of `indexes` and
validated by the same `FieldSets` path. The list is stored in metadata (core migration `V4__unique_constraints.sql`)
and each entry is a real `UNIQUE (a, b, …)` on every organization's table, built through
`ObjectSchemaManager` under a derived name (`<table>_uq_<hash>`) when the model is applied, and dropped when the
declaration goes. Adding one that existing records already repeat is a `409` naming `uniqueConstraints`, and the
metadata stays as it was; making a field `unique` over repeated values is a `409` naming `unique`. A field or
relationship that a unique constraint names cannot be deleted (`409`), and toggling a field's `unique` now drops only
its own one-column constraint, never a set that names it. As with `unique`, `NULL`s are distinct. A record write that repeats a unique value,
single-field or composite, now answers `409` problem+json with `errors[]` naming the constraint's fields, where it was
a `500`: the driver names the constraint and the catalog gives its columns, and no value is echoed back. ADR-037
records the decisions and ADR-031 D23 the differences from the original. For Kotlin callers, `ConflictException`
takes an optional list of violations and `GlobalExceptionHandler` an optional resolver of a violation's fields; core
compiles against the r2dbc PostgreSQL driver, which the starter already brings at runtime. Tested by `FieldSetsTest`,
`ObjectSchemaManagerTest`, `CompositeUniqueApiTest` (second organization, update, existing repeats, delete guards),
`FieldApiTest` (core and parity) and `SchemaParityTest`. Closes #14.

## 2026-10-02 — Declared indexes, lists without a count, keyset reads

A field can be `indexed: true` and an object can list composite `indexes: [["anio", "predio"]]`. Every `RELATION`
column is indexed whether declared or not, because PostgreSQL does not index a foreign key. The indexes are stored in
metadata (core migration `V3__declared_indexes.sql`) and built through `ObjectSchemaManager` on every organization's
table as its model is applied, under names derived from the column list (`<table>_ix_<hash>`). They are dropped when
the declaration goes. Applying the same metadata twice changes nothing. `DeclaredIndexReconciler` runs at startup and
builds what the metadata declares and the catalog lacks, which is how tables from before this change get their
relation indexes (`wasichai.metadata.reconcile-indexes=false` switches it off).

A record list takes `?count=false` (`RecordQuery.count`) to skip the `COUNT(*)`, and its `totalElements` and
`totalPages` are then `null`. Every page now carries `nextCursor` when another row follows, and `?after=<cursor>`
(`RecordQuery.after`) resumes strictly after that row's `(sort value, id)`, the pair #20 made unique. Reading a large
set therefore costs one pass, and every row comes back once, tied ones included. `after` with `page > 0`, or a cursor
from another sort, or a cursor whose value does not cast back to the key's type, is a `400`. An object or a field that
declares no index reads as before, but a default page now carries `nextCursor` whenever another row follows. `count`
and `after` are reserved: they no longer filter a field of that name, and are refused as new field names. A
relationship whose column a composite index names cannot be deleted (`409`). ADR-036 records the decisions and ADR-031
D22 the differences from the original. For Kotlin callers, `CustomObjectRepository` takes a `JsonMapper` as a third
constructor parameter, and `PageResponse.totalElements` and `totalPages` are now nullable (`Long?`, `Int?`). Tested by
`FieldSetsTest`, `ObjectSchemaManagerTest`, `PhysicalTableRecordStoreTest`, `RecordQueryParserTest`,
`PageResponseTest`, `RecordServiceTest`, `DeclaredIndexApiTest` (second organization, `EXPLAIN`, reconciliation) and
`RecordKeysetApiTest`. Closes #21.

## 2026-10-02 — Record lists page in a stable order

A record list ordered by one key only, so rows tied on it (a receipt and its lines share `created_at`) could repeat or
vanish between pages: PostgreSQL does not promise a consistent subset for `LIMIT/OFFSET` over a non-unique `ORDER BY`.
`PhysicalTableRecordStore` now ends every `ORDER BY` with `id`, in the direction of the primary sort (sorting by `id`
adds nothing), and the audit list ends with `a.id DESC`. `RelatedRecordService` pages through the same store, so it is
covered; the admin, metadata and automation lists are not paged. ADR-031 D21 records the difference. Tested by
`PhysicalTableRecordStoreTest` and, with more tied rows than a page, `RecordApiTest`. Closes #20.

## 2026-09-29 — Shared primitives go up to wasichai-ui, and a dev line to release them

Five components srtm-ui wrote for its portal move into wasichai-ui, so caja-ui does not write them again: `ConfirmDialog`,
`Pagination` (renders `data-slot="pagination" data-mode="pages"`, shows "Página x de y" / the
record count), `PageSizePagination` (renders `data-slot="pagination" data-mode="range"`, a range
footer "a–b de n" with a rows picker, for any paging where the caller knows the total) and `PdfDialog` in
`@wasichai/ui`,
and `QueryState` with `LoadingState`, `EmptyState` and `ErrorState` in `@wasichai/core`. The reason is wasichai-ui's rule 6:
an abstraction needs a concrete second user, and caja-ui, being rewritten on wasichai-ui the way srtm-ui is, is the
second user of all five. Markup and behaviour are srtm-ui's; what changes is that they are domain-free. The words moved
to core's `common` bundle (`es` and `en`; `@wasichai/ui` still imports nothing from core), Spanish plurals carry a
`_many` twin because i18next resolves `many` for multiples of 1,000,000, and counts go through i18next's `number` format
(`{{count, number}}`) so an app can swap the formatter. Each carries a `data-slot` hook for themes (`pagination` with `data-mode` `pages` or `range`,
`native-select`, `confirm-dialog`, `pdf-dialog`, `query-state` with `data-state`).
`DataTable` does not change.

The primitives ride a new `dev` line in wasichai-ui: a branch that is a clone of `main` and never merges back, so
nothing of this work reaches `main` before the design is settled. It publishes pre-releases `X.Y.0-dev.N`, the next
minor of the release-please manifest plus a counter (`0.4.0-dev.N` while wasichai-ui is at 0.3.x), under the npm
dist-tag `dev`, so `latest` stays where release-please left it. The upload script reads each package's `latest` before and
after publishing: if it moved, the run fails, and `latest` is put back when the package had an earlier one; on a package's
first publish there is none to restore, so the run fails saying so and `latest` stays until the next release.
A pre-release is published by pushing a tag `vX.Y.0-dev.N` on a `dev` commit (`git fetch --tags && node tooling/dev-release.mjs next`
prints the version; the tag is a `v` before it), not by `workflow_dispatch`: that trigger needs the workflow file on the
default branch, which this line never touches. The workflow refuses a commit that is not on `origin/dev`, uploads every
public package, skipping versions already up so a re-run of the failed jobs finishes a partial upload, and then creates the
GitHub prerelease. The first, `0.4.0-dev.0`, is cut once both wasichai-ui branches are merged into `dev`; srtm-ui adopts it.
Design and plans:
[the spec](superpowers/specs/2026-09-29-metadata-ui-design.md),
[wasichai-ui phase 1](superpowers/plans/2026-09-29-metadata-ui-f1-wasichai-ui.md) and
[srtm-ui phase 1](superpowers/plans/2026-09-29-metadata-ui-f1-srtm-ui.md).

## 2026-09-28 — Success text passes AA in light

Light `success` goes from `oklch(58% 0.13 155)` to `oklch(52% 0.13 155)` with wasichai-ui's next release, so success
text, badges and icons reach WCAG AA: 5.0:1 on `surface` and 4.6:1 on `success-soft`, the pair ADR-035 left as a
tested exception. Every green in light is a little darker; dark is unchanged. ADR-031 D20 records it.

## 2026-09-27 — Themes get extension tokens, data-slot hooks and an optional portal-tributario sheet

srtm-ui's `portal-tributario` theme, the look of an online municipal tax portal that it built on ADR-034 without
forking `@wasichai/ui`, moves into the library with wasichai-ui's next release (cut by release-please). `theme.css`
gains ten extension tokens, set in light and in dark: alert backgrounds and a fourth alert tone (`success-soft`,
`danger-soft`, `notice`, `notice-soft`), `link`, `focus`, `table-head`, `table-stripe`, `line` and `map-selected`,
so a theme now sets 28 tokens; an app theme written before inherits light's values for the new ten until it sets
them. `--radius` becomes a theme variable, so `rounded` follows a theme as `--font-sans` and the other radii do.
`Button`, `Input`, `Textarea`, `SelectTrigger`, `Table`, `Th`, `Td`, `Badge` and `Tabs` carry `data-slot` hooks with
shadcn's names (`Button` also `data-variant` and `data-size`), a styling contract that light and dark leave alone.
The theme is optional: an app imports `@wasichai/ui/themes/portal-tributario.css` after `theme.css` and lists
`PORTAL_TRIBUTARIO_THEME` from `@wasichai/core` in `config.themes`. The sheet styles only the library's own
components; the brand bar, the tree menu, the chevron steps and the title band stay in srtm-ui. The `index.html`
boot script now maps every theme id the app offers to its color scheme. In light and dark the one visible change
is a soft red behind `@wasichai/documents`' error messages, whose `bg-danger-soft` generated nothing until now
(ADR-031 D19). Light success text on its soft background stays below AA, a tested exception left to a follow-up.
ADR-035 records the decision and amends ADR-034.

## 2026-09-26 — wasichai-ui v0.1.0 released

The first release of the npm packages is out: release-please tagged `v0.1.0` in wasichai-ui and its `publish.yml`
pushed the eleven public `@wasichai/*` packages to `npm.pkg.github.com`. Its `"release-as"` pin is gone too.

## 2026-09-26 — v0.1.0 released

The first release of the Maven libraries is out: release-please tagged `v0.1.0` and `publish.yml` pushed the
libraries to GitHub Packages. The `"release-as": "0.1.0"` pin is gone, so the next version follows the commits.
`RELEASE_PLEASE_TOKEN` is an organization secret; the organization caps fine-grained PATs at 366 days.

## 2026-09-26 — Samples and local infrastructure move to their own repositories

Each sample is now a repository of its own in the wasichai organization, holding its server and its web together:
simple-sample, documents-sample, gis-sample (with the Perené model) and full-sample (with the Playwright e2e, now
against a server of the same repository, and the route-parity snapshot). They build against the published
libraries by default and pick up sibling wasichai and wasichai-ui checkouts on their own (a Gradle composite build,
`yarn link:local`). The docker compose setup moved to wasichai-infrastructure. wasichai states what an app needs
(PostgreSQL 18, PostGIS 3.6 for wasichai-gis, GeoServer optional) and assumes nothing about how it runs: GeoServer's
datastore host now defaults to `localhost` (ADR-031 D17). The `wasichai.sample-app` convention plugin is gone,
wasichai-ui is packages and tooling only, and `WASICHAI_REPO_TOKEN` is no longer needed. ADR-033 records the
decision. wasichai's integration run is 394 tests; the 16 sample smoke tests run in the sample repositories.

## 2026-09-26 — Chawpi becomes wasichai, in two repositories

The platform is renamed wasichai and lives in the `wasichai` GitHub organization: the Gradle libraries, the sample
servers and every doc in `wasichai/wasichai`, the npm packages and the sample webs in `wasichai/wasichai-ui`. Kotlin
packages, types, Maven coordinates, configuration keys, environment variables, the npm scope (`@wasichai/*`, equal to
the organization, as GitHub Packages requires), the React API (`WasichaiApp`, `WasichaiModule`, …) and the defaults
(schema, database, seed user, issuer) all say wasichai; tables, routes and behaviour are unchanged. Each repository
now has its own CI, its own release-please and its own version, both starting at 0.1.0, and the full-sample e2e runs
the web against a server built from the backend repository. ADR-032 records the decision and amends ADR-029; entries
below this one keep the old names.

## 2026-09-25 — The npm packages are published under `@hneyra`

GitHub Packages only accepts an npm package whose scope matches the repository owner, and the `chawpi` organization
name is taken, so the frontend packages moved from `@chawpi/*` to `@hneyra/*`: `@hneyra/ui`, `@hneyra/core`, one
package per module and `@hneyra/testing`. Every import, alias, Tailwind `@source` path, sample web, release script,
the `.npmrc` line and the publish workflow follow. The product, the Maven group `chawpi`, the Kotlin packages and
the `chawpi-*` artifacts are unchanged. Earlier entries, plans and ADR text keep the old scope; ADR-029 has an
addendum.

## 2026-09-25 — The libraries are documented, guarded and ready to publish

Every module now has a page saying what it adds, how to switch it on and off, which properties and routes it owns
and what happens without it, and a guide walks from a core-only app to one with every module. The decisions the
split took along the way are ADRs: libraries and starters (ADR-024), the frontend registry (ADR-028), one repository
and one version (ADR-029), the rename (ADR-030), and the short list of places where chawpi deliberately behaves
differently from the original (ADR-031). Everything else behaves as it did.

CI now builds and tests everything on every pull request: build-logic, every library, the integration tests on
real containers, every frontend package and sample web, the Perené model, and a headless Playwright smoke of the
full sample. Before anything is published, two guards check that exactly the twenty Maven artifacts and eleven npm
packages would go out, and that each npm package points at its own release. tiptap is pinned, so a frozen document
cannot change under a new editor version.

Library classes keep their `@Service` and `@Component` annotations although nothing component-scans them: the
kotlin-spring plugin opens only annotated classes, and without it a `@Transactional` service is final and its proxy
fails at start-up (ADR-024). The cleanup that planned to remove them was dropped. The last check ran everything
again: 94 core and 300 library integration tests in 22 suites, 16 across the four sample servers, the full-sample
smoke in a browser, and an app outside the repository compiled against the locally published starters and
type-checked against the packed npm packages. compose's PostGIS host port is now `CHAWPI_PG_PORT` (default 5432).

**Open items.** A handful of points from earlier phase ledgers stay open rather than fixed. The outside-the-repo
consumer check still runs by hand, not from a script in `publish.yml` or elsewhere, so BOM resolution and a compile
against the published artifacts are not reproduced in CI. Schema parity is proved on P3's full test app, not on
full-sample; full-sample applies the same starters and the same migrations, so the proof carries over. The
full-sample Playwright smoke leaves its `e2e…` object, workflow and document type in whatever database it runs
against. The absent-module probes in simple-sample and gis-sample cover only some routes, not the full surface. The
`chawpi-test-wipe` advisory lock in `ChawpiTestDatabase.kt` is redundant now that a suite lock already serializes the
two suites sharing one external database. AdminService stays unsplit at 550 lines — a ruling recorded only in the P7
plan until this entry. MULTI* geometry still cannot be saved from the UI; that stays backlog. And two paragraphs up,
"the integration tests on real containers" and the full-sample smoke describe CI jobs that are written and reviewed
but have not yet run on GitHub Actions: their first green run there, not this entry, is the proof.

## 2026-09-25 — Four sample apps

`examples/` holds four runnable apps, each a Spring Boot server built only from the starters and the BOM and a Vite
web built only from the `@chawpi/*` packages it needs. `simple-sample` is core alone on plain PostgreSQL, and proves
an app needs neither PostGIS nor any module. `documents-sample` adds documents and automation, which meet only
through automation's optional `DocumentIssuer` port. `gis-sample` is core and gis on PostGIS, with `perene/`, a real
cadastre model of 13 objects, 11 of them spatial, in EPSG:32718. `full-sample` has every module: it is the original
app assembled from the libraries, and all 95 of the original's routes answer on it, no more and no fewer. A headless
Playwright smoke logs in, draws a record on the map, issues a document and applies a transition. Samples are never
published, and each uses its own database.

## 2026-09-25 — The original's API tests run against the libraries

All twenty of the original app's API integration tests now run against apps assembled from the chawpi starters: a
full app with every module, and a core-only app on plain PostgreSQL that proves the geometry routes are simply
absent. Each module is also booted alone next to its optional neighbours' absence, and the final schema is compared
table by table, column by column and constraint by constraint with the original's.

22 suites, 94 core and 300 library integration tests, all green. The schema comparison covers 464 facts (19 tables,
167 columns, 225 constraints and 53 indexes) and finds no difference in either direction. Two suites pointed at one
external database now take turns through a suite lock instead of wiping each other's data.

## 2026-09-25 — The frontend modules become packages

The screens of every optional module left `@chawpi/core` for a package of their own: `@chawpi/views`, `forms`,
`pages`, `workflow`, `automation`, `documents`, `gis` and `agent`. Each registers its routes, sidebar entries,
renderers and strings with the registry, and 224 translation keys moved out of core into the module that uses them
(ADR-028). MapLibre, xyflow, tiptap and dnd-kit now load only when someone opens the screen that needs them -- the
builder pages that pull them in are reachable only as lazy routes. Issuing a document now refreshes the record's
history at once, which the original never did (ADR-031 D15). 669 frontend tests pass.

## 2026-09-25 — The frontend becomes a set of packages

`@chawpi/ui`, `@chawpi/core` and `@chawpi/testing` replace the one Vite app. An app renders `<ChawpiApp>` with a
config and a list of modules, and a registry merges what the modules contribute, refusing a clash at start-up
rather than drawing a broken screen (ADR-028). No URL is written by hand any more: links come from the registry,
the API base and storage prefix are configuration, and the history no longer imports documents to draw an issue.
A `401` in the middle of a session now signs the user out instead of leaving a signed-in-looking page whose every
call fails (ADR-031 D11). 284 tests pass.

## 2026-09-25 — A link answers to the same rules as the record

The one deliberate behaviour change against the original in the library split. Linking or unlinking two
records now checks both the way the record api does -- same organization, and only your own under
`own_records_only` -- and answers `404` otherwise instead of quietly succeeding. Each link or unlink
shows up as an `UPDATE` in both records' histories. The history's "after" is now the record as stored,
so a field the editor could not write no longer looks cleared. ADR-0025 records both.

## 2026-09-25 — Every module becomes a library

Views, forms, pages, workflow, automation, documents, gis and agent each moved into a library of their own, with
their own auto-configuration, `chawpi.<module>.enabled` switch and Flyway migrations. Each has a thin Spring Boot
starter, and `chawpi-bom` lines up all nineteen published artifacts (ADR-024). None of them is known to the core:
they plug in through the SPIs of ADR-025, so an app that leaves gis out runs on plain PostgreSQL and its geometry
routes simply do not exist (ADR-027). The GEOMETRY field type, the bbox query and the MAP page component all arrive
from chawpi-gis now, and the resulting `custom_fields` table is column for column and CHECK for CHECK the original's.

The agent library no longer picks a model provider; its starter does, and it picks the original's (ADR-031 D7).
265 unit tests pass across the modules, and every one of the original's 49 module routes answers at the same URL.

## 2026-09-25 — The core becomes a library

`chawpi-core` now stands on its own: identity, metadata, dynamic data, audit and admin, wired by
Spring Boot auto-configuration instead of component scanning, and extended through SPIs a module
declares beans against rather than one the core imports by name (ADR-025). Migrations run one Flyway
per module, each with its own history table, so a module's tables appear only when the module does
(ADR-026). Geometry is the first thing meant to arrive through that door -- sketched, not built yet
(ADR-027).

The core now runs end to end: 85 API integration tests against plain PostgreSQL 18 with no PostGIS,
including a test-only field type that proves the SPI.

## 2026-09-25 — Chawpi starts from sapgis

The whole of sapgis moves here to become a set of libraries: a Spring Boot starter per module and an
npm package per frontend module, so an app gets the platform by adding dependencies instead of
forking it. Nothing changes in behaviour. Entries below this one are sapgis history, kept as written.

## 2026-09-22 — A document prints

An issued document had exactly one way out of the app: a dialog. Now `/documents/:id/print` opens
it as its own page -- no sidebar, an A4 sheet, a button that calls `window.print()`. That is the
whole feature. The browser turns the sheet into a PDF; nothing in sapgis draws one, because the
only other renderer that could is `DocumentView`, and a second one in Kotlin would drift from it
the first time someone changed a mark or a node type.

An archived document prints a banner that says so, in a background color forced through with
`print-color-adjust: exact` -- browsers drop backgrounds on print by default, and a document
handed out as evidence must never look valid on paper because a checkbox in a print dialog was
off. The route sits behind the same auth check as the rest of the app, pulled into a small
`AuthGate` so the print page (no sidebar) and every other authed route (sidebar) share one
`useAuth()` + redirect instead of two.

## 2026-09-21 — The automation queue stops dying of its own timer

Issuing a document on reaching a state worked in tests and never once worked in the running
application. The reason was older than documents and had nothing to do with them: `AutomationDrain`
polled with `Flux.interval`, which has no backpressure. After about thirty-six ticks the source
gave up -- *"interval doesn't support small downstream requests that replenish slower than the
ticks"* -- and because `.subscribe()` carried no error handler, the overflow went to Reactor's
`onErrorDropped`, where nobody reads it.

Dying was the cheap part. Dying **cancelled the batch in flight**: the run it had already claimed
was left `RUNNING`, its own attempt to write `FAILED` cancelled with it, and since `concatMap` runs
one batch at a time the tenant's whole queue stayed dead until the next restart -- which bought
exactly one more batch. That is why a control `UPDATE_FIELD` automation hung identically to a
`GENERATE_DOCUMENT` one: the action was never the problem.

The integration tests never saw it because they call `drainOnce` directly from `runBlocking`. Only
the production wiring -- a timer feeding `concatMap` -- has a source that can overflow, so the seam
that was broken was the one nothing drove.

`onBackpressureDrop` is the fix: a dropped tick costs nothing, because the next one looks at the
same queue. The loop is now a small `internal` function so a test can drive it with a body too slow
to keep up -- one-millisecond ticks against a ten-millisecond batch reproduces sixty seconds of the
mismatch per second, and without the fix it fails on the real `OverflowException`.

**Still open**: nothing reclaims a run orphaned in `RUNNING` by a crash or a deploy. The queue no
longer orphans them on its own, but a killed process still can.

## 2026-09-20 — A state issues a document, and the history says so

Reaching a workflow state now issues a document. That needed no new machinery: `STATE_ENTERED` was
already a first-class automation trigger, so this is one more `ActionType` --
`GENERATE_DOCUMENT` with a `documentType` -- on a queue that already ran, retried nothing, and
recorded every run. The type is checked against the object when the rule is saved, not when it
fires, and nobody is named as the issuer: a queued run has no user behind it (ADR-016), so the
document says the platform issued it.

Automation reaches documents through a **port**, `Documents`, implemented by an adapter on the
documents side -- the same shape as `data/WorkflowStates`. The first cut of this reached straight
into `DocumentTypeRepository`, which CLAUDE.md forbids outright, and it came from the brief rather
than from the implementer.

Every issue is now a history entry that links to the document. The audit log predates documents --
three operations, a CHECK in SQL naming them, and no entry carrying a link of any kind -- so this
widened that contract end to end: a fourth operation `ISSUE`, a nullable `document_id` with
`ON DELETE SET NULL` because an audit entry is a fact and must not vanish when what it names does,
and a body in the timeline, which until now was drawn only for `UPDATE`.

## 2026-09-20 — A document is frozen when it is issued

An admin writes a document template once -- free text with the object's fields dropped into the
running prose, related records as tables, and platform values like the date -- and a record issues
it. Each issue takes a correlative, `SGTM-2026-001`, and freezes a copy: the values, the resolved
platform values, the related rows, **and the template as it was**. Editing a type never rewrites
what it already issued, which is the whole difference between a copy and a reference.

The template is the editor's node tree, not HTML. Nothing here renders HTML it was handed and there
is no sanitiser among the dependencies; the client walks the tree to React elements and the server
only reads it to check what it names. Tiptap is the one new dependency, and its document model *is*
the stored format.

The sigla is unique per organization rather than per object, because the counter runs per type: two
types sharing one would each issue their own `SGTM-2026-001` and the number would stop naming a
document. The counter is a table, not a sequence -- a sequence does not roll back, so a failed issue
would leave a hole in a correlative someone has to explain. "The last one prevails" is a partial
unique index, not a rule in a service.

Two defects came out of one concurrency test, neither of which a serial test could have seen.
`issueAsUser` called `issue` on itself, so Spring's proxy never ran and there was no transaction at
all: the advisory lock was taken and let go in the same breath and the counter handed numbers to
work that then failed. And ten issues of one type on one record fought over the one-valid index, so
a double-click on *Issue* was an error rather than what the admin meant. Ten at once now give
001..010, nine archived and one valid.

Field permissions do not apply to an issued document: whoever may see it sees all of it. That
departs from every other read in this platform and is written down in ADR-023, not in a comment.

## 2026-09-19 — A form built by dragging the object's own fields

`FORM` was already a dynamic form: its `fields` list names the object's columns and the renderer
draws them. What was missing was a way to build that list by dragging — the names were typed
comma-separated into an input. And a typed name cannot be selected, so it cannot carry properties
of its own, which is what settled the design: a placed field had to be a node.

`DYNAMIC_FORM` and `FIELD` join the catalogue as a pair with the same shape as `TABS`/`TAB` — one
holds only the other, and the other lives nowhere else, refused on both sides of the wire. A `FIELD`
carries `field`, `visible` and `editable`, the last two nullable so that null means "whatever the
object says": a field that later turns read-only turns read-only on every page that never had an
opinion. A placement may take away what the object grants and never add to it, so `editable: true`
on a read-only field is refused and the inspector does not offer it at all. The same field twice on
one form is refused; two forms on one page may each hold it, because they are two forms.

The palette stacks its items one per row and splits in two tabs: the component types, and the
object's own fields, geometry included. A field drags as `field:<name>` — its own branch in
`applyDrop` and in the drag label, because anything that is not `palette:` falls through to the move
branch, where it would evaporate without a word.

One trap worth recording: `firstForm` returned only on `FORM`, so a page whose only form was dynamic
would have had no submission owner — every form read-only, no save button anywhere, with the suite
green because nothing asked. It now recognises both.

## 2026-09-19 — The canvas draws tabs, and a region can split in two

The builder showed a `TABS` node as a box with every tab's contents stacked at once, so an admin
edited a shape the record page never produces. The canvas now draws a real strip, one tab open at a
time, forked from `components/ui/tabs.tsx` rather than reusing it — that one owns its open tab in
private state and hover has to drive this one from outside. A closed tab genuinely has no drop
zones, which is the mechanism, not a side effect: dnd-kit only knows droppables that are mounted.

Hover-to-open is wired but **does not work yet**, and the reason is measured rather than argued. A
tab title registers a `tab:` droppable and the hover decision is a pure function (`openTabs.ts`)
because jsdom cannot drag — but in a browser the pointer resting on a title never resolves to it.
Dumping dnd-kit's own collision inputs mid-drag shows why: a title's measured rect has the right
`left` and `right` and a `top` offset by about 400px, the builder's scroll container. So
`pointerWithin`, which tests viewport pointer coordinates against those rects, can never match one;
`rectIntersection` survives the same offset only because both sides carry it and it cancels. Two
attempts at a collision rule and a forced `MeasuringStrategy.Always` all failed against this, and
each was reverted rather than shipped. Aligning the two coordinate spaces is the actual fix and is
not yet written. Until it is, a component reaches a closed tab by clicking the tab first.

`REGION.layout` had been end to end since the template work — the type carried it, the server kept
it, the payload saved it and the renderer drew it — but nothing in the editor could set it and the
canvas drew one flat column, so a two-column region would have rendered in two and edited in one. A
region is now selectable, and only that: still never draggable, never deletable. Reversing
"regions are not selectable" reopened a hole that decision had closed, so there are two defences —
the inspector hides the bin, the title and the region's own column, and `removeNode` refuses `PAGE`
and `REGION` however the ask arrives.

`main-and-left-sidebar` joins the catalogue beside its right-hand twin. Every sentence and
assertion that pinned the catalogue at nine is now count-free rather than bumped to ten.

## 2026-09-19 — A page has a template

A page's tree stayed free at every level, so a blank detail page suggested nothing: no starting
point, no shared vocabulary between objects. `PageDefinition` now holds one root, `page`, always a
`PAGE` node holding one `REGION` child per region its `template` declares — a nine-entry catalogue
(`PageTemplate.kt`), code, not a table, on a 12-column grid, with a shared region vocabulary
(`HEADER`, `MAIN`, `LEFT`, `CENTER`, `RIGHT`) so `MAIN` never needs a new home when the template
changes. The scaffold is top-level only: inside a region the tree is exactly the free one ADR-021
built. The server validates that a page's regions equal its template's, in order — no drop, drag or
handwritten payload can add, remove, rename or reorder one (ADR-022, which amends ADR-021 rather than
editing it). `MAX_DEPTH` rose from 10 to 12 so the free part of the tree keeps its original ten. The
`layout` column leaves `sapgis.pages`; the template's spans replace it. `GET
/api/metadata/page-templates` publishes the catalogue.

Changing template is a client-side operation: the builder relocates a dying region's children and
sends an already-valid tree, so the server never learns what an orphan is and stays a pure validator.
That is a known trade, not an oversight — a client that discarded a dying region's children instead
of moving them would produce a payload the server accepts just as happily; there is no server-side
check that can catch it, by design (ADR-022).

`V11__a_page_has_a_template.sql` **deletes every stored page and regenerates from the derived
default**; it does not migrate them. `sapgis.forms` and `sapgis.views` are untouched by this
migration. One loss is worth naming on its own: a `FORM` component that pointed at a saved, named
form loses that link when its page is regenerated. The form row survives — nothing here touches
`sapgis.forms` — but no page points at it until an administrator opens the new page and picks it
again.

## 2026-09-19 — The page tree gets a canvas

The page builder used to be a flat, per-row form: one row per component, no way to see or build the
tree ADR-021 gave the model. It is a drag-and-drop canvas now — a palette of component types, a
canvas that renders the tree, and an inspector for the selected node — built on `pageTree.ts`, the
tree operations (`insert`, `remove`, `move`, `canDrop`, `accepts`, `depthOf`, `countOf`) extracted
pure and tested with no React in them. Its bounds mirror the server's exactly (depth 10, 200
components), so the canvas refuses at the cursor what the server would refuse at the request. Each
leaf draws from `ComponentMock`, a single switch over seven types that renders from metadata the
builder already has loaded — the object's real field labels, the real relationship label — with no
fetch, no map mount and no interactive control, because a mock lives inside a draggable node and a
real control would swallow the drag. `PageBuilderPage` is rebuilt on the three panes; the old
form-based editor, and the container-safety scaffolding a stop-gap had put around it, are gone.

- **A drop zone's id carries its column, not just a position**: `slot:<path>:<column>`. A path alone
  cannot tell a two-column container's left slot from its right one, and without the column encoded
  in the id, every drop or move kept whatever column the node already had — the entire point of a
  per-container layout, unusable.
- **The `DndContext` spans the palette and the canvas, not just the canvas.** It briefly lived inside
  Canvas, which left every palette item registering as draggable against dnd-kit's default context —
  real aria attributes, no sensors behind them. Nothing could be dragged out of the palette, and a
  keyboard user was told to press space and nothing happened.
- **`PointerSensor` carries `activationConstraint: { distance: 8 }`.** Without it, dnd-kit counted
  every `pointerdown` as a completed drag and swallowed the click that followed, so a canvas node
  could never be selected by mouse.
- **The editor refuses nothing it used to allow, and gains what the flat editor never could**:
  containers, nesting, per-container columns, and the `ACTION` component.

## 2026-09-19 — A page's components become a tree

A page's components were a flat list, tabbed by giving matching ones the same `tab` string and
grouping them at render time. That could not say "a section of three fields, above a map, inside the
second of four tabs" — there was no container to place. Components now form a free tree: `TABS`,
`TAB` and `SECTION` hold children, to any depth, and each container owns its own column layout
(ADR-021). `pageTabs.ts`, which grouped by equal strings, is deleted — a `TABS` node is explicit now.
`V10__a_page_is_a_tree.sql` migrates every stored page once; there is no reading the old shape
afterward.

- **Two defects, both pre-existing, fixed because this work touches exactly the code that carries
  them.** `PageService.toRequest()` mapped seven of the page component's eight fields and silently
  dropped `tab`, so a `PUT /api/pages/{name}` that omitted `definition` — a plain rename — revalidated
  the stored page through it and untabbed the whole thing. And `MAP.geometry`, which `PageRenderer`
  had always read and the frontend type had always declared, did not exist on the Kotlin
  `PageComponent`: it was dropped on write and absent on read, so a `MAP` has always drawn every
  geometry an object has, never the one it was told to.
- **A page with two `FORM` components used to render two "Guardar" buttons, one of which silently did
  nothing.** Only the first form in document order was ever wired to save. Now only that first form
  renders a save button at all; every other one renders read-only. `DynamicForm` gained an optional
  `readOnly` prop for this.
- **A new `ACTION` component** fires a named workflow transition or navigates to an object's record
  list or an external url. Validating a transition needs the object's workflow, so the `WorkflowStates`
  port gains `transitionNames`; pages check against it, the record path still never asks.
- **V10 changes how one kind of page looks.** A page where some components carried a `tab` and others
  did not used to render the untabbed ones as a tab of their own, labelled "Página", wherever the
  first untabbed component happened to appear. They now sit at the page root, above the strip — the
  reading a tree makes available and the flat model could not express. A page where every component
  was tabbed, or none was, migrates with no visible change.
- Guard rails, not design limits: a `definition` nests at most 10 deep and holds at most 200
  components. Neither is reachable by dragging; both exist for a page typed or generated by hand.

## 2026-09-18 — The caller can ask what they may do

`GET /api/auth/me/permissions` lists, for every object the caller may read, the record actions they
hold on it (ADR-020). GIS-XP, the end-user runtime, uses it to hide the create, edit and delete
actions a person would only be refused. It reuses the same permission queries the services check,
and grants nothing by itself: every write is still checked where it happens.

## 2026-09-19 — A record's detail page comes in tabs

Everything a record had to say was stacked on one page: the form, the map, every related list, the
workflow panel and the whole audit trail, all mounted and all fetching at once.

- A page component may name a `tab`. Components sharing one are grouped behind a strip, in the order
  the tabs first appear. **A page where nobody named a tab renders exactly as it did**, so the pages
  stored before this are untouched — and the renderer's existing tests passed unchanged.
- The generated page now comes in four: Detalles (the form, and the workflow panel, because acting on
  a record's state is something you do while looking at it), Mapa when the object has geometry,
  Relacionados, and Historial. It is single-column now: the strip is the layout.
- **A tab nobody opened does not exist.** The related list, the history and the workflow panel each
  fetch when they mount, so an unopened tab must not mount — a detail page now asks for four things
  instead of six, and only fetches a tab's contents when you open it.
- **A tab you opened stays.** It is hidden, not thrown away, so what you typed into the form is still
  there when you come back. The map keeps its canvas across that, which is why `MapView` grew a
  `ResizeObserver` — it was trusting a size it was only given once.
- Tabs are keys on the generated page (`DETAILS`, `MAP`, …) because the backend has no language; the
  client translates those and prints anything an administrator typed verbatim.
- The page builder gained a tab field per component, offering the ones the page already uses.

## 2026-09-18 — The leftovers of the geometry change

Two things ADR-019 left behind, both found by looking at the running system rather than the code.

- **A layer published before ADR-019 was invisible to the application.** It is named after the table
  alone, so nothing matched it, and it sat in GeoServer serving a table that now has two geometry
  columns — with GeoServer choosing between them on its own. One such layer is now reported as the
  object's first geometry being published, and publishing that geometry retires it, so republishing
  normalises instead of leaving two layers for one thing. The naming rule moved to `GeoServerLayers`,
  where it can be unit-tested; GeoServer itself stays out of the suite.
- **The WMS preview never worked in the browser.** The layers screen fetches tiles straight from
  GeoServer, which is a different origin than the dev server, and the compose image had CORS off.
  `CORS_ENABLED` is on now, for local development only.

## 2026-09-18 — The platform's columns fold away, and relationships show up where they are used

The object editor showed nine permanent rows of system fields in a table about the object's own
fields, and said nothing at all about the relationships the object takes part in — those lived on a
flat, organization-wide page.

- The system fields fold into one row at the top of the fields table, closed by default, with the
  count on the trigger and `aria-expanded`/`aria-controls` wired to the rows they open. The hint
  moved inside, because it explains what was just opened.
- A «Relationships» card lists what the object takes part in, from either side, saying which end you
  are standing on and whether the column that backs it is here or on the other object.
- **A relationship's labels can be edited at last.** `PUT /api/relationships/{name}` is new: nothing
  about a relationship was editable before, at any layer — no route, no service method, no repository
  update, not even an `updated_at`. The label and its inverse are what each side of a record page
  reads, so being unable to fix a typo meant deleting the relationship and its column.
- Reshaping one — type, source or target — is refused with `400` and the reason: the column or join
  table would have to move and the stored links cannot follow.
- A relationship can be created from the object editor, with that object as the source, and the form
  says where the column will land.
- The column a relationship owns is marked in the fields table and its delete button is disabled. The
  server already refused it with a `409`; offering the button was the lie.

## 2026-09-18 — A geometry is a field, so an object can have more than one

Geometry was a property of the object, materialised as one column called `geom`. A parcel could not
carry its plot and its access point at once. The database had been ready the whole time — the check
on `custom_fields.type` already admitted `GEOMETRY` — and two Kotlin refusals were what stood in the
way (ADR-019).

- A geometry is now a Custom Field of type `GEOMETRY` with its own shape, CRS and dimension, stored
  on the field row the way enum options and relation targets already were. `custom_objects` loses its
  three geometry columns, so there is one place to read what a record carries.
- `V9` moves metadata and no data: the column was already called `geom`, so it becomes a field
  pointing at it. It records `dimension` as 2 whatever the object claimed, because 2 is what the
  column is; from here on the declared dimension is honoured and a 3D field gets `PointZ`.
- Every geometry field gets its own typed column and its own GIST index. `SqlIdentifier.indexName`
  truncates past Postgres's 63 characters and ends on a hash, because a silent truncation collides.
- A geometry can now be added to an object that already exists, which was impossible before.
- Records carry `geometries`, a map beside `attributes`. One left out of the map is left alone, one
  sent as `null` is cleared, and reading lists them all so a missing key never means two things.
- `?geometry=` names the geometry a `bbox` filters and the one a FeatureCollection carries. A `bbox`
  on a flat object is now a `400`; it used to be dropped in silence and answered `200`.
- The record form draws each geometry where the form's author put it, so a geometry can finally live
  inside a section instead of always trailing the form.
- A layer is one geometry of one object, `<table>__<column>`, and the GeoServer feature type is a JDBC
  virtual table that names the column — with two, GeoServer used to pick one in silence. Layers
  published before this keep the old name and should be republished.
- Field-level permissions now cover a geometry, because it is a field: one a role may not write is
  refused and one it may not read does not come back. Geometry had no field security at all before.
- `geom` stops being a reserved system column: it is a name the user picks now.

## 2026-09-18 — The platform's own columns, on screen instead of behind a 400

The field editor listed only the fields the administrator had created, so a record appeared to carry
two columns when it carried eight, and the nine names the platform reserves announced themselves only
as a `400` after pressing Create.

- `GET /api/metadata/system-fields` publishes them: `{ name, type, scope }`, where `scope` says
  whether the column is on every table, only with geometry, only once a workflow is attached, or
  reserved without existing at all (`version`, for optimistic locking that does not exist yet).
- `SqlIdentifier.SYSTEM_COLUMNS` is now derived from that one declaration rather than being a second
  list beside it, so the published set and the refused set cannot drift.
- The object editor lists them under the fields it owns, muted, with no label input, no checkboxes
  and no delete button. `geom` reads as a system column on an object with geometry and as a reserved
  name on one without.
- Typing a name the platform owns, or one another field already has, is refused inline while it is
  being typed, in both the object editor and the object builder. SQL keywords are deliberately not
  mirrored in the client: copying that list is the duplication this change removes.
- An integration test posts every published name and expects a `400` for each, so the screen cannot
  promise a name the server rejects.

## 2026-09-18 — Workflows are drawn, not filled in

A workflow definition is a directed graph, and the editor was two lists of form rows with a
read-only text summary at the bottom. Reading a seven-state flow meant drawing it on paper (ADR-018).

- `/automation/workflows` is now a canvas. States are dragged, a transition is made by dragging from
  one state to another, and a panel on the right edits whatever is selected. The three form cards are
  gone.
- State coordinates are stored: `x`/`y` are optional fields on each state inside the workflow's
  `jsonb` definition, so no migration was needed. A workflow saved before this change opens laid out
  breadth-first from its initial state, and the next save keeps where it was left.
- Dragging out of a FINAL state is refused on the canvas, naming the state, because the server
  refuses it too.
- The state list is sorted into reading order on save. The form editor ordered it with up/down
  buttons and that order is visible in the automation builder's state picker; arranging the diagram
  now does the same job.
- The rules that lived inside the form editor — one INITIAL state, a rename that drags its
  transitions, a delete that drops dangling ones — moved into `workflowGraph.ts` and are unit-tested
  there for the first time. Clearing a state's name used to lose the selection; it no longer does.
- xyflow does not render in jsdom, so canvas interaction is covered by a test double and verified in
  the browser instead. `@xyflow/react` is the one dependency this adds.

## 2026-09-18 — Object and field metadata become editable

Objects could be created and then never touched again. The `PUT` and `DELETE` endpoints existed but
the UI had no way to reach them, and `deleteObject` was unfinished underneath (ADR-017).

- A new editor at `/data/objects/:object/edit`: labels and description, a field table that adds,
  relabels, flags and drops fields, and a delete that only unlocks once the technical name is typed.
- `deleteObject` was leaving debris. It now unpublishes the GeoServer layer and drops the join tables
  of its relationships, which used to outlive the object; and it refuses with `409`, naming them,
  when another object has a `RELATION` field pointing at it. That case used to `DROP TABLE … CASCADE`
  — silently stripping the other object's foreign key — and then fail with a `500`.
- `deleteField` refuses a field a relationship owns, and a field an automation reads or writes,
  naming the rules. Losing a field breaks a rule only the next time it fires, long after the delete.
- Object names and field names and types are immutable, and now say so with a `400` instead of being
  quietly dropped from the request.
- `enabled` used to be stored and honoured by nothing. A disabled object is now read-only: its
  records stay readable, writes answer `409`. It is the reversible alternative to deleting.
- Two ports keep the boundaries: `gis` implements `ObjectRemovalListener`, `automation` implements
  `FieldUsage`. `metadata` still knows about neither.

## 2026-09-18 — Automations

Rules that run themselves: a trigger, some conditions, some actions, all metadata (ADR-016).

- `sapgis.automations` holds rules per object; `sapgis.automation_runs` is both the work queue and
  the log. Matching happens inside the write's transaction, the actions run off the request, so a
  slow webhook never delays a `PUT` and a broken rule never rolls back a user's write.
- Triggers cover creates, updates, deletes, a named transition and entering a state. Actions write a
  field, create a record on another object, or call a webhook. Values accept `{{field}}` templates.
- Every rule that matched leaves a run, including the ones that did not fire: a `SKIPPED` row names
  the condition that did not hold. "Why did nothing happen" has an answer in the UI.
- Two loop guards: a rule never answers its own writes, and every automation-caused change is one
  step deeper until `max-depth` stops the chain.
- Webhooks are checked on save and again before each call; a host resolving to a private address is
  refused, because an automation is the one place SAPGIS calls out on a user's say-so.
- Rules act as the platform, not as the user who triggered them, so a restricted user is exactly who
  they help instead of who they fail for.
- `RecordTransitioned` is gone: the `RecordChangeListener` port replaces the seam it was holding.

**Fixes worth remembering**
- R2DBC rejects a bound parameter the statement never names, so an `UPDATE` must not bind the columns
  it does not set.
- A webhook validated only at call time is validated too late: the admin who typed `localhost` finds
  out from a failed run instead of from the form.

## 2026-09-18 — The assistant moves to Embabel

The spec named Embabel as the agent runtime, so the hand-written tool loop was replaced by it
(ADR-015). The tool layer, the HTTP contract and every ADR-014 guarantee survived unchanged —
`AgentToolsTest` passed with its assertions untouched, which is the evidence that the security model
did not move, and five new tests drive the whole agent path with a stubbed model.

- `EmbabelGate` keeps SAPGIS bootable: Embabel's platform fails the context without a model, so with
  no API key every one of its autoconfigurations is excluded and the assistant reports itself
  unavailable, exactly as before.
- The assistant's model dropped from `claude-opus-5` to `claude-haiku-4-5`: Embabel 1.5.2's Anthropic
  catalogue does not know Opus 5.
- A user granted READ on single objects can now use the assistant. The old gate demanded a
  tenant-wide READ and refused them before the model was consulted — a hole left by the per-object
  permission work, not by Embabel.
- ADR-014's claim that dropping the Anthropic SDK would remove Jackson 2 was wrong and is corrected:
  Embabel brings Jackson 2 by five transitive routes of its own.

Verified live against the real API with the spec's own question — "¿cuántos predios comerciales de más
de 1.000 m²?" — answered correctly in five visible tool steps, including explaining that the one
commercial parcel has no area recorded rather than inventing one.

## 2026-09-18 — AI assistant (phase 11)

The last phase, and the one with the sharpest constraint: an assistant that answers questions about
a tenant's data without being able to see more than the person asking.

- Nine read-only tools, each calling a SAPGIS service — objects, definitions, record queries and
  counts, a record, relationships and related records, history, available transitions. No SQL, no
  repository, no database access of any kind (ADR-014).
- The agent runs as the caller. The model call is wrapped in `Dispatchers.IO` because the Anthropic
  SDK blocks and SAPGIS is WebFlux; the security context rides across that hop, asserted by a test
  rather than assumed.
- Object definitions come from the caller-filtered `definitionOf`, so a field hidden by field
  permissions is invisible to the agent too.
- **No write tools.** A tool that creates records would turn a sentence into a change, and the
  confirmation step that makes that safe does not exist yet.
- Answers carry the tools they were built from, and the assistant screen shows them, collapsed. An
  answer nobody can check is worse than no answer.
- Without `ANTHROPIC_API_KEY` the feature reports itself unavailable, asking answers 503 rather than
  500, and the rest of the platform is untouched — the rule GeoServer already follows.
- Model: `claude-opus-5` through the Anthropic Java SDK, thinking left on by default.

## 2026-09-18 — Audit history and workflows (phases 8 and 10)

**History (phase 8)**
- `GET /api/objects/{object}/records/{id}/history` and a `changes` list on every audit entry, diffed
  from the stored before and after states. Only fields that actually differ appear; a value that
  changed only in scale (`10` vs `10.0` out of jsonb) does not count as a change.
- Field permissions apply to the audit log too: a field the caller may not read never appears as a
  change or as a value. Without that the log would have been a way around phase 7.
- Reading a record's history is a read of its object, so a role granted READ on one object cannot
  read another's trail.

**Workflows (phase 10)**
- States and transitions, no engine. A workflow belongs to an object, has exactly one initial state,
  and its transitions name the roles that may take them.
- A record's state is a real column on its physical table (ADR-013), so views and filters can treat
  it as what it is. It is not a Custom Field, so no record payload can set it: a transition is the
  only way to move a record, and applying one from the wrong state answers 409.
- The transition listing reports every transition leaving the current state, including the ones the
  caller may not take and why, so the UI can explain a disabled button instead of hiding it.
- Every transition is written to the audit log, so workflow movement shows up in the history beside
  field changes.
- A `RecordTransitioned` event is published with no listeners yet — the seam automations attach to.
- Pages gained `HISTORY` and `WORKFLOW` components. A generated page ends with the record's history,
  and shows the workflow panel when the object actually has a workflow, so attaching one is visible
  without editing a layout.

**Also**
- The frontend now has a formatter: Prettier reading the repository `.editorconfig` (2-space indent,
  160 columns), wired into `yarn lint`. Kotlin already had ktlint; 8,700 lines of TypeScript were
  held together by convention alone.
- `WorkflowService` asked `identity` for role names through a new `RoleDirectory` service rather than
  reading `sapgis.roles` itself, closing a module-boundary shortcut.

**Fixes worth remembering**
- Two workflow tests timed out in a full suite and passed in isolation. A clean run on a quiet tree
  passed all 101: the failures were concurrent Gradle builds disrupting the test JVM, not a leak.

## 2026-09-18 — Security enforcement (phase 7)

Permissions stopped being a table and started being a rule the server applies.

- Users and roles are managed through the API, with the administration screens behind them.
  A user cannot delete or disable themselves and the `ADMIN` role cannot be deleted — the server
  refuses, it does not rely on the UI to hide the button.
- **Field permissions are a restriction, not a grant**: a role with no entry for a field keeps full
  access, and across several roles the permissive union wins. Unreadable fields never reach the
  response — they are not even selected — and unwritable ones come back `editable: false`. Writing
  one is rejected naming the field rather than silently dropped.
- **Record level**: a role marked `own_records_only` sees and edits only what its users created.
  A record they may not see answers 404, not 403, so existence does not leak.
- **Per-object permissions are now enforced.** The stored rules always had an `object_id`, but the
  check ignored it, so a grant meant for one object behaved as a tenant-wide grant of that action —
  while the permission matrix in the UI promised otherwise. A row naming an object now grants the
  action there only, a row without one grants it everywhere, and a check with no object in hand
  needs a tenant-wide row: one object's grant is not a platform grant.
- `GET /api/objects` returns the objects the caller may read rather than everything, since that list
  is what the whole UI hangs off.
- A required field the caller may not write now fails with a clear 403 instead of a NOT NULL 500.

**Fixes worth remembering**
- `PUT` on a record is a full replace, so enforcing field permissions would have blanked every field
  the caller could not write. Updates now apply the writable projection and leave the rest alone.
- Running integration tests against a persistent database leaks objects and their tables; 1200
  leftovers pushed the suite from two minutes to eleven and into timeouts. The database is recreated
  before a full run, and `docs/development` says so.

## 2026-09-17 — App Builder completed and GeoServer publishing (phase 9)

**Views and forms** finish App Builder, following the model Pages set: named metadata per object,
resolved to a default derived from the object when nothing is stored (ADR-012).
- A view is a list configuration — columns and order, exact-match filters, sort, page size — and one
  view per object can be the default the list opens with. The record list is driven by the selected
  view; sorting a column overrides it for the session without rewriting the stored definition.
- A form arranges fields into titled sections; a page's `FORM` component may name one instead of
  listing fields (`form` and `fields` are mutually exclusive).
- Both validate on write against the object's fields, so a bad layout fails at configuration time.
- V5 scopes both tables to the organization, as V4 did for pages.

**GeoServer (phase 9)**
- `POST /api/gis/layers/{object}` creates the workspace and PostGIS datastore when missing and
  publishes the object's physical table as a feature type. Layers are named after the physical
  table, so tenants cannot collide. Publishing is idempotent.
- `GET /api/gis/layers` reports every spatial object and whether it is published; `GET /api/gis/services`
  reports the WMS/WFS/WMTS endpoints. Nothing on a record or metadata path calls GeoServer, so the
  platform works with it down or disabled.
- The UI adds a Layers screen with publish, unpublish, copyable service URLs and a WMS preview;
  `MapView` gained a `wmsLayers` prop that diffs raster overlays under the GeoJSON layers.
- Verified against the live GeoServer 3.0.1: publishing through the API makes WFS return the real
  record and WMS render a PNG.

**Security administration UI (phase 7, frontend)**
- Users, Roles and a permission matrix (objects x actions, plus per-object field read/write), ready
  for the backend enforcement that follows.

**GeoServer 3.0.1 quirks worth remembering**
- A duplicate feature type answers 500, not 409, so idempotency needs an existence check first.
- Listing feature types of a missing datastore answers 500, not 404.
- Empty collections serialize as `""` rather than `[]`.
- Spring Boot 4 does not expose a `WebClient.Builder` bean with the WebFlux starter alone.

## 2026-09-17 — Dynamic pages (phase 6)

The record detail screen stopped being React code and became metadata.

- `Page` is metadata: a layout (`single-column`, `two-column`) and ordered components (`FORM`,
  `MAP`, `RELATED_LIST`, `TEXT`), each in a declared column, scoped to the organization and unique
  per object and kind.
- `GET /api/objects/{object}/pages/record-detail` always answers: the stored page, or one derived
  from the object's metadata and flagged `generated`. The client has one rendering path (ADR-011).
- Definitions are validated on write — unknown relationship, unknown field, a map on a flat object,
  blank text, or a column the layout does not have are all rejected at configuration time.
- UI: `PageRenderer` interprets the definition, and a Page Builder edits it — layout, components,
  order, per-component options — with Save turning a generated page into a stored one and Reset
  dropping back to the default. A visual drag-and-drop builder remains a later phase.
- The `pages` table from V1 was globally unique by name, which two tenants could not share; V4
  scopes it to the organization and adds the page kind.

**Fixes worth remembering**
- The paginated envelope type on the client was called `Page`, which collided with the page-layout
  type; it is now `Paged<T>`. The wire format did not change.

## 2026-09-17 — Relationships, organizations and full field CRUD

Closes the gaps left in phase 2 and delivers phase 5.

**Relationships (phase 5)**
- `Relationship` as first-class metadata for `MANY_TO_ONE`, `ONE_TO_ONE`, `ONE_TO_MANY` and
  `MANY_TO_MANY`. Creating one builds what it needs — a `RELATION` field with a real foreign key, or
  a join table — and deleting one removes it again.
- Related records read from either end of the relationship, paged like any other list.
- Link and unlink endpoints for many-to-many.
- UI: a Relationships page to create and inspect them, and related lists on every record detail,
  labelled with `label` from the source and `inverseLabel` from the target.

**Phase 2 gaps closed**
- Organization API: read and rename the current tenant, provision a new tenant with its first
  administrator, delete a tenant along with the tables it owns. No list-all endpoint, on purpose.
- `PUT` and `DELETE` for custom fields, applying DDL with the metadata: `NOT NULL`, unique
  constraints, replaced enum `CHECK`s, dropped columns.
- Permission actions centralised in `common/Actions`, plus `MANAGE_ORGANIZATION` (migration V3).

**Testing**
- Integration tests can now run against an already running database through `SAPGIS_TEST_DB_*`,
  because a remote docker daemon publishes container ports out of the test JVM's reach. 27 API tests
  green against PostGIS.

**Fixes worth remembering**
- Saving a record now invalidates related lists too: a relation field is a link, and both sides go
  stale.

## 2026-09-17 — Foundation, metadata and Vertical Slice #1

First working slice: an administrator creates a Custom Object and its records appear in a table, a
detail page and a map, with nothing written per object.

**Platform**
- Gradle 9.7.1 monorepo on the Java 25 toolchain, Kotlin 2.4.20, Spring Boot 4.1.1 WebFlux, version
  catalog, ktlint aligned with the repository `.editorconfig`.
- Docker Compose: PostgreSQL 18 + PostGIS 3.6 + pgvector 0.8.6 (custom image) and GeoServer 3.0.1.
- GitHub Actions: backend build with ktlint and unit tests plus container-backed integration tests;
  frontend typecheck, tests and build.

**Backend**
- Flyway schema for organizations, users, roles, permissions, custom objects and fields,
  relationships, views, pages, forms and the audit log, split across the `sapgis` and `app_data`
  schemas.
- Metadata API creating a Custom Object together with a real PostGIS table: typed columns, enum
  `CHECK` constraints, relation foreign keys, `geometry(<type>, <srid>)` and a GIST index.
- Dynamic record API with paging, sorting, search, equality filters and bbox queries, behind a
  `RecordStore` port; geometry crosses R2DBC as GeoJSON text.
- GeoJSON feature endpoints, audit log for every record write, RFC 7807 errors everywhere.
- Identity: BCrypt login issuing HS256 JWTs, resource-server security, tenancy resolved from the
  token.

**Frontend**
- React 19.3, Vite, Tailwind 4, shadcn-style primitives, react-router, TanStack Query, i18next
  (Spanish default, English available).
- `DynamicForm` and `DataTable` render any object from its metadata; zod schemas are built from field
  metadata at runtime.
- `MapView` (MapLibre) with feature selection, popups and fit-to-extent, and a `GeometryField` that
  draws Point, LineString and Polygon through Terra Draw.
- Object Builder, record list, create, two-column detail (form + map), map page and audit log.

**Fixes worth remembering**
- PostgreSQL 18 images want the data volume at `/var/lib/postgresql`, not `/var/lib/postgresql/data`.
- A remote Docker daemon resolves bind mounts on its own host, so database init scripts are baked
  into the image.
- The backend serves on 8090; 8080 is too often taken on development machines.
- MapLibre 6 resolves its worker from `import.meta.url`, which bundling breaks — the worker died
  silently and no GeoJSON source ever loaded until `setWorkerUrl` was set.
- `MapView` queues work until the style's source and layers exist: `isStyleLoaded()` can be true
  before the `load` handler has added them.

**Not in this slice** — relationships in the UI, page and form builders, field and record level
permissions, workflows, automation, AI agents, GDAL import, GeoServer layer publishing, Pulumi/k3s.
