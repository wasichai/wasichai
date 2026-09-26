package wasichai.core.platform

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WasichaiSchemasTest {
    @Test
    fun `defaults are wasichai and app_data`() {
        val schemas = WasichaiSchemas.of(WasichaiDatabaseProperties())
        assertThat(schemas.metadata).isEqualTo("wasichai")
        assertThat(schemas.data).isEqualTo("app_data")
        assertThat(schemas.dataTable("predio__1234abcd")).isEqualTo("\"app_data\".\"predio__1234abcd\"")
    }

    @Test
    fun `refuses a schema name that is not a plain identifier`() {
        listOf("App", "app-data", "x;drop schema y", "", "1abc").forEach { name ->
            assertThatThrownBy { WasichaiSchemas(name, "app_data") }.describedAs(name).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `refuses one schema for both metadata and data`() {
        assertThatThrownBy { WasichaiSchemas("same", "same") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
