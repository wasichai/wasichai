package wasichai.notifications

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

// date rules (spec C). the body is NotificationRuleDefinition; the answer adds the object.
@RestController
@RequestMapping("/api")
class NotificationRuleController(
    private val rules: NotificationRuleService
) {
    @GetMapping("/notification-rules")
    suspend fun listAll(): List<NotificationRuleView> = rules.listAll()

    @GetMapping("/objects/{object}/notification-rules")
    suspend fun list(
        @PathVariable("object") objectName: String
    ): List<NotificationRuleView> = rules.list(objectName)

    @PostMapping("/objects/{object}/notification-rules")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @PathVariable("object") objectName: String,
        @RequestBody request: NotificationRuleDefinition
    ): NotificationRuleView = rules.create(objectName, request)

    @GetMapping("/objects/{object}/notification-rules/{name}")
    suspend fun get(
        @PathVariable("object") objectName: String,
        @PathVariable name: String
    ): NotificationRuleView = rules.get(objectName, name)

    @PutMapping("/objects/{object}/notification-rules/{name}")
    suspend fun replace(
        @PathVariable("object") objectName: String,
        @PathVariable name: String,
        @RequestBody request: NotificationRuleDefinition
    ): NotificationRuleView = rules.replace(objectName, name, request)

    @DeleteMapping("/objects/{object}/notification-rules/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable("object") objectName: String,
        @PathVariable name: String
    ) = rules.delete(objectName, name)

    // {created, updated, reopened, resolved}
    @PostMapping("/objects/{object}/notification-rules/{name}/run")
    suspend fun run(
        @PathVariable("object") objectName: String,
        @PathVariable name: String
    ): ReconcileResult = rules.run(objectName, name)
}
