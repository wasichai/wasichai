package wasichai.core.metadata

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

// a question about the caller, so it sits beside /api/auth/me. metadata owns it: the answer is keyed by object.
@RestController
class CallerPermissionsController(
    private val permissions: CallerPermissionsService
) {
    @GetMapping("/api/auth/me/permissions")
    suspend fun mine(): CallerPermissionsResponse = permissions.ofCaller()
}
