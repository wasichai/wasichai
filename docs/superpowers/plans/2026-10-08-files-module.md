# Files module (FILE and IMAGE field types) — implementation plan

Issue [#54](https://github.com/wasichai/wasichai/issues/54). Decision record: ADR-061, ADR-031 D47.

## Goal

A new optional module `wasichai-files` (starter `wasichai-spring-boot-starter-files`, switch `wasichai.files.enabled`)
that adds the field types `FILE` and `IMAGE` through `FieldTypeHandler` (ADR-025), a storage SPI `FileStore` with a
local-directory and an S3-compatible implementation, upload and download routes that ride on `RecordService`, and an
orphan cleanup job under `ClusterLock` (ADR-039).

## Design

- **Column**: the field's column is `uuid`, the id of a `stored_files` row. The module's migration (`V1__files.sql`,
  ADR-026, order `MODULE_ORDER + 1` so it runs after gis on a fresh database) creates `stored_files` (no foreign key on
  `organization_id` or `object_id`: a deleted tenant's or object's rows must stay for the cleanup to find their bytes),
  the attribute columns `file_max_bytes` and `file_content_types`, and `stored_file_descriptor(uuid, uuid)`, the
  function the select list calls so the record JSON carries `{id, name, contentType, size, sha256}`.
- **Type list**: core's `custom_fields_type_valid` gets `FILE` and `IMAGE` appended to whatever list it holds (a `DO`
  block that reads the constraint), in V1 and again in an `afterMigrate` callback, so a module that later rebuilds the
  list (gis V1) does not lose them.
- **Writes**: the value is set only from a file the same user uploaded for that object and field, recently. A
  `RecordWriteGuard` (`StoredFileGuard`) enforces it on every write path; an unchanged id passes (PUT round-trip),
  null clears. Upload routes store first (row, then bytes), then write through `RecordService.patchViaApi` (new public
  core method: `patch` with the apiOnly door and the parsed If-Match list), so permissions, field access,
  own_records_only, read scope, write guards, appendOnly, requiresReason, If-Match, audit and listeners all apply. A
  refused write deletes what was stored at once; the cleanup job is the backstop.
- **Validation**: size cap (`maxBytes`, default and ceiling `wasichai.files.max-bytes`) while reading, content type
  sniffed from the bytes (magic numbers), allow-list `contentTypes` (IMAGE defaults to png/jpeg/webp). `400` on the
  field, nothing stored.
- **Reads**: `GET .../files/{field}` reads the record through `RecordService.get` (READ, field access, owner, scope),
  then streams the bytes. `IMAGE` inline, everything else `attachment`; always `X-Content-Type-Options: nosniff`.
- **Staged upload** for creates (and append-only objects): `POST /api/objects/{object}/files/{field}` answers a
  descriptor; the record create then names its id.
- **Cleanup**: `StoredFileCleanup` (SmartLifecycle, `wasichai.files.cleanup.interval`, `.delay`), under the
  `ClusterLock` `wasichai.files.cleanup`, per organization found in `stored_files` (deleted tenants included): rows older
  than the delay that no FILE/IMAGE column of that organization names; bytes first, then the row.

## Files

- `wasichai-files/`: `build.gradle.kts`, `FileFieldType`, `FileFields`, `FileSettings`, `ContentSniffer`,
  `StoredFileRepository`, `FileStore` + `LocalFileStore` + `S3FileStore`, `StoredFileGuard`, `FileService`,
  `FileController`, `StoredFileCleanup`, `autoconfigure/` (properties, main and S3 auto-configurations),
  `db/wasichai/files/V1__files.sql`, `afterMigrate__file_types.sql`.
- `starters/wasichai-spring-boot-starter-files/`.
- Core: `RecordService.patchViaApi`. `CoreArchitectureTest`: files is a module.
- Integration tests: `filesOnly` slice, full app gets the starter, `ModuleRoutes`, `AllModulesWiringTest`,
  `ModuleBoundariesTest`, `SchemaParityTest` deviations.
- Release guard list, version catalog (AWS SDK, testcontainers-minio).

## Tests

- Unit: `ContentSnifferTest`, `FileFieldTypeTest`, `LocalFileStoreTest`, `StoredFileGuardTest`,
  `WasichaiFilesAutoConfigurationTest`, `FilesMigrationSqlTest`.
- Integration (module, tagged `integration`): `FilesApiTest` (every acceptance item on the local store),
  `S3FileStoreTest` (MinIO container, CI only).

## Docs

HISTORY, ADR-061 + index, ADR-031 D47, `docs/modules/files.md`, `docs/modules/README.md`,
`docs/domain/metadata-model.md`, `docs/api/rest.md`, `docs/guides/build-your-app.md`, `docs/development/releasing.md`.
