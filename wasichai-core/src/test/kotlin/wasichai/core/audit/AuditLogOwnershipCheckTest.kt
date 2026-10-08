package wasichai.core.audit

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

// issue 58 (ADR-054): a WARN when the runtime role could drop audit_log's triggers, and never a failed startup
@ExtendWith(OutputCaptureExtension::class)
class AuditLogOwnershipCheckTest {
    @Test
    fun `a runtime role that can act as the owner gets a WARN naming both roles and the fix`(output: CapturedOutput) =
        runTest {
            val warning = AuditLogOwnershipCheck { AuditLogOwner(owner = "wasichai_owner", runtime = "wasichai_app") }.check()

            assertThat(warning)
                .contains("'wasichai_app'")
                .contains("'wasichai_owner'")
                .contains("drop or disable")
                .contains("two database roles")
            assertThat(output).contains("WARN").contains(warning)
        }

    @Test
    fun `a runtime role that cannot act as the owner, or no table, says nothing`(output: CapturedOutput) =
        runTest {
            assertThat(AuditLogOwnershipCheck { null }.check()).isNull()
            assertThat(output).doesNotContain("audit_log")
        }

    @Test
    fun `a check that cannot run is logged and swallowed, never thrown`(output: CapturedOutput) =
        runTest {
            val warning = AuditLogOwnershipCheck { error("connection refused") }.check()

            assertThat(warning).isNull()
            assertThat(output).contains("could not check who owns audit_log: connection refused").doesNotContain("WARN")
        }

    @Test
    fun `a check that hangs gives up instead of holding startup`() =
        runTest {
            // runTest skips virtual time: the 10 s timeout fires at once
            assertThat(AuditLogOwnershipCheck { awaitCancellation() }.check()).isNull()
        }
}
