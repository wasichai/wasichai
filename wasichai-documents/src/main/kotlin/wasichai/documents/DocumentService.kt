package wasichai.documents

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import wasichai.core.common.Actions
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordRow
import wasichai.core.data.RecordStore
import wasichai.core.data.RelatedRecordService
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

// how many related rows a table prints. a document is read, not scrolled: a relationship with
// thousands of rows is a report, and a report is not this.
private const val RELATED_ROWS = 200

@Service
class DocumentService(
    private val documents: DocumentRepository,
    private val counters: DocumentCounterRepository,
    private val types: DocumentTypeRepository,
    private val metadata: MetadataService,
    private val related: RelatedRecordService,
    private val store: RecordStore,
    private val currentUser: CurrentUser,
    private val audit: AuditService
) {
    suspend fun forRecord(
        objectName: String,
        recordId: UUID
    ): List<Document> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        return documents.findForRecord(user.organizationId, definition.obj.id, recordId)
    }

    suspend fun byId(id: UUID): Document {
        val user = currentUser.require()
        val document = documents.findById(user.organizationId, id) ?: throw NotFoundException("Document $id does not exist")
        currentUser.requirePermission(user, Actions.READ, document.objectId)
        return document
    }

    // annotated as well as issue(), and not by accident: a call from inside this class does not go
    // through the proxy, so without this there is no transaction at all -- the advisory lock would
    // be taken and let go in the same breath, and the counter would hand numbers to work that then
    // failed. the concurrency test is what found that.
    @Transactional
    suspend fun issueAsUser(
        objectName: String,
        recordId: UUID,
        typeName: String
    ): Document {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        return issue(user.organizationId, objectName, recordId, typeName, user.userId, user.email)
    }

    // the one path both callers take. `issuedBy` is null when the platform issues it -- the
    // automation runner has no user to name (ADR-016) -- which is also why nothing here asks
    // CurrentUser anything.
    //
    // the record is read WITHOUT the caller's field permissions, on purpose: a document says what
    // it says, and the control is on seeing the document at all. ADR-023.
    @Transactional
    suspend fun issue(
        organizationId: UUID,
        objectName: String,
        recordId: UUID,
        typeName: String,
        issuedBy: UUID?,
        issuedByEmail: String?
    ): Document {
        val definition = metadata.loadDefinition(organizationId, objectName.trim().lowercase())
        val type =
            types.findByName(definition.obj.id, typeName.trim().lowercase())
                ?: throw NotFoundException("Document type '$typeName' does not exist")
        // serialise this (type, record) before anything else: the number, the archive and the
        // insert all have to happen without another issue slipping between them
        documents.lockRecordType(type.id, recordId)
        val record =
            store.findById(definition, organizationId, recordId)
                ?: throw NotFoundException("Record $recordId does not exist")

        val issuedAt = Instant.now()
        val year = issuedAt.atZone(ZoneOffset.UTC).year
        val sequence = counters.next(type.id, year)
        val serial = "$year-${sequence.toString().padStart(3, '0')}"
        val number = "${type.prefix}-$serial"

        val platform =
            mapOf(
                PlatformValue.TODAY.key to DateTimeFormatter.ISO_LOCAL_DATE.format(issuedAt.atZone(ZoneOffset.UTC)),
                PlatformValue.NOW.key to DateTimeFormatter.ISO_INSTANT.format(issuedAt),
                PlatformValue.USER.key to (issuedByEmail ?: ""),
                PlatformValue.RECORD_ID.key to recordId.toString(),
                PlatformValue.DOCUMENT_NAME.key to type.label,
                PlatformValue.DOCUMENT_PREFIX.key to type.prefix,
                PlatformValue.DOCUMENT_SERIAL.key to serial,
                PlatformValue.DOCUMENT_NUMBER.key to number
            )

        val snapshot =
            DocumentSnapshot(
                template = type.template,
                values = record.templateValues(),
                platform = platform,
                related = relatedOf(type.template, organizationId, definition.obj.name, recordId),
                objectName = definition.obj.name,
                objectLabel = definition.obj.label,
                number = number,
                issuedAt = issuedAt
            )

        // the one that prevails is the last one issued: archive first, so the partial unique index
        // never sees two valid documents of this type on this record
        documents.archiveValid(type.id, recordId)

        val issued =
            documents.insert(
                Document(
                    id = UUID.randomUUID(),
                    organizationId = organizationId,
                    documentTypeId = type.id,
                    objectId = definition.obj.id,
                    recordId = recordId,
                    number = number,
                    year = year,
                    sequence = sequence,
                    status = DocumentStatus.VALID,
                    snapshot = snapshot,
                    issuedAt = issuedAt,
                    issuedBy = issuedBy
                )
            )

        // same transaction as the insert above: a document never exists without its history entry.
        audit.record(
            organizationId = organizationId,
            userId = issuedBy,
            objectName = definition.obj.name,
            recordId = recordId,
            operation = AuditOperation.ISSUE,
            documentId = issued.id
        )

        return issued
    }

    // only the relationships the template actually names get read: a template that draws no table
    // costs no query at all.
    private suspend fun relatedOf(
        template: TemplateNode,
        organizationId: UUID,
        objectName: String,
        recordId: UUID
    ): Map<String, RelatedSnapshot> {
        val named = mutableSetOf<String>()

        fun walk(node: TemplateNode) {
            if (node.type == TemplateNodes.RELATED_TABLE) {
                node.attrs
                    ?.get("relationship")
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(named::add)
            }
            node.content?.forEach(::walk)
        }
        walk(template)
        if (named.isEmpty()) return emptyMap()

        return named.associateWith { name ->
            val (other, page) = related.relatedRows(organizationId, objectName, recordId, name, RecordQuery(page = PageRequest(0, RELATED_ROWS)))
            RelatedSnapshot(
                label = other.obj.label,
                columns = other.fields.map { SnapshotColumn(it.name, it.label) },
                rows = page.content.map { row -> row.attributes }
            )
        }
    }
}

// what a template can name: the record's fields, then every section's fields (gis: its geometries)
// by field name, the way the original froze attributes + geometries
internal fun RecordRow.templateValues(): Map<String, Any?> = sections.values.fold(attributes) { all, section -> all + section }
