package wasichai.files

import wasichai.core.common.ValidationException
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
 * [maxAge]: how old an upload may be and still be attached. Below the cleanup delay, so the cleanup
 * never deletes a file a write is attaching.
 */
class StoredFileGuard(
    private val files: StoredFileRepository,
    private val maxAge: Duration
) : RecordWriteGuard {
    override suspend fun beforeWrite(change: RecordWrite) {
        if (change.kind != RecordChangeKind.CREATED && change.kind != RecordChangeKind.UPDATED) return
        val attributes = change.attributes?.takeIf { it.isNotEmpty() } ?: return
        val fields = files.fileFieldNames(change.objectId).filter { it in attributes }
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
        incoming.forEach { (name, id) ->
            val own =
                userId != null &&
                    id in files.uploadedBy(change.organizationId, userId, change.objectId, name, listOf(id), maxAge)
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
