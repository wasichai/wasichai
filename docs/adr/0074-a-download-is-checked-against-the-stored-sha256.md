# ADR-074: A download is checked against the stored sha256

**Status**: accepted · 2026-11-01 · extends [ADR-061](0061-file-and-image-fields-with-a-storage-spi.md)

## Context

wasichai-files takes the sha256 of every upload and keeps it in `stored_files`, but the download streamed the store's
bytes without looking at it. An object changed or damaged in the store (an S3-compatible bucket other credentials can
write to, a disk) was served as if it were the file; only a client that knew the descriptor could notice. The second
user is wasichai/elearning-backend, which serves course resources, student submissions and generated HTML and PDF
from an S3-compatible store and may not reimplement the download (finding L6 of its security review,
[wasichai#144](https://github.com/wasichai/wasichai/issues/144)).

## Decision

**The bytes are checked while they stream.** `FileService.open` wraps the store's `Flux` in a `DigestCheck`: every
buffer feeds a sha256 and a byte count, and the newest buffer is held back until the next one arrives. At the end the
size and the digest are compared with the row's. A match lets the last buffer go. A mismatch, or a byte past the
stored size (the read stops there), ends the stream with `StoredFileDamagedException`. The client never gets the
whole file: a small one, a single buffer, is a `500` before anything was sent; a larger one ends short of its
`Content-Length`, which every HTTP client reports as an incomplete response. The check costs one sha256 pass over
what is read anyway: no second read, nothing more in memory than one buffer.

**A mismatch is recorded**, before the error, with the client's hang-up unable to stop it:
- an `ERROR` log line with the key, the sizes and both digests;
- `stored_files.damaged_at` (`V3__damaged.sql`), stamped by the first failed check and kept by later ones;
- an audit row on the admin trail (ADR-049): `object_name` `admin:stored-file`, `record_id` the file's id,
  `UPDATE`, `before` `{damaged, size, sha256}` as stored (`damaged` true once an earlier check failed), `after`
  `{damaged: true, size, sha256}` as read (`sha256` null when the read stopped past the size), the downloader as
  `user_id`, source `files:integrity`, and the reason `integrity check failed serving <object>/<record>/<field>`.
  Every failed download writes one.

The admin trail and not the record's history: nothing about the record changed, and digests and object keys are an
operator's business. `MANAGE_ORGANIZATION` reads it, through `/api/audit?objectName=admin:stored-file`. The name is
the module's own; core's `AdminEntity` does not list it, as core knows no module (rule 3), and `AuditService` takes
any object name.

**The mark does not block.** Each download is checked again. Bytes put back in the store are served again; the stamp
stays as history. A file with no good copy fails every time and is logged and audited every time.

**`Repr-Digest`** (RFC 9530) on every download, `sha-256=:<base64>:`, the stored digest, so a client can check the
bytes itself. It is the representation's digest, so it holds whatever content coding a proxy adds.

**`wasichai.files.verify-on-download`**, default `true`. `false` streams unchecked as before; `Repr-Digest` stays.

## Consequences

- A damaged file is never served whole and leaves a trace an operator can query: the log, `damaged_at` and the admin
  trail.
- With a `FileStore` of the app's own, the check applies too: it sits in `FileService`, not in the stores.
- A large damaged file still sends most of its bytes before the cut. They are not the file, and the client is told so
  by the short body; holding the whole file back would cost memory the module never spends on a download.
- REST change: additive. The download gains `Repr-Digest`; a damaged file, which was `200`, is now a `500` or a cut
  response. ADR-031 D53.
- Pinned by `DigestCheckTest`, `FilesMigrationSqlTest`, `WasichaiFilesAutoConfigurationTest` and the integration test
  `FilesApiTest`.
