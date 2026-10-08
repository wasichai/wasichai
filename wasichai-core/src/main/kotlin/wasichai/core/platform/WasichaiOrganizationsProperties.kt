package wasichai.core.platform

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "wasichai.organizations")
data class WasichaiOrganizationsProperties(
    // true: creating and deleting tenants takes MANAGE_TENANTS, granted to a role, never implied by ADMIN.
    // false: MANAGE_ORGANIZATION still suffices, as it always did (ADR-056)
    val separateProvisioning: Boolean = false
)
