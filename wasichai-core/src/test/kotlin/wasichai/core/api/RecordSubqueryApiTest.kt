package wasichai.core.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordReadScope
import wasichai.core.data.RecordSubqueries
import wasichai.core.data.column
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.metadata.ObjectDefinition
import wasichai.test.WasichaiIntegrationTest
import java.util.concurrent.ConcurrentHashMap

// issue 147 (ADR-075): a lesson is read when its unit is of a version the caller reads; core renders the
// subqueries, with READ and the version's own scope (published only) applied to them
@Import(RecordSubqueryApiTest.ScopeConfig::class)
class RecordSubqueryApiTest : WasichaiIntegrationTest() {
    // the app's rules, for the callers it names: versions published only; lessons through unit -> version
    class CourseScope(
        private val subqueries: RecordSubqueries
    ) : RecordReadScope {
        val callers: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @Volatile var version = ""

        @Volatile var unit = ""

        @Volatile var lesson = ""

        override suspend fun criterion(
            caller: AuthenticatedUser,
            definition: ObjectDefinition
        ): RecordCriterion? {
            if (caller.email !in callers) return null
            return when (definition.obj.name) {
                version -> RecordCriterion { handed, bind -> "${handed.column("estado")} = ${bind("PUBLICADO")}" }
                lesson -> {
                    val versions = subqueries.select(caller, version)
                    subqueries.select(caller, unit, where = mapOf("version_curso" to versions)).criterion("unidad")
                }
                else -> null
            }
        }
    }

    @TestConfiguration
    class ScopeConfig {
        @Bean
        fun courseScope(subqueries: RecordSubqueries): CourseScope = CourseScope(subqueries)
    }

    @Autowired
    private lateinit var scope: CourseScope

    private lateinit var admin: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        scope.version = uniqueName("version")
        scope.unit = uniqueName("unidad")
        scope.lesson = uniqueName("leccion")
        createObject(scope.version, listOf(text("estado")))
        createObject(scope.unit, listOf(text("nombre")))
        createObject(scope.lesson, listOf(text("titulo")))
        createRelationship(scope.unit, scope.version, "version_curso")
        createRelationship(scope.lesson, scope.unit, "unidad")

        val published = createRecord(scope.version, mapOf("estado" to "PUBLICADO"))
        val draft = createRecord(scope.version, mapOf("estado" to "BORRADOR"))
        val open = createRecord(scope.unit, mapOf("nombre" to "U1", "version_curso" to published))
        val closed = createRecord(scope.unit, mapOf("nombre" to "U2", "version_curso" to draft))
        createRecord(scope.lesson, mapOf("titulo" to "L-publicada", "unidad" to open))
        createRecord(scope.lesson, mapOf("titulo" to "L-borrador", "unidad" to closed))
    }

    @Test
    fun `a lesson is read through its unit and version, with the version's own scope applied`() {
        val role = newRole(ownRecordsOnly = false)
        grant(role, null)
        val ana = newUserToken(role, scoped = true)

        assertThat(titles(ana)).containsExactly("L-publicada")
        // not named by the scope: every lesson
        assertThat(titles(newUserToken(role, scoped = false))).containsExactlyInAnyOrder("L-publicada", "L-borrador")
        assertThat(titles(admin)).containsExactlyInAnyOrder("L-publicada", "L-borrador")
    }

    @Test
    fun `no READ on an object a subquery reads, no lesson`() {
        val role = newRole(ownRecordsOnly = false)
        grant(role, scope.lesson, scope.unit)
        val ana = newUserToken(role, scoped = true)

        assertThat(titles(ana)).isEmpty()
    }

    // ---- helpers ----

    private fun titles(token: String): List<String> {
        val body =
            client
                .get()
                .uri("/api/objects/${scope.lesson}/records")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(Map::class.java)
                .returnResult()
                .responseBody!!
        return (body["content"] as List<*>).map { ((it as Map<*, *>)["attributes"] as Map<*, *>)["titulo"] as String }
    }

    private fun text(name: String) = mapOf("name" to name, "type" to "TEXT")

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

    private fun createRelationship(
        source: String,
        target: String,
        fieldName: String
    ) {
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to uniqueName("rel").take(30),
                    "label" to fieldName,
                    "inverseLabel" to source,
                    "type" to "MANY_TO_ONE",
                    "source" to source,
                    "target" to target,
                    "fieldName" to fieldName
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    private fun createRecord(
        name: String,
        attributes: Map<String, Any?>
    ): String =
        client
            .post()
            .uri("/api/objects/$name/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(Map::class.java)
            .returnResult()
            .responseBody!!["id"] as String

    private fun newRole(ownRecordsOnly: Boolean): String {
        val name = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to "Courses", "ownRecordsOnly" to ownRecordsOnly))
            .exchange()
            .expectStatus()
            .isCreated
        return name
    }

    // READ on [objects]; a single null: organization-wide
    private fun grant(
        role: String,
        vararg objects: String?
    ) {
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to objects.map { mapOf("objectName" to it, "action" to "READ", "allowed" to true) }))
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun newUserToken(
        role: String,
        scoped: Boolean
    ): String {
        val email = "${uniqueName("alumno")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Alumno", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        if (scoped) scope.callers += email
        return bearer(email, "supersecret")
    }
}
