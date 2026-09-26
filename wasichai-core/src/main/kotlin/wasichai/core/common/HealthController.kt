package wasichai.core.common

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// same shape as before; the name is the app's (spring.application.name)
@RestController
@RequestMapping("/api/health")
class HealthController(
    private val applicationName: String
) {
    @GetMapping
    fun health(): Map<String, String> = mapOf("status" to "UP", "application" to applicationName)
}
