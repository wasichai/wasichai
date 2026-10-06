package wasichai.notifications

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.dao.DuplicateKeyException
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import java.time.Clock
import java.util.UUID

// a rule as REST answers it: the normalised definition and the object it watches
data class NotificationRuleView(
    @param:JsonProperty("object") @get:JsonProperty("object") val objectName: String,
    val name: String,
    val label: String,
    val enabled: Boolean,
    val field: String,
    val stages: List<RuleStage>,
    val untilDays: Int,
    val conditions: List<RuleCondition>,
    val audience: List<AudienceJson>,
    val title: String,
    val body: String?,
    val tab: String?
)

// date rules by hand (spec C). MANAGE_METADATA on the object, as automations: a rule reads every field of it.
// save is strict: format and fields by NotificationRules.validate, recipients by the directories.
class NotificationRuleService(
    private val currentUser: CurrentUser,
    private val metadata: MetadataService,
    private val rules: NotificationRuleRepository,
    private val audience: AudienceResolver,
    private val runner: RuleNotifications,
    private val writer: NotificationWriter,
    private val clock: Clock,
    transactions: () -> TransactionalOperator
) {
    // resolved on first write, as the writer does
    private val operator by lazy(transactions)

    // every rule of the organization: MANAGE_METADATA on every object
    suspend fun listAll(): List<NotificationRuleView> {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        return rules.listAll(user.organizationId).map { it.toView() }
    }

    suspend fun list(objectName: String): List<NotificationRuleView> {
        val (organizationId, definition) = objectFor(objectName)
        return rules.listByObject(organizationId, definition.obj.id).map { it.toView() }
    }

    suspend fun get(
        objectName: String,
        name: String
    ): NotificationRuleView {
        val (organizationId, definition) = objectFor(objectName)
        return ruleOf(organizationId, definition, name).toView()
    }

    // runs at once: the inbox shows what the rule means before the loop next comes round
    suspend fun create(
        objectName: String,
        request: NotificationRuleDefinition
    ): NotificationRuleView {
        val (organizationId, definition) = objectFor(objectName)
        val rule = validate(organizationId, definition, request)
        if (rules.findByName(organizationId, rule.name) != null) throw taken(rule.name)
        try {
            rules.insert(organizationId, definition.obj.id, rule)
        } catch (_: DuplicateKeyException) {
            // another request took the name between the read and the insert
            throw taken(rule.name)
        }
        if (rule.enabled) runner.run(organizationId, rule, definition, clock.instant())
        return stored(organizationId, rule.name)
    }

    // full replace. enabled: runs at once. disabled: what it published resolves, in the same transaction
    suspend fun replace(
        objectName: String,
        name: String,
        request: NotificationRuleDefinition
    ): NotificationRuleView {
        val (organizationId, definition) = objectFor(objectName)
        val existing = ruleOf(organizationId, definition, name)
        if (request.name != name) throw ValidationException(RuleCheck.MESSAGE, "name", "must be '$name', the rule in the path; a rule is not renamed")
        val rule = validate(organizationId, definition, request)
        if (rule.enabled) {
            rules.update(organizationId, existing.id, rule)
            runner.run(organizationId, rule, definition, clock.instant())
        } else {
            operator.executeAndAwait {
                rules.update(organizationId, existing.id, rule)
                writer.resolveAll(organizationId, Sources.rule(name))
            }
        }
        return stored(organizationId, name)
    }

    // gone with everything it published, in one transaction
    suspend fun delete(
        objectName: String,
        name: String
    ) {
        val (organizationId, definition) = objectFor(objectName)
        val existing = ruleOf(organizationId, definition, name)
        operator.executeAndAwait {
            rules.delete(organizationId, existing.id)
            writer.resolveAll(organizationId, Sources.rule(name))
        }
    }

    // a disabled rule publishes nothing: enabling it is the way to run it
    suspend fun run(
        objectName: String,
        name: String
    ): ReconcileResult {
        val (organizationId, definition) = objectFor(objectName)
        val existing = ruleOf(organizationId, definition, name)
        if (!existing.rule.enabled) throw ConflictException("Notification rule '$name' is disabled")
        return runner.run(organizationId, existing.rule, definition, clock.instant())
    }

    // 404 for an unknown object (MetadataService says so), 403 without MANAGE_METADATA on it
    private suspend fun objectFor(objectName: String): Pair<UUID, ObjectDefinition> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.MANAGE_METADATA, definition.obj.id)
        return user.organizationId to definition
    }

    private suspend fun ruleOf(
        organizationId: UUID,
        definition: ObjectDefinition,
        name: String
    ): StoredRule {
        val stored = rules.findByName(organizationId, name)
        if (stored == null || stored.objectId != definition.obj.id) {
            throw NotFoundException("Object '${definition.obj.name}' has no notification rule '$name'")
        }
        return stored
    }

    private suspend fun stored(
        organizationId: UUID,
        name: String
    ): NotificationRuleView = (rules.findByName(organizationId, name) ?: throw NotFoundException("Notification rule '$name' does not exist")).toView()

    // format and fields first; then every recipient must exist (strict, audience[i])
    private suspend fun validate(
        organizationId: UUID,
        definition: ObjectDefinition,
        request: NotificationRuleDefinition
    ): NotificationRuleDefinition {
        val rule = NotificationRules.validate(request, definition).orThrow()
        val resolved = audience.resolve(organizationId, rule.audience.toAudiences(), strict = true)
        if (resolved.violations.isNotEmpty()) throw ValidationException(RuleCheck.MESSAGE, resolved.violations)
        return rule
    }

    private fun taken(name: String) = ConflictException("Notification rule '$name' already exists")

    private fun StoredRule.toView() =
        NotificationRuleView(
            objectName = objectName,
            name = rule.name,
            label = rule.label,
            enabled = rule.enabled,
            field = rule.field,
            stages = rule.stages,
            untilDays = rule.untilDays,
            conditions = rule.conditions,
            audience = rule.audience,
            title = rule.title,
            body = rule.body,
            tab = rule.tab
        )
}
