package wasichai.notifications

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// one notification_rules row. objectName comes from the join, so a list across objects needs no second read.
data class StoredRule(
    val id: UUID,
    val organizationId: UUID,
    val objectId: UUID,
    val objectName: String,
    val rule: NotificationRuleDefinition
)

// jsonb travels as text: CAST on the way in, ::text on the way out.
// definition holds the whole rule; name, label and enabled are columns too, and the columns win on read.
class NotificationRuleRepository(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    private val schemas: WasichaiSchemas
) {
    private val table get() = "${schemas.metadata}.notification_rules"

    private val select
        get() =
            """
            SELECT r.id, r.organization_id, r.object_id, o.name AS object_name, r.name, r.label, r.enabled, r.definition::text AS definition
            FROM $table r JOIN ${schemas.metadata}.custom_objects o ON o.id = r.object_id
            """.trimIndent()

    suspend fun insert(
        organizationId: UUID,
        objectId: UUID,
        rule: NotificationRuleDefinition
    ): UUID =
        db
            .sql(
                """
                INSERT INTO $table (organization_id, object_id, name, label, enabled, definition)
                VALUES (:org, :objectId, :name, :label, :enabled, CAST(:definition AS jsonb))
                RETURNING id
                """.trimIndent()
            ).bind("org", organizationId)
            .bind("objectId", objectId)
            .bindRule(rule)
            .map { row, _ -> Rows.uuid(row, "id") }
            .one()
            .awaitSingle()

    // the name is the rule's identity over REST: it never changes here
    suspend fun update(
        organizationId: UUID,
        id: UUID,
        rule: NotificationRuleDefinition
    ): Boolean =
        db
            .sql(
                """
                UPDATE $table SET name = :name, label = :label, enabled = :enabled, definition = CAST(:definition AS jsonb), updated_at = now()
                WHERE organization_id = :org AND id = :id
                """.trimIndent()
            ).bind("org", organizationId)
            .bind("id", id)
            .bindRule(rule)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    suspend fun delete(
        organizationId: UUID,
        id: UUID
    ): Boolean =
        db
            .sql("DELETE FROM $table WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    suspend fun findByName(
        organizationId: UUID,
        name: String
    ): StoredRule? =
        db
            .sql("$select WHERE r.organization_id = :org AND r.name = :name")
            .bind("org", organizationId)
            .bind("name", name)
            .map(::map)
            .one()
            .awaitFirstOrNull()

    suspend fun listAll(organizationId: UUID): List<StoredRule> = list("r.organization_id = :org", organizationId, null)

    suspend fun listByObject(
        organizationId: UUID,
        objectId: UUID
    ): List<StoredRule> = list("r.organization_id = :org AND r.object_id = :objectId", organizationId, objectId)

    // the loop's
    suspend fun enabledAll(organizationId: UUID): List<StoredRule> = list("r.organization_id = :org AND r.enabled", organizationId, null)

    // the hot path: every record write asks. notification_rules_object_idx answers it.
    suspend fun enabledByObject(
        organizationId: UUID,
        objectId: UUID
    ): List<StoredRule> = list("r.organization_id = :org AND r.object_id = :objectId AND r.enabled", organizationId, objectId)

    private suspend fun list(
        where: String,
        organizationId: UUID,
        objectId: UUID?
    ): List<StoredRule> =
        db
            .sql("$select WHERE $where ORDER BY o.name, r.name")
            .bind("org", organizationId)
            .let { if (objectId == null) it else it.bind("objectId", objectId) }
            .map(::map)
            .all()
            .collectList()
            .awaitSingle()

    private fun DatabaseClient.GenericExecuteSpec.bindRule(rule: NotificationRuleDefinition) =
        bind("name", rule.name)
            .bind("label", rule.label)
            .bind("enabled", rule.enabled)
            .bind("definition", json.writeValueAsString(rule))

    private fun map(
        row: Row,
        metadata: RowMetadata
    ): StoredRule =
        StoredRule(
            id = Rows.uuid(row, "id"),
            organizationId = Rows.uuid(row, "organization_id"),
            objectId = Rows.uuid(row, "object_id"),
            objectName = Rows.string(row, "object_name"),
            rule =
                json
                    .readValue(Rows.string(row, "definition"), NotificationRuleDefinition::class.java)
                    .copy(name = Rows.string(row, "name"), label = Rows.string(row, "label"), enabled = Rows.bool(row, "enabled"))
        )
}
