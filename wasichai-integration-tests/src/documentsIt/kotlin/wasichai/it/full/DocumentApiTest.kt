package wasichai.it.full

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Year

class DocumentApiTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    private lateinit var token: String
    private lateinit var predio: String
    private lateinit var sigla: String
    private lateinit var record: String

    @BeforeEach
    fun setUp() {
        token = bearer()
        predio = uniqueName("predio")
        // a sigla is unique across the whole organization, so every test mints its own
        sigla = uniqueName("s").uppercase().take(10)
        createObject(predio)
        record = createRecord()
    }

    @Test
    fun `two issues in a row give 001 and 002, and the first one is archived`() {
        createType("oficio", sigla)
        val year = Year.now().value

        issue("oficio")
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.number")
            .isEqualTo("$sigla-$year-001")
            .jsonPath("$.status")
            .isEqualTo("VALID")

        issue("oficio")
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.number")
            .isEqualTo("$sigla-$year-002")
            .jsonPath("$.status")
            .isEqualTo("VALID")

        // the one that prevails is the last one issued; the other is not deleted, it is archived
        client
            .get()
            .uri("/api/objects/$predio/records/$record/documents")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(2)
            .jsonPath("$[?(@.number == '$sigla-$year-002')].status")
            .isEqualTo("VALID")
            .jsonPath("$[?(@.number == '$sigla-$year-001')].status")
            .isEqualTo("ARCHIVED")
    }

    // the reason the snapshot carries the template: editing the type must not rewrite what was
    // already issued. without this test nothing distinguishes a frozen copy from a live reference.
    @Test
    fun `an issued document still says what it said after the template and the record change`() {
        createType("oficio", sigla, text = "Original")
        val issued =
            issue("oficio")
                .expectStatus()
                .isCreated
                .expectBody()
                .returnResult()
                .responseBody!!
                .decodeToString()
        val id = Regex("\"id\":\"([^\"]+)\"").find(issued)!!.groupValues[1]

        client
            .put()
            .uri("/api/objects/$predio/document-types/oficio")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("template" to doc(paragraph(text("Reescrito")), paragraph(field("codigo")))))
            .exchange()
            .expectStatus()
            .isOk

        client
            .put()
            .uri("/api/objects/$predio/records/$record")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "CAMBIADO")))
            .exchange()
            .expectStatus()
            .isOk

        client
            .get()
            .uri("/api/documents/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.snapshot.template.content[0].content[0].text")
            .isEqualTo("Original")
            .jsonPath("$.snapshot.values.codigo")
            .isEqualTo("P-1")
    }

    @Test
    fun `the platform values are resolved when the document is issued, not when it is read`() {
        createType("oficio", sigla)
        val year = Year.now().value

        issue("oficio")
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.snapshot.platform.documentPrefix")
            .isEqualTo(sigla)
            .jsonPath("$.snapshot.platform.documentSerial")
            .isEqualTo("$year-001")
            .jsonPath("$.snapshot.platform.documentNumber")
            .isEqualTo("$sigla-$year-001")
            .jsonPath("$.snapshot.platform.documentName")
            .isEqualTo("Oficio")
            // a date, frozen, not a placeholder to fill in later
            .jsonPath("$.snapshot.platform.today")
            .value<String> { assert(Regex("\\d{4}-\\d{2}-\\d{2}").matches(it)) { "today was '$it'" } }
    }

    @Test
    fun `two types on one object keep separate series`() {
        createType("oficio", sigla)
        createType("constancia", sigla + "B")
        val year = Year.now().value

        issue("oficio")
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.number")
            .isEqualTo("$sigla-$year-001")
        issue("constancia")
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.number")
            .isEqualTo("${sigla}B-$year-001")
        issue("oficio")
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.number")
            .isEqualTo("$sigla-$year-002")
    }

    // a counter that is only ever tested in series is not tested. ten at once on one record is also
    // the double-click case: they must serialise into 001..010, nine archived and one valid, rather
    // than nine of them dying on the one-valid index.
    @Test
    fun `ten issues at once give ten different numbers, with no gaps`() {
        createType("oficio", sigla)
        val year = Year.now().value
        val attempts = 10

        val numbers =
            runBlocking {
                (1..attempts)
                    .map { async(Dispatchers.IO) { issueNumber() } }
                    .awaitAll()
            }

        assertThat(numbers.filterNotNull()).hasSize(attempts)
        assertThat(numbers.toSet()).hasSize(attempts)
        assertThat(numbers.map { it!!.substringAfterLast('-').toInt() }.sorted())
            .isEqualTo((1..attempts).toList())
        assertThat(numbers).allSatisfy { assertThat(it).startsWith("$sigla-$year-") }

        // and exactly one of the ten is the one that prevails
        client
            .get()
            .uri("/api/objects/$predio/records/$record/documents")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[?(@.status == 'VALID')]")
            .value<List<*>> { assertThat(it).hasSize(1) }
    }

    @Test
    fun `a type that has issued a document cannot be deleted`() {
        createType("oficio", sigla)
        issue("oficio").expectStatus().isCreated

        client
            .delete()
            .uri("/api/objects/$predio/document-types/oficio")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Document type 'oficio' has issued documents")
    }

    @Test
    fun `issuing a type the object has not got is refused`() {
        issue("fantasma").expectStatus().isNotFound
    }

    // the point of phase 4: issuing writes history, in the same breath as the document itself.
    @Test
    fun `issuing a document puts an entry in the record's history, carrying the document's id`() {
        createType("oficio", sigla)
        val issued =
            issue("oficio")
                .expectStatus()
                .isCreated
                .expectBody()
                .returnResult()
                .responseBody!!
                .decodeToString()
        val id = Regex("\"id\":\"([^\"]+)\"").find(issued)!!.groupValues[1]

        client
            .get()
            .uri("/api/objects/$predio/records/$record/history")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[0].operation")
            .isEqualTo("ISSUE")
            .jsonPath("$[0].documentId")
            .isEqualTo(id)
    }

    // clean start: the original replayed its V14 here by hand. wasichai has no V14 to replay; what is
    // left to pin is V14's end state, which wasichai-documents' V1 builds: ISSUE is an audit operation
    // and an audit row points at its document.
    @Test
    fun `the audit log is born knowing ISSUE and pointing at documents`() {
        val definitions =
            runBlocking {
                db
                    .sql(
                        """
                        SELECT conname, pg_get_constraintdef(oid) AS def FROM pg_constraint
                        WHERE conrelid = 'wasichai.audit_log'::regclass
                          AND conname IN ('audit_log_operation_valid', 'audit_log_document_id_fkey')
                        """.trimIndent()
                    ).map { row, _ -> row.get("conname", String::class.java)!! to row.get("def", String::class.java)!! }
                    .all()
                    .collectList()
                    .awaitFirstOrNull()
                    .orEmpty()
                    .toMap()
            }
        assertThat(definitions["audit_log_operation_valid"]).contains("'ISSUE'")
        assertThat(definitions["audit_log_document_id_fkey"]).contains("documents(id)").contains("ON DELETE SET NULL")
    }

    @Test
    fun `a template that draws no table stores no related rows`() {
        createType("oficio", sigla)
        issue("oficio")
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.snapshot.related.length()")
            .isEqualTo(0)
    }

    // ---- fixtures ----

    private fun doc(vararg content: Map<String, Any?>): Map<String, Any?> = mapOf("type" to "doc", "content" to content.toList())

    private fun paragraph(vararg content: Map<String, Any?>): Map<String, Any?> = mapOf("type" to "paragraph", "content" to content.toList())

    private fun text(value: String): Map<String, Any?> = mapOf("type" to "text", "text" to value)

    private fun field(name: String): Map<String, Any?> = mapOf("type" to "objectField", "attrs" to mapOf("field" to name))

    private fun createType(
        name: String,
        prefix: String,
        text: String = "Original"
    ) {
        client
            .post()
            .uri("/api/objects/$predio/document-types")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "prefix" to prefix,
                    "label" to if (name == "oficio") "Oficio" else "Constancia",
                    "template" to doc(paragraph(text(text)), paragraph(field("codigo")))
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    // one issue, returning just its number. used by the concurrency test, where only the number
    // matters and the archiving races are the point.
    private fun issueNumber(): String? {
        val body =
            issue("oficio")
                .expectBody()
                .returnResult()
                .responseBody
                ?.decodeToString()
                .orEmpty()
        return Regex("\"number\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
    }

    private fun issue(type: String): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/objects/$predio/records/$record/documents/$type")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()

    private fun createRecord(): String {
        val body =
            client
                .post()
                .uri("/api/objects/$predio/records")
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "P-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody()
                .returnResult()
                .responseBody!!
                .decodeToString()
        return Regex("\"id\":\"([^\"]+)\"").find(body)!!.groupValues[1]
    }

    private fun createObject(name: String) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Predio",
                    "pluralLabel" to "Predios",
                    "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }
}
