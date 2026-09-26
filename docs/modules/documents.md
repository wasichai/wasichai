# Documents module

An app that installs this module lets an admin write a document template per object (fields, platform values and
related-table placeholders, edited in a rich text editor) and lets a record issue a frozen, numbered document from
it, printable on its own page. Issuing shows up in the record's history.

## Install

```kotlin
implementation("wasichai:wasichai-spring-boot-starter-documents")
```

```bash
yarn add @wasichai/documents
```

```tsx
<WasichaiApp modules={[documentsModule()]} />
```

See [../guides/build-your-app.md](../guides/build-your-app.md).

## What it adds

Document types per object, a template editor built on tiptap, issuing a frozen document from a record
([ADR-023](../adr/0023-a-document-is-frozen-when-it-is-issued.md)), a correlative per type and year, the print page
`/documents/:id/print`, and an `ISSUE` operation in the record's history.

REST routes:

| Method | Path |
|---|---|
| GET | `/api/documents/{id}` |
| GET | `/api/objects/{object}/document-types` |
| POST | `/api/objects/{object}/document-types` |
| GET | `/api/objects/{object}/document-types/{name}` |
| PUT | `/api/objects/{object}/document-types/{name}` |
| DELETE | `/api/objects/{object}/document-types/{name}` |
| GET | `/api/objects/{object}/records/{id}/documents` |
| POST | `/api/objects/{object}/records/{id}/documents/{type}` |

Frontend routes and slots:

| Slot | Contribution |
|---|---|
| route `documents:types` | `/builder/documents`: the document types builder |
| route `documents:print` | `/documents/:id/print`: the printable page (`chrome: 'bare'`, signed in, no shell) |
| nav | "Documentos" in the Builder group |
| `recordPanels` | "Documentos emitidos" under every record: issue a document, list and open issued ones |
| `historyRenderers.ISSUE` | an "Emisión" badge, `info` tone, and a "Ver documento" link on the record's history |

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `wasichai.documents.enabled` | `true` | `false` removes the documents beans, routes and migration |

Env form: `WASICHAI_DOCUMENTS_ENABLED`.

## Extension points

**Implements:** `wasichai.automation.DocumentIssuer`, through `DocumentIssuerAdapter`, only when wasichai-automation is
also on the classpath (`WasichaiDocumentsAutomationAutoConfiguration`, `@ConditionalOnClass` on
`wasichai.automation.DocumentIssuer`). That lets a workflow state or an automation's `GENERATE_DOCUMENT` action issue
a document. Without wasichai-automation, wasichai-documents is standalone and that bean does not exist.

**Overridable beans:** `documentRepository`, `documentCounterRepository`, `documentTypeRepository`,
`documentService`, `documentTypeService`, `documentController`, `documentTypeController` — all
`@ConditionalOnMissingBean`, so an app can replace any of them.

## Database

Migration location `classpath:db/wasichai/documents`, history table `flyway_history_documents`. Tables:
`document_types` (a template per object, unique name per object and unique prefix per organization),
`document_counters` (the correlative, keyed by type and year, incremented inside the issuing transaction so a
rollback returns the number), and `documents` (the frozen snapshot, with a partial unique index that keeps at most
one `VALID` document per type and record).

It also alters core's `audit_log`: adds the FK `audit_log.document_id` (`ON DELETE SET NULL`, so a history entry
outlives the document it names) and redefines the CHECK `audit_log_operation_valid` to add `ISSUE`
([ADR-026 addendum](../adr/0026-per-module-migrations.md#addendum-2026-09-25-p7-a-check-has-one-extending-owner)).
wasichai-documents is that CHECK's one extending owner; a second module adding another operation needs that addendum
revisited.

## Frontend package

`@wasichai/documents`: `documentsModule(options)`, with `options.basePath` (default `''`) prefixing both routes.
Main exports from `index.ts`: `documentsModule`, `documentsMessages`, `DocumentView`, `IssuedDocumentLink`,
`RecordDocuments`, the query hooks (`useDocumentTypes`, `useSaveDocumentType`, `useDeleteDocumentType`,
`useRecordDocuments`, `useIssueDocument`, `useIssuedDocument`), and the `DocumentType`/`IssuedDocument`/
`TemplateNode` types. i18n namespace `documents`.

`@tiptap/core`, `@tiptap/react` and `@tiptap/starter-kit` come with the package but stay out of the app's first
bundle: `TemplateEditor` is only reachable through the `documents:types` route's lazy import, never from `index.ts`
([ADR-028](../adr/0028-frontend-module-registry.md)).

`print.css` ships as `dist/print.css` (package export `@wasichai/documents/print.css`) and is imported once, anywhere
in the app, per the package README. It is plain, unlayered CSS on purpose, so it wins over Tailwind's preflight
without `!important`; it gives the printed sheet its A4 page, heading sizes, table reflow and the visible ARCHIVED
mark.

See [`packages/documents` in wasichai-ui](https://github.com/wasichai/wasichai-ui/tree/main/packages/documents) for the full API.

## Without this module

No issue button on the record panel and no `ISSUE` entries rendered specially in history. The builder route and
the print route answer `404` ([ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D1), so the frontend
shows neither the nav entry's target nor a working print link. An automation `GENERATE_DOCUMENT` action is refused
when it is saved, and a previously saved one fails its run instead
([ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D3).

## Behaviour differences

[ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D3: a `GENERATE_DOCUMENT` automation action is refused
at save time when wasichai-documents is absent, rather than failing later. D15: issuing a document now refreshes the
record's history at once, instead of needing a manual reload.

## Known limitations

None.
