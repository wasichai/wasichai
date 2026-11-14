package wasichai.core.api

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.web.reactive.server.WebTestClient
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.identity.JwtService
import wasichai.test.WasichaiIntegrationTest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// issues 166 and 167, against a real database. the consumer is hneyra/curricula: a catalog the platform writes, its
// fields read-only in the ui (editable: false), its values built in kotlin (a UUID, a LocalDate, an instant)
class PlatformFieldWriteApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    private lateinit var admin: String
    private lateinit var organizationId: UUID
    private lateinit var plan: String
    private lateinit var course: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        organizationId =
            UUID.fromString(runBlocking { decoder.decode(admin.removePrefix("Bearer ")).awaitSingle() }.getClaimAsString(JwtService.CLAIM_ORGANIZATION))
        plan = uniqueName("plan")
        createObject(plan, listOf(mapOf("name" to "codigo", "type" to "TEXT")))
        course = uniqueName("curso")
        createObject(
            course,
            listOf(
                mapOf("name" to "codigo", "type" to "TEXT"),
                readOnly("nombre", "TEXT"),
                readOnly("creditos", "INTEGER"),
                readOnly("vigente_desde", "DATE"),
                readOnly("publicado_en", "DATETIME"),
                readOnly("revision", "UUID"),
                readOnly("plan", "RELATION") + ("relationTarget" to plan)
            )
        )
    }

    @Test
    fun `the platform writes read-only fields on a create, from kotlin values`() {
        val planId = UUID.fromString(runBlocking { records.asPlatform(organizationId) { records.create(plan, RecordRequest(mapOf("codigo" to "P-1"))) } }.id)
        val revision = UUID.randomUUID()

        val created =
            runBlocking {
                records.asPlatform(organizationId) {
                    records.create(
                        course,
                        RecordRequest(
                            mapOf(
                                "codigo" to "MAT-101",
                                "nombre" to "Matemática I",
                                "creditos" to 4,
                                "vigente_desde" to LocalDate.of(2026, 3, 1),
                                "publicado_en" to Instant.parse("2026-11-13T14:30:00Z"),
                                "revision" to revision,
                                "plan" to planId
                            )
                        )
                    )
                }
            }

        // the answer and a read say the same: nothing dropped, every value as the api carries it
        assertThat(created.attributes)
            .containsEntry("nombre", "Matemática I")
            .containsEntry("creditos", 4L)
            .containsEntry("vigente_desde", "2026-03-01")
            .containsEntry("publicado_en", "2026-11-13T14:30:00Z")
            .containsEntry("revision", revision.toString())
            .containsEntry("plan", planId.toString())
        get(course, created.id)
            .jsonPath("$.attributes.nombre")
            .isEqualTo("Matemática I")
            .jsonPath("$.attributes.plan")
            .isEqualTo(planId.toString())
    }

    @Test
    fun `the platform PUT writes the read-only fields it sends and keeps the ones it leaves out`() {
        val id = platformCourse(mapOf("codigo" to "MAT-101", "nombre" to "Matemática I", "creditos" to 4))

        runBlocking {
            records.asPlatform(
                organizationId
            ) { records.update(course, id, RecordRequest(mapOf("codigo" to "MAT-101", "nombre" to "Matemática 1"))) }
        }

        get(course, id.toString())
            .jsonPath("$.attributes.nombre")
            .isEqualTo("Matemática 1")
            .jsonPath("$.attributes.creditos")
            .isEqualTo(4)
    }

    @Test
    fun `the platform PATCH writes a read-only field`() {
        val id = platformCourse(mapOf("codigo" to "MAT-101", "nombre" to "Matemática I", "creditos" to 4))

        runBlocking { records.asPlatform(organizationId) { records.patch(course, id, RecordRequest(mapOf("creditos" to 5))) } }

        get(course, id.toString())
            .jsonPath("$.attributes.creditos")
            .isEqualTo(5)
            .jsonPath("$.attributes.nombre")
            .isEqualTo("Matemática I")
    }

    @Test
    fun `a person's value for a read-only field on a create is a 403, and nothing is stored`() {
        client
            .post()
            .uri("/api/objects/$course/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "MAT-101", "nombre" to "Matemática I")))
            .exchange()
            .expectStatus()
            .isForbidden
            .expectBody()
            .jsonPath("$.detail")
            .value<String> { assertThat(it).contains("nombre").contains("read-only") }

        assertThat(count(course)).isZero()
    }

    @Test
    fun `a person's create that leaves read-only fields out, or sends them null, is what it was`() {
        val id =
            client
                .post()
                .uri("/api/objects/$course/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "MAT-101", "nombre" to null)))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
                .substringAfter("\"id\":\"")
                .substringBefore("\"")

        get(course, id).jsonPath("$.attributes.codigo").isEqualTo("MAT-101")
    }

    @Test
    fun `a person's PUT still ignores a read-only field and a PATCH naming one is still a 403`() {
        val id = platformCourse(mapOf("codigo" to "MAT-101", "nombre" to "Matemática I"))

        client
            .put()
            .uri("/api/objects/$course/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "MAT-102", "nombre" to "Otra")))
            .exchange()
            .expectStatus()
            .isOk
        client
            .patch()
            .uri("/api/objects/$course/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("nombre" to "Otra")))
            .exchange()
            .expectStatus()
            .isForbidden

        get(course, id.toString())
            .jsonPath("$.attributes.codigo")
            .isEqualTo("MAT-102")
            .jsonPath("$.attributes.nombre")
            .isEqualTo("Matemática I")
    }

    private fun platformCourse(attributes: Map<String, Any?>): UUID =
        UUID.fromString(runBlocking { records.asPlatform(organizationId) { records.create(course, RecordRequest(attributes)) } }.id)

    private fun readOnly(
        name: String,
        type: String
    ): Map<String, Any> = mapOf("name" to name, "type" to type, "editable" to false)

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

    private fun get(
        name: String,
        id: String
    ): WebTestClient.BodyContentSpec =
        client
            .get()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    private fun count(name: String): Long =
        client
            .get()
            .uri("/api/objects/$name/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .substringAfter("\"totalElements\":")
            .takeWhile { it.isDigit() }
            .toLong()
}
