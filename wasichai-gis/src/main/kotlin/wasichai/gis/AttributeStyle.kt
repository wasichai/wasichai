package wasichai.gis

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import wasichai.core.common.ValidationException
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.ObjectDefinition
import java.util.Locale

enum class StyleType {
    CATEGORIES,
    QUANTILES;

    @JsonValue
    fun wire(): String = name.lowercase()

    companion object {
        @JsonCreator
        @JvmStatic
        fun parse(raw: String?): StyleType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw ValidationException(
                    "Unknown style type '${raw ?: ""}'",
                    "style.type",
                    "must be one of ${entries.joinToString(", ") { it.wire() }}"
                )
    }
}

// colour by one attribute. the same thing paints the map, the legend and the published SLD.
data class AttributeStyle(
    val by: String,
    val type: StyleType,
    val classes: Int? = null,
    val palette: List<String>? = null
)

// one bucket: a category has a value, a quantile a [min, max) range (the last one closed)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class StyleClass(
    val label: String,
    val color: String,
    val value: Any? = null,
    val min: Double? = null,
    val max: Double? = null
)

data class StyleClasses(
    val by: String,
    val type: StyleType,
    val classes: List<StyleClass>
)

// colorbrewer set3/ylorrd: readable on the osm basemap
object StylePalettes {
    val CATEGORICAL =
        listOf("#8dd3c7", "#ffffb3", "#bebada", "#fb8072", "#80b1d3", "#fdb462", "#b3de69", "#fccde5", "#d9d9d9", "#bc80bd")
    val SEQUENTIAL = listOf("#ffffcc", "#ffeda0", "#fed976", "#feb24c", "#fd8d3c", "#fc4e2a", "#e31a1c", "#bd0026", "#800026")

    // what the SLD paints a feature no class matches
    const val OTHER = "#9e9e9e"

    // n colours spread over the ramp, so 3 classes still go light to dark
    fun sequential(
        n: Int,
        ramp: List<String> = SEQUENTIAL
    ): List<String> = if (n <= 1) ramp.takeLast(1) else List(n) { ramp[it * (ramp.size - 1) / (n - 1)] }
}

const val DEFAULT_CLASSES = 5
const val MIN_CLASSES = 2
const val MAX_CLASSES = 9
const val MAX_CATEGORIES = 50

private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")
private val NUMERIC = setOf(FieldType.INTEGER, FieldType.DECIMAL)
private val CATEGORICAL = setOf(FieldType.ENUM, FieldType.TEXT, FieldType.BOOLEAN)

// the field a style colours by, or a 400 saying why it cannot. definition is already cut to what the caller reads.
fun AttributeStyle.validate(definition: ObjectDefinition): CustomField {
    val field =
        definition.fields.firstOrNull { it.name == by }
            ?: throw ValidationException("Unknown style field '$by'", "style.by", "'${definition.obj.name}' has no field '$by'")
    when (type) {
        StyleType.QUANTILES ->
            if (field.type !in NUMERIC) {
                throw ValidationException("Field '$by' cannot be split in quantiles", "style.by", "quantiles need an INTEGER or DECIMAL field")
            }
        StyleType.CATEGORIES ->
            if (field.type !in CATEGORICAL) {
                throw ValidationException("Field '$by' cannot be styled by category", "style.by", "categories need an ENUM, TEXT or BOOLEAN field")
            }
    }
    if (classes != null && classes !in MIN_CLASSES..MAX_CLASSES) {
        throw ValidationException("Invalid class count $classes", "style.classes", "must be between $MIN_CLASSES and $MAX_CLASSES")
    }
    palette?.let { colors ->
        if (colors.isEmpty()) throw ValidationException("Empty palette", "style.palette", "must list at least one colour")
        colors.firstOrNull { !HEX_COLOR.matches(it) }?.let {
            throw ValidationException("Invalid colour '$it'", "style.palette", "must be #rrggbb")
        }
    }
    return field
}

// pure: the values come from the database, the colours from here
object StyleClassifier {
    fun categories(
        values: List<Any>,
        palette: List<String>?
    ): List<StyleClass> {
        val colors = palette ?: StylePalettes.CATEGORICAL
        return values.mapIndexed { index, value -> StyleClass(value.toString(), colors[index % colors.size], value = value) }
    }

    // breaks are the class edges, min first and max last. equal edges collapse, a column of one value is one class.
    fun quantiles(
        breaks: List<Double>,
        palette: List<String>?
    ): List<StyleClass> {
        val edges = breaks.distinct()
        if (edges.isEmpty()) return emptyList()
        if (edges.size == 1) return listOf(StyleClass(label(edges[0]), (palette ?: StylePalettes.sequential(1)).last(), min = edges[0], max = edges[0]))
        val n = edges.size - 1
        val colors = palette?.let { p -> List(n) { p[it % p.size] } } ?: StylePalettes.sequential(n)
        return List(n) { i -> StyleClass("${label(edges[i])} – ${label(edges[i + 1])}", colors[i], min = edges[i], max = edges[i + 1]) }
    }

    // fractions handed to percentile_cont: 0, 1/n, ..., 1
    fun fractions(classes: Int): List<Double> = List(classes + 1) { it.toDouble() / classes }

    private fun label(value: Double): String =
        if (value == Math.floor(value) &&
            !value.isInfinite()
        ) {
            value.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.2f", value)
        }
}
