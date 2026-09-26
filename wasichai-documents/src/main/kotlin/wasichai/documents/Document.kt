package wasichai.documents

import java.time.Instant
import java.util.UUID

enum class DocumentStatus { VALID, ARCHIVED }

// what a related table showed when the document was issued: the columns it drew and the rows under
// them, both frozen. the live relationship may gain a field tomorrow; this document will not.
data class RelatedSnapshot(
    val label: String,
    val columns: List<SnapshotColumn>,
    val rows: List<Map<String, Any?>>
)

data class SnapshotColumn(
    val name: String,
    val label: String
)

// everything the viewer needs and nothing it has to go and fetch. the template travels inside on
// purpose: without it, editing a type would rewrite every document it ever issued.
data class DocumentSnapshot(
    val template: TemplateNode,
    val values: Map<String, Any?>,
    val platform: Map<String, String>,
    val related: Map<String, RelatedSnapshot>,
    val objectName: String,
    val objectLabel: String,
    val number: String,
    val issuedAt: Instant
)

data class Document(
    val id: UUID,
    val organizationId: UUID,
    val documentTypeId: UUID,
    val objectId: UUID,
    val recordId: UUID,
    // the whole correlative: SGTM-2026-001
    val number: String,
    val year: Int,
    val sequence: Int,
    val status: DocumentStatus,
    val snapshot: DocumentSnapshot,
    val issuedAt: Instant?,
    val issuedBy: UUID?
)
