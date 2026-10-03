package wasichai.core.data

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import wasichai.core.metadata.CustomObjectRepository
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

/**
 * A RELATION value must name a record of the writer's organization (issue 33, ADR-031 D29). The FK
 * would refuse it anyway, but as a 409 meant for the delete race (ADR-044): a client that sent an id
 * that never existed made a mistake on a field, a 400.
 *
 * Called by [RecordWriteGuards], after the built-in write rules and before the app's guards, so every
 * write path checks. One tenant-filtered read per target object, only for values sent, non-null and
 * changed. Missing and another organization's record answer the same, so nothing leaks. A record
 * deleted between this check and the write still fails the FK: that stays the 409.
 */
open class RelationTargets(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    private val objects: CustomObjectRepository
) {
    // [before]: the stored row on an update. a value it already holds is not looked up again.
    suspend fun rejectMissing(
        organizationId: UUID,
        definition: ObjectDefinition,
        attributes: Map<String, Any?>,
        before: Map<String, Any?>? = null
    ) {
        // field -> id, only what can be looked up. a value that is no uuid is the codec's 400, later.
        val sent =
            definition.fields
                .filter { it.type == FieldType.RELATION && it.relationTargetObjectId != null }
                .mapNotNull { field ->
                    val id = uuidOf(attributes[field.name]) ?: return@mapNotNull null
                    if (before != null && uuidOf(before[field.name]) == id) null else field to id
                }
        if (sent.isEmpty()) return
        val missing =
            sent.groupBy { it.first.relationTargetObjectId!! }.flatMap { (targetObjectId, pairs) ->
                val found = existing(organizationId, targetObjectId, pairs.map { it.second }.distinct())
                pairs.filter { it.second !in found }.map { it.first }
            }
        if (missing.isEmpty()) return
        // field order, so the answer does not depend on how the map was grouped
        val fields = definition.fields.filter { it in missing }
        throw ValidationException(
            "Invalid value for ${fields.joinToString(", ") { "'${it.name}'" }}",
            fields.map { FieldViolation(it.name, "no record with this id") }
        )
    }

    // the ids of [ids] that are records of this organization. no target object: none are.
    // open for unit tests only.
    internal open suspend fun existing(
        organizationId: UUID,
        targetObjectId: UUID,
        ids: List<UUID>
    ): Set<UUID> {
        val target = objects.findById(organizationId, targetObjectId) ?: return emptySet()
        return db
            .sql("SELECT id FROM ${schemas.dataTable(target.physicalTable)} WHERE organization_id = :organizationId AND id = ANY(:ids)")
            .bind("organizationId", organizationId)
            .bind("ids", ids.toTypedArray())
            .map { row, _ -> row.get("id", UUID::class.java)!! }
            .all()
            .asFlow()
            .toList()
            .toSet()
    }

    private fun uuidOf(value: Any?): UUID? =
        when (value) {
            is UUID -> value
            is String -> runCatching { UUID.fromString(value) }.getOrNull()
            else -> null
        }
}
