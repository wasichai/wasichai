package wasichai.agent

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// nullable so a missing question reads as a validation error, not a 500 from jackson
data class AgentAskRequest(
    val question: String? = null
)

data class AgentStepResponse(
    val tool: String,
    val input: Map<String, Any?>,
    val summary: String
)

data class AgentAnswerResponse(
    val answer: String,
    val steps: List<AgentStepResponse>,
    val truncated: Boolean,
    // left out when the platform reported no model call, so that answer reads as it always did
    @field:JsonInclude(JsonInclude.Include.NON_NULL) val usage: AgentUsage? = null
)

@RestController
@RequestMapping("/api/agent")
class AgentController(
    private val agent: AgentService
) {
    @PostMapping("/ask")
    suspend fun ask(
        @RequestBody request: AgentAskRequest
    ): AgentAnswerResponse = agent.ask(request.question).toResponse()

    // the ui asks this before it renders the box, so a server without a key, or an organization
    // the app's AgentAccessPolicy leaves out, shows a notice
    @GetMapping("/status")
    suspend fun status(): AgentStatus = agent.status()
}

private fun AgentAnswer.toResponse(): AgentAnswerResponse =
    AgentAnswerResponse(
        answer = answer,
        steps = steps.map { AgentStepResponse(it.tool, it.input, it.summary) },
        truncated = truncated,
        usage = usage
    )
