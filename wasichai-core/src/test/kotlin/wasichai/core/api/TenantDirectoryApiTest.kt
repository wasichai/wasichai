package wasichai.core.api

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.identity.JwtService
import wasichai.core.platform.TenantDirectory
import wasichai.core.platform.TenantRef
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.time.Duration
import java.util.UUID

// ADR-057: background work finds the tenants through the TenantDirectory, and runs per tenant with
// forEachOrganization. a request never lists them.
@Import(TenantDirectoryApiTest.ProbeConfig::class)
class TenantDirectoryApiTest : WasichaiIntegrationTest() {
    // app code that wrongly reaches for the tenant list while serving a request
    @RestController
    class DirectoryProbe(
        private val tenants: TenantDirectory,
        private val records: RecordService
    ) {
        // the 500 body is generic on purpose; the test reads the cause here
        @Volatile
        var lastFailure: Throwable? = null

        // OPTIONS is the one method the security chain lets through without a token: the anonymous case
        @RequestMapping("/directory-probe/list", method = [RequestMethod.POST, RequestMethod.OPTIONS])
        suspend fun list(): String = probed { tenants.organizations().size.toString() }

        @RequestMapping("/directory-probe/each", method = [RequestMethod.POST, RequestMethod.OPTIONS])
        suspend fun each(): String =
            probed {
                records.forEachOrganization { }
                "ran"
            }

        private suspend fun probed(block: suspend () -> String): String =
            try {
                block()
            } catch (e: Exception) {
                lastFailure = e
                throw e
            }
    }

    // a startup hook, the other place background work starts from
    class StartupHook(
        private val tenants: TenantDirectory
    ) : ApplicationListener<ApplicationReadyEvent> {
        @Volatile
        var seen: List<TenantRef>? = null

        override fun onApplicationEvent(event: ApplicationReadyEvent) {
            seen = runBlocking { tenants.organizations() }
        }
    }

    @TestConfiguration
    class ProbeConfig {
        @Bean
        fun directoryProbe(
            tenants: TenantDirectory,
            records: RecordService
        ) = DirectoryProbe(tenants, records)

        @Bean
        fun tenantStartupHook(tenants: TenantDirectory) = StartupHook(tenants)
    }

    @Autowired
    private lateinit var tenants: TenantDirectory

    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var probe: DirectoryProbe

    @Autowired
    private lateinit var hook: StartupHook

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    private lateinit var admin: String
    private lateinit var adminOrg: TenantRef
    private lateinit var first: TenantRef
    private lateinit var second: TenantRef
    private lateinit var objectName: String

    @BeforeEach
    fun setUp() {
        probe.lastFailure = null
        admin = bearer()
        adminOrg = TenantRef(organizationOf(admin), DEMO_SLUG)
        first = tenant()
        second = tenant()
        objectName = uniqueName("dir")
        // two of the three define the object; the admin's organization does not
        createObject(token(first))
        createObject(token(second))
    }

    @Test
    fun `organizations lists every organization by id, the same on every call`() {
        val all = runBlocking { tenants.organizations() }

        assertThat(all).contains(adminOrg, first, second)
        // postgres orders uuids as unsigned bytes, i.e. as their text; java's compareTo is signed
        assertThat(all.map { it.id.toString() }).isSorted().doesNotHaveDuplicates()
        assertThat(runBlocking { tenants.organizations() }).isEqualTo(all)
        // id and slug, what the table says
        assertThat(all.size.toLong()).isEqualTo(count("SELECT count(*) FROM ${schemas.metadata}.organizations"))
    }

    @Test
    fun `organizationsWithObject lists only the organizations that define it, by id`() {
        val defining = runBlocking { tenants.organizationsWithObject(objectName) }

        assertThat(defining).containsExactlyInAnyOrder(first, second)
        assertThat(defining.map { it.id.toString() }).isSorted()
        assertThat(runBlocking { tenants.organizationsWithObject(objectName) }).isEqualTo(defining)
        assertThat(runBlocking { tenants.organizationsWithObject(uniqueName("none")) }).isEmpty()
    }

    @Test
    fun `a scheduled job and a startup hook get the list`() {
        // what a ticking job runs on: a reactor timer, no request, no security context
        val scheduled =
            Flux
                .interval(Duration.ofMillis(10))
                .take(1)
                .concatMap { mono { tenants.organizationsWithObject(objectName) } }
                .subscribeOn(Schedulers.parallel())
                .blockLast(Duration.ofSeconds(30))

        assertThat(scheduled).containsExactlyInAnyOrder(first, second)
        assertThat(hook.seen).isNotNull.contains(adminOrg)
    }

    @Test
    fun `a request with a token never lists the tenants`() {
        listOf("list" to "never lists the tenants", "each" to "never becomes the platform").forEach { (path, message) ->
            probe.lastFailure = null
            client
                .post()
                .uri("/directory-probe/$path")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isEqualTo(500)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("Unexpected error")

            // refused by the tripwire, not by something else on the way
            assertThat(probe.lastFailure).isInstanceOf(IllegalStateException::class.java).hasMessageContaining(message)
        }
    }

    @Test
    fun `a request without a token never lists the tenants either`() {
        listOf("list" to "never lists the tenants", "each" to "never becomes the platform").forEach { (path, message) ->
            probe.lastFailure = null
            client
                .options()
                .uri("/directory-probe/$path")
                .exchange()
                .expectStatus()
                .isEqualTo(500)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("Unexpected error")

            assertThat(probe.lastFailure).isInstanceOf(IllegalStateException::class.java).hasMessageContaining(message)
        }
    }

    @Test
    fun `forEachOrganization writes in each defining tenant as the platform and goes on after one throws`() {
        val visited = mutableListOf<UUID>()
        // the first one visited throws; the other still writes
        runBlocking {
            records.forEachOrganization(objectName, source = "job:directory") { organizationId ->
                visited += organizationId
                if (visited.size == 1) error("this tenant's job is broken")
                records.create(objectName, RecordRequest(mapOf("codigo" to "JOB")))
            }
        }

        assertThat(visited).containsExactlyInAnyOrder(first.id, second.id)
        val broken = visited.first()
        val written = visited.last()
        // scoped to that tenant: one audit row there, by nobody, labelled; none in the one that threw
        assertThat(audit(written)).containsExactly(Triple("CREATE", null, "job:directory"))
        assertThat(audit(broken)).isEmpty()
        assertThat(audit(adminOrg.id)).isEmpty()
    }

    private fun tenant(): TenantRef {
        val slug = "dir-" + uniqueName("").take(8)
        client
            .post()
            .uri("/api/organizations")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "Directory tenant", "slug" to slug, "adminEmail" to "$slug@wasichai.local", "adminPassword" to "supersecret"))
            .exchange()
            .expectStatus()
            .isCreated
        return TenantRef(organizationOf(bearer("$slug@wasichai.local", "supersecret")), slug)
    }

    private fun token(tenant: TenantRef): String = bearer("${tenant.slug}@wasichai.local", "supersecret")

    private fun createObject(token: String) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to objectName, "label" to objectName, "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
    }

    private fun organizationOf(token: String): UUID =
        UUID.fromString(runBlocking { decoder.decode(token.removePrefix("Bearer ")).awaitSingle() }.getClaimAsString(JwtService.CLAIM_ORGANIZATION))

    private fun count(sql: String): Long =
        runBlocking {
            db
                .sql(sql)
                .map { row, _ -> row.get(0, Number::class.java)!!.toLong() }
                .one()
                .awaitSingle()
        }

    // operation, user, source of every audit row of the object in that organization, oldest first
    private fun audit(organizationId: UUID): List<Triple<String, UUID?, String?>> =
        runBlocking {
            db
                .sql(
                    """
                    SELECT operation, user_id, source FROM ${schemas.metadata}.audit_log
                    WHERE organization_id = :organizationId AND object_name = :name
                    ORDER BY occurred_at
                    """.trimIndent()
                ).bind("organizationId", organizationId)
                .bind("name", objectName)
                .map { row, _ ->
                    Triple(row.get("operation", String::class.java)!!, row.get("user_id", UUID::class.java), row.get("source", String::class.java))
                }.all()
                .collectList()
                .awaitSingle()
        }

    private companion object {
        // the seeded organization the admin belongs to
        const val DEMO_SLUG = "demo"
    }
}
