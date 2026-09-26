package wasichai.gis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import wasichai.core.metadata.ObjectDefinition
import wasichai.gis.GisFixtures.definition
import wasichai.gis.GisFixtures.geometry
import wasichai.gis.GisFixtures.refused
import wasichai.gis.GisFixtures.text

class BboxQueryTest {
    private val query = BboxQuery()
    private val spatial = definition(text("codigo"), geometry("lote", "POLYGON", 32718), geometry("acceso", "POINT", 4326))

    // what core's RecordQueryParser hands a criterion: every value becomes a named placeholder
    private fun sqlOf(
        params: Map<String, String>,
        on: ObjectDefinition = spatial
    ): Pair<String, List<Any>> {
        val bound = mutableListOf<Any>()
        val sql =
            query.parse(params)!!.condition(on) { value ->
                bound += value
                ":c${bound.size - 1}"
            }
        return sql to bound
    }

    @Test
    fun `bbox and geometry are ours, never field filters`() {
        assertThat(query.parameters).containsExactlyInAnyOrder("bbox", "geometry")
        assertThat(query.parse(mapOf("geometry" to "lote"))).isNull()
        assertThat(query.parse(emptyMap())).isNull()
    }

    @Test
    fun `a bbox intersects the first geometry, in its own crs, with every number bound`() {
        val (sql, bound) = sqlOf(mapOf("bbox" to "-77.1, -12.2, -77.0, -12.0"))
        assertThat(sql).isEqualTo("ST_Intersects(\"lote\", ST_Transform(ST_MakeEnvelope(:c0, :c1, :c2, :c3, 4326), 32718))")
        assertThat(bound).containsExactly(-77.1, -12.2, -77.0, -12.0)
    }

    @Test
    fun `geometry names which one`() {
        val (sql, _) = sqlOf(mapOf("bbox" to "1,2,3,4", "geometry" to " acceso "))
        assertThat(sql).startsWith("ST_Intersects(\"acceso\",").endsWith(", 4326))")
    }

    @Test
    fun `a broken bbox is refused before any object is read`() {
        refused("Invalid bbox", "bbox") { query.parse(mapOf("bbox" to "1,2,3")) }
        refused("Invalid bbox", "bbox") { query.parse(mapOf("bbox" to "a,b,c,d")) }
    }

    // it used to be dropped in silence on a flat object, which answered 200 to a question nobody answered
    @Test
    fun `a bbox on a flat object or a geometry it does not have is a 400`() {
        refused("Object 'predio' has no geometry", "bbox") { sqlOf(mapOf("bbox" to "1,2,3,4"), definition(text("codigo"))) }
        refused("Unknown geometry 'x'", "geometry") { sqlOf(mapOf("bbox" to "1,2,3,4", "geometry" to "x")) }
    }
}
