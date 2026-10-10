package wasichai.files

import wasichai.core.data.RecordReadMask
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.ObjectDefinition

// one FILE or IMAGE value about to reach a caller
data class FileRead(
    val caller: AuthenticatedUser,
    val definition: ObjectDefinition,
    val field: CustomField,
    // the record as stored, every field, so a classification the caller cannot read still decides.
    // in history, the state of that entry. null for a staged upload: no record holds it yet.
    val record: Map<String, Any?>?,
    // {id, name, contentType, size, sha256}, or what the policy before this one answered
    val descriptor: Map<String, Any?>
)

/**
 * SPI: what a caller reads of a FILE or IMAGE value (ADR-064). A record's readers may differ in what they may
 * know of its files: a classified record's file name often carries a person's name or id number.
 *
 * Answer the descriptor to expose: [FileRead.descriptor] unchanged, `name` replaced or left out, other metadata
 * left out. `id` always stays, whatever the answer: a write sends it back and the download finds the file by it.
 * The download's file name is the `name` the caller reads, `file` when they read none.
 *
 * Asked for a person or a service account, ADMIN included, wherever the descriptor reaches them: records, lists,
 * `rows`, write answers and idempotent replays, related records, workflow transitions, history and `/api/audit`,
 * the staged upload's answer and the download. Never for the platform or an automation. Collected from every
 * bean, in `@Order`; each gets what the one before answered. With none, every answer is what it was.
 */
fun interface FileDescriptorReadPolicy {
    suspend fun descriptor(read: FileRead): Map<String, Any?>
}

// the policies, run over every FILE and IMAGE value of a record core hands a caller
class FileDescriptorReadPolicies(
    private val policies: List<FileDescriptorReadPolicy>
) : RecordReadMask {
    override fun appliesTo(definition: ObjectDefinition): Boolean = policies.isNotEmpty() && definition.fields.any { it.isFile }

    override suspend fun mask(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
        stored: Map<String, Any?>,
        attributes: Map<String, Any?>
    ): Map<String, Any?> {
        val fields = definition.fields.filter { it.isFile && attributes[it.name] is Map<*, *> }
        if (fields.isEmpty()) return attributes
        val masked = LinkedHashMap(attributes)
        fields.forEach { field ->
            @Suppress("UNCHECKED_CAST")
            masked[field.name] = descriptor(FileRead(caller, definition, field, stored, attributes[field.name] as Map<String, Any?>))
        }
        return masked
    }

    // [read] through every policy. the id stays first and as stored
    suspend fun descriptor(read: FileRead): Map<String, Any?> {
        if (policies.isEmpty()) return read.descriptor
        val id = read.descriptor["id"]
        val answered = policies.fold(read.descriptor) { current, policy -> policy.descriptor(read.copy(descriptor = current)) }
        return linkedMapOf<String, Any?>("id" to id) + answered.filterKeys { it != "id" }
    }

    companion object {
        val NONE = FileDescriptorReadPolicies(emptyList())
    }
}
