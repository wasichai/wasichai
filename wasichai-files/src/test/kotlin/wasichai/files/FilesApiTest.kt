package wasichai.files

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.web.reactive.function.BodyInserters
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

// issue 54's acceptance, on the local store, against a real postgres. every test names its own objects.
// the cleanup does not run by itself here: the tests call it.
@TestPropertySource(properties = ["wasichai.files.local.path=build/it-files", "wasichai.files.cleanup.interval=0s"])
class FilesApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var cleanup: StoredFileCleanup

    private val json = JsonMapper.builder().build()
    private val storeRoot = Path.of("build/it-files")
    private lateinit var admin: String

    @BeforeEach
    fun signIn() {
        admin = bearer()
    }

    @Test
    fun `a FILE field added to a populated object holds an upload, the record shows its descriptor, the bytes only come from the files route`() {
        val name = createObject(fields = listOf(text("codigo")))
        val id = createRecord(admin, name, mapOf("codigo" to "E-1"))
        client
            .post()
            .uri("/api/metadata/objects/$name/fields")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "acta", "type" to "FILE", "maxBytes" to 64))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.type")
            .isEqualTo("FILE")
            .jsonPath("$.file.maxBytes")
            .isEqualTo(64)
            .jsonPath("$.file.contentTypes")
            .doesNotExist()
        assertThat(record(admin, name, id).get("attributes").get("acta").isNull).isTrue()

        val uploaded = upload(admin, "/api/objects/$name/records/$id/files/acta", FileFixtures.PDF, "acta.pdf", "application/pdf")
        assertThat(uploaded.status.value()).isEqualTo(200)
        val descriptor = json.readTree(uploaded.responseBody).get("attributes").get("acta")
        assertThat(descriptor.propertyNames()).containsExactly("id", "name", "contentType", "size", "sha256")
        assertThat(descriptor.get("name").asString()).isEqualTo("acta.pdf")
        assertThat(descriptor.get("contentType").asString()).isEqualTo("application/pdf")
        assertThat(descriptor.get("size").asLong()).isEqualTo(FileFixtures.PDF.size.toLong())
        assertThat(descriptor.get("sha256").asString()).isEqualTo(sha256(FileFixtures.PDF))
        assertThat(uploaded.responseHeaders.eTag).isNotNull()

        // the record reads the same small object, and nothing else of the file
        assertThat(record(admin, name, id).get("attributes").get("acta")).isEqualTo(descriptor)

        val download = download(admin, name, id, "acta")
        assertThat(download.status.value()).isEqualTo(200)
        assertThat(download.responseBody).isEqualTo(FileFixtures.PDF)
        assertThat(download.responseHeaders.contentType.toString()).isEqualTo("application/pdf")
        assertThat(download.responseHeaders.contentDisposition.type).isEqualTo("attachment")
        assertThat(download.responseHeaders.contentDisposition.filename).isEqualTo("acta.pdf")
        assertThat(download.responseHeaders.getFirst("X-Content-Type-Options")).isEqualTo("nosniff")

        // the column holds the id; the key is <organization>/<id>, never the client's name
        val fileId = descriptor.get("id").asString()
        val key = scalar("SELECT object_key FROM wasichai.stored_files WHERE id = '$fileId'")
        assertThat(key).endsWith("/$fileId").doesNotContain("acta")
        assertThat(storeRoot.resolve(key)).hasBinaryContent(FileFixtures.PDF)
        assertThat(
            scalar(
                "SELECT data_type FROM information_schema.columns WHERE table_schema = 'app_data' AND column_name = 'acta' AND table_name = '${table(name)}'"
            )
        ).isEqualTo("uuid")
    }

    @Test
    fun `an IMAGE is served inline, its type sniffed from the bytes, not from the client`() {
        val name = createObject()
        val id = createRecord(admin, name, mapOf("codigo" to "I-1"))
        val uploaded = upload(admin, "/api/objects/$name/records/$id/files/foto", FileFixtures.PNG, "foto.bin", "application/octet-stream")
        assertThat(uploaded.status.value()).isEqualTo(200)
        assertThat(
            json
                .readTree(uploaded.responseBody)
                .get("attributes")
                .get("foto")
                .get("contentType")
                .asString()
        ).isEqualTo("image/png")

        val download = download(admin, name, id, "foto")
        assertThat(download.responseHeaders.contentType.toString()).isEqualTo("image/png")
        assertThat(download.responseHeaders.contentDisposition.type).isEqualTo("inline")
        assertThat(download.responseHeaders.getFirst("X-Content-Type-Options")).isEqualTo("nosniff")
    }

    @Test
    fun `an upload over maxBytes, of a type the field refuses, or empty, is a 400 on the field and nothing is stored`() {
        val name = createObject()
        val id = createRecord(admin, name, mapOf("codigo" to "V-1"))
        val before = storedCount(name)

        val tooBig = FileFixtures.PDF + ByteArray(64)
        refusedOn("acta", upload(admin, "/api/objects/$name/records/$id/files/acta", tooBig, "grande.pdf", "application/pdf"))
        // a pdf that calls itself a png is still a pdf
        refusedOn("foto", upload(admin, "/api/objects/$name/records/$id/files/foto", FileFixtures.PDF, "foto.png", "image/png"))
        refusedOn("acta", upload(admin, "/api/objects/$name/records/$id/files/acta", ByteArray(0), "vacio.pdf", "application/pdf"))
        // the admin's own allow-list: acta takes pdfs only
        refusedOn("acta", upload(admin, "/api/objects/$name/records/$id/files/acta", FileFixtures.PNG, "foto.png", "image/png"))

        assertThat(storedCount(name)).isEqualTo(before)
        assertThat(record(admin, name, id).get("attributes").get("acta").isNull).isTrue()
    }

    @Test
    fun `uploading takes UPDATE and downloading READ, exactly as for the record`() {
        val name = createObject()
        val id = createRecord(admin, name, mapOf("codigo" to "P-1"))
        attach(admin, name, id, "acta", FileFixtures.PDF)

        val reader = userWith(listOf("READ"))
        assertThat(upload(reader, "/api/objects/$name/records/$id/files/acta", FileFixtures.PDF, "otra.pdf", "application/pdf").status.value())
            .isEqualTo(403)
        assertThat(download(reader, name, id, "acta").responseBody).isEqualTo(FileFixtures.PDF)

        val nobody = userWith(listOf("CREATE"))
        assertThat(download(nobody, name, id, "acta").status.value()).isEqualTo(403)
        assertThat(storedCount(name)).isEqualTo(1)
    }

    @Test
    fun `own records only hides another's record and its file, both ways`() {
        val name = createObject()
        val theirs = createRecord(admin, name, mapOf("codigo" to "O-1"))
        attach(admin, name, theirs, "acta", FileFixtures.PDF)
        val owner = userWith(listOf("READ", "CREATE", "UPDATE"), ownRecordsOnly = true)
        val mine = createRecord(owner, name, mapOf("codigo" to "O-2"))

        assertThat(upload(owner, "/api/objects/$name/records/$mine/files/acta", FileFixtures.PDF, "mia.pdf", "application/pdf").status.value())
            .isEqualTo(200)
        assertThat(download(owner, name, mine, "acta").status.value()).isEqualTo(200)
        assertThat(download(owner, name, theirs, "acta").status.value()).isEqualTo(404)
        assertThat(upload(owner, "/api/objects/$name/records/$theirs/files/acta", FileFixtures.PDF, "x.pdf", "application/pdf").status.value())
            .isEqualTo(404)
        assertThat(storedCount(name)).isEqualTo(2)
    }

    @Test
    fun `a field the role cannot read is no file to it, and one it cannot write takes no upload`() {
        val name = createObject()
        val id = createRecord(admin, name, mapOf("codigo" to "F-1"))
        attach(admin, name, id, "acta", FileFixtures.PDF)
        val role = newRole(listOf("READ", "UPDATE"))
        restrictField(role, name, "acta", read = false, write = false)
        val token = newUser(role)

        assertThat(download(token, name, id, "acta").status.value()).isEqualTo(404)
        refusedOn("acta", upload(token, "/api/objects/$name/records/$id/files/acta", FileFixtures.PDF, "x.pdf", "application/pdf"))
        assertThat(storedCount(name)).isEqualTo(1)
    }

    @Test
    fun `on an append-only object a staged upload attaches on create, and is never replaced`() {
        val name = createObject(appendOnly = true)
        val staged = upload(admin, "/api/objects/$name/files/acta", FileFixtures.PDF, "evidencia.pdf", "application/pdf")
        assertThat(staged.status.value()).isEqualTo(201)
        val fileId = json.readTree(staged.responseBody).get("id").asString()

        val created =
            client
                .post()
                .uri("/api/objects/$name/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "A-1", "acta" to fileId)))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val record = json.readTree(created)
        assertThat(
            record
                .get("attributes")
                .get("acta")
                .get("name")
                .asString()
        ).isEqualTo("evidencia.pdf")
        val id = record.get("id").asString()

        val replaced = upload(admin, "/api/objects/$name/records/$id/files/acta", FileFixtures.PDF, "otra.pdf", "application/pdf")
        assertThat(replaced.status.value()).isEqualTo(409)
        // the refused upload's bytes and row are gone at once
        assertThat(storedCount(name)).isEqualTo(1)
        assertThat(download(admin, name, UUID.fromString(id), "acta").responseBody).isEqualTo(FileFixtures.PDF)
    }

    @Test
    fun `on a requiresReason object an upload without the reason is a 400 on reason, and the reason lands on its audit row`() {
        val name = createObject(requiresReason = true)
        val id =
            client
                .post()
                .uri("/api/objects/$name/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .header("X-Change-Reason", "alta")
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "R-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
                .let { UUID.fromString(json.readTree(it).get("id").asString()) }

        refusedOn("reason", upload(admin, "/api/objects/$name/records/$id/files/acta", FileFixtures.PDF, "acta.pdf", "application/pdf"))
        assertThat(storedCount(name)).isZero()

        val ok =
            upload(
                admin,
                "/api/objects/$name/records/$id/files/acta",
                FileFixtures.PDF,
                "acta.pdf",
                "application/pdf",
                mapOf("X-Change-Reason" to "acta firmada")
            )
        assertThat(ok.status.value()).isEqualTo(200)
        assertThat(scalar("SELECT reason FROM wasichai.audit_log WHERE record_id = '$id' AND operation = 'UPDATE'")).isEqualTo("acta firmada")
    }

    @Test
    fun `the audit entry of an upload shows which descriptor replaced which, never bytes`() {
        val name = createObject()
        val id = createRecord(admin, name, mapOf("codigo" to "H-1"))
        val first = attach(admin, name, id, "acta", FileFixtures.PDF)
        val second = attach(admin, name, id, "acta", FileFixtures.PDF + "%%EOF".toByteArray())

        client
            .get()
            .uri("/api/audit?objectName=$name&operation=UPDATE")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[0].changes.length()")
            .isEqualTo(1)
            .jsonPath("$[0].changes[0].field")
            .isEqualTo("acta")
            .jsonPath("$[0].changes[0].before.id")
            .isEqualTo(first)
            .jsonPath("$[0].changes[0].after.id")
            .isEqualTo(second)
            .jsonPath("$[1].changes[0].before")
            .isEmpty()
            .jsonPath("$[1].changes[0].after.id")
            .isEqualTo(first)

        val stored = json.readTree(scalar("SELECT after_state::text FROM wasichai.audit_log WHERE after_state->'acta'->>'id' = '$second'"))
        // jsonb keeps its own key order
        assertThat(stored.get("acta").propertyNames()).containsExactlyInAnyOrder("id", "name", "contentType", "size", "sha256")
    }

    @Test
    fun `a record write attaches only the writer's own fresh upload, and keeps or clears what it holds`() {
        val name = createObject()
        val id = createRecord(admin, name, mapOf("codigo" to "G-1"))
        val held = attach(admin, name, id, "acta", FileFixtures.PDF)

        // another record's file, by id or as a descriptor: not this writer's upload for this field
        val other = createObject()
        val otherId = createRecord(admin, other, mapOf("codigo" to "G-2"))
        val foreign = attach(admin, other, otherId, "acta", FileFixtures.PDF)
        refusedOn("acta", patch(admin, name, id, mapOf("acta" to foreign)))
        refusedOn("acta", patch(admin, name, id, mapOf("acta" to mapOf("id" to foreign))))
        refusedOn("acta", patch(admin, name, id, mapOf("acta" to UUID.randomUUID().toString())))
        // another user's staged upload for this very field
        val member = userWith(listOf("READ", "CREATE", "UPDATE"))
        val theirs =
            json
                .readTree(
                    upload(member, "/api/objects/$name/files/acta", FileFixtures.PDF, "suya.pdf", "application/pdf").responseBody
                ).get("id")
                .asString()
        refusedOn("acta", patch(admin, name, id, mapOf("acta" to theirs)))

        // a PUT sends back what it read: the held file stays
        val read = record(admin, name, id).get("attributes")
        val put =
            client
                .put()
                .uri("/api/objects/$name/records/$id")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("attributes" to json.convertValue(read, Map::class.java)))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertThat(put.status.value()).isEqualTo(200)
        assertThat(
            json
                .readTree(put.responseBody)
                .get("attributes")
                .get("acta")
                .get("id")
                .asString()
        ).isEqualTo(held)

        // null clears
        val cleared = patch(admin, name, id, mapOf("acta" to null))
        assertThat(cleared.status.value()).isEqualTo(200)
        assertThat(
            json
                .readTree(cleared.responseBody)
                .get("attributes")
                .get("acta")
                .isNull
        ).isTrue()
    }

    @Test
    fun `an apiOnly object refuses the upload route, and a stale If-Match is a 412, neither keeping the bytes`() {
        val apiOnly = createObject(apiOnly = true)
        assertThat(upload(admin, "/api/objects/$apiOnly/records/${UUID.randomUUID()}/files/acta", FileFixtures.PDF, "a.pdf", "application/pdf").status.value())
            .isEqualTo(403)
        assertThat(storedCount(apiOnly)).isZero()

        val name = createObject()
        val id = createRecord(admin, name, mapOf("codigo" to "M-1"))
        val stale =
            upload(
                admin,
                "/api/objects/$name/records/$id/files/acta",
                FileFixtures.PDF,
                "a.pdf",
                "application/pdf",
                mapOf("If-Match" to "\"2000-01-01T00:00:00Z\"")
            )
        assertThat(stale.status.value()).isEqualTo(412)
        assertThat(storedCount(name)).isZero()

        val etag = recordETag(admin, name, id)
        val fresh = upload(admin, "/api/objects/$name/records/$id/files/acta", FileFixtures.PDF, "a.pdf", "application/pdf", mapOf("If-Match" to etag))
        assertThat(fresh.status.value()).isEqualTo(200)
    }

    @Test
    fun `the cleanup deletes the files no record names - replaced, of a deleted record, of a deleted field - and keeps the rest`() {
        val name = createObject()
        val kept = createRecord(admin, name, mapOf("codigo" to "C-1"))
        val replaced = attach(admin, name, kept, "acta", FileFixtures.PDF)
        val current = attach(admin, name, kept, "acta", FileFixtures.PDF + "%%EOF".toByteArray())
        val gone = createRecord(admin, name, mapOf("codigo" to "C-2"))
        val ofDeletedRecord = attach(admin, name, gone, "acta", FileFixtures.PDF)
        client
            .delete()
            .uri("/api/objects/$name/records/$gone")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNoContent

        val other = createObject()
        val otherRecord = createRecord(admin, other, mapOf("codigo" to "C-3"))
        val ofDeletedField = attach(admin, other, otherRecord, "acta", FileFixtures.PDF)
        client
            .delete()
            .uri("/api/metadata/objects/$other/fields/acta")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .is2xxSuccessful

        // a fresh upload nobody attached yet: within the delay, it stays
        val staged =
            json
                .readTree(
                    upload(admin, "/api/objects/$name/files/acta", FileFixtures.PDF, "s.pdf", "application/pdf").responseBody
                ).get("id")
                .asString()

        val old = listOf(replaced, current, ofDeletedRecord, ofDeletedField)
        val keys = old.associateWith { scalar("SELECT object_key FROM wasichai.stored_files WHERE id = '$it'") }
        execute("UPDATE wasichai.stored_files SET created_at = now() - interval '2 days' WHERE id IN (${old.joinToString { "'$it'" }})")

        val deleted = runBlocking { cleanup.runOnce() }
        assertThat(deleted).isNotNull().isGreaterThanOrEqualTo(3)

        listOf(replaced, ofDeletedRecord, ofDeletedField).forEach { id ->
            assertThat(scalar("SELECT count(*)::text FROM wasichai.stored_files WHERE id = '$id'")).isEqualTo("0")
            assertThat(storeRoot.resolve(keys.getValue(id))).doesNotExist()
        }
        assertThat(scalar("SELECT count(*)::text FROM wasichai.stored_files WHERE id IN ('$current', '$staged')")).isEqualTo("2")
        assertThat(storeRoot.resolve(keys.getValue(current))).exists()
        assertThat(download(admin, name, kept, "acta").responseBody).isEqualTo(FileFixtures.PDF + "%%EOF".toByteArray())
    }

    // ---- helpers ----

    private fun text(name: String) = mapOf("name" to name, "type" to "TEXT")

    private fun createObject(
        fields: List<Map<String, Any?>> =
            listOf(
                text("codigo"),
                mapOf("name" to "acta", "type" to "FILE", "maxBytes" to 64, "contentTypes" to listOf("application/pdf")),
                mapOf("name" to "foto", "type" to "IMAGE")
            ),
        appendOnly: Boolean = false,
        apiOnly: Boolean = false,
        requiresReason: Boolean = false
    ): String {
        val name = uniqueName("exp")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Expediente",
                    "fields" to fields,
                    "appendOnly" to appendOnly,
                    "apiOnly" to apiOnly,
                    "requiresReason" to requiresReason
                )
            ).exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun createRecord(
        token: String,
        name: String,
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
        name: String,
        id: UUID
    ): JsonNode =
        json.readTree(
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

    private fun recordETag(
        token: String,
        name: String,
        id: UUID
    ): String =
        client
            .get()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseHeaders.eTag!!

    // uploads and answers the new file's id
    private fun attach(
        token: String,
        name: String,
        id: UUID,
        field: String,
        bytes: ByteArray
    ): String {
        val result = upload(token, "/api/objects/$name/records/$id/files/$field", bytes, "$field.pdf", "application/pdf")
        assertThat(result.status.value()).describedAs(result.responseBody).isEqualTo(200)
        return json
            .readTree(result.responseBody)
            .get("attributes")
            .get(field)
            .get("id")
            .asString()
    }

    private fun upload(
        token: String,
        uri: String,
        bytes: ByteArray,
        fileName: String,
        contentType: String,
        headers: Map<String, String> = emptyMap()
    ): EntityExchangeResult<String> {
        val parts = MultipartBodyBuilder()
        parts.part("file", bytes).filename(fileName).contentType(MediaType.parseMediaType(contentType))
        return client
            .post()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, token)
            .headers { h -> headers.forEach { (k, v) -> h.set(k, v) } }
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
    }

    private fun download(
        token: String,
        name: String,
        id: UUID,
        field: String
    ): EntityExchangeResult<ByteArray> =
        client
            .get()
            .uri("/api/objects/$name/records/$id/files/$field")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectBody(ByteArray::class.java)
            .returnResult()

    private fun patch(
        token: String,
        name: String,
        id: UUID,
        attributes: Map<String, Any?>
    ): EntityExchangeResult<String> =
        client
            .patch()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()
            .expectBody(String::class.java)
            .returnResult()

    private fun refusedOn(
        field: String,
        result: EntityExchangeResult<String>
    ) {
        assertThat(result.status.value()).describedAs(result.responseBody).isEqualTo(400)
        val fields = (json.readTree(result.responseBody).path("errors") as Iterable<JsonNode>).map { it.path("field").asString("") }
        assertThat(fields).describedAs(result.responseBody).containsExactly(field)
    }

    private fun userWith(
        actions: List<String>,
        ownRecordsOnly: Boolean = false
    ): String = newUser(newRole(actions, ownRecordsOnly))

    private fun newRole(
        actions: List<String>,
        ownRecordsOnly: Boolean = false
    ): String {
        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to role, "label" to "Rol", "ownRecordsOnly" to ownRecordsOnly))
            .exchange()
            .expectStatus()
            .isCreated
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

    private fun storedCount(objectName: String): Int =
        scalar(
            "SELECT count(*)::text FROM wasichai.stored_files s JOIN wasichai.custom_objects o ON o.id = s.object_id WHERE o.name = '$objectName'"
        ).toInt()

    private fun table(objectName: String): String = scalar("SELECT physical_table FROM wasichai.custom_objects WHERE name = '$objectName'")

    private fun scalar(sql: String): String =
        runBlocking {
            db
                .sql(sql)
                .map { row, _ -> row.get(0, String::class.java)!! }
                .one()
                .awaitFirstOrNull()!!
        }

    private fun execute(sql: String) {
        runBlocking {
            db
                .sql(sql)
                .fetch()
                .rowsUpdated()
                .awaitFirstOrNull()
        }
    }

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
