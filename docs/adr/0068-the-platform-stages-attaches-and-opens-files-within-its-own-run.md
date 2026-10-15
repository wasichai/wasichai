# ADR-068: The platform stages, attaches and opens files within its own run

**Status**: accepted · 2026-10-15 · extends [ADR-061](0061-file-and-image-fields-with-a-storage-spi.md) and
[ADR-039](0039-background-work-runs-as-the-platform-with-a-cluster-lock.md)

## Context

ADR-061 let only a person upload: `FileService` needed a user, and `StoredFileGuard` took a new file only from the
writer's own upload, which a write with no user never has. SGSPE generates report drafts (DOCX, PDF, a frozen JSON
model) in a scheduled job, inside `RecordService.asPlatform` under a `ClusterLock`. It wants to keep them in `FILE`
fields like any other file of the tenant, and to read the photos people attached earlier
([#111](https://github.com/wasichai/wasichai/issues/111)). Without that, the app needs a second store with its own keys
and its own cleanup.

A write with no user is the platform or an automation (`RecordWrite.userId` is null for both). "Accept any recent file
with no uploader" would let an automation rule with a literal id, or another platform job of the same organization,
attach a draft that is not theirs. That is the hole ADR-061's guard closes. The source label does not tell them apart
either: `ChangeOrigin.within` is public and anyone can say `platform`.

## Decision

**Each `asPlatform` block is a run.** `PlatformCaller.run` puts a `PlatformRun(organizationId, id)` in the Reactor
context, with a fresh id, in place of the bare organization. A nested block gets its own run, and the innermost wins.
`PlatformRun.current()` is the one public way to read it. It is null outside a block, and null under a security
context, as the platform itself is (ADR-039). It is read only: nothing outside `PlatformCaller` builds one into a
context, so it does not widen who can be the platform.

**The platform's writer is its run.**
- `FileService.stage` inside a block stores the file with `created_by` null and the run in a new
  `stored_files.platform_run` column (`V2__platform_run.sql`). A check keeps it to one uploader, a person or a run.
  No permission check applies, as for every platform call.
- `StoredFileGuard`, on a write with no user, takes a new file only when the write runs in a block of the same
  organization and that run staged the file for that object and field, within the same age as a person's upload
  (`StoredFileRepository.stagedBy`). A person still takes only their own upload. An automation, with no user and no
  run, still attaches nothing.
- `FileService.open` inside a block opens any file of the block's organization: the record is read as the platform
  reads it.

**Not done.** `replace` and `stageAsRead` serve the routes and stay for a person. Automations get no way to attach a
file: they have no bytes of their own to stage. No REST change.

## Consequences

- A job can stage a file and attach it in the same block: the record shows the descriptor, the file row has no
  uploader, and the audit row says the job's source with a null user. Replaced and unattached files go with
  `StoredFileCleanup` as before: it looks at references, not at uploaders.
- Stage and attach must happen in one `asPlatform` block. A file staged in one block and attached in another is
  refused, and the cleanup takes it later.
- Organizations stay apart: the run carries its organization, and every `stored_files` query filters by it.
- A file the platform staged has no `created_by`. To know which job made it, read the audit row of the write that
  attached it.
- Pinned by `StoredFileGuardTest`, `RecordServicePlatformTest`, `FilesMigrationSqlTest` and the integration test
  `PlatformFilesTest`.
- No new ADR-031 entry: no new route or JSON, and the files themselves are D47.
