package wasichai.core.metadata

// told before an object's table is dropped, inside the same transaction. a module that published
// something about the object (a map layer) cleans it up here. every listener is called, in @Order.
interface ObjectRemovalListener {
    suspend fun objectRemoved(obj: CustomObject)
}

// asked before a field is dropped. a rule that reads a deleted field fails only when it next fires,
// far from the admin who deleted it, so ask first. answers from every bean are joined.
interface FieldUsage {
    suspend fun whoUses(
        obj: CustomObject,
        fieldName: String
    ): List<String>
}
