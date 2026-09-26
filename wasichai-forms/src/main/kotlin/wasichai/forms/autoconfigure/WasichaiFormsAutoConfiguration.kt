package wasichai.forms.autoconfigure

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
import wasichai.forms.FormController
import wasichai.forms.FormMetadataController
import wasichai.forms.FormRepository
import wasichai.forms.FormService

// named forms. no scanning: every bean here, each one replaceable by the app.
@AutoConfiguration(after = [WasichaiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "wasichai.forms", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WasichaiFormsProperties::class)
class WasichaiFormsAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would always make it back off
    @Bean
    fun wasichaiFormsMigration(): ModuleMigration = ModuleMigration("forms", "classpath:db/wasichai/forms", ModuleMigration.MODULE_ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun formRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: WasichaiSchemas
    ): FormRepository = FormRepository(db, objectMapper, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun formService(
        forms: FormRepository,
        metadata: MetadataService,
        currentUser: CurrentUser
    ): FormService = FormService(forms, metadata, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun formController(forms: FormService): FormController = FormController(forms)

    @Bean
    @ConditionalOnMissingBean
    fun formMetadataController(forms: FormService): FormMetadataController = FormMetadataController(forms)
}
