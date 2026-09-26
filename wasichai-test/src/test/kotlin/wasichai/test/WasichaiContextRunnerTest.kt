package wasichai.test

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import wasichai.core.data.RecordService
import wasichai.core.platform.WasichaiMigrations

class WasichaiContextRunnerTest {
    @Test
    fun `core wires with no database and runs no migration`() {
        WasichaiContextRunner.core().run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(RecordService::class.java)
            assertThat(context).doesNotHaveBean(WasichaiMigrations::class.java)
        }
    }
}
