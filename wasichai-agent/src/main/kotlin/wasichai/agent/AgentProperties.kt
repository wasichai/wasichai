package wasichai.agent

import org.springframework.boot.context.properties.ConfigurationProperties

// the agent is optional. no key, no agent, and nothing else in wasichai notices.
@ConfigurationProperties(prefix = "wasichai.agent")
data class AgentProperties(
    val enabled: Boolean = true,
    val apiKey: String = "",
    // must be in embabel's catalogue. 1.5.4 knows the 5.5 family; haiku is the cheapest (ADR-073)
    val model: String = "claude-haiku-5-5",
    val maxTokens: Long = 16_000,
    val maxIterations: Int = 8,
    // what a user may pick instead of [model] (ADR-087). each must be in embabel's catalogue too.
    val models: List<String> = emptyList(),
    // how many earlier exchanges of a conversation are sent back with a new question
    val historyTurns: Int = 10
) {
    // switched on and actually usable are two different things
    val available: Boolean get() = enabled && apiKey.isNotBlank()

    // how many actions one run may take before the platform stops it. embabel's own tool loop
    // inside an action has a separate cap of 20 that 1.5.2 does not expose as configuration.
    val iterationCap: Int get() = maxIterations.coerceIn(1, 16)

    val tokenCap: Long get() = maxTokens.coerceIn(1_024, 64_000)

    // the default first, then the others, once each, blanks dropped
    val allowedModels: List<String> get() = (listOf(model) + models).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    val historyCap: Int get() = historyTurns.coerceIn(0, 50)
}
