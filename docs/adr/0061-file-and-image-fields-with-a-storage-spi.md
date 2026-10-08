# ADR-061: FILE and IMAGE fields, stored through a FileStore SPI and written as record writes

**Status**: accepted · 2026-10-08 · builds on [ADR-025](0025-extension-spis.md), [ADR-026](0026-per-module-migrations.md),
[ADR-039](0039-background-work-runs-as-the-platform-with-a-cluster-lock.md) and
[ADR-040](0040-append-only-objects-and-a-pre-write-guard.md)

## Context

The metadata model listed `FILE` and `IMAGE` as planned types, with no storage, no upload or download route and no
multipart handling anywhere. An app that keeps evidence (photos, attendance sheets, signed documents) built its own
object store, endpoints, permission checks and history next to the platform's, although evidence needs exactly the
permissions, ownership, append-only and audit rules of the record that owns it
([#54](https://github.com/wasichai/wasichai/issues/54)).

## Decision

A new optional module **wasichai-files** (starter `wasichai-spring-boot-starter-files`, switch
`wasichai.files.enabled`), built only on core's extension points, as wasichai-gis is.

1. **A file is a field.** `FILE` and `IMAGE` are `FieldTypeHandler`s (ADR-025). The column is a `uuid` naming a
   `stored_files` row (organization, object and field it was uploaded for, key, client name, sniffed type, size,
   SHA-256, uploader, time). The select list calls `stored_file_descriptor(column, organization_id)`, so a record,
   its audit `before`/`after` and every listener see `{id, name, contentType, size, sha256}` and never bytes. The
   field is an attribute, not a section: it belongs in the history, unlike a geometry (ADR-019).
2. **The bytes live in a `FileStore`** (`put`, `open`, `delete`), `@ConditionalOnMissingBean`: a local directory by
   default, an S3-compatible bucket with `wasichai.files.store=s3`. The S3 store is an auto-configuration of its own,
   on only with the AWS SDK on the classpath (the module has it `compileOnly`): an app that stores locally carries no
   SDK. Keys are `<organization id>/<file id>`, checked by shape in every store; the client's name is only shown back
   in `Content-Disposition`.
3. **An upload is a record write.** The route stores the row, then the bytes, then writes the field through the new
   `RecordService.patchViaApi`, the record API's own `PATCH` with the apiOnly door (ADR-040) and `If-Match` (ADR-051).
   No rule is repeated in the module: permission, field access, own records, read scope, appendOnly, requiresReason,
   guards, audit and listeners are the record write's. A refused write removes the row and the bytes; a cheap
   `UPDATE` permission check comes first, so a caller who may never write stores nothing. A download reads the record
   through `RecordService.get`, so it is refused exactly as reading the record or the field is.
4. **Only the writer's own upload attaches.** `StoredFileGuard` (a `RecordWriteGuard`, every write path) lets a file
   field take `null`, the file it holds, or a file the same user uploaded for that object and field within half of the
   cleanup delay. Any other value, from a body, an automation or the platform, is a `400` on the field. A create (and
   so an `appendOnly` object) gets its file from a staged upload, `POST /api/objects/{object}/files/{field}`.
5. **Validation before anything is stored.** The part is read while counting up to the field's `maxBytes` (default and
   ceiling `wasichai.files.max-bytes`); the type is sniffed from magic numbers (the declared type is believed only for
   zip-based office formats and text, and only when the bytes agree) and checked against the field's `contentTypes`
   (`IMAGE` defaults to PNG, JPEG, WebP). `400` on the field. Downloads: `IMAGE` inline, everything else
   `attachment`, always `nosniff`.
6. **Unreferenced files are deleted by a job**, not by the write that orphans them: `StoredFileCleanup` under the
   `ClusterLock` `wasichai.files.cleanup` (ADR-039), per organization found in `stored_files`, deletes the files no
   `FILE`/`IMAGE` column of that organization names and older than `wasichai.files.cleanup.delay`. It walks
   `stored_files` itself rather than the `TenantDirectory` (ADR-057): a deleted tenant's files must go too, which is
   why `stored_files` has no foreign key to organizations or objects.
7. **The type list is extended, not rewritten.** Core's `custom_fields_type_valid` is a list each type module re-adds
   (R4). The module appends `FILE` and `IMAGE` to whatever list is there, in a Flyway `afterMigrate` callback that runs
   on every start, and its migration runs after the other modules (`MODULE_ORDER + 1`), so gis's `GEOMETRY` and these
   two coexist in either install order on a fresh database.

## Consequences

- New routes and JSON, ADR-031 D47. The table, columns, function and the longer type list are known schema-parity
  deviations. No change for an app without the module.
- `RecordService.patchViaApi` is new public core API.
- With the module installed, every record write that sends attributes costs one indexed `custom_fields` read (the
  object's file fields), and one `stored_files` read per newly attached file.
- Adding wasichai-gis to a database that already has file fields fails at gis's `V1`, which rebuilds the type list
  and validates it. Its migration cannot change (ADR-026); install gis first.
- An upload is held in memory up to `max-bytes`. Streaming uploads to the store would need the hash and the type
  check to stream too: not needed for evidence-sized files.
- The platform and automations cannot attach files. The history keeps a replaced file's descriptor, its bytes go with
  the cleanup.
- The cleanup's query names every file column of an organization; each has an index for it.
