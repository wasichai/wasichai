package wasichai.gis

// pure, like GeoServerPayloads: the SLD a styled layer is published with. one rule per class, the rest grey.
object GeoServerStyles {
    const val SLD_CONTENT_TYPE = "application/vnd.ogc.sld+xml"

    // geoserver's built-in style for a geometry kind, what a layer goes back to when its style is dropped
    fun generic(geometryType: GeometryType?): String =
        when (geometryType) {
            GeometryType.POINT, GeometryType.MULTIPOINT -> "point"
            GeometryType.LINESTRING, GeometryType.MULTILINESTRING -> "line"
            else -> "polygon"
        }

    fun sld(
        layerName: String,
        geometryType: GeometryType?,
        style: StyleClasses
    ): String {
        val property = escape(style.by)
        val rules =
            style.classes.joinToString(
                ""
            ) { rule(it.label, filter(property, style.type, it, it == style.classes.last()), symbolizer(geometryType, it.color)) } +
                "<Rule><Name>other</Name><ElseFilter/>${symbolizer(geometryType, StylePalettes.OTHER)}</Rule>"
        return """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<StyledLayerDescriptor version="1.0.0" xmlns="http://www.opengis.net/sld" xmlns:ogc="http://www.opengis.net/ogc" """ +
            """xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""" +
            "<NamedLayer><Name>${escape(layerName)}</Name><UserStyle><Title>${escape(layerName)} by $property</Title>" +
            "<FeatureTypeStyle>$rules</FeatureTypeStyle></UserStyle></NamedLayer></StyledLayerDescriptor>"
    }

    private fun rule(
        label: String,
        filter: String,
        symbolizer: String
    ): String = "<Rule><Name>${escape(label)}</Name><Title>${escape(label)}</Title>$filter$symbolizer</Rule>"

    // the last quantile is closed so the maximum itself is painted
    private fun filter(
        property: String,
        type: StyleType,
        style: StyleClass,
        last: Boolean
    ): String {
        val name = "<ogc:PropertyName>$property</ogc:PropertyName>"
        return when (type) {
            StyleType.CATEGORIES ->
                "<ogc:Filter><ogc:PropertyIsEqualTo>$name<ogc:Literal>${escape(style.value.toString())}</ogc:Literal></ogc:PropertyIsEqualTo></ogc:Filter>"
            StyleType.QUANTILES -> {
                val upper = if (last) "PropertyIsLessThanOrEqualTo" else "PropertyIsLessThan"
                "<ogc:Filter><ogc:And>" +
                    "<ogc:PropertyIsGreaterThanOrEqualTo>$name<ogc:Literal>${style.min}</ogc:Literal></ogc:PropertyIsGreaterThanOrEqualTo>" +
                    "<ogc:$upper>$name<ogc:Literal>${style.max}</ogc:Literal></ogc:$upper>" +
                    "</ogc:And></ogc:Filter>"
            }
        }
    }

    private fun symbolizer(
        geometryType: GeometryType?,
        color: String
    ): String =
        when (geometryType) {
            GeometryType.POINT, GeometryType.MULTIPOINT ->
                "<PointSymbolizer><Graphic><Mark><WellKnownName>circle</WellKnownName>" +
                    "<Fill>${css("fill", color)}</Fill><Stroke>${css("stroke", "#ffffff")}${css("stroke-width", "1")}</Stroke>" +
                    "</Mark><Size>10</Size></Graphic></PointSymbolizer>"
            GeometryType.LINESTRING, GeometryType.MULTILINESTRING ->
                "<LineSymbolizer><Stroke>${css("stroke", color)}${css("stroke-width", "2")}</Stroke></LineSymbolizer>"
            else ->
                "<PolygonSymbolizer><Fill>${css("fill", color)}${css("fill-opacity", "0.6")}</Fill>" +
                    "<Stroke>${css("stroke", darker(color))}${css("stroke-width", "1")}</Stroke></PolygonSymbolizer>"
        }

    private fun css(
        name: String,
        value: String
    ): String = """<CssParameter name="$name">${escape(value)}</CssParameter>"""

    // the outline a third darker than the fill, so neighbours of one class still read apart
    fun darker(color: String): String {
        val rgb = color.removePrefix("#").chunked(2).map { (it.toInt(16) * 2 / 3).coerceIn(0, 255) }
        return rgb.joinToString("", prefix = "#") { "%02x".format(it) }
    }

    fun escape(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
