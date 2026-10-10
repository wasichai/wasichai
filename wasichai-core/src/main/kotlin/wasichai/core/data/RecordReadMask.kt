package wasichai.core.data

import wasichai.core.audit.AuditStateMask
import wasichai.core.common.PageRequest
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.metadata.ObjectDefinition
import java.util.UUID

/**
 * SPI: rewrites the values a caller reads of one record, below the field permissions (ADR-065). A field the
 * caller reads may still carry something they must not see on some records: the files module hides a file's
 * original name on a classified record this way.
 *
 * Asked for a person or a service account, ADMIN included: the mask decides who sees what. Never for the
 * platform (`asPlatform`, ADR-039) or an automation: they read the whole record, as for every other read rule.
 *
 * Asked wherever core hands a record's `attributes` to a caller: `RecordService` get, list, `rows`, the answers
 * of create, update and patch (so an idempotent replay replays the masked answer), related records, the
 * workflow transition's answer, and every before and after of `/api/audit` and a record's history. Sections
 * are not masked.
 *
 * Collected from every bean, in `@Order`; each gets what the one before answered.
 */
interface RecordReadMask {
    // false: nothing of this object is ever rewritten, so no read of it pays for the stored record
    fun appliesTo(definition: ObjectDefinition): Boolean = true

    /**
     * The attributes [caller] gets of one record of [definition]. [attributes]: what they would get, their
     * readable fields only. [stored]: the same record as stored, every field, so the decision may rest on a
     * field the caller cannot read; in history, the state of that entry. Answer the keys of [attributes]: a
     * value replaced is what the caller reads, a key left out is a field they do not get, a key added is
     * ignored.
     */
    suspend fun mask(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
        stored: Map<String, Any?>,
        attributes: Map<String, Any?>
    ): Map<String, Any?>
}

/**
 * Every [RecordReadMask], asked where a record leaves core for a caller. One bean, not replaceable: an app adds a
 * mask, it never removes another one (as RecordReadScopes, ADR-048). With no mask that applies, every answer is
 * what it was and nothing more is read.
 */
class RecordReadMasks(
    private val masks: List<RecordReadMask>,
    // only read when a mask applies to a projected read
    private val store: RecordStore?
) : AuditStateMask {
    init {
        require(masks.isEmpty() || store != null) { "RecordReadMasks needs a RecordStore to read the stored records" }
    }

    // caller null: the platform or an automation
    override fun appliesTo(
        caller: AuthenticatedUser?,
        definition: ObjectDefinition
    ): Boolean = caller != null && masks.any { it.appliesTo(definition) }

    /**
     * [rows] as [caller] reads them through [visible], their projection of [definition]. A projected read pays one
     * more query, by ids, for the stored records the masks decide on; [stored] skips it when the caller holds them.
     */
    suspend fun rows(
        caller: AuthenticatedUser?,
        organizationId: UUID,
        definition: ObjectDefinition,
        visible: ObjectDefinition,
        rows: List<RecordRow>,
        stored: Map<UUID, Map<String, Any?>>? = null
    ): List<RecordRow> {
        if (caller == null || rows.isEmpty()) return rows
        val applying = masks.filter { it.appliesTo(definition) }
        if (applying.isEmpty()) return rows
        val whole = stored ?: if (visible.fields.size == definition.fields.size) null else storedOf(organizationId, definition, rows)
        return rows.map { row ->
            val decidedOn = whole?.get(row.id) ?: row.attributes
            row.copy(attributes = masked(applying, caller, definition, decidedOn, row.attributes))
        }
    }

    suspend fun row(
        caller: AuthenticatedUser?,
        organizationId: UUID,
        definition: ObjectDefinition,
        visible: ObjectDefinition,
        row: RecordRow,
        stored: Map<String, Any?>? = null
    ): RecordRow = rows(caller, organizationId, definition, visible, listOf(row), stored?.let { mapOf(row.id to it) }).single()

    // an audit state is a whole record: it is what the masks decide on and what they rewrite
    override suspend fun state(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
        state: Map<String, Any?>
    ): Map<String, Any?> {
        val applying = masks.filter { it.appliesTo(definition) }
        if (applying.isEmpty()) return state
        return masked(applying, caller, definition, state, state)
    }

    private suspend fun masked(
        applying: List<RecordReadMask>,
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
        stored: Map<String, Any?>,
        attributes: Map<String, Any?>
    ): Map<String, Any?> =
        applying.fold(attributes) { current, mask ->
            // never a field the caller was not going to get
            mask.mask(caller, definition, stored, current).filterKeys { it in current }
        }

    // no read rule here: the rows were already read by the caller, this only completes them for the masks
    private suspend fun storedOf(
        organizationId: UUID,
        definition: ObjectDefinition,
        rows: List<RecordRow>
    ): Map<UUID, Map<String, Any?>> {
        val ids = rows.map { it.id }.distinct()
        val query = RecordQuery(page = PageRequest(0, ids.size), ids = ids, count = false)
        return store!!.query(definition, organizationId, query).content.associate { it.id to it.attributes }
    }

    companion object {
        val NONE = RecordReadMasks(emptyList(), null)
    }
}
