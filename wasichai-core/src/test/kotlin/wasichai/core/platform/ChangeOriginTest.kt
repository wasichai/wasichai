package wasichai.core.platform

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// ADR-050: the origin of a change rides in the Reactor context, set by code only
class ChangeOriginTest {
    @Test
    fun `nothing labels work outside a request or a block`() =
        runTest {
            assertThat(ChangeOrigin.source()).isNull()
            assertThat(ChangeOrigin.correlationId()).isNull()
        }

    @Test
    fun `a block labels its work and the label is gone after it`() =
        runTest {
            val inside = ChangeOrigin.within("job:retention", "run-7") { ChangeOrigin.source() to ChangeOrigin.correlationId() }

            assertThat(inside).isEqualTo("job:retention" to "run-7")
            assertThat(ChangeOrigin.source()).isNull()
            assertThat(ChangeOrigin.correlationId()).isNull()
        }

    @Test
    fun `the innermost label wins and an outer id stays when the inner block gives none`() =
        runTest {
            val inside =
                ChangeOrigin.within("automation:alerta", "req-1") {
                    ChangeOrigin.within("job:import") { ChangeOrigin.source() to ChangeOrigin.correlationId() }
                }

            assertThat(inside).isEqualTo("job:import" to "req-1")
        }

    @Test
    fun `a malformed label or id is refused before the block runs`() =
        runTest {
            var ran = false
            listOf("", "has space", "x".repeat(65), "a;b").forEach { bad ->
                val refused = runCatching { ChangeOrigin.within(bad) { ran = true } }
                assertThat(refused.exceptionOrNull()).describedAs(bad).isInstanceOf(IllegalArgumentException::class.java)
            }
            val badId = runCatching { ChangeOrigin.within("job", "a:b") { ran = true } }
            assertThat(badId.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)

            assertThat(ran).isFalse()
        }

    @Test
    fun `a rule is labelled automation and its name`() {
        assertThat(ChangeOrigin.automation("alerta_mora")).isEqualTo("automation:alerta_mora")
        // the longest name a rule may have today still fits the column
        assertThat(ChangeOrigin.automation("a" + "b".repeat(48))).hasSize(60)
        // a name from before the rule that cannot fit is still an automation, never a failed run
        assertThat(ChangeOrigin.automation("x".repeat(60))).isEqualTo("automation")
        assertThat(ChangeOrigin.automation("Con Espacio")).isEqualTo("automation")
    }

    @Test
    fun `a client id is kept only when well formed`() {
        assertThat(ChangeOrigin.acceptCorrelationId("abc-123_X.y")).isEqualTo("abc-123_X.y")
        assertThat(ChangeOrigin.acceptCorrelationId("a".repeat(64))).hasSize(64)
        assertThat(ChangeOrigin.acceptCorrelationId("a".repeat(65))).isNull()
        assertThat(ChangeOrigin.acceptCorrelationId("a:b")).isNull()
        assertThat(ChangeOrigin.acceptCorrelationId("line\n")).isNull()
        assertThat(ChangeOrigin.acceptCorrelationId("")).isNull()
        assertThat(ChangeOrigin.acceptCorrelationId(null)).isNull()
    }
}
