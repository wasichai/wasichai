package wasichai.it.full

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient

class MetadataApiTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Test
    fun `creates an object with fields, a physical table and a spatial index`() {
        val name = uniqueName("predio")
        val token = bearer()

        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Predio",
                    "pluralLabel" to "Predios",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT", "required" to true, "unique" to true),
                            mapOf("name" to "area", "type" to "DECIMAL"),
                            mapOf(
                                "name" to "uso",
                                "type" to "ENUM",
                                "enumOptions" to listOf("RESIDENCIAL", "COMERCIAL")
                            ),
                            mapOf("name" to "lote", "type" to "GEOMETRY", "geometryType" to "POLYGON", "srid" to 32718)
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.name")
            .isEqualTo(name)
            .jsonPath("$.geometry.type")
            .isEqualTo("POLYGON")
            .jsonPath("$.geometry.srid")
            .isEqualTo(32718)
            .jsonPath("$.fields.length()")
            .isEqualTo(4)
            .jsonPath("$.fields[3].geometry.type")
            .isEqualTo("POLYGON")

        val table = physicalTable(name)

        assertThat(columnType(table, "codigo")).isEqualTo("text")
        assertThat(columnType(table, "area")).isEqualTo("numeric")
        assertThat(columnType(table, "organization_id")).isEqualTo("uuid")

        assertThat(geometrySrid(table, "lote")).isEqualTo(32718)
        assertThat(geometryType(table, "lote")).isEqualTo("POLYGON")
        assertThat(indexNames(table)).contains("${table}_lote_gix", "${table}_org_idx")
    }

    // the point of the whole change: one table, two typed geometry columns, two spatial indexes
    @Test
    fun `an object can carry more than one geometry`() {
        val name = uniqueName("predio")
        val token = bearer()

        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Predio",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "lote", "type" to "GEOMETRY", "geometryType" to "POLYGON", "srid" to 32718),
                            mapOf("name" to "acceso", "type" to "GEOMETRY", "geometryType" to "POINT", "srid" to 32718)
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated

        val table = physicalTable(name)
        assertThat(geometryType(table, "lote")).isEqualTo("POLYGON")
        assertThat(geometryType(table, "acceso")).isEqualTo("POINT")
        assertThat(indexNames(table)).contains("${table}_lote_gix", "${table}_acceso_gix")
    }

    // `geometry` is the first one, kept so callers that only ask "is this spatial" still work
    @Test
    fun `the object reports its first geometry as the object geometry`() {
        val name = uniqueName("red")
        val token = bearer()

        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Red",
                    "fields" to
                        listOf(
                            mapOf("name" to "traza", "type" to "GEOMETRY", "geometryType" to "LINESTRING", "srid" to 4326),
                            mapOf("name" to "nodo", "type" to "GEOMETRY", "geometryType" to "POINT", "srid" to 4326)
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.geometry.type")
            .isEqualTo("LINESTRING")
    }

    @Test
    fun `a geometry field cannot be unique`() {
        val name = uniqueName("predio")

        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Predio",
                    "fields" to
                        listOf(
                            mapOf("name" to "lote", "type" to "GEOMETRY", "geometryType" to "POLYGON", "unique" to true)
                        )
                )
            ).exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("unique")
    }

    @Test
    fun `rejects a reserved SQL keyword as an object name`() {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .bodyValue(mapOf("name" to "select", "label" to "Bad"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectHeader()
            .contentTypeCompatibleWith("application/problem+json")
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("name")
    }

    @Test
    fun `rejects a duplicate object name`() {
        val name = uniqueName("via")
        val token = bearer()
        val body = mapOf("name" to name, "label" to "Via")

        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isCreated

        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isEqualTo(409)
    }

    @Test
    fun `adds a field to an existing object and to its table`() {
        val name = uniqueName("lote")
        val token = bearer()

        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to name, "label" to "Lote"))
            .exchange()
            .expectStatus()
            .isCreated

        client
            .post()
            .uri("/api/metadata/objects/$name/fields")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to "superficie", "type" to "DECIMAL"))
            .exchange()
            .expectStatus()
            .isCreated

        assertThat(columnType(physicalTable(name), "superficie")).isEqualTo("numeric")
    }

    @Test
    fun `requires authentication`() {
        client
            .get()
            .uri("/api/objects")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    private fun physicalTable(name: String): String =
        runBlocking {
            db
                .sql("SELECT physical_table FROM wasichai.custom_objects WHERE name = :name")
                .bind("name", name)
                .map { row, _ -> row.get("physical_table", String::class.java)!! }
                .one()
                .awaitFirstOrNull()!!
        }

    private fun columnType(
        table: String,
        column: String
    ): String? =
        runBlocking {
            db
                .sql(
                    """
                    SELECT data_type FROM information_schema.columns
                    WHERE table_schema = 'app_data' AND table_name = :table AND column_name = :column
                    """.trimIndent()
                ).bind("table", table)
                .bind("column", column)
                .map { row, _ -> row.get("data_type", String::class.java)!! }
                .one()
                .awaitFirstOrNull()
        }

    private fun geometrySrid(
        table: String,
        column: String
    ): Int? =
        runBlocking {
            db
                .sql("SELECT srid FROM geometry_columns WHERE f_table_name = :table AND f_geometry_column = :column")
                .bind("table", table)
                .bind("column", column)
                .map { row, _ -> (row.get("srid") as Number).toInt() }
                .one()
                .awaitFirstOrNull()
        }

    private fun geometryType(
        table: String,
        column: String
    ): String? =
        runBlocking {
            db
                .sql("SELECT type FROM geometry_columns WHERE f_table_name = :table AND f_geometry_column = :column")
                .bind("table", table)
                .bind("column", column)
                .map { row, _ -> row.get("type", String::class.java)!! }
                .one()
                .awaitFirstOrNull()
        }

    private fun indexNames(table: String): List<String> =
        runBlocking {
            db
                .sql("SELECT indexname FROM pg_indexes WHERE schemaname = 'app_data' AND tablename = :table")
                .bind("table", table)
                .map { row, _ -> row.get("indexname", String::class.java)!! }
                .all()
                .asFlow()
                .toList()
        }
}
