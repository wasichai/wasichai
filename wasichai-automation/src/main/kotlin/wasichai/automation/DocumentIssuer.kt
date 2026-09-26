package wasichai.automation

import wasichai.core.common.ValidationException
import java.util.UUID

// what an automation needs from a documents module, and nothing more. wasichai-documents implements
// it when both are installed; automation never reaches into its repositories or its service --
// the same shape as core's WorkflowStates, for the same reason.
interface DocumentIssuer {
    suspend fun typeExists(
        objectId: UUID,
        name: String
    ): Boolean

    // issues one and answers with the correlative it got, which is all a run step has to print.
    // nothing is passed for the issuer: no user sits behind a queued run (ADR-016), so the
    // platform issues it.
    suspend fun issue(
        organizationId: UUID,
        objectName: String,
        recordId: UUID,
        typeName: String
    ): String
}

// no documents module: no type exists, so a GENERATE_DOCUMENT action is refused when it is saved,
// exactly like a type nobody created. a stored one fails its run instead of the boot.
class NoDocumentIssuer : DocumentIssuer {
    override suspend fun typeExists(
        objectId: UUID,
        name: String
    ): Boolean = false

    override suspend fun issue(
        organizationId: UUID,
        objectName: String,
        recordId: UUID,
        typeName: String
    ): String = throw ValidationException("Unknown document type '$typeName'", "documentType", "the documents module is not installed")
}
