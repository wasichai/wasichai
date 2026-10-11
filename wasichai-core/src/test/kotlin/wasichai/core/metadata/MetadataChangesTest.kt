package wasichai.core.metadata

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.reactive.AbstractReactiveTransactionManager
import org.springframework.transaction.reactive.GenericReactiveTransaction
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import reactor.core.publisher.Mono
import wasichai.core.identity.AdminOperation
import java.time.Duration
import java.util.UUID

// issue 105 (ADR-066): a change drops the cached definitions at once and when its transaction ends, a transaction
// that changed the model never reads or fills the cache, and listeners hear of a change only once it committed
class MetadataChangesTest {
    private val organizationId = UUID.randomUUID()

    // a real reactive transaction, synchronizations and all, with no database behind it
    private class NoDatabaseTransactions : AbstractReactiveTransactionManager() {
        override fun doGetTransaction(synchronizationManager: TransactionSynchronizationManager): Any = Any()

        override fun doBegin(
            synchronizationManager: TransactionSynchronizationManager,
            transaction: Any,
            definition: TransactionDefinition
        ): Mono<Void> = Mono.empty()

        override fun doCommit(
            synchronizationManager: TransactionSynchronizationManager,
            status: GenericReactiveTransaction
        ): Mono<Void> = Mono.empty()

        override fun doRollback(
            synchronizationManager: TransactionSynchronizationManager,
            status: GenericReactiveTransaction
        ): Mono<Void> = Mono.empty()
    }

    private val transactions = TransactionalOperator.create(NoDatabaseTransactions())

    private class Heard : MetadataChangeListener {
        val changes = mutableListOf<MetadataChange>()

        override suspend fun metadataChanged(change: MetadataChange) {
            changes += change
        }
    }

    private class Loads {
        var count = 0
        val definition =
            ObjectDefinition(
                CustomObject(UUID.randomUUID(), UUID.randomUUID(), "predio", "Predio", "Predios", null, true, "predio__0000", null, null),
                emptyList()
            )

        suspend fun load(): ObjectDefinition {
            count++
            return definition
        }
    }

    private fun change(operation: AdminOperation = AdminOperation.UPDATE) =
        MetadataChange(organizationId, MetadataChangeKind.FIELD, operation, UUID.randomUUID(), "predio", "area")

    @Test
    fun `without a transaction the listeners are told at once`() =
        runTest {
            val heard = Heard()
            val changes = MetadataChanges(ObjectDefinitionCache(Duration.ofSeconds(5)), listOf(heard))

            changes.changed(change())

            assertThat(heard.changes).hasSize(1)
        }

    @Test
    fun `inside a transaction the listeners are told after the commit`() =
        runTest {
            val heard = Heard()
            val changes = MetadataChanges(ObjectDefinitionCache(Duration.ofSeconds(5)), listOf(heard))
            val told = change(AdminOperation.CREATE)

            transactions.executeAndAwait {
                changes.changed(told)
                assertThat(heard.changes).isEmpty()
            }

            assertThat(heard.changes).containsExactly(told)
        }

    @Test
    fun `a rolled back change is never told`() =
        runTest {
            val heard = Heard()
            val changes = MetadataChanges(ObjectDefinitionCache(Duration.ofSeconds(5)), listOf(heard))

            runCatching {
                transactions.executeAndAwait {
                    changes.changed(change())
                    error("the DDL failed")
                }
            }

            assertThat(heard.changes).isEmpty()
        }

    @Test
    fun `a listener that throws does not stop the next one`() =
        runTest {
            val heard = Heard()
            val failing =
                object : MetadataChangeListener {
                    override suspend fun metadataChanged(change: MetadataChange) = error("app cache is down")
                }
            val changes = MetadataChanges(ObjectDefinitionCache(Duration.ofSeconds(5)), listOf(failing, heard))

            changes.changed(change())

            assertThat(heard.changes).hasSize(1)
        }

    @Test
    fun `a transaction that changed the model reads it from the database and caches none of it`() =
        runTest {
            val changes = MetadataChanges(ObjectDefinitionCache(Duration.ofSeconds(5)))
            val loads = Loads()

            transactions.executeAndAwait {
                changes.changed(change())
                // its own uncommitted model, every time, and nobody else's to keep
                changes.definition(organizationId, "predio", loads::load)
                changes.definition(organizationId, "predio", loads::load)
            }
            assertThat(loads.count).isEqualTo(2)

            changes.definition(organizationId, "predio", loads::load)
            changes.definition(organizationId, "predio", loads::load)
            assertThat(loads.count).isEqualTo(3)
        }

    @Test
    fun `a transaction that changed nothing uses the cache`() =
        runTest {
            val changes = MetadataChanges(ObjectDefinitionCache(Duration.ofSeconds(5)))
            val loads = Loads()

            transactions.executeAndAwait {
                changes.definition(organizationId, "predio", loads::load)
                changes.definition(organizationId, "predio", loads::load)
            }

            assertThat(loads.count).isEqualTo(1)
        }

    @Test
    fun `a change drops what was cached before it`() =
        runTest {
            val changes = MetadataChanges(ObjectDefinitionCache(Duration.ofSeconds(5)))
            val loads = Loads()

            changes.definition(organizationId, "predio", loads::load)
            changes.changed(change())
            changes.definition(organizationId, "predio", loads::load)

            assertThat(loads.count).isEqualTo(2)
        }

    @Test
    fun `a deleted organization leaves nothing cached and tells nobody`() =
        runTest {
            val heard = Heard()
            val changes = MetadataChanges(ObjectDefinitionCache(Duration.ofSeconds(5)), listOf(heard))
            val loads = Loads()

            changes.definition(organizationId, "predio", loads::load)
            transactions.executeAndAwait { changes.organizationRemoved(organizationId) }
            changes.definition(organizationId, "predio", loads::load)

            assertThat(loads.count).isEqualTo(2)
            assertThat(heard.changes).isEmpty()
        }

    // issue 108 (ADR-067): a module's setup (a workflow, an automation, a rule) is kept and dropped like the model

    private data class WorkflowOf(
        val objectId: UUID
    )

    private class Values {
        var count = 0

        suspend fun load(): String {
            count++
            return "flujo $count"
        }
    }

    @Test
    fun `a setup write drops what was cached before and after it, and tells nobody`() =
        runTest {
            val heard = Heard()
            val changes = MetadataChanges(ObjectDefinitionCache(Duration.ofSeconds(5)), listOf(heard))
            val key = WorkflowOf(UUID.randomUUID())
            val values = Values()

            changes.cached(organizationId, key, values::load)
            // a read that lands while the write runs, outside any transaction: not kept past the write
            changes.setupChanging(organizationId) { changes.cached(organizationId, key, values::load) }
            val after = changes.cached(organizationId, key, values::load)

            assertThat(after).isEqualTo("flujo 3")
            assertThat(changes.cached(organizationId, key, values::load)).isEqualTo("flujo 3")
            assertThat(heard.changes).isEmpty()
        }

    @Test
    fun `a transaction that changed the setup reads it from the database and caches none of it`() =
        runTest {
            val changes = MetadataChanges(ObjectDefinitionCache(Duration.ofSeconds(5)))
            val key = WorkflowOf(UUID.randomUUID())
            val values = Values()

            transactions.executeAndAwait {
                changes.setupChanging(organizationId) { }
                changes.cached(organizationId, key, values::load)
                changes.cached(organizationId, key, values::load)
            }
            assertThat(values.count).isEqualTo(2)

            changes.cached(organizationId, key, values::load)
            changes.cached(organizationId, key, values::load)
            assertThat(values.count).isEqualTo(3)
        }

    @Test
    fun `a model change drops the setup too`() =
        runTest {
            val changes = MetadataChanges(ObjectDefinitionCache(Duration.ofSeconds(5)))
            val key = WorkflowOf(UUID.randomUUID())
            val values = Values()

            changes.cached(organizationId, key, values::load)
            changes.changed(change())
            changes.cached(organizationId, key, values::load)

            assertThat(values.count).isEqualTo(2)
        }
}
