package wasichai.core.metadata

import wasichai.core.common.ValidationException

// how one field type lives in postgres and in the api. core ships the scalar ones; a module adds
// a type by declaring a bean of this. every hook has the core behaviour as its default. ADR-0025.
interface FieldTypeHandler {
    val type: FieldType

    // ---- metadata ----

    // custom_fields columns this type owns (its module's migration creates them) and the java type
    // a null is bound as. every field row carries them, null when the field is of another type.
    val attributeColumns: Map<String, Class<*>> get() = emptyMap()

    // checks the type-specific part of a new field. returns what goes in attributeColumns.
    fun attributesOf(
        fieldName: String,
        request: FieldRequest
    ): Map<String, Any?> = emptyMap()

    // refuses a change that means nothing for this type
    fun checkUpdate(
        field: CustomField,
        request: UpdateFieldRequest
    ) = Unit

    // json properties added to EVERY field response, not only to this type's fields
    fun fieldProperties(field: CustomField): Map<String, Any?> = emptyMap()

    // json properties added to every object response
    fun objectProperties(definition: ObjectDefinition): Map<String, Any?> = emptyMap()

    // ---- ddl ----

    // postgres column type. may read the field's attributes.
    fun columnType(field: CustomField): String

    // statements run once the column exists. `table` is already qualified and quoted.
    fun indexes(
        obj: CustomObject,
        table: String,
        field: CustomField
    ): List<String> = emptyList()

    // ---- records ----

    // null: the value travels in "attributes". otherwise the payload section it travels in. a field
    // in a section is written only when its key is sent; null clears it.
    val section: String? get() = null

    // what a section key naming no field of this type answers
    fun unknownSectionKey(
        key: String,
        definition: ObjectDefinition
    ): ValidationException = ValidationException("Unknown field '$key'", key, "is not a field of '${definition.obj.name}'")

    // true: ?q= searches this column with ILIKE
    val textLike: Boolean get() = false

    // json value -> bound value, validated. the only place raw input becomes a parameter.
    fun toDatabase(
        field: CustomField,
        value: Any?
    ): Any?

    // the class a null of this type is bound as
    fun javaType(field: CustomField): Class<*>

    // sql for the bound value. `parameter` comes without the colon.
    fun bindExpression(
        field: CustomField,
        parameter: String
    ): String = ":$parameter"

    // select-list item. `column` is already quoted. core does NOT append an alias: the default
    // below just returns `column`, which already reads back under readName(field) (the column
    // name). A handler that rewrites the expression (not a bare column read) must add its own
    // ` AS ${SqlIdentifier.quote(readName(field))}` so the row still reads back under that name.
    fun select(
        field: CustomField,
        column: String
    ): String = column

    fun readName(field: CustomField): String = field.columnName

    // db value -> json friendly
    fun fromDatabase(
        field: CustomField,
        value: Any?
    ): Any?

    // non-null: the type can be neither filtered on nor sorted by, and this is what the caller hears
    fun rejectFilterOrSort(field: CustomField): ValidationException? = null
}
