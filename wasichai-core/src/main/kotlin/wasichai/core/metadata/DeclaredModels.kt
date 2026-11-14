package wasichai.core.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.TenantDirectory
import java.util.UUID

/**
 * A model an app's code depends on, declared in code (issue 165, ADR-081): its objects with their fields, indexes,
 * composite uniques and flags, its relationships and its declared actions, in the shapes the metadata api takes. An
 * app registers it as a bean; the platform ensures it in every tenant at startup and in every tenant it provisions,
 * with nobody behind it, and nobody else may change what it owns (a `409` from the metadata api).
 *
 * [name] owns what the model declares: it matches `^[a-z][a-z0-9_-]{0,48}$`, every object and relationship it makes
 * carries it as `declaredBy`, and its admin entries say `model:<name>`. Objects are ensured in the order given, so a
 * `RELATION` field's target comes before it; relationships and then actions once every object is there.
 */
data class DeclaredModel(
    val name: String,
    val objects: List<CreateObjectRequest>,
    val relationships: List<CreateRelationshipRequest> = emptyList(),
    // object name -> the actions declared on it (ADR-042)
    val actions: Map<String, List<ObjectActionRequest>> = emptyMap()
)

/**
 * What ensuring one [model] in one tenant did (ADR-081), each entry a short line such as `field curso.creditos`.
 * [incompatible]: what the tenant holds that the model cannot become without losing data or links (a field of
 * another type, a relationship between other objects, an object another model owns). It is reported, never applied,
 * and the rest of the model is. [failure]: the database refused a change (a unique the data repeats, a required field
 * with empty rows), so nothing of the model was applied in that tenant.
 */
data class DeclaredModelReport(
    val model: String,
    val organizationId: UUID,
    val created: List<String> = emptyList(),
    val updated: List<String> = emptyList(),
    val incompatible: List<String> = emptyList(),
    val failure: String? = null
) {
    // the tenant already held the model, whole
    val unchanged: Boolean get() = created.isEmpty() && updated.isEmpty() && incompatible.isEmpty() && failure == null
}

/**
 * Ensures every [DeclaredModel] bean (ADR-081): at startup in every tenant of the [TenantDirectory] (ADR-057), once
 * the app is ready, and in a tenant being provisioned, inside the provisioning's transaction. Idempotent: what the
 * tenant holds as declared is left alone, and a second run writes nothing, not even an admin entry. What is missing
 * is created, what differs and can change is changed, and an object or a relationship a person made under a
 * declared name is taken over once it matches. A field the tenant has and the model does not is kept: dropping it
 * would drop its data.
 *
 * One transaction per tenant and model, under a cluster lock per tenant (ADR-039), so two instances starting together
 * never race. At startup a tenant's failure is logged and the next goes on; [ensureAll] answers every report.
 */
class DeclaredModels(
    private val models: List<DeclaredModel>,
    private val tenants: TenantDirectory,
    private val metadata: MetadataService,
    private val relationshipService: RelationshipService,
    private val actionService: ObjectActionService,
    private val objects: CustomObjectRepository,
    private val fields: CustomFieldRepository,
    private val relationships: RelationshipRepository,
    private val actions: ObjectActionRepository,
    private val types: FieldTypeRegistry,
    private val lock: ClusterLock
) : ApplicationListener<ApplicationReadyEvent> {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        // a code bug, so the context refuses to start: two owners for one name would undo each other on every start
        models.forEach { require(NAME.matches(it.name)) { "declared model '${it.name}' must match ${NAME.pattern}" } }
        models
            .groupBy { it.name }
            .filterValues { it.size > 1 }
            .keys
            .firstOrNull()
            ?.let { error("two declared models are named '$it'") }
        owners("object") { model -> model.objects.map { it.name } }
        owners("relationship") { model -> model.relationships.map { it.name } }
    }

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        if (models.isNotEmpty()) runBlocking { ensureAll() }
    }

    /** Every model in every tenant, each in its own transaction. A failure is logged and reported, never thrown. */
    suspend fun ensureAll(): List<DeclaredModelReport> {
        if (models.isEmpty()) return emptyList()
        return tenants.organizations().flatMap { tenant -> models.map { logged(tenant.id, it) } }
    }

    /** Every model in [organizationId], in the caller's transaction when there is one: a failure throws. */
    suspend fun ensure(organizationId: UUID): List<DeclaredModelReport> = models.map { ensure(organizationId, it) }

    /** [model] in [organizationId], declared bean or not, in the caller's transaction or one of its own. */
    suspend fun ensure(
        organizationId: UUID,
        model: DeclaredModel
    ): DeclaredModelReport =
        lock
            .withXactLock("wasichai:declared-model:$organizationId") {
                ModelOwner.run(organizationId, model.name) { Ensuring(organizationId, model).run() }
            }.also(::log)

    private suspend fun logged(
        organizationId: UUID,
        model: DeclaredModel
    ): DeclaredModelReport =
        try {
            ensure(organizationId, model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("declared model '{}' could not be ensured in organization {}: {}", model.name, organizationId, e.message, e)
            DeclaredModelReport(model.name, organizationId, failure = e.message ?: e.javaClass.simpleName)
        }

    private fun log(report: DeclaredModelReport) {
        if (report.incompatible.isNotEmpty()) {
            log.warn(
                "declared model '{}' in organization {} left incompatible: {}",
                report.model,
                report.organizationId,
                report.incompatible.joinToString("; ")
            )
        }
        if (report.created.isNotEmpty() || report.updated.isNotEmpty()) {
            log.info(
                "declared model '{}' in organization {}: created {}, updated {}",
                report.model,
                report.organizationId,
                report.created,
                report.updated
            )
        }
    }

    private fun owners(
        kind: String,
        names: (DeclaredModel) -> List<String>
    ) {
        val owner = hashMapOf<String, String>()
        models.forEach { model ->
            names(model).map { it.trim().lowercase() }.forEach { name ->
                owner.put(name, model.name)?.let { other -> error("$kind '$name' is declared by both '$other' and '${model.name}'") }
            }
        }
    }

    // one model in one tenant: what it did, line by line
    private inner class Ensuring(
        private val organizationId: UUID,
        private val model: DeclaredModel
    ) {
        private val created = mutableListOf<String>()
        private val updated = mutableListOf<String>()
        private val incompatible = mutableListOf<String>()

        suspend fun run(): DeclaredModelReport {
            model.objects.forEach { ensureObject(it) }
            model.relationships.forEach { ensureRelationship(it) }
            model.actions.forEach { (objectName, declared) -> declared.forEach { ensureAction(objectName, it) } }
            return DeclaredModelReport(model.name, organizationId, created.toList(), updated.toList(), incompatible.toList())
        }

        private suspend fun ensureObject(declared: CreateObjectRequest) {
            val name = declared.name.trim().lowercase()
            val existing = objects.findByName(organizationId, name)
            if (existing == null) {
                metadata.createObject(declared)
                created += "object $name"
                return
            }
            if (existing.declaredBy != null && existing.declaredBy != model.name) {
                incompatible += "object $name is declared by '${existing.declaredBy}'"
                return
            }
            val stored = fields.findByObject(existing.id)
            declared.fields.forEach { ensureField(existing, stored, it) }
            // after the fields: its sets may name one just added
            objectChange(existing, declared)?.let {
                metadata.updateObject(name, it)
                updated += "object $name"
            }
        }

        private suspend fun ensureField(
            obj: CustomObject,
            stored: List<CustomField>,
            declared: FieldRequest
        ) {
            val name = declared.name.trim().lowercase()
            val label = "field ${obj.name}.$name"
            val current = stored.firstOrNull { it.name == name }
            if (current == null) {
                metadata.addField(obj.name, declared)
                created += label
                return
            }
            val type = types.parse(declared.type)
            if (type != current.type) {
                incompatible += "$label is ${current.type.name} and is declared ${type.name}: a field never changes type"
                return
            }
            val target =
                declared.relationTarget
                    ?.trim()
                    ?.lowercase()
                    ?.let { objects.findByName(organizationId, it)?.id }
            if (target != current.relationTargetObjectId) {
                incompatible += "$label points at another object than declared: a relation never moves"
                return
            }
            // a module type's own settings are fixed when the field is made: no update path changes them
            if (types.handler(type).attributesOf(name, declared).any { (column, value) -> current.attributes[column] != value }) {
                incompatible += "$label has other ${type.name} settings than declared: they never change"
                return
            }
            fieldChange(current, declared)?.let {
                metadata.updateField(obj.name, name, it)
                updated += label
            }
        }

        private suspend fun ensureRelationship(declared: CreateRelationshipRequest) {
            val name = declared.name.trim().lowercase()
            val existing = relationships.findByName(organizationId, name)
            if (existing == null) {
                relationshipService.create(declared)
                created += "relationship $name"
                return
            }
            if (existing.declaredBy != null && existing.declaredBy != model.name) {
                incompatible += "relationship $name is declared by '${existing.declaredBy}'"
                return
            }
            val source = objects.findByName(organizationId, declared.source.trim().lowercase())?.id
            val target = objects.findByName(organizationId, declared.target.trim().lowercase())?.id
            if (RelationshipType.parse(declared.type) != existing.type || source != existing.sourceObjectId || target != existing.targetObjectId) {
                incompatible += "relationship $name joins other objects or is of another type than declared: a relationship is never reshaped"
                return
            }
            val label = declared.label.trim()
            val inverseLabel = declared.inverseLabel?.trim()?.ifBlank { null }
            val labels = LocalizedTexts.checked(declared.labels, "labels")
            val inverseLabels = LocalizedTexts.checked(declared.inverseLabels, "inverseLabels")
            val same =
                existing.declaredBy == model.name &&
                    existing.label == label &&
                    existing.inverseLabel == inverseLabel &&
                    existing.labels == labels &&
                    existing.inverseLabels == inverseLabels
            if (same) return
            relationshipService.update(
                name,
                UpdateRelationshipRequest(label = label, inverseLabel = inverseLabel ?: "", labels = labels, inverseLabels = inverseLabels)
            )
            updated += "relationship $name"
        }

        // a declared action is added; one the tenant has already stays as it is, label included
        private suspend fun ensureAction(
            objectName: String,
            declared: ObjectActionRequest
        ) {
            val obj = objects.findByName(organizationId, objectName.trim().lowercase())
            if (obj == null) {
                incompatible += "action ${declared.name} is declared on '$objectName', which the model does not make"
                return
            }
            val name = ObjectActionService.requireValidName(declared.name)
            if (actions.exists(organizationId, obj.id, name)) return
            actionService.declare(obj.name, declared)
            created += "action ${obj.name}.$name"
        }

        // the whole object as declared, or null when it already reads so and is the model's
        private fun objectChange(
            existing: CustomObject,
            declared: CreateObjectRequest
        ): UpdateObjectRequest? {
            val label = declared.label.trim()
            val pluralLabel =
                declared.pluralLabel
                    ?.trim()
                    .orEmpty()
                    .ifBlank { label }
            val description = declared.description?.trim()
            val indexes = sets(declared.indexes)
            val uniques = sets(declared.uniqueConstraints)
            val labels = LocalizedTexts.checked(declared.labels, "labels")
            val pluralLabels = LocalizedTexts.checked(declared.pluralLabels, "pluralLabels")
            val descriptions = LocalizedTexts.checked(declared.descriptions, "descriptions")
            val auditMode = AuditMode.parse(declared.auditMode) ?: AuditMode.FULL
            val same =
                existing.declaredBy == model.name &&
                    existing.enabled &&
                    existing.label == label &&
                    existing.pluralLabel == pluralLabel &&
                    existing.description == description &&
                    existing.indexes == indexes &&
                    existing.uniqueConstraints == uniques &&
                    existing.appendOnly == declared.appendOnly &&
                    existing.apiOnly == declared.apiOnly &&
                    existing.requiresReason == declared.requiresReason &&
                    existing.labels == labels &&
                    existing.pluralLabels == pluralLabels &&
                    existing.descriptions == descriptions &&
                    existing.auditMode == auditMode
            if (same) return null
            return UpdateObjectRequest(
                label = label,
                pluralLabel = pluralLabel,
                description = description,
                enabled = true,
                indexes = indexes,
                uniqueConstraints = uniques,
                appendOnly = declared.appendOnly,
                apiOnly = declared.apiOnly,
                requiresReason = declared.requiresReason,
                labels = labels,
                pluralLabels = pluralLabels,
                descriptions = descriptions,
                auditMode = auditMode.name
            )
        }

        // what differs, and only that, or null. a description left out of the model is not cleared
        private fun fieldChange(
            current: CustomField,
            declared: FieldRequest
        ): UpdateFieldRequest? {
            val label =
                declared.label
                    ?.trim()
                    .orEmpty()
                    .ifBlank { current.name }
            val description = declared.description?.trim()
            val enumOptions = declared.enumOptions?.map { it.trim() }?.filter { it.isNotEmpty() }
            val defaultValue = declared.defaultValue?.takeIf { it.isNotBlank() }
            val timeZone = FieldTimeZones.checked(current.type, declared.timeZone)
            val labels = LocalizedTexts.checked(declared.labels, "labels")
            val descriptions = LocalizedTexts.checked(declared.descriptions, "descriptions")
            val enumOptionLabels = LocalizedTexts.checkedOptions(declared.enumOptionLabels, enumOptions ?: current.enumOptions, "enumOptionLabels")
            val change =
                UpdateFieldRequest(
                    label = label.takeIf { it != current.label },
                    required = declared.required.takeIf { it != current.required },
                    unique = declared.unique.takeIf { it != current.unique },
                    description = description?.takeIf { it != current.description },
                    enumOptions = enumOptions?.takeIf { it != current.enumOptions },
                    visible = declared.visible.takeIf { it != current.visible },
                    editable = declared.editable.takeIf { it != current.editable },
                    indexed = declared.indexed.takeIf { it != current.indexed },
                    // blank clears both
                    timeZone = if (timeZone != current.timeZone) timeZone ?: "" else null,
                    defaultValue = if (defaultValue != current.defaultValue) defaultValue ?: "" else null,
                    labels = labels.takeIf { it != current.labels },
                    descriptions = descriptions.takeIf { it != current.descriptions },
                    enumOptionLabels = enumOptionLabels.takeIf { it != current.enumOptionLabels },
                    audited = declared.audited.takeIf { it != current.audited }
                )
            return change.takeUnless { it == UpdateFieldRequest() }
        }

        // as FieldSets stores them: trimmed, lower case, a repeated set once
        private fun sets(raw: List<List<String>>): List<List<String>> = raw.map { set -> set.map { it.trim().lowercase() } }.distinct()
    }

    companion object {
        val NAME = Regex("^[a-z][a-z0-9_-]{0,48}$")
    }
}
