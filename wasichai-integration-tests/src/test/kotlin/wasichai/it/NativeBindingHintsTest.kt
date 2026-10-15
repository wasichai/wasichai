package wasichai.it

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.annotation.ReflectiveRuntimeHintsRegistrar
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates
import wasichai.automation.AutomationDefinition
import wasichai.automation.RunPayload
import wasichai.automation.RunStep
import wasichai.automation.autoconfigure.WasichaiAutomationAutoConfiguration
import wasichai.documents.DocumentSnapshot
import wasichai.documents.TemplateNode
import wasichai.documents.autoconfigure.WasichaiDocumentsAutoConfiguration
import wasichai.forms.FormDefinition
import wasichai.forms.autoconfigure.WasichaiFormsAutoConfiguration
import wasichai.gis.GeometryResponse
import wasichai.gis.autoconfigure.WasichaiGisAutoConfiguration
import wasichai.notifications.LinkJson
import wasichai.notifications.NotificationRuleDefinition
import wasichai.notifications.autoconfigure.WasichaiNotificationsAutoConfiguration
import wasichai.pages.PageDefinition
import wasichai.pages.autoconfigure.WasichaiPagesAutoConfiguration
import wasichai.views.ViewDefinition
import wasichai.views.autoconfigure.WasichaiViewsAutoConfiguration
import wasichai.workflow.WorkflowDefinition
import wasichai.workflow.autoconfigure.WasichaiWorkflowAutoConfiguration

// what a module reads from jsonb, or writes into a response as Any, has to be bindable in a native image (ADR-068)
class NativeBindingHintsTest {
    @ParameterizedTest
    @MethodSource("modules")
    fun `a module's auto-configuration registers the types spring cannot see`(
        configuration: Class<*>,
        types: List<Class<*>>
    ) {
        val hints = RuntimeHints()
        ReflectiveRuntimeHintsRegistrar().registerRuntimeHints(hints, configuration)

        types.forEach { assertThat(RuntimeHintsPredicates.reflection().onType(it)).accepts(hints) }
    }

    companion object {
        @JvmStatic
        fun modules() =
            listOf(
                arrayOf(WasichaiFormsAutoConfiguration::class.java, listOf(FormDefinition::class.java)),
                arrayOf(WasichaiViewsAutoConfiguration::class.java, listOf(ViewDefinition::class.java)),
                arrayOf(WasichaiPagesAutoConfiguration::class.java, listOf(PageDefinition::class.java)),
                arrayOf(WasichaiWorkflowAutoConfiguration::class.java, listOf(WorkflowDefinition::class.java)),
                arrayOf(
                    WasichaiAutomationAutoConfiguration::class.java,
                    listOf(AutomationDefinition::class.java, RunPayload::class.java, RunStep::class.java)
                ),
                arrayOf(WasichaiDocumentsAutoConfiguration::class.java, listOf(DocumentSnapshot::class.java, TemplateNode::class.java)),
                arrayOf(WasichaiGisAutoConfiguration::class.java, listOf(GeometryResponse::class.java)),
                arrayOf(
                    WasichaiNotificationsAutoConfiguration::class.java,
                    listOf(NotificationRuleDefinition::class.java, LinkJson::class.java)
                )
            )
    }
}
