package wasichai.files

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.web.reactive.function.BodyInserters
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// issue 90's acceptance (ADR-064): with a policy that hides the name of a reserved record's files, a reader
// without the right never gets the original name, from any route; ADMIN, whom the policy lets through, does
@TestPropertySource(properties = ["wasichai.files.local.path=build/it-files-policy", "wasichai.files.cleanup.interval=0s"])
@Import(FileDescriptorReadPolicyApiTest.ReservedNames::class)
class FileDescriptorReadPolicyApiTest : WasichaiIntegrationTest() {
    // the app's rule: decided per record on a field the reader cannot even read
    @TestConfiguration
    class ReservedNames {
        @Bean
        fun reservedName(): FileDescriptorReadPolicy =
            FileDescriptorReadPolicy { read ->
                if (read.caller.isAdmin || read.record?.get("clasificacion") != "RESERVADO") {
                    read.descriptor
                } else {
                    read.descriptor + ("name" to RESERVED) - "sha256"
                }
            }
    }

    private val json = JsonMapper.builder().build()
    private lateinit var admin: String

    @BeforeEach
    fun signIn() {
        admin = bearer()
    }

    @Test
    fun `a reserved record's file name never reaches a reader without the right, and reaches one with it`() {
        val caso = uniqueName("caso")
        val evidencia = uniqueName("evid")
        post(admin, "/api/objects", mapOf("name" to caso, "label" to "Caso", "fields" to listOf(text("codigo"))))
        post(
            admin,
            "/api/objects",
            mapOf("name" to evidencia, "label" to "Evidencia", "fields" to listOf(text("clasificacion"), mapOf("name" to "acta", "type" to "FILE")))
        )
        val relationship = "${evidencia}_caso"
        post(
            admin,
            "/api/relationships",
            mapOf("name" to relationship, "label" to "Caso", "type" to "MANY_TO_ONE", "source" to evidencia, "target" to caso, "fieldName" to "caso")
        )
        val casoId = createRecord(admin, caso, mapOf("codigo" to "C-1"))
        val reserved = createRecord(admin, evidencia, mapOf("clasificacion" to "RESERVADO", "caso" to casoId.toString()))
        val open = createRecord(admin, evidencia, mapOf("clasificacion" to "PUBLICO", "caso" to casoId.toString()))

        // ADMIN's own upload answers the name it sent
        val uploaded = upload(admin, evidencia, reserved, "juan-perez-dni.pdf")
        assertThat(uploaded.status.value()).describedAs(uploaded.responseBody).isEqualTo(200)
        val stored = json.readTree(uploaded.responseBody).get("attributes").get("acta")
        assertThat(stored.get("name").asString()).isEqualTo("juan-perez-dni.pdf")
        assertThat(upload(admin, evidencia, open, "plaza.pdf").status.value()).isEqualTo(200)

        // the reader may read and update everything but the classification
        val role = newRole(listOf("READ", "UPDATE"))
        restrictField(role, evidencia, "clasificacion", read = false, write = false)
        val reader = newUser(role)

        val answers = mutableListOf<String>()

        fun read(uri: String): String = get(reader, uri).also { answers += it }

        // the record: the same file, its id kept, its name replaced, its hash left out
        val record = json.readTree(read("/api/objects/$evidencia/records/$reserved")).get("attributes")
        assertThat(record.has("clasificacion")).isFalse()
        assertThat(record.get("acta").get("id")).isEqualTo(stored.get("id"))
        assertThat(record.get("acta").get("name").asString()).isEqualTo(RESERVED)
        assertThat(record.get("acta").has("sha256")).isFalse()
        assertThat(record.get("acta").get("size")).isEqualTo(stored.get("size"))
        // the policy decides per record: the public one keeps its name
        assertThat(acta(json.readTree(read("/api/objects/$evidencia/records/$open"))).get("name").asString()).isEqualTo("plaza.pdf")

        // the list, the related records and the history and audit log
        assertThat(names(json.readTree(read("/api/objects/$evidencia/records")).get("content"))).containsExactlyInAnyOrder(RESERVED, "plaza.pdf")
        assertThat(names(json.readTree(read("/api/objects/$caso/records/$casoId/related/$relationship")).get("content")))
            .containsExactlyInAnyOrder(RESERVED, "plaza.pdf")
        val history = json.readTree(read("/api/objects/$evidencia/records/$reserved/history"))
        val change = items(history).flatMap { items(it.get("changes")) }.single { it.get("field").asString() == "acta" }
        assertThat(change.get("after").get("name").asString()).isEqualTo(RESERVED)
        read("/api/audit?objectName=$evidencia")

        // the download is named as the reader reads it
        val download = download(reader, evidencia, reserved)
        assertThat(download.status.value()).isEqualTo(200)
        assertThat(download.responseBody).isEqualTo(FileFixtures.PDF)
        val disposition = download.responseHeaders.getFirst(HttpHeaders.CONTENT_DISPOSITION).orEmpty()
        assertThat(disposition).contains(RESERVED).doesNotContain("juan-perez")

        // a reader's own upload on a reserved record answers the replaced name too
        val replaced = upload(reader, evidencia, reserved, "maria-lopez-dni.pdf")
        assertThat(replaced.status.value()).describedAs(replaced.responseBody).isEqualTo(200)
        answers += replaced.responseBody!!
        assertThat(acta(json.readTree(replaced.responseBody)).get("name").asString()).isEqualTo(RESERVED)

        assertThat(answers).allSatisfy { assertThat(it).doesNotContain("juan-perez").doesNotContain("maria-lopez") }

        // ADMIN, whom the policy lets through, reads the names everywhere
        assertThat(acta(json.readTree(get(admin, "/api/objects/$evidencia/records/$reserved"))).get("name").asString()).isEqualTo("maria-lopez-dni.pdf")
        assertThat(get(admin, "/api/objects/$evidencia/records/$reserved/history")).contains("juan-perez-dni.pdf", "maria-lopez-dni.pdf")
        assertThat(download(admin, evidencia, reserved).responseHeaders.getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("maria-lopez-dni.pdf")
    }

    // ---- helpers ----

    private fun acta(record: JsonNode): JsonNode = record.get("attributes").get("acta")

    private fun items(node: JsonNode): List<JsonNode> = (node as Iterable<JsonNode>).toList()

    private fun names(records: JsonNode): List<String> = items(records).map { acta(it).get("name").asString() }

    private fun text(name: String) = mapOf("name" to name, "type" to "TEXT")

    private fun post(
        token: String,
        uri: String,
        body: Any
    ): String =
        client
            .post()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .is2xxSuccessful
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            .orEmpty()

    private fun get(
        token: String,
        uri: String
    ): String =
        client
            .get()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

    private fun createRecord(
        token: String,
        name: String,
        attributes: Map<String, Any?>
    ): UUID = UUID.fromString(json.readTree(post(token, "/api/objects/$name/records", mapOf("attributes" to attributes))).get("id").asString())

    private fun upload(
        token: String,
        name: String,
        id: UUID,
        fileName: String
    ): EntityExchangeResult<String> {
        val parts = MultipartBodyBuilder()
        parts.part("file", FileFixtures.PDF).filename(fileName).contentType(MediaType.APPLICATION_PDF)
        return client
            .post()
            .uri("/api/objects/$name/records/$id/files/acta")
            .header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
    }

    private fun download(
        token: String,
        name: String,
        id: UUID
    ): EntityExchangeResult<ByteArray> =
        client
            .get()
            .uri("/api/objects/$name/records/$id/files/acta")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectBody(ByteArray::class.java)
            .returnResult()

    private fun newRole(actions: List<String>): String {
        val role = "R" + uniqueName("").uppercase()
        post(admin, "/api/roles", mapOf("name" to role, "label" to "Rol", "ownRecordsOnly" to false))
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to actions.map { mapOf("objectName" to null, "action" to it, "allowed" to true) }))
            .exchange()
            .expectStatus()
            .isOk
        return role
    }

    private fun restrictField(
        role: String,
        objectName: String,
        fieldName: String,
        read: Boolean,
        write: Boolean
    ) {
        client
            .put()
            .uri("/api/roles/$role/field-permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("fields" to listOf(mapOf("objectName" to objectName, "fieldName" to fieldName, "read" to read, "write" to write))))
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun newUser(role: String): String {
        val email = "${uniqueName("member")}@wasichai.local"
        post(admin, "/api/users", mapOf("email" to email, "displayName" to "Member", "password" to "supersecret", "roles" to listOf(role)))
        return bearer(email, "supersecret")
    }

    private companion object {
        const val RESERVED = "Archivo reservado"
    }
}
