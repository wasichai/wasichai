package wasichai.it.slice.documents

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import wasichai.it.support.SliceSmokeTest
import java.time.Year
import java.time.ZoneOffset
import java.util.UUID

class DocumentsOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("documents")

    @Test
    fun `a document is issued and numbered without automation installed`() {
        val prefix = uniqueName("s").uppercase().take(10)
        client
            .post()
            .uri("/api/objects/$objectName/document-types")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to "oficio",
                    "prefix" to prefix,
                    "label" to "Oficio",
                    "template" to
                        mapOf(
                            "type" to "doc",
                            "content" to
                                listOf(
                                    mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to "Original"))),
                                    mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "objectField", "attrs" to mapOf("field" to "codigo"))))
                                )
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
        val created =
            client
                .post()
                .uri("/api/objects/$objectName/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "D-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val id = created.substringAfter("\"id\":\"").substringBefore("\"")

        client
            .post()
            .uri("/api/objects/$objectName/records/$id/documents/oficio")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.number")
            .isEqualTo("$prefix-${Year.now(ZoneOffset.UTC).value}-001")
            .jsonPath("$.status")
            .isEqualTo("VALID")

        client
            .get()
            .uri("/api/objects/$objectName/records/$id/documents")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
    }

    // issue 58 (ADR-054): audit_log refuses UPDATE and DELETE, but deleting a tenant still works. its documents go with
    // it, their ISSUE rows lose only document_id (the ON DELETE SET NULL the trigger lets through), and the trail stays.
    @Test
    fun `deleting a tenant with issued documents keeps its trail, the issue rows losing only their document id`() {
        val slug = "guard-" + uniqueName("").take(8)
        val created =
            client
                .post()
                .uri("/api/organizations")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("name" to "Guarded", "slug" to slug, "adminEmail" to "$slug@wasichai.local", "adminPassword" to "tenant-password"))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val organizationId = UUID.fromString(created.substringAfter("\"id\":\"").substringBefore("\""))
        val tenant = bearer("$slug@wasichai.local", "tenant-password")
        val obj = uniqueName("guarded")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, tenant)
            .bodyValue(mapOf("name" to obj, "label" to "Guarded", "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .post()
            .uri("/api/objects/$obj/document-types")
            .header(HttpHeaders.AUTHORIZATION, tenant)
            .bodyValue(
                mapOf(
                    "name" to "oficio",
                    "prefix" to "G" + uniqueName("").uppercase().take(8),
                    "label" to "Oficio",
                    "template" to
                        mapOf("type" to "doc", "content" to listOf(mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to "Hi")))))
                )
            ).exchange()
            .expectStatus()
            .isCreated
        val record =
            client
                .post()
                .uri("/api/objects/$obj/records")
                .header(HttpHeaders.AUTHORIZATION, tenant)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "G-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
                .substringAfter("\"id\":\"")
                .substringBefore("\"")
        client
            .post()
            .uri("/api/objects/$obj/records/$record/documents/oficio")
            .header(HttpHeaders.AUTHORIZATION, tenant)
            .exchange()
            .expectStatus()
            .isCreated
        val before = trail(organizationId)
        val issued = before.values.single { it.startsWith("ISSUE ") }
        assertThat(issued).doesNotEndWith(" null")

        client
            .delete()
            .uri("/api/organizations/current")
            .header(HttpHeaders.AUTHORIZATION, tenant)
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.NO_CONTENT)

        val after = trail(organizationId)
        // every row is still there, plus the tenant's own DELETE entry
        assertThat(after.keys).containsAll(before.keys).hasSize(before.size + 1)
        before.forEach { (id, line) ->
            if (line == issued) {
                // only document_id changed
                assertThat(after[id]).isEqualTo(line.substringBeforeLast(" ") + " null")
            } else {
                assertThat(after[id]).isEqualTo(line)
            }
        }
    }

    // id -> "<operation> <every other column as json> <document_id>"
    private fun trail(organizationId: UUID): Map<String, String> =
        runBlocking {
            db
                .sql(
                    """
                    SELECT id::text AS id, operation || ' ' || (to_jsonb(a) - 'document_id')::text || ' ' || coalesce(document_id::text, 'null') AS line
                    FROM wasichai.audit_log a WHERE organization_id = :organizationId
                    """.trimIndent()
                ).bind("organizationId", organizationId)
                .map { row, _ -> row.get("id", String::class.java)!! to row.get("line", String::class.java)!! }
                .all()
                .collectList()
                .awaitFirstOrNull()
                .orEmpty()
                .toMap()
        }
}
