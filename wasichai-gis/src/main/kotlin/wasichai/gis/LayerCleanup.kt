package wasichai.gis

import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.ObjectRemovalListener
import kotlin.time.Duration.Companion.seconds

// a layer whose table is gone answers every WMS request with an error. drop it with the object.
@Component
class LayerCleanup(
    private val client: GeoServerClient,
    private val properties: GeoServerProperties
) : ObjectRemovalListener {
    private val log = LoggerFactory.getLogger(javaClass)

    // every layer of the object, not one: a second one left behind answers each WMS call with an
    // error, which is what this class exists to prevent. the fields are gone by now, so the
    // published names are matched by prefix instead.
    override suspend fun objectRemoved(obj: CustomObject) {
        if (!properties.enabled) return
        // this runs inside the delete's transaction, so it may not wait on geoserver forever.
        // the object goes either way; a stale layer is republishable, a stuck DDL lock is not.
        val done =
            withTimeoutOrNull(UNPUBLISH_TIMEOUT) {
                runCatching {
                    client
                        .publishedLayerNames()
                        .filter { it == obj.physicalTable || it.startsWith("${obj.physicalTable}__") }
                        .forEach { client.deleteFeatureType(it) }
                }.onFailure { log.warn("could not unpublish the layers of {}: {}", obj.physicalTable, it.message) }
            }
        if (done == null) log.warn("geoserver did not answer in time; layers of {} may still be published", obj.physicalTable)
    }

    private companion object {
        val UNPUBLISH_TIMEOUT = 5.seconds
    }
}
