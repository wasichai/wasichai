package wasichai.gis

import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitExchange
import wasichai.core.common.WasichaiException

// geoserver is a downstream. its failures are 502, never our 500.
class GeoServerException(
    message: String
) : WasichaiException(HttpStatus.BAD_GATEWAY, message)

private const val BODY_EXCERPT = 300

// geoserver does not answer 409 on a duplicate feature type, it answers 500 with this in the body
private const val ALREADY_EXISTS = "already exists"

// thin reactive wrapper over geoserver's rest api. nothing else in wasichai talks to geoserver.
@Component
class GeoServerClient(
    private val properties: GeoServerProperties,
    /**
     * resolved datastore schema; exposed for diagnostics and tests.
     * datastore.schema, or wasichai.database.data-schema when the app never overrode it. resolved
     * once by the auto-config, so this class never has to know about WasichaiSchemas.
     */
    val schema: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // own builder: boot 4 does not hand out a WebClient.Builder bean, and we want no shared defaults anyway
    private val client =
        WebClient
            .builder()
            .defaultHeaders { it.setBasicAuth(properties.username, properties.password) }
            .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
            .build()

    private val base get() = properties.baseUrl
    private val workspace get() = properties.workspace
    private val datastore get() = properties.datastore.name

    suspend fun ensureWorkspace() {
        if (exists(GeoServerUrls.workspace(base, workspace), "workspace '$workspace'")) return
        post(
            GeoServerUrls.workspaces(base),
            GeoServerPayloads.workspace(workspace),
            "create workspace '$workspace'"
        )
    }

    suspend fun ensureDataStore() {
        if (dataStoreExists()) return
        post(
            GeoServerUrls.dataStores(base, workspace),
            GeoServerPayloads.dataStore(properties.datastore, schema),
            "create datastore '$datastore'"
        )
    }

    suspend fun dataStoreExists(): Boolean = exists(GeoServerUrls.dataStore(base, workspace, datastore), "datastore '$datastore'")

    suspend fun featureTypeExists(layerName: String): Boolean = exists(GeoServerUrls.featureType(base, workspace, datastore, layerName), "layer '$layerName'")

    suspend fun publishFeatureType(
        table: String,
        layerName: String,
        geometryColumn: String,
        geometryType: String,
        srid: Int,
        title: String,
        attributeColumns: List<String>
    ) {
        post(
            GeoServerUrls.featureTypes(base, workspace, datastore),
            GeoServerPayloads.featureType(schema, table, layerName, geometryColumn, geometryType, srid, title, attributeColumns),
            "publish layer '$layerName'"
        )
    }

    // recurse drops the layer that hangs off the feature type too
    suspend fun deleteFeatureType(layerName: String) {
        val url = "${GeoServerUrls.featureType(base, workspace, datastore, layerName)}?recurse=true"
        call("unpublish layer '$layerName'") {
            client.delete().uri(url).awaitExchange { response ->
                when {
                    response.statusCode().is2xxSuccessful -> drain(response)
                    // already gone is the state we wanted
                    response.statusCode().value() == 404 -> drain(response)
                    else -> throw failure("unpublish layer '$layerName'", response.statusCode(), drain(response))
                }
            }
        }
    }

    // one listing beats one HEAD per object. missing datastore means nothing is published yet.
    suspend fun publishedLayerNames(): Set<String> {
        if (!dataStoreExists()) return emptySet()
        val what = "list layers"
        val body: Map<*, *> =
            call(what) {
                client.get().uri(GeoServerUrls.featureTypes(base, workspace, datastore)).awaitExchange<Map<*, *>> { response ->
                    if (!response.statusCode().is2xxSuccessful) {
                        throw failure(what, response.statusCode(), drain(response))
                    }
                    response.bodyToMono(Map::class.java).awaitFirstOrNull() ?: emptyMap<String, Any>()
                }
            }
        // geoserver answers {"featureTypes":""} when the store holds nothing
        val featureTypes = body["featureTypes"] as? Map<*, *> ?: return emptySet()
        val entries = featureTypes["featureType"] as? List<*> ?: return emptySet()
        return entries.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }.toSet()
    }

    private suspend fun exists(
        url: String,
        what: String
    ): Boolean =
        call("check $what") {
            client.get().uri(url).awaitExchange { response ->
                when {
                    response.statusCode().is2xxSuccessful -> {
                        drain(response)
                        true
                    }
                    response.statusCode().value() == 404 -> {
                        drain(response)
                        false
                    }
                    else -> throw failure("check $what", response.statusCode(), drain(response))
                }
            }
        }

    private suspend fun post(
        url: String,
        payload: Map<String, Any>,
        what: String
    ) {
        call(what) {
            client
                .post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .awaitExchange { response ->
                    val body = drain(response)
                    when {
                        response.statusCode().is2xxSuccessful -> Unit
                        // racing with another publisher, or geoserver's odd 500 on a duplicate feature type
                        response.statusCode().value() == 409 || body.contains(ALREADY_EXISTS, ignoreCase = true) -> {
                            log.debug("geoserver reports it already has what we asked to {}", what)
                        }
                        else -> throw failure(what, response.statusCode(), body)
                    }
                }
        }
    }

    // any transport problem (geoserver down, dns, timeout) becomes the same clear 502
    private suspend fun <T> call(
        what: String,
        block: suspend () -> T
    ): T =
        try {
            block()
        } catch (ex: GeoServerException) {
            throw ex
        } catch (ex: Exception) {
            log.warn("GeoServer call failed: {}", what, ex)
            throw GeoServerException("GeoServer is unreachable, could not $what: ${ex.message}")
        }

    private fun failure(
        what: String,
        status: HttpStatusCode,
        body: String
    ): GeoServerException = GeoServerException("GeoServer refused to $what: HTTP ${status.value()} ${excerpt(body)}")

    // body must always be consumed or the connection leaks
    private suspend fun drain(response: ClientResponse): String = response.bodyToMono(String::class.java).awaitFirstOrNull().orEmpty()

    // geoserver answers html on some errors. flatten it, keep it short.
    private fun excerpt(body: String): String = body.replace(Regex("\\s+"), " ").trim().take(BODY_EXCERPT)
}
