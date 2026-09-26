package wasichai.core.platform

// the two schema names every sql string uses. checked once here, so interpolating them is safe.
class WasichaiSchemas(
    metadata: String,
    data: String
) {
    // metadata + identity, owned by flyway. sql writes "${schemas.metadata}.<table>" with a literal table
    // name: the schema is checked here once, so there is nothing left to quote. no metadataTable() helper.
    val metadata: String = safe(metadata, "metadata-schema")

    // one physical table per custom object, built at runtime. ADR-004.
    val data: String = safe(data, "data-schema")

    init {
        require(this.metadata != this.data) { "wasichai.database.metadata-schema and data-schema must differ" }
    }

    fun dataTable(table: String): String = SqlIdentifier.qualify(data, table)

    companion object {
        private val SAFE = Regex("^[a-z][a-z0-9_]{0,62}$")

        fun of(properties: WasichaiDatabaseProperties): WasichaiSchemas = WasichaiSchemas(properties.metadataSchema, properties.dataSchema)

        private fun safe(
            name: String,
            property: String
        ): String {
            require(SAFE.matches(name)) { "wasichai.database.$property '$name' must match ${SAFE.pattern}" }
            return name
        }
    }
}
