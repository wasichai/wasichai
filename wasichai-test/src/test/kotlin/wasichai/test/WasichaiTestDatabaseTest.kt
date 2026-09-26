package wasichai.test

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WasichaiTestDatabaseTest {
    @Test
    fun `refuses to wipe a database whose name does not end in _test`() {
        assertThatThrownBy { WasichaiTestDatabase.requireTestDatabaseName("wasichai") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("refusing to wipe 'wasichai'")
        assertThat(WasichaiTestDatabase.requireTestDatabaseName("wasichai_test")).isEqualTo("wasichai_test")
    }

    @Test
    fun `quotes identifiers it drops, doubling embedded quotes`() {
        assertThat(WasichaiTestDatabase.quoteIdentifier("app_data")).isEqualTo("\"app_data\"")
        assertThat(WasichaiTestDatabase.quoteIdentifier("we\"ird")).isEqualTo("\"we\"\"ird\"")
    }

    @Test
    fun `core tests run on plain postgres unless told otherwise`() {
        if (System.getProperty("wasichai.test.db.image") == null && System.getenv("WASICHAI_TEST_DB_IMAGE") == null) {
            assertThat(WasichaiTestDatabase.image).isEqualTo(WasichaiTestDatabase.DEFAULT_IMAGE)
        }
    }

    @Test
    fun `host set, port missing, refuses with a message naming the missing var`() {
        val env =
            mapOf(
                "WASICHAI_TEST_DB_HOST" to "db.internal",
                "WASICHAI_TEST_DB_NAME" to "wasichai_test",
                "WASICHAI_TEST_DB_USERNAME" to "wasichai",
                "WASICHAI_TEST_DB_PASSWORD" to "wasichai"
            )
        assertThatThrownBy { resolveExternalDatabaseConfig { env[it] } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("WASICHAI_TEST_DB_PORT")
    }

    @Test
    fun `all five vars set, properties resolve to them`() {
        val env =
            mapOf(
                "WASICHAI_TEST_DB_HOST" to "db.internal",
                "WASICHAI_TEST_DB_PORT" to "5555",
                "WASICHAI_TEST_DB_NAME" to "wasichai_test",
                "WASICHAI_TEST_DB_USERNAME" to "wasichai",
                "WASICHAI_TEST_DB_PASSWORD" to "secret"
            )
        assertThat(resolveExternalDatabaseConfig { env[it] })
            .isEqualTo(
                mapOf(
                    "WASICHAI_TEST_DB_HOST" to "db.internal",
                    "WASICHAI_TEST_DB_PORT" to "5555",
                    "WASICHAI_TEST_DB_NAME" to "wasichai_test",
                    "WASICHAI_TEST_DB_USERNAME" to "wasichai",
                    "WASICHAI_TEST_DB_PASSWORD" to "secret"
                )
            )
    }
}
