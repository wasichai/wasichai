package wasichai.files

import wasichai.core.common.ValidationException
import wasichai.core.data.PlatformRun
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard
import java.time.Duration
import java.util.UUID

/**
 * A file field takes a new file only from its writer's own recent upload for that object and field
 * (ADR-0061). Every write path passes here (ADR-040), so no body, automation or platform job can point a
 * record at a file it did not upload: another record's, another tenant's, or one the cleanup is about to
 * delete. The value a field already holds passes unchanged (a PUT sends back what it read); null clears.
 *
 * The platform's writer is its run (ADR-068): an `asPlatform` block attaches only what that same block staged. An
 * automation has neither a user nor a run, so it attaches nothing.
 *
 * [maxAge]: how old an upload may be and still be attached. Below the cleanup delay, so the cleanup
 * never deletes a file a write is attaching.
 */
class StoredFileGuard(
    private val files: StoredFileRepository,
    private val maxAge: Duration,
    // the platform block the write runs in; a seam for tests
    private val platformRun: suspend () -> PlatformRun? = { PlatformRun.current() }
) : RecordWriteGuard {
    override suspend fun beforeWrite(change: RecordWrite) {
        if (change.kind != RecordChangeKind.CREATED && change.kind != RecordChangeKind.UPDATED) return
        val attributes = change.attributes?.takeIf { it.isNotEmpty() } ?: return
        val fields = files.fileFieldNames(change.organizationId, change.objectId).filter { it in attributes }
        if (fields.isEmpty()) return
        // field -> the id it is about to take, when that is not the one it holds
        val incoming =
            fields
                .mapNotNull { name ->
                    val next = idOrRefuse(name, attributes[name]) ?: return@mapNotNull null
                    val held = runCatching { fileIdOf(change.before?.get(name)) }.getOrNull()
                    if (next == held) null else name to next
                }
        if (incoming.isEmpty()) return
        val userId = change.userId
        // no user: the platform's run, when the write is its own and in its organization
        val run = if (userId == null) platformRun()?.takeIf { it.organizationId == change.organizationId } else null
        incoming.forEach { (name, id) ->
            val own =
                when {
                    userId != null -> id in files.uploadedBy(change.organizationId, userId, change.objectId, name, listOf(id), maxAge)
                    run != null -> id in files.stagedBy(change.organizationId, run.id, change.objectId, name, listOf(id), maxAge)
                    else -> false
                }
            if (!own) {
                throw ValidationException(
                    "Invalid file for '$name'",
                    name,
                    "must be a file you uploaded for this field, through the files routes"
                )
            }
        }
    }

    private fun idOrRefuse(
        name: String,
        value: Any?
    ): UUID? =
        try {
            fileIdOf(value)
        } catch (_: IllegalArgumentException) {
            throw ValidationException("Invalid file", name, "must be the id of an uploaded file")
        }
}
