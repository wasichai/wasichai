package wasichai.it.full

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
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.data.RelatedRecordService
import wasichai.workflow.WorkflowService
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.UUID

// issue 108 (ADR-067): with every module on, a request that writes, links and asks for transitions reads none of the
// model on a warm cache: no definition, workflow, automation, notification rule, relationship or file field. a workflow
// changed is read by the very next request. statements counted on the real driver, as MetadataCacheApiTest does
@Import(ModelLookupCacheApiTest.CountingConfig::class)
class ModelLookupCacheApiTest : FullAppIntegrationTest() {
    class Statements {
        val sql: MutableList<String> = Collections.synchronizedList(mutableListOf())

        // what the model and the modules' setup cost: none of it should reach the database on a warm cache
        val model: List<String> get() =
            sql.filter { statement ->
                MODEL_READS.any { it in statement }
            }

        override fun toString(): String = sql.joinToString("\n")

        companion object {
            val MODEL_READS =
                listOf(
                    "custom_objects WHERE organization_id",
                    ".custom_fields WHERE object_id",
                    ".workflows WHERE",
                    ".automations",
                    ".notification_rules",
                    ".relationships WHERE"
                )
        }
    }

    @TestConfiguration
    class CountingConfig {
        companion object {
            @Bean
            @JvmStatic
            fun statementCounting(): BeanPostProcessor =
                object : BeanPostProcessor {
                    override fun postProcessAfterInitialization(
                        bean: Any,
                        beanName: String
                    ): Any = if (bean is ConnectionFactory) counting(bean) else bean
                }

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
                    if (method.name == "createStatement") statements.sql += args!![0] as String
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
    private lateinit var related: RelatedRecordService

    @Autowired
    private lateinit var workflows: WorkflowService

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    private lateinit var admin: String
    private lateinit var predio: String
    private lateinit var lote: String
    private lateinit var relationship: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        predio = uniqueName("predio")
        lote = uniqueName("lote")
        createObject(predio, "codigo")
        createObject(lote, "nombre")
        relationship = uniqueName("rel").take(30)
        post(
            "/api/relationships",
            mapOf(
                "name" to relationship,
                "label" to "Lotes",
                "inverseLabel" to "Predios",
                "type" to "MANY_TO_MANY",
                "source" to predio,
                "target" to lote
            )
        )
        putWorkflow(enabled = true)
    }

    @Test
    fun `a request that writes, links and reads transitions reads none of the model on a warm cache`() {
        val lots = (1..4).map { request { records.create(lote, RecordRequest(mapOf("nombre" to "L-$it"))).id } }
        // warms the cache: the same work once
        request { work(lots.take(2)) }

        val statements = Statements()
        request(statements) { work(lots.drop(2)) }

        println("issue 108: create, update, two links and transitions: ${statements.sql.size} statements")
        assertThat(statements.model).describedAs("%s", statements).isEmpty()
    }

    @Test
    fun `a workflow changed is read by the very next request`() {
        val id = request { records.create(predio, RecordRequest(mapOf("codigo" to "P-1"))).id }
        assertThat(request { workflows.transitionsOf(predio, UUID.fromString(id)) }).isNotEmpty()

        putWorkflow(enabled = false)

        assertThat(request { workflows.transitionsOf(predio, UUID.fromString(id)) }).isEmpty()
    }

    // a record of predio written twice, linked to two lots, and its transitions asked for
    private suspend fun work(lots: List<String>) {
        val id = UUID.fromString(records.create(predio, RecordRequest(mapOf("codigo" to "P-${UUID.randomUUID()}"))).id)
        records.update(predio, id, RecordRequest(mapOf("codigo" to "P-${UUID.randomUUID()}")))
        lots.forEach { related.link(predio, id, relationship, UUID.fromString(it)) }
        workflows.transitionsOf(predio, id)
    }

    // one request as admin, as the security filter would give it. [statements]: count the SQL
    private fun <T> request(
        statements: Statements? = null,
        block: suspend () -> T
    ): T =
        runBlocking {
            val jwt = decoder.decode(admin.removePrefix("Bearer ")).awaitSingle()
            mono { block() }
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(JwtAuthenticationToken(jwt)))
                .contextWrite { if (statements == null) it else it.put(Statements::class.java, statements) }
                .awaitSingle()
        }

    private fun createObject(
        name: String,
        field: String
    ) = post("/api/objects", mapOf("name" to name, "label" to name, "fields" to listOf(mapOf("name" to field, "type" to "TEXT"))))

    private fun post(
        uri: String,
        body: Map<String, Any>
    ) {
        client
            .post()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isCreated
    }

    private fun putWorkflow(enabled: Boolean) {
        client
            .put()
            .uri("/api/objects/$predio/workflow")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to "wf_$predio".take(60),
                    "label" to "Aprobacion",
                    "enabled" to enabled,
                    "definition" to
                        mapOf(
                            "states" to
                                listOf(
                                    mapOf("name" to "draft", "label" to "Borrador", "type" to "INITIAL"),
                                    mapOf("name" to "approved", "label" to "Aprobado", "type" to "FINAL")
                                ),
                            "transitions" to listOf(mapOf("name" to "approve", "label" to "Aprobar", "from" to "draft", "to" to "approved"))
                        )
                )
            ).exchange()
            .expectStatus()
            .is2xxSuccessful
    }
}
