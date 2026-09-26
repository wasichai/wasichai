package wasichai.documents

import wasichai.core.common.ValidationException
import java.util.UUID

// the editor's own document model, stored as it comes. shaped after prosemirror because that is
// what the client writes: a tree of typed nodes, text at the leaves, marks for bold and the rest.
//
// deliberately NOT html. nothing in this codebase renders html it was handed, there is no sanitiser
// in the dependency list, and a document template is the worst possible place to start. the client
// walks this tree to react elements; the server only ever reads it to check what it names.
data class TemplateNode(
    val type: String,
    val attrs: Map<String, Any?>? = null,
    val content: List<TemplateNode>? = null,
    val marks: List<TemplateMark>? = null,
    val text: String? = null
)

data class TemplateMark(
    val type: String,
    val attrs: Map<String, Any?>? = null
)

// what an author may drop into the running text besides words. three kinds, and the third is the
// one that keeps a document honest: it is resolved when the document is issued, never when it is
// read, so an archived document does not quietly print today's date.
object TemplateNodes {
    const val OBJECT_FIELD = "objectField"
    const val RELATED_TABLE = "relatedTable"
    const val PLATFORM_VALUE = "platformValue"
}

// the values the platform fills in. today/now/user/id are the same names the automation templater
// already answers to (AutomationRules), so an admin learns one vocabulary, not two. the document's
// own identity comes in pieces rather than pre-assembled, so a heading can read
// "[documentName] [documentPrefix]-[documentSerial]" and come out as "Oficio SGTM-2026-001".
enum class PlatformValue(
    val key: String
) {
    TODAY("today"),
    NOW("now"),
    USER("user"),
    RECORD_ID("id"),
    DOCUMENT_NAME("documentName"),
    DOCUMENT_PREFIX("documentPrefix"),
    DOCUMENT_SERIAL("documentSerial"),
    DOCUMENT_NUMBER("documentNumber");

    companion object {
        fun parse(raw: String?): PlatformValue =
            entries.firstOrNull { it.key == raw?.trim() }
                ?: throw ValidationException(
                    "Unknown value '${raw.orEmpty()}'",
                    "template",
                    "must be one of ${entries.joinToString(", ") { it.key }}"
                )
    }
}

data class DocumentType(
    val id: UUID,
    val organizationId: UUID,
    val objectId: UUID,
    val name: String,
    val label: String,
    // the sigla: SGTM in SGTM-2026-001
    val prefix: String,
    val template: TemplateNode
)
