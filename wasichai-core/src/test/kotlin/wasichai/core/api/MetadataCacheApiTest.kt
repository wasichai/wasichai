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
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.util.ClassUtils
import reactor.core.publisher.Mono
import wasichai.core.common.ForbiddenException
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.metadata.MetadataChange
import wasichai.core.metadata.MetadataChangeListener
import wasichai.core.platform.RequestMemo
import wasichai.test.WasichaiIntegrationTest
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.UUID

// issue 105 (ADR-066): a definition is read once per ttl, what a caller's roles say about an object once per
// request, and a change to either is seen by the very next request. statements counted on the real driver
@Import(MetadataCacheApiTest.CountingConfig::class)
class MetadataCacheApiTest : WasichaiIntegrationTest() {
    // the SQL a call sent, on connections taken inside it: background work of the app is not counted
    class Statements {
        val sql: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val count: Int get() = sql.size

        // the reads of a definition: the object by name or id, then its fields
        val definitions: Int get() =
            sql.count {
                "custom_objects WHERE organization_id" in it ||
                    ("custom_fields WHERE object_id" in it && "ORDER BY position" in it)
            }

        // the reads of the caller's roles: permissions, field rules, the owner rule
        val roles: Int get() = sql.count { "permissions" in it || ".roles" in it }

        override fun toString(): String = sql.joinToString("\n")
    }

    class Heard : MetadataChangeListener {
        val changes: MutableList<MetadataChange> = Collections.synchronizedList(mutableListOf())

        override suspend fun metadataChanged(change: MetadataChange) {
            changes += change
        }
    }

    @TestConfiguration
    class CountingConfig {
        @Bean
        fun heardMetadataChanges(): Heard = Heard()

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
    private lateinit var decoder: ReactiveJwtDecoder

    @Autowired
    private lateinit var heard: Heard

    private lateinit var admin: String
    private lateinit var predio: String
    private lateinit var role: String
    private lateinit var gestor: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        predio = uniqueName("predio")
        createObject(predio)
        role = newRole()
        grant(role, "READ", "CREATE", "UPDATE")
        gestor = newUserToken(role)
    }

    @Test
    fun `a list by a caller who is not ADMIN costs one statement more than ADMIN's, none of them a definition`() {
        // both have read the definition once: it is cached now
        request(admin) { records.list(predio, everything) }
        request(gestor) { records.list(predio, everything) }

        val asAdmin = Statements().also { request(admin, it) { records.list(predio, everything) } }
        val asGestor = Statements().also { request(gestor, it) { records.list(predio, everything) } }

        println("issue 105: list ADMIN ${asAdmin.count} statements, not ADMIN ${asGestor.count}")
        assertThat(asAdmin.definitions).describedAs("%s", asAdmin).isZero()
        assertThat(asGestor.definitions).describedAs("%s", asGestor).isZero()
        assertThat(asGestor.roles).describedAs("%s", asGestor).isEqualTo(1)
        assertThat(asGestor.count).describedAs("%s", asGestor).isLessThanOrEqualTo(asAdmin.count + 1)
    }

    @Test
    fun `a request with many record operations on one object reads its definition once and asks the roles once`() {
        val statements = Statements()

        request(gestor, statements) {
            val created = records.create(predio, RecordRequest(mapOf("codigo" to "P-1")))
            records.get(predio, UUID.fromString(created.id))
            records.update(predio, UUID.fromString(created.id), RecordRequest(mapOf("codigo" to "P-2")))
            records.create(predio, RecordRequest(mapOf("codigo" to "P-3")))
            records.list(predio, everything)
        }

        assertThat(statements.definitions).describedAs("%s", statements).isLessThanOrEqualTo(2)
        assertThat(statements.roles).describedAs("%s", statements).isEqualTo(1)
    }

    @Test
    fun `a field added is read by the next request, and the listeners hear of it after the commit`() {
        request(gestor) { records.list(predio, everything) }

        client
            .post()
            .uri("/api/metadata/objects/$predio/fields")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "zona", "type" to "TEXT"))
            .exchange()
            .expectStatus()
            .isCreated

        val created = request(gestor) { records.create(predio, RecordRequest(mapOf("codigo" to "P-1", "zona" to "norte"))) }
        assertThat(created.attributes["zona"]).isEqualTo("norte")
        assertThat(heard.changes.filter { it.objectName == predio }.map { it.name }).contains("zona")
    }

    @Test
    fun `a permission taken away refuses the next request`() {
        request(gestor) { records.list(predio, everything) }

        grant(role, "CREATE")

        val refused = runCatching { request(gestor) { records.list(predio, everything) } }.exceptionOrNull()
        assertThat(refused).isInstanceOf(ForbiddenException::class.java)
    }

    private val everything = RecordQuery(PageRequest(0, 50))

    // one request as the web filters would give it: the token's authentication and a fresh request memo.
    // [statements]: count the SQL
    private fun <T> request(
        token: String,
        statements: Statements? = null,
        block: suspend () -> T
    ): T =
        runBlocking {
            val jwt = decoder.decode(token.removePrefix("Bearer ")).awaitSingle()
            mono { block() }
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(JwtAuthenticationToken(jwt)))
                .contextWrite { RequestMemo.open(it) }
                .contextWrite { if (statements == null) it else it.put(Statements::class.java, statements) }
                .awaitSingle()
        }

    private fun createObject(name: String) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to name, "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
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
            .bodyValue(mapOf("name" to name, "label" to "Cached", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        return name
    }

    // replaces the role's grants on the object with these
    private fun grant(
        role: String,
        vararg actions: String
    ) {
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to actions.map { mapOf("objectName" to predio, "action" to it, "allowed" to true) }))
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun newUserToken(role: String): String {
        val email = "${uniqueName("gestor")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Gestor", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }
}
