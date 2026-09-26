package wasichai.it.full

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

// the wire minus what differs per run: ids, timestamps, the object's unique name. jackson tree
// equality ignores object key order and keeps array order and number types.
object WireJson {
    private val mapper = JsonMapper.builder().build()
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun normalize(
        raw: String,
        name: String
    ): JsonNode {
        val tree = mapper.readTree(uuid.replace(raw, "<uuid>").replace(name, "<name>"))
        blankTimestamps(tree)
        return tree
    }

    fun parse(expected: String): JsonNode = mapper.readTree(expected)

    private fun blankTimestamps(node: JsonNode) {
        if (node is ObjectNode) {
            listOf("createdAt", "updatedAt").forEach { key ->
                if (node.has(key) && !node.get(key).isNull) node.put(key, "<ts>")
            }
        }
        node.forEach { blankTimestamps(it) }
    }
}
