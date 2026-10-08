package wasichai.it.slice.files

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.context.TestPropertySource
import org.springframework.web.reactive.function.BodyInserters
import wasichai.it.support.SliceSmokeTest

@TestPropertySource(properties = ["wasichai.files.local.path=build/it-files-slice", "wasichai.files.cleanup.interval=0s"])
class FilesOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("files")

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)

    @Test
    fun `an IMAGE field takes an upload and serves it back inline`() {
        client
            .post()
            .uri("/api/metadata/objects/$objectName/fields")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "foto", "type" to "IMAGE"))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.file.contentTypes[0]")
            .isEqualTo("image/png")
        val id =
            client
                .post()
                .uri("/api/objects/$objectName/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "F-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
                .substringAfter("\"id\":\"")
                .substringBefore("\"")

        val parts = MultipartBodyBuilder()
        parts.part("file", png).filename("foto.png").contentType(MediaType.IMAGE_PNG)
        client
            .post()
            .uri("/api/objects/$objectName/records/$id/files/foto")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.attributes.foto.contentType")
            .isEqualTo("image/png")
            .jsonPath("$.attributes.foto.size")
            .isEqualTo(png.size)

        client
            .get()
            .uri("/api/objects/$objectName/records/$id/files/foto")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .valueMatches(HttpHeaders.CONTENT_DISPOSITION, "inline.*")
            .expectBody(ByteArray::class.java)
            .isEqualTo(png)
    }
}
