package wasichai.automation

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class AutomationResponse(
    val id: String,
    val objectName: String,
    val name: String,
    val label: String,
    val enabled: Boolean,
    val definition: AutomationDefinition
)

data class AutomationRunResponse(
    val id: String,
    val automation: String?,
    val objectName: String,
    val recordId: String?,
    val trigger: TriggerType,
    val status: RunStatus,
    val depth: Int,
    val steps: List<RunStep>,
    val error: String?,
    val attempts: Int,
    val createdAt: Instant?,
    val finishedAt: Instant?
)

fun Pair<Automation, String>.toResponse(): AutomationResponse =
    AutomationResponse(
        id = first.id.toString(),
        objectName = second,
        name = first.name,
        label = first.label,
        enabled = first.enabled,
        definition = first.definition
    )

fun AutomationRun.toResponse(): AutomationRunResponse =
    AutomationRunResponse(
        id = id.toString(),
        automation = automationName,
        objectName = objectName,
        recordId = recordId?.toString(),
        trigger = trigger,
        status = status,
        depth = depth,
        steps = steps,
        error = error,
        attempts = attempts,
        createdAt = createdAt,
        finishedAt = finishedAt
    )

@RestController
@RequestMapping("/api")
class AutomationController(
    private val automations: AutomationService
) {
    @GetMapping("/objects/{object}/automations")
    suspend fun list(
        @PathVariable("object") objectName: String
    ): List<AutomationResponse> {
        val (found, name) = automations.list(objectName)
        return found.map { (it to name).toResponse() }
    }

    @PostMapping("/objects/{object}/automations")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @PathVariable("object") objectName: String,
        @RequestBody request: SaveAutomationRequest
    ): AutomationResponse = automations.save(objectName, null, request).toResponse()

    @GetMapping("/objects/{object}/automations/{name}")
    suspend fun get(
        @PathVariable("object") objectName: String,
        @PathVariable name: String
    ): AutomationResponse = automations.byName(objectName, name).toResponse()

    @PutMapping("/objects/{object}/automations/{name}")
    suspend fun save(
        @PathVariable("object") objectName: String,
        @PathVariable name: String,
        @RequestBody request: SaveAutomationRequest
    ): AutomationResponse = automations.save(objectName, name, request).toResponse()

    @DeleteMapping("/objects/{object}/automations/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable("object") objectName: String,
        @PathVariable name: String
    ) = automations.delete(objectName, name)

    @GetMapping("/objects/{object}/automations/{name}/runs")
    suspend fun runs(
        @PathVariable("object") objectName: String,
        @PathVariable name: String,
        @RequestParam(defaultValue = "50") limit: Int
    ): List<AutomationRunResponse> = automations.runsOf(objectName, name, limit).map { it.toResponse() }

    @GetMapping("/automation-runs")
    suspend fun recent(
        @RequestParam(defaultValue = "50") limit: Int
    ): List<AutomationRunResponse> = automations.recentRuns(limit).map { it.toResponse() }
}
