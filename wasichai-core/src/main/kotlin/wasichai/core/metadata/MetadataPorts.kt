package wasichai.core.metadata

import wasichai.core.identity.AdminOperation
import java.util.UUID

// told before an object's table is dropped, inside the same transaction. a module that published
// something about the object (a map layer) cleans it up here. every listener is called, in @Order.
interface ObjectRemovalListener {
    suspend fun objectRemoved(obj: CustomObject)
}

enum class MetadataChangeKind { OBJECT, FIELD, RELATIONSHIP }

// one change to an organization's model. objectId and objectName: the object changed, or the one the field sits on;
// for a relationship, its source. name: the field's or the relationship's, null for an object.
data class MetadataChange(
    val organizationId: UUID,
    val kind: MetadataChangeKind,
    val operation: AdminOperation,
    val objectId: UUID?,
    val objectName: String?,
    val name: String? = null
)

// told after a change to objects, fields or relationships has committed (ADR-066); never for a rolled back one.
// for an app that keeps something derived from the model (its own cache) and would rather drop it now than wait out
// a ttl. the change already happened: a listener that throws is logged and the next one still runs. invalidate
// here, do not write to the database: the transaction is over. every listener is called, in @Order.
interface MetadataChangeListener {
    suspend fun metadataChanged(change: MetadataChange)
}

// asked before a field is dropped. a rule that reads a deleted field fails only when it next fires,
// far from the admin who deleted it, so ask first. answers from every bean are joined.
interface FieldUsage {
    suspend fun whoUses(
        obj: CustomObject,
        fieldName: String
    ): List<String>
}
