package wasichai.pages.autoconfigure

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.autoconfigure.WasichaiDataAutoConfiguration
import wasichai.core.data.WorkflowStates
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.RelationshipService
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiSchemas
import wasichai.forms.FormService
import wasichai.forms.autoconfigure.WasichaiFormsAutoConfiguration
import wasichai.pages.ObjectPageController
import wasichai.pages.PageComponentProvider
import wasichai.pages.PageComponentTypes
import wasichai.pages.PageController
import wasichai.pages.PageMetadataController
import wasichai.pages.PageRepository
import wasichai.pages.PageService
import wasichai.pages.PageTemplateController

// record pages. after forms, and only with its FormService: a FORM component names a stored form.
// module components (MAP, WORKFLOW) arrive as PageComponentProvider beans, found through a provider.
@AutoConfiguration(after = [WasichaiDataAutoConfiguration::class, WasichaiFormsAutoConfiguration::class])
@ConditionalOnProperty(prefix = "wasichai.pages", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(FormService::class)
@EnableConfigurationProperties(WasichaiPagesProperties::class)
class WasichaiPagesAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would always make it back off
    @Bean
    fun wasichaiPagesMigration(): ModuleMigration = ModuleMigration("pages", "classpath:db/wasichai/pages", ModuleMigration.MODULE_ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun pageComponentTypes(providers: ObjectProvider<PageComponentProvider>): PageComponentTypes = PageComponentTypes(providers.orderedStream().toList())

    @Bean
    @ConditionalOnMissingBean
    fun pageRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: WasichaiSchemas
    ): PageRepository = PageRepository(db, objectMapper, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun pageService(
        workflows: WorkflowStates,
        pages: PageRepository,
        metadata: MetadataService,
        relationships: RelationshipService,
        forms: FormService,
        currentUser: CurrentUser,
        componentTypes: PageComponentTypes
    ): PageService = PageService(workflows, pages, metadata, relationships, forms, currentUser, componentTypes)

    @Bean
    @ConditionalOnMissingBean
    fun pageController(pages: PageService): PageController = PageController(pages)

    @Bean
    @ConditionalOnMissingBean
    fun objectPageController(pages: PageService): ObjectPageController = ObjectPageController(pages)

    @Bean
    @ConditionalOnMissingBean
    fun pageTemplateController(): PageTemplateController = PageTemplateController()

    @Bean
    @ConditionalOnMissingBean
    fun pageMetadataController(pages: PageService): PageMetadataController = PageMetadataController(pages)
}
