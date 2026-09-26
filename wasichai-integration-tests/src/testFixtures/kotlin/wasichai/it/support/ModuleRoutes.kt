package wasichai.it.support

import org.springframework.http.HttpMethod

// every route a module adds, by module: the same 50 as AllModulesWiringTest.LEGACY_MODULE_ROUTES
// (ModuleRoutesTest keeps the two equal). the module matrix probes the ones an app has not got.
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
