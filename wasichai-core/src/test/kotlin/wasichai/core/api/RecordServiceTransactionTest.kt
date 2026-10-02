package wasichai.core.api

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeListener
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// ADR-038: RecordService opens no transaction and joins the caller's. R2DBC binds every write, the
// audit row and the listeners included, to the connection the operator holds; a failure rolls them all back.
@Import(RecordServiceTransactionTest.FailingListenerConfig::class)
class RecordServiceTransactionTest : WasichaiIntegrationTest() {
    // fails after the write, the way a listener of an app would, but only for the record it is told to
    class FailingListener : RecordChangeListener {
        override suspend fun recordChanged(change: RecordChange) {
            if (change.after?.get("codigo") == POISON || change.before?.get("codigo") == POISON) {
                error("listener refuses $POISON")
            }
        }
    }

    @TestConfiguration
    class FailingListenerConfig {
        @Bean
        fun failingListener(): RecordChangeListener = FailingListener()
    }

    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var transactions: TransactionalOperator

    @Autowired
    private lateinit var currentUser: CurrentUser

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    private lateinit var admin: String
    private lateinit var objectName: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        objectName = uniqueName("tx")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to objectName,
                    "label" to objectName,
                    "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"), mapOf("name" to "valor", "type" to "DECIMAL"))
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `a failure at the end of the transaction leaves no record and no audit row`() {
        val seeded = asAdmin { records.create(objectName, record("SEED", 1)) }

        assertThatThrownBy {
            asAdmin {
                transactions.executeAndAwait {
                    records.create(objectName, record("A-1", 10))
                    records.create(objectName, record("A-2", 20))
                    records.update(objectName, UUID.fromString(seeded.id), record("CHANGED", 2))
                    // the listener throws on the last write
                    records.create(objectName, record(POISON, 30))
                }
            }
        }.hasMessageContaining("listener refuses")

        // outside the transaction: only the seed, untouched, with its one audit row
        asAdmin {
            val page = records.list(objectName, everything)
            assertThat(page.content.map { it.attributes["codigo"] }).containsExactly("SEED")
            assertThat(records.get(objectName, UUID.fromString(seeded.id)).attributes["valor"].toString()).isEqualTo("1")
        }
        assertThat(auditRows()).isEqualTo(1)
    }

    @Test
    fun `an explicit throw in the block rolls everything back too`() {
        assertThatThrownBy {
            asAdmin {
                transactions.executeAndAwait {
                    records.create(objectName, record("B-1", 1))
                    records.create(objectName, record("B-2", 2))
                    error("the app gives up")
                }
            }
        }.hasMessageContaining("the app gives up")

        asAdmin { assertThat(records.list(objectName, everything).totalElements).isZero() }
        assertThat(auditRows()).isZero()
    }

    @Test
    fun `without a transaction a failure keeps the writes before it`() {
        // pins why the operator matters: RecordService alone commits each call
        assertThatThrownBy {
            asAdmin {
                records.create(objectName, record("C-1", 1))
                records.create(objectName, record(POISON, 2))
            }
        }.hasMessageContaining("listener refuses")

        asAdmin { assertThat(records.list(objectName, everything).totalElements).isEqualTo(2) }
        assertThat(auditRows()).isEqualTo(2)
    }

    @Test
    fun `when the block completes everything commits together, audit included`() {
        val ids =
            asAdmin {
                transactions.executeAndAwait {
                    val first = records.create(objectName, record("D-1", 1))
                    val second = records.create(objectName, record("D-2", 2))
                    records.update(objectName, UUID.fromString(first.id), record("D-1b", 3))
                    records.delete(objectName, UUID.fromString(records.create(objectName, record("D-3", 3)).id))
                    // the transaction sees its own writes
                    assertThat(records.get(objectName, UUID.fromString(second.id)).attributes["codigo"]).isEqualTo("D-2")
                    listOf(first.id, second.id)
                }
            }

        asAdmin {
            assertThat(records.list(objectName, everything).content.map { it.attributes["codigo"] })
                .containsExactlyInAnyOrder("D-1b", "D-2")
        }
        // three creates, one update, one delete
        assertThat(auditRows()).isEqualTo(5)
        assertThat(ids).hasSize(2)
    }

    @Test
    fun `the security context works inside the transaction`() {
        val seen =
            asAdmin {
                transactions.executeAndAwait {
                    val user = currentUser.require()
                    records.create(objectName, record("E-1", 1))
                    user to currentUser.require()
                }
            }

        assertThat(seen.first.email).isEqualTo(ADMIN_EMAIL)
        assertThat(seen.second).isEqualTo(seen.first)
        // the audit row carries the caller, so the identity reached the write inside the transaction
        assertThat(auditUsers()).containsExactly(seen.first.userId)
    }

    private val everything = RecordQuery(PageRequest(0, 50))

    private fun record(
        codigo: String,
        valor: Int
    ) = RecordRequest(mapOf("codigo" to codigo, "valor" to valor))

    // the caller of a RecordService is a request, which the web filter gives its context. a test has none, so it
    // gives the token's authentication to the coroutine's reactor context, as the filter would
    private fun <T> asAdmin(block: suspend () -> T): T =
        runBlocking {
            val jwt = decoder.decode(admin.removePrefix("Bearer ")).awaitSingle()
            mono { block() }
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(JwtAuthenticationToken(jwt)))
                .awaitSingle()
        }

    private fun auditRows(): Long =
        runBlocking {
            db
                .sql("SELECT count(*) AS n FROM ${schemas.metadata}.audit_log WHERE object_name = :name")
                .bind("name", objectName)
                .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        }

    private fun auditUsers(): List<UUID> =
        runBlocking {
            db
                .sql("SELECT user_id FROM ${schemas.metadata}.audit_log WHERE object_name = :name")
                .bind("name", objectName)
                .map { row, _ -> row.get("user_id", UUID::class.java)!! }
                .all()
                .collectList()
                .awaitSingle()
        }

    private companion object {
        const val POISON = "BOOM"
    }
}
