# ADR-083: An app mounts core's record screens in its own module

**Status**: accepted · 2026-11-14 · extends [ADR-028](0028-frontend-module-registry.md)

## Context

`RecordListPage`, `RecordDetailPage` and `RecordFormPage` read `:object` and `:id` from `useParams()` and link to
core's own record routes (`/data/objects/:object/records/...`). The only read-only view they had came from the
object's rules (`apiOnly`, `appendOnly`) or from the server's refusals. An app that wants the records of one of its
objects inside its own module, under its own urls and without writes, had to mount them under routes shaped like
core's and still watched every link leave its module
([wasichai-ui#182](https://github.com/wasichai/wasichai-ui/issues/182)).
[ADR-076](0076-the-app-picks-the-shell-menu-and-the-start-page.md) kept core's routes in the LMS app for that reason.

The record list also asked `GET /objects/{object}/views` on every visit. Without the `wasichai-views` backend module
that is a `404` in the browser console on every list (hneyra/curricula, `@wasichai/core` 1.8.2 on wasichai 1.10.0;
[wasichai-ui#183](https://github.com/wasichai/wasichai-ui/issues/183)). ADR-028 says a registered frontend module whose
backend is missing gets `404`s, read as "not installed"; it says nothing of core asking a module's endpoint when the
app registers no frontend module for it.

## Decision

**Props on the three screens, not a second set of screens.** Each takes `objectName`, `recordId` (detail and form),
`routes` and `readOnly`, all optional. Without them a screen reads its route and links to core's routes, as before.

**Route keys, not a base path.** `routes` (`RecordRoutes`) names registered route keys: `records` (the list, where
the detail's back goes), `record` (one record, where a row opens and a saved form lands) and `newRecord` (the create
form; absent, the list offers no create). Each link is `links.to(key, params)` with the current route's params plus
`:object` and `:id`, so a nested route (`courses/:course/lessons/:id`) keeps its course; in these routes `:id` always
names the record. A base path (`/lms/lessons`, with core appending `new` and `:id`) was set aside: it puts a url in a
prop, imposes core's suffixes on the app's routes, and nothing checks the routes exist. Route keys keep every url in
the registry (ADR-028, "Links"), leave the patterns to the app, and an unregistered key throws with its name. Links
to other objects (a related list's «Editar», a `NAVIGATE` action) stay core's.

**`readOnly` is the app's choice, not a permission.** It takes away create, edit, delete, link and unlink from the
screen, to an administrator too, and draws no write-policy notice, since the object's rules are not the reason. It
never grants anything: the server still decides. The record slots on these screens get it in their props
(`RecordListActionProps`, `RecordPanelProps`, `PageComponentProps`, `PageActionProps`), and the modules that write
there hold back: workflow shows the state without transitions and draws no TRANSITION button, documents lists the
issued documents without an issue. Two other ways were set aside: core dropping module slots on a read-only screen
(a map or a document list only reads), and a context every write hook reads (it would show the object's rule as the
reason a write is missing).

**The form also edits.** With a record (`recordId`, or `:id` in its route) `RecordFormPage` loads it, saves it with
`PUT` and lands on `record`; an edit the object's rules refuse reads along with the notice. Read only, an edit shows
the record without a save and a create draws no form.

**Stored views only with the views module.** The record list asks for views only when a module with id `views`
(`@wasichai/views`) is registered, and `useViews` reads a `404` as no views. Reading the `404` alone was not enough:
the browser logs the failed request whatever the page does with the answer. The pages module's resolved page
(`GET /objects/{object}/pages/record-detail`, R8) stays as it is: an app without `@wasichai/pages` still gets the page
its server resolves, and no app reported the `404`.

## Consequences

- Without the new props nothing changes on the record screens.
- An app mounts the list, the detail and the form on its own routes and its links stay in its module. Its record
  routes name the record `:id`.
- A module that writes on a record screen reads `readOnly` from its slot props. One that ignores it keeps offering
  its writes, and the server still answers them.
- Breaking, for wasichai-ui 2.0.0: an app whose server runs `wasichai-views` and that registers no `viewsModule()`
  loses its saved views in the record lists. It adds `viewsModule()` to its modules. ADR-031 D58.
- Core knows one module id, `views`, as it already knew the views endpoints through `useViews`. A second endpoint
  gated this way (pages) is the moment to let a module declare what it brings instead of checking ids.
