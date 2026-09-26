package wasichai.core.autoconfigure

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

// what an app gets without writing any yaml. added last: anything the app sets wins.
// no usable jwt secret on purpose (R15).
class WasichaiEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication
    ) {
        environment.propertySources.addLast(MapPropertySource(SOURCE, DEFAULTS))
    }

    companion object {
        const val SOURCE = "wasichaiDefaults"

        val DEFAULTS: Map<String, Any> =
            mapOf(
                "wasichai.database.host" to "\${WASICHAI_DB_HOST:localhost}",
                "wasichai.database.port" to "\${WASICHAI_DB_PORT:5432}",
                "wasichai.database.name" to "\${WASICHAI_DB_NAME:wasichai}",
                "wasichai.database.username" to "\${WASICHAI_DB_USERNAME:wasichai}",
                "wasichai.database.password" to "\${WASICHAI_DB_PASSWORD:wasichai}",
                "wasichai.security.jwt.secret" to "\${WASICHAI_JWT_SECRET:}",
                "spring.r2dbc.url" to "r2dbc:postgresql://\${wasichai.database.host}:\${wasichai.database.port}/\${wasichai.database.name}",
                "spring.r2dbc.username" to "\${wasichai.database.username}",
                "spring.r2dbc.password" to "\${wasichai.database.password}",
                "spring.r2dbc.pool.enabled" to "true",
                "spring.r2dbc.pool.initial-size" to "5",
                "spring.r2dbc.pool.max-size" to "20",
                "spring.webflux.problemdetails.enabled" to "true"
            )
    }
}
