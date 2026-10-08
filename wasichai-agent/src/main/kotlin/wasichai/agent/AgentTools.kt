package wasichai.agent

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import wasichai.core.audit.AuditQueryService
import wasichai.core.common.PageRequest
import wasichai.core.common.ValidationException
import wasichai.core.common.WasichaiException
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordQueryParser
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.data.RelatedRecordService
import wasichai.core.data.toResponse
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataMapper
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.RelationshipDirection
import wasichai.core.metadata.RelationshipService

// the payload section wasichai-gis fills. absent without gis, so a record simply has no geometry.
private const val GEOMETRIES = "geometries"

// what a tool hands back: the json the model sees, plus one line for the step log
data class AgentToolResult(
    val json: String,
    val summary: String,
    val error: Boolean
)

// The agent never touches the database. Every tool below calls a Wasichai service, so tenancy,
// object permissions, field visibility and record-level rules apply to the agent exactly as they
// apply to the human who asked. No DatabaseClient, no SQL, no repository lives in this file.
@Component
class AgentTools(
    private val metadata: MetadataService,
    private val mapper: MetadataMapper,
    private val records: RecordService,
    private val relationships: RelationshipService,
    private val related: RelatedRecordService,
    private val transitions: RecordTransitions,
    private val queries: RecordQueryParser,
    private val audit: AuditQueryService,
    private val currentUser: CurrentUser,
    private val json: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun invoke(
        name: String,
        input: Map<String, Any?>
    ): AgentToolResult =
        try {
            val (payload, summary) = dispatch(name, input)
            AgentToolResult(json.writeValueAsString(payload), summary, false)
        } catch (ex: WasichaiException) {
            // a refusal is an answer. tell the model it was denied instead of killing the turn.
            log.debug("Agent tool {} refused: {}", name, ex.message)
            AgentToolResult(json.writeValueAsString(mapOf("error" to ex.message)), "refused: ${ex.message}", true)
        }

    private suspend fun dispatch(
        name: String,
        input: Map<String, Any?>
    ): Pair<Any, String> =
        when (name) {
            AgentToolCatalog.LIST_OBJECTS -> listObjects()
            AgentToolCatalog.DESCRIBE_OBJECT -> describeObject(input)
            AgentToolCatalog.QUERY_RECORDS -> queryRecords(input)
            AgentToolCatalog.GET_RECORD -> getRecord(input)
            AgentToolCatalog.COUNT_RECORDS -> countRecords(input)
            AgentToolCatalog.LIST_RELATIONSHIPS -> listRelationships(input)
            AgentToolCatalog.RELATED_RECORDS -> relatedRecords(input)
            AgentToolCatalog.RECORD_HISTORY -> recordHistory(input)
            AgentToolCatalog.AVAILABLE_TRANSITIONS -> availableTransitions(input)
            else -> throw ValidationException("Unknown tool '$name'", "tool", "is not one of ${AgentToolCatalog.names.joinToString(", ")}")
        }

    private suspend fun listObjects(): Pair<Any, String> {
        // already filtered to what the caller may read
        val objects = metadata.listDefinitions().map { mapper.toObjectResponse(it) }
        return mapOf("objects" to objects) to "${objects.size} objects readable by you"
    }

    private suspend fun describeObject(input: Map<String, Any?>): Pair<Any, String> {
        val name = AgentToolInput.string(input, "object")
        // definitionOf is the caller-filtered one: a field you may not read is not in here
        val definition = metadata.definitionOf(name)
        val response = mapper.toResponse(definition, currentUser.require().organizationId)
        return response to "$name: ${response.fields.size} readable fields"
    }

    private suspend fun queryRecords(input: Map<String, Any?>): Pair<Any, String> {
        val name = AgentToolInput.string(input, "object")
        val limit = AgentToolInput.limit(input)
        // bbox goes through the installed query contributors (gis), like on the record api. without
        // gis it stays a field filter and gets that api's 400.
        val spatial = queries.parse(AgentToolInput.bbox(input)?.let { mapOf("bbox" to it) } ?: emptyMap())
        val page =
            records.list(
                name,
                RecordQuery(
                    page = PageRequest.of(0, limit),
                    sort = AgentToolInput.optionalString(input, "sort"),
                    descending = AgentToolInput.descending(input),
                    search = AgentToolInput.optionalString(input, "search"),
                    filters = AgentToolInput.filters(input) + spatial.filters,
                    criteria = spatial.criteria
                )
            )
        val payload =
            mapOf(
                "object" to name,
                "total" to page.totalElements,
                "returned" to page.content.size,
                "limit" to limit,
                "records" to page.content.map { it.brief() }
            )
        return payload to "$name: ${page.content.size} of ${page.totalElements} records"
    }

    private suspend fun getRecord(input: Map<String, Any?>): Pair<Any, String> {
        val name = AgentToolInput.string(input, "object")
        val id = AgentToolInput.uuid(input, "id")
        val record = records.get(name, id)
        return record to "$name record $id"
    }

    private suspend fun countRecords(input: Map<String, Any?>): Pair<Any, String> {
        val name = AgentToolInput.string(input, "object")
        // size 1: the store still reports the true total, and we throw the row away
        val page =
            records.list(
                name,
                RecordQuery(
                    page = PageRequest.of(0, 1),
                    search = AgentToolInput.optionalString(input, "search"),
                    filters = AgentToolInput.filters(input)
                )
            )
        return mapOf("object" to name, "count" to page.totalElements) to "$name: ${page.totalElements} records match"
    }

    private suspend fun listRelationships(input: Map<String, Any?>): Pair<Any, String> {
        val name = AgentToolInput.string(input, "object")
        val sides =
            relationships.forObject(name).map { side ->
                buildMap {
                    put("relationship", side.relationship.name)
                    put("label", side.label)
                    put("type", side.relationship.type.name)
                    put("relatedObject", side.otherObject.name)
                    put("relatedLabel", side.otherObject.label)
                    put("many", side.many)
                    // a self-relationship shows up once per direction; the model passes it back to related_records
                    side.direction?.let { put("direction", it.wire) }
                }
            }
        return mapOf("object" to name, "relationships" to sides) to "$name: ${sides.size} relationships"
    }

    private suspend fun relatedRecords(input: Map<String, Any?>): Pair<Any, String> {
        val name = AgentToolInput.string(input, "object")
        val id = AgentToolInput.uuid(input, "id")
        val relationship = AgentToolInput.string(input, "relationship")
        val limit = AgentToolInput.limit(input)
        val direction = RelationshipDirection.parse(AgentToolInput.optionalString(input, "direction"))
        val (definition, page) =
            related.relatedRecords(name, id, relationship, RecordQuery(page = PageRequest.of(0, limit)), direction)
        val payload =
            mapOf(
                "relationship" to relationship,
                "relatedObject" to definition.obj.name,
                "total" to page.totalElements,
                "returned" to page.content.size,
                "records" to page.content.map { it.toResponse().brief() }
            )
        return payload to "$relationship: ${page.content.size} of ${page.totalElements} related records"
    }

    private suspend fun recordHistory(input: Map<String, Any?>): Pair<Any, String> {
        val name = AgentToolInput.string(input, "object")
        val id = AgentToolInput.uuid(input, "id")
        val entries = audit.history(name, id, AgentToolInput.limit(input))
        return mapOf("object" to name, "record" to id.toString(), "history" to entries) to
            "$name record $id: ${entries.size} history entries"
    }

    private suspend fun availableTransitions(input: Map<String, Any?>): Pair<Any, String> {
        val name = AgentToolInput.string(input, "object")
        val id = AgentToolInput.uuid(input, "id")
        val transitions = transitions.transitionsOf(name, id)
        return mapOf("object" to name, "record" to id.toString(), "transitions" to transitions) to
            "$name record $id: ${transitions.count { it.allowed }} of ${transitions.size} transitions allowed"
    }

    // geometry can be megabytes of coordinates. a list answer only needs to know it is there.
    private fun RecordResponse.brief(): Map<String, Any?> =
        mapOf(
            "id" to id,
            "state" to state,
            "attributes" to attributes,
            "hasGeometry" to sections[GEOMETRIES].orEmpty().values.any { it != null }
        )
}
