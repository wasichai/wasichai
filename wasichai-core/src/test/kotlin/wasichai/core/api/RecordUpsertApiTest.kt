package wasichai.core.api

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.ForbiddenException
import wasichai.core.common.PageRequest
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.data.UpsertResult
import wasichai.core.identity.JwtService
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// issue 163, ADR-080: a batch write by natural key against a real database. the consumer is hneyra/curricula, which
// keeps its catalog as a projection of its own schema and syncs it on every import
class RecordUpsertApiTest : WasichaiIntegrationTest() {
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
    private lateinit var organizationId: UUID
    private lateinit var course: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        organizationId =
            UUID.fromString(runBlocking { decoder.decode(admin.removePrefix("Bearer ")).awaitSingle() }.getClaimAsString(JwtService.CLAIM_ORGANIZATION))
        course = uniqueName("curso")
        createObject(
            course,
            listOf(
                mapOf("name" to "codigo", "type" to "TEXT", "unique" to true),
                mapOf("name" to "plan", "type" to "TEXT"),
                mapOf("name" to "ciclo", "type" to "INTEGER"),
                mapOf("name" to "nombre", "type" to "TEXT", "editable" to false),
                mapOf("name" to "creditos", "type" to "INTEGER")
            ),
            uniqueConstraints = listOf(listOf("plan", "ciclo"))
        )
    }

    @Test
    fun `what is new is created, what changed is updated, what is the same is left alone`() {
        platform("codigo", course("MAT-101", "Matemática I", 4), course("FIS-101", "Física I", 4))
        val auditedBefore = audited()

        val result = platform("codigo", course("MAT-101", "Matemática I", 4), course("FIS-101", "Física 1", 5), course("QUI-101", "Química I", 3))

        assertThat(result.counts()).isEqualTo(listOf(1, 1, 1, 0))
        assertThat(result.records.map { it.attributes["codigo"] }).containsExactly("MAT-101", "FIS-101", "QUI-101")
        assertThat(result.records[1].attributes).containsEntry("nombre", "Física 1").containsEntry("creditos", 5L)
        // the unchanged one wrote no audit row: one UPDATE and one CREATE more
        assertThat(audited() - auditedBefore).isEqualTo(2)
        assertThat(rows().map { it["codigo"] to it["nombre"] })
            .containsExactlyInAnyOrder("MAT-101" to "Matemática I", "FIS-101" to "Física 1", "QUI-101" to "Química I")
    }

    @Test
    fun `values compare as a read answers them, and a key left out is no change`() {
        platform("codigo", course("MAT-101", "Matemática I", 4))

        val result =
            platform(
                "codigo",
                RecordRequest(mapOf("codigo" to "MAT-101", "creditos" to "4")),
                RecordRequest(mapOf("codigo" to "MAT-102", "creditos" to 2))
            )

        assertThat(result.counts()).isEqualTo(listOf(1, 0, 1, 0))
    }

    @Test
    fun `a composite unique is a key too, named in any order`() {
        platform(listOf("ciclo", "plan"), RecordRequest(mapOf("codigo" to "A", "plan" to "P1", "ciclo" to 1)))

        val result = platform(listOf("plan", "ciclo"), RecordRequest(mapOf("codigo" to "B", "plan" to "P1", "ciclo" to 1)))

        assertThat(result.counts()).isEqualTo(listOf(0, 1, 0, 0))
        assertThat(rows().map { it["codigo"] }).containsExactly("B")
    }

    @Test
    fun `deleteMissing deletes every record the list does not carry`() {
        platform("codigo", course("MAT-101", "Matemática I", 4), course("FIS-101", "Física I", 4), course("QUI-101", "Química I", 3))

        val result =
            runBlocking {
                records.asPlatform(
                    organizationId
                ) { records.upsertAll(course, listOf("codigo"), listOf(course("FIS-101", "Física I", 4)), deleteMissing = true) }
            }

        assertThat(result.counts()).isEqualTo(listOf(0, 0, 1, 2))
        assertThat(rows().map { it["codigo"] }).containsExactly("FIS-101")
    }

    @Test
    fun `a key that is not unique, a request without it, or two with the same one is a 400, and nothing is written`() {
        assertThatThrownBy { platform("plan", course("MAT-101", "Matemática I", 4)) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("no natural key")
        assertThatThrownBy { platform("codigo", course("MAT-101", "Matemática I", 4), RecordRequest(mapOf("nombre" to "Sin código"))) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("Record 1 has no 'codigo'")
        assertThatThrownBy { platform("codigo", course("MAT-101", "Matemática I", 4), course("MAT-101", "Otra", 1)) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("Records 0 and 1")

        assertThat(rows()).isEmpty()
    }

    @Test
    fun `a failing record fails the batch, and the caller's transaction takes it all back`() {
        platform("codigo", course("MAT-101", "Matemática I", 4))

        assertThatThrownBy {
            runBlocking {
                transactions.executeAndAwait {
                    records.asPlatform(organizationId) {
                        records.upsertAll(
                            course,
                            listOf("codigo"),
                            listOf(
                                course("MAT-101", "Cambiada", 9),
                                RecordRequest(
                                    mapOf(
                                        "codigo" to "X",
                                        "creditos" to "muchos"
                                    )
                                )
                            )
                        )
                    }
                }
            }
        }.isInstanceOf(ValidationException::class.java)

        assertThat(rows().map { it["nombre"] }).containsExactly("Matemática I")
    }

    @Test
    fun `a person writes it with the permissions it needs, and a read-only key is theirs to leave out`() {
        val id = createRecord(mapOf("codigo" to "MAT-101", "creditos" to 4))

        val result =
            runBlocking { asAdmin { records.upsertAll(course, listOf("codigo"), listOf(RecordRequest(mapOf("codigo" to "MAT-101", "creditos" to 5)))) } }

        assertThat(result.counts()).isEqualTo(listOf(0, 1, 0, 0))
        assertThat(result.records.single().id).isEqualTo(id)
        assertThatThrownBy { runBlocking { asAdmin { records.upsertAll(course, listOf("codigo"), listOf(course("MAT-101", "Otra", 5))) } } }
            .isInstanceOf(ForbiddenException::class.java)
            .hasMessageContaining("nombre")
    }

    private fun UpsertResult.counts() = listOf(created, updated, unchanged, deleted)

    private fun course(
        codigo: String,
        nombre: String,
        creditos: Int
    ) = RecordRequest(mapOf("codigo" to codigo, "nombre" to nombre, "creditos" to creditos))

    private fun platform(
        key: String,
        vararg requests: RecordRequest
    ): UpsertResult = platform(listOf(key), *requests)

    private fun platform(
        key: List<String>,
        vararg requests: RecordRequest
    ): UpsertResult = runBlocking { records.asPlatform(organizationId) { records.upsertAll(course, key, requests.toList()) } }

    // the admin's token in the reactor context, as the web filter would give it: a request's own code calling in
    private suspend fun <T> asAdmin(block: suspend () -> T): T {
        val jwt = decoder.decode(admin.removePrefix("Bearer ")).awaitSingle()
        return mono { block() }
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(JwtAuthenticationToken(jwt)))
            .awaitSingle()
    }

    private fun createObject(
        name: String,
        fields: List<Map<String, Any>>,
        uniqueConstraints: List<List<String>>
    ) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to name, "fields" to fields, "uniqueConstraints" to uniqueConstraints))
            .exchange()
            .expectStatus()
            .isCreated
    }

    private fun createRecord(attributes: Map<String, Any?>): String =
        client
            .post()
            .uri("/api/objects/$course/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .substringAfter("\"id\":\"")
            .substringBefore("\"")

    private fun rows(): List<Map<String, Any?>> =
        runBlocking {
            records.asPlatform(organizationId) { records.list(course, everything).content.map { it.attributes } }
        }

    private val everything = RecordQuery(page = PageRequest(0, 200))

    private fun audited(): Long =
        runBlocking {
            db
                .sql("SELECT COUNT(*) AS n FROM ${schemas.metadata}.audit_log WHERE object_name = :name")
                .bind("name", course)
                .map { row, _ -> row.get("n", Number::class.java)!!.toLong() }
                .one()
                .awaitSingle()
        }
}
