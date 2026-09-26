package wasichai.workflow.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import wasichai.core.data.WorkflowStates
import wasichai.workflow.WorkflowPageComponent

// the WORKFLOW component, only when wasichai-pages is on the classpath (named as a string, so nothing
// loads a pages class otherwise) and workflow itself is switched on. pages collects providers
// lazily, so no order against its auto-config is needed.
@AutoConfiguration
@ConditionalOnClass(name = ["wasichai.pages.PageComponentProvider"])
@ConditionalOnProperty(prefix = "wasichai.workflow", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class WasichaiWorkflowPagesAutoConfiguration {
    // order (the original app's MAP then WORKFLOW) lives on WorkflowPageComponent itself, matching gis's
    // MapPageComponent -- both providers carry their own @Order rather than the @Bean method.
    @Bean
    @ConditionalOnMissingBean
    fun workflowPageComponent(workflows: WorkflowStates): WorkflowPageComponent = WorkflowPageComponent(workflows)
}
