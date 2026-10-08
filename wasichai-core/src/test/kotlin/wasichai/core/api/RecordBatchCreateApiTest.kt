package wasichai.core.api

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
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
import org.springframework.util.ClassUtils
import reactor.core.publisher.Mono
import wasichai.core.common.PageRequest
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeListener
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.identity.JwtService
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.UUID

// issue 77: createAll looks the definition, the access and each relation target up once per batch, fails as a
// create loop would, and stores all of a batch or nothing. statements counted on the real driver
@Import(RecordBatchCreateApiTest.CountingConfig::class)
class RecordBatchCreateApiTest : WasichaiIntegrationTest() {
    // the SQL a call sent, on connections taken inside it: background work of the app is not counted
    class Statements {
        val sql: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val count: Int get() = sql.size

        fun matching(fragment: String): Int = sql.count { fragment in it }
    }

    // fails after the write, on the record it is told to, the way a listener of an app would
    class FailingListener : RecordChangeListener {
        override suspend fun recordChanged(change: RecordChange) {
            if (change.after?.get("codigo") == POISON) error("listener refuses $POISON")
        }
    }

    @TestConfiguration
    class CountingConfig {
        @Bean
        fun batchFailingListener(): RecordChangeListener = FailingListener()

        companion object {
            // static: a post-processor is made before the other beans
            @Bean
            @JvmStatic
            fun statementCounting(): BeanPostProcessor =
                object : BeanPostProcessor {
                    override fun postProcessAfterInitialization(
                        bean: Any,
                        beanName: String
                    ): Any = if (bean is ConnectionFactory) counting(bean) else bean
                }

            // the factory as it is, but a connection taken with Statements in the reactor context counts what it runs
            private fun counting(factory: ConnectionFactory): Any =
                proxy(factory) { method, args ->
                    val result = invoke(factory, method, args)
                    if (method.name == "create" && method.parameterCount == 0) {
                        Mono.deferContextual { context ->
                            @Suppress("UNCHECKED_CAST")
                            val connection = Mono.from(result as org.reactivestreams.Publisher<Connection>)
                            if (context.hasKey(Statements::class.java)) {
                                val statements = context.get(Statements::class.java)
                                connection.map { counted(it, statements) }
                            } else {
                                connection
                            }
                        }
                    } else {
                        result
                    }
                }

            private fun counted(
                connection: Connection,
                statements: Statements
            ): Any =
                proxy(connection) { method, args ->
                    when (method.name) {
                        "createStatement" -> statements.sql += args!![0] as String
                        "createBatch" -> statements.sql += "<batch>"
                    }
                    invoke(connection, method, args)
                }

            private fun proxy(
                target: Any,
                handler: (Method, Array<out Any?>?) -> Any?
            ): Any =
                Proxy.newProxyInstance(
                    target.javaClass.classLoader,
                    ClassUtils.getAllInterfacesForClass(target.javaClass),
                    InvocationHandler { _, method, args -> handler(method, args) }
                )

            private fun invoke(
                target: Any,
                method: Method,
                args: Array<out Any?>?
            ): Any? =
                try {
                    method.invoke(target, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
        }
    }

    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var transactions: TransactionalOperator

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    private lateinit var admin: String
    private lateinit var cobro: String
    private lateinit var predio: String
    private lateinit var contribuyente: String
    private lateinit var servicio: String
    private lateinit var parametro: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        cobro = uniqueName("cobro")
        predio = uniqueName("predio")
        contribuyente = uniqueName("contrib")
        servicio = uniqueName("servicio")
        parametro = uniqueName("param")
        listOf(predio, contribuyente, servicio, parametro).forEach { createObject(it, listOf(text("codigo"))) }
        createObject(
            cobro,
            listOf(
                text("codigo"),
                relation("predio", predio),
                relation("contribuyente", contribuyente),
                relation("servicio", servicio),
                relation("parametro", parametro)
            )
        )
    }

    @Test
    fun `48 records with repeated targets cost at most 10 statements each, fewer than a create loop`() {
        val (batch, loop) = measured(admin)

        println("issue 77, ADMIN: createAll ${batch.count} statements (${perRecord(batch)}/record), create loop ${loop.count} (${perRecord(loop)}/record)")
        assertThat(batch.count.toDouble() / SIZE).describedAs("createAll per record:\n%s", batch.sql.joinToString("\n")).isLessThanOrEqualTo(10.0)
        assertThat(loop.count).isGreaterThan(batch.count)
        // once per target object for the batch, against once per record and target object for the loop
        assertThat(batch.matching("id = ANY(")).isEqualTo(4)
        assertThat(loop.matching("id = ANY(")).isEqualTo(4 * SIZE)
        asAdmin { assertThat(records.list(cobro, everything).totalElements).isEqualTo(2L * SIZE) }
        assertThat(auditOf(cobro)).isEqualTo(2L * SIZE)
    }

    @Test
    fun `a scoped reader's relation lookups run once per target object for the whole batch`() {
        val role = newRole()
        grant(role, cobro to "CREATE", cobro to "READ", predio to "READ", contribuyente to "READ", servicio to "READ", parametro to "READ")
        val member = newUserToken(role)

        val (batch, loop) = measured(member)

        println("issue 77, scoped: createAll ${batch.count} statements (${perRecord(batch)}/record), create loop ${loop.count} (${perRecord(loop)}/record)")
        assertThat(batch.count.toDouble() / SIZE).describedAs("createAll per record:\n%s", batch.sql.joinToString("\n")).isLessThanOrEqualTo(10.0)
        assertThat(loop.count).isGreaterThan(batch.count)
        assertThat(batch.matching("id = ANY(")).isEqualTo(4)
        assertThat(loop.matching("id = ANY(")).isEqualTo(4 * SIZE)
    }

    @Test
    fun `a missing target fails the batch with create's error for that record, and nothing of it is stored`() {
        val batch = requests(10).toMutableList()
        batch[6] = RecordRequest(batch[6].attributes + ("contribuyente" to UUID.randomUUID().toString()))

        val expected = runCatching { asAdmin { records.create(cobro, batch[6]) } }.exceptionOrNull()
        val thrown = runCatching { asAdmin { records.createAll(cobro, batch) } }.exceptionOrNull()

        assertSameRefusal(thrown, expected, "contribuyente")
        asAdmin { assertThat(records.list(cobro, everything).totalElements).isZero() }
        assertThat(auditOf(cobro)).isZero()
    }

    @Test
    fun `a target the reader cannot read fails the batch as create does, and nothing of it is stored`() {
        val role = newRole()
        // no READ on parametro: its records are out of this reader's reach
        grant(role, cobro to "CREATE", cobro to "READ", predio to "READ", contribuyente to "READ", servicio to "READ")
        val member = newUserToken(role)
        val batch = requests(10).map { RecordRequest(it.attributes - "parametro") }.toMutableList()
        batch[3] = RecordRequest(batch[3].attributes + ("parametro" to targets.getValue(parametro).single()))

        val expected = runCatching { asCaller(member) { records.create(cobro, batch[3]) } }.exceptionOrNull()
        val thrown = runCatching { asCaller(member) { records.createAll(cobro, batch) } }.exceptionOrNull()

        assertSameRefusal(thrown, expected, "parametro")
        asAdmin { assertThat(records.list(cobro, everything).totalElements).isZero() }
        assertThat(auditOf(cobro)).isZero()
        // the same batch without it goes through: the reader was refused that target, nothing else
        batch[3] = RecordRequest(batch[3].attributes - "parametro")
        assertThat(asCaller(member) { records.createAll(cobro, batch) }).hasSize(10)
    }

    @Test
    fun `a failure late in the batch rolls the whole batch back, audit included`() {
        val batch = requests(SIZE).toMutableList()
        batch[SIZE - 1] = RecordRequest(batch[SIZE - 1].attributes + ("codigo" to POISON))

        val thrown = runCatching { asAdmin { records.createAll(cobro, batch) } }.exceptionOrNull()

        assertThat(thrown).hasMessageContaining("listener refuses")
        asAdmin { assertThat(records.list(cobro, everything).totalElements).isZero() }
        assertThat(auditOf(cobro)).isZero()
    }

    @Test
    fun `it joins the caller's transaction, so a throw after it rolls the batch back`() {
        val thrown =
            runCatching {
                asAdmin {
                    transactions.executeAndAwait {
                        records.createAll(cobro, requests(5))
                        records.create(cobro, RecordRequest(mapOf("codigo" to "AFTER")))
                        error("the app gives up")
                    }
                }
            }.exceptionOrNull()

        assertThat(thrown).hasMessageContaining("the app gives up")
        asAdmin { assertThat(records.list(cobro, everything).totalElements).isZero() }
        assertThat(auditOf(cobro)).isZero()
    }

    @Test
    fun `as the platform a batch writes with no user, in one transaction of its own`() {
        val input = requests(5)
        val organizationId =
            UUID.fromString(runBlocking { decoder.decode(admin.removePrefix("Bearer ")).awaitSingle() }.getClaimAsString(JwtService.CLAIM_ORGANIZATION))

        val answers = runBlocking { records.asPlatform(organizationId, "job:issue-77") { records.createAll(cobro, input) } }

        assertThat(answers).hasSize(5)
        val trail =
            runBlocking {
                db
                    .sql("SELECT user_id, source FROM ${schemas.metadata}.audit_log WHERE object_name = :name")
                    .bind("name", cobro)
                    .map { row, _ -> row.get("user_id", UUID::class.java) to row.get("source", String::class.java) }
                    .all()
                    .collectList()
                    .awaitSingle()
            }
        assertThat(trail).hasSize(5).allSatisfy { assertThat(it).isEqualTo(null to "job:issue-77") }
    }

    @Test
    fun `the answers come back in request order`() {
        val batch = requests(SIZE)

        val answers = asAdmin { records.createAll(cobro, batch) }

        assertThat(answers.map { it.attributes["codigo"] }).isEqualTo(batch.map { it.attributes["codigo"] })
        assertThat(answers.map { it.attributes["predio"].toString() }).isEqualTo(batch.map { it.attributes["predio"] })
    }

    // the target records, per object: 8 predios and contribuyentes, one servicio and one parametro
    private val targets = mutableMapOf<String, List<String>>()

    private fun seedTargets() {
        if (targets.isNotEmpty()) return
        mapOf(predio to 8, contribuyente to 8, servicio to 1, parametro to 1).forEach { (name, count) ->
            targets[name] = asAdmin { records.createAll(name, List(count) { RecordRequest(mapOf("codigo" to "$name-$it")) }) }.map { it.id }
        }
    }

    // the measured case: 48 records, servicio and parametro the same in all, predio and contribuyente repeating
    private fun requests(count: Int): List<RecordRequest> {
        seedTargets()
        return List(count) { i ->
            RecordRequest(
                mapOf(
                    "codigo" to "C-$i",
                    "predio" to targets.getValue(predio)[i % 8],
                    "contribuyente" to targets.getValue(contribuyente)[(i * 3) % 8],
                    "servicio" to targets.getValue(servicio).single(),
                    "parametro" to targets.getValue(parametro).single()
                )
            )
        }
    }

    // the same 48 requests, once through createAll and once through a create loop
    private fun measured(token: String): Pair<Statements, Statements> {
        val input = requests(SIZE)
        val batch = Statements()
        asCaller(token, batch) { records.createAll(cobro, input) }
        val loop = Statements()
        asCaller(token, loop) { input.forEach { records.create(cobro, it) } }
        return batch to loop
    }

    private fun perRecord(statements: Statements): String = "%.2f".format(statements.count.toDouble() / SIZE)

    private fun assertSameRefusal(
        thrown: Throwable?,
        expected: Throwable?,
        field: String
    ) {
        assertThat(expected).isInstanceOf(ValidationException::class.java)
        assertThat(thrown).isInstanceOf(ValidationException::class.java)
        thrown as ValidationException
        expected as ValidationException
        assertThat(thrown.message).isEqualTo(expected.message).isEqualTo("Invalid value for '$field'")
        assertThat(thrown.violations).isEqualTo(expected.violations)
    }

    private val everything = RecordQuery(PageRequest(0, 200))

    private fun <T> asAdmin(block: suspend () -> T): T = asCaller(admin, block = block)

    // the token's authentication in the reactor context, as the web filter would give it. [statements]: count the SQL
    private fun <T> asCaller(
        token: String,
        statements: Statements? = null,
        block: suspend () -> T
    ): T =
        runBlocking {
            val jwt = decoder.decode(token.removePrefix("Bearer ")).awaitSingle()
            mono { block() }
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(JwtAuthenticationToken(jwt)))
                .contextWrite { if (statements == null) it else it.put(Statements::class.java, statements) }
                .awaitSingle()
        }

    private fun auditOf(name: String): Long =
        runBlocking {
            db
                .sql("SELECT count(*) AS n FROM ${schemas.metadata}.audit_log WHERE object_name = :name")
                .bind("name", name)
                .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        }

    private fun text(name: String) = mapOf("name" to name, "type" to "TEXT")

    private fun relation(
        name: String,
        target: String
    ) = mapOf("name" to name, "type" to "RELATION", "relationTarget" to target)

    private fun createObject(
        name: String,
        fields: List<Map<String, Any>>
    ) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to name, "fields" to fields))
            .exchange()
            .expectStatus()
            .isCreated
    }

    private fun newRole(): String {
        val name = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to "Batch", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        return name
    }

    // replaces the role's grants with these
    private fun grant(
        role: String,
        vararg entries: Pair<String, String>
    ) {
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to entries.map { (target, action) -> mapOf("objectName" to target, "action" to action, "allowed" to true) }))
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun newUserToken(role: String): String {
        val email = "${uniqueName("member")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Member", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }

    private companion object {
        const val SIZE = 48
        const val POISON = "BOOM"
    }
}
