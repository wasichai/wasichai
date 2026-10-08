package wasichai.automation

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException
import java.util.UUID

class NoAutomationNotifierTest {
    @Test
    fun `without a notifications module NOTIFY is not available, so saving the action is refused`() {
        assertThat(NoAutomationNotifier().available).isFalse()
    }

    @Test
    fun `a stored NOTIFY without the module fails its run with a 400, not a crash`() =
        runTest {
            val request = NotifyRequest(UUID.randomUUID(), "aviso", "predio", UUID.randomUUID(), 0, "role:SUPERVISOR", "INFO", "Aprobado", null)
            val error = runCatching { NoAutomationNotifier().notify(request) }.exceptionOrNull()
            assertThat(error).isInstanceOf(ValidationException::class.java).hasMessage("NOTIFY needs the notifications module")
        }
}
