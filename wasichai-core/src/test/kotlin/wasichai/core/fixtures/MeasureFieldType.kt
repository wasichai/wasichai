package wasichai.core.fixtures

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordQueryContributor
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.FieldRequest
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldTypeHandler
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.metadata.UpdateFieldRequest
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.SqlIdentifier

// a made-up module type that uses every hook wasichai-gis needs, on plain postgres: its own
// custom_fields column, a payload section, select/bind sql, its own rules, json on every field and
// object, its own index and its own query parameter. if this works, the SPI is enough for GEOMETRY.
val MEASURE = FieldType("MEASURE")

class MeasureFieldType : FieldTypeHandler {
    override val type = MEASURE
    override val attributeColumns: Map<String, Class<*>> = mapOf("unit" to String::class.java)
    override val section = "measures"

    override fun attributesOf(
        fieldName: String,
        request: FieldRequest
    ): Map<String, Any?> {
        if (request.unique) throw ValidationException("Measure field '$fieldName' cannot be unique", "unique", "has no meaning on a measure")
        val unit = request.extensions["unit"] as? String ?: throw ValidationException("Measure field '$fieldName' has no unit", "unit", "is required")
        return mapOf("unit" to unit)
    }

    override fun checkUpdate(
        field: CustomField,
        request: UpdateFieldRequest
    ) {
        if (request.unique == true) throw ValidationException("Measure field '${field.name}' cannot be unique", "unique", "has no meaning on a measure")
    }

    override fun fieldProperties(field: CustomField): Map<String, Any?> =
        mapOf("measure" to if (field.type == MEASURE) mapOf("unit" to field.attributes["unit"]) else null)

    override fun objectProperties(definition: ObjectDefinition): Map<String, Any?> =
        mapOf("measure" to definition.fields.firstOrNull { it.type == MEASURE }?.let { mapOf("unit" to it.attributes["unit"]) })

    override fun columnType(field: CustomField) = "numeric"

    override fun indexes(
        obj: CustomObject,
        table: String,
        field: CustomField
    ): List<String> =
        listOf(
            "CREATE INDEX ${SqlIdentifier.quote(SqlIdentifier.indexName(obj.physicalTable, field.columnName, "mix"))} " +
                "ON $table (${SqlIdentifier.quote(field.columnName)})"
        )

    override fun unknownSectionKey(
        key: String,
        definition: ObjectDefinition
    ) = ValidationException("Unknown measure '$key'", key, "is not a measure of '${definition.obj.name}'")

    override fun toDatabase(
        field: CustomField,
        value: Any?
    ): Any? =
        when (value) {
            null -> null
            is Number -> value.toString()
            else -> throw ValidationException("Invalid measure", field.name, "must be a number")
        }

    override fun javaType(field: CustomField) = String::class.java

    // bound as text, cast in sql: the trick gis plays with geojson
    override fun bindExpression(
        field: CustomField,
        parameter: String
    ) = "CAST(:$parameter AS numeric)"

    override fun select(
        field: CustomField,
        column: String
    ) = "CAST($column AS text) AS ${SqlIdentifier.quote(readName(field))}"

    override fun readName(field: CustomField) = "${field.columnName}__txt"

    override fun fromDatabase(
        field: CustomField,
        value: Any?
    ): Any? = (value as String?)?.toBigDecimal()

    override fun rejectFilterOrSort(field: CustomField) =
        ValidationException("Cannot filter or sort by measure '${field.name}'", field.name, "use min_measure instead")
}

// ?min_measure=10: records whose first measure is at least 10. the gis bbox, in miniature.
class MinMeasureQuery : RecordQueryContributor {
    override val parameters = setOf("min_measure")

    override fun parse(params: Map<String, String>): RecordCriterion? {
        val raw = params["min_measure"] ?: return null
        val min = raw.toBigDecimalOrNull() ?: throw ValidationException("Invalid min_measure", "min_measure", "must be a number")
        return RecordCriterion { definition, bind ->
            val field =
                definition.fields.firstOrNull { it.type == MEASURE }
                    ?: throw ValidationException("Object '${definition.obj.name}' has no measure", "min_measure", "the object has no measure")
            "${SqlIdentifier.quote(field.columnName)} >= ${bind(min)}"
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class MeasureTestConfiguration {
    @Bean
    fun measureFieldType(): FieldTypeHandler = MeasureFieldType()

    @Bean
    fun minMeasureQuery(): RecordQueryContributor = MinMeasureQuery()

    @Bean
    fun measureMigration(): ModuleMigration = ModuleMigration("measure_test", "classpath:db/wasichai/measure-test", ModuleMigration.MODULE_ORDER)
}
