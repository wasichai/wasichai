package wasichai.it.slice.core

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.web.reactive.server.WebTestClient
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.identity.JwtService
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// issue 60 (ADR-031 D40): a field's defaultValue fills what a create leaves out, and is checked when it is set
class FieldDefaultValueApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    @Autowired
    private lateinit var db: DatabaseClient

    private lateinit var admin: String
    private lateinit var objectName: String

    @BeforeEach
    fun createObject() {
        admin = bearer()
        objectName = uniqueName("expediente")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to objectName,
                    "label" to "Expediente",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "cantidad", "type" to "INTEGER", "defaultValue" to "5"),
                            mapOf("name" to "estado", "type" to "TEXT", "required" to true, "defaultValue" to "NUEVO"),
                            mapOf("name" to "activo", "type" to "BOOLEAN", "defaultValue" to "true"),
                            mapOf("name" to "moneda", "type" to "ENUM", "enumOptions" to listOf("PEN", "USD"), "defaultValue" to "PEN"),
                            // a blank default is none
                            mapOf("name" to "nota", "type" to "TEXT", "defaultValue" to "")
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.fields[?(@.name == 'cantidad')].defaultValue")
            .isEqualTo(listOf("5"))
            .jsonPath("$.fields[?(@.name == 'nota')].defaultValue")
            .isEqualTo(listOf(null))
    }

    @Test
    fun `a create without the fields stores their defaults`() {
        create(admin, mapOf("codigo" to "E-1"))
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.attributes.cantidad")
            .isEqualTo(5)
            .jsonPath("$.attributes.estado")
            .isEqualTo("NUEVO")
            .jsonPath("$.attributes.activo")
            .isEqualTo(true)
            .jsonPath("$.attributes.moneda")
            .isEqualTo("PEN")
            .jsonPath("$.attributes.nota")
            .doesNotExist()
    }

    @Test
    fun `a value sent is stored instead of the default`() {
        create(admin, mapOf("cantidad" to 9, "estado" to "ABIERTO", "activo" to false, "moneda" to "USD"))
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.attributes.cantidad")
            .isEqualTo(9)
            .jsonPath("$.attributes.estado")
            .isEqualTo("ABIERTO")
            .jsonPath("$.attributes.activo")
            .isEqualTo(false)
            .jsonPath("$.attributes.moneda")
            .isEqualTo("USD")
    }

    @Test
    fun `an explicit null stores NULL, or is a 400 when the field is required`() {
        create(admin, mapOf("cantidad" to null))
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.attributes.cantidad")
            .doesNotExist()

        create(admin, mapOf("estado" to null))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("estado")
    }

    @Test
    fun `a required field with a default is created by a caller who may not write it`() {
        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to role, "label" to "Mesa de partes", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to listOf("READ", "CREATE").map { mapOf("objectName" to objectName, "action" to it) }))
            .exchange()
            .expectStatus()
            .isOk
        client
            .put()
            .uri("/api/roles/$role/field-permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("fields" to listOf(mapOf("objectName" to objectName, "fieldName" to "estado", "read" to true, "write" to false))))
            .exchange()
            .expectStatus()
            .isOk
        val email = "${uniqueName("member")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Member", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        val member = bearer(email, "supersecret")

        val id =
            idOf(
                create(member, mapOf("codigo" to "E-2"))
                    .expectStatus()
                    .isCreated
            )
        attributesOf(id).jsonPath("$.attributes.estado").isEqualTo("NUEVO")

        // what they send is still theirs to be refused
        create(member, mapOf("codigo" to "E-3", "estado" to "ABIERTO"))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("estado")

        // the audit row's after holds the default, as stored
        val after =
            runBlocking {
                db
                    .sql("SELECT after_state ->> 'estado' AS estado FROM wasichai.audit_log WHERE record_id = :id AND operation = 'CREATE'")
                    .bind("id", UUID.fromString(id))
                    .map { row, _ -> row.get("estado", String::class.java)!! }
                    .one()
                    .awaitSingle()
            }
        assertThat(after).isEqualTo("NUEVO")
    }

    @Test
    fun `the platform gets the defaults too`() {
        val organizationId =
            runBlocking { UUID.fromString(decoder.decode(admin.removePrefix("Bearer ")).awaitSingle().getClaimAsString(JwtService.CLAIM_ORGANIZATION)) }

        val created = runBlocking { records.asPlatform(organizationId) { records.create(objectName, RecordRequest(mapOf("codigo" to "P-1"))) } }

        assertThat(created.attributes).containsEntry("cantidad", 5L).containsEntry("estado", "NUEVO").containsEntry("activo", true)
    }

    @Test
    fun `an update applies no default`() {
        val id =
            idOf(
                create(admin, mapOf("codigo" to "E-4", "cantidad" to 7))
                    .expectStatus()
                    .isCreated
            )

        // PUT replaces: a field left out is cleared, as it always was
        client
            .put()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "E-4", "estado" to "ABIERTO")))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.attributes.cantidad")
            .doesNotExist()
            .jsonPath("$.attributes.moneda")
            .doesNotExist()
    }

    @Test
    fun `a default the type cannot parse is a 400 on defaultValue`() {
        addField(mapOf("name" to "piso", "type" to "INTEGER", "defaultValue" to "abc"))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("defaultValue")
        addField(mapOf("name" to "via", "type" to "ENUM", "enumOptions" to listOf("A", "B"), "defaultValue" to "C"))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("defaultValue")
        updateField("cantidad", mapOf("defaultValue" to "abc"))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("defaultValue")
        // new options must keep the default one of them
        updateField("moneda", mapOf("enumOptions" to listOf("USD", "EUR")))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("enumOptions")
    }

    @Test
    fun `records created before the default existed are not changed`() {
        addField(mapOf("name" to "piso", "type" to "INTEGER"))
            .expectStatus()
            .isCreated
        val before =
            idOf(
                create(admin, mapOf("codigo" to "E-5"))
                    .expectStatus()
                    .isCreated
            )

        updateField("piso", mapOf("defaultValue" to "3"))
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.defaultValue")
            .isEqualTo("3")

        attributesOf(before).jsonPath("$.attributes.piso").doesNotExist()
        create(admin, mapOf("codigo" to "E-6"))
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.attributes.piso")
            .isEqualTo(3)

        // blank clears it
        updateField("piso", mapOf("defaultValue" to ""))
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.defaultValue")
            .doesNotExist()
        create(admin, mapOf("codigo" to "E-7"))
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.attributes.piso")
            .doesNotExist()
    }

    private fun create(
        token: String,
        attributes: Map<String, Any?>
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()

    private fun addField(body: Map<String, Any?>): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/metadata/objects/$objectName/fields")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(body)
            .exchange()

    private fun updateField(
        field: String,
        body: Map<String, Any?>
    ): WebTestClient.ResponseSpec =
        client
            .put()
            .uri("/api/metadata/objects/$objectName/fields/$field")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(body)
            .exchange()

    private fun attributesOf(id: String) =
        client
            .get()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    private fun idOf(response: WebTestClient.ResponseSpec): String =
        response
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .substringAfter("\"id\":\"")
            .substringBefore("\"")
}
