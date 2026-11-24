package wasichai.agent

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.identity.CurrentUser
import java.util.UUID

data class AgentConversationRequest(
    val title: String? = null
)

data class AgentSettingsRequest(
    val model: String? = null
)

// the assistant's memory and the caller's model pick (ADR-087). /ask stays as it was: no memory.
@RestController
@RequestMapping("/api/agent")
class AgentConversationController(
    private val conversations: AgentConversationService,
    private val settings: AgentSettingsService,
    private val currentUser: CurrentUser
) {
    @GetMapping("/conversations")
    suspend fun list(): List<AgentConversation> = conversations.list()

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @RequestBody(required = false) request: AgentConversationRequest?
    ): AgentConversation = conversations.create(request?.title)

    @GetMapping("/conversations/{id}")
    suspend fun get(
        @PathVariable id: UUID
    ): AgentConversationDetail = conversations.get(id)

    @PatchMapping("/conversations/{id}")
    suspend fun rename(
        @PathVariable id: UUID,
        @RequestBody request: AgentConversationRequest
    ): AgentConversation = conversations.rename(id, request.title)

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun archive(
        @PathVariable id: UUID
    ) = conversations.archive(id)

    @PostMapping("/conversations/{id}/messages")
    suspend fun send(
        @PathVariable id: UUID,
        @RequestBody request: AgentAskRequest
    ): AgentExchange = conversations.send(id, request.question)

    @GetMapping("/settings")
    suspend fun settings(): AgentSettings = settings.get(currentUser.require())

    @PutMapping("/settings")
    suspend fun updateSettings(
        @RequestBody request: AgentSettingsRequest
    ): AgentSettings = settings.update(currentUser.require(), request.model)
}
