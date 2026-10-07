package wasichai.core.identity

import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import wasichai.core.platform.bindNullable
import java.util.UUID

data class UserPreferences(
    val theme: String = DEFAULT_THEME,
    val locale: String? = null
) {
    companion object {
        const val DEFAULT_THEME = "system"
    }
}

class UserPreferencesRepository(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas
) {
    suspend fun find(userId: UUID): UserPreferences? =
        db
            .sql("SELECT theme, locale FROM ${schemas.metadata}.user_preferences WHERE user_id = :userId")
            .bind("userId", userId)
            .map { row, _ -> UserPreferences(Rows.string(row, "theme"), Rows.stringOrNull(row, "locale")) }
            .one()
            .awaitFirstOrNull()

    suspend fun save(
        userId: UUID,
        preferences: UserPreferences
    ): UserPreferences {
        val spec =
            db
                .sql(
                    "INSERT INTO ${schemas.metadata}.user_preferences (user_id, theme, locale) VALUES (:userId, :theme, :locale) " +
                        "ON CONFLICT (user_id) DO UPDATE SET theme = EXCLUDED.theme, locale = EXCLUDED.locale, updated_at = now() " +
                        "RETURNING theme, locale"
                ).bind("userId", userId)
                .bind("theme", preferences.theme)
        val bound = spec.bindNullable("locale", preferences.locale)
        return bound
            .map { row, _ -> UserPreferences(Rows.string(row, "theme"), Rows.stringOrNull(row, "locale")) }
            .one()
            .awaitFirst()
    }
}

class UserPreferencesService(
    private val repository: UserPreferencesRepository
) {
    suspend fun get(userId: UUID): UserPreferences = repository.find(userId) ?: UserPreferences()

    // a map, not a dto: "locale": null clears, a missing key keeps
    suspend fun update(
        userId: UUID,
        body: Map<String, Any?>
    ): UserPreferences {
        val violations = mutableListOf<FieldViolation>()
        body.keys.filter { it !in FIELDS }.forEach { violations += FieldViolation(it, "is not a preference") }
        val theme = body["theme"]
        if ("theme" in body && (theme !is String || !THEME.matches(theme))) violations += FieldViolation("theme", "must match ${THEME.pattern}")
        val locale = body["locale"]
        if (locale != null && (locale !is String || locale.length > 35 || !LOCALE.matches(locale))) {
            violations += FieldViolation("locale", "must be a BCP 47 language tag or null")
        }
        if (violations.isNotEmpty()) throw ValidationException("Invalid preferences", violations)

        val current = get(userId)
        val next =
            current.copy(
                theme = if ("theme" in body) theme as String else current.theme,
                locale = if ("locale" in body) locale as String? else current.locale
            )
        return repository.save(userId, next)
    }

    companion object {
        private val FIELDS = setOf("theme", "locale")
        private val THEME = Regex("^[a-z0-9-]{1,40}$")
        private val LOCALE = Regex("^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$")
    }
}

@RestController
@RequestMapping("/api/auth/me/preferences")
class UserPreferencesController(
    private val service: UserPreferencesService,
    private val currentUser: CurrentUser
) {
    @GetMapping
    suspend fun get(): UserPreferences = service.get(currentUser.require().userId)

    @PutMapping
    suspend fun update(
        @RequestBody body: Map<String, Any?>
    ): UserPreferences = service.update(currentUser.require().userId, body)
}
