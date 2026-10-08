package wasichai.core.data

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.common.Actions
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.RoleQueries
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomFieldRepository
import wasichai.core.metadata.CustomObject
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
 * With a reader (a person or a service account, not ADMIN) the record must also be one they can read
 * (issue 39, ADR-031 D30): READ on the target object, their own record when they see only their own,
 * and the app's read scope on the target (ADR-048). The same rules RecordService reads with, folded
 * into the same read.
 *
 * Called by [RecordWriteGuards], after the built-in write rules and before the app's guards, so every
 * write path checks. One tenant-filtered read per target object, only for values sent, non-null and
 * changed. Missing, another organization's record and one the reader cannot see answer the same, so
 * nothing leaks. A record deleted between this check and the write still fails the FK: that stays the 409.
 */
open class RelationTargets(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    private val objects: CustomObjectRepository,
    private val fields: CustomFieldRepository,
    private val readScopes: RecordReadScopes
) {
    // a batch's RELATION targets, looked up once per target object (issue 77). only for the reader and
    // organization it was made for
    internal class Checked(
        val organizationId: UUID,
        val reader: AuthenticatedUser?,
        // target object -> the ids of the batch found there
        val found: Map<UUID, Set<UUID>>
    )

    // what rejectMissing would look up for each of [attributes], as creates (no before): one read per target
    // object for the whole batch, distinct ids only. nothing to look up: no read at all
    internal suspend fun check(
        organizationId: UUID,
        definition: ObjectDefinition,
        attributes: List<Map<String, Any?>>,
        reader: AuthenticatedUser?
    ): Checked {
        val looked =
            attributes
                .flatMap { lookups(definition, it, before = null) }
                .groupBy({ it.first.relationTargetObjectId!! }, { it.second })
                .mapValues { (_, ids) -> ids.toSet() }
        val scope = scopeOf(reader)
        val found = looked.mapValues { (targetObjectId, ids) -> existing(organizationId, targetObjectId, ids.toList(), scope) }
        return Checked(organizationId, reader, found)
    }

    // [before]: the stored row on an update. a value it already holds is not looked up again, in scope
    // or not: keeping a link is no new claim on its target.
    // [reader]: who writes. null (the platform, an automation) or ADMIN: the organization is the scope.
    suspend fun rejectMissing(
        organizationId: UUID,
        definition: ObjectDefinition,
        attributes: Map<String, Any?>,
        before: Map<String, Any?>? = null,
        reader: AuthenticatedUser? = null
    ) = rejectMissing(organizationId, definition, attributes, before, reader, checked = null)

    // [checked]: a batch's lookups (issue 77). an id it found is answered from it; any other is read as ever, so a
    // target an earlier record of the batch brought about (a listener's write) passes, as in a create loop.
    // an overload, not a default: Checked stays internal and the public signature stays as it was
    internal suspend fun rejectMissing(
        organizationId: UUID,
        definition: ObjectDefinition,
        attributes: Map<String, Any?>,
        before: Map<String, Any?>? = null,
        reader: AuthenticatedUser? = null,
        checked: Checked?
    ) {
        // another reader's cache could answer what this one may not see
        require(checked == null || (checked.reader == reader && checked.organizationId == organizationId)) {
            "a batch's relation lookups answer only the reader and organization they were made for"
        }
        val sent = lookups(definition, attributes, before)
        if (sent.isEmpty()) return
        val scope = scopeOf(reader)
        val missing =
            sent.groupBy { it.first.relationTargetObjectId!! }.flatMap { (targetObjectId, pairs) ->
                val ids = pairs.map { it.second }.distinct()
                val known = checked?.found?.get(targetObjectId).orEmpty()
                val rest = ids.filter { it !in known }
                val found = known + if (rest.isEmpty()) emptySet() else existing(organizationId, targetObjectId, rest, scope)
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

    // field -> id, only what can be looked up: RELATION values sent, non-null, changed. a value that is
    // no uuid is the codec's 400, later.
    internal fun lookups(
        definition: ObjectDefinition,
        attributes: Map<String, Any?>,
        before: Map<String, Any?>?
    ): List<Pair<CustomField, UUID>> =
        definition.fields
            .filter { it.type == FieldType.RELATION && it.relationTargetObjectId != null }
            .mapNotNull { field ->
                val id = uuidOf(attributes[field.name]) ?: return@mapNotNull null
                if (before != null && uuidOf(before[field.name]) == id) null else field to id
            }

    // the ids of [ids] that are records of this organization, and readable by [scope] when one is
    // given. no target object: none are. open for unit tests only.
    internal open suspend fun existing(
        organizationId: UUID,
        targetObjectId: UUID,
        ids: List<UUID>,
        scope: AuthenticatedUser?
    ): Set<UUID> {
        val target = objects.findById(organizationId, targetObjectId) ?: return emptySet()
        // no role grants nothing (RoleQueries): no read to ask
        if (scope != null && scope.roles.isEmpty()) return emptySet()
        // the app's read scope on the target, as RecordService.get applies it. its values bind as c<n>
        val bindings = mutableMapOf<String, Any>()
        val terms =
            if (scope == null || !readScopes.appliesTo(scope)) {
                emptyList()
            } else {
                val definition = ObjectDefinition(target, fields.findByObject(target.id))
                readScopes.criteria(scope, definition).map { it.term(definition, bindings) }
            }
        var spec =
            db
                .sql(sql(target, scope != null, terms))
                .bind("organizationId", organizationId)
                .bind("ids", ids.toTypedArray())
        if (scope != null) {
            spec =
                spec
                    .bind("roleNames", scope.roles)
                    .bind("action", Actions.READ)
                    .bind("objectId", targetObjectId)
                    .bind("userId", scope.userId)
        }
        bindings.forEach { (name, value) -> spec = spec.bind(name, value) }
        return spec
            .map { row, _ -> row.get("id", UUID::class.java)!! }
            .all()
            .asFlow()
            .toList()
            .toSet()
    }

    // pure, so it is tested directly. [scoped]: a reader's rules apply; [terms]: their read scope, in parens
    internal fun sql(
        target: CustomObject,
        scoped: Boolean,
        terms: List<String>
    ): String =
        buildString {
            append("SELECT id FROM ${schemas.dataTable(target.physicalTable)} WHERE organization_id = :organizationId AND id = ANY(:ids)")
            if (scoped) {
                // READ on the target object, then the owner filter, then the read scope, as RecordService.get applies them
                append(" AND EXISTS (${RoleQueries.permissionQuery(schemas, objectScoped = true)})")
                append(" AND (created_by = :userId OR NOT COALESCE((${AccessPolicy.ownRecordsOnlyQuery(schemas)}), false))")
                terms.forEach { append(" AND $it") }
            }
        }

    // a service account is never ADMIN (ADR-043), so it is always scoped
    private fun scopeOf(reader: AuthenticatedUser?): AuthenticatedUser? = reader?.takeUnless { it.isAdmin }

    private fun uuidOf(value: Any?): UUID? =
        when (value) {
            is UUID -> value
            is String -> runCatching { UUID.fromString(value) }.getOrNull()
            else -> null
        }
}
