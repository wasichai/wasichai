package wasichai.core.autoconfigure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class WasichaiEnvironmentPostProcessorTest {
    private fun environment(app: Map<String, Any>): StandardEnvironment =
        StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("app", app))
            WasichaiEnvironmentPostProcessor().postProcessEnvironment(this, SpringApplication())
        }

    @Test
    fun `r2dbc follows wasichai database settings`() {
        val env = environment(mapOf("wasichai.database.host" to "db.local", "wasichai.database.port" to "6543", "wasichai.database.name" to "acme"))

        assertThat(env.getProperty("spring.r2dbc.url")).isEqualTo("r2dbc:postgresql://db.local:6543/acme")
        assertThat(env.getProperty("spring.webflux.problemdetails.enabled")).isEqualTo("true")
    }

    @Test
    fun `whatever the app sets wins over the defaults`() {
        val env = environment(mapOf("spring.r2dbc.url" to "r2dbc:postgresql://elsewhere/x"))

        assertThat(env.getProperty("spring.r2dbc.url")).isEqualTo("r2dbc:postgresql://elsewhere/x")
    }

    // ADR-050: the correlation id reaches the MDC on every thread; an app may turn it back to limited
    @Test
    fun `reactor context propagation is on unless the app says otherwise`() {
        assertThat(environment(emptyMap()).getProperty("spring.reactor.context-propagation")).isEqualTo("auto")
        assertThat(environment(mapOf("spring.reactor.context-propagation" to "limited")).getProperty("spring.reactor.context-propagation"))
            .isEqualTo("limited")
    }

    @Test
    fun `no jwt secret unless the app gives one`() {
        assertThat(environment(emptyMap()).getProperty("wasichai.security.jwt.secret")).isEmpty()
    }
}
