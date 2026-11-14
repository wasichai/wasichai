# Views module

`wasichai-views` gives an object named list configurations: which columns show, their order, exact-match filters, a
sort and a page size (ADR-012). One view may be flagged default. Installing the module adds the storage and REST API
for views, and a builder screen to edit them. Core's own record list reads stored views when the app registers this
module's frontend, `viewsModule()` of `@wasichai/views`, and falls back to a generated one when none is stored, so
this module adds the part that authors them. Since wasichai-ui 2.0.0 an app whose server runs this module and that
registers no `viewsModule()` lists on the generated view only: the list no longer asks a server that may lack the
module (ADR-031 D58, [ADR-083](../adr/0083-an-app-mounts-cores-record-screens-in-its-own-module.md)).

## Install

Backend, under the BOM:

```kotlin
implementation("wasichai:wasichai-spring-boot-starter-views")
```

Frontend:

```bash
yarn add @wasichai/views
```

```tsx
<WasichaiApp modules={[viewsModule()]} />
```

See [../guides/build-your-app.md](../guides/build-your-app.md).

## What it adds

- The `views` table: named list configurations per object (`columns`, `filters`, `sort`, `pageSize`), with at most
  one default per object.
- The views REST API:

  | Method | Path |
  |---|---|
  | GET | `/api/metadata/objects/{object}/views` |
  | GET | `/api/objects/{object}/views` |
  | POST | `/api/objects/{object}/views` |
  | GET | `/api/objects/{object}/views/{name}` |
  | PUT | `/api/objects/{object}/views/{name}` |
  | DELETE | `/api/objects/{object}/views/{name}` |

  The metadata route lists only stored views (nothing generated); it moved out of core's metadata controller when
  views became its own module, keeping the same URL. The `/api/objects/...` routes serve and edit the resolved view,
  falling back to a generated one named `default` when nothing is stored.
- A screen and sidebar entry: the `views:builder` route at `<basePath>/views` (lazy-loaded), listed under core's
  `builder` nav group as "Views", order 40.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `wasichai.views.enabled` | `true` | Switches the module's beans, migration and builder route on or off. |

Env form: `WASICHAI_VIEWS_ENABLED`.

## Extension points

**Implements:** None; this module provides no beans for a core SPI.

**Defines:** None; no other module implements an SPI of this one.

**Overridable beans** (all `@ConditionalOnMissingBean`): `viewRepository`, `viewService`, `viewController`,
`viewMetadataController`.

## Database

Migration location `classpath:db/wasichai/views`, history table `flyway_history_views`. It creates the `views` table
(one row per named view, `definition` as `jsonb`, unique per object and name, at most one default per object via a
partial unique index). It does not change any core table.

## Frontend package

`@wasichai/views`: the `viewsModule(options)` factory, with one option, `basePath` (default `'builder'`), the url
prefix of the builder route. Main export from `index.ts` besides the module factory: `ViewBuilderPage`. i18n
namespace `views` (`es`, `en`).

`ViewBuilderPage` is exported directly from `index.ts` (not lazy from the package's own side; the module registers
it as a lazy route, so it still loads on demand inside the app shell). The package has no heavy runtime dependency
beyond `lucide-react` for icons.

See [`packages/views` in wasichai-ui](https://github.com/wasichai/wasichai-ui/tree/main/packages/views) for the full API.

## Without this module

The `views:builder` route and its nav entry are absent. Core's record list keeps working: it has no stored view to
read, so it always renders the columns metadata derives (the visible fields, in their configured position order,
capped at eight), with no way for an admin to name a different arrangement or a per-audience view.
`GET /api/objects/{object}/views` and the other view routes answer 404.

## Behaviour differences

None.

## Known limitations

None.
