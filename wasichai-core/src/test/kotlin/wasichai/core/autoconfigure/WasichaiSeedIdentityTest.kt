package wasichai.core.autoconfigure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import wasichai.core.platform.ModuleMigration

class WasichaiSeedIdentityTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WasichaiPlatformAutoConfiguration::class.java))
            .withPropertyValues("wasichai.database.migrate=false")

    @Test
    fun `the dev seed refuses oidc, naming both properties`() {
        runner
            .withPropertyValues(
                "wasichai.seed.dev=true",
                "wasichai.security.identity=oidc",
                "wasichai.security.oidc.issuer-uri=https://auth.example.org/realms/peru",
                "wasichai.security.oidc.client-id=caja"
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .rootCause()
                    .hasMessageContaining("wasichai.seed.dev")
                    .hasMessageContaining("wasichai.security.identity")
            }
    }

    @Test
    fun `the dev seed still loads in local mode`() {
        runner.withPropertyValues("wasichai.seed.dev=true").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values).contains(ModuleMigration.CORE_SEED)
        }
    }
}
