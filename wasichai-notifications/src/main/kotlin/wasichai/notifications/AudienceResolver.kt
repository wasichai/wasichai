package wasichai.notifications

import com.fasterxml.jackson.annotation.JsonInclude
import org.slf4j.LoggerFactory
import wasichai.core.common.FieldViolation
import wasichai.core.identity.OrgUnitDirectory
import wasichai.core.identity.RoleDirectory
import wasichai.core.identity.UserDirectory
import java.util.UUID

// one entry of the admin view's audience: what the admin wrote, a user with its email
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AudienceView(
    val type: String,
    val value: String?,
    val email: String? = null
)

// audience -> stored targets, through core's directories (ADR-045). one lookup per kind, never per entry.
// strict (REST): an unknown recipient is a violation naming audience[i].
// lenient (Kotlin): it is dropped with a WARN, so a person who left never rolls back business work.
class AudienceResolver(
    private val users: UserDirectory,
    private val roles: RoleDirectory,
    private val units: OrgUnitDirectory
) {
    data class Resolved(
        // in audience order, distinct
        val targets: List<StoredTarget>,
        // strict only
        val violations: List<FieldViolation>,
        // lenient only: why each entry was left out
        val dropped: List<String>
    )

    suspend fun resolve(
        organizationId: UUID,
        audience: List<Audience>,
        strict: Boolean
    ): Resolved {
        val userIds = audience.filterIsInstance<Audience.User>().map { it.id }
        val emails = audience.filterIsInstance<Audience.Email>().map { email(it) }
        val codes = audience.filterIsInstance<Audience.Unit>().map { code(it) }
        val existing = if (userIds.isEmpty()) emptySet() else users.existing(organizationId, userIds)
        val byEmail = if (emails.isEmpty()) emptyMap() else users.idsByEmail(organizationId, emails)
        val roleNames = if (audience.none { it is Audience.Role }) emptySet() else roles.namesOf(organizationId)
        val byCode = if (codes.isEmpty()) emptyMap() else units.idsByCode(organizationId, codes)

        val targets = mutableListOf<StoredTarget>()
        val violations = mutableListOf<FieldViolation>()
        val dropped = mutableListOf<String>()
        audience.forEachIndexed { i, entry ->
            val target =
                when (entry) {
                    Audience.All -> StoredTarget.all()
                    is Audience.User -> entry.id.takeIf { it in existing }?.let { StoredTarget.user(it) }
                    is Audience.Email -> byEmail[email(entry)]?.let { StoredTarget.user(it) }
                    is Audience.Role -> role(entry).takeIf { it in roleNames }?.let { StoredTarget.role(it) }
                    is Audience.Unit -> byCode[code(entry)]?.let { StoredTarget.unit(it) }
                }
            if (target != null) {
                targets += target
                return@forEachIndexed
            }
            val reason = unknown(entry)
            if (strict) violations += FieldViolation("audience[$i]", reason) else dropped += reason
        }
        if (dropped.isNotEmpty()) log.warn("organization {}: recipients dropped: {}", organizationId, dropped.joinToString("; "))
        return Resolved(targets.distinct(), violations, dropped)
    }

    suspend fun describe(
        organizationId: UUID,
        targets: List<StoredTarget>
    ): List<AudienceView> = describeAll(organizationId, mapOf(KEY to targets)).getValue(KEY)

    // a page of notifications at once: one email and one code lookup in all
    suspend fun <K> describeAll(
        organizationId: UUID,
        targets: Map<K, List<StoredTarget>>
    ): Map<K, List<AudienceView>> {
        val all = targets.values.flatten()
        val emails = users.emailsById(organizationId, all.mapNotNull { it.userId }.distinct())
        val codes = units.codesById(organizationId, all.mapNotNull { it.unitId }.distinct())
        return targets.mapValues { (_, list) ->
            list.map { target ->
                when (target.type) {
                    TargetType.ALL -> AudienceView(TargetType.ALL.name, null)
                    TargetType.USER -> AudienceView(TargetType.USER.name, target.userId.toString(), emails[target.userId])
                    TargetType.ROLE -> AudienceView(TargetType.ROLE.name, target.roleName)
                    // a deleted unit takes its targets with it; the id is only a fallback
                    TargetType.UNIT -> AudienceView(TargetType.UNIT.name, codes[target.unitId] ?: target.unitId.toString())
                }
            }
        }
    }

    private fun unknown(entry: Audience): String =
        when (entry) {
            Audience.All -> error("ALL is always known")
            is Audience.User -> "unknown user '${entry.id}'"
            is Audience.Email -> "unknown email '${email(entry)}'"
            is Audience.Role -> "unknown role '${role(entry)}'"
            is Audience.Unit -> "unknown unit '${code(entry)}'"
        }

    // validation normalised these already; again here so a direct caller matches the directories
    private fun email(entry: Audience.Email) = entry.email.trim().lowercase()

    private fun role(entry: Audience.Role) = entry.name.trim().uppercase()

    private fun code(entry: Audience.Unit) = OrgUnitDirectory.normaliseCode(entry.code)

    private companion object {
        private val log = LoggerFactory.getLogger(AudienceResolver::class.java)
        private const val KEY = ""
    }
}
