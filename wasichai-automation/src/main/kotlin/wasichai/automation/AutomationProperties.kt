package wasichai.automation

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "wasichai.automation")
data class AutomationProperties(
    // false: no automation beans, routes, drain or migration
    val enabled: Boolean = true,
    // zero disables the background drain. tests drive the runner by hand.
    val pollInterval: Duration = Duration.ofSeconds(1),
    val batchSize: Int = 20,
    // an automation that writes fires another change. this is where the chain stops.
    val maxDepth: Int = 3,
    val webhookTimeout: Duration = Duration.ofSeconds(10),
    // a webhook pointing at localhost turns an automation into a request forgery
    val allowPrivateWebhooks: Boolean = false
) {
    val polling: Boolean get() = !pollInterval.isZero && !pollInterval.isNegative

    val batch: Int get() = batchSize.coerceIn(1, 200)

    val depthCap: Int get() = maxDepth.coerceIn(1, 10)
}
