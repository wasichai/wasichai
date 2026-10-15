package wasichai.files

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import org.springframework.web.reactive.function.BodyInserters
import reactor.core.publisher.Flux
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// issue 111 (ADR-068): a job inside asPlatform stages, attaches and opens files of its own organization, and
// attaches only what its own block staged.
@TestPropertySource(properties = ["wasichai.files.local.path=build/it-files", "wasichai.files.cleanup.interval=0s"])
class PlatformFilesTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var files: FileService

    @Autowired
    private lateinit var db: DatabaseClient

    private val json = JsonMapper.builder().build()
    private lateinit var admin: String
    private lateinit var adminOrganization: UUID
    private lateinit var tenant: String
    private lateinit var tenantId: UUID
    private lateinit var name: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        adminOrganization = organizationOf(ADMIN_EMAIL)
        val slug = "tenant-" + uniqueName("").take(8)
        client
            .post()
            .uri("/api/organizations")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "Files tenant", "slug" to slug, "adminEmail" to "$slug@wasichai.local", "adminPassword" to "supersecret"))
            .exchange()
            .expectStatus()
            .isCreated
        tenant = bearer("$slug@wasichai.local", "supersecret")
        tenantId = organizationOf("$slug@wasichai.local")
        name = uniqueName("inf")
        // the same object in both organizations: only the organization tells the files apart
        createObject(admin)
        createObject(tenant)
    }

    @Test
    fun `a platform write stages and attaches a file, with no uploader, and the platform opens it`() {
        val (recordId, fileId) =
            runBlocking {
                records.asPlatform(tenantId, "job:drafts") {
                    val staged = files.stage(name, "borrador", pdf("borrador.pdf"))
                    val created = records.create(name, RecordRequest(mapOf("codigo" to "B-1", "borrador" to staged.id.toString())))
                    UUID.fromString(created.id) to staged.id
                }
            }

        // the tenant reads it like any other file; nobody uploaded it
        val read = record(tenant, recordId).get("attributes").get("borrador")
        assertThat(read.get("id").asString()).isEqualTo(fileId.toString())
        assertThat(read.get("name").asString()).isEqualTo("borrador.pdf")
        assertThat(scalar("SELECT coalesce(created_by::text, 'none') FROM wasichai.stored_files WHERE id = '$fileId'")).isEqualTo("none")
        assertThat(scalar("SELECT organization_id::text FROM wasichai.stored_files WHERE id = '$fileId'")).isEqualTo(tenantId.toString())
        assertThat(download(tenant, recordId, "borrador")).isEqualTo(FileFixtures.PDF)

        val opened = runBlocking { records.asPlatform(tenantId) { bytes(files.open(name, recordId, "borrador")) } }
        assertThat(opened).isEqualTo(FileFixtures.PDF)
    }

    @Test
    fun `the platform opens a file a person attached, in its own organization only`() {
        val recordId = createRecord(tenant, mapOf("codigo" to "E-1"))
        attach(tenant, recordId, "borrador")

        val opened = runBlocking { records.asPlatform(tenantId) { bytes(files.open(name, recordId, "borrador")) } }
        assertThat(opened).isEqualTo(FileFixtures.PDF)

        // the other organization's platform: no such record there
        assertThatThrownBy { runBlocking { records.asPlatform(adminOrganization) { files.open(name, recordId, "borrador") } } }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `a platform write attaches neither another organization's file nor a person's upload`() {
        // a file of the admin's organization, attached by a person there
        val foreignRecord = createRecord(admin, mapOf("codigo" to "F-1"))
        val foreign = attach(admin, foreignRecord, "borrador")
        // a person's staged upload in the tenant itself
        val theirs = stageOverRest(tenant)

        listOf(foreign, theirs).forEach { id ->
            assertThatThrownBy {
                runBlocking { records.asPlatform(tenantId) { records.create(name, RecordRequest(mapOf("codigo" to "X", "borrador" to id))) } }
            }.isInstanceOf(ValidationException::class.java)
        }
        // the other organization's platform does not reach the tenant's file either
        val tenantFile = runBlocking { records.asPlatform(tenantId) { files.stage(name, "borrador", pdf("b.pdf")).id } }
        assertThatThrownBy {
            runBlocking { records.asPlatform(adminOrganization) { records.create(name, RecordRequest(mapOf("codigo" to "X", "borrador" to tenantFile))) } }
        }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `what one platform block staged, neither another block nor a person attaches`() {
        val staged = runBlocking { records.asPlatform(tenantId) { files.stage(name, "borrador", pdf("b.pdf")).id } }

        assertThatThrownBy {
            runBlocking { records.asPlatform(tenantId) { records.create(name, RecordRequest(mapOf("codigo" to "X", "borrador" to staged))) } }
        }.isInstanceOf(ValidationException::class.java)

        val created =
            client
                .post()
                .uri("/api/objects/$name/records")
                .header(HttpHeaders.AUTHORIZATION, tenant)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "X", "borrador" to staged.toString())))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertThat(created.status.value()).describedAs(created.responseBody).isEqualTo(400)
    }

    // ---- helpers ----

    private fun pdf(fileName: String) = FileUpload(fileName, "application/pdf", Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(FileFixtures.PDF)))

    private suspend fun bytes(download: FileDownload): ByteArray {
        val joined = DataBufferUtils.join(download.content).awaitSingle()
        try {
            return ByteArray(joined.readableByteCount()).also { joined.read(it) }
        } finally {
            DataBufferUtils.release(joined)
        }
    }

    private fun createObject(token: String) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Informe",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "borrador", "type" to "FILE", "contentTypes" to listOf("application/pdf"))
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    private fun createRecord(
        token: String,
        attributes: Map<String, Any?>
    ): UUID =
        client
            .post()
            .uri("/api/objects/$name/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .let { UUID.fromString(json.readTree(it).get("id").asString()) }

    private fun record(
        token: String,
        id: UUID
    ) = json.readTree(
        client
            .get()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
    )

    // uploads over the record's files route and answers the new file's id
    private fun attach(
        token: String,
        id: UUID,
        field: String
    ): String =
        json
            .readTree(multipart(token, "/api/objects/$name/records/$id/files/$field"))
            .get("attributes")
            .get(field)
            .get("id")
            .asString()

    private fun stageOverRest(token: String): String = json.readTree(multipart(token, "/api/objects/$name/files/borrador")).get("id").asString()

    private fun multipart(
        token: String,
        uri: String
    ): String {
        val parts = MultipartBodyBuilder()
        parts.part("file", FileFixtures.PDF).filename("p.pdf").contentType(MediaType.APPLICATION_PDF)
        return client
            .post()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus()
            .is2xxSuccessful
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
    }

    private fun download(
        token: String,
        id: UUID,
        field: String
    ): ByteArray? =
        client
            .get()
            .uri("/api/objects/$name/records/$id/files/$field")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(ByteArray::class.java)
            .returnResult()
            .responseBody

    private fun organizationOf(email: String): UUID = UUID.fromString(scalar("SELECT organization_id::text FROM wasichai.users WHERE email = '$email'"))

    private fun scalar(sql: String): String =
        runBlocking {
            db
                .sql(sql)
                .map { row, _ -> row.get(0, String::class.java)!! }
                .one()
                .awaitFirstOrNull()!!
        }
}
