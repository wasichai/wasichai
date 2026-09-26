package wasichai.gis.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

// the module switch. geoserver publishing has its own switch: wasichai.gis.geoserver.enabled.
@ConfigurationProperties("wasichai.gis")
data class WasichaiGisProperties(
    // false: no GEOMETRY type, no bbox, no gis routes, no postgis migration
    val enabled: Boolean = true
)
