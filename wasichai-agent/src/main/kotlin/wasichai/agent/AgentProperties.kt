package wasichai.agent

import org.springframework.boot.context.properties.ConfigurationProperties

// the agent is optional. no key, no agent, and nothing else in wasichai notices.
@ConfigurationProperties(prefix = "wasichai.agent")
data class AgentProperties(
    val enabled: Boolean = true,
    val apiKey: String = "",
    // embabel 1.5.2 does not know claude-opus-5; its newest anthropic model is claude-opus-4-8
    val model: String = "claude-haiku-4-5",
    val maxTokens: Long = 16_000,
    val maxIterations: Int = 8
) {
    // switched on and actually usable are two different things
    val available: Boolean get() = enabled && apiKey.isNotBlank()

    // how many actions one run may take before the platform stops it. embabel's own tool loop
    // inside an action has a separate cap of 20 that 1.5.2 does not expose as configuration.
    val iterationCap: Int get() = maxIterations.coerceIn(1, 16)

    val tokenCap: Long get() = maxTokens.coerceIn(1_024, 64_000)
}
