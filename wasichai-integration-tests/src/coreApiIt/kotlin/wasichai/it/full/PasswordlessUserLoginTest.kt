package wasichai.it.full

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import java.util.UUID

class PasswordlessUserLoginTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Test
    fun `a user without a password cannot sign in locally, and gets the same answer as a wrong password`() {
        val email = "${uniqueName("nopass")}@wasichai.local"
        runBlocking {
            db
                .sql(
                    "INSERT INTO wasichai.users (id, organization_id, email, password_hash, display_name, external_subject) " +
                        "SELECT :id, id, :email, NULL, 'No Password', :subject FROM wasichai.organizations WHERE slug = 'demo'"
                ).bind("id", UUID.randomUUID())
                .bind("email", email)
                .bind("subject", UUID.randomUUID().toString())
                .fetch()
                .rowsUpdated()
                .awaitFirstOrNull()
        }

        val wrong = login(ADMIN_EMAIL, "not-the-password")
        val passwordless = login(email, "anything")

        assertThat(passwordless).isEqualTo(wrong).contains("Invalid email or password")
    }

    private fun login(
        email: String,
        password: String
    ): String =
        client
            .post()
            .uri("/api/auth/login")
            .bodyValue(mapOf("email" to email, "password" to password))
            .exchange()
            .expectStatus()
            .isUnauthorized
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
}
