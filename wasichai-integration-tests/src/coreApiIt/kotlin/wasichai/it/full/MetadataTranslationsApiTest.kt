package wasichai.it.full

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

// issue 155: per-language texts of objects, fields, enum options and relationships (ADR-078)
class MetadataTranslationsApiTest : FullAppIntegrationTest() {
    private lateinit var admin: String
    private lateinit var leccion: String

    @BeforeEach
    fun createObject() {
        admin = bearer()
        leccion = uniqueName("leccion")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to leccion,
                    "label" to "Lección",
                    "pluralLabel" to "Lecciones",
                    "labels" to mapOf("en" to "Lesson", "pt-BR" to " "),
                    "pluralLabels" to mapOf("en" to "Lessons"),
                    "descriptions" to mapOf("en" to "A unit's lesson"),
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT", "label" to "Código", "labels" to mapOf("en" to "Code")),
                            mapOf(
                                "name" to "tipo",
                                "type" to "ENUM",
                                "label" to "Tipo",
                                "enumOptions" to listOf("LECTURA", "VIDEO"),
                                "enumOptionLabels" to mapOf("LECTURA" to mapOf("en" to "Reading"), "VIDEO" to mapOf("en" to "Video"))
                            )
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.labels.en")
            .isEqualTo("Lesson")
            // a blank text drops its language
            .jsonPath("$.labels['pt-BR']")
            .doesNotExist()
    }

    @Test
    fun `the definition hands out every map, and the single texts keep their meaning`() {
        definition()
            .jsonPath("$.label")
            .isEqualTo("Lección")
            .jsonPath("$.pluralLabel")
            .isEqualTo("Lecciones")
            .jsonPath("$.labels.en")
            .isEqualTo("Lesson")
            .jsonPath("$.pluralLabels.en")
            .isEqualTo("Lessons")
            .jsonPath("$.descriptions.en")
            .isEqualTo("A unit's lesson")
            .jsonPath("$.fields[?(@.name == 'codigo')].label")
            .isEqualTo("Código")
            .jsonPath("$.fields[?(@.name == 'codigo')].labels.en")
            .isEqualTo("Code")
            .jsonPath("$.fields[?(@.name == 'tipo')].enumOptions[0]")
            .isEqualTo("LECTURA")
            .jsonPath("$.fields[?(@.name == 'tipo')].enumOptionLabels.LECTURA.en")
            .isEqualTo("Reading")
            // nothing stored, nothing written: a client that ignores the maps reads what it read before
            .jsonPath("$.fields[?(@.name == 'tipo')].labels")
            .doesNotExist()
    }

    @Test
    fun `an object with none reads exactly as before`() {
        val plain = uniqueName("plano")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to plain, "label" to "Plano", "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated

        client
            .get()
            .uri("/api/metadata/objects/$plain")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.labels")
            .doesNotExist()
            .jsonPath("$.pluralLabels")
            .doesNotExist()
            .jsonPath("$.fields[0].labels")
            .doesNotExist()
    }

    @Test
    fun `an update without the maps keeps them, a map replaces them, and the same update twice changes nothing`() {
        updateObject(mapOf("label" to "Lección"))
        definition().jsonPath("$.labels.en").isEqualTo("Lesson")

        val replace = mapOf("label" to "Lección", "labels" to mapOf("en" to "Class", "pt" to "Lição"), "pluralLabels" to emptyMap<String, String>())
        updateObject(replace)
        updateObject(replace)
        definition()
            .jsonPath("$.labels.en")
            .isEqualTo("Class")
            .jsonPath("$.labels.pt")
            .isEqualTo("Lição")
            .jsonPath("$.pluralLabels")
            .doesNotExist()
            .jsonPath("$.descriptions.en")
            .isEqualTo("A unit's lesson")

        updateField("codigo", mapOf("required" to false))
        definition().jsonPath("$.fields[?(@.name == 'codigo')].labels.en").isEqualTo("Code")
        updateField("codigo", mapOf("labels" to mapOf("en" to "Key"), "descriptions" to mapOf("en" to "Unique key")))
        definition()
            .jsonPath("$.fields[?(@.name == 'codigo')].labels.en")
            .isEqualTo("Key")
            .jsonPath("$.fields[?(@.name == 'codigo')].descriptions.en")
            .isEqualTo("Unique key")
    }

    @Test
    fun `a key that is not a language tag is a 400 on its property, and changes nothing`() {
        client
            .put()
            .uri("/api/objects/$leccion")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("label" to "Lección", "pluralLabels" to mapOf("English" to "Lessons")))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("pluralLabels")

        client
            .put()
            .uri("/api/metadata/objects/$leccion/fields/tipo")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("enumOptionLabels" to mapOf("AUDIO" to mapOf("en" to "Audio"))))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("enumOptionLabels")

        definition().jsonPath("$.pluralLabels.en").isEqualTo("Lessons")
    }

    @Test
    fun `an option's stored value and its check do not depend on its texts`() {
        record(mapOf("codigo" to "L-1", "tipo" to "LECTURA")).expectStatus().isCreated
        // the text is for people: it is no value
        record(mapOf("codigo" to "L-2", "tipo" to "Reading")).expectStatus().isBadRequest
    }

    @Test
    fun `an option that goes takes its texts with it`() {
        updateField("tipo", mapOf("enumOptions" to listOf("LECTURA", "QUIZ")))

        definition()
            .jsonPath("$.fields[?(@.name == 'tipo')].enumOptionLabels.LECTURA.en")
            .isEqualTo("Reading")
            .jsonPath("$.fields[?(@.name == 'tipo')].enumOptionLabels.VIDEO")
            .doesNotExist()
    }

    @Test
    fun `a relationship hands out its maps, and each side the texts of its own label`() {
        val unidad = uniqueName("unidad")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to unidad,
                    "label" to "Unidad",
                    "pluralLabel" to "Unidades",
                    "pluralLabels" to mapOf("en" to "Units"),
                    "fields" to listOf(mapOf("name" to "titulo", "type" to "TEXT"))
                )
            ).exchange()
            .expectStatus()
            .isCreated
        val name = uniqueName("rel").take(30)
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Unidad",
                    "labels" to mapOf("en" to "Unit"),
                    "inverseLabel" to "Lecciones",
                    "inverseLabels" to mapOf("en" to "Lessons"),
                    "type" to "MANY_TO_ONE",
                    "source" to leccion,
                    "target" to unidad
                )
            ).exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.labels.en")
            .isEqualTo("Unit")
            .jsonPath("$.inverseLabels.en")
            .isEqualTo("Lessons")

        client
            .get()
            .uri("/api/objects/$leccion/relationships")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[0].labels.en")
            .isEqualTo("Unit")
            .jsonPath("$[0].objectLabels.en")
            .isEqualTo("Units")
        client
            .get()
            .uri("/api/objects/$unidad/relationships")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[0].labels.en")
            .isEqualTo("Lessons")

        // labels only, and a bad tag is the caller's 400
        client
            .put()
            .uri("/api/relationships/$name")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("labels" to mapOf("en" to "Course unit")))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.labels.en")
            .isEqualTo("Course unit")
            .jsonPath("$.inverseLabels.en")
            .isEqualTo("Lessons")
        client
            .put()
            .uri("/api/relationships/$name")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("inverseLabels" to mapOf("EN" to "x")))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("inverseLabels")
    }

    @Test
    fun `a field the caller may not read takes its texts with it`() {
        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to role, "label" to "Blind", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to listOf(mapOf("objectName" to null, "action" to "READ", "allowed" to true))))
            .exchange()
            .expectStatus()
            .isOk
        client
            .put()
            .uri("/api/roles/$role/field-permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("fields" to listOf(mapOf("objectName" to leccion, "fieldName" to "tipo", "read" to false, "write" to false))))
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

        val body =
            client
                .get()
                .uri("/api/metadata/objects/$leccion")
                .header(HttpHeaders.AUTHORIZATION, bearer(email, "supersecret"))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        assertThat(body)
            .contains("Code")
            .doesNotContain("Reading")
    }

    private fun definition() =
        client
            .get()
            .uri("/api/metadata/objects/$leccion")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    private fun updateObject(body: Map<String, Any?>) {
        client
            .put()
            .uri("/api/objects/$leccion")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun updateField(
        field: String,
        body: Map<String, Any?>
    ) {
        client
            .put()
            .uri("/api/metadata/objects/$leccion/fields/$field")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun record(attributes: Map<String, Any?>) =
        client
            .post()
            .uri("/api/objects/$leccion/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()
}
