# Pages module

`wasichai-pages` turns the record detail screen into metadata: an administrator arranges a tree of components on a
template, per object, in a drag-and-drop builder ([ADR-011](../adr/0011-metadata-defined-pages.md)). Where no page
is stored, the server derives one from the object's metadata instead, so there is one rendering path, never a
hardcoded fallback layout. The module needs `wasichai-forms`, since a `FORM` component may name a stored form.

## Install

```kotlin
implementation("wasichai:wasichai-spring-boot-starter-pages")
```

This starter brings `wasichai-forms` with it.

```bash
yarn add @wasichai/pages
```

```tsx
<WasichaiApp modules={[pagesModule()]} />
```

See [../guides/build-your-app.md](../guides/build-your-app.md).

## What it adds

- A record-detail page per object: a tree of components ([ADR-021](../adr/0021-a-page-is-a-tree.md)) laid out in
  one region of a template ([ADR-022](../adr/0022-a-page-has-a-template.md)). When no page is stored, the server
  generates one from the object's metadata (the form, one tab per module component, one related list per
  relationship, history last).
- Tab keys: a `TAB` may carry a `key`, a stable upper-case name a link opens the tab by (`?tab=KEY`), so a
  notification can point at the tab where the work is done ([ADR-046](../adr/0046-notifications-module.md), ADR-031
  D33). See "Tab keys" below.
- The drag-and-drop builder screen, at `<basePath>/pages` (default `/builder/pages`).
- REST routes:

  | Method | Path |
  |---|---|
  | GET | `/api/metadata/objects/{object}/pages` |
  | GET | `/api/metadata/page-templates` |
  | GET | `/api/objects/{object}/pages/{kind}` |
  | GET | `/api/pages` |
  | POST | `/api/pages` |
  | GET | `/api/pages/{name}` |
  | PUT | `/api/pages/{name}` |
  | DELETE | `/api/pages/{name}` |

### Tab keys

Only a `TAB` may carry `key`; on any other component it is `400` (`key is only for TAB`). On write it is trimmed and
upper-cased, blank counts as none, and it must match `^[A-Z][A-Z0-9_]{0,39}$` (`TabKey.PATTERN`). It is unique in the
whole page, tabs of nested strips included, so `?tab=KEY` never names two tabs: a repeat is `400`
(`repeated tab key '…'`). A component without a key is stored and sent without the property
(`@JsonInclude(NON_NULL)`), so a page that uses no keys is stored and read as before. A `PUT` without a `definition`
re-emits the stored tree and keeps its keys.

A generated page keys each tab with its title: `DETAILS`, `RELATED` and `HISTORY` (`GeneratedTab`), and each module
tab with the `tab` its `PageComponentProvider` returns (`MAP` from wasichai-gis). The built-in keys are taken first:
a module tab whose title repeats a taken key keeps its tab, without a key, so the generated page stays valid.
`GeneratedComponent` refuses a `tab` that is not a valid key, which the module's own tests catch before a page is
ever generated.

The server checks a key's format and uniqueness only. Opening a tab from `?tab=`, with a fallback to the first tab
when the page has no such key, and editing keys in the builder belong to wasichai-ui. See
[../api/rest.md#component-fields](../api/rest.md#component-fields) for the wire shape and the refusals.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `wasichai.pages.enabled` | `true` | Switches the module's beans, its migration and its REST routes on or off. Even
  when `true`, `WasichaiPagesAutoConfiguration` also requires a `FormService` bean (`@ConditionalOnBean`) — pages
  backs off if `wasichai-forms` is absent or disabled. |

Env form: `WASICHAI_PAGES_ENABLED`.

## Extension points

**Implements:** nothing from a core SPI beyond registering its own `ModuleMigration` bean (`wasichaiPagesMigration`,
never `@ConditionalOnMissingBean` — core's null-object handling for migrations depends on always finding one).

**Defines:** `PageComponentProvider`, the SPI a module implements to add a component type to the tree. Pages' own
types (`ComponentType.BUILT_IN`) are `PAGE`, `REGION`, `TABS`, `TAB`, `SECTION`, `FORM`, `DYNAMIC_FORM`, `FIELD`,
`RELATED_LIST`, `TEXT`, `HISTORY` and `ACTION` — `HISTORY`, the record's audit trail, is one of pages' own rather
than a provider's. `wasichai-gis` adds `MAP` (`MapPageComponent`, `@Order(100)`) and `wasichai-workflow` adds
`WORKFLOW` (`WorkflowPageComponent`, `@Order(200)`), each registering only when `wasichai-pages` is also on the
classpath. `PageComponentTypes` collects every `PageComponentProvider` bean in order and refuses two providers
that declare the same type. A module adds a component type with a provider bean; the frontend adds its renderer
through the `pageComponents` registry slot ([ADR-028](../adr/0028-frontend-module-registry.md)).

**Overridable beans** (all `@ConditionalOnMissingBean`): `pageComponentTypes`, `pageRepository`, `pageService`,
`pageController`, `objectPageController`, `pageTemplateController`, `pageMetadataController`. The migration bean
is not overridable, for the reason above.

## Database

Migration location `classpath:db/wasichai/pages`, history table `flyway_history_pages`. `V1__pages.sql` creates the
`pages` table: one row per stored page, `definition` as `jsonb`, `kind` restricted to `RECORD_DETAIL`
(`pages_kind_valid`), unique per `(organization_id, name)` and per `(object_id, kind)`, with `object_id` and
`organization_id` both `ON DELETE CASCADE`. No changes to core tables.

## Frontend package

`@wasichai/pages`: the `pagesModule(options)` factory, with one option, `basePath` (default `'builder'`, the url
prefix of the builder route). Main exports from `index.ts`: `PAGES_MODULE_ID`, `pagesModule`, the
`PagesModuleOptions` type, and `pagesMessages`. i18n namespace `pages` (`es`, `en`); it carries no `MAP`,
`WORKFLOW` or `TRANSITION` strings — gis and workflow ship their own.

`PageBuilderPage` is deliberately **not** re-exported from `index.ts`: it, and everything it pulls in down to
`@dnd-kit/core`, reaches an app only through `pagesModule()`'s lazy route, so the builder never loads in the app's
initial bundle. The builder reads the registry rather than knowing any module by name: `pageComponents` supplies
each installed component type's palette entry, defaults, inspector settings and canvas preview, and `pageActions`
does the same for each installed `ACTION` kind, with core's `NAVIGATE` always last — so an installed module's kind
(e.g. workflow's `TRANSITION`) is what a freshly dropped action defaults to.

See [`packages/pages` in wasichai-ui](https://github.com/wasichai/wasichai-ui/tree/main/packages/pages) for the full API.

## Without this module

`/api/objects/{object}/pages/{kind}`, `/api/pages*` and `/api/metadata/objects/{object}/pages` all answer `404` to
an authenticated caller ([ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D1). The record detail page
still renders: on that `404`, core's `RecordDetailPage` draws a client-built fallback page instead of the richer,
tabbed one `PageService.generate()` would derive server-side — the form, one `RELATED_LIST` per relationship, then
`HISTORY`, all in a single full-width region. The pages builder route and its nav entry are absent, and with
`wasichai.pages.PageComponentProvider` off the classpath, `wasichai-gis`'s `MAP` and `wasichai-workflow`'s `WORKFLOW`
page components never register even when those modules are installed.

## Behaviour differences

[ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md):

- **D4**: a component whose type no registered module draws — a `MAP`/`WORKFLOW` component whose owning module is
  absent — draws a quiet placeholder instead of failing to load the page. An `ACTION` of an unregistered kind
  draws nothing at all: it is filtered out of the tree before rendering, so it cannot even hold a tab open on its
  own.
- **D5**: a freshly dropped `ACTION` defaults to the first registered kind: `TRANSITION` with `wasichai-workflow`
  installed, `NAVIGATE` without it.
- **D12**: when the record detail page fails to load, only a `404` falls back to the default page (the
  client-built fallback described above). Any other error shows the error and a retry, instead of silently
  swapping the layout.
- **D13**: a stored `NAVIGATE` whose target object has gone missing (e.g. deleted after the page was saved) links
  to the objects list instead of building a broken `/undefined` url.
- **D33**: a `TAB` may carry `key`, an upper-case token unique in its page, and generated pages key their tabs with
  their titles. A component without a key is stored and sent as before, with no `key` property.

## Known limitations

- The canvas's drag interactions are not exercised by a real pointer in tests — jsdom has none. The drop handler,
  the hover-to-open-tab logic and the rest of the pure functions behind the canvas are tested directly instead;
  the canvas itself is checked by looking at it.
