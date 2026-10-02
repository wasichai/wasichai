package wasichai.core.data

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException

class RecordQueryParserTest {
    // a module parameter, like gis's bbox: reserved, and turned into a criterion only when sent
    private val near =
        object : RecordQueryContributor {
            override val parameters = setOf("near")

            override fun parse(params: Map<String, String>): RecordCriterion? {
                val raw = params["near"] ?: return null
                val value = raw.toIntOrNull() ?: throw ValidationException("Invalid near", "near", "must be a number")
                return RecordCriterion { _, bind -> "distance < ${bind(value)}" }
            }
        }

    @Test
    fun `core parses paging, sort, search and treats the rest as field filters`() {
        val query =
            RecordQueryParser(emptyList()).parse(
                mapOf(
                    "page" to "2",
                    "size" to "10",
                    "sort" to "codigo",
                    "dir" to "DESC",
                    "q" to "x",
                    "limit" to "5",
                    "uso" to "COMERCIAL"
                )
            )

        assertThat(query.page.page).isEqualTo(2)
        assertThat(query.page.size).isEqualTo(10)
        assertThat(query.descending).isTrue()
        assertThat(query.search).isEqualTo("x")
        assertThat(query.filters).containsExactly(
            org.assertj.core.api.Assertions
                .entry("uso", "COMERCIAL")
        )
        assertThat(query.criteria).isEmpty()
    }

    @Test
    fun `without the module that owns it, bbox is just an unknown field filter`() {
        assertThat(RecordQueryParser(emptyList()).parse(mapOf("bbox" to "1,2,3,4")).filters).containsKey("bbox")
    }

    @Test
    fun `a contributor owns its parameters and turns them into a criterion`() {
        val parser = RecordQueryParser(listOf(near))

        val sent = parser.parse(mapOf("near" to "7", "uso" to "A"))
        assertThat(sent.filters).containsOnlyKeys("uso")
        val bound = mutableListOf<Any>()
        assertThat(
            sent.criteria.single().condition(ObjectDefinitionFixtures.empty()) {
                bound += it
                ":c0"
            }
        ).isEqualTo("distance < :c0")
        assertThat(bound).containsExactly(7)

        assertThat(parser.parse(mapOf("uso" to "A")).criteria).isEmpty()
        assertThatThrownBy { parser.parse(mapOf("near" to "lejos")) }.isInstanceOf(ValidationException::class.java)
    }

    // issue 21: count and after are core's, never field filters
    @Test
    fun `count defaults on, can be switched off, and after is passed through as sent`() {
        val parser = RecordQueryParser(emptyList())

        assertThat(parser.parse(emptyMap()).count).isTrue()
        assertThat(parser.parse(emptyMap()).after).isNull()
        val query = parser.parse(mapOf("count" to "FALSE", "after" to "abc"))
        assertThat(query.count).isFalse()
        assertThat(query.after).isEqualTo("abc")
        assertThat(query.filters).isEmpty()
        assertThat(parser.parse(mapOf("count" to "true")).count).isTrue()
        assertThatThrownBy { parser.parse(mapOf("count" to "nah")) }
            .isInstanceOf(ValidationException::class.java)
            .extracting { (it as ValidationException).violations.single().field }
            .isEqualTo("count")
    }
}
