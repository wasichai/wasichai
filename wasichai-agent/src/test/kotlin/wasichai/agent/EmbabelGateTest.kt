package wasichai.agent

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment
import wasichai.agent.autoconfigure.EmbabelGate

class EmbabelGateTest {
    private fun gated(vararg properties: Pair<String, String>): MockEnvironment {
        val environment = MockEnvironment()
        properties.forEach { (key, value) -> environment.setProperty(key, value) }
        EmbabelGate().postProcessEnvironment(environment, SpringApplication())
        return environment
    }

    private fun MockEnvironment.excluded(): List<String> = getProperty("spring.autoconfigure.exclude").orEmpty().split(",").filter { it.isNotBlank() }

    @Test
    fun `no key keeps embabel out, so the platform still boots`() {
        assertThat(gated().excluded()).containsAll(EmbabelGate.EXCLUDED)
    }

    @Test
    fun `a key lets embabel in, whichever way it came`() {
        assertThat(gated("ANTHROPIC_API_KEY" to "k").excluded()).isEmpty()
        assertThat(gated("wasichai.agent.api-key" to "k").excluded()).isEmpty()
        assertThat(gated("embabel.agent.platform.models.anthropic.api-key" to "k").excluded()).isEmpty()
    }

    @Test
    fun `switched off, embabel stays out even with a key`() {
        assertThat(gated("ANTHROPIC_API_KEY" to "k", "wasichai.agent.enabled" to "false").excluded()).containsAll(EmbabelGate.EXCLUDED)
    }

    @Test
    fun `the app's own exclusions survive`() {
        assertThat(gated("spring.autoconfigure.exclude" to "com.acme.FooAutoConfiguration").excluded())
            .contains("com.acme.FooAutoConfiguration")
            .containsAll(EmbabelGate.EXCLUDED)
    }

    // boot relax-binds a yaml list to exclude[0], exclude[1]...; getProperty("spring.autoconfigure.exclude")
    // never sees those keys, which used to drop the app's own list on the floor
    @Test
    fun `the app's own exclusions survive as an indexed list too`() {
        assertThat(
            gated(
                "spring.autoconfigure.exclude[0]" to "com.example.Foo",
                "spring.autoconfigure.exclude[1]" to "com.example.Bar"
            ).excluded()
        ).contains("com.example.Foo", "com.example.Bar")
            .containsAll(EmbabelGate.EXCLUDED)
    }

    // the original had these in application.yml; a library ships none (M8)
    @Test
    fun `the key and the model get defaults the app can override`() {
        assertThat(gated("ANTHROPIC_API_KEY" to "k").getProperty("wasichai.agent.api-key")).isEqualTo("k")
        assertThat(gated().getProperty("embabel.models.default-llm")).isEqualTo("claude-haiku-4-5")
        assertThat(gated("wasichai.agent.model" to "claude-opus-4-8").getProperty("embabel.models.default-llm")).isEqualTo("claude-opus-4-8")
        assertThat(gated("embabel.models.default-llm" to "mine").getProperty("embabel.models.default-llm")).isEqualTo("mine")
    }
}
