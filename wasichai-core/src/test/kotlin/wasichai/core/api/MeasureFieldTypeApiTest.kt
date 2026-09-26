package wasichai.core.api

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import wasichai.core.fixtures.MeasureTestConfiguration
import wasichai.test.WasichaiIntegrationTest

// a module field type, end to end, the way wasichai-gis will plug GEOMETRY in. own schemas so the
// extra column and check never reach the other contexts sharing this database.
@Import(MeasureTestConfiguration::class)
@TestPropertySource(properties = ["wasichai.database.metadata-schema=measure_meta", "wasichai.database.data-schema=measure_data"])
class MeasureFieldTypeApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    private lateinit var admin: String
    private lateinit var objectName: String

    @BeforeEach
    fun createObject() {
        admin = bearer()
        objectName = uniqueName("lote")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to objectName,
                    "label" to "Lote",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "area", "type" to "MEASURE", "unit" to "m2")
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.measure.unit")
            .isEqualTo("m2")
            .jsonPath("$.fields[0].measure")
            .isEmpty()
            .jsonPath("$.fields[1].type")
            .isEqualTo("MEASURE")
            .jsonPath("$.fields[1].measure.unit")
            .isEqualTo("m2")
    }

    private fun createRecord(
        codigo: String,
        measures: Map<String, Any?>? = null,
        token: String = admin
    ): String {
        val body = mutableMapOf<String, Any>("attributes" to mapOf("codigo" to codigo))
        if (measures != null) body["measures"] = measures
        val raw =
            client
                .post()
                .uri("/api/objects/$objectName/records")
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(raw)!!.groupValues[1]
    }

    private fun scalar(sql: String): Any? =
        runBlocking {
            db
                .sql(sql)
                .bind("name", objectName)
                .map { row, _ -> row.get(0)!! }
                .one()
                .awaitFirstOrNull()
        }

    private fun count(sql: String): Long = (scalar(sql) as Number).toLong()

    // area2 through the add-field path, not object creation: the other door ddl comes through
    private fun addArea2() {
        client
            .post()
            .uri("/api/metadata/objects/$objectName/fields")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "area2", "type" to "MEASURE", "unit" to "ha"))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.type")
            .isEqualTo("MEASURE")
            .jsonPath("$.measure.unit")
            .isEqualTo("ha")
    }

    private fun area2Unit(): Any? =
        scalar(
            "SELECT f.unit FROM measure_meta.custom_fields f JOIN measure_meta.custom_objects o ON o.id = f.object_id " +
                "WHERE o.name = :name AND f.name = 'area2'"
        )

    private fun area2Indexes(): Long =
        count(
            "SELECT count(*) FROM pg_indexes i JOIN measure_meta.custom_objects o ON i.tablename = o.physical_table " +
                "WHERE o.name = :name AND i.schemaname = 'measure_data' AND i.indexname LIKE '%_area2_mix'"
        )

    private fun area2Columns(): Long =
        count(
            "SELECT count(*) FROM information_schema.columns c JOIN measure_meta.custom_objects o ON c.table_name = o.physical_table " +
                "WHERE o.name = :name AND c.table_schema = 'measure_data' AND c.column_name = 'area2' AND c.data_type = 'numeric'"
        )

    @Test
    fun `a measure added to an existing object gets its attribute, column and index`() {
        addArea2()

        assertThat(area2Unit()).isEqualTo("ha")
        assertThat(area2Indexes()).isEqualTo(1)
        assertThat(area2Columns()).isEqualTo(1)
    }

    @Test
    fun `dropping a measure drops its column and index`() {
        addArea2()
        client
            .delete()
            .uri("/api/metadata/objects/$objectName/fields/area2")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNoContent

        assertThat(area2Unit()).isNull()
        assertThat(area2Indexes()).isEqualTo(0)
        assertThat(
            count(
                "SELECT count(*) FROM information_schema.columns c JOIN measure_meta.custom_objects o ON c.table_name = o.physical_table " +
                    "WHERE o.name = :name AND c.table_schema = 'measure_data' AND c.column_name = 'area2'"
            )
        ).isEqualTo(0)
    }

    @Test
    fun `the object reports its first measure`() {
        addArea2()
        client
            .get()
            .uri("/api/objects/$objectName")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.measure.unit")
            .isEqualTo("m2")
    }

    @Test
    fun `the attribute lands in the module's column and the index in the data schema`() {
        assertThat(
            scalar(
                "SELECT f.unit FROM measure_meta.custom_fields f JOIN measure_meta.custom_objects o ON o.id = f.object_id " +
                    "WHERE o.name = :name AND f.name = 'area'"
            )
        ).isEqualTo("m2")
        assertThat(
            scalar(
                "SELECT count(*) FROM pg_indexes i JOIN measure_meta.custom_objects o ON i.tablename = o.physical_table " +
                    "WHERE o.name = :name AND i.schemaname = 'measure_data' AND i.indexname LIKE '%_area_mix'"
            ).toString()
        ).isEqualTo("1")
    }

    @Test
    fun `the type's own rules refuse what it does not support`() {
        client
            .post()
            .uri("/api/metadata/objects/$objectName/fields")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "alto", "type" to "MEASURE"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("unit")

        client
            .post()
            .uri("/api/metadata/objects/$objectName/fields")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "alto", "type" to "MEASURE", "unit" to "m", "unique" to true))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("unique")

        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "A-9"), "measures" to mapOf("area" to "big")))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("area")
            .jsonPath("$.errors[0].message")
            .isEqualTo("must be a number")

        client
            .put()
            .uri("/api/metadata/objects/$objectName/fields/area")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("unique" to true))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("unique")
    }

    @Test
    fun `a section is written when sent, left alone when not, cleared by null`() {
        val id = createRecord("A-1", mapOf("area" to 12.5))
        val record = "/api/objects/$objectName/records/$id"

        client
            .get()
            .uri(record)
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectBody()
            .jsonPath("$.measures.area")
            .isEqualTo(12.5)

        client
            .put()
            .uri(record)
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "A-1b")))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.measures.area")
            .isEqualTo(12.5)

        client
            .put()
            .uri(record)
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "A-1b"), "measures" to mapOf("area" to null)))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.measures.area")
            .isEmpty()

        // stored, not just echoed
        client
            .get()
            .uri(record)
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.attributes.codigo")
            .isEqualTo("A-1b")
            .jsonPath("$.measures")
            .exists()
            .jsonPath("$.measures.area")
            .isEmpty()
    }

    @Test
    fun `a section sent with one key leaves its sibling alone`() {
        addArea2()
        val id = createRecord("A-5", mapOf("area" to 1, "area2" to 2))
        val record = "/api/objects/$objectName/records/$id"

        client
            .put()
            .uri(record)
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "A-5"), "measures" to mapOf("area" to 7)))
            .exchange()
            .expectStatus()
            .isOk

        client
            .get()
            .uri(record)
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.measures.area")
            .isEqualTo(7)
            .jsonPath("$.measures.area2")
            .isEqualTo(2)
    }

    @Test
    fun `a record with no measure still lists the section, and a flat object gets an empty one`() {
        val id = createRecord("A-2")
        client
            .get()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectBody()
            .jsonPath("$.measures")
            .exists()
            .jsonPath("$.measures.area")
            .isEmpty()

        val flat = uniqueName("flat")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to flat, "label" to "Flat", "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .post()
            .uri("/api/objects/$flat/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "F-1")))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.measures")
            .isEmpty()
    }

    @Test
    fun `a section key naming no such field is refused in the module's words`() {
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "A-3"), "measures" to mapOf("codigo" to 1)))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Unknown measure 'codigo'")
    }

    @Test
    fun `the module's query parameter filters, and its type cannot be sorted on`() {
        createRecord("small", mapOf("area" to 5))
        createRecord("big", mapOf("area" to 20))

        client
            .get()
            .uri("/api/objects/$objectName/records?min_measure=10")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)
            .jsonPath("$.content[0].attributes.codigo")
            .isEqualTo("big")

        client
            .get()
            .uri("/api/objects/$objectName/records?sort=area")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("area")
        client
            .get()
            .uri("/api/objects/$objectName/records?area=5")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("area")
        client
            .get()
            .uri("/api/objects/$objectName/records?min_measure=lots")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("min_measure")
    }

    @Test
    fun `the module's query parameter on an object with no measure is refused`() {
        val flat = uniqueName("flat")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to flat, "label" to "Flat", "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .get()
            .uri("/api/objects/$flat/records?min_measure=1")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Object '$flat' has no measure")
            .jsonPath("$.errors[0].field")
            .isEqualTo("min_measure")
    }

    // a section field is a field: its permissions are the field's, through either door
    @Test
    fun `a locked section field can be neither written nor read back`() {
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
            .bodyValue(mapOf("permissions" to listOf("READ", "CREATE", "UPDATE").map { mapOf("objectName" to null, "action" to it, "allowed" to true) }))
            .exchange()
            .expectStatus()
            .isOk
        client
            .put()
            .uri("/api/roles/$role/field-permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("fields" to listOf(mapOf("objectName" to objectName, "fieldName" to "area", "read" to false, "write" to false))))
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

        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, member)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "L-1"), "measures" to mapOf("area" to 3)))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("area")

        createRecord("L-2", mapOf("area" to 3))
        client
            .get()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, member)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.content[0].attributes.codigo")
            .isEqualTo("L-2")
            .jsonPath("$.content[0].measures.area")
            .doesNotExist()
    }
}
