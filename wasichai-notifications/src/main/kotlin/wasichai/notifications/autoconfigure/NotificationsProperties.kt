package wasichai.notifications.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.ZoneId

@ConfigurationProperties("wasichai.notifications")
data class NotificationsProperties(
    // false: no notifications beans, routes, loop, stream or migration
    val enabled: Boolean = true,
    // how often the loop looks for due sources. zero turns the loop off (tests).
    val tick: Duration = Duration.ofSeconds(30),
    // how often date rules run
    val ruleInterval: Duration = Duration.ofMinutes(15),
    // cap per rule and organization: one rule must not flood an inbox
    val ruleMaxNotifications: Int = 100,
    // resolved or expired notifications older than this are purged
    val retention: Duration = Duration.ofDays(90),
    // the furthest a snooze may reach
    val snoozeMax: Duration = Duration.ofDays(30),
    // false: no LISTEN connection. streams live on the refresh alone.
    val listen: Boolean = true,
    // every stream recomputes this often, whatever it heard
    val streamRefresh: Duration = Duration.ofSeconds(60),
    // a comment line keeps proxies from closing an idle stream
    val streamHeartbeat: Duration = Duration.ofSeconds(25),
    // signals closer than this cost one recompute
    val streamDebounce: Duration = Duration.ofMillis(500),
    // the zone date rules count days in. null: the app's unique Clock bean's zone, else the system's.
    val zone: ZoneId? = null,
    // how {{date}} and DATE values print in rule templates
    val datePattern: String = "dd/MM/yyyy"
) {
    init {
        require(ruleMaxNotifications in 1..MAX_RULE_NOTIFICATIONS) {
            "wasichai.notifications.rule-max-notifications must be between 1 and $MAX_RULE_NOTIFICATIONS, was $ruleMaxNotifications"
        }
    }

    companion object {
        const val MAX_RULE_NOTIFICATIONS = 200
    }
}
