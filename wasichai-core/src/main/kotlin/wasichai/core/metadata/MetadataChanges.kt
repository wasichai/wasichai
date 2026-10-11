package wasichai.core.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.UUID

/**
 * The cached definitions and every change to them, in one place (ADR-066). Whatever writes objects, fields or
 * relationships says so here: the organization's definitions are dropped at once and again when the transaction ends,
 * committed or not, and [MetadataChangeListener]s hear of it after the commit.
 *
 * Within a transaction that changed the model, definitions are read from the database and never cached: it sees its
 * own uncommitted model, and nobody else ever does (ADR-062's first objection).
 */
class MetadataChanges(
    private val cache: ObjectDefinitionCache,
    private val listeners: List<MetadataChangeListener> = emptyList()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // this instance's transaction resource: what the transaction changed so far
    private val pendingKey = Any()

    private class Pending {
        val organizations = mutableSetOf<UUID>()
        val changes = mutableListOf<MetadataChange>()
    }

    suspend fun definition(
        organizationId: UUID,
        name: String,
        load: suspend () -> ObjectDefinition
    ): ObjectDefinition = if (changedHere()) load() else cache.byName(organizationId, name, load)

    suspend fun definitionById(
        organizationId: UUID,
        id: UUID,
        load: suspend () -> ObjectDefinition
    ): ObjectDefinition = if (changedHere()) load() else cache.byId(organizationId, id, load)

    // anything else a request reads of the organization's model (ADR-067): a relationship, an object's workflow,
    // automations or rules. kept like a definition, under the reader's own key, and dropped with it
    suspend fun <T> cached(
        organizationId: UUID,
        key: Any,
        load: suspend () -> T
    ): T = if (changedHere()) load() else cache.value(organizationId, key, load)

    // [write] changes something [cached] keeps that is not an object, field or relationship (a workflow, an automation,
    // a rule): the organization is dropped before (the transaction then reads its own, and at its end again) and after
    // (outside a transaction, a read in between kept the old value). no MetadataChangeListener is told: they hear of
    // the model, not of a module's setup
    suspend fun <T> setupChanging(
        organizationId: UUID,
        write: suspend () -> T
    ): T {
        cache.evict(organizationId)
        synchronization()?.let { pending(it, organizationId) }
        return write().also { cache.evict(organizationId) }
    }

    // inside the transaction that writes it, or right away when there is none
    suspend fun changed(change: MetadataChange) {
        cache.evict(change.organizationId)
        val transaction = synchronization()
        if (transaction == null) {
            tell(change)
            return
        }
        pending(transaction, change.organizationId).changes += change
    }

    // a tenant deleted: nothing of it stays cached, and no listener is told (the tenant is gone)
    suspend fun organizationRemoved(organizationId: UUID) {
        cache.evict(organizationId)
        synchronization()?.let { pending(it, organizationId) }
    }

    private suspend fun changedHere(): Boolean = cache.enabled && synchronization()?.hasResource(pendingKey) == true

    // the first change of a transaction registers what runs when it ends
    private fun pending(
        transaction: TransactionSynchronizationManager,
        organizationId: UUID
    ): Pending {
        val pending =
            transaction.getResource(pendingKey) as Pending?
                ?: Pending().also { created ->
                    transaction.bindResource(pendingKey, created)
                    transaction.registerSynchronization(
                        object : TransactionSynchronization {
                            override fun afterCompletion(status: Int): Mono<Void> =
                                mono {
                                    created.organizations.forEach(cache::evict)
                                    if (status == TransactionSynchronization.STATUS_COMMITTED) created.changes.forEach { tell(it) }
                                }.then()
                        }
                    )
                }
        pending.organizations += organizationId
        return pending
    }

    private suspend fun tell(change: MetadataChange) {
        listeners.forEach { listener ->
            try {
                listener.metadataChanged(change)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("{} failed on {} {} of '{}': {}", listener.javaClass.name, change.operation, change.kind, change.objectName, e.message, e)
            }
        }
    }

    // the transaction's synchronization, null when none is active here
    private suspend fun synchronization(): TransactionSynchronizationManager? =
        try {
            TransactionSynchronizationManager
                .forCurrentTransaction()
                .awaitFirstOrNull()
                ?.takeIf { it.isSynchronizationActive }
        } catch (_: NoTransactionException) {
            null
        }

    companion object {
        // nothing cached, nobody told: the definitions load every time, as before ADR-066
        fun uncached(): MetadataChanges = MetadataChanges(ObjectDefinitionCache(Duration.ZERO))
    }
}
