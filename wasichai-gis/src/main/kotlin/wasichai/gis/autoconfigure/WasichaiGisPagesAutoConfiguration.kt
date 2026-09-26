package wasichai.gis.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import wasichai.gis.MapPageComponent

// the MAP component, only when wasichai-pages is on the classpath (named as a string, so nothing
// loads a pages class otherwise) and gis itself is switched on. pages collects providers lazily,
// so no order against its auto-config is needed.
@AutoConfiguration
@ConditionalOnClass(name = ["wasichai.pages.PageComponentProvider"])
@ConditionalOnProperty(prefix = "wasichai.gis", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class WasichaiGisPagesAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun mapPageComponent(): MapPageComponent = MapPageComponent()
}
