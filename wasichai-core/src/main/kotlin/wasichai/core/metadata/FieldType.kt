package wasichai.core.metadata

// a field type is a name. core knows the twelve below; a module adds more with a FieldTypeHandler.
// parsing has to know what is installed, so it lives in FieldTypeRegistry.
data class FieldType(
    val name: String
) {
    override fun toString(): String = name

    companion object {
        val TEXT = FieldType("TEXT")
        val LONG_TEXT = FieldType("LONG_TEXT")
        val INTEGER = FieldType("INTEGER")
        val DECIMAL = FieldType("DECIMAL")
        val BOOLEAN = FieldType("BOOLEAN")
        val DATE = FieldType("DATE")
        val DATETIME = FieldType("DATETIME")
        val ENUM = FieldType("ENUM")
        val EMAIL = FieldType("EMAIL")
        val URL = FieldType("URL")
        val UUID = FieldType("UUID")
        val RELATION = FieldType("RELATION")
    }
}
