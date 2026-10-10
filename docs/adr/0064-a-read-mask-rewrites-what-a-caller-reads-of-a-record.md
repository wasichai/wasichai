# ADR-064: A read mask rewrites what a caller reads of a record; files hide a file's name through it

**Status**: accepted · 2026-10-10 · extends [ADR-048](0048-a-read-scope-narrows-what-a-caller-reads.md) and
[ADR-061](0061-file-and-image-fields-with-a-storage-spi.md)

## Context

A `FILE` or `IMAGE` value reads as `{id, name, contentType, size, sha256}` for every caller who may read the field: in
the record, the list, related records, the answer of a write and the history ([ADR-061](0061-file-and-image-fields-with-a-storage-spi.md)).
Field permissions decide the whole field, per role; read scopes ([ADR-048](0048-a-read-scope-narrows-what-a-caller-reads.md))
decide the whole record. Nothing decides part of a value, per record.

SGSPE keeps evidence files classified (CONFIDENTIAL, PERSONAL DATA). Their original names often carry a person's name
or id number. The download already needs a dedicated action, but every reader of the evidence record still sees the
name. The app's workaround, a neutral name at upload, misses files uploaded before it or straight through the API
([#90](https://github.com/wasichai/wasichai/issues/90)). The issue asks for a hook, `FileDescriptorReadPolicy`, that
gets the caller, the object, the field and the stored record and answers the descriptor to expose, decided per record,
everywhere the descriptor is emitted, with no change when no hook is registered.

The descriptor is built in `FileFieldType.fromDatabase`, which sees one column and no caller, and core decides where a
record leaves for a caller. `wasichai-core` never depends on a module (rule 3), so the files module cannot reach those
places by itself.

## Decision

**Core: `RecordReadMask`, an SPI over a record's attributes.** `wasichai.core.data.RecordReadMask`:

```kotlin
interface RecordReadMask {
    fun appliesTo(definition: ObjectDefinition): Boolean = true

    suspend fun mask(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
        stored: Map<String, Any?>,
        attributes: Map<String, Any?>
    ): Map<String, Any?>
}
```

`attributes` is what the caller would get, their readable fields only; `stored` is the same record as stored, every
field, so the decision may rest on a field the caller cannot read (a classification). The mask answers the keys of
`attributes`: a value replaced is what the caller reads, a key left out is a field they do not get, a key added is
dropped, so a mask can never hand out a field the field permissions hide. Every bean is collected in `@Order` by one
non-replaceable `RecordReadMasks`, each mask getting what the one before answered (as `RecordReadScopes`).

It is asked wherever core hands a record's `attributes` to a caller: `RecordService` get, list and `rows` (so GIS
features and the agent's tools), the answers of create, update and patch (so the files module's upload answer and an
idempotent replay, which replays the stored answer), related records, the workflow module's transition answer, and each
`before` and `after` of `/api/audit` and a record's history. Asked for a person or a service account, `ADMIN` included:
hiding something from an administrator is a legitimate rule, and the mask can let `ADMIN` through in one line. Never for
the platform (`asPlatform`) or an automation, as for every other read rule. Sections are not masked: no section field
needs it yet (rule 10).

**Where `stored` comes from.** A write already holds the record whole (ADR-025), and so does an audit state (the log
stores every field). A read projected to the caller's readable fields re-reads the same records by id with every
field, once per page, before masking; a caller who reads every field pays nothing. The re-read applies no read rule:
it only completes rows the caller already read, and it is never answered. It runs only when a mask applies to the
object (`appliesTo`), so an app with no mask, or a mask on other objects, sees the same SQL as before.

Audit sits below data in core's DAG, so it asks through a port of its own, `AuditStateMask`, that `RecordReadMasks`
implements, as `AuditRecordScope` for read scopes. Each audit state is masked on itself before the field permissions
narrow it: the history shows a reclassified record's name as each state's classification decides.

**Files: `FileDescriptorReadPolicy`, the issue's SPI, over the mask.** `wasichai.files.FileDescriptorReadPolicy`:

```kotlin
fun interface FileDescriptorReadPolicy {
    suspend fun descriptor(read: FileRead): Map<String, Any?>
}

data class FileRead(caller, definition, field, record: Map<String, Any?>?, descriptor: Map<String, Any?>)
```

The module turns every policy bean into one `RecordReadMask` (`FileDescriptorReadPolicies`): for each `FILE` or `IMAGE`
value the caller reads, the policies run in `@Order` on the descriptor and answer what to expose: unchanged, `name`
replaced or left out, other metadata left out. `id` always stays, first and as stored, whatever a policy answers: a
write sends it back and the download finds the file by it. With no policy the mask applies to no object, so nothing is
re-read or rewritten. The staged upload's answer goes through the policies too, with `record` null: no record holds the
file yet. The download names the file (`Content-Disposition`) with the `name` the caller reads, `file` when they read
none, so a hidden name does not come back as a header.

**Why a core SPI over the attributes, not a hook on the field type.** A per-field hook in `FieldTypeHandler` would need
the caller and the stored record at `fromDatabase`, which runs inside the store, below every rule. A mask at the places
a record leaves core keeps the store unaware of callers, works for any module's values, and puts the decision next to
the field permissions it complements. Field permissions on the descriptor's subfields (the issue's alternative) would
decide per role only, never per record, which is what SGSPE needs.

**No ADR-031 entry.** With no mask and no policy, every answer, every query and the download's file name are what they
were.

## Consequences

- An app hides a classified record's file name from a reader with one bean; a reader with the right, or `ADMIN` if the
  policy says so, still reads it, from the same routes.
- A projected read of an object a mask applies to costs one more query per page or record.
- A mask runs per record and per page: it must be cheap, or cache per request what it looks up, as a read scope does.
- Not reached: GeoServer layers (they read the tables, ADR-048), wasichai-documents (it renders as the platform),
  notification texts, and record listeners and automations, which see the record whole by design (ADR-016). An app that
  prints a file name into a document or a notification decides there.
- An idempotent replay answers the bytes stored with the first answer (ADR-058): masked as the first caller read it,
  for that same caller.
- `RecordService`, `RelatedRecordService`, `AuditQueryService`, `WorkflowService` and `FileService` take the masks or
  policies as a new, defaulted constructor argument, so code that builds them itself keeps compiling and masks nothing.
- Tested by `RecordReadMasksTest`, `RecordServiceReadMaskTest`, `AuditQueryServiceMaskTest`,
  `FileDescriptorReadPoliciesTest` and the integration test `FileDescriptorReadPolicyApiTest` (a reader without the
  right never receives the original name from the record, the list, related records, history, `/api/audit`, the
  download or their own upload's answer; `ADMIN` does).
