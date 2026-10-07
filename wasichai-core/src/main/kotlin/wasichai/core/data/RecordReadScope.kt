package wasichai.core.data

import wasichai.core.audit.AuditRecordScope
import wasichai.core.common.PageRequest
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.metadata.ObjectDefinition
import java.util.UUID

/**
 * SPI: narrows what a caller reads of an object, below READ on it (ADR-048). An app keeps a person to the
 * records of their projects, territories or regions; the tenant and owner filters stay in front of it.
 *
 * Asked for a person or a service account. Never for ADMIN, the platform (`asPlatform`, ADR-039) or an
 * automation: they read the whole organization, as for every other record rule.
 *
 * - null: no restriction for this caller on this object.
 * - a criterion: ANDed, in parens, into every read of the object. A record outside it reads as missing:
 *   `404` on a record route, the `400` of a `RELATION` value naming no record (ADR-031 D29).
 * - nothing in scope: a criterion that matches nothing, `RecordCriterion { _, _ -> "false" }`.
 *
 * `suspend`, so the caller's assignments can be looked up here; it is asked once per object a read touches,
 * so cache them per request yourself. The condition is plain SQL with every value bound through `bind`, and
 * it is always handed the object's full definition, so it may name a field the caller cannot read.
 * Collected from every bean, in `@Order`; with several, every criterion applies.
 */
interface RecordReadScope {
    suspend fun criterion(
        caller: AuthenticatedUser,
        definition: ObjectDefinition
    ): RecordCriterion?
}

/**
 * Every [RecordReadScope], asked where a read already applies the owner filter: RecordService, related
 * records, the RELATION target check (D30), audit, workflow transitions. One bean, not replaceable: an app
 * adds a scope, it never removes another one (as RecordWriteGuards, ADR-040).
 */
class RecordReadScopes(
    private val scopes: List<RecordReadScope>,
    private val store: RecordStore
) : AuditRecordScope {
    // ADMIN reads the organization. no bean: nothing to ask, so no read gains a term or a query
    override fun appliesTo(caller: AuthenticatedUser): Boolean = !caller.isAdmin && scopes.isNotEmpty()

    // what to AND into a read of [definition] by [caller]. empty: the read stays as it was.
    // caller null: the platform or an automation.
    suspend fun criteria(
        caller: AuthenticatedUser?,
        definition: ObjectDefinition
    ): List<RecordCriterion> {
        if (caller == null || !appliesTo(caller)) return emptyList()
        return scopes.mapNotNull { scope ->
            val criterion = scope.criterion(caller, definition) ?: return@mapNotNull null
            // the full definition, whatever projection the read selects with
            RecordCriterion { _, bind -> criterion.condition(definition, bind) }
        }
    }

    // ids only: the scope decides, nothing of the rows is selected
    override suspend fun readable(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
        ids: Collection<UUID>
    ): Set<UUID>? {
        val criteria = criteria(caller, definition)
        if (criteria.isEmpty()) return null
        val wanted = ids.distinct()
        if (wanted.isEmpty()) return emptySet()
        val query = RecordQuery(page = PageRequest(0, wanted.size), ids = wanted, criteria = criteria, count = false)
        return store
            .query(definition.copy(fields = emptyList()), caller.organizationId, query)
            .content
            .mapTo(mutableSetOf()) { it.id }
    }
}
