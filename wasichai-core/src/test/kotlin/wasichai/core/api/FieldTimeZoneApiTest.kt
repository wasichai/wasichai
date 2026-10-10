package wasichai.core.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import wasichai.test.WasichaiIntegrationTest
import java.time.Instant

// issue 91: a DATETIME field may name its own zone. stored with the field, nothing converted
class FieldTimeZoneApiTest : WasichaiIntegrationTest() {
    private lateinit var objectName: String
    private lateinit var token: String

    @BeforeEach
    fun createObject() {
        objectName = uniqueName("incidente")
        token = bearer()
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to objectName,
                    "label" to "Incidente",
                    "fields" to
                        listOf(
                            mapOf("name" to "ocurrido", "type" to "DATETIME", "timeZone" to "America/Lima"),
                            mapOf("name" to "plazo", "type" to "DATETIME"),
                            mapOf("name" to "titulo", "type" to "TEXT")
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `a zone given on create is read back, and a field without one has no such key`() {
        definition()
            .jsonPath("$.fields[?(@.name == 'ocurrido')].timeZone")
            .isEqualTo("America/Lima")
            .jsonPath("$.fields[?(@.name == 'plazo')].timeZone")
            .doesNotExist()
            .jsonPath("$.fields[?(@.name == 'titulo')].timeZone")
            .doesNotExist()
    }

    @Test
    fun `an update sets, changes and clears the zone, and leaving it out keeps it`() {
        updateField("plazo", mapOf("timeZone" to "Europe/Madrid"))
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.timeZone")
            .isEqualTo("Europe/Madrid")
        updateField("plazo", mapOf("label" to "Plazo"))
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.timeZone")
            .isEqualTo("Europe/Madrid")
        updateField("ocurrido", mapOf("timeZone" to ""))
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.timeZone")
            .doesNotExist()

        definition()
            .jsonPath("$.fields[?(@.name == 'plazo')].timeZone")
            .isEqualTo("Europe/Madrid")
            .jsonPath("$.fields[?(@.name == 'ocurrido')].timeZone")
            .doesNotExist()
    }

    // issue 99: a region in any case and a fixed offset, both answered in one spelling
    @Test
    fun `a lowercase region and a fixed offset are stored and answered normalized`() {
        client
            .post()
            .uri("/api/metadata/objects/$objectName/fields")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to "contrato", "type" to "DATETIME", "timeZone" to "-0500"))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.timeZone")
            .isEqualTo("-05:00")
        updateField("plazo", mapOf("timeZone" to "america/lima"))
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.timeZone")
            .isEqualTo("America/Lima")
        updateField("ocurrido", mapOf("timeZone" to "+05:00"))
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.timeZone")
            .isEqualTo("+05:00")

        definition()
            .jsonPath("$.fields[?(@.name == 'contrato')].timeZone")
            .isEqualTo("-05:00")
            .jsonPath("$.fields[?(@.name == 'plazo')].timeZone")
            .isEqualTo("America/Lima")
            .jsonPath("$.fields[?(@.name == 'ocurrido')].timeZone")
            .isEqualTo("+05:00")
    }

    @Test
    fun `a zone on a TEXT field, or a name that is no zone, is a 400 on timeZone`() {
        listOf(
            "titulo" to "America/Lima",
            "plazo" to "America/Limaa",
            "plazo" to "GMT+5",
            "plazo" to "+05:00:30"
        ).forEach { (field, zone) ->
            updateField(field, mapOf("timeZone" to zone))
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.errors[0].field")
                .isEqualTo("timeZone")
        }
        listOf(
            mapOf("name" to "nota", "type" to "TEXT", "timeZone" to "America/Lima"),
            mapOf("name" to "cierre", "type" to "DATETIME", "timeZone" to "America/Limaa")
        ).forEach { field ->
            client
                .post()
                .uri("/api/metadata/objects/$objectName/fields")
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(field)
                .exchange()
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.errors[0].field")
                .isEqualTo("timeZone")
        }

        // nothing stored by the refusals
        definition()
            .jsonPath("$.fields[?(@.name == 'plazo')].timeZone")
            .doesNotExist()
            .jsonPath("$.fields[?(@.name == 'cierre')]")
            .doesNotExist()
    }

    @Test
    fun `the stored value is the instant sent, whatever the field's zone`() {
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("ocurrido" to "2026-10-10T15:00:00Z")))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.attributes.ocurrido")
            .value<String> { assertThat(Instant.parse(it)).isEqualTo(Instant.parse("2026-10-10T15:00:00Z")) }
    }

    private fun definition(): WebTestClient.BodyContentSpec =
        client
            .get()
            .uri("/api/objects/$objectName")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    private fun updateField(
        field: String,
        body: Map<String, Any?>
    ): WebTestClient.ResponseSpec =
        client
            .put()
            .uri("/api/metadata/objects/$objectName/fields/$field")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body)
            .exchange()
}
