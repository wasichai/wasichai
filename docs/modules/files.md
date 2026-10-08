# Files module

An app that installs this module can give an object `FILE` and `IMAGE` fields: photos, scanned forms, signed PDFs.
A file has the same permissions, ownership, write rules and history as the record that holds it, because every
upload is a write of that record ([ADR-061](../adr/0061-file-and-image-fields-with-a-storage-spi.md)).

## Install

```kotlin
implementation("wasichai:wasichai-spring-boot-starter-files")
// only for wasichai.files.store=s3
implementation("software.amazon.awssdk:s3:2.55.0")
```

No frontend package yet (planned). Runs on plain PostgreSQL.

## What it adds

- The field types `FILE` and `IMAGE`. The column is a `uuid` (indexed) naming a row of the module's table
  `stored_files` (`id`, `organization_id`, `object_id`, `field_name`, `object_key`, `file_name`, `content_type`,
  `size_bytes`, `sha256`, `created_by`, `created_at`). The record JSON, the audit trail and record listeners see
  `{ "id", "name", "contentType", "size", "sha256" }`; the bytes live in a `FileStore`, never in the database.
- Field settings, next to `type` when the field is created (fixed afterwards, like the type): `maxBytes` (1 to
  `wasichai.files.max-bytes`, which is also the default) and `contentTypes`, an allow-list of media types or
  `type/*` (none: any type for `FILE`; `image/png`, `image/jpeg`, `image/webp` for `IMAGE`). A file field is never
  `unique`, never has a default, and cannot be filtered or sorted on.
- Three routes ([rest.md](../api/rest.md#files)):

| Method | Path | What |
|---|---|---|
| POST | `/api/objects/{object}/records/{id}/files/{field}` | replace the field's file (multipart part `file`) |
| GET | `/api/objects/{object}/records/{id}/files/{field}` | stream it; `IMAGE` inline, others as an attachment |
| POST | `/api/objects/{object}/files/{field}` | stage an upload for a record still to be created |

- A `RecordWriteGuard` (`StoredFileGuard`): on every write path, a file field takes only `null`, the file it already
  holds, or the writer's own upload for that object and field, made within half of `wasichai.files.cleanup.delay`.
  No body, automation or platform job can point a record at another record's or another tenant's file.
- `StoredFileCleanup`: every `wasichai.files.cleanup.interval`, on one replica (`ClusterLock`
  `wasichai.files.cleanup`, ADR-039), deletes the files no `FILE`/`IMAGE` column of their organization names any more
  and older than `wasichai.files.cleanup.delay`: replaced or cleared ones, those of deleted records, fields, objects or
  tenants, and staged uploads never attached. Bytes first, then the row.

## How a write goes

`POST …/records/{id}/files/{field}` checks the caller has `UPDATE` on the object, reads the part while counting (one
byte over the cap is a `400`), sniffs the type from the bytes (PNG, JPEG, GIF, WebP and PDF by magic number; zip-based
office files and plain text or CSV only when the bytes agree with the declared type; anything else is
`application/octet-stream`), checks the allow-list, then stores: the `stored_files` row, the bytes under
`<organization id>/<file id>`, and a `PATCH` of the field through `RecordService.patchViaApi`. The `PATCH` applies
every record rule (permission, field access, owner, read scope, `apiOnly`, `appendOnly`, `requiresReason`,
`If-Match`, guards) and writes the audit row and tells the listeners. When it refuses, the row and the bytes are
removed at once; what a crash leaves, the cleanup removes.

An `appendOnly` object never takes an update, so its records get their file at creation: stage the upload, then send
its `id` as the field's value in the create.

## Stores

`FileStore` (`put(key, bytes, contentType)`, `open(key)`, `delete(key)`) is the SPI. Declare your own bean to replace
the module's (`@ConditionalOnMissingBean`).

- `local` (default): a directory, `wasichai.files.local.path`. Every replica must see the same directory.
- `s3`: an S3-compatible bucket through the AWS SDK's async client: AWS S3, MinIO, Ceph. Only when the app adds
  `software.amazon.awssdk:s3`; without it, `store=s3` fails at startup.

## Configuration

| Property | Default | |
|---|---|---|
| `wasichai.files.enabled` | `true` | `false`: no types, routes, guard, cleanup or migration |
| `wasichai.files.max-bytes` | `10MB` | default `maxBytes` and its ceiling; the upload is held in memory while checked |
| `wasichai.files.store` | `local` | `local` or `s3` |
| `wasichai.files.local.path` | `wasichai-files` | relative to the working directory |
| `wasichai.files.s3.bucket` | none | required with `store=s3` |
| `wasichai.files.s3.endpoint` | AWS | set for MinIO and other services |
| `wasichai.files.s3.region` | `us-east-1` | |
| `wasichai.files.s3.access-key`, `secret-key` | SDK default chain | both or neither |
| `wasichai.files.s3.path-style-access` | `false` | `true` for MinIO |
| `wasichai.files.s3.prefix` | none | keys go under it |
| `wasichai.files.cleanup.interval` | `1h` | `0s`: never |
| `wasichai.files.cleanup.delay` | `24h` | at least `2s`; an upload attaches within half of it |

## Schema

Migration `V1__files.sql` (history table `flyway_history_files`, ADR-026; order `MODULE_ORDER + 1`, after gis):
`stored_files` (no foreign key on `organization_id` or `object_id`, so a deleted tenant's or object's rows stay for the
cleanup), `custom_fields.file_max_bytes` and `file_content_types`, and the function `stored_file_descriptor(uuid,
uuid)` the record select calls. The Flyway callback `afterMigrate__file_types.sql` appends `FILE` and `IMAGE` to
core's `custom_fields_type_valid` list, whatever it holds, on every start.

## Known limitations

- An upload is read into memory (at most `max-bytes`) before it is checked and stored. Spring's multipart reader may
  spool a large part to disk before that.
- Adding wasichai-gis to a database that already has `FILE`/`IMAGE` fields fails: gis's `V1` rebuilds the type list
  without them and PostgreSQL refuses the rows. Install gis first, or before creating file fields.
- Only a person can upload: the platform (`asPlatform`) and automations cannot attach a file.
- The history keeps a replaced file's descriptor; its bytes go with the cleanup.
