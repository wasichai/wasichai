# Forms module

`wasichai-forms` gives an object named arrangements of its fields into titled sections (ADR-012). Installing the
module adds the storage and REST API for forms, and a builder screen that lets an admin drag the object's own fields
into sections; core's own `DynamicForm` and the page renderer's FORM component already draw stored forms, so this
module only adds the part that authors them.

## Install

Backend, under the BOM:

```kotlin
implementation("wasichai:wasichai-spring-boot-starter-forms")
```

Frontend:

```bash
yarn add @wasichai/forms
```

```tsx
<WasichaiApp modules={[formsModule()]} />
```

See [../guides/build-your-app.md](../guides/build-your-app.md).

## What it adds

- The `forms` table: named field arrangements per object, as an ordered list of sections (each with an optional
  title and its list of fields).
- The forms REST API:

  | Method | Path |
  |---|---|
  | GET | `/api/metadata/objects/{object}/forms` |
  | GET | `/api/objects/{object}/forms` |
  | POST | `/api/objects/{object}/forms` |
  | GET | `/api/objects/{object}/forms/{name}` |
  | PUT | `/api/objects/{object}/forms/{name}` |
  | DELETE | `/api/objects/{object}/forms/{name}` |

  The metadata route lists only stored forms (nothing generated); it moved out of core's metadata controller when
  forms became its own module, keeping the same URL. The `/api/objects/...` routes serve and edit the resolved form,
  falling back to a generated one named `default` when nothing is stored.
- A screen and sidebar entry: the `forms:builder` route at `<basePath>/forms` (lazy-loaded), listed under core's
  `builder` nav group as "Forms", order 20.
- `wasichai-pages` depends on this module's `FormService` bean: a page's `FORM` component can name a stored form only
  when this module is present (`WasichaiPagesAutoConfiguration` is `@ConditionalOnBean(FormService::class)` for that
  part of the wiring).

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `wasichai.forms.enabled` | `true` | Switches the module's beans, migration and builder route on or off. |

Env form: `WASICHAI_FORMS_ENABLED`.

## Extension points

**Implements:** None; this module provides no beans for a core SPI.

**Defines:** None directly, but `wasichai-pages` consumes this module's `FormService` bean to resolve a page's `FORM`
component by name.

**Overridable beans** (all `@ConditionalOnMissingBean`): `formRepository`, `formService`, `formController`,
`formMetadataController`.

## Database

Migration location `classpath:db/wasichai/forms`, history table `flyway_history_forms`. It creates the `forms` table
(one row per named form, `definition` as `jsonb`, unique per object and name). It does not change any core table.

## Frontend package

`@wasichai/forms`: the `formsModule(options)` factory, with one option, `basePath` (default `'builder'`), the url
prefix of the builder route. Main export from `index.ts` besides the module factory: `FormBuilderPage`. i18n
namespace `forms` (`es`, `en`).

`FormBuilderPage` is exported directly from `index.ts` (not lazy from the package's own side; the module registers
it as a lazy route, so it still loads on demand inside the app shell). The package has no heavy runtime dependency
beyond `lucide-react` for icons.

See [`packages/forms` in wasichai-ui](https://github.com/wasichai/wasichai-ui/tree/main/packages/forms) for the full API.

## Without this module

The `forms:builder` route and its nav entry are absent, and `GET /api/objects/{object}/forms` and the other form
routes answer 404. Core's `DynamicForm` keeps working on the base record edit screen: it never names a form there,
so it already renders every editable field in metadata order in one flat grid, with or without this module. What
this module's absence removes is the option to arrange fields into sections at all: with no `FormService` bean,
`wasichai-pages` cannot resolve a form by name either, so a `FORM` page component always falls back the same way.

## Behaviour differences

None.

## Known limitations

None.
