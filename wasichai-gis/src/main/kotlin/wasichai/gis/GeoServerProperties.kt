package wasichai.gis

import org.springframework.boot.context.properties.ConfigurationProperties

// how geoserver itself reaches postgres, not how wasichai does. localhost assumes no container network; an app
// whose geoserver runs on one names the database's host there (ADR-031 D17)
data class GeoServerDataStoreProperties(
    val name: String = "wasichai-postgis",
    val host: String = "localhost",
    val port: Int = 5432,
    val database: String = "wasichai",
    // null: follow wasichai.database.data-schema, so an app that only sets that still gets a working layer
    val schema: String? = null,
    val username: String = "wasichai",
    val password: String = "wasichai"
)

@ConfigurationProperties(prefix = "wasichai.gis.geoserver")
data class GeoServerProperties(
    val url: String = "http://localhost:8081/geoserver",
    val username: String = "admin",
    val password: String = "geoserver",
    val workspace: String = "wasichai",
    val enabled: Boolean = true,
    val datastore: GeoServerDataStoreProperties = GeoServerDataStoreProperties()
) {
    // a trailing slash would double up in every rest path
    val baseUrl: String get() = url.trimEnd('/')
}
