package wasichai.gis

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// map views are named per object, so they hang off the object; the flat list feeds the nav page
@RestController
@RequestMapping("/api/gis")
class MapViewController(
    private val views: MapViewService
) {
    @GetMapping("/map-views")
    suspend fun all(): List<MapViewResponse> = views.all()

    @GetMapping("/objects/{object}/map-views")
    suspend fun list(
        @PathVariable("object") objectName: String
    ): List<MapViewResponse> = views.list(objectName)

    @PostMapping("/objects/{object}/map-views")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @PathVariable("object") objectName: String,
        @RequestBody request: CreateMapViewRequest
    ): MapViewResponse = views.create(objectName, request)

    @GetMapping("/objects/{object}/map-views/{name}")
    suspend fun get(
        @PathVariable("object") objectName: String,
        @PathVariable name: String
    ): MapViewResponse = views.byName(objectName, name)

    @PutMapping("/objects/{object}/map-views/{name}")
    suspend fun update(
        @PathVariable("object") objectName: String,
        @PathVariable name: String,
        @RequestBody request: UpdateMapViewRequest
    ): MapViewResponse = views.update(objectName, name, request)

    @DeleteMapping("/objects/{object}/map-views/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable("object") objectName: String,
        @PathVariable name: String
    ) = views.delete(objectName, name)
}
