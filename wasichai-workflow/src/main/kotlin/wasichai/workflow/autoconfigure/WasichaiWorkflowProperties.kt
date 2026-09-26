package wasichai.workflow.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("wasichai.workflow")
data class WasichaiWorkflowProperties(
    // false: no workflow beans, routes or migration; records have no state
    val enabled: Boolean = true
)
