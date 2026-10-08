package wasichai.core.identity

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import wasichai.core.platform.WasichaiPasswordProperties
import java.util.UUID

// issue 55 (ADR-059): the default is the 8 characters there always were; the rest is opt in
class PasswordPolicyTest {
    private val user = UserInfo("ana@example.com", "Ana", UUID.randomUUID())

    @Test
    fun `the default asks for 8 characters and nothing else`() {
        val policy = ConfiguredPasswordPolicy(WasichaiPasswordProperties())
        assertThat(policy.check("short", user)).containsExactly("must be at least 8 characters")
        assertThat(policy.check("ana@example.com", user)).isEmpty()
        assertThat(policy.check("alllowercase", user)).isEmpty()
    }

    @Test
    fun `every configured rule names itself when broken`() {
        val policy =
            ConfiguredPasswordPolicy(
                WasichaiPasswordProperties(
                    minLength = 12,
                    requireUppercase = true,
                    requireLowercase = true,
                    requireDigit = true,
                    requireSymbol = true,
                    notEqualEmail = true
                )
            )
        assertThat(policy.check("abc", user)).containsExactly(
            "must be at least 12 characters",
            "must contain an uppercase letter",
            "must contain a digit",
            "must contain a symbol"
        )
        assertThat(policy.check("ABC", user)).contains("must contain a lowercase letter")
        assertThat(policy.check(" ANA@example.com ", user)).contains("must not be the email address")
        assertThat(policy.check("Correct-Horse-9", user)).isEmpty()
    }

    @Test
    fun `enforce answers 400 on the field, one violation per rule, and keeps the old detail for length alone`() {
        val default = ConfiguredPasswordPolicy(WasichaiPasswordProperties())
        assertThatThrownBy { PasswordPolicy.enforce(default, "short", user, "password") }
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(it.status).isEqualTo(HttpStatus.BAD_REQUEST)
                assertThat(it.message).isEqualTo("Password too short")
                assertThat(it.violations).containsExactly(FieldViolation("password", "must be at least 8 characters"))
            }
        val strict = ConfiguredPasswordPolicy(WasichaiPasswordProperties(requireDigit = true, requireSymbol = true))
        assertThatThrownBy { PasswordPolicy.enforce(strict, "longenough", user, "adminPassword") }
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(it.message).isEqualTo("Password does not meet the password policy")
                assertThat(it.violations).containsExactly(
                    FieldViolation("adminPassword", "must contain a digit"),
                    FieldViolation("adminPassword", "must contain a symbol")
                )
            }
        PasswordPolicy.enforce(strict, "longenough-1", user, "password")
    }
}
