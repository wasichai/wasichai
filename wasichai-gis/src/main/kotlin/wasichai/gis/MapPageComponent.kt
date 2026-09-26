package wasichai.gis

import org.springframework.core.annotation.Order
import wasichai.core.common.ValidationException
import wasichai.core.metadata.ObjectDefinition
import wasichai.pages.ComponentType
import wasichai.pages.GeneratedComponent
import wasichai.pages.PageComponent
import wasichai.pages.PageComponentProvider
import wasichai.pages.PageComponentRequest

// the MAP page component. pages placed it; what it means for an object is gis's to judge.
// ordered ahead of workflow's WORKFLOW component (200), so the "must be one of" list and the
// generated tab order match the original app, where MAP came before WORKFLOW.
@Order(100)
class MapPageComponent : PageComponentProvider {
    override val type = MAP

    override fun check(
        component: PageComponentRequest,
        definition: ObjectDefinition
    ) {
        if (definition.geometryFields.isEmpty()) {
            throw ValidationException("MAP on a non-spatial object", "components", "'${definition.obj.name}' has no geometry")
        }
        // naming none draws them all, which is what a one-shape object wants
        val geometry = component.geometry?.trim()?.ifBlank { null }
        if (geometry != null && definition.geometryFields.none { it.name == geometry }) {
            throw ValidationException("Unknown geometry '$geometry'", "components", "'${definition.obj.name}' has no geometry field '$geometry'")
        }
    }

    // a spatial object's generated page gets a map tab between details and related, as before
    override suspend fun generated(definition: ObjectDefinition): GeneratedComponent? =
        if (definition.geometryFields.isEmpty()) {
            null
        } else {
            GeneratedComponent(PageComponent(type = MAP, title = definition.obj.label), tab = MAP_TAB)
        }

    companion object {
        val MAP = ComponentType("MAP")

        // a key, not a word: the client translates it
        const val MAP_TAB = "MAP"
    }
}
