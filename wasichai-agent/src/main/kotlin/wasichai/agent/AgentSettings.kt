package wasichai.agent

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.common.ValidationException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.platform.WasichaiSchemas
import wasichai.core.platform.bindNullable
import java.util.UUID

/** The assistant as one user has it: the model in use, their own pick, and what they may pick from. */
data class AgentSettings(
    val model: String,
    // null: they never picked, the default applies
    val selected: String?,
    val defaultModel: String,
    val models: List<String>
)

// open: a test without a database stands in for it
open class AgentSettingsRepository(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas
) {
    open suspend fun model(userId: UUID): String? =
        db
            .sql("SELECT model FROM ${schemas.metadata}.agent_user_settings WHERE user_id = :userId")
            .bind("userId", userId)
            .map { row, _ -> row.get("model", String::class.java) ?: "" }
            .one()
            .awaitFirstOrNull()
            ?.ifEmpty { null }

    open suspend fun save(
        userId: UUID,
        model: String?
    ) {
        db
            .sql(
                "INSERT INTO ${schemas.metadata}.agent_user_settings (user_id, model) VALUES (:userId, :model) " +
                    "ON CONFLICT (user_id) DO UPDATE SET model = EXCLUDED.model, updated_at = now()"
            ).bind("userId", userId)
            .bindNullable("model", model)
            .fetch()
            .rowsUpdated()
            .awaitSingleOrNull()
    }
}

// the model a user picks for the assistant (ADR-087). only from the app's list: a model the app did
// not offer is a cost the organization did not agree to.
class AgentSettingsService(
    private val repository: AgentSettingsRepository,
    private val properties: AgentProperties
) {
    suspend fun get(user: AuthenticatedUser): AgentSettings {
        val selected = repository.model(user.userId)
        return AgentSettings(effective(selected), selected, properties.model, properties.allowedModels)
    }

    // a pick the app later dropped from its list falls back to the default, it does not fail the run
    suspend fun effectiveModel(user: AuthenticatedUser): String = effective(repository.model(user.userId))

    /** null or blank clears the pick. */
    suspend fun update(
        user: AuthenticatedUser,
        model: String?
    ): AgentSettings {
        val pick = model?.trim()?.ifEmpty { null }
        if (pick != null && pick !in properties.allowedModels) {
            throw ValidationException("Unknown model", "model", "must be one of ${properties.allowedModels.joinToString(", ")}")
        }
        repository.save(user.userId, pick)
        return get(user)
    }

    private fun effective(selected: String?): String = selected?.takeIf { it in properties.allowedModels } ?: properties.model
}
