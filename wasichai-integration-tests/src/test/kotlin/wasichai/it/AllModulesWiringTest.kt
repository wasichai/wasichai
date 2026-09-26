package wasichai.it

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
import wasichai.agent.AgentTools
import wasichai.agent.WorkflowRecordTransitions
import wasichai.agent.autoconfigure.WasichaiAgentAutoConfiguration
import wasichai.agent.autoconfigure.WasichaiAgentWorkflowAutoConfiguration
import wasichai.automation.AutomationService
import wasichai.automation.autoconfigure.WasichaiAutomationAutoConfiguration
import wasichai.core.data.WorkflowStates
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.SystemColumns
import wasichai.documents.DocumentIssuerAdapter
import wasichai.documents.autoconfigure.WasichaiDocumentsAutoConfiguration
import wasichai.documents.autoconfigure.WasichaiDocumentsAutomationAutoConfiguration
import wasichai.forms.autoconfigure.WasichaiFormsAutoConfiguration
import wasichai.gis.GEOMETRY
import wasichai.gis.MapPageComponent
import wasichai.gis.autoconfigure.WasichaiGisAutoConfiguration
import wasichai.gis.autoconfigure.WasichaiGisPagesAutoConfiguration
import wasichai.pages.ComponentType
import wasichai.pages.PageComponentTypes
import wasichai.pages.autoconfigure.WasichaiPagesAutoConfiguration
import wasichai.test.WasichaiContextRunner
import wasichai.views.autoconfigure.WasichaiViewsAutoConfiguration
import wasichai.workflow.WorkflowPageComponent
import wasichai.workflow.WorkflowStatesAdapter
import wasichai.workflow.autoconfigure.WasichaiWorkflowAutoConfiguration
import wasichai.workflow.autoconfigure.WasichaiWorkflowPagesAutoConfiguration

class AllModulesWiringTest {
    // a handler mapping, so every controller's routes are registered and an ambiguous one fails the
    // context. no @Configuration on purpose: P3's scanned test apps in this package must not pick it up.
    @EnableWebFlux
    class WebFlux

    private val runner =
        WasichaiContextRunner
            .core()
            .withUserConfiguration(WebFlux::class.java)
            .withConfiguration(
                AutoConfigurations.of(
                    WasichaiViewsAutoConfiguration::class.java,
                    WasichaiFormsAutoConfiguration::class.java,
                    WasichaiPagesAutoConfiguration::class.java,
                    WasichaiWorkflowAutoConfiguration::class.java,
                    WasichaiWorkflowPagesAutoConfiguration::class.java,
                    WasichaiAutomationAutoConfiguration::class.java,
                    WasichaiDocumentsAutoConfiguration::class.java,
                    WasichaiDocumentsAutomationAutoConfiguration::class.java,
                    WasichaiGisAutoConfiguration::class.java,
                    WasichaiGisPagesAutoConfiguration::class.java,
                    WasichaiAgentAutoConfiguration::class.java,
                    WasichaiAgentWorkflowAutoConfiguration::class.java
                )
            ).withPropertyValues("wasichai.automation.poll-interval=0s")

    @Test
    fun `every module wires with every optional link made`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name })
                .containsExactlyInAnyOrder("core", "views", "forms", "pages", "workflow", "automation", "documents", "gis")
            assertThat(context.getBean(FieldTypeRegistry::class.java).types.last()).isEqualTo(GEOMETRY)
            assertThat(context.getBean(SystemColumns::class.java).names).contains("workflow_state")
            assertThat(context.getBean(WorkflowStates::class.java)).isInstanceOf(WorkflowStatesAdapter::class.java)
            val components = context.getBean(PageComponentTypes::class.java)
            assertThat(components.provider(ComponentType("MAP"))).isInstanceOf(MapPageComponent::class.java)
            assertThat(components.provider(ComponentType("WORKFLOW"))).isInstanceOf(WorkflowPageComponent::class.java)
            assertThat(ReflectionTestUtils.getField(context.getBean(AutomationService::class.java), "documents"))
                .isInstanceOf(DocumentIssuerAdapter::class.java)
            assertThat(ReflectionTestUtils.getField(context.getBean(AgentTools::class.java), "transitions"))
                .isInstanceOf(WorkflowRecordTransitions::class.java)
        }
    }

    // the original's module routes, verb by verb, plus the three metadata routes that left core (P1 R16)
    @Test
    fun `the module routes are the original's, with no collision`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val routes =
                context.getBean(RequestMappingHandlerMapping::class.java).handlerMethods.keys.flatMap { info ->
                    info.methodsCondition.methods.flatMap { verb -> info.patternsCondition.patterns.map { "${verb.name} ${it.patternString}" } }
                }
            assertThat(routes).doesNotHaveDuplicates().containsAll(LEGACY_MODULE_ROUTES)
        }
    }

    companion object {
        val LEGACY_MODULE_ROUTES =
            listOf(
                "GET /api/metadata/objects/{object}/views",
                "GET /api/metadata/objects/{object}/forms",
                "GET /api/metadata/objects/{object}/pages",
                "DELETE /api/gis/layers/{object}",
                "DELETE /api/gis/layers/{object}/{geometry}",
                "DELETE /api/objects/{object}/automations/{name}",
                "DELETE /api/objects/{object}/document-types/{name}",
                "DELETE /api/objects/{object}/forms/{name}",
                "DELETE /api/objects/{object}/views/{name}",
                "DELETE /api/objects/{object}/workflow",
                "DELETE /api/pages/{name}",
                "GET /api/agent/status",
                "GET /api/automation-runs",
                "GET /api/documents/{id}",
                "GET /api/gis/layers",
                "GET /api/gis/objects/{object}/features",
                "GET /api/gis/objects/{object}/features/{id}",
                "GET /api/gis/services",
                "GET /api/metadata/page-templates",
                "GET /api/objects/{object}/automations",
                "GET /api/objects/{object}/automations/{name}",
                "GET /api/objects/{object}/automations/{name}/runs",
                "GET /api/objects/{object}/document-types",
                "GET /api/objects/{object}/document-types/{name}",
                "GET /api/objects/{object}/forms",
                "GET /api/objects/{object}/forms/{name}",
                "GET /api/objects/{object}/pages/{kind}",
                "GET /api/objects/{object}/records/{id}/documents",
                "GET /api/objects/{object}/records/{id}/transitions",
                "GET /api/objects/{object}/views",
                "GET /api/objects/{object}/views/{name}",
                "GET /api/objects/{object}/workflow",
                "GET /api/pages",
                "GET /api/pages/{name}",
                "POST /api/agent/ask",
                "POST /api/gis/layers/{object}",
                "POST /api/gis/layers/{object}/{geometry}",
                "POST /api/objects/{object}/automations",
                "POST /api/objects/{object}/document-types",
                "POST /api/objects/{object}/forms",
                "POST /api/objects/{object}/records/{id}/documents/{type}",
                "POST /api/objects/{object}/records/{id}/transitions/{name}",
                "POST /api/objects/{object}/views",
                "POST /api/pages",
                "PUT /api/objects/{object}/automations/{name}",
                "PUT /api/objects/{object}/document-types/{name}",
                "PUT /api/objects/{object}/forms/{name}",
                "PUT /api/objects/{object}/views/{name}",
                "PUT /api/objects/{object}/workflow",
                "PUT /api/pages/{name}"
            )
    }
}
