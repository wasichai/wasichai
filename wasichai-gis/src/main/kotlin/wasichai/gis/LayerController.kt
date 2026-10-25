package wasichai.gis

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// the only endpoints in wasichai that touch geoserver (style-classes aside). everything else works with it stopped.
@RestController
@RequestMapping("/api/gis")
class LayerController(
    private val layers: LayerService,
    private val styles: StyleClassService
) {
    @GetMapping("/layers")
    suspend fun list(): List<LayerStatusResponse> = layers.status()

    // without a geometry it means the first one, so links saved before layers were a pair still work
    @PostMapping("/layers/{object}")
    suspend fun publish(
        @PathVariable("object") objectName: String,
        @RequestBody(required = false) request: PublishLayerRequest?
    ): LayerStatusResponse = layers.publish(objectName, null, request?.style)

    @PostMapping("/layers/{object}/{geometry}")
    suspend fun publishGeometry(
        @PathVariable("object") objectName: String,
        @PathVariable("geometry") geometryName: String,
        @RequestBody(required = false) request: PublishLayerRequest?
    ): LayerStatusResponse = layers.publish(objectName, geometryName, request?.style)

    @DeleteMapping("/layers/{object}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun unpublish(
        @PathVariable("object") objectName: String
    ) {
        layers.unpublish(objectName, null)
    }

    @DeleteMapping("/layers/{object}/{geometry}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun unpublishGeometry(
        @PathVariable("object") objectName: String,
        @PathVariable("geometry") geometryName: String
    ) {
        layers.unpublish(objectName, geometryName)
    }

    // the classes a style resolves to on this tenant's data: what the map paints and the legend lists
    @PostMapping("/objects/{object}/style-classes")
    suspend fun styleClasses(
        @PathVariable("object") objectName: String,
        @RequestBody style: AttributeStyle
    ): StyleClasses = styles.classes(objectName, style)

    @GetMapping("/services")
    suspend fun services(): GeoServerServicesResponse = layers.services()
}
