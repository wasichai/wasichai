package wasichai.files

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import reactor.core.publisher.Flux
import wasichai.core.common.Actions
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

// an upload as it arrived: the client's name and type are only hints, the bytes are the truth
class FileUpload(
    val fileName: String?,
    val declaredType: String?,
    val content: Flux<DataBuffer>
)

// a stored file, ready to stream, and the type of the field that holds it
class FileDownload(
    val file: StoredFile,
    val fieldType: wasichai.core.metadata.FieldType,
    val content: Flux<DataBuffer>,
    // what Content-Disposition names it: the name its caller reads (ADR-064)
    val fileName: String = file.fileName
)

/**
 * Uploads and downloads of FILE and IMAGE fields (ADR-0061). A write is a [RecordService] write of that
 * one field, so every rule a record write has applies to it unchanged: permission, field access, owner,
 * read scope, apiOnly, appendOnly, requiresReason, If-Match, guards, audit and listeners. A read is a
 * [RecordService] read of the record. Bytes are stored before the write and removed again when it is
 * refused; the cleanup job catches what a crash leaves behind.
 */
class FileService(
    private val records: RecordService,
    private val metadata: MetadataService,
    private val currentUser: CurrentUser,
    private val files: StoredFileRepository,
    private val store: FileStore,
    private val handlers: Map<String, FileFieldType>,
    // the app's FileDescriptorReadPolicy beans (ADR-064). defaulted: code that builds this service itself keeps compiling
    private val policies: FileDescriptorReadPolicies = FileDescriptorReadPolicies.NONE
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Replaces the file of [fieldName] on the record: PATCH of that field with a new upload. */
    suspend fun replace(
        objectName: String,
        recordId: UUID,
        fieldName: String,
        upload: FileUpload,
        reason: String?,
        expectedUpdatedAt: List<Instant>?
    ): RecordResponse {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        val field = fileField(definition, fieldName)
        // checked again by the write: here so a caller who may never write stores no bytes
        currentUser.requirePermission(user, Actions.UPDATE, definition.obj.id)
        val stored = store(user, definition, field, upload)
        return try {
            records.patchViaApi(objectName, recordId, RecordRequest(mapOf(field.name to stored.id)), reason, expectedUpdatedAt)
        } catch (e: Throwable) {
            discard(stored)
            throw e
        }
    }

    /**
     * Stores an upload for [fieldName] of a record still to be created (or of an append-only object):
     * its id, sent as the field's value in the create, attaches it. Unattached, the cleanup removes it.
     */
    suspend fun stage(
        objectName: String,
        fieldName: String,
        upload: FileUpload
    ): FileDescriptor {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        val field = fileField(definition, fieldName)
        currentUser.requirePermission(user, Actions.CREATE, definition.obj.id)
        return store(user, definition, field, upload).descriptor()
    }

    /** [stage], answered as its uploader reads it: through the read policies, with no record (ADR-064). */
    suspend fun stageAsRead(
        objectName: String,
        fieldName: String,
        upload: FileUpload
    ): Map<String, Any?> {
        val staged = stage(objectName, fieldName, upload)
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        return policies.descriptor(FileRead(user, definition, fileField(definition, fieldName), null, staged.toMap()))
    }

    /** The file [fieldName] of the record holds, if the caller may read that field of that record. */
    suspend fun open(
        objectName: String,
        recordId: UUID,
        fieldName: String
    ): FileDownload {
        val user = currentUser.require()
        // READ, field access, owner and read scope: the record as its caller sees it, or a 403/404
        val record = records.get(objectName, recordId)
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        val field = fileField(definition, fieldName)
        // a field the caller cannot read is left out of the record: it is not there for them
        if (!record.attributes.containsKey(field.name)) throw noSuchField(definition, fieldName)
        val value = record.attributes[field.name]
        val id = fileIdOf(value) ?: throw NotFoundException("Record $recordId has no file in '${field.name}'")
        val file = files.find(user.organizationId, id) ?: throw NotFoundException("Record $recordId has no file in '${field.name}'")
        // the name the caller reads, as the read policies left it (ADR-064): none read, none sent
        val name = (value as? Map<*, *>)?.get("name") as? String
        return FileDownload(file, field.type, store.open(file.objectKey), safeName(name))
    }

    // the upload, checked against the field and stored: the row first, so a crash between the two leaves
    // a row the cleanup finds, never bytes nobody knows of
    private suspend fun store(
        user: AuthenticatedUser,
        definition: ObjectDefinition,
        field: CustomField,
        upload: FileUpload
    ): StoredFile {
        val handler = handlers.getValue(field.type.name)
        val maxBytes = handler.maxBytes(field)
        val bytes = read(field, upload.content, maxBytes)
        val contentType = ContentSniffer.detect(bytes, upload.declaredType)
        val allowList = handler.contentTypes(field)
        if (!ContentSniffer.allowed(contentType, allowList)) {
            throw ValidationException(
                "File type $contentType is not allowed in '${field.name}'",
                field.name,
                "must be one of ${allowList.orEmpty().joinToString(", ")}"
            )
        }
        val id = UUID.randomUUID()
        val file =
            files.insert(
                StoredFile(
                    id = id,
                    organizationId = user.organizationId,
                    objectId = definition.obj.id,
                    fieldName = field.name,
                    objectKey = FileStore.keyOf(user.organizationId, id),
                    fileName = safeName(upload.fileName),
                    contentType = contentType,
                    sizeBytes = bytes.size.toLong(),
                    sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    createdBy = user.userId,
                    createdAt = null
                )
            )
        try {
            store.put(file.objectKey, bytes, contentType)
        } catch (e: Throwable) {
            withContext(NonCancellable) { files.delete(file.organizationId, file.id) }
            throw e
        }
        return file
    }

    // never more than the cap in memory: the join stops one byte past it
    private suspend fun read(
        field: CustomField,
        content: Flux<DataBuffer>,
        maxBytes: Long
    ): ByteArray {
        val joined =
            try {
                DataBufferUtils.join(content, maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).awaitSingle()
            } catch (_: DataBufferLimitException) {
                throw ValidationException("File too large for '${field.name}'", field.name, "must be at most $maxBytes bytes")
            } catch (_: NoSuchElementException) {
                throw ValidationException("Empty file for '${field.name}'", field.name, "must not be empty")
            }
        try {
            val bytes = ByteArray(joined.readableByteCount())
            joined.read(bytes)
            if (bytes.isEmpty()) throw ValidationException("Empty file for '${field.name}'", field.name, "must not be empty")
            return bytes
        } finally {
            DataBufferUtils.release(joined)
        }
    }

    // best effort: what is left, the cleanup takes
    private suspend fun discard(file: StoredFile) {
        withContext(NonCancellable) {
            try {
                store.delete(file.objectKey)
                files.delete(file.organizationId, file.id)
            } catch (e: Exception) {
                log.warn("Could not discard refused upload {}: {}", file.id, e.message)
            }
        }
    }

    private fun fileField(
        definition: ObjectDefinition,
        name: String
    ): CustomField = definition.fields.firstOrNull { it.name == name && it.isFile } ?: throw noSuchField(definition, name)

    private fun noSuchField(
        definition: ObjectDefinition,
        name: String
    ) = NotFoundException("Object '${definition.obj.name}' has no file field '$name'")

    companion object {
        private const val MAX_NAME = 255

        // the client's name, for Content-Disposition only: no path, no control character, bounded
        internal fun safeName(raw: String?): String {
            val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            val clean = base.filterNot { Character.isISOControl(it) }.trim().take(MAX_NAME)
            return clean.ifEmpty { "file" }
        }
    }
}
