package wasichai.core.platform

import org.springframework.boot.context.properties.ConfigurationProperties

// one place for db coords. r2dbc at runtime, jdbc once for flyway.
@ConfigurationProperties(prefix = "wasichai.database")
data class WasichaiDatabaseProperties(
    val host: String = "localhost",
    val port: Int = 5432,
    val name: String = "wasichai",
    val username: String = "wasichai",
    val password: String = "wasichai",
    val metadataSchema: String = "wasichai",
    val dataSchema: String = "app_data",
    // false when the app runs the migrations some other way
    val migrate: Boolean = true
) {
    val jdbcUrl: String
        get() = "jdbc:postgresql://$host:$port/$name"
}
