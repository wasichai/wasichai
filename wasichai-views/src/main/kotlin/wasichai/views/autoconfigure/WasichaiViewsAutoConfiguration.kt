package wasichai.views.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.autoconfigure.WasichaiDataAutoConfiguration
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiSchemas
import wasichai.views.ViewController
import wasichai.views.ViewMetadataController
import wasichai.views.ViewRepository
import wasichai.views.ViewService

// named list views. no scanning: every bean here, each one replaceable by the app.
@AutoConfiguration(after = [WasichaiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "wasichai.views", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WasichaiViewsProperties::class)
class WasichaiViewsAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would always make it back off
    @Bean
    fun wasichaiViewsMigration(): ModuleMigration = ModuleMigration("views", "classpath:db/wasichai/views", ModuleMigration.MODULE_ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun viewRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: WasichaiSchemas
    ): ViewRepository = ViewRepository(db, objectMapper, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun viewService(
        views: ViewRepository,
        metadata: MetadataService,
        currentUser: CurrentUser
    ): ViewService = ViewService(views, metadata, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun viewController(views: ViewService): ViewController = ViewController(views)

    @Bean
    @ConditionalOnMissingBean
    fun viewMetadataController(views: ViewService): ViewMetadataController = ViewMetadataController(views)
}
