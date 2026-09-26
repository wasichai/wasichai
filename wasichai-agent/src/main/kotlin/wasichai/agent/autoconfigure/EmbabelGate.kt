package wasichai.agent.autoconfigure

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment
import org.springframework.core.env.MapPropertySource

/**
 * Embabel's platform refuses to start without a model: no key, no context, no app. The app has to
 * boot without one — CI runs with no key, and an app that does not want the assistant is still an
 * app. So with no key (or with the module switched off) Embabel's auto-configuration is kept out
 * of the context, and the assistant reports itself unavailable.
 *
 * It also adds the two defaults the original kept in its application.yml, last, so the app wins.
 */
class EmbabelGate : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication
    ) {
        environment.propertySources.addLast(MapPropertySource(DEFAULTS_SOURCE, DEFAULTS))
        val enabled = environment.getProperty("wasichai.agent.enabled", Boolean::class.java, true)
        if (enabled && anthropicKeyOf(environment).isNotBlank()) return
        // merged, not replaced: the app may exclude auto-configs of its own. a Binder, not
        // getProperty: yaml lists relax-bind to exclude[0], exclude[1]..., which getProperty
        // never sees; a plain comma string binds fine too, so both forms survive.
        val existing = Binder.get(environment).bind(EXCLUDE, Bindable.listOf(String::class.java)).orElse(emptyList())
        val excluded = (existing.orEmpty() + EXCLUDED).distinct()
        environment.propertySources.addFirst(MapPropertySource(SOURCE_NAME, mapOf(EXCLUDE to excluded.joinToString(","))))
    }

    companion object {
        const val SOURCE_NAME = "wasichai-embabel-gate"
        const val DEFAULTS_SOURCE = "wasichai-agent-defaults"
        private const val EXCLUDE = "spring.autoconfigure.exclude"

        val DEFAULTS: Map<String, Any> =
            mapOf(
                "wasichai.agent.api-key" to "\${ANTHROPIC_API_KEY:}",
                // without it embabel looks for gpt-4.1-mini and the context fails to start
                "embabel.models.default-llm" to "\${wasichai.agent.model:claude-haiku-4-5}"
            )

        // every embabel autoconfiguration. excluding only the model one moves the failure, it does
        // not avoid it: the platform itself asserts that a model exists.
        val EXCLUDED =
            listOf(
                "com.embabel.agent.autoconfigure.models.anthropic.AgentAnthropicAutoConfiguration",
                "com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration",
                "com.embabel.agent.autoconfigure.platform.cache.AgentCacheProviderAutoConfiguration",
                "com.embabel.agent.autoconfigure.platform.cache.CacheSnapshotStoreAutoConfiguration"
            )

        /** The key can arrive as the env var Embabel documents, its own property, or ours. */
        fun anthropicKeyOf(environment: Environment): String =
            sequenceOf(
                "ANTHROPIC_API_KEY",
                "embabel.agent.platform.models.anthropic.api-key",
                "wasichai.agent.api-key"
            ).mapNotNull { environment.getProperty(it) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
    }
}
