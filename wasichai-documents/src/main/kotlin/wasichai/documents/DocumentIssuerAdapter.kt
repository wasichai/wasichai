package wasichai.documents

import org.springframework.stereotype.Service
import wasichai.automation.DocumentIssuer
import java.util.UUID

// the documents side of automation's port. compiled against wasichai-automation, loaded only when an
// app has it (WasichaiDocumentsAutomationAutoConfiguration).
@Service
class DocumentIssuerAdapter(
    private val types: DocumentTypeRepository,
    private val documents: DocumentService
) : DocumentIssuer {
    override suspend fun typeExists(
        objectId: UUID,
        name: String
    ): Boolean = types.findByName(objectId, name.trim().lowercase()) != null

    // no issuer: a queued run has no user behind it (ADR-016), so the platform issues it
    override suspend fun issue(
        organizationId: UUID,
        objectName: String,
        recordId: UUID,
        typeName: String
    ): String = documents.issue(organizationId, objectName, recordId, typeName, null, null).number
}
