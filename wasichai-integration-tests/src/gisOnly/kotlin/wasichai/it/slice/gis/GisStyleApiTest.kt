package wasichai.it.slice.gis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.it.support.SliceSmokeTest

// style classes come from the tenant's rows; map views keep the style. geoserver stays out (ADR-031 D51, ADR-072)
@TestPropertySource(properties = ["wasichai.gis.geoserver.enabled=false", "wasichai.gis.geoserver.url=http://geoserver.invalid:8081/geoserver"])
class GisStyleApiTest : SliceSmokeTest() {
    override val installed = setOf("gis")

    private val json = JsonMapper.builder().build()

    @Test
    fun `an enum is classed by its options, in their order, with no rows yet`() {
        val name = createTramo()
        val classes = styleClasses(name, mapOf("by" to "uso", "type" to "categories"))
        assertThat(classes.get("type").asString()).isEqualTo("categories")
        assertThat(items(classes).map { it.get("value").asString() }).containsExactly("vivienda", "comercio", "industria")
        assertThat(items(classes).map { it.get("color").asString() }).allMatch { it.matches(Regex("^#[0-9a-f]{6}$")) }
    }

    @Test
    fun `text is classed by its distinct values, a number by quantiles`() {
        val name = createTramo()
        listOf("A" to 10.0, "B" to 20.0, "A" to 30.0, "C" to 40.0, "B" to 50.0).forEach { (via, arancel) -> createRecord(name, via, arancel) }

        val categories = styleClasses(name, mapOf("by" to "via", "type" to "categories"))
        assertThat(items(categories).map { it.get("label").asString() }).containsExactly("A", "B", "C")

        val quantiles = styleClasses(name, mapOf("by" to "arancel", "type" to "quantiles", "classes" to 2, "palette" to listOf("#00ff00", "#ff0000")))
        val ranges = items(quantiles).map { it.get("min").asDouble() to it.get("max").asDouble() }
        assertThat(ranges).containsExactly(10.0 to 30.0, 30.0 to 50.0)
        assertThat(items(quantiles).map { it.get("color").asString() }).containsExactly("#00ff00", "#ff0000")
    }

    @Test
    fun `a style that does not fit its field is a 400 naming it`() {
        val name = createTramo()
        client
            .post()
            .uri("/api/gis/objects/$name/style-classes")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("by" to "uso", "type" to "quantiles"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("style.by")
    }

    @Test
    fun `publishing with a bad style is the caller's 400, before geoserver is asked`() {
        val name = createTramo()
        client
            .post()
            .uri("/api/gis/layers/$name/tramo")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("style" to mapOf("by" to "nope", "type" to "categories")))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a map view keeps its geometry and style, and is listed, changed and removed`() {
        val name = createTramo()
        val style = mapOf("by" to "arancel", "type" to "quantiles", "classes" to 4)
        client
            .post()
            .uri("/api/gis/objects/$name/map-views")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "aranceles", "label" to "Aranceles por vía", "style" to style))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.objectName")
            .isEqualTo(name)
            .jsonPath("$.geometryName")
            .isEqualTo("tramo")
            .jsonPath("$.style.type")
            .isEqualTo("quantiles")
            .jsonPath("$.style.classes")
            .isEqualTo(4)

        client
            .post()
            .uri("/api/gis/objects/$name/map-views")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "aranceles"))
            .exchange()
            .expectStatus()
            .isEqualTo(409)

        client
            .get()
            .uri("/api/gis/map-views")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[?(@.objectName == '$name')].name")
            .isEqualTo(listOf("aranceles"))

        client
            .put()
            .uri("/api/gis/objects/$name/map-views/aranceles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("label" to "Usos", "style" to mapOf("by" to "uso", "type" to "categories")))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.label")
            .isEqualTo("Usos")
            .jsonPath("$.style.by")
            .isEqualTo("uso")

        client
            .delete()
            .uri("/api/gis/objects/$name/map-views/aranceles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNoContent
        client
            .get()
            .uri("/api/gis/objects/$name/map-views/aranceles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `a map view on a geometry the object lacks is refused`() {
        val name = createTramo()
        client
            .post()
            .uri("/api/gis/objects/$name/map-views")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "x", "geometryName" to "fachada"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("geometryName")
    }

    @Test
    fun `a member without grants cannot write a map view`() {
        val name = createTramo()
        client
            .post()
            .uri("/api/gis/objects/$name/map-views")
            .header(HttpHeaders.AUTHORIZATION, memberWithoutGrants())
            .bodyValue(mapOf("name" to "x"))
            .exchange()
            .expectStatus()
            .isForbidden
    }

    private fun items(classes: JsonNode): List<JsonNode> = List(classes.get("classes").size()) { classes.get("classes").get(it) }

    private fun styleClasses(
        name: String,
        style: Map<String, Any>
    ) = json.readTree(
        client
            .post()
            .uri("/api/gis/objects/$name/style-classes")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(style)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
    )

    private fun createTramo(): String {
        val name = uniqueName("tramo")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Tramo",
                    "fields" to
                        listOf(
                            mapOf("name" to "via", "type" to "TEXT"),
                            mapOf("name" to "arancel", "type" to "DECIMAL"),
                            mapOf("name" to "uso", "type" to "ENUM", "enumOptions" to listOf("vivienda", "comercio", "industria")),
                            mapOf("name" to "tramo", "type" to "GEOMETRY", "geometryType" to "LINESTRING", "srid" to 4326)
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun createRecord(
        name: String,
        via: String,
        arancel: Double
    ) {
        client
            .post()
            .uri("/api/objects/$name/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("via" to via, "arancel" to arancel)))
            .exchange()
            .expectStatus()
            .isCreated
    }
}
