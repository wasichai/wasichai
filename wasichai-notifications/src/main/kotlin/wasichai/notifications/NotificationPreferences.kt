package wasichai.notifications

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.FieldViolation
import wasichai.core.common.ForbiddenException
import wasichai.core.common.ValidationException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser

// which kinds reach me on each delivery channel (ADR-060), the ADR-034 way: a map, a missing key keeps.
// the inbox is always on and is no key here. no row for a channel = every kind.
class NotificationPreferencesService(
    private val repository: DeliveryRepository,
    // the app's channel names
    private val channels: Set<String>
) {
    suspend fun get(user: AuthenticatedUser): Map<String, List<NotificationKind>> {
        val stored = repository.preferences(person(user).userId)
        return channels.sorted().associateWith { channel -> NotificationKind.entries.filter { it in (stored[channel] ?: NotificationKind.entries) } }
    }

    // {"email": ["ACTION", "WARNING"]}. [] stops the channel; a channel left out keeps what it had
    suspend fun update(
        user: AuthenticatedUser,
        body: Map<String, Any?>
    ): Map<String, List<NotificationKind>> {
        val person = person(user)
        val violations = mutableListOf<FieldViolation>()
        val parsed =
            body.mapNotNull { (channel, value) ->
                if (channel !in channels) {
                    violations += FieldViolation(channel, "is not a delivery channel of this app${known()}")
                    return@mapNotNull null
                }
                val kinds = kinds(value)
                if (kinds == null) {
                    violations += FieldViolation(channel, "must be a list of ${NotificationKind.entries.joinToString(", ")}")
                    return@mapNotNull null
                }
                channel to kinds
            }
        if (violations.isNotEmpty()) throw ValidationException("Invalid notification preferences", violations)
        parsed.forEach { (channel, kinds) -> repository.savePreference(person.userId, channel, kinds) }
        return get(person)
    }

    // a service account is nobody's recipient, so it has no preferences either
    private fun person(user: AuthenticatedUser): AuthenticatedUser {
        if (user.serviceAccount != null) throw ForbiddenException("A service account has no notifications")
        return user
    }

    private fun kinds(value: Any?): Set<NotificationKind>? {
        if (value !is List<*>) return null
        return value
            .map { kind ->
                (kind as? String)?.trim()?.let { name -> NotificationKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } } ?: return null
            }.toSet()
    }

    private fun known(): String = if (channels.isEmpty()) " (it has none)" else " (${channels.sorted().joinToString(", ")})"
}

@RestController
@RequestMapping("/api/auth/me/notification-preferences")
class NotificationPreferencesController(
    private val service: NotificationPreferencesService,
    private val currentUser: CurrentUser
) {
    @GetMapping
    suspend fun get(): Map<String, List<NotificationKind>> = service.get(currentUser.require())

    @PutMapping
    suspend fun update(
        @RequestBody body: Map<String, Any?>
    ): Map<String, List<NotificationKind>> = service.update(currentUser.require(), body)
}
