package wasichai.core.metadata

// a type that is one plain postgres column. storage rules are FieldValueCodec's.
class ScalarFieldType(
    override val type: FieldType,
    private val column: String,
    override val textLike: Boolean = false
) : FieldTypeHandler {
    override fun columnType(field: CustomField): String = column

    override fun toDatabase(
        field: CustomField,
        value: Any?
    ): Any? = FieldValueCodec.toDatabase(field, value)

    override fun javaType(field: CustomField): Class<*> = FieldValueCodec.javaType(field.type)

    override fun fromDatabase(
        field: CustomField,
        value: Any?
    ): Any? = FieldValueCodec.fromDatabase(value)
}

// the types core knows, in the order the api lists them
object ScalarFieldTypes {
    val ALL: List<FieldTypeHandler> =
        listOf(
            ScalarFieldType(FieldType.TEXT, "text", textLike = true),
            ScalarFieldType(FieldType.LONG_TEXT, "text", textLike = true),
            ScalarFieldType(FieldType.INTEGER, "bigint"),
            ScalarFieldType(FieldType.DECIMAL, "numeric"),
            ScalarFieldType(FieldType.BOOLEAN, "boolean"),
            ScalarFieldType(FieldType.DATE, "date"),
            ScalarFieldType(FieldType.DATETIME, "timestamptz"),
            ScalarFieldType(FieldType.ENUM, "text", textLike = true),
            ScalarFieldType(FieldType.EMAIL, "text", textLike = true),
            ScalarFieldType(FieldType.URL, "text", textLike = true),
            ScalarFieldType(FieldType.UUID, "uuid"),
            ScalarFieldType(FieldType.RELATION, "uuid")
        )
}
