package wasichai.it.slice.documents

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import wasichai.it.support.SliceSmokeTest
import java.time.Year
import java.time.ZoneOffset

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
}
