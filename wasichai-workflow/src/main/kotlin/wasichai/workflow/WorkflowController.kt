package wasichai.workflow

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.data.RecordResponse
import java.util.UUID

data class WorkflowResponse(
    val id: String,
    val objectName: String,
    val name: String,
    val label: String,
    val enabled: Boolean,
    val definition: WorkflowDefinition
)

fun Pair<Workflow, String>.toResponse(): WorkflowResponse =
    WorkflowResponse(
        id = first.id.toString(),
        objectName = second,
        name = first.name,
        label = first.label,
        enabled = first.enabled,
        definition = first.definition
    )

@RestController
@RequestMapping("/api/objects/{object}")
class WorkflowController(
    private val workflows: WorkflowService
) {
    @GetMapping("/workflow")
    suspend fun get(
        @PathVariable("object") objectName: String
    ): WorkflowResponse = workflows.byObject(objectName).toResponse()

    @PutMapping("/workflow")
    suspend fun save(
        @PathVariable("object") objectName: String,
        @RequestBody request: SaveWorkflowRequest
    ): WorkflowResponse = workflows.save(objectName, request).toResponse()

    @DeleteMapping("/workflow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable("object") objectName: String
    ) = workflows.delete(objectName)

    @GetMapping("/records/{id}/transitions")
    suspend fun transitions(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID
    ): List<AvailableTransition> = workflows.transitionsOf(objectName, id)

    @PostMapping("/records/{id}/transitions/{name}")
    suspend fun apply(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @PathVariable name: String
    ): RecordResponse = workflows.apply(objectName, id, name)
}
