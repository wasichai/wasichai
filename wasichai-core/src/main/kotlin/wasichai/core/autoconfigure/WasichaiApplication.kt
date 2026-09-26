package wasichai.core.autoconfigure

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.core.annotation.AliasFor
import java.lang.annotation.Inherited
import kotlin.reflect.KClass

// an app's main class in one word: boot's app plus scanning of the app's own @ConfigurationProperties.
// scanning starts at the app's package, so library code is still never scanned: auto-config wires it.
// the three attributes forward to @SpringBootApplication so an app can still exclude an
// auto-config or narrow the scan, exactly as it could writing @SpringBootApplication directly.
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Inherited
@SpringBootApplication
@ConfigurationPropertiesScan
annotation class WasichaiApplication(
    @get:AliasFor(annotation = SpringBootApplication::class, attribute = "exclude")
    val exclude: Array<KClass<*>> = [],
    @get:AliasFor(annotation = SpringBootApplication::class, attribute = "excludeName")
    val excludeName: Array<String> = [],
    @get:AliasFor(annotation = SpringBootApplication::class, attribute = "scanBasePackages")
    val scanBasePackages: Array<String> = []
)
