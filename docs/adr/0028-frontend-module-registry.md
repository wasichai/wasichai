# ADR-028: Frontend modules plug into a registry

**Status**: accepted · 2026-09-25

> Moved from chawpi on 2026-09-26: identifiers renamed chawpi → wasichai; the decision is unchanged. See
> [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md) and [the origin page](../chawpi-origin.md).

## Context

The original frontend was one Vite app with sixteen feature folders. Routes and the sidebar were hardcoded,
`DynamicForm` imported MapLibre statically, the page renderer switched over every component type, history imported
documents and documents imported history. An app could not leave GIS out, and it could not add a screen without
editing the shell.

## Decision

**Packages.** `@wasichai/ui` (primitives and Tailwind theme), `@wasichai/core` (the app shell and everything that works
without a module), one package per backend module (`@wasichai/views`, `forms`, `pages`, `workflow`, `automation`,
`documents`, `gis`, `agent`) and `@wasichai/testing`. Each is a Vite library build (ESM plus `tsc` declarations). React,
react-dom, react-query, i18next, react-i18next and react-router are peer dependencies, so the app owns one copy.

**The contract.** A module is a value of type `WasichaiModule`, usually built by a factory
(`gisModule({ workerUrl })`). The app passes the list to `<WasichaiApp modules={[...]} />`, and `createRegistry` merges
the list once:

| Member | What the module contributes |
|---|---|
| `id` | its name, also its i18n namespace. Unique, and not one of core's reserved ids |
| `basePath`, `routes` | routes relative to `basePath`. The default `basePath` keeps the original app's URLs |
| `navGroups`, `nav` | sidebar groups and items, each with an order, an icon and a permission check |
| `fieldRenderers` | input, display and settings for a field type (gis: `GEOMETRY`), keyed by the section it owns |
| `pageComponents`, `pageActions` | page-builder components (gis: `MAP`, workflow: `WORKFLOW`) and action kinds (workflow: `TRANSITION`) |
| `recordPanels`, `recordListActions` | panels on a record's page, buttons above a record list |
| `historyRenderers`, `auditValueFormatters`, `auditFieldLabels` | how the history shows a module's operations (documents: `ISSUE`) and values |
| `dashboardCards`, `objectColumns`, `objectTileDetails`, `objectFlags` | additions to the dashboard and object lists |
| `recordQueryKeys` | extra react-query keys to invalidate when a record changes |
| `providers` | context providers wrapped around the whole app |
| `i18n` | resources per language, loaded under the namespace `id` |

`createRegistry` refuses a bad combination with a `RegistryError` at start-up, before anything renders: a duplicate
module id, route, path, nav item or page component, a nav item pointing at an unknown route, a field renderer whose
section collides with a core record key, or two modules claiming the same field-renderer setting.

**Slots, not imports.** Core never imports a module. `DynamicForm` asks the registry for a field type's renderer,
and the page renderer asks it for a component type. An unknown component type draws a muted placeholder rather than
nothing. Core's own page action is `NAVIGATE`, and core's history operations are `CREATE`, `UPDATE` and `DELETE`.
Everything else arrives through a slot, which is how the history↔documents cycle disappears.

**Links.** Code never writes a URL by hand. `useWasichaiLinks()` builds every link from the registry's routes, so a
module mounted under another `basePath` keeps working.

**i18n.** Core's resources are the namespace `common`, which is both `defaultNS` and `fallbackNS`. A module's keys
live in its own namespace (its `id`), and a module component may still use a core key unprefixed, because lookups
fall back to `common`. The keys that belong to one module moved out of core's `common.json` into that module.

**Heavy libraries stay lazy.** MapLibre and terra-draw (gis), xyflow (workflow), tiptap (documents) and dnd-kit
(pages) are reached only through lazy routes and `React.lazy`, so an app that registers the module pays for the
library only when a user opens that screen. The pages that pull them in (`WorkflowBuilderPage`, `PageBuilderPage`,
`TemplateEditor`) are not exported from their package's index. They are reachable only as routes. Light modules
still re-export their pages from the index. Vite then warns that the dynamic import is ineffective, but those
pages add nothing heavy to the initial chunk, so this is accepted.

## Consequences

- An app picks modules by listing them. Leaving one out removes its routes, sidebar entries, renderers and strings.
  Its data stays readable through core's generic views.
- A backend module the frontend does not register is invisible, and a registered frontend module whose backend is
  missing gets `404`s. Core treats a `404` on a module endpoint as "not installed" (ADR-031).
- Configuration errors fail at start-up with a message naming both modules, not as a broken screen later.
- An app cannot import the heavy builder pages directly. None needs to, and exporting them would undo the lazy
  loading.
- Adding a slot is a change to `@wasichai/core`'s contract, so it needs a second user first (CLAUDE.md, "no
  overengineering").
