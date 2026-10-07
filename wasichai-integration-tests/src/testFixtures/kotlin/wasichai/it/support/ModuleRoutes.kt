package wasichai.it.support

import org.springframework.http.HttpMethod

// every route a module adds, by module: the original's 50 (AllModulesWiringTest.LEGACY_MODULE_ROUTES) plus the
// ones it never had (ADDED_MODULE_ROUTES, ADR-031). ModuleRoutesTest keeps them equal. the module matrix probes
// the ones an app has not got: each must be a 404 (ADR-031 D1).
object ModuleRoutes {
    const val PROBE_ID = "00000000-0000-0000-0000-00000000abcd"

    val byModule: Map<String, List<String>> =
        mapOf(
            "views" to
                listOf(
                    "GET /api/metadata/objects/{object}/views",
                    "GET /api/objects/{object}/views",
                    "POST /api/objects/{object}/views",
                    "GET /api/objects/{object}/views/{name}",
                    "PUT /api/objects/{object}/views/{name}",
                    "DELETE /api/objects/{object}/views/{name}"
                ),
            "forms" to
                listOf(
                    "GET /api/metadata/objects/{object}/forms",
                    "GET /api/objects/{object}/forms",
                    "POST /api/objects/{object}/forms",
                    "GET /api/objects/{object}/forms/{name}",
                    "PUT /api/objects/{object}/forms/{name}",
                    "DELETE /api/objects/{object}/forms/{name}"
                ),
            "pages" to
                listOf(
                    "GET /api/metadata/objects/{object}/pages",
                    "GET /api/metadata/page-templates",
                    "GET /api/objects/{object}/pages/{kind}",
                    "GET /api/pages",
                    "POST /api/pages",
                    "GET /api/pages/{name}",
                    "PUT /api/pages/{name}",
                    "DELETE /api/pages/{name}"
                ),
            "workflow" to
                listOf(
                    "GET /api/objects/{object}/workflow",
                    "PUT /api/objects/{object}/workflow",
                    "DELETE /api/objects/{object}/workflow",
                    "GET /api/objects/{object}/records/{id}/transitions",
                    "POST /api/objects/{object}/records/{id}/transitions/{name}"
                ),
            "automation" to
                listOf(
                    "GET /api/automation-runs",
                    "GET /api/objects/{object}/automations",
                    "POST /api/objects/{object}/automations",
                    "GET /api/objects/{object}/automations/{name}",
                    "PUT /api/objects/{object}/automations/{name}",
                    "DELETE /api/objects/{object}/automations/{name}",
                    "GET /api/objects/{object}/automations/{name}/runs"
                ),
            "documents" to
                listOf(
                    "GET /api/documents/{id}",
                    "GET /api/objects/{object}/document-types",
                    "POST /api/objects/{object}/document-types",
                    "GET /api/objects/{object}/document-types/{name}",
                    "PUT /api/objects/{object}/document-types/{name}",
                    "DELETE /api/objects/{object}/document-types/{name}",
                    "GET /api/objects/{object}/records/{id}/documents",
                    "POST /api/objects/{object}/records/{id}/documents/{type}"
                ),
            "notifications" to
                listOf(
                    "GET /api/notifications",
                    "POST /api/notifications",
                    "GET /api/notifications/{id}",
                    "PUT /api/notifications/{id}",
                    "DELETE /api/notifications/{id}",
                    "GET /api/auth/me/notifications",
                    "GET /api/auth/me/notifications/summary",
                    "GET /api/auth/me/notifications/stream",
                    "POST /api/auth/me/notifications/{id}/read",
                    "POST /api/auth/me/notifications/{id}/dismiss",
                    "POST /api/auth/me/notifications/{id}/snooze",
                    "POST /api/auth/me/notifications/read-all",
                    "GET /api/notification-rules",
                    "GET /api/objects/{object}/notification-rules",
                    "POST /api/objects/{object}/notification-rules",
                    "GET /api/objects/{object}/notification-rules/{name}",
                    "PUT /api/objects/{object}/notification-rules/{name}",
                    "DELETE /api/objects/{object}/notification-rules/{name}",
                    "POST /api/objects/{object}/notification-rules/{name}/run"
                ),
            "gis" to
                listOf(
                    "GET /api/gis/layers",
                    "POST /api/gis/layers/{object}",
                    "DELETE /api/gis/layers/{object}",
                    "POST /api/gis/layers/{object}/{geometry}",
                    "DELETE /api/gis/layers/{object}/{geometry}",
                    "GET /api/gis/services",
                    "GET /api/gis/objects/{object}/features",
                    "GET /api/gis/objects/{object}/features/{id}"
                ),
            "agent" to
                listOf(
                    "GET /api/agent/status",
                    "POST /api/agent/ask"
                )
        )

    val all: List<String> get() = byModule.values.flatten()

    fun absentFrom(installed: Set<String>): List<String> = byModule.filterKeys { it !in installed }.values.flatten()

    // a concrete request for a route pattern: the caller's existing object, made-up ids and names.
    // an absent route is a 404 whatever the values; a present one must not be judged by these.
    fun probe(
        route: String,
        objectName: String
    ): Pair<HttpMethod, String> {
        val (verb, pattern) = route.split(" ", limit = 2)
        val uri =
            pattern
                .replace("{object}", objectName)
                .replace("{id}", PROBE_ID)
                .replace("{name}", "probe")
                .replace("{kind}", "record-detail")
                .replace("{type}", "probe")
                .replace("{geometry}", "geom")
        return HttpMethod.valueOf(verb) to uri
    }
}
