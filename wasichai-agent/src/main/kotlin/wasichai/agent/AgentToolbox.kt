package wasichai.agent

import com.embabel.agent.api.annotation.LlmTool

/**
 * The nine read-only tools, in the shape Embabel understands.
 *
 * Embabel discovers tools by reflecting over the methods of a *tool object* handed to a prompt
 * runner (`Tool.fromInstance`, which keeps every function annotated with [LlmTool] and builds the
 * JSON schema from its Kotlin signature). So this class is not a Spring bean: one is built per
 * question, around the [AgentRun] that holds the asking user's security context, and handed to the
 * prompt runner inside the action. That is deliberate. A singleton would have to find the caller
 * from somewhere at call time, and "somewhere" is exactly how an agent ends up querying as nobody.
 *
 * Every method does the same three things: hop back into the caller's coroutine, delegate to
 * [AgentTools] (which only ever calls Wasichai services, never SQL), and write down the step.
 */
class AgentToolbox(
    private val tools: AgentTools,
    private val run: AgentRun
) {
    @LlmTool(name = AgentToolCatalog.LIST_OBJECTS, description = AgentToolCatalog.LIST_OBJECTS_DESCRIPTION)
    fun listObjects(): String = call(AgentToolCatalog.LIST_OBJECTS, emptyMap())

    @LlmTool(name = AgentToolCatalog.DESCRIBE_OBJECT, description = AgentToolCatalog.DESCRIBE_OBJECT_DESCRIPTION)
    fun describeObject(
        @LlmTool.Param(description = AgentToolCatalog.OBJECT_ARGUMENT) `object`: String
    ): String = call(AgentToolCatalog.DESCRIBE_OBJECT, mapOf("object" to `object`))

    @LlmTool(name = AgentToolCatalog.QUERY_RECORDS, description = AgentToolCatalog.QUERY_RECORDS_DESCRIPTION)
    fun queryRecords(
        @LlmTool.Param(description = AgentToolCatalog.OBJECT_ARGUMENT) `object`: String,
        @LlmTool.Param(description = AgentToolCatalog.SEARCH_ARGUMENT, required = false) search: String? = null,
        @LlmTool.Param(description = AgentToolCatalog.FILTERS_ARGUMENT, required = false) filters: Map<String, String>? = null,
        @LlmTool.Param(description = AgentToolCatalog.SORT_ARGUMENT, required = false) sort: String? = null,
        @LlmTool.Param(description = AgentToolCatalog.DIRECTION_ARGUMENT, required = false) direction: String? = null,
        @LlmTool.Param(description = AgentToolCatalog.LIMIT_ARGUMENT, required = false) limit: Int? = null,
        @LlmTool.Param(description = AgentToolCatalog.BBOX_ARGUMENT, required = false) bbox: String? = null
    ): String =
        call(
            AgentToolCatalog.QUERY_RECORDS,
            mapOf(
                "object" to `object`,
                "search" to search,
                "filters" to filters,
                "sort" to sort,
                "direction" to direction,
                "limit" to limit,
                "bbox" to bbox
            )
        )

    @LlmTool(name = AgentToolCatalog.GET_RECORD, description = AgentToolCatalog.GET_RECORD_DESCRIPTION)
    fun getRecord(
        @LlmTool.Param(description = AgentToolCatalog.OBJECT_ARGUMENT) `object`: String,
        @LlmTool.Param(description = AgentToolCatalog.ID_ARGUMENT) id: String
    ): String = call(AgentToolCatalog.GET_RECORD, mapOf("object" to `object`, "id" to id))

    @LlmTool(name = AgentToolCatalog.COUNT_RECORDS, description = AgentToolCatalog.COUNT_RECORDS_DESCRIPTION)
    fun countRecords(
        @LlmTool.Param(description = AgentToolCatalog.OBJECT_ARGUMENT) `object`: String,
        @LlmTool.Param(description = AgentToolCatalog.SEARCH_ARGUMENT, required = false) search: String? = null,
        @LlmTool.Param(description = AgentToolCatalog.FILTERS_ARGUMENT, required = false) filters: Map<String, String>? = null
    ): String =
        call(
            AgentToolCatalog.COUNT_RECORDS,
            mapOf("object" to `object`, "search" to search, "filters" to filters)
        )

    @LlmTool(name = AgentToolCatalog.LIST_RELATIONSHIPS, description = AgentToolCatalog.LIST_RELATIONSHIPS_DESCRIPTION)
    fun listRelationships(
        @LlmTool.Param(description = AgentToolCatalog.OBJECT_ARGUMENT) `object`: String
    ): String = call(AgentToolCatalog.LIST_RELATIONSHIPS, mapOf("object" to `object`))

    @LlmTool(name = AgentToolCatalog.RELATED_RECORDS, description = AgentToolCatalog.RELATED_RECORDS_DESCRIPTION)
    fun relatedRecords(
        @LlmTool.Param(description = AgentToolCatalog.OBJECT_ARGUMENT) `object`: String,
        @LlmTool.Param(description = AgentToolCatalog.ID_ARGUMENT) id: String,
        @LlmTool.Param(description = AgentToolCatalog.RELATIONSHIP_ARGUMENT) relationship: String,
        @LlmTool.Param(description = AgentToolCatalog.LIMIT_ARGUMENT, required = false) limit: Int? = null,
        @LlmTool.Param(description = AgentToolCatalog.RELATED_DIRECTION_ARGUMENT, required = false) direction: String? = null
    ): String =
        call(
            AgentToolCatalog.RELATED_RECORDS,
            mapOf("object" to `object`, "id" to id, "relationship" to relationship, "limit" to limit, "direction" to direction)
        )

    @LlmTool(name = AgentToolCatalog.RECORD_HISTORY, description = AgentToolCatalog.RECORD_HISTORY_DESCRIPTION)
    fun recordHistory(
        @LlmTool.Param(description = AgentToolCatalog.OBJECT_ARGUMENT) `object`: String,
        @LlmTool.Param(description = AgentToolCatalog.ID_ARGUMENT) id: String,
        @LlmTool.Param(description = AgentToolCatalog.HISTORY_LIMIT_ARGUMENT, required = false) limit: Int? = null
    ): String = call(AgentToolCatalog.RECORD_HISTORY, mapOf("object" to `object`, "id" to id, "limit" to limit))

    @LlmTool(
        name = AgentToolCatalog.AVAILABLE_TRANSITIONS,
        description = AgentToolCatalog.AVAILABLE_TRANSITIONS_DESCRIPTION
    )
    fun availableTransitions(
        @LlmTool.Param(description = AgentToolCatalog.OBJECT_ARGUMENT) `object`: String,
        @LlmTool.Param(description = AgentToolCatalog.ID_ARGUMENT) id: String
    ): String = call(AgentToolCatalog.AVAILABLE_TRANSITIONS, mapOf("object" to `object`, "id" to id))

    // the whole security story of the AI phase is these three lines
    private fun call(
        name: String,
        input: Map<String, Any?>
    ): String {
        // an argument the model left out is not an argument: drop it so the defaults apply
        val arguments = input.filterValues { it != null }
        val result = run.asCaller { tools.invoke(name, arguments) }
        run.record(AgentStep(name, arguments, result.summary))
        return result.json
    }
}
