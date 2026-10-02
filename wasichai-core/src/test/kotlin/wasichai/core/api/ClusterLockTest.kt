package wasichai.core.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.platform.ClusterLock
import wasichai.test.WasichaiIntegrationTest
import java.util.Collections

// ADR-039: one holder per database. every tryLock takes a connection of its own, so two calls here
// are two sessions, exactly like two replicas.
class ClusterLockTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var locks: ClusterLock

    @Autowired
    private lateinit var transactions: TransactionalOperator

    @Autowired
    private lateinit var db: DatabaseClient

    @Test
    fun `a held key is refused to every other session until it is released`(): Unit =
        runBlocking {
            val key = uniqueName("lock")
            val first = locks.tryLock(key)!!

            assertThat(locks.tryLock(key)).isNull()
            assertThat(locks.tryLock(key)).isNull()
            // another key is another lock
            locks.tryLock(uniqueName("other"))!!.release()

            first.release()
            first.release()
            val second = locks.tryLock(key)
            assertThat(second).isNotNull()
            second!!.release()
        }

    @Test
    fun `use releases the lease when the block throws`(): Unit =
        runBlocking {
            val key = uniqueName("lock")

            assertThatThrownBy { runBlocking { locks.tryLock(key)!!.use { error("publisher failed") } } }
                .hasMessageContaining("publisher failed")

            val again = locks.tryLock(key)
            assertThat(again).isNotNull()
            assertThat(again!!.use { "published" }).isEqualTo("published")
            // and use released this one too
            locks.tryLock(key)!!.release()
        }

    @Test
    fun `a transaction lock keeps sessions out while its block runs, and goes with the commit`(): Unit =
        runBlocking {
            val key = uniqueName("lock")

            val seen = locks.withXactLock(key) { locks.tryLock(key) }

            assertThat(seen).isNull()
            locks.tryLock(key)!!.release()
        }

    @Test
    fun `a transaction lock joins the caller's transaction and lasts until it commits`(): Unit =
        runBlocking {
            val key = uniqueName("lock")

            val afterBlock =
                transactions.executeAndAwait {
                    locks.withXactLock(key) { "locked" }
                    // the block is over, the caller's transaction is not: still held
                    locks.tryLock(key)
                }

            assertThat(afterBlock).isNull()
            locks.tryLock(key)!!.release()
        }

    @Test
    fun `transaction locks on one key run one at a time`(): Unit =
        runBlocking {
            val key = uniqueName("lock")
            val order = Collections.synchronizedList(mutableListOf<String>())
            val firstIn = CompletableDeferred<Unit>()
            val letFirstGo = CompletableDeferred<Unit>()

            val first =
                async {
                    locks.withXactLock(key) {
                        order += "first in"
                        firstIn.complete(Unit)
                        letFirstGo.await()
                        order += "first out"
                    }
                }
            firstIn.await()
            val second = async { locks.withXactLock(key) { order += "second in" } }
            // the second waits on the database, not here. without a lock it would just finish
            withTimeout(30_000) { while (!second.isCompleted && !waitingOn(key)) delay(50) }
            order += "released"
            letFirstGo.complete(Unit)
            withTimeout(10_000) {
                first.await()
                second.await()
            }

            assertThat(order).containsExactly("first in", "released", "first out", "second in")
        }

    // pg_locks splits a bigint advisory key: high half in classid, low half in objid
    private suspend fun waitingOn(key: String): Boolean {
        val id = ClusterLock.lockId(key)
        return db
            .sql(
                """
                SELECT count(*) AS n FROM pg_locks
                WHERE locktype = 'advisory' AND NOT granted AND objsubid = 1
                  AND classid = CAST(:high AS bigint)::oid AND objid = CAST(:low AS bigint)::oid
                """.trimIndent()
            ).bind("high", id ushr 32)
            .bind("low", id and 0xffffffffL)
            .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
            .one()
            .awaitSingle() > 0
    }
}
