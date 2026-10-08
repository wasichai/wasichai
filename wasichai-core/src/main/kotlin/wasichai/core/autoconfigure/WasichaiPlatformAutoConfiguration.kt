package wasichai.core.autoconfigure

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import wasichai.core.common.GlobalExceptionHandler
import wasichai.core.common.HealthController
import wasichai.core.metadata.ObjectSchemaManager
import wasichai.core.platform.CorrelationIdWebFilter
import wasichai.core.platform.JwtProperties
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.SystemColumnContributor
import wasichai.core.platform.SystemColumns
import wasichai.core.platform.WasichaiAuditProperties
import wasichai.core.platform.WasichaiDatabaseProperties
import wasichai.core.platform.WasichaiMigrations
import wasichai.core.platform.WasichaiOrganizationsProperties
import wasichai.core.platform.WasichaiSchemas
import wasichai.core.platform.WasichaiWebProperties

// properties, schema names, system columns, migrations, errors, health, correlation id. no scanning: every bean here.
@AutoConfiguration
@EnableConfigurationProperties(
    WasichaiDatabaseProperties::class,
    JwtProperties::class,
    WasichaiWebProperties::class,
    WasichaiAuditProperties::class,
    WasichaiOrganizationsProperties::class
)
class WasichaiPlatformAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun wasichaiSchemas(database: WasichaiDatabaseProperties): WasichaiSchemas = WasichaiSchemas.of(database)

    @Bean
    @ConditionalOnMissingBean
    fun systemColumns(contributors: ObjectProvider<SystemColumnContributor>): SystemColumns = SystemColumns(contributors.orderedStream().toList())

    @Bean
    fun wasichaiCoreMigration(): ModuleMigration = ModuleMigration.CORE

    @Bean
    @ConditionalOnProperty(prefix = "wasichai.seed", name = ["dev"], havingValue = "true")
    fun wasichaiCoreSeedMigration(): ModuleMigration = ModuleMigration.CORE_SEED

    // runs at startup, before traffic: WasichaiMigrations.afterPropertiesSet() calls migrate(), so it
    // runs even if an app replaces this bean with its own instance. every module migration bean is
    // in the list. keep the bean name "wasichaiMigrations" stable: modules @DependsOn it by name.
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "wasichai.database", name = ["migrate"], havingValue = "true", matchIfMissing = true)
    fun wasichaiMigrations(
        database: WasichaiDatabaseProperties,
        schemas: WasichaiSchemas,
        migrations: ObjectProvider<ModuleMigration>,
        audit: WasichaiAuditProperties
    ): WasichaiMigrations = WasichaiMigrations(database, schemas, migrations.orderedStream().toList(), audit)

    @Bean
    @ConditionalOnMissingBean
    fun globalExceptionHandler(
        web: WasichaiWebProperties,
        schema: ObjectProvider<ObjectSchemaManager>
    ): GlobalExceptionHandler = GlobalExceptionHandler(web.problemBaseUri) { schema.getIfAvailable()?.uniqueFields(it).orEmpty() }

    // X-Correlation-Id on every request and response, ahead of the security chain (ADR-050)
    @Bean
    @ConditionalOnMissingBean
    fun correlationIdWebFilter(): CorrelationIdWebFilter = CorrelationIdWebFilter()

    @Bean
    @ConditionalOnMissingBean
    fun healthController(
        @Value("\${spring.application.name:wasichai}") applicationName: String
    ): HealthController = HealthController(applicationName)
}
