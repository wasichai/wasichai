package wasichai.gis

// geojson is the wire format for the UI. always EPSG:4326.
data class Feature(
    val id: String,
    val geometry: Map<String, Any?>?,
    val properties: Map<String, Any?>
) {
    val type: String = "Feature"
}

data class FeatureCollection(
    val features: List<Feature>
) {
    val type: String = "FeatureCollection"
}
