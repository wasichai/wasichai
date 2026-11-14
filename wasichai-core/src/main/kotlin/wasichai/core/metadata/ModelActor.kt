package wasichai.core.metadata

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.withContext
import reactor.util.context.Context
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.identity.AdminAudit
import wasichai.core.identity.AdminEntity
import wasichai.core.identity.AdminOperation
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.platform.ChangeOrigin
import java.util.UUID

// who changes the model: a person with MANAGE_METADATA, or the platform ensuring an app's declared model (ADR-081)
internal class ModelActor(
    val organizationId: UUID,
    // null: the platform
    val user: AuthenticatedUser?,
    // the declared model the platform ensures. null for a person
    val model: String?
) {
    // what a declared model owns is its own: a person's change is a 409, and so is another model's
    fun requireMayChange(
        owner: String?,
        what: String
    ) {
        if (owner == null || owner == model) return
        throw ConflictException("$what is declared by '$owner': only the app that declares it changes it")
    }

    // the admin trail (ADR-049): the person's entry, or the platform's with no user and its model as the source (ADR-050)
    suspend fun record(
        audit: AdminAudit,
        entity: AdminEntity,
        id: UUID,
        operation: AdminOperation,
        before: Map<String, Any?>?,
        after: Map<String, Any?>?
    ) {
        if (user !=
            null
        ) {
            audit.record(user, entity, id, operation, before, after)
        } else {
            audit.recordPlatform(organizationId, entity, id, operation, before, after)
        }
    }
}

/**
 * The platform as the one who changes a model, for [DeclaredModels] only (ADR-081). Like the platform caller of
 * records (ADR-039) it lives in the Reactor context under a private key, so it reaches every metadata call the
 * block makes, through transactions too, and nothing a request carries can put it there. Unlike it, it also runs
 * inside a request: provisioning a tenant ensures the new tenant's model in the provisioner's transaction.
 */
internal object ModelOwner {
    private val KEY = Any()

    private data class Run(
        val organizationId: UUID,
        val model: String
    )

    // the block's changes are [model]'s, in [organizationId], labelled model:<name> in the audit
    suspend fun <T> run(
        organizationId: UUID,
        model: String,
        block: suspend () -> T
    ): T {
        val reactor = currentCoroutineContext()[ReactorContext]?.context ?: Context.empty()
        val labelled = ChangeOrigin.label(reactor.put(KEY, Run(organizationId, model)), source(model))
        return withContext(labelled.asCoroutineContext()) { block() }
    }

    // the platform inside run, else the caller, who must hold MANAGE_METADATA
    suspend fun actor(currentUser: CurrentUser): ModelActor {
        currentCoroutineContext()[ReactorContext]?.context?.getOrDefault<Run>(KEY, null)?.let {
            return ModelActor(it.organizationId, null, it.model)
        }
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        return ModelActor(user.organizationId, user, null)
    }

    fun source(model: String): String = "model:$model"
}
